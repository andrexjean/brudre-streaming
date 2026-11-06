package com.brudre.streaming.torrent

import com.brudre.streaming.sources.ClassifiedException
import com.brudre.streaming.sources.ErrorKind
import java.security.MessageDigest

/**
 * Leitor bencode (BEP 3) em Kotlin puro: lista os arquivos de um .torrent sem iniciar a sessão
 * BitTorrent (rápido, sem memória nativa, testável). Limites contra arquivos malformados.
 */
object Bencode {
    class Reader(private val b: ByteArray) {
        var pos = 0
        var infoStart = -1
        var infoEnd = -1
        private var depth = 0

        fun read(): Any {
            if (pos >= b.size) throw ClassifiedException(ErrorKind.FORMAT, "Arquivo .torrent incompleto")
            if (++depth > 64) throw ClassifiedException(ErrorKind.FORMAT, "Arquivo .torrent malformado")
            try {
                return when (val c = b[pos].toInt().toChar()) {
                    'i' -> { pos++; val end = indexOf('e'); val v = String(b, pos, end - pos, Charsets.US_ASCII).toLong(); pos = end + 1; v }
                    'l' -> { pos++; val l = ArrayList<Any>(); while (b[pos] != 'e'.code.toByte()) l += read(); pos++; l }
                    'd' -> {
                        pos++
                        val m = LinkedHashMap<String, Any>()
                        while (b[pos] != 'e'.code.toByte()) {
                            val k = String(read() as ByteArray, Charsets.UTF_8)
                            val start = pos
                            val v = read()
                            if (k == "info" && depth == 1 && infoStart < 0) { infoStart = start; infoEnd = pos }
                            m[k] = v
                        }
                        pos++; m
                    }
                    in '0'..'9' -> {
                        val colon = indexOf(':')
                        val len = String(b, pos, colon - pos, Charsets.US_ASCII).toInt()
                        if (len < 0 || colon + 1 + len > b.size) throw ClassifiedException(ErrorKind.FORMAT, "Arquivo .torrent malformado")
                        pos = colon + 1 + len
                        b.copyOfRange(colon + 1, colon + 1 + len)
                    }
                    else -> throw ClassifiedException(ErrorKind.FORMAT, "Arquivo .torrent malformado (byte inesperado '$c')")
                }
            } catch (e: ClassifiedException) {
                throw e
            } catch (e: Exception) {
                throw ClassifiedException(ErrorKind.FORMAT, "Arquivo .torrent malformado", e)
            } finally {
                depth--
            }
        }

        private fun indexOf(ch: Char): Int {
            var i = pos
            while (i < b.size && b[i] != ch.code.toByte()) i++
            if (i >= b.size) throw ClassifiedException(ErrorKind.FORMAT, "Arquivo .torrent incompleto")
            return i
        }
    }

    fun decode(bytes: ByteArray): Pair<Any, Reader> {
        val r = Reader(bytes)
        return r.read() to r
    }

    fun encode(v: Any): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun w(x: Any) {
            when (x) {
                is Long -> out.write("i${x}e".toByteArray())
                is Int -> out.write("i${x}e".toByteArray())
                is String -> { val b = x.toByteArray(); out.write("${b.size}:".toByteArray()); out.write(b) }
                is ByteArray -> { out.write("${x.size}:".toByteArray()); out.write(x) }
                is List<*> -> { out.write('l'.code); x.forEach { w(it!!) }; out.write('e'.code) }
                is Map<*, *> -> {
                    out.write('d'.code)
                    x.keys.map { it as String }.sortedWith { a, b -> compareBytes(a.toByteArray(), b.toByteArray()) }.forEach { k -> w(k); w(x[k]!!) }
                    out.write('e'.code)
                }
                else -> throw IllegalArgumentException("tipo não suportado")
            }
        }
        w(v)
        return out.toByteArray()
    }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) { val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff); if (d != 0) return d }
        return a.size - b.size
    }
}

/** Arquivo dentro do torrent. [index] é a posição na lista do torrent (a mesma usada pelo libtorrent). */
data class TorrentFileEntry(val index: Int, val path: String, val size: Long) {
    val name: String get() = path.substringAfterLast('/')
    val ext: String get() = name.substringAfterLast('.', "").lowercase()
    val isVideo: Boolean get() = ext in TorrentContent.VIDEO_EXT
    val isSubtitle: Boolean get() = ext in TorrentContent.SUBTITLE_EXT
    val isSample: Boolean get() = Regex("(?i)(^|[ ._\\-/\\[(])(sample|amostra|trailer)([ ._\\-\\])]|$)").containsMatchIn(path)
}

