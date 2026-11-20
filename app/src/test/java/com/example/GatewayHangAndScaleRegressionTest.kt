package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.security.CryptoManager
import com.example.core.security.MasterKeyProvider
import com.example.data.local.*
import com.example.gateway.*
import com.example.network.ChatMessagePayload
import com.example.network.ChatRequestParams
import com.example.network.ProviderHttpClient
import com.example.network.adapters.AdapterStreamState
import com.example.network.adapters.GeminiProviderAdapter
import com.example.network.adapters.NormalizedToolDefinition
import com.example.network.adapters.OpenAiProviderAdapter
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKey

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GatewayHangAndScaleRegressionTest {

    private lateinit var app: JhGetWayApplication
    private lateinit var db: JhGetWayDatabase
    private val gatewayServers = mutableListOf<LocalGatewayHttpServer>()
    private val upstreamServers = mutableListOf<EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
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

    private suspend fun startMockUpstream(block: io.ktor.server.routing.Routing.() -> Unit): String {
        val srv = embeddedServer(CIO, configure = { connector { host = "127.0.0.1"; port = 0 } }) {
            routing { block() }
        }.start(wait = false)
        upstreamServers += srv
        val port = srv.engine.resolvedConnectors().first().port
        return "http://127.0.0.1:$port"
    }

    private suspend fun startLocalGateway(
        timeoutSec: Int = 15,
        maxRetries: Int = 2,
        autoAllowPaid: Boolean = true
    ): Triple<LocalGatewayHttpServer, String, String> {
        app.preferences.updateGatewaySettings(
            name = "Regression Gateway",
            mode = GatewayMode.LOCAL,
            host = "127.0.0.1",
            port = 0,
            remoteUrl = "",
            authRequired = true,
            timeoutSec = timeoutSec,
            maxRequestKb = 512,
            streaming = true,
            rateLimitPerMin = 500,
            loggingLevel = LoggingLevel.METADATA_ONLY
        )
        app.preferences.updateRoutingSettings(
            mode = RoutingMode.AUTOMATIC,
            fallbackEnabled = true,
            maxRetries = maxRetries
        )
        app.preferences.updateLocalAiSettings(
            policy = LocalRoutingPolicy.CLOUD_FIRST,
            allowCloudFallback = true,
            localNetworkAllowed = true
        )
        app.preferences.updateSmartAutoPreferences(
            openCodeSelectionMode = "auto",
            autoAllowPaidModels = autoAllowPaid,
            preferConfiguredDefaultInAuto = true
        )
        val rawToken = "jhgw_regression_agent_token_123456"
        db.codingAgentDao().upsert(
            CodingAgentEntity(
                id = "agent_reg",
                name = "OpenCode Test",
                agentType = CodingAgentType.OPENCODE,
                encryptedToken = CryptoManager.encrypt(rawToken),
                tokenPreview = "jhgw_••••3456",
                enabled = true,
                toolCalling = true
            )
        )
        val initial = GatewaySettingsState(
            gatewayName = "Regression Gateway",
            gatewayMode = GatewayMode.LOCAL,
            gatewayHost = "127.0.0.1",
            gatewayPort = 0,
            gatewayAuthRequired = true,
            gatewayTimeoutSec = timeoutSec,
            gatewayMaxRequestKb = 512,
            gatewayStreamingEnabled = true,
            gatewayRateLimitPerMin = 500,
            defaultRoutingMode = RoutingMode.AUTOMATIC,
            fallbackEnabled = true,
            maxRetries = maxRetries,
            localRoutingPolicy = LocalRoutingPolicy.CLOUD_FIRST,
            allowCloudFallbackForLocal = true,
            localNetworkAccessAllowed = true,
            autoAllowPaidModels = autoAllowPaid
        )
        val server = LocalGatewayHttpServer(
            db = db,
            router = SmartRouterEngine(db, ProviderHttpClient()),
            preferences = app.preferences
        )
        gatewayServers += server
        server.start(initial)
        assertEquals(GatewayRunStatus.LOCAL_RUNNING, server.runtimeState.value.status)
        return Triple(server, server.runtimeState.value.boundAddress, rawToken)
    }

    @Test
    fun largeCatalog_50Providers4550Models_avoidsRepeatedDecryptsAndDoesNotCrushAttemptTimeoutTo2s() = runBlocking {
        val primaryUrl = startMockUpstream {
            post("/v1/chat/completions") {
                // Delay 3.2s: previously crushed to 2s timeout when 4500+ models existed!
                delay(3200)
                call.respondText(
                    """{"id":"c1","model":"p1-model-0","choices":[{"message":{"role":"assistant","content":"Slow but healthy reply"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5}}""",
                    ContentType.Application.Json
                )
            }
        }
        val backupUrl = startMockUpstream {
            post("/v1/chat/completions") {
                call.respondText(
                    """{"id":"c2","model":"p2-model-0","choices":[{"message":{"role":"assistant","content":"Backup"},"finish_reason":"stop"}]}""",
                    ContentType.Application.Json
                )
            }
        }

        val providers = mutableListOf<ProviderEntity>()
        val keys = mutableListOf<ApiKeyEntity>()
        val models = mutableListOf<ModelEntity>()
        for (pIdx in 1..50) {
            val pid = "prov_$pIdx"
            val url = when (pIdx) {
                1 -> "$primaryUrl/v1"
                2 -> "$backupUrl/v1"
                else -> "http://127.0.0.1:${60000 + pIdx}/v1"
            }
            providers += ProviderEntity(
                id = pid,
                name = "Provider $pIdx",
                apiFormat = ApiFormat.OPENAI,
                baseUrl = url,
                defaultModel = "p${pIdx}-model-0",
                priority = pIdx,
                status = ProviderStatus.CONNECTED,
                enabled = true
            )
            keys += ApiKeyEntity(
                id = "key_$pIdx",
                providerId = pid,
                label = "Key $pIdx",
                encryptedKey = CryptoManager.encrypt("sk-secret-$pIdx"),
                maskedPreview = "sk-••••",
                isDefault = true,
                enabled = true
            )
            for (mIdx in 0 until 91) {
                val mid = "p${pIdx}-model-$mIdx"
                models += ModelEntity(
                    id = "$pid::$mid",
                    modelId = mid,
                    displayName = mid,
                    providerId = pid,
                    providerName = "Provider $pIdx",
                    supportsChat = true,
                    supportsTools = true,
                    supportsStreaming = true,
                    enabled = true,
                    isDefault = (pIdx == 1 && mIdx == 0),
                    accessTier = AccessTier.FREE_TIER,
                    freeTierVerified = true
                )
            }
        }
        providers.forEach { db.providerDao().upsert(it) }
        keys.forEach { db.apiKeyDao().upsert(it) }
        models.chunked(250).forEach { chunk -> chunk.forEach { db.modelDao().upsert(it) } }

        val innerProvider = CryptoManager.EphemeralTestMasterKeyProvider()
        CryptoManager.setMasterKeyProviderForTest(object : MasterKeyProvider {
            override fun getOrCreateKey(): SecretKey = innerProvider.getOrCreateKey()
            override fun deleteKeyIfExists() = innerProvider.deleteKeyIfExists()
        })
        val reEncrypted = keys.mapIndexed { idx, k ->
            k.copy(encryptedKey = CryptoManager.encrypt("sk-secret-${idx + 1}"))
        }
        reEncrypted.forEach { db.apiKeyDao().upsert(it) }

        val keyOpsDuringRouting = AtomicInteger(0)
        CryptoManager.setMasterKeyProviderForTest(object : MasterKeyProvider {
            override fun getOrCreateKey(): SecretKey {
                keyOpsDuringRouting.incrementAndGet()
                return innerProvider.getOrCreateKey()
            }
            override fun deleteKeyIfExists() = innerProvider.deleteKeyIfExists()
        })

        val settings = GatewaySettingsState(
            gatewayTimeoutSec = 60,
            maxRetries = 2,
            fallbackEnabled = true,
            localNetworkAccessAllowed = true,
            localRoutingPolicy = LocalRoutingPolicy.CLOUD_FIRST,
            autoAllowPaidModels = true
        )
        val router = SmartRouterEngine(db, ProviderHttpClient())

        val t0 = System.currentTimeMillis()
        val (candidates, _) = router.resolveCandidates("jh-getway/auto", settings, RoutingMode.AUTOMATIC)
        val resolveElapsedMs = System.currentTimeMillis() - t0

        assertTrue("Expected 4550 candidates, got ${candidates.size}", candidates.size >= 4550)
        assertTrue(
            "Expected <= 150 key ops for 50 providers / 4550 models, got ${keyOpsDuringRouting.get()}",
            keyOpsDuringRouting.get() <= 150
        )
        assertTrue("Expected fast candidate resolution (< 5000ms), took ${resolveElapsedMs}ms", resolveElapsedMs < 5000)

        // Primary takes 3.2s -> must succeed on Attempt 1 instead of timing out at 2s!
        val result = router.executeRoutedChat(
            requestedModelOrAlias = "jh-getway/auto",
            messages = listOf(ChatMessagePayload("user", "hi")),
            settings = settings
        )
        assertTrue("Primary 3.2s response should succeed instead of timing out at 2s: ${result.execution.safeErrorMessage}", result.execution.success)
        assertEquals("Slow but healthy reply", result.execution.content)
        assertEquals("Provider 1", result.decision.selectedProvider)
        assertEquals(1, result.decision.attemptCount)
    }

    @Test
    fun keepaliveOnlyHangingSseStream_triggersPreFirstChunkWatchdogAndFallsBack_resettingActiveRequests() = runBlocking {
        val primaryUrl = startMockUpstream {
            post("/v1/chat/completions") {
                call.respondTextWriter(ContentType.Text.EventStream, HttpStatusCode.OK) {
                    repeat(20) {
                        write(": OPENROUTER PROCESSING\n\n")
                        flush()
                        delay(350)
                    }
                }
            }
        }
        val backupUrl = startMockUpstream {
            post("/v1/chat/completions") {
                call.respondTextWriter(ContentType.Text.EventStream, HttpStatusCode.OK) {
                    write("data: ${JSONObject("""{"id":"b1","choices":[{"index":0,"delta":{"content":"Backup OK"},"finish_reason":"stop"}]}""")}\n\n")
                    write("data: [DONE]\n\n")
                    flush()
                }
            }
        }

        db.providerDao().upsert(
            ProviderEntity(
                id = "p_hang",
                name = "HangingKeepalive",
                apiFormat = ApiFormat.OPENAI,
                baseUrl = "$primaryUrl/v1",
                defaultModel = "hang-model",
                priority = 1,
                status = ProviderStatus.CONNECTED,
                enabled = true
            )
        )
        db.providerDao().upsert(
            ProviderEntity(
                id = "p_ok",
                name = "HealthyBackup",
                apiFormat = ApiFormat.OPENAI,
                baseUrl = "$backupUrl/v1",
                defaultModel = "ok-model",
                priority = 2,
                status = ProviderStatus.CONNECTED,
                enabled = true
            )
        )
        db.apiKeyDao().upsert(ApiKeyEntity(id = "k1", providerId = "p_hang", label = "K1", encryptedKey = CryptoManager.encrypt("sk-1"), maskedPreview = "sk-1"))
        db.apiKeyDao().upsert(ApiKeyEntity(id = "k2", providerId = "p_ok", label = "K2", encryptedKey = CryptoManager.encrypt("sk-2"), maskedPreview = "sk-2"))

        val (server, baseUrl, token) = startLocalGateway(timeoutSec = 6, maxRetries = 2, autoAllowPaid = true)

        val reqBody = """{"model":"jh-getway/auto","stream":true,"messages":[{"role":"user","content":"hi"}]}"""
        val req = Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .header("Authorization", "Bearer $token")
            .post(reqBody.toRequestBody("application/json".toMediaType()))
            .build()

        val respText = client.newCall(req).execute().use { resp ->
            assertEquals(200, resp.code)
            resp.body!!.string()
        }
        assertTrue("Expected Backup OK in SSE output, got: $respText", respText.contains("Backup OK"))

        delay(150)
        assertEquals("activeRequests must return to 0 after fallback", 0, server.runtimeState.value.activeRequests)
    }

    @Test
    fun clientDisconnectDuringStreaming_cleansUpActiveRequestsCount() = runBlocking {
        val slowStreamUrl = startMockUpstream {
            post("/v1/chat/completions") {
                call.respondTextWriter(ContentType.Text.EventStream, HttpStatusCode.OK) {
                    write("data: ${JSONObject("""{"id":"s1","choices":[{"index":0,"delta":{"content":"First"},"finish_reason":null}]}""")}\n\n")
                    flush()
                    repeat(20) {
                        delay(400)
                        write("data: ${JSONObject("""{"id":"s1","choices":[{"index":0,"delta":{"content":" more"},"finish_reason":null}]}""")}\n\n")
                        flush()
                    }
                    write("data: [DONE]\n\n")
                    flush()
                }
            }
        }
        db.providerDao().upsert(
            ProviderEntity(
                id = "p_slow",
                name = "SlowStream",
                apiFormat = ApiFormat.OPENAI,
                baseUrl = "$slowStreamUrl/v1",
                defaultModel = "slow-model",
                priority = 1,
                status = ProviderStatus.CONNECTED,
                enabled = true
            )
        )
        db.apiKeyDao().upsert(ApiKeyEntity(id = "k_slow", providerId = "p_slow", label = "K", encryptedKey = CryptoManager.encrypt("sk-slow"), maskedPreview = "sk-s"))

        val (server, baseUrl, token) = startLocalGateway(timeoutSec = 10, maxRetries = 0)

        val req = Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .header("Authorization", "Bearer $token")
            .post("""{"model":"slow-model","stream":true,"messages":[{"role":"user","content":"hi"}]}""".toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            assertEquals(200, resp.code)
            val reader = BufferedReader(InputStreamReader(resp.body!!.byteStream()))
            val firstLine = reader.readLine()
            assertNotNull(firstLine)
        }

        var deadline = 0
        while (server.runtimeState.value.activeRequests > 0 && deadline < 30) {
            delay(100)
            deadline++
        }
        assertEquals("activeRequests must return to 0 after client disconnect", 0, server.runtimeState.value.activeRequests)
    }

    @Test
    fun openAiStreamToolCall_withNullJsonFields_doesNotInjectLiteralNull() {
        val adapter = OpenAiProviderAdapter()
        val state = AdapterStreamState()

        val chunk1 = adapter.parseStreamLine(
            null,
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_abc","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"src/"}}]},"finish_reason":null}]}""",
            state
        )
        val chunk2 = adapter.parseStreamLine(
            null,
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":null,"function":{"name":null,"arguments":null}}]},"finish_reason":null}]}""",
            state
        )
        val chunk3 = adapter.parseStreamLine(
            null,
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":null,"function":{"name":null,"arguments":"app.kt\"}"}}]},"finish_reason":"tool_calls"}]}""",
            state
        )

        assertNull("null JSON id must parse as Kotlin null, not 'null'", chunk2.toolCallDeltas.single().id)
        assertNull("null JSON name must parse as Kotlin null, not 'null'", chunk2.toolCallDeltas.single().name)
        assertNull("null JSON arguments must parse as Kotlin null, not 'null'", chunk2.toolCallDeltas.single().argumentsChunk)
        assertEquals("read_file", chunk1.toolCallDeltas.single().name)
        assertEquals("tool_calls", chunk3.finishReason)

        val finalized = state.finalizedToolCalls().single()
        assertEquals("call_abc", finalized.id)
        assertEquals("read_file", finalized.name)
        assertEquals("""{"path":"src/app.kt"}""", finalized.argumentsJson)
    }

    @Test
    fun geminiAdapter_toolSchemaSanitization_stripsUnsupportedKeywords() {
        val adapter = GeminiProviderAdapter()
        val caps = adapter.capabilitiesForModel("gemini-2.5-flash", null)
        val dirtySchema = JSONObject(
            """
            {
              "${'$'}schema": "http://json-schema.org/draft-07/schema#",
              "type": "object",
              "additionalProperties": false,
              "title": "ReadFileInput",
              "properties": {
                "filePath": {
                  "type": ["string", "null"],
                  "default": "",
                  "description": "Path to file"
                },
                "lines": {
                  "type": "array",
                  "items": {
                    "type": "integer",
                    "default": 1
                  }
                }
              },
              "required": ["filePath"]
            }
            """.trimIndent()
        )
        val req = adapter.buildChatRequest(
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            apiKey = "gem-key",
            customHeadersJson = "",
            params = ChatRequestParams(
                messages = listOf(ChatMessagePayload("user", "Inspect file")),
                modelId = "gemini-2.5-flash",
                tools = listOf(NormalizedToolDefinition("read_file", "Read a file", dirtySchema)),
                capabilities = caps
            ),
            capabilities = caps,
            stream = false
        )
        val buf = okio.Buffer()
        req.body!!.writeTo(buf)
        val root = JSONObject(buf.readUtf8())
        val paramsOut = root.getJSONArray("tools")
            .getJSONObject(0)
            .getJSONArray("functionDeclarations")
            .getJSONObject(0)
            .getJSONObject("parameters")

        assertFalse(paramsOut.has("\$schema"))
        assertFalse(paramsOut.has("additionalProperties"))
        assertFalse(paramsOut.has("title"))
        val filePathSchema = paramsOut.getJSONObject("properties").getJSONObject("filePath")
        assertEquals("string", filePathSchema.getString("type"))
        assertTrue(filePathSchema.getBoolean("nullable"))
        assertFalse(filePathSchema.has("default"))
        val itemsSchema = paramsOut.getJSONObject("properties").getJSONObject("lines").getJSONObject("items")
        assertEquals("integer", itemsSchema.getString("type"))
        assertFalse(itemsSchema.has("default"))
    }

    @Test
    fun http402QuotaExceeded_triggersAutoFallback_whilePlain400StopsWithoutRetry() = runBlocking {
        var primaryStatus = HttpStatusCode.PaymentRequired // 402
        var primaryBody = """{"error":{"message":"Insufficient credits"}}"""
        val backupHits = AtomicInteger(0)

        val primaryUrl = startMockUpstream {
            post("/v1/chat/completions") {
                call.respondText(primaryBody, ContentType.Application.Json, primaryStatus)
            }
        }
        val backupUrl = startMockUpstream {
            post("/v1/chat/completions") {
                backupHits.incrementAndGet()
                call.respondText(
                    """{"id":"b","model":"m2","choices":[{"message":{"role":"assistant","content":"Backup after 402"},"finish_reason":"stop"}]}""",
                    ContentType.Application.Json
                )
            }
        }

        db.providerDao().upsert(
            ProviderEntity(
                id = "p1", name = "Primary", apiFormat = ApiFormat.OPENAI,
                baseUrl = "$primaryUrl/v1", defaultModel = "m1", priority = 1,
                status = ProviderStatus.CONNECTED, enabled = true
            )
        )
        db.providerDao().upsert(
            ProviderEntity(
                id = "p2", name = "Backup", apiFormat = ApiFormat.OPENAI,
                baseUrl = "$backupUrl/v1", defaultModel = "m2", priority = 2,
                status = ProviderStatus.CONNECTED, enabled = true
            )
        )
        db.apiKeyDao().upsert(ApiKeyEntity(id = "k1", providerId = "p1", label = "K1", encryptedKey = CryptoManager.encrypt("sk-1"), maskedPreview = "sk-1"))
        db.apiKeyDao().upsert(ApiKeyEntity(id = "k2", providerId = "p2", label = "K2", encryptedKey = CryptoManager.encrypt("sk-2"), maskedPreview = "sk-2"))

        val router = SmartRouterEngine(db, ProviderHttpClient())
        val settings = GatewaySettingsState(
            gatewayTimeoutSec = 15,
            maxRetries = 2,
            fallbackEnabled = true,
            localNetworkAccessAllowed = true,
            localRoutingPolicy = LocalRoutingPolicy.CLOUD_FIRST,
            autoAllowPaidModels = true
        )

        val res402 = router.executeRoutedChat("jh-getway/auto", listOf(ChatMessagePayload("user", "hi")), settings)
        assertTrue("HTTP 402 should trigger fallback to Backup", res402.execution.success)
        assertEquals("Backup after 402", res402.execution.content)
        assertEquals(1, backupHits.get())

        db.providerDao().upsert(db.providerDao().getById("p1")!!.copy(status = ProviderStatus.CONNECTED, lastCheckedAt = null))
        primaryStatus = HttpStatusCode.BadRequest
        primaryBody = """{"error":{"message":"malformed payload"}}"""
        val res400 = router.executeRoutedChat("jh-getway/auto", listOf(ChatMessagePayload("user", "bad")), settings)
        assertFalse(res400.execution.success)
        assertEquals(400, res400.execution.statusCode)
        assertEquals(1, res400.decision.attemptCount)
        assertEquals("Plain HTTP 400 must not hit Backup", 1, backupHits.get())
    }
}
