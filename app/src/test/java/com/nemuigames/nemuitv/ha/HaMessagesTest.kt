package com.nemuigames.nemuitv.ha

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HaMessagesTest {
    private val e = "weather.forecast_home"
    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private val sunny = WeatherSnapshot("sunny", 78.0, "°F")

    @Test fun parse_authMessages() {
        assertEquals(HaInbound.AuthRequired, parseInbound("""{"type":"auth_required","ha_version":"2026.10.1"}"""))
        assertEquals(HaInbound.AuthOk, parseInbound("""{"type":"auth_ok"}"""))
        assertEquals(HaInbound.AuthInvalid("Invalid access token"), parseInbound("""{"type":"auth_invalid","message":"Invalid access token"}"""))
    }

    @Test fun parse_resultAndEvent() {
        assertEquals(HaInbound.Result(1, true), parseInbound("""{"id":1,"type":"result","success":true,"result":null}"""))
        val ev = parseInbound("""{"id":1,"type":"event","event":{"a":{}}}""") as HaInbound.Event
        assertEquals(1, ev.id)
    }

    @Test fun parse_unknownOrGarbage_isNull() {
        assertNull(parseInbound("""{"type":"pong"}"""))
        assertNull(parseInbound("not json"))
    }

    @Test fun weather_fromAddEvent() {
        val ev = obj("""{"a":{"$e":{"s":"sunny","a":{"temperature":78,"temperature_unit":"°F"}}}}""")
        assertEquals(sunny, applyEntityEvent(null, ev, e))
    }

    @Test fun weather_fromChangeEvent_keepsUnit() {
        val ev = obj("""{"c":{"$e":{"+":{"s":"rainy","a":{"temperature":71}}}}}""")
        assertEquals(WeatherSnapshot("rainy", 71.0, "°F"), applyEntityEvent(sunny, ev, e))
    }

    @Test fun weather_unavailable_isNull() {
        assertNull(applyEntityEvent(sunny, obj("""{"c":{"$e":{"+":{"s":"unavailable"}}}}"""), e))
        assertNull(applyEntityEvent(null, obj("""{"a":{"$e":{"s":"unknown","a":{}}}}"""), e))
    }

    @Test fun weather_otherEntityIgnored() {
        assertEquals(sunny, applyEntityEvent(sunny, obj("""{"a":{"weather.other":{"s":"snowy","a":{"temperature":20}}}}"""), e))
    }

    @Test fun weather_removeEvent_isNull() {
        assertNull(applyEntityEvent(sunny, obj("""{"r":["$e"]}"""), e))
    }

    // Stale = we've been disconnected for more than 30 minutes (a live connection is never stale,
    // even if HA hasn't sent a weather change in an hour).
    @Test fun isStale_thirtyMinutesAfterDisconnect() {
        val min = 60_000L
        assertEquals(false, isStale(disconnectedSince = 0, now = 29 * min))
        assertEquals(true, isStale(disconnectedSince = 0, now = 31 * min))
        assertEquals(false, isStale(disconnectedSince = null, now = 999 * min))
    }
}

class TvEventTest {
    private fun ev(s: String) = kotlinx.serialization.json.Json.parseToJsonElement(s) as kotlinx.serialization.json.JsonObject

    @org.junit.Test fun applyTvEvent_readsSource() {
        val added = applyTvEvent(null, ev("""{"a":{"media_player.tv":{"s":"on","a":{"is_volume_muted":true,"source":"HDMI 2"}}}}"""), "media_player.tv")
        org.junit.Assert.assertEquals(TvState(true, "HDMI 2"), added)
        val changed = applyTvEvent(added, ev("""{"c":{"media_player.tv":{"+":{"a":{"source":"PS5"}}}}}"""), "media_player.tv")
        org.junit.Assert.assertEquals(TvState(true, "PS5"), changed)
        val off = applyTvEvent(changed, ev("""{"c":{"media_player.tv":{"+":{"s":"unavailable"}}}}"""), "media_player.tv")
        org.junit.Assert.assertEquals(false, off?.available)
    }
}

class EntityStateEventTest {
    private fun ev(s: String) = kotlinx.serialization.json.Json.parseToJsonElement(s) as kotlinx.serialization.json.JsonObject
    private val e = "media_player.spotify"

    @org.junit.Test fun applyStateEvent_tracksStateString() {
        val added = applyStateEvent(null, ev("""{"a":{"media_player.spotify":{"s":"paused","a":{"source":"Phone"}}}}"""), e)
        org.junit.Assert.assertEquals("paused", added)
        val playing = applyStateEvent(added, ev("""{"c":{"media_player.spotify":{"+":{"s":"playing"}}}}"""), e)
        org.junit.Assert.assertEquals("playing", playing)
        val attrOnly = applyStateEvent(playing, ev("""{"c":{"media_player.spotify":{"+":{"a":{"volume_level":0.3}}}}}"""), e)
        org.junit.Assert.assertEquals("playing", attrOnly)
        val other = applyStateEvent(attrOnly, ev("""{"c":{"media_player.tv":{"+":{"s":"off"}}}}"""), e)
        org.junit.Assert.assertEquals("playing", other)
        org.junit.Assert.assertNull(applyStateEvent(other, ev("""{"r":["media_player.spotify"]}"""), e))
    }
}
