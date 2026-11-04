package com.nemuigames.nemuitv.controlcenter

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The Control Center's action icons, from the approved mockup (cc-icons-v2.html, 24×24 SVG symbols): Inputs, Wi-Fi
 * (QR code with a Wi-Fi badge), Next wallpaper, Settings. Drawn in black; show them with a tint (the content colour),
 * so a focused (white) circle gets a dark icon.
 */
internal object CcIcons {
    val Input: ImageVector by lazy {
        icon("input") {
            stroke("M3 8V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-3M3 12h11m-4-4 4 4-4 4", 2f)
        }
    }

    val QrWifi: ImageVector by lazy {
        icon("qrwifi") {
            // Three finder squares, their centres, and a few scattered modules.
            for ((x, y) in listOf(2.5f to 2.5f, 15.5f to 2.5f, 2.5f to 15.5f)) stroke(roundRect(x, y, 6f, 6f, 1f), 1.8f)
            fill(
                listOf(4.6f to 4.6f, 17.6f to 4.6f, 4.6f to 17.6f).joinToString(" ") { (x, y) -> rect(x, y, 1.8f, 1.8f) } + " " +
                    listOf(10.5f to 2.5f, 2.5f to 10.5f, 19.5f to 10.5f, 10.5f to 19.5f, 16f to 17f, 19.5f to 19.5f)
                        .joinToString(" ") { (x, y) -> rect(x, y, 2f, 2f) },
            )
            // The Wi-Fi badge in the middle: a faint disc, two arcs and a dot.
            fill(circle(12f, 12f, 5f), alpha = 0.18f)
            stroke("M9.2 11.2a4 4 0 0 1 5.6 0", 1.5f)
            stroke("M10.6 12.7a2 2 0 0 1 2.8 0", 1.5f)
            fill(circle(12f, 14.3f, 0.9f))
        }
    }

    val Wallpaper: ImageVector by lazy {
        icon("wall") {
            stroke(roundRect(3f, 4f, 18f, 16f, 2f), 2f)
            stroke("M3 16l5-5 4 4 3-3 6 6", 2f)
            fill(circle(16f, 9f, 1.6f))
        }
    }

    val Gear: ImageVector by lazy {
        icon("gear") {
            // The mockup draws it shifted 1 unit left (transform="translate(-1 0)"); baked into the path here.
            fill(
                "M18.4 13a7.5 7.5 0 0 0 0-2l2.1-1.6-2-3.5-2.5 1a7.4 7.4 0 0 0-1.7-1L14 3.3h-4l-.4 2.6a7.4 7.4 0 0 0-1.7 1" +
                    "l-2.5-1-2 3.5L5.6 11a7.5 7.5 0 0 0 0 2l-2.2 1.6 2 3.5 2.5-1a7.4 7.4 0 0 0 1.7 1l.4 2.6h4l.4-2.6" +
                    "a7.4 7.4 0 0 0 1.7-1l2.5 1 2-3.5zM12 15.5a3.5 3.5 0 1 1 0-7 3.5 3.5 0 0 1 0 7z",
                evenOdd = true, // the hole in the middle, whichever way the two subpaths wind
            )
        }
    }

    val Shuffle: ImageVector by lazy {
        icon("shuffle") {
            // Two curved crossing lanes with arrowheads (the straight Feather X read as "close" at 14 dp).
            stroke("M2 6h3.5c3.5 0 5 2.5 6.5 6s3 6 6.5 6H21M2 18h3.5c3.5 0 5-2.5 6.5-6s3-6 6.5-6H21", 2f)
            stroke("M18 3l3 3-3 3M18 15l3 3-3 3", 2f)
        }
    }

    val Repeat: ImageVector by lazy {
        icon("repeat") { stroke("M17 1l4 4-4 4M3 11V9a4 4 0 0 1 4-4h14M7 23l-4-4 4-4M21 13v2a4 4 0 0 1-4 4H3", 2f) }
    }

    val RepeatOne: ImageVector by lazy {
        icon("repeat1") {
            stroke("M17 1l4 4-4 4M3 11V9a4 4 0 0 1 4-4h14M7 23l-4-4 4-4M21 13v2a4 4 0 0 1-4 4H3", 2f)
            stroke("M11 10l1.5-1v7", 1.8f)
        }
    }

    private const val HEART = "M12 21l-1.5-1.3C5.4 15.1 2 12 2 8.5 2 5.4 4.4 3 7.5 3c1.7 0 3.4.8 4.5 2.1C13.1 3.8 14.8 3 16.5 3 19.6 3 22 5.4 22 8.5c0 3.5-3.4 6.6-8.5 11.2z"

    val Heart: ImageVector by lazy { icon("heart") { fill(HEART) } }

    val HeartOutline: ImageVector by lazy { icon("heartoutline") { stroke(HEART, 2f) } }

    private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    private fun ImageVector.Builder.stroke(d: String, width: Float) {
        addPath(
            pathData = addPathNodes(d), stroke = SolidColor(Color.Black), strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
    }

    private fun ImageVector.Builder.fill(d: String, alpha: Float = 1f, evenOdd: Boolean = false) {
        addPath(
            pathData = addPathNodes(d), fill = SolidColor(Color.Black), fillAlpha = alpha,
            pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
        )
    }

    private fun rect(x: Float, y: Float, w: Float, h: Float) = "M$x ${y}h${w}v${h}h${-w}z"

    private fun roundRect(x: Float, y: Float, w: Float, h: Float, r: Float): String {
        val iw = w - 2 * r
        val ih = h - 2 * r
        return "M${x + r} ${y}h${iw}a$r $r 0 0 1 $r ${r}v${ih}a$r $r 0 0 1 ${-r} ${r}h${-iw}a$r $r 0 0 1 ${-r} ${-r}" +
            "v${-ih}a$r $r 0 0 1 $r ${-r}z"
    }

    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"
}
