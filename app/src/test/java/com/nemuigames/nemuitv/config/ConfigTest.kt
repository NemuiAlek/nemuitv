package com.nemuigames.nemuitv.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigTest {
    private val full = """
        {
          "ha": { "url": "http://homeassistant.local:8123", "token": "T", "weatherEntity": "weather.forecast_home" },
          "wallpaper": { "order": "shuffle" },
          "wifi": { "ssid": "Home", "password": "pw", "security": "WPA" }
        }
    """.trimIndent()

    @Test fun parse_full_ok() {
        val r = parseConfig(full) as ConfigResult.Ok
        assertEquals("http://homeassistant.local:8123", r.config.ha!!.url)
        assertEquals("T", r.config.ha!!.token)
        assertEquals("weather.forecast_home", r.config.ha!!.weatherEntity)
        assertEquals(WallpaperOrder.SHUFFLE, r.config.wallpaperOrder)
        assertEquals("Home", r.config.wifi.ssid)
    }

    // A token still set to config.example.json's placeholder would make HA look set up but never connect.
    @Test fun ha_placeholderToken_dropsHa_withIssue() {
        val r = parseConfig("""{ "ha": { "url": "http://homeassistant.local:8123", "token": " PASTE_LONG_LIVED_TOKEN " } }""") as ConfigResult.Ok
        assertNull(r.config.ha)
        assertEquals(listOf("ha.token is still the example placeholder"), r.issues)
    }

    private fun tv(body: String) = (parseConfig("""{ "tv": $body }""") as ConfigResult.Ok).config.tv

    @Test fun tv_parsed_withInputs() {
        val t = tv("""{ "entity": "media_player.tv", "inputs": [
            { "name": "HDMI 1", "source": "HDMI1" }, { "name": "Cable", "source": "HDMI2" } ] }""")!!
        assertEquals("media_player.tv", t.entity)
        assertEquals(listOf(TvInput("HDMI 1", "HDMI1"), TvInput("Cable", "HDMI2")), t.inputs)
    }

    @Test fun tv_missing_isNull() {
        assertEquals(null, (parseConfig(full) as ConfigResult.Ok).config.tv)
    }

    @Test fun tv_blankEntity_isNull() {
        assertEquals(null, tv("""{ "entity": "  ", "inputs": [] }"""))
    }

    @Test fun tv_inputWithBlankSource_skipped() {
        val t = tv("""{ "entity": "media_player.tv", "inputs": [
            { "name": "Bad", "source": " " }, { "name": "Good", "source": "HDMI1" } ] }""")!!
        assertEquals(listOf(TvInput("Good", "HDMI1")), t.inputs)
    }

    @Test fun tv_thisDevice_parsed() {
        val t = tv("""{ "entity": "media_player.tv", "thisDevice": " Google OTT ", "inputs": [
            { "name": "PS5", "source": "PS5 Game Console" } ] }""")!!
        assertEquals("Google OTT", t.thisDevice)
    }

    @Test fun tv_thisDevice_fallsBackToStreamerInput() {
        val t = tv("""{ "entity": "media_player.tv", "inputs": [
            { "name": "Streamer", "source": "Google OTT" }, { "name": "PS5", "source": "PS5" } ] }""")!!
        assertEquals("Google OTT", t.thisDevice)
    }

    @Test fun tv_thisDevice_noneWithoutStreamerInput() {
        val t = tv("""{ "entity": "media_player.tv", "thisDevice": "  ", "inputs": [ { "name": "PS5", "source": "PS5" } ] }""")!!
        assertEquals(null, t.thisDevice)
    }

    @Test fun parse_null_isMissing() {
        assertEquals(ConfigResult.Missing, parseConfig(null))
    }

    @Test fun parse_badJson_isInvalid() {
        val r = parseConfig("{not json")
        assertTrue(r is ConfigResult.Invalid)
        assertTrue((r as ConfigResult.Invalid).reason.isNotBlank())
    }

    @Test fun parse_orderSequential() {
        val r = parseConfig("""{"wallpaper":{"order":"sequential"}}""") as ConfigResult.Ok
        assertEquals(WallpaperOrder.SEQUENTIAL, r.config.wallpaperOrder)
    }

    @Test fun parse_unknownOrder_fallsBackWithIssue() {
        val r = parseConfig("""{"wallpaper":{"order":"random"}}""") as ConfigResult.Ok
        assertEquals(WallpaperOrder.SHUFFLE, r.config.wallpaperOrder)
        assertEquals(listOf("wallpaper.order must be shuffle or sequential"), r.issues)
    }

    @Test fun parse_noHaBlock_okWithNullHa() {
        assertEquals(ConfigResult.Ok(NemuiConfig(ha = null)), parseConfig("{}"))
    }

    @Test fun parse_haMissingToken_dropsHaWithIssue() {
        val r = parseConfig("""{"ha":{"url":"http://h:8123"}}""") as ConfigResult.Ok
        assertEquals(null, r.config.ha)
        assertEquals(listOf("ha.token is missing"), r.issues)
    }

    @Test fun parse_haMissingUrl_dropsHaWithIssue() {
        val r = parseConfig("""{"ha":{"token":"T"}}""") as ConfigResult.Ok
        assertEquals(null, r.config.ha)
        assertEquals(listOf("ha.url is missing"), r.issues)
    }

    @Test fun parse_cleanConfig_hasNoIssues() {
        assertEquals(emptyList<String>(), (parseConfig("""{"ha":{"url":"http://h:8123","token":"T"}}""") as ConfigResult.Ok).issues)
    }

    @Test fun parse_defaultWeatherEntity() {
        val r = parseConfig("""{"ha":{"url":"http://h:8123","token":"T"}}""") as ConfigResult.Ok
        assertEquals("weather.forecast_home", r.config.ha!!.weatherEntity)
    }

    @Test fun parse_unknownKeys_ignored() {
        val r = parseConfig("""{"future":{"x":1},"ha":{"url":"http://h:8123","token":"T","extra":true}}""")
        assertTrue(r is ConfigResult.Ok)
    }

    private val urlIssue = "ha.url isn't a URL (needs http:// or https://)"

    @Test fun parse_urlWithoutScheme_dropsHaKeepsRest() {
        val r = parseConfig("""{"ha":{"url":"homeassistant.local","token":"t"},"wifi":{"ssid":"Home"}}""") as ConfigResult.Ok
        assertEquals(null, r.config.ha)
        assertEquals("Home", r.config.wifi.ssid)
        assertEquals(listOf(urlIssue), r.issues)
        assertEquals(listOf(urlIssue), (parseConfig("""{"ha":{"url":"http//h:8123","token":"T"}}""") as ConfigResult.Ok).issues)
    }

    @Test fun effectiveHa_okWithHaIssue_isNull() =
        assertEquals(null, effectiveHa(parseConfig("""{"ha":{"url":"nope","token":"t"}}"""), previous = good))

    @Test fun parse_unknownCarColor_droppedWithIssue() {
        val r = parseConfig("""{"hub":{"carColors":{"my_car":"sparkly","second_car":"white"}}}""") as ConfigResult.Ok
        assertEquals(mapOf("second_car" to "white"), r.config.hub.carColors)
        assertEquals(listOf("""hub.carColors.my_car: unknown color "sparkly""""), r.issues)
    }

    private val good = HaConfig("http://a:8123", "T")

    @Test fun effectiveHa_okUsesNew() =
        assertEquals(HaConfig("http://b:8123", "T"), effectiveHa(ConfigResult.Ok(NemuiConfig(HaConfig("http://b:8123", "T"))), previous = good))

    @Test fun effectiveHa_invalidKeepsPrevious() =
        assertEquals(good, effectiveHa(ConfigResult.Invalid("oops"), previous = good))

    @Test fun effectiveHa_missingStops() =
        assertEquals(null, effectiveHa(ConfigResult.Missing, previous = good))

    private fun lofiCfg(body: String) = (parseConfig("""{ "lofi": $body }""") as ConfigResult.Ok).config.lofi
    private val lofiSpec = """{ "spotifyEntity": "media_player.spotify", "source": "Living Room TV",
        "playlist": "spotify:playlist:YOUR_PLAYLIST_ID", "volume": 0.15 }"""

    @Test fun lofi_parsed() {
        assertEquals(
            LofiConfig("media_player.spotify", "Living Room TV", "spotify:playlist:YOUR_PLAYLIST_ID", 0.15),
            lofiCfg(lofiSpec),
        )
    }

    @Test fun lofi_missing_isNull() {
        assertEquals(null, (parseConfig(full) as ConfigResult.Ok).config.lofi)
    }

    @Test fun lofi_blankPlaylist_isNull() {
        assertEquals(null, lofiCfg("""{ "spotifyEntity": "media_player.x", "source": "TV", "playlist": "  " }"""))
    }

    @Test fun lofi_volumeMissing_defaults015() {
        assertEquals(0.15, lofiCfg("""{ "spotifyEntity": "media_player.x", "source": "TV", "playlist": "p" }""")!!.volume, 0.0)
    }

    @Test fun lofi_volumeOutOfRange_clampedTo0to1() {
        fun vol(v: String) = lofiCfg("""{ "spotifyEntity": "e", "source": "s", "playlist": "p", "volume": $v }""")!!.volume
        assertEquals(1.0, vol("7"), 0.0)
        assertEquals(0.0, vol("-2"), 0.0)
    }

    @Test fun `lofi badType doesNotInvalidateWholeConfig`() {
        val r = parseConfig("""{ "ha": { "url": "http://h:8123", "token": "T" },
            "lofi": { "spotifyEntity": "e", "source": "s", "playlist": "p", "volume": "loud" } }""")
        val c = (r as ConfigResult.Ok).config
        assertEquals(0.15, c.lofi!!.volume, 0.0)
        assertEquals("T", c.ha!!.token)
        assertEquals(null, ((parseConfig("""{ "lofi": "nope" }""")) as ConfigResult.Ok).config.lofi)
        assertEquals(null, lofiCfg("""{ "spotifyEntity": 5, "source": "s", "playlist": "p" }"""))
    }

    @Test fun hub_missing_usesDefaults() {
        val h = (parseConfig("{}") as ConfigResult.Ok).config.hub
        assertEquals(38.0, h.lowTirePsi, 0.0)
        assertEquals(emptyMap<String, String>(), h.peopleNames)
        assertEquals(emptyMap<String, String>(), h.carColors)
    }

    @Test fun hub_parsed() {
        val h = (parseConfig("""{"hub":{"peopleNames":{"person.alex":"Alex"},"carColors":{"second_car":"white"},"lowTirePsi":40}}""") as ConfigResult.Ok).config.hub
        assertEquals(mapOf("person.alex" to "Alex"), h.peopleNames)
        assertEquals(mapOf("second_car" to "white"), h.carColors)
        assertEquals(40.0, h.lowTirePsi, 0.0)
    }

    @Test fun hub_homeSpecificLists_defaultEmpty() {
        val h = (parseConfig("{}") as ConfigResult.Ok).config.hub
        assertEquals(emptyList<String>(), h.excludedAreas)
        assertEquals(emptyList<String>(), h.hiddenPeople)
        assertEquals(emptyList<String>(), h.sceneOrder)
        val partial = (parseConfig("""{"hub":{"lowTirePsi":40}}""") as ConfigResult.Ok).config.hub
        assertEquals(emptyList<String>(), partial.excludedAreas)
    }

    @Test fun hub_homeSpecificLists_parsed() {
        val h = (parseConfig(
            """{"hub":{"excludedAreas":["Guest House"],"hiddenPeople":["person.nemuitv"],"sceneOrder":["script.movie_mode","script.night_mode"]}}""",
        ) as ConfigResult.Ok).config.hub
        assertEquals(listOf("Guest House"), h.excludedAreas)
        assertEquals(listOf("person.nemuitv"), h.hiddenPeople)
        assertEquals(listOf("script.movie_mode", "script.night_mode"), h.sceneOrder)
    }

    @Test fun hub_badPsi_defaultsWithIssue() {
        for (bad in listOf("0", "-1")) {
            val r = parseConfig("""{"hub":{"lowTirePsi":$bad}}""") as ConfigResult.Ok
            assertEquals(38.0, r.config.hub.lowTirePsi, 0.0)
            assertEquals(listOf("hub.lowTirePsi must be more than 0"), r.issues)
        }
    }

    @Test fun alerts_missing_defaults() {
        assertEquals(AlertsConfig(), (parseConfig("{}") as ConfigResult.Ok).config.alerts)
    }

    @Test fun alerts_textParsed() {
        val a = (parseConfig("""{"alerts":{"text":{"washing_is_complete":"Laundry's ready"}}}""") as ConfigResult.Ok).config.alerts
        assertEquals(mapOf("washing_is_complete" to "Laundry's ready"), a.text)
    }

    @Test fun spotify_parsed() {
        val c = (parseConfig("""{"ha":{"url":"http://h:8123","token":"t"},"spotify":{"clientId":"cid","refreshToken":"rt"}}""") as ConfigResult.Ok).config
        assertEquals(SpotifyConfig("cid", "rt"), c.spotify)
    }

    @Test fun `spotify missingOrBlank isNullAndConfigStillOk`() {
        for (s in listOf("", ""","spotify":{}""", ""","spotify":{"clientId":" ","refreshToken":"rt"}""", ""","spotify":"x"""")) {
            val r = parseConfig("""{"ha":{"url":"http://h:8123","token":"t"}$s}""")
            assertNull((r as ConfigResult.Ok).config.spotify)
        }
    }
}
