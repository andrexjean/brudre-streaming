package com.brudre.streaming.sources

import com.brudre.streaming.data.optStringOrNull
import com.brudre.streaming.live.M3uParser
import org.json.JSONArray
import org.json.JSONObject

/** Formatos aceitos em Configurações → Gerenciar fontes. */
enum class SourceType(val label: String) {
    CATALOG_JSON("Catálogo BruDre (JSON)"),
    RSS("Feed RSS/Atom de vídeos"),
    M3U("Playlist IPTV (M3U)"),
    HLS("Transmissão HLS (M3U8)"),
    VIDEO("Vídeo HTTP/HTTPS"),
    MAGNET("Magnet link"),
    TORRENT("Arquivo .torrent"),
    SEARCH("API de pesquisa (BruDre)"),
    OPENSEARCH("OpenSearch (descrição XML)"),
    REST("API REST (descritor BruDre)"),
    TORZNAB("Indexador Torznab/Newznab");

    val isCatalog get() = this == CATALOG_JSON || this == RSS
    val isSingle get() = this == HLS || this == VIDEO || this == MAGNET || this == TORRENT
    val isTorrent get() = this == MAGNET || this == TORRENT
    /** Fontes consultadas sob demanda (pesquisa dinâmica). */
    val isSearchable get() = this == SEARCH || this == OPENSEARCH || this == REST || this == TORZNAB

    /** Prefixo do id: mantém o roteamento dos módulos (catálogos, TV ao vivo, links, pesquisa). */
    val idPrefix get() = when {
        isCatalog -> CATALOG_PREFIX
        this == M3U -> M3U_PREFIX
        isSearchable -> SEARCH_PREFIX
        else -> LINK_PREFIX
    }

    companion object {
        const val SEARCH_PREFIX = "search_"
        /** Iguais a FeedRepository.PREFIX e LiveRepository.CUSTOM_PREFIX. */
        const val CATALOG_PREFIX = "feed_"
        const val M3U_PREFIX = "m3u_"
        const val LINK_PREFIX = "link_"
    }
}

/** Resultado do último teste. [kind] = [ErrorKind] da falha; [detail] = erro original (Detalhes técnicos). */
/**
 * Etapa COMPROVADA de uma fonte (9.2.43): conectividade, pesquisa e reprodução são coisas diferentes.
 * Uma fonte só acessível nunca é apresentada como reproduzível.
 */
enum class SourceStage(val label: String) {
    REGISTERED("Cadastrada"),
    REACHABLE("Acessível"),
    SEARCH_OK("Pesquisa funcional"),
    PLAYBACK_OK("Reprodução validada");

    companion object { fun parse(s: String?) = entries.firstOrNull { it.name == s } }
}

/**
 * Resultado de um teste: data, etapa concluída, tempo de resposta e motivo da falha (classificado).
 * [stage] = até onde o teste comprovou; numa falha, a etapa alcançada antes dela.
 */
data class SourceTest(
    val ok: Boolean, val at: Long, val message: String, val items: Int = 0, val kind: String? = null, val detail: String? = null,
    val stage: SourceStage = if (ok) SourceStage.REACHABLE else SourceStage.REGISTERED, val latencyMs: Int = 0,
) {
    val errorKind: ErrorKind? get() = kind?.let { k -> ErrorKind.entries.firstOrNull { it.name == k } }

    // Nada de segredo no que é gravado ou exportado
    fun toJson(): JSONObject = JSONObject().put("ok", ok).put("at", at).put("msg", Secrets.redactText(message)).put("items", items)
        .put("kind", kind ?: JSONObject.NULL).put("detail", Secrets.redactText(detail) ?: JSONObject.NULL)
        .put("stage", stage.name).put("lat", latencyMs)

    companion object {
        fun fromJson(o: JSONObject) = SourceTest(
            o.optBoolean("ok"), o.optLong("at"), Secrets.redactText(o.optString("msg"))!!, o.optInt("items"), o.optStringOrNull("kind"),
            Secrets.redactText(o.optStringOrNull("detail")),
            SourceStage.parse(o.optStringOrNull("stage")) ?: if (o.optBoolean("ok")) SourceStage.REACHABLE else SourceStage.REGISTERED, o.optInt("lat"),
        )

        fun failure(f: SourceFailure, at: Long = System.currentTimeMillis(), stage: SourceStage = SourceStage.REGISTERED, latencyMs: Int = 0) =
            SourceTest(false, at, f.summary, 0, f.kind.name, f.technical, stage, latencyMs)
    }
}

