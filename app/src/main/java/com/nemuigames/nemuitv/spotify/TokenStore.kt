package com.nemuigames.nemuitv.spotify

import android.content.Context

/**
 * Where a rotated Spotify refresh token lives between launches. It supersedes the config.json token only while
 * [origin] (the config token it was rotated from) still equals the config's current one.
 */
interface TokenStore {
    fun load(): String?
    fun origin(): String?
    fun save(refreshToken: String, origin: String)
}

class PrefsTokenStore(context: Context) : TokenStore {
    private val prefs = context.getSharedPreferences("spotify", Context.MODE_PRIVATE)
    override fun load(): String? = prefs.getString(KEY, null)
    override fun origin(): String? = prefs.getString(ORIGIN, null)
    override fun save(refreshToken: String, origin: String) {
        prefs.edit().putString(KEY, refreshToken).putString(ORIGIN, origin).commit() // always called on IO
    }

    private companion object {
        const val KEY = "refreshToken"
        const val ORIGIN = "origin"
    }
}
