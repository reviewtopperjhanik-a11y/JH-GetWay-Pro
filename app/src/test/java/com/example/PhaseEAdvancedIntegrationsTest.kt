package com.example

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.core.security.CryptoManager
import com.example.core.security.DnsResolver
import com.example.core.security.EndpointLocality
import com.example.core.security.NetworkAddressClassifier
import com.example.data.local.*
import com.example.gateway.*
import com.example.network.ChatMessagePayload
import com.example.network.McpHttpClient
import com.example.network.ProviderHttpClient
import com.example.network.adapters.OpenAiProviderAdapter
import com.example.ui.screens.AnalyticsRange
import com.example.ui.screens.aggregateMetricsIntoBuckets
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PhaseEAdvancedIntegrationsTest {

    private lateinit var app: JhGetWayApplication
    private lateinit var db: JhGetWayDatabase
    private val servers = mutableListOf<EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>>()

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
        servers.forEach { runCatching { it.stop(100, 300) } }
        servers.clear()
        db.close()
        CryptoManager.resetTestKeyProvider()
    }

    private fun nextFreePort(): Int = ServerSocket(0).use { it.localPort }

    // -------------------------------------------------------------------------
    // 1. SSRF & DNS Rebinding Protections
    // -------------------------------------------------------------------------

    @Test
    fun ssrfGuard_blocksMetadataLinksAndDnsRebinding() {
        val blockedUrls = listOf(
            "http://169.254.169.254/latest/meta-data/",
            "https://metadata.google.internal/computeMetadata/v1/",
            "http://0.0.0.0:8080/v1/models",
            "http://[fe80::1]/secret",
            "http://[fd00:ec2::254]/latest/meta-data",
            "https://user:pass@api.openai.com/v1",
            "http://api.openai.com/v1/chat/completions"
        )
        blockedUrls.forEach { url ->
            val res = NetworkAddressClassifier.validateEndpointSecurity(url, allowLocalHttp = true)
            assertFalse("Expected URL to be blocked even with localHttp=true: $url", res.allowed)
        }

        val rebindDns = object : DnsResolver {
            override fun lookup(hostname: String): List<InetAddress> = when (hostname) {
                "rebind-loopback.example.com" -> listOf(InetAddress.getByName("127.0.0.1"))
                "rebind-metadata.example.com" -> listOf(InetAddress.getByName("169.254.169.254"))
                "rebind-lan.example.com" -> listOf(InetAddress.getByName("192.168.1.55"))
                else -> listOf(InetAddress.getByName("93.184.216.34"))
            }
        }

        val loopbackRebind = NetworkAddressClassifier.validateEndpointSecurity(
            urlString = "https://rebind-loopback.example.com/v1",
            allowLocalHttp = false,
            resolver = rebindDns
        )
        assertFalse("DNS rebinding to 127.0.0.1 must fail when allowLocalHttp=false", loopbackRebind.allowed)

        val lanRebind = NetworkAddressClassifier.validateEndpointSecurity(
            urlString = "https://rebind-lan.example.com/v1",
            allowLocalHttp = false,
            resolver = rebindDns
        )
        assertFalse("DNS rebinding to 192.168.x.x must fail when allowLocalHttp=false", lanRebind.allowed)

        val metadataRebind = NetworkAddressClassifier.validateEndpointSecurity(
            urlString = "https://rebind-metadata.example.com/v1",
            allowLocalHttp = true,
            resolver = rebindDns
        )
        assertFalse("DNS rebinding to 169.254.169.254 must always be blocked", metadataRebind.allowed)

        val safeDns = NetworkAddressClassifier.pinnedSafeDns(allowLocalHttp = false, resolver = rebindDns)
        try {
            safeDns.lookup("rebind-loopback.example.com")
            fail("Expected pinnedSafeDns to reject loopback DNS rebind")
        } catch (_: UnknownHostException) {
            // expected
        }
        try {
            safeDns.lookup("metadata.google.internal")
            fail("Expected pinnedSafeDns to reject metadata.google.internal")
        } catch (_: UnknownHostException) {
            // expected
        }

        assertEquals(EndpointLocality.LOOPBACK, NetworkAddressClassifier.classifyEndpointLocality("http://127.0.0.1:11434"))
        assertEquals(EndpointLocality.PRIVATE_LAN, NetworkAddressClassifier.classifyEndpointLocality("http://192.168.1.25:1234"))
        assertEquals(EndpointLocality.PUBLIC_REMOTE, NetworkAddressClassifier.classifyEndpointLocality("https://api.openai.com/v1", rebindDns))
    }

    // -------------------------------------------------------------------------
    // 2. MCP Hardening (Sandbox STDIO, Streamable HTTP, JSON-RPC 2.0, Guards)
    // -------------------------------------------------------------------------

    @Test
    fun mcpClient_rejectsStdioBinaryExecutionAndEnforcesJsonRpcAndGuards() = runBlocking {
        val mcp = McpHttpClient()

        // Direct desktop shell binary command under STDIO is strictly blocked without ProcessBuilder
        val stdioServer = McpServerEntity(
            name = "Shell MCP",
            transportType = McpTransportType.STDIO,
            endpointOrCommand = "npx -y @modelcontextprotocol/server-filesystem /tmp"
        )
        val discStdio = mcp.connectAndDiscover(stdioServer, "", "{}", allowLocalHttp = true)
        assertFalse(discStdio.success)
        assertTrue(discStdio.errorMessage.orEmpty().contains("sandbox", ignoreCase = true))

        val execStdio = mcp.executeTool(stdioServer, "read_file", "{}", "", "{}", allowLocalHttp = true)
        assertFalse(execStdio.success)
        assertTrue(execStdio.errorMessage.orEmpty().contains("sandbox", ignoreCase = true))

        // Dangerous tool classification
        assertTrue(McpHttpClient.isDangerousTool("shell_exec", "Run arbitrary shell"))
        assertTrue(McpHttpClient.isDangerousTool("delete_record", ""))
        assertFalse(McpHttpClient.isDangerousTool("get_weather", "Fetch local forecast"))

        // Real Ktor Streamable HTTP MCP server
        var sessionSeenOnCall: String? = null
        val server = embeddedServer(CIO, configure = { connector { port = 0; host = "127.0.0.1" } }) {
            routing {
                post("/mcp") {
                    val req = JSONObject(call.receiveText())
                    val id = req.optString("id")
                    val method = req.optString("method")
                    call.response.header("Mcp-Session-Id", "sess-mcp-42")
                    when (method) {
                        "initialize" -> call.respondText(
                            JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", JSONObject().put("protocolVersion", "2024-11-05")).toString(),
                            ContentType.Application.Json
                        )
                        "tools/list" -> call.respondText(
                            JSONObject().put("jsonrpc", "2.0").put("id", id).put(
                                "result",
                                JSONObject().put(
                                    "tools",
                                    JSONArray()
                                        .put(JSONObject().put("name", "get_weather").put("description", "Fetch weather"))
                                        .put(JSONObject().put("name", "shell_exec").put("description", "Execute command"))
                                        .put(JSONObject().put("name", "fail_tool").put("description", "Returns isError"))
                                        .put(JSONObject().put("name", "huge_tool").put("description", "Oversized output"))
                                )
                            ).toString(),
                            ContentType.Application.Json
                        )
                        "resources/list" -> call.respondText(
                            JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", JSONObject().put("resources", JSONArray())).toString(),
                            ContentType.Application.Json
                        )
                        "prompts/list" -> call.respondText(
                            JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", JSONObject().put("prompts", JSONArray())).toString(),
                            ContentType.Application.Json
                        )
                        "tools/call" -> {
                            sessionSeenOnCall = call.request.headers["Mcp-Session-Id"]
                            val params = req.getJSONObject("params")
                            val tName = params.optString("name")
                            when (tName) {
                                "get_weather" -> call.respondText(
                                    JSONObject().put("jsonrpc", "2.0").put("id", id).put(
                                        "result",
                                        JSONObject()
                                            .put("isError", false)
                                            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "Dhaka 31C Clear")))
                                    ).toString(),
                                    ContentType.Application.Json
                                )
                                "fail_tool" -> call.respondText(
                                    JSONObject().put("jsonrpc", "2.0").put("id", id).put(
                                        "result",
                                        JSONObject()
                                            .put("isError", true)
                                            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "Upstream MCP failure")))
                                    ).toString(),
                                    ContentType.Application.Json
                                )
                                "huge_tool" -> {
                                    val hugeText = "A".repeat(McpHttpClient.MAX_MCP_RESPONSE_BYTES + 2048)
                                    call.respondText(
                                        JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", JSONObject().put("text", hugeText)).toString(),
                                        ContentType.Application.Json
                                    )
                                }
                                else -> call.respondText(
                                    // Wrong JSON-RPC id -> must be rejected by client
                                    JSONObject().put("jsonrpc", "2.0").put("id", "wrong-id").put("result", JSONObject()).toString(),
                                    ContentType.Application.Json
                                )
                            }
                        }
                    }
                }
            }
        }.start(wait = false)
        servers += server
        val serverPort = server.engine.resolvedConnectors().first().port

        val httpSrv = McpServerEntity(
            name = "Local MCP Bridge",
            transportType = McpTransportType.STREAMABLE_HTTP,
            endpointOrCommand = "http://127.0.0.1:$serverPort/mcp"
        )
        val disc = mcp.connectAndDiscover(httpSrv, "mcp-secret", "{}", allowLocalHttp = true)
        assertTrue("Discovery failed: ${disc.errorMessage}", disc.success)
        assertEquals(4, disc.tools.size)
        // Strict default permission on all discovered tools
        assertTrue(disc.tools.all { it.permission == ToolPermission.ASK_BEFORE_RUNNING })

        val okExec = mcp.executeTool(httpSrv, "get_weather", "{\"city\":\"Dhaka\"}", "mcp-secret", "{}", allowLocalHttp = true)
        assertTrue(okExec.success)
        assertEquals("Dhaka 31C Clear", okExec.output)
        assertEquals("sess-mcp-42", sessionSeenOnCall)

        val errExec = mcp.executeTool(httpSrv, "fail_tool", "{}", "mcp-secret", "{}", allowLocalHttp = true)
        assertFalse("isError=true must fail tool execution", errExec.success)

        val hugeExec = mcp.executeTool(httpSrv, "huge_tool", "{}", "mcp-secret", "{}", allowLocalHttp = true)
        assertFalse("Oversized MCP response must be rejected", hugeExec.success)

        val mismatchExec = mcp.executeTool(httpSrv, "mismatch_id", "{}", "mcp-secret", "{}", allowLocalHttp = true)
        assertFalse("Mismatched JSON-RPC id must be rejected", mismatchExec.success)
    }

    // -------------------------------------------------------------------------
    // 3. Local AI Routing Policies & Credential Isolation
    // -------------------------------------------------------------------------

    @Test
    fun smartRouter_enforcesLocalRoutingPoliciesAndZeroCloudKeyLeakage() = runBlocking {
        val cloudProv = ProviderEntity(
            id = "p-cloud",
            name = "CloudOpenAI",
            baseUrl = "https://api.openai.com/v1",
            apiFormat = ApiFormat.OPENAI,
            defaultModel = "gpt-4o",
            priority = 1,
            customHeadersJson = "{\"X-Cloud-Secret\":\"cloud-header-secret\"}"
        )
        db.providerDao().upsert(cloudProv)
        db.apiKeyDao().upsert(
            ApiKeyEntity(
                providerId = cloudProv.id,
                label = "Cloud Key",
                encryptedKey = CryptoManager.encrypt("sk-cloud-top-secret-999"),
                maskedPreview = "sk-cl••••999",
                priority = 1,
                isDefault = true
            )
        )

        val localSrv = LocalAiServerEntity(
            id = "loc-1",
            name = "Ollama Local",
            engineType = LocalEngineType.OLLAMA,
            serverUrl = "http://127.0.0.1:11434",
            encryptedAuthToken = CryptoManager.encrypt("local-ollama-token"),
            hasAuth = true,
            defaultModel = "qwen2.5-coder:7b",
            isPrivateNetwork = true
        )
        db.localAiServerDao().upsert(localSrv)

        val router = SmartRouterEngine(db, ProviderHttpClient())

        // LOCAL_ONLY -> excludes cloud completely
        val (localOnly, _) = router.resolveCandidates(
            requestedModelOrAlias = "",
            settings = GatewaySettingsState(localRoutingPolicy = LocalRoutingPolicy.LOCAL_ONLY),
            mode = RoutingMode.AUTOMATIC
        )
        assertEquals(1, localOnly.size)
        assertTrue(localOnly.first().isLocal)
        assertEquals("local-ollama-token", localOnly.first().apiKey)
        assertEquals("", localOnly.first().customHeadersJson)

        // LOCAL_FIRST + allowCloudFallbackForLocal = false -> excludes cloud
        val (localStrict, _) = router.resolveCandidates(
            requestedModelOrAlias = "",
            settings = GatewaySettingsState(
                localRoutingPolicy = LocalRoutingPolicy.LOCAL_FIRST,
                allowCloudFallbackForLocal = false
            ),
            mode = RoutingMode.AUTOMATIC
        )
        assertEquals(1, localStrict.size)
        assertTrue(localStrict.all { it.isLocal })

        // LOCAL_FIRST + allowCloudFallbackForLocal = true -> local first, cloud fallback second
        val (localWithFallback, _) = router.resolveCandidates(
            requestedModelOrAlias = "",
            settings = GatewaySettingsState(
                localRoutingPolicy = LocalRoutingPolicy.LOCAL_FIRST,
                allowCloudFallbackForLocal = true
            ),
            mode = RoutingMode.AUTOMATIC
        )
        assertEquals(2, localWithFallback.size)
        assertTrue(localWithFallback[0].isLocal)
        assertFalse(localWithFallback[1].isLocal)
        assertNotEquals(localWithFallback[0].apiKey, localWithFallback[1].apiKey)

        // CLOUD_FIRST -> cloud first, local second
        val (cloudFirst, _) = router.resolveCandidates(
            requestedModelOrAlias = "",
            settings = GatewaySettingsState(localRoutingPolicy = LocalRoutingPolicy.CLOUD_FIRST),
            mode = RoutingMode.AUTOMATIC
        )
        assertEquals(2, cloudFirst.size)
        assertFalse(cloudFirst[0].isLocal)
        assertTrue(cloudFirst[1].isLocal)
    }

    // -------------------------------------------------------------------------
    // 4. Workflow Engine Hardening (Validation, Branch Skip, Merge, Retry, Cap)
    // -------------------------------------------------------------------------

    @Test
    fun workflowEngine_validatesGraphSkipsUnchosenBranchRetrysTransientFailureAndEnforcesStepCap() = runBlocking {
        val router = SmartRouterEngine(db, ProviderHttpClient())
        val engine = WorkflowEngine(db, router, McpHttpClient())

        // Validation catches cycle, missing config, unreachable output, duplicate ids
        assertEquals(
            "DUPLICATE_NODE_ID",
            engine.validateGraph(
                listOf(WorkflowNode("n1", WorkflowNodeType.INPUT, "A"), WorkflowNode("n1", WorkflowNodeType.OUTPUT, "B")),
                emptyList()
            ).errorKey
        )
        assertEquals(
            "MISSING_NODE_CONFIG",
            engine.validateGraph(
                listOf(
                    WorkflowNode("n1", WorkflowNodeType.INPUT, "In"),
                    WorkflowNode("n2", WorkflowNodeType.HTTP_REQUEST, "HTTP", emptyMap()),
                    WorkflowNode("n3", WorkflowNodeType.OUTPUT, "Out")
                ),
                listOf(WorkflowEdge("e1", "n1", "n2"), WorkflowEdge("e2", "n2", "n3"))
            ).errorKey
        )
        assertEquals(
            "UNREACHABLE_OUTPUT",
            engine.validateGraph(
                listOf(
                    WorkflowNode("n1", WorkflowNodeType.INPUT, "In"),
                    WorkflowNode("n2", WorkflowNodeType.OUTPUT, "Out")
                ),
                emptyList()
            ).errorKey
        )

        // Conditional branch + MERGE + HTTP retry test
        val attempts = AtomicInteger(0)
        val server = embeddedServer(CIO, configure = { connector { port = 0; host = "127.0.0.1" } }) {
            routing {
                get("/retry-ok") {
                    if (attempts.incrementAndGet() == 1) {
                        call.respondText("transient error", ContentType.Text.Plain, HttpStatusCode.ServiceUnavailable)
                    } else {
                        call.respondText("ok-after-retry", ContentType.Text.Plain, HttpStatusCode.OK)
                    }
                }
            }
        }.start(wait = false)
        servers += server
        val retryServerPort = server.engine.resolvedConnectors().first().port

        val nodes = listOf(
            WorkflowNode("n1", WorkflowNodeType.INPUT, "Start"),
            WorkflowNode("n2", WorkflowNodeType.CONDITION, "Has Code?", mapOf("contains" to "kotlin")),
            WorkflowNode("n3", WorkflowNodeType.HTTP_REQUEST, "True Branch HTTP", mapOf("url" to "http://127.0.0.1:$retryServerPort/retry-ok", "retryCount" to "2")),
            WorkflowNode("n4", WorkflowNodeType.TEXT_TRANSFORM, "False Branch", mapOf("operation" to "uppercase")),
            WorkflowNode("n5", WorkflowNodeType.MERGE, "Join"),
            WorkflowNode("n6", WorkflowNodeType.OUTPUT, "Done")
        )
        val edges = listOf(
            WorkflowEdge("e1", "n1", "n2"),
            WorkflowEdge("e2", "n2", "n3", "true"),
            WorkflowEdge("e3", "n2", "n4", "false"),
            WorkflowEdge("e4", "n3", "n5"),
            WorkflowEdge("e5", "n4", "n5"),
            WorkflowEdge("e6", "n5", "n6")
        )
        val (nJson, eJson) = engine.serializeGraph(nodes, edges)
        val wf = WorkflowEntity(name = "Conditional Retry Flow", nodesJson = nJson, edgesJson = eJson)
        db.workflowDao().upsertWorkflow(wf)

        val run = engine.executeWorkflow(
            workflow = wf,
            inputText = "kotlin coroutines test",
            settings = GatewaySettingsState(localNetworkAccessAllowed = true),
            onProgress = {},
            onRequestMcpApproval = { _, _ -> false }
        )
        assertEquals("COMPLETED", run.status)
        assertEquals("ok-after-retry", run.finalOutput)
        assertEquals(2, attempts.get())
        val nodeResults = JSONArray(run.nodeResultsJson)
        val statuses = (0 until nodeResults.length()).associate { idx ->
            val o = nodeResults.getJSONObject(idx)
            o.getString("nodeId") to o.getString("status")
        }
        assertEquals("COMPLETED", statuses["n3"])
        assertEquals("SKIPPED", statuses["n4"])
        assertEquals("COMPLETED", statuses["n5"])
        assertEquals("COMPLETED", statuses["n6"])

        // Max step limit enforcement (> 40 nodes)
        val chainNodes = (1..42).map { i ->
            when (i) {
                1 -> WorkflowNode("s$i", WorkflowNodeType.INPUT, "In")
                42 -> WorkflowNode("s$i", WorkflowNodeType.OUTPUT, "Out")
                else -> WorkflowNode("s$i", WorkflowNodeType.TEXT_TRANSFORM, "Step $i", mapOf("operation" to "trim"))
            }
        }
        val chainEdges = (1..41).map { i -> WorkflowEdge("se$i", "s$i", "s${i + 1}") }
        val (cnJson, ceJson) = engine.serializeGraph(chainNodes, chainEdges)
        val longWf = WorkflowEntity(name = "Oversized Chain", nodesJson = cnJson, edgesJson = ceJson)
        val longRun = engine.executeWorkflow(longWf, "hi", GatewaySettingsState(), {}, { _, _ -> false })
        assertEquals("FAILED", longRun.status)
        assertTrue(longRun.errorMessage.orEmpty().contains("40"))
    }

    // -------------------------------------------------------------------------
    // 5. DB Schema v4 Export & Migration (3 -> 4) + Analytics & Billing
    // -------------------------------------------------------------------------

    @Test
    fun roomSchemaV4_snapshotExistsAndMigration3To4PreservesData() {
        val candidates = listOf(
            File("schemas/com.example.data.local.JhGetWayDatabase/4.json"),
            File("app/schemas/com.example.data.local.JhGetWayDatabase/4.json")
        )
        val schemaFile = candidates.firstOrNull { it.exists() }
        assertNotNull("Expected Room exported schema snapshot 4.json to exist", schemaFile)
        val dbObj = JSONObject(schemaFile!!.readText()).getJSONObject("database")
        assertEquals(4, dbObj.getInt("version"))

        val dbName = "phase_e_migration_3_4.db"
        app.deleteDatabase(dbName)

        // Create authentic v3 database
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(app)
                .name(dbName)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(s: SupportSQLiteDatabase) {
                        createV3SchemaAndSeed(s)
                    }
                    override fun onUpgrade(s: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        helper.writableDatabase.close()
        helper.close()

        val migratedDb = Room.databaseBuilder(app, JhGetWayDatabase::class.java, dbName)
            .addMigrations(*JhGetWayDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val writableDb = migratedDb.openHelper.writableDatabase
            assertEquals(7, writableDb.version)
            writableDb.query("SELECT name FROM sqlite_master WHERE type='table' AND name='service_pool_keys'").use { c ->
                assertTrue("Expected service_pool_keys table to exist after v6 migration", c.moveToFirst())
            }
            runBlocking {
                val wfs = migratedDb.workflowDao().getWorkflowsOnce()
                assertEquals(1, wfs.size)
                assertEquals(1, wfs.first().version)

                val metrics = migratedDb.metricAndLogDao().getMetricsOnce()
                assertEquals(1, metrics.size)
                assertNull(metrics.first().actualBilledCostUsd)
                assertNull(metrics.first().pricingSource)
            }
        } finally {
            migratedDb.close()
            app.deleteDatabase(dbName)
        }
    }

    @Test
    fun analyticsBillingAndTimeBucketing_parseActualCostAndBucketAccurately() = runBlocking {
        // Parse provider usage.cost / total_cost in OpenAiProviderAdapter
        val adapter = OpenAiProviderAdapter()
        val parsed = adapter.parseChatResponse(
            bodyStr = JSONObject()
                .put("model", "openrouter/auto")
                .put(
                    "choices",
                    JSONArray().put(
                        JSONObject().put("finish_reason", "stop").put("message", JSONObject().put("content", "Hello"))
                    )
                )
                .put(
                    "usage",
                    JSONObject()
                        .put("prompt_tokens", 120)
                        .put("completion_tokens", 30)
                        .put("cost", 0.0042)
                )
                .toString(),
            latencyMs = 180L,
            statusCode = 200
        )
        assertTrue(parsed.success)
        assertEquals(0.0042, parsed.actualBilledCostUsd ?: 0.0, 1e-6)

        // Aggregate metrics into time buckets across 7 days
        val now = 1_760_000_000_000L
        val day = 86_400_000L
        val metrics = listOf(
            RequestMetricEntity(requestId = "1", timestamp = now - 6 * day + 3600_000L, providerId = "p1", providerName = "OpenAI", modelId = "gpt-4o", success = true, latencyMs = 200),
            RequestMetricEntity(requestId = "2", timestamp = now - 2 * day + 3600_000L, providerId = "p1", providerName = "OpenAI", modelId = "gpt-4o", success = false, latencyMs = 500),
            RequestMetricEntity(requestId = "3", timestamp = now - 10_000L, providerId = "p1", providerName = "OpenAI", modelId = "gpt-4o", success = true, latencyMs = 100)
        )
        val buckets = aggregateMetricsIntoBuckets(metrics, AnalyticsRange.DAYS_7, nowMs = now)
        assertEquals(7, buckets.size)
        assertEquals(3, buckets.sumOf { it.totalCount })
        assertEquals(2, buckets.sumOf { it.successCount })
        assertEquals(1, buckets.sumOf { it.failCount })
    }

    private fun createV3SchemaAndSeed(s: SupportSQLiteDatabase) {
        s.execSQL("CREATE TABLE IF NOT EXISTS `providers` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `type` TEXT NOT NULL, `presetId` TEXT NOT NULL, `baseUrl` TEXT NOT NULL, `apiFormat` TEXT NOT NULL, `defaultModel` TEXT NOT NULL, `priority` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `customHeadersJson` TEXT NOT NULL, `notes` TEXT NOT NULL, `status` TEXT NOT NULL, `lastLatencyMs` INTEGER, `lastError` TEXT, `lastCheckedAt` INTEGER, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        s.execSQL("CREATE TABLE IF NOT EXISTS `api_keys` (`id` TEXT NOT NULL, `providerId` TEXT NOT NULL, `label` TEXT NOT NULL, `encryptedKey` TEXT NOT NULL, `maskedPreview` TEXT NOT NULL, `priority` INTEGER NOT NULL, `isDefault` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `status` TEXT NOT NULL, `selectionPolicy` TEXT NOT NULL, `usageCount` INTEGER NOT NULL, `lastUsedAt` INTEGER, `lastTestedAt` INTEGER, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        s.execSQL("CREATE TABLE IF NOT EXISTS `models` (`id` TEXT NOT NULL, `modelId` TEXT NOT NULL, `displayName` TEXT NOT NULL, `providerId` TEXT NOT NULL, `providerName` TEXT NOT NULL, `modelType` TEXT NOT NULL, `capabilitiesCsv` TEXT NOT NULL, `contextWindow` INTEGER, `supportsTools` INTEGER NOT NULL, `supportsStreaming` INTEGER NOT NULL, `isLocal` INTEGER NOT NULL, `inputPricePer1M` REAL, `outputPricePer1M` REAL, `pricingUpdatedAt` TEXT, `enabled` INTEGER NOT NULL, `isDefault` INTEGER NOT NULL, `alias` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        s.execSQL("CREATE TABLE IF NOT EXISTS `routing_rules` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `modelAlias` TEXT NOT NULL, `allowedProviderIdsCsv` TEXT NOT NULL, `preferredProviderId` TEXT NOT NULL, `backupProviderIdsCsv` TEXT NOT NULL, `priority` INTEGER NOT NULL, `requiredCapabilitiesCsv` TEXT NOT NULL, `maxCostPer1M` REAL, `maxLatencyMs` INTEGER, `localCloudPreference` TEXT NOT NULL, `enabled` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        s.execSQL("CREATE TABLE IF NOT EXISTS `coding_agents` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `agentType` TEXT NOT NULL, `modelAlias` TEXT NOT NULL, `defaultModelId` TEXT NOT NULL, `allowedModelsCsv` TEXT NOT NULL, `streaming` INTEGER NOT NULL, `toolCalling` INTEGER NOT NULL, `dailyTokenLimit` INTEGER, `tokensUsedToday` INTEGER NOT NULL, `tokensResetDateUtc` TEXT NOT NULL, `lastUsageSource` TEXT NOT NULL, `requestCount` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `encryptedToken` TEXT NOT NULL, `tokenPreview` TEXT NOT NULL, `lastActiveAt` INTEGER, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        s.execSQL("CREATE TABLE IF NOT EXISTS `local_ai_servers` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `engineType` TEXT NOT NULL, `serverUrl` TEXT NOT NULL, `encryptedAuthToken` TEXT NOT NULL, `hasAuth` INTEGER NOT NULL, `timeoutSec` INTEGER NOT NULL, `defaultModel` TEXT NOT NULL, `isPrivateNetwork` INTEGER NOT NULL, `status` TEXT NOT NULL, `lastLatencyMs` INTEGER, `lastError` TEXT, `enabled` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        s.execSQL("CREATE TABLE IF NOT EXISTS `mcp_servers` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `transportType` TEXT NOT NULL, `endpointOrCommand` TEXT NOT NULL, `arguments` TEXT NOT NULL, `encryptedEnvJson` TEXT NOT NULL, `encryptedAuthToken` TEXT NOT NULL, `timeoutSec` INTEGER NOT NULL, `status` TEXT NOT NULL, `lastConnectedAt` INTEGER, `lastError` TEXT, `resourcesJson` TEXT NOT NULL, `promptsJson` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        s.execSQL("CREATE TABLE IF NOT EXISTS `mcp_tools` (`id` TEXT NOT NULL, `serverId` TEXT NOT NULL, `toolName` TEXT NOT NULL, `description` TEXT NOT NULL, `inputSchemaJson` TEXT NOT NULL, `permission` TEXT NOT NULL, `lastResultPreview` TEXT NOT NULL, `lastExecutedAt` INTEGER, PRIMARY KEY(`id`))")
        s.execSQL("CREATE TABLE IF NOT EXISTS `workflows` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `nodesJson` TEXT NOT NULL, `edgesJson` TEXT NOT NULL, `isTemplate` INTEGER NOT NULL, `lastRunStatus` TEXT, `lastRunAt` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        s.execSQL("CREATE TABLE IF NOT EXISTS `workflow_runs` (`id` TEXT NOT NULL, `workflowId` TEXT NOT NULL, `workflowName` TEXT NOT NULL, `status` TEXT NOT NULL, `inputText` TEXT NOT NULL, `finalOutput` TEXT NOT NULL, `nodeResultsJson` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `finishedAt` INTEGER, `errorMessage` TEXT, PRIMARY KEY(`id`))")
        s.execSQL("CREATE TABLE IF NOT EXISTS `devices` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `deviceType` TEXT NOT NULL, `deviceCode` TEXT NOT NULL, `encryptedToken` TEXT NOT NULL, `tokenPreview` TEXT NOT NULL, `permissionsCsv` TEXT NOT NULL, `allowedModelsCsv` TEXT NOT NULL, `connectionType` TEXT NOT NULL, `requestCount` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `revoked` INTEGER NOT NULL, `lastActiveAt` INTEGER, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        s.execSQL("CREATE TABLE IF NOT EXISTS `request_metrics` (`id` TEXT NOT NULL, `requestId` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `providerId` TEXT NOT NULL, `providerName` TEXT NOT NULL, `modelId` TEXT NOT NULL, `deviceName` TEXT NOT NULL, `endpoint` TEXT NOT NULL, `statusCode` INTEGER NOT NULL, `success` INTEGER NOT NULL, `latencyMs` INTEGER NOT NULL, `inputTokens` INTEGER NOT NULL, `outputTokens` INTEGER NOT NULL, `usageSource` TEXT NOT NULL, `estimatedCostUsd` REAL, `fallbackAttempts` INTEGER NOT NULL, `routingMode` TEXT NOT NULL, `routingReason` TEXT NOT NULL, `errorCode` TEXT, `safeErrorMessage` TEXT, PRIMARY KEY(`id`))")
        s.execSQL("CREATE TABLE IF NOT EXISTS `activity_logs` (`id` TEXT NOT NULL, `eventId` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `category` TEXT NOT NULL, `relatedEntity` TEXT NOT NULL, `status` TEXT NOT NULL, `safeMessage` TEXT NOT NULL, PRIMARY KEY(`id`))")
        s.execSQL("CREATE TABLE IF NOT EXISTS `playground_sessions` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `modelId` TEXT NOT NULL, `providerId` TEXT NOT NULL, `systemPrompt` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        s.execSQL("CREATE TABLE IF NOT EXISTS `playground_messages` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `role` TEXT NOT NULL, `content` TEXT NOT NULL, `modelId` TEXT NOT NULL, `providerName` TEXT NOT NULL, `latencyMs` INTEGER, `inputTokens` INTEGER, `outputTokens` INTEGER, `estimatedCostUsd` REAL, `isError` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        JhGetWayDatabase.MIGRATION_2_3.migrate(s)
        s.execSQL("INSERT INTO workflows (id, name, description, nodesJson, edgesJson, isTemplate, lastRunStatus, lastRunAt, createdAt, updatedAt) VALUES ('wf-legacy', 'Legacy Flow', '', '[]', '[]', 0, NULL, NULL, 1000, 1000)")
        s.execSQL("INSERT INTO request_metrics (id, requestId, timestamp, providerId, providerName, modelId, deviceName, endpoint, statusCode, success, latencyMs, inputTokens, outputTokens, usageSource, estimatedCostUsd, fallbackAttempts, routingMode, routingReason, errorCode, safeErrorMessage) VALUES ('rm-legacy', 'req-1', 1000, 'p1', 'OpenAI', 'gpt-4o', 'Phone', '/v1/chat/completions', 200, 1, 150, 10, 20, 'ACTUAL', 0.001, 0, 'AUTOMATIC', 'Priority', NULL, NULL)")
    }
}
