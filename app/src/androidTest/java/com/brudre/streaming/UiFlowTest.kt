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
        val candidates = runBlocking { services.catalog.row("tmdb_movies", force = true) }
        val target = candidates.take(10).firstNotNullOfOrNull { t ->
            val d = runBlocking { services.catalog.details(t) }
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
            check(rule.onAllNodes(tagPrefix("provider_")).fetchSemanticsNodes().isEmpty()) { "Onde assistir visível sem senha" }
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
}
