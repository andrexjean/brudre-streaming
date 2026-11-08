package com.brudre.streaming.sources

import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Credenciais fora de tudo o que é gravado (9.2.43).
 *
 * Regra: chave de API, token ou senha só existem no [CredentialStore] (cifrados pelo Android Keystore).
 * - Endereços gravados (fontes, histórico de torrents, links de resultados) levam o marcador [REF] no lugar do segredo.
 * - O segredo real entra na requisição só na hora de executá-la, pelo [CredentialRefs] (interceptor do cliente
 *   protegido), e só para o MESMO host:porta da fonte dona da credencial. Redirecionamentos não recebem o segredo.
 * - Textos de diagnóstico, logs e relatórios passam por [redactText].
 */
object Secrets {
    /** Marcador gravado no lugar do segredo (codificado: endereços com "{" não são URI válidas). */
    const val REF = "%7Bcred%7D"
    const val REF_DECODED = "{cred}"
    const val MASK = "***"

    /** Nomes de parâmetro que carregam segredo (comparação sem maiúsculas). Usuário não é segredo; senha é. */
    val SENSITIVE = setOf(
        "apikey", "api_key", "api-key", "jackett_apikey", "passkey", "token", "access_token", "auth_token", "authkey",
        "auth_key", "password", "passwd", "pwd", "secret", "client_secret", "x-plex-token", "x-emby-token",
    )

    fun isSensitive(name: String) = name.lowercase() in SENSITIVE

    private fun isPlaceholder(v: String) = v == REF || v.equals(REF_DECODED, true) || v == "{key}" || v.equals("%7Bkey%7D", true) || v == MASK

    private val NAMES = SENSITIVE.joinToString("|") { Regex.escape(it) }

    /** `nome=valor` (também codificado dentro de outro parâmetro: `nome%3Dvalor`) — usado só para TEXTO livre. */
    // Antes do nome: início, qualquer não alfanumérico, ou "?"/"&" codificados (%3F, %26) dentro de outro parâmetro.
    private val IN_TEXT = Regex("(?i)(^|[^A-Za-z0-9_]|%3F|%26)($NAMES)(=|%3D|\\s*[:=]\\s*\"|\"\\s*:\\s*\")([^&\\s\"'<>#]+?)(?=(&|%26|\\s|\"|'|<|>|#|$))")
    private val BEARER = Regex("(?i)\\b(Bearer)\\s+[A-Za-z0-9._~+/=-]{8,}")

    /** Remove segredos de qualquer texto (mensagens de erro, logs, relatórios, exportações). */
    fun redactText(s: String?): String? = s?.let { t ->
        BEARER.replace(IN_TEXT.replace(t) { m -> if (isPlaceholder(m.groupValues[4])) m.value else m.groupValues[1] + m.groupValues[2] + m.groupValues[3] + MASK }, "$1 $MASK")
    }

    fun containsSecret(s: String?): Boolean = s != null && redactText(s) != s

    private fun splitQuery(url: String): Triple<String, List<Pair<String, String>>, String> {
        val frag = url.substringAfter('#', "")
        val noFrag = url.substringBefore('#')
        val base = noFrag.substringBefore('?')
        val q = noFrag.substringAfter('?', "")
        val pairs = if (q.isEmpty()) emptyList() else q.split('&').filter { it.isNotEmpty() }.map { it.substringBefore('=') to it.substringAfter('=', "") }
        return Triple(base, pairs, frag)
    }

    private fun join(base: String, pairs: List<Pair<String, String>>, frag: String) =
        base + (if (pairs.isEmpty()) "" else "?" + pairs.joinToString("&") { (k, v) -> "$k=$v" }) + (if (frag.isEmpty()) "" else "#$frag")

