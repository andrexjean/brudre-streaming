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
        val details = runBlocking { services.catalog.details(entry.title) }
        assertTrue("Internet Archive sem vídeo compatível", details.available)
        val p = details.playables.first()
        assertNotNull(p.directUrl)
        launch(details.title, p.copy(torrent = null, key = "test_ia")).use { sc -> sc.awaitPlaying(90_000, 2_000) }
    }

    @Test fun t10_networkInterruptionIsHandled() {
        TestUtil.shell("svc wifi disable")
        TestUtil.shell("svc data disable")
        TestUtil.waitFor(20_000, "rede desligada") { !services.isOnline() }
        val error = runCatching { runBlocking { services.catalog.search("chaplin") } }.exceptionOrNull()
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
            runBlocking { services.catalog.search("chaplin") }.isNotEmpty()
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

    @Test fun t15_releaseCatalogWhereToWatch() {
        assertTrue("app compilado sem a chave do TMDB", services.catalog.tmdb.enabled)
        val trending = runBlocking { services.catalog.row("tmdb_trending", force = true) }
        assertTrue("Em Alta vazio", trending.size >= 5)
        for (key in listOf("tmdb_new_movies", "tmdb_series", "tmdb_kids_series", "tmdb_free")) {
            val r = runBlocking { services.catalog.row(key, force = true) }
            TestUtil.log("$key: ${r.size} títulos, ex.: ${r.take(3).joinToString { it.name }}")
            assertTrue("$key vazio", r.isNotEmpty())
        }
        // Títulos populares no streaming brasileiro precisam trazer "onde assistir"
        val popular = runBlocking { services.catalog.row("tmdb_movies", force = true) }
        val withProviders = popular.take(5).map { runBlocking { services.catalog.details(it) } }
        withProviders.forEach { d -> TestUtil.log("${d.title.name} [${d.title.rating}]: ${d.providers.joinToString { it.name + "/" + it.offer }}") }
        assertTrue("nenhum provedor encontrado", withProviders.any { it.providers.isNotEmpty() })
        // Abrir um serviço não instalado no emulador leva à Play Store sem travar
        val d = withProviders.first { it.providers.isNotEmpty() }
        var msg = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            msg = com.brudre.streaming.ui.StreamingLauncher.open(ctx, d.providers.first(), d.title)
        }
        TestUtil.log("Launcher: $msg")
        assertTrue(msg.isNotBlank())
        // Busca encontra lançamentos
        val found = runBlocking { services.catalog.search("Divertida Mente") }
        assertTrue("busca sem lançamentos", found.any { it.isTmdb })
    }
}
