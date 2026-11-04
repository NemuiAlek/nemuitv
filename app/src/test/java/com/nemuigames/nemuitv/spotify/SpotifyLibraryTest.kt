package com.nemuigames.nemuitv.spotify

import com.nemuigames.nemuitv.config.SpotifyConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real IO threads + a scope job we join, so "in flight" windows are real and the assertions never race. */
class SpotifyLibraryTest {
    private class MemStore(var token: String? = null, var origin: String? = null) : TokenStore {
        override fun load() = token
        override fun origin() = origin
        override fun save(refreshToken: String, origin: String) { token = refreshToken; this.origin = origin }
    }

    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val store = MemStore()
    private var now = 0L
    private val tokenJson = """{"access_token":"a","expires_in":3600}"""
    private val playing = """{"item":{"uri":"spotify:track:1","name":"Test Song"}}"""

    /** Routes by "METHOD path"; [holds] maps a route to a latch the response waits on. */
    private class Router(val routes: MutableMap<String, () -> MockResponse>, val holds: Map<String, CountDownLatch> = emptyMap()) : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val key = "${request.method} ${request.requestUrl!!.encodedPath}"
            val response = routes[key]?.invoke() ?: MockResponse().setResponseCode(404) // decided on arrival, delivered when released
            holds[key]?.await(10, TimeUnit.SECONDS)
            return response
        }
    }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body)

    private fun route(vararg r: Pair<String, () -> MockResponse>, holds: Map<String, CountDownLatch> = emptyMap(), cfg: SpotifyConfig? = SpotifyConfig("cid", "rt1")): SpotifyLibrary {
        server.dispatcher = Router(mutableMapOf(*r), holds)
        server.start()
        return lib(cfg)
    }

    private var retryDelayMs = 5L

    private fun lib(cfg: SpotifyConfig?, config: () -> SpotifyConfig? = { cfg }) = SpotifyLibrary(
        config = config, store = store, httpClient = lazyOf(OkHttpClient()), scope = scope, io = Dispatchers.IO,
        accountsBase = server.url("/"), apiBase = server.url("/v1/"), clock = { now }, retryDelayMs = retryDelayMs,
    )

    private fun RecordedRequest.isNowPlaying() = path == "/v1/me/player/currently-playing"
    private fun drainCount(pred: (RecordedRequest) -> Boolean) =
        generateSequence { server.takeRequest(10, TimeUnit.MILLISECONDS) }.count(pred)

    private fun settle() = runBlocking { scope.coroutineContext.job.children.toList().forEach { it.join() } }

    private val tokenRoute = "POST /api/token" to { json(tokenJson) }
    private val nowRoute = "GET /v1/me/player/currently-playing" to { json(playing) }
    private fun containsRoute(liked: Boolean) = "GET /v1/me/library/contains" to { json("[$liked]") }

    @After fun tearDown() {
        scope.cancel()
        runCatching { server.shutdown() }
    }

    @Test fun lookup_liked() {
        val lib = route(tokenRoute, nowRoute, containsRoute(true))
        lib.lookup("com.spotify.tv.android", "Test Song"); settle()
        assertEquals(HeartUi(Heart.LIKED), lib.heart.value)
        val tok = server.takeRequest()
        val body = tok.body.readUtf8()
        assertTrue(body, "grant_type=refresh_token" in body && "client_id=cid" in body && "refresh_token=rt1" in body)
        assertFalse(body, "client_secret" in body)
        server.takeRequest()
        val contains = server.takeRequest()
        assertEquals("/v1/me/library/contains?uris=spotify%3Atrack%3A1", contains.path)
        assertEquals("Bearer a", contains.getHeader("Authorization"))
    }

    @Test fun notSpotify_hiddenNoCalls() {
        val lib = route(tokenRoute, nowRoute)
        lib.lookup("com.google.android.youtube.tvmusic", "x"); settle()
        assertEquals(Heart.HIDDEN, lib.heart.value.heart)
        assertEquals(0, server.requestCount)
    }

    @Test fun titleMismatch_retriedThenHidden() {
        val lib = route(tokenRoute, "GET /v1/me/player/currently-playing" to { json("""{"item":{"uri":"spotify:track:2","name":"Second Song"}}""") }, containsRoute(true))
        lib.lookup("com.spotify.tv.android", "Test Song"); settle()
        assertEquals(Heart.HIDDEN, lib.heart.value.heart)
        assertEquals(4, server.requestCount) // token once, then 3 currently-playing asks, no contains
        assertEquals(3, drainCount { it.isNowPlaying() })
    }

    @Test fun nothingPlaying204_retriedThenHidden() {
        val lib = route(tokenRoute, "GET /v1/me/player/currently-playing" to { MockResponse().setResponseCode(204) }, containsRoute(true))
        lib.lookup("com.spotify.tv.android", "Test Song"); settle()
        assertEquals(Heart.HIDDEN, lib.heart.value.heart)
        assertEquals(4, server.requestCount)
        assertEquals(3, drainCount { it.isNowPlaying() })
    }

    /** First ask still reports the previous track (Spotify lags the skip), second ask matches. */
    private fun laggingRoute(): Pair<String, () -> MockResponse> {
        var n = 0
        return "GET /v1/me/player/currently-playing" to {
            if (n++ == 0) json("""{"item":{"uri":"spotify:track:2","name":"Second Song"}}""") else json(playing)
        }
    }

    @Test fun titleMismatchThenMatch_liked() {
        val lib = route(tokenRoute, laggingRoute(), containsRoute(true))
        lib.lookup("com.spotify.tv.android", "Test Song"); settle()
        assertEquals(HeartUi(Heart.LIKED), lib.heart.value)
        assertEquals(2, drainCount { it.isNowPlaying() })
    }

    @Test fun nothingPlaying204ThenMatch_liked() {
        var n = 0
        val lib = route(tokenRoute, "GET /v1/me/player/currently-playing" to { if (n++ == 0) MockResponse().setResponseCode(204) else json(playing) }, containsRoute(false))
        lib.lookup("com.spotify.tv.android", "Test Song"); settle()
        assertEquals(HeartUi(Heart.NOT_LIKED), lib.heart.value)
    }

    @Test fun clearDuringRetryWait_cancelsRetry() {
        retryDelayMs = 300L
        val lib = route(tokenRoute, laggingRoute(), containsRoute(true))
        lib.lookup("com.spotify.tv.android", "Test Song")
        assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null) // token
        assertTrue(server.takeRequest(5, TimeUnit.SECONDS)!!.isNowPlaying()) // the mismatched ask; now waiting
        lib.clear(); settle()
        assertEquals(HeartUi(Heart.HIDDEN), lib.heart.value) // the retry would have matched → LIKED if it ran
        assertEquals(2, server.requestCount)
    }

    @Test fun newLookupDuringRetryWait_cancelsRetry() {
        retryDelayMs = 300L
        val lib = route(tokenRoute, laggingRoute(), containsRoute(true))
        lib.lookup("com.spotify.tv.android", "Test Song")
        assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)
        assertTrue(server.takeRequest(5, TimeUnit.SECONDS)!!.isNowPlaying())
        lib.lookup("com.google.android.youtube.tvmusic", "Test Song"); settle()
        assertEquals(HeartUi(Heart.HIDDEN), lib.heart.value)
        assertEquals(2, server.requestCount)
    }

    private fun likedLib(vararg extra: Pair<String, () -> MockResponse>, holds: Map<String, CountDownLatch> = emptyMap(), liked: Boolean = false): SpotifyLibrary {
        val lib = route(tokenRoute, nowRoute, containsRoute(liked), *extra, holds = holds)
        lib.lookup("com.spotify.tv.android", "Test Song"); settle()
        assertEquals(HeartUi(if (liked) Heart.LIKED else Heart.NOT_LIKED), lib.heart.value)
        repeat(3) { server.takeRequest() }
        return lib
    }

    @Test fun toggle_likes_optimisticThenConfirmed() {
        val hold = CountDownLatch(1)
        val lib = likedLib("PUT /v1/me/library" to { MockResponse().setResponseCode(200) }, holds = mapOf("PUT /v1/me/library" to hold))
        lib.toggle()
        assertEquals(HeartUi(Heart.LIKED, busy = true), lib.heart.value) // PUT is held, so this cannot race
        hold.countDown(); settle()
        assertEquals(HeartUi(Heart.LIKED), lib.heart.value)
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/v1/me/library?uris=spotify%3Atrack%3A1", put.path)
    }

    @Test fun toggle_failure_rollsBack() {
        val lib = likedLib("PUT /v1/me/library" to { MockResponse().setResponseCode(500) })
        lib.toggle(); settle()
        assertEquals(HeartUi(Heart.NOT_LIKED), lib.heart.value)
    }

    @Test fun toggle_whileBusy_ignored() {
        val hold = CountDownLatch(1)
        val lib = likedLib("PUT /v1/me/library" to { MockResponse().setResponseCode(200) }, holds = mapOf("PUT /v1/me/library" to hold))
        lib.toggle()
        assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null) // the PUT is in flight (held)
        lib.toggle()
        hold.countDown(); settle()
        assertEquals(HeartUi(Heart.LIKED), lib.heart.value)
        assertEquals(4, server.requestCount) // token, playing, contains, one PUT
    }

    @Test fun toggle_unlike_usesDelete() {
        val lib = likedLib("DELETE /v1/me/library" to { MockResponse().setResponseCode(200) }, liked = true)
        lib.toggle(); settle()
        assertEquals(HeartUi(Heart.NOT_LIKED), lib.heart.value)
        val del = server.takeRequest()
        assertEquals("DELETE", del.method)
        assertEquals("/v1/me/library?uris=spotify%3Atrack%3A1", del.path)
    }

    @Test fun rotatedRefreshToken_saved() {
        var n = 0
        val lib = route("POST /api/token" to { json("""{"access_token":"a","expires_in":3600,"refresh_token":"rt2"}""".also { n++ }) }, nowRoute, containsRoute(true))
        lib.lookup("com.spotify.tv.android", "Test Song"); settle()
        assertEquals("rt2", store.load())
        now = 4_000_000L // past expiry
        lib.lookup("com.spotify.tv.android", "Test Song"); settle()
        assertEquals(2, n)
        server.takeRequest(); repeat(2) { server.takeRequest() }
        assertTrue(server.takeRequest().body.readUtf8().contains("refresh_token=rt2"))
    }

    @Test fun accessTokenCached() {
        val lib = route(tokenRoute, nowRoute, containsRoute(true))
        lib.lookup("com.spotify.tv.android", "Test Song"); settle()
        now = 3_000_000L // 3000 s < 3540 s
        lib.lookup("com.spotify.tv.android", "Test Song"); settle()
        assertEquals(1, generateSequence { server.takeRequest(10, TimeUnit.MILLISECONDS) }.count { it.path == "/api/token" })
    }

    @Test fun refreshRefused_hiddenAndNoMoreCalls() {
        val lib = route("POST /api/token" to { json("""{"error":"invalid_grant"}""", 400) })
        lib.lookup("com.spotify.tv.android", "Test Song"); settle()
        assertEquals(Heart.HIDDEN, lib.heart.value.heart)
        assertEquals(1, server.requestCount)
        lib.lookup("com.spotify.tv.android", "Test Song"); settle()
        assertEquals(1, server.requestCount)
    }

    @Test fun refusedClearsWhenConfigTokenChanges() {
        var cfg = SpotifyConfig("cid", "rt1")
        var refuse = true
        server.dispatcher = Router(mutableMapOf("POST /api/token" to { if (refuse) json("{}", 400) else json(tokenJson) }, nowRoute, containsRoute(true)))
        server.start()
        val lib = lib(null) { cfg }
        lib.lookup("com.spotify.tv.android", "Test Song"); settle()
        refuse = false; cfg = SpotifyConfig("cid", "rt9")
        lib.lookup("com.spotify.tv.android", "Test Song"); settle()
        assertEquals(HeartUi(Heart.LIKED), lib.heart.value)
    }

    @Test fun `deadRotatedToken recoversWhenConfigTokenChanges`() {
        store.token = "rt2"; store.origin = "rt1"
        var cfg = SpotifyConfig("cid", "rt1")
        var refuse = true
        server.dispatcher = Router(mutableMapOf("POST /api/token" to { if (refuse) json("{}", 400) else json(tokenJson) }, nowRoute, containsRoute(true)))
        server.start()
        val lib = lib(null) { cfg }
        lib.lookup("com.spotify.tv.android", "Test Song"); settle()
        assertEquals(Heart.HIDDEN, lib.heart.value.heart)
        assertTrue(server.takeRequest().body.readUtf8().contains("refresh_token=rt2"))
        refuse = false; cfg = SpotifyConfig("cid", "rt9")
        lib.lookup("com.spotify.tv.android", "Test Song"); settle()
        assertEquals(HeartUi(Heart.LIKED), lib.heart.value)
        assertTrue(server.takeRequest().body.readUtf8().contains("refresh_token=rt9"))
    }

    @Test fun networkError_hidden() {
        val lib = route(tokenRoute, nowRoute, containsRoute(true))
        server.shutdown()
        lib.lookup("com.spotify.tv.android", "Test Song"); settle()
        assertEquals(Heart.HIDDEN, lib.heart.value.heart)
    }

    @Test fun noConfig_hidden() {
        val lib = route(tokenRoute, cfg = null)
        lib.lookup("com.spotify.tv.android", "Test Song"); settle()
        assertEquals(Heart.HIDDEN, lib.heart.value.heart)
        assertEquals(0, server.requestCount)
    }

    @Test fun staleLookup_ignoredAfterTrackChange() {
        val hold = CountDownLatch(1)
        var playingJson = playing
        var containsA = true
        val lib = route(
            tokenRoute,
            "GET /v1/me/player/currently-playing" to { json(playingJson) },
            "GET /v1/me/library/contains" to { json("[$containsA]") },
            holds = mapOf("GET /v1/me/library/contains" to hold),
        )
        lib.lookup("com.spotify.tv.android", "Test Song")
        repeat(3) { assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null) } // A's contains is held
        // B arrives while A is held; both are released together, A's (stale) answer must lose.
        playingJson = """{"item":{"uri":"spotify:track:3","name":"Other"}}"""
        containsA = false
        lib.lookup("com.spotify.tv.android", "Other")
        hold.countDown(); settle()
        assertEquals(HeartUi(Heart.NOT_LIKED), lib.heart.value)
    }

    @Test fun clear_hidesAndInvalidates() {
        val hold = CountDownLatch(1)
        val lib = route(tokenRoute, nowRoute, containsRoute(true), holds = mapOf("GET /v1/me/library/contains" to hold))
        lib.lookup("com.spotify.tv.android", "Test Song")
        repeat(3) { assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null) }
        lib.clear()
        hold.countDown(); settle()
        assertEquals(Heart.HIDDEN, lib.heart.value.heart)
    }
}
