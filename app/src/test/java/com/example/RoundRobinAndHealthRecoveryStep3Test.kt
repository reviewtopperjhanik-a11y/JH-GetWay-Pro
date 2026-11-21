package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.security.CryptoManager
import com.example.data.local.*
import com.example.gateway.OpenCodeModelCatalogResolver
import com.example.gateway.SmartRouterEngine
import com.example.network.ChatMessagePayload
import com.example.network.ProviderHttpClient
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoundRobinAndHealthRecoveryStep3Test {

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

    private fun baseSettings(mode: RoutingMode = RoutingMode.ROUND_ROBIN, autoAllowPaid: Boolean = true) =
        GatewaySettingsState(
            defaultRoutingMode = mode,
            gatewayTimeoutSec = 15,
            maxRetries = 2,
            fallbackEnabled = true,
            localNetworkAccessAllowed = true,
            localRoutingPolicy = LocalRoutingPolicy.CLOUD_FIRST,
            autoAllowPaidModels = autoAllowPaid,
            preferConfiguredDefaultInAuto = true
        )

    @Test
    fun autoMode_roundRobin_rotatesAcrossDistinctProvidersEvenWithDefaultPinnedAndFallsBackCleanly() = runBlocking {
        val callSequence = CopyOnWriteArrayList<String>()
        val failFirstProvider = AtomicBoolean(false)

        val urlA = startUpstream {
            post("/v1/chat/completions") {
                callSequence += "A"
                if (failFirstProvider.get()) {
                    call.respondText("""{"error":{"message":"A outage"}}""", ContentType.Application.Json, HttpStatusCode.InternalServerError)
                } else {
                    call.respondText("""{"id":"a","model":"m-a1","choices":[{"message":{"role":"assistant","content":"from A"},"finish_reason":"stop"}]}""", ContentType.Application.Json)
                }
            }
        }
        val urlB = startUpstream {
            post("/v1/chat/completions") {
                callSequence += "B"
                call.respondText("""{"id":"b","model":"m-b1","choices":[{"message":{"role":"assistant","content":"from B"},"finish_reason":"stop"}]}""", ContentType.Application.Json)
            }
        }

        db.providerDao().upsert(
            ProviderEntity(
                id = "p_a", name = "Provider A", apiFormat = ApiFormat.OPENAI,
                baseUrl = "$urlA/v1", defaultModel = "m-a1", priority = 1,
                status = ProviderStatus.CONNECTED, enabled = true
            )
        )
        db.providerDao().upsert(
            ProviderEntity(
                id = "p_b", name = "Provider B", apiFormat = ApiFormat.OPENAI,
                baseUrl = "$urlB/v1", defaultModel = "m-b1", priority = 10,
                status = ProviderStatus.CONNECTED, enabled = true
            )
        )
        db.apiKeyDao().upsert(ApiKeyEntity(id = "ka", providerId = "p_a", label = "KA", encryptedKey = CryptoManager.encrypt("sk-a"), maskedPreview = "sk-a", isDefault = true, status = KeyStatus.ACTIVE))
        db.apiKeyDao().upsert(ApiKeyEntity(id = "kb", providerId = "p_b", label = "KB", encryptedKey = CryptoManager.encrypt("sk-b"), maskedPreview = "sk-b", isDefault = true, status = KeyStatus.ACTIVE))
        // Give Provider A multiple models + isDefault = true to verify fair provider-level rotation
        db.modelDao().upsertAll(
            listOf(
                ModelEntity(id = "p_a::m-a1", modelId = "m-a1", displayName = "A1", providerId = "p_a", providerName = "Provider A", supportsChat = true, enabled = true, isDefault = true),
                ModelEntity(id = "p_a::m-a2", modelId = "m-a2", displayName = "A2", providerId = "p_a", providerName = "Provider A", supportsChat = true, enabled = true),
                ModelEntity(id = "p_b::m-b1", modelId = "m-b1", displayName = "B1", providerId = "p_b", providerName = "Provider B", supportsChat = true, enabled = true)
            )
        )

        val router = SmartRouterEngine(db, ProviderHttpClient())
        val settings = baseSettings(RoutingMode.ROUND_ROBIN).copy(
            defaultProviderId = "p_a",
            defaultModelId = "m-a1"
        )

        // Diagnostic/counting calls with advanceRotation=false must not affect round-robin index
        router.countEligibleAutoFreeModels(settings)
        router.resolveCandidates("jh-getway/auto", settings, RoutingMode.ROUND_ROBIN, advanceRotation = false)

        // Four consecutive jh-getway/auto requests should alternate across Provider A and Provider B
        val winners = mutableListOf<String>()
        repeat(4) {
            val res = router.executeRoutedChat(
                requestedModelOrAlias = "jh-getway/auto",
                messages = listOf(ChatMessagePayload("user", "ping")),
                settings = settings
            )
            assertTrue("Expected call $it to succeed: ${res.execution.safeErrorMessage}", res.execution.success)
            winners += res.decision.selectedProvider
        }
        assertEquals(listOf("Provider A", "Provider B", "Provider A", "Provider B"), winners)

        // Next turn would pick Provider A first; make Provider A fail 500 -> immediate fallback hits Provider B
        failFirstProvider.set(true)
        callSequence.clear()
        val failoverRes = router.executeRoutedChat(
            requestedModelOrAlias = "jh-getway/auto",
            messages = listOf(ChatMessagePayload("user", "ping failover")),
            settings = settings
        )
        assertTrue(failoverRes.execution.success)
        assertEquals("Provider B", failoverRes.decision.selectedProvider)
        assertEquals(listOf("A", "B"), callSequence.toList())
    }

    @Test
    fun freeSelector_roundRobin_rotatesVerifiedFreeProvidersWhilePreservingSafetyGates() = runBlocking {
        val urlA = startUpstream {
            post("/v1/chat/completions") {
                call.respondText("""{"id":"fa","model":"free-a","choices":[{"message":{"role":"assistant","content":"Free A"},"finish_reason":"stop"}]}""", ContentType.Application.Json)
            }
        }
        val urlB = startUpstream {
            post("/v1/chat/completions") {
                call.respondText("""{"id":"fb","model":"free-b","choices":[{"message":{"role":"assistant","content":"Free B"},"finish_reason":"stop"}]}""", ContentType.Application.Json)
            }
        }
        val urlPaid = startUpstream {
            post("/v1/chat/completions") {
                call.respondText("""{"id":"fp","model":"paid-p","choices":[{"message":{"role":"assistant","content":"Paid"},"finish_reason":"stop"}]}""", ContentType.Application.Json)
            }
        }

        db.providerDao().upsert(ProviderEntity(id = "pf_a", name = "FreeProvA", apiFormat = ApiFormat.OPENAI, baseUrl = "$urlA/v1", defaultModel = "free-a", priority = 1, status = ProviderStatus.CONNECTED, enabled = true))
        db.providerDao().upsert(ProviderEntity(id = "pf_b", name = "FreeProvB", apiFormat = ApiFormat.OPENAI, baseUrl = "$urlB/v1", defaultModel = "free-b", priority = 2, status = ProviderStatus.CONNECTED, enabled = true))
        db.providerDao().upsert(ProviderEntity(id = "pp_c", name = "PaidOnly", apiFormat = ApiFormat.OPENAI, baseUrl = "$urlPaid/v1", defaultModel = "paid-p", priority = 0, status = ProviderStatus.CONNECTED, enabled = true))
        db.providerDao().upsert(
            ProviderEntity(
                id = "pf_cool", name = "CoolingFree", apiFormat = ApiFormat.OPENAI,
                baseUrl = "$urlA/v1", defaultModel = "free-cool", priority = 0,
                status = ProviderStatus.RATE_LIMITED, lastCheckedAt = System.currentTimeMillis(),
                cooldownUntilMs = System.currentTimeMillis() + 60_000L, enabled = true
            )
        )

        db.apiKeyDao().upsert(ApiKeyEntity(id = "kfa", providerId = "pf_a", label = "FA", encryptedKey = CryptoManager.encrypt("sk-fa"), maskedPreview = "sk-fa", status = KeyStatus.ACTIVE))
        db.apiKeyDao().upsert(ApiKeyEntity(id = "kfb", providerId = "pf_b", label = "FB", encryptedKey = CryptoManager.encrypt("sk-fb"), maskedPreview = "sk-fb", status = KeyStatus.ACTIVE))
        db.apiKeyDao().upsert(ApiKeyEntity(id = "kpc", providerId = "pp_c", label = "PC", encryptedKey = CryptoManager.encrypt("sk-pc"), maskedPreview = "sk-pc", status = KeyStatus.ACTIVE))
        db.apiKeyDao().upsert(ApiKeyEntity(id = "kfc", providerId = "pf_cool", label = "FC", encryptedKey = CryptoManager.encrypt("sk-fc"), maskedPreview = "sk-fc", status = KeyStatus.ACTIVE))

        db.modelDao().upsertAll(
            listOf(
                ModelEntity(id = "pf_a::free-a", modelId = "free-a", displayName = "Free A", providerId = "pf_a", providerName = "FreeProvA", supportsChat = true, supportsVision = false, enabled = true, accessTier = AccessTier.FREE_TIER, freeTierVerified = true),
                ModelEntity(id = "pf_b::free-b", modelId = "free-b", displayName = "Free B", providerId = "pf_b", providerName = "FreeProvB", supportsChat = true, supportsVision = true, enabled = true, accessTier = AccessTier.FREE_TIER, freeTierVerified = true),
                ModelEntity(id = "pp_c::paid-p", modelId = "paid-p", displayName = "Paid P", providerId = "pp_c", providerName = "PaidOnly", supportsChat = true, supportsVision = true, enabled = true, accessTier = AccessTier.PAID, freeTierVerified = false),
                ModelEntity(id = "pf_cool::free-cool", modelId = "free-cool", displayName = "Cooling Free", providerId = "pf_cool", providerName = "CoolingFree", supportsChat = true, enabled = true, accessTier = AccessTier.FREE_TIER, freeTierVerified = true)
            )
        )

        val router = SmartRouterEngine(db, ProviderHttpClient())
        val settings = baseSettings(RoutingMode.ROUND_ROBIN, autoAllowPaid = false)

        val pickedProviders = mutableListOf<String>()
        repeat(4) {
            val res = router.executeRoutedChat(
                requestedModelOrAlias = "free",
                messages = listOf(ChatMessagePayload("user", "hi")),
                settings = settings
            )
            assertTrue(res.execution.success)
            pickedProviders += res.decision.selectedProvider
        }
        assertEquals(listOf("FreeProvA", "FreeProvB", "FreeProvA", "FreeProvB"), pickedProviders)
        assertFalse(pickedProviders.contains("PaidOnly"))
        assertFalse(pickedProviders.contains("CoolingFree"))

        // Vision attachment gate must still exclude non-vision FreeProvA even in ROUND_ROBIN
        val (visionCands, _) = router.resolveCandidates(
            requestedModelOrAlias = "free",
            settings = settings,
            mode = RoutingMode.ROUND_ROBIN,
            requireVision = true
        )
        assertEquals(1, visionCands.size)
        assertEquals("pf_b", visionCands.first().id)
    }

    @Test
    fun expiredErrorCooldown_recoversHealthRankAboveDisconnectedAndClearsPenaltyInRouterAndCatalog() = runBlocking {
        val now = 1_780_000_000_000L
        val recoveredProvider = ProviderEntity(
            id = "p_rec",
            name = "RecoveredProvider",
            apiFormat = ApiFormat.OPENAI,
            baseUrl = "https://rec.example/v1",
            defaultModel = "claude-sonnet-4",
            priority = 2,
            status = ProviderStatus.ERROR,
            lastCheckedAt = now - 120_000L,
            cooldownUntilMs = now - 60_000L,
            lastLatencyMs = 150L,
            enabled = true
        )
        val disconnectedProvider = ProviderEntity(
            id = "p_disc",
            name = "DisconnectedProvider",
            apiFormat = ApiFormat.OPENAI,
            baseUrl = "https://disc.example/v1",
            defaultModel = "claude-sonnet-4",
            priority = 1,
            status = ProviderStatus.DISCONNECTED,
            lastLatencyMs = 100L,
            enabled = true
        )
        val keys = listOf(
            ApiKeyEntity(id = "k_rec", providerId = "p_rec", label = "KR", encryptedKey = CryptoManager.encrypt("sk-rec"), maskedPreview = "sk-r", status = KeyStatus.ACTIVE),
            ApiKeyEntity(id = "k_disc", providerId = "p_disc", label = "KD", encryptedKey = CryptoManager.encrypt("sk-disc"), maskedPreview = "sk-d", status = KeyStatus.ACTIVE)
        )
        val models = listOf(
            ModelEntity(id = "p_rec::m", modelId = "claude-sonnet-4", displayName = "Sonnet Rec", providerId = "p_rec", providerName = "RecoveredProvider", modelType = ModelType.CODING, supportsChat = true, supportsTools = true, enabled = true),
            ModelEntity(id = "p_disc::m", modelId = "claude-sonnet-4", displayName = "Sonnet Disc", providerId = "p_disc", providerName = "DisconnectedProvider", modelType = ModelType.CODING, supportsChat = true, supportsTools = true, enabled = true)
        )

        db.providerDao().upsert(recoveredProvider)
        db.providerDao().upsert(disconnectedProvider)
        keys.forEach { db.apiKeyDao().upsert(it) }
        db.modelDao().upsertAll(models)

        // Catalog resolver promotes expired ERROR -> UNKNOWN (probation) so it outranks DISCONNECTED
        val catalog = OpenCodeModelCatalogResolver.buildCuratedCatalog(
            providers = listOf(recoveredProvider, disconnectedProvider),
            localServers = emptyList(),
            allModels = models,
            allKeys = keys,
            routingRules = emptyList(),
            settings = baseSettings(RoutingMode.AUTOMATIC),
            nowMs = now
        )
        assertEquals("RecoveredProvider / claude-sonnet-4", catalog.autoResolvedTarget)
        assertEquals("RecoveredProvider / claude-sonnet-4", catalog.bestCodingResolvedTarget)

        // SmartRouterEngine ranks RecoveredProvider (effective UNKNOWN = rank 1) ahead of DISCONNECTED (rank 3)
        val router = SmartRouterEngine(db, ProviderHttpClient())
        val (cands, _) = router.resolveCandidates(
            requestedModelOrAlias = "jh-getway/auto",
            settings = baseSettings(RoutingMode.AUTOMATIC),
            mode = RoutingMode.AUTOMATIC,
            nowMs = now
        )
        assertEquals(2, cands.size)
        assertEquals("p_rec", cands.first().id)
        assertEquals(ProviderStatus.UNKNOWN, cands.first().providerStatus)
    }

    @Test
    fun expiredRateLimitedStatus_isLazilyResetToUnknownInDbUponRotationReEntry() = runBlocking {
        val now = System.currentTimeMillis()
        val serverUrl = startUpstream {
            post("/v1/chat/completions") {
                call.respondText("""{"id":"ok","model":"m-live","choices":[{"message":{"role":"assistant","content":"Healthy again"},"finish_reason":"stop"}]}""", ContentType.Application.Json)
            }
        }

        // Seed expired RATE_LIMITED provider, local server, and API key
        db.providerDao().upsert(
            ProviderEntity(
                id = "p_rl", name = "RateLimitedProv", apiFormat = ApiFormat.OPENAI,
                baseUrl = "$serverUrl/v1", defaultModel = "m-live", priority = 1,
                status = ProviderStatus.RATE_LIMITED,
                lastCheckedAt = now - 120_000L,
                cooldownUntilMs = now - 5_000L,
                enabled = true
            )
        )
        db.apiKeyDao().upsert(
            ApiKeyEntity(
                id = "k_rl", providerId = "p_rl", label = "RL Key",
                encryptedKey = CryptoManager.encrypt("sk-rl"), maskedPreview = "sk-rl",
                priority = 1, isDefault = true,
                status = KeyStatus.RATE_LIMITED,
                lastUsedAt = now - 120_000L,
                cooldownUntilMs = now - 5_000L,
                enabled = true
            )
        )
        db.localAiServerDao().upsert(
            LocalAiServerEntity(
                id = "srv_rl", name = "LocalRL", engineType = LocalEngineType.OPENAI_COMPATIBLE,
                serverUrl = "$serverUrl/v1", defaultModel = "local-m",
                status = ProviderStatus.RATE_LIMITED,
                lastCheckedAt = now - 120_000L,
                cooldownUntilMs = now - 5_000L,
                enabled = true
            )
        )
        db.modelDao().upsertAll(
            listOf(
                ModelEntity(id = "p_rl::m-live", modelId = "m-live", displayName = "Live", providerId = "p_rl", providerName = "RateLimitedProv", supportsChat = true, enabled = true),
                ModelEntity(id = "local:srv_rl::local-m", modelId = "local-m", displayName = "Local M", providerId = "local:srv_rl", providerName = "LocalRL", isLocal = true, accessTier = AccessTier.LOCAL, supportsChat = true, enabled = true)
            )
        )

        val router = SmartRouterEngine(db, ProviderHttpClient())
        val settings = baseSettings(RoutingMode.AUTOMATIC)

        // Real-time resolveCandidates re-entry lazily clears expired RATE_LIMITED -> UNKNOWN in DB
        val (cands, _) = router.resolveCandidates(
            requestedModelOrAlias = "jh-getway/auto",
            settings = settings,
            mode = RoutingMode.AUTOMATIC
        )
        assertTrue(cands.isNotEmpty())
        assertEquals(ProviderStatus.UNKNOWN, db.providerDao().getById("p_rl")?.status)
        assertNull(db.providerDao().getById("p_rl")?.cooldownUntilMs)
        assertEquals(KeyStatus.UNKNOWN, db.apiKeyDao().getForProvider("p_rl").single().status)
        assertNull(db.apiKeyDao().getForProvider("p_rl").single().cooldownUntilMs)
        assertEquals(ProviderStatus.UNKNOWN, db.localAiServerDao().getAllOnce().single().status)
        assertNull(db.localAiServerDao().getAllOnce().single().cooldownUntilMs)

        // Executing call succeeds and promotes cloud provider/key to CONNECTED/ACTIVE
        val res = router.executeRoutedChat(
            requestedModelOrAlias = "jh-getway/auto",
            messages = listOf(ChatMessagePayload("user", "recover")),
            settings = settings
        )
        assertTrue(res.execution.success)
        assertEquals(ProviderStatus.CONNECTED, db.providerDao().getById("p_rl")?.status)
        assertEquals(KeyStatus.ACTIVE, db.apiKeyDao().getForProvider("p_rl").single().status)
    }
}
