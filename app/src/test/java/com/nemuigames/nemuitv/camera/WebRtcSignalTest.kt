package com.nemuigames.nemuitv.camera

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRtcSignalTest {
    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test fun parse_allTypes() {
        assertEquals(SignalEvent.Session("abc"), parseSignal(obj("""{"type":"session","session_id":"abc"}""")))
        assertEquals(SignalEvent.Answer("v=0"), parseSignal(obj("""{"type":"answer","answer":"v=0"}""")))
        assertEquals(
            SignalEvent.Candidate("candidate:1 1 udp 1 1.2.3.4 5 typ host", "1", 2),
            parseSignal(obj("""{"type":"candidate","candidate":{"candidate":"candidate:1 1 udp 1 1.2.3.4 5 typ host","sdpMid":"1","sdpMLineIndex":2}}""")),
        )
        assertEquals(
            SignalEvent.Error("webrtc_offer_failed", "boom"),
            parseSignal(obj("""{"type":"error","code":"webrtc_offer_failed","message":"boom"}""")),
        )
    }

    @Test fun parse_candidateDefaults() {
        assertEquals(
            SignalEvent.Candidate("candidate:x", "0", 0),
            parseSignal(obj("""{"type":"candidate","candidate":{"candidate":"candidate:x"}}""")),
        )
    }

    @Test fun parse_unknownType() {
        assertEquals(SignalEvent.Unknown, parseSignal(obj("""{"type":"wat"}""")))
        assertEquals(SignalEvent.Unknown, parseSignal(obj("""{"foo":1}""")))
    }

    @Test fun rateLimited_detection() {
        assertTrue(isRateLimited("unknown", "429 RESOURCE_EXHAUSTED"))
        assertTrue(isRateLimited("429", null))
        assertTrue(isRateLimited(null, "RESOURCE_EXHAUSTED"))
        assertFalse(isRateLimited("x", "boom"))
        assertFalse(isRateLimited(null, null))
    }

    @Test fun offerPayload_shape() {
        val p = offerPayload("camera.front", "v=0")
        assertEquals("camera.front", p["entity_id"]!!.jsonPrimitive.content)
        assertEquals("v=0", p["offer"]!!.jsonPrimitive.content)
    }

    @Test fun candidatePayload_shape() {
        val p = candidatePayload("camera.front", "sess1", SignalEvent.Candidate("candidate:x", "0", 1))
        assertEquals("camera.front", p["entity_id"]!!.jsonPrimitive.content)
        assertEquals("sess1", p["session_id"]!!.jsonPrimitive.content)
        val c = p["candidate"]!!.jsonObject
        assertEquals("candidate:x", c["candidate"]!!.jsonPrimitive.content)
        assertEquals("0", c["sdpMid"]!!.jsonPrimitive.content)
        assertEquals(1, c["sdpMLineIndex"]!!.jsonPrimitive.int)
    }

    @Test fun candidatesBufferedUntilSession() {
        val b = CandidateBuffer<String>()
        assertEquals(emptyList<String>(), b.add("c1"))
        assertEquals(emptyList<String>(), b.add("c2"))
        assertEquals(listOf("c1", "c2"), b.onSession())
        assertEquals(listOf("c3"), b.add("c3"))
    }

    @Test fun `candidatesBuffer concurrentAdds noneLostInOrderPerThread`() {
        val b = CandidateBuffer<String>()
        val out = java.util.Collections.synchronizedList(mutableListOf<String>())
        val start = java.util.concurrent.CountDownLatch(1)
        val half = java.util.concurrent.CountDownLatch(500)
        fun adder(tag: String) = Thread {
            start.await()
            for (i in 0 until 1000) {
                out += b.add("$tag$i").also { }
                half.countDown()
            }
        }
        val ts = listOf(adder("a"), adder("b"))
        val sess = Thread { start.await(); half.await(); synchronized(out) { out += b.onSession() } }
        (ts + sess).forEach { it.start() }
        start.countDown()
        (ts + sess).forEach { it.join() }
        assertEquals(2000, out.size)
        assertEquals(2000, out.toSet().size)
    }
}
