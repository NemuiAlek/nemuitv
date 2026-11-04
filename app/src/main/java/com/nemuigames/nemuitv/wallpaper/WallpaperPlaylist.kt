package com.nemuigames.nemuitv.wallpaper

import com.nemuigames.nemuitv.config.WallpaperOrder
import java.io.File
import kotlin.random.Random

/** The wallpaper videos in [dir]: *.mp4 (any case), ignoring hidden files and macOS "._" junk, sorted by name. */
fun listWallpapers(dir: File): List<File> =
    (dir.listFiles() ?: emptyArray())
        .filter { it.isFile && !it.name.startsWith(".") && it.extension.equals("mp4", ignoreCase = true) }
        .sortedBy { it.name }

/**
 * Decides which wallpaper plays next.
 * SEQUENTIAL loops through in order. SHUFFLE plays every video once per "cycle" in random order,
 * and never starts a new cycle with the video that just played. Broken files are skipped forever.
 * [startAt] (if it is one of [files]) starts on that video, e.g. to keep the current one playing when the order changes.
 */
class WallpaperPlaylist(
    files: List<String>,
    private val order: WallpaperOrder,
    private val random: Random = Random.Default,
    startAt: String? = null,
) {
    private val files = files.distinct()
    private val broken = mutableSetOf<String>()
    private var cycle: List<String> = newCycle(previous = null).let { c ->
        // Shuffle: put it first so the rest of the cycle is still random. In order: continue from it.
        if (startAt == null || startAt !in c || order == WallpaperOrder.SEQUENTIAL) c else listOf(startAt) + (c - startAt)
    }
    private var pos = if (startAt != null && order == WallpaperOrder.SEQUENTIAL) cycle.indexOf(startAt).coerceAtLeast(0) else 0
    private var cur: String? = cycle.getOrNull(pos)

    fun current(): String? = cur

    fun advance(): String? {
        if (files.none { it !in broken }) { cur = null; return null }
        while (true) {
            pos++
            if (pos >= cycle.size) { cycle = newCycle(previous = cur); pos = 0 }
            val candidate = cycle[pos]
            if (candidate !in broken) { cur = candidate; return cur }
        }
    }

    /** Remembers that [file] can't play; if it was the current one, moves on. Returns the (new) current file. */
    fun markBroken(file: String): String? {
        broken += file
        if (cur == file) advance()
        return cur
    }

    private fun newCycle(previous: String?): List<String> {
        val playable = files.filter { it !in broken }
        if (order == WallpaperOrder.SEQUENTIAL) return playable
        val shuffled = playable.shuffled(random).toMutableList()
        if (shuffled.size > 1 && shuffled[0] == previous) {
            val swapWith = 1 + random.nextInt(shuffled.size - 1)
            shuffled[0] = shuffled[swapWith].also { shuffled[swapWith] = shuffled[0] }
        }
        return shuffled
    }
}
