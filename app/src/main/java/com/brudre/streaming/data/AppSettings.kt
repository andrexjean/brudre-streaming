package com.brudre.streaming.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SourceMode(val label: String, val description: String) {
    AUTO("Automático", "Usa a fonte oficial de cada título e troca sozinho se ela falhar"),
    DIRECT("Somente direto", "Streaming HTTP do servidor oficial (mais leve)"),
    TORRENT("Preferir torrent", "BitTorrent primeiro; direto como alternativa"),
}

enum class Quality(val label: String) {
    LIGHT("Leve (até 480p)"),
    BEST("Melhor disponível (até 1080p)"),
}

data class Choice(val code: String, val label: String)

object LanguageChoices {
    val audio = listOf(Choice("pt", "Português"), Choice("en", "Inglês"), Choice("", "Original"))
    val subtitles = listOf(Choice("off", "Desligadas"), Choice("pt", "Português"), Choice("en", "Inglês"), Choice("es", "Espanhol"))
    val cacheMb = listOf(500, 1024, 2048)
    val connections = listOf(20, 40, 80)
}

data class SettingsValues(
    val sourceMode: SourceMode = SourceMode.AUTO,
    val quality: Quality = Quality.BEST,
    val audioLanguage: String = "pt",
    val subtitleLanguage: String = "pt",
    val cacheLimitMb: Int = 1024,
    val torrentConnections: Int = 40,
    /** Serviços que a família assina (chaves de [StreamingApps]). */
    val myServices: Set<String> = emptySet(),
    /** Mostrar no catálogo de lançamentos só o que está nos meus serviços (ou grátis). */
    val onlyMine: Boolean = false,
)

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("brudre_settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val values: StateFlow<SettingsValues> = state.asStateFlow()
    val current: SettingsValues get() = state.value

    private fun read(): SettingsValues {
        val d = SettingsValues()
        return SettingsValues(
            sourceMode = runCatching { SourceMode.valueOf(prefs.getString("sourceMode", d.sourceMode.name)!!) }.getOrDefault(d.sourceMode),
            quality = runCatching { Quality.valueOf(prefs.getString("quality", d.quality.name)!!) }.getOrDefault(d.quality),
            audioLanguage = prefs.getString("audio", d.audioLanguage) ?: d.audioLanguage,
            subtitleLanguage = prefs.getString("subtitles", d.subtitleLanguage) ?: d.subtitleLanguage,
            cacheLimitMb = prefs.getInt("cacheMb", d.cacheLimitMb),
            torrentConnections = prefs.getInt("connections", d.torrentConnections),
            myServices = prefs.getStringSet("myServices", emptySet())?.toSet() ?: emptySet(),
            onlyMine = prefs.getBoolean("onlyMine", d.onlyMine),
        )
    }

    fun update(transform: (SettingsValues) -> SettingsValues) {
        val v = transform(state.value)
        prefs.edit()
            .putString("sourceMode", v.sourceMode.name)
            .putString("quality", v.quality.name)
            .putString("audio", v.audioLanguage)
            .putString("subtitles", v.subtitleLanguage)
            .putInt("cacheMb", v.cacheLimitMb)
            .putInt("connections", v.torrentConnections)
            .putStringSet("myServices", v.myServices)
            .putBoolean("onlyMine", v.onlyMine)
            .apply()
        state.value = v
    }
}
