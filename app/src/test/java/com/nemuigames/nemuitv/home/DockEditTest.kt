package com.nemuigames.nemuitv.home

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

class DockEditTest {
    private val idle = DockEditState()
    private val lifted = DockEditState.startEditing("yt")

    @Test fun notEditing_downOpensTray() {
        val r = dockKey(idle, DOWN, down = true, focusedPkg = "yt")
        assertTrue(r.openTray); assertTrue(r.consumed)
    }

    @Test fun notEditing_otherKeysPassThrough() {
        listOf(LEFT, RIGHT, UP, OK, BACK).forEach { assertFalse(dockKey(idle, it, down = true, focusedPkg = "yt").consumed) }
    }

    @Test fun startEditing_liftsThatApp() {
        assertEquals(DockEditState(editing = true, lifted = "yt"), lifted)
    }

    @Test fun lifted_leftRightMoveIt() {
        assertEquals(-1, dockKey(lifted, LEFT, true, "yt").move)
        assertEquals(+1, dockKey(lifted, RIGHT, true, "yt").move)
        assertTrue(dockKey(lifted, RIGHT, true, "yt").consumed)
    }

    @Test fun lifted_okDrops_andItsReleaseIsSwallowed() {
        val dropped = dockKey(lifted, OK, down = true, focusedPkg = "yt")
        assertNull(dropped.state.lifted); assertTrue(dropped.state.editing); assertTrue(dropped.consumed)
        val release = dockKey(dropped.state, OK, down = false, focusedPkg = "yt")
        assertTrue("OK release must not launch the app", release.consumed)
    }

    @Test fun `editing notLifted leftRightMoveFocusNormally`() {
        val dropped = dockKey(lifted, OK, true, "yt").state
        assertFalse(dockKey(dropped, RIGHT, true, "yt").consumed)
        assertNull(dockKey(dropped, RIGHT, true, "yt").move)
    }

    @Test fun editing_okOnAnotherApp_liftsIt() {
        val dropped = dockKey(lifted, OK, true, "yt").state
        val r = dockKey(dropped, OK, down = true, focusedPkg = "netflix")
        assertEquals("netflix", r.state.lifted); assertTrue(r.consumed)
    }

    @Test fun editing_okOnHomeTile_doesNothing() {
        val dropped = dockKey(lifted, OK, true, "yt").state
        val r = dockKey(dropped, OK, down = true, focusedPkg = null)
        assertNull(r.state.lifted); assertTrue(r.consumed)
    }

    @Test fun editing_upDownStayInDock() {
        assertTrue(dockKey(lifted, UP, true, "yt").consumed)
        val down = dockKey(lifted, DOWN, true, "yt")
        assertTrue(down.consumed); assertFalse(down.openTray)
    }

    @Test fun editing_backPassesThroughToExit() {
        assertFalse(dockKey(lifted, BACK, true, "yt").consumed)
        assertFalse(dockKey(lifted, BACK, false, "yt").consumed)
        assertEquals(idle, DockEditState.stopEditing())
    }

    @Test fun editing_anyOkReleaseIsConsumed() {
        val dropped = dockKey(lifted, OK, true, "yt").state.copy(swallowOkRelease = false)
        assertTrue(dockKey(dropped, OK, down = false, focusedPkg = "yt").consumed)
    }
}
