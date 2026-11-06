package com.brudre.streaming.live

import android.content.Context
import android.util.Log
import com.brudre.streaming.data.AppSettings
import com.brudre.streaming.data.CatalogException
import com.brudre.streaming.data.CatalogRepository
import com.brudre.streaming.data.Kind
import com.brudre.streaming.data.Playable
import com.brudre.streaming.data.Title
import com.brudre.streaming.sources.BlockedUrlException
import com.brudre.streaming.sources.StatusBoard
import com.brudre.streaming.sources.UrlGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Lista M3U (embutida ou adicionada pelo usuário). */
data class LiveList(val id: String, val name: String, val url: String, val builtIn: Boolean, val fallbackUrl: String? = null)

/**
 * TV ao Vivo: listas públicas do projeto iptv-org e listas M3U adicionadas pelo usuário.
 * Download linha a linha, filtros de segurança (adulto, TV paga, URLs inseguras), cache em disco
 * de 12 h e última versão salva quando a rede falha.
 */
class LiveRepository(
    private val app: Context,
    private val guardedHttp: OkHttpClient,
    private val settings: AppSettings,
    private val board: StatusBoard,
    private val isOnline: () -> Boolean,
    private val sources: com.brudre.streaming.sources.SourceStore,
) {
    private val memory = ConcurrentHashMap<String, List<LiveChannel>>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val dir get() = File(app.cacheDir, "live").apply { mkdirs() }
    private val store = app.getSharedPreferences("brudre_live_channels", Context.MODE_PRIVATE)

    /** Verificação real dos sinais (no aparelho), usada para ocultar canais fora do ar. */
    val health = LiveHealth(guardedHttp, File(app.cacheDir, "live"))

    private val _offline = MutableStateFlow<Set<String>>(emptySet())
    /** Canais que falharam nesta sessão (mostrados como "fora do ar"). */
    val offline: StateFlow<Set<String>> = _offline.asStateFlow()

    val enabled: Boolean get() = PROVIDER_ID !in settings.current.disabledSources

    init { refreshRegistry() }

    fun refreshRegistry() {
        board.register(PROVIDER_ID, "TV ao Vivo (iptv-org)", "Canais abertos de listas públicas; canais pagos e adultos são removidos", enabled)
        val custom = customLists()
        custom.forEach { l -> board.register(l.id, "Lista: ${l.name}", l.url, sources.get(l.id)?.enabled == true, removable = true) }
        board.all.value.filter { it.id.startsWith(CUSTOM_PREFIX) && custom.none { c -> c.id == it.id } }.forEach { board.unregister(it.id) }
    }

    fun builtInLists(): List<LiveList> = BUILT_IN

    /** Playlists M3U cadastradas em Gerenciar fontes, na ordem de prioridade. */
    fun customLists(): List<LiveList> = sources.ofType(com.brudre.streaming.sources.SourceType.M3U)
        .filter { UrlGuard.isAllowed(it.url) }
        .map { LiveList(it.id, it.name, it.url, builtIn = false) }

    fun list(id: String): LiveList? = (BUILT_IN + customLists()).firstOrNull { it.id == id }

    fun isListEnabled(l: LiveList): Boolean =
        if (l.builtIn) enabled else sources.get(l.id)?.enabled == true

    suspend fun channels(listId: String, force: Boolean = false): List<LiveChannel> {
        val l = list(listId) ?: throw CatalogException("Lista não encontrada")
        if (!isListEnabled(l)) throw CatalogException("Fonte desativada em Configurações")
        if (!force) memory[l.id]?.let { return it }
        val lock = locks.getOrPut(l.id) { Mutex() }
        return lock.withLock {
            if (!force) memory[l.id]?.let { return@withLock it }
            withContext(Dispatchers.IO) { loadLocked(l, force) }
        }
    }

    private fun loadLocked(l: LiveList, force: Boolean): List<LiveChannel> {
        val file = File(dir, "${l.id}.json")
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < TTL_MS
        if (!force && fresh) {
            runCatching { LiveChannel.listFromJson(file.readText()) }.getOrNull()?.let { keep(l.id, it); return it }
        }
        val statusId = if (l.builtIn) PROVIDER_ID else l.id
        val t0 = System.currentTimeMillis()
        return try {
            val result = fetch(l.url, l.id).let { r ->
                if (r.channels.isEmpty() && l.fallbackUrl != null) fetch(l.fallbackUrl, l.id) else r
            }
            Log.i(TAG, "Lista ${l.id}: ${result.channels.size} canais de ${result.total} (removidos: ${result.rejected})")
            board.success(statusId, System.currentTimeMillis() - t0)
            keep(l.id, result.channels)
            runCatching { file.writeText(LiveChannel.listToJson(result.channels)) }
            result.channels
        } catch (e: Exception) {
            val fallback = l.fallbackUrl?.takeIf { e !is BlockedUrlException }?.let { runCatching { fetch(it, l.id) }.getOrNull() }
            if (fallback != null) {
                board.success(statusId, System.currentTimeMillis() - t0)
                keep(l.id, fallback.channels)
                runCatching { file.writeText(LiveChannel.listToJson(fallback.channels)) }
                return fallback.channels
            }
            board.failure(statusId, e.message ?: "Falha ao baixar a lista")
            if (file.exists()) {
                runCatching { LiveChannel.listFromJson(file.readText()) }.getOrNull()?.let { keep(l.id, it); return it }
            }
            throw if (e is IOException) CatalogException(if (isOnline()) "Não foi possível baixar a lista de canais" else "Sem conexão com a internet", e)
            else CatalogException(e.message ?: "Lista inválida", e)
        }
    }

    /** Baixa e interpreta a lista sem guardar o texto inteiro na memória. */
    fun fetch(url: String, source: String): M3uParser.Result {
        UrlGuard.check(url)?.let { throw BlockedUrlException(it) }
        val req = Request.Builder().url(url).header("User-Agent", CatalogRepository.USER_AGENT).build()
        guardedHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Servidor da lista respondeu ${resp.code}")
            val len = resp.body?.contentLength() ?: -1L
            if (len > MAX_BYTES) throw IOException("Lista grande demais para o aparelho")
            val src = resp.body?.source() ?: throw IOException("Lista vazia")
            var lines = 0
            val seq = generateSequence {
                if (lines++ > MAX_LINES) null else src.readUtf8Line()
            }
            return M3uParser.parse(seq, source)
        }
    }

    /** Valida uma lista nova antes de salvá-la (endereço seguro e ao menos um canal HLS). */
    suspend fun addCustomList(name: String, url: String): Int = withContext(Dispatchers.IO) {
        val clean = url.trim()
        UrlGuard.check(clean)?.let { throw CatalogException(it) }
        val r = try { fetch(clean, "custom") } catch (e: IllegalArgumentException) { throw CatalogException(e.message ?: "Arquivo inválido") }
        if (r.channels.isEmpty()) throw CatalogException("Nenhum canal HLS válido nessa lista (${r.total} itens analisados)")
        val id = com.brudre.streaming.sources.UserSource.idFor(com.brudre.streaming.sources.SourceType.M3U, clean)
        sources.put(com.brudre.streaming.sources.UserSource(id, name.trim().ifBlank { "Minha lista" }.take(60), clean, com.brudre.streaming.sources.SourceType.M3U,
            priority = sources.current.size, lastTest = com.brudre.streaming.sources.SourceTest(true, System.currentTimeMillis(), "${r.channels.size} canais", r.channels.size)))
        refreshRegistry()
        r.channels.size
    }

    fun removeCustomList(id: String) {
        sources.remove(id)
        memory.remove(id)
        File(dir, "$id.json").delete()
        refreshRegistry()
    }

    fun setListEnabled(id: String, on: Boolean) {
        if (id == PROVIDER_ID) settings.update { s -> s.copy(disabledSources = if (on) s.disabledSources - id else s.disabledSources + id) }
        else sources.setEnabled(id, on)
        memory.remove(id)
        refreshRegistry()
    }

    /** Descarta a lista em cache (após editar ou atualizar a fonte). */
    fun invalidate(id: String) { memory.remove(id); File(dir, "$id.json").delete() }

    /** Canais já carregados (para pesquisa e favoritos). */
    fun loaded(): List<LiveChannel> = memory.values.flatten().distinctBy { it.id }

    suspend fun search(term: String): List<LiveChannel> {
        if (!enabled) return emptyList()
        runCatching { channels("iptv_br") }
        runCatching { channels("iptv_por") }
        return LiveCategories.search(loaded(), term).take(40)
    }

    fun find(id: String): LiveChannel? =
        loaded().firstOrNull { it.id == id } ?: store.getString(id, null)?.let { runCatching { LiveChannel.fromJson(JSONObject(it)) }.getOrNull() }

    /** Guarda os dados do canal para reproduzir os favoritos mesmo antes de a lista carregar. */
    fun remember(ch: LiveChannel) { store.edit().putString(ch.id, ch.toJson().toString()).apply() }

    fun markOffline(id: String) { _offline.value = _offline.value + id; health.mark(id, false, "falhou no player") }
    fun markOnline(id: String) { if (id in _offline.value) _offline.value = _offline.value - id; health.mark(id, true) }

    /** Esconde os canais que falharam na verificação e usa o endereço que respondeu. */
    fun visible(list: List<LiveChannel>): List<LiveChannel> =
        list.filter { health.status(it.id) != false && it.id !in _offline.value }.map { health.withWorkingUrl(it) }

    /** Verifica em segundo plano as listas principais (Brasil e português). */
    suspend fun checkMain() {
        if (!enabled) return
        runCatching { health.check(channels("iptv_br")) }
        runCatching { health.check(channels("iptv_por").filter { it.country != "br" }) }
    }

    /** Guarda a lista em memória; acima de [MAX_LISTS_IN_MEMORY] listas, as do mundo mais antigas saem (ficam no disco). */
    private fun keep(id: String, list: List<LiveChannel>) {
        memory[id] = list
        if (memory.size > MAX_LISTS_IN_MEMORY) {
            memory.keys.filter { it != id && it != "iptv_br" && it != "iptv_por" }.take(memory.size - MAX_LISTS_IN_MEMORY).forEach { memory.remove(it) }
        }
    }

    fun trimMemory() {
        memory.keys.filter { it != "iptv_br" }.forEach { memory.remove(it) }
    }

    fun clearCache(): Long {
        val size = dir.listFiles()?.sumOf { it.length() } ?: 0L
        dir.listFiles()?.forEach { it.delete() }
        memory.clear()
        health.clear()
        return size
    }

    companion object {
        private const val TAG = "BruDreLive"
        const val PROVIDER_ID = "iptv"
        const val CUSTOM_PREFIX = "m3u_"
        private const val TTL_MS = 12 * 60 * 60 * 1000L
        private const val MAX_BYTES = 8L * 1024 * 1024
        private const val MAX_LINES = 60_000
        private const val MAX_CUSTOM = 10
        private const val MAX_LISTS_IN_MEMORY = 7
        private const val IPTV = "https://iptv-org.github.io/iptv"
        private const val IPTV_MIRROR = "https://raw.githubusercontent.com/iptv-org/iptv/gh-pages"

        private fun builtIn(id: String, name: String, path: String) = LiveList(id, name, "$IPTV/$path", true, "$IPTV_MIRROR/$path")

        val BUILT_IN = listOf(
            builtIn("iptv_br", "Brasil", "countries/br.m3u"),
            builtIn("iptv_por", "Em português", "languages/por.m3u"),
            builtIn("iptv_news", "Mundo — Notícias", "categories/news.m3u"),
            builtIn("iptv_kids", "Mundo — Infantil", "categories/kids.m3u"),
            builtIn("iptv_docs", "Mundo — Documentários", "categories/documentary.m3u"),
            builtIn("iptv_movies", "Mundo — Filmes", "categories/movies.m3u"),
            builtIn("iptv_series", "Mundo — Séries", "categories/series.m3u"),
            builtIn("iptv_ent", "Mundo — Entretenimento", "categories/entertainment.m3u"),
            builtIn("iptv_music", "Mundo — Música", "categories/music.m3u"),
            builtIn("iptv_sports", "Mundo — Esportes", "categories/sports.m3u"),
            builtIn("iptv_c_us", "Estados Unidos", "countries/us.m3u"),
            builtIn("iptv_c_es", "Espanha", "countries/es.m3u"),
            builtIn("iptv_c_mx", "México", "countries/mx.m3u"),
            builtIn("iptv_c_ar", "Argentina", "countries/ar.m3u"),
            builtIn("iptv_c_fr", "França", "countries/fr.m3u"),
            builtIn("iptv_c_it", "Itália", "countries/it.m3u"),
        )

        /** Países de língua portuguesa (canais deles contam como "em português"). */
        val LUSOPHONE = setOf("br", "pt", "ao", "mz", "cv", "gw", "st", "tl")

        /**
         * Separa canais em português dos demais: país lusófono ou presente na lista "Em português" do iptv-org
         * (mesmo nome ou mesmo endereço). [portuguese] = canais da lista languages/por.
         */
        fun splitByPortuguese(list: List<LiveChannel>, portuguese: List<LiveChannel>): Pair<List<LiveChannel>, List<LiveChannel>> {
            val names = portuguese.map { M3uParser.normalizeName(it.name) }.toHashSet()
            val urls = portuguese.flatMap { listOf(it.url) + it.mirrors }.toHashSet()
            return list.partition { ch -> ch.country in LUSOPHONE || M3uParser.normalizeName(ch.name) in names || ch.url in urls || ch.mirrors.any { it in urls } }
        }

        fun LiveChannel.toTitle(): Title = Title(
            id = titleId,
            kind = Kind.LIVE,
            name = name,
            synopsis = listOfNotNull(LiveCategories.categoryOf(this).label, countryName(country), quality).joinToString(" • "),
            genres = listOf(LiveCategories.categoryOf(this).label),
            posterUrl = logo,
            bannerUrl = logo,
            license = "Lista pública iptv-org",
        )

        fun LiveChannel.toPlayable(): Playable = Playable(
            key = "live", label = "Ao vivo", season = 1, number = 1,
            directUrl = url, torrent = null, subtitles = emptyList(), durationSec = null, preferTorrent = false,
            mirrors = mirrors, live = true, headers = headers,
        )

        fun countryName(code: String?): String? = code?.let {
            runCatching { java.util.Locale("", it.uppercase()).getDisplayCountry(java.util.Locale("pt", "BR")) }.getOrNull()?.takeIf { n -> n.isNotBlank() && n != it.uppercase() }
        }
    }
}
