package com.nemuigames.nemuitv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.nemuigames.nemuitv.controlcenter.qrBitmap

/** Where the on-screen hints point: the project's setup guides. */
object GuideLinks {
    const val REPO = "https://github.com/NemuiAlek/nemuitv"
    val HA = "$REPO/blob/main/docs/home-assistant.md"
    val WALLPAPERS = "$REPO/blob/main/docs/wallpapers.md"
    val SETUP = "$REPO#quick-start"
}

/** A QR code for [url], [size] square, to scan with a phone (same generator as the Wi-Fi QR). */
@Composable
fun GuideQr(url: String, size: Dp) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bitmap = remember(url, px) { qrBitmap(url, px) }
    Image(bitmap, contentDescription = "QR code: $url", modifier = Modifier.size(size), contentScale = ContentScale.Fit)
}
