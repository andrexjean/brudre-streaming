package com.brudre.streaming.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.brudre.streaming.BruDreApp
import com.brudre.streaming.data.Offer
import com.brudre.streaming.data.Playable
import com.brudre.streaming.data.StreamingApps
import com.brudre.streaming.data.WatchProvider
import com.brudre.streaming.data.SettingsValues
import com.brudre.streaming.data.Title
import com.brudre.streaming.data.TitleDetails
import com.brudre.streaming.data.db.FavoriteEntity
import com.brudre.streaming.data.db.ProgressEntity
import com.brudre.streaming.player.PlayerActivity
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
    HOME("Início"), MOVIES("Filmes"), SERIES("Séries"), KIDS("Infantil"),
    MY_LIST("Minha Lista"), SEARCH("Pesquisa"), SETTINGS("Configurações"),
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val services = BruDreApp.services(app)
    private val catalog = services.catalog
    val tmdbEnabled: Boolean get() = catalog.tmdb.enabled
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
            if (key == "featured" || key == "tmdb_trending") rebuildHero()
        }
    }

    // --------------------------------------------------------------- herói
    private val _hero = MutableStateFlow(catalog.curated.featured())
    val hero: StateFlow<List<Title>> = _hero.asStateFlow()

    private fun rebuildHero() {
        val trending = (rows["tmdb_trending"]?.value as? Load.Ok)?.value.orEmpty().filter { it.bannerUrl != null }.take(5)
        if (trending.isNotEmpty()) {
            _hero.value = (trending + catalog.curated.featured().take(1)).distinctBy { it.id }
            return
        }
        val extra = (rows["featured"]?.value as? Load.Ok)?.value.orEmpty().take(2)
        _hero.value = (catalog.curated.featured() + extra).distinctBy { it.id }.take(6)
    }

    init {
        if (catalog.tmdb.enabled) row("tmdb_trending")
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
                Load.Ok(catalog.details(title))
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
        onExternal: (TitleDetails, WatchProvider?) -> Unit = { _, _ -> },
    ) {
        if (_playLoading.value != null) return
        _playLoading.value = title.id
        viewModelScope.launch {
            try {
                if (title.isTmdb) {
                    // Lançamentos tocam no app oficial: escolhe o melhor serviço disponível.
                    val d = details ?: catalog.details(title)
                    onExternal(d, bestProvider(d.providers))
                    return@launch
                }
                val d = details ?: if (playable == null) catalog.details(title) else null
                val target = playable ?: run {
                    val list = d!!.playables.filter { it.hasSource }
                    if (list.isEmpty()) {
                        onError("Nenhuma fonte autorizada disponível para \"${title.name}\".")
                        return@launch
                    }
                    val last = library.latestFor(title.id)
                    list.firstOrNull { it.key == last?.episodeKey } ?: list.first()
                }
                val start = if (fromStart) 0L else library.resumePosition(title.id, target.key)
                onLaunch(PlayerActivity.intent(getApplication<Application>(), d?.title ?: title, target, start))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e.message ?: "Não foi possível iniciar a reprodução")
            } finally {
                _playLoading.value = null
            }
        }
    }

    /** Prioriza serviços que a família assina; depois grátis; nunca escolhe aluguel/compra sozinho. */
    fun bestProvider(list: List<WatchProvider>): WatchProvider? {
        val mine = services.settings.current.myServices
        return list.firstOrNull { it.offer == Offer.SUBSCRIPTION && StreamingApps.canonical(it.name) in mine }
            ?: list.firstOrNull { (it.offer == Offer.FREE || it.offer == Offer.ADS) }
            ?: list.firstOrNull { it.offer == Offer.SUBSCRIPTION && mine.isEmpty() }
    }

    // ---------------------------------------------------------------- busca
    private val _searchResults = MutableStateFlow<Load<List<Title>>?>(null)
    val searchResults: StateFlow<Load<List<Title>>?> = _searchResults.asStateFlow()
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
        searchJob = viewModelScope.launch {
            delay(150)
            _searchResults.value = try {
                Load.Ok(catalog.search(q))
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
        if (before.myServices != after.myServices || before.onlyMine != after.onlyMine) {
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
