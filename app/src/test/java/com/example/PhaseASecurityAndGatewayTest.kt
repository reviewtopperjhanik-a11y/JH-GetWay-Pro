package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.security.CryptoFailureException
import com.example.core.security.CryptoFailureReason
import com.example.core.security.CryptoManager
import com.example.core.security.MasterKeyProvider
import com.example.core.security.NetworkAddressClassifier
import com.example.data.local.DeviceConnectionType
import com.example.data.local.DeviceEntity
import com.example.data.local.GatewayMode
import com.example.data.local.GatewaySettingsState
import com.example.data.local.JhGetWayDatabase
import com.example.gateway.GatewayRunStatus
import com.example.gateway.LocalGatewayHttpServer
import com.example.gateway.RemoteGatewayHealthChecker
import com.example.gateway.RemoteHealthResult
import com.example.gateway.SmartRouterEngine
import com.example.network.ProviderHttpClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKey

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PhaseASecurityAndGatewayTest {

    private lateinit var app: JhGetWayApplication
    private lateinit var db: JhGetWayDatabase
    private val serversToStop = mutableListOf<LocalGatewayHttpServer>()

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
        runBlocking {
            serversToStop.forEach { srv ->
                runCatching { srv.stop() }
            }
            serversToStop.clear()
        }
        db.close()
        CryptoManager.resetTestKeyProvider()
    }

    // -------------------------------------------------------------------------
    // P0.1: Credential Encryption Fail-Closed Tests
    // -------------------------------------------------------------------------

    @Test
    fun cryptoManager_encryptDecryptAndMetadata_succeedsWithVersionPrefix() {
        val secret = "sk-jhgetway-live-secret-987654"
        val encrypted = CryptoManager.encrypt(secret)
        assertTrue(encrypted.startsWith("v1:"))
        assertFalse(encrypted.contains(secret))

        val meta = CryptoManager.parseMetadata(encrypted)
        assertNotNull(meta)
        assertEquals("v1", meta?.version)
        assertEquals("AES/GCM/NoPadding", meta?.algorithm)

        assertEquals(secret, CryptoManager.decrypt(encrypted))
        assertEquals(secret, CryptoManager.decryptResult(encrypted).getOrNull())
    }

    @Test
    fun cryptoManager_failsClosedWhenKeystoreUnavailable() {
        CryptoManager.setMasterKeyProviderForTest(object : MasterKeyProvider {
            override fun getOrCreateKey(): SecretKey {
                throw CryptoFailureException(
                    CryptoFailureReason.KEYSTORE_UNAVAILABLE,
                    "Android Keystore unavailable; refusing weak fallback"
                )
            }

            override fun deleteKeyIfExists() = Unit
        })

        val encErr = assertThrows(CryptoFailureException::class.java) {
            CryptoManager.encrypt("sk-secret-123456")
        }
        assertEquals(CryptoFailureReason.KEYSTORE_UNAVAILABLE, encErr.reason)

        val decResult = CryptoManager.decryptResult("v1:AAAAAAAAAAAAAAAAAAAAAAAA")
        assertTrue(decResult.isFailure)
        val decErr = decResult.exceptionOrNull() as? CryptoFailureException
        assertNotNull(decErr)
    }

    @Test
    fun cryptoManager_throwsTypedExceptionOnKeyInvalidationAndSupportsSafeReset() {
        var invalidated = true
        val fallbackTestProvider = CryptoManager.EphemeralTestMasterKeyProvider()
        CryptoManager.setMasterKeyProviderForTest(object : MasterKeyProvider {
            override fun getOrCreateKey(): SecretKey {
                if (invalidated) {
                    throw CryptoFailureException(
                        CryptoFailureReason.KEY_INVALIDATED,
                        "Vault master key permanently invalidated"
                    )
                }
                return fallbackTestProvider.getOrCreateKey()
            }

            override fun deleteKeyIfExists() {
                invalidated = false
                fallbackTestProvider.deleteKeyIfExists()
            }
        })

        val err = assertThrows(CryptoFailureException::class.java) {
            CryptoManager.encrypt("sk-test-token")
        }
        assertEquals(CryptoFailureReason.KEY_INVALIDATED, err.reason)

        assertTrue(CryptoManager.resetInvalidatedMasterKey().isSuccess)
        val reEncrypted = CryptoManager.encrypt("sk-test-token")
        assertEquals("sk-test-token", CryptoManager.decrypt(reEncrypted))
    }

    @Test
    fun cryptoManager_rejectsTamperedCorruptedAndUnsupportedCiphertext() {
        val providerA = CryptoManager.EphemeralTestMasterKeyProvider()
        val providerB = CryptoManager.EphemeralTestMasterKeyProvider()

        CryptoManager.setMasterKeyProviderForTest(providerA)
        val cipherText = CryptoManager.encrypt("sk-sensitive-api-key")

        // Different random key must fail GCM tag verification (never silently return garbage)
        CryptoManager.setMasterKeyProviderForTest(providerB)
        val wrongKeyErr = assertThrows(CryptoFailureException::class.java) {
            CryptoManager.decrypt(cipherText)
        }
        assertEquals(CryptoFailureReason.AUTHENTICATION_FAILED, wrongKeyErr.reason)

        // Unsupported version prefix
        val formatErr = assertThrows(CryptoFailureException::class.java) {
            CryptoManager.decrypt("FALLBACK:ZmFrZQ==")
        }
        assertEquals(CryptoFailureReason.UNSUPPORTED_FORMAT, formatErr.reason)

        // Truncated / corrupted ciphertext
        val corruptedErr = assertThrows(CryptoFailureException::class.java) {
            CryptoManager.decrypt("v1:YWJj")
        }
        assertEquals(CryptoFailureReason.CORRUPTED_CIPHERTEXT, corruptedErr.reason)
    }

    // -------------------------------------------------------------------------
    // P0.2 & P0.3: Bind Classification, URL Validation & Remote State Semantics
    // -------------------------------------------------------------------------

    @Test
    fun networkClassifier_enforcesLoopbackDefaultRejectsWildcardAndGatesLanExposure() {
        val loopback = NetworkAddressClassifier.validateLocalGatewayBindConfig("127.0.0.1", 8787, allowLanExposure = false)
        assertTrue(loopback.allowed)
        assertTrue(loopback.isLoopback)
        assertFalse(loopback.unencryptedLanWarning)

        val localhostAlias = NetworkAddressClassifier.validateLocalGatewayBindConfig("localhost", 8787, allowLanExposure = false)
        assertTrue(localhostAlias.allowed)
        assertEquals("127.0.0.1", localhostAlias.normalizedHost)

        val wildcardV4 = NetworkAddressClassifier.validateLocalGatewayBindConfig("0.0.0.0", 8787, allowLanExposure = true)
        assertFalse(wildcardV4.allowed)

        val wildcardV6 = NetworkAddressClassifier.validateLocalGatewayBindConfig("::", 8787, allowLanExposure = true)
        assertFalse(wildcardV6.allowed)

        val publicIp = NetworkAddressClassifier.validateLocalGatewayBindConfig("8.8.8.8", 8787, allowLanExposure = true)
        assertFalse(publicIp.allowed)

        val lanBlocked = NetworkAddressClassifier.validateLocalGatewayBindConfig("192.168.1.50", 8787, allowLanExposure = false)
        assertFalse(lanBlocked.allowed)

        val lanAllowed = NetworkAddressClassifier.validateLocalGatewayBindConfig("192.168.1.50", 8787, allowLanExposure = true)
        assertTrue(lanAllowed.allowed)
        assertFalse(lanAllowed.isLoopback)
        assertTrue(lanAllowed.unencryptedLanWarning)

        val privilegedPort = NetworkAddressClassifier.validateLocalGatewayBindConfig("127.0.0.1", 80, allowLanExposure = false)
        assertFalse(privilegedPort.allowed)
    }

    @Test
    fun networkClassifier_validatesRemoteHttpsUrlsAndRejectsEmbeddedCredentials() {
        assertTrue(NetworkAddressClassifier.validateRemoteGatewayUrl("https://gw.example.com/").valid)
        assertFalse(NetworkAddressClassifier.validateRemoteGatewayUrl("http://gw.example.com").valid)
        assertFalse(NetworkAddressClassifier.validateRemoteGatewayUrl("https://user:secret@gw.example.com").valid)
        assertFalse(NetworkAddressClassifier.isSecureOrAllowedLocalUrl("https://user:pass@api.openai.com/v1", allowLocalHttp = true))
    }

    @Test
    fun gatewayServer_remoteModeDoesNotStartLocalSocketAndReflectsHealthChecker() = runBlocking {
        var nextResult = RemoteHealthResult(status = GatewayRunStatus.REMOTE_CONNECTED, httpCode = 200)
        val fakeChecker = object : RemoteGatewayHealthChecker {
            override suspend fun checkHealth(url: String, bearerToken: String, timeoutSec: Int): RemoteHealthResult = nextResult
        }
        val server = createTestServer(fakeChecker)

        // Invalid HTTP remote URL must fail closed without pretending to be connected
        server.start(
            GatewaySettingsState(
                gatewayMode = GatewayMode.REMOTE,
                gatewayRemoteUrl = "http://remote.example.com"
            )
        )
        assertEquals(GatewayRunStatus.ERROR, server.runtimeState.value.status)
        assertFalse(server.runtimeState.value.isActive)

        // Healthy HTTPS remote gateway transitions to REMOTE_CONNECTED
        server.start(
            GatewaySettingsState(
                gatewayMode = GatewayMode.REMOTE,
                gatewayRemoteUrl = "https://gw.example.com"
            )
        )
        assertEquals(GatewayRunStatus.REMOTE_CONNECTED, server.runtimeState.value.status)
        assertEquals(200, server.runtimeState.value.lastHealthHttpCode)
        assertNotNull(server.runtimeState.value.lastHealthCheckAt)
        assertTrue(server.runtimeState.value.isActive)

        // Unavailable remote gateway reflects REMOTE_UNAVAILABLE
        nextResult = RemoteHealthResult(
            status = GatewayRunStatus.REMOTE_UNAVAILABLE,
            httpCode = 503,
            errorMessage = "Remote gateway returned HTTP 503."
        )
        server.refreshRemoteHealth(
            GatewaySettingsState(
                gatewayMode = GatewayMode.REMOTE,
                gatewayRemoteUrl = "https://gw.example.com"
            )
        )
        assertEquals(GatewayRunStatus.REMOTE_UNAVAILABLE, server.runtimeState.value.status)
        assertEquals(503, server.runtimeState.value.lastHealthHttpCode)
        assertFalse(server.runtimeState.value.isActive)
    }

    // -------------------------------------------------------------------------
    // P0.4 & P0.5: Ktor CIO Server, Streaming/Chunked, Limits, Auth & Honest 501
    // -------------------------------------------------------------------------

    @Test
    fun localGatewayServer_handlesHealthModelsAuthAnd501UnsupportedEndpoints() = runBlocking {
        val rawToken = "jhgw_dev_test_token_12345"
        db.deviceDao().upsert(
            DeviceEntity(
                name = "Test Client",
                deviceType = "CLI",
                deviceCode = "DEV-TEST01",
                encryptedToken = CryptoManager.encrypt(rawToken),
                tokenPreview = "jhgw_••••2345",
                connectionType = DeviceConnectionType.LOCAL_NETWORK
            )
        )

        val server = createTestServer()
        app.preferences.updateGatewaySettings(
            name = "Test Auth Gateway",
            mode = GatewayMode.LOCAL,
            host = "127.0.0.1",
            port = 0,
            remoteUrl = "",
            authRequired = true,
            timeoutSec = 60,
            maxRequestKb = 64,
            streaming = true,
            rateLimitPerMin = 60,
            loggingLevel = com.example.data.local.LoggingLevel.METADATA_ONLY,
            allowLanExposure = false,
            remoteAuthTokenEnc = ""
        )
        val settings = GatewaySettingsState(
            gatewayMode = GatewayMode.LOCAL,
            gatewayHost = "127.0.0.1",
            gatewayPort = 0,
            gatewayAuthRequired = true,
            gatewayMaxRequestKb = 64
        )
        server.start(settings)
        assertEquals(GatewayRunStatus.LOCAL_RUNNING, server.runtimeState.value.status)
        val baseUrl = server.runtimeState.value.boundAddress

        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        // 1. /health returns 200 and lists only supported endpoints
        client.newCall(Request.Builder().url("$baseUrl/health").get().build()).execute().use { resp ->
            assertEquals(200, resp.code)
            val json = JSONObject(resp.body!!.string())
            assertEquals("ok", json.getString("status"))
            val supported = json.getJSONArray("supported_endpoints")
            assertEquals(com.example.gateway.GatewayApiContract.supportedEndpoints.size, supported.length())
        }

        // 2. /v1/models without Bearer returns 401
        client.newCall(Request.Builder().url("$baseUrl/v1/models").get().build()).execute().use { resp ->
            assertEquals(401, resp.code)
        }

        // 3. /v1/models with valid Bearer returns 200
        client.newCall(
            Request.Builder()
                .url("$baseUrl/v1/models")
                .header("Authorization", "Bearer $rawToken")
                .get()
                .build()
        ).execute().use { resp ->
            assertEquals(200, resp.code)
            val json = JSONObject(resp.body!!.string())
            assertEquals("list", json.getString("object"))
        }

        // 4. Unsupported endpoints return honest HTTP 501 Not Implemented (not silent fake success)
        val unsupported = com.example.gateway.GatewayApiContract.unsupportedEndpoints
        for (ep in unsupported) {
            client.newCall(
                Request.Builder()
                    .url("$baseUrl$ep")
                    .header("Authorization", "Bearer $rawToken")
                    .post("{\"model\":\"x\"}".toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().use { resp ->
                assertEquals("Expected 501 for $ep", 501, resp.code)
                val bodyJson = JSONObject(resp.body!!.string())
                assertEquals("endpoint_not_supported", bodyJson.getJSONObject("error").getString("code"))
            }
        }
    }

    @Test
    fun localGatewayServer_handlesChunkedRequestsRejectsOversized413AndMalformedHttp() = runBlocking {
        val server = createTestServer()
        // Persist settings so Ktor route handler reads maxRequestKb = 2 (2048 bytes)
        app.preferences.updateGatewaySettings(
            name = "Test Gateway",
            mode = GatewayMode.LOCAL,
            host = "127.0.0.1",
            port = 0,
            remoteUrl = "",
            authRequired = false,
            timeoutSec = 30,
            maxRequestKb = 2,
            streaming = false,
            rateLimitPerMin = 120,
            loggingLevel = com.example.data.local.LoggingLevel.MINIMAL,
            allowLanExposure = false,
            remoteAuthTokenEnc = ""
        )
        server.start(
            GatewaySettingsState(
                gatewayMode = GatewayMode.LOCAL,
                gatewayHost = "127.0.0.1",
                gatewayPort = 0,
                gatewayAuthRequired = false,
                gatewayMaxRequestKb = 2
            )
        )
        assertEquals(GatewayRunStatus.LOCAL_RUNNING, server.runtimeState.value.status)
        val baseUrl = server.runtimeState.value.boundAddress
        val port = baseUrl.substringAfterLast(":").toInt()

        val client = OkHttpClient.Builder().build()

        // 1. Chunked transfer-encoding (contentLength() == -1) with invalid JSON -> parsed completely, returns 400 Invalid JSON
        val chunkedBody = object : RequestBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun writeTo(sink: BufferedSink) {
                sink.writeUtf8("{invalid_json")
            }
        }
        client.newCall(
            Request.Builder().url("$baseUrl/v1/chat/completions").post(chunkedBody).build()
        ).execute().use { resp ->
            assertEquals(400, resp.code)
            assertTrue(resp.body!!.string().contains("Invalid JSON"))
        }

        // 2. Oversized body (> 2KB) with known Content-Length -> rejected with 413 Payload Too Large
        val bigPayload = "x".repeat(4096)
        client.newCall(
            Request.Builder()
                .url("$baseUrl/v1/chat/completions")
                .post(bigPayload.toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().use { resp ->
            assertEquals(413, resp.code)
        }

        // 3. Oversized chunked body (> 2KB without Content-Length) -> rejected cleanly with 413
        val oversizedChunked = object : RequestBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun writeTo(sink: BufferedSink) {
                sink.writeUtf8("a".repeat(3500))
            }
        }
        client.newCall(
            Request.Builder().url("$baseUrl/v1/chat/completions").post(oversizedChunked).build()
        ).execute().use { resp ->
            assertEquals(413, resp.code)
        }

        // 4. Malformed raw HTTP request over raw Socket does not crash server; subsequent /health succeeds
        Socket("127.0.0.1", port).use { sock ->
            sock.soTimeout = 3000
            sock.getOutputStream().write("GARBAGE NOT HTTP\r\n\r\n".toByteArray())
            sock.getOutputStream().flush()
            val statusLine = BufferedReader(InputStreamReader(sock.getInputStream())).readLine()
            assertTrue("Expected HTTP 400 status line, got $statusLine", statusLine.orEmpty().contains("400"))
        }

        // 5. Concurrent requests complete and activeRequests drops back to 0
        val codes = (1..8).map {
            async {
                client.newCall(Request.Builder().url("$baseUrl/health").get().build()).execute().use { it.code }
            }
        }.awaitAll()
        assertTrue(codes.all { it == 200 })
        assertEquals(0, server.runtimeState.value.activeRequests)

        // 6. Stop releases port and transitions cleanly to STOPPED
        server.stop()
        assertEquals(GatewayRunStatus.STOPPED, server.runtimeState.value.status)
    }

    private fun createTestServer(
        healthChecker: RemoteGatewayHealthChecker? = null
    ): LocalGatewayHttpServer {
        val router = SmartRouterEngine(db, ProviderHttpClient())
        val srv = if (healthChecker != null) {
            LocalGatewayHttpServer(db, router, app.preferences, healthChecker)
        } else {
            LocalGatewayHttpServer(db, router, app.preferences)
        }
        serversToStop.add(srv)
        return srv
    }
}
