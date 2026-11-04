package com.nemuigames.nemuitv.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class HaConfig(val url: String, val token: String, val weatherEntity: String = DEFAULT_WEATHER_ENTITY)

enum class WallpaperOrder { SHUFFLE, SEQUENTIAL }

data class WifiConfig(val ssid: String = "", val password: String = "", val security: String = "WPA")

data class TvInput(val name: String, val source: String)

/**
 * [thisDevice] = the TV source that is this box (HA's `source`, e.g. "Google OTT"): `tv.thisDevice` in config.json,
 * else the source of the input named "Streamer", else null (Home then never switches the TV's input).
 */
data class TvConfig(val entity: String, val inputs: List<TvInput>, val thisDevice: String? = null)

/** Lofi Girl on Spotify via HA: which Spotify media_player, which of its sources is this TV, the playlist URI, volume 0..1. */
data class LofiConfig(val spotifyEntity: String, val source: String, val playlist: String, val volume: Double = DEFAULT_LOFI_VOLUME)

/** Spotify Web API access for the now-playing player: PKCE client id + long-lived refresh token. */
data class SpotifyConfig(val clientId: String, val refreshToken: String)

const val DEFAULT_LOFI_VOLUME = 0.15

/**
 * Home hub tweaks: display-name overrides per person entity, car icon colors per car key, low-tire warning threshold (psi),
 * plus home-specific lists: HA area names to leave out of the hub ([excludedAreas]), person entity ids to hide from the
 * strip ([hiddenPeople]), and the order scene scripts appear in ([sceneOrder]; unlisted scenes follow, alphabetical).
 */
data class HubConfig(
    val peopleNames: Map<String, String> = emptyMap(),
    val carColors: Map<String, String> = emptyMap(),
    val lowTirePsi: Double = 38.0,
    val excludedAreas: List<String> = emptyList(),
    val hiddenPeople: List<String> = emptyList(),
    val sceneOrder: List<String> = emptyList(),
)

/** Alert banner text overrides, keyed by the event's `event_type`. */
data class AlertsConfig(val text: Map<String, String> = emptyMap())

data class NemuiConfig(
    val ha: HaConfig?,
    val wallpaperOrder: WallpaperOrder = WallpaperOrder.SHUFFLE,
    val wifi: WifiConfig = WifiConfig(),
    val tv: TvConfig? = null,
    val lofi: LofiConfig? = null,
    val hub: HubConfig = HubConfig(),
    val alerts: AlertsConfig = AlertsConfig(),
    val spotify: SpotifyConfig? = null,
)

sealed interface ConfigResult {
    /** [issues]: one human-readable line per bad field; each such field fell back to its default. */
    data class Ok(val config: NemuiConfig, val issues: List<String> = emptyList()) : ConfigResult
    data object Missing : ConfigResult
    data class Invalid(val reason: String) : ConfigResult
}

/** Car glyph colors the hub can draw; [DEFAULT_CAR_COLOR] is used for a car with no `hub.carColors` entry. */
val KNOWN_CAR_COLORS = setOf("white", "gray", "darkgray")
const val DEFAULT_CAR_COLOR = "gray"

const val DEFAULT_WEATHER_ENTITY = "weather.forecast_home"

// Raw shape of config.json. Everything optional here; validation happens in parseConfig.
/** The token placeholder in config.example.json. */
private const val EXAMPLE_TOKEN = "PASTE_LONG_LIVED_TOKEN"

@Serializable private data class RawConfig(val ha: RawHa? = null, val wallpaper: RawWallpaper? = null, val wifi: RawWifi? = null, val tv: RawTv? = null, val lofi: JsonElement? = null, val hub: RawHub? = null, val alerts: RawAlerts? = null, val spotify: JsonElement? = null)
@Serializable private data class RawHa(val url: String? = null, val token: String? = null, val weatherEntity: String? = null)
@Serializable private data class RawHub(val peopleNames: Map<String, String> = emptyMap(), val carColors: Map<String, String> = emptyMap(), val lowTirePsi: Double? = null,
    val excludedAreas: List<String> = emptyList(), val hiddenPeople: List<String> = emptyList(), val sceneOrder: List<String> = emptyList())
@Serializable private data class RawAlerts(val text: Map<String, String> = emptyMap())
@Serializable private data class RawWallpaper(val order: String? = null)
@Serializable private data class RawWifi(val ssid: String = "", val password: String = "", val security: String = "WPA")

@Serializable private data class RawTv(val entity: String? = null, val inputs: List<RawTvInput> = emptyList(), val thisDevice: String? = null)
@Serializable private data class RawTvInput(val name: String? = null, val source: String? = null)

private val json = Json { ignoreUnknownKeys = true }

