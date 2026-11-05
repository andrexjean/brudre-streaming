package com.brudre.streaming.sources

import com.brudre.streaming.data.Kind
import com.brudre.streaming.data.Playable
import com.brudre.streaming.data.Title
import com.brudre.streaming.data.TitleDetails

/** O que uma fonte sabe fazer. */
enum class Capability { CATALOG, METADATA, DIRECT, HLS, TORRENT, LIVE }

enum class Health(val label: String) {
    UNKNOWN("Ainda não usada"), OK("Funcionando"), SLOW("Lenta"), ERROR("Com falha"), DISABLED("Desativada")
}

/** Estado de uma fonte, mostrado em Configurações > Fontes de conteúdo. */
data class ProviderStatus(
    val id: String,
    val name: String,
    val description: String,
    val enabled: Boolean,
    val health: Health,
    val lastError: String? = null,
    val lastLatencyMs: Long? = null,
    val successes: Int = 0,
    val failures: Int = 0,
    val removable: Boolean = false,
)

/**
 * Interface comum de provedores do BruDre Source Manager.
 * A interface gráfica nunca fala com um provedor diretamente: tudo passa pelo [SourceManager],
 * que isola falhas (uma fonte com erro não derruba as outras) e registra o estado de cada uma.
 */
interface ContentProvider {
    val id: String
    val name: String
    val description: String
    val capabilities: Set<Capability>
    /** Fontes nativas (Internet Archive, TMDB) não podem ser removidas, só desativadas. */
    val removable: Boolean get() = false

    /** true se o id do título pertence a esta fonte. */
    fun handles(title: Title): Boolean

    suspend fun search(query: String): List<Title> = emptyList()

    suspend fun getDetails(title: Title): TitleDetails

    /** Fontes reproduzíveis para um título catalogado em OUTRA fonte (ex.: obra do TMDB achada no acervo). */
    suspend fun findStreamsFor(hint: MatchHint): TitleDetails? = null
}

/** Dados que uma fonte de metadados oferece para outras procurarem a mesma obra. */
data class MatchHint(
    val title: Title,
    val names: List<String>,
    val year: String?,
    val kind: Kind,
)

/** Três estados exigidos: catalogado, com fonte identificada, com reprodução confirmada. */
enum class Availability(val label: String) {
    CATALOG_ONLY("Só catálogo — sem fonte gratuita"),
    SOURCE_FOUND("Fonte identificada"),
    CONFIRMED("Reprodução confirmada"),
}

/** Escolha da obra correspondente no acervo a partir do título e do ano (funções puras). */
object TitleMatcher {
    private val ARTICLES = Regex("^(the|an|a|os|as|o|le|la|les|der|die|das|el|los|il)\\s+")

    fun normalize(raw: String): String {
        var s = java.text.Normalizer.normalize(raw.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        s = s.replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ").replace("&", " and ")
        s = s.replace(Regex("[^a-z0-9]+"), " ").trim()
        s = s.replace(ARTICLES, "")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    fun names(vararg raw: String?): List<String> = raw.filterNotNull().map { normalize(it) }.filter { it.length >= 2 }.distinct()

    /** Consulta do Internet Archive pelos nomes (frase exata no título). */
    fun archiveQuery(names: List<String>): String =
        names.joinToString(" OR ") { "title:(\"${it.replace("\"", "")}\")" }

    fun matches(candidate: Title, names: List<String>, year: String?): Boolean {
        val c = normalize(candidate.name)
        val y = year?.toIntOrNull()
        val cy = candidate.year?.toIntOrNull()
        if (y != null && cy != null && kotlin.math.abs(y - cy) > 1) return false
        return names.any { n ->
            val trimmed = if (y != null) c.removeSuffix(" $y").removeSuffix(" ${y + 1}").removeSuffix(" ${y - 1}").trim() else c
            when {
                trimmed == n -> true
                // "metropolis 1927 restored", "nosferatu full movie": prefixo + sufixo curto, só com ano conferido.
                cy != null && y != null && trimmed.startsWith("$n ") && trimmed.length - n.length <= 18 -> true
                else -> false
            }
        }
    }

    /** Primeiro candidato compatível (a lista já vem ordenada por popularidade no acervo). */
    fun best(candidates: List<Title>, names: List<String>, year: String?): Title? =
        candidates.firstOrNull { matches(it, names, year) }
}

/** Ordem de tentativas: HTTP/HLS primeiro, depois outras diretas, BitTorrent por último. */
object SourcePriority {
    fun hasDirect(p: Playable) = p.directUrl != null || p.mirrors.isNotEmpty()
    fun hasTorrent(p: Playable) = p.torrent != null
}
