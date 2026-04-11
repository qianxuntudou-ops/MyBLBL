package com.tutu.myblbl.core.common.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

private val Context.appDataStore by preferencesDataStore(name = "app_settings")

class AppSettingsDataStore(private val context: Context) {

    private val dataStore: DataStore<Preferences> get() = context.appDataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = ConcurrentHashMap<String, Any?>()

    val isCacheReady: Boolean get() = cache.isNotEmpty()

    fun initCache() {
        runBlocking {
            val prefs = dataStore.data.first()
            prefs.asMap().forEach { (key, value) ->
                cache[key.name] = value
            }
        }
    }

    fun getCachedString(key: String, defaultValue: String? = null): String? {
        return cache[key] as? String ?: defaultValue
    }

    fun getCachedInt(key: String, defaultValue: Int = 0): Int {
        return (cache[key] as? Int) ?: defaultValue
    }

    fun getCachedBoolean(key: String, defaultValue: Boolean = false): Boolean {
        return (cache[key] as? Boolean) ?: defaultValue
    }

    fun getCachedStringSet(key: String, defaultValue: Set<String> = emptySet()): Set<String> {
        @Suppress("UNCHECKED_CAST")
        return (cache[key] as? Set<String>) ?: defaultValue
    }

    suspend fun getString(key: String, defaultValue: String? = null): String? {
        return dataStore.data.first()[stringPreferencesKey(key)] ?: defaultValue
    }

    suspend fun getInt(key: String, defaultValue: Int = 0): Int {
        return dataStore.data.first()[intPreferencesKey(key)] ?: defaultValue
    }

    suspend fun getStringSet(key: String, defaultValue: Set<String> = emptySet()): Set<String> {
        return dataStore.data.first()[stringSetPreferencesKey(key)] ?: defaultValue
    }

    fun getStringFlow(key: String, defaultValue: String? = null): Flow<String?> {
        return dataStore.data.map { it[stringPreferencesKey(key)] ?: defaultValue }
    }

    fun getIntFlow(key: String, defaultValue: Int = 0): Flow<Int> {
        return dataStore.data.map { it[intPreferencesKey(key)] ?: defaultValue }
    }

    fun getStringSetFlow(key: String, defaultValue: Set<String> = emptySet()): Flow<Set<String>> {
        return dataStore.data.map { it[stringSetPreferencesKey(key)] ?: defaultValue }
    }

    suspend fun putString(key: String, value: String?) {
        cache[key] = value
        dataStore.edit { prefs ->
            if (value != null) prefs[stringPreferencesKey(key)] = value
            else prefs.remove(stringPreferencesKey(key))
        }
    }

    suspend fun putInt(key: String, value: Int) {
        cache[key] = value
        dataStore.edit { prefs ->
            prefs[intPreferencesKey(key)] = value
        }
    }

    suspend fun putBoolean(key: String, value: Boolean) {
        cache[key] = value
        dataStore.edit { prefs ->
            prefs[booleanPreferencesKey(key)] = value
        }
    }

    suspend fun putStringSet(key: String, value: Set<String>) {
        cache[key] = value
        dataStore.edit { prefs ->
            prefs[stringSetPreferencesKey(key)] = value
        }
    }

    fun putStringAsync(key: String, value: String?) {
        cache[key] = value
        scope.launch {
            dataStore.edit { prefs ->
                if (value != null) prefs[stringPreferencesKey(key)] = value
                else prefs.remove(stringPreferencesKey(key))
            }
        }
    }

    fun putIntAsync(key: String, value: Int) {
        cache[key] = value
        scope.launch {
            dataStore.edit { prefs ->
                prefs[intPreferencesKey(key)] = value
            }
        }
    }

    fun putBooleanAsync(key: String, value: Boolean) {
        cache[key] = value
        scope.launch {
            dataStore.edit { prefs ->
                prefs[booleanPreferencesKey(key)] = value
            }
        }
    }

    fun putStringSetAsync(key: String, value: Set<String>) {
        cache[key] = value
        scope.launch {
            dataStore.edit { prefs ->
                prefs[stringSetPreferencesKey(key)] = value
            }
        }
    }

    suspend fun remove(key: String) {
        cache.remove(key)
        dataStore.edit { prefs ->
            prefs.remove(stringPreferencesKey(key))
            prefs.remove(intPreferencesKey(key))
            prefs.remove(booleanPreferencesKey(key))
            prefs.remove(stringSetPreferencesKey(key))
        }
    }

    fun clearAll() {
        cache.clear()
        scope.launch {
            dataStore.edit { it.clear() }
        }
    }
}
