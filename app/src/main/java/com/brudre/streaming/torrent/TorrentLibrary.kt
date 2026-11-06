package com.brudre.streaming.torrent

import android.content.Context
import com.brudre.streaming.data.Kind
import com.brudre.streaming.data.Playable
import com.brudre.streaming.data.SubtitleRef
import com.brudre.streaming.data.Title
import com.brudre.streaming.data.TorrentRef
import com.brudre.streaming.data.optStringOrNull
import com.brudre.streaming.sources.ClassifiedException
import com.brudre.streaming.sources.ErrorKind
import com.brudre.streaming.sources.FeedParser
import com.brudre.streaming.sources.TitleMatcher
import com.brudre.streaming.sources.UrlGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Torrent adicionado pelo usuário (magnet colado/compartilhado ou .torrent importado). */
data class TorrentEntry(
    val hash: String,
    val name: String,
    val magnet: String? = null,
    val torrentUrl: String? = null,
    val localFile: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val lastPlayedAt: Long = 0,
    val videoCount: Int = 0,
    val totalSize: Long = 0,
    /** Associação opcional a um filme/série do catálogo (nome + ano, ou id TMDB "movie:603"). */
    val linkedName: String? = null,
    val linkedYear: String? = null,
    val linkedTmdb: String? = null,
) {
    val titleId: String get() = "${TorrentLibrary.PREFIX}$hash"
    val ref: TorrentRef get() = TorrentRef(torrentUrl, magnet, null, null, localFile)
    val displayName: String get() = linkedName ?: name

    fun toJson(): JSONObject = JSONObject().put("hash", hash).put("name", name)
        .put("magnet", magnet ?: JSONObject.NULL).put("url", torrentUrl ?: JSONObject.NULL).put("local", localFile ?: JSONObject.NULL)
        .put("added", addedAt).put("played", lastPlayedAt).put("videos", videoCount).put("size", totalSize)
        .put("linkName", linkedName ?: JSONObject.NULL).put("linkYear", linkedYear ?: JSONObject.NULL).put("linkTmdb", linkedTmdb ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = TorrentEntry(
            o.getString("hash"), o.optString("name"), o.optStringOrNull("magnet"), o.optStringOrNull("url"), o.optStringOrNull("local"),
            o.optLong("added"), o.optLong("played"), o.optInt("videos"), o.optLong("size"),
            o.optStringOrNull("linkName"), o.optStringOrNull("linkYear"), o.optStringOrNull("linkTmdb"),
        )
    }
}

/**
 * Histórico de torrents do usuário: reproduzir os próprios torrents sem cadastrá-los num catálogo.
 * - O .torrent importado é validado (bencode, tamanho, caminhos) e guardado em files/torrents/<hash>.torrent.
 * - Nenhum arquivo do torrent é executado ou aberto fora do player: só vídeos e legendas são lidos.
 */
class TorrentLibrary(context: Context, private val engine: TorrentEngine) {
    private val prefs = context.getSharedPreferences("brudre_torrents", Context.MODE_PRIVATE)
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<TorrentEntry>> = _entries.asStateFlow()
    val current: List<TorrentEntry> get() = _entries.value

