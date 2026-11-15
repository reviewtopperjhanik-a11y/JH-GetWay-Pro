package com.example.gateway

import com.example.core.security.CryptoManager
import com.example.core.security.DeviceScope
import com.example.core.security.DeviceScopePolicy
import com.example.core.security.MaskingUtils
import com.example.core.security.NetworkAddressClassifier
import com.example.core.security.ResolvedTokenUsage
import com.example.core.security.TokenUsageAccounting
import com.example.core.security.UsageSource
import com.example.data.local.*
import com.example.network.*
import com.example.network.adapters.NormalizedToolChoice
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException

enum class GatewayRunStatus {
    STOPPED,
    STARTING,
    LOCAL_RUNNING,
    REMOTE_CONNECTING,
    REMOTE_CONNECTED,
    REMOTE_UNAVAILABLE,
    ERROR
}

data class GatewayRuntimeState(
    val status: GatewayRunStatus = GatewayRunStatus.STOPPED,
    val mode: GatewayMode = GatewayMode.LOCAL,
    val boundAddress: String = "http://127.0.0.1:8787",
    val activeRequests: Int = 0,
    val lastError: String? = null,
    val lastHealthCheckAt: Long? = null,
    val lastHealthHttpCode: Int? = null,
    val lanWarningActive: Boolean = false
) {
    val isActive: Boolean
        get() = status == GatewayRunStatus.LOCAL_RUNNING || status == GatewayRunStatus.REMOTE_CONNECTED
}

data class RemoteHealthResult(
    val status: GatewayRunStatus,
    val httpCode: Int? = null,
    val errorMessage: String? = null,
    val checkedAt: Long = System.currentTimeMillis()
)

interface RemoteGatewayHealthChecker {
    suspend fun checkHealth(url: String, bearerToken: String, timeoutSec: Int): RemoteHealthResult
}

class OkHttpRemoteHealthChecker : RemoteGatewayHealthChecker {
    override suspend fun checkHealth(url: String, bearerToken: String, timeoutSec: Int): RemoteHealthResult =
        withContext(Dispatchers.IO) {
            val cleanBase = url.trim().trimEnd('/')
            val allowLoopbackOnly = NetworkAddressClassifier.isLoopbackAddress(NetworkAddressClassifier.parseHost(cleanBase).orEmpty())
            val sec = NetworkAddressClassifier.validateEndpointSecurity(cleanBase, allowLocalHttp = allowLoopbackOnly)
            if (!sec.allowed) {
                return@withContext RemoteHealthResult(
                    status = GatewayRunStatus.ERROR,
                    errorMessage = sec.errorMessage ?: "Unsafe remote gateway URL blocked by SSRF policy.",
                    checkedAt = System.currentTimeMillis()
                )
            }
            val client = OkHttpClient.Builder()
                .connectTimeout(timeoutSec.toLong().coerceIn(5, 60), TimeUnit.SECONDS)
                .readTimeout(timeoutSec.toLong().coerceIn(5, 60), TimeUnit.SECONDS)
                .dns(NetworkAddressClassifier.pinnedSafeDns(allowLocalHttp = allowLoopbackOnly))
                .followRedirects(false)
                .followSslRedirects(false)
                .build()

            val candidates = listOf("$cleanBase/health", "$cleanBase/v1/models")
            var lastFailure: RemoteHealthResult? = null

            for ((idx, targetUrl) in candidates.withIndex()) {
                val reqBuilder = Request.Builder().url(targetUrl).get()
                if (bearerToken.isNotBlank()) {
                    reqBuilder.header("Authorization", "Bearer $bearerToken")
                }
                try {
                    client.newCall(reqBuilder.build()).execute().use { resp ->
                        val checkedAt = System.currentTimeMillis()
                        when {
                            resp.isSuccessful -> {
                                return@withContext RemoteHealthResult(
                                    status = GatewayRunStatus.REMOTE_CONNECTED,
                                    httpCode = resp.code,
                                    errorMessage = null,
                                    checkedAt = checkedAt
                                )
                            }
                            resp.code == 401 || resp.code == 403 -> {
                                return@withContext RemoteHealthResult(
                                    status = GatewayRunStatus.ERROR,
                                    httpCode = resp.code,
                                    errorMessage = "Remote gateway authentication failed (HTTP ${resp.code}).",
                                    checkedAt = checkedAt
                                )
                            }
                            resp.code in 300..399 -> {
                                return@withContext RemoteHealthResult(
                                    status = GatewayRunStatus.ERROR,
                                    httpCode = resp.code,
                                    errorMessage = "Remote gateway returned redirect (HTTP ${resp.code}); automatic redirects are disabled for security.",
                                    checkedAt = checkedAt
                                )
                            }
                            resp.code == 404 && idx == 0 -> {
                                lastFailure = RemoteHealthResult(
                                    status = GatewayRunStatus.REMOTE_UNAVAILABLE,
                                    httpCode = resp.code,
                                    errorMessage = "Remote gateway endpoint returned HTTP 404.",
                                    checkedAt = checkedAt
                                )
                            }
                            else -> {
                                return@withContext RemoteHealthResult(
                                    status = GatewayRunStatus.REMOTE_UNAVAILABLE,
                                    httpCode = resp.code,
                                    errorMessage = "Remote gateway returned HTTP ${resp.code}.",
                                    checkedAt = checkedAt
                                )
                            }
                        }
                    }
                } catch (e: SSLException) {
                    return@withContext RemoteHealthResult(
                        status = GatewayRunStatus.ERROR,
                        errorMessage = "TLS/Certificate verification failed: ${MaskingUtils.sanitizeErrorMessage(e.message, listOf(bearerToken))}",
                        checkedAt = System.currentTimeMillis()
                    )
                } catch (e: SocketTimeoutException) {
                    return@withContext RemoteHealthResult(
                        status = GatewayRunStatus.REMOTE_UNAVAILABLE,
                        errorMessage = "Remote gateway health check timed out.",
                        checkedAt = System.currentTimeMillis()
                    )
                } catch (e: Exception) {
                    return@withContext RemoteHealthResult(
                        status = GatewayRunStatus.REMOTE_UNAVAILABLE,
                        errorMessage = MaskingUtils.sanitizeErrorMessage(e.message, listOf(bearerToken)),
                        checkedAt = System.currentTimeMillis()
                    )
                }
            }
            lastFailure ?: RemoteHealthResult(
                status = GatewayRunStatus.REMOTE_UNAVAILABLE,
                errorMessage = "Remote gateway health endpoint unreachable.",
                checkedAt = System.currentTimeMillis()
            )
        }
}

