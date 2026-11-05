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
    /** Controle dos pais: hash SHA-256 do PIN (vazio = sem senha definida). */
    val pinHash: String = "",
    /** Classificação máxima liberada sem senha (L, 10, 12, 14, 16, 18). */
    val maxRating: String = "16",
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
            pinHash = prefs.getString("pinHash", "") ?: "",
            maxRating = prefs.getString("maxRating", d.maxRating) ?: d.maxRating,
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
            .putString("pinHash", v.pinHash)
            .putString("maxRating", v.maxRating)
            .apply()
        state.value = v
    }
}

/** Controle dos pais: classificação indicativa brasileira e PIN. */
object Parental {
    val RATINGS = listOf("L", "10", "12", "14", "16", "18")

    fun level(rating: String?): Int = rating?.let { RATINGS.indexOf(it.uppercase()) }?.takeIf { it >= 0 } ?: -1

    /** true se o título passa do limite liberado sem senha. Sem classificação conhecida = não bloqueia. */
    fun needsPin(rating: String?, maxRating: String): Boolean {
        val l = level(rating)
        return l >= 0 && l > level(maxRating)
    }

    fun hash(pin: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest("brudre:$pin".toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun check(pin: String, pinHash: String): Boolean = pinHash.isNotEmpty() && hash(pin) == pinHash

    /** Conteúdo pornográfico/explícito: bloqueado sempre, sem senha que libere. */
    private val EXPLICIT = Regex(
        "(?i)(porn|pornô|porno|xxx|hentai|sexo explícito|sexo explicito|explicit sex|erótic|erotic|softcore|hardcore sex|adult film|filme adulto|sex tape|playboy|penthouse|onlyfans|strip ?tease|nudismo|nudist|orgia|orgy)"
    )

    fun isExplicit(vararg texts: String?): Boolean = texts.any { it != null && EXPLICIT.containsMatchIn(it) }
}
