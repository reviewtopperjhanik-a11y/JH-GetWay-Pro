package com.example

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.core.security.CryptoManager
import com.example.data.local.*
import com.example.gateway.OpenCodeModelCatalogResolver
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
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CooldownAndRecoveryStep2Test {

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

    private fun defaultSettings() = GatewaySettingsState(
        gatewayTimeoutSec = 15,
        maxRetries = 2,
        fallbackEnabled = true,
        localNetworkAccessAllowed = true,
        localRoutingPolicy = LocalRoutingPolicy.CLOUD_FIRST,
        autoAllowPaidModels = true
    )

    @Test
    fun paymentRequired402_appliesFifteenMinuteCooldownAndReAdmitsAfterExpiry() = runBlocking {
        val url = startUpstream {
            post("/v1/chat/completions") {
                call.respondText(
                    """{"error":{"message":"Insufficient credits"}}""",
                    ContentType.Application.Json,
                    HttpStatusCode.PaymentRequired
                )
            }
        }

        db.providerDao().upsert(
            ProviderEntity(
                id = "p_402",
                name = "QuotaProvider",
                apiFormat = ApiFormat.OPENAI,
                baseUrl = "$url/v1",
                defaultModel = "m-quota",
                priority = 1,
                status = ProviderStatus.CONNECTED,
                enabled = true
            )
        )
        db.apiKeyDao().upsert(
            ApiKeyEntity(
                id = "k_402",
                providerId = "p_402",
                label = "Quota Key",
                encryptedKey = CryptoManager.encrypt("sk-quota-1"),
                maskedPreview = "sk-••1",
                isDefault = true,
                status = KeyStatus.ACTIVE,
                enabled = true
            )
        )
        db.modelDao().upsert(
            ModelEntity(
                id = "p_402::m-quota",
                modelId = "m-quota",
                displayName = "Quota Model",
                providerId = "p_402",
                providerName = "QuotaProvider",
                supportsChat = true,
                enabled = true
            )
        )

        val router = SmartRouterEngine(db, ProviderHttpClient())
        val settings = defaultSettings()
        val res = router.executeRoutedChat(
            requestedModelOrAlias = "jh-getway/auto",
            messages = listOf(ChatMessagePayload("user", "hello")),
            settings = settings
        )
        assertFalse(res.execution.success)
        assertEquals(402, res.execution.statusCode)

        val provAfter = db.providerDao().getById("p_402")!!
        val keyAfter = db.apiKeyDao().getForProvider("p_402").single()
        val checkedAt = provAfter.lastCheckedAt!!
        val provCooldown = provAfter.cooldownUntilMs!!
        val keyCooldown = keyAfter.cooldownUntilMs!!

        assertEquals(ProviderStatus.ERROR, provAfter.status)
        assertTrue(
            "Expected ~15m (900_000ms) cooldown for 402, got ${provCooldown - checkedAt}ms",
            (provCooldown - checkedAt) in 890_000L..910_000L
        )
        assertTrue(
            "Expected ~15m cooldown on key for 402, got ${keyCooldown - checkedAt}ms",
            (keyCooldown - checkedAt) in 890_000L..910_000L
        )

        // At +90s (old 60s window already expired), provider must STILL be skipped under 15m cooldown
        val (stillCooling, _) = router.resolveCandidates(
            requestedModelOrAlias = "jh-getway/auto",
            settings = settings,
            mode = RoutingMode.AUTOMATIC,
            nowMs = checkedAt + 90_000L
        )
        assertTrue("402 provider should stay skipped at +90s", stillCooling.isEmpty())

        // Subsequent request during cooldown should surface dynamic ~14m/15m diagnostic message instead of "60s"
        val blockedDuringCooldown = router.executeRoutedChat(
            requestedModelOrAlias = "jh-getway/auto",
            messages = listOf(ChatMessagePayload("user", "retry early")),
            settings = settings
        )
        assertFalse(blockedDuringCooldown.execution.success)
        assertTrue(
            "Diagnostic should mention minute-scale cooldown: ${blockedDuringCooldown.execution.safeErrorMessage}",
            blockedDuringCooldown.execution.safeErrorMessage.orEmpty().contains("m cooldown")
        )

        // After 15m cooldown expires, provider is cleanly re-admitted
        val (recovered, _) = router.resolveCandidates(
            requestedModelOrAlias = "jh-getway/auto",
            settings = settings,
            mode = RoutingMode.AUTOMATIC,
            nowMs = provCooldown + 1_000L
        )
        assertEquals(1, recovered.size)
        assertEquals("p_402", recovered.first().id)
    }

    @Test
    fun rateLimit429_honorsRetryAfterSecondsAndHttpDateInNonStreamingAndStreaming() = runBlocking {
        val futureHttpDate = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }.format(Date(System.currentTimeMillis() + 180_000L))

        val url = startUpstream {
            post("/v1/chat/completions") {
                val auth = call.request.headers["Authorization"].orEmpty()
                if (auth == "Bearer sk-sec") {
                    call.response.header("Retry-After", "125")
                    call.respondText("""{"error":{"message":"Slow down"}}""", ContentType.Application.Json, HttpStatusCode.TooManyRequests)
                } else {
                    call.response.header("Retry-After", futureHttpDate)
                    call.respondText("""{"error":{"message":"Slow down stream"}}""", ContentType.Application.Json, HttpStatusCode.TooManyRequests)
                }
            }
        }

        db.providerDao().upsert(
            ProviderEntity(
                id = "p_sec", name = "SecProv", apiFormat = ApiFormat.OPENAI,
                baseUrl = "$url/v1", defaultModel = "m1", priority = 1,
                status = ProviderStatus.CONNECTED, enabled = true
            )
        )
        db.providerDao().upsert(
            ProviderEntity(
                id = "p_date", name = "DateProv", apiFormat = ApiFormat.OPENAI,
                baseUrl = "$url/v1", defaultModel = "m2", priority = 2,
                status = ProviderStatus.CONNECTED, enabled = true
            )
        )
        db.apiKeyDao().upsert(ApiKeyEntity(id = "k_sec", providerId = "p_sec", label = "Sec", encryptedKey = CryptoManager.encrypt("sk-sec"), maskedPreview = "sk-s", isDefault = true))
        db.apiKeyDao().upsert(ApiKeyEntity(id = "k_date", providerId = "p_date", label = "Date", encryptedKey = CryptoManager.encrypt("sk-date"), maskedPreview = "sk-d", isDefault = true))
        db.modelDao().upsertAll(
            listOf(
                ModelEntity(id = "p_sec::m1", modelId = "m1", displayName = "M1", providerId = "p_sec", providerName = "SecProv", supportsChat = true, enabled = true),
                ModelEntity(id = "p_date::m2", modelId = "m2", displayName = "M2", providerId = "p_date", providerName = "DateProv", supportsChat = true, enabled = true)
            )
        )

        val router = SmartRouterEngine(db, ProviderHttpClient())
        val settings = defaultSettings()

        // Non-streaming 429 with integer Retry-After: 125
        val res1 = router.executeRoutedChat(
            requestedModelOrAlias = "m1",
            messages = listOf(ChatMessagePayload("user", "hi")),
            settings = settings,
            explicitProviderId = "p_sec"
        )
        assertFalse(res1.execution.success)
        assertEquals(429, res1.execution.statusCode)
        assertEquals(125, res1.execution.retryAfterSec)
        val pSecAfter = db.providerDao().getById("p_sec")!!
        val kSecAfter = db.apiKeyDao().getForProvider("p_sec").single()
        assertEquals(ProviderStatus.RATE_LIMITED, pSecAfter.status)
        assertEquals(KeyStatus.RATE_LIMITED, kSecAfter.status)
        val deltaSec = (pSecAfter.cooldownUntilMs!! - pSecAfter.lastCheckedAt!!) / 1000L
        assertTrue("Expected ~125s cooldown from Retry-After header, got ${deltaSec}s", deltaSec in 120L..130L)
        assertEquals(pSecAfter.cooldownUntilMs, kSecAfter.cooldownUntilMs)

        // Streaming 429 with HTTP-date Retry-After (~180s)
        val streamEvents = router.streamRoutedChat(
            requestedModelOrAlias = "m2",
            messages = listOf(ChatMessagePayload("user", "hi")),
            settings = settings,
            explicitProviderId = "p_date"
        ).toList()
        val failedEvent = streamEvents.mapNotNull { it.first as? StreamEvent.Failed }.singleOrNull()
        assertNotNull(failedEvent)
        assertEquals(429, failedEvent!!.error.statusCode)
        assertNotNull(failedEvent.error.retryAfterSec)
        assertTrue(
            "Expected HTTP-date Retry-After ~180s, got ${failedEvent.error.retryAfterSec}",
            failedEvent.error.retryAfterSec!! in 160..190
        )
        val pDateAfter = db.providerDao().getById("p_date")!!
        val dateDeltaSec = (pDateAfter.cooldownUntilMs!! - pDateAfter.lastCheckedAt!!) / 1000L
        assertTrue("Expected ~180s cooldown from HTTP-date Retry-After, got ${dateDeltaSec}s", dateDeltaSec in 160L..190L)
    }

    @Test
    fun localAiServer_cooldownPersistedAndAutoRecoversAfterWindowExpires() = runBlocking {
        val shouldFail = AtomicBoolean(true)
        val localUrl = startUpstream {
            post("/v1/chat/completions") {
                if (shouldFail.get()) {
                    call.respondText("""{"error":{"message":"Local OOM"}}""", ContentType.Application.Json, HttpStatusCode.InternalServerError)
                } else {
                    call.respondText("""{"id":"ok","model":"qwen-local","choices":[{"message":{"role":"assistant","content":"Local recovered"},"finish_reason":"stop"}]}""", ContentType.Application.Json)
                }
            }
        }

        db.localAiServerDao().upsert(
            LocalAiServerEntity(
                id = "srv_1",
                name = "LocalOllama",
                engineType = LocalEngineType.OPENAI_COMPATIBLE,
                serverUrl = "$localUrl/v1",
                defaultModel = "qwen-local",
                status = ProviderStatus.CONNECTED,
                enabled = true
            )
        )
        db.modelDao().upsert(
            ModelEntity(
                id = "local:srv_1::qwen-local",
                modelId = "qwen-local",
                displayName = "Qwen Local",
                providerId = "local:srv_1",
                providerName = "LocalOllama",
                isLocal = true,
                accessTier = AccessTier.LOCAL,
                supportsChat = true,
                enabled = true
            )
        )

        val router = SmartRouterEngine(db, ProviderHttpClient())
        val settings = defaultSettings().copy(localRoutingPolicy = LocalRoutingPolicy.LOCAL_FIRST)

        // 1. Trigger 500 failure -> persists lastCheckedAt & cooldownUntilMs on LocalAiServerEntity
        val failRes = router.executeRoutedChat(
            requestedModelOrAlias = "jh-getway/auto",
            messages = listOf(ChatMessagePayload("user", "hi")),
            settings = settings
        )
        assertFalse(failRes.execution.success)
        val srvFailed = db.localAiServerDao().getAllOnce().single()
        assertEquals(ProviderStatus.ERROR, srvFailed.status)
        assertNotNull(srvFailed.lastCheckedAt)
        assertNotNull(srvFailed.cooldownUntilMs)
        val cooldownDelta = srvFailed.cooldownUntilMs!! - srvFailed.lastCheckedAt!!
        assertTrue("Expected ~60s local server cooldown, got ${cooldownDelta}ms", cooldownDelta in 55_000L..65_000L)

        // 2. While inside cooldown, both SmartRouterEngine and OpenCodeModelCatalogResolver report cooling down
        val duringMs = srvFailed.lastCheckedAt!! + 10_000L
        val (duringCands, _) = router.resolveCandidates("jh-getway/auto", settings, RoutingMode.AUTOMATIC, nowMs = duringMs)
        assertTrue("Cooling local server must be skipped inside cooldown window", duringCands.isEmpty())

        val (catalogDuring, _) = OpenCodeModelCatalogResolver.resolveEligibleRecords(
            providers = emptyList(),
            localServers = listOf(srvFailed),
            allModels = db.modelDao().getAllOnce(),
            allKeys = emptyList(),
            settings = settings,
            nowMs = duringMs
        )
        assertTrue(OpenCodeModelCatalogResolver.isRecordCoolingDown(catalogDuring.single(), duringMs))

        // 3. After cooldown expires, both SmartRouterEngine & OpenCodeModelCatalogResolver auto-recover it
        val afterMs = srvFailed.cooldownUntilMs!! + 1_000L
        val (afterCands, _) = router.resolveCandidates("jh-getway/auto", settings, RoutingMode.AUTOMATIC, nowMs = afterMs)
        assertEquals(1, afterCands.size)
        assertEquals("local:srv_1", afterCands.first().id)
        assertFalse(OpenCodeModelCatalogResolver.isRecordCoolingDown(catalogDuring.single(), afterMs))

        // 4. Simulate expired cooldown in DB and verify next healthy call clears cooldown and restores CONNECTED
        db.localAiServerDao().upsert(
            srvFailed.copy(
                lastCheckedAt = System.currentTimeMillis() - 120_000L,
                cooldownUntilMs = System.currentTimeMillis() - 60_000L
            )
        )
        shouldFail.set(false)
        val recoveredRes = router.executeRoutedChat(
            requestedModelOrAlias = "jh-getway/auto",
            messages = listOf(ChatMessagePayload("user", "hi again")),
            settings = settings
        )
        assertTrue("Expected auto-recovered local server call to succeed", recoveredRes.execution.success)
        assertEquals("Local recovered", recoveredRes.execution.content)
        val srvHealthy = db.localAiServerDao().getAllOnce().single()
        assertEquals(ProviderStatus.CONNECTED, srvHealthy.status)
        assertNull(srvHealthy.cooldownUntilMs)
    }

    @Test
    fun roomMigration6To7_preservesDataAndAddsNullableCooldownColumns() {
        val candidates = listOf(
            java.io.File("schemas/com.example.data.local.JhGetWayDatabase/7.json"),
            java.io.File("app/schemas/com.example.data.local.JhGetWayDatabase/7.json")
        )
        val schemaFile = candidates.firstOrNull { it.exists() }
        assertNotNull("Expected Room exported schema snapshot 7.json to exist", schemaFile)
        val dbObj = org.json.JSONObject(schemaFile!!.readText()).getJSONObject("database")
        assertEquals(7, dbObj.getInt("version"))

        val dbName = "step2_migration_6_7.db"
        app.deleteDatabase(dbName)

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(app)
                .name(dbName)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(6) {
                    override fun onCreate(s: SupportSQLiteDatabase) {
                        createV6SchemaAndSeed(s)
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

            runBlocking {
                val prov = migratedDb.providerDao().getById("p-v6")
                assertNotNull(prov)
                assertEquals("Legacy V6 Provider", prov!!.name)
                assertNull(prov.cooldownUntilMs)

                val keys = migratedDb.apiKeyDao().getForProvider("p-v6")
                assertEquals(1, keys.size)
                assertEquals("sk-••v6", keys.single().maskedPreview)
                assertNull(keys.single().cooldownUntilMs)

                val locals = migratedDb.localAiServerDao().getAllOnce()
                assertEquals(1, locals.size)
                assertEquals("Legacy V6 Ollama", locals.single().name)
                assertNull(locals.single().lastCheckedAt)
                assertNull(locals.single().cooldownUntilMs)
            }
        } finally {
            migratedDb.close()
            app.deleteDatabase(dbName)
        }
    }

    private fun createV6SchemaAndSeed(s: SupportSQLiteDatabase) {
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
        JhGetWayDatabase.MIGRATION_3_4.migrate(s)
        JhGetWayDatabase.MIGRATION_4_5.migrate(s)
        JhGetWayDatabase.MIGRATION_5_6.migrate(s)

        s.execSQL("INSERT INTO providers (id, name, type, presetId, baseUrl, apiFormat, defaultModel, priority, enabled, customHeadersJson, notes, status, lastLatencyMs, lastError, lastCheckedAt, createdAt) VALUES ('p-v6', 'Legacy V6 Provider', 'BUILT_IN', 'OPENAI', 'https://api.openai.com/v1', 'OPENAI', 'gpt-4o', 1, 1, '', '', 'CONNECTED', 120, NULL, 1000, 1000)")
        s.execSQL("INSERT INTO api_keys (id, providerId, label, encryptedKey, maskedPreview, priority, isDefault, enabled, status, selectionPolicy, usageCount, lastUsedAt, lastTestedAt, createdAt) VALUES ('k-v6', 'p-v6', 'Primary', 'enc-v6', 'sk-••v6', 1, 1, 1, 'ACTIVE', 'PRIORITY', 3, 1000, 1000, 1000)")
        s.execSQL("INSERT INTO local_ai_servers (id, name, engineType, serverUrl, encryptedAuthToken, hasAuth, timeoutSec, defaultModel, isPrivateNetwork, status, lastLatencyMs, lastError, enabled, createdAt) VALUES ('l-v6', 'Legacy V6 Ollama', 'OLLAMA', 'http://127.0.0.1:11434', '', 0, 60, 'llama3.2', 1, 'CONNECTED', 50, NULL, 1, 1000)")
    }
}
