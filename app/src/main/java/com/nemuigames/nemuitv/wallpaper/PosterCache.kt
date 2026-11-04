package com.nemuigames.nemuitv.wallpaper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File
import java.security.MessageDigest

/** Cache file name for a video's poster; changes whenever the video file is replaced. */
fun posterName(videoName: String, size: Long, lastModified: Long): String {
    val digest = MessageDigest.getInstance("SHA-1").digest("$videoName|$size|$lastModified".toByteArray())
    return digest.joinToString("") { "%02x".format(it) } + ".jpg"
}

/**
 * A still of each wallpaper's first frame, kept on disk. A 4K video takes ~1.4 s on the Streamer before its
 * first frame shows; the poster covers that gap so coming back Home never flashes black.
 */
class PosterCache(private val dir: File) {
    private fun posterFile(video: File) = File(dir, posterName(video.name, video.length(), video.lastModified()))

    fun has(video: File) = posterFile(video).exists()

    fun load(video: File): Bitmap? = posterFile(video).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    /** Grabs the first frame (that's where playback starts). Slow and uses the decoder: call off the main thread. */
    fun generate(video: File): Boolean {
        val out = posterFile(video)
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(video.path)
            val frame = r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1920, 1080) ?: return false
            dir.mkdirs()
            val tmp = File(dir, out.name + ".tmp")
            tmp.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            frame.recycle()
            tmp.renameTo(out)
        } catch (e: Exception) {
            Log.w("NemuiTV", "poster failed for ${video.name}: $e"); false
        } finally { r.release() }
    }

    /** Deletes posters of videos that are gone or were replaced. */
    fun prune(videos: List<File>) {
        val keep = videos.map { posterFile(it).name }.toSet()
        dir.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
    }
}
