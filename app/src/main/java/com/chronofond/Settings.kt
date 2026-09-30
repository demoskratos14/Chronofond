package com.chronofond

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("settings")

enum class Target { HOME, LOCK }

data class ScreenConfig(
    val enabled: Boolean = false,
    val intervalMin: Int = 30,
    val layoutId: String = "1",     // disposition de la mosaïque (voir Layouts)
    val shuffle: Boolean = true,
    val singleCell: Boolean = false, // une seule case change à chaque rotation
    val gapPx: Int = 12,
    val topMarginPct: Int = 0,       // marge haute (horloge de l'écran de verrouillage)
    val uris: List<String> = emptyList(),
    val cursor: Int = 0,
    val cellPtr: Int = 0,
    val current: List<String> = emptyList(),
) {
    val layout: MosaicLayout get() = Layouts.byId(layoutId)
}

data class AppConfig(
    val home: ScreenConfig = ScreenConfig(),
    val lock: ScreenConfig = ScreenConfig(),
    val lockSameAsHome: Boolean = false,
)

private fun key(t: Target, n: String) = "${t.name.lowercase()}_$n"
private val LOCK_SAME = booleanPreferencesKey("lock_same")

private fun String?.asUris(): List<String> =
    this?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()

private fun Preferences.screen(t: Target) = ScreenConfig(
    enabled = this[booleanPreferencesKey(key(t, "enabled"))] ?: false,
    intervalMin = this[intPreferencesKey(key(t, "interval"))] ?: 30,
    layoutId = this[stringPreferencesKey(key(t, "layout"))]
        ?: Layouts.legacy(this[intPreferencesKey(key(t, "grid"))] ?: 1),
    shuffle = this[booleanPreferencesKey(key(t, "shuffle"))] ?: true,
    singleCell = this[booleanPreferencesKey(key(t, "single"))] ?: false,
    gapPx = this[intPreferencesKey(key(t, "gap"))] ?: 12,
    topMarginPct = this[intPreferencesKey(key(t, "margin"))] ?: 0,
    uris = this[stringPreferencesKey(key(t, "uris"))].asUris(),
    cursor = this[intPreferencesKey(key(t, "cursor"))] ?: 0,
    cellPtr = this[intPreferencesKey(key(t, "cellptr"))] ?: 0,
    current = this[stringPreferencesKey(key(t, "current"))].asUris(),
)

private fun MutablePreferences.put(t: Target, c: ScreenConfig) {
    this[booleanPreferencesKey(key(t, "enabled"))] = c.enabled
    this[intPreferencesKey(key(t, "interval"))] = c.intervalMin
    this[stringPreferencesKey(key(t, "layout"))] = c.layoutId
    this[booleanPreferencesKey(key(t, "shuffle"))] = c.shuffle
    this[booleanPreferencesKey(key(t, "single"))] = c.singleCell
    this[intPreferencesKey(key(t, "gap"))] = c.gapPx
    this[intPreferencesKey(key(t, "margin"))] = c.topMarginPct
    this[stringPreferencesKey(key(t, "uris"))] = c.uris.joinToString("\n")
    this[intPreferencesKey(key(t, "cursor"))] = c.cursor
    this[intPreferencesKey(key(t, "cellptr"))] = c.cellPtr
    this[stringPreferencesKey(key(t, "current"))] = c.current.joinToString("\n")
}

val Context.configFlow: Flow<AppConfig>
    get() = store.data.map {
        AppConfig(it.screen(Target.HOME), it.screen(Target.LOCK), it[LOCK_SAME] ?: false)
    }

suspend fun Context.updateScreen(t: Target, f: (ScreenConfig) -> ScreenConfig): ScreenConfig {
    var result = ScreenConfig()
    store.edit { p ->
        result = f(p.screen(t))
        p.put(t, result)
    }
    return result
}

suspend fun Context.setLockSame(v: Boolean) {
    store.edit { it[LOCK_SAME] = v }
}
