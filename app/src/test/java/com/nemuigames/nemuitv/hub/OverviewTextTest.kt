package com.nemuigames.nemuitv.hub

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class OverviewTextTest {
    private fun st(state: String, vararg attrs: Pair<String, String>) =
        EntityState(state, JsonObject(attrs.associate { (k, v) -> k to JsonPrimitive(v) }))

    @Test fun sceneIcon_known_andFallback() {
        assertEquals("⬛", sceneIcon("script.whole_house_off"))
        assertEquals("💤", sceneIcon("script.night_mode"))
        assertEquals("👥", sceneIcon("script.guest_mode"))
        assertEquals("🎬", sceneIcon("script.movie_mode"))
        assertEquals("▶", sceneIcon("script.other"))
    }

    @Test fun sceneName_friendlyElseObjectId() {
        assertEquals("Movie Time", sceneName("script.movie_mode", st("off", "friendly_name" to "Movie Time")))
        assertEquals("Movie mode", sceneName("script.movie_mode", null))
        assertEquals("Movie mode", sceneName("script.movie_mode", st("off")))
    }

    @Test fun lockStatus_allStates() {
        assertEquals(LockTileStatus.LOCKED, lockTileStatus(st("locked")))
        assertEquals(LockTileStatus.LOCKING, lockTileStatus(st("locking")))
        assertEquals(LockTileStatus.UNLOCKED, lockTileStatus(st("unlocked")))
        assertEquals(LockTileStatus.UNLOCKED, lockTileStatus(st("unlocking")))
        assertEquals(LockTileStatus.UNLOCKED, lockTileStatus(st("open")))
        assertEquals(LockTileStatus.UNLOCKED, lockTileStatus(st("opening")))
        assertEquals(LockTileStatus.JAMMED, lockTileStatus(st("jammed")))
        assertEquals(LockTileStatus.UNAVAILABLE, lockTileStatus(st("unavailable")))
        assertEquals(LockTileStatus.UNAVAILABLE, lockTileStatus(null))
    }

    @Test fun lockText_copy() {
        assertEquals("🔒 Front door · Locked", lockTileText("Front door", LockTileStatus.LOCKED))
        assertEquals("🔓 Front door · Unlocked", lockTileText("Front door", LockTileStatus.UNLOCKED))
        assertEquals("🔒 Front door · Locking…", lockTileText("Front door", LockTileStatus.LOCKING))
        assertEquals("⚠ Front door · Jammed", lockTileText("Front door", LockTileStatus.JAMMED))
        assertEquals("Front door · Unavailable", lockTileText("Front door", LockTileStatus.UNAVAILABLE))
    }

    @Test fun entityName_friendlyElseObjectId() {
        assertEquals("Doorbell", entityName("camera.front_yard", st("idle", "friendly_name" to "Doorbell")))
        assertEquals("Front yard", entityName("camera.front_yard", null))
        assertEquals("Front door", entityName("lock.front_door", st("locked")))
    }
}
