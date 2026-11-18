package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.i18n.AppLanguage
import com.example.core.security.AppLockManager
import com.example.core.security.CryptoManager
import com.example.data.local.*
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
class BackupMigrationCompletenessTest {

    private lateinit var context: Context
    private lateinit var db: JhGetWayDatabase
    private lateinit var prefs: AppPreferences
    private lateinit var backupManager: BackupManager

    @Before
    fun setUp() {
        CryptoManager.resetTestKeyProvider()
        context = ApplicationProvider.getApplicationContext()
        runBlocking {
            AppPreferences(context).clearAll()
        }
        prefs = AppPreferences(context)
        db = Room.inMemoryDatabaseBuilder(context, JhGetWayDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        backupManager = BackupManager(
            db = db,
            prefs = prefs,
            appVersionName = "1.0.11",
            appVersionCode = 111,
            applicationId = "com.aistudio.jhgetway.iffndf"
        )
    }

    @After
    fun tearDown() {
        runBlocking { prefs.clearAll() }
        db.close()
        CryptoManager.resetTestKeyProvider()
    }

    @Test
    fun exportAndRestoreAcrossFreshKeystore_migratesAllCategoriesAndExcludesAppLock() = runBlocking {
        // 1. Configure preferences + App Lock + Gateway remote token
        prefs.setLanguage(AppLanguage.BENGALI)
        prefs.setThemeMode(ThemeMode.DARK)
        prefs.setOnboardingCompleted(true)
        prefs.updateRoutingSettings(RoutingMode.AUTO_FREE, fallbackEnabled = true, maxRetries = 4)
        prefs.setAutoFreeIncludeLocal(true)
        prefs.setManualModelFallbackEnabled(true)
        prefs.updateSmartAutoPreferences("best-coding", autoAllowPaidModels = false, preferConfiguredDefaultInAuto = false)
        prefs.setGithubUpdateRepo("reviewtopperjhanik-a11y/JH-GetWay-")
        prefs.setCurrency("BDT")
        prefs.setAppLock(true, AppLockManager().hashPin("2468"))
        prefs.setAppLockBiometricEnabled(true)
        prefs.updateGatewaySettings(
            name = "My Production Gateway",
            mode = GatewayMode.REMOTE,
            host = "127.0.0.1",
            port = 8799,
            remoteUrl = "https://gw.example.com",
            authRequired = true,
            timeoutSec = 90,
            maxRequestKb = 4096,
            streaming = true,
            rateLimitPerMin = 120,
            loggingLevel = LoggingLevel.STANDARD,
            allowLanExposure = false,
            remoteAuthTokenEnc = CryptoManager.encrypt("remote_secret_token_999")
        )

        // 2. Seed Room tables including Playground sessions/messages, workflow runs & metrics
        db.providerDao().upsert(ProviderEntity(id = "p1", name = "OpenRouter", baseUrl = "https://openrouter.ai/api/v1", priority = 1))
        db.apiKeyDao().upsert(
            ApiKeyEntity(id = "k1", providerId = "p1", label = "Primary", encryptedKey = CryptoManager.encrypt("sk-or-secret-123"), maskedPreview = "sk-••••23")
        )
        db.modelDao().upsert(
            ModelEntity(
                id = "m1",
                modelId = "google/gemini-2.0-flash:free",
                displayName = "Gemini Flash Free",
                providerId = "p1",
                providerName = "OpenRouter",
                accessTier = AccessTier.FREE_TIER,
                freeTierVerified = true,
                alias = "fast-free"
            )
        )
        db.routingRuleDao().upsert(RoutingRuleEntity(id = "r1", name = "Free Route", modelAlias = "fast-free", preferredProviderId = "p1"))
        db.codingAgentDao().upsert(
            CodingAgentEntity(id = "a1", name = "Codex", agentType = CodingAgentType.CODEX, encryptedToken = CryptoManager.encrypt("jhgw_ag_abc"), tokenPreview = "jhgw_••••bc")
        )
        db.localAiServerDao().upsert(
            LocalAiServerEntity(id = "l1", name = "Ollama", engineType = LocalEngineType.OLLAMA, serverUrl = "http://127.0.0.1:11434", encryptedAuthToken = CryptoManager.encrypt("local-auth"), hasAuth = true)
        )
        db.mcpDao().upsertServer(
            McpServerEntity(id = "ms1", name = "Docs", transportType = McpTransportType.STREAMABLE_HTTP, endpointOrCommand = "https://mcp.example.com", encryptedEnvJson = CryptoManager.encrypt("""{"API_ENV":"v"}"""), encryptedAuthToken = CryptoManager.encrypt("mcp-tok"))
        )
        db.mcpDao().upsertTool(McpToolEntity(id = "mt1", serverId = "ms1", toolName = "search", permission = ToolPermission.ALLOWED))
        db.workflowDao().upsertWorkflow(WorkflowEntity(id = "w1", name = "Flow 1", nodesJson = """[{"id":"n1"}]""", edgesJson = """[]""", isTemplate = false))
        db.workflowDao().upsertRun(WorkflowRunEntity(id = "wr1", workflowId = "w1", workflowName = "Flow 1", status = "COMPLETED", inputText = "hello", finalOutput = "world"))
        db.deviceDao().upsert(DeviceEntity(id = "d1", name = "Laptop", deviceType = "CLI", deviceCode = "L1", encryptedToken = CryptoManager.encrypt("jhgw_dev_xyz"), tokenPreview = "jhgw_••••yz"))
        db.playgroundDao().upsertSession(PlaygroundSessionEntity(id = "s1", title = "Migration Chat", modelId = "google/gemini-2.0-flash:free", providerId = "p1"))
        db.playgroundDao().insertMessage(
            PlaygroundMessageEntity(
                id = "msg1",
                sessionId = "s1",
                role = "user",
                content = "Check my diagram",
                attachmentsMetadataJson = """[{"id":"att1","displayName":"arch.png","mimeType":"image/png","sizeBytes":1024,"category":"IMAGE","localFilePath":"/data/user/0/old/arch.png"}]"""
            )
        )
        db.metricAndLogDao().insertMetric(
            RequestMetricEntity(id = "rm1", requestId = "req-1", providerId = "p1", providerName = "OpenRouter", modelId = "google/gemini-2.0-flash:free", inputTokens = 50, outputTokens = 120)
        )

        val passphrase = "MigrationBackupPass!2026"
        val archive = backupManager.exportEncryptedBackup(passphrase)
        assertFalse(archive.contains("sk-or-secret-123"))
        assertFalse(archive.contains("remote_secret_token_999"))

        // 3. Simulate uninstall + fresh install with a DIFFERENT Android Keystore master key!
        db.clearAllTables()
        prefs.clearAll()
        CryptoManager.resetTestKeyProvider()
        CryptoManager.setMasterKeyProviderForTest(CryptoManager.EphemeralTestMasterKeyProvider())

        val preview = backupManager.previewEncryptedBackup(archive, passphrase)
        assertTrue(preview.integrityVerified)
        assertNotNull(preview.manifest)
        assertEquals("1.0.11", preview.manifest!!.appVersionName)
        assertEquals(111, preview.manifest!!.appVersionCode)
        assertEquals("com.aistudio.jhgetway.iffndf", preview.manifest!!.applicationId)
        assertTrue("preferences" in preview.manifest!!.includedCategories)
        assertTrue("playground_sessions" in preview.manifest!!.includedCategories)
        assertTrue("app_lock_pin_and_biometric" in preview.manifest!!.nonPortableCategories)
        assertEquals(15, preview.counts.total)
        assertEquals(0, preview.conflicts.totalConflicts)

        val restoreResult = backupManager.restoreEncryptedBackupDetailed(archive, passphrase, RestoreConflictStrategy.OVERWRITE_EXISTING)
        assertEquals(15, restoreResult.importedCount)
        assertTrue(restoreResult.preferencesRestored)

        // Verify all credentials decrypted & re-encrypted under NEW Keystore master key
        assertEquals("sk-or-secret-123", CryptoManager.decrypt(db.apiKeyDao().getAllOnce().single().encryptedKey))
        assertEquals("jhgw_ag_abc", CryptoManager.decrypt(db.codingAgentDao().getAllOnce().single().encryptedToken))
        assertEquals("local-auth", CryptoManager.decrypt(db.localAiServerDao().getAllOnce().single().encryptedAuthToken))
        assertEquals("mcp-tok", CryptoManager.decrypt(db.mcpDao().getServersOnce().single().encryptedAuthToken))
        assertEquals("jhgw_dev_xyz", CryptoManager.decrypt(db.deviceDao().getAllOnce().single().encryptedToken))

        // Verify Playground chat & attachment path sanitization
        val restoredSessions = db.playgroundDao().getSessionsOnce()
        assertEquals(1, restoredSessions.size)
        assertEquals("Migration Chat", restoredSessions.single().title)
        val restoredMsg = db.playgroundDao().getAllMessagesOnce().single()
        val parsedAtts = PlaygroundAttachmentManager.parseMetadataJson(restoredMsg.attachmentsMetadataJson)
        assertEquals("arch.png", parsedAtts.single().displayName)
        assertEquals("", parsedAtts.single().localFilePath)

        // Verify WorkflowRun + RequestMetrics
        assertEquals(1, db.workflowDao().getRunsOnce().size)
        assertEquals(1, db.metricAndLogDao().getMetricsOnce().size)

        // Verify portable preferences restored & App Lock stayed excluded (must be re-set)
        val restoredPrefs = prefs.settingsFlow.first()
        assertEquals(AppLanguage.BENGALI, restoredPrefs.language)
        assertEquals(ThemeMode.DARK, restoredPrefs.themeMode)
        assertTrue(restoredPrefs.onboardingCompleted)
        assertEquals(RoutingMode.AUTO_FREE, restoredPrefs.defaultRoutingMode)
        assertTrue(restoredPrefs.autoFreeIncludeLocal)
        assertTrue(restoredPrefs.manualModelFallbackEnabled)
        assertEquals("best-coding", restoredPrefs.openCodeSelectionMode)
        assertFalse(restoredPrefs.autoAllowPaidModels)
        assertFalse(restoredPrefs.preferConfiguredDefaultInAuto)
        assertEquals("reviewtopperjhanik-a11y/JH-GetWay-", restoredPrefs.githubUpdateRepo)
        assertEquals("BDT", restoredPrefs.analyticsCurrency)
        assertEquals("remote_secret_token_999", CryptoManager.decrypt(restoredPrefs.gatewayRemoteAuthTokenEnc))
        assertFalse("App lock must not be migrated blindly", restoredPrefs.appLockEnabled)
        assertEquals("", restoredPrefs.appLockPinHash)
        assertFalse(restoredPrefs.appLockBiometricEnabled)
    }

    @Test
    fun backupRestore_rejectsArchiveFromDifferentApplicationId() = runBlocking {
        val otherAppBackupManager = BackupManager(
            db = db,
            prefs = prefs,
            appVersionName = "1.0.11",
            appVersionCode = 111,
            applicationId = "com.other.untrusted.app"
        )
        val archive = otherAppBackupManager.exportEncryptedBackup("StrongPassphrase!123")
        val err = runCatching {
            backupManager.previewEncryptedBackup(archive, "StrongPassphrase!123")
        }.exceptionOrNull()
        assertTrue(err is IllegalArgumentException)
        assertTrue(err!!.message!!.contains("application ID"))
    }

    @Test
    fun releaseWorkflow_runsTestsOnMainAndGatesReleasesToTagsOrManualDispatch() {
        val workflowFile = listOf(
            File(".github/workflows/android-release.yml"),
            File("../.github/workflows/android-release.yml")
        ).firstOrNull { it.exists() }
        assertNotNull("Expected android-release.yml to exist", workflowFile)
        val yml = workflowFile!!.readText()

        assertFalse("Workflow must never reference ci-ephemeral", yml.contains("ci-ephemeral"))
        assertTrue("Workflow must trigger CI tests on push to main", yml.contains("- main"))
        assertTrue("Workflow must trigger on version tags", yml.contains("- 'v*'"))
        assertTrue("Workflow must restrict top-level permissions", yml.contains("permissions: {}"))
        assertTrue("Workflow must enforce expected applicationId", yml.contains("com.aistudio.jhgetway.iffndf"))
        assertTrue("Workflow must verify APK signature via apksigner", yml.contains("apksigner"))
        assertTrue("Workflow must generate update-manifest.json", yml.contains("update-manifest.json"))
        assertTrue("Workflow must generate SHA256SUMS.txt", yml.contains("SHA256SUMS.txt"))
        assertTrue(
            "Release job must be gated to v* tag pushes or workflow_dispatch",
            yml.contains("github.ref_type == 'tag'") && yml.contains("github.event_name == 'workflow_dispatch'")
        )
    }
}
