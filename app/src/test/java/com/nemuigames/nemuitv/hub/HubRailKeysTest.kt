package com.nemuigames.nemuitv.hub

import org.junit.Assert.assertEquals
import org.junit.Test

class HubRailKeysTest {
    private val rooms = listOf("r:a", "r:b")

    @Test fun keysWithoutCar() = assertEquals(listOf(OVERVIEW, "r:a", "r:b"), railKeys(rooms, false))
    @Test fun keysWithCar() = assertEquals(listOf(OVERVIEW, TESLA, "r:a", "r:b"), railKeys(rooms, true))

    @Test fun indexWithoutCar() {
        assertEquals(0, railIndex(0, false))
        assertEquals(2, railIndex(1, false))
    }

    @Test fun indexWithCar() {
        assertEquals(1, railIndex(1, true))
        assertEquals(3, railIndex(2, true))
    }

    @Test fun staleSelectionFallsBack() = assertEquals(OVERVIEW, validSelection(TESLA, listOf(OVERVIEW, "r:a")))
    @Test fun validSelectionKept() = assertEquals("r:a", validSelection("r:a", listOf(OVERVIEW, "r:a")))
}
