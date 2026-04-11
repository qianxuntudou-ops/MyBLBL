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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.appDataStore by preferencesDataStore(name = "app_settings")

class AppSettingsDataStore(private val context: Context) {

    private val dataStore: DataStore<Preferences> get() = context.appDataStore

    val data: Flow<Preferences> get() = dataStore.data

    suspend fun getString(key: String, defaultValue: String? = null): String? {
        return dataStore.data.first()[stringPreferencesKey(key)] ?: defaultValue
    }

    suspend fun getInt(key: String, defaultValue: Int = 0): Int {
        return dataStore.data.first()[intPreferencesKey(key)] ?: defaultValue
    }

    suspend fun getBoolean(key: String, defaultValue: Boolean = false): Boolean {
        return dataStore.data.first()[booleanPreferencesKey(key)] ?: defaultValue
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

    fun getBooleanFlow(key: String, defaultValue: Boolean = false): Flow<Boolean> {
        return dataStore.data.map { it[booleanPreferencesKey(key)] ?: defaultValue }
    }

    suspend fun putString(key: String, value: String?) {
        dataStore.edit { prefs ->
            if (value != null) prefs[stringPreferencesKey(key)] = value
            else prefs.remove(stringPreferencesKey(key))
        }
    }

    suspend fun putInt(key: String, value: Int) {
        dataStore.edit { prefs ->
            prefs[intPreferencesKey(key)] = value
        }
    }

    suspend fun putBoolean(key: String, value: Boolean) {
        dataStore.edit { prefs ->
            prefs[booleanPreferencesKey(key)] = value
        }
    }

    suspend fun putStringSet(key: String, value: Set<String>) {
        dataStore.edit { prefs ->
            prefs[stringSetPreferencesKey(key)] = value
        }
    }

    suspend fun remove(key: String) {
        dataStore.edit { prefs ->
            prefs.remove(stringPreferencesKey(key))
            prefs.remove(intPreferencesKey(key))
            prefs.remove(booleanPreferencesKey(key))
            prefs.remove(stringSetPreferencesKey(key))
        }
    }
}
