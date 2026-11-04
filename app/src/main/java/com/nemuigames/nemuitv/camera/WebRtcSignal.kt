package com.nemuigames.nemuitv.camera

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

fun isRateLimited(code: String?, message: String?): Boolean =
    listOf(code, message).any { it != null && ("429" in it || "RESOURCE_EXHAUSTED" in it) }

sealed interface SignalEvent {
    data class Session(val id: String) : SignalEvent
    data class Answer(val sdp: String) : SignalEvent
    data class Candidate(val candidate: String, val sdpMid: String, val sdpMLineIndex: Int) : SignalEvent
    data class Error(val code: String?, val message: String?) : SignalEvent
    data object Unknown : SignalEvent
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

fun parseSignal(event: JsonObject): SignalEvent = when (event.str("type")) {
    "session" -> event.str("session_id")?.let { SignalEvent.Session(it) } ?: SignalEvent.Unknown
    "answer" -> event.str("answer")?.let { SignalEvent.Answer(it) } ?: SignalEvent.Unknown
    "candidate" -> {
        val c = runCatching { event["candidate"]?.jsonObject }.getOrNull()
        val sdp = c?.str("candidate")
        if (c == null || sdp == null) SignalEvent.Unknown
        else SignalEvent.Candidate(
            sdp,
            c.str("sdpMid") ?: "0",
            (c["sdpMLineIndex"] as? JsonPrimitive)?.intOrNull ?: 0,
        )
    }
    "error" -> SignalEvent.Error(event.str("code"), event.str("message"))
    else -> SignalEvent.Unknown
}

fun offerPayload(entityId: String, sdp: String): JsonObject = buildJsonObject {
    put("entity_id", entityId)
    put("offer", sdp)
}

fun candidatePayload(entityId: String, sessionId: String, c: SignalEvent.Candidate): JsonObject = buildJsonObject {
    put("entity_id", entityId)
    put("session_id", sessionId)
    putJsonObject("candidate") {
        put("candidate", c.candidate)
        put("sdpMid", c.sdpMid)
        put("sdpMLineIndex", c.sdpMLineIndex)
    }
}

/** Holds local ICE candidates until HA's session id arrives, then passes them through. Safe across threads. */
class CandidateBuffer<T> {
    private val pending = mutableListOf<T>()
    private var open = false

    @Synchronized
    fun add(c: T): List<T> = if (open) listOf(c) else { pending += c; emptyList() }

    @Synchronized
    fun onSession(): List<T> {
        open = true
        return pending.toList().also { pending.clear() }
    }
}
