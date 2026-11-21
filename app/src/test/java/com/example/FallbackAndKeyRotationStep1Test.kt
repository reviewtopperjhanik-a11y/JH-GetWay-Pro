package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.security.CryptoManager
import com.example.data.local.*
import com.example.gateway.SmartRouterEngine
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
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FallbackAndKeyRotationStep1Test {

    private lateinit var app: JhGetWayApplication
    private lateinit var db: JhGetWayDatabase
    private val upstreamServers = mutableListOf<EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>>()

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
        upstreamServers.forEach { runCatching { it.stop(100, 300) } }
        upstreamServers.clear()
        db.close()
        runBlocking(Dispatchers.IO) {
            app.preferences.clearAll()
            app.database.clearAllTables()
        }
        CryptoManager.resetTestKeyProvider()
    }

    private suspend fun startUpstream(block: io.ktor.server.routing.Routing.() -> Unit): String {
        val srv = embeddedServer(CIO, configure = { connector { host = "127.0.0.1"; port = 0 } }) {
            routing { block() }
        }.start(wait = false)
        upstreamServers += srv
        val port = srv.engine.resolvedConnectors().first().port
        return "http://127.0.0.1:$port"
    }

    private fun defaultSettings(autoAllowPaid: Boolean = true) = GatewaySettingsState(
        gatewayTimeoutSec = 15,
        maxRetries = 2,
        fallbackEnabled = true,
        localNetworkAccessAllowed = true,
        localRoutingPolicy = LocalRoutingPolicy.CLOUD_FIRST,
        autoAllowPaidModels = autoAllowPaid
    )

    @Test
    fun autoMode_key1RateLimited429_failsOverToKey2_andDeprioritizesKey1NextRequest() = runBlocking {
        val seenAuthHeaders = CopyOnWriteArrayList<String>()
        val url = startUpstream {
            post("/v1/chat/completions") {
                val auth = call.request.headers["Authorization"].orEmpty()
                seenAuthHeaders += auth
                if (auth == "Bearer sk-key-1") {
                    call.respondText(
                        """{"error":{"message":"Rate limit exceeded for key 1"}}""",
                        ContentType.Application.Json,
                        HttpStatusCode.TooManyRequests
                    )
                } else {
                    call.respondText(
                        """{"id":"ok","model":"m1","choices":[{"message":{"role":"assistant","content":"Key 2 answered"},"finish_reason":"stop"}]}""",
                        ContentType.Application.Json
                    )
                }
            }
        }

        db.providerDao().upsert(
            ProviderEntity(
                id = "prov_a",
                name = "Provider A",
                apiFormat = ApiFormat.OPENAI,
                baseUrl = "$url/v1",
                defaultModel = "m1",
                priority = 1,
                status = ProviderStatus.CONNECTED,
                enabled = true
            )
        )
        db.apiKeyDao().upsert(
            ApiKeyEntity(
                id = "k1",
                providerId = "prov_a",
                label = "Key 1",
                encryptedKey = CryptoManager.encrypt("sk-key-1"),
                maskedPreview = "sk-••1",
                priority = 1,
                isDefault = true,
                selectionPolicy = KeySelectionPolicy.PRIORITY,
                status = KeyStatus.ACTIVE,
                enabled = true
            )
        )
        db.apiKeyDao().upsert(
            ApiKeyEntity(
                id = "k2",
                providerId = "prov_a",
                label = "Key 2",
                encryptedKey = CryptoManager.encrypt("sk-key-2"),
                maskedPreview = "sk-••2",
                priority = 2,
                isDefault = false,
                selectionPolicy = KeySelectionPolicy.PRIORITY,
                status = KeyStatus.ACTIVE,
                enabled = true
            )
        )
        db.modelDao().upsert(
            ModelEntity(
                id = "prov_a::m1",
                modelId = "m1",
                displayName = "Model 1",
                providerId = "prov_a",
                providerName = "Provider A",
                supportsChat = true,
                supportsTools = true,
                supportsStreaming = true,
                enabled = true,
                isDefault = true
            )
        )

        val router = SmartRouterEngine(db, ProviderHttpClient())
        val settings = defaultSettings()

        // First request: Key 1 gets 429 -> Auto fails over to Key 2 on same provider/model
        val firstRes = router.executeRoutedChat(
            requestedModelOrAlias = "jh-getway/auto",
            messages = listOf(ChatMessagePayload("user", "hello")),
            settings = settings
        )
        assertTrue("Expected Key 2 failover to succeed: ${firstRes.execution.safeErrorMessage}", firstRes.execution.success)
        assertEquals("Key 2 answered", firstRes.execution.content)
        assertEquals(2, firstRes.decision.attemptCount)
        assertTrue(firstRes.decision.fallbackUsed)
        assertEquals(listOf("Bearer sk-key-1", "Bearer sk-key-2"), seenAuthHeaders.toList())

        val dbKeysAfterFirst = db.apiKeyDao().getForProvider("prov_a").associateBy { it.id }
        assertEquals(KeyStatus.RATE_LIMITED, dbKeysAfterFirst["k1"]?.status)
        assertEquals(KeyStatus.ACTIVE, dbKeysAfterFirst["k2"]?.status)
        assertEquals(ProviderStatus.CONNECTED, db.providerDao().getById("prov_a")?.status)

        // Second request immediately after: Key 1 is cooling down -> Key 2 must be tried FIRST!
        seenAuthHeaders.clear()
        val secondRes = router.executeRoutedChat(
            requestedModelOrAlias = "jh-getway/auto",
            messages = listOf(ChatMessagePayload("user", "hello again")),
            settings = settings
        )
        assertTrue(secondRes.execution.success)
        assertEquals(1, secondRes.decision.attemptCount)
        assertFalse(secondRes.decision.fallbackUsed)
        assertEquals(listOf("Bearer sk-key-2"), seenAuthHeaders.toList())
    }

    @Test
    fun autoMode_streaming_capsBadKeysPerProviderAndFailsOverToHealthyBackupProvider() = runBlocking {
        val seenPrimaryKeys = CopyOnWriteArrayList<String>()
        val primaryUrl = startUpstream {
            post("/v1/chat/completions") {
                seenPrimaryKeys += call.request.headers["Authorization"].orEmpty()
                call.respondText(
                    """{"error":{"message":"Invalid API key"}}""",
                    ContentType.Application.Json,
                    HttpStatusCode.Unauthorized
                )
            }
        }
        val backupUrl = startUpstream {
            post("/v1/chat/completions") {
                call.respondTextWriter(ContentType.Text.EventStream, HttpStatusCode.OK) {
                    write("data: ${JSONObject("""{"id":"b","choices":[{"index":0,"delta":{"content":"Backup stream OK"},"finish_reason":"stop"}]}""")}\n\n")
                    write("data: [DONE]\n\n")
                    flush()
                }
            }
        }

        db.providerDao().upsert(
            ProviderEntity(
                id = "p_bad", name = "BadKeysProvider", apiFormat = ApiFormat.OPENAI,
                baseUrl = "$primaryUrl/v1", defaultModel = "m-bad", priority = 1,
                status = ProviderStatus.CONNECTED, enabled = true
            )
        )
        db.providerDao().upsert(
            ProviderEntity(
                id = "p_good", name = "HealthyBackup", apiFormat = ApiFormat.OPENAI,
                baseUrl = "$backupUrl/v1", defaultModel = "m-good", priority = 2,
                status = ProviderStatus.CONNECTED, enabled = true
            )
        )
        db.apiKeyDao().upsert(ApiKeyEntity(id = "kb1", providerId = "p_bad", label = "B1", encryptedKey = CryptoManager.encrypt("sk-bad-1"), maskedPreview = "sk-b1", priority = 1, isDefault = true))
        db.apiKeyDao().upsert(ApiKeyEntity(id = "kb2", providerId = "p_bad", label = "B2", encryptedKey = CryptoManager.encrypt("sk-bad-2"), maskedPreview = "sk-b2", priority = 2))
        db.apiKeyDao().upsert(ApiKeyEntity(id = "kb3", providerId = "p_bad", label = "B3", encryptedKey = CryptoManager.encrypt("sk-bad-3"), maskedPreview = "sk-b3", priority = 3))
        db.apiKeyDao().upsert(ApiKeyEntity(id = "kg1", providerId = "p_good", label = "G1", encryptedKey = CryptoManager.encrypt("sk-good-1"), maskedPreview = "sk-g1", priority = 1, isDefault = true))

        val router = SmartRouterEngine(db, ProviderHttpClient())
        val events = router.streamRoutedChat(
            requestedModelOrAlias = "jh-getway/auto",
            messages = listOf(ChatMessagePayload("user", "hi")),
            settings = defaultSettings()
        ).toList()

        val completed = events.mapNotNull { it.first as? StreamEvent.Completed }.singleOrNull()
        assertNotNull("Expected stream to succeed via HealthyBackup", completed)
        assertEquals("Backup stream OK", completed!!.result.content)
        // Auto mode allows 1 backup key retry (max 2 key failures per provider) before skipping to HealthyBackup
        assertEquals(listOf("Bearer sk-bad-1", "Bearer sk-bad-2"), seenPrimaryKeys.toList())
    }

    @Test
    fun autoMode_singleFailedProviderWithMultipleModels_neverRetriesSameFailedProviderViaFallback() = runBlocking {
        val hits = AtomicInteger(0)
        val url = startUpstream {
            post("/v1/chat/completions") {
                hits.incrementAndGet()
                call.respondText(
                    """{"error":{"message":"Insufficient credits"}}""",
                    ContentType.Application.Json,
                    HttpStatusCode.PaymentRequired
                )
            }
        }

        db.providerDao().upsert(
            ProviderEntity(
                id = "p_only", name = "OnlyProvider", apiFormat = ApiFormat.OPENAI,
                baseUrl = "$url/v1", defaultModel = "m1", priority = 1,
                status = ProviderStatus.CONNECTED, enabled = true
            )
        )
        db.apiKeyDao().upsert(ApiKeyEntity(id = "k_only", providerId = "p_only", label = "K", encryptedKey = CryptoManager.encrypt("sk-only"), maskedPreview = "sk-o", isDefault = true))
        db.modelDao().upsertAll(
            listOf(
                ModelEntity(id = "p_only::m1", modelId = "m1", displayName = "M1", providerId = "p_only", providerName = "OnlyProvider", supportsChat = true, enabled = true),
                ModelEntity(id = "p_only::m2", modelId = "m2", displayName = "M2", providerId = "p_only", providerName = "OnlyProvider", supportsChat = true, enabled = true),
                ModelEntity(id = "p_only::m3", modelId = "m3", displayName = "M3", providerId = "p_only", providerName = "OnlyProvider", supportsChat = true, enabled = true)
            )
        )

        val router = SmartRouterEngine(db, ProviderHttpClient())
        val res = router.executeRoutedChat(
            requestedModelOrAlias = "jh-getway/auto",
            messages = listOf(ChatMessagePayload("user", "hi")),
            settings = defaultSettings()
        )
        assertFalse(res.execution.success)
        assertEquals(402, res.execution.statusCode)
        assertEquals("Failed single-key provider must only be called once, not retried on m2/m3", 1, hits.get())
        assertEquals(1, res.decision.attemptCount)
    }

    @Test
    fun autoFreeMode_expandsMultipleKeysAndFailsOverOn429() = runBlocking {
        val seenAuths = CopyOnWriteArrayList<String>()
        val url = startUpstream {
            post("/v1/chat/completions") {
                val auth = call.request.headers["Authorization"].orEmpty()
                seenAuths += auth
                if (auth == "Bearer sk-free-1") {
                    call.respondText("""{"error":{"message":"Too many requests"}}""", ContentType.Application.Json, HttpStatusCode.TooManyRequests)
                } else {
                    call.respondText("""{"id":"ok","model":"deepseek/chat:free","choices":[{"message":{"role":"assistant","content":"Free key 2 OK"},"finish_reason":"stop"}]}""", ContentType.Application.Json)
                }
            }
        }

        db.providerDao().upsert(
            ProviderEntity(
                id = "p_free", name = "OpenRouter", apiFormat = ApiFormat.OPENAI,
                baseUrl = "$url/v1", defaultModel = "deepseek/chat:free", priority = 1,
                status = ProviderStatus.CONNECTED, enabled = true
            )
        )
        db.apiKeyDao().upsert(ApiKeyEntity(id = "kf1", providerId = "p_free", label = "F1", encryptedKey = CryptoManager.encrypt("sk-free-1"), maskedPreview = "sk-f1", priority = 1, isDefault = true))
        db.apiKeyDao().upsert(ApiKeyEntity(id = "kf2", providerId = "p_free", label = "F2", encryptedKey = CryptoManager.encrypt("sk-free-2"), maskedPreview = "sk-f2", priority = 2))
        db.modelDao().upsert(
            ModelEntity(
                id = "p_free::m",
                modelId = "deepseek/chat:free",
                displayName = "DeepSeek Free",
                providerId = "p_free",
                providerName = "OpenRouter",
                supportsChat = true,
                enabled = true,
                accessTier = AccessTier.FREE_TIER,
                freeTierVerified = true
            )
        )

        val router = SmartRouterEngine(db, ProviderHttpClient())
        val res = router.executeRoutedChat(
            requestedModelOrAlias = "free",
            messages = listOf(ChatMessagePayload("user", "hi")),
            settings = defaultSettings(autoAllowPaid = false),
            routingModeOverride = RoutingMode.AUTO_FREE
        )
        assertTrue("Expected Auto-Free key failover to succeed: ${res.execution.safeErrorMessage}", res.execution.success)
        assertEquals("Free key 2 OK", res.execution.content)
        assertEquals(listOf("Bearer sk-free-1", "Bearer sk-free-2"), seenAuths.toList())
    }

    @Test
    fun roundRobinKeys_diagnosticPathDoesNotAdvanceKeyRotationCursor() = runBlocking {
        db.providerDao().upsert(
            ProviderEntity(
                id = "p_rr", name = "RRProv", apiFormat = ApiFormat.OPENAI,
                baseUrl = "https://example.invalid/v1", defaultModel = "rr-model", priority = 1,
                status = ProviderStatus.CONNECTED, enabled = true
            )
        )
        db.apiKeyDao().upsert(ApiKeyEntity(id = "kr1", providerId = "p_rr", label = "R1", encryptedKey = CryptoManager.encrypt("sk-rr-1"), maskedPreview = "sk-r1", priority = 1, selectionPolicy = KeySelectionPolicy.ROUND_ROBIN, status = KeyStatus.ACTIVE))
        db.apiKeyDao().upsert(ApiKeyEntity(id = "kr2", providerId = "p_rr", label = "R2", encryptedKey = CryptoManager.encrypt("sk-rr-2"), maskedPreview = "sk-r2", priority = 2, selectionPolicy = KeySelectionPolicy.ROUND_ROBIN, status = KeyStatus.ACTIVE))
        db.modelDao().upsert(
            ModelEntity(
                id = "p_rr::rr-model", modelId = "rr-model", displayName = "RR Model",
                providerId = "p_rr", providerName = "RRProv", supportsChat = true, enabled = true
            )
        )

        val router = SmartRouterEngine(db, ProviderHttpClient())
        val settings = defaultSettings()

        // Diagnostic check (missing vision model) calls diagnoseNoCandidates internally; must not mutate cursor
        val missingRes = router.executeRoutedChat(
            requestedModelOrAlias = "rr-model",
            messages = listOf(ChatMessagePayload("user", "hi")),
            settings = settings,
            requireVision = true
        )
        assertFalse(missingRes.execution.success)

        val (first, _) = router.resolveCandidates("rr-model", settings, RoutingMode.AUTOMATIC)
        val (second, _) = router.resolveCandidates("rr-model", settings, RoutingMode.AUTOMATIC)
        assertEquals(2, first.size)
        assertEquals(2, second.size)
        assertNotEquals("Consecutive resolveCandidates calls should rotate Round-Robin keys", first.first().keyEntityId, second.first().keyEntityId)
    }
}
