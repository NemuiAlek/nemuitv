package com.nemuigames.nemuitv.alerts

import com.nemuigames.nemuitv.hub.EntityState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.OffsetDateTime

class AlertTrackerTest {
    private val id = "event.washer_notification"
    private val base = "2026-11-03T14:14:35.118+00:00"
    private var now = OffsetDateTime.parse(base).toInstant().toEpochMilli() + 1_000
    private val tracker = AlertTracker { now }

    private fun st(ts: String, type: String? = "washing_is_complete") =
        EntityState(ts, JsonObject(if (type == null) emptyMap() else mapOf("event_type" to JsonPrimitive(type))))

    private fun at(offsetSec: Long) = OffsetDateTime.parse(base).plusSeconds(offsetSec).toString()

    @Test fun firstStateAfterSubscribe_notFired() {
        assertNull(tracker.onState(id, st(base)))
    }

    @Test fun newerTimestamp_firesEventType() {
        tracker.onState(id, st(base))
        now += 10_000
        assertEquals("drying_is_complete", tracker.onState(id, st(at(5), "drying_is_complete")))
    }

    @Test fun sameOrOlderTimestamp_notFired() {
        tracker.onState(id, st(base))
        assertNull(tracker.onState(id, st(base)))
        assertNull(tracker.onState(id, st(at(-5))))
    }

    @Test fun staleEvent_over60s_notFired() {
        tracker.onState(id, st(base))
        now = OffsetDateTime.parse(base).plusSeconds(1 + 61).toInstant().toEpochMilli()
        assertNull(tracker.onState(id, st(at(1))))
    }

    @Test fun `unavailable notFired andDoesNotReplaceRemembered`() {
        tracker.onState(id, st(base))
        assertNull(tracker.onState(id, st("unavailable", null)))
        assertNull(tracker.onState(id, null))
        // still compares against the remembered timestamp
        assertNull(tracker.onState(id, st(base)))
        assertEquals("washing_is_complete", tracker.onState(id, st(at(2))))
    }

    @Test fun `unavailableFirst thenRealStateIsStillFirst`() {
        assertNull(tracker.onState(id, st("unavailable", null)))
        assertNull(tracker.onState(id, st(base)))
    }

    @Test fun reset_makesNextStateFirstAgain() {
        tracker.onState(id, st(base))
        tracker.reset()
        assertNull(tracker.onState(id, st(at(2))))
        assertEquals("washing_is_complete", tracker.onState(id, st(at(3))))
    }

    @Test fun unparsable_notFired() {
        tracker.onState(id, st(base))
        assertNull(tracker.onState(id, st("not a time")))
        assertEquals("washing_is_complete", tracker.onState(id, st(at(2))))
    }
}
