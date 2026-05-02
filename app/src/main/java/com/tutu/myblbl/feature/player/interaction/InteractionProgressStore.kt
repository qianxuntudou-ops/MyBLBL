package com.tutu.myblbl.feature.player.interaction

import com.tutu.myblbl.core.common.settings.AppSettingsDataStore
import org.json.JSONArray
import org.json.JSONObject

class InteractionProgressStore(private val appSettings: AppSettingsDataStore) {

    data class Progress(
        val bvid: String,
        val graphVersion: Long,
        val edgeId: Long,
        val cid: Long,
        val positionMs: Long,
        val variables: Map<String, Float>,
        val history: List<InteractionEngine.HistoryEntry>
    )

    companion object {
        private const val KEY_PREFIX = "interaction_progress_"
    }

    suspend fun save(progress: Progress) {
        val obj = JSONObject().apply {
            put("bvid", progress.bvid)
            put("graph_version", progress.graphVersion)
            put("edge_id", progress.edgeId)
            put("cid", progress.cid)
            put("position_ms", progress.positionMs)

            val varsObj = JSONObject()
            progress.variables.forEach { (key, value) -> varsObj.put(key, value.toDouble()) }
            put("variables", varsObj)

            val historyArr = JSONArray()
            progress.history.forEach { entry ->
                historyArr.put(JSONObject().apply {
                    put("edge_id", entry.edgeId)
                    put("cid", entry.cid)
                    put("title", entry.title)
                })
            }
            put("history", historyArr)
        }
        appSettings.putString(KEY_PREFIX + progress.bvid, obj.toString())
    }

    fun loadCached(bvid: String): Progress? {
        val raw = appSettings.getCachedString(KEY_PREFIX + bvid) ?: return null
        return parseProgress(bvid, raw)
    }

    suspend fun load(bvid: String): Progress? {
        val raw = appSettings.getString(KEY_PREFIX + bvid) ?: return null
        return parseProgress(bvid, raw)
    }

    suspend fun clear(bvid: String) {
        appSettings.remove(KEY_PREFIX + bvid)
    }

    private fun parseProgress(bvid: String, raw: String): Progress? {
        return runCatching {
            val obj = JSONObject(raw)

            val variables = mutableMapOf<String, Float>()
            val varsObj = obj.optJSONObject("variables")
            if (varsObj != null) {
                varsObj.keys().forEach { key ->
                    variables[key] = varsObj.getDouble(key).toFloat()
                }
            }

            val history = mutableListOf<InteractionEngine.HistoryEntry>()
            val historyArr = obj.optJSONArray("history")
            if (historyArr != null) {
                for (i in 0 until historyArr.length()) {
                    val entry = historyArr.getJSONObject(i)
                    history.add(
                        InteractionEngine.HistoryEntry(
                            edgeId = entry.getLong("edge_id"),
                            cid = entry.getLong("cid"),
                            title = entry.getString("title")
                        )
                    )
                }
            }

            Progress(
                bvid = bvid,
                graphVersion = obj.getLong("graph_version"),
                edgeId = obj.getLong("edge_id"),
                cid = obj.getLong("cid"),
                positionMs = obj.getLong("position_ms"),
                variables = variables,
                history = history
            )
        }.getOrNull()
    }
}
