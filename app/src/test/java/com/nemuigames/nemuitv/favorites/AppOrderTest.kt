package com.nemuigames.nemuitv.favorites

import org.junit.Assert.assertEquals
import org.junit.Test

class AppOrderTest {
    private fun layout(vararg order: String, hidden: Set<String> = emptySet()) = AppLayout(order.toList(), hidden)
    private fun letters(n: Int) = ('a'..'z').take(n).map { it.toString() }
    private val H = AppOrder.HOME_ID
    private fun vis(l: AppLayout, all: List<String> = l.order) = AppOrder.visible(l, all.toSet())

    @Test fun `upgrade keepsFavoritesFirst thenRestAlphabetical`() {
        val l = AppOrder.upgrade(listOf("n", "y"), listOf("a", "n", "s", "y"))
        assertEquals(listOf(H, "n", "y", "a", "s"), l.order)
        assertEquals(AppOrder.HIDDEN_BY_DEFAULT, l.hidden)
    }

    @Test fun `upgrade savedFavoriteNotInstalled keptInOrder`() {
        val l = AppOrder.upgrade(listOf("gone", "y"), listOf("a", "y"))
        assertEquals(listOf(H, "gone", "y", "a"), l.order)
        assertEquals(listOf(H, "y", "a"), AppOrder.favorites(AppOrder.visible(l, setOf("a", "y"))))
    }

    @Test fun defaults_preferredFirst() {
        val l = AppOrder.defaults(listOf("a", "com.netflix.ninja", "z", "com.google.android.youtube.tv"), haConfigured = false)
        assertEquals(listOf("com.netflix.ninja", "com.google.android.youtube.tv", "a", "z"), l.order.take(4))
    }

    @Test fun settingsHiddenByDefault_andNotVisible() {
        val installed = listOf("a", "com.android.tv.settings")
        assertEquals(listOf("a"), AppOrder.visible(AppOrder.defaults(installed, haConfigured = false), installed.toSet()) - H)
    }

    @Test fun reconcile_appendsNewAtEnd_neverRemoves() {
        val l = layout(H, "b", "a")
        assertEquals(listOf(H, "b", "a", "c"), AppOrder.reconcile(l, listOf("a", "b", "c")).order)
        assertEquals(listOf(H, "b", "a"), AppOrder.reconcile(l, listOf("a")).order)
    }

    @Test fun move_right_left_clamped() {
        val l = layout("a", "b", "c")
        val all = l.order.toSet()
        assertEquals(listOf("b", "a", "c"), AppOrder.move(l, all, "a", 1).order)
        assertEquals(listOf("a", "b", "c"), AppOrder.move(l, all, "c", 1).order)
        assertEquals(listOf("a", "b", "c"), AppOrder.move(l, all, "a", -1).order)
    }

    @Test fun `move down oneRow crossesFavoriteBoundary`() {
        val l = layout(*letters(8).toTypedArray())
        val m = AppOrder.move(l, l.order.toSet(), "a", 6)
        val v = vis(m)
        assertEquals(listOf("b", "c", "d", "e", "f", "g", "a", "h"), v)
        assertEquals(listOf("b", "c", "d", "e", "f"), AppOrder.favorites(v))
    }

    @Test fun move_down_pastEnd_clampsToLast() {
        val l = layout(*letters(8).toTypedArray())
        assertEquals("e", vis(AppOrder.move(l, l.order.toSet(), "e", 6)).last())
    }

    @Test fun move_skipsHiddenAndUninstalled() {
        val l = layout("a", "x", "b", "c", hidden = setOf("x"))
        val m = AppOrder.move(l, l.order.toSet(), "a", 1)
        assertEquals(listOf("b", "a", "c"), AppOrder.visible(m, l.order.toSet()))
    }

    @Test fun moveInDock_staysInRowOne() {
        val l = layout(*letters(7).toTypedArray())
        val all = l.order.toSet()
        assertEquals(l.order, AppOrder.moveInDock(l, all, "e", 1).order)
        assertEquals(listOf("b", "a", "c", "d", "e", "f", "g"), AppOrder.moveInDock(l, all, "a", 1).order)
    }

    @Test fun hideFavorite_nextMovesUp() {
        val l = layout(*letters(6).toTypedArray())
        val h = AppOrder.hide(l, "b")
        assertEquals(listOf("a", "c", "d", "e", "f"), AppOrder.favorites(AppOrder.visible(h, l.order.toSet())))
    }

    @Test fun unhide_goesToEnd() {
        val u = AppOrder.unhide(layout("a", "b", "c", hidden = setOf("a")), "a")
        assertEquals(listOf("b", "c", "a"), u.order)
        assertEquals(emptySet<String>(), u.hidden)
    }

