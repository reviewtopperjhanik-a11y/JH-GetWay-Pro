package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.security.CryptoManager
import com.example.core.security.TokenUsageAccounting
import com.example.data.local.*
import com.example.gateway.*
import com.example.network.ChatMessagePayload
import com.example.network.ProviderHttpClient
import com.example.network.StreamEvent
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OpenCodeSmartSelectorAndRoutingTest {

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

    private suspend fun startLocalGateway(settingsModifier: (GatewaySettingsState) -> GatewaySettingsState = { it }): Pair<LocalGatewayHttpServer, String> {
        app.preferences.updateGatewaySettings(
            name = "Smart Selector Test Gateway",
            mode = GatewayMode.LOCAL,
            host = "127.0.0.1",
            port = 0,
            remoteUrl = "",
            authRequired = true,
            timeoutSec = 15,
            maxRequestKb = 512,
            streaming = true,
            rateLimitPerMin = 500,
            loggingLevel = LoggingLevel.METADATA_ONLY
        )
        app.preferences.updateLocalAiSettings(
            policy = LocalRoutingPolicy.CLOUD_FIRST,
            allowCloudFallback = true,
            localNetworkAllowed = true
        )
        val initial = settingsModifier(
            GatewaySettingsState(
                gatewayMode = GatewayMode.LOCAL,
                gatewayHost = "127.0.0.1",
                gatewayPort = 0,
                gatewayAuthRequired = true,
                gatewayStreamingEnabled = true,
                gatewayRateLimitPerMin = 500,
                localRoutingPolicy = LocalRoutingPolicy.CLOUD_FIRST,
                allowCloudFallbackForLocal = true,
                localNetworkAccessAllowed = true
            )
        )
        val server = LocalGatewayHttpServer(
            db = db,
            router = SmartRouterEngine(db, ProviderHttpClient()),
            preferences = app.preferences
        )
        gatewayServers += server
        server.start(initial)
        assertEquals(GatewayRunStatus.LOCAL_RUNNING, server.runtimeState.value.status)
        return server to server.runtimeState.value.boundAddress
    }

    private fun startScriptedUpstream(
        handler: suspend (reqJson: JSONObject, attemptIdx: Int) -> Pair<HttpStatusCode, String>
    ): Pair<Int, CopyOnWriteArrayList<String>> {
        val seenModels = CopyOnWriteArrayList<String>()
        val counter = AtomicInteger(0)
        val srv = embeddedServer(CIO, configure = {
            connector {
                host = "127.0.0.1"
                port = 0
            }
        }) {
            routing {
                post("/v1/chat/completions") {
                    val req = JSONObject(call.receiveText())
                    val model = req.optString("model")
                    seenModels += model
                    val idx = counter.getAndIncrement()
                    val (status, body) = handler(req, idx)
                    call.respondText(body, ContentType.Application.Json, status)
                }
            }
        }
        srv.start(wait = false)
        upstreamServers += srv
        return runBlocking { srv.engine.resolvedConnectors().first().port } to seenModels
    }

    private fun openAiOkJson(model: String, reply: String = "OK from $model"): String = JSONObject()
        .put("id", "chatcmpl-$model")
        .put("object", "chat.completion")
        .put("model", model)
        .put(
            "choices",
            JSONArray().put(
                JSONObject()
                    .put("index", 0)
                    .put("finish_reason", "stop")
                    .put("message", JSONObject().put("role", "assistant").put("content", reply))
            )
        )
        .put(
            "usage",
            JSONObject()
                .put("prompt_tokens", 12)
                .put("completion_tokens", 8)
                .put("total_tokens", 20)
        )
        .toString()

    private suspend fun seedAgent(token: String, alias: String = "auto", allowedCsv: String = ""): CodingAgentEntity {
        val ag = CodingAgentEntity(
            name = "OpenCode Test Agent",
            agentType = CodingAgentType.OPENCODE,
            modelAlias = alias,
            allowedModelsCsv = allowedCsv,
            streaming = true,
            toolCalling = true,
            tokensResetDateUtc = TokenUsageAccounting.utcDateString(),
            encryptedToken = CryptoManager.encrypt(token),
            tokenPreview = "jhgw_••••test",
            enabled = true
        )
        db.codingAgentDao().upsert(ag)
        return ag
    }

    // Scenarios 1, 2, 16, 17, 19:
    // 500+ raw models curated cleanly, ?full=true toggle, OpenCode JSON selector exposure,
    // deduplication of duplicate (providerId, modelId), every curated ID resolves, DB untouched.
    @Test
    fun scenario01_02_16_17_19_curatedCatalogCaps500PlusModelsPreservesDbAndResolvesAllIds(): Unit = runBlocking {
        val (upPort, seenUpstreamModels) = startScriptedUpstream { req, _ ->
            HttpStatusCode.OK to openAiOkJson(req.optString("model"))
        }
        val provA = ProviderEntity(
            id = "prov-a",
            name = "Provider A",
            baseUrl = "http://127.0.0.1:$upPort/v1",
            apiFormat = ApiFormat.OPENAI,
            defaultModel = "claude-sonnet-4",
            priority = 1,
            status = ProviderStatus.CONNECTED,
            enabled = true
        )
        val provB = ProviderEntity(
            id = "prov-b",
            name = "OpenRouter",
            baseUrl = "http://127.0.0.1:$upPort/v1",
            apiFormat = ApiFormat.OPENAI,
            defaultModel = "deepseek/deepseek-chat:free",
            priority = 2,
            status = ProviderStatus.CONNECTED,
            enabled = true
        )
        db.providerDao().upsert(provA)
        db.providerDao().upsert(provB)
        db.apiKeyDao().upsert(
            ApiKeyEntity(providerId = provA.id, label = "KA", encryptedKey = CryptoManager.encrypt("sk-a"), maskedPreview = "sk-••a", enabled = true, isDefault = true)
        )
        db.apiKeyDao().upsert(
            ApiKeyEntity(providerId = provB.id, label = "KB", encryptedKey = CryptoManager.encrypt("sk-b"), maskedPreview = "sk-••b", enabled = true, isDefault = true)
        )

        val storedModels = mutableListOf<ModelEntity>()
        storedModels += ModelEntity(
            id = "m-default",
            modelId = "claude-sonnet-4",
            displayName = "Claude Sonnet 4",
            providerId = provA.id,
            providerName = provA.name,
            modelType = ModelType.CODING,
            capabilitiesCsv = "CHAT,TOOLS,CODING",
            supportsTools = true,
            isDefault = true,
            enabled = true,
            accessTier = AccessTier.PAID
        )
        // Duplicate (providerId, modelId) row with distinct primary key
        storedModels += ModelEntity(
            id = "m-default-dup",
            modelId = "claude-sonnet-4",
            displayName = "Claude Sonnet 4 Duplicate Row",
            providerId = provA.id,
            providerName = provA.name,
            modelType = ModelType.CODING,
            supportsTools = true,
            enabled = true,
            accessTier = AccessTier.PAID
        )
        storedModels += ModelEntity(
            id = "m-free-1",
            modelId = "deepseek/deepseek-chat:free",
            displayName = "DeepSeek Free",
            providerId = provB.id,
            providerName = provB.name,
            modelType = ModelType.GENERAL_CHAT,
            supportsTools = true,
            enabled = true,
            accessTier = AccessTier.FREE_TIER,
            freeTierVerified = true
        )
        repeat(520) { i ->
            storedModels += ModelEntity(
                id = "m-bulk-$i",
                modelId = "bulk-model-$i",
                displayName = "Bulk Model $i",
                providerId = if (i % 2 == 0) provA.id else provB.id,
                providerName = if (i % 2 == 0) provA.name else provB.name,
                modelType = ModelType.GENERAL_CHAT,
                supportsTools = true,
                enabled = true,
                accessTier = AccessTier.UNKNOWN
            )
        }
        db.modelDao().upsertAll(storedModels)
        app.preferences.setDefaultModelAndProvider("claude-sonnet-4", provA.id)

        val token = "jhgw_ag_curated_1"
        val agent = seedAgent(token, alias = "auto")
        val (_, baseUrl) = startLocalGateway()

        // Default /v1/models MUST expose ONLY one primary Auto entry ("jh-getway/auto"), NOT 520+ models!
        val listedIds = mutableListOf<String>()
        httpClient.newCall(
            Request.Builder().url("$baseUrl/v1/models").header("Authorization", "Bearer $token").get().build()
        ).execute().use { resp ->
            assertEquals(200, resp.code)
            val json = JSONObject(resp.body!!.string())
            assertTrue(json.getBoolean("curated"))
            assertTrue(json.getBoolean("single_auto_mode"))
            assertEquals(522, json.getInt("total_eligible_count"))
            assertEquals(523, json.getInt("total_stored_count"))
            val arr = json.getJSONArray("data")
            assertEquals("Default /v1/models must expose exactly 1 primary Auto entry", 1, arr.length())
            val autoObj = arr.getJSONObject(0)
            assertEquals(OpenCodeSelectorIds.STABLE_AUTO_ID, autoObj.getString("id"))
            assertEquals(OpenCodeSelectorIds.DISPLAY_NAME_AUTO, autoObj.getString("name"))
            assertEquals(OpenCodeSelectorIds.DISPLAY_PROVIDER_NAME, autoObj.getString("owned_by"))
            assertTrue(autoObj.getString("description").contains("fallback", ignoreCase = true))
            listedIds += autoObj.getString("id")
        }

        // Separate opt-in ?full=true diagnostic endpoint returns full eligible catalog (excluding duplicates)
        httpClient.newCall(
            Request.Builder().url("$baseUrl/v1/models?full=true").header("Authorization", "Bearer $token").get().build()
        ).execute().use { resp ->
            assertEquals(200, resp.code)
            val json = JSONObject(resp.body!!.string())
            assertFalse(json.getBoolean("curated"))
            assertFalse(json.getBoolean("single_auto_mode"))
            assertTrue(json.getJSONArray("data").length() >= 520)
        }

        // OpenCode config builder emits ONLY one primary JH GetWay Auto model entry ("jh-getway/auto")
        val settings = GatewaySettingsState(defaultModelId = "claude-sonnet-4", defaultProviderId = provA.id)
        val curatedCatalog = OpenCodeModelCatalogResolver.buildCuratedCatalog(
            providers = db.providerDao().getAllOnce(),
            localServers = db.localAiServerDao().getAllOnce(),
            allModels = db.modelDao().getAllOnce(),
            allKeys = db.apiKeyDao().getAllOnce(),
            routingRules = emptyList(),
            settings = settings
        )
        assertEquals(1, curatedCatalog.entries.size)
        assertEquals(OpenCodeSelectorIds.STABLE_AUTO_ID, curatedCatalog.entries.single().id)

        val openCodeConfig = JSONObject(
            CodingAgentConfigBuilder.buildOpenCodeConfigJson(
                agent = agent,
                baseV1Url = "$baseUrl/v1",
                apiKeyValue = token,
                settings = settings,
                curatedCatalog = curatedCatalog
            )
        )
        assertEquals("jhgetway/${OpenCodeSelectorIds.STABLE_AUTO_ID}", openCodeConfig.getString("model"))
        val configModels = openCodeConfig.getJSONObject("provider").getJSONObject("jhgetway").getJSONObject("models")
        assertEquals("opencode.json must contain only 1 default Auto entry", 1, configModels.length())
        assertTrue(configModels.has(OpenCodeSelectorIds.STABLE_AUTO_ID))
        assertEquals(
            OpenCodeSelectorIds.DISPLAY_NAME_AUTO,
            configModels.getJSONObject(OpenCodeSelectorIds.STABLE_AUTO_ID).getString("name")
        )
        assertTrue(
            configModels.getJSONObject(OpenCodeSelectorIds.STABLE_AUTO_ID).getString("description").isNotBlank()
        )

        // Stable Auto ID returned by /v1/models (and jhgetway-prefixed form) must resolve and execute cleanly
        seenUpstreamModels.clear()
        (listedIds + "jhgetway/${OpenCodeSelectorIds.STABLE_AUTO_ID}").forEach { selectorId ->
            val payload = JSONObject()
                .put("model", selectorId)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))
                .toString()
            httpClient.newCall(
                Request.Builder()
                    .url("$baseUrl/v1/chat/completions")
                    .header("Authorization", "Bearer $token")
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().use { resp ->
                assertEquals("Expected 200 for listed id '$selectorId'", 200, resp.code)
            }
        }
        // None of the virtual IDs were ever sent upstream!
        assertTrue(seenUpstreamModels.none { OpenCodeSelectorIds.isVirtualSelector(it) })
        // Existing DB rows untouched!
        assertEquals(523, db.modelDao().getAllOnce().size)
        assertEquals(2, db.providerDao().getAllOnce().size)
        assertEquals(2, db.apiKeyDao().getAllOnce().size)
    }

    // Scenarios 3, 4, 5, 18, 20:
    // auto never sends "auto" upstream; default resolves canonical default; auto prefers default when enabled;
    // explicit model IDs & jhgetway/<id> work; preference updates apply immediately without restart.
    @Test
    fun scenario03_04_05_18_20_virtualSelectorsResolveUpstreamAndRespectDefaultAndImmediatePrefs(): Unit = runBlocking {
        val (upPort, seenModels) = startScriptedUpstream { req, _ ->
            HttpStatusCode.OK to openAiOkJson(req.optString("model"))
        }
        val prov = ProviderEntity(
            id = "prov-1",
            name = "OpenAI",
            baseUrl = "http://127.0.0.1:$upPort/v1",
            apiFormat = ApiFormat.OPENAI,
            defaultModel = "gpt-4o-mini",
            priority = 1,
            status = ProviderStatus.CONNECTED,
            enabled = true
        )
        db.providerDao().upsert(prov)
        db.apiKeyDao().upsert(
            ApiKeyEntity(providerId = prov.id, label = "K1", encryptedKey = CryptoManager.encrypt("sk-1"), maskedPreview = "sk-1", enabled = true, isDefault = true)
        )
        db.modelDao().upsertAll(
            listOf(
                ModelEntity(
                    id = "m-default",
                    modelId = "gpt-4o-mini",
                    displayName = "GPT-4o mini",
                    providerId = prov.id,
                    providerName = prov.name,
                    supportsTools = true,
                    isDefault = true,
                    enabled = true,
                    accessTier = AccessTier.PAID
                ),
                ModelEntity(
                    id = "m-coder",
                    modelId = "gpt-5",
                    displayName = "GPT-5",
                    providerId = prov.id,
                    providerName = prov.name,
                    modelType = ModelType.CODING,
                    capabilitiesCsv = "CHAT,TOOLS,CODING",
                    supportsTools = true,
                    isDefault = false,
                    enabled = true,
                    accessTier = AccessTier.PAID
                )
            )
        )
        app.preferences.setDefaultModelAndProvider("gpt-4o-mini", prov.id)

        val token = "jhgw_ag_route_2"
        seedAgent(token)
        val (_, baseUrl) = startLocalGateway()

        suspend fun postChat(model: String, prompt: String): JSONObject {
            val payload = JSONObject()
                .put("model", model)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                .toString()
            return httpClient.newCall(
                Request.Builder()
                    .url("$baseUrl/v1/chat/completions")
                    .header("Authorization", "Bearer $token")
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().use { resp ->
                assertEquals(200, resp.code)
                JSONObject(resp.body!!.string())
            }
        }

        // Scenario 3 & 5: jh-getway/auto on non-coding prompt uses canonical default ("gpt-4o-mini")
        val autoResp = postChat(OpenCodeSelectorIds.STABLE_AUTO_ID, "Summarize a calendar invite")
        assertEquals("gpt-4o-mini", autoResp.getString("model"))
        assertEquals("gpt-4o-mini", seenModels.last())

        // Scenario 4: "default" resolves to canonical default ("gpt-4o-mini")
        val defResp = postChat("default", "Hi")
        assertEquals("gpt-4o-mini", defResp.getString("model"))

        // Scenario 20: Changing openCodeSelectionMode or preferConfiguredDefaultInAuto immediately changes jh-getway/auto routing
        app.preferences.updateSmartAutoPreferences(
            openCodeSelectionMode = OpenCodeSelectorIds.BEST_CODING,
            autoAllowPaidModels = true,
            preferConfiguredDefaultInAuto = true
        )
        val bestCodingViaAuto = postChat(OpenCodeSelectorIds.STABLE_AUTO_ID, "Any prompt")
        assertEquals("gpt-5", bestCodingViaAuto.getString("model"))

        app.preferences.updateSmartAutoPreferences(
            openCodeSelectionMode = "auto",
            autoAllowPaidModels = true,
            preferConfiguredDefaultInAuto = false
        )
        val codeResp = postChat(OpenCodeSelectorIds.STABLE_AUTO_ID, "Refactor ```kotlin\nfun main() {}\n``` and run unit test")
        assertEquals("gpt-5", codeResp.getString("model"))

        // Scenario 18: Explicit model ID still routes directly
        val explicitResp = postChat("jhgetway/gpt-4o-mini", "Explicit call")
        assertEquals("gpt-4o-mini", explicitResp.getString("model"))
        assertFalse(seenModels.any { it == "auto" || it == "jhgetway/auto" || it == "jh-getway/auto" || it == "default" })
    }

    // Scenarios 6, 7, 8, 13:
    // best-coding uses capability provenance & falls back cleanly; free contains ONLY verified free;
    // UNKNOWN never treated as free; free-only mode never routes to paid.
    @Test
    fun scenario06_07_08_13_bestCodingAndStrictFreeVerificationNeverTreatUnknownAsFree(): Unit = runBlocking {
        val (upPort, seenModels) = startScriptedUpstream { req, _ ->
            val model = req.optString("model")
            if (model.endsWith(":free")) {
                HttpStatusCode.TooManyRequests to """{"error":{"message":"free tier busy"}}"""
            } else {
                HttpStatusCode.OK to openAiOkJson(model)
            }
        }

        val provPaid = ProviderEntity(
            id = "prov-paid",
            name = "PaidProvider",
            baseUrl = "http://127.0.0.1:$upPort/v1",
            apiFormat = ApiFormat.OPENAI,
            defaultModel = "claude-3-5-sonnet",
            priority = 1,
            status = ProviderStatus.CONNECTED,
            enabled = true
        )
        val provFree = ProviderEntity(
            id = "prov-free",
            name = "FreeProvider",
            baseUrl = "http://127.0.0.1:$upPort/v1",
            apiFormat = ApiFormat.OPENAI,
            defaultModel = "llama-3.3-70b:free",
            priority = 2,
            status = ProviderStatus.CONNECTED,
            enabled = true
        )
        db.providerDao().upsert(provPaid)
        db.providerDao().upsert(provFree)
        db.apiKeyDao().upsert(ApiKeyEntity(providerId = provPaid.id, label = "KP", encryptedKey = CryptoManager.encrypt("sk-p"), maskedPreview = "sk-p", enabled = true, isDefault = true))
        db.apiKeyDao().upsert(ApiKeyEntity(providerId = provFree.id, label = "KF", encryptedKey = CryptoManager.encrypt("sk-f"), maskedPreview = "sk-f", enabled = true, isDefault = true))

        db.modelDao().upsertAll(
            listOf(
                ModelEntity(
                    id = "m-coder",
                    modelId = "claude-3-5-sonnet",
                    displayName = "Claude 3.5 Sonnet",
                    providerId = provPaid.id,
                    providerName = provPaid.name,
                    modelType = ModelType.CODING,
                    capabilitiesCsv = "CHAT,TOOLS,CODING",
                    supportsTools = true,
                    enabled = true,
                    accessTier = AccessTier.PAID
                ),
                ModelEntity(
                    id = "m-unknown",
                    modelId = "mystery-unknown-model",
                    displayName = "Mystery Unknown",
                    providerId = provPaid.id,
                    providerName = provPaid.name,
                    modelType = ModelType.GENERAL_CHAT,
                    supportsTools = true,
                    enabled = true,
                    accessTier = AccessTier.UNKNOWN,
                    freeTierVerified = false
                ),
                ModelEntity(
                    id = "m-free",
                    modelId = "llama-3.3-70b:free",
                    displayName = "Llama 3.3 Free",
                    providerId = provFree.id,
                    providerName = provFree.name,
                    modelType = ModelType.GENERAL_CHAT,
                    supportsTools = true,
                    enabled = true,
                    accessTier = AccessTier.FREE_TIER,
                    freeTierVerified = true
                )
            )
        )

        val engine = SmartRouterEngine(db, ProviderHttpClient())
        val baseSettings = GatewaySettingsState(localNetworkAccessAllowed = true, autoFreeIncludeLocal = false)

        // Scenario 6: best-coding selects coding-capable claude-3-5-sonnet
        val (codingCandidates, codingReason) = engine.resolveCandidates("best-coding", baseSettings, RoutingMode.AUTOMATIC)
        assertEquals("claude-3-5-sonnet", codingCandidates.first().modelId)
        assertTrue(codingReason.contains("Best Coding", ignoreCase = true))

        // Scenario 7 & 8: free strictly includes verified free model ONLY, NEVER UNKNOWN or PAID
        val (freeCandidates, _) = engine.resolveCandidates("free", baseSettings, RoutingMode.AUTOMATIC)
        assertEquals(listOf("llama-3.3-70b:free"), freeCandidates.map { it.modelId })
        assertFalse(freeCandidates.any { it.modelId == "mystery-unknown-model" || it.modelId == "claude-3-5-sonnet" })

        // Scenario 13: When free model returns 429, free-only execution MUST fail cleanly without falling back to paid model
        val resFree = engine.executeRoutedChat(
            requestedModelOrAlias = "free",
            messages = listOf(ChatMessagePayload("user", "hi")),
            settings = baseSettings.copy(maxRetries = 3)
        )
        assertFalse(resFree.execution.success)
        assertFalse("Paid model must never be called in free mode", seenModels.contains("claude-3-5-sonnet"))
        assertFalse("UNKNOWN model must never be called in free mode", seenModels.contains("mystery-unknown-model"))

        // autoAllowPaidModels = false behaves identically to free-only!
        db.providerDao().upsert(provFree.copy(status = ProviderStatus.CONNECTED, lastCheckedAt = null))
        seenModels.clear()
        val resAutoNoPaid = engine.executeRoutedChat(
            requestedModelOrAlias = "auto",
            messages = listOf(ChatMessagePayload("user", "hi")),
            settings = baseSettings.copy(autoAllowPaidModels = false)
        )
        assertFalse(resAutoNoPaid.execution.success)
        assertFalse(seenModels.contains("claude-3-5-sonnet"))
    }

    // Scenario 6b: best-coding falls back gracefully with documented general fallback when no coding metadata exists
    @Test
    fun scenario06b_bestCodingWithNoCodingMetadataUsesGeneralFallbackAndDoesNotBreak(): Unit = runBlocking {
        val prov = ProviderEntity(
            id = "prov-gen",
            name = "Generic",
            baseUrl = "https://example.invalid/v1",
            apiFormat = ApiFormat.OPENAI,
            defaultModel = "custom-chat-alpha",
            priority = 1,
            status = ProviderStatus.CONNECTED,
            enabled = true
        )
        db.providerDao().upsert(prov)
        db.apiKeyDao().upsert(ApiKeyEntity(providerId = prov.id, label = "KG", encryptedKey = CryptoManager.encrypt("sk-g"), maskedPreview = "sk-g", enabled = true, isDefault = true))
        db.modelDao().upsert(
            ModelEntity(
                id = "m-gen",
                modelId = "custom-chat-alpha",
                displayName = "Custom Chat Alpha",
                providerId = prov.id,
                providerName = prov.name,
                modelType = ModelType.GENERAL_CHAT,
                supportsTools = true,
                enabled = true
            )
        )
        val engine = SmartRouterEngine(db, ProviderHttpClient())
        val (candidates, reason) = engine.resolveCandidates("best-coding", GatewaySettingsState(), RoutingMode.AUTOMATIC)
        assertEquals(listOf("custom-chat-alpha"), candidates.map { it.modelId })
        assertTrue(reason.contains("Fallback", ignoreCase = true))
    }

    // Scenarios 9 & 10: Deleted or disabled default falls back cleanly; disabled models cannot ghost-route via provider.defaultModel
    @Test
    fun scenario09_10_disabledOrDeletedDefaultExcludedAndFallsBackCleanly(): Unit = runBlocking {
        val prov = ProviderEntity(
            id = "prov-1",
            name = "Provider 1",
            baseUrl = "https://example.invalid/v1",
            apiFormat = ApiFormat.OPENAI,
            defaultModel = "disabled-default-model",
            priority = 1,
            status = ProviderStatus.CONNECTED,
            enabled = true
        )
        db.providerDao().upsert(prov)
        db.apiKeyDao().upsert(ApiKeyEntity(providerId = prov.id, label = "K", encryptedKey = CryptoManager.encrypt("sk"), maskedPreview = "sk", enabled = true, isDefault = true))
        db.modelDao().upsertAll(
            listOf(
                ModelEntity(
                    id = "m-dis",
                    modelId = "disabled-default-model",
                    displayName = "Disabled Default",
                    providerId = prov.id,
                    providerName = prov.name,
                    isDefault = true,
                    enabled = false // Disabled!
                ),
                ModelEntity(
                    id = "m-healthy",
                    modelId = "healthy-backup-model",
                    displayName = "Healthy Backup",
                    providerId = prov.id,
                    providerName = prov.name,
                    isDefault = false,
                    enabled = true
                )
            )
        )
        val settings = GatewaySettingsState(defaultProviderId = prov.id, defaultModelId = "disabled-default-model")
        val catalog = OpenCodeModelCatalogResolver.buildCuratedCatalog(
            providers = db.providerDao().getAllOnce(),
            localServers = emptyList(),
            allModels = db.modelDao().getAllOnce(),
            allKeys = db.apiKeyDao().getAllOnce(),
            routingRules = emptyList(),
            settings = settings
        )
        assertFalse(catalog.entries.any { it.id == "disabled-default-model" })
        assertEquals("Provider 1 / healthy-backup-model", catalog.defaultResolvedTarget)
        assertTrue(catalog.defaultFallbackUsed)

        val engine = SmartRouterEngine(db, ProviderHttpClient())
        val (defCandidates, reason) = engine.resolveCandidates("default", settings, RoutingMode.AUTOMATIC)
        assertEquals(listOf("healthy-backup-model"), defCandidates.map { it.modelId })
        assertTrue(reason.contains("Fallback", ignoreCase = true))

        // Scenario 10: explicit request for disabled model is strictly rejected rather than ghost-routed via provider.defaultModel
        val (explicitDisabled, _) = engine.resolveCandidates("disabled-default-model", settings, RoutingMode.AUTOMATIC)
        assertTrue(explicitDisabled.isEmpty())
    }

    // Scenarios 11, 12, 14: 429 cooldown + fallback, 5xx bounded fallback, 400 malformed does NOT loop or poison cooldown
    @Test
    fun scenario11_12_14_rateLimit429And5xxFallbackWithCooldownWhile400NeitherLoopsNorPoisons(): Unit = runBlocking {
        var primaryStatus = HttpStatusCode.TooManyRequests
        val (primaryPort, primaryHits) = startScriptedUpstream { _, _ ->
            primaryStatus to """{"error":{"message":"primary error $primaryStatus"}}"""
        }
        val (backupPort, backupHits) = startScriptedUpstream { req, _ ->
            HttpStatusCode.OK to openAiOkJson(req.optString("model"), "Recovered from backup")
        }

        val p1 = ProviderEntity(id = "p1", name = "Primary", baseUrl = "http://127.0.0.1:$primaryPort/v1", apiFormat = ApiFormat.OPENAI, defaultModel = "gpt-4o-mini", priority = 1, status = ProviderStatus.CONNECTED, enabled = true)
        val p2 = ProviderEntity(id = "p2", name = "Backup", baseUrl = "http://127.0.0.1:$backupPort/v1", apiFormat = ApiFormat.OPENAI, defaultModel = "gpt-4o", priority = 2, status = ProviderStatus.CONNECTED, enabled = true)
        db.providerDao().upsert(p1)
        db.providerDao().upsert(p2)
        db.apiKeyDao().upsert(ApiKeyEntity(providerId = "p1", label = "K1", encryptedKey = CryptoManager.encrypt("sk-1"), maskedPreview = "1", enabled = true, isDefault = true))
        db.apiKeyDao().upsert(ApiKeyEntity(providerId = "p2", label = "K2", encryptedKey = CryptoManager.encrypt("sk-2"), maskedPreview = "2", enabled = true, isDefault = true))
        db.modelDao().upsert(ModelEntity(id = "p1::gpt-4o-mini", modelId = "gpt-4o-mini", displayName = "gpt-4o-mini", providerId = "p1", providerName = "Primary", isDefault = true, enabled = true))
        db.modelDao().upsert(ModelEntity(id = "p2::gpt-4o", modelId = "gpt-4o", displayName = "gpt-4o", providerId = "p2", providerName = "Backup", enabled = true))

        val settings = GatewaySettingsState(
            defaultProviderId = "p1",
            defaultModelId = "gpt-4o-mini",
            fallbackEnabled = true,
            maxRetries = 2,
            localNetworkAccessAllowed = true
        )
        val engine = SmartRouterEngine(db, ProviderHttpClient())

        // Scenario 11: 429 on Primary triggers fallback to Backup and marks Primary RATE_LIMITED (cooling down)
        val r429 = engine.executeRoutedChat("auto", listOf(ChatMessagePayload("user", "test 429")), settings)
        assertTrue(r429.execution.success)
        assertTrue(r429.decision.fallbackUsed)
        assertEquals("Backup", r429.decision.selectedProvider)
        assertEquals(ProviderStatus.RATE_LIMITED, db.providerDao().getById("p1")?.status)

        // Immediately next `auto` call skips cooling-down Primary and goes directly to Backup
        val prevPrimaryHits = primaryHits.size
        val rCooldown = engine.executeRoutedChat("auto", listOf(ChatMessagePayload("user", "during cooldown")), settings)
        assertTrue(rCooldown.execution.success)
        assertEquals("Backup", rCooldown.decision.selectedProvider)
        assertEquals(prevPrimaryHits, primaryHits.size)

        // Reset Primary to healthy CONNECTED, test Scenario 12 (503 fallback)
        db.providerDao().upsert(p1.copy(status = ProviderStatus.CONNECTED, lastCheckedAt = null))
        primaryStatus = HttpStatusCode.ServiceUnavailable
        val r503 = engine.executeRoutedChat("auto", listOf(ChatMessagePayload("user", "test 503")), settings)
        assertTrue(r503.execution.success)
        assertTrue(r503.decision.fallbackUsed)
        assertEquals(ProviderStatus.ERROR, db.providerDao().getById("p1")?.status)

        // Scenario 14: Reset Primary to CONNECTED, simulate HTTP 400 malformed request -> MUST stop on attempt 1 & keep Primary CONNECTED!
        db.providerDao().upsert(p1.copy(status = ProviderStatus.CONNECTED, lastCheckedAt = null))
        primaryStatus = HttpStatusCode.BadRequest
        val backupCountBefore400 = backupHits.size
        val r400 = engine.executeRoutedChat("auto", listOf(ChatMessagePayload("user", "bad request")), settings)
        assertFalse(r400.execution.success)
        assertEquals(400, r400.execution.statusCode)
        assertEquals(1, r400.decision.attemptCount)
        assertEquals("Backup must NOT be called on non-retryable 400", backupCountBefore400, backupHits.size)
        assertEquals("Healthy provider status must NOT be poisoned by 400", ProviderStatus.CONNECTED, db.providerDao().getById("p1")?.status)
    }

    // Scenario 15: Streaming SSE pre-stream fallback succeeds cleanly; mid-stream failure never duplicates or restarts from scratch
    @Test
    fun scenario15_streamingPreStreamFallsBackCleanlyWhileMidStreamNeverRestarts(): Unit = runBlocking {
        var primaryMode = "pre_stream_fail"
        val primarySrv = embeddedServer(CIO, configure = { connector { host = "127.0.0.1"; port = 0 } }) {
            routing {
                post("/v1/chat/completions") {
                    if (primaryMode == "pre_stream_fail") {
                        call.respondText("""{"error":{"message":"rate limit"}}""", ContentType.Application.Json, HttpStatusCode.TooManyRequests)
                    } else {
                        call.respondTextWriter(ContentType.Text.EventStream, HttpStatusCode.OK) {
                            write("""data: {"choices":[{"index":0,"delta":{"content":"partial-"}}]}""" + "\n\n")
                            flush()
                            write("""data: {"error":{"message":"Upstream stream reset","type":"server_error"}}""" + "\n\n")
                            flush()
                        }
                    }
                }
            }
        }
        primarySrv.start(wait = false)
        upstreamServers += primarySrv
        val primaryPort = runBlocking { primarySrv.engine.resolvedConnectors().first().port }

        val backupSrv = embeddedServer(CIO, configure = { connector { host = "127.0.0.1"; port = 0 } }) {
            routing {
                post("/v1/chat/completions") {
                    call.respondTextWriter(ContentType.Text.EventStream, HttpStatusCode.OK) {
                        write("""data: {"model":"gpt-4o","choices":[{"index":0,"delta":{"content":"backup-ok"}}]}""" + "\n\n")
                        write("""data: {"model":"gpt-4o","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""" + "\n\n")
                        write("data: [DONE]\n\n")
                        flush()
                    }
                }
            }
        }
        backupSrv.start(wait = false)
        upstreamServers += backupSrv
        val backupPort = runBlocking { backupSrv.engine.resolvedConnectors().first().port }

        val p1 = ProviderEntity(id = "p1", name = "PrimaryStream", baseUrl = "http://127.0.0.1:$primaryPort/v1", apiFormat = ApiFormat.OPENAI, defaultModel = "gpt-4o-mini", priority = 1, status = ProviderStatus.CONNECTED, enabled = true)
        val p2 = ProviderEntity(id = "p2", name = "BackupStream", baseUrl = "http://127.0.0.1:$backupPort/v1", apiFormat = ApiFormat.OPENAI, defaultModel = "gpt-4o", priority = 2, status = ProviderStatus.CONNECTED, enabled = true)
        db.providerDao().upsert(p1)
        db.providerDao().upsert(p2)
        db.apiKeyDao().upsert(ApiKeyEntity(providerId = "p1", label = "K1", encryptedKey = CryptoManager.encrypt("sk-1"), maskedPreview = "1", enabled = true, isDefault = true))
        db.apiKeyDao().upsert(ApiKeyEntity(providerId = "p2", label = "K2", encryptedKey = CryptoManager.encrypt("sk-2"), maskedPreview = "2", enabled = true, isDefault = true))
        db.modelDao().upsert(ModelEntity(id = "p1::gpt-4o-mini", modelId = "gpt-4o-mini", displayName = "gpt-4o-mini", providerId = "p1", providerName = "PrimaryStream", isDefault = true, enabled = true))
        db.modelDao().upsert(ModelEntity(id = "p2::gpt-4o", modelId = "gpt-4o", displayName = "gpt-4o", providerId = "p2", providerName = "BackupStream", enabled = true))

        val settings = GatewaySettingsState(
            defaultProviderId = "p1",
            defaultModelId = "gpt-4o-mini",
            fallbackEnabled = true,
            maxRetries = 2,
            localNetworkAccessAllowed = true
        )
        val engine = SmartRouterEngine(db, ProviderHttpClient())

        // 15a: Pre-stream failure -> retries cleanly on backup and yields only backup deltas
        val preEvents = engine.streamRoutedChat("auto", listOf(ChatMessagePayload("user", "hi")), settings).toList()
        val deltasPre = preEvents.mapNotNull { (ev, _) -> (ev as? StreamEvent.Delta)?.text }.joinToString("")
        assertEquals("backup-ok", deltasPre)
        val completedPre = preEvents.last().second!!
        assertTrue(completedPre.fallbackUsed)
        assertEquals("BackupStream", completedPre.selectedProvider)

        // 15b: Reset primary & emit partial chunk before disconnect -> MUST NOT duplicate or fall back to backup mid-stream!
        db.providerDao().upsert(p1.copy(status = ProviderStatus.CONNECTED, lastCheckedAt = null))
        primaryMode = "mid_stream_drop"
        val midEvents = engine.streamRoutedChat("auto", listOf(ChatMessagePayload("user", "hi")), settings).toList()
        val deltasMid = midEvents.mapNotNull { (ev, _) -> (ev as? StreamEvent.Delta)?.text }
        assertEquals(listOf("partial-"), deltasMid)
        assertTrue(midEvents.any { it.first is StreamEvent.Failed })
        assertFalse("Mid-stream failure must not fallback after chunks emitted", midEvents.last().second?.fallbackUsed == true)
    }

    @Test
    fun e2e_jhGetwayAuto_succeedsAndNeverSendsVirtualIdUpstream(): Unit = runBlocking {
        val seenAuthHeaders = CopyOnWriteArrayList<String>()
        val seenModels = CopyOnWriteArrayList<String>()
        val srv = embeddedServer(CIO, configure = { connector { host = "127.0.0.1"; port = 0 } }) {
            routing {
                post("/v1/chat/completions") {
                    seenAuthHeaders += call.request.headers["Authorization"].orEmpty()
                    val req = JSONObject(call.receiveText())
                    val model = req.optString("model")
                    seenModels += model
                    call.respondText(openAiOkJson(model, "Hello from $model"), ContentType.Application.Json, HttpStatusCode.OK)
                }
            }
        }
        srv.start(wait = false)
        upstreamServers += srv
        val upPort = srv.engine.resolvedConnectors().first().port

        val prov = ProviderEntity(
            id = "prov-auto-ok",
            name = "PrimaryOpenAI",
            baseUrl = "http://127.0.0.1:$upPort/v1",
            apiFormat = ApiFormat.OPENAI,
            defaultModel = "gpt-4o",
            priority = 1,
            status = ProviderStatus.CONNECTED,
            enabled = true
        )
        db.providerDao().upsert(prov)
        val secretApiKey = "sk-secret-primary-never-log-me-999"
        db.apiKeyDao().upsert(
            ApiKeyEntity(
                providerId = prov.id,
                label = "Key1",
                encryptedKey = CryptoManager.encrypt(secretApiKey),
                maskedPreview = "sk-••••999",
                enabled = true,
                isDefault = true
            )
        )
        db.modelDao().upsert(
            ModelEntity(
                id = "${prov.id}::gpt-4o",
                modelId = "gpt-4o",
                displayName = "GPT-4o",
                providerId = prov.id,
                providerName = prov.name,
                isDefault = true,
                enabled = true
            )
        )

        val agentToken = "jhgw_opencode_auto_ok_token"
        seedAgent(token = agentToken)
        val (_, baseUrl) = startLocalGateway()

        for (autoVariant in listOf(OpenCodeSelectorIds.STABLE_AUTO_ID, "jhgetway/${OpenCodeSelectorIds.STABLE_AUTO_ID}")) {
            val reqBody = JSONObject()
                .put("model", autoVariant)
                .put("stream", false)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "ping")))
                .toString()
            val req = Request.Builder()
                .url("$baseUrl/v1/chat/completions")
                .header("Authorization", "Bearer $agentToken")
                .post(reqBody.toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                assertEquals(200, resp.code)
                val json = JSONObject(resp.body!!.string())
                val content = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                assertEquals("Hello from gpt-4o", content)
            }
        }

        assertEquals(listOf("gpt-4o", "gpt-4o"), seenModels.toList())
        assertTrue(seenAuthHeaders.all { it == "Bearer $secretApiKey" })
        val persistedLogs = db.metricAndLogDao().observeLogs().first()
        val logsJoined = persistedLogs.joinToString("\n") { "${it.status} ${it.relatedEntity} ${it.safeMessage}" }
        assertFalse("API key must never appear in persisted logs", logsJoined.contains(secretApiKey))
        assertTrue("Safe log should include requested Auto ID and upstream HTTP", logsJoined.contains("jh-getway/auto") && logsJoined.contains("http=200"))
        val latestLogs = db.metricAndLogDao().getMetricsOnce()
        assertEquals(2, latestLogs.size)
        assertTrue(latestLogs.all { it.success && it.modelId == "gpt-4o" && it.fallbackAttempts == 0 && it.statusCode == 200 })
    }

    @Test
    fun e2e_jhGetwayAuto_failedFirstModelMovesToDifferentModelNeverRepeatsFailedModel(): Unit = runBlocking {
        val brokenHits = CopyOnWriteArrayList<String>()
        val brokenKeys = CopyOnWriteArrayList<String>()
        val backupHits = CopyOnWriteArrayList<String>()

        val primarySrv = embeddedServer(CIO, configure = { connector { host = "127.0.0.1"; port = 0 } }) {
            routing {
                post("/v1/chat/completions") {
                    brokenKeys += call.request.headers["Authorization"].orEmpty()
                    val req = JSONObject(call.receiveText())
                    brokenHits += req.optString("model")
                    // Inline HTTP 200 JSON error payload even when stream=true
                    call.respondText(
                        """{"error":{"message":"Model temporarily overloaded","type":"server_error","code":503}}""",
                        ContentType.Application.Json,
                        HttpStatusCode.OK
                    )
                }
            }
        }
        primarySrv.start(wait = false)
        upstreamServers += primarySrv
        val p1Port = primarySrv.engine.resolvedConnectors().first().port

        val backupSrv = embeddedServer(CIO, configure = { connector { host = "127.0.0.1"; port = 0 } }) {
            routing {
                post("/v1/chat/completions") {
                    val req = JSONObject(call.receiveText())
                    val model = req.optString("model")
                    backupHits += model
                    call.respondTextWriter(ContentType.Text.EventStream, HttpStatusCode.OK) {
                        write("""data: {"model":"$model","choices":[{"index":0,"delta":{"role":"assistant","content":"ok-via-"}}]}""" + "\n\n")
                        write("""data: {"model":"$model","choices":[{"index":0,"delta":{"content":"$model"}}]}""" + "\n\n")
                        write("""data: {"model":"$model","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""" + "\n\n")
                        write("data: [DONE]\n\n")
                        flush()
                    }
                }
            }
        }
        backupSrv.start(wait = false)
        upstreamServers += backupSrv
        val p2Port = backupSrv.engine.resolvedConnectors().first().port

        val p1 = ProviderEntity(id = "p-broken", name = "PrimaryBroken", baseUrl = "http://127.0.0.1:$p1Port/v1", apiFormat = ApiFormat.OPENAI, defaultModel = "broken-model", priority = 1, status = ProviderStatus.CONNECTED, enabled = true)
        val p2 = ProviderEntity(id = "p-healthy", name = "BackupHealthy", baseUrl = "http://127.0.0.1:$p2Port/v1", apiFormat = ApiFormat.OPENAI, defaultModel = "healthy-backup-model", priority = 2, status = ProviderStatus.CONNECTED, enabled = true)
        db.providerDao().upsert(p1)
        db.providerDao().upsert(p2)
        // Multiple keys on broken primary: Auto must NOT burn retries repeating broken-model across duplicate keys!
        db.apiKeyDao().upsert(ApiKeyEntity(providerId = p1.id, label = "K1", selectionPolicy = KeySelectionPolicy.FAILOVER, priority = 1, encryptedKey = CryptoManager.encrypt("sk-broken-1"), maskedPreview = "1", enabled = true, isDefault = true))
        db.apiKeyDao().upsert(ApiKeyEntity(providerId = p1.id, label = "K2", selectionPolicy = KeySelectionPolicy.FAILOVER, priority = 2, encryptedKey = CryptoManager.encrypt("sk-broken-2"), maskedPreview = "2", enabled = true, isDefault = false))
        db.apiKeyDao().upsert(ApiKeyEntity(providerId = p2.id, label = "K3", encryptedKey = CryptoManager.encrypt("sk-backup-ok"), maskedPreview = "3", enabled = true, isDefault = true))
        db.modelDao().upsertAll(
            listOf(
                ModelEntity(id = "${p1.id}::broken-model", modelId = "broken-model", displayName = "broken-model", providerId = p1.id, providerName = p1.name, isDefault = true, enabled = true),
                ModelEntity(id = "${p2.id}::healthy-backup-model", modelId = "healthy-backup-model", displayName = "healthy-backup-model", providerId = p2.id, providerName = p2.name, enabled = true)
            )
        )

        val token = "jhgw_opencode_fallback_diff_model"
        seedAgent(token = token)
        val (_, baseUrl) = startLocalGateway()
        app.preferences.setDefaultModelAndProvider("broken-model", p1.id)
        app.preferences.updateRoutingSettings(RoutingMode.PRIORITY, fallbackEnabled = true, maxRetries = 4)

        val reqBody = JSONObject()
            .put("model", OpenCodeSelectorIds.STABLE_AUTO_ID)
            .put("stream", true)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "help")))
            .toString()
        val req = Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .header("Authorization", "Bearer $token")
            .post(reqBody.toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(req).execute().use { resp ->
            assertEquals(200, resp.code)
            val sseText = resp.body!!.string()
            assertTrue(sseText.contains("ok-via-"))
            assertTrue(sseText.contains("healthy-backup-model"))
            assertTrue(sseText.contains("data: [DONE]"))
        }

        assertEquals("Failed model must be tried only once before moving to a different model", 1, brokenHits.size)
        assertEquals(listOf("broken-model"), brokenHits.toList())
        assertEquals(listOf("healthy-backup-model"), backupHits.toList())

        val metrics = db.metricAndLogDao().getMetricsOnce()
        assertEquals(1, metrics.size)
        assertTrue(metrics.first().success)
        assertEquals("healthy-backup-model", metrics.first().modelId)
        assertEquals(1, metrics.first().fallbackAttempts)
        val logsText = db.metricAndLogDao().observeLogs().first().joinToString("\n") { it.safeMessage }
        assertTrue(logsText.contains("fallbacks=1"))
        assertFalse(logsText.contains("sk-broken"))
        assertFalse(logsText.contains("sk-backup-ok"))
    }

    @Test
    fun e2e_jhGetwayAuto_rateLimited429AndEmptyStreamTriggerFallback(): Unit = runBlocking {
        var primaryMode = "429"
        val primaryModelsSeen = CopyOnWriteArrayList<String>()
        val primarySrv = embeddedServer(CIO, configure = { connector { host = "127.0.0.1"; port = 0 } }) {
            routing {
                post("/v1/chat/completions") {
                    val req = JSONObject(call.receiveText())
                    primaryModelsSeen += req.optString("model")
                    if (primaryMode == "429") {
                        call.respondText(
                            """{"error":{"message":"Rate limit reached for requests"}}""",
                            ContentType.Application.Json,
                            HttpStatusCode.TooManyRequests
                        )
                    } else {
                        // Empty SSE stream without content or tool calls
                        call.respondTextWriter(ContentType.Text.EventStream, HttpStatusCode.OK) {
                            write("""data: {"choices":[]}""" + "\n\n")
                            write("data: [DONE]\n\n")
                            flush()
                        }
                    }
                }
            }
        }
        primarySrv.start(wait = false)
        upstreamServers += primarySrv
        val p1Port = primarySrv.engine.resolvedConnectors().first().port

        val backupSrv = embeddedServer(CIO, configure = { connector { host = "127.0.0.1"; port = 0 } }) {
            routing {
                post("/v1/chat/completions") {
                    val req = JSONObject(call.receiveText())
                    val m = req.optString("model")
                    call.respondTextWriter(ContentType.Text.EventStream, HttpStatusCode.OK) {
                        write("""data: {"model":"$m","choices":[{"index":0,"delta":{"content":"recovered-$primaryMode"}}]}""" + "\n\n")
                        write("""data: {"model":"$m","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""" + "\n\n")
                        write("data: [DONE]\n\n")
                        flush()
                    }
                }
            }
        }
        backupSrv.start(wait = false)
        upstreamServers += backupSrv
        val p2Port = backupSrv.engine.resolvedConnectors().first().port

        val p1 = ProviderEntity(id = "p-429", name = "RateLimitedPrimary", baseUrl = "http://127.0.0.1:$p1Port/v1", apiFormat = ApiFormat.OPENAI, defaultModel = "model-a", priority = 1, status = ProviderStatus.CONNECTED, enabled = true)
        val p2 = ProviderEntity(id = "p-ok2", name = "BackupTwo", baseUrl = "http://127.0.0.1:$p2Port/v1", apiFormat = ApiFormat.OPENAI, defaultModel = "model-b", priority = 2, status = ProviderStatus.CONNECTED, enabled = true)
        db.providerDao().upsert(p1)
        db.providerDao().upsert(p2)
        db.apiKeyDao().upsert(ApiKeyEntity(providerId = p1.id, label = "K1", encryptedKey = CryptoManager.encrypt("sk-111"), maskedPreview = "111", enabled = true, isDefault = true))
        db.apiKeyDao().upsert(ApiKeyEntity(providerId = p2.id, label = "K2", encryptedKey = CryptoManager.encrypt("sk-222"), maskedPreview = "222", enabled = true, isDefault = true))
        db.modelDao().upsertAll(
            listOf(
                ModelEntity(id = "${p1.id}::model-a", modelId = "model-a", displayName = "Model A", providerId = p1.id, providerName = p1.name, isDefault = true, enabled = true),
                ModelEntity(id = "${p2.id}::model-b", modelId = "model-b", displayName = "Model B", providerId = p2.id, providerName = p2.name, enabled = true)
            )
        )

        val token = "jhgw_opencode_429_empty"
        seedAgent(token = token)
        val (_, baseUrl) = startLocalGateway()
        app.preferences.setDefaultModelAndProvider("model-a", p1.id)
        app.preferences.updateRoutingSettings(RoutingMode.PRIORITY, fallbackEnabled = true, maxRetries = 2)

        fun sendStreamReq(): String {
            val reqBody = JSONObject()
                .put("model", OpenCodeSelectorIds.STABLE_AUTO_ID)
                .put("stream", true)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "test")))
                .toString()
            val req = Request.Builder()
                .url("$baseUrl/v1/chat/completions")
                .header("Authorization", "Bearer $token")
                .post(reqBody.toRequestBody("application/json".toMediaType()))
                .build()
            return httpClient.newCall(req).execute().use { resp ->
                assertEquals(200, resp.code)
                resp.body!!.string()
            }
        }

        // 1) 429 triggers fallback + cooldown
        val body429 = sendStreamReq()
        assertTrue(body429.contains("recovered-429"))
        assertEquals(ProviderStatus.RATE_LIMITED, db.providerDao().getById(p1.id)?.status)

        // 2) Reset primary health, verify empty stream response triggers fallback rather than "No answer from the model"
        db.providerDao().upsert(p1.copy(status = ProviderStatus.CONNECTED, lastCheckedAt = null))
        primaryMode = "empty"
        val bodyEmpty = sendStreamReq()
        assertTrue(bodyEmpty.contains("recovered-empty"))
        assertTrue(primaryModelsSeen.all { it == "model-a" })
    }

    @Test
    fun e2e_jhGetwayAuto_highVolumeStreamingAndStreamOptionsCompatAndToolDeltasCompleteCleanly(): Unit = runBlocking {
        var attemptNo = 0
        val srv = embeddedServer(CIO, configure = { connector { host = "127.0.0.1"; port = 0 } }) {
            routing {
                post("/v1/chat/completions") {
                    val req = JSONObject(call.receiveText())
                    attemptNo++
                    // Simulate proxy that rejects stream_options on first attempt
                    if (attemptNo == 1 && req.has("stream_options")) {
                        call.respondText(
                            """{"error":{"message":"Extra inputs are not permitted: stream_options"}}""",
                            ContentType.Application.Json,
                            HttpStatusCode.BadRequest
                        )
                        return@post
                    }
                    call.respondTextWriter(ContentType.Text.EventStream, HttpStatusCode.OK) {
                        // Emit 90 text chunks (> channel buffer 64) + a tool call delta to prove zero drops!
                        for (i in 0 until 90) {
                            write("""data: {"model":"gpt-4o","choices":[{"index":0,"delta":{"content":"[$i]"}}]}""" + "\n\n")
                        }
                        write("""data: {"model":"gpt-4o","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"read_file","arguments":""}}]}}]}""" + "\n\n")
                        write("""data: {"model":"gpt-4o","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"path\":\"a.kt\"}"}}]}}]}""" + "\n\n")
                        write("""data: {"model":"gpt-4o","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":25,"completion_tokens":95,"total_tokens":120}}""" + "\n\n")
                        write("data: [DONE]\n\n")
                        flush()
                    }
                }
            }
        }
        srv.start(wait = false)
        upstreamServers += srv
        val upPort = srv.engine.resolvedConnectors().first().port

        val prov = ProviderEntity(id = "p-stream", name = "HighVolumeStream", baseUrl = "http://127.0.0.1:$upPort/v1", apiFormat = ApiFormat.OPENAI, defaultModel = "gpt-4o", priority = 1, status = ProviderStatus.CONNECTED, enabled = true)
        db.providerDao().upsert(prov)
        db.apiKeyDao().upsert(ApiKeyEntity(providerId = prov.id, label = "K", encryptedKey = CryptoManager.encrypt("sk-stream-test"), maskedPreview = "sk", enabled = true, isDefault = true))
        db.modelDao().upsert(ModelEntity(id = "${prov.id}::gpt-4o", modelId = "gpt-4o", displayName = "gpt-4o", providerId = prov.id, providerName = prov.name, supportsTools = true, isDefault = true, enabled = true))

        val token = "jhgw_opencode_highvol_stream"
        seedAgent(token = token)
        val (_, baseUrl) = startLocalGateway()

        val reqBody = JSONObject()
            .put("model", OpenCodeSelectorIds.STABLE_AUTO_ID)
            .put("stream", true)
            .put("tools", JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "read_file"))))
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "read a.kt")))
            .toString()
        val req = Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .header("Authorization", "Bearer $token")
            .post(reqBody.toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(req).execute().use { resp ->
            assertEquals(200, resp.code)
            val sse = resp.body!!.string()
            // Every single one of the 90 deltas must arrive intact (zero trySend drops!)
            for (i in 0 until 90) {
                assertTrue("Missing chunk [$i]", sse.contains("[$i]"))
            }
            assertTrue(sse.contains("read_file"))
            assertTrue(sse.contains("\"finish_reason\":\"tool_calls\""))
            assertTrue(sse.contains("data: [DONE]"))
        }
        assertEquals(2, attemptNo)
    }

    @Test
    fun e2e_jhGetwayAuto_allCandidatesFailOrHangReturnsPromptNon200ErrorWithoutHanging(): Unit = runBlocking {
        val slowSrv = embeddedServer(CIO, configure = { connector { host = "127.0.0.1"; port = 0 } }) {
            routing {
                post("/v1/chat/completions") {
                    delay(4_500)
                    call.respondText(openAiOkJson("slow-model", "too late"), ContentType.Application.Json, HttpStatusCode.OK)
                }
            }
        }
        slowSrv.start(wait = false)
        upstreamServers += slowSrv
        val slowPort = slowSrv.engine.resolvedConnectors().first().port

        val deadSrv = embeddedServer(CIO, configure = { connector { host = "127.0.0.1"; port = 0 } }) {
            routing {
                post("/v1/chat/completions") {
                    call.respondText("""{"error":{"message":"Backup overloaded"}}""", ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
                }
            }
        }
        deadSrv.start(wait = false)
        upstreamServers += deadSrv
        val deadPort = deadSrv.engine.resolvedConnectors().first().port

        val p1 = ProviderEntity(id = "p-slow", name = "SlowPrimary", baseUrl = "http://127.0.0.1:$slowPort/v1", apiFormat = ApiFormat.OPENAI, defaultModel = "slow-model", priority = 1, status = ProviderStatus.CONNECTED, enabled = true)
        val p2 = ProviderEntity(id = "p-dead", name = "DeadBackup", baseUrl = "http://127.0.0.1:$deadPort/v1", apiFormat = ApiFormat.OPENAI, defaultModel = "dead-model", priority = 2, status = ProviderStatus.CONNECTED, enabled = true)
        db.providerDao().upsert(p1)
        db.providerDao().upsert(p2)
        db.apiKeyDao().upsert(ApiKeyEntity(providerId = p1.id, label = "K1", encryptedKey = CryptoManager.encrypt("sk-slow-secret"), maskedPreview = "1", enabled = true, isDefault = true))
        db.apiKeyDao().upsert(ApiKeyEntity(providerId = p2.id, label = "K2", encryptedKey = CryptoManager.encrypt("sk-dead-secret"), maskedPreview = "2", enabled = true, isDefault = true))
        db.modelDao().upsertAll(
            listOf(
                ModelEntity(id = "${p1.id}::slow-model", modelId = "slow-model", displayName = "slow-model", providerId = p1.id, providerName = p1.name, isDefault = true, enabled = true),
                ModelEntity(id = "${p2.id}::dead-model", modelId = "dead-model", displayName = "dead-model", providerId = p2.id, providerName = p2.name, enabled = true)
            )
        )

        val token = "jhgw_opencode_all_fail_promptly"
        seedAgent(token = token)
        val (_, baseUrl) = startLocalGateway()
        // Short 3s gateway timeout proves bounded timeout across SlowPrimary + DeadBackup!
        app.preferences.updateGatewaySettings(
            name = "Bounded Timeout GW",
            mode = GatewayMode.LOCAL,
            host = "127.0.0.1",
            port = 0,
            remoteUrl = "",
            authRequired = true,
            timeoutSec = 3,
            maxRequestKb = 512,
            streaming = true,
            rateLimitPerMin = 500,
            loggingLevel = LoggingLevel.METADATA_ONLY
        )
        app.preferences.updateRoutingSettings(RoutingMode.PRIORITY, fallbackEnabled = true, maxRetries = 2)

        for (isStream in listOf(true, false)) {
            db.providerDao().upsert(p1.copy(status = ProviderStatus.CONNECTED, lastCheckedAt = null))
            db.providerDao().upsert(p2.copy(status = ProviderStatus.CONNECTED, lastCheckedAt = null))
            val t0 = System.nanoTime()
            val reqBody = JSONObject()
                .put("model", OpenCodeSelectorIds.STABLE_AUTO_ID)
                .put("stream", isStream)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "ping")))
                .toString()
            val req = Request.Builder()
                .url("$baseUrl/v1/chat/completions")
                .header("Authorization", "Bearer $token")
                .post(reqBody.toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                val elapsedMs = (System.nanoTime() - t0) / 1_000_000L
                assertTrue("Expected prompt failure under 8s, got ${elapsedMs}ms", elapsedMs < 8_000L)
                assertTrue("All-failed must return HTTP 4xx/5xx error (was ${resp.code})", resp.code in 400..599)
                val body = resp.body!!.string()
                val errObj = JSONObject(body).getJSONObject("error")
                assertTrue(errObj.getString("message").isNotBlank())
                assertFalse(body.contains("sk-slow-secret"))
                assertFalse(body.contains("sk-dead-secret"))
            }
        }
    }

    @Test
    fun e2e_directRealModelRequest_continuesToWorkDirectly(): Unit = runBlocking {
        val seen = CopyOnWriteArrayList<String>()
        val (upPort, _) = startScriptedUpstream { req, _ ->
            val m = req.optString("model")
            seen += m
            HttpStatusCode.OK to openAiOkJson(m, "Direct $m")
        }

        val prov = ProviderEntity(id = "p-direct", name = "DirectProvider", baseUrl = "http://127.0.0.1:$upPort/v1", apiFormat = ApiFormat.OPENAI, defaultModel = "gpt-4o", priority = 1, status = ProviderStatus.CONNECTED, enabled = true)
        db.providerDao().upsert(prov)
        db.apiKeyDao().upsert(ApiKeyEntity(providerId = prov.id, label = "K", encryptedKey = CryptoManager.encrypt("sk-direct"), maskedPreview = "sk", enabled = true, isDefault = true))
        db.modelDao().upsertAll(
            listOf(
                ModelEntity(id = "${prov.id}::gpt-4o", modelId = "gpt-4o", displayName = "gpt-4o", providerId = prov.id, providerName = prov.name, isDefault = true, enabled = true),
                ModelEntity(id = "${prov.id}::gpt-4.1-mini", modelId = "gpt-4.1-mini", displayName = "gpt-4.1-mini", providerId = prov.id, providerName = prov.name, enabled = true)
            )
        )

        val token = "jhgw_opencode_direct_model"
        seedAgent(token = token)
        val (_, baseUrl) = startLocalGateway()

        val reqBody = JSONObject()
            .put("model", "gpt-4.1-mini")
            .put("stream", false)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "direct")))
            .toString()
        val req = Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .header("Authorization", "Bearer $token")
            .post(reqBody.toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(req).execute().use { resp ->
            assertEquals(200, resp.code)
            val content = JSONObject(resp.body!!.string())
                .getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            assertEquals("Direct gpt-4.1-mini", content)
        }
        assertEquals(listOf("gpt-4.1-mini"), seen.toList())
    }
}
