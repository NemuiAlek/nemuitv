package com.nemuigames.nemuitv.favorites

/**
 * The one saved app order. [order] holds every package we have ever seen (uninstalled ones are kept, not shown);
 * [hidden] are packages the user hid. Favorites are not stored: they are the first [AppOrder.DOCK_SIZE] visible items.
 * [homePending]: this layout came from the no-Home-Assistant defaults (⌂ in the tray) and the user hasn't moved ⌂
 * since, so ⌂ may still move to the front once Home Assistant is set up ([AppOrder.promoteHome]).
 */
data class AppLayout(val order: List<String>, val hidden: Set<String>, val homePending: Boolean = false)

/** Pure rules for the app order. */
object AppOrder {
    /** The favorites row is always this many tiles: 6 apps, or ⌂ + 5 apps when ⌂ is among the first 6. */
    const val DOCK_SIZE = 6

    /** The ⌂ Home hub tile. It is a normal entry in the saved order (movable, never hidden, always "installed"). */
    const val HOME_ID = "__home"

    /** Tray grid width. Visible item i sits at grid index i (row i / TRAY_COLUMNS). */
    const val TRAY_COLUMNS = 6

    /** Starting order, if installed: Netflix, YouTube, Disney+, Spotify, Max, Prime Video, Plex. */
    val PREFERRED = listOf(
        "com.netflix.ninja",
        "com.google.android.youtube.tv",
        "com.disney.disneyplus",
        "com.spotify.tv.android",
        "com.wbd.stream",
        "com.amazon.amazonvideo.livingroom",
        "com.plexapp.android",
    )

    /** System Settings apps: reachable from the Control Center, so hidden until the user unhides them. */
    val HIDDEN_BY_DEFAULT = setOf("com.android.tv.settings", "com.android.settings")

    /** Migrates old saved favorites: ⌂ first, then they stay (in order), then every other installed app by label. */
    fun upgrade(savedFavorites: List<String>, installedByLabel: List<String>): AppLayout {
        val favs = savedFavorites.distinct().filterNot { it == HOME_ID }
        return AppLayout(listOf(HOME_ID) + favs + installedByLabel.filterNot { it in favs || it == HOME_ID }, HIDDEN_BY_DEFAULT)
    }

    /**
     * First-run layout: PREFERRED apps that are installed, then the rest in the given (label) order. ⌂ goes first
     * when Home Assistant is configured; otherwise right after the 6th app (the tray's first spot), or last if
     * there are fewer apps than that, and the layout is marked [AppLayout.homePending].
     */
    fun defaults(installedByLabel: List<String>, haConfigured: Boolean): AppLayout {
        val preferred = PREFERRED.filter { it in installedByLabel }
        val apps = preferred + installedByLabel.filterNot { it in preferred }
        if (haConfigured) return AppLayout(listOf(HOME_ID) + apps, HIDDEN_BY_DEFAULT)
        // Count only apps that will be visible, so hidden-by-default entries don't eat dock slots.
        var shown = 0
        var at = apps.size
        for ((i, a) in apps.withIndex()) {
            if (a in HIDDEN_BY_DEFAULT) continue
            if (shown == DOCK_SIZE) { at = i; break }
            shown++
        }
        return AppLayout(apps.toMutableList().apply { add(at, HOME_ID) }, HIDDEN_BY_DEFAULT, homePending = true)
    }

    /**
     * Appends installed packages we have not seen to the end (in the given order). Never removes anything.
     * A saved order without ⌂ (from before it was movable) gets it at the front, where it used to be.
     * One-time dock migration (5 -> 6 tiles): a [AppLayout.homePending] layout with ⌂ right after the 5th visible app
     * gets ⌂ moved after the 6th, so the row shows 6 apps. The flag stays; a ⌂ the user placed is never touched.
     */
    fun reconcile(layout: AppLayout, installedByLabel: List<String>): AppLayout {
        val known = layout.order.toSet()
        val fresh = installedByLabel.filterNot { it in known }
        val order = if (HOME_ID in known) layout.order else listOf(HOME_ID) + layout.order
        val full = order + fresh
        val moved = if (layout.homePending) migrateHomeToSixth(full, installedByLabel.toSet(), layout.hidden) else full
        return if (moved === layout.order) layout else layout.copy(order = moved)
    }

    private fun migrateHomeToSixth(order: List<String>, installed: Set<String>, hidden: Set<String>): List<String> {
        val vis = order.filter { (it == HOME_ID || it in installed) && it !in hidden }
        if (vis.indexOf(HOME_ID) != DOCK_SIZE - 1) return order
        val sixth = vis.getOrNull(DOCK_SIZE) ?: return order
        val rest = order.filterNot { it == HOME_ID }
        return rest.toMutableList().apply { add(rest.indexOf(sixth) + 1, HOME_ID) }
    }

