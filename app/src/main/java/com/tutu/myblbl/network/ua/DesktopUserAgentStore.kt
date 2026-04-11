package com.tutu.myblbl.network.ua

import android.content.Context
import com.tutu.myblbl.core.common.settings.AppSettingsDataStore
import org.koin.core.context.GlobalContext
import java.util.Locale
import kotlin.random.Random

class DesktopUserAgentStore(
    private val defaultUserAgent: String,
    private val preferenceName: String,
    private val preferenceKey: String
) {

    private val appSettings: AppSettingsDataStore get() = GlobalContext.get().get()

    private var currentUserAgent: String = defaultUserAgent

    fun init(context: Context): String {
        currentUserAgent = loadOrCreateCurrentUserAgent(context.applicationContext)
        return currentUserAgent
    }

    fun getCurrentUserAgent(): String {
        return currentUserAgent
    }

    fun getAcceptLanguage(locale: Locale = Locale.getDefault()): String {
        val languageTag = locale.toLanguageTag().ifBlank { "en-US" }
        val language = locale.language.ifBlank { "en" }
        return "$languageTag,$language;q=0.9,en-US;q=0.8,en;q=0.7"
    }

    fun refreshUserAgent(context: Context?): String {
        val newUserAgent = generateDesktopUserAgent()
        currentUserAgent = newUserAgent
        appSettings.putStringAsync(preferenceKey, newUserAgent)
        return newUserAgent
    }

    private fun loadOrCreateCurrentUserAgent(context: Context): String {
        val stored = appSettings.getCachedString(preferenceKey).orEmpty().trim()
        if (stored.isNotBlank()) {
            return stored
        }
        val generated = generateDesktopUserAgent()
        appSettings.putStringAsync(preferenceKey, generated)
        return generated
    }

    private fun generateDesktopUserAgent(): String {
        val chromeMajor = Random.nextInt(120, 135)
        val chromeBuild = Random.nextInt(0, 6500)
        val chromePatch = Random.nextInt(0, 220)
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$chromeMajor.0.$chromeBuild.$chromePatch Safari/537.36"
    }
}
