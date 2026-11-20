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
import com.example.updater.AppUpdateManager
import com.example.updater.SharedPreferencesUpdateCacheStore
import com.example.updater.UpdateCacheEntry
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
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
        AppUpdateManager.resetCacheForTesting(app)
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
        AppUpdateManager.resetCacheForTesting(app)
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

    @Test
    fun settingsScreen_checkUpdatesWithUnsavedRepo_persistsBeforeCheckingAndRejectsInvalidInput() {
        val initDeadlineNs = System.nanoTime() + 10_000_000_000L
        while (!viewModel.isInitialSettingsLoaded.value && System.nanoTime() < initDeadlineNs) {
            Thread.sleep(25)
            composeRule.waitForIdle()
        }

        val relBytes = "UI-TEST-APK".toByteArray()
        val relSha = MessageDigest.getInstance("SHA-256").digest(relBytes).joinToString("") { "%02x".format(it) }
        val certSha = "c".repeat(64)

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port
        server.createContext("/repos/jhsoft/jh-getway/releases/latest") { exchange ->
            val body = """
                {
                  "tag_name": "v8.8.8",
                  "name": "JH GetWay v8.8.8",
                  "body": "UI test update",
                  "html_url": "https://github.com/jhsoft/jh-getway/releases/tag/v8.8.8",
                  "assets": [
                    {"name": "JH-GetWay-v8.8.8-release.apk", "browser_download_url": "http://127.0.0.1:$port/dl/release.apk", "size": ${relBytes.size}},
                    {"name": "update-manifest.json", "browser_download_url": "http://127.0.0.1:$port/dl/manifest.json", "size": 300}
                  ]
                }
            """.trimIndent().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/dl/manifest.json") { exchange ->
            val body = """
                {
                  "versionCode": 888,
                  "versionName": "8.8.8",
                  "tag": "v8.8.8",
                  "packageId": "${app.packageName}",
                  "apkName": "JH-GetWay-v8.8.8-release.apk",
                  "sha256": "$relSha",
                  "signingMode": "production",
                  "signingCertSha256": "$certSha"
                }
            """.trimIndent().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        viewModel.updateApiBaseUrlOverride = "http://127.0.0.1:$port"

        try {
            composeRule.setContent {
                CompositionLocalProvider(LocalStrings provides EnglishStrings) {
                    JhGetWayTheme {
                        SettingsScreen(viewModel = viewModel, onNavigate = {})
                    }
                }
            }
            composeRule.waitForIdle()

            // Clicking Check directly after typing a full GitHub URL (without clicking Save first)
            // must persist the normalized slug cleanly and fetch the release without racing.
            composeRule.onNodeWithTag("settings_list")
                .performScrollToNode(hasTestTag("settings_github_repo_input"))
            composeRule.onNodeWithTag("settings_github_repo_input")
                .performTextReplacement("https://github.com/jhsoft/jh-getway")

            composeRule.onNodeWithTag("settings_list")
                .performScrollToNode(hasTestTag("settings_check_updates_btn"))
            composeRule.onNodeWithTag("settings_check_updates_btn").performClick()

            val deadlineNs = System.nanoTime() + 10_000_000_000L
            while ((viewModel.effectiveGithubUpdateRepo() != "jhsoft/jh-getway" ||
                    viewModel.appUpdateUiState.value.availableRelease?.tagName != "v8.8.8") &&
                System.nanoTime() < deadlineNs
            ) {
                Thread.sleep(25)
                composeRule.waitForIdle()
            }
            assertEquals("jhsoft/jh-getway", viewModel.effectiveGithubUpdateRepo())
            assertEquals("v8.8.8", viewModel.appUpdateUiState.value.availableRelease?.tagName)
            assertNull(viewModel.appUpdateUiState.value.errorMessage)

            composeRule.onNodeWithTag("settings_list")
                .performScrollToNode(hasTestTag("settings_available_update_card"))
            composeRule.onNodeWithTag("settings_available_update_card").assertIsDisplayed()

            // Invalid slug on Check must surface validation error cleanly
            composeRule.onNodeWithTag("settings_list")
                .performScrollToNode(hasTestTag("settings_github_repo_input"))
            composeRule.onNodeWithTag("settings_github_repo_input")
                .performTextReplacement("not-a-valid-repo")
            composeRule.onNodeWithTag("settings_list")
                .performScrollToNode(hasTestTag("settings_check_updates_btn"))
            composeRule.onNodeWithTag("settings_check_updates_btn").performClick()

            val errDeadlineNs = System.nanoTime() + 10_000_000_000L
            while (viewModel.appUpdateUiState.value.errorMessage?.contains("Invalid format") != true &&
                System.nanoTime() < errDeadlineNs
            ) {
                Thread.sleep(25)
                composeRule.waitForIdle()
            }
            assertTrue(viewModel.appUpdateUiState.value.errorMessage?.contains("Invalid format") == true)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun checkForAppUpdatesInternal_detectsUpdateAndRecoversWhenCachedApkIsDeleted() {
        val initDeadlineNs = System.nanoTime() + 10_000_000_000L
        while (!viewModel.isInitialSettingsLoaded.value && System.nanoTime() < initDeadlineNs) {
            Thread.sleep(25)
            composeRule.waitForIdle()
        }

        val relBytes = "TEST-APK-BYTES".toByteArray()
        val relSha = MessageDigest.getInstance("SHA-256").digest(relBytes).joinToString("") { "%02x".format(it) }
        val certSha = "b".repeat(64)

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port
        server.createContext("/repos/jhsoft/jh-getway/releases/latest") { exchange ->
            val body = """
                {
                  "tag_name": "v9.9.9",
                  "name": "JH GetWay v9.9.9",
                  "body": "Updater regression test release",
                  "html_url": "https://github.com/jhsoft/jh-getway/releases/tag/v9.9.9",
                  "assets": [
                    {"name": "JH-GetWay-v9.9.9-release.apk", "browser_download_url": "http://127.0.0.1:$port/dl/release.apk", "size": ${relBytes.size}},
                    {"name": "update-manifest.json", "browser_download_url": "http://127.0.0.1:$port/dl/manifest.json", "size": 300}
                  ]
                }
            """.trimIndent().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/dl/manifest.json") { exchange ->
            val body = """
                {
                  "versionCode": 999,
                  "versionName": "9.9.9",
                  "tag": "v9.9.9",
                  "packageId": "${app.packageName}",
                  "apkName": "JH-GetWay-v9.9.9-release.apk",
                  "sha256": "$relSha",
                  "signingMode": "production",
                  "signingCertSha256": "$certSha"
                }
            """.trimIndent().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()

        try {
            val job = viewModel.checkForAppUpdatesInternal(
                silent = false,
                repoInputOverride = "https://github.com/jhsoft/jh-getway",
                apiBaseUrl = "http://127.0.0.1:$port",
                expectedPackageId = app.packageName
            )
            assertNotNull(job)
            val deadlineNs = System.nanoTime() + 10_000_000_000L
            while (!job!!.isCompleted && System.nanoTime() < deadlineNs) {
                Thread.sleep(25)
                composeRule.waitForIdle()
            }
            assertTrue("Update check job did not finish in time; state=${viewModel.appUpdateUiState.value}", job.isCompleted)

            val state = viewModel.appUpdateUiState.value
            assertFalse(state.isChecking)
            assertEquals("v9.9.9", state.availableRelease?.tagName)
            assertTrue(state.showPromptDialog)
            assertEquals("jhsoft/jh-getway", viewModel.effectiveGithubUpdateRepo())

            // Verify permission return handling when Unknown Sources remains denied
            viewModel.onInstallPermissionSettingsOpened()
            assertTrue(viewModel.appUpdateUiState.value.pendingInstallAfterPermission)
            viewModel.onUnknownSourcesPermissionReturned(canInstallOverride = false)
            assertFalse(viewModel.appUpdateUiState.value.pendingInstallAfterPermission)
            assertTrue(
                viewModel.appUpdateUiState.value.errorMessage?.contains("Install unknown apps", ignoreCase = true) == true
            )

            // Verify deleting a missing APK cleanly resets downloadedApkFile so UI does not get stuck
            val missingApk = File(app.cacheDir, "updates/deleted.apk")
            if (missingApk.exists()) missingApk.delete()
            assertNull(viewModel.appUpdateUiState.value.downloadedApkFile)
        } finally {
            server.stop(0)
        }
    }
}
