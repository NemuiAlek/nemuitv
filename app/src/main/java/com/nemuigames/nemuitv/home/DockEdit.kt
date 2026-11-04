package com.nemuigames.nemuitv.home

/** Remote keys the dock cares about. */
enum class DockKey { LEFT, RIGHT, UP, DOWN, OK, BACK, OTHER }

/**
 * Dock edit mode ("Move in favorites"): one app can be *lifted*; ◀ ▶ slide it, OK drops it, OK on another app
 * lifts that one, Back leaves edit mode (handled by a BackHandler, so Back always passes through here).
 */
data class DockEditState(
    val editing: Boolean = false,
    val lifted: String? = null,
    /** The OK that lifted/dropped was a key-down; its key-up must not also "click" (launch) the tile. */
    val swallowOkRelease: Boolean = false,
) {
    companion object {
        fun startEditing(pkg: String) = DockEditState(editing = true, lifted = pkg)
        fun stopEditing() = DockEditState()
    }
}

data class DockKeyResult(
    val state: DockEditState,
    /** True = the dock handled the key (don't let Compose move focus / click). */
    val consumed: Boolean,
    /** Slide the lifted app by this many slots. */
    val move: Int? = null,
    val openTray: Boolean = false,
)

fun dockKey(state: DockEditState, key: DockKey, down: Boolean, focusedPkg: String?): DockKeyResult {
    if (!state.editing) {
        return if (down && key == DockKey.DOWN) DockKeyResult(state, consumed = true, openTray = true)
        else DockKeyResult(state, consumed = false)
    }
    return when (key) {
        DockKey.BACK -> DockKeyResult(state, consumed = false)
        DockKey.UP, DockKey.DOWN -> DockKeyResult(state, consumed = true)
        DockKey.LEFT, DockKey.RIGHT -> when {
            state.lifted == null -> DockKeyResult(state, consumed = false) // just move focus
            down -> DockKeyResult(state, consumed = true, move = if (key == DockKey.LEFT) -1 else +1)
            else -> DockKeyResult(state, consumed = true)
        }
        DockKey.OK -> when {
            !down -> DockKeyResult(state.copy(swallowOkRelease = false), consumed = true)
            state.lifted != null -> DockKeyResult(state.copy(lifted = null, swallowOkRelease = true), consumed = true)
            focusedPkg != null -> DockKeyResult(state.copy(lifted = focusedPkg, swallowOkRelease = true), consumed = true)
            else -> DockKeyResult(state, consumed = true) // OK on nothing liftable: ignore
        }
        DockKey.OTHER -> DockKeyResult(state, consumed = false)
    }
}
