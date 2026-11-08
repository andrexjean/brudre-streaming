package com.brudre.streaming

import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.brudre.streaming.data.TorrentCleanup
import com.brudre.streaming.player.PlayerActivity
import com.brudre.streaming.player.SourceAttempt
import com.brudre.streaming.sources.ErrorKind
import com.brudre.streaming.sources.FeedItem
import com.brudre.streaming.sources.IndexerRegistry
import com.brudre.streaming.sources.LocalServers
import com.brudre.streaming.sources.SearchTemplate
import com.brudre.streaming.sources.SourceStore
import com.brudre.streaming.sources.SourceType
import com.brudre.streaming.torrent.TorrentContent
import com.brudre.streaming.ui.MainActivity
import com.brudre.streaming.ui.MainViewModel
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * v9.2 — Source Engine 4.0 no emulador, com rede real.
 *
 * Torznab: servidor de REFERÊNCIA do projeto (ci/torznab_server.py) com acervo autorizado, em dois cenários:
 *  - rede local: https://10.0.2.2:8443 (máquina do CI vista pelo emulador), certificado autoassinado fixado;
 *  - público: túnel HTTPS temporário (certificado válido), quando o CI conseguiu abri-lo.
 * Levantamento: fontes reais da biblioteca (Internet Archive, Wikimedia, distribuições). Cada linha "SURVEY|" do
 * log vira a coluna "Teste real" do relatório. Nada aqui consulta indexadores de terceiros.
 */
