package com.nemuigames.nemuitv.ambient

import android.view.KeyEvent
import com.nemuigames.nemuitv.ha.TvState
import com.nemuigames.nemuitv.settings.LauncherPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AmbientRulesTest {
    private val d = LauncherPrefs.DEFAULT

    @Test fun screenOffMs_defaults_is5min() = assertEquals(300_000L, screenOffTimeoutMs(d))
    @Test fun sleepMs_default_is4h() = assertEquals(14_400_000L, sleepTimeoutMs(d))
    @Test fun sleepMs_never_isMinusOne() = assertEquals(-1L, sleepTimeoutMs(d.copy(sleepAfterMin = -1)))

    @Test fun sleepWarning_2hWith30plus90_false() =
        assertFalse(sleepsBeforeBlack(d.copy(ambientAfterMin = 30, blackAfterMin = 90, sleepAfterMin = 120)))
    @Test fun sleepWarning_true_whenSleepComesFirst() =
        assertTrue(sleepsBeforeBlack(d.copy(ambientAfterMin = 30, blackAfterMin = 90, sleepAfterMin = 60)))
    @Test fun sleepWarning_neverIsFalse() = assertFalse(sleepsBeforeBlack(d.copy(sleepAfterMin = -1)))

    @Test fun labels() {
        assertEquals("5 min", minutesLabel(5))
        assertEquals("2 h", minutesLabel(120))
        assertEquals("8 h", minutesLabel(480))
        assertEquals("Never", minutesLabel(-1))
    }

    @Test fun next_cycles_andUnknownGoesFirst() {
        assertEquals(10, nextAmbientAfter(5))
        assertEquals(5, nextAmbientAfter(30))
        assertEquals(5, nextAmbientAfter(7))
        assertEquals(60, nextBlackAfter(30))
        assertEquals(30, nextBlackAfter(90))
        assertEquals(30, nextBlackAfter(1))
        assertEquals(480, nextSleepAfter(240))
        assertEquals(-1, nextSleepAfter(480))
        assertEquals(120, nextSleepAfter(-1))
        assertEquals(120, nextSleepAfter(99))
    }

    @Test fun shift_everyMoveIsOneStride() {
        for (step in 0..44) {
            val a = shiftOffsetDp(step); val b = shiftOffsetDp(step + 1)
            assertEquals("step $step", 8, abs(a.first - b.first) + abs(a.second - b.second))
        }
    }
    @Test fun shift_staysInBox() {
        for (step in -50..100) {
            val (x, y) = shiftOffsetDp(step)
            assertTrue(x in 0..24); assertTrue(y in 0..16)
        }
    }
    @Test fun shift_visitsAll12() = assertEquals(12, (0 until 22).map { shiftOffsetDp(it) }.toSet().size)

    @Test fun shiftStep_3minPerStep() {
        assertEquals(0, shiftStep(179_999))
        assertEquals(1, shiftStep(180_000))
    }
    @Test fun isBlack_atExactly30min() {
        assertFalse(isBlack(30 * 60_000L - 1, 30))
        assertTrue(isBlack(30 * 60_000L, 30))
    }

    @Test fun lofi_decisionTable() {
        val b = listOf(true, false)
        for (h in b) for (l in b) for (p in b) for (c in b) for (tv in b) for (acct in b) {
            assertEquals("$h $l $p $c $tv $acct", h && l && !p && c && tv && !acct, shouldStartLofi(h, l, p, c, tv, acct))
        }
        assertTrue(shouldStartLofi(true, true, false, true, true, false))
    }
    @Test fun lofi_notStarted_whenSomethingPlaying() = assertFalse(shouldStartLofi(true, true, true, true, true, false))
    @Test fun lofi_notStarted_whenTvOnAnotherInput() = assertFalse(shouldStartLofi(true, true, false, true, false, false))
    @Test fun `lofi notStarted whenAccountAlreadyPlaying`() = assertFalse(shouldStartLofi(true, true, false, true, true, true))

    @Test fun `tvShowsThisBox notConfigured doesNotBlock`() {
        assertTrue(tvShowsThisBox(tvEntity = null, thisDevice = "Google OTT", tv = null))
        assertTrue(tvShowsThisBox(tvEntity = "media_player.tv", thisDevice = null, tv = TvState(true, "PS5")))
    }
    @Test fun tvShowsThisBox_onThisBox() = assertTrue(tvShowsThisBox("media_player.tv", "Google OTT", TvState(true, "Google OTT")))
    @Test fun tvShowsThisBox_otherInput_false() = assertFalse(tvShowsThisBox("media_player.tv", "Google OTT", TvState(true, "PS5")))
    @Test fun tvShowsThisBox_unavailable_false() = assertFalse(tvShowsThisBox("media_player.tv", "Google OTT", TvState(false, "Google OTT")))
    @Test fun tvShowsThisBox_unknownState_false() {
        assertFalse(tvShowsThisBox("media_player.tv", "Google OTT", null))
        assertFalse(tvShowsThisBox("media_player.tv", "Google OTT", TvState(true, null)))
    }

    @Test fun accountPlaying_onlyWhenPlaying() {
        assertTrue(accountPlaying("playing"))
        for (s in listOf(null, "paused", "idle", "off", "unknown", "unavailable")) assertFalse("$s", accountPlaying(s))
    }

    @Test fun wasHome_resumed() = assertTrue(wasHomeBeforeDream(true, null, 10_000))
    @Test fun wasHome_paused2sAgo_true() = assertTrue(wasHomeBeforeDream(false, 8_000, 10_000))
    @Test fun wasHome_paused4sAgo_false() = assertFalse(wasHomeBeforeDream(false, 6_000, 10_000))
    @Test fun wasHome_neverHome_false() = assertFalse(wasHomeBeforeDream(false, null, 10_000))

    // Now Playing without the notification-listener grant can't see anything: treat as "maybe playing" (no lofi).
    @Test fun mediaMayBePlaying_notListening_true() = assertTrue(mediaMayBePlaying(listening = false, anyPlaying = false))
    @Test fun `mediaMayBePlaying listening followsSessions`() {
        assertFalse(mediaMayBePlaying(listening = true, anyPlaying = false))
        assertTrue(mediaMayBePlaying(listening = true, anyPlaying = true))
    }

    // The dream starts on what Home showed, not the video Home advanced to as it stopped.
    @Test fun `ambientStartWallpaper homeJustPaused usesSnapshot`() =
        assertEquals("a", ambientStartWallpaper(false, 8_000, 10_000, atHomePause = "a", current = "b"))
    @Test fun `ambientStartWallpaper homeStillResumed usesCurrent`() =
        assertEquals("b", ambientStartWallpaper(true, 1_000, 10_000, atHomePause = "a", current = "b"))
    @Test fun `ambientStartWallpaper overApps usesCurrent`() =
        assertEquals("b", ambientStartWallpaper(false, 1_000, 10_000, atHomePause = "a", current = "b"))
    @Test fun `ambientStartWallpaper noSnapshot usesCurrent`() =
        assertEquals("b", ambientStartWallpaper(false, 8_000, 10_000, atHomePause = null, current = "b"))

    // Now Playing banner in ambient: only while music actually plays, never in black.
    @Test fun `ambientBanner onlyWhilePlaying andNotBlack`() {
        assertTrue(ambientBannerShown(playing = true, black = false))
        assertFalse(ambientBannerShown(playing = false, black = false)) // paused
        assertFalse(ambientBannerShown(playing = null, black = false)) // nothing
        assertFalse(ambientBannerShown(playing = true, black = true))
    }

    @Test fun `bannerShift mirrorsClockShiftHorizontally`() {
        for (step in 0 until 22) {
            val (cx, cy) = shiftOffsetDp(step)
            assertEquals(-cx to cy, bannerShiftDp(step))
        }
    }
    // ◀ ▶ change songs in the screensaver; any other key wakes.
    private val L = KeyEvent.KEYCODE_DPAD_LEFT
    private val R = KeyEvent.KEYCODE_DPAD_RIGHT

    @Test fun `ambientKey leftRight whilePlaying changeSongOnFirstDown`() {
        assertEquals(AmbientKeyAction.PREVIOUS, ambientKey(L, repeatCount = 0, down = true, playing = true))
        assertEquals(AmbientKeyAction.NEXT, ambientKey(R, repeatCount = 0, down = true, playing = true))
    }

    @Test fun `ambientKey leftRight heldOrReleased doNothing`() {
        assertEquals(AmbientKeyAction.IGNORE, ambientKey(L, repeatCount = 3, down = true, playing = true))
        assertEquals(AmbientKeyAction.IGNORE, ambientKey(R, repeatCount = 1, down = true, playing = true))
        assertEquals(AmbientKeyAction.IGNORE, ambientKey(L, repeatCount = 0, down = false, playing = true))
        assertEquals(AmbientKeyAction.IGNORE, ambientKey(R, repeatCount = 0, down = false, playing = false))
    }

    @Test fun `ambientKey leftRight whileNothingPlays wake`() {
        assertEquals(AmbientKeyAction.WAKE, ambientKey(L, repeatCount = 0, down = true, playing = false))
        assertEquals(AmbientKeyAction.WAKE, ambientKey(R, repeatCount = 0, down = true, playing = false))
    }

    @Test fun ambientKey_otherKeys_wake() {
        for (k in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) {
            assertEquals("$k", AmbientKeyAction.WAKE, ambientKey(k, repeatCount = 0, down = true, playing = true))
            assertEquals("$k up", AmbientKeyAction.WAKE, ambientKey(k, repeatCount = 0, down = false, playing = false))
        }
    }
}
