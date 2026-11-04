package com.nemuigames.nemuitv.tray

import com.nemuigames.nemuitv.home.DockKey
import com.nemuigames.nemuitv.home.DockKey.BACK
import com.nemuigames.nemuitv.home.DockKey.DOWN
import com.nemuigames.nemuitv.home.DockKey.LEFT
import com.nemuigames.nemuitv.home.DockKey.OK
import com.nemuigames.nemuitv.home.DockKey.RIGHT
import com.nemuigames.nemuitv.home.DockKey.UP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrayEditTest {
    private val idle = TrayEditState()
    private val lifted = TrayEditState.startEditing("a")

    @Test fun notEditing_passesEverything() {
        DockKey.values().forEach {
            val r = trayKey(idle, it, down = true, repeatCount = 0, focusedPkg = "a")
            assertFalse(r.consumed); assertNull(r.move)
        }
    }

    @Test fun lifted_arrows_moveByOneOrOneRow() {
        assertEquals(-1, trayKey(lifted, LEFT, true, 0, "a").move)
        assertEquals(+1, trayKey(lifted, RIGHT, true, 0, "a").move)
        assertEquals(-TrayColumns, trayKey(lifted, UP, true, 0, "a").move)
        assertEquals(+TrayColumns, trayKey(lifted, DOWN, true, 0, "a").move)
        listOf(LEFT, RIGHT, UP, DOWN).forEach { assertTrue(trayKey(lifted, it, true, 0, "a").consumed) }
    }

    @Test fun lifted_heldArrowRepeats_keepMoving() {
        assertEquals(+1, trayKey(lifted, RIGHT, true, 3, "a").move)
    }

    @Test fun ok_dropsThenLiftsOther() {
        val dropped = trayKey(lifted, OK, true, 0, "a")
        assertNull(dropped.state.lifted); assertTrue(dropped.state.swallowOkRelease); assertTrue(dropped.consumed)
        val r = trayKey(dropped.state, OK, true, 0, "b")
        assertEquals("b", r.state.lifted)
    }

    @Test fun okRelease_isConsumed_andClearsSwallow() {
        val dropped = trayKey(lifted, OK, true, 0, "a").state
        val r = trayKey(dropped, OK, false, 0, "a")
        assertTrue(r.consumed); assertFalse(r.state.swallowOkRelease)
    }

    @Test fun heldOk_repeats_doNothing() {
        val r = trayKey(lifted, OK, true, 2, "a")
        assertEquals(lifted, r.state); assertTrue(r.consumed)
        val dropped = TrayEditState(editing = true)
        val r2 = trayKey(dropped, OK, true, 2, "b")
        assertEquals(dropped, r2.state); assertTrue(r2.consumed)
    }

    @Test fun `editing notLifted arrowsMoveFocusNormally`() {
        val dropped = TrayEditState(editing = true)
        listOf(LEFT, RIGHT, UP, DOWN).forEach {
            val r = trayKey(dropped, it, true, 0, "a")
            assertFalse(r.consumed); assertNull(r.move)
        }
    }

    @Test fun back_passesThrough() {
        assertFalse(trayKey(lifted, BACK, true, 0, "a").consumed)
        assertFalse(trayKey(lifted, BACK, false, 0, "a").consumed)
    }

    @Test fun okOnNonApp_ignored() {
        val dropped = TrayEditState(editing = true)
        val r = trayKey(dropped, OK, true, 0, null)
        assertNull(r.state.lifted); assertTrue(r.consumed)
    }
}
