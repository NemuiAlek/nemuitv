package com.nemuigames.nemuitv.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nemuigames.nemuitv.apps.AppInfo

val TileWidth: Dp = 128.dp
val TileHeight: Dp = 72.dp

/**
 * The "liquid glass" look of the ⌂ tile: frosted fill that turns near-white when focused, a hairline frost border that
 * becomes a white outline, and a soft shadow glow. One definition for every glass control (dock/tray tiles, the
 * Now Playing banner, [⊜], the Control Center's panel/tiles/lists); pass the control's own [Shape].
 */
object Glass {
    /** [frosted] = false: no fill (an app banner shown edge-to-edge), just the border/outline. */
    @Composable
    fun colors(frosted: Boolean = true) = ClickableSurfaceDefaults.colors(
        containerColor = if (frosted) Jt.Frost else Color.Transparent,
        focusedContainerColor = if (frosted) Jt.FrostFocused else Color.Transparent,
        pressedContainerColor = if (frosted) Jt.FrostFocused else Color.Transparent,
        contentColor = Jt.TextPrimary,
        focusedContentColor = Jt.TextOnFocus,
        pressedContentColor = Jt.TextOnFocus,
    )

    @Composable
    fun border(shape: Shape) = ClickableSurfaceDefaults.border(
        border = Border(BorderStroke(1.dp, Jt.FrostBorder), shape = shape),
        focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = shape),
    )

    @Composable
    fun glow() = ClickableSurfaceDefaults.glow(focusedGlow = Glow(elevationColor = Color.Black.copy(alpha = 0.5f), elevation = 8.dp))

    /** The resting glass (frosted fill + hairline border) for a surface that isn't focusable itself (Now Playing frame). */
    fun surface(shape: Shape): Modifier = Modifier.background(Jt.Frost, shape).border(1.dp, Jt.FrostBorder, shape)

    /** Matte glass for the Control Center panel: see-through, but readable on bright video (see [Jt.MatteTint]). */
    fun matte(shape: Shape): Modifier =
        Modifier.background(Jt.MatteTint, shape).background(Jt.MatteFrost, shape).border(1.dp, Jt.FrostBorder, shape)

}

/**
 * A focusable 16:9 tile that grows a little and gets a white outline when focused. [frosted] = a frosted card behind
 * the content (⌂ tile, apps without a TV banner); apps with a banner show it edge-to-edge instead.
 */
@Composable
fun FrostTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    frosted: Boolean = true,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.size(TileWidth, TileHeight),
        shape = ClickableSurfaceDefaults.shape(shape = Jt.TileShape),
        colors = Glass.colors(frosted),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        border = Glass.border(Jt.TileShape),
        glow = Glass.glow(),
    ) { content() }
}

/** An app's tile: its TV banner if it has one, otherwise its icon with the name underneath. */
@Composable
fun AppTile(
    app: AppInfo,
    bannerBitmap: ImageBitmap?,
    iconBitmap: ImageBitmap,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    lifted: Boolean = false, // picked up in dock edit mode: bigger and raised
) {
    val lift by animateFloatAsState(if (lifted) 1f else 0f, tween(180), label = "lift")
    FrostTile(
        onClick = onClick, onLongClick = onLongClick, frosted = bannerBitmap == null,
        modifier = modifier.graphicsLayer {
            val scale = 1f + 0.08f * lift
            scaleX = scale; scaleY = scale
            translationY = -14.dp.toPx() * lift
        },
    ) {
        if (bannerBitmap != null) {
            Image(bannerBitmap, contentDescription = app.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Column(
                Modifier.fillMaxSize().padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Image(iconBitmap, contentDescription = null, modifier = Modifier.size(34.dp))
                Spacer(Modifier.height(4.dp))
                Text(app.label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The ⌂ Home tile: opens the hub. A normal, movable entry in the dock / tray order. */
@Composable
fun HomeTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    lifted: Boolean = false, // picked up in edit mode: bigger and raised
) {
    val lift by animateFloatAsState(if (lifted) 1f else 0f, tween(180), label = "lift")
    FrostTile(
        onClick = onClick, onLongClick = onLongClick,
        modifier = modifier.graphicsLayer {
            val scale = 1f + 0.08f * lift
            scaleX = scale; scaleY = scale
            translationY = -14.dp.toPx() * lift
        },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                HouseGlyph(androidx.tv.material3.LocalContentColor.current, size = 28.dp)
                Text("Home", fontSize = 11.sp)
            }
        }
    }
}
