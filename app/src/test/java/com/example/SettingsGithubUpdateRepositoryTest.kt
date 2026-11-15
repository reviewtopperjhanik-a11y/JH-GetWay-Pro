package com.example

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.example.core.i18n.EnglishStrings
import com.example.core.i18n.LocalStrings
import com.example.core.security.CryptoManager
import com.example.ui.GatewayViewModel
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.JhGetWayTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsGithubUpdateRepositoryTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var app: JhGetWayApplication
    private lateinit var vmStore: ViewModelStore
    private lateinit var viewModel: GatewayViewModel

    @Before
    fun setUp() {
        CryptoManager.resetTestKeyProvider()
        app = ApplicationProvider.getApplicationContext()
        runBlocking(Dispatchers.IO) {
            app.preferences.clearAll()
            app.database.clearAllTables()
        }
        vmStore = ViewModelStore()
        viewModel = ViewModelProvider(
            vmStore,
            ViewModelProvider.AndroidViewModelFactory.getInstance(app)
        )[GatewayViewModel::class.java]
    }

    @After
    fun tearDown() {
        vmStore.clear()
        runBlocking(Dispatchers.IO) {
            app.gatewayServer.stop()
            app.preferences.clearAll()
            app.database.clearAllTables()
        }
        CryptoManager.resetTestKeyProvider()
    }

    @Test
    fun settingsScreen_savesNormalizedGithubUpdateRepository() {
        val initDeadlineNs = System.nanoTime() + 10_000_000_000L
        while (!viewModel.isInitialSettingsLoaded.value && System.nanoTime() < initDeadlineNs) {
            Thread.sleep(25)
            composeRule.waitForIdle()
        }

        composeRule.setContent {
            CompositionLocalProvider(LocalStrings provides EnglishStrings) {
                JhGetWayTheme {
                    SettingsScreen(viewModel = viewModel, onNavigate = {})
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("settings_github_repo_input"))

        composeRule.onNodeWithTag("settings_github_repo_input")
            .assertIsDisplayed()
            .performTextReplacement("https://github.com/jhsoft/jh-getway")

        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("settings_github_repo_save_btn"))

        composeRule.onNodeWithTag("settings_github_repo_save_btn")
            .performClick()

        val deadlineNs = System.nanoTime() + 10_000_000_000L
        while (viewModel.effectiveGithubUpdateRepo() != "jhsoft/jh-getway" && System.nanoTime() < deadlineNs) {
            Thread.sleep(50)
            composeRule.waitForIdle()
        }
        assertEquals("jhsoft/jh-getway", viewModel.effectiveGithubUpdateRepo())
    }
}