    // ▲ / ▼ (±TRAY_COLUMNS): visible item i sits at grid index i, so row(i) = i / 6.
    @Test fun move_up_fromRowOne_isNoOp() {
        val l = layout(*letters(12).toTypedArray())
        val all = l.order.toSet()
        for (p in letters(6)) assertEquals(l.order, AppOrder.move(l, all, p, -AppOrder.TRAY_COLUMNS).order)
        // g is the first item of row 2 (grid index 6): ▲ moves it into row 1.
        assertEquals(listOf("g", "a", "b", "c", "d", "e"), vis(AppOrder.move(l, all, "g", -6)).take(6))
    }

    @Test fun move_down_fromLastRow_isNoOp() {
        val l = layout(*letters(8).toTypedArray()) // row 2 = g h
        val all = l.order.toSet()
        for (p in listOf("g", "h")) assertEquals(l.order, AppOrder.move(l, all, p, AppOrder.TRAY_COLUMNS).order)
        val few = layout("a", "b", "c") // a single row
        assertEquals(few.order, AppOrder.move(few, few.order.toSet(), "a", 6).order)
    }

    @Test fun move_down_intoShortLastRow_clampsToLast() {
        val l = layout(*letters(8).toTypedArray())
        assertEquals(listOf("a", "b", "c", "e", "f", "g", "h", "d"), vis(AppOrder.move(l, l.order.toSet(), "d", 6)))
    }

    @Test fun move_backward_overHiddenApp() {
        val l = layout("a", "x", "b", "c", hidden = setOf("x"))
        val m = AppOrder.move(l, l.order.toSet(), "b", -1)
        assertEquals(listOf("b", "a", "c"), AppOrder.visible(m, l.order.toSet()))
        assertEquals(listOf("b", "a", "x", "c"), m.order) // the hidden entry stays where it was
    }

