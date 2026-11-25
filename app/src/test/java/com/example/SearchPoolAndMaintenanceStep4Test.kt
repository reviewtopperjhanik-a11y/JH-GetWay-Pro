package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.security.CryptoManager
import com.example.data.local.*
import com.example.gateway.*
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SearchPoolAndMaintenanceStep4Test {

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
            app.gatewayServer.stop()
            app.preferences.clearAll()
            app.database.clearAllTables()
        }
        CryptoManager.resetTestKeyProvider()
    }

    private suspend fun startMockServer(block: io.ktor.server.routing.Routing.() -> Unit): String {
        val srv = embeddedServer(CIO, configure = { connector { host = "127.0.0.1"; port = 0 } }) {
            routing { block() }
        }.start(wait = false)
        upstreamServers += srv
        val port = srv.engine.resolvedConnectors().first().port
        return "http://127.0.0.1:$port"
    }

    @Test
    fun webSearchPool_excludesInvalidExpiredDisabledAfterCooldownWait_recoversRateLimited_andSupportsManualRetest() = runBlocking {
        val tavilyCalls = AtomicInteger(0)
        val exaCalls = AtomicInteger(0)
        val braveCalls = AtomicInteger(0)
        val tavilyFixed = AtomicBoolean(false)
        val braveRateLimited = AtomicBoolean(false)

        val tavilyUrl = startMockServer {
            post("/search") {
                tavilyCalls.incrementAndGet()
                if (!tavilyFixed.get()) {
                    call.respondText(
                        """{"detail":"Unauthorized"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.Unauthorized
                    )
                } else {
                    call.respondText(
                        """{"results":[{"title":"Tavily Fixed","url":"https://example.com/tavily","content":"Recovered"}]}""",
                        ContentType.Application.Json,
                        HttpStatusCode.OK
                    )
                }
            }
        }
        val exaUrl = startMockServer {
            post("/search") {
                exaCalls.incrementAndGet()
                call.respondText(
                    """{"results":[]}""",
                    ContentType.Application.Json,
                    HttpStatusCode.OK
                )
            }
        }
        val braveUrl = startMockServer {
            get("/res/v1/web/search") {
                braveCalls.incrementAndGet()
                if (braveRateLimited.get()) {
                    call.response.header(HttpHeaders.RetryAfter, "60")
                    call.respondText(
                        """{"message":"Too many requests"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.TooManyRequests
                    )
                } else {
                    call.respondText(
                        """{"web":{"results":[{"title":"Brave OK","url":"https://example.com/brave","description":"Good"}]}}""",
                        ContentType.Application.Json,
                        HttpStatusCode.OK
                    )
                }
            }
        }

        var clockMs = 1_765_000_000_000L
        db.servicePoolDao().upsert(
            ServicePoolKeyEntity(
                id = "ws-tavily-invalid",
                serviceType = ServicePoolType.WEB_SEARCH,
                providerId = "tavily",
                label = "Tavily Dead",
                encryptedKey = CryptoManager.encrypt("tvly-dead-key"),
                maskedPreview = "tvly-••••dead",
                priority = 1
            )
        )
        db.servicePoolDao().upsert(
            ServicePoolKeyEntity(
                id = "ws-exa-expired",
                serviceType = ServicePoolType.WEB_SEARCH,
                providerId = "exa",
                label = "Exa Expired",
                encryptedKey = CryptoManager.encrypt("exa-expired-key"),
                maskedPreview = "exa-••••exp",
                priority = 1,
                status = KeyStatus.EXPIRED,
                cooldownUntilMs = clockMs + 60_000L
            )
        )
        db.servicePoolDao().upsert(
            ServicePoolKeyEntity(
                id = "ws-brave-disabled-status",
                serviceType = ServicePoolType.WEB_SEARCH,
                providerId = "brave",
                label = "Brave Status Disabled",
                encryptedKey = CryptoManager.encrypt("BSA-disabled-key"),
                maskedPreview = "BSA-••••dis",
                priority = 1,
                enabled = true,
                status = KeyStatus.DISABLED
            )
        )
        db.servicePoolDao().upsert(
            ServicePoolKeyEntity(
                id = "ws-brave-healthy",
                serviceType = ServicePoolType.WEB_SEARCH,
                providerId = "brave",
                label = "Brave Good",
                encryptedKey = CryptoManager.encrypt("BSA-good-key"),
                maskedPreview = "BSA-••••good",
                priority = 2
            )
        )

        val service = WebSearchRouterService(
            servicePoolDao = db.servicePoolDao(),
            prefs = app.preferences,
            endpoints = WebSearchProviderEndpoints(
                tavilyBaseUrl = tavilyUrl,
                braveBaseUrl = braveUrl,
                serperBaseUrl = braveUrl,
                exaBaseUrl = exaUrl
            ),
            allowLocalEndpointsForTests = true,
            nowMs = { clockMs }
        )

        // 1. Search hits Tavily (401 -> INVALID, cooldownUntilMs = null) and rotates to Brave Good
        val first = service.search(ParsedWebSearchRequest(query = "kotlin flow", maxResults = 3))
        assertTrue(first is WebSearchGatewayResult.Success)
        assertEquals("brave", (first as WebSearchGatewayResult.Success).providerUsed)
        assertEquals(1, tavilyCalls.get())
        assertEquals(0, exaCalls.get())
        assertEquals(1, braveCalls.get())

        val tavilyAfter = db.servicePoolDao().getByIdOnce("ws-tavily-invalid")!!
        assertEquals(KeyStatus.INVALID, tavilyAfter.status)
        assertNull("Terminal INVALID key must not receive a temporary cooldown", tavilyAfter.cooldownUntilMs)

        // Health endpoint must count only the 1 healthy Brave key
        val healthBeforeAdvance = service.getHealthStatus()
        assertEquals(1, healthBeforeAdvance.getInt("available_keys_count"))
        assertEquals(4, healthBeforeAdvance.getInt("total_keys_count"))

        // 2. Advance clock past 10 minutes (exceeds old 5m cooldown) -> dead keys MUST remain excluded
        clockMs += 600_000L
        val second = service.search(ParsedWebSearchRequest(query = "android compose", maxResults = 3))
        assertTrue(second is WebSearchGatewayResult.Success)
        assertEquals(1, tavilyCalls.get())
        assertEquals(0, exaCalls.get())
        assertEquals(2, braveCalls.get())

        // 3. Rate limit Brave Good -> cooled down -> returns 429; once cooldown expires -> recovers
        braveRateLimited.set(true)
        val rlFail = service.search(ParsedWebSearchRequest(query = "rate limit probe", maxResults = 2))
        assertTrue(rlFail is WebSearchGatewayResult.Failure)
        assertEquals(429, (rlFail as WebSearchGatewayResult.Failure).statusCode)

        val blockedWhileCooling = service.search(ParsedWebSearchRequest(query = "during cooldown", maxResults = 2))
        assertTrue(blockedWhileCooling is WebSearchGatewayResult.Failure)
        assertEquals(429, (blockedWhileCooling as WebSearchGatewayResult.Failure).statusCode)
        assertEquals("rate_limit_exhausted", blockedWhileCooling.errorCode)
        assertEquals(3, braveCalls.get())

        // Expire Brave cooldown and confirm automatic recovery
        braveRateLimited.set(false)
        clockMs += 65_000L
        val recovered = service.search(ParsedWebSearchRequest(query = "after cooldown", maxResults = 2))
        assertTrue(recovered is WebSearchGatewayResult.Success)
        assertEquals(4, braveCalls.get())

        // 4. Disable Brave Good so only INVALID/EXPIRED/DISABLED keys remain -> returns 503 unavailable (not 429)
        val braveEntity = db.servicePoolDao().getByIdOnce("ws-brave-healthy")!!
        db.servicePoolDao().upsert(braveEntity.copy(enabled = false))
        val allDead = service.search(ParsedWebSearchRequest(query = "all dead keys", maxResults = 2))
        assertTrue(allDead is WebSearchGatewayResult.Failure)
        val allDeadFail = allDead as WebSearchGatewayResult.Failure
        assertEquals(503, allDeadFail.statusCode)
        assertEquals("search_providers_unavailable", allDeadFail.errorCode)
        assertNull(allDeadFail.retryAfterSeconds)

        // 5. Manual testKey() can still probe & restore a fixed INVALID key
        tavilyFixed.set(true)
        val testRes = service.testKey("ws-tavily-invalid")
        assertTrue(testRes.isSuccess)
        assertEquals(KeyStatus.ACTIVE, db.servicePoolDao().getByIdOnce("ws-tavily-invalid")!!.status)
        assertEquals(1, service.getHealthStatus().getInt("available_keys_count"))
    }

    @Test
    fun githubSearchPool_excludesInvalidExpiredDisabledAcrossSearchAndReadmeAndReturns503WhenAllTerminal() = runBlocking {
        val deadPatCalls = AtomicInteger(0)
        val expiredPatCalls = AtomicInteger(0)
        val disabledPatCalls = AtomicInteger(0)
        val goodPatCalls = AtomicInteger(0)

        val deadSecret = "ghp_deadPat111111111111111111111111"
        val expiredSecret = "ghp_expiredPat2222222222222222222222"
        val disabledSecret = "ghp_disabledPat333333333333333333333"
        val goodSecret = "ghp_goodPat444444444444444444444444"

        val ghUrl = startMockServer {
            get("/search/repositories") {
                val auth = call.request.header(HttpHeaders.Authorization).orEmpty()
                when {
                    auth.contains(deadSecret) -> {
                        deadPatCalls.incrementAndGet()
                        call.respondText("""{"message":"Bad credentials"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                    }
                    auth.contains(expiredSecret) -> {
                        expiredPatCalls.incrementAndGet()
                        call.respondText("""{"message":"Bad credentials"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                    }
                    auth.contains(disabledSecret) -> {
                        disabledPatCalls.incrementAndGet()
                        call.respondText("""{"message":"Bad credentials"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                    }
                    else -> {
                        goodPatCalls.incrementAndGet()
                        call.response.header("X-RateLimit-Remaining", "25")
                        call.respondText(
                            """{"total_count":1,"items":[{"full_name":"org/repo","html_url":"https://github.com/org/repo","description":"Repo","stargazers_count":10,"forks_count":2,"language":"Kotlin","updated_at":"2026-10-01T00:00:00Z","topics":[]}]}""",
                            ContentType.Application.Json,
                            HttpStatusCode.OK
                        )
                    }
                }
            }
            get("/repos/org/repo/readme") {
                val auth = call.request.header(HttpHeaders.Authorization).orEmpty()
                if (auth.contains(goodSecret)) {
                    call.response.header("X-RateLimit-Remaining", "24")
                    call.respondText("# Repo README", ContentType.Text.Plain, HttpStatusCode.OK)
                } else {
                    deadPatCalls.incrementAndGet()
                    call.respondText("""{"message":"Bad credentials"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                }
            }
        }

        var clockMs = 1_765_000_000_000L
        db.servicePoolDao().upsert(
            ServicePoolKeyEntity(
                id = "gh-dead",
                serviceType = ServicePoolType.GITHUB_SEARCH,
                providerId = "github",
                label = "Dead PAT",
                encryptedKey = CryptoManager.encrypt(deadSecret),
                maskedPreview = "ghp_••••1111",
                priority = 1
            )
        )
        db.servicePoolDao().upsert(
            ServicePoolKeyEntity(
                id = "gh-expired",
                serviceType = ServicePoolType.GITHUB_SEARCH,
                providerId = "github",
                label = "Expired PAT",
                encryptedKey = CryptoManager.encrypt(expiredSecret),
                maskedPreview = "ghp_••••2222",
                priority = 1,
                status = KeyStatus.EXPIRED,
                cooldownUntilMs = clockMs + 60_000L
            )
        )
        db.servicePoolDao().upsert(
            ServicePoolKeyEntity(
                id = "gh-disabled",
                serviceType = ServicePoolType.GITHUB_SEARCH,
                providerId = "github",
                label = "Disabled PAT",
                encryptedKey = CryptoManager.encrypt(disabledSecret),
                maskedPreview = "ghp_••••3333",
                priority = 1,
                status = KeyStatus.DISABLED
            )
        )
        db.servicePoolDao().upsert(
            ServicePoolKeyEntity(
                id = "gh-good",
                serviceType = ServicePoolType.GITHUB_SEARCH,
                providerId = "github",
                label = "Good PAT",
                encryptedKey = CryptoManager.encrypt(goodSecret),
                maskedPreview = "ghp_••••4444",
                priority = 2
            )
        )

        val ghService = GitHubSearchRouterService(
            servicePoolDao = db.servicePoolDao(),
            githubApiBaseUrl = ghUrl,
            allowLocalEndpointsForTests = true,
            nowMs = { clockMs }
        )

        // First search fails over from Dead PAT (401 -> INVALID, cooldownUntilMs = null) to Good PAT
        val res1 = ghService.search(ParsedGitHubSearchRequest(query = "kotlin", includeReadme = true))
        assertTrue(res1 is GitHubSearchGatewayResult.Success)
        assertEquals(1, deadPatCalls.get())
        assertEquals(0, expiredPatCalls.get())
        assertEquals(0, disabledPatCalls.get())
        assertEquals(1, goodPatCalls.get())

        val deadAfter = db.servicePoolDao().getByIdOnce("gh-dead")!!
        assertEquals(KeyStatus.INVALID, deadAfter.status)
        assertNull(deadAfter.cooldownUntilMs)
        assertEquals(1, ghService.getHealthStatus().getInt("healthy_tokens"))

        // Advance clock by 10 minutes -> Dead/Expired/Disabled PATs must never be retried
        clockMs += 600_000L
        val res2 = ghService.search(ParsedGitHubSearchRequest(query = "ktor", includeReadme = false))
        assertTrue(res2 is GitHubSearchGatewayResult.Success)
        assertEquals(1, deadPatCalls.get())
        assertEquals(0, expiredPatCalls.get())
        assertEquals(0, disabledPatCalls.get())
        assertEquals(2, goodPatCalls.get())

        // Disable Good PAT -> all remaining PATs are terminal -> 503 github_pats_unavailable
        val goodEntity = db.servicePoolDao().getByIdOnce("gh-good")!!
        db.servicePoolDao().upsert(goodEntity.copy(enabled = false))
        val res3 = ghService.search(ParsedGitHubSearchRequest(query = "coroutines", includeReadme = false))
        assertTrue(res3 is GitHubSearchGatewayResult.Failure)
        val fail3 = res3 as GitHubSearchGatewayResult.Failure
        assertEquals(503, fail3.statusCode)
        assertEquals("github_pats_unavailable", fail3.errorCode)
        assertNull(fail3.retryAfterSeconds)
    }

    @Test
    fun refreshExpiredCooldownStatuses_clearsExpiredRateLimitedAcrossAllEntitiesAndPreservesActiveOrTerminal() = runBlocking {
        val nowMs = 1_765_000_500_000L

        // Providers: 1 expired RATE_LIMITED, 1 still-cooling RATE_LIMITED, 1 ERROR
        db.providerDao().upsert(
            ProviderEntity(
                id = "p-rl-expired",
                name = "ExpiredRL",
                baseUrl = "https://example.com/v1",
                status = ProviderStatus.RATE_LIMITED,
                lastCheckedAt = nowMs - 120_000L,
                cooldownUntilMs = nowMs - 5_000L
            )
        )
        db.providerDao().upsert(
            ProviderEntity(
                id = "p-rl-active",
                name = "ActiveRL",
                baseUrl = "https://example.com/v1",
                status = ProviderStatus.RATE_LIMITED,
                lastCheckedAt = nowMs - 10_000L,
                cooldownUntilMs = nowMs + 45_000L
            )
        )
        db.providerDao().upsert(
            ProviderEntity(
                id = "p-err",
                name = "ErrorProv",
                baseUrl = "https://example.com/v1",
                status = ProviderStatus.ERROR,
                lastCheckedAt = nowMs - 120_000L,
                cooldownUntilMs = nowMs - 5_000L
            )
        )

        // API keys: 1 expired RATE_LIMITED, 1 INVALID
        db.apiKeyDao().upsert(
            ApiKeyEntity(
                id = "k-rl-expired",
                providerId = "p-rl-expired",
                label = "ExpiredRLKey",
                encryptedKey = CryptoManager.encrypt("sk-rl"),
                maskedPreview = "sk-••••rl",
                status = KeyStatus.RATE_LIMITED,
                lastUsedAt = nowMs - 120_000L,
                cooldownUntilMs = nowMs - 1_000L
            )
        )
        db.apiKeyDao().upsert(
            ApiKeyEntity(
                id = "k-invalid",
                providerId = "p-err",
                label = "InvalidKey",
                encryptedKey = CryptoManager.encrypt("sk-inv"),
                maskedPreview = "sk-••••inv",
                status = KeyStatus.INVALID
            )
        )

        // Local AI server: 1 expired RATE_LIMITED
        db.localAiServerDao().upsert(
            LocalAiServerEntity(
                id = "loc-rl-expired",
                name = "LocalRL",
                engineType = LocalEngineType.OLLAMA,
                serverUrl = "http://127.0.0.1:11434",
                status = ProviderStatus.RATE_LIMITED,
                lastCheckedAt = nowMs - 120_000L,
                cooldownUntilMs = nowMs - 2_000L
            )
        )

        // Service pool keys: 1 expired RATE_LIMITED, 1 header-blocked RATE_LIMITED, 1 EXPIRED
        db.servicePoolDao().upsert(
            ServicePoolKeyEntity(
                id = "spk-rl-expired",
                serviceType = ServicePoolType.WEB_SEARCH,
                providerId = "tavily",
                label = "ExpiredPoolRL",
                encryptedKey = CryptoManager.encrypt("tvly-rl"),
                maskedPreview = "tvly-••••rl",
                status = KeyStatus.RATE_LIMITED,
                lastUsedAt = nowMs - 120_000L,
                cooldownUntilMs = nowMs - 1_000L
            )
        )
        db.servicePoolDao().upsert(
            ServicePoolKeyEntity(
                id = "spk-rl-blocked",
                serviceType = ServicePoolType.GITHUB_SEARCH,
                providerId = "github",
                label = "BlockedPoolRL",
                encryptedKey = CryptoManager.encrypt("ghp-rl"),
                maskedPreview = "ghp-••••rl",
                status = KeyStatus.RATE_LIMITED,
                rateLimitRemaining = 0,
                rateLimitResetAtMs = nowMs + 60_000L,
                cooldownUntilMs = nowMs - 1_000L
            )
        )
        db.servicePoolDao().upsert(
            ServicePoolKeyEntity(
                id = "spk-expired",
                serviceType = ServicePoolType.WEB_SEARCH,
                providerId = "exa",
                label = "TerminalExpired",
                encryptedKey = CryptoManager.encrypt("exa-exp"),
                maskedPreview = "exa-••••exp",
                status = KeyStatus.EXPIRED
            )
        )

        val updatedCount = SmartRouterEngine.refreshExpiredCooldownStatuses(db, nowMs)
        assertEquals(4, updatedCount)

        val pExpired = db.providerDao().getById("p-rl-expired")!!
        assertEquals(ProviderStatus.UNKNOWN, pExpired.status)
        assertNull(pExpired.cooldownUntilMs)

        assertEquals(ProviderStatus.RATE_LIMITED, db.providerDao().getById("p-rl-active")!!.status)
        assertEquals(ProviderStatus.ERROR, db.providerDao().getById("p-err")!!.status)

        val kExpired = db.apiKeyDao().getAllOnce().first { it.id == "k-rl-expired" }
        assertEquals(KeyStatus.UNKNOWN, kExpired.status)
        assertNull(kExpired.cooldownUntilMs)
        assertEquals(KeyStatus.INVALID, db.apiKeyDao().getAllOnce().first { it.id == "k-invalid" }.status)

        val locExpired = db.localAiServerDao().getAllOnce().first { it.id == "loc-rl-expired" }
        assertEquals(ProviderStatus.UNKNOWN, locExpired.status)
        assertNull(locExpired.cooldownUntilMs)

        val spkCleared = db.servicePoolDao().getByIdOnce("spk-rl-expired")!!
        assertEquals(KeyStatus.UNKNOWN, spkCleared.status)
        assertNull(spkCleared.cooldownUntilMs)
        assertEquals(KeyStatus.RATE_LIMITED, db.servicePoolDao().getByIdOnce("spk-rl-blocked")!!.status)
        assertEquals(KeyStatus.EXPIRED, db.servicePoolDao().getByIdOnce("spk-expired")!!.status)
    }

    @Test
    fun gatewayForegroundService_runsCooldownMaintenanceWhileRunningAndStopsCleanly() = runBlocking {
        val pastMs = System.currentTimeMillis() - 120_000L
        app.database.providerDao().upsert(
            ProviderEntity(
                id = "svc-p-rl",
                name = "ServiceExpiredRL",
                baseUrl = "https://example.com/v1",
                status = ProviderStatus.RATE_LIMITED,
                lastCheckedAt = pastMs,
                cooldownUntilMs = pastMs + 30_000L
            )
        )
        app.database.apiKeyDao().upsert(
            ApiKeyEntity(
                id = "svc-k-rl",
                providerId = "svc-p-rl",
                label = "ServiceExpiredKey",
                encryptedKey = CryptoManager.encrypt("sk-svc-rl"),
                maskedPreview = "sk-••••rl",
                status = KeyStatus.RATE_LIMITED,
                lastUsedAt = pastMs,
                cooldownUntilMs = pastMs + 30_000L
            )
        )
        app.preferences.updateGatewaySettings(
            name = "Step4 Gateway",
            mode = GatewayMode.LOCAL,
            host = "127.0.0.1",
            port = 0,
            remoteUrl = "",
            authRequired = false,
            timeoutSec = 30,
            maxRequestKb = 256,
            streaming = true,
            rateLimitPerMin = 60,
            loggingLevel = LoggingLevel.MINIMAL
        )

        val startController = Robolectric.buildService(
            GatewayForegroundService::class.java,
            GatewayForegroundService.startGatewayIntent(app)
        ).create()
        startController.startCommand(0, 1)

        withTimeout(5_000) {
            app.gatewayServer.runtimeState.first { it.status == GatewayRunStatus.LOCAL_RUNNING }
            while (
                app.database.providerDao().getById("svc-p-rl")?.status == ProviderStatus.RATE_LIMITED ||
                app.database.apiKeyDao().getAllOnce().first { it.id == "svc-k-rl" }.status == KeyStatus.RATE_LIMITED
            ) {
                delay(50)
            }
        }

        val cleanedProv = app.database.providerDao().getById("svc-p-rl")!!
        val cleanedKey = app.database.apiKeyDao().getAllOnce().first { it.id == "svc-k-rl" }
        assertEquals(ProviderStatus.UNKNOWN, cleanedProv.status)
        assertNull(cleanedProv.cooldownUntilMs)
        assertEquals(KeyStatus.UNKNOWN, cleanedKey.status)
        assertNull(cleanedKey.cooldownUntilMs)

        val stopController = Robolectric.buildService(
            GatewayForegroundService::class.java,
            GatewayForegroundService.stopGatewayIntent(app)
        ).create()
        stopController.startCommand(0, 2)
        withTimeout(5_000) {
            while (
                app.gatewayServer.runtimeState.value.status != GatewayRunStatus.STOPPED ||
                !Shadows.shadowOf(stopController.get()).isStoppedBySelf
            ) {
                Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                delay(50)
            }
        }
        assertEquals(GatewayRunStatus.STOPPED, app.gatewayServer.runtimeState.value.status)
        assertTrue(Shadows.shadowOf(stopController.get()).isStoppedBySelf)
    }
}