    /**
     * Home Assistant is set up now: a [AppLayout.homePending] layout gets ⌂ at the front, once (the flag clears).
     * Setup opens the app before config.json can be pushed, so without this ⌂ would stay in the tray for HA users.
     */
    fun promoteHome(layout: AppLayout): AppLayout =
        if (!layout.homePending) layout
        else layout.copy(order = listOf(HOME_ID) + layout.order.filterNot { it == HOME_ID }, homePending = false)

    /** What the tray shows: [AppLayout.order] minus hidden and uninstalled apps. ⌂ is always there. */
    fun visible(layout: AppLayout, installed: Set<String>): List<String> =
        layout.order.filter { (it == HOME_ID || it in installed) && it !in layout.hidden }

    /** The dock: the first [DOCK_SIZE] visible items, ⌂ included if it is among them (⌂ + 5 apps, else 6 apps). */
    fun favorites(visible: List<String>): List<String> = visible.take(DOCK_SIZE)

    /** Where dock focus goes with no preference: the first app, else ⌂ if it is all there is, else nothing. */
    fun defaultFocus(dock: List<String>): String? = dock.firstOrNull { it != HOME_ID } ?: dock.firstOrNull()

    /**
     * Moves [pkg] by [delta] visible slots (negative = earlier), clamped to the first/last visible app.
     * A whole-row move (|delta| >= [TRAY_COLUMNS], ▲ ▼) from the top row up, or from the last row down, does nothing;
     * ▼ into a shorter last row still clamps to the last app.
     */
    fun move(layout: AppLayout, installed: Set<String>, pkg: String, delta: Int): AppLayout {
        if (pkg == HOME_ID && layout.homePending) return move(layout.copy(homePending = false), installed, pkg, delta)
        if (delta <= -TRAY_COLUMNS || delta >= TRAY_COLUMNS) {
            val vis = visible(layout, installed)
            val from = vis.indexOf(pkg)
            if (from < 0) return layout
            fun row(i: Int) = i / TRAY_COLUMNS
            if (delta < 0 && row(from) == 0) return layout
            if (delta > 0 && row(from) == row(vis.lastIndex)) return layout
        }
        return moveClamped(layout, installed, pkg, delta, maxSlot = Int.MAX_VALUE)
    }

    /** Like [move] but the target slot stays within the dock (first row of favorites). */
    fun moveInDock(layout: AppLayout, installed: Set<String>, pkg: String, delta: Int): AppLayout {
        if (pkg == HOME_ID && layout.homePending) return moveInDock(layout.copy(homePending = false), installed, pkg, delta)
        return moveClamped(layout, installed, pkg, delta, maxSlot = dockMaxSlot(layout, installed))
    }

    /** ⌂ can't be hidden. */
    fun hide(layout: AppLayout, pkg: String): AppLayout =
        if (pkg == HOME_ID) layout else layout.copy(hidden = layout.hidden + pkg)

    /** Shows [pkg] again, at the end of the order. */
    fun unhide(layout: AppLayout, pkg: String): AppLayout =
        layout.copy(order = layout.order.filterNot { it == pkg } + pkg, hidden = layout.hidden - pkg)

    /**
     * Where focus goes when [pkg] leaves a list ([before] → [after], e.g. hidden or uninstalled): the app now at its
     * old index, else the one before it; null = the caller picks (nothing left, or [pkg] wasn't in [before]).
     */
    fun neighbour(pkg: String, before: List<String>, after: List<String>): String? {
        val i = before.indexOf(pkg)
        if (i < 0) return null
        return after.getOrNull(i) ?: after.getOrNull(i - 1)
    }

    /** Last dock slot any item, ⌂ included, may move to: the last tile of the dock. */
    private fun dockMaxSlot(layout: AppLayout, installed: Set<String>): Int =
        favorites(visible(layout, installed)).lastIndex

    private fun moveClamped(layout: AppLayout, installed: Set<String>, pkg: String, delta: Int, maxSlot: Int): AppLayout {
        val vis = visible(layout, installed)
        val from = vis.indexOf(pkg)
        if (from < 0) return layout
        val to = (from + delta).coerceIn(0, minOf(maxSlot, vis.lastIndex))
        if (to == from) return layout
        val anchor = vis[to]
        val rest = layout.order.filterNot { it == pkg }
        val at = rest.indexOf(anchor)
        // Forward: land just after the app now in that slot; backward: just before it. Hidden entries stay put.
        return layout.copy(order = rest.toMutableList().apply { add(if (to > from) at + 1 else at, pkg) })
    }
}
