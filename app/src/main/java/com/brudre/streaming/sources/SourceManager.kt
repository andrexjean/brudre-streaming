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
import com.brudre.streaming.data.SourceOption
import com.brudre.streaming.data.TmdbParser
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
    private val store: SourceStore? = null,
    val searchRepo: SearchRepository? = null,
    private val torrents: (() -> com.brudre.streaming.torrent.TorrentLibrary)? = null,
) {
    private val curated = CuratedProvider(catalog)
    private val archive = ArchiveProvider(catalog)
    private val tmdb = TmdbProvider(catalog)

    private val external = FeedProvider(feeds, searchRepo)

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

    /**
     * Junta TODAS as fontes cadastradas que têm a obra (catálogos, APIs de pesquisa e torrents associados),
     * na ordem configurada (prioridade + histórico) e na posição escolhida em relação às nativas.
     * A primeira fonte vira a principal; as demais viram alternativas automáticas no player e na escolha manual.
     */
    private suspend fun attachUserSources(d: TitleDetails, original: Title): TitleDetails {
        val names = (TitleMatcher.names(d.title.name, original.name) + (tmdb.hintFor(d.title)?.names ?: emptyList())).distinct()
        val tmdbId = TmdbParser.parseId(d.title.id)?.let { "${it.first}:${it.second}" }
        val series = when {
            tmdbId != null -> tmdbId.startsWith("tv:")
            d.title.kind == com.brudre.streaming.data.Kind.SERIES -> true
            d.title.kind == com.brudre.streaming.data.Kind.MOVIE -> false
            else -> null
        }
        val user = runCatching { userCandidates(names, d.title, tmdbId, series) }.getOrElse { if (it is CancellationException) throw it; emptyList() }
        if (user.isEmpty()) return d
        val native = if (d.available) SourceOption(d.providerId ?: "native", nativeName(d), d.playables, SourceRanker.formats(d.playables), native = true,
            subtitles = d.playables.flatMap { p -> p.subtitles.map { it.language } }.filter { it != "und" }.distinct()) else null
        val s = settings.current
        val prefs = com.brudre.streaming.data.SourcePreferences(s.audioLanguage, s.subtitleLanguage, s.preferredResolution, s.preferredSourceId)
        val options = SourceRanker.order(native, user, s.userSourcesFirst, prefs)
        val merged = SourceRanker.merge(options)
        val first = options.first()
        val label = when {
            merged.isEmpty() -> "Fontes encontradas só pelo nome (sem ano para confirmar): escolha manualmente em \"Escolher fonte\""
            options.size == 1 -> if (first.native) d.sourceLabel else "Fonte cadastrada: ${first.name}"
            first.native -> "${d.sourceLabel} • mais ${options.size - 1} fonte(s) cadastrada(s)"
            else -> "Fonte: ${first.name} • ${options.size} fontes encontradas"
        }
        return d.copy(playables = merged, sourceLabel = label, providerId = first.sourceId, confirmed = false, options = options)
    }

    private fun nativeName(d: TitleDetails) = when (d.providerId) {
        "archive" -> "Internet Archive"
        "curated" -> "Obras abertas (BruDre)"
        else -> d.sourceLabel.substringBefore(" •").take(40)
    }

    /** Fontes cadastradas com a obra, consultadas em paralelo e com tempo-limite (uma lenta não segura as outras). */
    private suspend fun userCandidates(names: List<String>, t: Title, tmdbId: String?, series: Boolean?): List<SourceRanker.Candidate> = coroutineScope {
        val st = store?.current.orEmpty()
        fun rank(id: String) = st.indexOfFirst { it.id == id }.let { if (it < 0) 500 else it }
        fun unreliable(id: String) = st.firstOrNull { it.id == id }?.stats?.unreliable == true
        fun health(id: String) = st.firstOrNull { it.id == id }?.stats?.summary
        fun latency(id: String) = st.firstOrNull { it.id == id }?.stats?.avgLatencyMs ?: 0
        val catalogJob = async {
            if (feeds.enabledFeeds().isEmpty()) emptyList()
            else runCatching { withTimeout(15_000) { feeds.matches(names, t.year, tmdbId, series) } }.getOrElse { if (it is CancellationException && it !is TimeoutCancellationException) throw it; emptyList() }
        }
        val searchJob = async {
            val repo = searchRepo ?: return@async emptyList()
            if (repo.sources().isEmpty() || names.isEmpty()) return@async emptyList()
            val category = when {
                t.kind == com.brudre.streaming.data.Kind.KIDS -> "infantil"
                t.genres.any { it.contains("Document", true) } -> "documentarios"
                series == true -> "series"
                series == false -> "filmes"
                else -> null
            }
            val q = SearchTemplate.Query(t.name, tmdbId, t.year, tmdbId?.substringBefore(':') ?: series?.let { if (it) "tv" else "movie" }, category = category)
            runCatching { withTimeout(SearchRepository.PER_SOURCE_MS + 4_000) { repo.search(q) } }
                .getOrElse { if (it is CancellationException && it !is TimeoutCancellationException) throw it; emptyList() }
                .filter { h -> TitleMatcher.matchesItem(h.item, names, t.year, tmdbId, series) }
        }
        val out = ArrayList<SourceRanker.Candidate>()
        catalogJob.await().forEach { (f, item) ->
            val id = if (f.id == FeedRepository.LINKS_ID) item.title.id.substringAfterLast(':') else f.id
            val name = if (f.id == FeedRepository.LINKS_ID) item.title.name else f.name
            val ps = item.playables()
            if (ps.isNotEmpty()) out += SourceRanker.Candidate(
                SourceRanker.option(id, name, item, ps, health(id), TitleMatcher.confident(item, t.year, tmdbId)),
                rank(id), unreliable(id), latency(id), health(id),
            )
        }
        searchJob.await().forEach { h ->
            val ps = h.item.playables()
            if (ps.isNotEmpty()) out += SourceRanker.Candidate(
                SourceRanker.option(h.source.id, "${h.source.name} (pesquisa)", h.item, ps, health(h.source.id), TitleMatcher.confident(h.item, t.year, tmdbId)),
                rank(h.source.id), unreliable(h.source.id), latency(h.source.id), health(h.source.id),
            )
        }
        torrents?.invoke()?.matches(names, t.year, tmdbId)?.forEach { e ->
            val p = Playable("${e.hash}:auto", e.displayName, 1, 1, null, e.ref, emptyList(), null, preferTorrent = true)
            // Associação feita pelo próprio usuário: confiável por definição.
            out += SourceRanker.Candidate(SourceOption("torrent:${e.hash}", "Meu torrent: ${e.name}", listOf(p), "BitTorrent", FeedParser.qualityOf(e.name)), 900, false)
        }
        // Mesma fonte com o mesmo vídeo duas vezes (catálogo e pesquisa) entra uma vez só.
        out.distinctBy { c -> c.option.sourceId + "|" + c.option.playables.joinToString { it.directUrl ?: it.torrent?.magnet ?: it.torrent?.torrentUrl ?: it.key } }
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
    private suspend fun confirm(d0: TitleDetails): TitleDetails {
        // Legenda em português disponível na fonte → selo "LEG PT" na tela de detalhes.
        val d = if (!d0.title.ptSubtitles && d0.playables.any { p -> p.subtitles.any { com.brudre.streaming.data.Languages.isPortuguese(it.language) } })
            d0.copy(title = d0.title.copy(ptSubtitles = true)) else d0
        if (!d.available) return d
        val list = d.playables.filter { it.hasSource }
        val first = list.first()
        val probe = if (!torrentAllowed && first.directUrl == null && first.mirrors.isEmpty()) StreamValidator.Result.INVALID
        else validator.validate(if (torrentAllowed) first else first.copy(torrent = null, altTorrents = emptyList()))
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

/** Ordem e junção das fontes de um título (funções puras, testadas em SourceManagerTest). */
object SourceRanker {
    /** [latencyMs] = tempo médio de resposta observado (0 = sem medição). */
    data class Candidate(val option: SourceOption, val priority: Int, val unreliable: Boolean, val latencyMs: Int = 0, val history: String? = null)

    /**
     * Ordem final: fontes cadastradas pela prioridade do usuário (as que falharam 3x seguidas vão para o fim),
     * antes ou depois da fonte nativa conforme a configuração.
     */
    fun order(native: SourceOption?, user: List<Candidate>, userFirst: Boolean, prefs: com.brudre.streaming.data.SourcePreferences = com.brudre.streaming.data.SourcePreferences(audio = "", subtitle = "off")): List<SourceOption> {
        // Ordem: confiáveis antes; sem 3 falhas seguidas; fonte preferida; idioma e resolução preferidos
        // (informação ausente = neutra, nunca "inventada"); por fim a prioridade da lista.
        val users = user.sortedWith(compareBy<Candidate>(
            { !it.option.confident }, { it.unreliable },
            { prefs.preferredSourceId.isNotEmpty() && it.option.sourceId != prefs.preferredSourceId },
            { languageScore(it.option.audio, prefs.audio) }, { languageScore(it.option.subtitles, prefs.subtitle) },
            { resolutionScore(it.option.quality, prefs.resolution) }, { it.priority },
            { if (it.latencyMs > 0) it.latencyMs else Int.MAX_VALUE },
        )).map { it.option.copy(reasons = explain(it, prefs)) }
        return when {
            native == null -> users
            userFirst -> users + native
            else -> listOf(native) + users
        }
    }

    /** Motivos legíveis da posição — só com dados reais; o que falta aparece como desconhecido. */
    fun explain(c: Candidate, prefs: com.brudre.streaming.data.SourcePreferences): List<String> {
        val o = c.option
        val r = ArrayList<String>()
        r += if (o.confident) "título conferido (TMDB ou nome + ano)" else "sem ano para conferir: só manual"
        if (prefs.preferredSourceId.isNotEmpty() && o.sourceId == prefs.preferredSourceId) r += "sua fonte preferida"
        if (prefs.audio.isNotBlank() && prefs.audio != "off") r += when (languageScore(o.audio, prefs.audio)) {
            0 -> "áudio ${com.brudre.streaming.data.Languages.name(prefs.audio)} informado"
            1 -> "áudio desconhecido"
            else -> "áudio informado: ${o.audio.joinToString { com.brudre.streaming.data.Languages.name(it) }}"
        }
        if (prefs.subtitle.isNotBlank() && prefs.subtitle != "off" && languageScore(o.subtitles, prefs.subtitle) == 0) r += "legenda ${com.brudre.streaming.data.Languages.name(prefs.subtitle)}"
        r += o.quality?.let { q -> if (prefs.resolution > 0 && resolutionScore(q, prefs.resolution) == 0) "$q (preferida)" else q } ?: "resolução desconhecida"
        r += "formato: ${o.formats}"
        if (c.unreliable) r += "falhou 3x seguidas: vai para o fim"
        c.history?.let { r += "histórico: $it" }
        if (c.latencyMs > 0) r += "resposta ~${c.latencyMs} ms"
        r += "prioridade ${c.priority + 1} na sua lista"
        return r
    }

    /** 0 = idioma preferido informado, 1 = desconhecido, 2 = informado e diferente. */
    fun languageScore(known: List<String>, pref: String): Int = when {
        pref.isBlank() || pref == "off" -> 0
        known.isEmpty() -> 1
        known.any { it.equals(pref, true) || it.startsWith("$pref-", true) } -> 0
        else -> 2
    }

    /** 0 = resolução preferida, 1 = desconhecida, 2 = outra resolução informada. */
    fun resolutionScore(quality: String?, pref: Int): Int {
        if (pref <= 0) return 0
        val q = when { quality == null -> return 1; quality.equals("4K", true) -> 2160; else -> quality.removeSuffix("p").toIntOrNull() ?: return 1 }
        return if (q == pref) 0 else 2
    }

    /** Opção de um item de fonte: formato, qualidade e idiomas só quando INFORMADOS pela fonte. */
    fun option(id: String, name: String, item: FeedItem, ps: List<Playable>, health: String?, confident: Boolean): SourceOption {
        val audio = (listOfNotNull(item.title.language) + if (item.title.ptAudio) listOf("pt") else emptyList()).map { it.lowercase().substringBefore('-') }.distinct()
        val subs = (item.subtitles.map { it.language } + if (item.title.ptSubtitles) listOf("pt") else emptyList()).filter { it != "und" }.map { it.lowercase().substringBefore('-') }.distinct()
        return SourceOption(id, name + if (!confident) " (sem ano: confirme)" else "", tag(ps, id), formats(ps),
            FeedParser.qualityOf(item.title.name, ps.firstOrNull()?.directUrl), health = health, confident = confident, audio = audio, subtitles = subs)
    }

    /** Marca a origem de cada endereço/torrent (histórico de sucesso e falha no player). */
    fun tag(ps: List<Playable>, sourceId: String): List<Playable> = ps.map { p ->
        val keys = listOfNotNull(p.directUrl) + p.mirrors + listOfNotNull(p.torrent?.let { refKey(it) }) + p.altTorrents.map { refKey(it) }
        p.copy(origins = p.origins + keys.associateWith { sourceId })
    }

    fun refKey(t: com.brudre.streaming.data.TorrentRef): String = t.magnet ?: t.torrentUrl ?: t.localFile ?: ""

    /**
     * Junta as opções: os vídeos da primeira fonte, com os endereços e torrents equivalentes das outras
     * (mesmo episódio) como alternativas, na ordem. O player tenta a próxima diante de erro recuperável.
     */
    fun merge(options: List<SourceOption>, manual: Boolean = false): List<Playable> {
        if (options.isEmpty()) return emptyList()
        // Sem associação confirmada, a fonte não entra sozinha: só se o usuário a escolher (manual).
        if (!manual && !options.first().confident) return emptyList()
        val primary = options.first().playables
        return primary.map { p ->
            var out = p
            for (o in options.drop(1).filter { it.confident }) {
                val q = o.playables.firstOrNull { it.season == p.season && it.number == p.number }
                    ?: o.playables.singleOrNull()?.takeIf { primary.size == 1 } ?: continue
                val newDirect = out.directUrl ?: q.directUrl ?: q.mirrors.firstOrNull()
                val allDirect = (listOfNotNull(out.directUrl) + out.mirrors + listOfNotNull(q.directUrl) + q.mirrors).distinct()
                val newTorrent = out.torrent ?: q.torrent ?: q.altTorrents.firstOrNull()
                val allTorrents = (listOfNotNull(out.torrent) + out.altTorrents + listOfNotNull(q.torrent) + q.altTorrents).distinctBy { refKey(it) }
                out = out.copy(
                    directUrl = newDirect,
                    mirrors = allDirect.filter { it != newDirect },
                    torrent = newTorrent,
                    altTorrents = allTorrents.filter { refKey(it) != newTorrent?.let(::refKey) },
                    origins = q.origins + out.origins,
                    subtitles = (out.subtitles + q.subtitles).distinctBy { it.url },
                )
            }
            out
        }
    }

    fun formats(ps: List<Playable>): String {
        val all = ps.flatMap { listOfNotNull(it.directUrl) + it.mirrors }
        val f = ArrayList<String>()
        if (all.any { !it.substringBefore('?').endsWith(".m3u8", true) }) f += "HTTP"
        if (all.any { it.substringBefore('?').endsWith(".m3u8", true) }) f += "HLS"
        if (ps.any { it.torrent != null || it.altTorrents.isNotEmpty() }) f += "BitTorrent"
        return f.joinToString(" • ").ifEmpty { "—" }
    }
}
