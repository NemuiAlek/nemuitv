package com.nemuigames.nemuitv.setup

import com.nemuigames.nemuitv.ha.HaStatus
import com.nemuigames.nemuitv.ui.GuideLinks

/** What the Setup page reads from the device. */
data class SetupInputs(
    val secureGranted: Boolean,
    val takeoverOn: Boolean,
    val nowPlayingOn: Boolean,
    val screensaverOurs: Boolean,
    val haStatus: HaStatus,
    /** The current Home Assistant error text, when it has one. */
    val haProblem: String?,
    val wallpaperCount: Int,
    /** One line per problem in config.json; empty = fine. */
    val configIssues: List<String>,
)

enum class RowKind { COMPUTER, TAKEOVER, NOW_PLAYING, SCREENSAVER, HA, WALLPAPERS, CONFIG }

/**
 * One checklist row. [switchOn] null = not a switch (just a status line). [enabled] false = OK does nothing.
 * [guideUrl] set = focusing the row shows a QR code to that guide.
 */
data class Row(
    val kind: RowKind,
    val label: String,
    val detail: String,
    val switchOn: Boolean?,
    val enabled: Boolean,
    val guideUrl: String?,
)

object SetupChecklist {
    /**
     * [statusOnly]: switch rows the app can't flip itself on this device (a settings write that doesn't take effect
     * without a reboot). They show their state but don't toggle, and say how to fix it.
     */
    fun rows(i: SetupInputs, statusOnly: Set<RowKind> = emptySet()): List<Row> {
        fun switchRow(kind: RowKind, label: String, on: Boolean): Row = when {
            !i.secureGranted -> Row(kind, label, NEEDS_COMPUTER, on, false, null)
            kind in statusOnly -> Row(kind, label, if (on) "On" else "Off, run setup again", on, false, null)
            else -> Row(kind, label, if (on) "On" else "Off", on, true, null)
        }
        val out = mutableListOf(
            if (i.secureGranted) Row(RowKind.COMPUTER, "Set up from a computer", "Done", true, false, null)
            else Row(RowKind.COMPUTER, "Set up from a computer", "Run setup from the README", false, false, GuideLinks.SETUP),
            switchRow(RowKind.TAKEOVER, "Home button opens NemuiTV", i.takeoverOn),
            switchRow(RowKind.NOW_PLAYING, "Now Playing", i.nowPlayingOn),
            switchRow(RowKind.SCREENSAVER, "Screensaver", i.screensaverOurs),
            if (i.haStatus == HaStatus.NOT_CONFIGURED)
                Row(RowKind.HA, "Home Assistant", "Not set up, see the guide", null, false, GuideLinks.HA)
            else Row(RowKind.HA, "Home Assistant",
                if (i.haStatus == HaStatus.CONNECTED) "Connected" else i.haProblem ?: "Not connected", null, false, null),
            if (i.wallpaperCount == 0)
                Row(RowKind.WALLPAPERS, "Wallpapers", "Using the built-in one, see the guide", null, false, GuideLinks.WALLPAPERS)
            else Row(RowKind.WALLPAPERS, "Wallpapers",
                if (i.wallpaperCount == 1) "1 wallpaper" else "${i.wallpaperCount} wallpapers", null, false, null),
        )
        if (i.configIssues.isNotEmpty()) out += Row(RowKind.CONFIG, "Config", i.configIssues.joinToString("\n"), null, false, null)
        return out
    }

    const val NEEDS_COMPUTER = "Needs setup from a computer"
}
