package com.nemuigames.nemuitv.update

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class UpdateNetTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val net by lazy { UpdateNet(OkHttpClient(), server.url("/").toString().trimEnd('/')) }
    private val apk = ByteArray(10_000) { (it % 251).toByte() }
    private val apkSha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }

    @After fun stop() { server.shutdown() }

    private fun releaseJson() = """{"tag_name":"v1.2.0","body":"notes","assets":[
        {"name":"NemuiTV-1.2.0.apk","size":${apk.size},"browser_download_url":"${server.url("/apk")}"},
        {"name":"NemuiTV-1.2.0.apk.sha256","size":65,"browser_download_url":"${server.url("/sha")}"}]}"""

    private fun info(size: Long = apk.size.toLong()) = ReleaseInfo(
        Version(1, 2, 0), "1.2.0", "", server.url("/apk").toString(), size, server.url("/sha").toString(),
    )

    private fun serve(sha: String = apkSha) {
        server.enqueue(MockResponse().setBody(Buffer().write(apk)))
        server.enqueue(MockResponse().setBody("$sha  NemuiTV-1.2.0.apk\n"))
    }

    @Test fun latestParsesOk() {
        server.enqueue(MockResponse().setBody(releaseJson()))
        val r = runBlocking { net.latest() }
        assertNotNull(r)
        assertEquals("1.2.0", r!!.versionText)
        val req = server.takeRequest()
        assertEquals("/repos/NemuiAlek/nemuitv/releases/latest", req.path)
        assertEquals("application/vnd.github+json", req.getHeader("Accept"))
    }

    @Test fun latestRateLimitedIsNull() {
        server.enqueue(MockResponse().setResponseCode(403))
        assertNull(runBlocking { net.latest() })
    }

    @Test fun latestServerErrorIsNull() {
        server.enqueue(MockResponse().setResponseCode(500))
        assertNull(runBlocking { net.latest() })
    }

    @Test fun downloadVerifies() {
        serve()
        val dir = tmp.newFolder("dl")
        val progress = mutableListOf<Int>()
        val res = runBlocking { net.download(info(), dir) { progress += it } }
        assertTrue(res is DownloadResult.Ok)
        val file = (res as DownloadResult.Ok).file
        assertEquals("NemuiTV-1.2.0.apk", file.name)
        assertTrue(file.readBytes().contentEquals(apk))
        assertEquals(100, progress.last())
    }

    @Test fun downloadClearsOldFilesFirst() {
        serve()
        val dir = tmp.newFolder("dl")
        val stale = dir.resolve("old.apk").apply { writeText("x") }
        runBlocking { net.download(info(), dir) { } }
        assertTrue(!stale.exists())
    }

    @Test fun downloadWrongShaIsMismatchAndDeletes() {
        serve(sha = "0".repeat(64))
        val dir = tmp.newFolder("dl")
        assertEquals(DownloadResult.Mismatch, runBlocking { net.download(info(), dir) { } })
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test fun downloadWrongSizeIsMismatch() {
        serve()
        val dir = tmp.newFolder("dl")
        assertEquals(DownloadResult.Mismatch, runBlocking { net.download(info(size = 9_999), dir) { } })
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test fun downloadDropIsFailed() {
        server.enqueue(
            MockResponse().setBody(Buffer().write(apk))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        val dir = tmp.newFolder("dl")
        assertEquals(DownloadResult.Failed, runBlocking { net.download(info(), dir) { } })
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }
}
