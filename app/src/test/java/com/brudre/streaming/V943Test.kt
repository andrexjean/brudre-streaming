package com.brudre.streaming

import com.brudre.streaming.sources.ClassifiedException
import com.brudre.streaming.sources.CredentialRefs
import com.brudre.streaming.sources.ErrorClassifier
import com.brudre.streaming.sources.ErrorKind
import com.brudre.streaming.sources.HttpStatusException
import com.brudre.streaming.sources.RetryAfterException
import com.brudre.streaming.sources.Secrets
import com.brudre.streaming.sources.SourceHttp
import com.brudre.streaming.sources.SourceStage
import com.brudre.streaming.sources.SourceStats
import com.brudre.streaming.sources.SourceTest
import com.brudre.streaming.sources.SourceType
import com.brudre.streaming.sources.Torznab
import com.brudre.streaming.sources.TorznabAdapter
import com.brudre.streaming.sources.UserSource
import com.brudre.streaming.sources.UserSourcesJson
import com.brudre.streaming.torrent.TorrentEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * v9.2.43 — testes SIMULADOS (JVM, sem rede): credenciais fora do que é gravado, etapas do diagnóstico,
 * classificação de falhas e novas tentativas. Os de rede real estão em V943DeviceTest (emulador).
 */
class V943Test {
    private val key = "S3gr3d0Api42xyz"

    @After fun clean() { CredentialRefs.lookup = { _, _ -> null } }

