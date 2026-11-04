package com.nemuigames.nemuitv.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureListsTest {
    private val c = "com.x/com.x.takeover.Svc"
    private val s = "com.x/.takeover.Svc"

    @Test fun addToEmptyishLists() {
        assertEquals(c, SecureLists.add(null, c, s))
        assertEquals(c, SecureLists.add("null", c, s))
        assertEquals(c, SecureLists.add("", c, s))
        assertEquals(c, SecureLists.add("  ", c, s))
    }

    @Test fun addKeepsOthersAndSkipsDuplicates() {
        assertEquals("x/.A:$c", SecureLists.add("x/.A", c, s))
        assertEquals("x/.A:$s", SecureLists.add("x/.A:$s", c, s))
        assertEquals("x/.A:$c", SecureLists.add("x/.A:$c", c, s))
    }

    @Test fun removeDropsBothSpellingsKeepingOrder() {
        assertEquals("x/.A:y/.B", SecureLists.remove("x/.A:$c:y/.B", c, s))
        assertEquals("x/.A:y/.B", SecureLists.remove("x/.A:$s:y/.B:$c", c, s))
        assertEquals("", SecureLists.remove(s, c, s))
        assertEquals("", SecureLists.remove(null, c, s))
        assertEquals("", SecureLists.remove("null", c, s))
    }

    @Test fun containsEitherSpelling() {
        assertTrue(SecureLists.contains("x/.A:$s", c, s))
        assertTrue(SecureLists.contains(c, c, s))
        assertFalse(SecureLists.contains("x/.A", c, s))
        assertFalse(SecureLists.contains(null, c, s))
    }

    @Test fun addIsIdempotent() {
        listOf(null, "", "x/.A", "x/.A:$s").forEach {
            assertEquals(SecureLists.add(it, c, s), SecureLists.add(SecureLists.add(it, c, s), c, s))
        }
    }
}
