package com.nemuigames.nemuitv.controlcenter

import com.nemuigames.nemuitv.config.WifiConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WifiQrTest {
    @Test fun qr_wpa_basic() =
        assertEquals("WIFI:T:WPA;S:MyNetwork;P:pw;;", wifiQrText(WifiConfig("MyNetwork", "pw")))

    @Test fun qr_escapes_specials() =
        assertEquals("""WIFI:T:WPA;S:MyNetwork;P:a\;b\:c\,d\"e\\f;;""", wifiQrText(WifiConfig("MyNetwork", """a;b:c,d"e\f""")))

    @Test fun qr_escapes_ssid() =
        assertEquals("""WIFI:T:WPA;S:a\;b;P:pw;;""", wifiQrText(WifiConfig("a;b", "pw")))

    @Test fun qr_nopass_omitsP() =
        assertEquals("WIFI:T:nopass;S:Guest;;", wifiQrText(WifiConfig("Guest", "ignored", "nopass")))

    @Test fun qr_blankSsid_null() = assertNull(wifiQrText(WifiConfig("  ", "pw")))
}
