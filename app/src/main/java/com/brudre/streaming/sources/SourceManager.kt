package com.brudre.streaming.sources

import android.util.Log
import com.brudre.streaming.data.AppSettings
import com.brudre.streaming.data.CatalogException
import com.brudre.streaming.data.CatalogRepository
import com.brudre.streaming.data.Playable
import com.brudre.streaming.data.Title
import com.brudre.streaming.data.TitleDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/** Estado de todas as fontes, atualizado a cada uso (sucesso, falha, latência). */
class StatusBoard {
    private val map = ConcurrentHashMap<String, ProviderStatus>()
    private val _all = MutableStateFlow<List<ProviderStatus>>(emptyList())
    val all: StateFlow<List<ProviderStatus>> = _all.asStateFlow()

    fun register(id: String, name: String, description: String, enabled: Boolean, removable: Boolean = false) {
        val old = map[id]
        map[id] = (old ?: ProviderStatus(id, name, description, enabled, Health.UNKNOWN, removable = removable))
            .copy(name = name, description = description, enabled = enabled, removable = removable,
                health = if (!enabled) Health.DISABLED else if (old?.health == Health.DISABLED) Health.UNKNOWN else old?.health ?: Health.UNKNOWN)
        publish()
    }

    fun unregister(id: String) { map.remove(id); publish() }

    fun success(id: String, latencyMs: Long) {
        map.computeIfPresent(id) { _, s ->
            s.copy(health = if (latencyMs > SLOW_MS) Health.SLOW else Health.OK, lastLatencyMs = latencyMs, successes = s.successes + 1, lastError = null)
        }
        publish()
    }

    fun failure(id: String, message: String) {
        map.computeIfPresent(id) { _, s -> s.copy(health = Health.ERROR, lastError = message, failures = s.failures + 1) }
        publish()
    }

    private fun publish() { _all.value = map.values.sortedBy { ORDER.indexOf(it.id).let { i -> if (i < 0) 99 else i } } }

    companion object {
        private const val SLOW_MS = 6_000L
        private val ORDER = listOf("archive", "curated", "tmdb", "iptv", "bittorrent")
    }
}

/**
 * BruDre Source Manager: separa catálogo, descoberta de fontes, validação e reprodução.
 *
 * - Cada provedor é chamado isoladamente (tempo-limite próprio, erro registrado só nele).
 * - Títulos só de catálogo (TMDB) são procurados nas fontes com vídeo (Internet Archive).
 * - O primeiro vídeo é verificado antes de liberar o "Assistir".
 */
