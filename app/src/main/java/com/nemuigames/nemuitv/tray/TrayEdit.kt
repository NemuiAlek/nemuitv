package com.nemuigames.nemuitv.tray

import com.nemuigames.nemuitv.home.DockKey

/**
 * Tray edit mode: one app can be *lifted*; ◀ ▶ move it ±1, ▲ ▼ move it a whole row (∓[TrayColumns]),
 * OK drops it, OK on another app lifts that one, Back leaves edit mode (BackHandler, so Back always passes through).
 */
data class TrayEditState(
    val editing: Boolean = false,
    val lifted: String? = null,
    /** The OK that lifted/dropped was a key-down; its key-up must not also "click" (launch) the tile. */
    val swallowOkRelease: Boolean = false,
) {
    companion object {
        fun startEditing(pkg: String) = TrayEditState(editing = true, lifted = pkg)
        fun stopEditing() = TrayEditState()
    }
}

data class TrayKeyResult(
    val state: TrayEditState,
    /** True = the tray handled the key (don't let Compose move focus / click). */
    val consumed: Boolean,
    /** Move the lifted app by this many grid slots. */
    val move: Int? = null,
)

fun trayKey(state: TrayEditState, key: DockKey, down: Boolean, repeatCount: Int, focusedPkg: String?): TrayKeyResult {
    if (!state.editing) return TrayKeyResult(state, consumed = false)
    return when (key) {
        DockKey.BACK, DockKey.OTHER -> TrayKeyResult(state, consumed = false)
        DockKey.LEFT, DockKey.RIGHT, DockKey.UP, DockKey.DOWN -> when {
            state.lifted == null -> TrayKeyResult(state, consumed = false) // just move focus
            down -> TrayKeyResult(
                state, consumed = true,
                move = when (key) {
                    DockKey.LEFT -> -1
                    DockKey.RIGHT -> +1
                    DockKey.UP -> -TrayColumns
                    else -> +TrayColumns
                },
            ) // held arrows (repeatCount > 0) keep sliding
            else -> TrayKeyResult(state, consumed = true)
        }
        DockKey.OK -> when {
            !down -> TrayKeyResult(state.copy(swallowOkRelease = false), consumed = true)
            repeatCount > 0 -> TrayKeyResult(state, consumed = true) // held OK: no lift/drop
            state.lifted != null -> TrayKeyResult(state.copy(lifted = null, swallowOkRelease = true), consumed = true)
            focusedPkg != null -> TrayKeyResult(state.copy(lifted = focusedPkg, swallowOkRelease = true), consumed = true)
            else -> TrayKeyResult(state, consumed = true) // OK on the Hidden apps tile while editing: ignore
        }
    }
}
