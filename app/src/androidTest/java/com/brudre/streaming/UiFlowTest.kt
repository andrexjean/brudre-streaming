package com.brudre.streaming

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.brudre.streaming.ui.MainActivity
import com.brudre.streaming.ui.MainViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Testes 1 (inicialização), 2 (controle remoto), 3 (catálogo), 4 (pesquisa) e 5 (Minha Lista) pela interface. */
@RunWith(AndroidJUnit4::class)
class UiFlowTest {

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun tagPrefix(prefix: String) = SemanticsMatcher("testTag começa com $prefix") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    private fun diagnose(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            TestUtil.log("FALHA esperando $what — árvore de semântica:")
            runCatching { rule.onAllNodes(isRoot()).fetchSemanticsNodes().size.let { TestUtil.log("raízes: $it") } }
            runCatching { rule.onAllNodes(isRoot())[0].printToLog("BruDreTest") }
            throw e
        }
    }

    private fun waitTag(tag: String, timeout: Long = 30_000) = diagnose(tag) {
        rule.waitUntil(timeout) { rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, tag)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitPrefix(prefix: String, timeout: Long = 60_000) = diagnose(prefix) {
        rule.waitUntil(timeout) { rule.onAllNodes(tagPrefix(prefix)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitFocused(tag: String, timeout: Long = 8_000) = diagnose("foco em $tag") {
        rule.waitUntil(timeout) { runCatching { rule.onNodeWithTag(tag).assertIsFocused(); true }.getOrDefault(false) }
    }

    /** Envia uma tecla pelo caminho real de entrada (janela → Compose), como o controle do Mi Stick. */
    private fun remote(keyCode: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
        rule.waitForIdle()
    }

    @Before fun setUp() {
        runBlocking { TestUtil.services.library.removeFavorite("cur:sintel") }
        // Android TV não tem tela de toque: simula esse modo (sem "touch mode") como no Mi Stick.
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        com.brudre.streaming.ui.components.IntroState.shown = true // testes de navegação sem a abertura
        rule.activityRule.scenario.recreate()
    }

    @Test fun t01_launchShowsBrandHeroAndFocus() {
        waitTag("hero_play")
        rule.onNodeWithTag("topbar").assertIsDisplayed()
        rule.onNodeWithTag("hero_title").assertIsDisplayed()
        rule.onNodeWithTag("nav_HOME").assertIsDisplayed()
        waitFocused("hero_play")
    }

    @Test fun t02_remoteControlNavigation() {
        waitTag("hero_play")
        waitFocused("hero_play")
        remote(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFocused("hero_details")
        remote(KeyEvent.KEYCODE_DPAD_UP)
        // foco subiu para a barra superior
        diagnose("foco na barra superior") { rule.waitUntil(3_000) { rule.onAllNodes(tagPrefix("nav_").and(isFocused())).fetchSemanticsNodes().isNotEmpty() } }
        // OK sobre "Filmes" troca de seção (sem toque: só controle remoto)
        rule.onNodeWithTag("nav_MOVIES").requestFocus()
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        waitTag("movies")
        rule.waitUntil(90_000) { rule.onAllNodes(tagPrefix("card_")).fetchSemanticsNodes().isNotEmpty() }
        var tries = 0
        while (tries++ < 4 && rule.onAllNodes(tagPrefix("card_").and(isFocused())).fetchSemanticsNodes().isEmpty()) {
            remote(KeyEvent.KEYCODE_DPAD_DOWN)
        }
        diagnose("foco em um card após descer") { rule.waitUntil(3_000) { rule.onAllNodes(tagPrefix("card_").and(isFocused())).fetchSemanticsNodes().isNotEmpty() } }
        // OK abre os detalhes; Voltar fecha
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        waitTag("details")
        Thread.sleep(500)
        remote(KeyEvent.KEYCODE_BACK)
        diagnose("detalhes fechados pelo Voltar") {
            rule.waitUntil(5_000) { rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "details")).fetchSemanticsNodes().isEmpty() }
        }
    }

    @Test fun t03_catalogLoadsAutomatically() {
        waitTag("home")
        rule.onNodeWithTag("home").performScrollToKey("featured")
        waitPrefix("card_featured_ia:", 90_000)
        rule.onNodeWithTag("nav_SERIES").performClick()
        waitTag("series")
        rule.onNodeWithTag("series").performScrollToKey("series")
        waitPrefix("card_series_ia:", 90_000)
        rule.onNodeWithTag("nav_KIDS").performClick()
        waitTag("kids")
        rule.onNodeWithTag("kids").performScrollToKey("curated_kids")
        waitPrefix("card_curated_kids_cur:", 10_000)
        rule.onNodeWithTag("kids").performScrollToKey("kids")
        waitPrefix("card_kids_ia:", 90_000)
        // Seções novas: Clássicos, Documentários e TV ao Vivo
        rule.onNodeWithTag("nav_CLASSICS").performClick()
        waitTag("classics")
        rule.onNodeWithTag("classics").performScrollToKey("silent")
        waitPrefix("card_silent_ia:", 90_000)
        rule.onNodeWithTag("nav_DOCS").performClick()
        waitTag("docs")
        rule.onNodeWithTag("docs").performScrollToKey("docs_prelinger")
        waitPrefix("card_docs_prelinger_ia:", 90_000)
        rule.onNodeWithTag("nav_LIVE").performClick()
        waitTag("live")
        waitPrefix("channel_live_br_", 90_000)
        // Abas novas: Lançamentos e Em Alta (filmes e séries)
        rule.onNodeWithTag("nav_RELEASES").performClick()
        waitTag("releases")
        waitPrefix("card_tmdb_new_movies_tmdb:", 60_000)
        rule.onNodeWithTag("nav_TRENDING").performClick()
        waitTag("trending")
        waitPrefix("card_tmdb_trending_movies_tmdb:", 60_000)
        rule.onNodeWithTag("trending").performScrollToKey("tmdb_trending_series")
        waitPrefix("card_tmdb_trending_series_tmdb:", 60_000)
    }

    @Test fun t04_searchFindsTitles() {
        waitTag("nav_SEARCH")
        rule.onNodeWithTag("nav_SEARCH").performClick()
        waitTag("search_field")
        rule.onNodeWithTag("search_field").performTextInput("popeye")
        rule.onNodeWithTag("search_button").performClick()
        waitPrefix("result_", 90_000)
        rule.onNodeWithTag("search_count").assertIsDisplayed()
        // índice curado também entra na busca
        rule.onNodeWithTag("suggest_horror").performClick()
        waitPrefix("result_", 90_000)
    }

    @Test fun t05_myListFromDetails() {
        waitTag("nav_MOVIES")
        rule.onNodeWithTag("nav_MOVIES").performClick()
        waitTag("movies")
        rule.onNodeWithTag("movies").performScrollToKey("curated_torrent")
        waitPrefix("card_curated_torrent_cur:sintel")
        rule.onNodeWithTag("card_curated_torrent_cur:sintel").performClick()
        waitTag("details")
        waitTag("btn_play", 15_000)
        rule.onNodeWithTag("details_title").assert(hasText("Sintel"))
        rule.onNodeWithTag("btn_favorite").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Na Minha Lista")).fetchSemanticsNodes().isNotEmpty() }
        rule.runOnUiThread { ViewModelProvider(rule.activity)[MainViewModel::class.java].closeDetails() }
        rule.onNodeWithTag("nav_MY_LIST").performClick()
        waitTag("fav_cur:sintel")
        rule.onNodeWithTag("fav_cur:sintel").performClick()
        waitTag("btn_favorite", 15_000)
        rule.onNodeWithTag("btn_favorite").performClick()
        rule.runOnUiThread { ViewModelProvider(rule.activity)[MainViewModel::class.java].closeDetails() }
        waitTag("mylist_empty")
    }

    @Test fun t06_titleWithoutSourceHasNoPlayButton() {
        // Um título cuja fonte não existe não deve exibir "Assistir".
        waitTag("hero_play")
        rule.runOnUiThread {
            ViewModelProvider(rule.activity)[MainViewModel::class.java].openDetails(
                com.brudre.streaming.data.Title("ia:brudre_item_inexistente_123456", com.brudre.streaming.data.Kind.MOVIE, "Inexistente"),
            )
        }
        waitTag("details")
        rule.waitUntil(60_000) {
            rule.onAllNodes(tagPrefix("details_retry")).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodes(tagPrefix("details_unavailable")).fetchSemanticsNodes().isNotEmpty()
        }
        check(rule.onAllNodes(tagPrefix("btn_play")).fetchSemanticsNodes().isEmpty()) { "Botão Assistir não deveria existir" }
        rule.runOnUiThread { ViewModelProvider(rule.activity)[MainViewModel::class.java].closeDetails() }
    }

    @Test fun t07_parentalControlAsksPin() {
        val services = TestUtil.services
        // Um filme popular com classificação acima de "Livre"
        val candidates = listOf("Interestelar", "Duna", "Toy Story", "Matrix").flatMap { q ->
            runBlocking { services.sources.search(q) }.filter { it.isTmdb }.take(3)
        }
        val target = candidates.firstNotNullOfOrNull { t ->
            val d = runCatching { runBlocking { services.sources.details(t) } }.getOrNull() ?: return@firstNotNullOfOrNull null
            if (com.brudre.streaming.data.Parental.level(d.title.rating) > 0) d.title else null
        } ?: error("nenhum filme classificado encontrado")
        services.settings.update { it.copy(maxRating = "L", pinHash = "") }
        try {
            waitTag("hero_play")
            rule.runOnUiThread {
                val vm = ViewModelProvider(rule.activity)[MainViewModel::class.java]
                vm.lockNow()
                vm.openDetails(target)
            }
            waitTag("details_locked", 30_000)
            check(rule.onAllNodes(tagPrefix("btn_play")).fetchSemanticsNodes().isEmpty()) { "Assistir visível sem senha" }
            rule.onNodeWithTag("btn_unlock").performClick()
            waitTag("pin_dialog")
            listOf("1", "2", "3", "4").forEach { rule.onNodeWithTag("pin_key_$it").performClick() }
            diagnose("detalhes liberados após a senha") {
                rule.waitUntil(10_000) { rule.onAllNodes(tagPrefix("details_locked")).fetchSemanticsNodes().isEmpty() }
            }
            check(com.brudre.streaming.data.Parental.check("1234", services.settings.current.pinHash))
            rule.runOnUiThread { ViewModelProvider(rule.activity)[MainViewModel::class.java].closeDetails() }
        } finally {
            services.settings.update { it.copy(maxRating = "16", pinHash = "") }
            rule.runOnUiThread { ViewModelProvider(rule.activity)[MainViewModel::class.java].lockNow() }
        }
    }

    private fun introVisible() = rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "intro")).fetchSemanticsNodes().isNotEmpty()

    private fun relaunchWithIntro() {
        com.brudre.streaming.ui.components.IntroState.shown = false
        com.brudre.streaming.ui.components.IntroVideoState.started = false
        rule.activityRule.scenario.recreate()
    }

    /** Testes 19 a 22: vinheta toca sozinha, OK e Voltar pulam, opção de desligar e transição para o Início. */
    @Test fun t08_cinematicIntro() {
        TestUtil.shell("settings put global animator_duration_scale 1")
        TestUtil.shell("settings put global transition_animation_scale 1")
        try {
            // 19 + 22: toca até o fim (~7 s) e entrega a tela inicial
            relaunchWithIntro()
            waitTag("intro", 5_000)
            val t0 = System.currentTimeMillis()
            for (ms in listOf(1_000L, 2_300L, 3_700L, 5_400L, 6_300L)) {
                val wait = ms - (System.currentTimeMillis() - t0)
                if (wait > 0) Thread.sleep(wait)
                TestUtil.shot("intro_${ms}ms")
            }
            diagnose("vinheta terminar sozinha") { rule.waitUntil(6_000) { !introVisible() } }
            val took = System.currentTimeMillis() - t0
            TestUtil.log("Vinheta: terminou em $took ms; vídeo iniciou=${com.brudre.streaming.ui.components.IntroVideoState.started}; " +
                "animação reserva=${com.brudre.streaming.ui.components.IntroVideoState.usedFallback}")
            check(took in 5_500..11_000) { "duração inesperada: $took ms" }
            waitTag("hero_play", 10_000)
            TestUtil.shot("intro_transicao_inicio")

            // 20: OK pula
            relaunchWithIntro()
            waitTag("intro", 5_000)
            Thread.sleep(1_200)
            remote(KeyEvent.KEYCODE_DPAD_CENTER)
            diagnose("vinheta pulada pelo OK") { rule.waitUntil(2_000) { !introVisible() } }
            // Voltar também pula (sem fechar o app)
            relaunchWithIntro()
            waitTag("intro", 5_000)
            Thread.sleep(800)
            remote(KeyEvent.KEYCODE_BACK)
            diagnose("vinheta pulada pelo Voltar") { rule.waitUntil(2_000) { !introVisible() } }
            waitTag("hero_play", 10_000)

            // 21: desativada nas Configurações
            TestUtil.services.settings.update { it.copy(intro = false) }
            relaunchWithIntro()
            waitTag("hero_play", 15_000)
            check(!introVisible()) { "vinheta apareceu desativada" }
        } finally {
            TestUtil.services.settings.update { it.copy(intro = true) }
            com.brudre.streaming.ui.components.IntroState.shown = true
            TestUtil.shell("settings put global animator_duration_scale 0")
            TestUtil.shell("settings put global transition_animation_scale 0")
        }
    }

    /** Telas principais para validação visual. */
    @Test fun t09_screensForVisualCheck() {
        waitTag("hero_play")
        Thread.sleep(4_000); TestUtil.shot("home")
        for ((nav, tag) in listOf("RELEASES" to "releases", "TRENDING" to "trending", "MOVIES" to "movies", "SERIES" to "series", "CLASSICS" to "classics", "DOCS" to "docs", "LIVE" to "live", "KIDS" to "kids")) {
            rule.onNodeWithTag("nav_$nav").requestFocus(); remote(KeyEvent.KEYCODE_DPAD_CENTER)
            waitTag(tag); Thread.sleep(if (tag == "live") 9_000 else 5_000); TestUtil.shot(tag)
        }
        rule.onNodeWithTag("nav_SETTINGS").requestFocus(); remote(KeyEvent.KEYCODE_DPAD_CENTER)
        waitTag("settings"); Thread.sleep(1_500); TestUtil.shot("configuracoes")
        waitTag("source_archive"); rule.onNodeWithTag("source_archive").requestFocus(); Thread.sleep(800); TestUtil.shot("configuracoes_fontes")
        // Gerenciar fontes (com duas fontes de exemplo)
        val st = TestUtil.services.sourceStore
        val ex = listOf(
            com.brudre.streaming.sources.UserSource(com.brudre.streaming.sources.UserSource.idFor(com.brudre.streaming.sources.SourceType.CATALOG_JSON, "https://raw.githubusercontent.com/andrexjean/brudre-streaming/main/samples/feeds/blender-open-movies.json"),
                "Filmes abertos da Blender", "https://raw.githubusercontent.com/andrexjean/brudre-streaming/main/samples/feeds/blender-open-movies.json",
                com.brudre.streaming.sources.SourceType.CATALOG_JSON, lastTest = com.brudre.streaming.sources.SourceTest(true, System.currentTimeMillis(), "5 títulos", 5)),
            com.brudre.streaming.sources.UserSource(com.brudre.streaming.sources.UserSource.idFor(com.brudre.streaming.sources.SourceType.HLS, "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"),
                "HLS de teste", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", com.brudre.streaming.sources.SourceType.HLS, priority = 1),
        )
        ex.forEach { st.put(it) }
        try {
            rule.onNodeWithTag("manage_sources").performClick()
            waitTag("source_manager"); waitTag("src_card_${ex[0].id}")
            Thread.sleep(1_000); TestUtil.shot("gerenciar_fontes")
            rule.onNodeWithTag("src_add").performClick()
            waitTag("src_dialog"); Thread.sleep(600); TestUtil.shot("gerenciar_fontes_adicionar")
            remote(KeyEvent.KEYCODE_BACK)
            rule.onNodeWithTag("src_close").performClick()
        } finally {
            ex.forEach { st.remove(it.id) }
        }
        // Detalhes de um clássico do catálogo TMDB (fonte procurada no acervo)
        val t = runBlocking { TestUtil.services.catalog.row("tmdb_pd_classics") }.first()
        rule.runOnUiThread { ViewModelProvider(rule.activity)[MainViewModel::class.java].openDetails(t) }
        waitTag("details")
        rule.waitUntil(60_000) { rule.onAllNodes(tagPrefix("details_loading")).fetchSemanticsNodes().isEmpty() }
        Thread.sleep(2_000); TestUtil.shot("detalhes_classico")
        rule.runOnUiThread { ViewModelProvider(rule.activity)[MainViewModel::class.java].closeDetails() }
        // Detalhes de um filme recente grátis (botão do app oficial)
        val free = runBlocking { TestUtil.services.catalog.row("tmdb_free_movies") }.first()
        rule.runOnUiThread { ViewModelProvider(rule.activity)[MainViewModel::class.java].openDetails(free) }
        waitTag("details")
        rule.waitUntil(60_000) { rule.onAllNodes(tagPrefix("details_loading")).fetchSemanticsNodes().isEmpty() }
        Thread.sleep(2_000); TestUtil.shot("detalhes_gratis_app")
        rule.runOnUiThread { ViewModelProvider(rule.activity)[MainViewModel::class.java].closeDetails() }
        // Pesquisa unificada com canais
        rule.onNodeWithTag("nav_SEARCH").performClick()
        waitTag("search_field")
        rule.onNodeWithTag("search_field").performTextInput("cultura")
        rule.onNodeWithTag("search_button").performClick()
        waitPrefix("result_", 90_000)
        Thread.sleep(3_000); TestUtil.shot("pesquisa")
    }

    /** Item 17 (v9.0): Gerenciar fontes inteiro pelo controle remoto — seções, BitTorrent e Diagnóstico. */
    @Test fun t10_sourceManagerSectionsWithDpad() {
        waitTag("hero_play")
        rule.onNodeWithTag("nav_SETTINGS").requestFocus(); remote(KeyEvent.KEYCODE_DPAD_CENTER)
        waitTag("settings")
        waitTag("manage_sources")
        rule.onNodeWithTag("manage_sources").requestFocus(); remote(KeyEvent.KEYCODE_DPAD_CENTER)
        waitTag("source_manager")
        waitFocused("src_tab_mine")
        TestUtil.shot("fontes_minhas")
        // Direita → Catálogos → BitTorrent, OK abre a seção
        remote(KeyEvent.KEYCODE_DPAD_RIGHT); waitFocused("src_tab_catalogs")
        remote(KeyEvent.KEYCODE_DPAD_RIGHT); waitFocused("src_tab_torrent")
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        waitTag("tor_paste"); waitTag("tor_cleanup_ON_CLOSE")
        Thread.sleep(800); TestUtil.shot("fontes_bittorrent")
        // Para baixo chega aos botões; OK em "Colar magnet" abre o diálogo
        // O foco desce pela coluna mais próxima: pode cair em "Importar arquivo"; à esquerda chega em "Colar".
        fun focused(tag: String) = runCatching { rule.onNodeWithTag(tag).assertIsFocused(); true }.getOrDefault(false)
        var guard = 0
        while (!focused("tor_paste") && !focused("tor_import") && guard++ < 6) remote(KeyEvent.KEYCODE_DPAD_DOWN)
        if (focused("tor_import")) remote(KeyEvent.KEYCODE_DPAD_LEFT)
        waitFocused("tor_paste")
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        waitTag("src_dialog"); Thread.sleep(500); TestUtil.shot("fontes_colar_magnet")
        // Voltar: o 1º toque fecha o teclado na tela (comportamento do Android), o seguinte fecha o diálogo.
        fun dialogOpen() = rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "src_dialog")).fetchSemanticsNodes().isNotEmpty()
        var backs = 0
        while (dialogOpen() && backs++ < 3) { remote(KeyEvent.KEYCODE_BACK); Thread.sleep(400) }
        rule.waitUntil(10_000) { !dialogOpen() }
        rule.waitUntil(10_000) { runCatching { rule.onNodeWithTag("src_tab_torrent").requestFocus(); rule.onNodeWithTag("src_tab_torrent").assertIsFocused(); true }.getOrDefault(false) }
        remote(KeyEvent.KEYCODE_DPAD_RIGHT); waitFocused("src_tab_native"); remote(KeyEvent.KEYCODE_DPAD_CENTER)
        waitTag("order_native_first"); waitPrefix("native_"); Thread.sleep(800); TestUtil.shot("fontes_nativas")
        // v9.1: preferências da escolha automática pelo controle — desce até "Resolução preferida" e escolhe outra.
        fun focusedPrefix(p: String) = rule.onAllNodes(isFocused()).fetchSemanticsNodes().any { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(p) == true }
        var g = 0
        while (!focusedPrefix("pref_res_") && g++ < 12) remote(KeyEvent.KEYCODE_DPAD_DOWN)
        org.junit.Assert.assertTrue("D-pad chegou às preferências", focusedPrefix("pref_res_"))
        remote(KeyEvent.KEYCODE_DPAD_RIGHT); remote(KeyEvent.KEYCODE_DPAD_CENTER)
        TestUtil.waitFor(3_000, "resolução preferida escolhida") { TestUtil.services.settings.current.preferredResolution != 0 }
        Thread.sleep(600); TestUtil.shot("fontes_preferencias")
        TestUtil.services.settings.update { it.copy(preferredResolution = 0) }
        rule.waitUntil(10_000) { runCatching { rule.onNodeWithTag("src_tab_native").requestFocus(); rule.onNodeWithTag("src_tab_native").assertIsFocused(); true }.getOrDefault(false) }
        remote(KeyEvent.KEYCODE_DPAD_RIGHT); waitFocused("src_tab_diag"); remote(KeyEvent.KEYCODE_DPAD_CENTER)
        waitTag("diag"); waitTag("diag_summary"); waitTag("diag_tls"); Thread.sleep(800); TestUtil.shot("fontes_diagnostico")
        rule.onNodeWithTag("src_close").performClick()
    }

    /** Validação visual: conteúdo do mundo separado por idioma (TV ao vivo e Cinema Brasileiro). */
    @Test fun t11_worldContentSeparatedByLanguage() {
        waitTag("hero_play")
        rule.onNodeWithTag("nav_LIVE").requestFocus(); remote(KeyEvent.KEYCODE_DPAD_CENTER)
        waitTag("live")
        rule.onNodeWithTag("live").performScrollToKey("world_iptv_movies")
        waitPrefix("row_live_iptv_movies_", 90_000)
        Thread.sleep(4_000); TestUtil.shot("ao_vivo_mundo_filmes")
        // Linhas brasileiras: o TMDB precisa devolver títulos em português (idioma original pt, país BR)
        val br = runBlocking { TestUtil.services.catalog.row("tmdb_br_movies") }
        val brSeries = runBlocking { runCatching { TestUtil.services.catalog.row("tmdb_br_series") }.getOrDefault(emptyList()) }
        val brFree = runBlocking { runCatching { TestUtil.services.catalog.row("tmdb_br_free") }.getOrDefault(emptyList()) }
        TestUtil.log("Cinema Brasileiro: ${br.size} filmes (${br.take(5).map { it.name }}) • séries BR: ${brSeries.size} • grátis BR: ${brFree.size}")
        org.junit.Assert.assertTrue(br.isNotEmpty() && br.all { it.ptAudio && it.languageBadge == "PT-BR" })
        rule.onNodeWithTag("nav_MOVIES").requestFocus(); remote(KeyEvent.KEYCODE_DPAD_CENTER)
        waitTag("movies")
        rule.onNodeWithTag("movies").performScrollToKey("tmdb_br_movies")
        Thread.sleep(6_000); TestUtil.shot("filmes_cinema_brasileiro")
    }
}
