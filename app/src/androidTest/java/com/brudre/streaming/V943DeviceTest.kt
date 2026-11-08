package com.brudre.streaming

import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.brudre.streaming.data.TorrentCleanup
import com.brudre.streaming.player.PlayerActivity
import com.brudre.streaming.sources.ErrorKind
import com.brudre.streaming.sources.LocalServers
import com.brudre.streaming.sources.SearchTemplate
import com.brudre.streaming.sources.Secrets
import com.brudre.streaming.sources.SourceStage
import com.brudre.streaming.sources.SourceType
import com.brudre.streaming.sources.UserSource
import com.brudre.streaming.torrent.TorrentEntry
import com.brudre.streaming.ui.MainActivity
import com.brudre.streaming.ui.MainViewModel
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * v9.2.43 no emulador com rede real (NÃO é teste físico no Mi Stick).
 * Credenciais: servidor Torznab de referência do projeto (ci/torznab_server.py), cujos links /dl/ exigem a chave.
 * Cada resultado vai para o log como "SURVEY|nome|PASS/PARTIAL/FAIL/BLOCKED|evidência".
 */
@RunWith(AndroidJUnit4::class)
class V943DeviceTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val services get() = TestUtil.services
    private val args get() = InstrumentationRegistry.getArguments()
    private val key: String? get() = args.getString("torznabKey")?.ifBlank { null }
    private val lanHost = "https://10.0.2.2:8443"
    private val raw = "https://raw.githubusercontent.com/andrexjean/brudre-streaming/main/samples"

    @Before fun setUp() {
        services.settings.update { it.copy(userSourcesFirst = false, torrentCleanup = TorrentCleanup.ON_CLOSE, audioLanguage = "", subtitleLanguage = "off") }
        services.sourceStore.current.forEach { services.sourceStore.remove(it.id); services.credentials.put(it.id, null) }
        services.searchRepo.clear()
    }

    @After fun tearDown() {
        services.sourceStore.current.forEach { services.sourceStore.remove(it.id); services.credentials.put(it.id, null) }
        LocalServers.sync(emptyMap())
        services.settings.update { it.copy(audioLanguage = "pt", subtitleLanguage = "pt") }
    }

    private fun survey(name: String, status: String, detail: String) = TestUtil.log("SURVEY|$name|$status|$detail")

    private fun saveViaUi(form: MainViewModel.SourceForm): Triple<Boolean, String, String?> {
        com.brudre.streaming.ui.components.IntroState.shown = true
        var result: Triple<Boolean, String, String?>? = null
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a -> androidx.lifecycle.ViewModelProvider(a)[MainViewModel::class.java].saveSource(null, form) { ok, msg, d -> result = Triple(ok, msg, d) } }
            TestUtil.waitFor(120_000, "cadastro da fonte") { result != null }
        }
        return result!!
    }

    /** Todo arquivo persistente do app (preferências, banco, caches, metadados) — onde um segredo NÃO pode estar. */
    private fun persistedFiles(): List<File> {
        val data = File(ctx.applicationInfo.dataDir)
        return listOf(File(data, "shared_prefs"), File(data, "databases"), ctx.filesDir, ctx.cacheDir, ctx.getExternalFilesDir(null))
            .filterNotNull().flatMap { d -> d.walkTopDown().filter { it.isFile && it.length() < 20_000_000 }.toList() }
    }

    private fun filesContaining(secret: String): List<String> {
        return persistedFiles().filter { f ->
            runCatching { String(f.readBytes(), Charsets.ISO_8859_1).contains(secret) }.getOrDefault(false)
        }.map { it.path.substringAfter(ctx.packageName) }
    }

    /** 1, 2, 4: chave do indexador nunca gravada — só no CredentialStore — e o download autenticado continua funcionando. */
    @Test fun t50_credentialsNeverPersistedAndAuthenticatedLinksStillWork() {
        val k: String = key ?: run { survey("Credenciais (Torznab local)", "BLOCKED", "servidor de referência ausente"); assumeTrue(false); return }
        val cert = LocalServers.fetchCertificate("$lanHost/api")
        val r = saveViaUi(MainViewModel.SourceForm("Torznab com chave", "$lanHost/api?apikey=$k", localPin = cert.pin))
        assertTrue(r.second, r.first)
        val src = services.sourceStore.current.single { it.type == SourceType.TORZNAB }
        val hits = runBlocking { services.searchRepo.page(SearchTemplate.Query("BruDre Teste Serie", type = "tv", season = 1, category = "series"), 1) }.hits
        val links = hits.flatMap { h -> h.item.episodes.flatMap { it.sources } }.map { it.url }
        TestUtil.log("Links dos resultados: $links")
        assertTrue(links.isNotEmpty() && links.all { it.contains(Secrets.REF) && !it.contains(k) })
        // O link /dl/ exige a chave (401 sem ela): só funciona porque o app a resolve na hora da requisição
        val ep = hits.first { it.item.episodes.single().number == 2 }.item
        // Como a tela de detalhes faz: o vídeo leva a origem (fonte cadastrada), para o diagnóstico registrar a reprodução
        val p0 = ep.playables().single()
        val p = p0.copy(origins = mapOf(com.brudre.streaming.sources.SourceRanker.refKey(p0.torrent!!) to src.id))
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(ctx, ep.title, p, 0)).use { sc ->
            TestUtil.waitFor(180_000, "reprodução pelo link autenticado") {
                var st = 0; var pos = 0L
                sc.onActivity { st = it.playerForTest()?.playbackState ?: 0; pos = it.playerForTest()?.currentPosition ?: 0 }
                st == Player.STATE_READY && pos > 2_000
            }
        }
        TestUtil.waitFor(10_000, "reprodução registrada") { services.sourceStore.get(src.id)?.stage == SourceStage.PLAYBACK_OK }
        // Relatórios e exportações
        val report = com.brudre.streaming.data.Homologation.report()
        assertFalse(report.contains(k)); assertFalse(services.sourceStore.export().contains(k))
        Thread.sleep(1_500) // gravações assíncronas
        val leaked = filesContaining(k)
        val log = TestUtil.shell("logcat -d -v brief --pid=${android.os.Process.myPid()}")
        val logLeak = log.lines().filter { it.contains(k) }.take(3)
        TestUtil.log("Arquivos com a chave: $leaked • linhas de log com a chave: ${logLeak.size}")
        assertTrue("chave gravada em: $leaked", leaked.isEmpty())
        assertTrue("chave no log: $logLeak", logLeak.isEmpty())
        assertEquals(k, services.credentials.key(src.id))
        survey("Credenciais fora do armazenamento", "PASS", "links {cred}; download autenticado tocou S01E02; ${persistedFiles().size} arquivos e o logcat varridos sem a chave; relatório e exportação limpos")
    }

    /** 3: registros antigos com segredo (fonte, teste, histórico de torrent) são migrados e o endereço continua funcionando. */
    @Test fun t51_oldRecordsMigrated() {
        val secret = "TokenAntigo98765"
        // api_key: o servidor de arquivos do GitHub ignora (com "token" ele tentaria autenticar e responderia 404)
        val url = "$raw/feeds/blender-open-movies.json?api_key=$secret"
        val old = UserSource(UserSource.idFor(SourceType.CATALOG_JSON, url), "Antiga com token", url, SourceType.CATALOG_JSON,
            lastTest = com.brudre.streaming.sources.SourceTest(false, 1, "Falhou em $url", 0, ErrorKind.HTTP.name, "GET $url"))
        services.sourceStore.put(old)
        val prefs = File(File(ctx.applicationInfo.dataDir, "shared_prefs"), "brudre_sources.xml")
        TestUtil.log("Antes: segredo no arquivo de fontes = ${prefs.readText().contains(secret)}")
        services.torrentLibrary.addUrl("https://example.org/x.torrent?passkey=$secret", "antigo")
        val moved = services.sourceStore.migrateSecrets(services.credentials)
        services.torrentLibrary.sanitizeStored()
        val now = services.sourceStore.current.single { it.name == "Antiga com token" }
        assertEquals(1, moved)
        assertTrue(now.url.endsWith("api_key=${Secrets.REF}"))
        assertEquals(secret, Secrets.valueFor(services.credentials.get(now.id)!!, "api_key"))
        Thread.sleep(500)
        assertFalse(prefs.readText().contains(secret))
        assertFalse(File(File(ctx.applicationInfo.dataDir, "shared_prefs"), "brudre_torrents.xml").readText().contains(secret))
        // o endereço com o marcador continua funcionando (a credencial entra só na requisição)
        val r = runBlocking { services.inspector.inspect(now.url, forcedType = SourceType.CATALOG_JSON) }
        TestUtil.log("Depois da migração: ${r.test.message} (${r.test.stage})")
        assertTrue(r.test.ok)
        services.torrentLibrary.current.filter { it.name == "antigo" }.forEach { services.torrentLibrary.remove(it.hash) }
        survey("Migração de registros antigos", "PASS", "fonte com token: segredo movido para o CredentialStore, endereço {cred} testado OK; histórico de torrent limpo")
    }

    /** 5–10: etapas do diagnóstico e falhas classificadas, com data, latência e etapa. */
    @Test fun t52_diagnosticStagesAndFailures() {
        val k: String = key ?: run { survey("Diagnóstico de fontes", "BLOCKED", "servidor de referência ausente"); assumeTrue(false); return }
        val cert = LocalServers.fetchCertificate("$lanHost/api")
        LocalServers.register("$lanHost/api", cert.pin)
        fun insp(u: String, kk: String? = null) = runBlocking { services.inspector.inspect(u, key = kk) }
        val rows = ArrayList<String>()
        fun row(name: String, t: com.brudre.streaming.sources.SourceTest) { rows += "$name → ${t.stage.label}${t.kind?.let { " / $it" } ?: ""} (${t.latencyMs} ms)"; TestUtil.log("Diagnóstico: ${rows.last()} — ${t.message}") }
        // 5. cadastrada (sem validação)
        val pending = UserSource("feed_x", "Pendente", "$raw/feeds/nao-existe.json", SourceType.CATALOG_JSON, enabled = false, pending = true,
            lastTest = com.brudre.streaming.sources.SourceTest(false, 1, "Salva sem validar", 0))
        assertEquals(SourceStage.REGISTERED, pending.stage); assertFalse(pending.hasError)
        // 6. acessível sem pesquisa (protocolo certo, nenhum resultado)
        val empty = insp("$lanHost/vazio/api", k).test.also { row("vazio", it) }
        assertTrue(empty.ok); assertEquals(SourceStage.REACHABLE, empty.stage); assertEquals(ErrorKind.NO_RESULTS.name, empty.kind)
        // 7. pesquisa funcional sem vídeo reproduzível
        val noVideo = insp("$lanHost/semvideo/api", k).test.also { row("semvideo", it) }
        assertEquals(SourceStage.SEARCH_OK, noVideo.stage); assertEquals(ErrorKind.UNAVAILABLE.name, noVideo.kind)
        // pesquisa funcional com resultado reproduzível
        val search = insp("$lanHost/api", k).test.also { row("pesquisa", it) }
        assertEquals(SourceStage.SEARCH_OK, search.stage); assertTrue(search.items > 0); assertTrue(search.latencyMs > 0)
        // 10. autenticação inválida
        val auth = insp("$lanHost/api", "chave-errada").test.also { row("chave errada", it) }
        assertEquals(ErrorKind.AUTH.name, auth.kind); assertEquals(SourceStage.REACHABLE, auth.stage)
        // 9. DNS e TLS
        val dns = insp("https://brudre-nao-existe.invalid/catalogo.json").test.also { row("dns", it) }
        assertEquals(ErrorKind.DNS.name, dns.kind); assertEquals(SourceStage.REGISTERED, dns.stage)
        val tls = insp("https://expired.badssl.com/").test.also { row("tls", it) }
        assertEquals(ErrorKind.TLS.name, tls.kind)
        // bloqueada pela segurança
        val blocked = insp("https://192.168.0.9/lista.json").test.also { row("bloqueada", it) }
        assertEquals(ErrorKind.BLOCKED.name, blocked.kind)
        // 8. reprodução validada só por reprodução real
        val catUrl = "$raw/feeds/blender-open-movies.json"
        val cat = UserSource(UserSource.idFor(SourceType.CATALOG_JSON, catUrl), "Blender", catUrl, SourceType.CATALOG_JSON, lastTest = insp(catUrl).test)
        services.sourceStore.put(cat)
        assertEquals(SourceStage.SEARCH_OK, services.sourceStore.get(cat.id)!!.stage)
        services.sourceStore.recordPlayback(cat.id, 900)
        assertEquals(SourceStage.PLAYBACK_OK, services.sourceStore.get(cat.id)!!.stage)
        assertTrue(services.sourceStore.testLog().any { it.sourceId == cat.id && it.test.stage == SourceStage.PLAYBACK_OK })
        survey("Diagnóstico por etapas", "PASS", rows.joinToString("; "))
    }

    /** 13, 14: trackers HTTPS e UDP, DHT e falhas de certificado e DNS de tracker, com o mecanismo que achou participantes. */
    @Test fun t53_trackersHttpsUdpDhtAndFailures() {
        fun bytes(u: String) = runCatching { services.guardedHttp.newCall(Request.Builder().url(u).header("User-Agent", "Mozilla/5.0").build()).execute().use { if (it.isSuccessful) it.body?.bytes() else null } }.getOrNull()
        val synthetic = bytes("$raw/test-torrent/brudre-teste-serie.torrent")!!
        // A) determinístico: tracker HTTPS local (certificado fixado), certificado vencido, host errado, DNS inexistente, tracker UDP público
        val k = key
        val local = if (k != null) "$lanHost/announce" else null
        if (local != null) LocalServers.register(local, LocalServers.fetchCertificate(local).pin)
        val extra = listOfNotNull(local, "https://expired.badssl.com/announce", "https://wrong.host.badssl.com/announce", "https://brudre-nao-existe.invalid/announce", "udp://tracker.opentrackr.org:1337/announce")
        val b = runBlocking { services.torrent.probePeers(listOf(synthetic), 30, extraTrackers = extra) }.single()
        TestUtil.log("Trackers (sintético): ${b.mechanisms}; respostas=${b.trackerReplies}; erros=${b.trackerErrors}; alertas do motor=${b.totalAlerts}; HTTPS entregues=${b.httpsConnected}")
        if (local != null) {
            assertTrue("tracker HTTPS local respondeu: ${b.trackerReplies}", b.trackerReplies.any { it.startsWith("$lanHost/announce: 2") })
            survey("Tracker HTTPS (servidor local, certificado fixado)", "PASS", "anúncio pelo OkHttp: 2 participantes recebidos, ${b.httpsConnected} entregues ao motor")
        } else survey("Tracker HTTPS (servidor local)", "BLOCKED", "servidor de referência ausente")
        val cert = b.trackerErrors.filter { it.contains("badssl") }
        assertTrue("certificado inválido recusado: $cert", cert.size >= 2 && cert.all { it.contains("Certificado", true) })
        assertFalse("tracker com certificado inválido aceito", b.trackerReplies.any { it.contains("badssl") })
        survey("Tracker com certificado inválido", "PASS", "vencido e host errado recusados: ${cert.joinToString(" | ").take(220)}")
        val dns = b.trackerErrors.filter { it.contains("brudre-nao-existe") }
        assertTrue("falha de DNS identificada: $dns", dns.any { it.contains("DNS") })
        survey("Tracker com falha de DNS", "PASS", dns.joinToString(" | ").take(200))
        val udp = b.trackerReplies.any { it.startsWith("udp://") } || b.viaUdp > 0
        survey("Tracker UDP", if (udp) "PASS" else "PARTIAL", if (udp) "respostas: ${b.trackerReplies.filter { it.startsWith("udp") }}" else "sem alerta de resposta UDP do motor (alertas recebidos: ${b.totalAlerts})")
        // B) rede pública: Ubuntu (tracker HTTPS oficial) só com os trackers dele + DHT
        val ubuntuLink = bytes("https://releases.ubuntu.com/noble/")?.let { String(it) }?.let { Regex("href=\"([^\"]*live-server-amd64\\.iso\\.torrent)\"").find(it)?.groupValues?.get(1) }
            ?.let { java.net.URI("https://releases.ubuntu.com/noble/").resolve(it).toString() }
        val ubuntu = ubuntuLink?.let { bytes(it) }
        val a = ubuntu?.let { runBlocking { services.torrent.probePeers(listOf(it), 45, extraTrackers = emptyList()) }.single() }
        a?.let { TestUtil.log("Ubuntu: ${it.mechanisms}; respostas=${it.trackerReplies}; erros=${it.trackerErrors}; conhecidos=${it.known}; enxame=${it.swarm}") }
        val pubHttps = a?.trackerReplies?.any { it.startsWith("https://") } == true
        survey("Tracker HTTPS público (Ubuntu)", if (pubHttps) "PASS" else if (a == null) "BLOCKED" else "PARTIAL",
            a?.let { "via ${it.mechanisms}; respostas ${it.trackerReplies}; erros ${it.trackerErrors.take(2)}" } ?: "listagem do Ubuntu indisponível")
        val dht = (a?.viaDht ?: 0) > 0 || b.viaDht > 0
        survey("DHT", if (dht) "PASS" else "PARTIAL", if (dht) "participantes via DHT: Ubuntu=${a?.viaDht} sintético=${b.viaDht}" else "sem alerta de DHT do motor; participantes conhecidos (todas as origens): Ubuntu=${a?.known}")
        services.torrent.shutdownIfIdle()
    }

    /** 16: memória — testar e pesquisar várias fontes, uma por vez, não pode inchar o processo. */
    @Test fun t54_memoryDuringSourceTests() {
        val urls = listOf("$raw/feeds/blender-open-movies.json", "$raw/feeds/blender-open-movies.rss", "$raw/search/{slug}.json", "$raw/feeds/paginado-1.json")
        urls.forEach { u ->
            val r = runBlocking { services.inspector.inspect(u) }
            r.type?.let { t -> services.sourceStore.put(UserSource(UserSource.idFor(t, u), u.substringAfterLast('/'), u, t, lastTest = r.test)) }
        }
        System.gc(); Thread.sleep(500)
        val before = com.brudre.streaming.data.Homologation.pssKb()
        repeat(3) {
            services.sourceStore.current.forEach { s -> runBlocking { services.inspector.inspect(s.url, forcedType = s.type) }.let { services.sourceStore.saveTest(s.id, it.test) } }
            runBlocking { services.searchRepo.page(SearchTemplate.Query("Big Buck Bunny"), 1) }
            services.searchRepo.clear()
        }
        System.gc(); Thread.sleep(500)
        val after = com.brudre.streaming.data.Homologation.pssKb()
        val deltaMb = (after - before) / 1024
        TestUtil.log("Memória (PSS do processo): antes ${before / 1024} MB, depois ${after / 1024} MB, variação $deltaMb MB após 3 rodadas de teste e pesquisa")
        assertTrue("memória cresceu $deltaMb MB", deltaMb < 40)
        survey("Memória durante testes de fontes", "PASS", "PSS ${before / 1024} → ${after / 1024} MB (${deltaMb} MB) após 3 rodadas com ${urls.size} fontes")
    }
}
