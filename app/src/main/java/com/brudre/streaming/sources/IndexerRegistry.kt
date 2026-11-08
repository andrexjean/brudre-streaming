package com.brudre.streaming.sources

import org.json.JSONObject

/**
 * Registro de indexadores (Source Engine 4.0). Correspondência com a especificação:
 * - IndexerProvider / IndexerAdapterFactory → [SearchAdapter] + [Adapters.forSource]
 * - IndexerDescriptor → [UserSource] (+ [RestDescriptor] / [OpenSearch] em `config`)
 * - IndexerCapabilities → [SourceCapabilities]
 * - IndexerSearchRequest / IndexerSearchResult → [SearchTemplate.Query] / [SearchHit] + [SearchPage]
 * - IndexerHealth → [SourceTest] (último teste) + [SourceStats] (uso real, falhas, tempo de resposta)
 * - IndexerRegistry → este objeto: biblioteca de fontes candidatas avaliadas (assets/source_library.json)
 *
 * A biblioteca só descreve; nada é cadastrado sem o usuário escolher "Adicionar", e todo cadastro passa pela
 * identificação de formato e pelas validações de segurança. Nenhum script de fonte é executado.
 */
object IndexerRegistry {
    enum class Integration(val label: String) {
        INTEGRADO("Integrado"),
        TESTE_MOTOR("Só teste do motor BitTorrent"),
        NAO_APLICAVEL("Não aplicável (não é vídeo)"),
        AVALIADO("Avaliado — sem API utilizável"),
        NAO_SUPORTADO("Protocolo não suportado"),
        NAO_INTEGRADO("Não integrado (conteúdo não autorizado)"),
        INDISPONIVEL("Serviço encerrado"),
    }

    data class Entry(
        val id: String,
        val name: String,
        /** conteudo, software, protocolo, avaliado */
        val group: String,
        /** servico (provedor concreto) ou protocolo (formato) — contados separadamente */
        val kind: String,
        val protocol: String,
        val integration: Integration,
        val note: String,
        val search: String,
        val torrent: String,
        val addUrl: String?,
        val restrictions: String?,
        /** Avaliação técnica de provedores (disponibilidade, API/feed, adaptador compatível, condição de integração, teste). */
        val availability: String? = null,
        val api: String? = null,
        val adapter: String? = null,
        val condition: String? = null,
        val test: String? = null,
    ) {
        val isService get() = kind == "servico"
    }

    fun parse(json: String): List<Entry> {
        val o = JSONObject(json)
        require(o.optString("format") == "brudre-biblioteca") { "Biblioteca inválida" }
        val a = o.getJSONArray("entries")
        return (0 until a.length()).map { i ->
            val e = a.getJSONObject(i)
            Entry(
                e.getString("id"), e.getString("name"), e.getString("group"), e.getString("kind"), e.getString("protocol"),
                Integration.valueOf(e.getString("integration").uppercase()), e.optString("note"), e.optString("search"), e.optString("torrent"),
                e.optString("addUrl").ifBlank { null }?.takeIf { it.startsWith("https://") && UrlGuard.isAllowed(it) },
                e.optString("restrictions").ifBlank { null },
                e.optString("availability").ifBlank { null }, e.optString("api").ifBlank { null }, e.optString("adapter").ifBlank { null },
                e.optString("condition").ifBlank { null }, e.optString("test").ifBlank { null },
            )
        }
    }

    @Volatile private var cache: List<Entry>? = null

    fun entries(context: android.content.Context): List<Entry> = cache ?: runCatching {
        parse(context.assets.open("source_library.json").bufferedReader().use { it.readText() })
    }.getOrDefault(emptyList()).also { cache = it }

    val GROUPS = linkedMapOf(
        "conteudo" to "Conteúdo aberto e domínio público",
        "software" to "Software livre (teste do motor BitTorrent)",
        "protocolo" to "Protocolos e formatos",
        "avaliado" to "Provedores avaliados tecnicamente (não integrados)",
    )
}
