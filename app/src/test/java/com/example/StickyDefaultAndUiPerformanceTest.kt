package com.example

import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.core.i18n.EnglishStrings
import com.example.core.i18n.LocalStrings
import com.example.core.security.CryptoManager
import com.example.data.local.*
import com.example.gateway.OpenCodeSelectorIds
import com.example.gateway.SmartRouterEngine
import com.example.network.ChatMessagePayload
import com.example.network.ProviderHttpClient
import com.example.ui.GatewayViewModel
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.PlaygroundScreen
import com.example.ui.theme.JhGetWayTheme
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StickyDefaultAndUiPerformanceTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var app: JhGetWayApplication
    private val upstreamServers = mutableListOf<EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>>()

    @Before
    fun setUp() {
        CryptoManager.resetTestKeyProvider()
        app = ApplicationProvider.getApplicationContext()
        runBlocking(Dispatchers.IO) {
            app.gatewayServer.stop()
            app.preferences.clearAll()
            app.database.clearAllTables()
        }
    }

    @After
    fun tearDown() {
        upstreamServers.forEach { runCatching { it.stop(50, 200) } }
        upstreamServers.clear()
        runBlocking(Dispatchers.IO) {
            app.gatewayServer.stop()
            app.preferences.clearAll()
            app.database.clearAllTables()
        }
        CryptoManager.resetTestKeyProvider()
    }

    private suspend fun startMockServer(
        handler: suspend io.ktor.server.routing.RoutingContext.(JSONObject) -> Unit
    ): String {
        val srv = embeddedServer(CIO, configure = { connector { host = "127.0.0.1"; port = 0 } }) {
            routing {
                post("/v1/chat/completions") {
                    val reqJson = runCatching { JSONObject(call.receiveText()) }.getOrElse { JSONObject() }
                    handler(reqJson)
                }
            }
        }
        srv.start(wait = false)
        upstreamServers += srv
        val port = srv.engine.resolvedConnectors().first().port
        return "http://127.0.0.1:$port"
    }

    @Test
    fun stickyDefaultModel_staysPinnedAcrossNormalAndCodingTurnsUntilRateLimitedAndReturnsAfterRecovery() = runBlocking {
        var primaryRateLimited = false
        val primaryModelsSeen = CopyOnWriteArrayList<String>()
        val backupModelsSeen = CopyOnWriteArrayList<String>()

        val primaryUrl = startMockServer { req ->
            primaryModelsSeen += req.optString("model")
            if (primaryRateLimited) {
                call.respondText(
                    """{"error":{"message":"Rate limit exceeded"}}""",
                    ContentType.Application.Json,
                    HttpStatusCode.TooManyRequests
                )
            } else {
                call.respondText(
                    """{"id":"p1","model":"${req.optString("model")}","choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"primary-ok-${primaryModelsSeen.size}"}}]}""",
                    ContentType.Application.Json
                )
            }
        }

        val backupUrl = startMockServer { req ->
            backupModelsSeen += req.optString("model")
            call.respondText(
                """{"id":"p2","model":"${req.optString("model")}","choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"backup-ok-${backupModelsSeen.size}"}}]}""",
                ContentType.Application.Json
            )
        }

        val db = app.database
        // Primary default has lower priority & worse latency than backup coder, yet must stay pinned!
        db.providerDao().upsert(
            ProviderEntity(
                id = "p-default",
                name = "Connected Default API",
                baseUrl = "$primaryUrl/v1",
                apiFormat = ApiFormat.OPENAI,
                defaultModel = "my-custom-fast",
                priority = 5,
                lastLatencyMs = 240,
                status = ProviderStatus.CONNECTED,
                enabled = true
            )
        )
        db.providerDao().upsert(
            ProviderEntity(
                id = "p-coder",
                name = "Backup Coder API",
                baseUrl = "$backupUrl/v1",
                apiFormat = ApiFormat.OPENAI,
                defaultModel = "qwen-2.5-coder",
                priority = 1,
                lastLatencyMs = 35,
                status = ProviderStatus.CONNECTED,
                enabled = true
            )
        )
        db.apiKeyDao().upsert(ApiKeyEntity(id = "k-def", providerId = "p-default", label = "KD", encryptedKey = CryptoManager.encrypt("sk-def"), maskedPreview = "def", enabled = true, isDefault = true))
        db.apiKeyDao().upsert(ApiKeyEntity(id = "k-cod", providerId = "p-coder", label = "KC", encryptedKey = CryptoManager.encrypt("sk-cod"), maskedPreview = "cod", enabled = true, isDefault = true))
        db.modelDao().upsertAll(
            listOf(
                ModelEntity(id = "p-default::my-custom-fast", modelId = "my-custom-fast", displayName = "My Custom Fast", providerId = "p-default", providerName = "Connected Default API", isDefault = true, supportsChat = true, supportsTools = true, enabled = true),
                ModelEntity(id = "p-coder::qwen-2.5-coder", modelId = "qwen-2.5-coder", displayName = "Qwen Coder", providerId = "p-coder", providerName = "Backup Coder API", isDefault = false, supportsChat = true, supportsTools = true, enabled = true)
            )
        )

        val settings = GatewaySettingsState(
            defaultModelId = "my-custom-fast",
            defaultProviderId = "p-default",
            defaultRoutingMode = RoutingMode.AUTOMATIC,
            preferConfiguredDefaultInAuto = true,
            autoAllowPaidModels = true,
            fallbackEnabled = true,
            maxRetries = 2,
            localNetworkAccessAllowed = true
        )
        val router = SmartRouterEngine(db, ProviderHttpClient())

        // Turn 1: casual greeting -> pinned default
        val r1 = router.executeRoutedChat(
            requestedModelOrAlias = OpenCodeSelectorIds.STABLE_AUTO_ID,
            messages = listOf(ChatMessagePayload("user", "hello")),
            settings = settings
        )
        assertTrue(r1.execution.success)
        assertEquals("my-custom-fast", r1.decision.selectedModel)

        // Turn 2: strong coding prompt -> must STILL stay on pinned default, NOT switch per message!
        val r2 = router.executeRoutedChat(
            requestedModelOrAlias = OpenCodeSelectorIds.STABLE_AUTO_ID,
            messages = listOf(ChatMessagePayload("user", "Refactor SmartRouter.kt ```kotlin\nfun route() {}\n``` with read and bash tools")),
            settings = settings
        )
        assertTrue(r2.execution.success)
        assertEquals("my-custom-fast", r2.decision.selectedModel)
        assertEquals(0, backupModelsSeen.size)

        // Turn 3: Primary hits 429 rate limit -> fast failover to Backup Coder
        primaryRateLimited = true
        val r3 = router.executeRoutedChat(
            requestedModelOrAlias = OpenCodeSelectorIds.STABLE_AUTO_ID,
            messages = listOf(ChatMessagePayload("user", "fix failing test")),
            settings = settings
        )
        assertTrue(r3.execution.success)
        assertEquals("qwen-2.5-coder", r3.decision.selectedModel)
        assertEquals(2, r3.decision.attemptCount)

        // Turn 4: Primary recovers once cooldown expires -> next message returns to pinned default
        primaryRateLimited = false
        db.providerDao().upsert(
            db.providerDao().getById("p-default")!!.copy(
                status = ProviderStatus.CONNECTED,
                cooldownUntilMs = System.currentTimeMillis() - 1_000L
            )
        )
        db.apiKeyDao().upsert(
            db.apiKeyDao().getAllOnce().first { it.id == "k-def" }.copy(
                status = KeyStatus.ACTIVE,
                cooldownUntilMs = System.currentTimeMillis() - 1_000L
            )
        )
        SmartRouterEngine.refreshExpiredCooldownStatuses(db)

        val r4 = router.executeRoutedChat(
            requestedModelOrAlias = OpenCodeSelectorIds.STABLE_AUTO_ID,
            messages = listOf(ChatMessagePayload("user", "next question")),
            settings = settings
        )
        assertTrue(r4.execution.success)
        assertEquals("my-custom-fast", r4.decision.selectedModel)
        assertEquals(1, r4.decision.attemptCount)
    }

    @Test
    fun virtualAuto_respectsBoundedRetryCap_max3AttemptsWhenMaxRetriesIs2() = runBlocking {
        val totalHits = AtomicInteger(0)
        val failUrl = startMockServer {
            totalHits.incrementAndGet()
            call.respondText(
                """{"error":{"message":"Upstream 503"}}""",
                ContentType.Application.Json,
                HttpStatusCode.ServiceUnavailable
            )
        }

        val db = app.database
        (1..6).forEach { idx ->
            val pid = "p-$idx"
            db.providerDao().upsert(
                ProviderEntity(
                    id = pid,
                    name = "FailProvider $idx",
                    baseUrl = "$failUrl/v1",
                    apiFormat = ApiFormat.OPENAI,
                    defaultModel = "fail-m-$idx",
                    priority = idx,
                    status = ProviderStatus.CONNECTED,
                    enabled = true
                )
            )
            db.apiKeyDao().upsert(
                ApiKeyEntity(
                    id = "k-$idx",
                    providerId = pid,
                    label = "K$idx",
                    encryptedKey = CryptoManager.encrypt("sk-$idx"),
                    maskedPreview = "$idx",
                    enabled = true,
                    isDefault = true
                )
            )
            db.modelDao().upsert(
                ModelEntity(
                    id = "$pid::fail-m-$idx",
                    modelId = "fail-m-$idx",
                    displayName = "Fail $idx",
                    providerId = pid,
                    providerName = "FailProvider $idx",
                    supportsChat = true,
                    enabled = true
                )
            )
        }

        val router = SmartRouterEngine(db, ProviderHttpClient())
        val res = router.executeRoutedChat(
            requestedModelOrAlias = OpenCodeSelectorIds.STABLE_AUTO_ID,
            messages = listOf(ChatMessagePayload("user", "hello")),
            settings = GatewaySettingsState(
                maxRetries = 2,
                fallbackEnabled = true,
                autoAllowPaidModels = true,
                localNetworkAccessAllowed = true
            )
        )
        assertFalse(res.execution.success)
        assertEquals("Default maxRetries=2 should stop at 3 attempts instead of 6", 3, totalHits.get())
    }

    @Test
    fun playgroundAuto_usesConnectedDefaultPaidOrUnknownModelAndRespectsCustomAlias() = runBlocking {
        val seenModels = CopyOnWriteArrayList<String>()
        val url = startMockServer { req ->
            val model = req.optString("model")
            seenModels += model
            call.respondText(
                """{"id":"pg","model":"$model","choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"reply-from-$model"}}]}""",
                ContentType.Application.Json
            )
        }

        val db = app.database
        app.preferences.updateLocalAiSettings(
            policy = LocalRoutingPolicy.LOCAL_CLOUD_FALLBACK,
            allowCloudFallback = true,
            localNetworkAllowed = true
        )
        app.preferences.setDefaultModelAndProvider("claude-sonnet-4", "p-paid")
        app.preferences.setSaveChatHistory(false)

        db.providerDao().upsert(
            ProviderEntity(
                id = "p-paid",
                name = "Connected Cloud",
                baseUrl = "$url/v1",
                apiFormat = ApiFormat.OPENAI,
                defaultModel = "claude-sonnet-4",
                priority = 1,
                status = ProviderStatus.CONNECTED,
                enabled = true
            )
        )
        db.apiKeyDao().upsert(
            ApiKeyEntity(
                id = "k-paid",
                providerId = "p-paid",
                label = "Primary",
                encryptedKey = CryptoManager.encrypt("sk-paid"),
                maskedPreview = "paid",
                enabled = true,
                isDefault = true
            )
        )
        db.modelDao().upsertAll(
            listOf(
                ModelEntity(
                    id = "p-paid::claude-sonnet-4",
                    modelId = "claude-sonnet-4",
                    displayName = "Claude Sonnet 4",
                    providerId = "p-paid",
                    providerName = "Connected Cloud",
                    accessTier = AccessTier.PAID,
                    freeTierVerified = false,
                    isDefault = true,
                    supportsChat = true,
                    enabled = true
                ),
                ModelEntity(
                    id = "p-paid::fast-helper",
                    modelId = "fast-helper",
                    alias = "jh-fast",
                    displayName = "Fast Helper",
                    providerId = "p-paid",
                    providerName = "Connected Cloud",
                    accessTier = AccessTier.PAID,
                    freeTierVerified = false,
                    supportsChat = true,
                    enabled = true
                )
            )
        )

        val vm = GatewayViewModel(app)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        delay(50)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        var validationError: String? = null
        vm.sendPlaygroundPrompt(
            prompt = "Hello Playground",
            systemPrompt = "",
            modelIdOrAlias = "",
            explicitProviderId = null,
            temperature = 0.5f,
            topP = 0.9f,
            maxTokens = 256,
            stopSequences = emptyList(),
            jsonOutput = false,
            streaming = false,
            isAutoMode = true,
            onValidationError = { validationError = it }
        )

        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline && vm.ephemeralMessages.value.size < 2 && validationError == null) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            delay(30)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertNull("Auto Playground should not fail with free-only validation error", validationError)
        val assistant1 = vm.ephemeralMessages.value.lastOrNull { it.role == "assistant" }
        assertNotNull(assistant1)
        assertEquals("reply-from-claude-sonnet-4", assistant1?.content)
        assertEquals("claude-sonnet-4", assistant1?.modelId)

        // Now test custom alias entered in Playground (isAutoMode = false, explicitProviderId = null)
        vm.sendPlaygroundPrompt(
            prompt = "Use custom alias",
            systemPrompt = "",
            modelIdOrAlias = "jh-fast",
            explicitProviderId = null,
            temperature = 0.5f,
            topP = 0.9f,
            maxTokens = 256,
            stopSequences = emptyList(),
            jsonOutput = false,
            streaming = false,
            isAutoMode = false,
            onValidationError = { validationError = it }
        )
        val deadline2 = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline2 && vm.ephemeralMessages.value.size < 4 && validationError == null) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            delay(30)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertNull(validationError)
        assertEquals("fast-helper", seenModels.lastOrNull())
    }

    @Test
    fun largeScale_50ProvidersAnd4550Models_rendersDashboardAndPlaygroundQuicklyWithAccurateSqlCounts() {
        runBlocking(Dispatchers.IO) {
            val db = app.database
            val providers = (1..50).map { i ->
                ProviderEntity(
                    id = "prov-$i",
                    name = "Provider $i",
                    baseUrl = "https://api$i.example.com/v1",
                    apiFormat = ApiFormat.OPENAI,
                    defaultModel = "model-$i-1",
                    priority = i,
                    status = when {
                        i <= 40 -> ProviderStatus.CONNECTED
                        i <= 47 -> ProviderStatus.ERROR
                        else -> ProviderStatus.RATE_LIMITED
                    },
                    enabled = true
                )
            }
            providers.forEach { db.providerDao().upsert(it) }

            val allModels = ArrayList<ModelEntity>(4550)
            var count = 0
            for (pIdx in 1..50) {
                for (mIdx in 1..91) {
                    count++
                    val isDisabled = count <= 20
                    allModels += ModelEntity(
                        id = "prov-$pIdx::model-$pIdx-$mIdx",
                        modelId = "model-$pIdx-$mIdx",
                        displayName = "Model $pIdx-$mIdx",
                        providerId = "prov-$pIdx",
                        providerName = "Provider $pIdx",
                        isLocal = false,
                        enabled = !isDisabled,
                        supportsChat = true
                    )
                }
            }
            db.modelDao().upsertAll(allModels)

            val summary = db.modelDao().observeModelSummaryCounts().first()
            assertEquals(4550, summary.totalCount)
            assertEquals(4530, summary.enabledCount)
            assertEquals(0, summary.localCount)
            assertEquals(4550, summary.cloudCount)
        }

        val vm = GatewayViewModel(app)
        composeRule.setContent {
            CompositionLocalProvider(LocalStrings provides EnglishStrings) {
                JhGetWayTheme {
                    DashboardScreen(viewModel = vm, onNavigate = {})
                }
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            vm.providers.value.size == 50 && vm.modelSummaryCounts.value.totalCount == 4550
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("dashboard_list")
            .performScrollToNode(hasTestTag("dashboard_provider_card"))
        composeRule.onNodeWithTag("dashboard_provider_card").assertIsDisplayed()
        composeRule.onNodeWithText("50").assertExists()

        composeRule.onNodeWithTag("dashboard_list")
            .performScrollToNode(hasTestTag("dashboard_model_card"))
        composeRule.onNodeWithTag("dashboard_model_card").assertIsDisplayed()
        composeRule.onNodeWithText("4550").assertExists()
        composeRule.onNodeWithText("4530").assertExists()
    }

    @Test
    fun playgroundWith50ProvidersAnd4550Models_displaysAutoChipWithoutWarningWhenPaidDefaultConnected() {
        runBlocking(Dispatchers.IO) {
            val db = app.database
            app.preferences.setDefaultModelAndProvider("model-1-1", "prov-1")
            db.providerDao().upsert(
                ProviderEntity(
                    id = "prov-1",
                    name = "Provider 1",
                    baseUrl = "https://api1.example.com/v1",
                    apiFormat = ApiFormat.OPENAI,
                    defaultModel = "model-1-1",
                    priority = 1,
                    status = ProviderStatus.CONNECTED,
                    enabled = true
                )
            )
            db.apiKeyDao().upsert(
                ApiKeyEntity(
                    id = "key-1",
                    providerId = "prov-1",
                    label = "K1",
                    encryptedKey = CryptoManager.encrypt("sk-test-1"),
                    maskedPreview = "test",
                    enabled = true,
                    isDefault = true
                )
            )
            val batch = (1..200).map { i ->
                ModelEntity(
                    id = "prov-1::model-1-$i",
                    modelId = "model-1-$i",
                    displayName = "Model 1-$i",
                    providerId = "prov-1",
                    providerName = "Provider 1",
                    accessTier = AccessTier.PAID,
                    freeTierVerified = false,
                    enabled = true,
                    supportsChat = true
                )
            }
            db.modelDao().upsertAll(batch)
        }

        val vm = GatewayViewModel(app)
        composeRule.setContent {
            CompositionLocalProvider(LocalStrings provides EnglishStrings) {
                JhGetWayTheme {
                    PlaygroundScreen(viewModel = vm, onNavigate = {})
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("playground_model_selector_chip").assertIsDisplayed()
        composeRule.onNodeWithText(EnglishStrings.autoShortLabel).assertIsDisplayed()
        // Should NOT show "No eligible free-tier models" false error when user's default paid model is active!
        assertEquals(0, composeRule.onAllNodes(androidx.compose.ui.test.hasText(EnglishStrings.noEligibleFreeModels)).fetchSemanticsNodes().size)
    }
}
