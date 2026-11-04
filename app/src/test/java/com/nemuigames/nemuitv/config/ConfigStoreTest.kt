package com.nemuigames.nemuitv.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ConfigStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun cfg(url: String) = """{"ha":{"url":"$url","token":"T"}}"""

    @Test fun noFile_isMissing() {
        val store = ConfigStore(tmp.root)
        store.loadIfChanged()
        assertEquals(ConfigResult.Missing, store.state.value)
    }

    @Test fun loadIfChanged_detectsModifiedFile() {
        val f = File(tmp.root, "config.json").apply { writeText(cfg("http://a:8123")) }
        val store = ConfigStore(tmp.root)
        assertTrue(store.loadIfChanged())
        assertEquals("http://a:8123", (store.state.value as ConfigResult.Ok).config.ha!!.url)
        assertFalse(store.loadIfChanged())

        f.writeText(cfg("http://b:8123"))
        f.setLastModified(f.lastModified() + 5_000)
        assertTrue(store.loadIfChanged())
        assertEquals("http://b:8123", (store.state.value as ConfigResult.Ok).config.ha!!.url)
    }

    @Test fun fileDeleted_becomesMissing() {
        val f = File(tmp.root, "config.json").apply { writeText(cfg("http://a:8123")) }
        val store = ConfigStore(tmp.root)
        store.loadIfChanged()
        f.delete()
        assertTrue(store.loadIfChanged())
        assertEquals(ConfigResult.Missing, store.state.value)
    }
}
