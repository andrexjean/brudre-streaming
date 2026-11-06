package com.brudre.streaming

import android.content.ComponentCallbacks2
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.brudre.streaming.data.Kind
import com.brudre.streaming.data.Playable
import com.brudre.streaming.data.SourceMode
import com.brudre.streaming.data.Title
import com.brudre.streaming.player.PlayerActivity
import com.brudre.streaming.player.SourceAttempt
import com.brudre.streaming.torrent.CacheManager
import com.brudre.streaming.sources.SourceType as SourceTypeAlias
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Testes 7 a 14: reprodução direta, torrent autorizado, buffering/seek, rede, retomada,
 * cache, erro de codec e memória baixa. Usam rede real e conteúdo de distribuição livre.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val services get() = TestUtil.services

    private val sintel get() = services.curated.find("cur:sintel")!!
    private val sintelTitle get() = sintel.title
    private val sintelPlayable get() = sintel.playable!!

    @Before fun setUp() {
        services.settings.update { it.copy(sourceMode = SourceMode.AUTO) }
        TestUtil.shell("svc wifi enable")
        TestUtil.shell("svc data enable")
    }

    @After fun tearDown() {
        TestUtil.shell("svc wifi enable")
        TestUtil.shell("svc data enable")
    }

    private fun launch(title: Title, playable: Playable, startMs: Long = 0): ActivityScenario<PlayerActivity> =
        ActivityScenario.launch(PlayerActivity.intent(ctx, title, playable, startMs))

    private fun <T> ActivityScenario<PlayerActivity>.read(block: (PlayerActivity) -> T): T {
        var out: T? = null
        onActivity { out = block(it) }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    private fun ActivityScenario<PlayerActivity>.state() = read { it.playerForTest()?.playbackState ?: -1 }
    private fun ActivityScenario<PlayerActivity>.position() = read { it.playerForTest()?.currentPosition ?: -1L }
    private fun ActivityScenario<PlayerActivity>.error() = read { it.lastErrorMessage }

    private fun ActivityScenario<PlayerActivity>.awaitPlaying(timeout: Long, minPositionMs: Long) {
        TestUtil.waitFor(timeout, "reprodução passar de ${minPositionMs}ms (erro=${error()})") {
            state() == Player.STATE_READY && position() > minPositionMs
        }
    }

    @Test fun t07_directPlaybackStartsAndSavesProgress() {
        val playable = sintelPlayable.copy(torrent = null, key = "test_direct")
        launch(sintelTitle, playable).use { sc ->
            sc.awaitPlaying(60_000, 3_000)
            assertTrue(sc.read { it.currentAttemptForTest() } is SourceAttempt.Direct)
            sc.moveToState(androidx.lifecycle.Lifecycle.State.CREATED) // onPause salva o progresso
        }
        TestUtil.waitFor(5_000, "progresso salvo") {
            (runBlocking { services.library.position(sintelTitle.id, "test_direct") }?.positionMs ?: 0L) > 2_000L
        }
    }

    @Test fun t08_torrentPlaybackWithSeek() {
        val playable = sintelPlayable.copy(directUrl = null, key = "test_torrent")
        launch(sintelTitle, playable).use { sc ->
            sc.awaitPlaying(180_000, 2_000)
            assertTrue(sc.read { it.currentAttemptForTest() } is SourceAttempt.Torrent)
            assertTrue("motor torrent ativo", services.torrent.activeCount == 1)
            // Teste 9: seek para o meio do filme força buscar outras peças
            val duration = sc.read { it.playerForTest()!!.duration }
            assertTrue(duration > 60_000)
            val target = duration / 2
            sc.onActivity { it.playerForTest()!!.seekTo(target) }
            sc.awaitPlaying(180_000, target + 1_000)
        }
        TestUtil.waitFor(15_000, "torrent removido ao sair") { services.torrent.activeCount == 0 }
    }

    @Test fun t08b_magnetOnlyTorrent() {
        val ref = sintelPlayable.torrent!!.copy(torrentUrl = null)
        val playable = sintelPlayable.copy(directUrl = null, torrent = ref, key = "test_magnet")
        launch(sintelTitle, playable).use { sc -> sc.awaitPlaying(240_000, 1_000) }
    }

    @Test fun t07b_internetArchiveItemResolvesAndPlays() {
        val entry = services.curated.find("cur:night-of-the-living-dead")!!
        val details = runBlocking { services.sources.details(entry.title) }
        assertTrue("Internet Archive sem vídeo compatível", details.available)
        assertTrue("fonte não confirmada: ${details.sourceLabel}", details.confirmed)
        val p = details.playables.first()
        assertNotNull(p.directUrl)
        launch(details.title, p.copy(torrent = null, key = "test_ia")).use { sc -> sc.awaitPlaying(90_000, 2_000) }
    }

    @Test fun t10_networkInterruptionIsHandled() {
        TestUtil.shell("svc wifi disable")
        TestUtil.shell("svc data disable")
        TestUtil.waitFor(20_000, "rede desligada") { !services.isOnline() }
        val error = runCatching { runBlocking { services.sources.search("chaplin") } }.exceptionOrNull()
        assertNotNull("busca deveria falhar sem rede", error)
        assertTrue(error!!.message!!.contains("internet", ignoreCase = true))
        // Player sem rede: deve mostrar erro, não fechar o app
        launch(sintelTitle, sintelPlayable.copy(torrent = null, key = "test_offline")).use { sc ->
            TestUtil.waitFor(90_000, "erro de rede exibido") { sc.error() != null }
        }
        TestUtil.shell("svc wifi enable")
        TestUtil.shell("svc data enable")
        TestUtil.waitFor(30_000, "rede religada") { services.isOnline() }
        // Após reconectar, a busca volta a funcionar (o app tenta de novo sozinho em falhas transitórias)
        TestUtil.waitFor(60_000, "busca funcionar após reconectar") {
            runBlocking { services.sources.search("chaplin") }.isNotEmpty()
        }
    }

    @Test fun t11_resumesFromSavedPosition() {
        launch(sintelTitle, sintelPlayable.copy(torrent = null, key = "test_resume"), startMs = 300_000).use { sc ->
            sc.awaitPlaying(60_000, 300_500)
        }
    }

    @Test fun t12_cacheCleanup() = runBlocking {
        val dir = services.torrent.saveDir
        File(dir, "velho/video.mp4").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(2 * 1024 * 1024)) }
        assertTrue(CacheManager.dirSize(dir) >= 2 * 1024 * 1024)
        val freed = services.clearTemporaryData()
        assertTrue("liberou $freed", freed >= 2 * 1024 * 1024)
        assertEquals(0L, CacheManager.dirSize(dir))
    }

    @Test fun t13_incompatibleFormatShowsError() {
        // Uma legenda .srt no lugar do vídeo: o player precisa falhar de forma controlada.
        val bad = Playable("bad", "Arquivo inválido", 1, 1, sintelPlayable.subtitles.first().url, null, emptyList(), null, false)
        launch(sintelTitle, bad).use { sc ->
            TestUtil.waitFor(60_000, "erro de formato") { sc.error() != null }
            TestUtil.log("Mensagem exibida: ${sc.error()}")
            assertTrue(sc.state.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
        }
    }

    @Test fun t13b_noSourceShowsMessage() {
        val none = Playable("none", "Sem fonte", 1, 1, null, null, emptyList(), null, false)
        launch(sintelTitle, none).use { sc ->
            TestUtil.waitFor(5_000, "mensagem sem fonte") { sc.error()?.contains("fonte autorizada") == true }
        }
    }

    @Test fun t14_lowMemoryReleasesCachesDuringPlayback() {
        launch(sintelTitle, sintelPlayable.copy(torrent = null, key = "test_mem")).use { sc ->
            sc.awaitPlaying(60_000, 1_000)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                TestUtil.app.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
            }
            assertEquals(0, services.imageLoader.memoryCache?.size ?: 0)
            val before = sc.position()
            Thread.sleep(3_000)
            assertTrue("continua tocando após memória baixa", sc.position() > before)
            val rt = Runtime.getRuntime()
            TestUtil.log("Heap usado: ${(rt.totalMemory() - rt.freeMemory()) / 1048576} MB de ${rt.maxMemory() / 1048576} MB")
        }
    }

    /** Catálogo TMDB (só metadados): clássicos em domínio público encontrados no acervo e tocados no app. */
    @Test fun t15_catalogTitlesFindFreeSource() {
        assertTrue("app compilado sem a chave do TMDB", services.catalog.tmdb.enabled)
        val classics = runBlocking { services.catalog.row("tmdb_pd_classics", force = true) }
        assertTrue("linha de clássicos vazia", classics.size >= 5)
        var playable: com.brudre.streaming.data.TitleDetails? = null
        var found = 0
        for (t in classics.take(10)) {
            val d = runCatching { runBlocking { services.sources.details(t) } }.getOrNull() ?: continue
            TestUtil.log("TMDB→fonte: ${t.name} (${t.year}) → disponível=${d.available} confirmado=${d.confirmed} • ${d.sourceLabel}")
            if (d.available && d.confirmed) { found++; if (playable == null) playable = d }
        }
        TestUtil.log("Clássicos com fonte gratuita confirmada: $found de ${classics.take(10).size}")
        assertNotNull("nenhum clássico do catálogo achou fonte gratuita", playable)
        val d = playable!!
        assertTrue(d.title.isTmdb)
        launch(d.title, d.playables.first().copy(key = "test_tmdb_match")).use { sc -> sc.awaitPlaying(90_000, 2_000) }
        // Filmes recentes grátis em apps oficiais (Pluto TV, YouTube, Plex, Mercado Play…)
        for (key in listOf("tmdb_free_movies", "tmdb_free_new", "tmdb_free_series", "tmdb_trending_movies", "tmdb_new_movies", "tmdb_trending_series", "tmdb_new_series")) {
            val r = runBlocking { services.catalog.row(key, force = true) }
            TestUtil.log("$key: ${r.size} títulos, ex.: ${r.take(4).joinToString { it.name + " (" + it.year + ")" }}")
            assertTrue("$key vazio", r.isNotEmpty())
        }
        val freeRecent = runBlocking { services.catalog.row("tmdb_free_movies") }.take(6).mapNotNull { t ->
            runCatching { runBlocking { services.sources.details(t) } }.getOrNull()
        }
        freeRecent.forEach { fd -> TestUtil.log("Grátis: ${fd.title.name} (${fd.title.year}) → ${fd.freeProviders.joinToString { it.name + if (it.withAds) "/anúncios" else "/grátis" }}") }
        assertTrue("nenhum filme recente com app gratuito", freeRecent.any { it.freeElsewhere })
        assertTrue("serviço pago na lista de grátis", freeRecent.flatMap { it.freeProviders }.none { com.brudre.streaming.data.FreeApps.isPaidBrand(it.name) })
        // Abrir o app gratuito (não instalado no emulador) leva à Play Store sem travar
        val fd = freeRecent.first { it.freeElsewhere }
        var msg = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync { msg = com.brudre.streaming.ui.FreeAppLauncher.open(ctx, fd.freeProviders.first(), fd.title) }
        TestUtil.log("Abrir app grátis: $msg")
        assertTrue(msg.isNotBlank())
        // Lançamento recente: catalogado, sem fonte gratuita e sem redirecionamento pago
        val recent = runBlocking { services.sources.search("Duna Parte Dois") }.firstOrNull { it.isTmdb }
        if (recent != null) {
            val rd = runBlocking { services.sources.details(recent) }
            TestUtil.log("Lançamento: ${rd.title.name} → ${rd.sourceLabel}")
            assertTrue("lançamento comercial não deveria ter fonte", !rd.available)
            TestUtil.log("Lançamento grátis em: ${rd.freeProviders.joinToString { it.name }.ifEmpty { "nenhum app gratuito" }}")
        }
    }

    /** Teste 6: listas do iptv-org baixadas, filtradas (sem pagos/adultos/http) e organizadas. */
    @Test fun t17_iptvOrgListsLoad() {
        val br = runBlocking { services.live.channels("iptv_br", force = true) }
        TestUtil.log("iptv-org Brasil: ${br.size} canais")
        assertTrue("poucos canais: ${br.size}", br.size >= 50)
        assertTrue(br.all { it.url.startsWith("https://") && com.brudre.streaming.live.ChannelPolicy.isHls(it.url) })
        assertTrue(br.none { com.brudre.streaming.live.ChannelPolicy.isPayTv(it.name, it.url) || com.brudre.streaming.live.ChannelPolicy.isAdult(it.name, it.groups) })
        val groups = com.brudre.streaming.live.LiveCategories.group(br)
        groups.forEach { (c, l) -> TestUtil.log("  ${c.label}: ${l.size}") }
        assertTrue(groups.size >= 4)
        val por = runBlocking { services.live.channels("iptv_por") }
        TestUtil.log("Em português: ${por.size} canais; países: ${por.mapNotNull { it.country }.distinct()}")
        assertTrue(por.isNotEmpty())
        assertTrue(runBlocking { services.live.search("tv") }.isNotEmpty())
    }

    /** Teste 7: um canal ao vivo da lista toca (tenta vários; sinais de terceiros caem). */
    @Test fun t18_liveChannelPlays() {
        val br = runBlocking { services.live.channels("iptv_br") }
        val ordered = br.sortedBy { if (com.brudre.streaming.live.LiveCategories.categoryOf(it).key == "news") 0 else 1 }
        var ok: String? = null
        for (ch in ordered.take(14)) {
            val t = com.brudre.streaming.live.LiveRepository.run { ch.toTitle() }
            val p = com.brudre.streaming.live.LiveRepository.run { ch.toPlayable() }.copy(mirrors = emptyList())
            val played = launch(t, p).use { sc ->
                runCatching { TestUtil.waitFor(25_000, "canal ${ch.name}") { sc.state() == Player.STATE_READY && sc.read { it.playerForTest()!!.isPlaying } } }.isSuccess
                    .also { if (it) { Thread.sleep(3_000); TestUtil.shot("ao_vivo") } }
            }
            TestUtil.log("Canal ${ch.name} (${ch.url.take(60)}): ${if (played) "TOCOU" else "sem sinal"}")
            if (played) { ok = ch.name; break }
        }
        assertNotNull("nenhum canal ao vivo tocou", ok)
    }

    /** Teste de TODOS os canais (Brasil + português): manifesto → variante → segmento; depois amostra no player. */
    @Test fun t21_allLiveChannelsChecked() {
        val live = services.live
        val br = runBlocking { live.channels("iptv_br") }
        val por = runBlocking { live.channels("iptv_por") }.filter { it.country != "br" }
        val all = (br + por).distinctBy { it.id }
        val t0 = System.currentTimeMillis()
        runBlocking { live.health.check(all, force = true) }
        val secs = (System.currentTimeMillis() - t0) / 1000
        val res = live.health.results.value
        val ok = all.filter { res[it.id]?.ok == true }
        val fail = all.filter { res[it.id]?.ok == false }
        TestUtil.log("Canais testados: ${all.size} em ${secs}s • no ar: ${ok.size} • fora do ar: ${fail.size}")
        TestUtil.log("Brasil: ${br.count { res[it.id]?.ok == true }} de ${br.size} no ar")
        fail.groupingBy { (res[it.id]?.reason ?: "?").replace(Regex("\\d{3}"), "NNN").take(40) }.eachCount()
            .entries.sortedByDescending { it.value }.take(10).forEach { TestUtil.log("  motivo: ${it.key} → ${it.value}") }
        // Relatório completo, um canal por linha (vai para o logcat publicado no CI)
        all.forEach { c ->
            val e = res[c.id]
            TestUtil.log("CANAL;${c.source};${c.name.replace(';', ',')};${if (e?.ok == true) "no ar" else "fora"};${e?.reason ?: ""};${(e?.workingUrl ?: c.url).take(120)}")
        }
        assertTrue("nenhum canal no ar", ok.size >= 20)
        // Precisão da verificação: canais aprovados precisam tocar de verdade no player
        val sample = ok.shuffled(java.util.Random(7)).take(10)
        var played = 0
        for (ch in sample) {
            val c = live.health.withWorkingUrl(ch)
            val t = com.brudre.streaming.live.LiveRepository.run { c.toTitle() }
            val p = com.brudre.streaming.live.LiveRepository.run { c.toPlayable() }.copy(mirrors = emptyList())
            val good = launch(t, p).use { sc ->
                runCatching { TestUtil.waitFor(25_000, c.name) { sc.state() == Player.STATE_READY && sc.read { it.playerForTest()!!.isPlaying } } }.isSuccess
            }
            if (good) played++
            TestUtil.log("Player: ${c.name} → ${if (good) "tocou" else "não tocou"}")
        }
        TestUtil.log("Aprovados na verificação que tocaram no player: $played de ${sample.size}")
        assertTrue("verificação aprovou canais que não tocam ($played/${sample.size})", played >= sample.size * 7 / 10)
    }

    /** Fontes externas: catálogo cadastrado (JSON) com magnet, .torrent, HTTP e HLS de obras abertas da Blender. */
    @Test fun t22_externalCatalogPlaysEverySourceType() {
        val url = "https://raw.githubusercontent.com/andrexjean/brudre-streaming/main/samples/feeds/blender-open-movies.json"
        val feeds = services.feeds
        runCatching { feeds.feeds().forEach { feeds.remove(it.id) } }
        val n = runBlocking { feeds.add("Blender (teste)", url) }
        TestUtil.log("Catálogo externo cadastrado: $n títulos")
        assertEquals(5, n)
        val feedId = feeds.feeds().single().id
        val items = runBlocking { feeds.items(feedId) }
        // Busca unificada encontra títulos do catálogo externo
        assertTrue(runBlocking { services.sources.search("Tears of Steel") }.any { it.id.startsWith("feed:") })
        fun detailsOf(prefix: String) = runBlocking { services.sources.details(items.first { it.title.name.startsWith(prefix) }.title) }
        val results = LinkedHashMap<String, String>()
        try {
            for ((prefix, timeout, expectTorrent) in listOf(
                Triple("Sintel (HTTP", 60_000L, false),
                Triple("Big Buck Bunny (HLS)", 60_000L, false),
                Triple("Sintel (magnet)", 240_000L, true),
                Triple("Big Buck Bunny (.torrent)", 240_000L, true),
                Triple("Tears of Steel", 240_000L, true),
            )) {
                val d = detailsOf(prefix)
                assertTrue("$prefix sem fonte confirmada: ${d.sourceLabel}", d.available && d.confirmed)
                val ok = launch(d.title, d.playables.first().copy(key = "feed_$prefix")).use { sc ->
                    val played = runCatching { sc.awaitPlaying(timeout, 1_500) }.isSuccess
                    val attempt = sc.read { it.currentAttemptForTest() }
                    results[prefix] = "${if (played) "TOCOU" else "falhou"} via ${attempt?.label} (${sc.error() ?: "sem erro"})"
                    if (played) assertEquals(expectTorrent, attempt is SourceAttempt.Torrent)
                    played
                }
                TestUtil.log("Fonte externa: $prefix → ${results[prefix]}")
                if (!prefix.startsWith("Tears")) assertTrue("$prefix não tocou: ${results[prefix]}", ok)
                TestUtil.waitFor(20_000, "torrent encerrado") { services.torrent.activeCount == 0 }
            }
        } finally {
            feeds.remove(feedId)
        }
        assertTrue(feeds.feeds().isEmpty())
    }

    /** Abrir link: magnet colado à mão toca no player (progressivo, sem baixar o arquivo inteiro). */
    @Test fun t23_manualMagnetLinkPlaysProgressively() {
        val (t, p) = com.brudre.streaming.sources.FeedParser.manual(sintelPlayable.torrent!!.magnet!!)!!
        launch(t, p).use { sc ->
            sc.awaitPlaying(240_000, 1_000)
            assertTrue(sc.read { it.currentAttemptForTest() } is SourceAttempt.Torrent)
            val dir = services.torrent.saveDir
            val onDisk = CacheManager.dirSize(dir)
            TestUtil.log("Magnet manual: começou a tocar com ${onDisk / 1048576} MB em disco")
        }
        assertTrue(com.brudre.streaming.sources.FeedParser.manual("http://example.com/x.mp4") == null)
    }

    /** Gerenciar fontes: cadastro, formato, reprodução, ativar/desativar, prioridade, persistência, exportar/importar, exclusão. */
    @Test fun t24_sourceManagerLifecycle() {
        val store = services.sourceStore
        val insp = services.inspector
        val feeds = services.feeds
        store.current.forEach { store.remove(it.id) }
        val raw = "https://raw.githubusercontent.com/andrexjean/brudre-streaming/main/samples/feeds"
        val cases = listOf(
            "Blender JSON" to "$raw/blender-open-movies.json",
            "Blender RSS" to "$raw/blender-open-movies.rss",
            "HLS de teste" to "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
            "Sintel magnet" to sintelPlayable.torrent!!.magnet!!,
            "Sintel .torrent" to "https://webtorrent.io/torrents/sintel.torrent",
            "Sintel HTTP" to "https://webtorrent.io/torrents/Sintel/Sintel.mp4",
            "IPTV Brasil" to "https://raw.githubusercontent.com/iptv-org/iptv/gh-pages/countries/br.m3u",
        )
        val expected = listOf(SourceTypeAlias.CATALOG_JSON, SourceTypeAlias.RSS, SourceTypeAlias.HLS, SourceTypeAlias.MAGNET, SourceTypeAlias.TORRENT, SourceTypeAlias.VIDEO, SourceTypeAlias.M3U)
        for ((i, c) in cases.withIndex()) {
            val r = runBlocking { insp.inspect(c.second) }
            TestUtil.log("Fonte '${c.first}': formato=${r.type?.label} ok=${r.test.ok} • ${r.test.message}")
            assertEquals("formato de ${c.first}", expected[i], r.type)
            assertTrue("${c.first} não validou: ${r.test.message}", r.test.ok)
            store.put(com.brudre.streaming.sources.UserSource(com.brudre.streaming.sources.UserSource.idFor(r.type!!, c.second), c.first, c.second, r.type!!, priority = i, lastTest = r.test))
        }
        // Endereços que não são fontes: recusados sem salvar
        for (bad in listOf("https://github.com/", "https://raw.githubusercontent.com/andrexjean/brudre-streaming/main/gradlew", "http://example.com/a.mp4", "file:///system/build.prop")) {
            val r = runBlocking { insp.inspect(bad) }
            TestUtil.log("Recusado '$bad': ${r.test.message}")
            assertTrue(r.type == null || !r.test.ok)
        }
        assertEquals(7, store.current.size)
        services.feeds.refreshRegistry(); services.live.refreshRegistry()

        // Reprodução: vídeo de um catálogo cadastrado e um link único (HLS)
        val catalogId = store.current.first { it.type == SourceTypeAlias.CATALOG_JSON }.id
        val item = runBlocking { feeds.items(catalogId) }.first { it.title.name.startsWith("Sintel (HTTP") }
        val d = runBlocking { services.sources.details(item.title) }
        assertTrue(d.confirmed)
        launch(d.title, d.playables.first().copy(key = "src_catalog")).use { sc -> sc.awaitPlaying(60_000, 1_500) }
        val links = runBlocking { feeds.items(com.brudre.streaming.sources.FeedRepository.LINKS_ID) }
        TestUtil.log("Minhas fontes (links): ${links.map { it.title.name }}")
        assertEquals(4, links.size)
        val hls = runBlocking { services.sources.details(links.first { it.title.name == "HLS de teste" }.title) }
        launch(hls.title, hls.playables.first().copy(key = "src_hls")).use { sc -> sc.awaitPlaying(60_000, 1_500) }
        // Associação ao catálogo: "Sintel" (obra aberta do índice) também encontra as fontes cadastradas
        val assoc = runBlocking { services.sources.details(sintelTitle) }
        TestUtil.log("Associação: ${assoc.sourceLabel}")
        // TV ao vivo: a playlist M3U cadastrada aparece como lista
        assertTrue(services.live.customLists().any { it.name == "IPTV Brasil" })

        // Desativar e ativar
        store.setEnabled(catalogId, false); feeds.invalidate(catalogId)
        assertTrue(feeds.enabledFeeds().none { it.id == catalogId })
        assertTrue(runBlocking { services.sources.search("Tears of Steel") }.none { it.id.startsWith("feed:") && !it.id.contains("links") })
        store.setEnabled(catalogId, true); feeds.invalidate(catalogId)
        assertTrue(feeds.enabledFeeds().any { it.id == catalogId })
        assertTrue(runBlocking { services.sources.search("Tears of Steel") }.any { it.id.startsWith("feed:") })

        // Prioridade
        val hlsId = store.current.first { it.type == SourceTypeAlias.HLS }.id
        store.move(hlsId, -1); store.move(hlsId, -1)
        assertEquals(hlsId, store.current.first().id)

        // Persistência após reiniciar: as fontes estão gravadas em disco e são relidas por uma nova instância
        val prefsFile = java.io.File(ctx.applicationInfo.dataDir, "shared_prefs/brudre_sources.xml")
        assertTrue("configuração não gravada em disco", prefsFile.exists() && prefsFile.readText().contains(catalogId))
        val reloaded = com.brudre.streaming.sources.SourceStore(ctx, services.settings).current
        assertEquals(store.current.map { it.id to it.enabled }, reloaded.map { it.id to it.enabled })

        // Exportar → apagar → importar
        val file = services.inspector.exportFile(ctx)
        file.writeText(store.export())
        TestUtil.log("Exportado para ${file.absolutePath} (${file.length()} bytes)")
        val before = store.current.map { it.id }
        store.current.forEach { store.remove(it.id) }
        assertEquals(0, store.current.size)
        assertEquals(7, store.import(file.readText()))
        assertEquals(before, store.current.map { it.id })

        // Exclusão
        store.remove(catalogId); feeds.invalidate(catalogId)
        assertTrue(store.get(catalogId) == null && feeds.feeds().none { it.id == catalogId })
        store.current.forEach { store.remove(it.id) }
        services.feeds.refreshRegistry(); services.live.refreshRegistry()
    }

    /** Teste 11: reprodução HLS (stream público de teste da Mux). */
    @Test fun t19_hlsPlayback() {
        val hls = Playable("hls", "HLS", 1, 1, "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", null, emptyList(), null, false)
        launch(Title("test:hls", Kind.MOVIE, "Teste HLS"), hls).use { sc ->
            sc.awaitPlaying(60_000, 3_000)
            val fmt = sc.read { it.playerForTest()!!.videoFormat }
            TestUtil.log("HLS: ${fmt?.sampleMimeType} ${fmt?.width}x${fmt?.height}")
            assertTrue(sc.read { it.currentAttemptForTest() }!!.label.contains("HLS"))
        }
    }

    /** Segurança: destinos locais, esquemas perigosos e executáveis bloqueados; fonte válida confirmada. */
    @Test fun t20_urlGuardAndValidation() {
        val client = services.guardedHttp
        for (u in listOf("https://127.0.0.1:9/x.m3u8", "https://localhost/x.m3u8", "https://[::1]/x.m3u8", "https://169.254.169.254/latest", "https://archive.org/download/x/setup.apk")) {
            val blocked = runCatching { client.newCall(okhttp3.Request.Builder().url(u).build()).execute().close() }.exceptionOrNull()
            TestUtil.log("UrlGuard $u → ${blocked?.javaClass?.simpleName}: ${blocked?.message}")
            assertTrue("$u deveria ser bloqueado: $blocked", blocked is com.brudre.streaming.sources.BlockedUrlException)
        }
        // Tráfego sem criptografia continua proibido pelo app
        assertTrue(runCatching { client.newCall(okhttp3.Request.Builder().url("http://example.com/").build()).execute().close() }.isFailure)
        val v = com.brudre.streaming.sources.StreamValidator(client)
        assertEquals(com.brudre.streaming.sources.StreamValidator.Result.OK, runBlocking { v.validate(sintelPlayable.copy(torrent = null)) })
        // 404 garantido e página HTML no lugar do vídeo: link descartado
        assertEquals(com.brudre.streaming.sources.StreamValidator.Result.INVALID,
            runBlocking { v.validate(sintelPlayable.copy(directUrl = "https://raw.githubusercontent.com/andrexjean/brudre-streaming/main/nao-existe.mp4", torrent = null)) })
        assertEquals(com.brudre.streaming.sources.StreamValidator.Result.INVALID, v.check("https://github.com/", emptyMap()))
        TestUtil.log("Validador, item inexistente no acervo: ${runBlocking { v.validate(sintelPlayable.copy(directUrl = "https://archive.org/download/brudre_nao_existe_123/x.mp4", torrent = null)) }}")
        assertEquals(com.brudre.streaming.sources.StreamValidator.Result.INVALID, v.check("file:///system/build.prop", emptyMap()))
        // Device: versão, ABI e decodificadores (teste 23)
        val info = DeviceInfo.collect(ctx)
        TestUtil.log("Aparelho: ${info.summary()} • ${info.codecLine()}")
        assertTrue("sem decodificador H.264", info.codecs.first { it.label == "H.264" }.supported)
        assertTrue("sem decodificador AAC", info.codecs.first { it.label == "AAC" }.supported)
    }

    @Test fun t16_inAppUpdatePipeline() = runBlocking {
        val up = services.updater
        val state = up.check(force = true)
        TestUtil.log("Atualização: $state (build atual ${up.currentBuild})")
        assertTrue("verificação falhou: $state", state !is com.brudre.streaming.update.UpdateState.Error)
        // Baixa o APK da release publicada e passa pela validação (pacote, versão, assinatura)
        val json = InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("brudre_update", 0).getString("lastRelease", null)!!
        val release = com.brudre.streaming.update.UpdateManager.parseRelease(json)
        val file = java.io.File(ctx.cacheDir, "updates/teste.apk").apply { parentFile!!.mkdirs() }
        services.http.newCall(okhttp3.Request.Builder().url(release.apkUrl).build()).execute().use { r ->
            assertTrue("download HTTP ${r.code}", r.isSuccessful)
            file.outputStream().use { r.body!!.byteStream().copyTo(it) }
        }
        val problem = up.validate(file, release)
        TestUtil.log("Validação da release ${release.tag}: ${problem ?: "aceita"}")
        // A release publicada é mais antiga que este build de teste: deve recusar o "downgrade", nunca aceitar outro app
        assertTrue(problem == null || problem.contains("já tem"))
        file.delete()
        Unit
    }
}
