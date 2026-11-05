package com.brudre.streaming.sources

import com.brudre.streaming.data.CatalogRepository
import com.brudre.streaming.data.Kind
import com.brudre.streaming.data.Title
import com.brudre.streaming.data.TitleDetails
import com.brudre.streaming.data.TmdbDetails
import java.util.concurrent.ConcurrentHashMap

/** Obras abertas publicadas pelos próprios titulares (Blender etc.): HTTP direto + torrent oficial. */
class CuratedProvider(private val catalog: CatalogRepository) : ContentProvider {
    override val id = ID
    override val name = "Filmes abertos dos titulares"
    override val description = "Obras com licença aberta publicadas pelos autores, com torrent oficial (ex.: Blender Studio)"
    override val capabilities = setOf(Capability.CATALOG, Capability.DIRECT, Capability.TORRENT)

    override fun handles(title: Title) = title.isCurated
    override suspend fun search(query: String): List<Title> = catalog.curated.search(query)
    override suspend fun getDetails(title: Title): TitleDetails = catalog.curatedDetails(title)

    companion object { const val ID = "curated" }
}

/** Internet Archive: só itens com licença declarada (domínio público / Creative Commons) e sem restrição. */
class ArchiveProvider(private val catalog: CatalogRepository) : ContentProvider {
    override val id = ID
    override val name = "Internet Archive"
    override val description = "Acervo público: filmes, desenhos, séries e documentários em domínio público ou Creative Commons"
    override val capabilities = setOf(Capability.CATALOG, Capability.DIRECT, Capability.TORRENT)

    override fun handles(title: Title) = title.isArchive
    override suspend fun search(query: String): List<Title> = catalog.searchArchive(query)
    override suspend fun getDetails(title: Title): TitleDetails = catalog.archiveDetails(title)

    /** Mesma obra no acervo (ex.: clássico catalogado pelo TMDB). Mantém os metadados de quem pediu. */
    override suspend fun findStreamsFor(hint: MatchHint): TitleDetails? {
        val match = catalog.matchArchive(hint.names, hint.year, hint.kind) ?: return null
        val found = catalog.archiveDetails(match.copy(kind = hint.kind))
        if (!found.available) return null
        return found.copy(
            title = hint.title,
            sourceLabel = "Grátis no Internet Archive (${found.title.license ?: "acervo público"}) • \"${found.title.name}\"",
        )
    }

    companion object { const val ID = "archive" }
}

/** TMDB: somente metadados (capas, sinopses em português, classificação). Não tem vídeos. */
class TmdbProvider(private val catalog: CatalogRepository) : ContentProvider {
    override val id = ID
    override val name = "Catálogo TMDB (metadados)"
    override val description = "Capas, sinopses e classificação indicativa; a reprodução depende de uma fonte gratuita"
    override val capabilities = setOf(Capability.CATALOG, Capability.METADATA)

    private val hints = ConcurrentHashMap<String, TmdbDetails>()

    override fun handles(title: Title) = title.isTmdb
    override suspend fun search(query: String): List<Title> =
        if (catalog.tmdb.enabled) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { catalog.tmdb.search(query) } else emptyList()

    override suspend fun getDetails(title: Title): TitleDetails {
        val d = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { catalog.parseOrThrow { catalog.tmdb.details(title) } }
        if (hints.size > 40) hints.clear()
        hints[d.title.id] = d
        return TitleDetails(d.title, emptyList(), NO_FREE_SOURCE, providerId = null)
    }

    fun hintFor(title: Title): MatchHint? {
        val d = hints[title.id] ?: return null
        val names = TitleMatcher.names(d.englishTitle, d.originalTitle, title.name.takeIf { d.originalLanguage == "pt" })
        return MatchHint(d.title, names, d.title.year, if (d.media == "tv") Kind.SERIES else d.title.kind)
    }

    companion object {
        const val ID = "tmdb"
        const val NO_FREE_SOURCE = "Título catalogado, mas nenhuma fonte gratuita e autorizada foi encontrada para reproduzir no BruDre."
    }
}
