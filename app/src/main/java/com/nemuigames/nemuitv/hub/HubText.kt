package com.nemuigames.nemuitv.hub

/** One status-strip item: its text, whether it's warm yellow (lights on), and the car's color for a car chip. */
data class StripItem(val text: String, val warm: Boolean = false, val carColor: String? = null)

/** The strip's items, in order: lock · inside temp · lights on · people · one per car (decision 1, ledgered). */
fun stripItems(strip: Strip): List<StripItem> = buildList {
    val warn = if (strip.lockWarning) " ⚠" else ""
    when (strip.lockStatus) {
        LockStatus.LOCKED -> add(StripItem("🔒 Locked$warn"))
        LockStatus.UNLOCKED -> add(StripItem("🔓 ${strip.lock}$warn"))
        LockStatus.JAMMED -> add(StripItem("⚠ Jammed$warn"))
        LockStatus.UNAVAILABLE -> add(StripItem("⚠ Lock unavailable$warn"))
        LockStatus.NONE -> Unit
    }
    strip.insideTemp?.let { add(StripItem("🌡 $it")) }
    add(StripItem("💡 ${strip.lightsOn} on", warm = strip.lightsOn > 0))
    add(StripItem("🏠 ${strip.people}"))
    strip.cars.forEach { add(StripItem(carText(it), carColor = it.color)) }
}

/** `My Car 97% ⚡` (⚡ only while charging, 💤 while asleep or unreachable; `—` when the battery level is unknown). */
fun carText(car: CarChip): String =
    "${car.name} ${car.percent?.let { "$it%" } ?: "—"}" + if (car.asleep) " 💤" else if (car.charging) " ⚡" else ""

/** A device tile's state line: `On · 80%` (dimmable and on), `On`, `Off`, `Unavailable`. */
fun tileStateLine(t: TileUi): String = when {
    t.unavailable -> "Unavailable"
    t.cycle != null -> cycleLeftText(t.cycle.minutesLeft)
    t.on && t.dimmable && t.brightness != null -> "On · ${t.brightness}%"
    t.on -> "On"
    else -> "Off"
}

fun tileIcon(domain: String): String = when (domain) {
    "switch" -> "🔌"
    "fan" -> "🌀"
    else -> "💡"
}

/** Room header: `Living Room` + ` · 3 on` (omitted at 0). */
fun roomOnSuffix(onCount: Int): String = if (onCount > 0) " · $onCount on" else ""

/** On-count shown next to a room (rail + header): the same rule as the strip's `💡 N on`, so the rooms add up to it. */
fun roomOnCount(room: Room, states: Map<String, EntityState>): Int = lightsOnCount(listOf(room), states)

/** Where the brightness sheet starts: the light's current brightness, else 50 %. */
fun sheetStart(brightness: Int?): Int = brightness ?: 50

/**
 * Tells a short OK press from a held one on a hub tile. The real remote repeats key-downs while OK is held (only
 * repeatCount 0 starts a press); the emulator's `--longpress` sends one repeat flagged long-press. Either the repeat
 * flagged long-press or the hold timer ([fire]) makes it a long press; a release before that is a click. A release
 * with no press seen here (e.g. the OK that moved focus onto this tile) does nothing.
 */
class OkPress {
    enum class Action { NONE, CLICK, LONG }
    private var down = false
    private var fired = false
    val pressed: Boolean get() = down && !fired

    fun onDown(repeatCount: Int, longPressFlag: Boolean): Action {
        if (repeatCount == 0) { down = true; fired = false; return Action.NONE }
        return if (down && !fired && longPressFlag) { fired = true; Action.LONG } else Action.NONE
    }

    /** The hold timer ran out with OK still down. */
    fun fire(): Action = if (down && !fired) { fired = true; Action.LONG } else Action.NONE

    fun onUp(): Action {
        val a = if (down && !fired) Action.CLICK else Action.NONE
        down = false; fired = false
        return a
    }

    fun reset() { down = false; fired = false }
}
