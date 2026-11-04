package com.nemuigames.nemuitv.setup

import com.nemuigames.nemuitv.ha.HaStatus
import com.nemuigames.nemuitv.ui.GuideLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupChecklistTest {
    private fun inputs(
        granted: Boolean = true, takeover: Boolean = false, nowPlaying: Boolean = false, saver: Boolean = false,
        ha: HaStatus = HaStatus.NOT_CONFIGURED, haProblem: String? = null, wallpapers: Int = 0,
        issues: List<String> = emptyList(),
    ) = SetupInputs(granted, takeover, nowPlaying, saver, ha, haProblem, wallpapers, issues)

    private fun row(i: SetupInputs, k: RowKind) = SetupChecklist.rows(i).first { it.kind == k }

    @Test fun notGrantedDisablesSwitches() {
        val i = inputs(granted = false)
        listOf(RowKind.TAKEOVER, RowKind.NOW_PLAYING, RowKind.SCREENSAVER).forEach {
            val r = row(i, it)
            assertFalse(it.name, r.enabled)
            assertEquals("Needs setup from a computer", r.detail)
        }
        val c = row(i, RowKind.COMPUTER)
        assertEquals("Run setup from the README", c.detail)
        assertEquals(GuideLinks.SETUP, c.guideUrl)
        assertEquals(false, c.switchOn)
    }

    @Test fun grantedShowsDone() {
        val c = row(inputs(), RowKind.COMPUTER)
        assertEquals("Done", c.detail)
        assertNull(c.guideUrl)
        assertEquals(true, c.switchOn)
    }

    @Test fun takeoverOnShowsSwitchOn() {
        val r = row(inputs(takeover = true), RowKind.TAKEOVER)
        assertEquals(true, r.switchOn)
        assertTrue(r.enabled)
        assertEquals("Home button opens NemuiTV", r.label)
        assertEquals(false, row(inputs(), RowKind.TAKEOVER).switchOn)
    }

    @Test fun haRow() {
        val nc = row(inputs(ha = HaStatus.NOT_CONFIGURED), RowKind.HA)
        assertEquals("Not set up, see the guide", nc.detail)
        assertEquals(GuideLinks.HA, nc.guideUrl)
        val ok = row(inputs(ha = HaStatus.CONNECTED), RowKind.HA)
        assertEquals("Connected", ok.detail)
        assertNull(ok.guideUrl)
        assertEquals("Token rejected", row(inputs(ha = HaStatus.AUTH_FAILED, haProblem = "Token rejected"), RowKind.HA).detail)
    }

    @Test fun wallpaperRow() {
        val none = row(inputs(wallpapers = 0), RowKind.WALLPAPERS)
        assertEquals("Using the built-in one, see the guide", none.detail)
        assertEquals(GuideLinks.WALLPAPERS, none.guideUrl)
        assertEquals("1 wallpaper", row(inputs(wallpapers = 1), RowKind.WALLPAPERS).detail)
        val three = row(inputs(wallpapers = 3), RowKind.WALLPAPERS)
        assertEquals("3 wallpapers", three.detail)
        assertNull(three.guideUrl)
    }

    @Test fun configRowOnlyWithIssues() {
        assertTrue(SetupChecklist.rows(inputs()).none { it.kind == RowKind.CONFIG })
        val r = row(inputs(issues = listOf("a is bad", "b is bad")), RowKind.CONFIG)
        assertEquals("a is bad\nb is bad", r.detail)
        assertEquals("Config", r.label)
        assertNotNull(r)
    }

    @Test fun statusOnlyRowsShowStateButCannotToggle() {
        val on = SetupChecklist.rows(inputs(takeover = true), statusOnly = setOf(RowKind.TAKEOVER)).first { it.kind == RowKind.TAKEOVER }
        assertEquals(true, on.switchOn); assertFalse(on.enabled)
        val off = SetupChecklist.rows(inputs(), statusOnly = setOf(RowKind.TAKEOVER)).first { it.kind == RowKind.TAKEOVER }
        assertEquals(false, off.switchOn); assertFalse(off.enabled)
        assertEquals("Off, run setup again", off.detail)
    }

    @Test fun order() {
        assertEquals(
            listOf(RowKind.COMPUTER, RowKind.TAKEOVER, RowKind.NOW_PLAYING, RowKind.SCREENSAVER, RowKind.HA, RowKind.WALLPAPERS),
            SetupChecklist.rows(inputs()).map { it.kind },
        )
    }
}
