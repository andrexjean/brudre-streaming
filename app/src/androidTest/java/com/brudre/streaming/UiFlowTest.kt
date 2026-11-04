package com.brudre.streaming

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.brudre.streaming.ui.MainActivity
import com.brudre.streaming.ui.MainViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Testes 1 (inicialização), 2 (controle remoto), 3 (catálogo), 4 (pesquisa) e 5 (Minha Lista) pela interface. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class UiFlowTest {

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun tagPrefix(prefix: String) = SemanticsMatcher("testTag começa com $prefix") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    private fun waitTag(tag: String, timeout: Long = 30_000) =
        rule.waitUntil(timeout) { rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, tag)).fetchSemanticsNodes().isNotEmpty() }

    private fun waitPrefix(prefix: String, timeout: Long = 60_000) =
        rule.waitUntil(timeout) { rule.onAllNodes(tagPrefix(prefix)).fetchSemanticsNodes().isNotEmpty() }

    @Before fun clean() = runBlocking {
        TestUtil.services.library.removeFavorite("cur:sintel")
    }

    @Test fun t01_launchShowsBrandHeroAndFocus() {
        waitTag("hero_play")
        rule.onNodeWithTag("topbar").assertIsDisplayed()
        rule.onNodeWithTag("hero_title").assertIsDisplayed()
        rule.onNodeWithTag("nav_HOME").assertIsDisplayed()
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithTag("hero_play").assertIsFocused(); true }.getOrDefault(false) }
    }

    @Test fun t02_remoteControlNavigation() {
        waitTag("hero_play")
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithTag("hero_play").assertIsFocused(); true }.getOrDefault(false) }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        rule.onNodeWithTag("hero_details").assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        rule.waitForIdle()
        // foco subiu para a barra superior
        rule.waitUntil(3_000) { rule.onAllNodes(tagPrefix("nav_").and(isFocused())).fetchSemanticsNodes().isNotEmpty() }
        // OK sobre "Filmes" troca de seção
        rule.onNodeWithTag("nav_MOVIES").performClick()
        waitTag("movies")
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
    }

    @Test fun t03_catalogLoadsAutomatically() {
        waitTag("home")
        rule.onNodeWithTag("home").performScrollToKey("featured")
        waitPrefix("card_featured_ia:", 90_000)
        rule.onNodeWithTag("nav_SERIES").performClick()
        waitPrefix("card_series_ia:", 90_000)
        rule.onNodeWithTag("nav_KIDS").performClick()
        waitPrefix("card_curated_kids_cur:", 10_000)
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
        rule.onNodeWithTag("suggest_Chaplin").performClick()
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
}
