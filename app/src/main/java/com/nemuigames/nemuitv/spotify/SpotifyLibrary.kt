package com.nemuigames.nemuitv.spotify

import android.os.SystemClock
import android.util.Log
import com.nemuigames.nemuitv.config.SpotifyConfig
import com.nemuigames.nemuitv.nowplaying.titleMatches
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

val SPOTIFY_PACKAGES = setOf("com.spotify.tv.android", "com.spotify.music")

enum class Heart { HIDDEN, LIKED, NOT_LIKED }

data class HeartUi(val heart: Heart, val busy: Boolean = false)

private const val TAG = "NemuiTV"
/** Extra currently-playing asks after a title mismatch / 204 (so up to 3 in all). */
private const val LOOKUP_RETRIES = 2

/**
 * The Control Center's heart: is the track Spotify is playing saved in the user's library, and a toggle for it.
 * Talks to the Spotify Web API with a PKCE refresh token (no client secret). Any trouble -> the heart hides.
 * Never logs tokens.
 */
class SpotifyLibrary(
    private val config: () -> SpotifyConfig?,
    private val store: TokenStore,
    httpClient: Lazy<OkHttpClient>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val accountsBase: HttpUrl = "https://accounts.spotify.com/".toHttpUrl(),
    private val apiBase: HttpUrl = "https://api.spotify.com/v1/".toHttpUrl(),
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    /** Wait before re-asking currently-playing when Spotify still reports the previous track (or nothing). */
    private val retryDelayMs: Long = 1500L,
) {
    /** Built on first use (on [io]), not at app start on Main. */
    private val http: OkHttpClient by httpClient

    private val _heart = MutableStateFlow(HeartUi(Heart.HIDDEN))
    val heart: StateFlow<HeartUi> = _heart.asStateFlow()

    @Volatile private var generation = 0
    @Volatile private var trackUri: String? = null

    // Token state, touched only under [tokenLock] (inside io work).
    private val tokenLock = Mutex()
    private var accessToken: String? = null
    private var expiresAtMs = 0L
    private var refusedToken: String? = null

    private open class Failure(val reason: String) : Exception(reason)
    /** Spotify's currently-playing lags the session after a skip (~0.5-2 s): worth asking again. */
    private class Lagging(reason: String) : Failure(reason)

    fun lookup(pkg: String, title: String?) {
        val gen = ++generation
        trackUri = null
        _heart.value = HeartUi(Heart.HIDDEN)
        if (pkg !in SPOTIFY_PACKAGES) return
        if (config() == null) { hidden("no config"); return }
        scope.launch {
            var attempt = 0
            while (true) {
                val result = try {
                    withContext(io) { fetchLiked(title) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Lagging) {
                    if (gen != generation) return@launch
                    if (attempt++ < LOOKUP_RETRIES) {
                        delay(retryDelayMs) // suspends on [scope]; no IO thread is held while waiting
                        if (gen != generation) return@launch // a newer lookup / clear owns the state now
                        continue
                    }
                    hidden(e.reason)
                    return@launch
                } catch (e: Failure) {
                    if (gen == generation) hidden(e.reason)
                    return@launch
                } catch (e: Exception) {
                    if (gen == generation) hidden("error ${e.javaClass.simpleName}")
                    return@launch
                }
                if (gen != generation) return@launch
                trackUri = result.first
                Log.i(TAG, "spotify: liked=${result.second}")
                _heart.value = HeartUi(if (result.second) Heart.LIKED else Heart.NOT_LIKED)
                return@launch
            }
        }
    }

    fun clear() {
        generation++
        trackUri = null
        _heart.value = HeartUi(Heart.HIDDEN)
    }

    fun toggle() {
        val cur = _heart.value
        val uri = trackUri
        if (cur.busy || cur.heart == Heart.HIDDEN || uri == null) return
        val gen = generation
        val wasLiked = cur.heart == Heart.LIKED
        _heart.value = HeartUi(if (wasLiked) Heart.NOT_LIKED else Heart.LIKED, busy = true)
        scope.launch {
            val code = try {
                withContext(io) { writeLiked(uri, !wasLiked) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                -1
            }
            if (gen != generation) return@launch // the track moved on; the new lookup owns the state
            if (code in 200..299) {
                _heart.value = HeartUi(if (wasLiked) Heart.NOT_LIKED else Heart.LIKED)
            } else {
                Log.w(TAG, "spotify: like/unlike failed ($code)")
                _heart.value = HeartUi(if (wasLiked) Heart.LIKED else Heart.NOT_LIKED)
            }
        }
    }

    private fun hidden(reason: String) {
        Log.i(TAG, "spotify: ♥ hidden ($reason)")
        _heart.value = HeartUi(Heart.HIDDEN)
    }

    // ---- everything below runs on [io] ----

    private suspend fun fetchLiked(sessionTitle: String?): Pair<String, Boolean> {
        val token = accessToken()
        val playing = api(token, "GET", apiBase.newBuilder().addPathSegments("me/player/currently-playing").build())
        if (playing.code == 204) throw Lagging("nothing playing")
        if (playing.code != 200) throw Failure("currently-playing ${playing.code}")
        val item = (parse(playing.body) as? JsonObject)?.get("item") as? JsonObject ?: throw Failure("no item")
        val uri = item["uri"]?.jsonPrimitive?.contentOrNull ?: throw Failure("no uri")
        if (!uri.startsWith("spotify:track:")) throw Failure("not a track")
        if (!titleMatches(sessionTitle, item["name"]?.jsonPrimitive?.contentOrNull)) throw Lagging("title mismatch")
        val url = apiBase.newBuilder().addPathSegments("me/library/contains").addQueryParameter("uris", uri).build()
        val contains = api(token, "GET", url)
        if (contains.code != 200) throw Failure("contains ${contains.code}")
        val liked = (parse(contains.body) as? JsonArray)?.firstOrNull()?.jsonPrimitive?.booleanOrNull ?: throw Failure("bad contains")
        return uri to liked
    }

    private suspend fun writeLiked(uri: String, like: Boolean): Int {
        val token = accessToken()
        val url = apiBase.newBuilder().addPathSegments("me/library").addQueryParameter("uris", uri).build()
        return api(token, if (like) "PUT" else "DELETE", url).code
    }

    private class Reply(val code: Int, val body: String)

    private suspend fun api(token: String, method: String, url: HttpUrl): Reply {
        val b = Request.Builder().url(url).header("Authorization", "Bearer $token")
        when (method) {
            "PUT" -> b.put(ByteArray(0).toRequestBody())
            "DELETE" -> b.delete()
        }
        http.newCall(b.build()).execute().use { r ->
            if (r.code == 401) forgetAccessToken(token)
            return Reply(r.code, r.body.string())
        }
    }

    /** Drops the cached token, but only if it is the one that got the 401 (a newer one may already be in place). */
    private suspend fun forgetAccessToken(rejected: String) = tokenLock.withLock {
        if (accessToken == rejected) { accessToken = null; expiresAtMs = 0L }
    }

    private suspend fun accessToken(): String = tokenLock.withLock {
        val cfg = config() ?: throw Failure("no config")
        // The rotated token only counts while it derives from the token currently in config.json.
        val refresh = store.load()?.takeIf { store.origin() == cfg.refreshToken } ?: cfg.refreshToken
        if (refusedToken != null && refusedToken != refresh) refusedToken = null
        if (refusedToken != null) throw Failure("refresh refused")
        accessToken?.let { if (clock() < expiresAtMs) return@withLock it }
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token").add("refresh_token", refresh).add("client_id", cfg.clientId).build()
        http.newCall(Request.Builder().url(accountsBase.newBuilder().addPathSegments("api/token").build()).post(form).build())
            .execute().use { r ->
                val text = r.body.string()
                if (r.code == 400 || r.code == 401) {
                    refusedToken = refresh
                    throw Failure("refresh refused (${r.code})")
                }
                if (r.code != 200) throw Failure("token ${r.code}")
                val obj = parse(text) as? JsonObject ?: throw Failure("bad token reply")
                val access = obj["access_token"]?.jsonPrimitive?.contentOrNull ?: throw Failure("bad token reply")
                val ttl = obj["expires_in"]?.jsonPrimitive?.intOrNull ?: 3600
                obj["refresh_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != refresh }?.let { store.save(it, cfg.refreshToken) }
                accessToken = access
                expiresAtMs = clock() + (ttl - 60) * 1000L
                access
            }
    }

    private fun parse(text: String) = Json.parseToJsonElement(text)
}
