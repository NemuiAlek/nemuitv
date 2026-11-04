package com.nemuigames.nemuitv.controlcenter

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.nemuigames.nemuitv.config.WifiConfig

private val SPECIALS = setOf('\\', ';', ',', ':', '"')

private fun String.escapeWifi(): String = buildString {
    for (c in this@escapeWifi) {
        if (c in SPECIALS) append('\\')
        append(c)
    }
}

/** The text phones understand as "join this Wi-Fi": WIFI:T:<security>;S:<ssid>;P:<password>;; — null when no network is set. */
fun wifiQrText(wifi: WifiConfig): String? {
    if (wifi.ssid.isBlank()) return null
    val security = wifi.security.ifBlank { "WPA" }
    val password = if (security.equals("nopass", ignoreCase = true)) "" else "P:${wifi.password.escapeWifi()};"
    return "WIFI:T:$security;S:${wifi.ssid.escapeWifi()};$password;"
}

/** Renders [text] as a black-on-white QR code, [sizePx] square (modules are scaled to fit). */
fun qrBitmap(text: String, sizePx: Int): ImageBitmap {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 2,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val pixels = IntArray(matrix.width * matrix.height)
    for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
        pixels[y * matrix.width + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    bmp.setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
    return bmp.asImageBitmap()
}