/** Como a fonte autentica (a credencial em si fica cifrada no aparelho). */
enum class AuthMethod(val label: String) {
    NONE("Sem autenticação"),
    URL_KEY("Chave no endereço ({key} / apikey)"),
    HEADER_KEY("Cabeçalho X-Api-Key"),
    BEARER("Token (Authorization: Bearer)");

    /** Cabeçalho a enviar com a credencial (nenhum para chave no endereço). */
    fun header(secret: String?): Pair<String, String>? = when {
        secret.isNullOrEmpty() -> null
        this == HEADER_KEY -> "X-Api-Key" to secret
        this == BEARER -> "Authorization" to "Bearer $secret"
        else -> null
    }
}

/** Histórico de uso real (reprodução e pesquisa): ordena as fontes e rebaixa as que falham seguidamente. */
data class SourceStats(
    val ok: Int = 0, val fail: Int = 0, val streak: Int = 0, val lastAt: Long = 0, val lastError: String? = null, val avgLatencyMs: Int = 0,
    /** Última reprodução que chegou a tocar por esta fonte (prova de "Reprodução validada"). */
    val playOkAt: Long = 0,
) {
    /** 3 falhas seguidas: a fonte vai para o fim da fila automática (continua disponível na escolha manual). */
    val unreliable: Boolean get() = streak >= 3
    val summary: String get() = when {
        ok == 0 && fail == 0 -> "sem uso ainda"
        else -> "✓ $ok • ✗ $fail" + (if (streak > 0) " (falhou $streak seguida${if (streak > 1) "s" else ""})" else "") + (if (avgLatencyMs > 0) " • ~$avgLatencyMs ms" else "")
    }

    fun record(success: Boolean, error: String? = null, now: Long = System.currentTimeMillis(), latencyMs: Int? = null): SourceStats {
        // Média móvel do tempo de resposta (só respostas bem-sucedidas medidas)
        val lat = if (success && latencyMs != null) (if (avgLatencyMs == 0) latencyMs else (avgLatencyMs * 3 + latencyMs) / 4) else avgLatencyMs
        return if (success) copy(ok = ok + 1, streak = 0, lastAt = now, lastError = null, avgLatencyMs = lat)
        else copy(fail = fail + 1, streak = streak + 1, lastAt = now, lastError = error ?: lastError)
    }

    fun toJson(): JSONObject = JSONObject().put("ok", ok).put("fail", fail).put("streak", streak).put("at", lastAt)
        .put("err", Secrets.redactText(lastError) ?: JSONObject.NULL).put("lat", avgLatencyMs).put("play", playOkAt)

    companion object {
        fun fromJson(o: JSONObject?) = if (o == null) SourceStats() else SourceStats(
            o.optInt("ok"), o.optInt("fail"), o.optInt("streak"), o.optLong("at"), Secrets.redactText(o.optStringOrNull("err")), o.optInt("lat"), o.optLong("play"),
        )
    }
}

