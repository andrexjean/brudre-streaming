package com.brudre.streaming.live

import android.util.Log
import com.brudre.streaming.data.CatalogRepository
import com.brudre.streaming.sources.BlockedUrlException
import com.brudre.streaming.sources.UrlGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Verificação real de cada canal, feita no próprio aparelho (mesma rede e mesmo país do usuário):
 * manifesto HLS → variante → segmento mais recente. Canais sem sinal ficam ocultos.
 * Resultado salvo em disco: canal no ar vale 6 h, fora do ar 2 h.
 */
class LiveHealth(guarded: OkHttpClient, private val dir: File) {

    data class Entry(val ok: Boolean, val at: Long, val workingUrl: String?, val reason: String?) {
        fun fresh(now: Long) = now - at < if (ok) OK_TTL_MS else FAIL_TTL_MS
    }

    data class Progress(val done: Int, val total: Int)

    private val client = guarded.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    private val map = ConcurrentHashMap<String, Entry>()
    private val _results = MutableStateFlow<Map<String, Entry>>(emptyMap())
    val results: StateFlow<Map<String, Entry>> = _results.asStateFlow()
    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()
    private val running = Mutex()
    private val file get() = File(dir, "health.json")

    init {
        runCatching {
            if (file.exists()) {
                val o = JSONObject(file.readText())
                val now = System.currentTimeMillis()
                o.keys().forEach { k ->
                    val e = o.getJSONObject(k)
                    val entry = Entry(e.getBoolean("ok"), e.getLong("at"), e.optString("url").ifBlank { null }, e.optString("why").ifBlank { null })
                    if (entry.fresh(now)) map[k] = entry
                }
                _results.value = HashMap(map)
            }
        }
    }

    /** true = no ar, false = fora do ar, null = ainda não verificado. */
    fun status(id: String): Boolean? = map[id]?.takeIf { it.fresh(System.currentTimeMillis()) }?.ok

    /** Canal com o endereço que respondeu na verificação em primeiro lugar. */
    fun withWorkingUrl(ch: LiveChannel): LiveChannel {
        val url = map[ch.id]?.workingUrl ?: return ch
        if (url == ch.url) return ch
        return ch.copy(url = url, mirrors = (listOf(ch.url) + ch.mirrors).filter { it != url })
    }

    fun mark(id: String, ok: Boolean, reason: String? = null) {
        map[id] = Entry(ok, System.currentTimeMillis(), map[id]?.workingUrl, reason)
        _results.value = HashMap(map)
    }

