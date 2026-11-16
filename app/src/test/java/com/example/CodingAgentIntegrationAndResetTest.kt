package com.example

import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.i18n.AppLanguage
import com.example.core.i18n.EnglishStrings
import com.example.core.i18n.LocalStrings
import com.example.core.security.CryptoManager
import com.example.core.security.MaskingUtils
import com.example.core.security.TokenUsageAccounting
import com.example.data.local.*
import com.example.gateway.*
import com.example.network.ProviderHttpClient
import com.example.ui.GatewayViewModel
import com.example.ui.screens.CodingAgentsScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.JhGetWayTheme
import com.example.ui.theme.ThemeMode
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CodingAgentIntegrationAndResetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var app: JhGetWayApplication
    private lateinit var db: JhGetWayDatabase
    private val gatewayServers = mutableListOf<LocalGatewayHttpServer>()
    private val upstreamServers = mutableListOf<EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>>()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp() {
        CryptoManager.resetTestKeyProvider()
        app = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(app, JhGetWayDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking(Dispatchers.IO) {
            app.preferences.clearAll()
            app.gatewayServer.stop()
            app.database.clearAllTables()
        }
    }

    @After
    fun tearDown() {
        runBlocking(Dispatchers.IO) {
            gatewayServers.forEach { runCatching { it.stop() } }
            gatewayServers.clear()
            app.gatewayServer.stop()
            app.preferences.clearAll()
            app.database.clearAllTables()
        }
        upstreamServers.forEach { runCatching { it.stop(100, 300) } }
        upstreamServers.clear()
        db.close()
        CryptoManager.resetTestKeyProvider()
    }

    // -------------------------------------------------------------------------
    // Phase 2 & 3: Base URL Normalization, Validation & Config Generation
    // -------------------------------------------------------------------------

    @Test
    fun endpointBuilder_normalizesLocalRemoteExistingAndRejectsInvalidOrInactive() {
        assertEquals(
            "http://127.0.0.1:8787" to "http://127.0.0.1:8787/v1",
            CodingAgentConfigBuilder.normalizeGatewayUrls("http://127.0.0.1:8787/")
        )
        assertEquals(
            "https://gw.example.com" to "https://gw.example.com/v1",
            CodingAgentConfigBuilder.normalizeGatewayUrls("https://gw.example.com/v1/")
        )
        assertEquals(
            "https://gw.example.com/proxy" to "https://gw.example.com/proxy/v1",
            CodingAgentConfigBuilder.normalizeGatewayUrls("https://gw.example.com/proxy/")
        )
        assertEquals(
            "https://gw.example.com/proxy" to "https://gw.example.com/proxy/v1",
            CodingAgentConfigBuilder.normalizeGatewayUrls("https://gw.example.com/proxy/v1")
        )

        // LOCAL stopped vs running
        val stoppedLocal = CodingAgentConfigBuilder.resolveGatewayEndpoint(
            settings = GatewaySettingsState(gatewayMode = GatewayMode.LOCAL, gatewayHost = "127.0.0.1", gatewayPort = 8787),
            runtime = GatewayRuntimeState(status = GatewayRunStatus.STOPPED, mode = GatewayMode.LOCAL)
        )
        assertTrue(stoppedLocal.isUrlValid)
        assertFalse(stoppedLocal.isGatewayActive)
        assertFalse(stoppedLocal.isReadyToConnect)
        assertEquals("http://127.0.0.1:8787/v1", stoppedLocal.baseV1Url)
        assertTrue(stoppedLocal.errorMessage.orEmpty().contains("stopped", ignoreCase = true))

        val runningLocal = CodingAgentConfigBuilder.resolveGatewayEndpoint(
            settings = GatewaySettingsState(gatewayMode = GatewayMode.LOCAL, gatewayHost = "127.0.0.1", gatewayPort = 8787),
            runtime = GatewayRuntimeState(
                status = GatewayRunStatus.LOCAL_RUNNING,
                mode = GatewayMode.LOCAL,
                boundAddress = "http://127.0.0.1:9090"
            )
        )
        assertTrue(runningLocal.isReadyToConnect)
        assertEquals("http://127.0.0.1:9090/v1", runningLocal.baseV1Url)

        // REMOTE requires HTTPS & non-blank URL
        val missingRemote = CodingAgentConfigBuilder.resolveGatewayEndpoint(
            settings = GatewaySettingsState(gatewayMode = GatewayMode.REMOTE, gatewayRemoteUrl = ""),
            runtime = GatewayRuntimeState()
        )
        assertFalse(missingRemote.isUrlValid)

        val httpRemoteRejected = CodingAgentConfigBuilder.resolveGatewayEndpoint(
            settings = GatewaySettingsState(gatewayMode = GatewayMode.REMOTE, gatewayRemoteUrl = "http://api.example.com"),
            runtime = GatewayRuntimeState()
        )
        assertFalse(httpRemoteRejected.isUrlValid)

        val validRemote = CodingAgentConfigBuilder.resolveGatewayEndpoint(
            settings = GatewaySettingsState(gatewayMode = GatewayMode.REMOTE, gatewayRemoteUrl = "https://gw.example.com/api/v1/"),
            runtime = GatewayRuntimeState(
                status = GatewayRunStatus.REMOTE_CONNECTED,
                mode = GatewayMode.REMOTE,
                boundAddress = "https://gw.example.com/api/v1"
            )
        )
        assertTrue(validRemote.isUrlValid)
        assertTrue(validRemote.isReadyToConnect)
        assertEquals("https://gw.example.com/api/v1", validRemote.baseV1Url)

        // EXISTING loopback HTTP allowed when localNetworkAccessAllowed = true
        val existingLoopback = CodingAgentConfigBuilder.resolveGatewayEndpoint(
            settings = GatewaySettingsState(
                gatewayMode = GatewayMode.EXISTING,
                gatewayRemoteUrl = "http://127.0.0.1:8088/gw/",
                localNetworkAccessAllowed = true
            ),
            runtime = GatewayRuntimeState(
                status = GatewayRunStatus.REMOTE_CONNECTED,
                mode = GatewayMode.EXISTING,
                boundAddress = "http://127.0.0.1:8088/gw"
            )
        )
        assertTrue(existingLoopback.isReadyToConnect)
        assertEquals("http://127.0.0.1:8088/gw/v1", existingLoopback.baseV1Url)
    }

    @Test
    fun configBuilders_generateOfficialOpenCodeJsonAndCodexTomlWithProperSecretBoundaries() {
        val rawSecret = "jhgw_ag_super_secret_token_987654"
        val previewSecret = MaskingUtils.maskSecret(rawSecret)
        val settings = GatewaySettingsState(defaultModelId = "gpt-4o-mini")

        val openCodeAgent = CodingAgentEntity(
            name = "OpenCode Dev",
            agentType = CodingAgentType.OPENCODE,
            modelAlias = "jh-coder",
            allowedModelsCsv = "jh-coder,gpt-4o-mini",
            toolCalling = true,
            encryptedToken = "enc",
            tokenPreview = previewSecret
        )
        val openCodeJsonStr = CodingAgentConfigBuilder.buildOpenCodeConfigJson(
            agent = openCodeAgent,
            baseV1Url = "http://127.0.0.1:8787/v1",
            apiKeyValue = rawSecret,
            settings = settings
        )
        val openCodeJson = JSONObject(openCodeJsonStr)
        assertEquals("https://opencode.ai/config.json", openCodeJson.getString("\$schema"))
        assertEquals("jhgetway/jh-coder", openCodeJson.getString("model"))
        val provider = openCodeJson.getJSONObject("provider").getJSONObject("jhgetway")
        assertEquals("@ai-sdk/openai-compatible", provider.getString("npm"))
        assertEquals("http://127.0.0.1:8787/v1", provider.getJSONObject("options").getString("baseURL"))
        assertEquals(rawSecret, provider.getJSONObject("options").getString("apiKey"))
        val models = provider.getJSONObject("models")
        assertTrue(models.has("jh-coder"))
        assertTrue(models.has("gpt-4o-mini"))
        assertTrue(models.getJSONObject("jh-coder").getBoolean("tool_call"))

        // UI preview must NEVER contain rawSecret
        val openCodePreview = CodingAgentConfigBuilder.buildPreviewSnippet(
            agent = openCodeAgent,
            baseV1Url = "http://127.0.0.1:8787/v1",
            settings = settings
        )
        assertFalse(openCodePreview.contains(rawSecret))
        assertTrue(openCodePreview.contains(previewSecret))

        val codexAgent = CodingAgentEntity(
            name = "Codex CLI",
            agentType = CodingAgentType.CODEX,
            modelAlias = "codex-main",
            encryptedToken = "enc",
            tokenPreview = previewSecret
        )
        val codexToml = CodingAgentConfigBuilder.buildCodexConfigToml(
            agent = codexAgent,
            baseV1Url = "https://gw.example.com/v1",
            settings = settings
        )
        assertTrue(codexToml.contains("""model = "codex-main""""))
        assertTrue(codexToml.contains("""model_provider = "jhgetway""""))
        assertTrue(codexToml.contains("[model_providers.jhgetway]"))
        assertTrue(codexToml.contains("""base_url = "https://gw.example.com/v1""""))
        assertTrue(codexToml.contains("""env_key = "OPENAI_API_KEY""""))
        assertTrue(codexToml.contains("""wire_api = "responses""""))
        assertFalse("Codex TOML must not embed raw token", codexToml.contains(rawSecret))

        val codexPreview = CodingAgentConfigBuilder.buildPreviewSnippet(
            agent = codexAgent,
            baseV1Url = "https://gw.example.com/v1",
            settings = settings
        )
        assertFalse(codexPreview.contains(rawSecret))
        assertEquals("""export OPENAI_API_KEY="$rawSecret"""", CodingAgentConfigBuilder.buildCodexEnvCommand(rawSecret))
    }

    // -------------------------------------------------------------------------
    // Phase 2 & 3 Live Gateway Integration Tests: OpenCode & Codex
    // -------------------------------------------------------------------------

    @Test
    fun openCodeAndCodexGatewayE2E_realHttpStreamsToolsAllowlistDisabledInvalidAnd501(): Unit = runBlocking {
        val upstreamPort = startCodingUpstreamMock()
        seedOpenAiProvider(upstreamPort, modelIds = listOf("gpt-4o-mini", "gpt-4o"))
        db.routingRuleDao().upsert(
            RoutingRuleEntity(
                name = "Coder Alias Rule",
                modelAlias = "jh-coder",
                preferredProviderId = "prov-agent-1",
                enabled = true
            )
        )
        db.routingRuleDao().upsert(
            RoutingRuleEntity(
                name = "Codex Alias Rule",
                modelAlias = "codex-main",
                preferredProviderId = "prov-agent-1",
                enabled = true
            )
        )

        val openCodeToken = "jhgw_ag_opencode_live_111"
        val codexToken = "jhgw_ag_codex_live_222"
        val disabledToken = "jhgw_ag_disabled_live_333"

        val openCodeAgent = CodingAgentEntity(
            name = "OpenCode Live",
            agentType = CodingAgentType.OPENCODE,
            modelAlias = "jh-coder",
            allowedModelsCsv = "jh-coder,gpt-4o-mini",
            streaming = true,
            toolCalling = true,
            tokensResetDateUtc = TokenUsageAccounting.utcDateString(),
            encryptedToken = CryptoManager.encrypt(openCodeToken),
            tokenPreview = MaskingUtils.maskSecret(openCodeToken),
            enabled = true
        )
        val codexAgent = CodingAgentEntity(
            name = "Codex Live",
            agentType = CodingAgentType.CODEX,
            modelAlias = "codex-main",
            allowedModelsCsv = "codex-main,gpt-4o-mini",
            streaming = true,
            toolCalling = true,
            tokensResetDateUtc = TokenUsageAccounting.utcDateString(),
            encryptedToken = CryptoManager.encrypt(codexToken),
            tokenPreview = MaskingUtils.maskSecret(codexToken),
            enabled = true
        )
        val disabledAgent = CodingAgentEntity(
            name = "Disabled Agent",
            agentType = CodingAgentType.OPENCODE,
            modelAlias = "jh-coder",
            tokensResetDateUtc = TokenUsageAccounting.utcDateString(),
            encryptedToken = CryptoManager.encrypt(disabledToken),
            tokenPreview = MaskingUtils.maskSecret(disabledToken),
            enabled = false
        )
        db.codingAgentDao().upsert(openCodeAgent)
        db.codingAgentDao().upsert(codexAgent)
        db.codingAgentDao().upsert(disabledAgent)

        val gateway = startLocalGatewayServer()
        val baseUrl = gateway.runtimeState.value.boundAddress
        val baseV1 = "$baseUrl/v1"
        val activeSettings = app.preferences.settingsFlow.first()
        val tester = CodingAgentGatewayTester()

        // 1. Live Tester passes for OpenCode (/v1/chat/completions) & Codex (/v1/responses)
        val openCodeStatus = tester.testConnection(openCodeAgent, openCodeToken, activeSettings, gateway.runtimeState.value)
        assertTrue("Expected OpenCode probe success, got: ${openCodeStatus.message}", openCodeStatus.success == true)
        assertEquals(200, openCodeStatus.httpCode)
        assertTrue(openCodeStatus.endpointTested.endsWith("/v1/chat/completions"))
        assertFalse(openCodeStatus.message.contains(openCodeToken))

        val codexStatus = tester.testConnection(codexAgent, codexToken, activeSettings, gateway.runtimeState.value)
        assertTrue("Expected Codex probe success, got: ${codexStatus.message}", codexStatus.success == true)
        assertEquals(200, codexStatus.httpCode)
        assertTrue(codexStatus.endpointTested.endsWith("/v1/responses"))
        assertFalse(codexStatus.message.contains(codexToken))

        // 2. /v1/models exposes allowed aliases + models and hides disallowed gpt-4o
        httpClient.newCall(
            Request.Builder().url("$baseV1/models").header("Authorization", "Bearer $openCodeToken").get().build()
        ).execute().use { resp ->
            assertEquals(200, resp.code)
            val ids = JSONObject(resp.body!!.string()).getJSONArray("data").let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }
            }
            assertTrue(ids.contains("gpt-4o-mini"))
            assertTrue(ids.contains("jh-coder"))
            assertFalse("Disallowed gpt-4o must be filtered from /v1/models", ids.contains("gpt-4o"))
        }

        // 3. OpenCode Chat Completions: non-stream + tool calling + SSE stream
        val chatNonStream = JSONObject()
            .put("model", "jh-coder")
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "call tool please")))
            .put(
                "tools",
                JSONArray().put(
                    JSONObject()
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", "read_file")
                                .put("parameters", JSONObject().put("type", "object"))
                        )
                )
            )
            .toString()
        httpClient.newCall(
            Request.Builder()
                .url("$baseV1/chat/completions")
                .header("Authorization", "Bearer $openCodeToken")
                .post(chatNonStream.toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().use { resp ->
            assertEquals(200, resp.code)
            val bodyJson = JSONObject(resp.body!!.string())
            val msg = bodyJson.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            assertEquals("read_file", msg.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("name"))
        }

        val chatStream = JSONObject()
            .put("model", "jh-coder")
            .put("stream", true)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hello stream")))
            .toString()
        httpClient.newCall(
            Request.Builder()
                .url("$baseV1/chat/completions")
                .header("Authorization", "Bearer $openCodeToken")
                .post(chatStream.toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().use { resp ->
            assertEquals(200, resp.code)
            val sse = resp.body!!.string()
            assertTrue(sse.contains("chat.completion.chunk"))
            assertTrue(sse.contains("data: [DONE]"))
        }

        // 4. Codex /v1/responses: non-stream + tool calling + function_call_output roundtrip + SSE stream
        val responsesToolReq = JSONObject()
            .put("model", "codex-main")
            .put("input", "call tool please")
            .put(
                "tools",
                JSONArray().put(
                    JSONObject()
                        .put("type", "function")
                        .put("name", "read_file")
                        .put("parameters", JSONObject().put("type", "object"))
                )
            )
            .put("stream", false)
            .toString()
        httpClient.newCall(
            Request.Builder()
                .url("$baseV1/responses")
                .header("Authorization", "Bearer $codexToken")
                .post(responsesToolReq.toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().use { resp ->
            assertEquals(200, resp.code)
            val bodyJson = JSONObject(resp.body!!.string())
            assertEquals("response", bodyJson.getString("object"))
            val output = bodyJson.getJSONArray("output")
            assertEquals("function_call", output.getJSONObject(0).getString("type"))
            assertEquals("read_file", output.getJSONObject(0).getString("name"))
        }

        // Stateless multi-turn Responses follow-up with function_call + function_call_output
        val responsesFollowUp = JSONObject()
            .put("model", "codex-main")
            .put(
                "input",
                JSONArray()
                    .put(JSONObject().put("type", "message").put("role", "user").put("content", "read README"))
                    .put(
                        JSONObject()
                            .put("type", "function_call")
                            .put("call_id", "call_fn_1")
                            .put("name", "read_file")
                            .put("arguments", """{"path":"README.md"}""")
                    )
                    .put(
                        JSONObject()
                            .put("type", "function_call_output")
                            .put("call_id", "call_fn_1")
                            .put("output", "JH GetWay README content")
                    )
            )
            .put("stream", true)
            .toString()
        httpClient.newCall(
            Request.Builder()
                .url("$baseV1/responses")
                .header("Authorization", "Bearer $codexToken")
                .post(responsesFollowUp.toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().use { resp ->
            assertEquals(200, resp.code)
            val sse = resp.body!!.string()
            assertTrue(sse.contains("event: response.created"))
            assertTrue(sse.contains("event: response.output_text.delta"))
            assertTrue(sse.contains("event: response.completed"))
        }

        // 5. Codex /v1/responses explicitly rejects unsupported stateful/async/built-in features with HTTP 501
        val unsupportedPayloads = listOf(
            """{"model":"codex-main","previous_response_id":"resp_123","input":"next"}""",
            """{"model":"codex-main","background":true,"input":"async"}""",
            """{"model":"codex-main","input":[{"type":"item_reference","id":"item_1"}]}""",
            """{"model":"codex-main","input":"search","tools":[{"type":"web_search"}]}"""
        )
        unsupportedPayloads.forEach { badJson ->
            httpClient.newCall(
                Request.Builder()
                    .url("$baseV1/responses")
                    .header("Authorization", "Bearer $codexToken")
                    .post(badJson.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().use { resp ->
                assertEquals("Expected HTTP 501 for $badJson", 501, resp.code)
            }
        }

        // 6. Disabled agent -> 401; invalid token -> 401; disallowed model -> 403
        val disabledTest = tester.testConnection(disabledAgent, disabledToken, activeSettings, gateway.runtimeState.value)
        assertFalse(disabledTest.success == true)
        assertEquals(401, disabledTest.httpCode)

        val invalidTokenTest = tester.testConnection(openCodeAgent, "jhgw_ag_wrong_token", activeSettings, gateway.runtimeState.value)
        assertFalse(invalidTokenTest.success == true)
        assertEquals(401, invalidTokenTest.httpCode)

        httpClient.newCall(
            Request.Builder()
                .url("$baseV1/chat/completions")
                .header("Authorization", "Bearer $openCodeToken")
                .post("""{"model":"gpt-4o","messages":[{"role":"user","content":"blocked"}]}""".toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().use { resp ->
            assertEquals(403, resp.code)
        }
    }

    @Test
    fun remoteGatewayBoundary_localAgentTokenRejected401WithoutSpoofingOrCredentialSubstitution(): Unit = runBlocking {
        val remoteExpectedToken = "remote-gw-admin-secret-xyz"
        val localAgentToken = "jhgw_ag_local_only_444"
        val seenAuthHeaders = mutableListOf<String?>()

        val remoteSrv = embeddedServer(CIO, configure = {
            connector { host = "127.0.0.1"; port = 0 }
        }) {
            routing {
                get("/health") {
                    call.respondText("""{"status":"ok"}""", ContentType.Application.Json, HttpStatusCode.OK)
                }
                get("/v1/models") {
                    val auth = call.request.headers[HttpHeaders.Authorization]
                    seenAuthHeaders += auth
                    if (auth == "Bearer $remoteExpectedToken") {
                        call.respondText("""{"object":"list","data":[]}""", ContentType.Application.Json, HttpStatusCode.OK)
                    } else {
                        call.respondText(
                            """{"error":{"message":"Unknown remote token","type":"authentication_error"}}""",
                            ContentType.Application.Json,
                            HttpStatusCode.Unauthorized
                        )
                    }
                }
            }
        }
        remoteSrv.start(wait = false)
        upstreamServers += remoteSrv
        val remotePort = remoteSrv.engine.resolvedConnectors().first().port
        val remoteUrl = "http://127.0.0.1:$remotePort"

        val remoteSettings = GatewaySettingsState(
            gatewayMode = GatewayMode.EXISTING,
            gatewayRemoteUrl = remoteUrl,
            gatewayRemoteAuthTokenEnc = CryptoManager.encrypt(remoteExpectedToken),
            localNetworkAccessAllowed = true
        )
        val runtime = GatewayRuntimeState(
            status = GatewayRunStatus.REMOTE_CONNECTED,
            mode = GatewayMode.EXISTING,
            boundAddress = remoteUrl
        )
        val agent = CodingAgentEntity(
            name = "Remote Check Agent",
            agentType = CodingAgentType.OPENCODE,
            modelAlias = "jh-coder",
            encryptedToken = CryptoManager.encrypt(localAgentToken),
            tokenPreview = MaskingUtils.maskSecret(localAgentToken)
        )

        val result = CodingAgentGatewayTester().testConnection(agent, localAgentToken, remoteSettings, runtime)
        assertFalse("Remote test must NOT spoof success", result.success == true)
        assertEquals(401, result.httpCode)
        assertTrue(seenAuthHeaders.contains("Bearer $localAgentToken"))
        assertFalse("Must NOT silently substitute gatewayRemoteAuthTokenEnc", seenAuthHeaders.contains("Bearer $remoteExpectedToken"))
        assertTrue(result.message.contains("remote", ignoreCase = true))
        assertFalse(result.message.contains(localAgentToken))
        assertFalse(result.message.contains(remoteExpectedToken))
    }

    // -------------------------------------------------------------------------
    // Phase 4: Agent Edit Preserves Existing Token
    // -------------------------------------------------------------------------

    @Test
    fun viewModel_saveCodingAgentPreservesTokenOnEditEvenWithoutActiveFlowCollection() {
        val vmStore = ViewModelStore()
        val vm = ViewModelProvider(vmStore, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[GatewayViewModel::class.java]
        try {
            vm.saveCodingAgent(
                existingId = null,
                name = "OpenCode Original",
                type = CodingAgentType.OPENCODE,
                modelAlias = "jh-coder",
                streaming = true,
                toolCalling = true,
                dailyTokenLimit = 5000L,
                allowedModelsCsv = "jh-coder"
            )
            awaitCondition {
                runBlocking(Dispatchers.IO) { app.database.codingAgentDao().getAllOnce().size == 1 }
            }
            val created = runBlocking(Dispatchers.IO) { app.database.codingAgentDao().getAllOnce().first() }
            val origDecrypted = CryptoManager.decryptResult(created.encryptedToken).getOrThrow()

            // Edit without ever collecting vm.codingAgents StateFlow
            vm.saveCodingAgent(
                existingId = created.id,
                name = "OpenCode Renamed",
                type = CodingAgentType.OPENCODE,
                modelAlias = "gpt-4o-mini",
                streaming = false,
                toolCalling = false,
                dailyTokenLimit = 9000L,
                allowedModelsCsv = "gpt-4o-mini"
            )
            awaitCondition {
                runBlocking(Dispatchers.IO) {
                    app.database.codingAgentDao().getById(created.id)?.name == "OpenCode Renamed"
                }
            }
            val edited = runBlocking(Dispatchers.IO) { app.database.codingAgentDao().getById(created.id)!! }
            assertEquals(created.encryptedToken, edited.encryptedToken)
            assertEquals(created.tokenPreview, edited.tokenPreview)
            assertEquals(origDecrypted, CryptoManager.decryptResult(edited.encryptedToken).getOrThrow())
            assertEquals("gpt-4o-mini", edited.modelAlias)
            assertFalse(edited.streaming)
            assertFalse(edited.toolCalling)
        } finally {
            vmStore.clear()
        }
    }

    // -------------------------------------------------------------------------
    // Phase 5: Safe Reset App Settings (Data Preservation & Atomic Allowlist)
    // -------------------------------------------------------------------------

    @Test
    fun resetSafeAppSettings_resetsOnlyUiAndGeneralPrefsAndPreservesAllDbAndProtectedSettingsIdempotently() {
        // Check complete classification coverage between SAFE_RESET_KEY_NAMES & PROTECTED_KEY_NAMES
        assertTrue(AppPreferences.SAFE_RESET_KEY_NAMES.intersect(AppPreferences.PROTECTED_KEY_NAMES).isEmpty())
        assertEquals(46, AppPreferences.SAFE_RESET_KEY_NAMES.size + AppPreferences.PROTECTED_KEY_NAMES.size)

        val vmStore = ViewModelStore()
        val vm = ViewModelProvider(vmStore, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[GatewayViewModel::class.java]
        try {
            awaitCondition { vm.isInitialSettingsLoaded.value }

            // 1. Seed ALL Room database tables with user data
            val realDb = app.database
            val provider = ProviderEntity(id = "p-keep", name = "KeepProvider", baseUrl = "https://api.example.com/v1", defaultModel = "keep-model")
            val apiKey = ApiKeyEntity(id = "k-keep", providerId = "p-keep", label = "Prod Key", encryptedKey = CryptoManager.encrypt("sk-keep"), maskedPreview = "sk-••••ep")
            val model = ModelEntity(id = "m-keep", modelId = "keep-model", displayName = "Keep Model", providerId = "p-keep", providerName = "KeepProvider", isDefault = true)
            val rule = RoutingRuleEntity(id = "r-keep", name = "KeepRule", modelAlias = "jh-keep", preferredProviderId = "p-keep")
            val agent = CodingAgentEntity(id = "a-keep", name = "KeepAgent", agentType = CodingAgentType.OPENCODE, encryptedToken = CryptoManager.encrypt("jhgw_ag_keep"), tokenPreview = "jhgw_••••ep")
            val localSrv = LocalAiServerEntity(id = "l-keep", name = "KeepOllama", engineType = LocalEngineType.OLLAMA, serverUrl = "http://127.0.0.1:11434")
            val mcpSrv = McpServerEntity(id = "ms-keep", name = "KeepMcp", transportType = McpTransportType.STREAMABLE_HTTP, endpointOrCommand = "https://mcp.example.com")
            val mcpTool = McpToolEntity(id = "mt-keep", serverId = "ms-keep", toolName = "search_docs")
            val wf = WorkflowEntity(id = "wf-keep", name = "KeepFlow", nodesJson = "[]", edgesJson = "[]")
            val wfRun = WorkflowRunEntity(id = "wfr-keep", workflowId = "wf-keep", workflowName = "KeepFlow", status = "COMPLETED", inputText = "hi")
            val dev = DeviceEntity(id = "d-keep", name = "KeepDevice", deviceType = "CLI", deviceCode = "DEV-99", encryptedToken = CryptoManager.encrypt("jhgw_dev_keep"), tokenPreview = "jhgw_••••ep")
            val metric = RequestMetricEntity(id = "rm-keep", requestId = "req-1", providerId = "p-keep", providerName = "KeepProvider", modelId = "keep-model", deviceName = "KeepAgent", endpoint = "/v1/chat/completions", statusCode = 200, success = true, latencyMs = 123, inputTokens = 10, outputTokens = 20, routingMode = "AUTOMATIC", routingReason = "primary")
            val log = ActivityLogEntity(id = "al-keep", category = ActivityCategory.SYSTEM_EVENT, relatedEntity = "Keep", status = "OK", safeMessage = "Keep log entry")
            val session = PlaygroundSessionEntity(id = "ps-keep", title = "Keep Chat", modelId = "keep-model")
            val msg = PlaygroundMessageEntity(id = "pm-keep", sessionId = "ps-keep", role = "user", content = "Keep this message")

            val remoteTokenEnc = CryptoManager.encrypt("remote-secret-preserve")
            val pinHash = "pbkdf2_v1\$10000\$salt\$hashkeep"

            runBlocking(Dispatchers.IO) {
                realDb.providerDao().upsert(provider)
                realDb.apiKeyDao().upsert(apiKey)
                realDb.modelDao().upsert(model)
                realDb.routingRuleDao().upsert(rule)
                realDb.codingAgentDao().upsert(agent)
                realDb.localAiServerDao().upsert(localSrv)
                realDb.mcpDao().upsertServer(mcpSrv)
                realDb.mcpDao().upsertTool(mcpTool)
                realDb.workflowDao().upsertWorkflow(wf)
                realDb.workflowDao().upsertRun(wfRun)
                realDb.deviceDao().upsert(dev)
                realDb.metricAndLogDao().insertMetric(metric)
                realDb.metricAndLogDao().insertLog(log)
                realDb.playgroundDao().upsertSession(session)
                realDb.playgroundDao().insertMessage(msg)

                // 2. Customize BOTH protected settings AND resettable settings
                app.preferences.setLanguage(AppLanguage.BENGALI)
                app.preferences.setOnboardingCompleted(true)
                app.preferences.updateGatewaySettings(
                    name = "My Prod Gateway",
                    mode = GatewayMode.REMOTE,
                    host = "10.0.0.5",
                    port = 9999,
                    remoteUrl = "https://gw.prod.example.com",
                    authRequired = true,
                    timeoutSec = 120,
                    maxRequestKb = 4096,
                    streaming = false,
                    rateLimitPerMin = 150,
                    loggingLevel = LoggingLevel.STANDARD, // resettable
                    allowLanExposure = true,
                    remoteAuthTokenEnc = remoteTokenEnc
                )
                app.preferences.setGatewayServiceDesiredRunning(true)
                app.preferences.setDefaultModelAndProvider("keep-model", "p-keep")
                app.preferences.updateLocalAiSettings(LocalRoutingPolicy.LOCAL_ONLY, allowCloudFallback = false, localNetworkAllowed = true)
                app.preferences.setSaveChatHistory(false)
                app.preferences.setAppLock(true, pinHash)
                app.preferences.setAppLockAutoLockOnBackground(false)
                app.preferences.setAppLockBiometricEnabled(true)
                app.preferences.setLogRetentionDays(77)
                app.preferences.setUsageRetentionDays(180)
                app.preferences.setPlaygroundRetentionDays(45)
                app.preferences.setGithubUpdateRepo("jhsoft/jh-getway")

                // Resettable preferences customized away from defaults
                app.preferences.setThemeMode(ThemeMode.DARK)
                app.preferences.setCurrency("BDT")
                app.preferences.updateNotificationPrefs(false, false, false, false, false)
                app.preferences.setAutoCheckUpdates(false)
                app.preferences.setSkippedUpdateTag("v9.9.9")
                app.preferences.updateRoutingSettings(RoutingMode.LOWEST_COST, fallbackEnabled = false, maxRetries = 5)
                app.preferences.setManualModelFallbackEnabled(true)
                app.preferences.setAutoFreeIncludeLocal(true)
            }

            awaitCondition {
                vm.settings.value.themeMode == ThemeMode.DARK &&
                    vm.settings.value.analyticsCurrency == "BDT" &&
                    vm.settings.value.defaultRoutingMode == RoutingMode.LOWEST_COST
            }

            // 3. Call resetSafeAppSettings() twice for idempotency verification
            repeat(2) {
                vm.resetSafeAppSettings()
                awaitCondition {
                    val cur = runBlocking(Dispatchers.IO) { app.preferences.settingsFlow.first() }
                    cur.themeMode == ThemeMode.SYSTEM &&
                        cur.analyticsCurrency == "USD" &&
                        cur.defaultRoutingMode == RoutingMode.AUTOMATIC
                }
            }

            runBlocking(Dispatchers.IO) {
                val after = app.preferences.settingsFlow.first()

                // Verify resettable preferences returned to defaults
                assertEquals(ThemeMode.SYSTEM, after.themeMode)
                assertEquals("USD", after.analyticsCurrency)
                assertTrue(after.notifyProviderFailure)
                assertTrue(after.notifyGatewayDisconnected)
                assertTrue(after.notifyRateLimit)
                assertTrue(after.notifyWorkflowCompleted)
                assertTrue(after.notifySecurityEvent)
                assertTrue(after.autoCheckUpdates)
                assertEquals("", after.skippedUpdateTag)
                assertEquals(LoggingLevel.METADATA_ONLY, after.gatewayLoggingLevel)
                assertEquals(RoutingMode.AUTOMATIC, after.defaultRoutingMode)
                assertTrue(after.fallbackEnabled)
                assertEquals(2, after.maxRetries)
                assertFalse(after.manualModelFallbackEnabled)
                assertFalse(after.autoFreeIncludeLocal)

                // Verify protected preferences were 100% preserved
                assertEquals(AppLanguage.BENGALI, after.language)
                assertTrue(after.onboardingCompleted)
                assertEquals("My Prod Gateway", after.gatewayName)
                assertEquals(GatewayMode.REMOTE, after.gatewayMode)
                assertEquals("10.0.0.5", after.gatewayHost)
                assertEquals(9999, after.gatewayPort)
                assertEquals("https://gw.prod.example.com", after.gatewayRemoteUrl)
                assertEquals(remoteTokenEnc, after.gatewayRemoteAuthTokenEnc)
                assertTrue(after.gatewayAuthRequired)
                assertEquals(120, after.gatewayTimeoutSec)
                assertEquals(4096, after.gatewayMaxRequestKb)
                assertFalse(after.gatewayStreamingEnabled)
                assertEquals(150, after.gatewayRateLimitPerMin)
                assertTrue(after.gatewayAllowLanExposure)
                assertTrue(after.gatewayServiceDesiredRunning)
                assertEquals("keep-model", after.defaultModelId)
                assertEquals("p-keep", after.defaultProviderId)
                assertEquals(LocalRoutingPolicy.LOCAL_ONLY, after.localRoutingPolicy)
                assertFalse(after.allowCloudFallbackForLocal)
                assertTrue(after.localNetworkAccessAllowed)
                assertFalse(after.saveChatHistoryAllowed)
                assertTrue(after.appLockEnabled)
                assertEquals(pinHash, after.appLockPinHash)
                assertFalse(after.appLockAutoLockOnBackground)
                assertTrue(after.appLockBiometricEnabled)
                assertEquals(77, after.logRetentionDays)
                assertEquals(180, after.usageRetentionDays)
                assertEquals(45, after.playgroundRetentionDays)
                assertEquals("jhsoft/jh-getway", after.githubUpdateRepo)

                // Verify EVERY Room table remained intact (not a single row lost or altered!)
                assertEquals(listOf(provider), realDb.providerDao().getAllOnce())
                assertEquals(listOf(apiKey), realDb.apiKeyDao().getAllOnce())
                assertEquals(listOf(model), realDb.modelDao().getAllOnce())
                assertEquals(listOf(rule), realDb.routingRuleDao().getAllOnce())
                assertEquals(listOf(agent), realDb.codingAgentDao().getAllOnce())
                assertEquals(listOf(localSrv), realDb.localAiServerDao().getAllOnce())
                assertEquals(listOf(mcpSrv), realDb.mcpDao().getServersOnce())
                assertEquals(listOf(mcpTool), realDb.mcpDao().getToolsForServer("ms-keep"))
                assertEquals(listOf(wf), realDb.workflowDao().getWorkflowsOnce())
                assertEquals(listOf(wfRun), realDb.workflowDao().getRunsOnce())
                assertEquals(listOf(dev), realDb.deviceDao().getAllOnce())
                assertEquals(listOf(metric), realDb.metricAndLogDao().getMetricsOnce())
                assertEquals(listOf(log), realDb.metricAndLogDao().observeLogs().first())
                assertEquals(listOf(session), realDb.playgroundDao().getSessionsOnce())
                assertEquals(listOf(msg), realDb.playgroundDao().getAllMessagesOnce())
            }
        } finally {
            vmStore.clear()
        }
    }

    // -------------------------------------------------------------------------
    // UI Integration Tests: Settings Reset Dialog & Coding Agents Screen
    // -------------------------------------------------------------------------

    @Test
    fun settingsScreen_resetAppSettingsDialogResetsPrefsWithoutWipingData() {
        val vmStore = ViewModelStore()
        val viewModel = ViewModelProvider(vmStore, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[GatewayViewModel::class.java]
        val initDeadlineNs = System.nanoTime() + 10_000_000_000L
        while (!viewModel.isInitialSettingsLoaded.value && System.nanoTime() < initDeadlineNs) {
            Thread.sleep(25)
            composeRule.waitForIdle()
        }

        runBlocking(Dispatchers.IO) {
            val savedProvider = ProviderEntity(id = "ui-prov", name = "UI Keep Provider", baseUrl = "https://api.example.com/v1")
            app.database.providerDao().upsert(savedProvider)
            app.preferences.setThemeMode(ThemeMode.DARK)
            app.preferences.setLanguage(AppLanguage.BENGALI)
        }
        val seedDeadlineNs = System.nanoTime() + 8_000_000_000L
        while (viewModel.settings.value.themeMode != ThemeMode.DARK && System.nanoTime() < seedDeadlineNs) {
            Thread.sleep(25)
            composeRule.waitForIdle()
        }

        try {
            composeRule.setContent {
                CompositionLocalProvider(LocalStrings provides EnglishStrings) {
                    JhGetWayTheme {
                        SettingsScreen(viewModel = viewModel, onNavigate = {})
                    }
                }
            }
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("settings_list")
                .performScrollToNode(hasTestTag("settings_reset_app_settings_btn"))
            composeRule.onNodeWithTag("settings_reset_app_settings_btn").assertIsDisplayed().performClick()

            composeRule.onNodeWithTag("settings_reset_confirm_btn").assertIsDisplayed().performClick()

            val deadlineNs = System.nanoTime() + 8_000_000_000L
            while (viewModel.settings.value.themeMode != ThemeMode.SYSTEM && System.nanoTime() < deadlineNs) {
                Thread.sleep(30)
                composeRule.waitForIdle()
            }

            runBlocking(Dispatchers.IO) {
                val updatedSettings = app.preferences.settingsFlow.first()
                assertEquals(ThemeMode.SYSTEM, updatedSettings.themeMode)
                assertEquals(AppLanguage.BENGALI, updatedSettings.language)
                assertEquals(1, app.database.providerDao().getAllOnce().size)
            }
        } finally {
            vmStore.clear()
        }
    }

    @Test
    fun codingAgentsScreen_displaysBaseUrlModelIdRemoteNoticeAndCopyButtons() {
        val vmStore = ViewModelStore()
        val viewModel = ViewModelProvider(vmStore, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[GatewayViewModel::class.java]
        val initDeadlineNs = System.nanoTime() + 10_000_000_000L
        while (!viewModel.isInitialSettingsLoaded.value && System.nanoTime() < initDeadlineNs) {
            Thread.sleep(25)
            composeRule.waitForIdle()
        }

        runBlocking(Dispatchers.IO) {
            val codex = CodingAgentEntity(
                id = "ui-codex-1",
                name = "Codex CLI Agent",
                agentType = CodingAgentType.CODEX,
                modelAlias = "gpt-4o-mini",
                encryptedToken = CryptoManager.encrypt("jhgw_ag_ui_codex"),
                tokenPreview = "jhgw_••••odex"
            )
            app.database.codingAgentDao().upsert(codex)
            app.preferences.updateGatewaySettings(
                name = "Remote GW",
                mode = GatewayMode.REMOTE,
                host = "127.0.0.1",
                port = 8787,
                remoteUrl = "https://gw.remote.example.com/proxy",
                authRequired = true,
                timeoutSec = 30,
                maxRequestKb = 1024,
                streaming = true,
                rateLimitPerMin = 60,
                loggingLevel = LoggingLevel.METADATA_ONLY
            )
        }
        val remoteDeadlineNs = System.nanoTime() + 8_000_000_000L
        while (viewModel.settings.value.gatewayMode != GatewayMode.REMOTE && System.nanoTime() < remoteDeadlineNs) {
            Thread.sleep(25)
            composeRule.waitForIdle()
        }

        try {
            composeRule.setContent {
                CompositionLocalProvider(LocalStrings provides EnglishStrings) {
                    JhGetWayTheme {
                        CodingAgentsScreen(viewModel = viewModel)
                    }
                }
            }
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("agents_base_url_text").assertIsDisplayed()
            composeRule.onNodeWithTag("agents_base_url_text").assertTextContains("https://gw.remote.example.com/proxy/v1", substring = true)
            composeRule.onNodeWithTag("agents_remote_boundary_note").assertIsDisplayed()
            composeRule.onNodeWithTag("coding_agents_list").performTouchInput { swipeUp() }
            composeRule.onNodeWithTag("coding_agents_list").performTouchInput { swipeUp() }
            composeRule.onNodeWithTag("agent_test_btn_ui-codex-1").assertIsDisplayed()
            composeRule.onNodeWithTag("agent_copy_config_btn_ui-codex-1").assertIsDisplayed()
            composeRule.onNodeWithTag("agent_copy_codex_env_btn_ui-codex-1").assertIsDisplayed()
        } finally {
            vmStore.clear()
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun awaitCondition(timeoutMs: Long = 8000, condition: () -> Boolean) {
        val deadlineNs = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadlineNs) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            composeRule.waitForIdle()
            if (condition()) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                return
            }
            Thread.sleep(25)
        }
        assertTrue("Timed out after ${timeoutMs}ms waiting for condition", condition())
    }

    private suspend fun startLocalGatewayServer(): LocalGatewayHttpServer {
        app.preferences.updateGatewaySettings(
            name = "Agent Integration Gateway",
            mode = GatewayMode.LOCAL,
            host = "127.0.0.1",
            port = 0,
            remoteUrl = "",
            authRequired = true,
            timeoutSec = 30,
            maxRequestKb = 256,
            streaming = true,
            rateLimitPerMin = 200,
            loggingLevel = LoggingLevel.METADATA_ONLY
        )
        app.preferences.updateLocalAiSettings(
            policy = LocalRoutingPolicy.CLOUD_FIRST,
            allowCloudFallback = true,
            localNetworkAllowed = true
        )
        val server = LocalGatewayHttpServer(
            db = db,
            router = SmartRouterEngine(db, ProviderHttpClient()),
            preferences = app.preferences
        )
        gatewayServers += server
        server.start(
            GatewaySettingsState(
                gatewayMode = GatewayMode.LOCAL,
                gatewayHost = "127.0.0.1",
                gatewayPort = 0,
                gatewayAuthRequired = true,
                gatewayStreamingEnabled = true,
                gatewayRateLimitPerMin = 200,
                localNetworkAccessAllowed = true
            )
        )
        assertEquals(GatewayRunStatus.LOCAL_RUNNING, server.runtimeState.value.status)
        return server
    }

    private suspend fun seedOpenAiProvider(port: Int, modelIds: List<String>) {
        val provider = ProviderEntity(
            id = "prov-agent-1",
            name = "MockAgentUpstream",
            type = ProviderType.OPENAI_COMPATIBLE,
            baseUrl = "http://127.0.0.1:$port/v1",
            apiFormat = ApiFormat.OPENAI,
            defaultModel = modelIds.first(),
            priority = 1,
            enabled = true
        )
        db.providerDao().upsert(provider)
        db.apiKeyDao().upsert(
            ApiKeyEntity(
                providerId = provider.id,
                label = "Key1",
                encryptedKey = CryptoManager.encrypt("sk-upstream-secret"),
                maskedPreview = "sk-••••cret",
                isDefault = true,
                enabled = true
            )
        )
        modelIds.forEachIndexed { idx, mid ->
            db.modelDao().upsert(
                ModelEntity(
                    id = "${provider.id}::$mid",
                    modelId = mid,
                    displayName = mid,
                    providerId = provider.id,
                    providerName = provider.name,
                    supportsTools = true,
                    supportsStreaming = true,
                    isDefault = idx == 0,
                    enabled = true
                )
            )
        }
    }

    private fun startCodingUpstreamMock(): Int {
        val srv = embeddedServer(CIO, configure = {
            connector {
                host = "127.0.0.1"
                port = 0
            }
        }) {
            routing {
                post("/v1/chat/completions") {
                    val reqJson = JSONObject(call.receiveText())
                    val isStream = reqJson.optBoolean("stream", false)
                    val model = reqJson.optString("model", "gpt-4o-mini")
                    val hasTools = reqJson.optJSONArray("tools")?.length()?.let { it > 0 } == true
                    val messages = reqJson.optJSONArray("messages") ?: JSONArray()
                    val lastContent = if (messages.length() > 0) {
                        messages.getJSONObject(messages.length() - 1).optString("content")
                    } else ""

                    if (!isStream) {
                        val msgObj = JSONObject().put("role", "assistant")
                        val finishReason = if (hasTools && lastContent.contains("call tool")) {
                            msgObj.put("content", JSONObject.NULL)
                            msgObj.put(
                                "tool_calls",
                                JSONArray().put(
                                    JSONObject()
                                        .put("id", "call_fn_1")
                                        .put("type", "function")
                                        .put(
                                            "function",
                                            JSONObject()
                                                .put("name", "read_file")
                                                .put("arguments", """{"path":"README.md"}""")
                                        )
                                )
                            )
                            "tool_calls"
                        } else {
                            msgObj.put("content", "OK")
                            "stop"
                        }
                        val resp = JSONObject()
                            .put("id", "chatcmpl-agent-test")
                            .put("object", "chat.completion")
                            .put("model", model)
                            .put(
                                "choices",
                                JSONArray().put(
                                    JSONObject()
                                        .put("index", 0)
                                        .put("finish_reason", finishReason)
                                        .put("message", msgObj)
                                )
                            )
                            .put(
                                "usage",
                                JSONObject()
                                    .put("prompt_tokens", 12)
                                    .put("completion_tokens", 8)
                                    .put("total_tokens", 20)
                            )
                        call.respondText(resp.toString(), ContentType.Application.Json, HttpStatusCode.OK)
                    } else {
                        call.respondTextWriter(ContentType.Text.EventStream, HttpStatusCode.OK) {
                            write(
                                "data: " + JSONObject()
                                    .put("id", "chatcmpl-stream")
                                    .put("object", "chat.completion.chunk")
                                    .put("model", model)
                                    .put(
                                        "choices",
                                        JSONArray().put(
                                            JSONObject().put("index", 0)
                                                .put("delta", JSONObject().put("content", "OK "))
                                                .put("finish_reason", JSONObject.NULL)
                                        )
                                    )
                                    .toString() + "\n\n"
                            )
                            write(
                                "data: " + JSONObject()
                                    .put("id", "chatcmpl-stream")
                                    .put("object", "chat.completion.chunk")
                                    .put("model", model)
                                    .put(
                                        "choices",
                                        JSONArray().put(
                                            JSONObject().put("index", 0)
                                                .put("delta", JSONObject())
                                                .put("finish_reason", "stop")
                                        )
                                    )
                                    .put(
                                        "usage",
                                        JSONObject()
                                            .put("prompt_tokens", 10)
                                            .put("completion_tokens", 5)
                                            .put("total_tokens", 15)
                                    )
                                    .toString() + "\n\n"
                            )
                            write("data: [DONE]\n\n")
                            flush()
                        }
                    }
                }
            }
        }
        srv.start(wait = false)
        upstreamServers += srv
        return runBlocking { srv.engine.resolvedConnectors().first().port }
    }
}