/** Fonte cadastrada pelo usuário. A posição na lista ([priority], menor primeiro) define a prioridade. */
data class UserSource(
    val id: String,
    val name: String,
    val url: String,
    val type: SourceType,
    val enabled: Boolean = true,
    val priority: Int = 0,
    val addedAt: Long = System.currentTimeMillis(),
    val lastTest: SourceTest? = null,
    val stats: SourceStats = SourceStats(),
    /** Salva sem validar: fica desativada até um teste confirmar formato, segurança e compatibilidade. */
    val pending: Boolean = false,
    /** Configuração resolvida no teste (modelo OpenSearch, descritor REST). */
    val config: String? = null,
    /** Validada depois de ter sido salva sem validação: aguardando o usuário ativar. */
    val validatedAt: Long = 0,
    /** Autenticação (a credencial fica cifrada no [CredentialStore], nunca aqui). */
    val auth: AuthMethod = AuthMethod.NONE,
    /** Tempo-limite por consulta (s) e máximo de resultados por página desta fonte. */
    val timeoutSec: Int = DEFAULT_TIMEOUT_S,
    val resultLimit: Int = DEFAULT_LIMIT,
    /** Categorias atendidas (vazio = todas): "filmes", "series", "documentarios", "infantil", "livre". */
    val categories: Set<String> = emptySet(),
    /** Formato escolhido manualmente pelo usuário (a detecção automática não confirmou). */
    val manualType: Boolean = false,
    /** Servidor local autorizado: SHA-256 do certificado confirmado pelo usuário (fixado só para este host:porta). */
    val localPin: String? = null,
) {
    /** A fonte deve ser consultada para títulos desta categoria? */
    fun serves(category: String?): Boolean = categories.isEmpty() || category == null || category in categories || "livre" in categories

    /** Etapa comprovada: a reprodução real vale mais que o teste; um teste que falhou mostra o erro, não a etapa. */
    val stage: SourceStage get() = when {
        stats.playOkAt > 0 && lastTest?.ok != false -> SourceStage.PLAYBACK_OK
        else -> lastTest?.stage ?: SourceStage.REGISTERED
    }
    /** Salva sem validar e ainda não testada: é só "cadastrada", não um erro. */
    private val awaitingValidation: Boolean get() = pending && lastTest?.kind == null
    val hasError: Boolean get() = lastTest?.ok == false && !awaitingValidation
    /** "Pesquisa funcional", "Erro: Tempo esgotado"… */
    val statusLabel: String get() = if (awaitingValidation) "${SourceStage.REGISTERED.label} — aguardando validação" else lastTest?.takeIf { !it.ok }?.let { "Erro: " + (it.errorKind?.title ?: "falha") }
        ?: (stage.label + (lastTest?.takeIf { it.ok && stage != SourceStage.PLAYBACK_OK }?.errorKind?.let { " — ${it.title.lowercase()}" } ?: ""))
    val capabilities: SourceCapabilities get() = SourceCapabilities.of(this)

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("url", url).put("type", type.name)
        .put("enabled", enabled).put("priority", priority).put("addedAt", addedAt)
        .put("test", lastTest?.toJson() ?: JSONObject.NULL)
        .put("stats", stats.toJson())
        .put("pending", pending)
        .put("config", config ?: JSONObject.NULL)
        .put("validatedAt", validatedAt)
        .put("auth", auth.name).put("timeout", timeoutSec).put("limit", resultLimit)
        .put("categories", JSONArray(categories.toList())).put("manualType", manualType)
        .put("localPin", localPin ?: JSONObject.NULL)

    companion object {
        fun idFor(type: SourceType, url: String) = type.idPrefix + M3uParser.stableId("feed", url.trim())
        const val MAX_CONFIG = 64 * 1024
        const val DEFAULT_TIMEOUT_S = 12
        const val DEFAULT_LIMIT = 50
        val CATEGORIES = listOf("filmes", "series", "documentarios", "infantil", "livre")
        val CATEGORY_LABELS = mapOf("filmes" to "Filmes", "series" to "Séries", "documentarios" to "Documentários", "infantil" to "Infantil", "livre" to "Domínio público / livre")

        /**
         * Endereço normalizado para achar duplicadas: esquema/servidor em minúsculas, sem porta padrão,
         * sem barra final, sem fragmento e com parâmetros em ordem. Magnets: pelo hash.
         */
        fun normalizedUrl(url: String): String {
            val u = url.trim()
            if (u.startsWith("magnet:", true)) return "magnet:" + (com.brudre.streaming.torrent.TorrentContent.magnetHash(u) ?: u.lowercase())
            return runCatching {
                val uri = java.net.URI(u.replace("{", "%7B").replace("}", "%7D"))
                val host = uri.host?.lowercase()?.trimEnd('.') ?: return u.lowercase()
                val port = if (uri.port == -1 || uri.port == 443 || uri.port == 80) "" else ":${uri.port}"
                val path = (uri.rawPath ?: "").trimEnd('/')
                val query = uri.rawQuery?.split('&')?.filter { it.isNotEmpty() }?.sorted()?.joinToString("&")?.let { "?$it" } ?: ""
                "${uri.scheme?.lowercase()}://$host$port$path$query"
            }.getOrDefault(u.lowercase())
        }

        fun fromJson(o: JSONObject): UserSource? {
            val type = runCatching { SourceType.valueOf(o.getString("type")) }.getOrNull() ?: return null
            val url = o.optString("url").trim()
            if (url.isEmpty()) return null
            return UserSource(
                id = idFor(type, url),
                name = o.optString("name").trim().ifBlank { "Fonte" }.take(60),
                url = url,
                type = type,
                enabled = o.optBoolean("enabled", true),
                priority = o.optInt("priority", 0),
                addedAt = o.optLong("addedAt", System.currentTimeMillis()),
                lastTest = o.optJSONObject("test")?.let { SourceTest.fromJson(it) },
                stats = SourceStats.fromJson(o.optJSONObject("stats")),
                pending = o.optBoolean("pending", false),
                config = o.optStringOrNull("config")?.take(MAX_CONFIG),
                validatedAt = o.optLong("validatedAt", 0),
                auth = runCatching { AuthMethod.valueOf(o.optString("auth", "NONE")) }.getOrDefault(AuthMethod.NONE),
                timeoutSec = o.optInt("timeout", DEFAULT_TIMEOUT_S).coerceIn(3, 60),
                resultLimit = o.optInt("limit", DEFAULT_LIMIT).coerceIn(5, 200),
                categories = o.optJSONArray("categories")?.let { a -> (0 until a.length()).map { a.getString(it) }.filter { it in CATEGORIES }.toSet() }.orEmpty(),
                manualType = o.optBoolean("manualType", false),
                localPin = o.optStringOrNull("localPin")?.let { LocalServers.normalizePin(it) }?.takeIf { it.length == 64 },
            )
        }
    }
}