class SourceManager(
    private val catalog: CatalogRepository,
    private val settings: AppSettings,
    private val validator: StreamValidator,
    val board: StatusBoard,
    val feeds: FeedRepository,
) {
    private val curated = CuratedProvider(catalog)
    private val archive = ArchiveProvider(catalog)
    private val tmdb = TmdbProvider(catalog)

    private val external = FeedProvider(feeds)

    /** Ordem da busca: catálogos externos e obras abertas (tocam no app), acervo, e por fim só catálogo. */
    val providers: List<ContentProvider> = listOf(external, curated, archive, tmdb)

    init {
        refreshRegistry()
    }

    fun isEnabled(id: String): Boolean = id !in settings.current.disabledSources

    fun setEnabled(id: String, enabled: Boolean) {
        if (id.startsWith(FeedRepository.PREFIX)) { feeds.setEnabled(id, enabled); return }
        settings.update { s -> s.copy(disabledSources = if (enabled) s.disabledSources - id else s.disabledSources + id) }
        refreshRegistry()
    }

    fun refreshRegistry() {
        providers.filter { it !== external }.forEach { p ->
            val on = isEnabled(p.id) && (p !== tmdb || catalog.tmdb.enabled)
            board.register(p.id, p.name, if (p === tmdb && !catalog.tmdb.enabled) "App compilado sem a chave do TMDB" else p.description, on)
        }
        board.register(BITTORRENT, "BitTorrent (torrents oficiais)", "Usado só quando o streaming direto falha. Seu IP fica visível aos outros participantes.", isEnabled(BITTORRENT))
    }

    val torrentAllowed: Boolean get() = isEnabled(BITTORRENT)

    /** Executa uma operação de um provedor sem deixar a falha dele afetar os demais. */
    private suspend fun <T> guarded(p: ContentProvider, timeoutMs: Long = TIMEOUT_MS, block: suspend () -> T): Result<T> {
        if (!isEnabled(p.id)) return Result.failure(CatalogException("Fonte \"${p.name}\" desativada em Configurações"))
        val t0 = System.currentTimeMillis()
        return try {
            val v = withTimeout(timeoutMs) { block() }
            board.success(p.id, System.currentTimeMillis() - t0)
            Result.success(v)
        } catch (e: TimeoutCancellationException) {
            board.failure(p.id, "Não respondeu em ${timeoutMs / 1000} s")
            Result.failure(CatalogException("${p.name} não respondeu a tempo"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Falha na fonte ${p.id}", e)
            board.failure(p.id, e.message ?: e.javaClass.simpleName)
            Result.failure(e)
        }
    }

    // ------------------------------------------------------------------ busca

    /** Pesquisa unificada: obras com fonte primeiro (abertas, acervo), catálogo depois. */
    suspend fun search(term: String): List<Title> = coroutineScope {
        val clean = catalog.cleanQuery(term)
        if (clean.length < 2) return@coroutineScope emptyList()
        val active = providers.filter { Capability.CATALOG in it.capabilities && isEnabled(it.id) && (it !== tmdb || catalog.tmdb.enabled) }
        val results = active.map { p -> async { p to guarded(p) { p.search(clean) } } }.awaitAll()
        val ok = results.flatMap { (_, r) -> r.getOrNull().orEmpty() }
        if (ok.isEmpty()) results.firstNotNullOfOrNull { it.second.exceptionOrNull() }?.let { if (it is java.io.IOException) throw it }
        ok.distinctBy { it.id }
    }

    // --------------------------------------------------------------- detalhes

    suspend fun details(title: Title): TitleDetails {
        val owner = providers.firstOrNull { it.handles(title) }
            ?: return TitleDetails(title, emptyList(), "Fonte desconhecida para este título")
        var d = guarded(owner) { owner.getDetails(title) }.getOrThrow()
        if (owner === external) return confirm(enrichFromCatalog(d))
        if (!d.available && owner === tmdb) {
            // 1º: vídeo dentro do BruDre (acervo público).
            if (tmdb.worthArchiveSearch(d.title)) d = findFreeSource(d).let { if (it.available) it else d }
        }
        // 2º: fontes cadastradas pelo usuário (Gerenciar fontes), na ordem de prioridade.
        d = attachUserSources(d, title)
        if (!d.available && d.freeProviders.isNotEmpty()) {
            // 3º: app oficial gratuito.
            val names = d.freeProviders.joinToString(", ") { com.brudre.streaming.data.FreeApps.label(it.name) }
            return d.copy(sourceLabel = "Grátis no app oficial: $names (com anúncios, sem assinatura) • Disponibilidade: JustWatch")
        }
        return confirm(d)
    }

    /** Associa o título do catálogo a um título das fontes cadastradas (mesmo nome e ano). */
    private suspend fun attachUserSources(d: TitleDetails, original: Title): TitleDetails {
        if (feeds.enabledFeeds().isEmpty()) return d
        val names = (TitleMatcher.names(d.title.name, original.name) + (tmdb.hintFor(d.title)?.names ?: emptyList())).distinct()
        val match = runCatching { feeds.matches(names, d.title.year) }.getOrDefault(emptyList()).firstOrNull() ?: return d
        val extra = match.second.playables()
        if (extra.isEmpty()) return d
        return if (!d.available) {
            d.copy(playables = extra, sourceLabel = "Fonte cadastrada: ${match.first.name}", providerId = match.first.id, confirmed = false)
        } else {
            val first = d.playables.first()
            val q = extra.first()
            val merged = first.copy(
                mirrors = (first.mirrors + listOfNotNull(q.directUrl) + q.mirrors).distinct().filter { it != first.directUrl },
                torrent = first.torrent ?: q.torrent,
            )
            d.copy(playables = listOf(merged) + d.playables.drop(1), sourceLabel = "${d.sourceLabel} • também em ${match.first.name}")
        }
    }

    /** Título de fonte externa sem capa: usa capa, sinopse e classificação do catálogo (TMDB) quando nome e ano batem. */
    private suspend fun enrichFromCatalog(d: TitleDetails): TitleDetails {
        val t = d.title
        if (t.posterUrl != null || !catalog.tmdb.enabled || !isEnabled(TmdbProvider.ID)) return d
        val names = TitleMatcher.names(t.name.replace(Regex("\\((magnet|\\.torrent|hls|http[^)]*)\\)", RegexOption.IGNORE_CASE), ""))
        val found = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { catalog.tmdb.search(t.name) } }.getOrDefault(emptyList())
        val m = TitleMatcher.best(found, names, t.year) ?: return d
        return d.copy(title = t.copy(
            posterUrl = m.posterUrl, bannerUrl = m.bannerUrl ?: m.posterUrl, synopsis = t.synopsis ?: m.synopsis,
            year = t.year ?: m.year, genres = t.genres.ifEmpty { m.genres }, rating = t.rating ?: m.rating,
        ))
    }

    /** Obra só catalogada: procura a mesma obra nas fontes com vídeo, uma por vez (motores leves primeiro). */
    private suspend fun findFreeSource(meta: TitleDetails): TitleDetails {
        val hint = tmdb.hintFor(meta.title) ?: return meta
        if (hint.names.isEmpty()) return meta
        for (p in providers) {
            if (p === tmdb || !isEnabled(p.id)) continue
            val found = guarded(p) { p.findStreamsFor(hint) }.getOrNull() ?: continue
            if (found.available) return found
        }
        return meta
    }

    /** Verifica o vídeo principal; links mortos são descartados e o resto continua disponível. */
    private suspend fun confirm(d: TitleDetails): TitleDetails {
        if (!d.available) return d
        val list = d.playables.filter { it.hasSource }
        val first = list.first()
        val probe = if (!torrentAllowed && first.directUrl == null && first.mirrors.isEmpty()) StreamValidator.Result.INVALID
        else validator.validate(if (torrentAllowed) first else first.copy(torrent = null))
        return when (probe) {
            StreamValidator.Result.OK -> d.copy(confirmed = true)
            StreamValidator.Result.UNKNOWN -> d.copy(confirmed = false, sourceLabel = "${d.sourceLabel} • não foi possível confirmar agora")
            StreamValidator.Result.INVALID -> {
                val rest = list.drop(1)
                if (rest.isNotEmpty() && validator.validate(rest.first()) == StreamValidator.Result.OK) {
                    d.copy(playables = rest, confirmed = true, sourceLabel = "${d.sourceLabel} • 1 vídeo indisponível removido")
                } else {
                    d.copy(playables = emptyList(), confirmed = false, sourceLabel = "A fonte cadastrada não está respondendo (link indisponível ou removido).")
                }
            }
        }
    }

    fun playableAllowed(p: Playable): Boolean = p.hasSource && (torrentAllowed || p.directUrl != null || p.mirrors.isNotEmpty())

    companion object {
        private const val TAG = "BruDreSources"
        private const val TIMEOUT_MS = 25_000L
        const val BITTORRENT = "bittorrent"
    }
}