    private fun dec(v: String) = runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)

    /**
     * Separa os segredos de um endereço para gravar a fonte: devolve o endereço com [REF] no lugar de cada valor
     * sensível e o mapa nome → valor (vai para o [CredentialStore]). Magnets não são alterados aqui.
     */
    fun extract(url: String): Pair<String, Map<String, String>> {
        if (url.startsWith("magnet:", true)) return url to emptyMap()
        val (base, pairs, frag) = splitQuery(url.trim())
        val found = LinkedHashMap<String, String>()
        val out = pairs.map { (k, v) ->
            if (isSensitive(dec(k)) && v.isNotEmpty() && !isPlaceholder(v)) { found[dec(k)] = dec(v); k to REF } else k to v
        }
        return if (found.isEmpty()) url to emptyMap() else join(base, out, frag) to found
    }

    /**
     * Endereço que vai ser GRAVADO sem dono de credencial (histórico de torrents, registros antigos):
     * valores sensíveis viram [MASK]; marcadores [REF] e {key} ficam. Magnets: o mesmo nos parâmetros de tracker (tr=).
     */
    fun sanitizeUrl(url: String?): String? {
        if (url == null) return null
        if (url.startsWith("magnet:", true)) return redactText(url)
        val (base, pairs, frag) = splitQuery(url)
        if (pairs.none { (k, v) -> isSensitive(dec(k)) && !isPlaceholder(v) }) return redactText(url)
        return redactText(join(base, pairs.map { (k, v) -> if (isSensitive(dec(k)) && !isPlaceholder(v)) k to MASK else k to v }, frag))
    }

    /** Troca um segredo CONHECIDO (a chave da fonte) pelo marcador [REF] em links devolvidos pelo servidor. */
    fun referenceKnown(url: String, secret: String?): String {
        if (secret.isNullOrEmpty() || url.startsWith("magnet:", true)) return url
        val (base, pairs, frag) = splitQuery(url)
        var changed = false
        val out = pairs.map { (k, v) -> if (dec(v) == secret) { changed = true; k to REF } else k to v }
        val withRefs = if (changed) join(base, out, frag) else url
        // segredo no caminho (/dl/<chave>/…): não há como referenciar com segurança → mascarado
        return if (withRefs.contains(secret)) withRefs.replace(secret, MASK) else withRefs
    }

    private const val KEY_ENTRY = "__key"

    /**
     * Credencial guardada: texto simples (uma chave) ou JSON {"parametro": "valor", "__key": "chave da autenticação"}.
     * [existing] = credencial que a fonte já tinha (a chave digitada em "Credencial"), preservada.
     */
    fun encodeMap(m: Map<String, String>, existing: String? = null): String {
        val prevKey = existing?.let { keyPart(it) }
        val prevMap = existing?.trim()?.takeIf { it.startsWith("{") }?.let { t -> runCatching { JSONObject(t) }.getOrNull() }
        if (prevMap == null && prevKey == null && m.size == 1 && m.keys.first().lowercase() in setOf("apikey", "api_key")) return m.values.first()
        return JSONObject().apply {
            prevMap?.let { o -> o.keys().forEach { k -> put(k, o.get(k)) } }
            m.forEach { (k, v) -> put(k, v) }
            if (prevKey != null && !has(KEY_ENTRY)) put(KEY_ENTRY, prevKey)
        }.toString()
    }

    /** A chave usada em {key}, cabeçalhos e apikey do Torznab: o texto simples, ou a entrada "__key" do JSON. */
    fun keyPart(stored: String?): String? {
        val t = stored?.trim()?.ifEmpty { null } ?: return null
        if (!t.startsWith("{")) return t
        return runCatching { JSONObject(t).optString(KEY_ENTRY).ifEmpty { null } }.getOrNull()
    }

    /** Troca (ou remove, com null) a chave de autenticação, preservando os segredos do endereço. */
    fun withKey(stored: String?, key: String?): String? {
        val t = stored?.trim()
        if (t == null || !t.startsWith("{")) return key
        val o = runCatching { JSONObject(t) }.getOrNull() ?: return key
        if (key == null) o.remove(KEY_ENTRY) else o.put(KEY_ENTRY, key)
        return if (o.length() == 0) null else o.toString()
    }

    /** Todos os valores secretos de uma credencial guardada (para reconhecê-los em textos e caches). */
    fun values(stored: String?): List<String> {
        val t = stored?.trim()?.ifEmpty { null } ?: return emptyList()
        if (!t.startsWith("{")) return listOf(t)
        return runCatching { JSONObject(t).let { o -> o.keys().asSequence().map { o.optString(it) }.filter { it.isNotEmpty() }.toList() } }.getOrDefault(emptyList())
    }

    /** Segredos conhecidos (credenciais guardadas), definidos pelo app; usados para proteger caches e textos. */
    @Volatile var known: () -> List<String> = { emptyList() }

    /** Troca, num texto a ser gravado em cache, valores de parâmetro iguais a um segredo conhecido pelo marcador. */
    fun protectKnown(text: String): String {
        var out = text
        known().filter { it.length >= 6 }.forEach { v ->
            out = out.replace(Regex("(=|%3D)" + Regex.escape(v) + "(?=(&|%26|\"|'|\\s|<|#|$))"), "$1$REF")
            val enc = URLEncoder.encode(v, "UTF-8")
            if (enc != v) out = out.replace(Regex("(=|%3D)" + Regex.escape(enc) + "(?=(&|%26|\"|'|\\s|<|#|$))"), "$1$REF")
        }
        // Tokens gerados pelo PROVEDOR em cada item (URLs assinadas) não são credenciais do usuário e ficam:
        // mascará-los quebraria a reprodução pelo cache.
        return out
    }

    fun valueFor(stored: String, param: String): String? {
        val t = stored.trim()
        if (t.startsWith("{")) return runCatching { JSONObject(t).let { o -> o.keys().asSequence().firstOrNull { it.equals(param, true) }?.let { o.getString(it) } } }.getOrNull()
        return t.ifEmpty { null }
    }

    /** Substitui cada [REF] do endereço pelo valor real (usado SÓ no instante da requisição, nunca gravado). */
    fun resolve(url: String, stored: String): String {
        val (base, pairs, frag) = splitQuery(url)
        return join(base, pairs.map { (k, v) ->
            if (v == REF || v.equals(REF_DECODED, true)) k to URLEncoder.encode(valueFor(stored, dec(k)) ?: "", "UTF-8") else k to v
        }, frag)
    }

    fun hasRef(url: String) = url.contains(REF, true) || url.contains(REF_DECODED, true)
}

/**
 * Resolução das credenciais no instante da requisição. O [lookup] (definido pelo app) devolve a credencial guardada
 * da fonte cadastrada naquele host:porta, ou null. Endereço sem marcador passa intacto.
 */
object CredentialRefs {
    @Volatile var lookup: (host: String, port: Int) -> String? = { _, _ -> null }

    fun resolveFor(url: String, host: String, port: Int): String? {
        if (!Secrets.hasRef(url)) return url
        val stored = lookup(host.lowercase(), port) ?: return null
        return Secrets.resolve(url, stored)
    }
}
