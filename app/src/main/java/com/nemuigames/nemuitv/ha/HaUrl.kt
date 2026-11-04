package com.nemuigames.nemuitv.ha

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * "http://host:8123/" → "ws://host:8123/api/websocket" (https → wss). Tolerates spaces, trailing slashes, any case,
 * and a copied page path like "/lovelace/0" (only scheme, host and port are kept).
 */
fun toWebSocketUrl(httpUrl: String): String {
    httpUrl.trim().toHttpUrlOrNull()?.let { u ->
        val ws = if (u.isHttps) "wss" else "ws"
        val port = if (u.port == HttpUrl.defaultPort(u.scheme)) "" else ":${u.port}"
        return "$ws://${u.host}$port/api/websocket"
    }
    val base = httpUrl.trim().trimEnd('/')
    val ws = when {
        base.startsWith("https://", ignoreCase = true) -> "wss://" + base.substring(8)
        base.startsWith("http://", ignoreCase = true) -> "ws://" + base.substring(7)
        else -> base
    }
    return "$ws/api/websocket"
}