    /** Verifica os canais ainda sem resultado recente (6 por vez). Devolve quantos estão no ar. */
    suspend fun check(channels: List<LiveChannel>, force: Boolean = false): Int = running.withLock {
        val now = System.currentTimeMillis()
        val todo = channels.distinctBy { it.id }.filter { force || map[it.id]?.fresh(now) != true }
        if (todo.isEmpty()) return@withLock channels.count { status(it.id) == true }
        _progress.value = Progress(0, todo.size)
        val gate = Semaphore(PARALLEL)
        var done = 0
        coroutineScope {
            todo.map { ch ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val entry = withTimeoutOrNull(PER_CHANNEL_MS) { checkChannel(ch) }
                            ?: Entry(false, System.currentTimeMillis(), null, "tempo esgotado")
                        map[ch.id] = entry
                        synchronized(this@LiveHealth) {
                            done++
                            if (done % 8 == 0 || done == todo.size) {
                                _progress.value = Progress(done, todo.size)
                                _results.value = HashMap(map)
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        _progress.value = null
        _results.value = HashMap(map)
        save()
        val ok = todo.count { map[it.id]?.ok == true }
        Log.i(TAG, "Verificação: $ok de ${todo.size} canais no ar")
        channels.count { status(it.id) == true }
    }

    private fun checkChannel(ch: LiveChannel): Entry {
        var reason: String? = null
        for (url in (listOf(ch.url) + ch.mirrors).take(3)) {
            reason = probe(url, ch.headers)
            if (reason == null) return Entry(true, System.currentTimeMillis(), url, null)
        }
        return Entry(false, System.currentTimeMillis(), null, reason)
    }

    /** null = canal no ar; senão o motivo da falha. */
    fun probe(url: String, headers: Map<String, String>): String? = try {
        probeOrThrow(url, headers)
    } catch (e: BlockedUrlException) {
        "bloqueado: ${e.message}"
    } catch (e: IOException) {
        e.message?.take(60) ?: "falha de rede"
    } catch (e: Exception) {
        e.javaClass.simpleName
    }

    fun probeOrThrow(url: String, headers: Map<String, String>): String? {
        val top = fetchPlaylist(url, headers) ?: return "não é HLS"
        var (playlistUrl, playlist) = top
        if (playlist.isMaster) {
            val v = HlsProbe.lightest(playlist) ?: return "sem variantes"
            val vUrl = HlsProbe.resolve(playlistUrl, v.uri) ?: return "variante inválida"
            val media = fetchPlaylist(vUrl, headers) ?: return "variante não é HLS"
            playlistUrl = media.first; playlist = media.second
        }
        var seg = HlsProbe.probeSegment(playlist) ?: return "sem segmentos"
        var segUrl = HlsProbe.resolve(playlistUrl, seg) ?: return "segmento inválido"
        if (segUrl.substringBefore('?').endsWith(".m3u8", true)) {
            val nested = fetchPlaylist(segUrl, headers) ?: return "sublista inválida"
            seg = HlsProbe.probeSegment(nested.second) ?: return "sem segmentos"
            segUrl = HlsProbe.resolve(nested.first, seg) ?: return "segmento inválido"
        }
        return segmentReason(segUrl, headers)
    }

    private fun request(url: String, headers: Map<String, String>) = Request.Builder().url(url)
        .header("User-Agent", headers["User-Agent"] ?: CatalogRepository.USER_AGENT)
        .apply { headers["Referer"]?.let { header("Referer", it) } }

    /** Baixa no máximo 256 KB da playlist; devolve a URL final (após redirecionamentos) e o conteúdo. */
    private fun fetchPlaylist(url: String, headers: Map<String, String>): Pair<String, HlsProbe.Playlist>? {
        if (!UrlGuard.isAllowed(url)) throw BlockedUrlException(UrlGuard.check(url) ?: "url")
        client.newCall(request(url, headers).get().build()).execute().use { r ->
            if (!r.isSuccessful) throw com.brudre.streaming.sources.HttpStatusException(r.code, "HTTP ${r.code}")
            val src = r.body?.source() ?: throw IOException("vazio")
            src.request(MAX_PLAYLIST)
            val text = src.buffer.snapshot().utf8().take(MAX_PLAYLIST.toInt())
            val p = HlsProbe.parse(text) ?: return null
            return r.request.url.toString() to p
        }
    }

    private fun segmentReason(url: String, headers: Map<String, String>): String? {
        client.newCall(request(url, headers).header("Range", "bytes=0-2047").get().build()).execute().use { r ->
            if (r.code == 416) return null // o servidor respondeu: o segmento existe
            if (!r.isSuccessful) return "segmento HTTP ${r.code}"
            val type = r.header("Content-Type")?.lowercase().orEmpty()
            if (type.startsWith("text/html")) return "segmento é página web"
            return null
        }
    }

    private fun save() {
        runCatching {
            val o = JSONObject()
            map.forEach { (k, e) ->
                o.put(k, JSONObject().put("ok", e.ok).put("at", e.at).put("url", e.workingUrl ?: "").put("why", e.reason ?: ""))
            }
            dir.mkdirs()
            file.writeText(o.toString())
        }
    }

    fun clear() { map.clear(); _results.value = emptyMap(); file.delete() }

    companion object {
        private const val TAG = "BruDreLive"
        private const val PARALLEL = 6
        private const val PER_CHANNEL_MS = 20_000L
        private const val OK_TTL_MS = 6 * 60 * 60 * 1000L
        private const val FAIL_TTL_MS = 2 * 60 * 60 * 1000L
        private const val MAX_PLAYLIST = 256L * 1024
    }
}
