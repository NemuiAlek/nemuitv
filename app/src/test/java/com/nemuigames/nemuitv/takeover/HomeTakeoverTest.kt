package com.nemuigames.nemuitv.takeover

import com.nemuigames.nemuitv.takeover.HomeKeyDecider.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeKeyDeciderTest {
    private val d = HomeKeyDecider()

    @Test fun tap_opensHome_onRelease() {
        assertEquals(HomeKeyDecider.Result(consume = true, action = Action.SCHEDULE_HOLD_CHECK), d.onDown(repeatCount = 0))
        assertEquals(HomeKeyDecider.Result(consume = true, action = Action.OPEN_HOME), d.onUp())
    }

    @Test fun `hold opensControlCenter whenTimerFiresWhileDown andReleaseDoesNothing`() {
        d.onDown(repeatCount = 0)
        assertEquals(Action.OPEN_CONTROL_CENTER, d.onHoldTimeout())
        assertEquals(HomeKeyDecider.Result(consume = true, action = Action.NONE), d.onUp())
    }

    @Test fun staleTimer_afterTap_doesNothing() {
        d.onDown(repeatCount = 0); d.onUp()
        assertEquals(Action.NONE, d.onHoldTimeout())
    }

    @Test fun holdOnlyFiresOnce() {
        d.onDown(repeatCount = 0)
        d.onHoldTimeout()
        assertEquals(Action.NONE, d.onHoldTimeout())
    }

    @Test fun `remotes that repeat, repeats are swallowed without restarting the timer`() {
        d.onDown(repeatCount = 0)
        assertEquals(HomeKeyDecider.Result(consume = true, action = Action.NONE), d.onDown(repeatCount = 1))
        assertEquals(Action.OPEN_CONTROL_CENTER, d.onHoldTimeout())
    }

    @Test fun `releaseWeNeverSawPressed isPassedThrough`() {
        // Service started mid-press: the system owns that press, so it must get the release too.
        assertFalse(d.onUp().consume)
    }

    @Test fun repeatWeNeverSawStart_isPassedThrough() {
        assertFalse(d.onDown(repeatCount = 3).consume)
    }

    @Test fun nextTapAfterHold_worksNormally() {
        d.onDown(repeatCount = 0); d.onHoldTimeout(); d.onUp()
        d.onDown(repeatCount = 0)
        assertEquals(Action.OPEN_HOME, d.onUp().action)
    }

    // Screensaver running (couch round 2, 11-02): opening NemuiTV alone leaves the dream up, so the press must end it and
    // go Home at once, on the down. No hold → Control Center while dreaming: a held Home just wakes + goes Home.
    @Test fun dreaming_downOpensHomeImmediately() {
        assertEquals(HomeKeyDecider.Result(consume = true, action = Action.OPEN_HOME), d.onDown(repeatCount = 0, dreaming = true))
    }

    @Test fun `dreaming holdNeverOpensControlCenter andRestOfPressIsSwallowed`() {
        d.onDown(repeatCount = 0, dreaming = true)
        assertEquals(Action.NONE, d.onHoldTimeout())
        assertEquals(HomeKeyDecider.Result(consume = true, action = Action.NONE), d.onDown(repeatCount = 1))
        assertEquals(HomeKeyDecider.Result(consume = true, action = Action.NONE), d.onUp())
    }

    @Test fun `dreaming nextTapAfterWaking worksNormally`() {
        d.onDown(repeatCount = 0, dreaming = true); d.onUp()
        assertEquals(Action.SCHEDULE_HOLD_CHECK, d.onDown(repeatCount = 0).action)
        assertEquals(Action.OPEN_HOME, d.onUp().action)
    }
}

class GoogleHomeWatchTest {
    private val home = "com.google.android.apps.tv.launcherx"
    private val homeCls = "com.google.android.apps.tv.launcherx.home.HomeActivity"

    @Test fun googlesHomeScreen_isReclaimed() {
        assertTrue(GoogleHomeWatch.shouldReclaim(home, homeCls, nowMs = 10_000, lastLaunchMs = 0))
    }

    @Test fun `googlesQuickSettingsPanelAndOverlays areLeftAlone`() {
        assertFalse(GoogleHomeWatch.shouldReclaim(home, "$home.dashboard.DashboardActivity", 10_000, 0))
        assertFalse(GoogleHomeWatch.shouldReclaim(home, "android.widget.FrameLayout", 10_000, 0))
    }

    @Test fun otherApps_areLeftAlone() {
        assertFalse(GoogleHomeWatch.shouldReclaim("com.netflix.ninja", "com.netflix.ninja.MainActivity", 10_000, 0))
        assertFalse(GoogleHomeWatch.shouldReclaim(null, null, 10_000, 0))
    }

    @Test fun `duplicateEventRightAfterALaunch isIgnored`() {
        assertFalse(GoogleHomeWatch.shouldReclaim(home, homeCls, nowMs = 10_500, lastLaunchMs = 10_000))
        assertTrue(GoogleHomeWatch.shouldReclaim(home, homeCls, nowMs = 11_100, lastLaunchMs = 10_000))
    }
}