    // 1. Sanitização de API keys
    @Test fun t01_redactsKeysTokensAndPasswordsInAnyText() {
        val t = "GET https://h.org/api?t=search&apikey=$key&q=x falhou; token=abc123def; password%3Dsenha123 Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.abc"
        val r = Secrets.redactText(t)!!
        assertFalse(r.contains(key)); assertFalse(r.contains("abc123def")); assertFalse(r.contains("senha123")); assertFalse(r.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertTrue(r.contains("apikey=***") && r.contains("t=search") && r.contains("q=x"))
        // marcadores ficam (não são segredo)
        assertEquals("https://h.org/dl?apikey=${Secrets.REF}", Secrets.redactText("https://h.org/dl?apikey=${Secrets.REF}"))
        assertEquals("https://h.org/s?q={query}&api_key={key}", Secrets.redactText("https://h.org/s?q={query}&api_key={key}"))
        // magnet com passkey de tracker (codificado dentro de tr=)
        val m = "magnet:?xt=urn:btih:bf16e86e7636315da5c443296bee3dd58c051039&tr=https%3A%2F%2Ft.org%2Fannounce%3Fpasskey%3Dzz99secret"
        assertFalse(Secrets.sanitizeUrl(m)!!.contains("zz99secret"))
        assertTrue(Secrets.sanitizeUrl(m)!!.contains("xt=urn:btih:bf16e86e7636315da5c443296bee3dd58c051039"))
    }

    // 2. Persistência segura
    @Test fun t02_persistedSourcesStatsAndExportsCarryNoSecrets() {
        val test = SourceTest(false, 1L, "Falhou: https://h.org/api?apikey=$key", 0, ErrorKind.HTTP.name, "HttpStatus: GET …?token=$key")
        val s = UserSource("search_x", "Idx", "https://h.org/api?apikey=${Secrets.REF}", SourceType.SEARCH, lastTest = test,
            stats = SourceStats(lastError = "erro em …&password=$key"))
        val json = UserSourcesJson.listToJson(listOf(s))
        assertFalse(json.contains(key))
        assertFalse(UserSourcesJson.export(listOf(s)).contains(key))
        val e = TorrentEntry("h", "n", magnet = null, torrentUrl = "https://h.org/dl/x.torrent?jackett_apikey=$key")
        assertFalse(e.toJson().toString().contains(key))
    }

    // 3. Migração de registros antigos
    @Test fun t03_oldRecordsAreCleanedWhenRead() {
        val old = JSONObject().put("id", "x").put("name", "Antiga").put("url", "https://h.org/lista.json?token=$key").put("type", "CATALOG_JSON")
            .put("test", JSONObject().put("ok", false).put("at", 1).put("msg", "erro apikey=$key").put("detail", "…token=$key"))
            .put("stats", JSONObject().put("err", "falha password=$key"))
        val s = UserSource.fromJson(old)!!
        assertFalse(s.lastTest!!.message.contains(key)); assertFalse(s.lastTest!!.detail!!.contains(key)); assertFalse(s.stats.lastError!!.contains(key))
        // o segredo do endereço sai para a credencial; no endereço fica o marcador
        val (url, found) = Secrets.extract(s.url)
        assertEquals("https://h.org/lista.json?token=${Secrets.REF}", url)
        assertEquals(mapOf("token" to key), found)
        assertEquals(key, Secrets.valueFor(Secrets.encodeMap(found), "token"))
        // idempotente: endereço já limpo não muda
        assertEquals(url to emptyMap<String, String>(), Secrets.extract(url))
        // chave da autenticação preservada junto dos segredos do endereço
        val merged = Secrets.encodeMap(found, "chave-de-auth")
        assertEquals("chave-de-auth", Secrets.keyPart(merged)); assertEquals(key, Secrets.valueFor(merged, "token"))
        assertEquals("só", Secrets.keyPart("só"))
    }

    // 4. URLs autenticadas sem exposição de credenciais
    @Test fun t04_authenticatedLinksResolveOnlyAtRequestTimeAndOnlyForTheOwnerHost() {
        val dl = "https://idx.example.org:9117/dl/indexador/?jackett_apikey=$key&path=abc&file=Serie"
        val ref = Secrets.referenceKnown(dl, key)
        assertFalse(ref.contains(key))
        assertEquals("https://idx.example.org:9117/dl/indexador/?jackett_apikey=${Secrets.REF}&path=abc&file=Serie", ref)
        assertFalse(Secrets.referenceKnown("https://idx.example.org/dl/$key/x.torrent", key).contains(key)) // chave no caminho: mascarada
        CredentialRefs.lookup = { host, port -> if (host == "idx.example.org" && port == 9117) key else null }
        assertEquals(dl, CredentialRefs.resolveFor(ref, "idx.example.org", 9117))
        // outro host (ex.: destino de redirecionamento) nunca recebe a chave
        assertNull(CredentialRefs.resolveFor(ref.replace("idx.example.org", "outro.example.net"), "outro.example.net", 9117))
        // sem marcador: passa intacto, sem consulta
        assertEquals("https://a.org/x.torrent", CredentialRefs.resolveFor("https://a.org/x.torrent", "a.org", 443))
        // credencial com vários segredos do endereço (JSON): cada parâmetro recebe o seu
        CredentialRefs.lookup = { _, _ -> Secrets.encodeMap(mapOf("username" to "u1", "password" to "p@ss")) }
        assertEquals("https://h.org/get?username=u1&password=p%40ss", CredentialRefs.resolveFor("https://h.org/get?username=${Secrets.REF}&password=${Secrets.REF}", "h.org", 443))
    }

    @Test fun t04b_torznabResultLinksNeverCarryTheKey() {
        val caps = Torznab.parseCaps(javaClass.classLoader!!.getResource("torznab/caps.xml")!!.readText(), "https://indexador.exemplo.org/api")
        val xml = javaClass.classLoader!!.getResource("torznab/search-p1.xml")!!.readText()
            .replace("/dl/brudre-teste-serie.torrent", "/dl/brudre-teste-serie.torrent?jackett_apikey=$key")
        val page = TorznabAdapter(caps, key).parse(xml, "search_tz")
        val urls = page.items.flatMap { it.sources + it.episodes.flatMap { e -> e.sources } }.map { it.url }
        assertTrue(urls.isNotEmpty())
        assertTrue(urls.none { it.contains(key) })
        assertTrue(urls.all { it.contains(Secrets.REF) })
    }

    // 5–8. Etapas do diagnóstico
    @Test fun t05_t08_stagesSeparateRegisteredReachableSearchAndPlayback() {
        val base = UserSource("x", "x", "https://a.org/api?q={query}", SourceType.SEARCH)
        assertEquals(SourceStage.REGISTERED, base.stage) // 5. só cadastrada
        val reachable = base.copy(lastTest = SourceTest(true, 1, "respondeu sem resultados", 0, ErrorKind.NO_RESULTS.name, stage = SourceStage.REACHABLE))
        assertEquals(SourceStage.REACHABLE, reachable.stage) // 6. acessível sem pesquisa
        assertTrue(reachable.statusLabel.contains("Acessível") && reachable.statusLabel.contains("sem resultados"))
        val searchNoVideo = base.copy(lastTest = SourceTest(true, 1, "3 resultados, nenhum utilizável", 0, ErrorKind.UNAVAILABLE.name, stage = SourceStage.SEARCH_OK))
        assertEquals(SourceStage.SEARCH_OK, searchNoVideo.stage) // 7. pesquisa sem vídeo reproduzível
        assertTrue(searchNoVideo.statusLabel.contains("indisponível"))
        val played = searchNoVideo.copy(stats = SourceStats(ok = 1, playOkAt = 5))
        assertEquals(SourceStage.PLAYBACK_OK, played.stage) // 8. reprodução validada
        // teste novo com falha: mostra o erro, nunca "reproduzível"
        val broken = played.copy(lastTest = SourceTest(false, 9, "Tempo esgotado", 0, ErrorKind.TIMEOUT.name))
        assertTrue(broken.hasError); assertTrue(broken.statusLabel.startsWith("Erro: Tempo esgotado"))
        // etapa e latência gravadas e relidas
        val t = SourceTest(true, 7, "ok", 2, stage = SourceStage.SEARCH_OK, latencyMs = 321)
        assertEquals(t, SourceTest.fromJson(t.toJson()))
        assertEquals(1, SourceStats.fromJson(SourceStats(playOkAt = 1).toJson()).playOkAt)
    }

    // 9–10. Falhas de DNS, TLS e autenticação
    @Test fun t09_t10_dnsTlsAndAuthAreSeparated() {
        assertEquals(ErrorKind.DNS, ErrorClassifier.classify(UnknownHostException("Unable to resolve host \"x.invalid\"")).kind)
        assertEquals(ErrorKind.TLS, ErrorClassifier.classify(SSLHandshakeException("Chain validation failed")).kind)
        assertEquals(ErrorKind.AUTH, ErrorClassifier.classify(HttpStatusException(401)).kind)
        assertEquals(ErrorKind.HTTP, ErrorClassifier.classify(HttpStatusException(403)).kind)
        try { Torznab.checkError("<error code=\"100\" description=\"Incorrect user credentials\"/>"); fail() } catch (e: ClassifiedException) { assertEquals(ErrorKind.AUTH, e.kind) }
        // mensagem técnica nunca carrega o segredo
        val f = ErrorClassifier.classify(java.io.IOException("falhou em https://h.org/x?apikey=$key"))
        assertFalse(f.technical.contains(key)); assertFalse(f.message.contains(key))
    }

    // 11. Tempo esgotado e novas tentativas
    @Test fun t11_retriesTransientFailuresWithBackoffAndDeadline() = runBlocking {
        val waits = ArrayList<Long>()
        var calls = 0
        val r = SourceHttp.retrying(60_000, sleep = { waits += it }) { calls++; if (calls < 3) throw SocketTimeoutException("timeout") else "ok" }
        assertEquals("ok", r); assertEquals(3, calls); assertEquals(listOf(1_000L, 2_000L), waits)
        // não transitória: uma tentativa só
        calls = 0
        try { SourceHttp.retrying(60_000, sleep = {}) { calls++; throw HttpStatusException(404) }; fail() } catch (e: HttpStatusException) { assertEquals(1, calls) }
        calls = 0
        try { SourceHttp.retrying(60_000, sleep = {}) { calls++; throw HttpStatusException(401) }; fail() } catch (e: HttpStatusException) { assertEquals(1, calls) }
        // limite de tentativas
        calls = 0
        try { SourceHttp.retrying(60_000, sleep = {}) { calls++; throw HttpStatusException(503) }; fail() } catch (e: HttpStatusException) { assertEquals(SourceHttp.MAX_ATTEMPTS, calls) }
        // Retry-After respeitado e limitado a 8 s
        waits.clear(); calls = 0
        SourceHttp.retrying(60_000, sleep = { waits += it }) { calls++; if (calls == 1) throw RetryAfterException(429, 3_000) else "ok" }
        assertEquals(listOf(3_000L), waits)
        assertEquals(SourceHttp.MAX_RETRY_AFTER_MS, SourceHttp.retryDelay(RetryAfterException(429, 120_000), 1))
        assertEquals(5_000L, SourceHttp.parseRetryAfter("5")); assertNull(SourceHttp.parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT"))
        // prazo total: não tenta se a espera passaria do prazo
        var clock = 0L
        calls = 0
        try { SourceHttp.retrying(900, now = { clock }, sleep = { clock += it }) { calls++; throw SocketTimeoutException("timeout") }; fail() } catch (e: SocketTimeoutException) { assertEquals(1, calls) }
        assertEquals(24_000L, SourceHttp.deadlineMs(12)); assertEquals(60_000L, SourceHttp.deadlineMs(60))
    }

    // 12. Paginação (offset/limit e total do Torznab; resposta vazia ≠ falha)
    @Test fun t12_paginationAndEmptyResponses() {
        val caps = Torznab.parseCaps(javaClass.classLoader!!.getResource("torznab/caps.xml")!!.readText(), "https://indexador.exemplo.org/api")
        val a = TorznabAdapter(caps, null)
        assertTrue(a.url(com.brudre.streaming.sources.SearchTemplate.Query("x"), 3).contains("offset=6&limit=3"))
        val empty = a.parse("<rss><channel><torznab:response offset=\"0\" total=\"0\"/></channel></rss>", "s")
        assertEquals(0, empty.raw); assertFalse(empty.hasMore); assertTrue(empty.items.isEmpty())
        val p2 = a.parse(javaClass.classLoader!!.getResource("torznab/search-p2.xml")!!.readText(), "s")
        assertTrue("página com itens descartados: há resultados brutos, nenhum reproduzível", p2.raw > 0 && p2.items.isEmpty())
    }

    // 15. Cancelamento de pesquisas
    @Test fun t15_cancellationStopsRetriesImmediately() = runBlocking {
        var calls = 0
        val job = async { SourceHttp.retrying(60_000) { calls++; throw SocketTimeoutException("timeout") } }
        delay(200) // a 1ª tentativa falhou e está esperando 1 s para tentar de novo
        job.cancel()
        try { job.await(); fail() } catch (e: CancellationException) { }
        delay(1_500)
        assertEquals("nenhuma tentativa depois de cancelar", 1, calls)
    }
}
