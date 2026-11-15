package com.nemuigames.nemuitv.hub

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EntityEventLastChangedTest {
    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private val id = "sensor.x"

    @Test fun `add event parses lc to epoch ms`() {
        val out = applyEntityEvent(emptyMap(), obj("""{"a":{"$id":{"s":"on","a":{},"lc":1763032463.2,"lu":1763032470.0}}}"""))
        assertEquals(1763032463200L, out[id]?.lastChangedMs)
    }

    @Test fun `diff with lc updates it`() {
        val start = applyEntityEvent(emptyMap(), obj("""{"a":{"$id":{"s":"on","a":{},"lc":1763032463.2}}}"""))
        val out = applyEntityEvent(start, obj("""{"c":{"$id":{"+":{"s":"off","lc":1763032999.5}}}}"""))
        assertEquals(1763032999500L, out[id]?.lastChangedMs)
    }

    @Test fun `diff without lc keeps old value`() {
        val start = applyEntityEvent(emptyMap(), obj("""{"a":{"$id":{"s":"on","a":{},"lc":1763032463.2}}}"""))
        val out = applyEntityEvent(start, obj("""{"c":{"$id":{"+":{"a":{"k":1},"lu":1763033000.0}}}}"""))
        assertEquals(1763032463200L, out[id]?.lastChangedMs)
    }

    @Test fun `add without lc is null`() {
        val out = applyEntityEvent(emptyMap(), obj("""{"a":{"$id":{"s":"on","a":{}}}}"""))
        assertNull(out[id]?.lastChangedMs)
    }
}
