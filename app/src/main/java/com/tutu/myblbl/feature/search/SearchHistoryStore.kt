package com.tutu.myblbl.feature.search

import android.content.Context
import org.json.JSONArray

class SearchHistoryStore(
    context: Context
) {

    companion object {
        private const val PREF_NAME = "app_settings"
        private const val KEY_RECENT_SEARCH = "recentSearch"
        private const val MAX_RECENT_SEARCHES = 10
        private const val MAX_RECENT_CHARS = 90
    }

    private val preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun load(): List<String> {
        val value = preferences.getString(KEY_RECENT_SEARCH, null)
        if (value.isNullOrBlank()) {
            return emptyList()
        }
        return buildList {
            runCatching {
                val array = JSONArray(value)
                repeat(array.length()) { index ->
                    array.optString(index)
                        .takeIf { it.isNotBlank() }
                        ?.let(::add)
                }
            }
        }
    }

    fun save(keyword: String, existing: List<String>): List<String> {
        val normalized = keyword.trim()
        if (normalized.isEmpty()) {
            return existing
        }

        val updated = existing.toMutableList().apply {
            removeAll { it == normalized }
            add(normalized)
        }

        trimByTotalLength(updated)
        trimByEntryCount(updated)
        persist(updated)
        return updated
    }

    private fun trimByTotalLength(items: MutableList<String>) {
        var totalLength = items.sumOf(String::length)
        while (totalLength > MAX_RECENT_CHARS && items.size > 1) {
            totalLength -= items.removeAt(0).length
        }
    }

    private fun trimByEntryCount(items: MutableList<String>) {
        if (items.size > MAX_RECENT_SEARCHES) {
            items.subList(0, items.size - MAX_RECENT_SEARCHES).clear()
        }
    }

    private fun persist(items: List<String>) {
        val array = JSONArray()
        items.forEach(array::put)
        preferences.edit()
            .putString(KEY_RECENT_SEARCH, array.toString())
            .apply()
    }
}