/** Episódio reconhecido pelo nome do arquivo ou da pasta. */
data class EpisodeId(val season: Int, val episode: Int)

/** Vídeo listado para o usuário escolher (com episódio, se reconhecido, e legendas do próprio torrent). */
data class TorrentVideo(val file: TorrentFileEntry, val episode: EpisodeId?, val subtitles: List<TorrentFileEntry>) {
    val label: String get() = episode?.let { "T${it.season}:E${it.episode} — ${file.name}" } ?: file.name
}

data class TorrentMeta(val infoHash: String, val name: String, val files: List<TorrentFileEntry>, val webSeeds: List<String>, val trackers: List<String>) {
    val totalSize: Long get() = files.sumOf { it.size }
    val videos: List<TorrentVideo> get() = TorrentContent.videos(this)
}

object TorrentContent {
    val VIDEO_EXT = setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "ts", "m2ts")
    val SUBTITLE_EXT = setOf("srt", "vtt", "ass", "ssa")
    const val MAX_TORRENT_BYTES = 16 * 1024 * 1024
    private const val MAX_FILES = 5_000

    /** Lê nome, hash (v1) e arquivos de um .torrent. Lança [ClassifiedException] se não for um torrent válido. */
    fun parse(bytes: ByteArray): TorrentMeta {
        if (bytes.isEmpty() || bytes.size > MAX_TORRENT_BYTES || bytes[0] != 'd'.code.toByte()) throw ClassifiedException(ErrorKind.FORMAT, "O arquivo não é um .torrent")
        val (root, reader) = Bencode.decode(bytes)
        val top = root as? Map<*, *> ?: throw ClassifiedException(ErrorKind.FORMAT, "O arquivo não é um .torrent")
        val info = top["info"] as? Map<*, *> ?: throw ClassifiedException(ErrorKind.FORMAT, ".torrent sem a seção \"info\"")
        if (reader.infoStart < 0) throw ClassifiedException(ErrorKind.FORMAT, ".torrent sem a seção \"info\"")
        val hash = MessageDigest.getInstance("SHA-1").digest(bytes.copyOfRange(reader.infoStart, reader.infoEnd)).joinToString("") { "%02x".format(it) }
        val name = str(info["name.utf-8"]) ?: str(info["name"]) ?: hash
        val files = ArrayList<TorrentFileEntry>()
        val list = info["files"] as? List<*>
        if (list == null) {
            val len = info["length"] as? Long ?: throw ClassifiedException(ErrorKind.FORMAT, ".torrent sem tamanho de arquivo")
            files += TorrentFileEntry(0, safePath(listOf(name)), len)
        } else {
            if (list.size > MAX_FILES) throw ClassifiedException(ErrorKind.FORMAT, "Torrent com arquivos demais (${list.size})")
            list.forEachIndexed { i, f ->
                val m = f as? Map<*, *> ?: return@forEachIndexed
                val parts = ((m["path.utf-8"] ?: m["path"]) as? List<*>)?.mapNotNull { str(it) }.orEmpty()
                files += TorrentFileEntry(i, safePath(listOf(name) + parts), (m["length"] as? Long) ?: 0L)
            }
        }
        val seeds = when (val u = top["url-list"]) {
            is List<*> -> u.mapNotNull { str(it) }
            is ByteArray -> listOfNotNull(str(u))
            else -> emptyList()
        }.filter { it.startsWith("https://") }
        val trackers = (listOfNotNull(str(top["announce"])) +
            ((top["announce-list"] as? List<*>)?.flatMap { t -> (t as? List<*>)?.mapNotNull { str(it) }.orEmpty() }.orEmpty())).distinct()
        return TorrentMeta(hash, name, files, seeds, trackers)
    }

    /** Remove "..", barras invertidas e caracteres de controle do caminho (proteção contra path traversal). */
    fun safePath(parts: List<String>): String = parts
        .map { it.replace('\\', '/').split('/') }.flatten()
        .filter { it.isNotBlank() && it != "." && it != ".." }
        .joinToString("/") { p -> p.filter { it.code >= 0x20 } }

    private fun str(v: Any?): String? = (v as? ByteArray)?.toString(Charsets.UTF_8)

    private val PATTERNS = listOf(
        Regex("(?i)\\bS(\\d{1,2})[ ._-]?E(\\d{1,3})\\b"),
        Regex("(?i)\\b(\\d{1,2})x(\\d{1,3})\\b"),
        Regex("(?i)\\btemporada[ ._-]*(\\d{1,2})[ ._-]*(?:epis[oó]dio|ep)[ ._-]*(\\d{1,3})\\b"),
        Regex("(?i)\\bseason[ ._-]*(\\d{1,2})[ ._-]*(?:episode|ep)[ ._-]*(\\d{1,3})\\b"),
    )
    private val SEASON_DIR = Regex("(?i)(?:season|temporada|s)[ ._-]*(\\d{1,2})(?![0-9])")
    private val EP_ONLY = Regex("(?i)(?:\\bE|\\bEp|\\bEpis[oó]dio|\\bEpisode)[ ._-]*(\\d{1,3})\\b")

    /** Reconhece S01E01, 1x01, "Temporada 1 Episódio 1" e "Season 01/E01". */
    fun episodeOf(path: String): EpisodeId? {
        val name = path.substringAfterLast('/')
        PATTERNS.forEach { r -> r.find(name)?.let { return EpisodeId(it.groupValues[1].toInt(), it.groupValues[2].toInt()) } }
        val dirs = path.substringBeforeLast('/', "")
        val season = dirs.split('/').reversed().firstNotNullOfOrNull { SEASON_DIR.find(it)?.groupValues?.get(1)?.toInt() }
        val ep = EP_ONLY.find(name)?.groupValues?.get(1)?.toInt()
        if (season != null && ep != null) return EpisodeId(season, ep)
        return null
    }

    /**
     * Vídeos que o usuário pode escolher. Amostras ("sample") só aparecem se não houver outro vídeo.
     * Ordem: por temporada/episódio quando reconhecidos, senão pelo caminho.
     */
    fun videos(meta: TorrentMeta): List<TorrentVideo> {
        val all = meta.files.filter { it.isVideo }
        val main = all.filterNot { it.isSample }.ifEmpty { all }
        val subs = meta.files.filter { it.isSubtitle }
        return main.map { f -> TorrentVideo(f, episodeOf(f.path), subtitlesFor(f, subs)) }
            .sortedWith(compareBy<TorrentVideo>({ it.episode?.season ?: Int.MAX_VALUE }, { it.episode?.episode ?: Int.MAX_VALUE }, { it.file.path.lowercase() }))
    }

    /** Legendas do torrent para o vídeo: mesmo nome-base ou mesma pasta (ou pasta Subs/Legendas). */
    fun subtitlesFor(video: TorrentFileEntry, subs: List<TorrentFileEntry>): List<TorrentFileEntry> {
        val base = video.name.substringBeforeLast('.').lowercase()
        val dir = video.path.substringBeforeLast('/', "")
        val ep = episodeOf(video.path)
        return subs.filter { s ->
            val sn = s.name.lowercase()
            val sdir = s.path.substringBeforeLast('/', "")
            when {
                sn.startsWith(base) -> true
                ep != null && episodeOf(s.path) == ep -> true
                episodeOf(s.path) != null -> false
                sdir == dir -> true
                sdir.startsWith("$dir/") && Regex("(?i)/(subs?|legendas?|subtitles?)(/|$)").containsMatchIn(sdir.removePrefix(dir) + "/") -> true
                else -> false
            }
        }.take(8)
    }

    /** "Filme.pt-BR.srt" → "pt"; usado para pré-selecionar a legenda no idioma configurado. */
    fun subtitleLanguage(name: String): String {
        val n = name.lowercase().substringBeforeLast('.')
        return when {
            Regex("(^|[._ -])(pt|por|pt-br|ptbr|portugues|português|brazilian)([._ -]|$)").containsMatchIn(n) -> "pt"
            Regex("(^|[._ -])(en|eng|english|ingles)([._ -]|$)").containsMatchIn(n) -> "en"
            Regex("(^|[._ -])(es|spa|spanish|espanol|español)([._ -]|$)").containsMatchIn(n) -> "es"
            else -> "und"
        }
    }

    fun languageLabel(code: String) = when (code) {
        "pt" -> "Português"; "en" -> "Inglês"; "es" -> "Espanhol"; else -> "Legenda"
    }

    /** Hash do magnet (hex v1); base32 é convertido para hex. */
    fun magnetHash(magnet: String): String? {
        val h = MagnetLink.parse(magnet)?.infoHash ?: return null
        return when {
            h.length == 40 && h.all { it in "0123456789abcdef" } -> h
            h.length == 32 -> base32ToHex(h)
            else -> null
        }
    }

    private fun base32ToHex(s: String): String? {
        val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
        var bits = 0L; var n = 0
        val out = StringBuilder()
        for (c in s.lowercase()) {
            val v = alphabet.indexOf(c); if (v < 0) return null
            bits = (bits shl 5) or v.toLong(); n += 5
            while (n >= 8) { n -= 8; out.append("%02x".format(((bits shr n) and 0xff).toInt())) }
        }
        return out.toString().takeIf { it.length == 40 }
    }
}
