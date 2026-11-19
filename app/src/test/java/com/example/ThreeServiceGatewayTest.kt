package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.security.CryptoManager
import com.example.core.security.DeviceScope
import com.example.core.security.DeviceScopePolicy
import com.example.data.local.*
import com.example.gateway.*
import com.example.network.ProviderHttpClient
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.first
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
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ThreeServiceGatewayTest {

    private lateinit var app: JhGetWayApplication
    private lateinit var db: JhGetWayDatabase
    private val serversToStop = mutableListOf<LocalGatewayHttpServer>()
    private val mockUpstreamsToStop = mutableListOf<EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>>()

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
        runBlocking {
            app.preferences.clearAll()
            app.gatewayServer.stop()
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            serversToStop.forEach { runCatching { it.stop() } }
            serversToStop.clear()
            app.gatewayServer.stop()
            app.preferences.clearAll()
        }
        mockUpstreamsToStop.forEach { runCatching { it.stop(100, 300) } }
        mockUpstreamsToStop.clear()
        db.close()
        CryptoManager.resetTestKeyProvider()
    }

    // -------------------------------------------------------------------------
    // 1. Scope Isolation & Standardized Search Error Envelope
    // -------------------------------------------------------------------------

    @Test
    fun scopeIsolation_enforcesServiceTokensAndStandardizedSearchErrors() = runBlocking {
        val webOnlyToken = "jhgw_dev_web_only_1111"
        val githubOnlyToken = "jhgw_dev_gh_only_2222"
        val llmOnlyToken = "jhgw_dev_llm_only_3333"
        val adminToken = "jhgw_dev_admin_all_4444"

        insertDevice("Web Client", webOnlyToken, DeviceScopePolicy.formatScopesCsv(listOf(DeviceScope.WEB_SEARCH)))
        insertDevice("GitHub Client", githubOnlyToken, DeviceScopePolicy.formatScopesCsv(listOf(DeviceScope.GITHUB_SEARCH)))
        insertDevice("LLM Client", llmOnlyToken, DeviceScopePolicy.formatScopesCsv(listOf(DeviceScope.CHAT, DeviceScope.MODELS_READ)))
        insertDevice("R7 ATG Admin", adminToken, DeviceScopePolicy.formatScopesCsv(listOf(DeviceScope.ADMIN)))

        val server = startLocalGateway()
        val baseUrl = server.runtimeState.value.boundAddress

        // WEB_SEARCH token: allowed on /v1/search/web/*, forbidden on GitHub & LLM
        httpGet("$baseUrl/v1/search/web/health", webOnlyToken).use { resp ->
            assertEquals(200, resp.code)
            val json = JSONObject(resp.body!!.string())
            assertEquals("ok", json.getString("status"))
            assertEquals("web_search", json.getString("service"))
        }
        httpGet("$baseUrl/v1/search/github/health", webOnlyToken).use { resp ->
            assertEquals(403, resp.code)
            val err = JSONObject(resp.body!!.string()).getJSONObject("error")
            assertEquals("forbidden", err.getString("code"))
        }
        httpPost("$baseUrl/v1/search/github", """{"query":"kotlin"}""", webOnlyToken).use { resp ->
            assertEquals(403, resp.code)
            assertEquals("forbidden", JSONObject(resp.body!!.string()).getJSONObject("error").getString("code"))
        }
        httpGet("$baseUrl/v1/models", webOnlyToken).use { assertEquals(403, it.code) }
        httpPost("$baseUrl/v1/chat/completions", """{"model":"jh-getway/auto","messages":[{"role":"user","content":"hi"}]}""", webOnlyToken).use {
            assertEquals(403, it.code)
        }

        // GITHUB_SEARCH token: allowed on /v1/search/github/*, forbidden on Web Search & LLM
        httpGet("$baseUrl/v1/search/github/health", githubOnlyToken).use { resp ->
            assertEquals(200, resp.code)
            val json = JSONObject(resp.body!!.string())
            assertEquals("ok", json.getString("status"))
            assertEquals("github_search", json.getString("service"))
        }
        httpGet("$baseUrl/v1/search/web/health", githubOnlyToken).use { resp ->
            assertEquals(403, resp.code)
            assertEquals("forbidden", JSONObject(resp.body!!.string()).getJSONObject("error").getString("code"))
        }
        httpPost("$baseUrl/v1/search/web", """{"query":"ktor"}""", githubOnlyToken).use { resp ->
            assertEquals(403, resp.code)
            assertEquals("forbidden", JSONObject(resp.body!!.string()).getJSONObject("error").getString("code"))
        }
        httpGet("$baseUrl/v1/models", githubOnlyToken).use { assertEquals(403, it.code) }
        httpPost("$baseUrl/v1/chat/completions", """{"model":"jh-getway/auto","messages":[{"role":"user","content":"hi"}]}""", githubOnlyToken).use {
            assertEquals(403, it.code)
        }

        // CHAT+MODELS_READ token: allowed on /v1/models, forbidden on both search endpoints with search error envelope
        httpGet("$baseUrl/v1/models", llmOnlyToken).use { assertEquals(200, it.code) }
        httpPost("$baseUrl/v1/search/web", """{"query":"test"}""", llmOnlyToken).use { resp ->
            assertEquals(403, resp.code)
            assertEquals("forbidden", JSONObject(resp.body!!.string()).getJSONObject("error").getString("code"))
        }
        httpPost("$baseUrl/v1/search/github", """{"query":"test"}""", llmOnlyToken).use { resp ->
            assertEquals(403, resp.code)
            assertEquals("forbidden", JSONObject(resp.body!!.string()).getJSONObject("error").getString("code"))
        }

        // ADMIN token accesses all 3 services cleanly
        httpGet("$baseUrl/v1/models", adminToken).use { assertEquals(200, it.code) }
        httpGet("$baseUrl/v1/search/web/health", adminToken).use { assertEquals(200, it.code) }
        httpGet("$baseUrl/v1/search/github/health", adminToken).use { assertEquals(200, it.code) }

        // Missing/invalid token on search endpoints -> 401 standardized envelope
        httpPost("$baseUrl/v1/search/web", """{"query":"hello"}""", token = "invalid-token").use { resp ->
            assertEquals(401, resp.code)
            assertEquals("unauthorized", JSONObject(resp.body!!.string()).getJSONObject("error").getString("code"))
        }

        // Missing query -> 400 standardized envelope
        httpPost("$baseUrl/v1/search/web", """{"query":"   "}""", webOnlyToken).use { resp ->
            assertEquals(400, resp.code)
            assertEquals("invalid_request", JSONObject(resp.body!!.string()).getJSONObject("error").getString("code"))
        }
        httpPost("$baseUrl/v1/search/github", """{"query":"ok","mode":"issues"}""", githubOnlyToken).use { resp ->
            assertEquals(400, resp.code)
            assertEquals("invalid_request", JSONObject(resp.body!!.string()).getJSONObject("error").getString("code"))
        }
    }

    // -------------------------------------------------------------------------
    // 2. Root /health Advertises All 3 Services While Preserving Existing Contract
    // -------------------------------------------------------------------------

    @Test
    fun rootHealth_advertisesAllThreeServicesWithoutBreakingExistingFields() = runBlocking {
        val server = startLocalGateway()
        val baseUrl = server.runtimeState.value.boundAddress

        httpGet("$baseUrl/health", token = null).use { resp ->
            assertEquals(200, resp.code)
            val json = JSONObject(resp.body!!.string())
            assertEquals("ok", json.getString("status"))
            assertEquals(GatewayMode.LOCAL.name, json.getString("mode"))
            assertTrue(json.has("gateway"))
            val endpoints = json.getJSONArray("supported_endpoints")
            assertEquals(GatewayApiContract.supportedEndpoints.size, endpoints.length())

            val services = json.getJSONObject("services")
            assertTrue(services.getBoolean("llm"))
            assertTrue(services.getBoolean("web_search"))
            assertTrue(services.getBoolean("github_search"))
        }
    }

    // -------------------------------------------------------------------------
    // 3. Web Search Multi-Provider Rotation, 429 Cooldown, Cache, DDG & No Secret Leaks
    // -------------------------------------------------------------------------

    @Test
    fun webSearch_rotatesOn429CachesNormalizedResultsSupportsDdgFallbackAndNeverLeaksKeys() = runBlocking {
        val tavilyHits = AtomicInteger(0)
        val braveHits = AtomicInteger(0)
        val ddgHits = AtomicInteger(0)

        val tavilySecret = "tvly-secret-test-key-123456789"
        val braveSecret = "BSA-secret-brave-key-987654321"

        // Always return 429 from Tavily mock with Retry-After: 45 and secret echoed inside upstream body to test redaction
        val tavilyPort = findFreePort()
        startMockServer(tavilyPort) {
            routing {
                post("/search") {
                    tavilyHits.incrementAndGet()
                    call.response.header(HttpHeaders.RetryAfter, "45")
                    call.respondText(
                        """{"error":{"message":"Rate limit hit for api_key $tavilySecret"}}""",
                        ContentType.Application.Json,
                        HttpStatusCode.TooManyRequests
                    )
                }
            }
        }

        // Brave mock: 200 on first request, 429 on second request (to trigger all-keys-exhausted scenario)
        val braveShouldFail = java.util.concurrent.atomic.AtomicBoolean(false)
        val bravePort = findFreePort()
        startMockServer(bravePort) {
            routing {
                get("/res/v1/web/search") {
                    braveHits.incrementAndGet()
                    if (braveShouldFail.get()) {
                        call.response.header(HttpHeaders.RetryAfter, "40")
                        call.respondText(
                            """{"message":"Quota exceeded for $braveSecret"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.TooManyRequests
                        )
                    } else {
                        call.respondText(
                            """
                            {
                              "web": {
                                "results": [
                                  {
                                    "title": "Ktor CIO Docs",
                                    "url": "https://ktor.io/docs/server-engines.html",
                                    "description": "Asynchronous Ktor CIO server engine documentation.",
                                    "extra_snippets": ["Supports embedded HTTP servers on Android."],
                                    "page_age": "2026-01-10"
                                  }
                                ]
                              }
                            }
                            """.trimIndent(),
                            ContentType.Application.Json,
                            HttpStatusCode.OK
                        )
                    }
                }
            }
        }

        // DuckDuckGo HTML fallback mock
        val ddgPort = findFreePort()
        startMockServer(ddgPort) {
            routing {
                get("/html/") {
                    ddgHits.incrementAndGet()
                    val html = """
                        <html><body>
                        <div class="result">
                          <a class="result__a" href="https://kotlinlang.org/docs/coroutines-overview.html">Kotlin Coroutines Guide</a>
                          <a class="result__snippet">Asynchronous programming with coroutines in Kotlin.</a>
                        </div>
                        </body></html>
                    """.trimIndent()
                    call.respondText(html, ContentType.Text.Html, HttpStatusCode.OK)
                }
            }
        }

        db.servicePoolDao().upsert(
            ServicePoolKeyEntity(
                id = "ws-tavily-1",
                serviceType = ServicePoolType.WEB_SEARCH,
                providerId = "tavily",
                label = "Tavily Primary",
                encryptedKey = CryptoManager.encrypt(tavilySecret),
                maskedPreview = "tvly-••••6789",
                priority = 1
            )
        )
        db.servicePoolDao().upsert(
            ServicePoolKeyEntity(
                id = "ws-brave-1",
                serviceType = ServicePoolType.WEB_SEARCH,
                providerId = "brave",
                label = "Brave Secondary",
                encryptedKey = CryptoManager.encrypt(braveSecret),
                maskedPreview = "BSA-••••4321",
                priority = 2
            )
        )

        var clockMs = 1_765_000_000_000L
        val webSearchRouter = WebSearchRouterService(
            servicePoolDao = db.servicePoolDao(),
            prefs = app.preferences,
            endpoints = WebSearchProviderEndpoints(
                tavilyBaseUrl = "http://127.0.0.1:$tavilyPort",
                braveBaseUrl = "http://127.0.0.1:$bravePort",
                serperBaseUrl = "http://127.0.0.1:$bravePort",
                exaBaseUrl = "http://127.0.0.1:$bravePort",
                duckDuckGoHtmlUrl = "http://127.0.0.1:$ddgPort/html/"
            ),
            allowLocalEndpointsForTests = true,
            nowMs = { clockMs }
        )

        val token = "jhgw_dev_web_search_test"
        insertDevice("R7 ATG Search", token, DeviceScopePolicy.formatScopesCsv(listOf(DeviceScope.WEB_SEARCH)))

        val server = startLocalGateway(webSearchRouter = webSearchRouter)
        val baseUrl = server.runtimeState.value.boundAddress

        // 1. First query hits Tavily (429 -> cooled down), immediately rotates to Brave (200)
        httpPost(
            "$baseUrl/v1/search/web",
            """{"query":"Ktor CIO Android","max_results":3,"search_depth":"basic","include_content":true}""",
            token
        ).use { resp ->
            assertEquals(200, resp.code)
            val bodyStr = resp.body!!.string()
            assertFalse(bodyStr.contains(tavilySecret))
            assertFalse(bodyStr.contains(braveSecret))
            val json = JSONObject(bodyStr)
            assertEquals("brave", json.getString("provider_used"))
            assertFalse(json.getBoolean("cached"))
            val results = json.getJSONArray("results")
            assertEquals(1, results.length())
            assertEquals("Ktor CIO Docs", results.getJSONObject(0).getString("title"))
            assertEquals("https://ktor.io/docs/server-engines.html", results.getJSONObject(0).getString("url"))
            assertTrue(results.getJSONObject(0).getString("content").contains("embedded HTTP servers"))
        }
        assertEquals(1, tavilyHits.get())
        assertEquals(1, braveHits.get())

        // Verify Tavily key is marked RATE_LIMITED with cooldown and sanitized error
        val tavilyKeyAfter = db.servicePoolDao().getByIdOnce("ws-tavily-1")!!
        assertEquals(KeyStatus.RATE_LIMITED, tavilyKeyAfter.status)
        assertTrue((tavilyKeyAfter.cooldownUntilMs ?: 0L) > clockMs)
        assertFalse(tavilyKeyAfter.lastError.orEmpty().contains(tavilySecret))

        // 2. Identical query hits 15-minute TTL cache with 0 extra upstream requests
        httpPost(
            "$baseUrl/v1/search/web",
            """{"query":"  ktor   cio android ","max_results":3,"search_depth":"basic","include_content":true}""",
            token
        ).use { resp ->
            assertEquals(200, resp.code)
            val json = JSONObject(resp.body!!.string())
            assertEquals("brave", json.getString("provider_used"))
            assertTrue(json.getBoolean("cached"))
        }
        assertEquals(1, tavilyHits.get())
        assertEquals(1, braveHits.get())

        // 3. New query when Brave also returns 429 and DDG fallback is DISABLED -> 429 rate_limit_exhausted + Retry-After
        braveShouldFail.set(true)
        httpPost("$baseUrl/v1/search/web", """{"query":"kotlin coroutines flow","max_results":2}""", token).use { resp ->
            assertEquals(429, resp.code)
            assertNotNull(resp.header("Retry-After"))
            val bodyStr = resp.body!!.string()
            assertFalse(bodyStr.contains(tavilySecret))
            assertFalse(bodyStr.contains(braveSecret))
            val err = JSONObject(bodyStr).getJSONObject("error")
            assertEquals("rate_limit_exhausted", err.getString("code"))
            assertTrue(err.optInt("retry_after_seconds", 0) > 0)
        }
        // Tavily was skipped because it is in cooldown
        assertEquals(1, tavilyHits.get())
        assertEquals(2, braveHits.get())

        // 4. Enable DuckDuckGo fallback -> uncached query succeeds via duckduckgo
        app.preferences.setWebSearchDuckDuckGoFallbackEnabled(true)
        httpPost("$baseUrl/v1/search/web", """{"query":"kotlin coroutines guide","max_results":2}""", token).use { resp ->
            assertEquals(200, resp.code)
            val json = JSONObject(resp.body!!.string())
            assertEquals("duckduckgo", json.getString("provider_used"))
            assertFalse(json.getBoolean("cached"))
            val results = json.getJSONArray("results")
            assertEquals(1, results.length())
            assertEquals("Kotlin Coroutines Guide", results.getJSONObject(0).getString("title"))
        }
        assertEquals(1, ddgHits.get())

        // Verify activity logs contain zero plaintext API secrets
        val allLogs = db.metricAndLogDao().observeLogs().first().joinToString("\n") { it.safeMessage }
        assertFalse(allLogs.contains(tavilySecret))
        assertFalse(allLogs.contains(braveSecret))
    }

    // -------------------------------------------------------------------------
    // 4. GitHub Search PAT Rotation, RateLimit Headers, Repositories+README, Code & Cache
    // -------------------------------------------------------------------------

    @Test
    fun githubSearch_rotatesPatsTracksRateLimitsFetchesReadmeSupportsCodeModeAndCaches() = runBlocking {
        val patOne = "ghp_patOneSecretValue111111111111"
        val patTwo = "ghp_patTwoSecretValue222222222222"

        val pat1SearchCalls = AtomicInteger(0)
        val pat2SearchCalls = AtomicInteger(0)
        val readmeCalls = AtomicInteger(0)

        var clockMs = 1_765_000_000_000L
        val resetEpochSec = (clockMs / 1000L) + 120L

        val ghPort = findFreePort()
        startMockServer(ghPort) {
            routing {
                get("/search/repositories") {
                    val auth = call.request.header(HttpHeaders.Authorization).orEmpty()
                    if (auth.contains(patOne)) {
                        pat1SearchCalls.incrementAndGet()
                        call.response.header("X-RateLimit-Remaining", "0")
                        call.response.header("X-RateLimit-Reset", resetEpochSec.toString())
                        call.respondText(
                            """{"message":"API rate limit exceeded for token $patOne"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.Forbidden
                        )
                    } else {
                        pat2SearchCalls.incrementAndGet()
                        call.response.header("X-RateLimit-Remaining", "29")
                        call.response.header("X-RateLimit-Reset", resetEpochSec.toString())
                        call.respondText(
                            """
                            {
                              "total_count": 1,
                              "items": [
                                {
                                  "full_name": "JetBrains/kotlin",
                                  "html_url": "https://github.com/JetBrains/kotlin",
                                  "description": "The Kotlin Programming Language.",
                                  "stargazers_count": 50123,
                                  "forks_count": 5800,
                                  "language": "Kotlin",
                                  "license": { "spdx_id": "Apache-2.0", "name": "Apache License 2.0" },
                                  "updated_at": "2026-10-01T12:00:00Z",
                                  "topics": ["kotlin", "compiler"]
                                }
                              ]
                            }
                            """.trimIndent(),
                            ContentType.Application.Json,
                            HttpStatusCode.OK
                        )
                    }
                }
                get("/search/code") {
                    pat2SearchCalls.incrementAndGet()
                    call.response.header("X-RateLimit-Remaining", "28")
                    call.response.header("X-RateLimit-Reset", resetEpochSec.toString())
                    call.respondText(
                        """
                        {
                          "total_count": 1,
                          "items": [
                            {
                              "name": "LocalGatewayHttpServer.kt",
                              "path": "app/src/main/java/com/example/gateway/LocalGatewayHttpServer.kt",
                              "html_url": "https://github.com/jhsoft/jhgetway/blob/main/LocalGatewayHttpServer.kt",
                              "repository": {
                                "full_name": "jhsoft/jhgetway",
                                "description": "Android AI Gateway",
                                "stargazers_count": 420,
                                "forks_count": 35,
                                "language": "Kotlin",
                                "updated_at": "2026-10-15T08:00:00Z"
                              },
                              "text_matches": [
                                { "fragment": "fun start(settings: GatewaySettingsState)" }
                              ]
                            }
                          ]
                        }
                        """.trimIndent(),
                        ContentType.Application.Json,
                        HttpStatusCode.OK
                    )
                }
                get("/repos/{owner}/{repo}/readme") {
                    readmeCalls.incrementAndGet()
                    call.response.header("X-RateLimit-Remaining", "4990")
                    call.response.header("X-RateLimit-Reset", resetEpochSec.toString())
                    val longReadme = buildString {
                        append("<!-- hidden badge comment -->\n")
                        append("[![Build](https://img.shields.io/badge/build-passing-green)](https://ci.example)\n")
                        append("# Kotlin\n\nModern multiplatform language. ")
                        append("A".repeat(2500))
                    }
                    call.respondText(longReadme, ContentType.Text.Plain, HttpStatusCode.OK)
                }
            }
        }

        // Put PAT #1 higher priority so it is attempted first and triggers failover to PAT #2
        db.servicePoolDao().upsert(
            ServicePoolKeyEntity(
                id = "gh-pat-1",
                serviceType = ServicePoolType.GITHUB_SEARCH,
                providerId = "github",
                label = "PAT 1",
                encryptedKey = CryptoManager.encrypt(patOne),
                maskedPreview = "ghp_••••1111",
                priority = 1
            )
        )
        db.servicePoolDao().upsert(
            ServicePoolKeyEntity(
                id = "gh-pat-2",
                serviceType = ServicePoolType.GITHUB_SEARCH,
                providerId = "github",
                label = "PAT 2",
                encryptedKey = CryptoManager.encrypt(patTwo),
                maskedPreview = "ghp_••••2222",
                priority = 2
            )
        )

        val ghRouter = GitHubSearchRouterService(
            servicePoolDao = db.servicePoolDao(),
            githubApiBaseUrl = "http://127.0.0.1:$ghPort",
            allowLocalEndpointsForTests = true,
            nowMs = { clockMs }
        )

        val token = "jhgw_dev_github_search_test"
        insertDevice("R7 ATG GitHub", token, DeviceScopePolicy.formatScopesCsv(listOf(DeviceScope.GITHUB_SEARCH)))
        val server = startLocalGateway(githubSearchRouter = ghRouter)
        val baseUrl = server.runtimeState.value.boundAddress

        // 1. Repository search with include_readme = true -> PAT #1 returns 403 remaining=0, rotates to PAT #2
        httpPost(
            "$baseUrl/v1/search/github",
            """{"query":"kotlin compiler","mode":"repositories","language":"kotlin","sort":"stars","max_results":3,"include_readme":true}""",
            token
        ).use { resp ->
            assertEquals(200, resp.code)
            val bodyStr = resp.body!!.string()
            assertFalse(bodyStr.contains(patOne))
            assertFalse(bodyStr.contains(patTwo))
            val json = JSONObject(bodyStr)
            assertEquals("repositories", json.getString("mode"))
            assertFalse(json.getBoolean("cached"))
            val items = json.getJSONArray("results")
            assertEquals(1, items.length())
            val repo = items.getJSONObject(0)
            assertEquals("JetBrains/kotlin", repo.getString("full_name"))
            assertEquals(50123, repo.getInt("stars"))
            val excerpt = repo.getString("readme_excerpt")
            assertTrue(excerpt.contains("Modern multiplatform language."))
            assertFalse(excerpt.contains("hidden badge comment"))
            assertTrue("README excerpt must be bounded <= 2000 chars", excerpt.length <= 2000)
        }
        assertEquals(1, pat1SearchCalls.get())
        assertEquals(1, pat2SearchCalls.get())
        assertEquals(1, readmeCalls.get())

        val pat1Entity = db.servicePoolDao().getByIdOnce("gh-pat-1")!!
        assertEquals(KeyStatus.RATE_LIMITED, pat1Entity.status)
        assertEquals(0, pat1Entity.rateLimitRemaining)
        assertFalse(pat1Entity.lastError.orEmpty().contains(patOne))

        val pat2Entity = db.servicePoolDao().getByIdOnce("gh-pat-2")!!
        assertEquals(KeyStatus.ACTIVE, pat2Entity.status)
        assertNotNull(pat2Entity.rateLimitResetAtMs)

        // 2. Repeated repo search hits 20m search cache without calling GitHub API again
        httpPost(
            "$baseUrl/v1/search/github",
            """{"query":"kotlin compiler","mode":"repositories","language":"kotlin","sort":"stars","max_results":3,"include_readme":true}""",
            token
        ).use { resp ->
            assertEquals(200, resp.code)
            assertTrue(JSONObject(resp.body!!.string()).getBoolean("cached"))
        }
        assertEquals(1, pat1SearchCalls.get())
        assertEquals(1, pat2SearchCalls.get())

        // 3. Code mode query skips exhausted PAT #1 and goes straight to healthy PAT #2
        httpPost(
            "$baseUrl/v1/search/github",
            """{"query":"LocalGatewayHttpServer","mode":"code","max_results":2}""",
            token
        ).use { resp ->
            assertEquals(200, resp.code)
            val json = JSONObject(resp.body!!.string())
            assertEquals("code", json.getString("mode"))
            val items = json.getJSONArray("results")
            assertEquals(1, items.length())
            assertEquals("LocalGatewayHttpServer.kt", items.getJSONObject(0).getString("name"))
            assertTrue(items.getJSONObject(0).getString("snippet").contains("GatewaySettingsState"))
        }
        assertEquals("Exhausted PAT #1 should have been skipped", 1, pat1SearchCalls.get())
        assertEquals(2, pat2SearchCalls.get())
    }

    // -------------------------------------------------------------------------
    // 5. Backup & Restore Roundtrip for ServicePoolKeyEntity + DDG Preference
    // -------------------------------------------------------------------------

    @Test
    fun backupRoundtrip_preservesServicePoolKeysEncryptedAndDuckDuckGoPreference() = runBlocking {
        val tavilySecret = "tvly-backup-secret-111"
        val ghPatSecret = "ghp_backupPatSecret222"

        db.servicePoolDao().upsert(
            ServicePoolKeyEntity(
                id = "pool-ws-1",
                serviceType = ServicePoolType.WEB_SEARCH,
                providerId = "tavily",
                label = "Backup Tavily",
                encryptedKey = CryptoManager.encrypt(tavilySecret),
                maskedPreview = "tvly-••••111",
                priority = 1
            )
        )
        db.servicePoolDao().upsert(
            ServicePoolKeyEntity(
                id = "pool-gh-1",
                serviceType = ServicePoolType.GITHUB_SEARCH,
                providerId = "github",
                label = "Backup GitHub PAT",
                encryptedKey = CryptoManager.encrypt(ghPatSecret),
                maskedPreview = "ghp_••••222",
                priority = 1
            )
        )
        app.preferences.setWebSearchDuckDuckGoFallbackEnabled(true)

        val backupManager = BackupManager(db, app.preferences)
        val passphrase = "ThreeServiceBackup!2026"
        val archive = backupManager.exportEncryptedBackup(passphrase)

        assertFalse(archive.contains(tavilySecret))
        assertFalse(archive.contains(ghPatSecret))

        // Simulate wipe + fresh Keystore key
        db.servicePoolDao().clearAll()
        app.preferences.setWebSearchDuckDuckGoFallbackEnabled(false)
        CryptoManager.resetTestKeyProvider()
        CryptoManager.setMasterKeyProviderForTest(CryptoManager.EphemeralTestMasterKeyProvider())

        val preview = backupManager.previewEncryptedBackup(archive, passphrase)
        assertTrue(preview.integrityVerified)
        assertEquals(2, preview.counts.servicePoolKeys)

        val restored = backupManager.restoreEncryptedBackupDetailed(archive, passphrase, RestoreConflictStrategy.OVERWRITE_EXISTING)
        assertTrue(restored.importedCount >= 2)
        assertTrue(restored.preferencesRestored)

        val restoredKeys = db.servicePoolDao().getAllOnce()
        assertEquals(2, restoredKeys.size)
        val restoredTavily = restoredKeys.first { it.id == "pool-ws-1" }
        val restoredGh = restoredKeys.first { it.id == "pool-gh-1" }
        assertEquals(tavilySecret, CryptoManager.decrypt(restoredTavily.encryptedKey))
        assertEquals(ghPatSecret, CryptoManager.decrypt(restoredGh.encryptedKey))
        assertTrue(app.preferences.settingsFlow.first().webSearchDuckDuckGoFallbackEnabled)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private suspend fun insertDevice(name: String, rawToken: String, scopesCsv: String) {
        db.deviceDao().upsert(
            DeviceEntity(
                name = name,
                deviceType = "ANDROID_CLIENT",
                deviceCode = "DEV-${name.take(4).uppercase()}",
                encryptedToken = CryptoManager.encrypt(rawToken),
                tokenPreview = "jhgw_••••${rawToken.takeLast(4)}",
                permissionsCsv = scopesCsv,
                connectionType = DeviceConnectionType.LOCAL_NETWORK
            )
        )
    }

    private suspend fun startLocalGateway(
        webSearchRouter: WebSearchRouterService = WebSearchRouterService(db.servicePoolDao(), app.preferences),
        githubSearchRouter: GitHubSearchRouterService = GitHubSearchRouterService(db.servicePoolDao())
    ): LocalGatewayHttpServer {
        app.preferences.updateGatewaySettings(
            name = "Three-Service Test Gateway",
            mode = GatewayMode.LOCAL,
            host = "127.0.0.1",
            port = 0,
            remoteUrl = "",
            authRequired = true,
            timeoutSec = 30,
            maxRequestKb = 128,
            streaming = false,
            rateLimitPerMin = 120,
            loggingLevel = LoggingLevel.METADATA_ONLY,
            allowLanExposure = false,
            remoteAuthTokenEnc = ""
        )
        val server = LocalGatewayHttpServer(
            db = db,
            router = SmartRouterEngine(db, ProviderHttpClient()),
            preferences = app.preferences,
            webSearchRouter = webSearchRouter,
            githubSearchRouter = githubSearchRouter
        )
        serversToStop.add(server)
        server.start(
            GatewaySettingsState(
                gatewayName = "Three-Service Test Gateway",
                gatewayMode = GatewayMode.LOCAL,
                gatewayHost = "127.0.0.1",
                gatewayPort = 0,
                gatewayAuthRequired = true,
                gatewayMaxRequestKb = 128,
                gatewayStreamingEnabled = false,
                gatewayRateLimitPerMin = 120
            )
        )
        assertEquals(GatewayRunStatus.LOCAL_RUNNING, server.runtimeState.value.status)
        return server
    }

    private fun startMockServer(
        port: Int,
        module: io.ktor.server.application.Application.() -> Unit
    ) {
        val srv = embeddedServer(
            CIO,
            configure = {
                connector {
                    host = "127.0.0.1"
                    this.port = port
                }
            },
            module = module
        )
        srv.start(wait = false)
        mockUpstreamsToStop.add(srv)
    }

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }

    private fun httpGet(url: String, token: String?) = httpClient.newCall(
        Request.Builder().url(url).apply {
            if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
        }.get().build()
    ).execute()

    private fun httpPost(url: String, jsonBody: String, token: String?) = httpClient.newCall(
        Request.Builder().url(url).apply {
            if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
        }.post(jsonBody.toRequestBody("application/json".toMediaType())).build()
    ).execute()
}