/** Turns the text of config.json (null = no file) into a typed, validated config. */
fun parseConfig(text: String?): ConfigResult {
    if (text == null) return ConfigResult.Missing
    val raw = try {
        json.decodeFromString<RawConfig>(text)
    } catch (e: SerializationException) {
        return ConfigResult.Invalid("not valid JSON: ${e.message?.lineSequence()?.firstOrNull() ?: "parse error"}")
    } catch (e: IllegalArgumentException) {
        return ConfigResult.Invalid("not valid JSON: ${e.message ?: "parse error"}")
    }

    val issues = mutableListOf<String>()

    val ha = raw.ha?.let {
        val before = issues.size
        val url = it.url?.trim().orEmpty()
        val hasScheme = url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
        when {
            url.isEmpty() -> issues += "ha.url is missing"
            !hasScheme || url.toHttpUrlOrNull() == null -> issues += "ha.url isn't a URL (needs http:// or https://)"
        }
        when {
            it.token.isNullOrBlank() -> issues += "ha.token is missing"
            // Copied from config.example.json unchanged: HA would look set up but could never connect.
            it.token.trim() == EXAMPLE_TOKEN -> issues += "ha.token is still the example placeholder"
        }
        if (issues.size > before) null
        else HaConfig(url, it.token!!.trim(), it.weatherEntity?.takeIf(String::isNotBlank)?.trim() ?: DEFAULT_WEATHER_ENTITY)
    }

    val order = when (raw.wallpaper?.order?.trim()?.lowercase()) {
        null, "shuffle" -> WallpaperOrder.SHUFFLE
        "sequential" -> WallpaperOrder.SEQUENTIAL
        else -> { issues += "wallpaper.order must be shuffle or sequential"; WallpaperOrder.SHUFFLE }
    }

    val wifi = raw.wifi?.let { WifiConfig(it.ssid, it.password, it.security) } ?: WifiConfig()
    // A tv block without an entity is ignored; inputs without a source can't be switched to, so they are skipped.
    val tv = raw.tv?.entity?.trim()?.takeIf(String::isNotEmpty)?.let { entity ->
        val inputs = raw.tv.inputs.mapNotNull { i ->
            val source = i.source?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            TvInput(i.name?.trim()?.takeIf(String::isNotEmpty) ?: source, source)
        }
        val thisDevice = raw.tv.thisDevice?.trim()?.takeIf(String::isNotEmpty)
            ?: inputs.firstOrNull { it.name.equals("Streamer", ignoreCase = true) }?.source
        TvConfig(entity, inputs, thisDevice)
    }

    val hub = raw.hub?.let {
        val psi = it.lowTirePsi ?: HubConfig().lowTirePsi
        val goodPsi = if (psi <= 0.0 || psi.isNaN()) {
            issues += "hub.lowTirePsi must be more than 0"
            HubConfig().lowTirePsi
        } else psi
        val colors = LinkedHashMap<String, String>()
        it.carColors.forEach { (key, value) ->
            val color = value.trim().lowercase()
            if (color in KNOWN_CAR_COLORS) colors[key] = color else issues += "hub.carColors.$key: unknown color \"$value\""
        }
        HubConfig(it.peopleNames, colors, goodPsi, it.excludedAreas, it.hiddenPeople, it.sceneOrder)
    } ?: HubConfig()

    return ConfigResult.Ok(NemuiConfig(ha, order, wifi, tv, parseLofi(raw.lofi), hub, AlertsConfig(raw.alerts?.text ?: emptyMap()), parseSpotify(raw.spotify)), issues)
}

/** Lenient on purpose: a bad lofi value only turns lofi off (null) or falls back to the default volume, never invalidates the config. */
private fun parseLofi(el: JsonElement?): LofiConfig? {
    val obj = el as? JsonObject ?: return null
    fun text(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf(String::isNotEmpty)
    val volume = ((obj["volume"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: DEFAULT_LOFI_VOLUME).coerceIn(0.0, 1.0)
    return LofiConfig(text("spotifyEntity") ?: return null, text("source") ?: return null, text("playlist") ?: return null, volume)
}

/** Lenient like [parseLofi]: a non-object or blank field only turns Spotify off. */
private fun parseSpotify(el: JsonElement?): SpotifyConfig? {
    val obj = el as? JsonObject ?: return null
    fun text(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf(String::isNotEmpty)
    return SpotifyConfig(text("clientId") ?: return null, text("refreshToken") ?: return null)
}

/**
 * Which HA settings to use after (re)reading config.json. A broken file pushed mid-session keeps the last
 * working settings (the [⊜] panel shows "Config invalid"); a deleted file stops HA.
 */
fun effectiveHa(result: ConfigResult, previous: HaConfig?): HaConfig? = when (result) {
    is ConfigResult.Ok -> result.config.ha
    is ConfigResult.Invalid -> previous
    ConfigResult.Missing -> null
}
