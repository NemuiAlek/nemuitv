package com.nemuigames.nemuitv.favorites

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
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

private val orderKey = stringPreferencesKey("order")
private val hiddenKey = stringPreferencesKey("hidden")
private val homePendingKey = booleanPreferencesKey("home_pending") // see AppLayout.homePending; absent = false
private val dockSixKey = booleanPreferencesKey("dock_six")         // see AppLayout.dockSix; absent (old save) = false
private val legacyKey = stringPreferencesKey("favorites")

private fun String.toList() = split(',').filter(String::isNotBlank)

internal fun Preferences.toStoredLayout() = StoredLayout(
    layout = this[orderKey]?.toList()?.let {
        AppLayout(it, this[hiddenKey]?.toList().orEmpty().toSet(), this[homePendingKey] == true, this[dockSixKey] == true)
    },
    legacyFavorites = this[legacyKey]?.toList(),
)

internal fun MutablePreferences.writeLayout(layout: AppLayout) {
    this[orderKey] = layout.order.joinToString(",")
    this[hiddenKey] = layout.hidden.joinToString(",")
    if (layout.homePending) this[homePendingKey] = true else remove(homePendingKey)
    if (layout.dockSix) this[dockSixKey] = true else remove(dockSixKey)
}

/** Saves the app order + hidden set. Same DataStore as the old favorites, so the upgrade can read them. */
class AppLayoutStore(context: Context) {
    private val store = context.applicationContext.favoritesDataStore
    val layout: Flow<StoredLayout> = store.data.map { it.toStoredLayout() }

    suspend fun save(layout: AppLayout) {
        store.edit { it.writeLayout(layout) }
    }
}
