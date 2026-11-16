package com.nemuigames.nemuitv.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateRulesTest {

    /** Builds GitHub-shaped latest-release JSON; every APK is 123456 bytes. */
    private fun release(
        tag: String,
        strayApk: Boolean = false,
        zipFirst: Boolean = false,
        withSha: Boolean = true,
    ): String {
        val ver = tag.removePrefix("v")
        fun asset(name: String, size: Long = 123_456L) =
            """{"name":"$name","size":$size,"browser_download_url":"https://example.com/dl/$name"}"""
        val assets = buildList {
            if (zipFirst) add(asset("NemuiTV-source.zip", 999L))
            if (strayApk) add(asset("NemuiTV-1.1.0.apk", 111L))
            if (withSha) add(asset("NemuiTV-$ver.apk.sha256", 65L))
            add(asset("NemuiTV-$ver.apk"))
        }
        return """{"tag_name":"$tag","name":"NemuiTV $ver","body":"## What's new\n- **Six** tiles","assets":[${assets.joinToString(",")}]}"""
    }

    @Test fun versionsCompareNumerically() {
        assertTrue(parseVersion("1.10.0")!! > parseVersion("v1.9.9")!!)
        assertEquals(Version(1, 2, 0), parseVersion("1.2.0-debug"))
        assertNull(parseVersion("latest"))
    }

    @Test fun newerOnlyWhenStrictlyNewer() {
        val r = parseLatestRelease(release("v1.2.1"))!!
        assertEquals(r, newerRelease("1.2.0", r))
        assertEquals(r, newerRelease("1.2.0-debug", r))
        assertNull(newerRelease("1.2.1", r)); assertNull(newerRelease("1.3.0", r)); assertNull(newerRelease("?", r))
        assertNull(newerRelease("1.2.0", null))
    }

    @Test fun picksTheTagsApkAmongOtherAssets() {
        val r = parseLatestRelease(release("v1.2.1", strayApk = true, zipFirst = true))!!
        assertTrue(r.apkUrl.endsWith("/NemuiTV-1.2.1.apk")); assertEquals(123_456L, r.apkSize)
        assertTrue(r.shaUrl.endsWith("/NemuiTV-1.2.1.apk.sha256"))
        assertEquals("1.2.1", r.versionText)
        assertEquals(Version(1, 2, 1), r.version)
    }

    @Test fun missingShaOrBadJsonIsNoUpdate() {
        assertNull(parseLatestRelease(release("v1.2.1", withSha = false)))
        assertNull(parseLatestRelease("{not json")); assertNull(parseLatestRelease(release("nightly")))
    }

    @Test fun shaFileFormats() {
        val hex = "ab".repeat(32)
        assertTrue(sha256Matches(hex, "${hex.uppercase()}  NemuiTV-1.2.1.apk\n"))
        assertFalse(sha256Matches(hex, "cd".repeat(32)))
        assertFalse(sha256Matches(hex, ""))
    }

    @Test fun markdownStripped() {
        assertEquals(
            "What's new\n• Six tiles\n• See docs",
            stripMarkdown("## What's new\n- **Six** tiles\n* See [docs](https://x)"),
        )
        assertEquals("a\n\nb", stripMarkdown("a\n\n\n\n\nb"))
    }

    @Test fun scheduleGate() {
        assertTrue(UpdateSchedule.due(0, null))
        assertFalse(UpdateSchedule.due(UpdateSchedule.INTERVAL_MS - 1, 0))
        assertTrue(UpdateSchedule.due(UpdateSchedule.INTERVAL_MS, 0))
    }
}
