package com.nemuigames.nemuitv.favorites

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.favoritesDataStore by preferencesDataStore(name = "favorites")

/**
 * What is in storage: the new layout (null = never saved) and the old dock-only favorites list
 * (null = never saved), which is only read, to upgrade from.
 */
data class StoredLayout(val layout: AppLayout?, val legacyFavorites: List<String>?)

/** Saves the app order + hidden set. Same DataStore as the old favorites, so the upgrade can read them. */
class AppLayoutStore(context: Context) {
    private val store = context.applicationContext.favoritesDataStore
    private val legacyKey = stringPreferencesKey("favorites")
    private val orderKey = stringPreferencesKey("order")
    private val hiddenKey = stringPreferencesKey("hidden")
    private val homePendingKey = booleanPreferencesKey("home_pending") // see AppLayout.homePending; absent = false

    private fun String.toList() = split(',').filter(String::isNotBlank)

    val layout: Flow<StoredLayout> = store.data.map { prefs ->
        val order = prefs[orderKey]?.toList()
        StoredLayout(
            layout = order?.let { AppLayout(it, prefs[hiddenKey]?.toList().orEmpty().toSet(), prefs[homePendingKey] == true) },
            legacyFavorites = prefs[legacyKey]?.toList(),
        )
    }

    suspend fun save(layout: AppLayout) {
        store.edit {
            it[orderKey] = layout.order.joinToString(",")
            it[hiddenKey] = layout.hidden.joinToString(",")
            if (layout.homePending) it[homePendingKey] = true else it.remove(homePendingKey)
        }
    }
}
