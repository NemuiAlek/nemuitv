package com.nemuigames.nemuitv.ha

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.LinkedBlockingQueue

/** A tiny fake Home Assistant WebSocket server side, for MockWebServer upgrades. */
class FakeHa(
    private val acceptToken: String = "T",
    private val dropAfterEvent: Boolean = false,
    private val entityExists: Boolean = true,
    private val subscribeSucceeds: Boolean = true,
    /** Test hook, tried first for every non-auth message; true = handled, skip the built-in replies. */
    private val respond: (type: String, msg: JsonObject, ws: WebSocket) -> Boolean = { _, _, _ -> false },
) : WebSocketListener() {
    val received = LinkedBlockingQueue<String>()
    @Volatile var closed = false

    override fun onOpen(webSocket: WebSocket, response: Response) {
        webSocket.send("""{"type":"auth_required","ha_version":"2026.10.1"}""")
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        received.add(text)
        val msg = Json.parseToJsonElement(text).jsonObject
        val type = msg["type"]?.jsonPrimitive?.content
        if (type != "auth" && type != null && respond(type, msg, webSocket)) return
        when (type) {
            "auth" ->
                if (msg["access_token"]?.jsonPrimitive?.content == acceptToken) webSocket.send("""{"type":"auth_ok"}""")
                else webSocket.send("""{"type":"auth_invalid","message":"Invalid access token"}""")
            "call_service" -> webSocket.send("""{"id":${msg["id"]!!.jsonPrimitive.int},"type":"result","success":true,"result":null}""")
            "subscribe_entities" -> {
                val id = msg["id"]!!.jsonPrimitive.int
                if (!subscribeSucceeds) {
                    webSocket.send("""{"id":$id,"type":"result","success":false,"error":{"code":"invalid_format","message":"bad"}}""")
                    return
                }
                webSocket.send("""{"id":$id,"type":"result","success":true,"result":null}""")
                if (entityExists) webSocket.send("""{"id":$id,"type":"event","event":{"a":{"weather.forecast_home":{"s":"sunny","a":{"temperature":78,"temperature_unit":"°F"}}}}}""")
                else webSocket.send("""{"id":$id,"type":"event","event":{"a":{}}}""")
                if ("media_player.tv" in text) webSocket.send("""{"id":$id,"type":"event","event":{"a":{"media_player.tv":{"s":"on","a":{"is_volume_muted":false,"source":"HDMI 1"}}}}}""")
                if ("media_player.spotify" in text) webSocket.send("""{"id":$id,"type":"event","event":{"a":{"media_player.spotify":{"s":"playing","a":{"source":"Phone"}}}}}""")
                if (dropAfterEvent) webSocket.close(1001, "going away")
            }
        }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { closed = true; webSocket.close(1000, null) }
    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed = true }
}