    @Test fun `move withUninstalledAppBetweenVisibleOnes`() {
        val l = layout("a", "gone", "b", "c")
        val installed = setOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), AppOrder.visible(AppOrder.move(l, installed, "a", 1), installed))
        assertEquals(listOf("b", "a", "c"), AppOrder.visible(AppOrder.move(l, installed, "b", -1), installed))
        assertEquals(listOf("a", "c", "b"), AppOrder.visible(AppOrder.move(l, installed, "b", 1), installed))
    }

    @Test fun moveInDock_fewerThanFiveApps() {
        val l = layout("a", "b", "c")
        val all = l.order.toSet()
        assertEquals(l.order, AppOrder.moveInDock(l, all, "c", 1).order)
        assertEquals(listOf("a", "c", "b"), AppOrder.moveInDock(l, all, "b", 1).order)
        assertEquals(l.order, AppOrder.moveInDock(l, all, "a", -1).order)
    }

    @Test fun `neighbour sameIndex thenPrevious thenHome`() {
        val before = listOf("a", "b", "c")
        assertEquals("c", AppOrder.neighbour("b", before, listOf("a", "c")))      // same index
        assertEquals("b", AppOrder.neighbour("c", before, listOf("a", "b")))      // was last: previous
        assertEquals(null, AppOrder.neighbour("a", listOf("a"), emptyList()))    // nothing left: ⌂
        assertEquals(null, AppOrder.neighbour("z", before, listOf("a", "b")))     // unknown: ⌂
        assertEquals(null, AppOrder.neighbour("a", emptyList(), emptyList()))
    }

    private val seven = listOf("a", "b", "c", "d", "e", "f", "g")

    @Test fun `defaults withHa putsHomeFirst`() {
        assertEquals(H, AppOrder.defaults(seven, haConfigured = true).order.first())
    }

    @Test fun `defaults withoutHa putsHomeAfterFavorites`() {
        assertEquals(5, AppOrder.defaults(seven, haConfigured = false).order.indexOf(H))
    }

    @Test fun `defaults withoutHa fewApps putsHomeLast`() {
        assertEquals(H, AppOrder.defaults(listOf("a", "b"), haConfigured = false).order.last())
    }

    @Test fun `defaults withoutHa hiddenSettingsDontEatFavoriteSlots`() {
        val l = AppOrder.defaults(listOf("a", "com.android.tv.settings", "b", "c", "d", "e", "f"), haConfigured = false)
        val v = AppOrder.visible(l, l.order.toSet())
        assertEquals(5, v.indexOf(H))
    }

    @Test fun `reconcile insertsHomeFirstWhenMissing`() {
        assertEquals(listOf(H, "a", "b"), AppOrder.reconcile(AppLayout(listOf("a", "b"), emptySet()), listOf("a", "b")).order)
    }

    @Test fun `reconcile keepsMovedHome`() {
        val l = AppLayout(listOf("a", H, "b"), emptySet())
        assertEquals(l, AppOrder.reconcile(l, listOf("a", "b")))
    }

    @Test fun `hide home isNoOp`() {
        val l = layout("a", H)
        assertEquals(l, AppOrder.hide(l, H))
    }

    @Test fun `visible treatsHomeAsAlwaysInstalled`() {
        assertEquals(listOf("a", H), AppOrder.visible(AppLayout(listOf("a", H), emptySet()), setOf("a")))
    }

    @Test fun `move home swapsWithNextApp`() {
        val l = layout(H, "a", "b")
        assertEquals(listOf("a", H, "b"), AppOrder.move(l, setOf("a", "b"), H, 1).order)
        assertEquals(listOf(H, "a", "b"), AppOrder.moveInDock(layout("a", H, "b"), setOf("a", "b"), H, -1).order)
    }

    @Test fun `tray row math index6IsRowOne`() {
        // 13 items, the item at index 6 is in row 1: a whole-row ▲ moves it, but index 5 (row 0) stays.
        val l = layout(*letters(13).toTypedArray())
        val all = l.order.toSet()
        assertEquals(l.order, AppOrder.move(l, all, "f", -6).order)
        assertEquals("g", vis(AppOrder.move(l, all, "g", -6)).first())
    }

    @Test fun `favorites homeDoesNotUseASlot`() {
        assertEquals(listOf(H, "a", "b", "c", "d", "e"), AppOrder.favorites(listOf(H, "a", "b", "c", "d", "e", "f")))
        assertEquals(listOf("a", "b", "c", "d", "e"), AppOrder.favorites(listOf("a", "b", "c", "d", "e", H, "f")))
        assertEquals(listOf("a", H), AppOrder.favorites(listOf("a", H)))
    }

    @Test fun `moveInDock withHome appsUseSixthTile`() {
        val l = layout(H, "a", "b", "c", "d", "e", "f")
        val all = l.order.toSet()
        assertEquals(l.order, AppOrder.moveInDock(l, all, "e", 1).order) // clamped inside the 6-tile dock
        assertEquals(listOf(H, "a", "b", "c", "e", "d", "f"), AppOrder.moveInDock(l, all, "d", 1).order)
    }

    @Test fun `moveInDock homeStaysInDock`() {
        val l = layout("a", "b", "c", "d", H, "e", "f")
        val all = l.order.toSet()
        assertEquals(l.order, AppOrder.moveInDock(l, all, H, 1).order) // clamped at slot 4
        assertEquals(listOf("a", "b", "c", H, "d", "e", "f"), AppOrder.moveInDock(l, all, H, -1).order)
    }

    @Test fun `defaultFocus firstAppElseHomeElseNull`() {
        assertEquals("a", AppOrder.defaultFocus(listOf(H, "a", "b")))
        assertEquals(H, AppOrder.defaultFocus(listOf(H)))
        assertEquals(null, AppOrder.defaultFocus(emptyList()))
    }

    // I6: setup opens the app before config.json is pushed, so the first layout is the no-HA one (⌂ in the tray).
    // The first time HA is configured, ⌂ moves to the front once, unless the user moved it in the meantime.
    @Test fun `defaults noHa marksHomePending haDoesNot`() {
        val apps = listOf("a", "b", "c", "d", "e", "f")
        assertEquals(true, AppOrder.defaults(apps, haConfigured = false).homePending)
        assertEquals(false, AppOrder.defaults(apps, haConfigured = true).homePending)
    }

    @Test fun `promoteHome movesHomeFirst once`() {
        val l = AppOrder.defaults(listOf("a", "b", "c", "d", "e", "f"), haConfigured = false)
        val p = AppOrder.promoteHome(l)
        assertEquals(listOf(H, "a", "b", "c", "d", "e", "f"), p.order)
        assertEquals(false, p.homePending)
        assertEquals(l.hidden, p.hidden)
        assertEquals(p, AppOrder.promoteHome(p))
    }

    @Test fun `promoteHome withoutFlag isNoOp`() {
        val l = layout("a", "b", H, "c")
        assertEquals(l, AppOrder.promoteHome(l))
    }

    @Test fun `moving home clearsFlag soPromoteLeavesIt`() {
        val l = AppOrder.defaults(listOf("a", "b", "c", "d", "e", "f"), haConfigured = false)
        val moved = AppOrder.move(l, l.order.toSet(), H, 1)
        assertEquals(false, moved.homePending)
        assertEquals(moved, AppOrder.promoteHome(moved))
        val docked = AppOrder.moveInDock(AppOrder.move(l, l.order.toSet(), H, -1), l.order.toSet(), H, -1)
        assertEquals(false, docked.homePending)
    }

    @Test fun `moving another app keepsFlag hideUnhideKeepFlag`() {
        val l = AppOrder.defaults(listOf("a", "b", "c", "d", "e", "f"), haConfigured = false)
        val all = l.order.toSet()
        assertEquals(true, AppOrder.move(l, all, "a", 1).homePending)
        assertEquals(true, AppOrder.hide(l, "b").homePending)
        assertEquals(true, AppOrder.unhide(AppOrder.hide(l, "b"), "b").homePending)
    }

    @Test fun `reconcile and upgrade neverSetFlag`() {
        assertEquals(false, AppOrder.reconcile(AppLayout(listOf("a", "b"), emptySet()), listOf("a", "b", "c")).homePending)
        assertEquals(false, AppOrder.upgrade(listOf("a"), listOf("a", "b")).homePending)
    }
}
