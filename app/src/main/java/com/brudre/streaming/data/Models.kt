package com.brudre.streaming.data

import org.json.JSONArray
import org.json.JSONObject

enum class Kind(val label: String) {
    MOVIE("Filme"), SERIES("Série"), KIDS("Infantil"), LIVE("Ao vivo");

    companion object {
        fun parse(value: String?): Kind = entries.firstOrNull { it.name == value } ?: MOVIE
    }
}

/** Item do catálogo. Ter capa e sinopse NÃO significa estar disponível: ver [TitleDetails.playables]. */
data class Title(
    val id: String,
    val kind: Kind,
    val name: String,
    val year: String? = null,
    val synopsis: String? = null,
    val durationMin: Int? = null,
    val genres: List<String> = emptyList(),
    val rating: String? = null,
    val posterUrl: String? = null,
    val bannerUrl: String? = null,
    val license: String? = null,
    /** Idioma original do áudio (código ISO 639-1, ex.: "en", "pt", "ja"); null = não informado pela fonte. */
    val language: String? = null,
    /** Áudio em português disponível (dublado ou original em português). */
    val ptAudio: Boolean = false,
    /** Legenda em português disponível na fonte. */
    val ptSubtitles: Boolean = false,
) {
    /** Selo de idioma: separa o que tem português do que está só no idioma original. */
    val languageBadge: String? get() = when {
        ptAudio -> "PT-BR"
        ptSubtitles -> "LEG PT"
        language != null -> "ORIGINAL ${language.uppercase()}"
        else -> null
    }
    val languageLine: String? get() {
        val orig = language?.let { Languages.name(it) }
        return when {
            ptAudio && (language == null || language == "pt") -> "Áudio em português"
            ptAudio -> "Áudio em português disponível • idioma original: $orig"
            ptSubtitles -> "Legenda em português disponível • áudio ${orig ?: "original"}"
            orig != null -> "Somente no idioma original ($orig) — sem áudio ou legenda em português informados pela fonte"
            else -> null
        }
    }
    val isArchive: Boolean get() = id.startsWith(ARCHIVE_PREFIX)
    val isCurated: Boolean get() = id.startsWith(CURATED_PREFIX)
    val isTmdb: Boolean get() = id.startsWith(TmdbParser.PREFIX)
    val isLive: Boolean get() = id.startsWith(LIVE_PREFIX)
    val archiveId: String? get() = if (isArchive) id.removePrefix(ARCHIVE_PREFIX) else null

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("kind", kind.name).put("name", name)
        .put("year", year ?: JSONObject.NULL)
        .put("synopsis", synopsis ?: JSONObject.NULL)
        .put("durationMin", durationMin ?: JSONObject.NULL)
        .put("genres", JSONArray(genres))
        .put("rating", rating ?: JSONObject.NULL)
        .put("poster", posterUrl ?: JSONObject.NULL)
        .put("banner", bannerUrl ?: JSONObject.NULL)
        .put("license", license ?: JSONObject.NULL)
        .put("lang", language ?: JSONObject.NULL)
        .put("ptAudio", ptAudio)
        .put("ptSubs", ptSubtitles)

    companion object {
        const val ARCHIVE_PREFIX = "ia:"
        const val CURATED_PREFIX = "cur:"
        const val LIVE_PREFIX = "live:"

        fun fromJson(o: JSONObject): Title = Title(
            id = o.getString("id"),
            kind = Kind.parse(o.optString("kind")),
            name = o.optString("name"),
            year = o.optStringOrNull("year"),
            synopsis = o.optStringOrNull("synopsis"),
            durationMin = if (o.isNull("durationMin")) null else o.optInt("durationMin"),
            genres = o.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            rating = o.optStringOrNull("rating"),
            posterUrl = o.optStringOrNull("poster"),
            bannerUrl = o.optStringOrNull("banner"),
            license = o.optStringOrNull("license"),
            language = o.optStringOrNull("lang"),
            ptAudio = o.optBoolean("ptAudio", false),
            ptSubtitles = o.optBoolean("ptSubs", false),
        )

        fun listToJson(list: List<Title>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()
        fun listFromJson(json: String): List<Title> {
            val a = JSONArray(json)
            return (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
        }
    }
}

/** Nomes de idiomas em português para os selos e a tela de detalhes. */
object Languages {
    private val NAMES = mapOf(
        "pt" to "português", "en" to "inglês", "es" to "espanhol", "fr" to "francês", "it" to "italiano", "de" to "alemão",
        "ja" to "japonês", "ko" to "coreano", "zh" to "chinês", "cn" to "chinês", "hi" to "hindi", "ru" to "russo", "ar" to "árabe",
        "tr" to "turco", "sv" to "sueco", "da" to "dinamarquês", "no" to "norueguês", "nl" to "holandês", "pl" to "polonês",
        "th" to "tailandês", "id" to "indonésio", "fa" to "persa", "he" to "hebraico", "el" to "grego", "fi" to "finlandês",
        "cs" to "tcheco", "hu" to "húngaro", "ro" to "romeno", "uk" to "ucraniano", "tl" to "tagalo", "ta" to "tâmil", "te" to "télugo",
        "xx" to "sem diálogos",
    )

    fun name(code: String): String = NAMES[code.lowercase()] ?: runCatching {
        java.util.Locale(code).getDisplayLanguage(java.util.Locale("pt", "BR")).lowercase().takeIf { it.isNotBlank() && it != code }
    }.getOrNull() ?: code.uppercase()

    fun isPortuguese(code: String?): Boolean = code != null && (code.equals("pt", true) || code.startsWith("pt-", true) || code.equals("por", true))
}

data class SubtitleRef(val url: String, val language: String, val label: String) {
    fun toJson(): JSONObject = JSONObject().put("url", url).put("lang", language).put("label", label)

    companion object {
        fun fromJson(o: JSONObject) = SubtitleRef(o.getString("url"), o.optString("lang", "und"), o.optString("label", o.optString("lang")))
    }
}

/**
 * Torrent: .torrent (endereço ou arquivo local importado) e/ou magnet, mais o vídeo a reproduzir.
 * [fileIndex] escolhe o arquivo exato (episódio); [filePathHint] é a dica por nome dos catálogos.
 */
data class TorrentRef(
    val torrentUrl: String?,
    val magnet: String?,
    val filePathHint: String?,
    val fileIndex: Int? = null,
    /** Caminho do .torrent importado no armazenamento interno do app (Arquivos → BruDre, compartilhar). */
    val localFile: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("torrentUrl", torrentUrl ?: JSONObject.NULL)
        .put("magnet", magnet ?: JSONObject.NULL)
        .put("file", filePathHint ?: JSONObject.NULL)
        .put("index", fileIndex ?: JSONObject.NULL)
        .put("local", localFile ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = TorrentRef(
            o.optStringOrNull("torrentUrl"), o.optStringOrNull("magnet"), o.optStringOrNull("file"),
            if (o.has("index") && !o.isNull("index")) o.optInt("index") else null,
            o.optStringOrNull("local"),
        )
    }
}

/** Unidade reproduzível (filme ou episódio) com as fontes autorizadas encontradas. */
data class Playable(
    val key: String,
    val label: String,
    val season: Int,
    val number: Int,
    val directUrl: String?,
    val torrent: TorrentRef?,
    val subtitles: List<SubtitleRef>,
    val durationSec: Int?,
    val preferTorrent: Boolean,
    /** Endereços diretos alternativos (mesmo conteúdo), tentados em ordem quando o principal falha. */
    val mirrors: List<String> = emptyList(),
    /** Transmissão ao vivo (HLS): sem retomada de posição, com reconexão automática. */
    val live: Boolean = false,
    /** Cabeçalhos HTTP exigidos pela fonte (Referer / User-Agent das listas M3U). */
    val headers: Map<String, String> = emptyMap(),
    /** Outros torrents com o mesmo conteúdo (fontes cadastradas), tentados depois do principal. */
    val altTorrents: List<TorrentRef> = emptyList(),
    /** Endereço/magnet → id da fonte cadastrada que o forneceu (histórico de sucesso e falha). */
    val origins: Map<String, String> = emptyMap(),
) {
    val hasSource: Boolean get() = directUrl != null || torrent != null || mirrors.isNotEmpty() || altTorrents.isNotEmpty()

    fun toJson(): JSONObject = JSONObject()
        .put("key", key).put("label", label).put("season", season).put("number", number)
        .put("direct", directUrl ?: JSONObject.NULL)
        .put("torrent", torrent?.toJson() ?: JSONObject.NULL)
        .put("subtitles", JSONArray().apply { subtitles.forEach { put(it.toJson()) } })
        .put("durationSec", durationSec ?: JSONObject.NULL)
        .put("preferTorrent", preferTorrent)
        .put("mirrors", JSONArray(mirrors))
        .put("live", live)
        .put("headers", JSONObject(headers as Map<*, *>))
        .put("altTorrents", JSONArray().apply { altTorrents.forEach { put(it.toJson()) } })
        .put("origins", JSONObject(origins as Map<*, *>))

    companion object {
        fun fromJson(o: JSONObject): Playable = Playable(
            key = o.getString("key"),
            label = o.optString("label"),
            season = o.optInt("season", 1),
            number = o.optInt("number", 1),
            directUrl = o.optStringOrNull("direct"),
            torrent = o.optJSONObject("torrent")?.let { TorrentRef.fromJson(it) },
            subtitles = o.optJSONArray("subtitles")?.let { a -> (0 until a.length()).map { SubtitleRef.fromJson(a.getJSONObject(it)) } } ?: emptyList(),
            durationSec = if (o.isNull("durationSec")) null else o.optInt("durationSec"),
            preferTorrent = o.optBoolean("preferTorrent", false),
            mirrors = o.optJSONArray("mirrors")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            live = o.optBoolean("live", false),
            headers = o.optJSONObject("headers")?.let { h -> h.keys().asSequence().associateWith { h.getString(it) } } ?: emptyMap(),
            altTorrents = o.optJSONArray("altTorrents")?.let { a -> (0 until a.length()).map { TorrentRef.fromJson(a.getJSONObject(it)) } } ?: emptyList(),
            origins = o.optJSONObject("origins")?.let { h -> h.keys().asSequence().associateWith { h.getString(it) } } ?: emptyMap(),
        )
    }
}

data class TitleDetails(
    val title: Title,
    val playables: List<Playable>,
    val sourceLabel: String,
    /** Fonte que forneceu os vídeos (id do provedor no Source Manager). */
    val providerId: String? = null,
    /** true quando o primeiro vídeo respondeu à verificação de disponibilidade. */
    val confirmed: Boolean = false,
    /** Apps oficiais gratuitos que têm o título (quando não há vídeo dentro do BruDre). */
    val freeProviders: List<FreeProvider> = emptyList(),
    /** Todas as fontes compatíveis encontradas para o título, na ordem de preferência (escolha manual). */
    val options: List<SourceOption> = emptyList(),
) {
    val freeElsewhere: Boolean get() = !available && freeProviders.isNotEmpty()
    val available: Boolean get() = playables.any { it.hasSource }
    val seasons: List<Int> get() = playables.map { it.season }.distinct().sorted()
    val isEpisodic: Boolean get() = playables.size > 1
}

/**
 * Uma fonte encontrada para o título (nativa ou cadastrada), com o que se sabe dela para a escolha manual.
 */
data class SourceOption(
    val sourceId: String,
    val name: String,
    val playables: List<Playable>,
    /** "HTTP • BitTorrent" etc. */
    val formats: String,
    /** "1080p", "720p"… quando informado no nome ou no endereço. */
    val quality: String? = null,
    val native: Boolean = false,
    /** Último resultado conhecido da fonte ("✓ funcionou 3x", "✗ falhou 2x"). */
    val health: String? = null,
    /** Associação confirmada (TMDB ou nome + ano). Sem confirmação: só na escolha manual. */
    val confident: Boolean = true,
    /** Idiomas de áudio/legenda INFORMADOS pela fonte (vazio = desconhecido). */
    val audio: List<String> = emptyList(),
    val subtitles: List<String> = emptyList(),
    /** Por que a fonte está nesta posição (classificação explicável). */
    val reasons: List<String> = emptyList(),
) {
    val languageInfo: String get() = listOf(
        "áudio: " + (audio.takeIf { it.isNotEmpty() }?.joinToString(", ") { Languages.name(it) } ?: "desconhecido"),
        "legenda: " + (subtitles.takeIf { it.isNotEmpty() }?.joinToString(", ") { Languages.name(it) } ?: "desconhecida"),
    ).joinToString(" • ")
    val qualityInfo: String get() = quality ?: "resolução desconhecida"
}

/** Preferências para a escolha automática da fonte (Configurações). */
data class SourcePreferences(
    val audio: String = "pt",
    val subtitle: String = "pt",
    val resolution: Int = 0,
    val preferredSourceId: String = "",
)

fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
