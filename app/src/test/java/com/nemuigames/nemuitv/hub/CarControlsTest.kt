package com.nemuigames.nemuitv.hub

import com.nemuigames.nemuitv.ha.HaError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarControlsTest {
    private fun err(message: String) = HaError("home_assistant_error", message)

    @Test fun `noKeyError recordedTeslaMessage`() {
        assertTrue(isNoKeyError(err(
            "Command failed: Vehicle did not recognize the key used to authorize command. Make sure your key is paired with the vehicle"
        )))
    }

    @Test fun `noKeyError caseInsensitiveAndEachPhrase`() {
        assertTrue(isNoKeyError(err("VEHICLE DID NOT RECOGNIZE THE KEY")))
        assertTrue(isNoKeyError(err("make sure your Key Is Paired")))
        assertTrue(isNoKeyError(err("not Paired With The Vehicle")))
    }

    @Test fun `noKeyError otherErrorsFailOpen`() {
        assertFalse(isNoKeyError(err("Command failed: vehicle unavailable")))
        assertFalse(isNoKeyError(err("Timeout")))
        assertFalse(isNoKeyError(err("")))
    }

    @Test fun `nextCopMode onGoesOff offGoesCool`() {
        assertEquals("off", nextCopMode("cool"))
        assertEquals("off", nextCopMode("fan_only"))
        assertEquals("cool", nextCopMode("off"))
        assertEquals("cool", nextCopMode(null))
    }
}