/** Importação/exportação das fontes (arquivo JSON versionado). */
object UserSourcesJson {
    const val FORMAT = "brudre-fontes"

    fun export(list: List<UserSource>): String = JSONObject()
        .put("format", FORMAT).put("version", 1)
        .put("sources", JSONArray().apply { list.sortedBy { it.priority }.forEach { put(it.toJson().apply { remove("test"); remove("stats"); remove("localPin") }) } })
        .toString(2)

    /** Lê um arquivo exportado; fontes inseguras ou de formato desconhecido são descartadas. */
    fun import(text: String): List<UserSource> {
        val root = try { JSONObject(text.trim()) } catch (e: Exception) { throw IllegalArgumentException("Arquivo de configuração inválido") }
        if (root.optString("format") != FORMAT) throw IllegalArgumentException("Não é um arquivo de fontes do BruDre")
        val arr = root.optJSONArray("sources") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { UserSource.fromJson(it) } }
            .map { s ->
                // Fixações de certificado nunca vêm de arquivo: servidor local importado entra desativado, aguardando confirmação no aparelho.
                if (isLocalAddress(s.url)) s.copy(localPin = null, enabled = false, pending = true) else s.copy(localPin = null)
            }
            .filter { s -> isSafe(s.type, s.url) || (s.pending && isLocalAddress(s.url) && s.url.startsWith("https://", true)) }
            .distinctBy { it.id }
            .distinctBy { UserSource.normalizedUrl(it.url) }
            .take(MAX_SOURCES)
    }

    const val MAX_SOURCES = 40

    /** Endereço aceitável para o tipo (magnet válido, https permitido pelo UrlGuard, modelo de pesquisa seguro). */
    fun isSafe(type: SourceType, url: String): Boolean = when (type) {
        SourceType.MAGNET -> FeedParser.isValidMagnet(url)
        SourceType.SEARCH -> SearchTemplate.isTemplate(url) && SearchTemplate.check(url) == null
        SourceType.OPENSEARCH, SourceType.REST, SourceType.TORZNAB -> UrlGuard.isAllowed(url) && url.startsWith("https://", true) && !url.contains("apikey=", true)
        else -> UrlGuard.isAllowed(url) && url.startsWith("https://", true)
    }

    /** IP literal da rede privada (servidor local). */
    fun isLocalAddress(url: String): Boolean = UrlGuard.hostOf(url)?.let { h ->
        val lit = h.removePrefix("[").removeSuffix("]")
        if (!Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(lit) && !lit.contains(':')) return@let false
        runCatching { LocalServers.isPrivate(java.net.InetAddress.getByName(lit)) }.getOrDefault(false)
    } == true

    /** Pode ser servidor da rede local (IP privado ou nome de rede doméstica)? Mostra a opção de fixar certificado. */
    fun mayBeLocal(url: String): Boolean = isLocalAddress(url) ||
        UrlGuard.hostOf(url)?.let { h -> listOf(".local", ".lan", ".home", ".home.arpa", ".internal").any { h.endsWith(it) } } == true

    fun listToJson(list: List<UserSource>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()
    fun listFromJson(json: String): List<UserSource> = runCatching {
        val a = JSONArray(json); (0 until a.length()).mapNotNull { UserSource.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())
}

/**
 * Identificação automática do formato pelos primeiros bytes (e pelo tipo informado pelo servidor).
 * Nunca presume que um endereço é catálogo: páginas web, executáveis e conteúdo desconhecido são recusados.
 */
object SourceDetector {
    data class Detection(val type: SourceType?, val reason: String)

    fun classify(url: String, contentType: String?, head: ByteArray): Detection {
        val u = url.trim()
        if (u.startsWith("magnet:", true)) {
            return if (FeedParser.isValidMagnet(u)) Detection(SourceType.MAGNET, "Magnet link válido") else Detection(null, "Magnet sem hash de torrent válido")
        }
        if (SearchTemplate.isTemplate(u)) return Detection(SourceType.SEARCH, "API de pesquisa")
        val mime = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (UrlGuard.isExecutableResponse(mime, u) || isExecutable(head)) return Detection(null, "Arquivo executável ou instalador: bloqueado")
        val text = String(head, Charsets.UTF_8).trimStart('﻿', ' ', '\n', '\r', '\t')
        val lower = text.take(4096).lowercase()
        return when {
            text.startsWith("#EXTM3U") -> when {
                lower.contains("#ext-x-stream-inf") || lower.contains("#ext-x-targetduration") || lower.contains("#ext-x-media-sequence") ->
                    Detection(SourceType.HLS, "Transmissão HLS")
                lower.contains("#extinf") -> Detection(SourceType.M3U, "Playlist IPTV M3U")
                else -> Detection(null, "Lista M3U vazia")
            }
            text.startsWith("#EXTINF") -> Detection(SourceType.M3U, "Playlist IPTV M3U")
            isTorrent(head) || mime == "application/x-bittorrent" -> Detection(SourceType.TORRENT, "Arquivo .torrent")
            text.startsWith("{") && Regex("\"format\"\\s*:\\s*\"brudre-adapter\"").containsMatchIn(lower) -> Detection(SourceType.REST, "Descritor de API REST do BruDre")
            text.startsWith("{") -> if (lower.contains("\"items\"")) Detection(SourceType.CATALOG_JSON, "Catálogo JSON do BruDre")
            else Detection(null, "JSON sem a lista \"items\" do formato de catálogo do BruDre")
            text.startsWith("<") -> when {
                lower.contains("<opensearchdescription") -> Detection(SourceType.OPENSEARCH, "Descrição OpenSearch")
                Regex("<caps\\b").containsMatchIn(lower) -> Detection(SourceType.TORZNAB, "Capacidades Torznab/Newznab")
                Regex("<error\\s+code=\"\\d+\"").containsMatchIn(lower) -> Detection(null, "Resposta de erro de API de indexação (Torznab/Newznab?)")
                lower.contains("<rss") || lower.contains("<channel") -> Detection(SourceType.RSS, "Feed RSS")
                Regex("<feed\\b").containsMatchIn(lower) && lower.contains("<entry") -> Detection(SourceType.RSS, "Feed Atom")
                lower.contains("<html") || lower.contains("<!doctype html") -> Detection(null, "É uma página web, não uma fonte de vídeo")
                else -> Detection(null, "XML não reconhecido")
            }
            isVideo(head) || mime.startsWith("video/") -> Detection(SourceType.VIDEO, "Vídeo")
            mime.contains("mpegurl") -> Detection(SourceType.HLS, "Transmissão HLS")
            else -> Detection(null, "Formato não reconhecido")
        }
    }

    /** Formato pelo endereço (sem acessar a rede): usado em "Salvar sem validar". */
    fun guessByAddress(url: String): SourceType? {
        val u = url.trim()
        if (u.startsWith("magnet:", true)) return SourceType.MAGNET.takeIf { FeedParser.isValidMagnet(u) }
        if (SearchTemplate.isTemplate(u)) return SourceType.SEARCH
        val path = u.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".torrent") -> SourceType.TORRENT
            path.endsWith(".m3u8") -> SourceType.HLS
            path.endsWith(".m3u") -> SourceType.M3U
            path.endsWith(".json") -> SourceType.CATALOG_JSON
            path.endsWith(".rss") || path.endsWith(".xml") || path.endsWith("/feed") -> SourceType.RSS
            path.contains("torznab") || path.contains("newznab") -> SourceType.TORZNAB
            listOf(".mp4", ".m4v", ".mkv", ".webm", ".mov").any { path.endsWith(it) } -> SourceType.VIDEO
            else -> null
        }
    }

    private fun isExecutable(b: ByteArray): Boolean {
        if (b.size < 4) return false
        val mz = b[0] == 'M'.code.toByte() && b[1] == 'Z'.code.toByte()
        val elf = b[0] == 0x7F.toByte() && b[1] == 'E'.code.toByte() && b[2] == 'L'.code.toByte() && b[3] == 'F'.code.toByte()
        val dex = b[0] == 'd'.code.toByte() && b[1] == 'e'.code.toByte() && b[2] == 'x'.code.toByte() && b[3] == '\n'.code.toByte()
        // ZIP/APK: só é aceito se não houver vídeo (MP4 nunca começa com PK)
        val zip = b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() && b[2] == 3.toByte() && b[3] == 4.toByte()
        return mz || elf || dex || zip
    }

    private fun isTorrent(b: ByteArray): Boolean {
        if (b.isEmpty() || b[0] != 'd'.code.toByte()) return false
        val s = String(b, 0, minOf(b.size, 4096), Charsets.ISO_8859_1)
        return s.contains("4:info") || s.contains("8:announce") || s.contains("13:announce-list")
    }

    private fun isVideo(b: ByteArray): Boolean {
        if (b.size < 12) return false
        val ftyp = b[4] == 'f'.code.toByte() && b[5] == 't'.code.toByte() && b[6] == 'y'.code.toByte() && b[7] == 'p'.code.toByte()
        val ebml = b[0] == 0x1A.toByte() && b[1] == 0x45.toByte() && b[2] == 0xDF.toByte() && b[3] == 0xA3.toByte()
        val ts = b[0] == 0x47.toByte() && (b.size < 189 || b[188] == 0x47.toByte())
        return ftyp || ebml || ts
    }
}

