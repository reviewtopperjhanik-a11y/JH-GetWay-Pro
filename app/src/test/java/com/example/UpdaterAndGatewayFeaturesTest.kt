package com.example

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.core.i18n.EnglishStrings
import com.example.core.i18n.LocalStrings
import com.example.core.security.CryptoManager
import com.example.core.security.NetworkAddressClassifier
import com.example.data.local.*
import com.example.gateway.GatewayConfigDraft
import com.example.gateway.GatewayConfigValidator
import com.example.gateway.GatewayRunStatus
import com.example.gateway.OkHttpRemoteHealthChecker
import com.example.ui.GatewayViewModel
import com.example.ui.screens.AllApisScreen
import com.example.ui.screens.GatewayConfigScreen
import com.example.ui.screens.GatewayScreen
import com.example.ui.screens.ProviderDetailScreen
import com.example.ui.screens.ProviderDirectoryHelper
import com.example.ui.screens.ProvidersScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.JhGetWayTheme
import com.example.updater.AppReleaseInfo
import com.example.updater.AppUpdateCheckResult
import com.example.updater.AppUpdateManager
import com.example.updater.InMemoryUpdateCacheStore
import com.example.updater.SharedPreferencesUpdateCacheStore
import com.example.updater.UpdateCacheEntry
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UpdaterAndGatewayFeaturesTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var app: JhGetWayApplication
    private lateinit var viewModel: GatewayViewModel

    @Before
    fun setUp() {
        CryptoManager.resetTestKeyProvider()
        app = ApplicationProvider.getApplicationContext()
        runBlocking(Dispatchers.IO) {
            app.gatewayServer.stop()
            app.preferences.clearAll()
            app.database.clearAllTables()
        }
        AppUpdateManager.resetCacheForTesting(app)
        viewModel = GatewayViewModel(app)
        waitUntilSettingsLoaded()
    }

    @After
    fun tearDown() {
        runBlocking(Dispatchers.IO) {
            app.gatewayServer.stop()
            app.preferences.clearAll()
            app.database.clearAllTables()
        }
        AppUpdateManager.resetCacheForTesting(app)
        CryptoManager.resetTestKeyProvider()
    }

    private fun waitUntilSettingsLoaded() {
        waitUntilCondition(10_000L) { viewModel.isInitialSettingsLoaded.value }
    }

    private fun waitUntilCondition(timeoutMs: Long = 10_000L, condition: () -> Boolean) {
        val deadlineNs = System.nanoTime() + timeoutMs * 1_000_000L
        while (!condition() && System.nanoTime() < deadlineNs) {
            Thread.sleep(40)
            composeRule.waitForIdle()
        }
        assertTrue("Condition still not satisfied after ${timeoutMs}ms", condition())
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // -------------------------------------------------------------------------
    // Feature 1: Gateway Configuration Management
    // -------------------------------------------------------------------------

    @Test
    fun gatewayConfigValidator_blocksInvalidAndAcceptsValidModes() {
        val badPort = GatewayConfigDraft(
            name = "My Gateway",
            mode = GatewayMode.LOCAL,
            host = "127.0.0.1",
            port = 80,
            remoteUrl = "",
            authRequired = true,
            timeoutSec = 60,
            maxRequestKb = 2048,
            streamingEnabled = true,
            rateLimitPerMin = 60,
            loggingLevel = LoggingLevel.METADATA_ONLY,
            allowLanExposure = false,
            allowExistingLocalHttp = false,
            remoteAuthTokenInput = "",
            clearSavedRemoteToken = false
        )
        assertFalse(GatewayConfigValidator.validate(badPort).valid)

        val nonHttpsRemote = badPort.copy(
            mode = GatewayMode.REMOTE,
            port = 8787,
            remoteUrl = "http://gateway.example.com"
        )
        assertFalse(GatewayConfigValidator.validate(nonHttpsRemote).valid)

        val httpsRemote = nonHttpsRemote.copy(remoteUrl = "https://gateway.example.com/")
        val remoteRes = GatewayConfigValidator.validate(httpsRemote)
        assertTrue(remoteRes.valid)
        assertEquals("https://gateway.example.com", remoteRes.normalizedRemoteUrl)

        val existingLoopbackBlocked = badPort.copy(
            mode = GatewayMode.EXISTING,
            port = 8787,
            remoteUrl = "http://127.0.0.1:9000",
            allowExistingLocalHttp = false
        )
        assertFalse(GatewayConfigValidator.validate(existingLoopbackBlocked).valid)

        val existingLoopbackAllowed = existingLoopbackBlocked.copy(allowExistingLocalHttp = true)
        assertTrue(GatewayConfigValidator.validate(existingLoopbackAllowed).valid)
    }

    @Test
    fun gatewayModeSwitching_requiresConfirmation_verifiesConnection_andPreservesAllUserData() {
        val encryptedKey = CryptoManager.encrypt("AIza-secret-gemini-key-999")
        val provider = ProviderEntity(
            id = "prov-gemini-1",
            name = "Gemini Work",
            type = ProviderType.BUILT_IN,
            presetId = "GEMINI",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            apiFormat = ApiFormat.GEMINI,
            defaultModel = "gemini-2.5-flash",
            priority = 1,
            enabled = true,
            status = ProviderStatus.CONNECTED
        )
        val keyEntity = ApiKeyEntity(
            id = "key-1",
            providerId = provider.id,
            label = "Primary Gemini Key",
            encryptedKey = encryptedKey,
            maskedPreview = "AIza...999",
            isDefault = true,
            enabled = true
        )
        val modelEntity = ModelEntity(
            id = "model-1",
            modelId = "gemini-2.5-flash",
            displayName = "Gemini 2.5 Flash",
            providerId = provider.id,
            providerName = provider.name,
            isDefault = true,
            enabled = true
        )
        val agentEntity = CodingAgentEntity(
            id = "agent-1",
            name = "OpenCode",
            agentType = CodingAgentType.OPENCODE,
            defaultModelId = "gemini-2.5-flash",
            encryptedToken = CryptoManager.encrypt("agent-token-123"),
            tokenPreview = "agen...123",
            enabled = true
        )
        val mcpServer = McpServerEntity(
            id = "mcp-1",
            name = "Docs MCP",
            transportType = McpTransportType.STREAMABLE_HTTP,
            endpointOrCommand = "https://mcp.example.com",
            enabled = true
        )
        val workflow = WorkflowEntity(
            id = "wf-1",
            name = "Code Review Flow",
            nodesJson = "[]",
            edgesJson = "[]"
        )
        val playgroundSession = PlaygroundSessionEntity(
            id = "sess-1",
            title = "Important Prompt Session"
        )
        val playgroundMsg = PlaygroundMessageEntity(
            id = "msg-1",
            sessionId = playgroundSession.id,
            role = "user",
            content = "Keep this message safe",
            attachmentsMetadataJson = """[{"name":"spec.txt","mimeType":"text/plain"}]"""
        )

        runBlocking(Dispatchers.IO) {
            app.database.providerDao().upsert(provider)
            app.database.apiKeyDao().upsert(keyEntity)
            app.database.modelDao().upsertAll(listOf(modelEntity))
            app.database.codingAgentDao().upsert(agentEntity)
            app.database.mcpDao().upsertServer(mcpServer)
            app.database.workflowDao().upsertWorkflow(workflow)
            app.database.playgroundDao().upsertSession(playgroundSession)
            app.database.playgroundDao().insertMessage(playgroundMsg)
        }

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port
        server.createContext("/health") { exchange ->
            val body = """{"status":"ok"}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()

        try {
            // 1. Render GatewayConfigScreen
            composeRule.setContent {
                CompositionLocalProvider(LocalStrings provides EnglishStrings) {
                    JhGetWayTheme {
                        GatewayConfigScreen(viewModel = viewModel, onBack = {})
                    }
                }
            }
            composeRule.waitForIdle()

            // 2. Test draft connection for EXISTING gateway
            val existingDraft = GatewayConfigDraft(
                name = "Shared LAN Gateway",
                mode = GatewayMode.EXISTING,
                host = "127.0.0.1",
                port = 8787,
                remoteUrl = "http://127.0.0.1:$port",
                authRequired = true,
                timeoutSec = 45,
                maxRequestKb = 2048,
                streamingEnabled = true,
                rateLimitPerMin = 90,
                loggingLevel = LoggingLevel.METADATA_ONLY,
                allowLanExposure = false,
                allowExistingLocalHttp = true,
                remoteAuthTokenInput = "remote-bearer-123",
                clearSavedRemoteToken = false
            )
            viewModel.testGatewayDraftConnection(existingDraft)
            waitUntilCondition {
                !viewModel.gatewayConfigTestResult.value.isTesting &&
                    viewModel.gatewayConfigTestResult.value.testedAtMs != null
            }
            assertEquals(true, viewModel.gatewayConfigTestResult.value.success)
            assertEquals(200, viewModel.gatewayConfigTestResult.value.httpCode)

            // 3. Confirm mode switch dialog appears before changing modes in UI
            composeRule.onNodeWithTag("gw_config_active_mode_text")
                .assertTextContains(EnglishStrings.localGatewayMode)

            composeRule.onNodeWithTag("gateway_config_list")
                .performScrollToNode(hasTestTag("gw_config_mode_EXISTING"))
            composeRule.onNodeWithTag("gw_config_mode_EXISTING").performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("gateway_config_list")
                .performScrollToNode(hasTestTag("gw_config_remote_url_input"))
            composeRule.onNodeWithTag("gw_config_remote_url_input")
                .performTextReplacement("http://127.0.0.1:$port")

            composeRule.onNodeWithTag("gateway_config_list")
                .performScrollToNode(hasTestTag("gw_config_existing_local_switch"))
            if (!viewModel.settings.value.localNetworkAccessAllowed) {
                composeRule.onNodeWithTag("gw_config_existing_local_switch").performClick()
            }

            composeRule.onNodeWithTag("gateway_config_list")
                .performScrollToNode(hasTestTag("gw_config_save_btn"))
            composeRule.onNodeWithTag("gw_config_save_btn").performClick()
            composeRule.waitForIdle()

            // Confirmation dialog MUST appear before switching
            composeRule.onNodeWithTag("gw_mode_switch_confirm_dialog").assertIsDisplayed()

            // Cancel first -> mode must still be LOCAL
            composeRule.onNodeWithTag("gw_mode_switch_cancel_btn").performClick()
            composeRule.waitForIdle()
            assertEquals(GatewayMode.LOCAL, viewModel.settings.value.gatewayMode)

            // Confirm switch -> EXISTING
            composeRule.onNodeWithTag("gw_config_save_btn").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("gw_mode_switch_confirm_btn").performClick()

            waitUntilCondition {
                viewModel.settings.value.gatewayMode == GatewayMode.EXISTING
            }
            assertEquals(GatewayMode.EXISTING, viewModel.settings.value.gatewayMode)
            assertEquals("http://127.0.0.1:$port", viewModel.settings.value.gatewayRemoteUrl)

            // Also verify switching back to LOCAL works cleanly
            val localSave = runBlocking(Dispatchers.IO) {
                viewModel.saveAndApplyGatewayConfig(
                    existingDraft.copy(mode = GatewayMode.LOCAL, port = 18787)
                )
            }
            assertTrue(localSave.isSuccess)
            waitUntilCondition {
                viewModel.settings.value.gatewayMode == GatewayMode.LOCAL &&
                    viewModel.settings.value.gatewayPort == 18787
            }
            assertEquals(GatewayMode.LOCAL, viewModel.settings.value.gatewayMode)
            assertEquals(18787, viewModel.settings.value.gatewayPort)

            // Verify ALL user data is 100% preserved & encrypted key still decrypts
            runBlocking(Dispatchers.IO) {
                val storedProviders = app.database.providerDao().getAllOnce()
                val storedKeys = app.database.apiKeyDao().getAllOnce()
                val storedModels = app.database.modelDao().getAllOnce()
                val storedAgents = app.database.codingAgentDao().getAllOnce()
                val storedMcp = app.database.mcpDao().getServersOnce()
                val storedWorkflows = app.database.workflowDao().getWorkflowsOnce()
                val storedSessions = app.database.playgroundDao().getSessionsOnce()
                val storedMsgs = app.database.playgroundDao().getMessagesOnce("sess-1")

                assertEquals(1, storedProviders.size)
                assertEquals("AIza-secret-gemini-key-999", CryptoManager.decrypt(storedKeys.single().encryptedKey))
                assertEquals(1, storedModels.size)
                assertEquals(1, storedAgents.size)
                assertEquals(1, storedMcp.size)
                assertEquals(1, storedWorkflows.size)
                assertEquals(1, storedSessions.size)
                assertEquals(1, storedMsgs.size)
                assertTrue(storedMsgs.single().attachmentsMetadataJson.contains("spec.txt"))
            }
        } finally {
            server.stop(0)
        }
    }

    // -------------------------------------------------------------------------
    // Feature 2: Provider Directory UI Upgrade & Strict Isolation
    // -------------------------------------------------------------------------

    @Test
    fun providerDirectory_groupsCounts_andIsolatesIndividualProviderPage() {
        val g1 = ProviderEntity(
            id = "g1",
            name = "Gemini Personal",
            type = ProviderType.BUILT_IN,
            presetId = "GEMINI",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            apiFormat = ApiFormat.GEMINI,
            defaultModel = "gemini-2.5-flash",
            priority = 1,
            enabled = true,
            status = ProviderStatus.CONNECTED,
            lastLatencyMs = 120L
        )
        val g2 = ProviderEntity(
            id = "g2",
            name = "Gemini Work",
            type = ProviderType.BUILT_IN,
            presetId = "GEMINI",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            apiFormat = ApiFormat.GEMINI,
            defaultModel = "gemini-2.5-pro",
            priority = 2,
            enabled = true,
            status = ProviderStatus.UNKNOWN
        )
        val or1 = ProviderEntity(
            id = "or1",
            name = "OpenRouter Primary",
            type = ProviderType.OPENAI_COMPATIBLE,
            presetId = "OPENROUTER",
            baseUrl = "https://openrouter.ai/api/v1",
            apiFormat = ApiFormat.OPENAI,
            defaultModel = "openai/gpt-4o-mini",
            priority = 3,
            enabled = true,
            status = ProviderStatus.ERROR,
            lastError = "401 Unauthorized"
        )
        val custom1 = ProviderEntity(
            id = "c1",
            name = "Corp Proxy",
            type = ProviderType.OPENAI_COMPATIBLE,
            presetId = "CUSTOM",
            baseUrl = "https://proxy.corp.internal/v1",
            apiFormat = ApiFormat.OPENAI,
            defaultModel = "llama3",
            priority = 4,
            enabled = true,
            status = ProviderStatus.CONNECTED
        )

        runBlocking(Dispatchers.IO) {
            listOf(g1, g2, or1, custom1).forEach { app.database.providerDao().upsert(it) }
        }

        val groups = ProviderDirectoryHelper.groupProviders(listOf(g1, g2, or1, custom1))
        assertEquals(3, groups.size)
        val geminiGroup = groups.first { it.presetId == "GEMINI" }
        assertEquals("Google Gemini", geminiGroup.displayName)
        assertEquals(2, geminiGroup.providers.size)
        assertEquals(ProviderStatus.CONNECTED, geminiGroup.aggregatedStatus)

        val navigatedRoutes = mutableListOf<String>()
        composeRule.setContent {
            CompositionLocalProvider(LocalStrings provides EnglishStrings) {
                JhGetWayTheme {
                    ProvidersScreen(viewModel = viewModel, onNavigate = { navigatedRoutes += it })
                }
            }
        }
        waitUntilCondition { viewModel.providers.value.size == 4 }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("providers_connected_apis_count")
            .assert(hasAnyDescendant(hasText("4")))
        composeRule.onNodeWithTag("providers_distinct_count")
            .assert(hasAnyDescendant(hasText("3")))
        composeRule.onNodeWithTag("providers_all_btn").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("add_provider_fab").assertIsDisplayed()
        composeRule.onNodeWithTag("provider_group_card_preset:GEMINI")
            .assertIsDisplayed()
            .assertTextContains("Google Gemini", substring = true)
            .assertTextContains("2 APIs", substring = true)

        composeRule.onNodeWithTag("providers_directory_list")
            .performScrollToNode(hasTestTag("provider_group_card_preset:OPENROUTER"))
        composeRule.onNodeWithTag("provider_group_card_preset:OPENROUTER")
            .assertIsDisplayed()
            .assertTextContains("OpenRouter", substring = true)
            .assertTextContains("1 API", substring = true)

        composeRule.onNodeWithTag("providers_directory_list")
            .performScrollToNode(hasTestTag("providers_summary_card"))
        composeRule.onNodeWithTag("providers_all_btn").performClick()
        assertEquals("providers/all", navigatedRoutes.last())

        composeRule.onNodeWithTag("providers_directory_list")
            .performScrollToNode(hasTestTag("provider_group_card_preset:GEMINI"))
        composeRule.onNodeWithTag("provider_group_card_preset:GEMINI").performClick()
        assertTrue(navigatedRoutes.last().startsWith("providers/detail/"))
    }

    @Test
    fun providerDetailScreen_showsStrictlySelectedProviderOnly_withoutCrossProviderLeakage() {
        val g1 = ProviderEntity(
            id = "g1",
            name = "Gemini Fast",
            type = ProviderType.BUILT_IN,
            presetId = "GEMINI",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            apiFormat = ApiFormat.GEMINI,
            defaultModel = "gemini-2.5-flash",
            enabled = true,
            status = ProviderStatus.CONNECTED
        )
        val or1 = ProviderEntity(
            id = "or1",
            name = "OpenRouter Secret",
            type = ProviderType.OPENAI_COMPATIBLE,
            presetId = "OPENROUTER",
            baseUrl = "https://openrouter.ai/api/v1",
            apiFormat = ApiFormat.OPENAI,
            defaultModel = "openai/gpt-4o-mini",
            enabled = true,
            status = ProviderStatus.CONNECTED
        )
        runBlocking(Dispatchers.IO) {
            app.database.providerDao().upsert(g1)
            app.database.providerDao().upsert(or1)
            app.database.apiKeyDao().upsert(
                ApiKeyEntity(
                    id = "kg1",
                    providerId = g1.id,
                    label = "Gemini Primary Key",
                    encryptedKey = CryptoManager.encrypt("AIza-test-key"),
                    maskedPreview = "AIza...key",
                    isDefault = true,
                    enabled = true
                )
            )
        }

        composeRule.setContent {
            CompositionLocalProvider(LocalStrings provides EnglishStrings) {
                JhGetWayTheme {
                    ProviderDetailScreen(groupKey = "preset:GEMINI", viewModel = viewModel)
                }
            }
        }
        waitUntilCondition { viewModel.providers.value.size == 2 }
        composeRule.waitForIdle()

        // Gemini content & operations MUST be visible
        composeRule.onNodeWithTag("provider_detail_header")
            .assert(hasAnyDescendant(hasText("Google Gemini", substring = true)))
        composeRule.onNodeWithTag("provider_api_card_g1").assertIsDisplayed()
        composeRule.onNodeWithTag("test_provider_g1").assertExists()
        composeRule.onNodeWithTag("fetch_models_g1").assertExists()
        composeRule.onNodeWithTag("manage_keys_g1").assertExists()
        composeRule.onNodeWithTag("edit_provider_g1").assertExists()
        composeRule.onNodeWithTag("view_details_g1").assertExists()
        composeRule.onNodeWithTag("toggle_provider_g1").assertExists()
        composeRule.onNodeWithTag("delete_provider_g1").assertExists()

        // ZERO other provider names/navigation/cards inside individual Provider page
        composeRule.onAllNodesWithText("OpenRouter", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("OpenAI", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("Anthropic", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("Groq", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithTag("provider_api_card_or1").assertCountEquals(0)

        // Opening floating + inside Gemini page MUST NOT leak other provider preset chips
        composeRule.onNodeWithTag("add_provider_fab").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("OpenRouter", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("Anthropic", substring = true).assertCountEquals(0)
    }

    @Test
    fun allApisScreen_listsAllConnectedApisWithProviderBadges() {
        runBlocking(Dispatchers.IO) {
            app.database.providerDao().upsert(
                ProviderEntity(
                    id = "g1",
                    name = "Gemini Endpoint",
                    presetId = "GEMINI",
                    baseUrl = "https://generativelanguage.googleapis.com/v1beta",
                    apiFormat = ApiFormat.GEMINI
                )
            )
            app.database.providerDao().upsert(
                ProviderEntity(
                    id = "or1",
                    name = "Router Endpoint",
                    presetId = "OPENROUTER",
                    baseUrl = "https://openrouter.ai/api/v1",
                    apiFormat = ApiFormat.OPENAI
                )
            )
        }

        composeRule.setContent {
            CompositionLocalProvider(LocalStrings provides EnglishStrings) {
                JhGetWayTheme {
                    AllApisScreen(viewModel = viewModel)
                }
            }
        }
        composeRule.waitUntil(5000) { viewModel.providers.value.size == 2 }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("provider_api_card_g1")
            .assertIsDisplayed()
            .assert(hasAnyDescendant(hasText("Google Gemini", substring = true)))
        composeRule.onNodeWithTag("all_apis_list")
            .performScrollToNode(hasTestTag("provider_api_card_or1"))
        composeRule.onNodeWithTag("provider_api_card_or1")
            .assertIsDisplayed()
            .assert(hasAnyDescendant(hasText("OpenRouter", substring = true)))
    }

    // -------------------------------------------------------------------------
    // Feature 3: GitHub Auto Updater Cache, ETag/304, Rate Limit & Resume
    // -------------------------------------------------------------------------

    @Test
    fun githubUpdater_usesEtagAndTtlCache_handles429And403Distinctly_andPreservesCachedRelease() {
        runBlocking {
            val relSha = "a".repeat(64)
            val certSha = "b".repeat(64)
            var clockMs = 1_700_000_000_000L
            val latestHitCount = AtomicInteger(0)
            var mode = "200"

            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            val port = server.address.port

            server.createContext("/repos/jhsoft/jh-getway/releases/latest") { exchange ->
                latestHitCount.incrementAndGet()
                val ifNoneMatch = exchange.requestHeaders.getFirst("If-None-Match")
                when {
                    mode == "304" && ifNoneMatch == "\"etag-v112\"" -> {
                        exchange.responseHeaders.add("ETag", "\"etag-v112\"")
                        exchange.responseHeaders.add("X-RateLimit-Remaining", "58")
                        exchange.sendResponseHeaders(304, -1)
                        exchange.close()
                    }
                    mode == "429" -> {
                        val body = """{"message":"API rate limit exceeded for IP"}""".toByteArray()
                        exchange.responseHeaders.add("X-RateLimit-Remaining", "0")
                        exchange.responseHeaders.add("X-RateLimit-Reset", ((clockMs + 120_000L) / 1000L).toString())
                        exchange.responseHeaders.add("Retry-After", "120")
                        exchange.sendResponseHeaders(429, body.size.toLong())
                        exchange.responseBody.use { it.write(body) }
                    }
                    mode == "403_forbidden" -> {
                        val body = """{"message":"Repository access blocked by organization policy"}""".toByteArray()
                        exchange.responseHeaders.add("X-RateLimit-Remaining", "42")
                        exchange.sendResponseHeaders(403, body.size.toLong())
                        exchange.responseBody.use { it.write(body) }
                    }
                    else -> {
                        val body = """
                            {
                              "tag_name": "v1.0.12",
                              "name": "JH GetWay v1.0.12",
                              "body": "Bug fixes",
                              "html_url": "https://github.com/jhsoft/jh-getway/releases/tag/v1.0.12",
                              "assets": [
                                {"name": "JH-GetWay-v1.0.12-release.apk", "browser_download_url": "http://127.0.0.1:$port/dl/release.apk", "size": 2048},
                                {"name": "update-manifest.json", "browser_download_url": "http://127.0.0.1:$port/dl/manifest.json", "size": 300}
                              ]
                            }
                        """.trimIndent().toByteArray()
                        exchange.responseHeaders.add("ETag", "\"etag-v112\"")
                        exchange.responseHeaders.add("X-RateLimit-Remaining", "59")
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, body.size.toLong())
                        exchange.responseBody.use { it.write(body) }
                    }
                }
            }
            server.createContext("/dl/manifest.json") { exchange ->
                val body = """
                    {
                      "versionCode": 112,
                      "versionName": "1.0.12",
                      "tag": "v1.0.12",
                      "packageId": "com.aistudio.jhgetway.iffndf",
                      "apkName": "JH-GetWay-v1.0.12-release.apk",
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
                val base = "http://127.0.0.1:$port"
                val store = InMemoryUpdateCacheStore()

                val first = AppUpdateManager.checkForUpdate(
                    repoInput = "jhsoft/jh-getway",
                    currentVersionCode = 110,
                    currentVersionName = "1.0.10",
                    apiBaseUrl = base,
                    expectedPackageId = "com.aistudio.jhgetway.iffndf",
                    cacheStore = store,
                    nowMsProvider = { clockMs }
                )
                assertTrue(first is AppUpdateCheckResult.UpdateAvailable)
                assertFalse((first as AppUpdateCheckResult.UpdateAvailable).fromCache)
                assertEquals(1, latestHitCount.get())

                clockMs += 60_000L
                val ttlCached = AppUpdateManager.checkForUpdate(
                    repoInput = "jhsoft/jh-getway",
                    currentVersionCode = 110,
                    currentVersionName = "1.0.10",
                    apiBaseUrl = base,
                    expectedPackageId = "com.aistudio.jhgetway.iffndf",
                    cacheStore = store,
                    forceRefresh = false,
                    nowMsProvider = { clockMs }
                )
                assertTrue(ttlCached is AppUpdateCheckResult.UpdateAvailable)
                assertTrue((ttlCached as AppUpdateCheckResult.UpdateAvailable).fromCache)
                assertEquals(1, latestHitCount.get())

                mode = "304"
                val notModified = AppUpdateManager.checkForUpdate(
                    repoInput = "jhsoft/jh-getway",
                    currentVersionCode = 110,
                    currentVersionName = "1.0.10",
                    apiBaseUrl = base,
                    expectedPackageId = "com.aistudio.jhgetway.iffndf",
                    cacheStore = store,
                    forceRefresh = true,
                    nowMsProvider = { clockMs }
                )
                assertTrue(notModified is AppUpdateCheckResult.UpdateAvailable)
                assertTrue((notModified as AppUpdateCheckResult.UpdateAvailable).fromCache)
                assertEquals(2, latestHitCount.get())

                mode = "403_forbidden"
                val forbiddenRes = AppUpdateManager.checkForUpdate(
                    repoInput = "jhsoft/jh-getway",
                    currentVersionCode = 110,
                    currentVersionName = "1.0.10",
                    apiBaseUrl = base,
                    expectedPackageId = "com.aistudio.jhgetway.iffndf",
                    cacheStore = store,
                    forceRefresh = true,
                    nowMsProvider = { clockMs }
                )
                assertTrue("Non-rate-limit 403 must be Error, got $forbiddenRes", forbiddenRes is AppUpdateCheckResult.Error)
                val err403 = forbiddenRes as AppUpdateCheckResult.Error
                assertEquals(403, err403.httpCode)
                assertFalse(err403.message.contains("rate limit", ignoreCase = true))
                assertNotNull("Valid cached release should still be preserved", err403.cachedRelease)

                mode = "429"
                val hitsBefore429 = latestHitCount.get()
                val rateLimited1 = AppUpdateManager.checkForUpdate(
                    repoInput = "jhsoft/jh-getway",
                    currentVersionCode = 110,
                    currentVersionName = "1.0.10",
                    apiBaseUrl = base,
                    expectedPackageId = "com.aistudio.jhgetway.iffndf",
                    cacheStore = store,
                    forceRefresh = true,
                    nowMsProvider = { clockMs }
                )
                assertTrue(rateLimited1 is AppUpdateCheckResult.RateLimited)
                val rl1 = rateLimited1 as AppUpdateCheckResult.RateLimited
                assertEquals(clockMs + 120_000L, rl1.retryAfterEpochMs)
                assertNotNull(rl1.cachedRelease)
                assertEquals(hitsBefore429 + 1, latestHitCount.get())

                clockMs += 30_000L
                val rateLimitedBlocked = AppUpdateManager.checkForUpdate(
                    repoInput = "jhsoft/jh-getway",
                    currentVersionCode = 110,
                    currentVersionName = "1.0.10",
                    apiBaseUrl = base,
                    expectedPackageId = "com.aistudio.jhgetway.iffndf",
                    cacheStore = store,
                    forceRefresh = true,
                    nowMsProvider = { clockMs }
                )
                assertTrue(rateLimitedBlocked is AppUpdateCheckResult.RateLimited)
                assertEquals("Pre-deadline retry must NOT hit network", hitsBefore429 + 1, latestHitCount.get())
            } finally {
                server.stop(0)
            }
        }
    }

    @Test
    fun githubUpdater_resumesPartialApkDownloadAndVerifiesSha256() {
        runBlocking {
            val fullPayload = ByteArray(1024) { idx -> ((idx * 31) % 251).toByte() }
            val expectedSha = sha256Hex(fullPayload)
            val attempt = AtomicInteger(0)
            val receivedRangeHeaders = mutableListOf<String?>()

            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            val port = server.address.port
            server.createContext("/dl/resumable.apk") { exchange ->
                val currentAttempt = attempt.incrementAndGet()
                val range = exchange.requestHeaders.getFirst("Range")
                receivedRangeHeaders += range
                exchange.responseHeaders.add("ETag", "\"apk-etag-1\"")

                if (currentAttempt == 1) {
                    val firstPart = fullPayload.copyOfRange(0, 400)
                    exchange.sendResponseHeaders(200, firstPart.size.toLong())
                    exchange.responseBody.use { it.write(firstPart) }
                } else {
                    val offset = range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                    val rest = fullPayload.copyOfRange(offset, fullPayload.size)
                    exchange.responseHeaders.add("Content-Range", "bytes $offset-${fullPayload.size - 1}/${fullPayload.size}")
                    exchange.sendResponseHeaders(206, rest.size.toLong())
                    exchange.responseBody.use { it.write(rest) }
                }
            }
            server.start()

            try {
                val release = AppReleaseInfo(
                    repoSlug = "jhsoft/jh-getway",
                    tagName = "v1.0.15",
                    versionName = "1.0.15",
                    versionCode = 115,
                    releaseTitle = "v1.0.15",
                    releaseNotes = "",
                    htmlUrl = "https://github.com/jhsoft/jh-getway/releases/tag/v1.0.15",
                    publishedAt = "",
                    apkDownloadUrl = "http://127.0.0.1:$port/dl/resumable.apk",
                    apkFileName = "JH-GetWay-v1.0.15-release.apk",
                    apkSizeBytes = fullPayload.size.toLong(),
                    expectedSha256 = expectedSha,
                    signingMode = "production",
                    packageId = app.packageName,
                    expectedSigningCertSha256 = "e".repeat(64)
                )

                val firstRes = AppUpdateManager.downloadApkWithProgress(
                    context = app,
                    release = release,
                    onProgress = {},
                    allowLocalHttpForTesting = true
                )
                assertTrue("First truncated attempt should fail", firstRes.isFailure)
                val partFile = File(File(app.cacheDir, "updates"), "JH-GetWay-v1.0.15-release.apk.part")
                assertTrue("Partial .part file should be preserved for resume", partFile.exists())
                assertEquals(400L, partFile.length())

                val progressHistory = mutableListOf<Int>()
                val secondRes = AppUpdateManager.downloadApkWithProgress(
                    context = app,
                    release = release,
                    onProgress = { progressHistory += it },
                    allowLocalHttpForTesting = true
                )
                assertTrue("Second attempt should resume and succeed", secondRes.isSuccess)
                assertEquals("bytes=400-", receivedRangeHeaders[1])
                val downloadedFile = secondRes.getOrThrow()
                assertEquals(fullPayload.size.toLong(), downloadedFile.length())
                assertEquals(expectedSha, sha256Hex(downloadedFile.readBytes()))
                assertEquals(100, progressHistory.last())
            } finally {
                server.stop(0)
            }
        }
    }

    @Test
    fun settingsScreen_showsManualReleasesFallbackOnRateLimit() {
        runBlocking(Dispatchers.IO) {
            app.preferences.setGithubUpdateRepo("jhsoft/jh-getway")
        }
        val now = System.currentTimeMillis()
        SharedPreferencesUpdateCacheStore(app).put(
            "https://api.github.com|jhsoft/jh-getway|debug=false",
            UpdateCacheEntry(
                lastCheckedAtMs = now,
                retryAfterUntilMs = now + 180_000L
            )
        )

        composeRule.setContent {
            CompositionLocalProvider(LocalStrings provides EnglishStrings) {
                JhGetWayTheme {
                    SettingsScreen(viewModel = viewModel, onNavigate = {})
                }
            }
        }
        waitUntilCondition {
            viewModel.effectiveGithubUpdateRepo() == "jhsoft/jh-getway"
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("settings_check_updates_btn"))
        composeRule.onNodeWithTag("settings_check_updates_btn").performClick()

        waitUntilCondition {
            viewModel.appUpdateUiState.value.rateLimitResetAtMs != null
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("settings_open_releases_fallback_btn"))
        composeRule.onNodeWithTag("settings_open_releases_fallback_btn")
            .assertIsDisplayed()
            .assertTextContains(EnglishStrings.openOfficialReleasesAction, substring = true)
    }

    @Test
    fun existingGatewayPrivateLanHttp_validationAndHealthCheckerRespectToggle() {
        val lanUrl = "http://192.168.1.50:8787"
        assertFalse(NetworkAddressClassifier.validateRemoteGatewayUrl(lanUrl, allowExistingPrivateHttp = false).valid)
        assertTrue(NetworkAddressClassifier.validateRemoteGatewayUrl(lanUrl, allowExistingPrivateHttp = true).valid)
        assertFalse(
            NetworkAddressClassifier.validateRemoteGatewayUrl("http://8.8.8.8:8787", allowExistingPrivateHttp = true).valid
        )

        val blockedHealth = runBlocking {
            OkHttpRemoteHealthChecker().checkHealth(
                url = lanUrl,
                bearerToken = "",
                timeoutSec = 5,
                allowPrivateLocalHttp = false
            )
        }
        assertEquals(GatewayRunStatus.ERROR, blockedHealth.status)
        assertTrue(
            blockedHealth.errorMessage?.contains("HTTPS", ignoreCase = true) == true ||
                blockedHealth.errorMessage?.contains("private", ignoreCase = true) == true
        )
    }

    @Test
    fun gatewayScreen_linksToValidatedGatewayConfig_withoutLegacyRawModeForm() {
        var openedConfig = false
        composeRule.setContent {
            CompositionLocalProvider(LocalStrings provides EnglishStrings) {
                JhGetWayTheme {
                    GatewayScreen(
                        viewModel = viewModel,
                        onOpenGatewayConfig = { openedConfig = true }
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onAllNodesWithTag("gateway_allow_lan_switch").assertCountEquals(0)
        composeRule.onNodeWithTag("gateway_screen_list")
            .performScrollToNode(hasTestTag("gateway_open_config_btn"))
        composeRule.onNodeWithTag("gateway_open_config_btn")
            .assertIsDisplayed()
            .performClick()
        assertTrue(openedConfig)
    }

    @Test
    fun saveAndApplyGatewayConfig_switchingToLocalWhileStoppedUpdatesRuntimeAddress() {
        val remoteDraft = GatewayConfigDraft(
            name = "Remote GW",
            mode = GatewayMode.REMOTE,
            host = "127.0.0.1",
            port = 8787,
            remoteUrl = "https://gw.example.com",
            authRequired = true,
            timeoutSec = 60,
            maxRequestKb = 2048,
            streamingEnabled = true,
            rateLimitPerMin = 60,
            loggingLevel = LoggingLevel.METADATA_ONLY,
            allowLanExposure = false,
            allowExistingLocalHttp = false,
            remoteAuthTokenInput = "",
            clearSavedRemoteToken = false
        )
        runBlocking(Dispatchers.IO) {
            val remoteSave = viewModel.saveAndApplyGatewayConfig(remoteDraft)
            assertTrue(remoteSave.isSuccess)
        }
        waitUntilCondition {
            viewModel.settings.value.gatewayMode == GatewayMode.REMOTE &&
                viewModel.gatewayRuntime.value.mode == GatewayMode.REMOTE &&
                viewModel.gatewayRuntime.value.boundAddress == "https://gw.example.com"
        }

        val localResult = runBlocking(Dispatchers.IO) {
            viewModel.saveAndApplyGatewayConfig(
                remoteDraft.copy(
                    mode = GatewayMode.LOCAL,
                    port = 19876,
                    remoteUrl = ""
                )
            )
        }
        assertTrue(localResult.isSuccess)
        waitUntilCondition {
            viewModel.gatewayRuntime.value.mode == GatewayMode.LOCAL &&
                viewModel.gatewayRuntime.value.boundAddress == "http://127.0.0.1:19876"
        }
        assertEquals(GatewayRunStatus.STOPPED, viewModel.gatewayRuntime.value.status)
    }

    @Test
    fun githubUpdater_missingManifestErrorPreservesCachedReleaseAndReleasesUrl() {
        runBlocking {
            val relSha = "c".repeat(64)
            val certSha = "d".repeat(64)
            var serveManifest = true
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            val port = server.address.port

            server.createContext("/repos/jhsoft/jh-getway/releases/latest") { exchange ->
                val manifestAsset = if (serveManifest) {
                    """,{"name": "update-manifest.json", "browser_download_url": "http://127.0.0.1:$port/dl/manifest.json", "size": 300}"""
                } else {
                    ""
                }
                val body = """
                    {
                      "tag_name": "v1.0.20",
                      "name": "JH GetWay v1.0.20",
                      "body": "Release notes",
                      "html_url": "https://github.com/jhsoft/jh-getway/releases/tag/v1.0.20",
                      "assets": [
                        {"name": "JH-GetWay-v1.0.20-release.apk", "browser_download_url": "http://127.0.0.1:$port/dl/release.apk", "size": 2048}$manifestAsset
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
                      "versionCode": 120,
                      "versionName": "1.0.20",
                      "tag": "v1.0.20",
                      "packageId": "com.aistudio.jhgetway.iffndf",
                      "apkName": "JH-GetWay-v1.0.20-release.apk",
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
                val store = InMemoryUpdateCacheStore()
                val base = "http://127.0.0.1:$port"
                val okRes = AppUpdateManager.checkForUpdate(
                    repoInput = "jhsoft/jh-getway",
                    currentVersionCode = 110,
                    currentVersionName = "1.0.10",
                    apiBaseUrl = base,
                    expectedPackageId = "com.aistudio.jhgetway.iffndf",
                    cacheStore = store
                )
                assertTrue(okRes is AppUpdateCheckResult.UpdateAvailable)

                serveManifest = false
                val errRes = AppUpdateManager.checkForUpdate(
                    repoInput = "jhsoft/jh-getway",
                    currentVersionCode = 110,
                    currentVersionName = "1.0.10",
                    apiBaseUrl = base,
                    expectedPackageId = "com.aistudio.jhgetway.iffndf",
                    cacheStore = store,
                    forceRefresh = true
                )
                assertTrue(errRes is AppUpdateCheckResult.Error)
                val err = errRes as AppUpdateCheckResult.Error
                assertNotNull("Valid cached release must be preserved on manifest error", err.cachedRelease)
                assertEquals("https://github.com/jhsoft/jh-getway/releases/tag/v1.0.20", err.releasesUrl)
            } finally {
                server.stop(0)
            }
        }
    }
}
