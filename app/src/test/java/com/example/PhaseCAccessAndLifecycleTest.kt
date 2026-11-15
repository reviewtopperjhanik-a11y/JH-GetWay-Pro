package com.example

import android.app.NotificationManager
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.core.security.AppLockManager
import com.example.core.security.CryptoManager
import com.example.core.security.DeviceScope
import com.example.core.security.DeviceScopePolicy
import com.example.core.security.LockoutState
import com.example.core.security.PinVerifyResult
import com.example.core.security.TokenUsageAccounting
import com.example.core.security.UsageSource
import com.example.data.local.*
import com.example.gateway.GatewayForegroundService
import com.example.gateway.GatewayRunStatus
import com.example.gateway.LocalGatewayHttpServer
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
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PhaseCAccessAndLifecycleTest {

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
        runBlocking {
            app.preferences.clearAll()
            app.gatewayServer.stop()
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            gatewayServers.forEach { runCatching { it.stop() } }
            gatewayServers.clear()
            app.gatewayServer.stop()
            app.preferences.clearAll()
        }
        upstreamServers.forEach { runCatching { it.stop(100, 300) } }
        upstreamServers.clear()
        db.close()
        CryptoManager.resetTestKeyProvider()
    }

    // -------------------------------------------------------------------------
    // Section 8: App Lock PBKDF2, Throttling, Timed Lockout, Auto-Lock & Upgrade
    // -------------------------------------------------------------------------

    @Test
    fun appLockManager_usesSaltedPbkdf2MigratesLegacySha256AndEnforcesTimedLockout() {
        var nowMs = 1_000_000L
        val mgr = AppLockManager(iterations = 10_000, clockMs = { nowMs })

        val hash1 = mgr.hashPin("2468")
        val hash2 = mgr.hashPin("2468")
        assertTrue(hash1.startsWith("pbkdf2_v1$10000$"))
        assertNotEquals("Same PIN must receive distinct random salts", hash1, hash2)

        // Wrong PIN does not verify and increments failure count without locking yet
        var lockout = LockoutState()
        repeat(4) { idx ->
            val r = mgr.verifyPin("1111", hash1, lockout)
            assertTrue(r is PinVerifyResult.InvalidPin)
            val inv = r as PinVerifyResult.InvalidPin
            assertEquals(idx + 1, inv.failedAttempts)
            assertEquals(0L, inv.lockedUntilEpochMs)
            lockout = LockoutState(inv.failedAttempts, inv.lockedUntilEpochMs)
        }

        // 5th consecutive failure triggers 30-second lockout
        val fifth = mgr.verifyPin("9999", hash1, lockout)
        assertTrue(fifth is PinVerifyResult.LockedOut)
        val locked5 = fifth as PinVerifyResult.LockedOut
        assertEquals(5, locked5.failedAttempts)
        assertEquals(30_000L, locked5.remainingCooldownMs)
        lockout = LockoutState(locked5.failedAttempts, locked5.lockedUntilEpochMs)

        // Even the CORRECT PIN must be refused while locked out
        nowMs += 10_000L
        val duringLockout = mgr.verifyPin("2468", hash1, lockout)
        assertTrue(duringLockout is PinVerifyResult.LockedOut)
        assertEquals(20_000L, (duringLockout as PinVerifyResult.LockedOut).remainingCooldownMs)

        // After cooldown expires, wrong PIN (6th failure) escalates to 60s lockout
        nowMs += 20_001L
        val sixth = mgr.verifyPin("0000", hash1, lockout)
        assertTrue(sixth is PinVerifyResult.LockedOut)
        assertEquals(6, (sixth as PinVerifyResult.LockedOut).failedAttempts)
        assertEquals(60_000L, sixth.remainingCooldownMs)
        lockout = LockoutState(sixth.failedAttempts, sixth.lockedUntilEpochMs)

        // After 60s expires, correct PIN succeeds cleanly
        nowMs += 60_001L
        val unlocked = mgr.verifyPin("2468", hash1, lockout)
        assertTrue(unlocked is PinVerifyResult.Success)
        assertNull((unlocked as PinVerifyResult.Success).upgradedHash)

        // Legacy unsalted SHA-256 verifies and emits a salted PBKDF2 upgrade hash
        val legacySha256 = MessageDigest.getInstance("SHA-256")
            .digest("1357".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val migrated = mgr.verifyPin("1357", legacySha256, LockoutState())
        assertTrue(migrated is PinVerifyResult.Success)
        val upgradedHash = (migrated as PinVerifyResult.Success).upgradedHash
        assertNotNull(upgradedHash)
        assertTrue(upgradedHash!!.startsWith("pbkdf2_v1$"))
        assertTrue(mgr.verifyPin("1357", upgradedHash, LockoutState()) is PinVerifyResult.Success)
    }

    @Test
    fun gatewayViewModel_persistsLockoutAcrossRestartAndAutoLocksOnBackground() = runBlocking {
        val vm1 = com.example.ui.GatewayViewModel(app)
        vm1.setAppLockPin(true, "4321")
        awaitMainCondition {
            val s = app.preferences.settingsFlow.first()
            s.appLockEnabled && s.appLockPinHash.startsWith("pbkdf2_v1$") && vm1.settings.value.appLockEnabled
        }

        // Trigger background auto-lock masking
        vm1.onAppBackgrounded()
        assertFalse(vm1.isAppUnlocked.value)

        // Submit 5 wrong PINs to trigger persistent lockout
        repeat(5) { attempt ->
            vm1.verifyUnlockPin("9999")
            awaitMainCondition {
                val st = vm1.appLockUiState.value
                !st.isVerifying && st.failedAttempts == attempt + 1
            }
        }
        assertTrue(vm1.appLockUiState.value.lockedUntilEpochMs > System.currentTimeMillis())

        // Recreate ViewModel (simulates process/activity restart) -> still locked and throttled
        val vm2 = com.example.ui.GatewayViewModel(app)
        awaitMainCondition {
            val st = vm2.appLockUiState.value
            st.failedAttempts == 5 && st.lockedUntilEpochMs > System.currentTimeMillis()
        }
        assertFalse(vm2.isAppUnlocked.value)

        // Reset lockout in prefs so we can verify successful PBKDF2 unlock
        app.preferences.setAppLockLockoutState(0, 0L)
        vm2.verifyUnlockPin("4321")
        awaitMainCondition {
            vm2.isAppUnlocked.value
        }
        assertTrue(vm2.isAppUnlocked.value)
        assertEquals(0, vm2.appLockUiState.value.failedAttempts)

        // Switching gateway mode away from LOCAL stops any desired running foreground service
        app.preferences.setGatewayMode(GatewayMode.LOCAL)
        app.preferences.setGatewayServiceDesiredRunning(true)
        vm2.setGatewayMode(GatewayMode.REMOTE)
        awaitMainCondition {
            val s = app.preferences.settingsFlow.first()
            s.gatewayMode == GatewayMode.REMOTE && !s.gatewayServiceDesiredRunning
        }
        assertFalse(app.preferences.settingsFlow.first().gatewayServiceDesiredRunning)
    }

    @Test
    fun gatewayViewModel_blocksBiometricWhileLockedOutAndInitializesLockedOnColdStart() = runBlocking {
        val vmSetup = com.example.ui.GatewayViewModel(app)
        vmSetup.setAppLockPin(true, "5678")
        awaitMainCondition {
            app.preferences.settingsFlow.first().appLockEnabled && vmSetup.isInitialSettingsLoaded.value
        }
        app.preferences.setAppLockBiometricEnabled(true)
        val futureLockout = System.currentTimeMillis() + 60_000L
        app.preferences.setAppLockLockoutState(5, futureLockout)

        // Cold-start ViewModel loads locked state and waits for initialization before unlocking
        val vmCold = com.example.ui.GatewayViewModel(app)
        awaitMainCondition {
            vmCold.isInitialSettingsLoaded.value
        }
        assertFalse(vmCold.isAppUnlocked.value)
        assertEquals(5, vmCold.appLockUiState.value.failedAttempts)

        // Biometric convenience must NOT bypass an active timed lockout
        vmCold.unlockWithBiometric()
        awaitMainCondition {
            vmCold.appLockUiState.value.isInvalid
        }
        assertFalse(vmCold.isAppUnlocked.value)

        // Once lockout expires/clears, biometric unlock succeeds
        app.preferences.setAppLockLockoutState(0, 0L)
        vmCold.unlockWithBiometric()
        awaitMainCondition {
            vmCold.isAppUnlocked.value
        }
        assertTrue(vmCold.isAppUnlocked.value)
    }

    private suspend fun awaitMainCondition(timeoutMs: Long = 8000, condition: suspend () -> Boolean) {
        withTimeout(timeoutMs) {
            while (true) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                if (condition()) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    break
                }
                delay(15)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Section 7a: Device Scopes (Default-Deny), Model Allowlists & Revocation
    // -------------------------------------------------------------------------

    @Test
    fun localGateway_enforcesDefaultDenyScopesModelAllowlistsAndImmediateRevocation() = runBlocking {
        val upstreamPort = startFakeOpenAiUpstream(reportUsage = true)
        seedOpenAiProvider(upstreamPort, modelIds = listOf("gpt-4o-mini", "gpt-4o"))

        val noneToken = "jhgw_dev_none_0001"
        val modelsOnlyToken = "jhgw_dev_models_0002"
        val chatLimitedToken = "jhgw_dev_chat_limited_0003"
        val adminToken = "jhgw_dev_admin_0004"

        val noneDevice = DeviceEntity(
            name = "NoScopeDevice",
            deviceType = "CLI",
            deviceCode = "DEV-01",
            encryptedToken = CryptoManager.encrypt(noneToken),
            tokenPreview = "jhgw_••••0001",
            permissionsCsv = "" // Default deny!
        )
        val modelsOnlyDevice = DeviceEntity(
            name = "CatalogReader",
            deviceType = "Monitor",
            deviceCode = "DEV-02",
            encryptedToken = CryptoManager.encrypt(modelsOnlyToken),
            tokenPreview = "jhgw_••••0002",
            permissionsCsv = "MODELS_READ"
        )
        val chatLimitedDevice = DeviceEntity(
            name = "ScopedChatBot",
            deviceType = "Editor",
            deviceCode = "DEV-03",
            encryptedToken = CryptoManager.encrypt(chatLimitedToken),
            tokenPreview = "jhgw_••••0003",
            permissionsCsv = "CHAT,MODELS_READ",
            allowedModelsCsv = "gpt-4o-mini"
        )
        val adminDevice = DeviceEntity(
            name = "AdminConsole",
            deviceType = "Desktop",
            deviceCode = "DEV-04",
            encryptedToken = CryptoManager.encrypt(adminToken),
            tokenPreview = "jhgw_••••0004",
            permissionsCsv = "ADMIN"
        )
        listOf(noneDevice, modelsOnlyDevice, chatLimitedDevice, adminDevice).forEach { db.deviceDao().upsert(it) }

        val baseUrl = startLocalGatewayServer()

        // 1. Default deny: empty permissions rejected on /v1/models & /v1/chat/completions
        //    EVEN when client sends fake permission escalation header!
        httpClient.newCall(
            Request.Builder()
                .url("$baseUrl/v1/models")
                .header("Authorization", "Bearer $noneToken")
                .header("X-Device-Permissions", "ADMIN")
                .get()
                .build()
        ).execute().use { resp ->
            assertEquals(403, resp.code)
            assertTrue(resp.body!!.string().contains("permission_denied"))
        }

        // 2. MODELS_READ-only device succeeds on /v1/models, denied (403) on /v1/chat/completions
        httpClient.newCall(
            Request.Builder()
                .url("$baseUrl/v1/models")
                .header("Authorization", "Bearer $modelsOnlyToken")
                .get()
                .build()
        ).execute().use { resp ->
            assertEquals(200, resp.code)
        }
        val chatPayload = """{"model":"gpt-4o-mini","messages":[{"role":"user","content":"Hi"}]}"""
            .toRequestBody("application/json".toMediaType())
        httpClient.newCall(
            Request.Builder()
                .url("$baseUrl/v1/chat/completions")
                .header("Authorization", "Bearer $modelsOnlyToken")
                .post(chatPayload)
                .build()
        ).execute().use { resp ->
            assertEquals(403, resp.code)
        }

        // 3. Model allowlist: filters /v1/models and rejects disallowed model gpt-4o with 403
        httpClient.newCall(
            Request.Builder()
                .url("$baseUrl/v1/models")
                .header("Authorization", "Bearer $chatLimitedToken")
                .get()
                .build()
        ).execute().use { resp ->
            assertEquals(200, resp.code)
            val data = JSONObject(resp.body!!.string()).getJSONArray("data")
            assertEquals(1, data.length())
            assertEquals("gpt-4o-mini", data.getJSONObject(0).getString("id"))
        }
        val disallowedPayload = """{"model":"gpt-4o","messages":[{"role":"user","content":"Hi"}]}"""
            .toRequestBody("application/json".toMediaType())
        httpClient.newCall(
            Request.Builder()
                .url("$baseUrl/v1/chat/completions")
                .header("Authorization", "Bearer $chatLimitedToken")
                .post(disallowedPayload)
                .build()
        ).execute().use { resp ->
            assertEquals(403, resp.code)
            assertTrue(resp.body!!.string().contains("model_not_allowed"))
        }
        // Allowed model succeeds
        httpClient.newCall(
            Request.Builder()
                .url("$baseUrl/v1/chat/completions")
                .header("Authorization", "Bearer $chatLimitedToken")
                .post(chatPayload)
                .build()
        ).execute().use { resp ->
            assertEquals(200, resp.code)
        }

        // 4. ADMIN grants all scopes and immediate revocation invalidates token on very next call
        httpClient.newCall(
            Request.Builder()
                .url("$baseUrl/v1/chat/completions")
                .header("Authorization", "Bearer $adminToken")
                .post(disallowedPayload)
                .build()
        ).execute().use { resp ->
            assertEquals(200, resp.code)
        }
        db.deviceDao().upsert(adminDevice.copy(revoked = true, enabled = false))
        httpClient.newCall(
            Request.Builder()
                .url("$baseUrl/v1/models")
                .header("Authorization", "Bearer $adminToken")
                .get()
                .build()
        ).execute().use { resp ->
            assertEquals(401, resp.code)
        }

        // 5. Audit logs contain PERMISSION_DENIED events and NEVER log raw bearer tokens
        val logs = db.metricAndLogDao().observeLogs().first()
        val deniedLogs = logs.filter { it.status == "PERMISSION_DENIED" }
        assertTrue(deniedLogs.size >= 3)
        val allLogText = logs.joinToString("\n") { "${it.relatedEntity}:${it.safeMessage}" }
        assertFalse(allLogText.contains(noneToken))
        assertFalse(allLogText.contains(modelsOnlyToken))
        assertFalse(allLogText.contains(chatLimitedToken))
        assertFalse(allLogText.contains(adminToken))
    }

    @Test
    fun localGateway_enforcesAllEightScopesOnProtectedAndCatchAllEndpoints() = runBlocking {
        assertNull(DeviceScopePolicy.requiredScopeForGatewayPath("/health", "GET"))
        assertEquals(DeviceScope.MODELS_READ, DeviceScopePolicy.requiredScopeForGatewayPath("/v1/models", "GET"))
        assertEquals(DeviceScope.CHAT, DeviceScopePolicy.requiredScopeForGatewayPath("/v1/chat/completions", "POST"))
        assertEquals(DeviceScope.WORKFLOWS_READ, DeviceScopePolicy.requiredScopeForGatewayPath("/v1/workflows", "GET"))
        assertEquals(DeviceScope.WORKFLOWS_RUN, DeviceScopePolicy.requiredScopeForGatewayPath("/v1/workflows/wf1/run", "POST"))
        assertEquals(DeviceScope.MCP_READ, DeviceScopePolicy.requiredScopeForGatewayPath("/v1/mcp/servers", "GET"))
        assertEquals(DeviceScope.MCP_EXECUTE, DeviceScopePolicy.requiredScopeForGatewayPath("/v1/mcp/tools/call", "POST"))
        assertEquals(DeviceScope.ANALYTICS_READ, DeviceScopePolicy.requiredScopeForGatewayPath("/v1/analytics/summary", "GET"))
        assertEquals(DeviceScope.ADMIN, DeviceScopePolicy.requiredScopeForGatewayPath("/admin/keys", "GET"))

        val limitedToken = "jhgw_dev_wf_read_9001"
        val adminToken = "jhgw_dev_admin_9002"
        db.deviceDao().upsert(
            DeviceEntity(
                name = "WorkflowReader",
                deviceType = "CLI",
                deviceCode = "DEV-WFR",
                encryptedToken = CryptoManager.encrypt(limitedToken),
                tokenPreview = "jhgw_••••9001",
                permissionsCsv = "WORKFLOWS_READ,MCP_READ"
            )
        )
        db.deviceDao().upsert(
            DeviceEntity(
                name = "AdminCatchAll",
                deviceType = "Desktop",
                deviceCode = "DEV-ADM",
                encryptedToken = CryptoManager.encrypt(adminToken),
                tokenPreview = "jhgw_••••9002",
                permissionsCsv = "ADMIN"
            )
        )

        val baseUrl = startLocalGatewayServer()
        val emptyPost = "{}".toRequestBody("application/json".toMediaType())

        // Unauthenticated request to protected catch-all path returns 401 (does not leak 404)
        httpClient.newCall(
            Request.Builder().url("$baseUrl/v1/workflows/wf1/run").post(emptyPost).build()
        ).execute().use { resp ->
            assertEquals(401, resp.code)
        }

        // Device with WORKFLOWS_READ+MCP_READ is denied (403) on WORKFLOWS_RUN, MCP_EXECUTE, ANALYTICS_READ, ADMIN
        httpClient.newCall(
            Request.Builder()
                .url("$baseUrl/v1/workflows/wf1/run")
                .header("Authorization", "Bearer $limitedToken")
                .post(emptyPost)
                .build()
        ).execute().use { resp ->
            assertEquals(403, resp.code)
            assertTrue(resp.body!!.string().contains("WORKFLOWS_RUN"))
        }

        httpClient.newCall(
            Request.Builder()
                .url("$baseUrl/v1/mcp/tools/call")
                .header("Authorization", "Bearer $limitedToken")
                .post(emptyPost)
                .build()
        ).execute().use { resp ->
            assertEquals(403, resp.code)
            assertTrue(resp.body!!.string().contains("MCP_EXECUTE"))
        }

        httpClient.newCall(
            Request.Builder()
                .url("$baseUrl/v1/analytics/summary")
                .header("Authorization", "Bearer $limitedToken")
                .get()
                .build()
        ).execute().use { resp ->
            assertEquals(403, resp.code)
            assertTrue(resp.body!!.string().contains("ANALYTICS_READ"))
        }

        // Device with WORKFLOWS_READ passes RBAC on /v1/workflows (receives 404 unimplemented endpoint, not 403)
        httpClient.newCall(
            Request.Builder()
                .url("$baseUrl/v1/workflows")
                .header("Authorization", "Bearer $limitedToken")
                .get()
                .build()
        ).execute().use { resp ->
            assertEquals(404, resp.code)
        }

        // ADMIN passes RBAC on /admin/keys
        httpClient.newCall(
            Request.Builder()
                .url("$baseUrl/admin/keys")
                .header("Authorization", "Bearer $adminToken")
                .get()
                .build()
        ).execute().use { resp ->
            assertEquals(404, resp.code)
        }
    }

    // -------------------------------------------------------------------------
    // Section 7b: Coding Agent Token Accounting, UTC Reset, Quota 429 & UsageSource
    // -------------------------------------------------------------------------

    @Test
    fun codingAgentAccounting_tracksActualVsEstimatedVsUnknownAtomicConcurrencyAndUtcReset() = runBlocking {
        // Unit check for UsageSource resolution (missing/failed usage is NEVER claimed as 0 ACTUAL)
        val unknownFailed = TokenUsageAccounting.resolveUsage(
            reportedInputTokens = 0,
            reportedOutputTokens = 0,
            usageReported = false,
            promptMessages = listOf(ChatMessagePayload("user", "Hello")),
            completionText = "",
            requestSucceeded = false
        )
        assertEquals(UsageSource.UNKNOWN, unknownFailed.source)
        assertEquals(0, unknownFailed.totalTokens)

        val estimated = TokenUsageAccounting.resolveUsage(
            reportedInputTokens = 0,
            reportedOutputTokens = 0,
            usageReported = false,
            promptMessages = listOf(ChatMessagePayload("user", "12345678")),
            completionText = "123456789012",
            requestSucceeded = true
        )
        assertEquals(UsageSource.ESTIMATED, estimated.source)
        assertEquals(2 + 3, estimated.totalTokens)

        // End-to-end gateway test: upstream reports 15+25=40 tokens per request, daily limit = 120
        val upstreamPort = startFakeOpenAiUpstream(reportUsage = true, inTokens = 15, outTokens = 25)
        seedOpenAiProvider(upstreamPort, modelIds = listOf("gpt-4o-mini"))

        val agentToken = "jhgw_ag_quota_test_777"
        val todayUtc = TokenUsageAccounting.utcDateString()
        val agent = CodingAgentEntity(
            name = "Codex Agent",
            agentType = CodingAgentType.CODEX,
            modelAlias = "gpt-4o-mini",
            dailyTokenLimit = 120L,
            tokensUsedToday = 0L,
            tokensResetDateUtc = todayUtc,
            encryptedToken = CryptoManager.encrypt(agentToken),
            tokenPreview = "jhgw_••••_777"
        )
        db.codingAgentDao().upsert(agent)

        val baseUrl = startLocalGatewayServer()
        val chatBodyJson = """{"model":"gpt-4o-mini","messages":[{"role":"user","content":"Write a function"}]}"""

        // 1. Run 2 concurrent non-streaming requests -> should atomically sum 40 + 40 = 80 ACTUAL tokens
        val results = (1..2).map {
            async {
                httpClient.newCall(
                    Request.Builder()
                        .url("$baseUrl/v1/chat/completions")
                        .header("Authorization", "Bearer $agentToken")
                        .post(chatBodyJson.toRequestBody("application/json".toMediaType()))
                        .build()
                ).execute().use { it.code }
            }
        }.awaitAll()
        assertEquals(listOf(200, 200), results)

        val afterConcurrent = db.codingAgentDao().getById(agent.id)!!
        assertEquals(80L, afterConcurrent.tokensUsedToday)
        assertEquals("ACTUAL", afterConcurrent.lastUsageSource)

        // 2. Streaming SSE request reconciles usage -> 80 + 40 = 120 ACTUAL tokens
        val streamBodyJson = """{"model":"gpt-4o-mini","stream":true,"messages":[{"role":"user","content":"Stream it"}]}"""
        httpClient.newCall(
            Request.Builder()
                .url("$baseUrl/v1/chat/completions")
                .header("Authorization", "Bearer $agentToken")
                .post(streamBodyJson.toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().use { resp ->
            assertEquals(200, resp.code)
            val sse = resp.body!!.string()
            assertTrue(sse.contains("data: [DONE]"))
        }

        val afterStream = db.codingAgentDao().getById(agent.id)!!
        assertEquals(120L, afterStream.tokensUsedToday)
        assertEquals("ACTUAL", afterStream.lastUsageSource)

        // 3. Pre-flight daily quota check now blocks with HTTP 429 daily_token_limit_exceeded (NOT 401!)
        httpClient.newCall(
            Request.Builder()
                .url("$baseUrl/v1/chat/completions")
                .header("Authorization", "Bearer $agentToken")
                .post(chatBodyJson.toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().use { resp ->
            assertEquals(429, resp.code)
            assertTrue(resp.body!!.string().contains("daily_token_limit_exceeded"))
        }

        // 4. UTC day rollover resets counter automatically on next request
        db.codingAgentDao().upsert(afterStream.copy(tokensResetDateUtc = "1999-12-31"))
        httpClient.newCall(
            Request.Builder()
                .url("$baseUrl/v1/chat/completions")
                .header("Authorization", "Bearer $agentToken")
                .post(chatBodyJson.toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().use { resp ->
            assertEquals(200, resp.code)
        }
        val afterReset = db.codingAgentDao().getById(agent.id)!!
        assertEquals(todayUtc, afterReset.tokensResetDateUtc)
        assertEquals(40L, afterReset.tokensUsedToday)

        // Metrics also recorded UsageSource = ACTUAL
        val metrics = db.metricAndLogDao().getMetricsOnce()
        assertTrue(metrics.isNotEmpty())
        assertTrue(metrics.all { it.usageSource == "ACTUAL" })
    }

    @Test
    fun codingAgentAccounting_recordsEstimatedWhenUpstreamOmitsUsage() = runBlocking {
        val upstreamPort = startFakeOpenAiUpstream(reportUsage = false)
        seedOpenAiProvider(upstreamPort, modelIds = listOf("gpt-4o-mini"))

        val agentToken = "jhgw_ag_est_test_888"
        val agent = CodingAgentEntity(
            name = "EstimatedAgent",
            agentType = CodingAgentType.OPENCODE,
            modelAlias = "gpt-4o-mini",
            tokensResetDateUtc = TokenUsageAccounting.utcDateString(),
            encryptedToken = CryptoManager.encrypt(agentToken),
            tokenPreview = "jhgw_••••_888"
        )
        db.codingAgentDao().upsert(agent)

        val baseUrl = startLocalGatewayServer()
        val body = """{"model":"gpt-4o-mini","messages":[{"role":"user","content":"Hello estimation world!"}]}"""
        httpClient.newCall(
            Request.Builder()
                .url("$baseUrl/v1/chat/completions")
                .header("Authorization", "Bearer $agentToken")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().use { resp ->
            assertEquals(200, resp.code)
        }

        val updated = db.codingAgentDao().getById(agent.id)!!
        assertEquals("ESTIMATED", updated.lastUsageSource)
        assertTrue("Missing provider usage should compute non-zero heuristic estimate", updated.tokensUsedToday > 0L)
    }

    // -------------------------------------------------------------------------
    // Section 6: Gateway Lifecycle, Port Conflicts & Foreground Service
    // -------------------------------------------------------------------------

    @Test
    fun localGateway_preventsDuplicatesHandlesPortConflictAndReleasesSocketOnStop() = runBlocking {
        // Hold an ephemeral port first to provoke deterministic BindException
        val occupiedSocket = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val conflictPort = occupiedSocket.localPort
        try {
            val server = LocalGatewayHttpServer(db, SmartRouterEngine(db, ProviderHttpClient()), app.preferences)
            gatewayServers.add(server)
            val conflictSettings = GatewaySettingsState(
                gatewayMode = GatewayMode.LOCAL,
                gatewayHost = "127.0.0.1",
                gatewayPort = conflictPort,
                gatewayAuthRequired = false
            )
            server.start(conflictSettings)
            assertEquals(GatewayRunStatus.ERROR, server.runtimeState.value.status)
            assertTrue(server.runtimeState.value.lastError.orEmpty().contains("already in use"))

            // Release conflicting socket, then start succeeds and duplicate start() is idempotent
            occupiedSocket.close()
            server.start(conflictSettings)
            assertEquals(GatewayRunStatus.LOCAL_RUNNING, server.runtimeState.value.status)
            val firstAddr = server.runtimeState.value.boundAddress

            server.start(conflictSettings)
            assertEquals(GatewayRunStatus.LOCAL_RUNNING, server.runtimeState.value.status)
            assertEquals(firstAddr, server.runtimeState.value.boundAddress)

            // Stop releases socket so a brand new ServerSocket can immediately bind the same port
            server.stop()
            assertEquals(GatewayRunStatus.STOPPED, server.runtimeState.value.status)
            ServerSocket(conflictPort, 1, java.net.InetAddress.getByName("127.0.0.1")).use { rebound ->
                assertEquals(conflictPort, rebound.localPort)
            }
        } finally {
            runCatching { occupiedSocket.close() }
        }
    }

    @Test
    fun gatewayForegroundService_handlesStartRestartStopAndProcessRecreationSafely() = runBlocking {
        app.preferences.updateGatewaySettings(
            name = "Service Gateway",
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

        val serviceController = Robolectric.buildService(
            GatewayForegroundService::class.java,
            GatewayForegroundService.startGatewayIntent(app)
        ).create()

        // 1. ACTION_START promotes foreground notification and starts singleton server
        serviceController.startCommand(0, 1)
        val runningState = withTimeout(5000) {
            app.gatewayServer.runtimeState.first { it.status == GatewayRunStatus.LOCAL_RUNNING }
        }
        assertTrue(runningState.boundAddress.startsWith("http://127.0.0.1:"))
        assertTrue(app.preferences.settingsFlow.first().gatewayServiceDesiredRunning)

        val shadowService = Shadows.shadowOf(serviceController.get())
        assertNotNull(shadowService.lastForegroundNotification)
        val notifActions = shadowService.lastForegroundNotification.actions.map { it.title.toString() }
        assertTrue("Foreground notification must include Stop & Restart actions", notifActions.containsAll(listOf("Stop", "Restart")))

        // Verify /health responds on the bound port
        httpClient.newCall(Request.Builder().url("${runningState.boundAddress}/health").get().build()).execute().use { resp ->
            assertEquals(200, resp.code)
        }

        // 2. ACTION_RESTART cleanly restarts listener without leaving duplicates
        Robolectric.buildService(
            GatewayForegroundService::class.java,
            GatewayForegroundService.restartGatewayIntent(app)
        ).create().startCommand(0, 2)
        val restartedState = withTimeout(5000) {
            app.gatewayServer.runtimeState.first { it.status == GatewayRunStatus.LOCAL_RUNNING }
        }
        httpClient.newCall(Request.Builder().url("${restartedState.boundAddress}/health").get().build()).execute().use { resp ->
            assertEquals(200, resp.code)
        }

        // 3. Process death / START_STICKY recreation (null intent) when desiredRunning == true keeps/restores listener
        app.gatewayServer.stop()
        Robolectric.buildService(GatewayForegroundService::class.java, null)
            .create()
            .startCommand(0, 3)
        withTimeout(5000) {
            app.gatewayServer.runtimeState.first { it.status == GatewayRunStatus.LOCAL_RUNNING }
        }

        // 4. ACTION_STOP shuts down server cleanly, clears desiredRunning, and clears foreground state
        val stopController = Robolectric.buildService(
            GatewayForegroundService::class.java,
            GatewayForegroundService.stopGatewayIntent(app)
        ).create()
        stopController.startCommand(0, 4)
        awaitMainCondition {
            app.gatewayServer.runtimeState.value.status == GatewayRunStatus.STOPPED &&
                !app.preferences.settingsFlow.first().gatewayServiceDesiredRunning &&
                Shadows.shadowOf(stopController.get()).isStoppedBySelf
        }
        assertFalse(app.preferences.settingsFlow.first().gatewayServiceDesiredRunning)
        assertTrue(Shadows.shadowOf(stopController.get()).isStoppedBySelf)
        val nm = app.getSystemService(NotificationManager::class.java)
        assertNotNull(Shadows.shadowOf(nm))
    }

    // -------------------------------------------------------------------------
    // Section 9 preview: Room Non-Destructive MIGRATION_1_2
    // -------------------------------------------------------------------------

    @Test
    fun roomMigration1To2_preservesDataAndMigratesLegacyModelsScope() {
        val dbName = "migration_1_2_test.db"
        app.deleteDatabase(dbName)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(app)
                .name(dbName)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `devices` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `deviceType` TEXT NOT NULL, `deviceCode` TEXT NOT NULL, `encryptedToken` TEXT NOT NULL, `tokenPreview` TEXT NOT NULL, `permissionsCsv` TEXT NOT NULL, `connectionType` TEXT NOT NULL, `requestCount` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `revoked` INTEGER NOT NULL, `lastActiveAt` INTEGER, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `coding_agents` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `agentType` TEXT NOT NULL, `modelAlias` TEXT NOT NULL, `defaultModelId` TEXT NOT NULL, `allowedModelsCsv` TEXT NOT NULL, `streaming` INTEGER NOT NULL, `toolCalling` INTEGER NOT NULL, `dailyTokenLimit` INTEGER, `tokensUsedToday` INTEGER NOT NULL, `requestCount` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `encryptedToken` TEXT NOT NULL, `tokenPreview` TEXT NOT NULL, `lastActiveAt` INTEGER, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `request_metrics` (`id` TEXT NOT NULL, `requestId` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `providerId` TEXT NOT NULL, `providerName` TEXT NOT NULL, `modelId` TEXT NOT NULL, `deviceName` TEXT NOT NULL, `endpoint` TEXT NOT NULL, `statusCode` INTEGER NOT NULL, `success` INTEGER NOT NULL, `latencyMs` INTEGER NOT NULL, `inputTokens` INTEGER NOT NULL, `outputTokens` INTEGER NOT NULL, `estimatedCostUsd` REAL, `fallbackAttempts` INTEGER NOT NULL, `routingMode` TEXT NOT NULL, `routingReason` TEXT NOT NULL, `errorCode` TEXT, `safeErrorMessage` TEXT, PRIMARY KEY(`id`))"
                        )
                        db.execSQL(
                            "INSERT INTO `devices` VALUES ('d1','LegacyClient','CLI','DEV-1','v1:enc','jhgw_1','CHAT,MODELS','LOCAL_NETWORK',5,1,0,NULL,1000)"
                        )
                    }

                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) = Unit
                })
                .build()
        )
        val rawDb = helper.writableDatabase
        JhGetWayDatabase.MIGRATION_1_2.migrate(rawDb)

        rawDb.query("SELECT permissionsCsv, allowedModelsCsv FROM devices WHERE id = 'd1'").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals("CHAT,MODELS_READ", cur.getString(0))
            assertEquals("", cur.getString(1))
        }
        rawDb.query("SELECT tokensResetDateUtc, lastUsageSource FROM coding_agents").use { cur ->
            assertEquals(2, cur.columnCount)
        }
        rawDb.query("SELECT usageSource FROM request_metrics").use { cur ->
            assertEquals(1, cur.columnCount)
        }
        rawDb.close()
        app.deleteDatabase(dbName)
    }

    // -------------------------------------------------------------------------
    // Test Helpers
    // -------------------------------------------------------------------------

    private suspend fun startLocalGatewayServer(): String {
        app.preferences.updateGatewaySettings(
            name = "PhaseC Gateway",
            mode = GatewayMode.LOCAL,
            host = "127.0.0.1",
            port = 0,
            remoteUrl = "",
            authRequired = true,
            timeoutSec = 30,
            maxRequestKb = 256,
            streaming = true,
            rateLimitPerMin = 200,
            loggingLevel = LoggingLevel.METADATA_ONLY
        )
        app.preferences.updateLocalAiSettings(
            policy = LocalRoutingPolicy.CLOUD_FIRST,
            allowCloudFallback = true,
            localNetworkAllowed = true
        )
        val server = LocalGatewayHttpServer(
            db = db,
            router = SmartRouterEngine(db, ProviderHttpClient()),
            preferences = app.preferences
        )
        gatewayServers.add(server)
        server.start(
            GatewaySettingsState(
                gatewayMode = GatewayMode.LOCAL,
                gatewayHost = "127.0.0.1",
                gatewayPort = 0,
                gatewayAuthRequired = true,
                gatewayStreamingEnabled = true,
                gatewayRateLimitPerMin = 200,
                localNetworkAccessAllowed = true
            )
        )
        assertEquals(GatewayRunStatus.LOCAL_RUNNING, server.runtimeState.value.status)
        return server.runtimeState.value.boundAddress
    }

    private suspend fun seedOpenAiProvider(port: Int, modelIds: List<String>) {
        val provider = ProviderEntity(
            id = "prov-test-1",
            name = "TestOpenAI",
            type = ProviderType.OPENAI_COMPATIBLE,
            baseUrl = "http://127.0.0.1:$port/v1",
            apiFormat = ApiFormat.OPENAI,
            defaultModel = modelIds.first(),
            priority = 1,
            enabled = true
        )
        db.providerDao().upsert(provider)
        db.apiKeyDao().upsert(
            ApiKeyEntity(
                providerId = provider.id,
                label = "Key1",
                encryptedKey = CryptoManager.encrypt("sk-upstream-secret"),
                maskedPreview = "sk-••••cret",
                isDefault = true,
                enabled = true
            )
        )
        modelIds.forEachIndexed { idx, mid ->
            db.modelDao().upsert(
                ModelEntity(
                    id = "${provider.id}::$mid",
                    modelId = mid,
                    displayName = mid,
                    providerId = provider.id,
                    providerName = provider.name,
                    supportsTools = true,
                    supportsStreaming = true,
                    isDefault = idx == 0,
                    enabled = true
                )
            )
        }
    }

    private fun startFakeOpenAiUpstream(
        reportUsage: Boolean,
        inTokens: Int = 10,
        outTokens: Int = 20
    ): Int {
        val srv = embeddedServer(CIO, configure = {
            connector {
                host = "127.0.0.1"
                port = 0
            }
        }) {
            routing {
                post("/v1/chat/completions") {
                    val reqJson = JSONObject(call.receiveText())
                    val isStream = reqJson.optBoolean("stream", false)
                    val model = reqJson.optString("model", "gpt-4o-mini")
                    if (!isStream) {
                        val resp = JSONObject()
                            .put("id", "chatcmpl-upstream")
                            .put("object", "chat.completion")
                            .put("model", model)
                            .put(
                                "choices",
                                JSONArray().put(
                                    JSONObject()
                                        .put("index", 0)
                                        .put("finish_reason", "stop")
                                        .put("message", JSONObject().put("role", "assistant").put("content", "Hello from upstream"))
                                )
                            )
                        if (reportUsage) {
                            resp.put(
                                "usage",
                                JSONObject()
                                    .put("prompt_tokens", inTokens)
                                    .put("completion_tokens", outTokens)
                                    .put("total_tokens", inTokens + outTokens)
                            )
                        }
                        call.respondText(resp.toString(), ContentType.Application.Json, HttpStatusCode.OK)
                    } else {
                        call.respondTextWriter(ContentType.Text.EventStream, HttpStatusCode.OK) {
                            write(
                                "data: " + JSONObject()
                                    .put("id", "chatcmpl-s1")
                                    .put("model", model)
                                    .put(
                                        "choices",
                                        JSONArray().put(
                                            JSONObject().put("index", 0)
                                                .put("delta", JSONObject().put("content", "Streamed reply"))
                                        )
                                    ).toString() + "\n\n"
                            )
                            val finalChunk = JSONObject()
                                .put("id", "chatcmpl-s1")
                                .put("model", model)
                                .put(
                                    "choices",
                                    JSONArray().put(
                                        JSONObject().put("index", 0)
                                            .put("delta", JSONObject())
                                            .put("finish_reason", "stop")
                                    )
                                )
                            if (reportUsage) {
                                finalChunk.put(
                                    "usage",
                                    JSONObject()
                                        .put("prompt_tokens", inTokens)
                                        .put("completion_tokens", outTokens)
                                        .put("total_tokens", inTokens + outTokens)
                                )
                            }
                            write("data: $finalChunk\n\n")
                            write("data: [DONE]\n\n")
                            flush()
                        }
                    }
                }
            }
        }
        srv.start(wait = false)
        upstreamServers.add(srv)
        return runBlocking { srv.engine.resolvedConnectors().first().port }
    }
}
