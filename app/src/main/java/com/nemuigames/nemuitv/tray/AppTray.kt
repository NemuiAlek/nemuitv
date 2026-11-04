package com.nemuigames.nemuitv.tray

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.nemuigames.nemuitv.apps.AppInfo
import com.nemuigames.nemuitv.home.DockKey
import com.nemuigames.nemuitv.home.trapFocus

const val TrayColumns = com.nemuigames.nemuitv.favorites.AppOrder.TRAY_COLUMNS

/** Room above the first row so a lifted (risen + bigger) tile isn't clipped by the grid's top edge. */
val TrayTopRoom = 24.dp

/** Remote key → the keys the dock / tray edit rules understand. */
fun Key.toDockKey(): DockKey = when (this) {
    Key.DirectionLeft -> DockKey.LEFT; Key.DirectionRight -> DockKey.RIGHT
    Key.DirectionUp -> DockKey.UP; Key.DirectionDown -> DockKey.DOWN
    Key.DirectionCenter, Key.Enter -> DockKey.OK; Key.Back -> DockKey.BACK
    else -> DockKey.OTHER
}

/**
 * The app focused in the tray or dock (null = something else: Hidden apps tile, top bar). A plain holder, not UI
 * state: read by key handlers and HomeScreen (to move focus to a neighbour when this app disappears).
 */
class FocusedTile {
    var pkg: String? = null
    /** When the focused app's tile is removed, Compose hands focus elsewhere (⌂, the top bar) before HomeScreen hears
     *  about it; this keeps
     *  the app that had focus then (only when it was already gone, so a deliberate move to ⌂ never counts). */
    var vanished: String? = null
    /** The current app list (set while composing, before the removal is applied). */
    internal var apps: List<AppInfo> = emptyList()

    internal fun focusedApp(app: String?) {
        val prev = pkg
        vanished = if (app == null && prev != null && apps.none { it.packageName == prev }) prev else null
        pkg = app
    }
}

/**
 * Every entry (apps and ⌂) as a grid (no search). It takes the dock's place below the clock when you press Down. Its first row is
 * the favorites in dock order ([apps] is `vm.trayApps`, the saved order), so it looks like the dock simply moved up;
 * the same app stays highlighted. Up from the top row (or Back) gives the space back to the dock — except in edit mode.
 * The Hidden apps tile is always last.
 *
 * [onKey] sees remote keys first (tray edit mode, see TrayEdit.kt) with the focused app (null = ⌂ / Hidden apps tile).
 * [gridState] and [focused] live in HomeScreen, so it can scroll a tile into view before focusing it, and knows which
 * app was focused.
 */
@Composable
fun AppTray(
    apps: List<AppInfo>,
    editState: TrayEditState,
    onKey: (key: DockKey, down: Boolean, repeatCount: Int, focusedPkg: String?) -> Boolean,
    hiddenTile: @Composable (Modifier) -> Unit,
    tile: @Composable (AppInfo, Modifier) -> Unit,
    focusFor: (String) -> FocusRequester,
    gridState: LazyGridState,
    focused: FocusedTile,
    initialFocusPkg: String?, // entry highlighted in the dock when Down was pressed (null = the first one)
    onUpFromTopRow: (pkg: String?) -> Unit,
    modifier: Modifier = Modifier,
    trapped: Boolean = true, // false while animating away, so focus can go back to the dock
) {
    LaunchedEffect(Unit) {
        runCatching {
            val target = initialFocusPkg?.takeIf { p -> apps.any { it.packageName == p } }
            (target ?: apps.firstOrNull()?.packageName)?.let(focusFor)?.requestFocus()
        }
    }
    val edgeMarginPx = with(LocalDensity.current) { TrayTopRoom.toPx() }
    focused.apps = apps

    // Keep the lifted tile in view (and focused) as it moves: scroll only when it's (partly) outside the grid.
    LaunchedEffect(editState.lifted, apps) {
        val pkg = editState.lifted ?: return@LaunchedEffect
        val i = apps.indexOfFirst { it.packageName == pkg }
        if (i < 0) return@LaunchedEffect
        val gridIndex = i
        val info = gridState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == gridIndex }
        when {
            item == null -> gridState.animateScrollToItem(gridIndex)
            item.offset.y < info.viewportStartOffset + edgeMarginPx ->
                gridState.animateScrollBy(item.offset.y - info.viewportStartOffset - edgeMarginPx)
            item.offset.y + item.size.height > info.viewportEndOffset - edgeMarginPx ->
                gridState.animateScrollBy(item.offset.y + item.size.height - info.viewportEndOffset + edgeMarginPx)
        }
        runCatching { focusFor(pkg).requestFocus() }
    }

    fun Modifier.upLeavesFromTopRow(gridIndex: Int, pkg: String?) = onPreviewKeyEvent {
        if (!editState.editing && gridIndex < TrayColumns && it.type == KeyEventType.KeyDown && it.key == Key.DirectionUp) {
            onUpFromTopRow(pkg); true
        } else false
    }
    fun Modifier.tracksFocus(pkg: String?) = onFocusChanged { if (it.isFocused) focused.focusedApp(pkg) }

    Box(
        modifier.fillMaxSize().trapFocus(trapped).onPreviewKeyEvent {
            if (it.type != KeyEventType.KeyDown && it.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
            onKey(it.key.toDockKey(), it.type == KeyEventType.KeyDown, it.nativeKeyEvent.repeatCount, focused.pkg)
        },
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(TrayColumns),
            state = gridState,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            // Room so a highlighted / lifted (bigger, risen) logo isn't clipped at the grid's edges.
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = TrayTopRoom, bottom = 14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(apps, key = { _, a -> a.packageName }) { i, app ->
                tile(
                    app,
                    Modifier.animateItem()
                        .focusRequester(focusFor(app.packageName))
                        .tracksFocus(app.packageName)
                        .upLeavesFromTopRow(i, app.packageName),
                )
            }
            item(key = "__hidden") {
                hiddenTile(Modifier.animateItem().tracksFocus(null).upLeavesFromTopRow(apps.size, null))
            }
        }
    }
}
