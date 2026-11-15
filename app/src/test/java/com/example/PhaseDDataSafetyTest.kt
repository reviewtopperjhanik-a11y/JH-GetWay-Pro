package com.example

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.core.security.CryptoFailureException
import com.example.core.security.CryptoFailureReason
import com.example.core.security.CryptoManager
import com.example.data.local.*
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PhaseDDataSafetyTest {

    private lateinit var app: Context
    private lateinit var db: JhGetWayDatabase
    private lateinit var backupManager: BackupManager

    @Before
    fun setUp() {
        CryptoManager.resetTestKeyProvider()
        app = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(app, JhGetWayDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        backupManager = BackupManager(db)
    }

    @After
    fun tearDown() {
        db.close()
        CryptoManager.resetTestKeyProvider()
    }

    // -------------------------------------------------------------------------
    // Room Schema Export & Non-Destructive Migrations (1 -> 2 -> 3)
    // -------------------------------------------------------------------------

    @Test
    fun roomSchemaSnapshot_isExportedAndMatchesVersion3() {
        val candidates = listOf(
            File("schemas/com.example.data.local.JhGetWayDatabase/3.json"),
            File("app/schemas/com.example.data.local.JhGetWayDatabase/3.json")
        )
        val schemaFile = candidates.firstOrNull { it.exists() }
        assertNotNull("Expected Room exported schema snapshot 3.json to exist", schemaFile)
        val schemaJson = JSONObject(schemaFile!!.readText())
        val dbObj = schemaJson.getJSONObject("database")
        assertEquals(3, dbObj.getInt("version"))
        val entities = dbObj.getJSONArray("entities")
        assertEquals(15, entities.length())
    }

    @Test
    fun roomMigration1To2To3_preservesAllUserDataAndCreatesIndexes() {
        val dbName = "phase_d_migration_1_2_3.db"
        app.deleteDatabase(dbName)

        // Create authentic Version 1 schema and populate existing user data
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(app)
                .name(dbName)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(s: SupportSQLiteDatabase) {
                        createV1SchemaAndSeed(s)
                    }
                    override fun onUpgrade(s: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        helper.writableDatabase.close()
        helper.close()

        // Open with Room v3 + explicit migrations (non-destructive)
        val migratedDb = Room.databaseBuilder(app, JhGetWayDatabase::class.java, dbName)
            .addMigrations(*JhGetWayDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val sqlite = migratedDb.openHelper.writableDatabase
            assertEquals(5, sqlite.version)

            // Verify all expected Phase D indexes exist
            val indexNames = mutableSetOf<String>()
            sqlite.query("SELECT name FROM sqlite_master WHERE type = 'index'").use { c ->
                while (c.moveToNext()) indexNames.add(c.getString(0))
            }
            val expectedIndexes = listOf(
                "index_api_keys_providerId",
                "index_models_providerId",
                "index_models_modelId",
                "index_coding_agents_enabled",
                "index_mcp_tools_serverId",
                "index_workflow_runs_workflowId",
                "index_workflow_runs_startedAt",
                "index_devices_enabled",
                "index_devices_revoked",
                "index_request_metrics_timestamp",
                "index_request_metrics_providerId",
                "index_request_metrics_modelId",
                "index_request_metrics_requestId",
                "index_activity_logs_timestamp",
                "index_activity_logs_category",
                "index_playground_sessions_updatedAt",
                "index_playground_messages_sessionId",
                "index_playground_messages_timestamp"
            )
            expectedIndexes.forEach { idx ->
                assertTrue("Missing index $idx after migration", idx in indexNames)
            }

            // Verify user data survived intact across 1 -> 2 -> 3
            runBlocking {
                val providers = migratedDb.providerDao().getAllOnce()
                assertEquals(1, providers.size)
                assertEquals("Legacy Provider", providers.first().name)

                val devices = migratedDb.deviceDao().getAllOnce()
                assertEquals(1, devices.size)
                assertEquals("CHAT,MODELS_READ", devices.first().permissionsCsv)

                val metrics = migratedDb.metricAndLogDao().getMetricsOnce()
                assertEquals(1, metrics.size)
                assertEquals("UNKNOWN", metrics.first().usageSource)
            }
        } finally {
            migratedDb.close()
            app.deleteDatabase(dbName)
        }
    }

    // -------------------------------------------------------------------------
    // Retention Policies (Logs, Usage Metrics/Workflow Runs, Playground History)
    // -------------------------------------------------------------------------

    @Test
    fun retentionManager_prunesExpiredLogsMetricsRunsAndPlaygroundWhilePreservingRecent() = runBlocking {
        val nowMs = 1_750_000_000_000L
        val dayMs = 86_400_000L

        // Logs: retention = 30d
        db.metricAndLogDao().insertLog(
            ActivityLogEntity(id = "log-old", timestamp = nowMs - 31 * dayMs, category = ActivityCategory.SYSTEM_EVENT, relatedEntity = "Sys", status = "OK", safeMessage = "Old")
        )
        db.metricAndLogDao().insertLog(
            ActivityLogEntity(id = "log-fresh", timestamp = nowMs - 5 * dayMs, category = ActivityCategory.SYSTEM_EVENT, relatedEntity = "Sys", status = "OK", safeMessage = "Fresh")
        )

        // Metrics & workflow runs: retention = 90d
        db.metricAndLogDao().insertMetric(
            RequestMetricEntity(id = "m-old", requestId = "r1", timestamp = nowMs - 95 * dayMs, providerId = "p1", providerName = "OpenAI", modelId = "gpt-4o")
        )
        db.metricAndLogDao().insertMetric(
            RequestMetricEntity(id = "m-fresh", requestId = "r2", timestamp = nowMs - 10 * dayMs, providerId = "p1", providerName = "OpenAI", modelId = "gpt-4o")
        )
        db.workflowDao().upsertRun(
            WorkflowRunEntity(id = "run-old", workflowId = "w1", workflowName = "Flow", status = "COMPLETED", inputText = "in", startedAt = nowMs - 100 * dayMs)
        )
        db.workflowDao().upsertRun(
            WorkflowRunEntity(id = "run-fresh", workflowId = "w1", workflowName = "Flow", status = "COMPLETED", inputText = "in", startedAt = nowMs - 2 * dayMs)
        )

        // Playground sessions & messages: retention = 60d
        db.playgroundDao().upsertSession(
            PlaygroundSessionEntity(id = "sess-old", title = "Old Session", createdAt = nowMs - 70 * dayMs, updatedAt = nowMs - 65 * dayMs)
        )
        db.playgroundDao().insertMessage(
            PlaygroundMessageEntity(id = "msg-old", sessionId = "sess-old", role = "user", content = "old", timestamp = nowMs - 65 * dayMs)
        )
        db.playgroundDao().upsertSession(
            PlaygroundSessionEntity(id = "sess-fresh", title = "Fresh Session", createdAt = nowMs - 20 * dayMs, updatedAt = nowMs - 1 * dayMs)
        )
        db.playgroundDao().insertMessage(
            PlaygroundMessageEntity(id = "msg-fresh", sessionId = "sess-fresh", role = "user", content = "fresh", timestamp = nowMs - 1 * dayMs)
        )

        val retention = DataRetentionManager(db)
        val report = retention.enforcePolicies(
            logRetentionDays = 30,
            usageRetentionDays = 90,
            playgroundRetentionDays = 60,
            nowMs = nowMs
        )

        assertEquals(1, report.deletedLogs)
        assertEquals(1, report.deletedMetrics)
        assertEquals(1, report.deletedWorkflowRuns)
        assertEquals(1, report.deletedPlaygroundSessions)
        assertEquals(1, report.deletedPlaygroundMessages)

        assertEquals(listOf("m-fresh"), db.metricAndLogDao().getMetricsOnce().map { it.id })
    }

    // -------------------------------------------------------------------------
    // Versioned Encrypted Backup: Export, Integrity, Preview, Conflict, Rollback
    // -------------------------------------------------------------------------

    @Test
    fun backupExportPreviewAndRestore_roundtripsAllEntitiesWithPassphraseKdfAndNoPlaintextLeak() = runBlocking {
        val secretApiKey = "sk-backup-super-secret-value-998877"
        val secretAgentToken = "jhgw_ag_secret_agent_token_1122"
        val secretDeviceToken = "jhgw_dev_secret_device_token_3344"
        val secretMcpToken = "mcp_bearer_secret_5566"
        val passphrase = "StrongBackupPassphrase!2026"

        seedFullSampleData(secretApiKey, secretAgentToken, secretDeviceToken, secretMcpToken)

        val archive = backupManager.exportEncryptedBackup(passphrase)
        assertTrue(archive.startsWith("JHBACKUP_V2:150000:"))
        assertFalse(archive.contains(secretApiKey))
        assertFalse(archive.contains(secretAgentToken))
        assertFalse(archive.contains(secretDeviceToken))
        assertFalse(archive.contains(secretMcpToken))
        assertFalse(archive.contains(passphrase))

        // Wipe DB to simulate fresh device restore
        clearAllDbTables()

        val preview = backupManager.previewEncryptedBackup(archive, passphrase)
        assertEquals(2, preview.formatVersion)
        assertEquals(5, preview.schemaVersion)
        assertEquals(150_000, preview.kdfIterations)
        assertTrue(preview.integrityVerified)
        assertEquals(0, preview.conflicts.totalConflicts)
        assertEquals(10, preview.counts.total) // 1 of each of 10 backed-up tables

        val result = backupManager.restoreEncryptedBackupDetailed(archive, passphrase, RestoreConflictStrategy.OVERWRITE_EXISTING)
        assertEquals(10, result.importedCount)
        assertEquals(0, result.skippedConflicts)

        // Verify all credentials decrypt cleanly into destination Keystore
        val restoredKey = db.apiKeyDao().getAllOnce().single()
        assertEquals(secretApiKey, CryptoManager.decrypt(restoredKey.encryptedKey))
        val restoredAgent = db.codingAgentDao().getAllOnce().single()
        assertEquals(secretAgentToken, CryptoManager.decrypt(restoredAgent.encryptedToken))
        val restoredDevice = db.deviceDao().getAllOnce().single()
        assertEquals(secretDeviceToken, CryptoManager.decrypt(restoredDevice.encryptedToken))
        val restoredMcp = db.mcpDao().getServersOnce().single()
        assertEquals(secretMcpToken, CryptoManager.decrypt(restoredMcp.encryptedAuthToken))
    }

    @Test
    fun backupRestore_detectsWrongPassphraseTamperedArchiveAndIntegrityMismatch() = runBlocking {
        seedFullSampleData("sk-orig-1", "jhgw_ag_1", "jhgw_dev_1", "mcp_1")
        val passphrase = "CorrectPassphrase123!"
        val archive = backupManager.exportEncryptedBackup(passphrase)

        // 1. Wrong passphrase fails with AUTHENTICATION_FAILED
        val wrongPassErr = runCatching {
            backupManager.previewEncryptedBackup(archive, "WrongPassphrase999!")
        }.exceptionOrNull()
        assertTrue(wrongPassErr is CryptoFailureException)
        assertEquals(CryptoFailureReason.AUTHENTICATION_FAILED, (wrongPassErr as CryptoFailureException).reason)

        // 2. Tampering KDF iterations in header fails AAD authentication
        val parts = archive.split(":").toMutableList()
        parts[1] = "100000"
        val aadTampered = parts.joinToString(":")
        val aadErr = runCatching {
            backupManager.restoreEncryptedBackup(aadTampered, passphrase)
        }.exceptionOrNull()
        assertTrue(aadErr is CryptoFailureException)
        assertEquals(CryptoFailureReason.AUTHENTICATION_FAILED, (aadErr as CryptoFailureException).reason)

        // 3. Tampering SHA-256 integrity field inside encrypted payload is caught before restore
        val decrypted = CryptoManager.decryptBackupArchive(archive, passphrase).plainJson
        val tamperedJson = JSONObject(decrypted).put("integritySha256", "0".repeat(64)).toString()
        val tamperedEnvelope = CryptoManager.encryptBackupArchive(tamperedJson, passphrase)
        val integrityErr = runCatching {
            backupManager.previewEncryptedBackup(tamperedEnvelope, passphrase)
        }.exceptionOrNull()
        assertTrue(integrityErr is IllegalArgumentException)
        assertTrue(integrityErr!!.message!!.contains("integrity checksum mismatch"))

        // 4. Legacy V1 backup format remains restorable
        val legacyJson = JSONObject()
            .put("version", 1)
            .put("providers", JSONArray().put(
                JSONObject()
                    .put("id", "prov-v1")
                    .put("name", "Legacy V1")
                    .put("baseUrl", "https://api.openai.com/v1")
            ))
            .toString()
        val legacyArchive = CryptoManager.encryptWithPassphrase(legacyJson, "legacyPass1")
        val v1Preview = backupManager.previewEncryptedBackup(legacyArchive, "legacyPass1")
        assertEquals(1, v1Preview.formatVersion)
        assertEquals(1, v1Preview.counts.providers)
    }

    @Test
    fun backupRestore_conflictStrategiesAndTransactionalRollbackOnFailure() = runBlocking {
        seedFullSampleData("sk-backup-new", "jhgw_ag_new", "jhgw_dev_new", "mcp_new")
        val passphrase = "BackupSecretPassphrase!"
        val archive = backupManager.exportEncryptedBackup(passphrase)

        // Mutate existing DB: modify p1 name and add local-only provider p-local
        clearAllDbTables()
        db.providerDao().upsert(
            ProviderEntity(
                id = "p1",
                name = "Local Existing P1",
                baseUrl = "https://local.existing/v1"
            )
        )
        db.providerDao().upsert(
            ProviderEntity(
                id = "p-local-only",
                name = "Keep Local Only",
                baseUrl = "https://keep.local/v1"
            )
        )

        // Preview detects 1 conflict (p1)
        val preview = backupManager.previewEncryptedBackup(archive, passphrase)
        assertEquals(1, preview.conflicts.providers)
        assertEquals(1, preview.conflicts.totalConflicts)

        // Strategy 1: SKIP_CONFLICTS keeps "Local Existing P1" and imports remaining 9 records
        val skipRes = backupManager.restoreEncryptedBackupDetailed(archive, passphrase, RestoreConflictStrategy.SKIP_CONFLICTS)
        assertEquals(1, skipRes.skippedConflicts)
        assertEquals(9, skipRes.importedCount)
        assertEquals("Local Existing P1", db.providerDao().getById("p1")!!.name)
        assertNotNull(db.providerDao().getById("p-local-only"))

        // Strategy 2: OVERWRITE_EXISTING updates p1 while preserving p-local-only
        val overwriteRes = backupManager.restoreEncryptedBackupDetailed(archive, passphrase, RestoreConflictStrategy.OVERWRITE_EXISTING)
        assertEquals(10, overwriteRes.overwrittenCount)
        assertEquals("Backup OpenAI", db.providerDao().getById("p1")!!.name)
        assertNotNull(db.providerDao().getById("p-local-only"))

        // Simulated mid-transaction failure during REPLACE_ALL MUST roll back cleanly
        val failureErr = runCatching {
            backupManager.restoreEncryptedBackupInternal(
                encryptedPayload = archive,
                passphrase = passphrase,
                strategy = RestoreConflictStrategy.REPLACE_ALL,
                beforeCommitHook = { throw IllegalStateException("Simulated disk error before commit") }
            )
        }.exceptionOrNull()
        assertNotNull(failureErr)
        // Because REPLACE_ALL cleared p-local-only inside the transaction before the failure hook,
        // transaction rollback MUST restore p-local-only intact!
        assertNotNull("Previous DB must remain intact after failed restore", db.providerDao().getById("p-local-only"))

        // Strategy 3: REPLACE_ALL without failure removes p-local-only cleanly
        backupManager.restoreEncryptedBackupDetailed(archive, passphrase, RestoreConflictStrategy.REPLACE_ALL)
        assertNull(db.providerDao().getById("p-local-only"))
        assertEquals("Backup OpenAI", db.providerDao().getById("p1")!!.name)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private suspend fun seedFullSampleData(
        apiKey: String,
        agentToken: String,
        deviceToken: String,
        mcpToken: String
    ) {
        db.providerDao().upsert(
            ProviderEntity(id = "p1", name = "Backup OpenAI", baseUrl = "https://api.openai.com/v1")
        )
        db.apiKeyDao().upsert(
            ApiKeyEntity(id = "k1", providerId = "p1", label = "Main Key", encryptedKey = CryptoManager.encrypt(apiKey), maskedPreview = "sk-••••77")
        )
        db.modelDao().upsert(
            ModelEntity(id = "m1", modelId = "gpt-4o-mini", displayName = "GPT-4o mini", providerId = "p1", providerName = "Backup OpenAI")
        )
        db.routingRuleDao().upsert(
            RoutingRuleEntity(id = "r1", name = "Default Rule", modelAlias = "jh-general", preferredProviderId = "p1")
        )
        db.codingAgentDao().upsert(
            CodingAgentEntity(id = "a1", name = "Codex CLI", agentType = CodingAgentType.CODEX, encryptedToken = CryptoManager.encrypt(agentToken), tokenPreview = "jhgw_••••22")
        )
        db.localAiServerDao().upsert(
            LocalAiServerEntity(id = "l1", name = "Local Ollama", engineType = LocalEngineType.OLLAMA, serverUrl = "http://127.0.0.1:11434")
        )
        db.mcpDao().upsertServer(
            McpServerEntity(id = "ms1", name = "HTTP Tools", transportType = McpTransportType.STREAMABLE_HTTP, endpointOrCommand = "https://mcp.example.com/rpc", encryptedAuthToken = CryptoManager.encrypt(mcpToken))
        )
        db.mcpDao().upsertTool(
            McpToolEntity(id = "mt1", serverId = "ms1", toolName = "search_docs", inputSchemaJson = """{"type":"object"}""")
        )
        db.workflowDao().upsertWorkflow(
            WorkflowEntity(id = "w1", name = "Simple Flow", nodesJson = """[{"id":"n1","type":"INPUT"}]""", edgesJson = """[]""", isTemplate = false)
        )
        db.deviceDao().upsert(
            DeviceEntity(id = "d1", name = "Dev Laptop", deviceType = "CLI", deviceCode = "DEV-99", encryptedToken = CryptoManager.encrypt(deviceToken), tokenPreview = "jhgw_••••44")
        )
    }

    private suspend fun clearAllDbTables() {
        db.providerDao().clearAll()
        db.apiKeyDao().clearAll()
        db.modelDao().clearAll()
        db.routingRuleDao().clearAll()
        db.codingAgentDao().clearAll()
        db.localAiServerDao().clearAll()
        db.mcpDao().clearTools()
        db.mcpDao().clearServers()
        db.workflowDao().clearWorkflows()
        db.deviceDao().clearAll()
    }

    private fun createV1SchemaAndSeed(s: SupportSQLiteDatabase) {
        s.execSQL("CREATE TABLE IF NOT EXISTS `providers` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `type` TEXT NOT NULL, `presetId` TEXT NOT NULL, `baseUrl` TEXT NOT NULL, `apiFormat` TEXT NOT NULL, `defaultModel` TEXT NOT NULL, `priority` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `customHeadersJson` TEXT NOT NULL, `notes` TEXT NOT NULL, `status` TEXT NOT NULL, `lastLatencyMs` INTEGER, `lastError` TEXT, `lastCheckedAt` INTEGER, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        s.execSQL("CREATE TABLE IF NOT EXISTS `api_keys` (`id` TEXT NOT NULL, `providerId` TEXT NOT NULL, `label` TEXT NOT NULL, `encryptedKey` TEXT NOT NULL, `maskedPreview` TEXT NOT NULL, `priority` INTEGER NOT NULL, `isDefault` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `status` TEXT NOT NULL, `selectionPolicy` TEXT NOT NULL, `usageCount` INTEGER NOT NULL, `lastUsedAt` INTEGER, `lastTestedAt` INTEGER, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        s.execSQL("CREATE TABLE IF NOT EXISTS `models` (`id` TEXT NOT NULL, `modelId` TEXT NOT NULL, `displayName` TEXT NOT NULL, `providerId` TEXT NOT NULL, `providerName` TEXT NOT NULL, `modelType` TEXT NOT NULL, `capabilitiesCsv` TEXT NOT NULL, `contextWindow` INTEGER, `supportsTools` INTEGER NOT NULL, `supportsStreaming` INTEGER NOT NULL, `isLocal` INTEGER NOT NULL, `inputPricePer1M` REAL, `outputPricePer1M` REAL, `pricingUpdatedAt` TEXT, `enabled` INTEGER NOT NULL, `isDefault` INTEGER NOT NULL, `alias` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        s.execSQL("CREATE TABLE IF NOT EXISTS `routing_rules` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `modelAlias` TEXT NOT NULL, `allowedProviderIdsCsv` TEXT NOT NULL, `preferredProviderId` TEXT NOT NULL, `backupProviderIdsCsv` TEXT NOT NULL, `priority` INTEGER NOT NULL, `requiredCapabilitiesCsv` TEXT NOT NULL, `maxCostPer1M` REAL, `maxLatencyMs` INTEGER, `localCloudPreference` TEXT NOT NULL, `enabled` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        s.execSQL("CREATE TABLE IF NOT EXISTS `coding_agents` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `agentType` TEXT NOT NULL, `modelAlias` TEXT NOT NULL, `defaultModelId` TEXT NOT NULL, `allowedModelsCsv` TEXT NOT NULL, `streaming` INTEGER NOT NULL, `toolCalling` INTEGER NOT NULL, `dailyTokenLimit` INTEGER, `tokensUsedToday` INTEGER NOT NULL, `requestCount` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `encryptedToken` TEXT NOT NULL, `tokenPreview` TEXT NOT NULL, `lastActiveAt` INTEGER, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        s.execSQL("CREATE TABLE IF NOT EXISTS `local_ai_servers` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `engineType` TEXT NOT NULL, `serverUrl` TEXT NOT NULL, `encryptedAuthToken` TEXT NOT NULL, `hasAuth` INTEGER NOT NULL, `timeoutSec` INTEGER NOT NULL, `defaultModel` TEXT NOT NULL, `isPrivateNetwork` INTEGER NOT NULL, `status` TEXT NOT NULL, `lastLatencyMs` INTEGER, `lastError` TEXT, `enabled` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        s.execSQL("CREATE TABLE IF NOT EXISTS `mcp_servers` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `transportType` TEXT NOT NULL, `endpointOrCommand` TEXT NOT NULL, `arguments` TEXT NOT NULL, `encryptedEnvJson` TEXT NOT NULL, `encryptedAuthToken` TEXT NOT NULL, `timeoutSec` INTEGER NOT NULL, `status` TEXT NOT NULL, `lastConnectedAt` INTEGER, `lastError` TEXT, `resourcesJson` TEXT NOT NULL, `promptsJson` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        s.execSQL("CREATE TABLE IF NOT EXISTS `mcp_tools` (`id` TEXT NOT NULL, `serverId` TEXT NOT NULL, `toolName` TEXT NOT NULL, `description` TEXT NOT NULL, `inputSchemaJson` TEXT NOT NULL, `permission` TEXT NOT NULL, `lastResultPreview` TEXT NOT NULL, `lastExecutedAt` INTEGER, PRIMARY KEY(`id`))")
        s.execSQL("CREATE TABLE IF NOT EXISTS `workflows` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `nodesJson` TEXT NOT NULL, `edgesJson` TEXT NOT NULL, `isTemplate` INTEGER NOT NULL, `lastRunStatus` TEXT, `lastRunAt` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        s.execSQL("CREATE TABLE IF NOT EXISTS `workflow_runs` (`id` TEXT NOT NULL, `workflowId` TEXT NOT NULL, `workflowName` TEXT NOT NULL, `status` TEXT NOT NULL, `inputText` TEXT NOT NULL, `finalOutput` TEXT NOT NULL, `nodeResultsJson` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `finishedAt` INTEGER, `errorMessage` TEXT, PRIMARY KEY(`id`))")
        s.execSQL("CREATE TABLE IF NOT EXISTS `devices` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `deviceType` TEXT NOT NULL, `deviceCode` TEXT NOT NULL, `encryptedToken` TEXT NOT NULL, `tokenPreview` TEXT NOT NULL, `permissionsCsv` TEXT NOT NULL, `connectionType` TEXT NOT NULL, `requestCount` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `revoked` INTEGER NOT NULL, `lastActiveAt` INTEGER, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        s.execSQL("CREATE TABLE IF NOT EXISTS `request_metrics` (`id` TEXT NOT NULL, `requestId` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `providerId` TEXT NOT NULL, `providerName` TEXT NOT NULL, `modelId` TEXT NOT NULL, `deviceName` TEXT NOT NULL, `endpoint` TEXT NOT NULL, `statusCode` INTEGER NOT NULL, `success` INTEGER NOT NULL, `latencyMs` INTEGER NOT NULL, `inputTokens` INTEGER NOT NULL, `outputTokens` INTEGER NOT NULL, `estimatedCostUsd` REAL, `fallbackAttempts` INTEGER NOT NULL, `routingMode` TEXT NOT NULL, `routingReason` TEXT NOT NULL, `errorCode` TEXT, `safeErrorMessage` TEXT, PRIMARY KEY(`id`))")
        s.execSQL("CREATE TABLE IF NOT EXISTS `activity_logs` (`id` TEXT NOT NULL, `eventId` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `category` TEXT NOT NULL, `relatedEntity` TEXT NOT NULL, `status` TEXT NOT NULL, `safeMessage` TEXT NOT NULL, PRIMARY KEY(`id`))")
        s.execSQL("CREATE TABLE IF NOT EXISTS `playground_sessions` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `modelId` TEXT NOT NULL, `providerId` TEXT NOT NULL, `systemPrompt` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        s.execSQL("CREATE TABLE IF NOT EXISTS `playground_messages` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `role` TEXT NOT NULL, `content` TEXT NOT NULL, `modelId` TEXT NOT NULL, `providerName` TEXT NOT NULL, `latencyMs` INTEGER, `inputTokens` INTEGER, `outputTokens` INTEGER, `estimatedCostUsd` REAL, `isError` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, PRIMARY KEY(`id`))")

        s.execSQL("INSERT INTO `providers` VALUES ('p1','Legacy Provider','BUILT_IN','OPENAI','https://api.openai.com/v1','OPENAI','gpt-4o-mini',1,1,'','','UNKNOWN',NULL,NULL,NULL,1000)")
        s.execSQL("INSERT INTO `devices` VALUES ('d1','LegacyCLI','CLI','DEV-1','v1:enc','jhgw_1','CHAT,MODELS','LOCAL_NETWORK',3,1,0,NULL,1000)")
        s.execSQL("INSERT INTO `request_metrics` VALUES ('rm1','req1',2000,'p1','Legacy Provider','gpt-4o-mini','LegacyCLI','/v1/chat/completions',200,1,42,10,20,NULL,0,'AUTOMATIC','',NULL,NULL)")
    }
}
