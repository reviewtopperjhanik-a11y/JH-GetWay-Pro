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

        // Default /v1/models must be curated (<= 20 entries, NOT 520+), and must expose logical selectors
        val listedIds = mutableListOf<String>()
        httpClient.newCall(
            Request.Builder().url("$baseUrl/v1/models").header("Authorization", "Bearer $token").get().build()
        ).execute().use { resp ->
            assertEquals(200, resp.code)
            val json = JSONObject(resp.body!!.string())
            assertTrue(json.getBoolean("curated"))
            assertEquals(522, json.getInt("total_eligible_count"))
            val arr = json.getJSONArray("data")
            for (i in 0 until arr.length()) {
                listedIds += arr.getJSONObject(i).getString("id")
            }
            assertTrue("Curated list size should be compact (< 25), got ${listedIds.size}", listedIds.size < 25)
            assertTrue(listedIds.containsAll(listOf("auto", "default", "best-coding", "free")))
            // No duplicate IDs
            assertEquals(listedIds.size, listedIds.map { it.lowercase() }.distinct().size)
        }

        // Explicit ?full=true must return full eligible catalog (excluding duplicates)
        httpClient.newCall(
            Request.Builder().url("$baseUrl/v1/models?full=true").header("Authorization", "Bearer $token").get().build()
        ).execute().use { resp ->
            assertEquals(200, resp.code)
            val json = JSONObject(resp.body!!.string())
            assertFalse(json.getBoolean("curated"))
            assertTrue(json.getJSONArray("data").length() >= 520)
        }

        // OpenCode config builder embeds curated models and jhgetway/auto
        val settings = GatewaySettingsState(defaultModelId = "claude-sonnet-4", defaultProviderId = provA.id)
        val curatedCatalog = OpenCodeModelCatalogResolver.buildCuratedCatalog(
            providers = db.providerDao().getAllOnce(),
            localServers = db.localAiServerDao().getAllOnce(),
            allModels = db.modelDao().getAllOnce(),
            allKeys = db.apiKeyDao().getAllOnce(),
            routingRules = emptyList(),
            settings = settings
        )
        val openCodeConfig = JSONObject(
            CodingAgentConfigBuilder.buildOpenCodeConfigJson(
                agent = agent,
                baseV1Url = "$baseUrl/v1",
                apiKeyValue = token,
                settings = settings,
                curatedCatalog = curatedCatalog
            )
        )
        assertEquals("jhgetway/auto", openCodeConfig.getString("model"))
        val configModels = openCodeConfig.getJSONObject("provider").getJSONObject("jhgetway").getJSONObject("models")
        assertTrue(configModels.has("auto"))
        assertTrue(configModels.has("default"))
        assertTrue(configModels.has("best-coding"))
        assertTrue(configModels.has("free"))
        assertTrue(configModels.length() < 25)

        // Every single ID returned by /v1/models must resolve and execute cleanly
        seenUpstreamModels.clear()
        listedIds.forEach { selectorOrModelId ->
            val payload = JSONObject()
                .put("model", selectorOrModelId)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))
                .toString()
            httpClient.newCall(
                Request.Builder()
                    .url("$baseUrl/v1/chat/completions")
                    .header("Authorization", "Bearer $token")
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().use { resp ->
                assertEquals("Expected 200 for listed id '$selectorOrModelId'", 200, resp.code)
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

        // Scenario 3 & 5: jhgetway/auto on non-coding prompt uses canonical default ("gpt-4o-mini")
        val autoResp = postChat("jhgetway/auto", "Summarize a calendar invite")
        assertEquals("gpt-4o-mini", autoResp.getString("model"))
        assertEquals("gpt-4o-mini", seenModels.last())

        // Scenario 4: "default" resolves to canonical default ("gpt-4o-mini")
        val defResp = postChat("default", "Hi")
        assertEquals("gpt-4o-mini", defResp.getString("model"))

        // Scenario 20: Disabling preferConfiguredDefaultInAuto immediately changes `auto` behavior on coding prompt to pick coding tier ("gpt-5")
        app.preferences.updateSmartAutoPreferences(
            openCodeSelectionMode = "auto",
            autoAllowPaidModels = true,
            preferConfiguredDefaultInAuto = false
        )
        val codeResp = postChat("auto", "Refactor ```kotlin\nfun main() {}\n``` and run unit test")
        assertEquals("gpt-5", codeResp.getString("model"))

        // Scenario 18: Explicit model ID still routes directly
        val explicitResp = postChat("jhgetway/gpt-4o-mini", "Explicit call")
        assertEquals("gpt-4o-mini", explicitResp.getString("model"))
        assertFalse(seenModels.any { it == "auto" || it == "jhgetway/auto" || it == "default" })
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
}