    private fun load(): List<TorrentEntry> = runCatching {
        val a = JSONArray(prefs.getString(KEY, "[]"))
        (0 until a.length()).map { TorrentEntry.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    @Synchronized
    private fun save(list: List<TorrentEntry>) {
        val l = list.sortedByDescending { maxOf(it.addedAt, it.lastPlayedAt) }.take(MAX_ENTRIES)
        prefs.edit().putString(KEY, JSONArray().apply { l.forEach { put(it.toJson()) } }.toString()).commit()
        _entries.value = l
    }

    fun get(hash: String) = current.firstOrNull { it.hash == hash }

    private fun upsert(e: TorrentEntry): TorrentEntry {
        val old = get(e.hash)
        val merged = if (old == null) e else old.copy(
            magnet = e.magnet ?: old.magnet, torrentUrl = e.torrentUrl ?: old.torrentUrl, localFile = e.localFile ?: old.localFile,
            name = if (old.name == old.hash) e.name else old.name,
            videoCount = if (e.videoCount > 0) e.videoCount else old.videoCount, totalSize = if (e.totalSize > 0) e.totalSize else old.totalSize,
        )
        save(current.filterNot { it.hash == e.hash } + merged)
        return merged
    }

    /** Magnet colado, compartilhado ou aberto por outro app. */
    fun addMagnet(magnet: String, name: String? = null): TorrentEntry {
        val m = magnet.trim()
        if (!FeedParser.isValidMagnet(m)) throw ClassifiedException(ErrorKind.MAGNET, "Link magnet sem hash de torrent válido")
        val hash = TorrentContent.magnetHash(m) ?: throw ClassifiedException(ErrorKind.MAGNET, "Hash do magnet não suportado (use btih)")
        val dn = name ?: Regex("[?&]dn=([^&]+)").find(m)?.groupValues?.get(1)?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
        val known = File(engine.metaDir, "$hash.torrent").takeIf { it.exists() }?.let { runCatching { TorrentContent.parse(it.readBytes()) }.getOrNull() }
        return upsert(TorrentEntry(hash, (dn ?: known?.name ?: hash).take(120), magnet = m, videoCount = known?.videos?.size ?: 0, totalSize = known?.totalSize ?: 0))
    }

    /** Arquivo .torrent recebido (seletor de arquivos, "Abrir com", compartilhamento). */
    fun importBytes(bytes: ByteArray): TorrentEntry {
        val meta = TorrentContent.parse(bytes)
        val f = File(engine.metaDir, "${meta.infoHash}.torrent")
        if (!f.exists()) f.writeBytes(bytes)
        return upsert(TorrentEntry(meta.infoHash, meta.name.take(120), localFile = f.absolutePath, videoCount = meta.videos.size, totalSize = meta.totalSize))
    }

    /** .torrent publicado num endereço https:// (baixado e validado ao listar). */
    fun addUrl(url: String, name: String?): TorrentEntry {
        UrlGuard.check(url)?.let { throw ClassifiedException(ErrorKind.BLOCKED, it) }
        val hash = "url-" + com.brudre.streaming.live.M3uParser.stableId("torrent", url)
        return upsert(TorrentEntry(hash, (name ?: url.substringBefore('?').substringAfterLast('/').removeSuffix(".torrent")).take(120), torrentUrl = url))
    }

    /** Lista de arquivos: lê o .torrent (local, remoto ou metadados do magnet obtidos pela DHT). */
    suspend fun meta(ref: TorrentRef, onStatus: (String) -> Unit = {}): TorrentMeta = withContext(Dispatchers.IO) {
        val bytes = engine.metadata(ref, onStatus)
        val meta = TorrentContent.parse(bytes)
        current.firstOrNull { it.hash == meta.infoHash || (ref.torrentUrl != null && it.torrentUrl == ref.torrentUrl) }?.let { e ->
            val local = File(engine.metaDir, "${meta.infoHash}.torrent").takeIf { it.exists() }?.absolutePath
            val updated = e.copy(name = if (e.name == e.hash) meta.name else e.name, videoCount = meta.videos.size, totalSize = meta.totalSize, localFile = e.localFile ?: local)
            if (updated != e) save(current.map { if (it.hash == e.hash) updated else it })
        }
        meta
    }

    fun markPlayed(hash: String) { get(hash)?.let { e -> save(current.map { if (it.hash == hash) e.copy(lastPlayedAt = System.currentTimeMillis()) else it }) } }

    fun link(hash: String, name: String?, year: String?, tmdb: String? = null) {
        save(current.map { if (it.hash == hash) it.copy(linkedName = name?.trim()?.ifBlank { null }?.take(120), linkedYear = year?.trim()?.take(4)?.ifBlank { null }, linkedTmdb = tmdb) else it })
    }

    fun remove(hash: String) {
        val e = get(hash) ?: return
        e.localFile?.let { runCatching { File(it).delete() } }
        e.magnet?.let { m -> TorrentContent.magnetHash(m)?.let { File(engine.metaDir, "$it.torrent").delete() } }
        save(current.filterNot { it.hash == hash })
    }

    /** Torrents associados à obra (fontes adicionais na tela de detalhes). */
    fun matches(names: List<String>, year: String?, tmdbId: String?): List<TorrentEntry> = current.filter { e ->
        when {
            tmdbId != null && e.linkedTmdb != null -> e.linkedTmdb == tmdbId
            e.linkedName != null -> TitleMatcher.matches(Title("x", Kind.MOVIE, e.linkedName, e.linkedYear), names, year)
            else -> false
        }
    }

    companion object {
        const val PREFIX = "tor:"
        private const val KEY = "entries"
        private const val MAX_ENTRIES = 100

        fun hashOf(titleId: String) = titleId.removePrefix(PREFIX).takeIf { titleId.startsWith(PREFIX) }

        /** Título do histórico (usado no player, no progresso e em "Continuar assistindo"). */
        fun title(e: TorrentEntry): Title = Title(
            id = e.titleId, kind = if (e.videoCount > 1) Kind.SERIES else Kind.MOVIE, name = e.displayName,
            year = e.linkedYear, synopsis = "Torrent adicionado por você${if (e.videoCount > 0) " • ${e.videoCount} vídeo(s)" else ""}" +
                if (e.totalSize > 0) " • ${CacheManager.formatBytes(e.totalSize)}" else "",
            license = "Torrent do usuário",
        )

        /** Episódio/vídeo escolhido → unidade reproduzível com progresso próprio ("hash:índice"). */
        fun playable(e: TorrentEntry, v: TorrentVideo): Playable = playable(e.hash, e.ref, v)

        fun playable(key: String, ref: TorrentRef, v: TorrentVideo): Playable = Playable(
            key = "$key:${v.file.index}",
            label = v.episode?.let { "T${it.season}:E${it.episode} — ${v.file.name}" } ?: v.file.name,
            season = v.episode?.season ?: 1,
            number = v.episode?.episode ?: (v.file.index + 1),
            directUrl = null,
            torrent = ref.copy(fileIndex = v.file.index, filePathHint = v.file.path),
            subtitles = emptyList<SubtitleRef>(),
            durationSec = null,
            preferTorrent = true,
        )
    }
}