@RunWith(AndroidJUnit4::class)
class V92DeviceTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val services get() = TestUtil.services
    private val args get() = InstrumentationRegistry.getArguments()
    private val key: String? get() = args.getString("torznabKey")?.ifBlank { null }
    private val lan = "https://10.0.2.2:8443/api"

    @Before fun setUp() {
        services.settings.update { it.copy(userSourcesFirst = false, torrentCleanup = TorrentCleanup.ON_CLOSE, audioLanguage = "", subtitleLanguage = "off", preferredResolution = 0, preferredSourceId = "") }
        services.sourceStore.current.forEach { services.sourceStore.remove(it.id); services.credentials.put(it.id, null) }
        services.searchRepo.clear()
    }

    @After fun tearDown() {
        services.sourceStore.current.forEach { services.sourceStore.remove(it.id); services.credentials.put(it.id, null) }
        services.settings.update { it.copy(audioLanguage = "pt", subtitleLanguage = "pt") }
    }

    private fun survey(name: String, status: String, detail: String) = TestUtil.log("SURVEY|$name|$status|$detail")

    private fun <T> ActivityScenario<PlayerActivity>.read(block: (PlayerActivity) -> T): T {
        var out: T? = null
        onActivity { out = block(it) }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    private fun ActivityScenario<PlayerActivity>.awaitPlaying(timeout: Long, minPositionMs: Long) {
        TestUtil.waitFor(timeout, "reprodução passar de ${minPositionMs}ms (erro=${read { it.lastErrorMessage }})") {
            read { it.playerForTest()?.playbackState } == Player.STATE_READY && read { it.playerForTest()?.currentPosition ?: 0 } > minPositionMs
        }
    }

    /** Salva pelo mesmo caminho da tela (MainViewModel.saveSource). */
    private fun saveViaUi(form: MainViewModel.SourceForm): Triple<Boolean, String, String?> {
        com.brudre.streaming.ui.components.IntroState.shown = true
        var result: Triple<Boolean, String, String?>? = null
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a -> androidx.lifecycle.ViewModelProvider(a)[MainViewModel::class.java].saveSource(null, form) { ok, msg, d -> result = Triple(ok, msg, d) } }
            TestUtil.waitFor(90_000, "cadastro da fonte") { result != null }
        }
        return result!!
    }

    private fun play(item: FeedItem, minMs: Long = 2_000): Long {
        val p = item.playables().first()
        val t0 = System.currentTimeMillis()
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(ctx, item.title, p, 0)).use { sc ->
            sc.awaitPlaying(180_000, minMs)
            assertTrue(sc.read { it.currentAttemptForTest() } is SourceAttempt.Torrent)
            return System.currentTimeMillis() - t0
        }
    }

    /** Torznab em servidor LOCAL: bloqueado sem confirmação, certificado fixado, apikey cifrada, pesquisa, páginas, episódio e reprodução. */
    @Test fun t40_torznabLocalServerPinnedSearchPagesAndPlayback() {
        val k: String = key ?: run { survey("Torznab (servidor local)", "BLOCKED", "servidor de referência não iniciado no CI"); assumeTrue(false); return }
        // 1) Sem confirmação, a rede privada é bloqueada (anti-SSRF)
        val blocked = runBlocking { services.inspector.inspect(lan, key = k) }
        assertEquals(ErrorKind.BLOCKED.name, blocked.test.kind)
        // 2) Certificado lido (só handshake) — impressão digital confirmada
        val cert = LocalServers.fetchCertificate(lan)
        TestUtil.log("Servidor local: ${LocalServers.pretty(cert.pin)} ${cert.subject} autoassinado=${cert.selfSigned}")
        // 3) Fixação errada: recusa por TLS (nenhum dado trafega)
        LocalServers.register(lan, "00".repeat(32))
        val wrongPin = runBlocking { services.inspector.inspect(lan, key = k) }
        assertFalse(wrongPin.test.ok)
        TestUtil.log("Fixação errada: ${wrongPin.test.message}")
        LocalServers.sync(emptyMap())
        // 4) apikey errada: erro 100 do protocolo traduzido
        LocalServers.register(lan, cert.pin)
        val badKey = runBlocking { services.inspector.inspect(lan, key = "errada") }
        assertFalse(badKey.test.ok)
        assertTrue(badKey.test.message, badKey.test.message.contains("Credencial"))
        LocalServers.sync(emptyMap())
        // 5) Cadastro pela tela, com a apikey colada no endereço (sai do endereço e vai cifrada)
        val r = saveViaUi(MainViewModel.SourceForm("Torznab local", "$lan?apikey=$k", localPin = cert.pin))
        TestUtil.log("Cadastro: ${r.first} ${r.second}")
        assertTrue(r.second, r.first)
        val src = services.sourceStore.current.single { it.type == SourceType.TORZNAB }
        assertFalse("apikey fora do endereço salvo", src.url.contains("apikey"))
        assertEquals(cert.pin, src.localPin)
        assertEquals(k, services.credentials.get(src.id))
        assertTrue(services.sourceStore.export().let { !it.contains(k) && !it.contains(cert.pin) })
        // 6) Pesquisa de série por temporada, paginada (limite 3 do servidor): NZB e sem semeadores descartados
        val q = SearchTemplate.Query("BruDre Teste Serie", type = "tv", season = 1, category = "series")
        val p1 = runBlocking { services.searchRepo.page(q, 1) }
        TestUtil.log("Página 1: ${p1.hits.map { it.item.title.name + " " + it.item.episodes.map { e -> "${e.season}x${e.number}" } }} mais=${p1.moreFrom}")
        assertEquals(listOf(1 to 1, 1 to 2), p1.hits.map { it.item.episodes.single().let { e -> e.season to e.number } }.sortedBy { it.second })
        assertTrue(src.id in p1.moreFrom)
        val p2 = runBlocking { services.searchRepo.page(q, 2, p1.moreFrom) }
        assertTrue("página 2 só tinha item sem semeadores", p2.hits.isEmpty() && p2.moreFrom.isEmpty())
        // 7) Reprodução do episódio 2: .torrent baixado do servidor local (certificado fixado) → arquivo certo do pacote
        val ep2 = p1.hits.first { it.item.episodes.single().number == 2 }.item
        val bytes = services.guardedHttp.newCall(Request.Builder().url(ep2.episodes.single().sources.single().url).build()).execute().use { it.body!!.bytes() }
        val wanted = TorrentContent.parse(bytes).videos.first { it.episode?.season == 1 && it.episode?.episode == 2 }.file.index
        val p = ep2.playables().single()
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(ctx, ep2.title, p, 0)).use { sc ->
            sc.awaitPlaying(180_000, 2_000)
            assertEquals("episódio escolhido pelo indexador", wanted, sc.read { it.torrentStreamForTest()?.fileIndex })
        }
        // 8) Persistência: outra instância do armazenamento lê a fonte e a fixação
        val reloaded = SourceStore(ctx, services.settings).current.single { it.type == SourceType.TORZNAB }
        assertEquals(cert.pin, reloaded.localPin)
        survey("Torznab (servidor local)", "PASS", "caps, apikey cifrada, erro 100, fixação de certificado, tvsearch+season, 2 páginas, NZB/0 semeadores descartados, S01E02 reproduzido")
    }

    /** Torznab em endpoint PÚBLICO (HTTPS com certificado válido): filme por TMDB, reprodução e descarte de categorias. */
    @Test fun t41_torznabPublicEndpoint() {
        val k: String? = key
        val base: String? = args.getString("torznabPublic")?.ifBlank { null }
        if (k == null || base == null) { survey("Torznab (endpoint público)", "BLOCKED", "túnel HTTPS do CI indisponível nesta execução"); assumeTrue(false); return }
        val r = saveViaUi(MainViewModel.SourceForm("Torznab público", "$base/torznab/", key = k))
        TestUtil.log("Cadastro público: ${r.first} ${r.second}")
        assertTrue(r.second, r.first)
        val src = services.sourceStore.current.single { it.type == SourceType.TORZNAB }
        assertTrue("…/torznab/ → …/torznab/api descoberto", src.config!!.contains("/torznab/api"))
        val movie = runBlocking { services.searchRepo.page(SearchTemplate.Query("Night of the Living Dead", "movie:10331", "1968", "movie", category = "filmes"), 1) }.hits
        TestUtil.log("Filme: ${movie.map { it.item.title.name + " " + it.item.tmdbId + " " + it.item.sources.map { s -> s.url } }}")
        assertEquals("movie:10331", movie.single().item.tmdbId)
        val serie = runBlocking { services.searchRepo.page(SearchTemplate.Query("BruDre Teste Serie", type = "tv", season = 2, category = "series"), 1) }.hits
        assertTrue(serie.any { it.item.episodes.single().number == 1 })
        val ms = play(serie.first { it.item.episodes.single().sources.any { s -> s.url.startsWith(base) } }.item)
        survey("Torznab (endpoint público)", "PASS", "caps em …/api, movie por tmdbid, tvsearch season 2, reprodução em ${ms} ms")
        // Filme de domínio público via web seed do Internet Archive (pode depender da disponibilidade do Archive)
        val ia = runCatching { play(movie.single().item, 3_000) }
        survey("Internet Archive (.torrent via Torznab)", if (ia.isSuccess) "PASS" else "FAIL", ia.fold({ "reproduziu em $it ms" }, { it.message ?: it.toString() }))
    }

    /** Fallback inteligente: a fonte alternativa tem outra duração (120 s x 30 s) → pausa e pergunta, sem trocar em silêncio. */
    @Test fun t44_fallbackComparesDuration() {
        val raw = "https://raw.githubusercontent.com/andrexjean/brudre-streaming/main/samples"
        val title = com.brudre.streaming.data.Title("test:duracao", com.brudre.streaming.data.Kind.MOVIE, "Teste de duração")
        val p = com.brudre.streaming.data.Playable("dur", "Duração", 1, 1, "$raw/fallback/s01e02-truncado.mp4", null, emptyList(), null, false,
            mirrors = listOf("$raw/fallback/outra-versao-120s.mp4"))
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(ctx, title, p, 0)).use { sc ->
            TestUtil.waitFor(150_000, "aviso de duração diferente") { sc.read { it.durationMismatch } != null }
            TestUtil.log("Duração: ${sc.read { it.durationMismatch }}")
            Thread.sleep(1_500)
            assertFalse("pausado aguardando a decisão", sc.read { it.playerForTest()!!.isPlaying })
            TestUtil.shot("fallback_duracao")
        }
        survey("Fallback por duração", "PASS", "30 s → 120 s: pausou e perguntou (Continuar / Escolher outra fonte / Voltar)")
    }

    /** Rotina de homologação: mede a reprodução real e gera o relatório (no emulador ele se declara inválido como homologação). */
    @Test fun t45_homologationReport() {
        val H = com.brudre.streaming.data.Homologation
        H.reset()
        val raw = "https://raw.githubusercontent.com/andrexjean/brudre-streaming/main/samples"
        val title = com.brudre.streaming.data.Title("test:homolog", com.brudre.streaming.data.Kind.MOVIE, "Teste de homologação")
        val p = com.brudre.streaming.data.Playable("h", "H", 1, 1, "$raw/test-torrent/BruDre.Teste.Serie/Season.01/BruDre.Teste.S01E01.mp4", null, emptyList(), null, false)
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(ctx, title, p, 0)).use { sc -> sc.awaitPlaying(90_000, 3_000) }
        H.setDpad("catalogo", true)
        TestUtil.waitFor(15_000, "sessão gravada") { H.report().contains("HTTP; ") }
        val f = H.export()
        val text = f.readText()
        text.lines().take(16).forEach { TestUtil.log("HOMOLOG|$it") }
        assertTrue(text.contains("EMULADOR: não vale como homologação"))
        assertTrue(text.contains("[OK] Navegar pelo catálogo"))
        assertTrue(Regex("2\\. Tempo até o vídeo começar: todos mediana \\d+ ms").containsMatchIn(text))
        survey("Rotina de homologação", "PASS", "relatório gerado em ${f.name}: tempo até o vídeo, memória, travamentos, armazenamento e D-pad (emulador marcado como não homologação)")
    }

    /** Biblioteca: Internet Archive e Wikimedia adicionados pelos descritores, com pesquisa e reprodução reais. */
    @Test fun t42_libraryServicesSearchAndPlay() {
        val lib = IndexerRegistry.entries(ctx)
        assertTrue(lib.size >= 30)
        for (id in listOf("internet-archive", "wikimedia-commons", "blender")) {
            val e = lib.single { it.id == id }
            val res = runCatching {
                val addUrl: String = e.addUrl!!
                val r = runBlocking { services.inspector.inspect(addUrl) }
                if (!r.test.ok) error("cadastro: ${r.test.message}")
                val t = r.type!!
                services.sourceStore.put(com.brudre.streaming.sources.UserSource(com.brudre.streaming.sources.UserSource.idFor(t, addUrl), e.name, addUrl, t, lastTest = r.test, config = r.config))
                if (!t.isSearchable) return@runCatching "catálogo: ${r.test.message}"
                val q = when (id) { "internet-archive" -> "night of the living dead"; else -> "apollo 11" }
                val hits = runBlocking { services.searchRepo.page(SearchTemplate.Query(q), 1) }.hits.filter { it.source.url == addUrl }
                if (hits.isEmpty()) error("pesquisa sem resultados para \"$q\"")
                val item = hits.first().item
                val p = item.playables().first()
                val t0 = System.currentTimeMillis()
                ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(ctx, item.title, p, 0)).use { sc -> sc.awaitPlaying(180_000, 2_000) }
                "${hits.size} resultados; \"${item.title.name}\" reproduziu em ${System.currentTimeMillis() - t0} ms (${if (p.torrent != null) "BitTorrent" else "HTTP"})"
            }
            survey(e.name, if (res.isSuccess) "PASS" else "FAIL", res.fold({ it }, { it.message ?: it.toString() }))
            services.sourceStore.current.forEach { services.sourceStore.remove(it.id) }
            services.searchRepo.clear()
        }
    }

    /** Distribuições e repositórios abertos: listagem oficial HTTPS → .torrent → leitura → participantes na rede. */
    @Test fun t43_realTorrentSourcesSurvey() {
        val client = services.guardedHttp.newBuilder().callTimeout(25, TimeUnit.SECONDS).build()
        fun get(url: String): Pair<Int, ByteArray?> = runCatching {
            client.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (BruDre teste de rede)").build()).execute().use { r ->
                r.code to (if (r.isSuccessful) r.body?.source()?.let { s -> s.request(4L * 1024 * 1024); s.buffer.snapshot().toByteArray() } else null)
            }
        }.getOrElse { -1 to null }
        data class Src(val name: String, val page: String, val pattern: Regex)
        val iso = Regex("href=\"([^\"]+?\\.torrent)\"", RegexOption.IGNORE_CASE)
        val list = listOf(
            Src("Debian", "https://cdimage.debian.org/debian-cd/current/amd64/bt-cd/", Regex("href=\"([^\"]*netinst\\.iso\\.torrent)\"")),
            Src("Ubuntu", "https://releases.ubuntu.com/noble/", Regex("href=\"([^\"]*live-server-amd64\\.iso\\.torrent)\"")),
            Src("Fedora", "https://torrent.fedoraproject.org/", iso),
            Src("Linux Mint", "https://linuxmint.com/torrents/", iso),
            Src("Arch Linux", "https://archlinux.org/releng/releases/", Regex("href=\"(/releng/releases/[0-9.]+/torrent/)\"")),
            Src("openSUSE", "https://download.opensuse.org/tumbleweed/iso/", iso),
            Src("FreeBSD", "https://download.freebsd.org/releases/ISO-IMAGES/", iso),
            Src("NetBSD", "https://www.netbsd.org/mirrors/torrents/", iso),
            Src("Slackware", "https://www.slackware.com/getslack/torrents.php", iso),
            Src("Kali Linux", "https://cdimage.kali.org/current/", iso),
            Src("Manjaro", "https://manjaro.org/products/download/x86", iso),
            Src("EndeavourOS", "https://endeavouros.com/", iso),
            Src("AlmaLinux", "https://repo.almalinux.org/almalinux/9/isos/x86_64/", iso),
            Src("Rocky Linux", "https://download.rockylinux.org/pub/rocky/9/isos/x86_64/", iso),
            Src("FOSS Torrents", "https://fosstorrents.com/", iso),
            Src("Academic Torrents", "https://academictorrents.com/", iso),
            Src("eTree", "https://bt.etree.org/", iso),
            Src("Public Domain Torrents", "https://www.publicdomaintorrents.info/", iso),
        )
        val obtained = ArrayList<Pair<String, ByteArray>>()
        for (s in list) {
            val (code, page) = get(s.page)
            if (page == null) { survey(s.name, if (code == -1) "BLOCKED" else "FAIL", "listagem HTTPS: ${if (code == -1) "sem conexão/bloqueada pela segurança" else "HTTP $code"}"); continue }
            val html = String(page, Charsets.UTF_8)
            val link = s.pattern.findAll(html).map { it.groupValues[1].replace("&amp;", "&") }.firstOrNull()
                ?.let { java.net.URI(s.page).resolve(it).toString() }
            if (link == null) { survey(s.name, "PARTIAL", "listagem respondeu, nenhum .torrent HTTPS encontrado na página"); continue }
            val (tc, bytes) = get(link)
            val meta = bytes?.let { b -> runCatching { TorrentContent.parse(b) }.getOrNull() }
            if (meta == null) { survey(s.name, "FAIL", ".torrent ${if (bytes == null) "não baixou (HTTP $tc)" else "inválido"}: $link"); continue }
            TestUtil.log("Survey ${s.name}: ${meta.name} ${meta.files.size} arquivos hash=${meta.infoHash}")
            obtained += s.name to bytes
        }
        if (obtained.isEmpty()) return
        val probes = runBlocking { services.torrent.probePeers(obtained.map { it.second }, 90) }
        obtained.forEachIndexed { i, (name, _) ->
            val pr = probes[i]
            survey(name, if (pr.best > 0) "PASS" else "PARTIAL",
                ".torrent oficial válido (${pr.name}); participantes: conectados=${pr.connected}, conhecidos=${pr.known}, enxame(tracker)=${pr.swarm}")
        }
        services.torrent.shutdownIfIdle()
        assertNotNull(probes)
    }
}
