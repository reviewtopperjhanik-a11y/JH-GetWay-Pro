package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.security.CryptoManager
import com.example.data.local.*
import com.example.gateway.LocalGatewayHttpServer
import com.example.gateway.SmartRouterEngine
import com.example.network.*
import com.example.network.adapters.*
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
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
class PhaseBApiAndAdapterTest {

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
    }

    @After
    fun tearDown() {
        runBlocking {
            gatewayServers.forEach { runCatching { it.stop() } }
            gatewayServers.clear()
            upstreamServers.forEach { runCatching { it.stop(200, 500) } }
            upstreamServers.clear()
        }
        db.close()
        CryptoManager.resetTestKeyProvider()
    }

    private fun Request.readBodyText(): String {
        val buf = Buffer()
        body?.writeTo(buf)
        return buf.readUtf8()
    }

    private suspend fun startGatewayServer(authRequired: Boolean = true): String {
        app.preferences.updateGatewaySettings(
            name = "Phase B Test Gateway",
            mode = GatewayMode.LOCAL,
            host = "127.0.0.1",
            port = 0,
            remoteUrl = "",
            authRequired = authRequired,
            timeoutSec = 30,
            maxRequestKb = 256,
            streaming = true,
            rateLimitPerMin = 200,
            loggingLevel = LoggingLevel.METADATA_ONLY,
            allowLanExposure = false,
            remoteAuthTokenEnc = ""
        )
        app.preferences.updateRoutingSettings(
            mode = RoutingMode.PRIORITY,
            fallbackEnabled = true,
            maxRetries = 2
        )
        app.preferences.updateLocalAiSettings(
            policy = LocalRoutingPolicy.CLOUD_FIRST,
            allowCloudFallback = true,
            localNetworkAllowed = true
        )
        val router = SmartRouterEngine(db, ProviderHttpClient())
        val server = LocalGatewayHttpServer(db, router, app.preferences)
        gatewayServers += server
        server.start(
            GatewaySettingsState(
                gatewayMode = GatewayMode.LOCAL,
                gatewayHost = "127.0.0.1",
                gatewayPort = 0,
                gatewayAuthRequired = authRequired,
                gatewayStreamingEnabled = true,
                localNetworkAccessAllowed = true,
                fallbackEnabled = true,
                maxRetries = 2,
                localRoutingPolicy = LocalRoutingPolicy.CLOUD_FIRST
            )
        )
        return server.runtimeState.value.boundAddress
    }

    private fun startMockUpstream(
        configure: io.ktor.server.routing.Routing.() -> Unit
    ): String {
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
        upstreamServers += srv
        val port = runBlocking { srv.engine.resolvedConnectors().first().port }
        return "http://127.0.0.1:$port"
    }

    private suspend fun seedCloudProvider(
        id: String,
        name: String,
        baseUrl: String,
        apiFormat: ApiFormat,
        modelId: String,
        alias: String = "jh-general",
        priority: Int = 1,
        supportsTools: Boolean = true
    ) {
        db.providerDao().upsert(
            ProviderEntity(
                id = id,
                name = name,
                type = ProviderType.CUSTOM,
                baseUrl = baseUrl,
                apiFormat = apiFormat,
                defaultModel = modelId,
                priority = priority,
                enabled = true
            )
        )
        db.apiKeyDao().upsert(
            ApiKeyEntity(
                providerId = id,
                label = "$name Key",
                encryptedKey = CryptoManager.encrypt("upstream-secret-$id"),
                maskedPreview = "upst••••$id",
                enabled = true
            )
        )
        db.modelDao().upsert(
            ModelEntity(
                id = "$id::$modelId",
                providerId = id,
                providerName = name,
                modelId = modelId,
                displayName = modelId,
                alias = alias,
                supportsTools = supportsTools,
                isDefault = true,
                enabled = true
            )
        )
    }

    // -------------------------------------------------------------------------
    // P1.5: Provider Adapter Unit Tests
    // -------------------------------------------------------------------------

    @Test
    fun openAiAdapter_preservesRolesMultimodalToolCallsAndGatesReasoningModelParams() {
        val adapter = OpenAiProviderAdapter()
        val tools = listOf(
            NormalizedToolDefinition(
                name = "get_weather",
                description = "Fetch weather for city",
                parametersSchema = JSONObject("""{"type":"object","properties":{"city":{"type":"string"}}}""")
            )
        )
        val messages = listOf(
            ChatMessagePayload(role = "developer", content = "Be concise"),
            ChatMessagePayload(
                role = "user",
                content = "Check this image",
                contentParts = listOf(
                    ContentPart.Text("Check this image"),
                    ContentPart.ImageUrl("https://example.com/chart.png", detail = "high")
                )
            ),
            ChatMessagePayload(
                role = "assistant",
                content = "",
                toolCalls = listOf(ToolCallPayload("call_1", "get_weather", """{"city":"Dhaka"}"""))
            ),
            ChatMessagePayload(
                role = "tool",
                content = """{"tempC":31}""",
                toolCallId = "call_1",
                name = "get_weather"
            )
        )

        val gptCaps = adapter.capabilitiesForModel("gpt-4o", null)
        val gptReq = adapter.buildChatRequest(
            baseUrl = "https://api.openai.com/v1",
            apiKey = "sk-test",
            customHeadersJson = "",
            params = ChatRequestParams(
                messages = messages,
                modelId = "gpt-4o",
                temperature = 0.2f,
                topP = 0.9f,
                maxTokens = 512,
                stopSequences = listOf("DONE"),
                tools = tools,
                toolChoice = NormalizedToolChoice.SpecificFunction("get_weather"),
                responseFormat = NormalizedResponseFormat(type = "json_object"),
                capabilities = gptCaps
            ),
            capabilities = gptCaps,
            stream = false
        )

        val gptJson = JSONObject(gptReq.readBodyText())
        assertEquals("https://api.openai.com/v1/chat/completions", gptReq.url.toString())
        assertEquals(0.2, gptJson.getDouble("temperature"), 0.01)
        assertEquals(512, gptJson.getInt("max_tokens"))
        assertEquals("json_object", gptJson.getJSONObject("response_format").getString("type"))
        val reqMsgs = gptJson.getJSONArray("messages")
        assertEquals("developer", reqMsgs.getJSONObject(0).getString("role"))
        assertEquals("image_url", reqMsgs.getJSONObject(1).getJSONArray("content").getJSONObject(1).getString("type"))
        assertEquals("call_1", reqMsgs.getJSONObject(2).getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        assertEquals("tool", reqMsgs.getJSONObject(3).getString("role"))
        assertEquals("call_1", reqMsgs.getJSONObject(3).getString("tool_call_id"))

        // Reasoning model gating (o3-mini omits temperature & top_p, maps max_completion_tokens)
        val reasoningCaps = adapter.capabilitiesForModel("o3-mini", null)
        assertFalse(reasoningCaps.supportsStopSequences)

        val o3Req = adapter.buildChatRequest(
            baseUrl = "https://api.openai.com/v1",
            apiKey = "sk-test",
            customHeadersJson = "",
            params = ChatRequestParams(
                messages = listOf(ChatMessagePayload("user", "Solve")),
                modelId = "o3-mini",
                temperature = 0.7f,
                topP = 0.8f,
                maxTokens = 1024,
                capabilities = reasoningCaps
            ),
            capabilities = reasoningCaps,
            stream = false
        )
        val o3Json = JSONObject(o3Req.readBodyText())
        assertFalse(o3Json.has("temperature"))
        assertFalse(o3Json.has("top_p"))
        assertFalse(o3Json.has("max_tokens"))
        assertEquals(1024, o3Json.getInt("max_completion_tokens"))

        // Parse tool_calls response
        val parsed = adapter.parseChatResponse(
            bodyStr = """
            {
              "model":"gpt-4o",
              "choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_abc","type":"function","function":{"name":"get_weather","arguments":"{\"city\":\"Dhaka\"}"}}]},"finish_reason":"tool_calls"}],
              "usage":{"prompt_tokens":44,"completion_tokens":19}
            }
            """.trimIndent(),
            latencyMs = 55L,
            statusCode = 200
        )
        assertTrue(parsed.success)
        assertEquals("tool_calls", parsed.finishReason)
        assertEquals(1, parsed.toolCalls.size)
        assertEquals("call_abc", parsed.toolCalls[0].id)
        assertEquals("get_weather", parsed.toolCalls[0].name)
        assertEquals(44, parsed.inputTokens)
        assertEquals(19, parsed.outputTokens)
    }

    @Test
    fun anthropicAdapter_mapsSystemToolBlocksConsecutiveToolResultsAndSseEvents() {
        val adapter = AnthropicProviderAdapter()
        val caps = adapter.capabilitiesForModel("claude-3-7-sonnet", null)
        val built = adapter.buildChatRequest(
            baseUrl = "https://api.anthropic.com",
            apiKey = "sk-ant-test",
            customHeadersJson = "",
            params = ChatRequestParams(
                messages = listOf(
                    ChatMessagePayload("system", "Always verify"),
                    ChatMessagePayload(
                        "user",
                        "Look & call tools",
                        contentParts = listOf(
                            ContentPart.Text("Look & call tools"),
                            ContentPart.ImageUrl("data:image/png;base64,iVBORw0KGgo=")
                        )
                    ),
                    ChatMessagePayload(
                        "assistant",
                        "Calling both tools",
                        toolCalls = listOf(
                            ToolCallPayload("toolu_1", "read_file", """{"path":"a.kt"}"""),
                            ToolCallPayload("toolu_2", "read_file", """{"path":"b.kt"}""")
                        )
                    ),
                    ChatMessagePayload("tool", "contents A", toolCallId = "toolu_1"),
                    ChatMessagePayload("tool", "contents B", toolCallId = "toolu_2")
                ),
                modelId = "claude-3-7-sonnet",
                maxTokens = 1024,
                stopSequences = listOf("END"),
                tools = listOf(
                    NormalizedToolDefinition("read_file", "Read", JSONObject("""{"type":"object"}"""))
                ),
                toolChoice = NormalizedToolChoice.Required,
                capabilities = caps
            ),
            capabilities = caps,
            stream = false
        )

        val body = JSONObject(built.readBodyText())
        assertEquals("https://api.anthropic.com/v1/messages", built.url.toString())
        assertEquals("sk-ant-test", built.header("x-api-key"))
        assertEquals("Always verify", body.getString("system"))
        assertEquals(1024, body.getInt("max_tokens"))
        assertEquals("any", body.getJSONObject("tool_choice").getString("type"))

        val msgs = body.getJSONArray("messages")
        assertEquals(3, msgs.length())
        // Consecutive tool results are grouped into one user message with two tool_result blocks
        val toolResultMsg = msgs.getJSONObject(2)
        assertEquals("user", toolResultMsg.getString("role"))
        val trContent = toolResultMsg.getJSONArray("content")
        assertEquals(2, trContent.length())
        assertEquals("tool_result", trContent.getJSONObject(0).getString("type"))
        assertEquals("toolu_1", trContent.getJSONObject(0).getString("tool_use_id"))
        assertEquals("toolu_2", trContent.getJSONObject(1).getString("tool_use_id"))

        // Parse Anthropic SSE event stream
        val sseState = AdapterStreamState()
        val d1 = adapter.parseStreamLine("message_start", """{"type":"message_start","message":{"usage":{"input_tokens":15,"output_tokens":0}}}""", sseState)
        assertFalse(d1.isTerminal)
        val d2 = adapter.parseStreamLine("content_block_start", """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_9","name":"read_file","input":{}}}""", sseState)
        assertEquals("toolu_9", d2.toolCallDeltas.first().id)
        val d3 = adapter.parseStreamLine("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"path\":\"c.kt\"}"}}""", sseState)
        assertEquals("{\"path\":\"c.kt\"}", d3.toolCallDeltas.first().argumentsChunk)
        adapter.parseStreamLine("content_block_stop", """{"type":"content_block_stop","index":0}""", sseState)
        val d4 = adapter.parseStreamLine("message_delta", """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":22}}""", sseState)
        assertEquals("tool_calls", d4.finishReason)
        val d5 = adapter.parseStreamLine("message_stop", """{"type":"message_stop"}""", sseState)
        assertTrue(d5.isTerminal)
        assertEquals(15, sseState.inputTokens)
        assertEquals(22, sseState.outputTokens)
        assertEquals("toolu_9", sseState.finalizedToolCalls().single().id)
        assertEquals("""{"path":"c.kt"}""", sseState.finalizedToolCalls().single().argumentsJson)
    }

    @Test
    fun geminiAndOllamaAdapters_mapToolsFunctionCallsAndNormalizeErrors() {
        val gemini = GeminiProviderAdapter()
        val gemCaps = gemini.capabilitiesForModel("gemini-2.5-flash", null)
        val gemReq = gemini.buildChatRequest(
            baseUrl = "https://generativelanguage.googleapis.com",
            apiKey = "gem-key",
            customHeadersJson = "",
            params = ChatRequestParams(
                messages = listOf(
                    ChatMessagePayload("system", "Sys prompt"),
                    ChatMessagePayload(
                        "user",
                        "Inspect",
                        contentParts = listOf(
                            ContentPart.Text("Inspect"),
                            ContentPart.ImageUrl("data:image/jpeg;base64,AAAA")
                        )
                    ),
                    ChatMessagePayload("assistant", "", toolCalls = listOf(ToolCallPayload("c1", "ping", """{"host":"1.1.1.1"}"""))),
                    ChatMessagePayload("tool", """{"ok":true}""", toolCallId = "c1", name = "ping")
                ),
                modelId = "gemini-2.5-flash",
                tools = listOf(NormalizedToolDefinition("ping", "Ping host", JSONObject("""{"type":"object"}"""))),
                toolChoice = NormalizedToolChoice.Required,
                capabilities = gemCaps
            ),
            capabilities = gemCaps,
            stream = false
        )
        assertEquals("https://generativelanguage.googleapis.com/models/gemini-2.5-flash:generateContent", gemReq.url.toString())
        assertEquals("gem-key", gemReq.header("x-goog-api-key"))
        val gemJson = JSONObject(gemReq.readBodyText())
        assertEquals("Sys prompt", gemJson.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        assertEquals("ANY", gemJson.getJSONObject("toolConfig").getJSONObject("functionCallingConfig").getString("mode"))

        val gemResp = gemini.parseChatResponse(
            bodyStr = """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"ping","args":{"host":"1.1.1.1"}}}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":30,"candidatesTokenCount":12}}""",
            latencyMs = 40L,
            statusCode = 200
        )
        assertTrue(gemResp.success)
        assertEquals("tool_calls", gemResp.finishReason)
        assertEquals("ping", gemResp.toolCalls.single().name)
        assertEquals(30, gemResp.inputTokens)
        assertEquals(12, gemResp.outputTokens)

        val ollama = OllamaProviderAdapter()
        val olCaps = ollama.capabilitiesForModel("llama3.2", null)
        val olReq = ollama.buildChatRequest(
            baseUrl = "http://127.0.0.1:11434",
            apiKey = "",
            customHeadersJson = "",
            params = ChatRequestParams(
                messages = listOf(ChatMessagePayload("user", "Run")),
                modelId = "llama3.2",
                tools = listOf(NormalizedToolDefinition("ping", "Ping host", JSONObject("""{"type":"object"}"""))),
                capabilities = olCaps
            ),
            capabilities = olCaps,
            stream = false
        )
        assertEquals("http://127.0.0.1:11434/api/chat", olReq.url.toString())
        assertTrue(JSONObject(olReq.readBodyText()).has("tools"))

        val olErr = ollama.normalizeHttpError(404, """{"error":"model 'llama3.2' not found"}""", 10L, "")
        assertFalse(olErr.success)
        assertEquals("MODEL_UNAVAILABLE", olErr.errorCode)
        assertTrue(olErr.retryable)
    }

    // -------------------------------------------------------------------------
    // P1.1 & P1.2: /v1/chat/completions Multi-Turn Tool Calling + Validation + 403
    // -------------------------------------------------------------------------

    @Test
    fun openAiChatCompletions_multiTurnToolCallingRoundtripAndSchemaValidation() = runBlocking {
        val capturedBodies = CopyOnWriteArrayList<JSONObject>()
        val upstreamUrl = startMockUpstream {
            post("/v1/chat/completions") {
                val reqJson = JSONObject(call.receiveText())
                capturedBodies += reqJson
                val msgs = reqJson.getJSONArray("messages")
                val hasToolResult = (0 until msgs.length()).any { msgs.getJSONObject(it).getString("role") == "tool" }
                if (!hasToolResult) {
                    call.respondText(
                        text = """
                        {
                          "id":"chatcmpl-up-1",
                          "object":"chat.completion",
                          "model":"gpt-4o",
                          "choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_wx_1","type":"function","function":{"name":"get_weather","arguments":"{\"city\":\"Dhaka\"}"}}]},"finish_reason":"tool_calls"}],
                          "usage":{"prompt_tokens":35,"completion_tokens":14}
                        }
                        """.trimIndent(),
                        contentType = ContentType.Application.Json
                    )
                } else {
                    call.respondText(
                        text = """
                        {
                          "id":"chatcmpl-up-2",
                          "object":"chat.completion",
                          "model":"gpt-4o",
                          "choices":[{"index":0,"message":{"role":"assistant","content":"It is 31C in Dhaka."},"finish_reason":"stop"}],
                          "usage":{"prompt_tokens":62,"completion_tokens":11}
                        }
                        """.trimIndent(),
                        contentType = ContentType.Application.Json
                    )
                }
            }
        }

        seedCloudProvider("prov-1", "UpstreamOpenAI", "$upstreamUrl/v1", ApiFormat.OPENAI, "gpt-4o", alias = "jh-general")

        val devToken = "jhgw_dev_full_tools_1234"
        db.deviceDao().upsert(
            DeviceEntity(
                name = "IDE Client",
                deviceType = "CLI",
                deviceCode = "DEV-T1",
                encryptedToken = CryptoManager.encrypt(devToken),
                tokenPreview = "jhgw_••••1234",
                connectionType = DeviceConnectionType.LOCAL_NETWORK
            )
        )
        val restrictedAgentToken = "jhgw_agent_notools_5678"
        db.codingAgentDao().upsert(
            CodingAgentEntity(
                name = "ReadOnly Bot",
                agentType = CodingAgentType.OTHER,
                encryptedToken = CryptoManager.encrypt(restrictedAgentToken),
                tokenPreview = "jhgw_••••5678",
                toolCalling = false
            )
        )

        val baseUrl = startGatewayServer(authRequired = true)

        // Turn 1: send tools + tool_choice
        val turn1Body = JSONObject()
            .put("model", "jh-general")
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Weather in Dhaka?")))
            .put(
                "tools",
                JSONArray().put(
                    JSONObject().put("type", "function").put(
                        "function",
                        JSONObject()
                            .put("name", "get_weather")
                            .put("description", "Weather lookup")
                            .put("parameters", JSONObject("""{"type":"object","properties":{"city":{"type":"string"}}}"""))
                    )
                )
            )
            .put("tool_choice", "required")
            .toString()

        val turn1Resp = httpClient.newCall(
            Request.Builder()
                .url("$baseUrl/v1/chat/completions")
                .header("Authorization", "Bearer $devToken")
                .post(turn1Body.toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().use { resp ->
            assertEquals(200, resp.code)
            JSONObject(resp.body!!.string())
        }
        val choice1 = turn1Resp.getJSONArray("choices").getJSONObject(0)
        assertEquals("tool_calls", choice1.getString("finish_reason"))
        val callId = choice1.getJSONObject("message").getJSONArray("tool_calls").getJSONObject(0).getString("id")
        assertEquals("call_wx_1", callId)

        // Turn 2: send assistant tool_calls + role=tool result
        val turn2Body = JSONObject()
            .put("model", "jh-general")
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "user").put("content", "Weather in Dhaka?"))
                    .put(
                        JSONObject()
                            .put("role", "assistant")
                            .put("content", JSONObject.NULL)
                            .put("tool_calls", choice1.getJSONObject("message").getJSONArray("tool_calls"))
                    )
                    .put(
                        JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", "call_wx_1")
                            .put("content", """{"tempC":31}""")
                    )
            )
            .toString()

        val turn2Resp = httpClient.newCall(
            Request.Builder()
                .url("$baseUrl/v1/chat/completions")
                .header("Authorization", "Bearer $devToken")
                .post(turn2Body.toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().use { resp ->
            assertEquals(200, resp.code)
            JSONObject(resp.body!!.string())
        }
        assertEquals("It is 31C in Dhaka.", turn2Resp.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"))
        assertEquals(62, turn2Resp.getJSONObject("usage").getInt("prompt_tokens"))

        // Validation: invalid tool schema -> 400
        httpClient.newCall(
            Request.Builder()
                .url("$baseUrl/v1/chat/completions")
                .header("Authorization", "Bearer $devToken")
                .post("""{"model":"jh-general","messages":[{"role":"user","content":"hi"}],"tools":[{"type":"unknown"}]}""".toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().use { resp ->
            assertEquals(400, resp.code)
        }

        // Permission check: agent with toolCalling=false receives 403 when sending tools
        httpClient.newCall(
            Request.Builder()
                .url("$baseUrl/v1/chat/completions")
                .header("Authorization", "Bearer $restrictedAgentToken")
                .post(turn1Body.toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().use { resp ->
            assertEquals(403, resp.code)
            val errJson = JSONObject(resp.body!!.string())
            assertEquals("tool_calling_disabled", errJson.getJSONObject("error").getString("code"))
        }
    }

    // -------------------------------------------------------------------------
    // P1.1 & P1.2: SSE Streaming Deltas, Pre-Stream Fallback, No Mid-Stream Restart, Cancellation
    // -------------------------------------------------------------------------

    @Test
    fun openAiChatCompletions_sseStreamingEmitsDeltasToolCallsAndHandlesFallbackRules() = runBlocking {
        val primaryCalls = AtomicInteger(0)
        val secondaryCalls = AtomicInteger(0)
        var primaryMode = "PRE_STREAM_503"

        val primaryUrl = startMockUpstream {
            post("/v1/chat/completions") {
                primaryCalls.incrementAndGet()
                when (primaryMode) {
                    "PRE_STREAM_503" -> {
                        call.respondText("""{"error":{"message":"Upstream overloaded"}}""", ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
                    }
                    "MID_STREAM_FAIL" -> {
                        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                            write("""data: {"id":"c1","choices":[{"delta":{"content":"Partial "},"finish_reason":null}]}""" + "\n\n")
                            flush()
                            write("""data: {"error":{"message":"Upstream stream reset","type":"server_error"}}""" + "\n\n")
                            flush()
                        }
                    }
                }
            }
        }
        val secondaryUrl = startMockUpstream {
            post("/v1/chat/completions") {
                secondaryCalls.incrementAndGet()
                call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                    write("""data: {"id":"c2","choices":[{"delta":{"role":"assistant","content":"Hello "},"finish_reason":null}]}""" + "\n\n")
                    flush()
                    write("""data: {"id":"c2","choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_stream_1","type":"function","function":{"name":"lookup","arguments":"{\"k\":"}}]},"finish_reason":null}]}""" + "\n\n")
                    flush()
                    write("""data: {"id":"c2","choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"1}"}}]},"finish_reason":null}]}""" + "\n\n")
                    flush()
                    write("""data: {"id":"c2","choices":[{"delta":{},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":10,"completion_tokens":8}}""" + "\n\n")
                    flush()
                    write("data: [DONE]\n\n")
                    flush()
                }
            }
        }

        seedCloudProvider("prov-prim", "Primary", "$primaryUrl/v1", ApiFormat.OPENAI, "gpt-4o", priority = 1)
        seedCloudProvider("prov-sec", "Secondary", "$secondaryUrl/v1", ApiFormat.OPENAI, "gpt-4o", priority = 2)
        val baseUrl = startGatewayServer(authRequired = false)

        // 1. Pre-stream 503 fails over cleanly to Secondary and delivers full SSE delta + tool_calls + [DONE]
        val sseReqBody = """{"model":"jh-general","stream":true,"messages":[{"role":"user","content":"Hi"}]}"""
        val lines = httpClient.newCall(
            Request.Builder()
                .url("$baseUrl/v1/chat/completions")
                .post(sseReqBody.toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().use { resp ->
            assertEquals(200, resp.code)
            resp.body!!.string().lines().filter { it.startsWith("data: ") }
        }
        assertEquals(1, primaryCalls.get())
        assertEquals(1, secondaryCalls.get())
        val joined = lines.joinToString("\n")
        assertTrue(joined.contains("\"Hello \""))
        assertTrue(joined.contains("\"call_stream_1\""))
        assertTrue(joined.contains("data: [DONE]"))

        // 2. Mid-stream failure MUST NOT silently restart on Secondary
        primaryMode = "MID_STREAM_FAIL"
        val midLines = httpClient.newCall(
            Request.Builder()
                .url("$baseUrl/v1/chat/completions")
                .post(sseReqBody.toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().use { resp ->
            assertEquals(200, resp.code)
            resp.body!!.string().lines().filter { it.startsWith("data: ") }
        }
        assertEquals(2, primaryCalls.get())
        assertEquals(1, secondaryCalls.get()) // Secondary was NOT invoked after mid-stream chunk
        val midJoined = midLines.joinToString("\n")
        assertTrue(midJoined.contains("Partial "))
        assertTrue(midJoined.contains("\"error\""))
    }

    @Test
    fun providerHttpClient_streamCancellationAbortsUpstreamCall() = runBlocking {
        val slowUrl = startMockUpstream {
            post("/v1/chat/completions") {
                call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                    write("""data: {"id":"c","choices":[{"delta":{"content":"First"},"finish_reason":null}]}""" + "\n\n")
                    flush()
                    delay(10_000)
                    write("data: [DONE]\n\n")
                    flush()
                }
            }
        }
        val client = ProviderHttpClient()
        val seen = mutableListOf<String>()
        withTimeout(2_000) {
            client.streamChat(
                baseUrl = "$slowUrl/v1",
                apiFormat = ApiFormat.OPENAI,
                apiKey = "sk-local",
                customHeadersJson = "",
                params = ChatRequestParams(
                    messages = listOf(ChatMessagePayload("user", "Wait")),
                    modelId = "gpt-4o",
                    stream = true
                ),
                allowLocalHttp = true,
                timeoutSec = 30
            ).take(1).collect { ev ->
                if (ev is StreamEvent.Delta) seen += ev.text
            }
        }
        assertEquals(listOf("First"), seen)
    }

    // -------------------------------------------------------------------------
    // P1.3: OpenAI Responses API (/v1/responses)
    // -------------------------------------------------------------------------

    @Test
    fun openAiResponsesApi_handlesStructuredInputToolsStreamingAndRejectsUnsupportedStatefulFields() = runBlocking {
        var sawImageAndToolResult = false
        val upstreamUrl = startMockUpstream {
            post("/v1/chat/completions") {
                val reqJson = JSONObject(call.receiveText())
                val isStream = reqJson.optBoolean("stream", false)
                val msgs = reqJson.getJSONArray("messages")
                for (i in 0 until msgs.length()) {
                    if (msgs.getJSONObject(i).getString("role") == "tool") {
                        sawImageAndToolResult = true
                    }
                }
                if (!isStream) {
                    call.respondText(
                        text = """
                        {
                          "id":"chatcmpl-r1",
                          "model":"gpt-4o",
                          "choices":[{"index":0,"message":{"role":"assistant","content":"Chart checked","tool_calls":[{"id":"call_r1","type":"function","function":{"name":"save_note","arguments":"{\"title\":\"OK\"}"}}]},"finish_reason":"tool_calls"}],
                          "usage":{"prompt_tokens":40,"completion_tokens":15}
                        }
                        """.trimIndent(),
                        contentType = ContentType.Application.Json
                    )
                } else {
                    call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                        write("""data: {"id":"cs","choices":[{"delta":{"content":"Streamed resp"},"finish_reason":null}]}""" + "\n\n")
                        flush()
                        write("""data: {"id":"cs","choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":11,"completion_tokens":4}}""" + "\n\n")
                        flush()
                        write("data: [DONE]\n\n")
                        flush()
                    }
                }
            }
        }

        seedCloudProvider("prov-resp", "RespUpstream", "$upstreamUrl/v1", ApiFormat.OPENAI, "gpt-4o", alias = "jh-general")
        val baseUrl = startGatewayServer(authRequired = false)

        // 1. Non-streaming structured input items (message + input_image + function_call + function_call_output)
        val respReq = JSONObject()
            .put("model", "jh-general")
            .put("instructions", "Be factual")
            .put(
                "input",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("type", "message")
                            .put("role", "user")
                            .put(
                                "content",
                                JSONArray()
                                    .put(JSONObject().put("type", "input_text").put("text", "Analyze"))
                                    .put(JSONObject().put("type", "input_image").put("image_url", "https://example.com/a.png"))
                            )
                    )
                    .put(
                        JSONObject()
                            .put("type", "function_call")
                            .put("call_id", "call_prev_1")
                            .put("name", "fetch_metrics")
                            .put("arguments", "{}")
                    )
                    .put(
                        JSONObject()
                            .put("type", "function_call_output")
                            .put("call_id", "call_prev_1")
                            .put("output", "42")
                    )
            )
            .put(
                "tools",
                JSONArray().put(
                    JSONObject()
                        .put("type", "function")
                        .put("name", "save_note")
                        .put("description", "Save note")
                        .put("parameters", JSONObject("""{"type":"object","properties":{"title":{"type":"string"}}}"""))
                )
            )
            .toString()

        val respJson = httpClient.newCall(
            Request.Builder()
                .url("$baseUrl/v1/responses")
                .post(respReq.toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().use { resp ->
            assertEquals(200, resp.code)
            JSONObject(resp.body!!.string())
        }
        assertTrue(sawImageAndToolResult)
        assertTrue(respJson.getString("id").startsWith("resp_"))
        assertEquals("response", respJson.getString("object"))
        assertEquals("completed", respJson.getString("status"))
        assertEquals("Chart checked", respJson.getString("output_text"))
        val outputArr = respJson.getJSONArray("output")
        assertEquals("message", outputArr.getJSONObject(0).getString("type"))
        assertEquals("function_call", outputArr.getJSONObject(1).getString("type"))
        assertEquals("call_r1", outputArr.getJSONObject(1).getString("call_id"))
        assertEquals(55, respJson.getJSONObject("usage").getInt("total_tokens"))

        // 2. Streaming /v1/responses SSE event sequence
        val streamBody = JSONObject().put("model", "jh-general").put("input", "Hello").put("stream", true).toString()
        val sseText = httpClient.newCall(
            Request.Builder()
                .url("$baseUrl/v1/responses")
                .post(streamBody.toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().use { resp ->
            assertEquals(200, resp.code)
            resp.body!!.string()
        }
        assertTrue(sseText.contains("event: response.created"))
        assertTrue(sseText.contains("event: response.in_progress"))
        assertTrue(sseText.contains("event: response.output_item.added"))
        assertTrue(sseText.contains("event: response.output_text.delta"))
        assertTrue(sseText.contains("event: response.completed"))

        // 3. Unsupported stateful / built-in features return honest 501
        val unsupportedPayloads = listOf(
            """{"model":"jh-general","input":"hi","previous_response_id":"resp_old"}""",
            """{"model":"jh-general","input":"hi","background":true}""",
            """{"model":"jh-general","input":[{"type":"item_reference","id":"item_1"}]}""",
            """{"model":"jh-general","input":"hi","tools":[{"type":"web_search_preview"}]}"""
        )
        for (payload in unsupportedPayloads) {
            httpClient.newCall(
                Request.Builder()
                    .url("$baseUrl/v1/responses")
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().use { resp ->
                assertEquals(501, resp.code)
            }
        }
    }

    // -------------------------------------------------------------------------
    // P1.4: Anthropic Messages API (/v1/messages)
    // -------------------------------------------------------------------------

    @Test
    fun anthropicMessagesApi_handlesContentBlocksToolUseStreamingAndErrorEnvelope() = runBlocking {
        val upstreamUrl = startMockUpstream {
            post("/v1/messages") {
                val reqJson = JSONObject(call.receiveText())
                val isStream = reqJson.optBoolean("stream", false)
                if (!isStream) {
                    call.respondText(
                        text = """
                        {
                          "id":"msg_up_1",
                          "type":"message",
                          "role":"assistant",
                          "model":"claude-3-7-sonnet",
                          "content":[
                            {"type":"text","text":"Let me run grep."},
                            {"type":"tool_use","id":"toolu_up_1","name":"grep","input":{"pattern":"Phase"}}
                          ],
                          "stop_reason":"tool_use",
                          "usage":{"input_tokens":50,"output_tokens":28}
                        }
                        """.trimIndent(),
                        contentType = ContentType.Application.Json
                    )
                } else {
                    call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                        write("event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":12,\"output_tokens\":0}}}\n\n")
                        flush()
                        write("event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n")
                        flush()
                        write("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Anthropic SSE\"}}\n\n")
                        flush()
                        write("event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n")
                        flush()
                        write("event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":7}}\n\n")
                        flush()
                        write("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n")
                        flush()
                    }
                }
            }
        }

        seedCloudProvider("prov-ant", "AnthropicUpstream", upstreamUrl, ApiFormat.ANTHROPIC, "claude-3-7-sonnet", alias = "jh-claude")
        val clientToken = "jhgw_dev_ant_999"
        db.deviceDao().upsert(
            DeviceEntity(
                name = "Claude CLI",
                deviceType = "CLI",
                deviceCode = "DEV-CLD",
                encryptedToken = CryptoManager.encrypt(clientToken),
                tokenPreview = "jhgw_••••999",
                connectionType = DeviceConnectionType.LOCAL_NETWORK
            )
        )
        val baseUrl = startGatewayServer(authRequired = true)

        // 1. Missing max_tokens returns Anthropic-formatted 400 error envelope
        httpClient.newCall(
            Request.Builder()
                .url("$baseUrl/v1/messages")
                .header("x-api-key", clientToken)
                .post("""{"model":"jh-claude","messages":[{"role":"user","content":"Hi"}]}""".toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().use { resp ->
            assertEquals(400, resp.code)
            val errJson = JSONObject(resp.body!!.string())
            assertEquals("error", errJson.getString("type"))
            assertEquals("invalid_request_error", errJson.getJSONObject("error").getString("type"))
        }

        // 2. Full structured messages + system + image + tool_use + tool_result with x-api-key auth
        val antBody = JSONObject()
            .put("model", "jh-claude")
            .put("max_tokens", 512)
            .put("system", "Act as senior engineer")
            .put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject().put("role", "user").put(
                            "content",
                            JSONArray()
                                .put(JSONObject().put("type", "text").put("text", "Find Phase"))
                                .put(
                                    JSONObject().put("type", "image").put(
                                        "source",
                                        JSONObject()
                                            .put("type", "base64")
                                            .put("media_type", "image/png")
                                            .put("data", "iVBORw0KGgo=")
                                    )
                                )
                        )
                    )
                    .put(
                        JSONObject().put("role", "assistant").put(
                            "content",
                            JSONArray().put(
                                JSONObject()
                                    .put("type", "tool_use")
                                    .put("id", "toolu_0")
                                    .put("name", "grep")
                                    .put("input", JSONObject().put("pattern", "init"))
                            )
                        )
                    )
                    .put(
                        JSONObject().put("role", "user").put(
                            "content",
                            JSONArray().put(
                                JSONObject()
                                    .put("type", "tool_result")
                                    .put("tool_use_id", "toolu_0")
                                    .put("content", "matched 2 lines")
                            )
                        )
                    )
            )
            .put(
                "tools",
                JSONArray().put(
                    JSONObject()
                        .put("name", "grep")
                        .put("description", "Search files")
                        .put("input_schema", JSONObject("""{"type":"object","properties":{"pattern":{"type":"string"}}}"""))
                )
            )
            .toString()

        val respJson = httpClient.newCall(
            Request.Builder()
                .url("$baseUrl/v1/messages")
                .header("x-api-key", clientToken)
                .post(antBody.toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().use { resp ->
            assertEquals(200, resp.code)
            JSONObject(resp.body!!.string())
        }
        assertEquals("message", respJson.getString("type"))
        assertEquals("tool_use", respJson.getString("stop_reason"))
        assertEquals(50, respJson.getJSONObject("usage").getInt("input_tokens"))
        assertEquals(28, respJson.getJSONObject("usage").getInt("output_tokens"))
        val contentArr = respJson.getJSONArray("content")
        assertEquals("text", contentArr.getJSONObject(0).getString("type"))
        assertEquals("tool_use", contentArr.getJSONObject(1).getString("type"))
        assertEquals("toolu_up_1", contentArr.getJSONObject(1).getString("id"))

        // 3. Streaming /v1/messages SSE event sequence
        val antStreamBody = JSONObject()
            .put("model", "jh-claude")
            .put("max_tokens", 128)
            .put("stream", true)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Hello")))
            .toString()

        val antSse = httpClient.newCall(
            Request.Builder()
                .url("$baseUrl/v1/messages")
                .header("x-api-key", clientToken)
                .post(antStreamBody.toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().use { resp ->
            assertEquals(200, resp.code)
            resp.body!!.string()
        }
        assertTrue(antSse.contains("event: message_start"))
        assertTrue(antSse.contains("event: content_block_start"))
        assertTrue(antSse.contains("event: content_block_delta"))
        assertTrue(antSse.contains("Anthropic SSE"))
        assertTrue(antSse.contains("event: content_block_stop"))
        assertTrue(antSse.contains("event: message_delta"))
        assertTrue(antSse.contains("event: message_stop"))
    }
}
