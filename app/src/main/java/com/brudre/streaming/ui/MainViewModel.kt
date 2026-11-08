package com.brudre.streaming.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.brudre.streaming.BruDreApp
import com.brudre.streaming.data.Parental
import com.brudre.streaming.data.Playable
import com.brudre.streaming.live.LiveChannel
import com.brudre.streaming.live.LiveRepository.Companion.toPlayable
import com.brudre.streaming.live.LiveRepository.Companion.toTitle
import com.brudre.streaming.sources.ProviderStatus
import com.brudre.streaming.data.SettingsValues
import com.brudre.streaming.data.Title
import com.brudre.streaming.data.TitleDetails
import com.brudre.streaming.data.db.FavoriteEntity
import com.brudre.streaming.data.db.ProgressEntity
import com.brudre.streaming.player.PlayerActivity
import com.brudre.streaming.update.UpdateState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Error(val message: String) : Load<Nothing>
}

enum class Section(val label: String) {
    HOME("Início"), RELEASES("Lançamentos"), TRENDING("Em Alta"), MOVIES("Filmes"), SERIES("Séries"), KIDS("Infantil"), CLASSICS("Clássicos"),
    DOCS("Documentários"), LIVE("TV ao Vivo"), MY_LIST("Minha Lista"), SEARCH("Pesquisa"), SETTINGS("Configurações"),
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    companion object {
        /** Teto de resultados mantidos na Pesquisa (memória do Mi Stick). */
        const val MAX_SEARCH_RESULTS = 300
    }

    val services = BruDreApp.services(app)
    private val catalog = services.catalog
    private val sources = services.sources
    private val live = services.live
    val tmdbEnabled: Boolean get() = catalog.tmdb.enabled && sources.isEnabled("tmdb")
    private val library = services.library

    // ------------------------------------------------------------- linhas
    private val rows = ConcurrentHashMap<String, MutableStateFlow<Load<List<Title>>>>()

    fun row(key: String): StateFlow<Load<List<Title>>> =
        rows.getOrPut(key) { MutableStateFlow<Load<List<Title>>>(Load.Loading).also { load(key, it, false) } }

    fun retryRow(key: String) {
        rows[key]?.let { load(key, it, true) }
    }

    private fun load(key: String, state: MutableStateFlow<Load<List<Title>>>, force: Boolean) {
        state.value = Load.Loading
        viewModelScope.launch {
            state.value = try {
                Load.Ok(catalog.row(key, force))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("BruDre", "Falha ao carregar a linha $key", e)
                Load.Error(e.message ?: "Falha ao carregar")
            }
            // Passa o estado direto: com cache em memória a carga termina antes de a linha entrar no mapa.
            if (key == "featured" || key == "tmdb_free_movies") rebuildHero(key, state.value)
        }
    }

    // --------------------------------------------------------------- herói
    private val _hero = MutableStateFlow(catalog.curated.featured())
    val hero: StateFlow<List<Title>> = _hero.asStateFlow()

    /** Destaques só com títulos que tocam no BruDre (obras abertas + acervo público). */
    private fun rebuildHero(changedKey: String, changed: Load<List<Title>>) {
        fun stateOf(k: String) = if (k == changedKey) changed else rows[k]?.value
        val extra = (stateOf("featured") as? Load.Ok)?.value.orEmpty().take(3)
        // Filmes recentes grátis (apps oficiais) também entram no destaque.
        val free = (stateOf("tmdb_free_movies") as? Load.Ok)?.value.orEmpty().filter { it.bannerUrl != null }.take(3)
        _hero.value = (free.take(2) + catalog.curated.featured().take(2) + extra + free.drop(2) + catalog.curated.featured().drop(2))
            .distinctBy { it.id }.take(8)
    }

    init {
        row("featured")
        if (catalog.tmdb.enabled) row("tmdb_free_movies")
    }

    // ----------------------------------------------------------- biblioteca
    val favorites: StateFlow<List<FavoriteEntity>> =
        library.favorites.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val continueWatching: StateFlow<List<ProgressEntity>> =
        library.continueWatching.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val history: StateFlow<List<ProgressEntity>> =
        library.history.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun isFavorite(titleId: String): Flow<Boolean> = library.isFavorite(titleId)
    fun progressFor(titleId: String): Flow<List<ProgressEntity>> = library.progressFor(titleId)

    fun toggleFavorite(title: Title, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch { onResult(library.toggleFavorite(title)) }
    }

    // ------------------------------------------------------------- detalhes
    private val _detailsTitle = MutableStateFlow<Title?>(null)
    val detailsTitle: StateFlow<Title?> = _detailsTitle.asStateFlow()
    private val _details = MutableStateFlow<Load<TitleDetails>>(Load.Loading)
    val details: StateFlow<Load<TitleDetails>> = _details.asStateFlow()
    private var detailsJob: Job? = null

    fun openDetails(title: Title) {
        com.brudre.streaming.torrent.TorrentLibrary.hashOf(title.id)?.let { h ->
            services.torrentLibrary.get(h)?.let { openTorrentEntry(it); return }
        }
        _detailsTitle.value = title
        loadDetails(title)
    }

    /** Pede para abrir a escolha manual de fonte assim que os detalhes carregarem. */
    private val _requestChooser = MutableStateFlow(false)
    val requestChooser: StateFlow<Boolean> = _requestChooser.asStateFlow()
    fun openChooser(t: Title) { _requestChooser.value = true; openDetails(t) }
    fun chooserShown() { _requestChooser.value = false }

    fun reloadDetails() {
        _detailsTitle.value?.let { loadDetails(it) }
    }

    private fun loadDetails(title: Title) {
        detailsJob?.cancel()
        _details.value = Load.Loading
        detailsJob = viewModelScope.launch {
            _details.value = try {
                if (title.isLive) throw IllegalStateException("Canal ao vivo")
                Load.Ok(sources.details(title))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Error(e.message ?: "Falha ao carregar detalhes")
            }
        }
    }

    fun closeDetails() {
        detailsJob?.cancel()
        _detailsTitle.value = null
    }

    // ------------------------------------------------------------- reprodução
    private val _playLoading = MutableStateFlow<String?>(null)
    val playLoading: StateFlow<String?> = _playLoading.asStateFlow()

