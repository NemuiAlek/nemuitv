package com.nemuigames.nemuitv.setup

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.setupDataStore by preferencesDataStore(name = "setup")

/** What the Setup page remembers on the TV: the checklist's "Done", the wallpaper hint, the screensaver we replaced. */
class SetupStore(context: Context) {
    private val store = context.applicationContext.setupDataStore
    private val doneKey = booleanPreferencesKey("checklist_done")
    private val hintKey = booleanPreferencesKey("wallpaper_hint_dismissed")
    private val prevKey = stringPreferencesKey("previous_screensaver")

    val checklistDone: Flow<Boolean> = store.data.map { it[doneKey] ?: false }
    val wallpaperHintDismissed: Flow<Boolean> = store.data.map { it[hintKey] ?: false }
    val previousScreensaver: Flow<String?> = store.data.map { it[prevKey] }

    suspend fun setChecklistDone(done: Boolean) { store.edit { it[doneKey] = done } }
    suspend fun setWallpaperHintDismissed(v: Boolean) { store.edit { it[hintKey] = v } }
    suspend fun setPreviousScreensaver(component: String?) {
        store.edit { if (component == null) it.remove(prevKey) else it[prevKey] = component }
    }
}
