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
) {
    private val memory = ConcurrentHashMap<String, List<LiveChannel>>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val dir get() = File(app.cacheDir, "live").apply { mkdirs() }
    private val store = app.getSharedPreferences("brudre_live_channels", Context.MODE_PRIVATE)

    private val _offline = MutableStateFlow<Set<String>>(emptySet())
    /** Canais que falharam nesta sessão (mostrados como "fora do ar"). */
    val offline: StateFlow<Set<String>> = _offline.asStateFlow()

    val enabled: Boolean get() = PROVIDER_ID !in settings.current.disabledSources

    init { refreshRegistry() }

    fun refreshRegistry() {
        board.register(PROVIDER_ID, "TV ao Vivo (iptv-org)", "Canais abertos de listas públicas; canais pagos e adultos são removidos", enabled)
        val custom = customLists()
        custom.forEach { l -> board.register(l.id, "Lista: ${l.name}", l.url, l.id !in settings.current.disabledSources, removable = true) }
        board.all.value.filter { it.id.startsWith(CUSTOM_PREFIX) && custom.none { c -> c.id == it.id } }.forEach { board.unregister(it.id) }
    }

    fun builtInLists(): List<LiveList> = BUILT_IN

    fun customLists(): List<LiveList> = settings.current.customLists.mapNotNull { entry ->
        val name = entry.substringBefore('|').trim()
        val url = entry.substringAfter('|').trim()
        if (UrlGuard.isAllowed(url)) LiveList(CUSTOM_PREFIX + M3uParser.stableId("custom", url), name.ifBlank { "Minha lista" }, url, builtIn = false) else null
    }

    fun list(id: String): LiveList? = (BUILT_IN + customLists()).firstOrNull { it.id == id }

    fun isListEnabled(l: LiveList): Boolean =
        if (l.builtIn) enabled else l.id !in settings.current.disabledSources

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
            runCatching { LiveChannel.listFromJson(file.readText()) }.getOrNull()?.let { memory[l.id] = it; return it }
        }
        val statusId = if (l.builtIn) PROVIDER_ID else l.id
        val t0 = System.currentTimeMillis()
        return try {
            val result = fetch(l.url, l.id).let { r ->
                if (r.channels.isEmpty() && l.fallbackUrl != null) fetch(l.fallbackUrl, l.id) else r
            }
            Log.i(TAG, "Lista ${l.id}: ${result.channels.size} canais de ${result.total} (removidos: ${result.rejected})")
            board.success(statusId, System.currentTimeMillis() - t0)
            memory[l.id] = result.channels
            runCatching { file.writeText(LiveChannel.listToJson(result.channels)) }
            result.channels
        } catch (e: Exception) {
            val fallback = l.fallbackUrl?.takeIf { e !is BlockedUrlException }?.let { runCatching { fetch(it, l.id) }.getOrNull() }
            if (fallback != null) {
                board.success(statusId, System.currentTimeMillis() - t0)
                memory[l.id] = fallback.channels
                runCatching { file.writeText(LiveChannel.listToJson(fallback.channels)) }
                return fallback.channels
            }
            board.failure(statusId, e.message ?: "Falha ao baixar a lista")
            if (file.exists()) {
                runCatching { LiveChannel.listFromJson(file.readText()) }.getOrNull()?.let { memory[l.id] = it; return it }
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
        settings.update { s -> s.copy(customLists = (s.customLists.filterNot { it.substringAfter('|') == clean } + "${name.replace('|', ' ').trim()}|$clean").takeLast(MAX_CUSTOM)) }
        refreshRegistry()
        r.channels.size
    }

    fun removeCustomList(id: String) {
        val l = customLists().firstOrNull { it.id == id } ?: return
        settings.update { s -> s.copy(customLists = s.customLists.filterNot { it.substringAfter('|').trim() == l.url }, disabledSources = s.disabledSources - id) }
        memory.remove(id)
        File(dir, "$id.json").delete()
        refreshRegistry()
    }

    fun setListEnabled(id: String, on: Boolean) {
        settings.update { s -> s.copy(disabledSources = if (on) s.disabledSources - id else s.disabledSources + id) }
        refreshRegistry()
    }

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

    fun markOffline(id: String) { _offline.value = _offline.value + id }
    fun markOnline(id: String) { if (id in _offline.value) _offline.value = _offline.value - id }

    fun trimMemory() {
        memory.keys.filter { it != "iptv_br" }.forEach { memory.remove(it) }
    }

    fun clearCache(): Long {
        val size = dir.listFiles()?.sumOf { it.length() } ?: 0L
        dir.listFiles()?.forEach { it.delete() }
        memory.clear()
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
        private const val IPTV = "https://iptv-org.github.io/iptv"
        private const val IPTV_MIRROR = "https://raw.githubusercontent.com/iptv-org/iptv/gh-pages"

        private fun builtIn(id: String, name: String, path: String) = LiveList(id, name, "$IPTV/$path", true, "$IPTV_MIRROR/$path")

        val BUILT_IN = listOf(
            builtIn("iptv_br", "Brasil", "countries/br.m3u"),
            builtIn("iptv_por", "Em português", "languages/por.m3u"),
            builtIn("iptv_news", "Mundo — Notícias", "categories/news.m3u"),
            builtIn("iptv_kids", "Mundo — Infantil", "categories/kids.m3u"),
            builtIn("iptv_docs", "Mundo — Documentários", "categories/documentary.m3u"),
        )

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
