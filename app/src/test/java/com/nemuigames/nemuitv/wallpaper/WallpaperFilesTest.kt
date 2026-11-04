package com.nemuigames.nemuitv.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WallpaperFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `onlyRealMp4s caseInsensitive skipsHiddenAndMacJunk`() {
        listOf("a.mp4", "B.MP4", "c.txt", "d.jpg", "._a.mp4", ".hidden.mp4").forEach { File(tmp.root, it).writeText("x") }
        assertEquals(listOf("B.MP4", "a.mp4"), listWallpapers(tmp.root).map { it.name })
    }

    @Test fun missingDir_isEmpty() {
        assertEquals(emptyList<File>(), listWallpapers(File(tmp.root, "nope")))
    }
}
