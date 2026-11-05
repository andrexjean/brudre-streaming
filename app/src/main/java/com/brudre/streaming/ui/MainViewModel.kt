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
        _detailsTitle.value = title
        loadDetails(title)
    }

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

    private fun loadFeed(id: String, state: MutableStateFlow<Load<List<Title>>>, force: Boolean) {
        state.value = Load.Loading
        viewModelScope.launch {
            state.value = try {
                Load.Ok(services.feeds.items(id, force).map { it.title })
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

    /** Abre no player um magnet, .torrent, HLS ou vídeo HTTPS colado pelo usuário. */
    fun openLink(input: String, onLaunch: (Intent) -> Unit, onError: (String) -> Unit) {
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
