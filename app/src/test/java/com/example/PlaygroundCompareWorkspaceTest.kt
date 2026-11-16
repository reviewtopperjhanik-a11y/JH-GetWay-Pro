package com.example

import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.core.i18n.EnglishStrings
import com.example.core.i18n.LocalStrings
import com.example.core.security.CryptoManager
import com.example.data.local.*
import com.example.ui.CompareModelEntry
import com.example.ui.CompareRequestStatus
import com.example.ui.CompareWorkspaceState
import com.example.ui.GatewayViewModel
import com.example.ui.screens.PlaygroundCompareWorkspace
import com.example.ui.screens.PlaygroundScreen
import com.example.ui.theme.JhGetWayTheme
import com.example.ui.theme.ThemeMode
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
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlaygroundCompareWorkspaceTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var app: JhGetWayApplication
    private val servers = mutableListOf<EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>>()

    @Before
    fun setUp() {
        CryptoManager.resetTestKeyProvider()
        app = ApplicationProvider.getApplicationContext()
        runBlocking(Dispatchers.IO) {
            app.preferences.clearAll()
            app.database.clearAllTables()
        }
    }

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.stop(100, 300) } }
        servers.clear()
        runBlocking(Dispatchers.IO) {
            app.gatewayServer.stop()
            app.database.clearAllTables()
            app.preferences.clearAll()
        }
        CryptoManager.resetTestKeyProvider()
    }

    private fun startMockServer(configure: io.ktor.server.routing.Routing.() -> Unit): String {
        val srv = embeddedServer(CIO, configure = {
            connector {
                host = "127.0.0.1"
                port = 0
            }
            reuseAddress = true
        }) {
            routing { configure() }
        }
        srv.start(wait = false)
        servers += srv
        val port = runBlocking { srv.engine.resolvedConnectors().first().port }
        java.net.Socket("127.0.0.1", port).use { }
        return "http://127.0.0.1:$port"
    }

    private suspend fun insertProviderWithModel(
        providerId: String,
        providerName: String,
        baseUrl: String,
        modelId: String,
        displayName: String,
        priority: Int = 1
    ): ModelEntity {
        app.database.providerDao().upsert(
            ProviderEntity(
                id = providerId,
                name = providerName,
                type = ProviderType.CUSTOM,
                baseUrl = baseUrl,
                apiFormat = ApiFormat.OPENAI,
                defaultModel = modelId,
                priority = priority,
                enabled = true,
                status = ProviderStatus.CONNECTED,
                lastLatencyMs = 100L
            )
        )
        app.database.apiKeyDao().upsert(
            ApiKeyEntity(
                providerId = providerId,
                label = "$providerName Key",
                encryptedKey = CryptoManager.encrypt("sk-test-$providerId"),
                maskedPreview = "sk-t••••$providerId",
                enabled = true,
                isDefault = true
            )
        )
        val model = ModelEntity(
            id = "$providerId::$modelId",
            modelId = modelId,
            displayName = displayName,
            providerId = providerId,
            providerName = providerName,
            accessTier = AccessTier.PAID,
            inputPricePer1M = 2.0,
            outputPricePer1M = 6.0,
            enabled = true,
            supportsChat = true,
            supportsStreaming = true
        )
        app.database.modelDao().upsertAll(listOf(model))
        return model
    }

    private fun sampleEntries(): List<CompareModelEntry> = listOf(
        CompareModelEntry(
            entryKey = "k_gpt",
            modelId = "gpt-4o",
            displayName = "GPT-4o",
            providerId = "p_oai",
            providerName = "OpenAI",
            supportsStreaming = true,
            status = CompareRequestStatus.COMPLETED,
            responseText = "# GPT-4o Answer\n\nAlpha **response** with `inlineCode()`.\n\n- Bullet one\n- Bullet two",
            latencyMs = 420L,
            ttftMs = 95L,
            inputTokens = 18,
            outputTokens = 44,
            totalTokens = 62,
            usageEstimated = false,
            estimatedCostUsd = 0.0012,
            actualCostUsd = null,
            finishReason = "stop",
            httpStatus = 200,
            errorMessage = null
        ),
        CompareModelEntry(
            entryKey = "k_claude",
            modelId = "claude-3.7-sonnet",
            displayName = "Claude 3.7 Sonnet",
            providerId = "p_ant",
            providerName = "Anthropic",
            supportsStreaming = true,
            status = CompareRequestStatus.COMPLETED,
            responseText = "## Claude Reasoning\n\n> Quoted insight from Claude.\n\n```kotlin\nfun compare() = \"Beta response\"\n```\n\n| Metric | Value |\n| --- | --- |\n| Speed | Fast |",
            latencyMs = 610L,
            ttftMs = 140L,
            inputTokens = 20,
            outputTokens = 55,
            totalTokens = 75,
            usageEstimated = true,
            estimatedCostUsd = 0.0024,
            actualCostUsd = 0.0029,
            finishReason = "end_turn",
            httpStatus = 200,
            errorMessage = null
        ),
        CompareModelEntry(
            entryKey = "k_gemini",
            modelId = "gemini-2.5-flash",
            displayName = "Gemini 2.5 Flash",
            providerId = "p_gem",
            providerName = "Google",
            supportsStreaming = true,
            status = CompareRequestStatus.COMPLETED,
            responseText = (1..45).joinToString("\n") { idx -> "Gemini detail line #$idx for scrolling check." },
            latencyMs = 310L,
            ttftMs = 70L,
            inputTokens = 16,
            outputTokens = 120,
            totalTokens = 136,
            usageEstimated = false,
            estimatedCostUsd = 0.0005,
            actualCostUsd = null,
            finishReason = "stop",
            httpStatus = 200,
            errorMessage = null
        )
    )

    private fun drainMainUntil(timeoutMs: Long = 5_000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            composeRule.waitForIdle()
            if (predicate()) return
            Thread.sleep(40)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
        assertTrue("Condition timed out after ${timeoutMs}ms", predicate())
    }

    @Test
    fun quickNavigationAtTop_swipeGesturesAndPrevNextStaySynchronizedWithSingleCardVisible() {
        var state by mutableStateOf(
            CompareWorkspaceState(
                selectedModelKeys = listOf("k_gpt", "k_claude", "k_gemini"),
                prompt = "Explain compare mode",
                entries = sampleEntries(),
                activeEntryKey = "k_gpt"
            )
        )

        composeRule.setContent {
            CompositionLocalProvider(LocalStrings provides EnglishStrings) {
                JhGetWayTheme(themeMode = ThemeMode.LIGHT) {
                    Box(modifier = Modifier.width(412.dp).height(760.dp)) {
                        PlaygroundCompareWorkspace(
                            compareState = state,
                            availableModels = emptyList(),
                            currency = "USD",
                            onToggleModel = {},
                            onClearSelection = {},
                            onSelectEntry = { key -> state = state.copy(activeEntryKey = key) },
                            onToggleDetails = { key ->
                                val next = if (key in state.expandedDetailsKeys) state.expandedDetailsKeys - key else state.expandedDetailsKeys + key
                                state = state.copy(expandedDetailsKeys = next)
                            },
                            onRetryModel = {},
                            onStopModel = {},
                            onStopAll = {},
                            onShowBanner = {}
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // Verify Top-to-Bottom layout order: Quick Nav above Progress above Pager above Prev/Next
        val navTop = composeRule.onNodeWithTag("compare_quick_nav").fetchSemanticsNode().boundsInRoot.top
        val progTop = composeRule.onNodeWithTag("compare_progress_indicator").fetchSemanticsNode().boundsInRoot.top
        val pagerTop = composeRule.onNodeWithTag("compare_pager").fetchSemanticsNode().boundsInRoot.top
        val footerTop = composeRule.onNodeWithTag("compare_prev_btn").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Quick nav must be above progress indicator", navTop < progTop)
        assertTrue("Progress indicator must be above response pager", progTop < pagerTop)
        assertTrue("Pager must be above Previous/Next bar", pagerTop < footerTop)

        // Initial state: 1 of 3, Previous disabled, Next enabled, GPT-4o card active
        composeRule.onNodeWithTag("compare_progress_text").assertTextEquals("1 of 3")
        composeRule.onNodeWithTag("compare_prev_btn").assertIsNotEnabled()
        composeRule.onNodeWithTag("compare_next_btn").assertIsEnabled()
        composeRule.onNodeWithTag("compare_card_k_gpt").assertIsDisplayed()
        composeRule.onNodeWithText("GPT-4o Answer", substring = true).assertIsDisplayed()

        // Tap top Quick Model Navigation item for Gemini (page 3)
        composeRule.onNodeWithTag("compare_nav_k_gemini").performClick()
        drainMainUntil { state.activeEntryKey == "k_gemini" }
        composeRule.onNodeWithTag("compare_progress_text").assertTextEquals("3 of 3")
        composeRule.onNodeWithTag("compare_prev_btn").assertIsEnabled()
        composeRule.onNodeWithTag("compare_next_btn").assertIsNotEnabled()
        composeRule.onNodeWithText("Gemini detail line #1", substring = true).assertIsDisplayed()

        // Scroll long Gemini response vertically to line #45
        composeRule.onNodeWithTag("compare_response_scroll_k_gemini").performScrollToIndexOrBottom()
        composeRule.onNodeWithText("Gemini detail line #45", substring = true).assertExists()

        // Swipe right from Model 3 -> Model 2 (Claude)
        composeRule.onNodeWithTag("compare_pager").performTouchInput { swipeRight() }
        drainMainUntil { state.activeEntryKey == "k_claude" }
        composeRule.onNodeWithTag("compare_progress_text").assertTextEquals("2 of 3")
        composeRule.onNodeWithText("Claude Reasoning", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("compare_code_block_2").performScrollTo().assertIsDisplayed()

        // Swipe right from Model 2 -> Model 1 (GPT-4o)
        composeRule.onNodeWithTag("compare_pager").performTouchInput { swipeRight() }
        drainMainUntil { state.activeEntryKey == "k_gpt" }
        composeRule.onNodeWithTag("compare_progress_text").assertTextEquals("1 of 3")
        composeRule.onNodeWithText("GPT-4o Answer", substring = true).assertIsDisplayed()

        // Previous/Next supporting buttons stay synced
        composeRule.onNodeWithTag("compare_next_btn").performClick()
        drainMainUntil { state.activeEntryKey == "k_claude" }
        composeRule.onNodeWithTag("compare_progress_text").assertTextEquals("2 of 3")

        composeRule.onNodeWithTag("compare_prev_btn").performClick()
        drainMainUntil { state.activeEntryKey == "k_gpt" }
        composeRule.onNodeWithTag("compare_progress_text").assertTextEquals("1 of 3")
    }

    private fun SemanticsNodeInteraction.performScrollToIndexOrBottom() {
        performTouchInput {
            swipeUp(startY = bottom * 0.85f, endY = top + 20f)
            swipeUp(startY = bottom * 0.85f, endY = top + 20f)
        }
        composeRule.waitForIdle()
    }

    @Test
    fun detailsAndCopyResponse_stayIndependentAccurateAndNeverLeakAcrossModels() {
        var state by mutableStateOf(
            CompareWorkspaceState(
                selectedModelKeys = listOf("k_gpt", "k_claude", "k_empty"),
                prompt = "Compare accuracy",
                entries = sampleEntries().take(2) + CompareModelEntry(
                    entryKey = "k_empty",
                    modelId = "empty-model",
                    displayName = "Empty Model",
                    providerId = "p_empty",
                    providerName = "Custom",
                    status = CompareRequestStatus.COMPLETED,
                    responseText = ""
                ),
                activeEntryKey = "k_gpt"
            )
        )
        val banners = mutableListOf<String>()

        composeRule.setContent {
            CompositionLocalProvider(LocalStrings provides EnglishStrings) {
                JhGetWayTheme(themeMode = ThemeMode.LIGHT) {
                    Box(modifier = Modifier.width(412.dp).height(760.dp)) {
                        PlaygroundCompareWorkspace(
                            compareState = state,
                            availableModels = emptyList(),
                            currency = "USD",
                            onToggleModel = {},
                            onClearSelection = {},
                            onSelectEntry = { key -> state = state.copy(activeEntryKey = key) },
                            onToggleDetails = { key ->
                                val next = if (key in state.expandedDetailsKeys) state.expandedDetailsKeys - key else state.expandedDetailsKeys + key
                                state = state.copy(expandedDetailsKeys = next)
                            },
                            onRetryModel = {},
                            onStopModel = {},
                            onStopAll = {},
                            onShowBanner = { banners += it }
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // Details panel collapsed by default
        composeRule.onNodeWithTag("compare_details_panel_k_gpt").assertDoesNotExist()

        // Expand GPT-4o details: verify real metrics & "Not available" for missing actualCost/error
        composeRule.onNodeWithTag("compare_details_btn_k_gpt").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("compare_details_panel_k_gpt").assertIsDisplayed()
        composeRule.onNodeWithText("420 ms").assertExists()
        composeRule.onNodeWithText("95 ms").assertExists()
        composeRule.onNodeWithText("62").assertExists()
        assertTrue(composeRule.onAllNodesWithText("Not available").fetchSemanticsNodes().size >= 2)

        // Copy GPT-4o response
        composeRule.onNodeWithTag("compare_copy_btn_k_gpt").performClick()
        composeRule.waitForIdle()
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(
            "# GPT-4o Answer\n\nAlpha **response** with `inlineCode()`.\n\n- Bullet one\n- Bullet two",
            clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        )
        assertTrue(banners.contains(EnglishStrings.compareResponseCopied))

        // Switch to Claude: Claude's details remain collapsed (no leak from GPT-4o) and has distinct metadata
        composeRule.onNodeWithTag("compare_nav_k_claude").performClick()
        drainMainUntil { state.activeEntryKey == "k_claude" }
        composeRule.onNodeWithTag("compare_details_panel_k_claude").assertDoesNotExist()

        composeRule.onNodeWithTag("compare_details_btn_k_claude").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("compare_details_panel_k_claude").assertIsDisplayed()
        composeRule.onNodeWithText("610 ms").assertExists()
        composeRule.onNodeWithText("140 ms").assertExists()
        composeRule.onNodeWithText("75 (Estimated)").assertExists()

        // Copy Claude response -> clipboard updates strictly to Claude's response
        composeRule.onNodeWithTag("compare_copy_btn_k_claude").performClick()
        composeRule.waitForIdle()
        assertEquals(
            "## Claude Reasoning\n\n> Quoted insight from Claude.\n\n```kotlin\nfun compare() = \"Beta response\"\n```\n\n| Metric | Value |\n| --- | --- |\n| Speed | Fast |",
            clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        )

        // Empty model response disables Copy cleanly
        composeRule.onNodeWithTag("compare_nav_k_empty").performClick()
        drainMainUntil { state.activeEntryKey == "k_empty" }
        composeRule.onNodeWithTag("compare_copy_btn_k_empty").assertIsNotEnabled()
    }

    @Test
    fun viewModelDynamicModelSelection_preservesKeptEntriesAndReconcilesActiveKeySafely() {
        val vm = GatewayViewModel(app)
        val m1 = ModelEntity(id = "p1::m1", modelId = "m1", displayName = "Model 1", providerId = "p1", providerName = "OpenAI")
        val m2 = ModelEntity(id = "p2::m2", modelId = "m2", displayName = "Model 2", providerId = "p2", providerName = "Anthropic")
        val m3 = ModelEntity(id = "p3::m3", modelId = "m3", displayName = "Model 3", providerId = "p3", providerName = "Google")
        val m4 = ModelEntity(id = "p4::m4", modelId = "m4", displayName = "Model 4", providerId = "p4", providerName = "Qwen")

        // Start with 2 models, expand to 4 models, select m3, then remove m3
        vm.syncCompareModelSelection(listOf(m1, m2))
        assertEquals(2, vm.compareState.value.entries.size)
        assertEquals("p1::m1", vm.compareState.value.activeEntryKey)

        vm.syncCompareModelSelection(listOf(m1, m2, m3, m4))
        assertEquals(4, vm.compareState.value.entries.size)

        vm.selectCompareEntry("p3::m3")
        assertEquals("p3::m3", vm.compareState.value.activeEntryKey)

        // Remove active model m3 -> reconciles safely without out-of-bounds
        vm.syncCompareModelSelection(listOf(m1, m2, m4))
        assertEquals(3, vm.compareState.value.entries.size)
        assertEquals("p4::m4", vm.compareState.value.activeEntryKey)

        // Remove all -> empty entries & null activeEntryKey
        vm.syncCompareModelSelection(emptyList())
        assertTrue(vm.compareState.value.entries.isEmpty())
        assertNull(vm.compareState.value.activeEntryKey)
    }

    @Test
    fun endToEndPlaygroundCompareMode_streamsIndependentlyIsolatesFailureAndRetriesSingleFailedModel() {
        val failModelB = AtomicBoolean(true)
        val modelBCalls = AtomicInteger(0)

        val urlA = startMockServer {
            post("/chat/completions") {
                call.receiveText()
                val sse = buildString {
                    append("data: ${JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", "Alpha ")))).toString()}\n\n")
                    append("data: ${JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", "streamed ok")))).put("usage", JSONObject().put("prompt_tokens", 10).put("completion_tokens", 6).put("total_tokens", 16)).toString()}\n\n")
                    append("data: [DONE]\n\n")
                }
                call.respondText(sse, ContentType.Text.EventStream, HttpStatusCode.OK)
            }
        }
        val urlB = startMockServer {
            post("/chat/completions") {
                call.receiveText()
                modelBCalls.incrementAndGet()
                if (failModelB.get()) {
                    call.respondText(
                        """{"error":{"message":"Upstream 503 busy"}}""",
                        ContentType.Application.Json,
                        HttpStatusCode.ServiceUnavailable
                    )
                } else {
                    val sse = buildString {
                        append("data: ${JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", "Beta recovered")))).put("usage", JSONObject().put("prompt_tokens", 11).put("completion_tokens", 5).put("total_tokens", 16)).toString()}\n\n")
                        append("data: [DONE]\n\n")
                    }
                    call.respondText(sse, ContentType.Text.EventStream, HttpStatusCode.OK)
                }
            }
        }
        val urlC = startMockServer {
            post("/chat/completions") {
                call.receiveText()
                val sse = buildString {
                    append("data: ${JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", "Gamma ok")))).toString()}\n\n")
                    append("data: [DONE]\n\n")
                }
                call.respondText(sse, ContentType.Text.EventStream, HttpStatusCode.OK)
            }
        }

        val models = runBlocking(Dispatchers.IO) {
            listOf(
                insertProviderWithModel("cmp-a", "ProviderA", urlA, "alpha-1", "Alpha One", priority = 1),
                insertProviderWithModel("cmp-b", "ProviderB", urlB, "beta-2", "Beta Two", priority = 2),
                insertProviderWithModel("cmp-c", "ProviderC", urlC, "gamma-3", "Gamma Three", priority = 3)
            )
        }
        val vm = GatewayViewModel(app)
        vm.updateLocalAiSettings(LocalRoutingPolicy.CLOUD_FIRST, false, true)

        composeRule.setContent {
            CompositionLocalProvider(LocalStrings provides EnglishStrings) {
                JhGetWayTheme(themeMode = ThemeMode.LIGHT) {
                    Box(modifier = Modifier.width(412.dp).height(760.dp)) {
                        PlaygroundScreen(viewModel = vm, onNavigate = {})
                    }
                }
            }
        }
        drainMainUntil { vm.settings.value.localNetworkAccessAllowed && vm.models.value.size == 3 }

        // Switch to Compare Mode and verify empty state
        composeRule.onNodeWithTag("playground_compare_mode").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("compare_empty_state").assertIsDisplayed()

        // Select all 3 models via quick-pick chips once Room models load
        models.forEach { m ->
            composeRule.onNodeWithTag("compare_quick_pick_${m.id}").performClick()
            composeRule.waitForIdle()
        }

        // Send shared prompt
        composeRule.onNodeWithTag("playground_prompt_input").performTextInput("Compare streaming")
        composeRule.onNodeWithTag("playground_send_btn").performClick()

        // Wait until all 3 entries finish executing
        drainMainUntil(timeoutMs = 8_000L) {
            val st = vm.compareState.value
            !st.isRunning && st.entries.size == 3 && st.entries.none { it.status.isActive }
        }

        val afterFirstRun = vm.compareState.value.entries.associateBy { it.entryKey }
        assertEquals(
            "Alpha error: ${afterFirstRun["cmp-a::alpha-1"]?.errorMessage}",
            CompareRequestStatus.COMPLETED,
            afterFirstRun["cmp-a::alpha-1"]?.status
        )
        assertEquals("Alpha streamed ok", afterFirstRun["cmp-a::alpha-1"]?.responseText)
        assertEquals(CompareRequestStatus.FAILED, afterFirstRun["cmp-b::beta-2"]?.status)
        assertNotNull(afterFirstRun["cmp-b::beta-2"]?.errorMessage)
        assertEquals(
            "Gamma error: ${afterFirstRun["cmp-c::gamma-3"]?.errorMessage}",
            CompareRequestStatus.COMPLETED,
            afterFirstRun["cmp-c::gamma-3"]?.status
        )
        assertEquals("Gamma ok", afterFirstRun["cmp-c::gamma-3"]?.responseText)

        // Active card A shows Alpha's streamed response
        composeRule.onNodeWithTag("compare_progress_text").assertTextEquals("1 of 3")
        composeRule.onNodeWithText("Alpha streamed ok", substring = true).performScrollTo().assertIsDisplayed()

        // Navigate to failed Model B, verify error panel + retry recovers ONLY Model B
        composeRule.onNodeWithTag("compare_nav_cmp-b::beta-2").performClick()
        drainMainUntil { vm.compareState.value.activeEntryKey == "cmp-b::beta-2" }
        composeRule.onNodeWithTag("compare_error_panel_cmp-b::beta-2").performScrollTo().assertIsDisplayed()

        failModelB.set(false)
        composeRule.onNodeWithTag("compare_retry_btn_cmp-b::beta-2").performScrollTo().performClick()
        drainMainUntil(timeoutMs = 8_000L) {
            val b = vm.compareState.value.entries.firstOrNull { it.entryKey == "cmp-b::beta-2" }
            b?.status == CompareRequestStatus.COMPLETED
        }

        // Model B succeeded on retry, while A & C preserved their existing responses intact
        val afterRetry = vm.compareState.value.entries.associateBy { it.entryKey }
        assertEquals("Beta recovered", afterRetry["cmp-b::beta-2"]?.responseText)
        assertEquals("Alpha streamed ok", afterRetry["cmp-a::alpha-1"]?.responseText)
        assertEquals("Gamma ok", afterRetry["cmp-c::gamma-3"]?.responseText)
        assertEquals(2, modelBCalls.get())
    }
}
