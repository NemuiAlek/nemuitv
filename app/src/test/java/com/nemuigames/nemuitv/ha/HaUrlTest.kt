package com.nemuigames.nemuitv.ha

import org.junit.Assert.assertEquals
import org.junit.Test

class HaUrlTest {
    @Test fun http_toWs() = assertEquals("ws://homeassistant.local:8123/api/websocket", toWebSocketUrl("http://homeassistant.local:8123"))
    @Test fun https_toWss_trailingSlash() = assertEquals("wss://x.ui.nabu.casa/api/websocket", toWebSocketUrl("https://x.ui.nabu.casa/"))
    @Test fun whitespaceAndSlash() = assertEquals("ws://h:8123/api/websocket", toWebSocketUrl("  http://h:8123/  "))
    @Test fun mixedCaseScheme() = assertEquals("ws://h:8123/api/websocket", toWebSocketUrl("HTTP://h:8123"))
    @Test fun pagePathIsDropped() = assertEquals("ws://h:8123/api/websocket", toWebSocketUrl("http://h:8123/lovelace/0"))
}
