package com.nemuigames.nemuitv.update

import com.nemuigames.nemuitv.controlcenter.updateButton
import com.nemuigames.nemuitv.controlcenter.updateMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdatePageRulesTest {
    @Test fun buttonLabels() {
        assertEquals("Install" to true, updateButton(UpdateState.Idle))
        assertEquals("Install" to true, updateButton(UpdateState.Cancelled))
        assertEquals("Install" to true, updateButton(UpdateState.Mismatch))
        assertEquals("Install" to true, updateButton(UpdateState.DownloadFailed))
        assertEquals("Install" to true, updateButton(UpdateState.InstallFailed("x")))
        assertEquals("Allow installs first" to true, updateButton(UpdateState.NeedsPermission))
        assertEquals("Downloading… 42%" to false, updateButton(UpdateState.Downloading(42)))
        assertEquals("Installing…" to false, updateButton(UpdateState.Installing))
    }

    @Test fun messages() {
        assertNull(updateMessage(UpdateState.Idle))
        assertNull(updateMessage(UpdateState.Downloading(5)))
        assertNull(updateMessage(UpdateState.Installing))
        assertEquals("Turn on 'Install unknown apps' for NemuiTV, then come back.", updateMessage(UpdateState.NeedsPermission))
        assertEquals("Download didn't check out. Try again.", updateMessage(UpdateState.Mismatch))
        assertEquals("Download failed. Try again.", updateMessage(UpdateState.DownloadFailed))
        assertEquals("Cancelled. Press Install to try again.", updateMessage(UpdateState.Cancelled))
        assertEquals("Couldn't install: INSTALL_FAILED_UPDATE_INCOMPATIBLE",
            updateMessage(UpdateState.InstallFailed("INSTALL_FAILED_UPDATE_INCOMPATIBLE")))
    }
}
