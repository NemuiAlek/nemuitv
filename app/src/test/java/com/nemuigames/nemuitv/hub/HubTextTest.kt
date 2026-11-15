package com.nemuigames.nemuitv.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HubTextTest {
    private fun strip(
        status: LockStatus = LockStatus.LOCKED, lock: String = "Locked", warn: Boolean = false, temp: String? = "72°",
        on: Int = 6, cars: List<CarChip> = emptyList(),
    ) = Strip(lock, status, warn, temp, on, "Alex, Sam", cars)

    @Test fun strip_orderAndText() {
        val cars = listOf(
            CarChip("my_car", "My Car", 97, true, "darkgray"),
            CarChip("second_car", "Second Car", 86, false, "white"),
        )
        assertEquals(
            listOf(
                StripItem("🔒 Locked"), StripItem("🌡 72°"), StripItem("💡 6 on", warm = true), StripItem("🏠 Alex, Sam"),
                StripItem("My Car 97% ⚡", carColor = "darkgray"), StripItem("Second Car 86%", carColor = "white"),
            ),
            stripItems(strip(cars = cars)),
        )
    }

    @Test fun strip_lockVariants() {
        assertEquals("🔓 Front door", stripItems(strip(LockStatus.UNLOCKED, "Front door")).first().text)
        assertEquals("⚠ Jammed", stripItems(strip(LockStatus.JAMMED, "Jammed")).first().text)
        assertEquals("⚠ Lock unavailable", stripItems(strip(LockStatus.UNAVAILABLE, "Lock unavailable")).first().text)
        assertEquals("🔒 Locked ⚠", stripItems(strip(warn = true)).first().text)
        assertEquals("🌡 72°", stripItems(strip(LockStatus.NONE, "")).first().text)
    }

    @Test fun strip_noTemp_zeroLightsNotWarm() {
        val items = stripItems(strip(temp = null, on = 0))
        assertEquals(listOf("🔒 Locked", "💡 0 on", "🏠 Alex, Sam"), items.map { it.text })
        assertEquals(false, items[1].warm)
    }

    @Test fun `asleep car chip shows 💤 and no ⚡ even when charging was reported`() {
        val text = carText(CarChip("my_car", "My Car", 97, charging = true, color = "darkgray", asleep = true))
        assertEquals("My Car 97% 💤", text)
        assertFalse("⚡" in text)
    }

    @Test fun car_unknownPercent() = assertEquals("Second Car —", carText(CarChip("second_car", "Second Car", null, false, "white")))

    private fun tile(on: Boolean = false, unavailable: Boolean = false, b: Int? = null, dim: Boolean = false) =
        TileUi(Tile("light.x", "X", "light", false), on, unavailable, b, dim, false)

    @Test fun stateLine() {
        assertEquals("On · 80%", tileStateLine(tile(on = true, b = 80, dim = true)))
        assertEquals("On", tileStateLine(tile(on = true)))
        assertEquals("On", tileStateLine(tile(on = true, dim = true)))
        assertEquals("Off", tileStateLine(tile()))
        assertEquals("Unavailable", tileStateLine(tile(unavailable = true)))
    }

    @Test fun icons() {
        assertEquals("💡", tileIcon("light")); assertEquals("🔌", tileIcon("switch")); assertEquals("🌀", tileIcon("fan"))
    }

    @Test fun roomSuffix() {
        assertEquals("", roomOnSuffix(0)); assertEquals(" · 3 on", roomOnSuffix(3))
    }

    @Test fun sheetStartsAtFiftyWhenUnknown() {
        assertEquals(50, sheetStart(null)); assertEquals(40, sheetStart(40))
    }

    @Test fun okPress_shortClick() {
        val p = OkPress()
        assertEquals(OkPress.Action.NONE, p.onDown(0, false))
        assertEquals(OkPress.Action.CLICK, p.onUp())
    }

    @Test fun okPress_realRemoteHold_timerFiresOnce() {
        val p = OkPress()
        p.onDown(0, false)
        assertEquals(OkPress.Action.NONE, p.onDown(1, false))
        assertEquals(OkPress.Action.LONG, p.fire())
        assertEquals(OkPress.Action.NONE, p.onDown(5, true))
        assertEquals(OkPress.Action.NONE, p.onUp())
    }

    @Test fun okPress_emulatorLongPressFlag() {
        val p = OkPress()
        p.onDown(0, false)
        assertEquals(OkPress.Action.LONG, p.onDown(1, true))
        assertEquals(OkPress.Action.NONE, p.fire())
        assertEquals(OkPress.Action.NONE, p.onUp())
    }

    @Test fun okPress_releaseWithoutPress_doesNothing() {
        val p = OkPress()
        assertEquals(OkPress.Action.NONE, p.onDown(3, true)) // held OK that arrived from elsewhere
        assertEquals(OkPress.Action.NONE, p.onUp())
        assertEquals(OkPress.Action.NONE, p.fire())
    }

    @Test fun roomOnCount_matchesStripRule() {
        val room = Room("lr", "Living Room", listOf(
            Tile("light.a", "A", "light", false), Tile("light.b", "B", "light", true), Tile("fan.c", "C", "fan", false),
        ))
        val on = EntityState("on", kotlinx.serialization.json.JsonObject(emptyMap()))
        assertEquals(1, roomOnCount(room, mapOf("light.a" to on, "light.b" to on, "fan.c" to on)))
    }

    @Test fun `okPress longPressOnTileWithoutHold isAClick`() {
        // The tile passes longPressFlag = false and never fires the timer when it can't open the sheet.
        val p = OkPress()
        p.onDown(0, false)
        assertEquals(OkPress.Action.NONE, p.onDown(1, false))
        assertEquals(OkPress.Action.NONE, p.onDown(9, false))
        assertEquals(OkPress.Action.CLICK, p.onUp())
    }
}
