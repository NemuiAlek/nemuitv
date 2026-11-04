package com.nemuigames.nemuitv.ambient

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DreamOwnershipTest {
    private val pkg = "com.nemuigames.nemuitv"

    @Test fun ours_fullAndShortSpelling() {
        assertTrue(isOurDream("$pkg/$pkg.ambient.AmbientDream", pkg))
        assertTrue(isOurDream("$pkg/.ambient.AmbientDream", pkg))
    }

    // An older build with another package also has a .ambient.AmbientDream: that one is not ours.
    @Test fun otherPackageSameClass_notOurs() {
        assertFalse(isOurDream("com.example.oldtv/.ambient.AmbientDream", pkg))
        assertFalse(isOurDream("com.example.oldtv/com.example.oldtv.ambient.AmbientDream", pkg))
    }

    @Test fun unset_notOurs() {
        assertFalse(isOurDream(null, pkg))
        assertFalse(isOurDream("null", pkg))
        assertFalse(isOurDream("", pkg))
    }

    @Test fun oursInAList() {
        assertTrue(isOurDream("a.b/.Other:$pkg/.ambient.AmbientDream", pkg))
        assertTrue(isOurDream("a.b/.Other,$pkg/.ambient.AmbientDream", pkg))
        assertFalse(isOurDream("a.b/.Other:com.example.oldtv/.ambient.AmbientDream", pkg))
    }

    @Test fun otherClassInOurPackage_notOurs() {
        assertFalse(isOurDream("$pkg/.ambient.SomethingElse", pkg))
    }
}
