package com.brudre.streaming

import android.content.Intent
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.brudre.streaming.data.TorrentCleanup
import com.brudre.streaming.player.PlayerActivity
import com.brudre.streaming.player.SourceAttempt
import com.brudre.streaming.sources.ErrorKind
import com.brudre.streaming.sources.SearchTemplate
import com.brudre.streaming.sources.SourceType
import com.brudre.streaming.sources.UserSource
import com.brudre.streaming.torrent.CacheManager
import com.brudre.streaming.torrent.TorrentLibrary
import com.brudre.streaming.ui.MainActivity
import com.brudre.streaming.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 * v9.0 — Source Engine 2.0 em emulador com rede real.
 * Conteúdo de teste: série sintética do próprio projeto (samples/test-torrent), servida por web seed HTTPS
 * do GitHub (sem depender de peers), API de pesquisa estática (samples/search) e catálogos de troca de fonte.
 */
@RunWith(AndroidJUnit4::class)
class SourceEngineTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val services get() = TestUtil.services
    private val raw = "https://raw.githubusercontent.com/andrexjean/brudre-streaming/main/samples"
    private val testHash = "bf16e86e7636315da5c443296bee3dd58c051039"

    @Before fun setUp() {
        services.settings.update { it.copy(userSourcesFirst = false, torrentCleanup = TorrentCleanup.ON_CLOSE, disabledSources = it.disabledSources - "bittorrent") }
        services.sourceStore.current.forEach { services.sourceStore.remove(it.id) }
        services.torrentLibrary.current.forEach { services.torrentLibrary.remove(it.hash) }
        services.feeds.refreshRegistry()
        services.searchRepo.clear()
    }

    @After fun tearDown() {
        services.sourceStore.current.forEach { services.sourceStore.remove(it.id) }
        services.settings.update { it.copy(userSourcesFirst = false, torrentCleanup = TorrentCleanup.ON_CLOSE) }
    }

    private fun download(url: String): ByteArray =
        services.guardedHttp.newCall(Request.Builder().url(url).build()).execute().use { it.body!!.bytes() }

    private fun testTorrentBytes() = download("$raw/test-torrent/brudre-teste-serie.torrent")

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

    /** Itens 2, 3, 4, 5, 14: .torrent importado → lista de episódios → só o escolhido baixa → legenda do torrent → retomada. */
    @Test fun t25_importedTorrentEpisodeSelectionSubtitlesAndResume() {
        val entry = services.torrentLibrary.importBytes(testTorrentBytes())
        assertEquals(testHash, entry.hash)
        assertEquals(3, entry.videoCount)
        val meta = runBlocking { services.torrentLibrary.meta(entry.ref) }
        val videos = meta.videos
        TestUtil.log("Torrent: ${videos.map { it.label }}")
        assertEquals(listOf("1x1", "1x2", "2x1"), videos.map { "${it.episode!!.season}x${it.episode!!.episode}" })
        val title = TorrentLibrary.title(entry)

        // Episódio 1: legenda .srt do próprio torrent aparece como faixa de texto
        val ep1 = TorrentLibrary.playable(entry, videos[0])
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(ctx, title, ep1, 0)).use { sc ->
            sc.awaitPlaying(180_000, 1_500)
            assertTrue(sc.read { it.currentAttemptForTest() } is SourceAttempt.Torrent)
            assertEquals(videos[0].file.index, sc.read { it.torrentStreamForTest()?.fileIndex })
            assertTrue("legenda do torrent carregada", sc.read { it.lastSubtitleCount } >= 1)
            TestUtil.waitFor(30_000, "faixa de legenda") { sc.read { p -> p.playerForTest()!!.currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT } } }
            TestUtil.shot("torrent_episodio_legenda")
        }

        // Episódio 2 (escolhido manualmente): o arquivo certo, sem baixar os outros vídeos
        val ep2 = TorrentLibrary.playable(entry, videos[1])
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(ctx, title, ep2, 0)).use { sc ->
            sc.awaitPlaying(180_000, 3_000)
            assertEquals(videos[1].file.index, sc.read { it.torrentStreamForTest()?.fileIndex })
            val dir = services.torrent.saveDir
            val other = File(dir, videos[2].file.path)
            TestUtil.log("Outro episódio em disco: existe=${other.exists()} tamanho=${other.length()}")
            assertTrue("episódio não escolhido não deve ser baixado", !other.exists() || other.length() <= 2 * 65_536)
            // Retomada: avança para 15 s e sai (onPause salva o progresso do episódio)
            sc.onActivity { it.playerForTest()!!.seekTo(15_000) }
            TestUtil.waitFor(60_000, "chegar em 15 s") { sc.read { it.playerForTest()!!.currentPosition } >= 15_500 && sc.read { it.playerForTest()!!.playbackState } == Player.STATE_READY }
            sc.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        }
        TestUtil.waitFor(8_000, "progresso do episódio salvo") { (runBlocking { services.library.position(title.id, ep2.key) }?.positionMs ?: 0) > 14_000 }
        val resume = runBlocking { services.library.resumePosition(title.id, ep2.key) }
        assertTrue(resume > 14_000)
        // progresso individual: o episódio 1 tem o seu, separado
        assertTrue(runBlocking { services.library.position(title.id, ep1.key) }?.episodeKey == ep1.key)
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(ctx, title, ep2, resume)).use { sc ->
            sc.awaitPlaying(120_000, resume - 1_000)
            TestUtil.log("Retomou em ${sc.read { it.playerForTest()!!.currentPosition }} ms (salvo: $resume)")
        }
    }

    /** Item 16: limpeza do armazenamento temporário (apagar ao fechar x manter até o limite). */
    @Test fun t26_torrentCacheCleanupPolicy() {
        val entry = services.torrentLibrary.importBytes(testTorrentBytes())
        val v = runBlocking { services.torrentLibrary.meta(entry.ref) }.videos[2]
        val title = TorrentLibrary.title(entry)
        val dir = services.torrent.saveDir
        for (policy in listOf(TorrentCleanup.KEEP_LIMIT, TorrentCleanup.ON_CLOSE)) {
            services.settings.update { it.copy(torrentCleanup = policy) }
            ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(ctx, title, TorrentLibrary.playable(entry, v), 0)).use { sc -> sc.awaitPlaying(180_000, 1_000) }
            TestUtil.waitFor(15_000, "stream fechado") { services.torrent.activeCount == 0 }
            services.torrent.shutdownIfIdle()
            val size = CacheManager.dirSize(dir)
            TestUtil.log("Política $policy: ${size} bytes no cache após fechar")
            if (policy == TorrentCleanup.KEEP_LIMIT) assertTrue("mantém para rever", size > 0 && size <= services.settings.current.cacheLimitMb * 1048576L)
            else assertEquals("apaga ao fechar", 0L, size)
        }
    }

    /** Itens 1 e 2 via Android: magnet compartilhado (Compartilhar), magnet: (Abrir) e .torrent por content://. */
    @Test fun t27_sharedMagnetAndTorrentFromOtherApps() {
        val magnet = "magnet:?xt=urn:btih:$testHash&dn=BruDre.Teste.Serie&ws=" + java.net.URLEncoder.encode("$raw/test-torrent/", "UTF-8") +
            "&xs=" + java.net.URLEncoder.encode("$raw/test-torrent/brudre-teste-serie.torrent", "UTF-8")
        val share = Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Olha esse: $magnet").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        com.brudre.streaming.ui.components.IntroState.shown = true
        ActivityScenario.launch<MainActivity>(share).use { sc ->
            TestUtil.waitFor(10_000, "magnet compartilhado no histórico") { services.torrentLibrary.get(testHash)?.magnet != null }
            // Abre a lista de arquivos do torrent (metadados via DHT/peers ou cache local)
            var vm: MainViewModel? = null
            sc.onActivity { vm = androidx.lifecycle.ViewModelProvider(it)[MainViewModel::class.java] }
            TestUtil.waitFor(90_000, "lista de episódios") { vm!!.torrentPick.value?.meta?.videos?.size == 3 || vm!!.torrentPick.value?.error != null }
            val pick = vm!!.torrentPick.value
            TestUtil.log("Compartilhado: ${pick?.meta?.videos?.size} vídeos, erro=${pick?.error?.summary}")
            assertEquals(3, pick?.meta?.videos?.size)
            Thread.sleep(1_500); TestUtil.shot("torrent_arquivos")
            sc.onActivity { vm!!.closeTorrentPick() }
        }
        services.torrentLibrary.remove(testHash)
        // .torrent recebido por content:// (gerenciador de arquivos "Abrir com")
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val f = File(dir, "serie-teste.torrent").apply { writeBytes(testTorrentBytes()) }
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", f)
        val view = Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setDataAndType(uri, "application/x-bittorrent")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(view).use { sc ->
            TestUtil.waitFor(15_000, ".torrent importado") { services.torrentLibrary.get(testHash)?.localFile != null }
            var vm: MainViewModel? = null
            sc.onActivity { vm = androidx.lifecycle.ViewModelProvider(it)[MainViewModel::class.java] }
            TestUtil.waitFor(20_000, "lista de episódios do .torrent") { vm!!.torrentPick.value?.meta?.videos?.size == 3 }
            sc.onActivity { vm!!.closeTorrentPick() }
        }
        // Magnet inválido: recusado com mensagem clara
        val bad = runBlocking { services.inspector.inspect("magnet:?xt=urn:btih:123") }
        assertEquals(ErrorKind.MAGNET.name, bad.test.kind)
    }

    /** Itens 7, 8 e 9: API de pesquisa autorizada, associação ao título e várias fontes do mesmo título. */
    @Test fun t28_dynamicSearchAndMultipleSources() {
        val template = "$raw/search/{slug}.json"
        val r = runBlocking { services.inspector.inspect(template) }
        TestUtil.log("API de pesquisa: ${r.type} ok=${r.test.ok} ${r.test.message}")
        assertEquals(SourceType.SEARCH, r.type)
        assertTrue(r.test.ok)
        val searchSrc = UserSource(UserSource.idFor(SourceType.SEARCH, template), "Pesquisa de teste", template, SourceType.SEARCH, priority = 0, lastTest = r.test)
        services.sourceStore.put(searchSrc)
        val catUrl = "$raw/feeds/blender-open-movies.json"
        val catalog = UserSource(UserSource.idFor(SourceType.CATALOG_JSON, catUrl), "Blender", catUrl, SourceType.CATALOG_JSON, priority = 1)
        services.sourceStore.put(catalog)
        services.feeds.refreshRegistry()
        // pesquisa dinâmica (texto) devolve o resultado da API
        val hits = runBlocking { services.searchRepo.search(SearchTemplate.Query("Big Buck Bunny")) }
        assertEquals(1, hits.size)
        assertEquals("movie:10378", hits.first().item.tmdbId)
        assertTrue(runBlocking { services.sources.search("Big Buck Bunny") }.any { it.id.startsWith("feed:search") })

        val bbb = services.curated.find("cur:big-buck-bunny")!!.title
        val d = runBlocking { services.sources.details(bbb) }
        TestUtil.log("Fontes do título: ${d.options.map { "${it.name} [${it.formats}]" }} • ${d.sourceLabel}")
        assertTrue("nativa primeiro", d.options.first().native)
        assertTrue(d.options.any { it.sourceId == searchSrc.id })
        assertTrue(d.options.any { it.sourceId == catalog.id })
        assertTrue(d.confirmed)
        // ordem configurável: minhas fontes antes das nativas, na prioridade da lista
        // (sem preferência de idioma: a API informa áudio "en" e, com preferência "pt", ficaria depois das de idioma desconhecido)
        services.settings.update { it.copy(userSourcesFirst = true, audioLanguage = "") }
        val d2 = runBlocking { services.sources.details(bbb) }
        assertEquals(searchSrc.id, d2.options.first().sourceId)
        assertTrue(d2.options.last().native)
        assertEquals(d.options.size, d2.options.size)
        // com preferência "pt": a fonte que INFORMA inglês vai para depois das de idioma desconhecido
        services.settings.update { it.copy(audioLanguage = "pt") }
        val d3 = runBlocking { services.sources.details(bbb) }
        assertTrue(d3.options.indexOfFirst { it.sourceId == searchSrc.id } > d3.options.indexOfFirst { it.sourceId == catalog.id })
    }

    /** Itens 9 e 13: fonte com problema não impede as demais; o player troca de fonte e registra o histórico. */
    @Test fun t29_fallbackToNextSourceAndHistory() {
        val a = "$raw/feeds/fallback-a.json"
        val b = "$raw/feeds/fallback-b.json"
        val sa = UserSource(UserSource.idFor(SourceType.CATALOG_JSON, a), "Fonte quebrada", a, SourceType.CATALOG_JSON, priority = 0)
        val sb = UserSource(UserSource.idFor(SourceType.CATALOG_JSON, b), "Fonte boa", b, SourceType.CATALOG_JSON, priority = 1)
        services.sourceStore.put(sa); services.sourceStore.put(sb); services.feeds.refreshRegistry()
        services.settings.update { it.copy(userSourcesFirst = true) }
        val ed = services.curated.find("cur:elephants-dream")!!.title
        val d = runBlocking { services.sources.details(ed) }
        TestUtil.log("Elephants Dream: ${d.options.map { it.name }}")
        assertEquals(listOf(sa.id, sb.id), d.options.take(2).map { it.sourceId })
        assertTrue(d.confirmed)
        val p = d.playables.first()
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.intent(ctx, d.title, p.copy(key = "fallback"), 0)).use { sc ->
            sc.awaitPlaying(90_000, 1_500)
            val attempts = sc.read { it.attemptsForTest() }
            TestUtil.log("Tentativas: ${attempts.map { "${it.label}:${it.sourceId}" }} atual=${sc.read { it.currentAttemptForTest() }?.sourceId}")
            assertEquals(sa.id, attempts.first().sourceId)
            assertEquals(sb.id, sc.read { it.currentAttemptForTest() }?.sourceId)
        }
        TestUtil.waitFor(5_000, "histórico gravado") {
            (services.sourceStore.get(sa.id)?.stats?.fail ?: 0) >= 1 && (services.sourceStore.get(sb.id)?.stats?.ok ?: 0) >= 1
        }
        TestUtil.log("Histórico: A=${services.sourceStore.get(sa.id)?.stats?.summary} B=${services.sourceStore.get(sb.id)?.stats?.summary}")
    }

    /** Itens 10, 11, 12 e 13: erros de rede reais classificados; salvar sem validar e ativar depois do teste. */
    @Test fun t30_networkErrorsAndSaveWithoutValidation() {
        val insp = services.inspector
        val dns = runBlocking { insp.inspect("https://brudre-nao-existe.invalid/catalogo.json") }
        TestUtil.log("DNS: ${dns.test.message} | ${dns.test.detail}")
        assertEquals(ErrorKind.DNS.name, dns.test.kind)
        assertTrue(dns.test.detail!!.contains("UnknownHost"))
        val http = runBlocking { insp.inspect("$raw/nao-existe.json") }
        TestUtil.log("HTTP: ${http.test.message}")
        assertEquals(ErrorKind.HTTP.name, http.test.kind)
        val html = runBlocking { insp.inspect("https://github.com/") }
        assertEquals(ErrorKind.FORMAT.name, html.test.kind)
        val blocked = runBlocking { insp.inspect("http://example.com/a.json") }
        assertEquals(ErrorKind.BLOCKED.name, blocked.test.kind)
        // 9.2: rede privada sem confirmação de servidor local é bloqueada antes de qualquer conexão
        val lan = runBlocking { insp.inspect("https://10.255.255.1/catalogo.json") }
        assertEquals(ErrorKind.BLOCKED.name, lan.test.kind)
        // Endereço público sem rota (faixa de documentação TEST-NET-1): tempo esgotado ou servidor indisponível
        val t0 = System.currentTimeMillis()
        val down = runBlocking { insp.inspect("https://192.0.2.1/catalogo.json") }
        TestUtil.log("Inalcançável em ${System.currentTimeMillis() - t0} ms: ${down.test.message} | ${down.test.detail}")
        assertTrue(down.test.kind == ErrorKind.TIMEOUT.name || down.test.kind == ErrorKind.UNREACHABLE.name)

        // Salvar sem validar: fonte "fora do ar" fica desativada até um teste bem-sucedido
        com.brudre.streaming.ui.components.IntroState.shown = true
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            var vm: MainViewModel? = null
            sc.onActivity { vm = androidx.lifecycle.ViewModelProvider(it)[MainViewModel::class.java] }
            val url = "$raw/feeds/blender-open-movies.json"
            var result: Pair<Boolean, String>? = null
            sc.onActivity { vm!!.saveSourceUnvalidated(null, "Temporariamente offline", url) { ok, msg, _ -> result = ok to msg } }
            TestUtil.waitFor(5_000, "salvo sem validar") { result != null }
            assertTrue(result!!.second, result!!.first)
            val saved = services.sourceStore.current.single()
            assertTrue(saved.pending); assertFalse(saved.enabled)
            assertTrue(services.feeds.enabledFeeds().none { it.id == saved.id })
            // Teste bem-sucedido VALIDA (formato redetectado igual), mas não ativa sozinho (v9.1)
            sc.onActivity { vm!!.testSource(saved.id) }
            TestUtil.waitFor(30_000, "validada após teste") { services.sourceStore.get(saved.id)?.pending == false }
            val validated = services.sourceStore.get(saved.id)!!
            assertTrue(validated.validatedAt > 0)
            assertFalse("não ativa sozinha", validated.enabled)
            assertTrue(services.feeds.enabledFeeds().none { it.id == saved.id })
            // Ativar é ação do usuário
            sc.onActivity { vm!!.toggleSource(saved.id) }
            TestUtil.waitFor(5_000, "ativada pelo usuário") { services.sourceStore.get(saved.id)?.enabled == true }
            assertTrue(services.feeds.enabledFeeds().any { it.id == saved.id })
            // Fonte pendente (nunca validada) não pode ser ativada
            var refused: String? = null
            sc.onActivity { vm!!.saveSourceUnvalidated(null, "Outra", "https://brudre-nao-existe.invalid/c.json") { _, _, _ -> } }
            val pend = services.sourceStore.current.first { it.pending }
            sc.onActivity { vm!!.toggleSource(pend.id) { m -> refused = m } }
            TestUtil.waitFor(3_000, "recusa") { refused != null }
            assertFalse(services.sourceStore.get(pend.id)!!.enabled)
        }
    }

    /** Item 15: pesquisa cancelada ao sair da tela libera a conexão na hora. */
    @Test fun t31_searchCancellation() = runBlocking {
        val template = "https://10.255.255.1/busca?q={query}"
        services.sourceStore.put(UserSource(UserSource.idFor(SourceType.SEARCH, template), "Lenta", template, SourceType.SEARCH))
        val job = launch(Dispatchers.IO) { services.searchRepo.search(SearchTemplate.Query("qualquer coisa")) }
        delay(800)
        val t0 = System.currentTimeMillis()
        job.cancelAndJoin()
        val took = System.currentTimeMillis() - t0
        TestUtil.log("Cancelamento da pesquisa levou $took ms")
        assertTrue("cancelamento demorou $took ms", took < 2_000)
        // Uma fonte lenta não segura as outras: com tempo-limite, a pesquisa termina sozinha
        val t1 = System.currentTimeMillis()
        val hits = services.searchRepo.search(SearchTemplate.Query("outra"))
        assertTrue(hits.isEmpty())
        assertTrue(System.currentTimeMillis() - t1 < com.brudre.streaming.sources.SearchRepository.PER_SOURCE_MS + 6_000)
        assertNotNull(services.sourceStore.current.single().stats.lastError)
    }
}
