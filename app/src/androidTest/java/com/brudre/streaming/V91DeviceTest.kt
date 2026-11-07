package com.brudre.streaming

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.brudre.streaming.data.AppSettings
import com.brudre.streaming.data.Kind
import com.brudre.streaming.data.Playable
import com.brudre.streaming.data.Title
import com.brudre.streaming.data.TorrentCleanup
import com.brudre.streaming.data.TorrentRef
import com.brudre.streaming.data.VerifiedLanguages
import com.brudre.streaming.player.PlayerActivity
import com.brudre.streaming.player.SourceAttempt
import com.brudre.streaming.sources.ErrorKind
import com.brudre.streaming.sources.SearchTemplate
import com.brudre.streaming.sources.SourceType
import com.brudre.streaming.sources.UserSource
import com.brudre.streaming.torrent.Bencode
import com.brudre.streaming.torrent.TlsTrust
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * v9.1 em emulador com rede real. Fontes de teste do próprio projeto (samples/), badssl.com (certificados inválidos
 * mantidos para teste) e httpbingo.org (redirecionamentos). Desempenho medido aqui NÃO vale como prova no Mi Stick.
 */
@RunWith(AndroidJUnit4::class)
class V91DeviceTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val services get() = TestUtil.services
    private val raw = "https://raw.githubusercontent.com/andrexjean/brudre-streaming/main/samples"
    private val e2 = "$raw/test-torrent/BruDre.Teste.Serie/Season.01/BruDre.Teste.S01E02.mp4"

    @Before fun setUp() {
        services.settings.update { it.copy(userSourcesFirst = false, torrentCleanup = TorrentCleanup.ON_CLOSE, audioLanguage = "pt", subtitleLanguage = "pt", preferredResolution = 0, preferredSourceId = "") }
        services.sourceStore.current.forEach { services.sourceStore.remove(it.id) }
        services.feeds.refreshRegistry()
        services.searchRepo.clear()
    }

    @After fun tearDown() {
        services.sourceStore.current.forEach { services.sourceStore.remove(it.id) }
        services.settings.update { it.copy(audioLanguage = "pt", subtitleLanguage = "pt", preferredResolution = 0, preferredSourceId = "") }
    }

    private fun bytes(url: String) = services.guardedHttp.newCall(Request.Builder().url(url).build()).execute().use { it.body!!.bytes() }

    private fun <T> ActivityScenario<PlayerActivity>.read(block: (PlayerActivity) -> T): T {
        var out: T? = null
        onActivity { out = block(it) }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    private fun put(type: SourceType, url: String, name: String = type.label, config: String? = null) =
        UserSource(UserSource.idFor(type, url), name, url, type, priority = services.sourceStore.current.size, config = config).also { services.sourceStore.put(it) }

    // ------------------------------------------------------------------ 1, 2, 3: TLS

    /** Itens 1 e 2: CAs do Android no OpenSSL do libtorrent; certificado vencido, hostname errado e autoassinado recusados. */
    @Test fun v01_tlsCertificatesAreValidated() {
        com.brudre.streaming.torrent.CacheManager.clearDir(services.torrent.saveDir)
        assertTrue(TlsTrust.install(ctx))
        TestUtil.log("TLS: ${TlsTrust.status}")
        assertTrue(TlsTrust.status.startsWith("validação ativa") && TlsTrust.certificateCount > 50)
        // OkHttp (catálogos, pesquisa, player): validação padrão do Android
        for (bad in listOf("https://expired.badssl.com/", "https://wrong.host.badssl.com/", "https://self-signed.badssl.com/", "https://untrusted-root.badssl.com/")) {
            val r = runBlocking { services.inspector.inspect(bad + "catalogo.json") }
            TestUtil.log("OkHttp $bad → ${r.test.kind}: ${r.test.message}")
            assertEquals(bad, ErrorKind.TLS.name, r.test.kind)
        }
        // libtorrent: web seed com certificado inválido não entrega NENHUMA peça (validação ligada)
        val original = bytes("$raw/test-torrent/brudre-teste-serie.torrent")
        @Suppress("UNCHECKED_CAST")
        val root = (Bencode.decode(original).first as Map<String, Any>).toMutableMap()
        for (bad in listOf("https://expired.badssl.com/", "https://wrong.host.badssl.com/")) {
            root["url-list"] = listOf(bad.toByteArray())
            val f = File(services.torrent.metaDir, "tls-teste.torrent").apply { writeBytes(Bencode.encode(root)) }
            val meta = TorrentContent.parse(f.readBytes())
            val v = meta.videos.first()
            services.torrent.lastWebSeedError = null
            val stream = runBlocking { services.torrent.open(TorrentRef(null, null, v.file.path, v.file.index, f.absolutePath)) }
            try {
                Thread.sleep(20_000)
                TestUtil.log("libtorrent $bad → peça inicial=${stream.havePiece(stream.math.firstPiece)} erro=${services.torrent.lastWebSeedError}")
                assertFalse("peça recebida de servidor com certificado inválido ($bad)", stream.havePiece(stream.math.firstPiece))
                assertNotNull("erro de web seed registrado ($bad)", services.torrent.lastWebSeedError)
            } finally {
                services.torrent.close(stream)
                f.delete()
            }
        }
        TestUtil.waitFor(15_000, "streams fechados") { services.torrent.activeCount == 0 }
        services.torrent.shutdownIfIdle()
    }

    /** Item 3: redirecionamentos HTTPS validados a cada salto (e bloqueados para http:// ou destino local). */
    @Test fun v02_httpsRedirects() {
        val ok = runBlocking { services.inspector.inspect("https://github.com/andrexjean/brudre-streaming/raw/main/samples/feeds/blender-open-movies.json") }
        TestUtil.log("Redireciona github.com → raw: ${ok.type} ok=${ok.test.ok} ${ok.test.message}")
        assertEquals(SourceType.CATALOG_JSON, ok.type)
        assertTrue(ok.test.ok)
        val toHttp = runBlocking { services.inspector.inspect("https://httpbingo.org/redirect-to?url=http%3A%2F%2Fexample.com%2Fa.json") }
        TestUtil.log("Redireciona para http://: ${toHttp.test.kind} ${toHttp.test.message}")
        assertEquals(ErrorKind.BLOCKED.name, toHttp.test.kind)
        val toLocal = runBlocking { services.inspector.inspect("https://httpbingo.org/redirect-to?url=https%3A%2F%2F127.0.0.1%2Fa.json") }
        TestUtil.log("Redireciona para 127.0.0.1: ${toLocal.test.kind} ${toLocal.test.message}")
        assertEquals(ErrorKind.BLOCKED.name, toLocal.test.kind)
    }

    // ------------------------------------------------------------------ 5, 6, 7, 16: fontes

    /** Itens 5 e 16: Atualizar redetecta o formato; endereço duplicado é recusado. */
    @Test fun v03_redetectFormatAndDuplicates() {
        val rss = "$raw/feeds/blender-open-movies.rss"
        val wrong = put(SourceType.CATALOG_JSON, rss, "Salva com formato errado")
        com.brudre.streaming.ui.components.IntroState.shown = true
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            var vm: MainViewModel? = null
            sc.onActivity { vm = androidx.lifecycle.ViewModelProvider(it)[MainViewModel::class.java] }
            var msg: String? = null
            sc.onActivity { vm!!.refreshSource(wrong.id) { m -> msg = m } }
            TestUtil.waitFor(30_000, "redetecção") { msg != null }
            TestUtil.log("Redetecção: $msg")
            val now = services.sourceStore.current.single()
            assertEquals(SourceType.RSS, now.type)
            assertEquals("Salva com formato errado", now.name)
            // duplicada: mesmo endereço com variação (maiúsculas, barra final)
            var dup: Pair<Boolean, String>? = null
            sc.onActivity { vm!!.saveSource(null, "", rss.replace("raw.githubusercontent.com", "RAW.githubusercontent.com")) { ok, m, _ -> dup = ok to m } }
            TestUtil.waitFor(30_000, "resposta da duplicada") { dup != null }
            TestUtil.log("Duplicada: ${dup!!.second}")
            assertFalse(dup!!.first)
            assertTrue(dup!!.second.contains("duplicada", true))
            assertEquals(1, services.sourceStore.current.size)
        }
    }

    /** Itens 6 e 7: pesquisa em OpenSearch + REST + API BruDre paginada, com "carregar mais" sob demanda. */
    @Test fun v04_multiSourceSearchAndPagination() {
        for (u in listOf("$raw/opensearch/descricao.xml", "$raw/rest/descritor.json", "$raw/search/paged/{slug}-p{page}.json")) {
            val r = runBlocking { services.inspector.inspect(u) }
            TestUtil.log("Fonte $u → ${r.type} ok=${r.test.ok} ${r.test.message}")
            assertTrue(r.test.ok)
            put(r.type!!, u, r.suggestedName ?: r.type!!.label, r.config)
        }
        val types = services.sourceStore.current.map { it.type }
        assertEquals(listOf(SourceType.OPENSEARCH, SourceType.REST, SourceType.SEARCH), types)
        services.sourceStore.current.forEach { TestUtil.log("Capacidades ${it.name}: ${it.capabilities.summary}") }
        val q = SearchTemplate.Query("big buck bunny")
        val p1 = runBlocking { services.searchRepo.page(q, 1) }
        TestUtil.log("Página 1: ${p1.hits.map { "${it.source.type}:${it.item.title.name}" }} mais em=${p1.moreFrom}")
        assertEquals(3, p1.hits.map { it.source.id }.distinct().size)
        val paged = services.sourceStore.current.first { it.type == SourceType.SEARCH }.id
        assertEquals(setOf(paged), p1.moreFrom)
        val p2 = runBlocking { services.searchRepo.page(q, 2, p1.moreFrom) }
        TestUtil.log("Página 2: ${p2.hits.map { it.item.title.name }} mais em=${p2.moreFrom}")
        assertEquals(listOf("Big Buck Bunny (versão 2)"), p2.hits.map { it.item.title.name })
        assertTrue(p2.moreFrom.isEmpty())
        // a mesma página não é consultada de novo (cache): resposta imediata
        val t0 = System.currentTimeMillis(); runBlocking { services.searchRepo.page(q, 2, p1.moreFrom) }
        assertTrue(System.currentTimeMillis() - t0 < 200)
        // catálogo paginado
        val cat = put(SourceType.CATALOG_JSON, "$raw/feeds/paginado-1.json", "Paginado")
        services.feeds.refreshRegistry()
        assertEquals(2, runBlocking { services.feeds.items(cat.id, force = true) }.size)
        assertTrue(services.feeds.hasMore(cat.id))
        val all = runBlocking { services.feeds.loadMore(cat.id) }
        assertEquals(listOf("Teste Paginado Um", "Teste Paginado Dois", "Teste Paginado Três"), all.map { it.title.name })
        assertFalse(services.feeds.hasMore(cat.id))
    }

    // ------------------------------------------------------------------ 11: faixas

    /** Item 11: faixas reais (2 áudios + legenda embutida), seleção manual salva como preferência, idiomas confirmados. */
    @Test fun v05_audioAndSubtitleTracks() {
        VerifiedLanguages.clearForTest()
        val title = Title("test:faixas", Kind.MOVIE, "Teste de faixas")
        val p = Playable("faixas", "Faixas", 1, 1, "$raw/tracks/brudre-faixas.mkv", null, emptyList(), null, false)
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(ctx, title, p, 0)).use { sc ->
            TestUtil.waitFor(60_000, "faixas lidas") { sc.read { it.lastTrackLanguages } != null && sc.read { it.playerForTest()?.playbackState } == Player.STATE_READY }
            val (audio, text) = sc.read { it.lastTrackLanguages }!!
            TestUtil.log("Faixas: áudio=$audio legendas=$text")
            assertEquals(2, audio.size)
            assertTrue(text.any { VerifiedLanguages.normalize(it) == "pt" })
            // preferência pt: começa no áudio em português
            val selected = sc.read { a -> a.playerForTest()!!.currentTracks.groups.first { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }.let { g -> (0 until g.length).first { g.isTrackSelected(it) }.let { g.getTrackFormat(it).language } } }
            assertEquals("pt", VerifiedLanguages.normalize(selected))
            // escolha manual: inglês (como no menu do player) → vira preferência salva
            sc.onActivity { a ->
                val pl = a.playerForTest()!!
                val g = pl.currentTracks.groups.first { it.type == C.TRACK_TYPE_AUDIO && (0 until it.length).any { i -> VerifiedLanguages.normalize(it.getTrackFormat(i).language) == "en" } }
                val idx = (0 until g.length).first { VerifiedLanguages.normalize(g.getTrackFormat(it).language) == "en" }
                pl.trackSelectionParameters = pl.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, idx)).build()
            }
            TestUtil.waitFor(5_000, "preferência salva") { services.settings.current.audioLanguage == "en" }
            Thread.sleep(1_500); TestUtil.shot("player_faixas")
        }
        val v = VerifiedLanguages.get(title.id)!!
        assertEquals(listOf("pt", "en"), v.audio)
        assertTrue("pt" in v.subtitles)
        assertTrue(VerifiedLanguages.apply(title).ptAudio)
    }

    // ------------------------------------------------------------------ 13, 14: falhas

    /** Item 13: falha NO MEIO do vídeo → tenta a mesma fonte, depois a próxima, da mesma posição, sem ciclo. */
    @Test fun v06_fallbackDuringPlayback() {
        val title = Title("test:fallback-meio", Kind.MOVIE, "Teste de falha no meio")
        val truncated = "$raw/fallback/s01e02-truncado.mp4"
        val p = Playable("meio", "Meio", 1, 1, truncated, null, emptyList(), null, false, mirrors = listOf(e2))
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(ctx, title, p, 0)).use { sc ->
            TestUtil.waitFor(120_000, "trocou para a fonte completa e passou do ponto da falha") {
                (sc.read { it.currentAttemptForTest() } as? SourceAttempt.Direct)?.url == e2 &&
                    sc.read { it.playerForTest()?.playbackState } == Player.STATE_READY && sc.read { it.playerForTest()?.currentPosition ?: 0 } > 17_000
            }
            val switches = sc.read { it.switchesForTest() }
            TestUtil.log("Falha no meio: trocas=$switches posição=${sc.read { it.playerForTest()!!.currentPosition }} (sem voltar do início)")
            assertTrue("tentou a mesma fonte antes de trocar", switches >= 2)
            assertTrue(switches <= 4)
        }
        // todas falham: diálogo final, sem loop
        val dead = Playable("morto", "Morto", 1, 1, "$raw/fallback/nao-existe-1.mp4", null, emptyList(), null, false, mirrors = listOf("$raw/fallback/nao-existe-2.mp4"))
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(ctx, title, dead, 0)).use { sc ->
            TestUtil.waitFor(60_000, "erro final") { sc.read { it.lastErrorMessage }?.contains("Todas as 2 fontes") == true }
            val before = sc.read { it.switchesForTest() }
            Thread.sleep(8_000)
            assertEquals("não reinicia sozinho", before, sc.read { it.switchesForTest() })
        }
    }

    /** Item 14: torrent sem participantes → mensagem "Sem participantes". */
    @Test fun v07_noPeers() {
        val magnet = "magnet:?xt=urn:btih:" + "0123456789abcdef0123456789abcdef" + java.util.UUID.randomUUID().toString().replace("-", "").take(8) + "&dn=sem-peers"
        val t0 = System.currentTimeMillis()
        val f = try { runBlocking { services.torrentLibrary.meta(TorrentRef(null, magnet, null)) }; null } catch (e: Exception) { com.brudre.streaming.sources.ErrorClassifier.classify(e) }
        TestUtil.log("Sem peers em ${System.currentTimeMillis() - t0} ms: ${f?.summary}")
        assertEquals(ErrorKind.NO_PEERS, f?.kind)
        services.torrent.shutdownIfIdle()
    }

    // ------------------------------------------------------------------ 17, 19

    /** Item 17: preferências gravadas em disco e relidas por uma nova instância. */
    @Test fun v08_settingsPersistence() {
        services.settings.update { it.copy(audioLanguage = "en", subtitleLanguage = "off", preferredResolution = 720, preferredSourceId = "feed_x", revalidateHours = 12, torrentCleanup = TorrentCleanup.KEEP_LIMIT, userSourcesFirst = true) }
        Thread.sleep(500)
        val again = AppSettings(ctx).current
        assertEquals(listOf("en", "off", "720", "feed_x", "12", "KEEP_LIMIT", "true"),
            listOf(again.audioLanguage, again.subtitleLanguage, again.preferredResolution.toString(), again.preferredSourceId, again.revalidateHours.toString(), again.torrentCleanup.name, again.userSourcesFirst.toString()))
        services.settings.update { it.copy(revalidateHours = 24, torrentCleanup = TorrentCleanup.ON_CLOSE, userSourcesFirst = false) }
    }

    /** Item 19: memória com o player aberto (torrent) e depois de sair; sessão BitTorrent liberada. */
    @Test fun v09_memoryWithPlayerAndRelease() {
        fun pssMb(): Long { Runtime.getRuntime().gc(); Thread.sleep(300); return android.os.Debug.getPss() / 1024 }
        val base = pssMb()
        val entry = services.torrentLibrary.importBytes(bytes("$raw/test-torrent/brudre-teste-serie.torrent"))
        val v = runBlocking { services.torrentLibrary.meta(entry.ref) }.videos[1]
        var playing = 0L
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(ctx, com.brudre.streaming.torrent.TorrentLibrary.title(entry), com.brudre.streaming.torrent.TorrentLibrary.playable(entry, v), 0)).use { sc ->
            TestUtil.waitFor(180_000, "tocando") { sc.read { it.playerForTest()?.playbackState } == Player.STATE_READY && sc.read { it.playerForTest()?.currentPosition ?: 0 } > 3_000 }
            playing = pssMb()
            TestUtil.log("Status BitTorrent: ${sc.read { it.lastTorrentStatus }}")
            assertTrue(sc.read { it.lastTorrentStatus }?.contains("servidor(es) web") == true)
        }
        TestUtil.waitFor(15_000, "stream fechado") { services.torrent.activeCount == 0 }
        services.torrent.shutdownIfIdle()
        assertFalse("sessão BitTorrent encerrada", services.torrent.isRunning)
        val after = pssMb()
        TestUtil.log("Memória (PSS, emulador x86_64): início=${base} MB • player+torrent=${playing} MB • depois de sair=${after} MB")
        assertTrue("player + torrent abaixo de 350 MB no emulador", playing < 350)
        assertTrue("sem crescimento depois de sair", after <= playing + 20)
    }
}
