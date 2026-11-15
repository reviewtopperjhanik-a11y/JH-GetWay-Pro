package com.example

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.core.i18n.BengaliStrings
import com.example.core.i18n.EnglishStrings
import com.example.core.i18n.LocalStrings
import com.example.core.security.CryptoManager
import com.example.data.local.*
import com.example.gateway.SmartRouterEngine
import com.example.network.ChatMessagePayload
import com.example.network.ChatRequestParams
import com.example.network.ProviderHttpClient
import com.example.network.adapters.*
import com.example.ui.GatewayViewModel
import com.example.ui.screens.ModelsScreen
import com.example.ui.screens.PlaygroundScreen
import com.example.ui.theme.JhGetWayTheme
import com.example.ui.theme.ThemeMode
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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlaygroundUpgradeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var app: JhGetWayApplication
    private lateinit var inMemoryDb: JhGetWayDatabase
    private val servers = mutableListOf<EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>>()

    @Before
    fun setUp() {
        CryptoManager.resetTestKeyProvider()
        app = ApplicationProvider.getApplicationContext()
        PlaygroundAttachmentManager.cleanupAllOwnedAttachments(app)
        runBlocking(Dispatchers.IO) {
            app.preferences.clearAll()
            app.database.clearAllTables()
        }
        inMemoryDb = Room.inMemoryDatabaseBuilder(app, JhGetWayDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.stop(100, 300) } }
        servers.clear()
        inMemoryDb.close()
        PlaygroundAttachmentManager.cleanupAllOwnedAttachments(app)
        runBlocking(Dispatchers.IO) {
            app.gatewayServer.stop()
            app.database.clearAllTables()
            app.preferences.clearAll()
        }
        CryptoManager.resetTestKeyProvider()
    }

    private fun startMockServer(configure: io.ktor.server.routing.Routing.() -> Unit): String {
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
        servers += srv
        val port = runBlocking { srv.engine.resolvedConnectors().first().port }
        java.net.Socket("127.0.0.1", port).use { }
        return "http://127.0.0.1:$port"
    }

    private suspend fun insertCloudProvider(
        db: JhGetWayDatabase,
        id: String,
        name: String,
        baseUrl: String = "https://example.invalid/v1",
        apiFormat: ApiFormat = ApiFormat.OPENAI,
        priority: Int = 1,
        status: ProviderStatus = ProviderStatus.CONNECTED,
        lastCheckedAt: Long? = null,
        lastLatencyMs: Long? = 150L,
        withKey: Boolean = true
    ) {
        db.providerDao().upsert(
            ProviderEntity(
                id = id,
                name = name,
                type = ProviderType.CUSTOM,
                baseUrl = baseUrl,
                apiFormat = apiFormat,
                defaultModel = "",
                priority = priority,
                enabled = true,
                status = status,
                lastLatencyMs = lastLatencyMs,
                lastCheckedAt = lastCheckedAt
            )
        )
        if (withKey) {
            db.apiKeyDao().upsert(
                ApiKeyEntity(
                    providerId = id,
                    label = "$name Key",
                    encryptedKey = CryptoManager.encrypt("sk-test-$id"),
                    maskedPreview = "sk-t••••$id",
                    enabled = true,
                    isDefault = true
                )
            )
        }
    }

    // =========================================================================
    // Phase 2: Auto — Free Models Router & Manual Selection Pinning
    // =========================================================================

    @Test
    fun autoFreeRouter_selectsOnlyVerifiedFreeModelsAndExcludesPaidUnknownCooldownMissingKey() = runBlocking {
        val now = 1_770_000_000_000L

        insertCloudProvider(inMemoryDb, id = "p-or", name = "OpenRouter", priority = 2, lastLatencyMs = 180)
        insertCloudProvider(inMemoryDb, id = "p-gem", name = "Gemini", apiFormat = ApiFormat.GEMINI, priority = 1, lastLatencyMs = 120)
        insertCloudProvider(
            inMemoryDb,
            id = "p-cool",
            name = "Cooling Provider",
            priority = 0,
            status = ProviderStatus.RATE_LIMITED,
            lastCheckedAt = now - 15_000L
        )
        insertCloudProvider(inMemoryDb, id = "p-nokey", name = "Keyless Free", priority = 0, withKey = false)

        inMemoryDb.modelDao().upsertAll(
            listOf(
                ModelEntity(
                    id = "p-or::deepseek/deepseek-chat:free",
                    modelId = "deepseek/deepseek-chat:free",
                    displayName = "DeepSeek Chat Free",
                    providerId = "p-or",
                    providerName = "OpenRouter",
                    accessTier = AccessTier.FREE_TIER,
                    freeTierVerified = true,
                    freeTierSource = "Provider Catalog (:free)",
                    enabled = true,
                    supportsChat = true
                ),
                ModelEntity(
                    id = "p-or::openai/gpt-4o",
                    modelId = "openai/gpt-4o",
                    displayName = "GPT-4o Paid",
                    providerId = "p-or",
                    providerName = "OpenRouter",
                    inputPricePer1M = 2.5,
                    outputPricePer1M = 10.0,
                    accessTier = AccessTier.PAID,
                    freeTierVerified = false,
                    enabled = true,
                    supportsChat = true
                ),
                ModelEntity(
                    id = "p-or::unknown/mystery",
                    modelId = "unknown/mystery",
                    displayName = "Mystery Unknown",
                    providerId = "p-or",
                    providerName = "OpenRouter",
                    accessTier = AccessTier.UNKNOWN,
                    freeTierVerified = false,
                    enabled = true,
                    supportsChat = true
                ),
                ModelEntity(
                    id = "p-or::disabled/free:free",
                    modelId = "disabled/free:free",
                    displayName = "Disabled Free",
                    providerId = "p-or",
                    providerName = "OpenRouter",
                    accessTier = AccessTier.FREE_TIER,
                    freeTierVerified = true,
                    enabled = false,
                    supportsChat = true
                ),
                ModelEntity(
                    id = "p-gem::gemini-2.5-flash",
                    modelId = "gemini-2.5-flash",
                    displayName = "Gemini 2.5 Flash",
                    providerId = "p-gem",
                    providerName = "Gemini",
                    accessTier = AccessTier.FREE_TIER,
                    freeTierVerified = true,
                    freeTierSource = "User Confirmed",
                    enabled = true,
                    supportsChat = true,
                    supportsVision = true
                ),
                ModelEntity(
                    id = "p-cool::cool/model:free",
                    modelId = "cool/model:free",
                    displayName = "Cooling Free",
                    providerId = "p-cool",
                    providerName = "Cooling Provider",
                    accessTier = AccessTier.FREE_TIER,
                    freeTierVerified = true,
                    enabled = true,
                    supportsChat = true
                ),
                ModelEntity(
                    id = "p-nokey::nokey/model:free",
                    modelId = "nokey/model:free",
                    displayName = "NoKey Free",
                    providerId = "p-nokey",
                    providerName = "Keyless Free",
                    accessTier = AccessTier.FREE_TIER,
                    freeTierVerified = true,
                    enabled = true,
                    supportsChat = true
                )
            )
        )

        val engine = SmartRouterEngine(inMemoryDb, ProviderHttpClient())
        val settings = GatewaySettingsState(autoFreeIncludeLocal = false)

        val (candidates, reason) = engine.resolveCandidates(
            requestedModelOrAlias = "",
            settings = settings,
            mode = RoutingMode.AUTO_FREE,
            explicitProviderId = null,
            nowMs = now
        )

        assertTrue(reason.contains("Free", ignoreCase = true))
        assertEquals(2, candidates.size)
        assertEquals(2, engine.countEligibleAutoFreeModels(settings, nowMs = now))
        assertEquals("gemini-2.5-flash", candidates[0].modelId) // higher priority & lower latency
        assertEquals("deepseek/deepseek-chat:free", candidates[1].modelId)
        assertTrue(candidates.all { it.accessTier == AccessTier.FREE_TIER && it.freeTierVerified })

        // With image vision required, non-vision DeepSeek is filtered out and only Gemini remains
        val visionCount = engine.countEligibleAutoFreeModels(settings, requireVision = true, nowMs = now)
        assertEquals(1, visionCount)
    }

    @Test
    fun autoFreeRouter_localModelsExcludedByDefaultAndIncludedOnlyWhenOptedIn() = runBlocking {
        inMemoryDb.localAiServerDao().upsert(
            LocalAiServerEntity(
                id = "loc-1",
                name = "Ollama Phone",
                engineType = LocalEngineType.OLLAMA,
                serverUrl = "http://127.0.0.1:11434",
                defaultModel = "llama3.2",
                enabled = true,
                status = ProviderStatus.CONNECTED
            )
        )

        val engine = SmartRouterEngine(inMemoryDb, ProviderHttpClient())

        val defaultOffSettings = GatewaySettingsState(autoFreeIncludeLocal = false)
        assertEquals(0, engine.countEligibleAutoFreeModels(defaultOffSettings))
        val failedResult = engine.executeRoutedChat(
            requestedModelOrAlias = "",
            messages = listOf(ChatMessagePayload("user", "hi")),
            settings = defaultOffSettings,
            routingModeOverride = RoutingMode.AUTO_FREE
        )
        assertFalse(failedResult.execution.success)
        assertTrue(failedResult.execution.safeErrorMessage.orEmpty().contains("free", ignoreCase = true))

        val optInSettings = GatewaySettingsState(autoFreeIncludeLocal = true)
        assertEquals(1, engine.countEligibleAutoFreeModels(optInSettings))
        val (candidates, _) = engine.resolveCandidates(
            requestedModelOrAlias = "",
            settings = optInSettings,
            mode = RoutingMode.AUTO_FREE
        )
        assertEquals(1, candidates.size)
        assertTrue(candidates.first().isLocal)
        assertEquals(AccessTier.LOCAL, candidates.first().accessTier)
    }

    @Test
    fun autoFreeRouter_sequentialPreStreamRetryAcrossFreePoolNeverTouchesPaidOrUnknown() = runBlocking {
        val paidCalls = AtomicInteger(0)
        val baseUrl = startMockServer {
            post("/free1/v1/chat/completions") {
                call.respondText(
                    """{"error":{"message":"Rate limit exceeded"}}""",
                    ContentType.Application.Json,
                    HttpStatusCode.TooManyRequests
                )
            }
            post("/free2/v1/chat/completions") {
                call.respondText(
                    """{"id":"c2","model":"qwen/qwen-free:free","choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Free fallback succeeded"}}],"usage":{"prompt_tokens":8,"completion_tokens":4}}""",
                    ContentType.Application.Json
                )
            }
            post("/paid/v1/chat/completions") {
                paidCalls.incrementAndGet()
                call.respondText(
                    """{"id":"cp","model":"gpt-4o","choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Should never run"}}]}""",
                    ContentType.Application.Json
                )
            }
        }

        insertCloudProvider(inMemoryDb, id = "p-free1", name = "Free One", baseUrl = "$baseUrl/free1/v1", priority = 1, lastLatencyMs = 50)
        insertCloudProvider(inMemoryDb, id = "p-free2", name = "Free Two", baseUrl = "$baseUrl/free2/v1", priority = 2, lastLatencyMs = 60)
        insertCloudProvider(inMemoryDb, id = "p-paid", name = "Paid Prime", baseUrl = "$baseUrl/paid/v1", priority = 0, lastLatencyMs = 10)

        inMemoryDb.modelDao().upsertAll(
            listOf(
                ModelEntity(
                    id = "p-free1::llama-3.1-free:free",
                    modelId = "llama-3.1-free:free",
                    displayName = "Llama Free",
                    providerId = "p-free1",
                    providerName = "Free One",
                    accessTier = AccessTier.FREE_TIER,
                    freeTierVerified = true,
                    enabled = true,
                    supportsChat = true
                ),
                ModelEntity(
                    id = "p-free2::qwen/qwen-free:free",
                    modelId = "qwen/qwen-free:free",
                    displayName = "Qwen Free",
                    providerId = "p-free2",
                    providerName = "Free Two",
                    accessTier = AccessTier.FREE_TIER,
                    freeTierVerified = true,
                    enabled = true,
                    supportsChat = true
                ),
                ModelEntity(
                    id = "p-paid::gpt-4o",
                    modelId = "gpt-4o",
                    displayName = "GPT-4o",
                    providerId = "p-paid",
                    providerName = "Paid Prime",
                    accessTier = AccessTier.PAID,
                    freeTierVerified = false,
                    enabled = true,
                    supportsChat = true
                )
            )
        )

        val engine = SmartRouterEngine(inMemoryDb, ProviderHttpClient())
        val result = engine.executeRoutedChat(
            requestedModelOrAlias = "",
            messages = listOf(ChatMessagePayload("user", "Hello")),
            settings = GatewaySettingsState(localNetworkAccessAllowed = true, fallbackEnabled = true, maxRetries = 2),
            routingModeOverride = RoutingMode.AUTO_FREE
        )

        assertTrue(result.execution.success)
        assertEquals("Free fallback succeeded", result.execution.content)
        assertTrue(result.decision.fallbackUsed)
        assertEquals(2, result.decision.attemptCount)
        assertEquals("Free Two", result.decision.selectedProvider)
        assertEquals(0, paidCalls.get())
    }

    @Test
    fun manualSelection_pinsExactProviderWithoutSilentSwitchUnlessFallbackEnabled() = runBlocking {
        val backupCalls = AtomicInteger(0)
        val baseUrl = startMockServer {
            post("/fail/v1/chat/completions") {
                call.respondText(
                    """{"error":{"message":"Upstream 500"}}""",
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError
                )
            }
            post("/backup/v1/chat/completions") {
                backupCalls.incrementAndGet()
                call.respondText(
                    """{"id":"cb","model":"shared-model","choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Backup replied"}}]}""",
                    ContentType.Application.Json
                )
            }
        }

        insertCloudProvider(inMemoryDb, id = "p-fail", name = "Primary Fail", baseUrl = "$baseUrl/fail/v1", priority = 1)
        insertCloudProvider(inMemoryDb, id = "p-backup", name = "Backup Provider", baseUrl = "$baseUrl/backup/v1", priority = 2)
        // Add a second API key on p-fail to test manual fallback toggle cleanly as well
        inMemoryDb.apiKeyDao().upsert(
            ApiKeyEntity(
                providerId = "p-fail",
                label = "Secondary Key",
                encryptedKey = CryptoManager.encrypt("sk-second-key"),
                maskedPreview = "sk-s••••key",
                priority = 2,
                enabled = true,
                isDefault = false
            )
        )
        inMemoryDb.modelDao().upsertAll(
            listOf(
                ModelEntity(
                    id = "p-fail::shared-model",
                    modelId = "shared-model",
                    displayName = "Shared Model",
                    providerId = "p-fail",
                    providerName = "Primary Fail",
                    accessTier = AccessTier.PAID,
                    enabled = true,
                    supportsChat = true
                ),
                ModelEntity(
                    id = "p-backup::shared-model",
                    modelId = "shared-model",
                    displayName = "Shared Model",
                    providerId = "p-backup",
                    providerName = "Backup Provider",
                    accessTier = AccessTier.PAID,
                    enabled = true,
                    supportsChat = true
                )
            )
        )

        val engine = SmartRouterEngine(inMemoryDb, ProviderHttpClient())

        // Default manualModelFallbackEnabled = false: exact single attempt, never switches provider or retries backup
        val pinnedFail = engine.executeRoutedChat(
            requestedModelOrAlias = "shared-model",
            messages = listOf(ChatMessagePayload("user", "ping")),
            settings = GatewaySettingsState(
                localNetworkAccessAllowed = true,
                fallbackEnabled = true,
                manualModelFallbackEnabled = false,
                maxRetries = 2
            ),
            routingModeOverride = RoutingMode.MANUAL,
            explicitProviderId = "p-fail"
        )
        assertFalse(pinnedFail.execution.success)
        assertEquals(1, pinnedFail.decision.attemptCount)
        assertEquals(0, backupCalls.get())

        // When manualModelFallbackEnabled = true on p-fail (multiple keys), retry runs within p-fail
        val pinnedWithRetry = engine.executeRoutedChat(
            requestedModelOrAlias = "shared-model",
            messages = listOf(ChatMessagePayload("user", "ping")),
            settings = GatewaySettingsState(
                localNetworkAccessAllowed = true,
                fallbackEnabled = true,
                manualModelFallbackEnabled = true,
                maxRetries = 1
            ),
            routingModeOverride = RoutingMode.MANUAL,
            explicitProviderId = "p-fail"
        )
        assertFalse(pinnedWithRetry.execution.success)
        assertEquals(2, pinnedWithRetry.decision.attemptCount)
        assertEquals(0, backupCalls.get())
    }

    // =========================================================================
    // Room Schema v5 Export & Non-Destructive Migration (4 -> 5)
    // =========================================================================

    @Test
    fun roomSchemaV5_snapshotExistsAndMigration4To5PreservesModelsAndMessages() {
        val candidates = listOf(
            File("schemas/com.example.data.local.JhGetWayDatabase/5.json"),
            File("app/schemas/com.example.data.local.JhGetWayDatabase/5.json")
        )
        val schemaFile = candidates.firstOrNull { it.exists() }
        assertNotNull("Expected Room exported schema snapshot 5.json to exist", schemaFile)
        val dbObj = JSONObject(schemaFile!!.readText()).getJSONObject("database")
        assertEquals(5, dbObj.getInt("version"))

        val dbName = "playground_upgrade_migration_4_5.db"
        app.deleteDatabase(dbName)

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(app)
                .name(dbName)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(s: SupportSQLiteDatabase) {
                        createV4SchemaAndSeed(s)
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
            assertEquals(5, migratedDb.openHelper.writableDatabase.version)
            runBlocking {
                val models = migratedDb.modelDao().getAllOnce()
                assertEquals(2, models.size)
                val cloudModel = models.first { it.id == "p-cloud::gpt-4o" }
                assertEquals(AccessTier.UNKNOWN, cloudModel.accessTier)
                assertFalse(cloudModel.freeTierVerified)
                assertTrue(cloudModel.supportsChat)

                val localModel = models.first { it.id == "local:s1::llama3.2" }
                assertEquals(AccessTier.LOCAL, localModel.accessTier)
                assertTrue(localModel.isLocal)

                val msgs = migratedDb.playgroundDao().getMessagesOnce("sess-v4")
                assertEquals(1, msgs.size)
                assertEquals("Preserved message", msgs.first().content)
                assertEquals("[]", msgs.first().attachmentsMetadataJson)
            }
        } finally {
            migratedDb.close()
            app.deleteDatabase(dbName)
        }
    }

    private fun createV4SchemaAndSeed(s: SupportSQLiteDatabase) {
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

        s.execSQL("INSERT INTO models (id, modelId, displayName, providerId, providerName, modelType, capabilitiesCsv, contextWindow, supportsTools, supportsStreaming, isLocal, inputPricePer1M, outputPricePer1M, pricingUpdatedAt, enabled, isDefault, alias, createdAt) VALUES ('p-cloud::gpt-4o', 'gpt-4o', 'GPT-4o', 'p-cloud', 'OpenAI', 'GENERAL_CHAT', 'CHAT,TOOLS', 128000, 1, 1, 0, NULL, NULL, NULL, 1, 0, '', 1000)")
        s.execSQL("INSERT INTO models (id, modelId, displayName, providerId, providerName, modelType, capabilitiesCsv, contextWindow, supportsTools, supportsStreaming, isLocal, inputPricePer1M, outputPricePer1M, pricingUpdatedAt, enabled, isDefault, alias, createdAt) VALUES ('local:s1::llama3.2', 'llama3.2', 'Llama 3.2', 'local:s1', 'Ollama', 'GENERAL_CHAT', 'CHAT,LOCAL', 8192, 1, 1, 1, NULL, NULL, NULL, 1, 0, '', 1000)")
        s.execSQL("INSERT INTO playground_sessions (id, title, modelId, providerId, systemPrompt, createdAt, updatedAt) VALUES ('sess-v4', 'V4 Session', 'gpt-4o', 'p-cloud', '', 1000, 1000)")
        s.execSQL("INSERT INTO playground_messages (id, sessionId, role, content, modelId, providerName, latencyMs, inputTokens, outputTokens, estimatedCostUsd, isError, timestamp) VALUES ('msg-v4', 'sess-v4', 'user', 'Preserved message', '', '', NULL, NULL, NULL, NULL, 0, 1000)")
    }

    // =========================================================================
    // Phase 3: Attachment Manager Allowlist, Limits, Metadata & Cleanup
    // =========================================================================

    @Test
    fun attachmentManager_validatesAllowlistLimitsAndCleansOwnedFiles() {
        assertEquals("image/jpeg", PlaygroundAttachmentManager.normalizeMimeType(null, "photo.jpg"))
        assertEquals("image/png", PlaygroundAttachmentManager.normalizeMimeType("image/png", "pic.png"))
        assertEquals("image/webp", PlaygroundAttachmentManager.normalizeMimeType(null, "shot.webp"))
        assertEquals("application/pdf", PlaygroundAttachmentManager.normalizeMimeType("application/pdf", "doc.pdf"))
        assertEquals("text/markdown", PlaygroundAttachmentManager.normalizeMimeType(null, "notes.md"))
        assertEquals("text/csv", PlaygroundAttachmentManager.normalizeMimeType(null, "table.csv"))
        assertEquals("application/json", PlaygroundAttachmentManager.normalizeMimeType(null, "config.json"))
        assertNull(PlaygroundAttachmentManager.normalizeMimeType("application/x-msdownload", "bad.exe"))
        assertNull(PlaygroundAttachmentManager.normalizeMimeType("application/zip", "archive.zip"))

        val tmpDir = File(app.cacheDir, "unit_test_picks").apply { mkdirs() }
        try {
            val txtFile = File(tmpDir, "notes.txt").apply { writeText("Hello from JH GetWay") }
            val pickedTxt = PlaygroundAttachmentManager.importPickedUri(
                context = app,
                uri = Uri.fromFile(txtFile),
                saveHistory = true,
                existingCount = 0,
                existingTotalBytes = 0L
            )
            assertTrue(pickedTxt is AttachmentImportResult.Success)
            val att = (pickedTxt as AttachmentImportResult.Success).attachment
            assertEquals("text/plain", att.mimeType)
            assertFalse(att.isEphemeral)
            assertTrue(File(att.localFilePath).exists())

            val jsonMeta = PlaygroundAttachmentManager.toMetadataJson(listOf(att))
            val parsedMeta = PlaygroundAttachmentManager.parseMetadataJson(jsonMeta)
            assertEquals(1, parsedMeta.size)
            assertEquals("notes.txt", parsedMeta.first().displayName)

            val parts = PlaygroundAttachmentManager.buildContentParts("Analyze", listOf(att))
            assertEquals(2, parts.size)
            assertTrue(parts[0] is ContentPart.Text)
            assertTrue(parts[1] is ContentPart.FileAttachment)
            assertEquals("Hello from JH GetWay", (parts[1] as ContentPart.FileAttachment).textContent)

            // Exceed per-file 5 MB limit
            val bigFile = File(tmpDir, "oversize.txt").apply {
                writeBytes(ByteArray((5 * 1024 * 1024) + 64) { 'A'.code.toByte() })
            }
            val bigResult = PlaygroundAttachmentManager.importPickedUri(
                context = app,
                uri = Uri.fromFile(bigFile),
                saveHistory = false,
                existingCount = 0,
                existingTotalBytes = 0L
            )
            assertTrue(bigResult is AttachmentImportResult.Error)
            assertEquals(AttachmentImportError.FILE_TOO_LARGE, (bigResult as AttachmentImportResult.Error).reason)

            // Exceed 5 attachments count limit
            val countLimitResult = PlaygroundAttachmentManager.importPickedUri(
                context = app,
                uri = Uri.fromFile(txtFile),
                saveHistory = true,
                existingCount = PlaygroundAttachmentManager.MAX_ATTACHMENTS,
                existingTotalBytes = 100L
            )
            assertTrue(countLimitResult is AttachmentImportResult.Error)
            assertEquals(AttachmentImportError.LIMIT_EXCEEDED, (countLimitResult as AttachmentImportResult.Error).reason)

            // Cleanup owned attachment files
            PlaygroundAttachmentManager.deleteAttachmentsFromMetadata(app, listOf(jsonMeta))
            assertFalse(File(att.localFilePath).exists())
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    // =========================================================================
    // Phase 4: Multimodal Serialization & Explicit Capability Rejections
    // =========================================================================

    @Test
    fun multimodalAdapters_serializeOrRejectExplicitlyWithoutSilentDrop() {
        val imgPart = ContentPart.ImageUrl("data:image/png;base64,iVBORw0KGgo=")
        val pdfPart = ContentPart.FileAttachment(
            fileName = "spec.pdf",
            mimeType = "application/pdf",
            base64Data = "JVBERi0xLjQ=",
            sizeBytes = 128
        )
        val txtPart = ContentPart.FileAttachment(
            fileName = "readme.md",
            mimeType = "text/markdown",
            textContent = "# Header",
            sizeBytes = 8
        )

        // 1. OpenAI: :free parsing, serializes image_url + text file, rejects PDF explicitly
        val openAi = OpenAiProviderAdapter()
        val parsedModels = openAi.parseModelsResponse(
            bodyStr = """
                {
                  "data": [
                    {"id": "qwen/qwen-2.5:free", "pricing": {"prompt": "0", "completion": "0"}},
                    {"id": "mystery/zero-no-suffix", "pricing": {"prompt": "0", "completion": "0"}},
                    {"id": "openai/gpt-4o", "pricing": {"prompt": "0.0000025", "completion": "0.00001"}}
                  ]
                }
            """.trimIndent(),
            providerId = "or-1",
            providerName = "OpenRouter",
            isLocal = false
        )
        val freeModel = parsedModels.first { it.modelId == "qwen/qwen-2.5:free" }
        assertEquals(AccessTier.FREE_TIER, freeModel.accessTier)
        assertTrue(freeModel.freeTierVerified)
        val mysteryModel = parsedModels.first { it.modelId == "mystery/zero-no-suffix" }
        assertEquals(AccessTier.UNKNOWN, mysteryModel.accessTier)
        assertFalse(mysteryModel.freeTierVerified)
        val paidModel = parsedModels.first { it.modelId == "openai/gpt-4o" }
        assertEquals(AccessTier.PAID, paidModel.accessTier)

        val openAiReq = openAi.buildChatRequest(
            baseUrl = "https://api.openai.com/v1",
            apiKey = "sk-test",
            customHeadersJson = "",
            params = ChatRequestParams(
                messages = listOf(ChatMessagePayload("user", "Inspect", contentParts = listOf(ContentPart.Text("Inspect"), imgPart, txtPart))),
                modelId = "gpt-4o"
            ),
            capabilities = openAi.capabilitiesForModel("gpt-4o", paidModel),
            stream = false
        )
        assertEquals("https://api.openai.com/v1/chat/completions", openAiReq.url.toString())
        assertThrows(IllegalArgumentException::class.java) {
            openAi.buildChatRequest(
                baseUrl = "https://api.openai.com/v1",
                apiKey = "sk-test",
                customHeadersJson = "",
                params = ChatRequestParams(
                    messages = listOf(ChatMessagePayload("user", "Read PDF", contentParts = listOf(ContentPart.Text("Read"), pdfPart))),
                    modelId = "gpt-4o"
                ),
                capabilities = openAi.capabilitiesForModel("gpt-4o", paidModel),
                stream = false
            )
        }

        // 2. Gemini: serializes inlineData PDF + image
        val gemini = GeminiProviderAdapter()
        val gemReq = gemini.buildChatRequest(
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            apiKey = "AIzaTest",
            customHeadersJson = "",
            params = ChatRequestParams(
                messages = listOf(ChatMessagePayload("user", "Check", contentParts = listOf(ContentPart.Text("Check"), imgPart, pdfPart))),
                modelId = "gemini-2.5-flash"
            ),
            capabilities = gemini.capabilitiesForModel("gemini-2.5-flash", null),
            stream = false
        )
        val gemBuffer = okio.Buffer()
        gemReq.body!!.writeTo(gemBuffer)
        val gemJson = JSONObject(gemBuffer.readUtf8())
        val gemParts = gemJson.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        assertEquals(3, gemParts.length())
        assertEquals("application/pdf", gemParts.getJSONObject(2).getJSONObject("inlineData").getString("mimeType"))

        // 3. Anthropic: serializes base64 PDF document + image
        val anthropic = AnthropicProviderAdapter()
        val antReq = anthropic.buildChatRequest(
            baseUrl = "https://api.anthropic.com/v1",
            apiKey = "sk-ant",
            customHeadersJson = "",
            params = ChatRequestParams(
                messages = listOf(ChatMessagePayload("user", "Check", contentParts = listOf(ContentPart.Text("Check"), imgPart, pdfPart))),
                modelId = "claude-3-7-sonnet-latest"
            ),
            capabilities = anthropic.capabilitiesForModel("claude-3-7-sonnet-latest", null),
            stream = false
        )
        val antBuffer = okio.Buffer()
        antReq.body!!.writeTo(antBuffer)
        val antContent = JSONObject(antBuffer.readUtf8()).getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals("image", antContent.getJSONObject(1).getString("type"))
        assertEquals("document", antContent.getJSONObject(2).getString("type"))

        // 4. Cohere: rejects image and PDF explicitly; allows text file
        val cohere = CohereProviderAdapter()
        assertThrows(IllegalArgumentException::class.java) {
            cohere.buildChatRequest(
                baseUrl = "https://api.cohere.com/v2",
                apiKey = "co-test",
                customHeadersJson = "",
                params = ChatRequestParams(
                    messages = listOf(ChatMessagePayload("user", "Img", contentParts = listOf(imgPart))),
                    modelId = "command-a-03-2025"
                ),
                capabilities = cohere.capabilitiesForModel("command-a-03-2025", null),
                stream = false
            )
        }
        val cohTextReq = cohere.buildChatRequest(
            baseUrl = "https://api.cohere.com/v2",
            apiKey = "co-test",
            customHeadersJson = "",
            params = ChatRequestParams(
                messages = listOf(ChatMessagePayload("user", "Notes", contentParts = listOf(txtPart))),
                modelId = "command-a-03-2025"
            ),
            capabilities = cohere.capabilitiesForModel("command-a-03-2025", null),
            stream = false
        )
        assertNotNull(cohTextReq)

        // 5. Ollama: accepts image on vision model (llava), rejects image on llama3 and PDF
        val ollama = OllamaProviderAdapter()
        val olReq = ollama.buildChatRequest(
            baseUrl = "http://127.0.0.1:11434",
            apiKey = "",
            customHeadersJson = "",
            params = ChatRequestParams(
                messages = listOf(ChatMessagePayload("user", "See", contentParts = listOf(ContentPart.Text("See"), imgPart))),
                modelId = "llava:latest"
            ),
            capabilities = ollama.capabilitiesForModel("llava:latest", null),
            stream = false
        )
        val olBuffer = okio.Buffer()
        olReq.body!!.writeTo(olBuffer)
        val olMsg = JSONObject(olBuffer.readUtf8()).getJSONArray("messages").getJSONObject(0)
        assertEquals(1, olMsg.getJSONArray("images").length())

        assertThrows(IllegalArgumentException::class.java) {
            ollama.buildChatRequest(
                baseUrl = "http://127.0.0.1:11434",
                apiKey = "",
                customHeadersJson = "",
                params = ChatRequestParams(
                    messages = listOf(ChatMessagePayload("user", "See", contentParts = listOf(imgPart))),
                    modelId = "llama3:8b"
                ),
                capabilities = ollama.capabilitiesForModel("llama3:8b", null),
                stream = false
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ollama.buildChatRequest(
                baseUrl = "http://127.0.0.1:11434",
                apiKey = "",
                customHeadersJson = "",
                params = ChatRequestParams(
                    messages = listOf(ChatMessagePayload("user", "PDF", contentParts = listOf(pdfPart))),
                    modelId = "llava:latest"
                ),
                capabilities = ollama.capabilitiesForModel("llava:latest", null),
                stream = false
            )
        }
    }

    // =========================================================================
    // Phase 1 & 2 & 3 Compose UI Tests (Playground + ModelsScreen)
    // =========================================================================

    @Test
    fun playgroundChatUi_hidesTopChipRowsDefaultsToAutoAndOpensModelAndAttachSheets() {
        runBlocking(Dispatchers.IO) {
            insertCloudProvider(app.database, id = "ui-p1", name = "OpenRouter", priority = 1)
            app.database.modelDao().upsertAll(
                listOf(
                    ModelEntity(
                        id = "ui-p1::deepseek:free",
                        modelId = "deepseek:free",
                        displayName = "DeepSeek Free",
                        providerId = "ui-p1",
                        providerName = "OpenRouter",
                        accessTier = AccessTier.FREE_TIER,
                        freeTierVerified = true,
                        freeTierSource = "Provider Catalog (:free)",
                        enabled = true,
                        supportsChat = true
                    ),
                    ModelEntity(
                        id = "ui-p1::openai/gpt-4o",
                        modelId = "openai/gpt-4o",
                        displayName = "GPT-4o",
                        providerId = "ui-p1",
                        providerName = "OpenRouter",
                        accessTier = AccessTier.PAID,
                        freeTierVerified = false,
                        enabled = true,
                        supportsChat = true
                    )
                )
            )
        }

        val vm = GatewayViewModel(app)
        composeRule.setContent {
            CompositionLocalProvider(LocalStrings provides EnglishStrings) {
                JhGetWayTheme(themeMode = ThemeMode.DARK) {
                    Box(modifier = Modifier.width(320.dp).height(720.dp)) {
                        PlaygroundScreen(viewModel = vm, onNavigate = {})
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // No old top provider/model LazyRow filter chips
        composeRule.onAllNodesWithTag("playground_provider_filter_ui-p1").assertCountEquals(0)
        composeRule.onAllNodesWithTag("playground_model_deepseek:free").assertCountEquals(0)

        // Default composer model chip displays Auto with >= 48dp touch height
        val modelChip = composeRule.onNodeWithTag("playground_model_selector_chip")
        modelChip.assertIsDisplayed()
        modelChip.assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText(EnglishStrings.autoShortLabel).assertIsDisplayed()

        // Preserve typed prompt across opening/dismissing Attach Sheet
        composeRule.onNodeWithTag("playground_prompt_input").performTextInput("Draft prompt text")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("playground_plus_btn").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("playground_attach_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("playground_attach_camera").assertIsDisplayed()
        composeRule.onNodeWithTag("playground_attach_photos").assertIsDisplayed()
        composeRule.onNodeWithTag("playground_attach_files").assertIsDisplayed()

        composeRule.onNodeWithTag("playground_attach_camera").performClick()
        composeRule.waitForIdle()
        // Prompt text must still be preserved even after attach interaction
        composeRule.onNodeWithText("Draft prompt text").assertExists()

        // Open Model Selector sheet
        composeRule.onNodeWithTag("playground_model_selector_chip").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("playground_model_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("playground_model_option_auto").assertIsDisplayed()
        composeRule.onNodeWithTag("playground_model_search").assertIsDisplayed()
        composeRule.onNodeWithTag("playground_model_list")
            .performScrollToNode(hasTestTag("playground_sheet_model_deepseek:free"))
        composeRule.onNodeWithText(EnglishStrings.tierFree, substring = true).assertExists()

        // Select manual paid model
        composeRule.onNodeWithTag("playground_model_list")
            .performScrollToNode(hasTestTag("playground_sheet_model_openai/gpt-4o"))
        composeRule.onNodeWithTag("playground_sheet_model_openai/gpt-4o").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("GPT-4o").assertIsDisplayed()

        // Re-open and return to Auto
        composeRule.onNodeWithTag("playground_model_selector_chip").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("playground_model_option_auto").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(EnglishStrings.autoShortLabel).assertIsDisplayed()
    }

    @Test
    fun playgroundScreen_rendersCleanlyInBengaliAnd320dpNarrowScreen() {
        runBlocking(Dispatchers.IO) {
            insertCloudProvider(app.database, id = "bn-p1", name = "Gemini", apiFormat = ApiFormat.GEMINI)
            app.database.modelDao().upsert(
                ModelEntity(
                    id = "bn-p1::gemini-2.5-flash",
                    modelId = "gemini-2.5-flash",
                    displayName = "Gemini 2.5 Flash",
                    providerId = "bn-p1",
                    providerName = "Gemini",
                    accessTier = AccessTier.FREE_TIER,
                    freeTierVerified = true,
                    enabled = true,
                    supportsChat = true
                )
            )
        }
        val vm = GatewayViewModel(app)
        composeRule.setContent {
            CompositionLocalProvider(LocalStrings provides BengaliStrings) {
                JhGetWayTheme(themeMode = ThemeMode.LIGHT) {
                    Box(modifier = Modifier.width(320.dp).height(640.dp)) {
                        PlaygroundScreen(viewModel = vm, onNavigate = {})
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("playground_model_selector_chip").assertIsDisplayed()
        composeRule.onNodeWithText(BengaliStrings.autoShortLabel).assertIsDisplayed()
        composeRule.onNodeWithTag("playground_plus_btn").assertIsDisplayed()
        composeRule.onNodeWithTag("playground_send_btn").assertIsDisplayed()
    }

    @Test
    fun modelsScreen_detailsDialogCanConfirmAndRevokeFreeTier() {
        runBlocking(Dispatchers.IO) {
            insertCloudProvider(app.database, id = "m-p1", name = "Groq", apiFormat = ApiFormat.OPENAI)
            app.database.modelDao().upsert(
                ModelEntity(
                    id = "m-p1::llama-3.3-70b-versatile",
                    modelId = "llama-3.3-70b-versatile",
                    displayName = "Llama 3.3 70B",
                    providerId = "m-p1",
                    providerName = "Groq",
                    accessTier = AccessTier.UNKNOWN,
                    freeTierVerified = false,
                    enabled = true,
                    supportsChat = true
                )
            )
        }
        val vm = GatewayViewModel(app)
        composeRule.setContent {
            CompositionLocalProvider(LocalStrings provides EnglishStrings) {
                JhGetWayTheme {
                    ModelsScreen(viewModel = vm, onNavigate = {})
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(EnglishStrings.tierUnknown).assertExists()
        composeRule.onNodeWithTag("model_details_llama-3.3-70b-versatile").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("model_confirm_free_btn").performScrollTo().performClick()

        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val updated = runBlocking { app.database.modelDao().getAllOnce().firstOrNull() }
            if (updated?.freeTierVerified == true) break
            Thread.sleep(25)
        }
        composeRule.waitForIdle()

        val persisted = runBlocking { app.database.modelDao().getAllOnce().single() }
        assertEquals(AccessTier.FREE_TIER, persisted.accessTier)
        assertTrue(persisted.freeTierVerified)
        assertEquals("User Confirmed", persisted.freeTierSource)
        composeRule.onNodeWithTag("model_revoke_free_btn", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }
}
