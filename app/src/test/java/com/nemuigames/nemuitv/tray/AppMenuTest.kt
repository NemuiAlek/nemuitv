package com.nemuigames.nemuitv.tray

import com.nemuigames.nemuitv.tray.MenuOption.FORCE_CLOSE
import com.nemuigames.nemuitv.tray.MenuOption.HIDE
import com.nemuigames.nemuitv.tray.MenuOption.MOVE
import com.nemuigames.nemuitv.tray.MenuOption.UNINSTALL
import org.junit.Assert.assertEquals
import org.junit.Test

class AppMenuTest {
    @Test fun `systemApp moveHideForceClose noUninstall`() {
        assertEquals(listOf(MOVE, HIDE, FORCE_CLOSE), menuOptions(isSystem = true))
    }

    @Test fun userApp_alsoCanUninstall() {
        assertEquals(listOf(MOVE, HIDE, UNINSTALL, FORCE_CLOSE), menuOptions(isSystem = false))
    }

    @Test fun homeTile_canOnlyBeMoved() {
        assertEquals(listOf(MOVE), menuOptions(isSystem = true, isHome = true))
    }

    @Test fun forceClose_labelSignalsANextStep() {
        assertEquals("Force close…", FORCE_CLOSE.label)
    }

    @Test fun uninstall_labelSignalsANextStep() {
        assertEquals("Uninstall…", UNINSTALL.label)
    }
}
