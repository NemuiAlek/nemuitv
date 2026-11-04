package com.nemuigames.nemuitv.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import com.nemuigames.nemuitv.apps.AppInfo

private val Gap = 14.dp
/** Room for a focused/lifted tile to grow sideways (lifted + focused ≈ 1.15× → ~10dp each side) plus its glow. */
private val EdgeRoom = 16.dp
/** Room above/below: a lifted tile rises 14dp and grows. */
private val VerticalRoom = 26.dp
/**
 * Bottom dock: the first 5 entries of the saved order (apps, and the ⌂ Home tile if it is among them). [onKey] sees remote keys first (Down → tray, edit mode).
 * Favorites live in a LazyRow so `animateItem()` slides tiles smoothly when the order changes.
 *
 * The LazyRow gets an exact width (its content + [EdgeRoom] on each side) inside a Box: a LazyRow clips along its
 * scroll axis, so without the room the focused first/last tile's growth was cut off; and if it's narrower than its
 * content it scrolls to reveal the last tile (shifting + clipping). An *unbounded* width crashes, so: exact width.
 *
 * [focusable] = false while the tray covers the dock: its (invisible) tiles can't take focus then.
 */
@Composable
fun Dock(
    favorites: List<AppInfo>,
    tile: @Composable (AppInfo, Modifier) -> Unit,
    onKey: (KeyEvent) -> Boolean,
    onFavoriteFocused: (String?) -> Unit,
    favoriteFocus: (String) -> FocusRequester,
    modifier: Modifier = Modifier,
    focusable: Boolean = true,
) {
    val canFocus = Modifier.focusProperties { this.canFocus = focusable }
    val n = favorites.size
    val rowWidth = TileWidth * n + Gap * (n - 1).coerceAtLeast(0) + EdgeRoom * 2
    Box(modifier.height(TileHeight).onPreviewKeyEvent(onKey)) {
        if (n > 0) LazyRow(
            // requiredHeight lets the row overflow the dock's height equally above and below (centered), so a
            // lifted tile has room to rise without the dock itself moving.
            // unbounded: with ⌂ in the row (5 apps + ⌂) the exact width is a few dp wider than the safe area; a clamped
            // LazyRow scrolls to keep the focused tile in view, so the whole row slid on every ◀ ▶. The extra is EdgeRoom
            // (empty), so letting it overflow is invisible.
            modifier = Modifier.offset(x = -EdgeRoom).wrapContentWidth(Alignment.Start, unbounded = true).width(rowWidth).requiredHeight(TileHeight + VerticalRoom * 2),
            contentPadding = PaddingValues(horizontal = EdgeRoom, vertical = VerticalRoom),
            horizontalArrangement = Arrangement.spacedBy(Gap),
            userScrollEnabled = false,
        ) {
            items(favorites, key = { it.packageName }) { app ->
                tile(
                    app,
                    Modifier.animateItem()
                        .then(canFocus)
                        .focusRequester(favoriteFocus(app.packageName))
                        .onFocusChanged { if (it.isFocused) onFavoriteFocused(app.packageName) },
                )
            }
        }
    }
}