    /**
     * Resolve a fonte e devolve o Intent do player.
     * Sem [playable]: usa o último episódio assistido ou o primeiro disponível.
     */
    fun play(
        title: Title,
        playable: Playable? = null,
        fromStart: Boolean = false,
        details: TitleDetails? = null,
        onLaunch: (Intent) -> Unit,
        onError: (String) -> Unit,
        onLocked: (Title) -> Unit = { openDetails(it) },
        onFreeApp: (Title, com.brudre.streaming.data.FreeProvider) -> Unit = { t, _ -> openDetails(t) },
    ) {
        if (title.isLive) {
            live.find(title.id.removePrefix(Title.LIVE_PREFIX))?.let { playChannel(it, onLaunch) }
                ?: onError("Canal não encontrado nas listas carregadas. Abra TV ao Vivo e tente de novo.")
            return
        }
        if (_playLoading.value != null) return
        _playLoading.value = title.id
        viewModelScope.launch {
            try {
                // Fluxo: título → identificação da fonte → verificação → reprodução.
                val d = details ?: sources.details(title)
                if (isLocked(d.title.rating)) { onLocked(d.title); return@launch }
                if (d.freeElsewhere) { onFreeApp(d.title, d.freeProviders.first()); return@launch }
                if (!d.available || !d.confirmed) {
                    onError(if (d.available) "A fonte de \"${title.name}\" não respondeu agora. Tente de novo em instantes." else d.sourceLabel)
                    return@launch
                }
                val target = playable ?: run {
                    val list = d.playables.filter { sources.playableAllowed(it) }
                    if (list.isEmpty()) {
                        onError("Nenhuma fonte autorizada disponível para \"${title.name}\".")
                        return@launch
                    }
                    val last = library.latestFor(title.id)
                    list.firstOrNull { it.key == last?.episodeKey } ?: list.first()
                }
                // Torrent com vários vídeos e sem arquivo definido: o usuário escolhe (não presume o maior arquivo).
                val t = target.torrent
                if (t != null && t.fileIndex == null && t.filePathHint == null && target.directUrl == null && target.mirrors.isEmpty()) {
                    val resolved = resolveTorrentFile(d.title, target) ?: return@launch
                    val start = if (fromStart) 0L else library.resumePosition(d.title.id, resolved.key)
                    onLaunch(PlayerActivity.intent(getApplication<Application>(), d.title, resolved, start))
                    return@launch
                }
                val start = if (fromStart) 0L else library.resumePosition(title.id, target.key)
                onLaunch(PlayerActivity.intent(getApplication<Application>(), d.title, target, start))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e.message ?: "Não foi possível iniciar a reprodução")
            } finally {
                _playLoading.value = null
            }
        }
    }


    // ------------------------------------------------------------- BitTorrent do usuário
    /** Escolha de vídeo/episódio dentro de um torrent (tela "Arquivos do torrent"). */
    data class TorrentPick(
        val title: Title,
        val baseKey: String,
        val base: Playable,
        val meta: com.brudre.streaming.torrent.TorrentMeta? = null,
        val status: String? = null,
        val error: com.brudre.streaming.sources.SourceFailure? = null,
    )

    private val _torrentPick = MutableStateFlow<TorrentPick?>(null)
    val torrentPick: StateFlow<TorrentPick?> = _torrentPick.asStateFlow()
    private var torrentJob: Job? = null
    /** Para o player ser aberto a partir da tela de arquivos. */
    var launcher: ((Intent) -> Unit)? = null
    val torrentHistory = services.torrentLibrary.entries

    fun closeTorrentPick() { torrentJob?.cancel(); _torrentPick.value = null }

    fun retryTorrentPick() {
        val p = _torrentPick.value ?: return
        torrentJob?.cancel()
        torrentJob = viewModelScope.launch {
            val r = resolveTorrentFile(p.title, p.base) ?: return@launch
            launcher?.invoke(PlayerActivity.intent(getApplication<Application>(), p.title, r, library.resumePosition(p.title.id, r.key)))
        }
    }

    /** Lista os vídeos do torrent; um vídeo só → toca direto; vários → o usuário escolhe. */
    private suspend fun resolveTorrentFile(title: Title, base: Playable): Playable? {
        val ref = base.torrent ?: return base
        _torrentPick.value = TorrentPick(title, base.key, base, status = "Obtendo a lista de arquivos do torrent…")
        return try {
            val meta = services.torrentLibrary.meta(ref) { msg -> _torrentPick.value = _torrentPick.value?.copy(status = msg) }
            val videos = meta.videos
            when {
                videos.isEmpty() -> {
                    _torrentPick.value = TorrentPick(title, base.key, base, meta, error = com.brudre.streaming.sources.SourceFailure(
                        com.brudre.streaming.sources.ErrorKind.NO_VIDEO, "Nenhum vídeo compatível entre ${meta.files.size} arquivo(s).", "files=${meta.files.map { it.path }.take(10)}"))
                    null
                }
                videos.size == 1 -> { _torrentPick.value = null; videoPlayable(base, videos.first()) }
                else -> { _torrentPick.value = TorrentPick(title, base.key, base, meta); null }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _torrentPick.value = TorrentPick(title, base.key, base, error = com.brudre.streaming.sources.ErrorClassifier.classify(e))
            null
        }
    }

    private fun videoPlayable(base: Playable, v: com.brudre.streaming.torrent.TorrentVideo): Playable {
        val ref = base.torrent!!.copy(fileIndex = v.file.index, filePathHint = v.file.path)
        return base.copy(
            key = "${base.key.removeSuffix(":auto")}#${v.file.index}",
            label = v.episode?.let { "T${it.season}:E${it.episode} — ${v.file.name}" } ?: v.file.name,
            season = v.episode?.season ?: base.season, number = v.episode?.episode ?: base.number,
            torrent = ref, altTorrents = emptyList(), directUrl = null, mirrors = emptyList(),
        )
    }

    /** Vídeo escolhido na tela de arquivos: abre o player retomando de onde parou. */
    fun playTorrentVideo(v: com.brudre.streaming.torrent.TorrentVideo, fromStart: Boolean = false) {
        val pick = _torrentPick.value ?: return
        val p = videoPlayable(pick.base, v)
        viewModelScope.launch {
            val start = if (fromStart) 0L else library.resumePosition(pick.title.id, p.key)
            _torrentPick.value = null
            launcher?.invoke(PlayerActivity.intent(getApplication<Application>(), pick.title, p, start))
        }
    }

    /** Torrent do histórico (ou recém-adicionado): lista os arquivos e permite escolher o vídeo. */
    fun openTorrentEntry(e: com.brudre.streaming.torrent.TorrentEntry) {
        if (!sources.torrentAllowed) { _torrentPick.value = null; toastFlow.tryEmit("BitTorrent está desativado em Configurações > Fontes de conteúdo."); return }
        val title = com.brudre.streaming.torrent.TorrentLibrary.title(e)
        val base = Playable(e.titleId.removePrefix(com.brudre.streaming.torrent.TorrentLibrary.PREFIX), e.displayName, 1, 1, null, e.ref, emptyList(), null, preferTorrent = true)
        torrentJob?.cancel()
        torrentJob = viewModelScope.launch {
            val p = resolveTorrentFile(title, base.copy(key = e.hash)) ?: return@launch
            val start = library.resumePosition(title.id, p.key)
            launcher?.invoke(PlayerActivity.intent(getApplication<Application>(), title, p, start))
        }
    }

    /** Avisos curtos para a interface (toast). */
    val toastFlow = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)

    fun addMagnet(magnet: String, open: Boolean = true): String = try {
        val e = services.torrentLibrary.addMagnet(magnet)
        if (open) openTorrentEntry(e)
        "Magnet adicionado: ${e.name}"
    } catch (e: Exception) {
        com.brudre.streaming.sources.ErrorClassifier.classify(e).summary
    }

    /** Lê um .torrent recebido de outro app (content://) com limite de tamanho e o adiciona ao histórico. */
    fun importTorrentUri(uri: android.net.Uri, open: Boolean = true, onResult: (String) -> Unit = { toastFlow.tryEmit(it) }) {
        viewModelScope.launch {
            onResult(try {
                val bytes = withContext(Dispatchers.IO) {
                    val cr = getApplication<Application>().contentResolver
                    cr.openInputStream(uri)?.use { input ->
                        val max = com.brudre.streaming.torrent.TorrentContent.MAX_TORRENT_BYTES
                        val buf = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(chunk); if (n < 0) break
                            buf.write(chunk, 0, n)
                            if (buf.size() > max) throw com.brudre.streaming.sources.ClassifiedException(com.brudre.streaming.sources.ErrorKind.FORMAT, "Arquivo grande demais para ser um .torrent")
                        }
                        buf.toByteArray()
                    } ?: throw com.brudre.streaming.sources.ClassifiedException(com.brudre.streaming.sources.ErrorKind.STORAGE, "Não foi possível ler o arquivo recebido")
                }
                val e = withContext(Dispatchers.IO) { services.torrentLibrary.importBytes(bytes) }
                if (open) openTorrentEntry(e)
                ".torrent importado: ${e.name} (${e.videoCount} vídeo(s))"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                com.brudre.streaming.sources.ErrorClassifier.classify(e).summary
            })
        }
    }

    fun addTorrentUrl(url: String): String = try {
        val e = services.torrentLibrary.addUrl(url.trim(), null)
        openTorrentEntry(e)
        ".torrent adicionado: ${e.name}"
    } catch (e: Exception) {
        com.brudre.streaming.sources.ErrorClassifier.classify(e).summary
    }

    /** Só adiciona ao histórico (sem abrir). */
    fun addTorrentUrlOnly(url: String): String = try {
        if (!url.trim().startsWith("https://", true)) throw com.brudre.streaming.sources.ClassifiedException(com.brudre.streaming.sources.ErrorKind.BLOCKED, "Use magnet: ou um endereço https:// de .torrent")
        ".torrent adicionado: ${services.torrentLibrary.addUrl(url.trim(), null).name}"
    } catch (e: Exception) {
        com.brudre.streaming.sources.ErrorClassifier.classify(e).summary
    }

    fun removeTorrent(hash: String) = services.torrentLibrary.remove(hash)
    fun linkTorrent(hash: String, name: String?, year: String?) = services.torrentLibrary.link(hash, name, year)

    /** Intents de outros apps: magnet:, .torrent (Abrir com) e Compartilhar (texto com magnet ou arquivo). */
    fun handleIncoming(intent: Intent?): Boolean {
        intent ?: return false
        val data = intent.data
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                if (data == null) return false
                if (data.scheme.equals("magnet", true)) { toastFlow.tryEmit(addMagnet(data.toString())); return true }
                if (data.scheme == "content" || data.scheme == "file") { importTorrentUri(data); return true }
                if (data.scheme == "https" && data.path?.endsWith(".torrent", true) == true) { toastFlow.tryEmit(addTorrentUrl(data.toString())); return true }
            }
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val stream = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                if (stream != null) { importTorrentUri(stream); return true }
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return false
                Regex("magnet:\\?[^\\s\"'<>]+").find(text)?.let { toastFlow.tryEmit(addMagnet(it.value)); return true }
                Regex("https://[^\\s\"'<>]+\\.torrent").find(text)?.let { toastFlow.tryEmit(addTorrentUrl(it.value)); return true }
                toastFlow.tryEmit("O texto compartilhado não tem um link magnet ou .torrent")
                return true
            }
        }
        return false
    }

    // -------------------------------------------------------- escolha manual de fonte
    /** Toca a partir da fonte escolhida; as seguintes continuam como alternativas automáticas. */
    fun playWithOption(details: TitleDetails, option: com.brudre.streaming.data.SourceOption, playable: Playable?, onLaunch: (Intent) -> Unit, onError: (String) -> Unit) {
        val ordered = listOf(option) + details.options.filter { it !== option }
        val merged = com.brudre.streaming.sources.SourceRanker.merge(ordered, manual = true)
        val target = playable?.let { p -> merged.firstOrNull { it.season == p.season && it.number == p.number } } ?: merged.firstOrNull()
            ?: return onError("Essa fonte não tem vídeo para este título.")
        play(details.title, target, details = details.copy(playables = merged, confirmed = true), onLaunch = onLaunch, onError = onError)
    }

    /** Cancela a pesquisa em andamento (ao sair da tela); resultados já prontos são mantidos. */
    fun cancelSearch(): Boolean {
        if (searchJob?.isActive != true) return false
        searchJob?.cancel()
        if (_searchResults.value is Load.Loading) _searchResults.value = null
        return true
    }

    // ------------------------------------------------------------- TV ao Vivo
    private val liveLists = ConcurrentHashMap<String, MutableStateFlow<Load<List<LiveChannel>>>>()
    val offlineChannels: StateFlow<Set<String>> = live.offline
    /** Resultado da verificação dos sinais (muda conforme os canais são testados). */
    val liveHealth = live.health.results
    val liveCheckProgress = live.health.progress

    fun visibleChannels(list: List<LiveChannel>): List<LiveChannel> = live.visible(list)

    /** Verifica os canais de uma linha que ainda não foram testados (ex.: listas do mundo). */
    fun checkChannels(list: List<LiveChannel>) {
        viewModelScope.launch { runCatching { live.health.check(list) } }
    }

    fun recheckLive() {
        viewModelScope.launch {
            runCatching { live.health.check(live.channels("iptv_br"), force = true) }
            runCatching { live.health.check(live.channels("iptv_por").filter { it.country != "br" }, force = true) }
        }
    }

    init {
        // Revalidação automática configurável (sem serviço em segundo plano): só com o app aberto, uma fonte por vez.
        viewModelScope.launch {
            delay(30_000)
            val hours = services.settings.current.revalidateHours
            if (hours <= 0 || !services.isOnline()) return@launch
            val due = services.sourceStore.current.filter { s -> s.enabled && !s.pending && (s.lastTest?.at ?: 0L) < System.currentTimeMillis() - hours * 3_600_000L }
            for (s in due) runCatching { runTest(s) }
            if (due.isNotEmpty()) android.util.Log.i("BruDre", "Revalidação automática: ${due.size} fonte(s)")
        }
    }

    init {
        // Depois da abertura e do Início carregarem, testa os canais em segundo plano.
        viewModelScope.launch {
            delay(15_000)
            live.checkMain()
        }
    }

    fun liveList(id: String): StateFlow<Load<List<LiveChannel>>> =
        liveLists.getOrPut(id) { MutableStateFlow<Load<List<LiveChannel>>>(Load.Loading).also { loadLive(id, it, false) } }

    fun retryLive(id: String) { liveLists[id]?.let { loadLive(id, it, true) } }

    private fun loadLive(id: String, state: MutableStateFlow<Load<List<LiveChannel>>>, force: Boolean) {
        state.value = Load.Loading
        viewModelScope.launch {
            state.value = try {
                Load.Ok(live.channels(id, force))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Error(e.message ?: "Falha ao carregar os canais")
            }
        }
    }

    fun liveListsInfo() = live.builtInLists() + live.customLists().filter { live.isListEnabled(it) }
    val liveEnabled: Boolean get() = live.enabled

    fun playChannel(ch: LiveChannel, onLaunch: (Intent) -> Unit) {
        live.remember(ch)
        onLaunch(PlayerActivity.intent(getApplication<Application>(), ch.toTitle(), ch.toPlayable(), 0L))
    }

    fun toggleFavoriteChannel(ch: LiveChannel, onResult: (Boolean) -> Unit = {}) {
        live.remember(ch)
        toggleFavorite(ch.toTitle(), onResult)
    }

    // ---------------------------------------------------- fontes de conteúdo
    val sourceStatus: StateFlow<List<ProviderStatus>> = services.sourceStatus.all

    fun setSourceEnabled(id: String, enabled: Boolean) {
        when {
            id == com.brudre.streaming.live.LiveRepository.PROVIDER_ID || id.startsWith(com.brudre.streaming.live.LiveRepository.CUSTOM_PREFIX) -> {
                live.setListEnabled(id, enabled)
                liveLists.forEach { (k, v) -> loadLive(k, v, false) }
            }
            else -> {
                sources.setEnabled(id, enabled)
                rows.forEach { (k, v) -> if (v.value is Load.Error || k.startsWith("tmdb_")) load(k, v, false) }
            }
        }
    }

    fun addCustomList(name: String, url: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            onResult(
                try {
                    val n = live.addCustomList(name, url)
                    "Lista adicionada: $n canais"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.message ?: "Não foi possível adicionar a lista"
                },
            )
        }
    }

    fun removeCustomList(id: String) {
        if (id.startsWith(com.brudre.streaming.sources.FeedRepository.PREFIX)) {
            services.feeds.remove(id)
            feedRows.remove(id)
            return
        }
        live.removeCustomList(id)
        liveLists.remove(id)
    }

    // ------------------------------------------------------- catálogos externos
    private val feedRows = ConcurrentHashMap<String, MutableStateFlow<Load<List<Title>>>>()

    fun enabledFeeds() = services.feeds.enabledFeeds()

    fun feedRow(id: String): StateFlow<Load<List<Title>>> =
        feedRows.getOrPut(id) { MutableStateFlow<Load<List<Title>>>(Load.Loading).also { loadFeed(id, it, false) } }

    fun retryFeed(id: String) { feedRows[id]?.let { loadFeed(id, it, true) } }

    /** Catálogos com mais páginas para carregar. */
    private val _feedHasMore = MutableStateFlow<Set<String>>(emptySet())
    val feedHasMore: StateFlow<Set<String>> = _feedHasMore.asStateFlow()
    private val loadingMore = java.util.Collections.synchronizedSet(HashSet<String>())

    fun loadMoreFeed(id: String) {
        if (!loadingMore.add(id)) return // evita consultas duplicadas
        viewModelScope.launch {
            try {
                val items = services.feeds.loadMore(id)
                feedRows[id]?.value = Load.Ok(items.map { it.title })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toastFlow.tryEmit(com.brudre.streaming.sources.ErrorClassifier.classify(e).summary)
            } finally {
                loadingMore.remove(id)
                _feedHasMore.value = if (services.feeds.hasMore(id)) _feedHasMore.value + id else _feedHasMore.value - id
            }
        }
    }

    private fun loadFeed(id: String, state: MutableStateFlow<Load<List<Title>>>, force: Boolean) {
        state.value = Load.Loading
        viewModelScope.launch {
            state.value = try {
                Load.Ok(services.feeds.items(id, force).map { it.title }).also {
                    _feedHasMore.value = if (services.feeds.hasMore(id)) _feedHasMore.value + id else _feedHasMore.value - id
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Error(e.message ?: "Falha ao carregar o catálogo")
            }
        }
    }

    fun addFeed(name: String, url: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            onResult(
                try {
                    "Catálogo adicionado: ${services.feeds.add(name, url)} títulos"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.message ?: "Não foi possível adicionar o catálogo"
                },
            )
        }
    }

    // ------------------------------------------------------- Gerenciar fontes
    val userSources: StateFlow<List<com.brudre.streaming.sources.UserSource>> = services.sourceStore.sources
    private val _testingSources = MutableStateFlow<Set<String>>(emptySet())
    val testingSources: StateFlow<Set<String>> = _testingSources.asStateFlow()

    private fun afterSourcesChanged(vararg ids: String) {
        ids.forEach { id -> services.feeds.invalidate(id); live.invalidate(id); feedRows.remove(id); liveLists.remove(id) }
        services.feeds.invalidate(com.brudre.streaming.sources.FeedRepository.LINKS_ID)
        feedRows[com.brudre.streaming.sources.FeedRepository.LINKS_ID]?.let { loadFeed(com.brudre.streaming.sources.FeedRepository.LINKS_ID, it, true) }
        services.feeds.refreshRegistry()
        live.refreshRegistry()
    }

    /** Campos do cadastro avançado de fonte. [type] null = detectar automaticamente. */
    data class SourceForm(
        val name: String,
        val url: String,
        val type: com.brudre.streaming.sources.SourceType? = null,
        val auth: com.brudre.streaming.sources.AuthMethod = com.brudre.streaming.sources.AuthMethod.NONE,
        /** Credencial digitada agora (null = manter a que já está guardada). */
        val key: String? = null,
        val timeoutSec: Int = com.brudre.streaming.sources.UserSource.DEFAULT_TIMEOUT_S,
        val limit: Int = com.brudre.streaming.sources.UserSource.DEFAULT_LIMIT,
        val categories: Set<String> = emptySet(),
        val enabled: Boolean = true,
        /** Servidor local: impressão digital SHA-256 confirmada pelo usuário. */
        val localPin: String? = null,
    )

    /** Lê o certificado de um servidor da rede local para o usuário conferir antes de confirmar. */
    fun readLocalCertificate(url: String, onResult: (Boolean, String, String?) -> Unit) {
        viewModelScope.launch {
            try {
                val c = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.brudre.streaming.sources.LocalServers.fetchCertificate(url.trim()) }
                val fmt = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale("pt", "BR"))
                onResult(true, "SHA-256: ${com.brudre.streaming.sources.LocalServers.pretty(c.pin)}\nEmitido para: ${c.subject}" +
                    (if (c.selfSigned) " (autoassinado)" else "\nEmissor: ${c.issuer}") + "\nVálido até ${fmt.format(c.notAfter)}. Confira com a impressão digital mostrada no seu servidor.", c.pin)
            } catch (e: Exception) {
                val f = com.brudre.streaming.sources.ErrorClassifier.classify(e)
                onResult(false, f.summary, null)
            }
        }
    }

    /** Identifica o formato, valida estrutura e conexão e só então salva (nova fonte ou edição de [oldId]). */
    fun saveSource(oldId: String?, name: String, url: String, onResult: (Boolean, String, String?) -> Unit) =
        saveSource(oldId, SourceForm(name, url), onResult)

    fun saveSource(oldId: String?, form: SourceForm, onResult: (Boolean, String, String?) -> Unit) {
        viewModelScope.launch {
            // Chave colada no endereço (…/api?apikey=…, comum em Torznab/Newznab): sai do endereço e vai cifrada.
            val (url, urlKey) = if (form.url.contains("apikey=", true) && !com.brudre.streaming.sources.SearchTemplate.isTemplate(form.url)) com.brudre.streaming.sources.Torznab.splitKey(form.url) else form.url to null
            val form = if (urlKey != null) form.copy(url = url, key = form.key?.ifBlank { null } ?: urlKey) else form
            val key = form.key ?: oldId?.let { services.credentials.get(it) }
            val oldSrc = oldId?.let { services.sourceStore.get(it) }
            val pin = form.localPin ?: oldSrc?.localPin?.takeIf { com.brudre.streaming.sources.LocalServers.keyOf(oldSrc.url) == com.brudre.streaming.sources.LocalServers.keyOf(form.url) }
            // Servidor local: a fixação vale já para o teste; se não salvar, é desfeita.
            pin?.let { com.brudre.streaming.sources.LocalServers.register(form.url, it) }
            fun undoPin() { if (pin != null && services.sourceStore.current.none { it.localPin != null && com.brudre.streaming.sources.LocalServers.keyOf(it.url) == com.brudre.streaming.sources.LocalServers.keyOf(form.url) }) com.brudre.streaming.sources.LocalServers.unregister(form.url) }
            // Formato escolhido à mão: as mesmas validações de segurança e de estrutura valem.
            val r = services.inspector.inspect(form.url, forcedType = form.type, key = key, auth = form.auth)
            val type = r.type
            if (type == null || !r.test.ok) {
                undoPin()
                val hint = if (pin == null && com.brudre.streaming.sources.UserSourcesJson.mayBeLocal(form.url)) "\nServidor da rede local? Use \"Ler certificado\" e confirme antes de salvar." else ""
                onResult(false, (type?.let { "${it.label} — " } ?: "") + r.test.message + hint, r.test.detail)
                return@launch
            }
            val clean = form.url.trim()
            if (!com.brudre.streaming.sources.UserSourcesJson.isSafe(type, clean)) {
                undoPin()
                onResult(false, "${com.brudre.streaming.sources.ErrorKind.BLOCKED.title}: endereço não permitido para ${type.label}", null)
                return@launch
            }
            services.sourceStore.duplicateOf(clean, oldId)?.let { d ->
                undoPin()
                onResult(false, "Fonte duplicada: este endereço já está cadastrado como \"${d.name}\" (${d.type.label}).", null)
                return@launch
            }
            val old = oldId?.let { services.sourceStore.get(it) }
            val src = com.brudre.streaming.sources.UserSource(
                id = com.brudre.streaming.sources.UserSource.idFor(type, clean),
                name = form.name.trim().ifBlank { r.suggestedName ?: type.label }.take(60),
                url = clean, type = type, enabled = form.enabled, priority = old?.priority ?: services.sourceStore.current.size, lastTest = r.test, config = r.config,
                auth = form.auth, timeoutSec = form.timeoutSec.coerceIn(3, 60), resultLimit = form.limit.coerceIn(5, 200),
                categories = form.categories, manualType = form.type != null, stats = old?.stats ?: com.brudre.streaming.sources.SourceStats(),
                localPin = pin, pending = false,
            )
            try {
                if (oldId != null) services.sourceStore.replace(oldId, src) else services.sourceStore.put(src)
                if (form.key != null) services.credentials.put(src.id, form.key.ifBlank { null }) else if (oldId != null) services.credentials.move(oldId, src.id)
                if (form.auth == com.brudre.streaming.sources.AuthMethod.NONE && type != com.brudre.streaming.sources.SourceType.TORZNAB && !(r.config?.contains("{key}") ?: false) && !clean.contains("{key}")) services.credentials.put(src.id, null)
                afterSourcesChanged(*listOfNotNull(oldId, src.id).toTypedArray())
                onResult(true, "${type.label} salvo: ${r.test.message}", null)
            } catch (e: Exception) {
                undoPin()
                onResult(false, e.message ?: "Não foi possível salvar", e.toString())
            }
        }
    }

    /** Tem credencial guardada? (para a tela mostrar "guardada" sem nunca exibir o valor) */
    fun hasCredential(id: String) = services.credentials.has(id)

    /**
     * Salvar sem validar: registra uma fonte temporariamente indisponível, DESATIVADA até um teste dar certo.
     * O formato vem do endereço (extensão/marcadores); as regras de segurança continuam valendo.
     */
    fun saveSourceUnvalidated(oldId: String?, name: String, url: String, onResult: (Boolean, String, String?) -> Unit) {
        val clean = url.trim()
        val type = com.brudre.streaming.sources.SourceDetector.guessByAddress(clean)
            ?: return onResult(false, "Não dá para saber o formato só pelo endereço. Use um endereço terminado em .json, .rss, .m3u, .m3u8, .torrent, .mp4 ou um modelo com {query}.", null)
        services.sourceStore.duplicateOf(clean, oldId)?.let { d ->
            return onResult(false, "Fonte duplicada: este endereço já está cadastrado como \"${d.name}\".", null)
        }
        if (!com.brudre.streaming.sources.UserSourcesJson.isSafe(type, clean)) {
            return onResult(false, "${com.brudre.streaming.sources.ErrorKind.BLOCKED.title}: ${com.brudre.streaming.sources.ErrorKind.BLOCKED.hint}", com.brudre.streaming.sources.UrlGuard.check(clean))
        }
        val src = com.brudre.streaming.sources.UserSource(
            id = com.brudre.streaming.sources.UserSource.idFor(type, clean),
            name = name.trim().ifBlank { com.brudre.streaming.sources.UrlGuard.hostOf(clean) ?: type.label }.take(60),
            url = clean, type = type, enabled = false, pending = true, priority = services.sourceStore.current.size,
            lastTest = com.brudre.streaming.sources.SourceTest(false, System.currentTimeMillis(), "Salva sem validar: desativada até ser validada e ativada por você", 0),
        )
        try {
            if (oldId != null) services.sourceStore.replace(oldId, src) else services.sourceStore.put(src)
            afterSourcesChanged(*listOfNotNull(oldId, src.id).toTypedArray())
            onResult(true, "${type.label} salva sem validar (desativada: teste e depois ative)", null)
        } catch (e: Exception) {
            onResult(false, e.message ?: "Não foi possível salvar", e.toString())
        }
    }

    fun testSource(id: String, onResult: (String) -> Unit = {}) {
        val src = services.sourceStore.get(id) ?: return
        _testingSources.value = _testingSources.value + id
        viewModelScope.launch {
            try { onResult(runTest(src)) } finally { _testingSources.value = _testingSources.value - id }
        }
    }

    /**
     * Testa uma fonte. Fonte salva sem validação passa pela identificação COMPLETA do formato (sem presumir o tipo):
     * só é considerada validada se o formato detectado for o mesmo, o endereço for seguro e a estrutura passar —
     * e mesmo assim continua desativada até o usuário ativar.
     */
    private suspend fun runTest(src: com.brudre.streaming.sources.UserSource): String {
        val store = services.sourceStore
        if (src.pending) {
            val r = services.inspector.inspect(src.url, key = services.credentials.get(src.id), auth = src.auth)
            return when {
                r.type == null || !r.test.ok -> { store.saveTest(src.id, r.test); "${src.name}: ainda não validada — ${r.test.message}" }
                r.type != src.type -> {
                    store.saveTest(src.id, r.test.copy(ok = false, message = "Formato encontrado (${r.type.label}) é diferente do salvo (${src.type.label}): edite a fonte", kind = com.brudre.streaming.sources.ErrorKind.FORMAT.name))
                    "${src.name}: formato diferente do salvo — não validada"
                }
                !com.brudre.streaming.sources.UserSourcesJson.isSafe(r.type, src.url) -> "${src.name}: bloqueada pelas regras de segurança"
                else -> { store.markValidated(src.id, r.test, r.config); afterSourcesChanged(src.id); "${src.name}: validada (${r.type.label}, ${r.test.message}). Ative quando quiser." }
            }
        }
        val r = services.inspector.inspect(src.url, forcedType = src.type, key = services.credentials.get(src.id), auth = src.auth)
        store.saveTest(src.id, r.test, r.config)
        return "${src.name}: ${if (r.test.ok) "OK" else "falhou"} — ${r.test.message}"
    }

    /**
     * Atualizar: descarta o cache, IDENTIFICA O FORMATO DE NOVO e testa. Se o endereço passou a servir outro formato
     * (ex.: JSON → RSS), a fonte é convertida, mantendo nome, prioridade, histórico e estado.
     */
    fun refreshSource(id: String, onResult: (String) -> Unit = {}) {
        val src = services.sourceStore.get(id) ?: return
        afterSourcesChanged(id)
        _testingSources.value = _testingSources.value + id
        viewModelScope.launch {
            try {
                val key = services.credentials.get(src.id)
                // Formato escolhido à mão é respeitado; senão, identifica de novo.
                val r = services.inspector.inspect(src.url, forcedType = if (src.manualType) src.type else null, key = key, auth = src.auth)
                val t = r.type
                if (t != null && r.test.ok && t != src.type && com.brudre.streaming.sources.UserSourcesJson.isSafe(t, src.url)) {
                    val converted = src.copy(id = com.brudre.streaming.sources.UserSource.idFor(t, src.url), type = t, lastTest = r.test, config = r.config)
                    services.sourceStore.replace(src.id, converted)
                    services.credentials.move(src.id, converted.id)
                    afterSourcesChanged(src.id, converted.id)
                    onResult("${src.name}: formato redetectado — agora ${t.label} (antes ${src.type.label}). ${r.test.message}")
                } else if (src.pending) {
                    onResult(runTest(src))
                } else {
                    val test = if (t == null) r.test else if (t == src.type) r.test else services.inspector.inspect(src.url, forcedType = src.type, key = key, auth = src.auth).test
                    services.sourceStore.saveTest(src.id, test, if (t == src.type) r.config else null)
                    onResult("${src.name}: ${if (test.ok) "OK" else "falhou"} — ${test.message}")
                }
            } finally {
                _testingSources.value = _testingSources.value - id
            }
        }
    }

    fun testAllSources(onResult: (String) -> Unit) {
        viewModelScope.launch {
            val list = services.sourceStore.current
            var ok = 0
            list.forEach { s ->
                _testingSources.value = _testingSources.value + s.id
                val msg = runCatching { runTest(s) }.getOrNull()
                if (services.sourceStore.get(s.id)?.lastTest?.ok == true && msg != null) ok++
                _testingSources.value = _testingSources.value - s.id
            }
            onResult("$ok de ${list.size} fontes funcionando")
        }
    }

    fun moveSource(id: String, delta: Int) { services.sourceStore.move(id, delta); afterSourcesChanged() }

    fun toggleSource(id: String, onResult: (String) -> Unit = {}) {
        val s = services.sourceStore.get(id) ?: return
        // Salva sem validação: só pode ser ativada depois de validada (formato, segurança e compatibilidade).
        if (!s.enabled && s.pending) { onResult("${s.name}: teste a fonte primeiro — ela só pode ser ativada depois de validada."); return }
        services.sourceStore.setEnabled(id, !s.enabled)
        afterSourcesChanged(id)
    }

    fun deleteSource(id: String) { services.sourceStore.remove(id); services.credentials.put(id, null); afterSourcesChanged(id) }

    /** Exporta para um arquivo na pasta do app (acessível por gerenciadores de arquivos da TV). */
    fun exportSources(onResult: (String) -> Unit) {
        viewModelScope.launch {
            onResult(runCatching {
                withContext(Dispatchers.IO) {
                    val f = services.inspector.exportFile(getApplication())
                    f.parentFile?.mkdirs()
                    f.writeText(services.sourceStore.export())
                    "${services.sourceStore.current.size} fontes exportadas para ${f.absolutePath}"
                }
            }.getOrElse { "Falha ao exportar: ${it.message}" })
        }
    }

    /** Importa do arquivo exportado (endereço vazio) ou de um endereço https:// com o mesmo formato. */
    fun importSources(url: String?, onResult: (String) -> Unit) {
        viewModelScope.launch {
            onResult(runCatching {
                val text = withContext(Dispatchers.IO) {
                    if (url.isNullOrBlank() || url.trim() == "https://") services.inspector.exportFile(getApplication()).readText()
                    else services.inspector.fetchText(url.trim())
                }
                val n = services.sourceStore.import(text)
                afterSourcesChanged(*services.sourceStore.current.map { it.id }.toTypedArray())
                "$n fontes importadas"
            }.getOrElse { "Falha ao importar: ${if (it is java.io.FileNotFoundException) "nenhum arquivo exportado encontrado" else it.message}" })
        }
    }

    /** Abre no player um magnet, .torrent, HLS ou vídeo HTTPS colado pelo usuário. */
    fun openLink(input: String, onLaunch: (Intent) -> Unit, onError: (String) -> Unit) {
        val clean = input.trim()
        // Magnet e .torrent entram no histórico e passam pela lista de arquivos (escolha do episódio).
        if (clean.startsWith("magnet:", true)) {
            if (!sources.torrentAllowed) return onError("BitTorrent está desativado em Configurações > Fontes de conteúdo.")
            launcher = launcher ?: onLaunch
            val msg = addMagnet(clean)
            if (!msg.startsWith("Magnet adicionado")) onError(msg)
            return
        }
        if (clean.startsWith("https://", true) && clean.substringBefore('?').endsWith(".torrent", true)) {
            if (!sources.torrentAllowed) return onError("BitTorrent está desativado em Configurações > Fontes de conteúdo.")
            launcher = launcher ?: onLaunch
            val msg = addTorrentUrl(clean)
            if (!msg.startsWith(".torrent adicionado")) onError(msg)
            return
        }
        val (t, p) = com.brudre.streaming.sources.FeedParser.manual(input)
            ?: return onError("Link inválido ou bloqueado. Use magnet:, um arquivo .torrent ou um endereço https:// de vídeo/HLS.")
        if (p.directUrl == null && !sources.torrentAllowed) return onError("BitTorrent está desativado em Configurações > Fontes de conteúdo.")
        onLaunch(PlayerActivity.intent(getApplication<Application>(), t, p, 0L))
    }

    // ------------------------------------------------------------ atualização
    val update: StateFlow<UpdateState> = services.updater.state

    fun checkUpdate(force: Boolean = false) {
        viewModelScope.launch { services.updater.check(force) }
    }

    fun startUpdate() {
        when (val s = update.value) {
            is UpdateState.Available -> viewModelScope.launch { services.updater.downloadAndInstall(s.release) }
            is UpdateState.ReadyToInstall -> services.updater.install(s.release, s.file)
            is UpdateState.NeedsPermission -> services.updater.openPermissionSettings()
            is UpdateState.Error, UpdateState.Idle, UpdateState.UpToDate -> checkUpdate(force = true)
            else -> Unit
        }
    }

    /** Ao voltar da tela de permissão do Android, tenta instalar de novo. */
    fun resumeUpdateIfPending() {
        val s = update.value
        if (s is UpdateState.NeedsPermission) services.updater.install(s.release, s.file)
    }

    init {
        checkUpdate()
    }

    // ------------------------------------------------------- controle dos pais
    private val _unlockedUntil = MutableStateFlow(0L)
    val unlockedUntil: StateFlow<Long> = _unlockedUntil.asStateFlow()

    fun isUnlocked(): Boolean = System.currentTimeMillis() < _unlockedUntil.value

    /** O título passa da classificação liberada e a sessão não foi desbloqueada com o PIN. */
    fun isLocked(rating: String?): Boolean =
        Parental.needsPin(rating, services.settings.current.maxRating) && !isUnlocked()

    /** Verifica o PIN (ou cria, se ainda não existir). Desbloqueia por 30 min. Retorna erro ou null. */
    fun submitPin(pin: String): String? {
        val hash = services.settings.current.pinHash
        if (hash.isEmpty()) {
            services.settings.update { it.copy(pinHash = Parental.hash(pin)) }
        } else if (!Parental.check(pin, hash)) {
            return "Senha incorreta"
        }
        _unlockedUntil.value = System.currentTimeMillis() + 30 * 60_000L
        return null
    }

    fun lockNow() { _unlockedUntil.value = 0L }

    fun changePin(newPin: String) {
        services.settings.update { it.copy(pinHash = Parental.hash(newPin)) }
    }

    // ---------------------------------------------------------------- busca
    private val _searchResults = MutableStateFlow<Load<List<Title>>?>(null)
    val searchResults: StateFlow<Load<List<Title>>?> = _searchResults.asStateFlow()
    private val _channelResults = MutableStateFlow<List<LiveChannel>>(emptyList())
    val channelResults: StateFlow<List<LiveChannel>> = _channelResults.asStateFlow()
    var lastQuery: String = ""
        private set
    private var searchJob: Job? = null

    fun search(query: String) {
        val q = query.trim()
        lastQuery = q
        searchJob?.cancel()
        searchPage = 1
        searchMoreFrom = emptySet()
        _searchHasMore.value = false
        if (q.length < 2) {
            _searchResults.value = Load.Error("Digite pelo menos 2 letras")
            return
        }
        _searchResults.value = Load.Loading
        _channelResults.value = emptyList()
        searchJob = viewModelScope.launch {
            delay(150)
            launch { _channelResults.value = runCatching { live.search(q) }.getOrDefault(emptyList()) }
            _searchResults.value = try {
                Load.Ok(sources.search(q))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Error(e.message ?: "Falha na pesquisa")
            }
            // Quais fontes de pesquisa têm mais páginas (a 1ª página já está no cache: sem nova consulta).
            searchMoreFrom = runCatching { services.searchRepo.page(com.brudre.streaming.sources.SearchTemplate.Query(catalog.cleanQuery(q)), 1).moreFrom }.getOrDefault(emptySet())
            _searchHasMore.value = searchMoreFrom.isNotEmpty()
        }
    }

    private var searchPage = 1
    private var searchMoreFrom: Set<String> = emptySet()
    private val _searchHasMore = MutableStateFlow(false)
    val searchHasMore: StateFlow<Boolean> = _searchHasMore.asStateFlow()
    private val _searchLoadingMore = MutableStateFlow(false)
    val searchLoadingMore: StateFlow<Boolean> = _searchLoadingMore.asStateFlow()

    /** Próxima página só das fontes que ainda têm resultados; sem repetir títulos; até [MAX_SEARCH_RESULTS]. */
    fun searchMore() {
        val current = (_searchResults.value as? Load.Ok)?.value ?: return
        if (_searchLoadingMore.value || searchMoreFrom.isEmpty()) return
        _searchLoadingMore.value = true
        val q = lastQuery
        searchJob = viewModelScope.launch {
            try {
                val page = services.searchRepo.page(com.brudre.streaming.sources.SearchTemplate.Query(catalog.cleanQuery(q)), searchPage + 1, only = searchMoreFrom)
                searchPage++
                val merged = (current + page.hits.map { it.item.title }).distinctBy { it.id }.take(MAX_SEARCH_RESULTS)
                _searchResults.value = Load.Ok(merged)
                searchMoreFrom = if (merged.size >= MAX_SEARCH_RESULTS || merged.size == current.size) emptySet() else page.moreFrom
                _searchHasMore.value = searchMoreFrom.isNotEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toastFlow.tryEmit(com.brudre.streaming.sources.ErrorClassifier.classify(e).summary)
            } finally {
                _searchLoadingMore.value = false
            }
        }
    }

    // --------------------------------------------------------- configurações
    val settings: StateFlow<SettingsValues> = services.settings.values

    fun updateSettings(transform: (SettingsValues) -> SettingsValues) {
        val before = services.settings.current
        services.settings.update(transform)
        val after = services.settings.current
        if (before.quality != after.quality) reloadDetails()
        if (before.maxRating != after.maxRating) {
            rows.forEach { (k, v) -> if (k.startsWith("tmdb_")) load(k, v, false) }
        }
    }

    private val _cacheUsage = MutableStateFlow(0L)
    val cacheUsage: StateFlow<Long> = _cacheUsage.asStateFlow()

    fun refreshCacheUsage() {
        viewModelScope.launch { _cacheUsage.value = withContext(Dispatchers.IO) { services.cacheUsage() } }
    }

    fun clearCache(onDone: (Long) -> Unit) {
        viewModelScope.launch {
            val freed = withContext(Dispatchers.IO) { services.clearTemporaryData() }
            rows.forEach { (k, v) -> if (v.value is Load.Error) load(k, v, true) }
            _cacheUsage.value = withContext(Dispatchers.IO) { services.cacheUsage() }
            onDone(freed)
        }
    }

    fun clearHistory(onDone: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { library.clearHistory() }
            onDone()
        }
    }

    // ---------------------------------------------------------------- rede
    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                val now = withContext(Dispatchers.IO) { services.isOnline() }
                if (now && !_online.value) rows.forEach { (k, v) -> if (v.value is Load.Error) load(k, v, true) }
                _online.value = now
                delay(4000)
            }
        }
    }
}