/**
 * O que cada fonte sabe fazer (exibido em Gerenciar fontes e usado pela pesquisa).
 * Para APIs REST, vem do descritor; para os demais formatos, do próprio formato.
 */
data class SourceCapabilities(
    val textSearch: Boolean,
    val idLookup: Boolean,
    val episodes: Boolean,
    val pagination: Boolean,
    val mediaFormats: List<String>,
    val auth: Boolean = false,
    val ratePerMinute: Int? = null,
) {
    val summary: String get() = listOfNotNull(
        "pesquisa por texto ${mark(textSearch)}", "por identificador ${mark(idLookup)}", "episódios ${mark(episodes)}",
        "paginação ${mark(pagination)}", "formatos: ${mediaFormats.joinToString(", ")}",
        if (auth) "autenticação: sim" else null, ratePerMinute?.let { "limite: $it/min" },
    ).joinToString(" • ")

    companion object {
        private fun mark(b: Boolean) = if (b) "✓" else "✗"

        fun of(s: UserSource): SourceCapabilities = when (s.type) {
            SourceType.CATALOG_JSON -> SourceCapabilities(true, true, true, true, listOf("HTTP", "HLS", "magnet", ".torrent"))
            SourceType.RSS -> SourceCapabilities(true, false, false, true, listOf("HTTP", "HLS", "magnet", ".torrent"))
            SourceType.M3U -> SourceCapabilities(true, false, false, false, listOf("HLS ao vivo"))
            SourceType.HLS -> SourceCapabilities(false, false, false, false, listOf("HLS"))
            SourceType.VIDEO -> SourceCapabilities(false, false, false, false, listOf("HTTP"))
            SourceType.MAGNET -> SourceCapabilities(false, false, true, false, listOf("magnet"))
            SourceType.TORRENT -> SourceCapabilities(false, false, true, false, listOf(".torrent"))
            SourceType.SEARCH -> SourceCapabilities(true, s.url.contains("{tmdb}"), true,
                s.url.contains("{page}") || s.url.contains("{offset}"), listOf("HTTP", "HLS", "magnet", ".torrent"))
            SourceType.OPENSEARCH -> {
                val t = s.config?.let { runCatching { JSONObject(it).optString("template") }.getOrNull() }.orEmpty()
                SourceCapabilities(true, false, false, t.contains("{page}") || t.contains("{offset}"), listOf("HTTP", "HLS", "magnet", ".torrent"))
            }
            SourceType.REST -> s.config?.let { runCatching { RestDescriptor.parse(it).capabilities }.getOrNull() }
                ?: SourceCapabilities(true, false, false, false, listOf("?"))
            SourceType.TORZNAB -> s.config?.let { runCatching { TorznabAdapter(TorznabCaps.fromConfig(it), null).capabilities.copy(auth = true) }.getOrNull() }
                ?: SourceCapabilities(true, false, false, true, listOf("magnet", ".torrent"), auth = true)
        }
    }
}
