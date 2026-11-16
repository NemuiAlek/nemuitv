package com.nemuigames.nemuitv.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed interface DownloadResult {
    data class Ok(val file: File) : DownloadResult
    /** Downloaded fine but the size or checksum is wrong; the file is already deleted. */
    data object Mismatch : DownloadResult
    /** Network or disk error; no file is left behind. */
    data object Failed : DownloadResult
}

/** GitHub release lookup and verified APK download. Plain JVM so it is unit-testable. */
class UpdateNet(
    http: OkHttpClient,
    private val baseUrl: String = "https://api.github.com",
) {
    // Slow TV Wi-Fi: allow a long gap between chunks instead of a short default.
    private val http = http.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()

    /** The latest release, or null on any failure (rate limit, offline, bad JSON). */
    suspend fun latest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$baseUrl/repos/NemuiAlek/nemuitv/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else parseLatestRelease(resp.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Downloads the APK into [dir] (emptied first) and returns Ok only if both the byte
     * count and the SHA-256 match what the release promised.
     */
    suspend fun download(r: ReleaseInfo, dir: File, onProgress: (Int) -> Unit): DownloadResult =
        withContext(Dispatchers.IO) {
            var file: File? = null
            try {
                dir.mkdirs()
                dir.listFiles()?.forEach { it.deleteRecursively() }
                val target = File(dir, "NemuiTV-${r.versionText}.apk")
                file = target
                val digest = MessageDigest.getInstance("SHA-256")
                var written = 0L
                http.newCall(Request.Builder().url(r.apkUrl).build()).execute().use { resp ->
                    val body = resp.body
                    if (!resp.isSuccessful || body == null) return@withContext fail(target)
                    var lastPercent = -1
                    body.byteStream().use { input ->
                        target.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                digest.update(buf, 0, n)
                                written += n
                                val pct = if (r.apkSize > 0) (written * 100 / r.apkSize).toInt().coerceIn(0, 100) else 0
                                if (pct != lastPercent) { lastPercent = pct; onProgress(pct) }
                            }
                        }
                    }
                }
                if (written != r.apkSize) return@withContext mismatch(target)
                val shaText = http.newCall(Request.Builder().url(r.shaUrl).build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext fail(target)
                    resp.body?.string().orEmpty()
                }
                val hex = digest.digest().joinToString("") { "%02x".format(it) }
                if (sha256Matches(hex, shaText)) DownloadResult.Ok(target) else mismatch(target)
            } catch (e: Exception) {
                file?.delete()
                DownloadResult.Failed
            }
        }

    private fun fail(f: File): DownloadResult { f.delete(); return DownloadResult.Failed }
    private fun mismatch(f: File): DownloadResult { f.delete(); return DownloadResult.Mismatch }
}
