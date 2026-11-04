package com.nemuigames.nemuitv.apps

import org.junit.Assert.assertEquals
import org.junit.Test

class AppInfoTest {
    private fun app(pkg: String, label: String, leanback: Boolean = false) =
        AppInfo(pkg, label, "$pkg.Main${if (leanback) "Tv" else ""}", leanback, isSystem = false)

    @Test fun merge_dedupesPreferLeanback() {
        val merged = mergeLaunchables(
            leanback = listOf(app("yt", "YouTube", leanback = true)),
            launcher = listOf(app("yt", "YouTube"), app("chrome", "Chrome")),
            selfPackage = "me",
        )
        assertEquals(listOf(app("chrome", "Chrome"), app("yt", "YouTube", leanback = true)), merged)
    }

    @Test fun merge_excludesSelf() {
        val merged = mergeLaunchables(listOf(app("me", "NemuiTV", true)), listOf(app("a", "A")), "me")
        assertEquals(listOf(app("a", "A")), merged)
    }

    @Test fun merge_sortsCaseInsensitive() {
        val merged = mergeLaunchables(emptyList(), listOf(app("b", "beta"), app("a", "Alpha"), app("c", "Charlie")), "me")
        assertEquals(listOf("Alpha", "beta", "Charlie"), merged.map { it.label })
    }

    private val apps = listOf(app("a", "Netflix"), app("b", "YouTube"), app("c", "YouTube Kids"))

    @Test fun filter_blankReturnsAll() {
        assertEquals(apps, filterApps(apps, "   "))
    }

    @Test fun filter_caseInsensitiveSubstring() {
        assertEquals(listOf("YouTube", "YouTube Kids"), filterApps(apps, "tUbE").map { it.label })
    }

    @Test fun filter_trimsWhitespace() {
        assertEquals(listOf("Netflix"), filterApps(apps, "  netf ").map { it.label })
    }
}