class LocalGatewayHttpServer(
    private val db: JhGetWayDatabase,
    private val router: SmartRouterEngine,
    private val preferences: AppPreferences,
    private val healthChecker: RemoteGatewayHealthChecker = OkHttpRemoteHealthChecker()
) {
    private val lifecycleMutex = Mutex()
    private val concurrencyLimiter = Semaphore(32)
    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val activeCount = AtomicInteger(0)
    private val rateWindow = ConcurrentHashMap<String, MutableList<Long>>()
    private val agentQuotaMutexes = ConcurrentHashMap<String, Mutex>()

    private val _runtimeState = MutableStateFlow(GatewayRuntimeState())
    val runtimeState: StateFlow<GatewayRuntimeState> = _runtimeState.asStateFlow()

    companion object {
        private const val MAX_TOTAL_HEADERS_BYTES = 32 * 1024
    }

    suspend fun start(settings: GatewaySettingsState) = lifecycleMutex.withLock {
        startLocked(settings)
    }

    suspend fun restart(settings: GatewaySettingsState) = lifecycleMutex.withLock {
        stopLocalEngineLocked()
        _runtimeState.update { it.copy(status = GatewayRunStatus.STOPPED, activeRequests = 0, lastError = null) }
        startLocked(settings)
    }

    private suspend fun startLocked(settings: GatewaySettingsState) {
        if (settings.gatewayMode == GatewayMode.REMOTE || settings.gatewayMode == GatewayMode.EXISTING) {
            stopLocalEngineLocked()
            connectRemoteLocked(settings)
            return
        }

        if (engine != null && _runtimeState.value.status == GatewayRunStatus.LOCAL_RUNNING) {
            return
        }

        val bindValidation = NetworkAddressClassifier.validateLocalGatewayBindConfig(
            hostInput = settings.gatewayHost,
            port = settings.gatewayPort,
            allowLanExposure = settings.gatewayAllowLanExposure
        )
        if (!bindValidation.allowed) {
            _runtimeState.value = GatewayRuntimeState(
                status = GatewayRunStatus.ERROR,
                mode = GatewayMode.LOCAL,
                boundAddress = "http://${settings.gatewayHost.ifBlank { "127.0.0.1" }}:${settings.gatewayPort}",
                activeRequests = 0,
                lastError = bindValidation.errorMessage,
                lanWarningActive = false
            )
            db.metricAndLogDao().insertLog(
                ActivityLogEntity(
                    category = ActivityCategory.SYSTEM_EVENT,
                    relatedEntity = settings.gatewayName,
                    status = "BIND_REJECTED",
                    safeMessage = bindValidation.errorMessage ?: "Unsafe gateway bind configuration rejected"
                )
            )
            return
        }

        _runtimeState.value = GatewayRuntimeState(
            status = GatewayRunStatus.STARTING,
            mode = GatewayMode.LOCAL,
            boundAddress = "http://${bindValidation.normalizedHost}:${bindValidation.normalizedPort}",
            activeRequests = 0,
            lastError = null,
            lanWarningActive = bindValidation.unencryptedLanWarning
        )

        withContext(Dispatchers.IO) {
            var createdServer: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
            try {
                if (bindValidation.normalizedPort > 0 &&
                    isPortAlreadyInUse(bindValidation.normalizedHost, bindValidation.normalizedPort)
                ) {
                    throw BindException("Address already in use: ${bindValidation.normalizedHost}:${bindValidation.normalizedPort}")
                }
                createdServer = embeddedServer(
                    CIO,
                    configure = {
                        connector {
                            host = bindValidation.normalizedHost
                            port = bindValidation.normalizedPort
                        }
                        connectionIdleTimeoutSeconds = settings.gatewayTimeoutSec.coerceIn(5, 300)
                        reuseAddress = true
                    }
                ) {
                    routing {
                        get("/health") {
                            handleWithConcurrencyLimit(call) { handleHealth(call) }
                        }
                        get("/v1/models") {
                            handleWithConcurrencyLimit(call) { handleAuthenticatedModels(call) }
                        }
                        post("/v1/chat/completions") {
                            handleWithConcurrencyLimit(call) { handleAuthenticatedChat(call) }
                        }
                        post("/v1/responses") {
                            handleWithConcurrencyLimit(call) { handleAuthenticatedResponses(call) }
                        }
                        post("/v1/messages") {
                            handleWithConcurrencyLimit(call) { handleAuthenticatedAnthropicMessages(call) }
                        }
                        GatewayApiContract.unsupportedEndpoints.forEach { unsupportedPath ->
                            post(unsupportedPath) {
                                handleWithConcurrencyLimit(call) {
                                    respondUnsupportedEndpoint(
                                        call,
                                        unsupportedPath,
                                        "Endpoint $unsupportedPath is not supported in this version."
                                    )
                                }
                            }
                        }
                        route("{...}") {
                            handle {
                                handleWithConcurrencyLimit(call) {
                                    val settings = preferences.settingsFlow.first()
                                    val requiredScope = DeviceScopePolicy.requiredScopeForGatewayPath(
                                        path = call.request.path(),
                                        method = call.request.httpMethod.value
                                    )
                                    if (requiredScope != null) {
                                        authenticateAndAuthorize(call, settings, requiredScope) ?: return@handleWithConcurrencyLimit
                                    }
                                    respondJson(
                                        call,
                                        HttpStatusCode.NotFound,
                                        JSONObject().put(
                                            "error",
                                            JSONObject()
                                                .put("message", "Unknown endpoint ${call.request.path()}")
                                                .put("type", "invalid_request_error")
                                        ).toString()
                                    )
                                }
                            }
                        }
                    }
                }
                createdServer.start(wait = false)
                val resolvedPort = createdServer.engine.resolvedConnectors().firstOrNull()?.port ?: bindValidation.normalizedPort
                engine = createdServer
                val addrStr = "http://${bindValidation.normalizedHost}:$resolvedPort"
                _runtimeState.value = GatewayRuntimeState(
                    status = GatewayRunStatus.LOCAL_RUNNING,
                    mode = GatewayMode.LOCAL,
                    boundAddress = addrStr,
                    activeRequests = 0,
                    lastError = null,
                    lanWarningActive = bindValidation.unencryptedLanWarning
                )
                db.metricAndLogDao().insertLog(
                    ActivityLogEntity(
                        category = ActivityCategory.SYSTEM_EVENT,
                        relatedEntity = settings.gatewayName,
                        status = "LOCAL_RUNNING",
                        safeMessage = "Local Gateway Ktor CIO server listening on $addrStr"
                    )
                )
            } catch (e: Exception) {
                runCatching { createdServer?.stop(200, 500) }
                runCatching { engine?.stop(200, 500) }
                engine = null
                val isPortConflict = e.hasCauseOrSelfIsBindException() ||
                    isPortAlreadyInUse(bindValidation.normalizedHost, bindValidation.normalizedPort)
                if (e is CancellationException && !isPortConflict) {
                    throw e
                }
                if (isPortConflict) {
                    val conflictMsg = "Port ${bindValidation.normalizedPort} is already in use on ${bindValidation.normalizedHost}. Stop the conflicting listener or choose a different port."
                    _runtimeState.value = GatewayRuntimeState(
                        status = GatewayRunStatus.ERROR,
                        mode = GatewayMode.LOCAL,
                        boundAddress = "http://${bindValidation.normalizedHost}:${bindValidation.normalizedPort}",
                        activeRequests = 0,
                        lastError = conflictMsg,
                        lanWarningActive = bindValidation.unencryptedLanWarning
                    )
                    db.metricAndLogDao().insertLog(
                        ActivityLogEntity(
                            category = ActivityCategory.SYSTEM_EVENT,
                            relatedEntity = settings.gatewayName,
                            status = "PORT_CONFLICT",
                            safeMessage = conflictMsg
                        )
                    )
                } else {
                    val errMsg = MaskingUtils.sanitizeErrorMessage(e.message)
                    _runtimeState.value = GatewayRuntimeState(
                        status = GatewayRunStatus.ERROR,
                        mode = GatewayMode.LOCAL,
                        boundAddress = "http://${bindValidation.normalizedHost}:${bindValidation.normalizedPort}",
                        activeRequests = 0,
                        lastError = errMsg,
                        lanWarningActive = bindValidation.unencryptedLanWarning
                    )
                    db.metricAndLogDao().insertLog(
                        ActivityLogEntity(
                            category = ActivityCategory.SYSTEM_EVENT,
                            relatedEntity = settings.gatewayName,
                            status = "START_FAILED",
                            safeMessage = errMsg
                        )
                    )
                }
            }
        }
    }

    private fun Throwable.hasCauseOrSelfIsBindException(): Boolean {
        var cur: Throwable? = this
        val visited = mutableSetOf<Throwable>()
        while (cur != null && visited.add(cur)) {
            if (cur is BindException || cur.message?.contains("Address already in use", ignoreCase = true) == true) {
                return true
            }
            cur = cur.cause
        }
        return false
    }

    private fun isPortAlreadyInUse(host: String, port: Int): Boolean {
        if (port <= 0) return false
        return try {
            ServerSocket().use { sock ->
                sock.reuseAddress = true
                sock.bind(InetSocketAddress(host, port))
            }
            false
        } catch (_: BindException) {
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun refreshRemoteHealth(settings: GatewaySettingsState) = lifecycleMutex.withLock {
        if (settings.gatewayMode == GatewayMode.LOCAL) return@withLock
        connectRemoteLocked(settings)
    }

    private suspend fun connectRemoteLocked(settings: GatewaySettingsState) {
        val allowLoopbackHttp = settings.gatewayMode == GatewayMode.EXISTING && settings.localNetworkAccessAllowed
        val validation = NetworkAddressClassifier.validateRemoteGatewayUrl(
            urlString = settings.gatewayRemoteUrl,
            allowExistingPrivateHttp = allowLoopbackHttp
        )
        if (!validation.valid) {
            _runtimeState.value = GatewayRuntimeState(
                status = GatewayRunStatus.ERROR,
                mode = settings.gatewayMode,
                boundAddress = settings.gatewayRemoteUrl.ifBlank { "https://" },
                activeRequests = 0,
                lastError = validation.errorMessage,
                lastHealthCheckAt = System.currentTimeMillis()
            )
            return
        }

        _runtimeState.value = GatewayRuntimeState(
            status = GatewayRunStatus.REMOTE_CONNECTING,
            mode = settings.gatewayMode,
            boundAddress = validation.normalizedUrl,
            activeRequests = 0,
            lastError = null
        )

        val remoteToken = if (settings.gatewayRemoteAuthTokenEnc.isNotBlank()) {
            CryptoManager.decryptResult(settings.gatewayRemoteAuthTokenEnc).getOrElse { err ->
                _runtimeState.value = GatewayRuntimeState(
                    status = GatewayRunStatus.ERROR,
                    mode = settings.gatewayMode,
                    boundAddress = validation.normalizedUrl,
                    activeRequests = 0,
                    lastError = "Remote auth token decryption failed: ${err.message}",
                    lastHealthCheckAt = System.currentTimeMillis()
                )
                return
            }
        } else ""

        val result = healthChecker.checkHealth(validation.normalizedUrl, remoteToken, settings.gatewayTimeoutSec)
        _runtimeState.value = GatewayRuntimeState(
            status = result.status,
            mode = settings.gatewayMode,
            boundAddress = validation.normalizedUrl,
            activeRequests = 0,
            lastError = result.errorMessage,
            lastHealthCheckAt = result.checkedAt,
            lastHealthHttpCode = result.httpCode
        )
        db.metricAndLogDao().insertLog(
            ActivityLogEntity(
                category = ActivityCategory.SYSTEM_EVENT,
                relatedEntity = settings.gatewayName,
                status = result.status.name,
                safeMessage = "Remote gateway health check (${validation.normalizedUrl}): ${result.status.name}" +
                    (result.httpCode?.let { " HTTP $it" } ?: "")
            )
        )
    }

    suspend fun stop() = lifecycleMutex.withLock {
        stopLocalEngineLocked()
        _runtimeState.update {
            it.copy(
                status = GatewayRunStatus.STOPPED,
                activeRequests = 0,
                lastError = null
            )
        }
        db.metricAndLogDao().insertLog(
            ActivityLogEntity(
                category = ActivityCategory.SYSTEM_EVENT,
                relatedEntity = "Gateway",
                status = "STOPPED",
                safeMessage = "Gateway stopped"
            )
        )
    }

    private suspend fun stopLocalEngineLocked() {
        val current = engine ?: return
        engine = null
        withContext(Dispatchers.IO) {
            runCatching { current.stop(gracePeriodMillis = 500, timeoutMillis = 2000) }
        }
    }

    private suspend fun handleWithConcurrencyLimit(
        call: ApplicationCall,
        block: suspend () -> Unit
    ) {
        if (!concurrencyLimiter.tryAcquire()) {
            respondJson(
                call,
                HttpStatusCode.ServiceUnavailable,
                """{"error":{"message":"Gateway concurrency limit reached. Retry shortly.","type":"server_overloaded"}}"""
            )
            return
        }
        val currentActive = activeCount.incrementAndGet()
        _runtimeState.update { it.copy(activeRequests = currentActive) }
        try {
            if (!validateHeadersSize(call)) {
                respondJson(
                    call,
                    HttpStatusCode.RequestHeaderFieldTooLarge,
                    """{"error":{"message":"Request headers exceed maximum allowed size","type":"invalid_request_error"}}"""
                )
                return
            }
            block()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            runCatching {
                respondJson(
                    call,
                    HttpStatusCode.BadRequest,
                    JSONObject().put(
                        "error",
                        JSONObject()
                            .put("message", MaskingUtils.sanitizeErrorMessage(e.message))
                            .put("type", "gateway_error")
                    ).toString()
                )
            }
        } finally {
            val left = activeCount.decrementAndGet().coerceAtLeast(0)
            _runtimeState.update { it.copy(activeRequests = left) }
            concurrencyLimiter.release()
        }
    }

    private fun validateHeadersSize(call: ApplicationCall): Boolean {
        var total = 0
        for ((name, values) in call.request.headers.entries()) {
            total += name.length + 4
            for (v in values) {
                total += v.length + 2
                if (total > MAX_TOTAL_HEADERS_BYTES) return false
            }
        }
        return true
    }

    private data class AuthenticatedClient(
        val label: String,
        val toolCallingAllowed: Boolean = true,
        val scopes: Set<DeviceScope> = DeviceScope.entries.toSet(),
        val allowedModelsCsv: String = "",
        val codingAgentId: String? = null
    )

    private sealed interface AuthOutcome {
        data class Authenticated(val client: AuthenticatedClient) : AuthOutcome
        data object MissingOrInvalid : AuthOutcome
        data class QuotaExceeded(
            val agentName: String,
            val usedTokens: Long,
            val dailyLimit: Long,
            val utcDate: String
        ) : AuthOutcome
    }

    private suspend fun handleHealth(call: ApplicationCall) {
        val settings = preferences.settingsFlow.first()
        val payload = JSONObject()
            .put("status", "ok")
            .put("mode", GatewayMode.LOCAL.name)
            .put("gateway", settings.gatewayName)
            .put(
                "supported_endpoints",
                JSONArray(GatewayApiContract.supportedEndpoints)
            )
        respondJson(call, HttpStatusCode.OK, payload.toString())
    }

    private suspend fun authenticateAndAuthorize(
        call: ApplicationCall,
        settings: GatewaySettingsState,
        requiredScope: DeviceScope,
        anthropicErrorFormat: Boolean = false
    ): AuthenticatedClient? {
        val authHeader = call.request.headers[HttpHeaders.Authorization]
        val xApiKeyHeader = call.request.headers["x-api-key"]
        val outcome = authenticateRequest(authHeader, xApiKeyHeader, settings)
        val effectiveClient = when (outcome) {
            is AuthOutcome.Authenticated -> outcome.client
            is AuthOutcome.QuotaExceeded -> {
                db.metricAndLogDao().insertLog(
                    ActivityLogEntity(
                        category = ActivityCategory.AUTH_EVENT,
                        relatedEntity = outcome.agentName,
                        status = "QUOTA_EXCEEDED",
                        safeMessage = "Daily UTC token quota exceeded (${outcome.usedTokens}/${outcome.dailyLimit} on ${outcome.utcDate} UTC) for ${call.request.path()}"
                    )
                )
                val msg = "Daily token quota exceeded for ${outcome.agentName} (${outcome.usedTokens}/${outcome.dailyLimit} tokens on ${outcome.utcDate} UTC)."
                val errBody = if (anthropicErrorFormat) {
                    GatewayApiContract.formatAnthropicError("rate_limit_error", msg)
                } else {
                    JSONObject().put(
                        "error",
                        JSONObject()
                            .put("message", msg)
                            .put("type", "rate_limit_error")
                            .put("code", "daily_token_limit_exceeded")
                    ).toString()
                }
                respondJson(call, HttpStatusCode.TooManyRequests, errBody)
                return null
            }
            AuthOutcome.MissingOrInvalid -> {
                db.metricAndLogDao().insertLog(
                    ActivityLogEntity(
                        category = ActivityCategory.AUTH_EVENT,
                        relatedEntity = call.request.path(),
                        status = "UNAUTHORIZED",
                        safeMessage = "Rejected request without valid active Bearer token"
                    )
                )
                val errBody = if (anthropicErrorFormat) {
                    GatewayApiContract.formatAnthropicError("authentication_error", "Invalid or missing Gateway Bearer token")
                } else {
                    """{"error":{"message":"Invalid or missing Gateway Bearer token","type":"authentication_error"}}"""
                }
                respondJson(call, HttpStatusCode.Unauthorized, errBody)
                return null
            }
        }

        // Enforce default-deny RBAC scope; client-supplied headers/claims are never trusted
        if (!DeviceScopePolicy.hasScope(effectiveClient.scopes, requiredScope)) {
            db.metricAndLogDao().insertLog(
                ActivityLogEntity(
                    category = ActivityCategory.AUTH_EVENT,
                    relatedEntity = effectiveClient.label,
                    status = "PERMISSION_DENIED",
                    safeMessage = "Denied ${call.request.path()}: missing required scope ${requiredScope.name}"
                )
            )
            val errMsg = "Client '${effectiveClient.label}' lacks required scope ${requiredScope.name}"
            val errBody = if (anthropicErrorFormat) {
                GatewayApiContract.formatAnthropicError("permission_error", errMsg)
            } else {
                JSONObject().put(
                    "error",
                    JSONObject()
                        .put("message", errMsg)
                        .put("type", "permission_error")
                        .put("code", "permission_denied")
                ).toString()
            }
            respondJson(call, HttpStatusCode.Forbidden, errBody)
            return null
        }

        if (!checkRateLimit(effectiveClient.label, settings.gatewayRateLimitPerMin)) {
            val errBody = if (anthropicErrorFormat) {
                GatewayApiContract.formatAnthropicError("rate_limit_error", "Gateway rate limit exceeded")
            } else {
                """{"error":{"message":"Gateway rate limit exceeded","type":"rate_limit_error"}}"""
            }
            respondJson(call, HttpStatusCode.TooManyRequests, errBody)
            return null
        }
        return effectiveClient
    }

    private suspend fun enforceModelAllowlist(
        call: ApplicationCall,
        client: AuthenticatedClient,
        requestedModelOrAlias: String,
        settings: GatewaySettingsState? = null,
        anthropicErrorFormat: Boolean = false
    ): Boolean {
        if (client.allowedModelsCsv.isBlank()) return true
        val effectiveRequest = requestedModelOrAlias.trim().ifBlank { settings?.defaultModelId.orEmpty().trim() }
        val models = db.modelDao().getAllOnce().filter { it.enabled }
        val matched = models.firstOrNull {
            it.alias.equals(effectiveRequest, ignoreCase = true) ||
                it.modelId.equals(effectiveRequest, ignoreCase = true)
        }
        val allowed = DeviceScopePolicy.isModelAllowed(
            allowedModelsCsv = client.allowedModelsCsv,
            requestedModelOrAlias = effectiveRequest,
            resolvedModelId = matched?.modelId
        )
        if (!allowed) {
            db.metricAndLogDao().insertLog(
                ActivityLogEntity(
                    category = ActivityCategory.AUTH_EVENT,
                    relatedEntity = client.label,
                    status = "PERMISSION_DENIED",
                    safeMessage = "Denied ${call.request.path()}: model '$effectiveRequest' is not in client allowlist"
                )
            )
            val errMsg = "Model '$effectiveRequest' is not permitted for ${client.label}"
            val errBody = if (anthropicErrorFormat) {
                GatewayApiContract.formatAnthropicError("permission_error", errMsg)
            } else {
                JSONObject().put(
                    "error",
                    JSONObject()
                        .put("message", errMsg)
                        .put("type", "permission_error")
                        .put("code", "model_not_allowed")
                ).toString()
            }
            respondJson(call, HttpStatusCode.Forbidden, errBody)
            return false
        }
        return true
    }

    private suspend fun reconcileAgentUsageIfNeeded(
        client: AuthenticatedClient,
        resolvedUsage: ResolvedTokenUsage
    ) {
        val agentId = client.codingAgentId ?: return
        val mutex = agentQuotaMutexes.getOrPut(agentId) { Mutex() }
        val todayUtc = TokenUsageAccounting.utcDateString()
        val now = System.currentTimeMillis()
        mutex.withLock {
            db.codingAgentDao().reconcileUsageAtomic(
                id = agentId,
                deltaTokens = resolvedUsage.totalTokens.toLong(),
                usageSource = resolvedUsage.source.name,
                todayUtc = todayUtc,
                now = now
            )
        }
    }

    private suspend fun handleAuthenticatedModels(call: ApplicationCall) {
        val settings = preferences.settingsFlow.first()
        val client = authenticateAndAuthorize(call, settings, DeviceScope.MODELS_READ) ?: return
        val models = db.modelDao().getAllOnce().filter { it.enabled }.filter { m ->
            DeviceScopePolicy.isModelAllowed(
                allowedModelsCsv = client.allowedModelsCsv,
                requestedModelOrAlias = m.alias.ifBlank { m.modelId },
                resolvedModelId = m.modelId
            )
        }
        val data = JSONArray()
        models.forEach { m ->
            data.put(JSONObject().apply {
                put("id", m.alias.ifBlank { m.modelId })
                put("object", "model")
                put("owned_by", m.providerName)
            })
        }
        respondJson(call, HttpStatusCode.OK, JSONObject().put("object", "list").put("data", data).toString())
    }

    private suspend fun handleAuthenticatedChat(call: ApplicationCall) {
        val settings = preferences.settingsFlow.first()
        val client = authenticateAndAuthorize(call, settings, DeviceScope.CHAT) ?: return
        val body = readBoundedBody(call, settings.gatewayMaxRequestKb * 1024) ?: return
        handleOpenAiChat(call, body, client, settings)
    }

    private suspend fun handleAuthenticatedResponses(call: ApplicationCall) {
        val settings = preferences.settingsFlow.first()
        val client = authenticateAndAuthorize(call, settings, DeviceScope.CHAT) ?: return
        val body = readBoundedBody(call, settings.gatewayMaxRequestKb * 1024) ?: return
        handleOpenAiResponses(call, body, client, settings)
    }

    private suspend fun handleAuthenticatedAnthropicMessages(call: ApplicationCall) {
        val settings = preferences.settingsFlow.first()
        val client = authenticateAndAuthorize(call, settings, DeviceScope.CHAT, anthropicErrorFormat = true) ?: return
        val body = readBoundedBody(call, settings.gatewayMaxRequestKb * 1024) ?: return
        handleAnthropicMessages(call, body, client, settings)
    }

    private suspend fun respondUnsupportedEndpoint(
        call: ApplicationCall,
        endpoint: String,
        message: String
    ) {
        val settings = preferences.settingsFlow.first()
        val requiredScope = DeviceScopePolicy.requiredScopeForGatewayPath(endpoint, call.request.httpMethod.value) ?: DeviceScope.CHAT
        authenticateAndAuthorize(call, settings, requiredScope) ?: return
        val payload = JSONObject().put(
            "error",
            JSONObject()
                .put("message", message)
                .put("type", "not_implemented_error")
                .put("code", "endpoint_not_supported")
                .put("endpoint", endpoint)
                .put("supported_endpoints", JSONArray(GatewayApiContract.supportedEndpoints))
        )
        respondJson(call, HttpStatusCode.NotImplemented, payload.toString())
    }

    private suspend fun readBoundedBody(call: ApplicationCall, maxBytes: Int): String? {
        val contentLengthHeader = call.request.headers[HttpHeaders.ContentLength]
        if (contentLengthHeader != null) {
            val declaredLen = contentLengthHeader.toLongOrNull()
            if (declaredLen == null || declaredLen < 0L) {
                respondJson(call, HttpStatusCode.BadRequest, """{"error":{"message":"Invalid Content-Length header","type":"invalid_request_error"}}""")
                return null
            }
            if (declaredLen > maxBytes) {
                respondJson(call, HttpStatusCode.PayloadTooLarge, """{"error":{"message":"Request body exceeds limit of $maxBytes bytes","type":"invalid_request_error"}}""")
                return null
            }
        }

        val channel = call.receiveChannel()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val read = channel.readAvailable(buf, 0, buf.size)
            if (read == -1) break
            if (read == 0) continue
            if (out.size() + read > maxBytes) {
                respondJson(
                    call,
                    HttpStatusCode.PayloadTooLarge,
                    """{"error":{"message":"Request body exceeds limit of $maxBytes bytes","type":"invalid_request_error"}}"""
                )
                return null
            }
            out.write(buf, 0, read)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private suspend fun authenticateRequest(
        authHeader: String?,
        xApiKeyHeader: String?,
        settings: GatewaySettingsState
    ): AuthOutcome {
        val trimmedHeader = authHeader?.trim().orEmpty()
        val token = when {
            trimmedHeader.startsWith("Bearer ", ignoreCase = true) -> trimmedHeader.substring(7).trim()
            trimmedHeader.isEmpty() && !xApiKeyHeader.isNullOrBlank() -> xApiKeyHeader.trim()
            trimmedHeader.isEmpty() -> ""
            else -> return AuthOutcome.MissingOrInvalid
        }
        if (token.isEmpty()) {
            return if (settings.gatewayAuthRequired) {
                AuthOutcome.MissingOrInvalid
            } else {
                AuthOutcome.Authenticated(
                    AuthenticatedClient(
                        label = "Anonymous Local",
                        toolCallingAllowed = true,
                        scopes = DeviceScope.entries.toSet()
                    )
                )
            }
        }

        val devices = db.deviceDao().getAllOnce()
        devices.firstOrNull { dev ->
            !dev.revoked && dev.enabled &&
                CryptoManager.decryptResult(dev.encryptedToken).getOrNull() == token
        }?.let { dev ->
            db.deviceDao().recordRequestStarted(dev.id, System.currentTimeMillis())
            return AuthOutcome.Authenticated(
                AuthenticatedClient(
                    label = dev.name,
                    toolCallingAllowed = true,
                    scopes = DeviceScopePolicy.parseScopes(dev.permissionsCsv),
                    allowedModelsCsv = dev.allowedModelsCsv
                )
            )
        }

        val agents = db.codingAgentDao().getAllOnce()
        agents.firstOrNull { ag ->
            ag.enabled && CryptoManager.decryptResult(ag.encryptedToken).getOrNull() == token
        }?.let { ag ->
            val mutex = agentQuotaMutexes.getOrPut(ag.id) { Mutex() }
            val todayUtc = TokenUsageAccounting.utcDateString()
            return mutex.withLock {
                val fresh = db.codingAgentDao().rollUtcDayIfNeeded(ag.id, todayUtc) ?: ag
                val limit = fresh.dailyTokenLimit
                if (limit != null && fresh.tokensUsedToday >= limit) {
                    AuthOutcome.QuotaExceeded(
                        agentName = fresh.name,
                        usedTokens = fresh.tokensUsedToday,
                        dailyLimit = limit,
                        utcDate = todayUtc
                    )
                } else {
                    db.codingAgentDao().recordRequestStarted(fresh.id, System.currentTimeMillis())
                    AuthOutcome.Authenticated(
                        AuthenticatedClient(
                            label = "Agent: ${fresh.name}",
                            toolCallingAllowed = fresh.toolCalling,
                            scopes = setOf(DeviceScope.CHAT, DeviceScope.MODELS_READ),
                            allowedModelsCsv = fresh.allowedModelsCsv,
                            codingAgentId = fresh.id
                        )
                    )
                }
            }
        }
        return AuthOutcome.MissingOrInvalid
    }

    private fun checkRateLimit(clientKey: String, maxPerMin: Int): Boolean {
        val now = System.currentTimeMillis()
        val list = rateWindow.getOrPut(clientKey) { mutableListOf() }
        synchronized(list) {
            list.removeAll { now - it > 60_000L }
            if (list.size >= maxPerMin.coerceAtLeast(1)) return false
            list.add(now)
            return true
        }
    }

    private fun isToolCallingPermitted(client: AuthenticatedClient, parsed: ParsedGatewayChatRequest): Boolean {
        if (client.toolCallingAllowed) return true
        val requestsTools = parsed.tools.isNotEmpty() ||
            parsed.toolChoice == NormalizedToolChoice.Required ||
            parsed.toolChoice is NormalizedToolChoice.SpecificFunction
        return !requestsTools
    }

    private suspend fun respondContractInvalid(
        call: ApplicationCall,
        invalid: ContractParseResult.Invalid,
        anthropicErrorFormat: Boolean = false
    ) {
        val body = if (anthropicErrorFormat) {
            GatewayApiContract.formatAnthropicError(invalid.errorType, invalid.message)
        } else {
            JSONObject().put(
                "error",
                JSONObject()
                    .put("message", invalid.message)
                    .put("type", invalid.errorType)
                    .put("code", invalid.errorCode)
            ).toString()
        }
        respondJson(call, invalid.statusCode, body)
    }

    private suspend fun handleOpenAiChat(
        call: ApplicationCall,
        body: String,
        client: AuthenticatedClient,
        settings: GatewaySettingsState
    ) {
        val parsed = when (val res = GatewayApiContract.parseOpenAiChatRequest(body, settings.gatewayStreamingEnabled)) {
            is ContractParseResult.Invalid -> {
                respondContractInvalid(call, res)
                return
            }
            is ContractParseResult.Valid -> res.value
        }

        if (!enforceModelAllowlist(call, client, parsed.model, settings = settings)) return

        if (!isToolCallingPermitted(client, parsed)) {
            db.metricAndLogDao().insertLog(
                ActivityLogEntity(
                    category = ActivityCategory.AUTH_EVENT,
                    relatedEntity = client.label,
                    status = "PERMISSION_DENIED",
                    safeMessage = "Denied ${call.request.path()}: tool calling disabled for ${client.label}"
                )
            )
            respondJson(
                call,
                HttpStatusCode.Forbidden,
                """{"error":{"message":"Tool calling is disabled for ${client.label}","type":"permission_error","code":"tool_calling_disabled"}}"""
            )
            return
        }

        if (!parsed.stream) {
            val result = router.executeRoutedChat(
                requestedModelOrAlias = parsed.model,
                messages = parsed.messages,
                settings = settings,
                deviceName = client.label,
                endpoint = "/v1/chat/completions",
                temperature = parsed.temperature,
                topP = parsed.topP,
                maxTokens = parsed.maxTokens,
                stopSequences = parsed.stopSequences,
                jsonOutput = parsed.jsonOutput,
                tools = parsed.tools,
                toolChoice = parsed.toolChoice,
                responseFormat = parsed.responseFormat
            )
            reconcileAgentUsageIfNeeded(client, result.decision.resolvedUsage)
            if (!result.execution.success) {
                val code = HttpStatusCode.fromValue(result.execution.statusCode.coerceIn(400, 599))
                val errBody = JSONObject().put(
                    "error",
                    JSONObject()
                        .put("message", result.execution.safeErrorMessage ?: "Gateway error")
                        .put("type", "upstream_error")
                        .put("code", result.execution.errorCode ?: "gateway_error")
                ).toString()
                respondJson(call, code, errBody)
            } else {
                val respJson = GatewayApiContract.formatOpenAiChatResponse(
                    requestId = result.decision.requestId,
                    model = result.decision.selectedModel,
                    result = result.execution
                )
                respondJson(call, HttpStatusCode.OK, respJson.toString())
            }
        } else {
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.response.header(HttpHeaders.Connection, "close")
            call.respondTextWriter(contentType = ContentType.Text.EventStream, status = HttpStatusCode.OK) {
                val cid = "chatcmpl-${UUID.randomUUID().toString().take(8)}"
                var firstChunk = true
                router.streamRoutedChat(
                    requestedModelOrAlias = parsed.model,
                    messages = parsed.messages,
                    settings = settings,
                    deviceName = client.label,
                    endpoint = "/v1/chat/completions",
                    temperature = parsed.temperature,
                    topP = parsed.topP,
                    maxTokens = parsed.maxTokens,
                    stopSequences = parsed.stopSequences,
                    jsonOutput = parsed.jsonOutput,
                    tools = parsed.tools,
                    toolChoice = parsed.toolChoice,
                    responseFormat = parsed.responseFormat
                ).collect { (ev, decision) ->
                    currentCoroutineContext().ensureActive()
                    if (decision != null && (ev is StreamEvent.Completed || ev is StreamEvent.Failed)) {
                        reconcileAgentUsageIfNeeded(client, decision.resolvedUsage)
                    }
                    val emitted = GatewayApiContract.writeOpenAiSseEvent(
                        writer = this,
                        cid = cid,
                        model = parsed.model,
                        event = ev,
                        isFirstChunk = firstChunk
                    )
                    if (emitted) firstChunk = false
                }
            }
        }
    }

    private suspend fun handleOpenAiResponses(
        call: ApplicationCall,
        body: String,
        client: AuthenticatedClient,
        settings: GatewaySettingsState
    ) {
        val parsed = when (val res = GatewayApiContract.parseOpenAiResponsesRequest(body, settings.gatewayStreamingEnabled)) {
            is ContractParseResult.Invalid -> {
                respondContractInvalid(call, res)
                return
            }
            is ContractParseResult.Valid -> res.value
        }

        if (!enforceModelAllowlist(call, client, parsed.model, settings = settings)) return

        if (!isToolCallingPermitted(client, parsed)) {
            db.metricAndLogDao().insertLog(
                ActivityLogEntity(
                    category = ActivityCategory.AUTH_EVENT,
                    relatedEntity = client.label,
                    status = "PERMISSION_DENIED",
                    safeMessage = "Denied ${call.request.path()}: tool calling disabled for ${client.label}"
                )
            )
            respondJson(
                call,
                HttpStatusCode.Forbidden,
                """{"error":{"message":"Tool calling is disabled for ${client.label}","type":"permission_error","code":"tool_calling_disabled"}}"""
            )
            return
        }

        if (!parsed.stream) {
            val result = router.executeRoutedChat(
                requestedModelOrAlias = parsed.model,
                messages = parsed.messages,
                settings = settings,
                deviceName = client.label,
                endpoint = "/v1/responses",
                temperature = parsed.temperature,
                topP = parsed.topP,
                maxTokens = parsed.maxTokens,
                stopSequences = parsed.stopSequences,
                jsonOutput = parsed.jsonOutput,
                tools = parsed.tools,
                toolChoice = parsed.toolChoice,
                responseFormat = parsed.responseFormat
            )
            reconcileAgentUsageIfNeeded(client, result.decision.resolvedUsage)
            if (!result.execution.success) {
                val code = HttpStatusCode.fromValue(result.execution.statusCode.coerceIn(400, 599))
                val errBody = JSONObject().put(
                    "error",
                    JSONObject()
                        .put("message", result.execution.safeErrorMessage ?: "Gateway error")
                        .put("type", "upstream_error")
                        .put("code", result.execution.errorCode ?: "gateway_error")
                ).toString()
                respondJson(call, code, errBody)
            } else {
                val respJson = GatewayApiContract.formatOpenAiResponsesResponse(
                    responseId = "resp_${result.decision.requestId}",
                    model = result.decision.selectedModel,
                    result = result.execution
                )
                respondJson(call, HttpStatusCode.OK, respJson.toString())
            }
        } else {
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.response.header(HttpHeaders.Connection, "close")
            call.respondTextWriter(contentType = ContentType.Text.EventStream, status = HttpStatusCode.OK) {
                val respId = "resp_${UUID.randomUUID().toString().take(8)}"
                val sseWriter = GatewayApiContract.ResponsesSseWriter(this, respId, parsed.model)
                router.streamRoutedChat(
                    requestedModelOrAlias = parsed.model,
                    messages = parsed.messages,
                    settings = settings,
                    deviceName = client.label,
                    endpoint = "/v1/responses",
                    temperature = parsed.temperature,
                    topP = parsed.topP,
                    maxTokens = parsed.maxTokens,
                    stopSequences = parsed.stopSequences,
                    jsonOutput = parsed.jsonOutput,
                    tools = parsed.tools,
                    toolChoice = parsed.toolChoice,
                    responseFormat = parsed.responseFormat
                ).collect { (ev, decision) ->
                    currentCoroutineContext().ensureActive()
                    if (decision != null && (ev is StreamEvent.Completed || ev is StreamEvent.Failed)) {
                        reconcileAgentUsageIfNeeded(client, decision.resolvedUsage)
                    }
                    sseWriter.onEvent(ev)
                }
            }
        }
    }

    private suspend fun handleAnthropicMessages(
        call: ApplicationCall,
        body: String,
        client: AuthenticatedClient,
        settings: GatewaySettingsState
    ) {
        val parsed = when (val res = GatewayApiContract.parseAnthropicMessagesRequest(body, settings.gatewayStreamingEnabled)) {
            is ContractParseResult.Invalid -> {
                respondContractInvalid(call, res, anthropicErrorFormat = true)
                return
            }
            is ContractParseResult.Valid -> res.value
        }

        if (!enforceModelAllowlist(call, client, parsed.model, settings = settings, anthropicErrorFormat = true)) return

        if (!isToolCallingPermitted(client, parsed)) {
            db.metricAndLogDao().insertLog(
                ActivityLogEntity(
                    category = ActivityCategory.AUTH_EVENT,
                    relatedEntity = client.label,
                    status = "PERMISSION_DENIED",
                    safeMessage = "Denied ${call.request.path()}: tool calling disabled for ${client.label}"
                )
            )
            respondJson(
                call,
                HttpStatusCode.Forbidden,
                GatewayApiContract.formatAnthropicError("permission_error", "Tool calling is disabled for ${client.label}")
            )
            return
        }

        if (!parsed.stream) {
            val result = router.executeRoutedChat(
                requestedModelOrAlias = parsed.model,
                messages = parsed.messages,
                settings = settings,
                deviceName = client.label,
                endpoint = "/v1/messages",
                temperature = parsed.temperature,
                topP = parsed.topP,
                maxTokens = parsed.maxTokens,
                stopSequences = parsed.stopSequences,
                jsonOutput = parsed.jsonOutput,
                tools = parsed.tools,
                toolChoice = parsed.toolChoice,
                responseFormat = parsed.responseFormat
            )
            reconcileAgentUsageIfNeeded(client, result.decision.resolvedUsage)
            if (!result.execution.success) {
                val code = HttpStatusCode.fromValue(result.execution.statusCode.coerceIn(400, 599))
                val errType = when (code.value) {
                    400 -> "invalid_request_error"
                    401 -> "authentication_error"
                    403 -> "permission_error"
                    429 -> "rate_limit_error"
                    529 -> "overloaded_error"
                    else -> "api_error"
                }
                respondJson(
                    call,
                    code,
                    GatewayApiContract.formatAnthropicError(errType, result.execution.safeErrorMessage ?: "Upstream error")
                )
            } else {
                val respJson = GatewayApiContract.formatAnthropicMessagesResponse(
                    requestId = result.decision.requestId,
                    model = result.decision.selectedModel,
                    result = result.execution
                )
                respondJson(call, HttpStatusCode.OK, respJson.toString())
            }
        } else {
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.response.header(HttpHeaders.Connection, "close")
            call.respondTextWriter(contentType = ContentType.Text.EventStream, status = HttpStatusCode.OK) {
                val msgId = "msg_${UUID.randomUUID().toString().take(8)}"
                val sseWriter = GatewayApiContract.AnthropicSseWriter(this, msgId, parsed.model)
                router.streamRoutedChat(
                    requestedModelOrAlias = parsed.model,
                    messages = parsed.messages,
                    settings = settings,
                    deviceName = client.label,
                    endpoint = "/v1/messages",
                    temperature = parsed.temperature,
                    topP = parsed.topP,
                    maxTokens = parsed.maxTokens,
                    stopSequences = parsed.stopSequences,
                    jsonOutput = parsed.jsonOutput,
                    tools = parsed.tools,
                    toolChoice = parsed.toolChoice,
                    responseFormat = parsed.responseFormat
                ).collect { (ev, decision) ->
                    currentCoroutineContext().ensureActive()
                    if (decision != null && (ev is StreamEvent.Completed || ev is StreamEvent.Failed)) {
                        reconcileAgentUsageIfNeeded(client, decision.resolvedUsage)
                    }
                    sseWriter.onEvent(ev)
                }
            }
        }
    }

    private suspend fun respondJson(call: ApplicationCall, status: HttpStatusCode, jsonBody: String) {
        call.respondText(
            text = jsonBody,
            contentType = ContentType.Application.Json,
            status = status
        )
    }
}
