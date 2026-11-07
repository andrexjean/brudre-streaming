package com.brudre.streaming.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Idiomas CONFIRMADOS nas faixas reais do arquivo, lidos pelo player (Media3) ao tocar.
 * Têm prioridade sobre o que o TMDB ou a fonte informam: o selo PT-BR só aparece com confirmação ou informação
 * explícita da fonte; idioma ausente na faixa ("und") conta como desconhecido.
 */
object VerifiedLanguages {
    data class Entry(val audio: List<String>, val subtitles: List<String>, val at: Long)

    private const val MAX = 400
    private val map = ConcurrentHashMap<String, Entry>()
    @Volatile private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences("brudre_languages", Context.MODE_PRIVATE)
        prefs = p
        runCatching {
            val o = JSONObject(p.getString("map", "{}")!!)
            o.keys().forEach { k ->
                val e = o.getJSONObject(k)
                map[k] = Entry(list(e.optJSONArray("a")), list(e.optJSONArray("s")), e.optLong("at"))
            }
        }
    }

    private fun list(a: JSONArray?) = a?.let { (0 until it.length()).map { i -> it.getString(i) } }.orEmpty()

    /** Normaliza "por", "pt-BR", "pob" → "pt"; "eng" → "en"; vazio/und → null (desconhecido). */
    fun normalize(code: String?): String? {
        val c = code?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "und" && it != "unk" && it != "mis" && it != "zxx" } ?: return null
        val base = c.substringBefore('-').substringBefore('_')
        return when (base) {
            "por", "pob", "pt" -> "pt"
            "eng", "en" -> "en"
            "spa", "es" -> "es"
            "fre", "fra", "fr" -> "fr"
            "ger", "deu", "de" -> "de"
            "ita", "it" -> "it"
            "jpn", "ja" -> "ja"
            "kor", "ko" -> "ko"
            "chi", "zho", "zh" -> "zh"
            else -> base.take(3)
        }
    }

    fun get(titleId: String): Entry? = map[titleId]

    /** Grava o que o player encontrou (só idiomas conhecidos). */
    fun record(titleId: String, audio: List<String?>, subtitles: List<String?>) {
        val a = audio.mapNotNull { normalize(it) }.distinct()
        val s = subtitles.mapNotNull { normalize(it) }.distinct()
        if (a.isEmpty() && s.isEmpty()) return
        val old = map[titleId]
        if (old != null && old.audio == a && old.subtitles == s) return
        map[titleId] = Entry(a, s, System.currentTimeMillis())
        if (map.size > MAX) map.entries.sortedBy { it.value.at }.take(map.size - MAX).forEach { map.remove(it.key) }
        persist()
    }

    private fun persist() {
        val p = prefs ?: return
        val o = JSONObject()
        map.forEach { (k, e) -> o.put(k, JSONObject().put("a", JSONArray(e.audio)).put("s", JSONArray(e.subtitles)).put("at", e.at)) }
        p.edit().putString("map", o.toString()).apply()
    }

    /** Título com os idiomas confirmados aplicados (sem confirmação, fica como a fonte/TMDB informou). */
    fun apply(t: Title): Title {
        val e = map[t.id] ?: return t
        return t.copy(
            ptAudio = if (e.audio.isNotEmpty()) "pt" in e.audio else t.ptAudio,
            ptSubtitles = t.ptSubtitles || "pt" in e.subtitles,
            language = t.language ?: e.audio.firstOrNull(),
        )
    }

    /** Linha "Confirmado no arquivo: …" para a tela de detalhes. */
    fun line(titleId: String): String? {
        val e = map[titleId] ?: return null
        val a = e.audio.takeIf { it.isNotEmpty() }?.joinToString(", ") { Languages.name(it) } ?: "idioma não informado no arquivo"
        val s = e.subtitles.takeIf { it.isNotEmpty() }?.joinToString(", ") { Languages.name(it) } ?: "nenhuma"
        return "Confirmado no arquivo: áudio $a • legendas $s"
    }

    fun clearForTest() { map.clear(); persist() }
}
