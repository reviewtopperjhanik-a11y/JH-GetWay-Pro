package com.example.network

import com.example.core.security.DnsResolver
import com.example.core.security.MaskingUtils
import com.example.core.security.NetworkAddressClassifier
import com.example.core.security.SystemDnsResolver
import com.example.data.local.ApiFormat
import com.example.data.local.LocalAiServerEntity
import com.example.data.local.LocalEngineType
import com.example.data.local.ModelEntity
import com.example.data.local.ProviderEntity
import com.example.data.local.ProviderStatus
import com.example.gateway.OpenCodeSelectorIds
import com.example.network.adapters.AdapterHelper
import com.example.network.adapters.AdapterStreamState
import com.example.network.adapters.ContentPart
import com.example.network.adapters.NormalizedResponseFormat
import com.example.network.adapters.NormalizedToolChoice
import com.example.network.adapters.NormalizedToolDefinition
import com.example.network.adapters.ProviderAdapterRegistry
import com.example.network.adapters.ProviderCapabilities
import com.example.network.adapters.ToolCallDelta
import com.example.network.adapters.ToolCallPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

data class ConnectionTestResult(
    val status: ProviderStatus,
    val latencyMs: Long,
    val httpCode: Int? = null,
    val message: String,
    val retryAfterSec: Int? = null
)

data class ChatMessagePayload(
    val role: String,
    val content: String = "",
    val contentParts: List<ContentPart> = emptyList(),
    val name: String? = null,
    val toolCallId: String? = null,
    val toolCalls: List<ToolCallPayload> = emptyList()
)

data class ChatRequestParams(
    val messages: List<ChatMessagePayload>,
    val modelId: String,
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    val stopSequences: List<String> = emptyList(),
    val jsonOutput: Boolean = false,
    val stream: Boolean = false,
    val toolsJson: JSONArray? = null,
    val tools: List<NormalizedToolDefinition> = emptyList(),
    val toolChoice: NormalizedToolChoice? = null,
    val responseFormat: NormalizedResponseFormat? = null,
    val capabilities: ProviderCapabilities? = null
)

data class ChatExecutionResult(
    val success: Boolean,
    val content: String = "",
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val latencyMs: Long = 0,
    val statusCode: Int = 200,
    val finishReason: String = "stop",
    val errorCode: String? = null,
    val safeErrorMessage: String? = null,
    val retryable: Boolean = false,
    val toolCalls: List<ToolCallPayload> = emptyList(),
    val usageReported: Boolean = false,
    val modelUsed: String? = null,
    val actualBilledCostUsd: Double? = null,
    val retryAfterSec: Int? = null
)

sealed interface StreamEvent {
    data class Delta(
        val text: String = "",
        val toolCallDeltas: List<ToolCallDelta> = emptyList(),
        val finishReason: String? = null
    ) : StreamEvent

    data class Completed(val result: ChatExecutionResult) : StreamEvent
    data class Failed(val error: ChatExecutionResult, val emittedAnyChunk: Boolean) : StreamEvent
}

class ProviderHttpClient(
    private val dnsResolver: DnsResolver = SystemDnsResolver
) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    companion object {
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024 // 2 MB cap for non-streaming bodies
    }

    private fun buildClient(
        timeoutSec: Int,
        allowLocalHttp: Boolean,
        connectTimeoutSec: Int = minOf(timeoutSec, 10),
        callTimeoutSec: Int? = null
    ): OkHttpClient {
        val builder = baseClient.newBuilder()
            .connectTimeout(connectTimeoutSec.toLong().coerceIn(2, 15), TimeUnit.SECONDS)
            .readTimeout(timeoutSec.toLong().coerceIn(2, 300), TimeUnit.SECONDS)
            .writeTimeout(timeoutSec.toLong().coerceIn(2, 120), TimeUnit.SECONDS)
            .dns(NetworkAddressClassifier.pinnedSafeDns(allowLocalHttp, dnsResolver))
        if (callTimeoutSec != null) {
            builder.callTimeout(callTimeoutSec.toLong().coerceIn(2, 300), TimeUnit.SECONDS)
        }
        return builder.build()
    }

    private fun buildRequestWithoutStreamOptions(original: Request): Request? {
        val body = original.body ?: return null
        val jsonStr = runCatching {
            val buf = okio.Buffer()
            body.writeTo(buf)
            buf.readUtf8()
        }.getOrNull() ?: return null
        val obj = runCatching { org.json.JSONObject(jsonStr) }.getOrNull() ?: return null
        if (!obj.has("stream_options")) return null
        obj.remove("stream_options")
        return original.newBuilder()
            .post(obj.toString().toRequestBody(jsonMedia))
            .build()
    }

    private fun readBoundedBody(resp: okhttp3.Response): String {
        val body = resp.body ?: return ""
        val source = body.source()
        source.request((MAX_RESPONSE_BYTES + 1).toLong())
        val buffer = source.buffer
        val readCount = minOf(buffer.size, MAX_RESPONSE_BYTES.toLong())
        return buffer.clone().readString(readCount, Charsets.UTF_8)
    }

    private fun parseRetryAfterSeconds(resp: okhttp3.Response): Int? {
        val raw = resp.header("Retry-After")?.trim().orEmpty()
        if (raw.isEmpty()) return null
        raw.toIntOrNull()?.let { return it.coerceIn(5, 3600) }
        val patterns = listOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEEE, dd-MMM-yy HH:mm:ss zzz",
            "EEE MMM d HH:mm:ss yyyy"
        )
        for (pattern in patterns) {
            val parsedMs = runCatching {
                java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("GMT")
                }.parse(raw)?.time
            }.getOrNull()
            if (parsedMs != null) {
                val deltaSec = ((parsedMs - System.currentTimeMillis()) / 1000L).toInt()
                return deltaSec.coerceIn(5, 3600)
            }
        }
        return null
    }

    suspend fun testProviderConnection(
        provider: ProviderEntity,
        apiKey: String,
        allowLocalHttp: Boolean,
        timeoutSec: Int = 20
    ): ConnectionTestResult = withContext(Dispatchers.IO) {
        val sec = NetworkAddressClassifier.validateEndpointSecurity(provider.baseUrl, allowLocalHttp, resolver = dnsResolver)
        if (!sec.allowed) {
            return@withContext ConnectionTestResult(
                status = ProviderStatus.ERROR,
                latencyMs = 0,
                message = sec.errorMessage ?: "HTTPS is required for remote endpoints (or enable Local Network Access for private hosts)."
            )
        }
        if (apiKey.isBlank() && !NetworkAddressClassifier.isPrivateOrLoopbackHost(provider.baseUrl)) {
            return@withContext ConnectionTestResult(
                status = ProviderStatus.ERROR,
                latencyMs = 0,
                message = "No active API key configured for this provider."
            )
        }

        val adapter = ProviderAdapterRegistry.adapterFor(provider.apiFormat)
        val start = System.nanoTime()
        try {
            val url = adapter.buildModelsUrl(provider.baseUrl)
            val reqBuilder = Request.Builder().url(url).get()
            adapter.applyAuthHeaders(reqBuilder, apiKey)
            AdapterHelper.applyCustomHeaders(reqBuilder, provider.customHeadersJson)
            buildClient(timeoutSec, allowLocalHttp).newCall(reqBuilder.build()).execute().use { resp ->
                val latency = (System.nanoTime() - start) / 1_000_000L
                val bodyStr = readBoundedBody(resp)
                return@withContext when {
                    resp.isSuccessful -> ConnectionTestResult(
                        status = ProviderStatus.CONNECTED,
                        latencyMs = latency,
                        httpCode = resp.code,
                        message = "Connected (${resp.code}) in ${latency}ms"
                    )
                    resp.code == 429 -> ConnectionTestResult(
                        status = ProviderStatus.RATE_LIMITED,
                        latencyMs = latency,
                        httpCode = resp.code,
                        message = "Rate limited (HTTP 429)",
                        retryAfterSec = parseRetryAfterSeconds(resp)
                    )
                    resp.code == 401 || resp.code == 403 -> ConnectionTestResult(
                        status = ProviderStatus.ERROR,
                        latencyMs = latency,
                        httpCode = resp.code,
                        message = "Authentication failed (HTTP ${resp.code}). Verify API key."
                    )
                    else -> ConnectionTestResult(
                        status = ProviderStatus.ERROR,
                        latencyMs = latency,
                        httpCode = resp.code,
                        message = "HTTP ${resp.code}: ${MaskingUtils.sanitizeErrorMessage(bodyStr, listOf(apiKey))}"
                    )
                }
            }
        } catch (e: SocketTimeoutException) {
            val latency = (System.nanoTime() - start) / 1_000_000L
            ConnectionTestResult(ProviderStatus.DISCONNECTED, latency, message = "Connection timed out after ${latency}ms")
        } catch (e: Exception) {
            val latency = (System.nanoTime() - start) / 1_000_000L
            ConnectionTestResult(
                status = ProviderStatus.DISCONNECTED,
                latencyMs = latency,
                message = MaskingUtils.sanitizeErrorMessage(e.message, listOf(apiKey))
            )
        }
    }

    suspend fun fetchProviderModels(
        provider: ProviderEntity,
        apiKey: String,
        allowLocalHttp: Boolean,
        timeoutSec: Int = 25
    ): Result<List<ModelEntity>> = withContext(Dispatchers.IO) {
        val sec = NetworkAddressClassifier.validateEndpointSecurity(provider.baseUrl, allowLocalHttp, resolver = dnsResolver)
        if (!sec.allowed) {
            return@withContext Result.failure(IllegalArgumentException(sec.errorMessage ?: "Insecure URL blocked by HTTPS/Local policy"))
        }
        if (apiKey.isBlank() && !NetworkAddressClassifier.isPrivateOrLoopbackHost(provider.baseUrl)) {
            return@withContext Result.failure(IllegalStateException("No active API key configured for this provider."))
        }
        val adapter = ProviderAdapterRegistry.adapterFor(provider.apiFormat)
        try {
            val url = adapter.buildModelsUrl(provider.baseUrl)
            val reqBuilder = Request.Builder().url(url).get()
            adapter.applyAuthHeaders(reqBuilder, apiKey)
            AdapterHelper.applyCustomHeaders(reqBuilder, provider.customHeadersJson)
            buildClient(timeoutSec, allowLocalHttp).newCall(reqBuilder.build()).execute().use { resp ->
                val bodyStr = readBoundedBody(resp)
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("HTTP ${resp.code}: ${MaskingUtils.sanitizeErrorMessage(bodyStr, listOf(apiKey))}")
                    )
                }
                Result.success(adapter.parseModelsResponse(bodyStr, provider.id, provider.name, isLocal = false))
            }
        } catch (e: Exception) {
            Result.failure(IllegalStateException(MaskingUtils.sanitizeErrorMessage(e.message, listOf(apiKey))))
        }
    }

    suspend fun testLocalServer(
        server: LocalAiServerEntity,
        authToken: String,
        allowLocalHttp: Boolean
    ): ConnectionTestResult = withContext(Dispatchers.IO) {
        val sec = NetworkAddressClassifier.validateEndpointSecurity(server.serverUrl, allowLocalHttp, resolver = dnsResolver)
        if (!sec.allowed) {
            return@withContext ConnectionTestResult(
                status = ProviderStatus.ERROR,
                latencyMs = 0,
                message = sec.errorMessage ?: "Local Network Access must be allowed for HTTP private servers, or use HTTPS for remote hosts."
            )
        }
        val format = if (server.engineType == LocalEngineType.OLLAMA) ApiFormat.OLLAMA else ApiFormat.OPENAI
        val adapter = ProviderAdapterRegistry.adapterFor(format)
        val start = System.nanoTime()
        try {
            val url = adapter.buildModelsUrl(server.serverUrl)
            val reqBuilder = Request.Builder().url(url).get()
            if (authToken.isNotBlank()) reqBuilder.header("Authorization", "Bearer $authToken")
            buildClient(server.timeoutSec, allowLocalHttp).newCall(reqBuilder.build()).execute().use { resp ->
                val latency = (System.nanoTime() - start) / 1_000_000L
                return@withContext if (resp.isSuccessful) {
                    ConnectionTestResult(ProviderStatus.CONNECTED, latency, resp.code, "Local server online (${latency}ms)")
                } else {
                    ConnectionTestResult(ProviderStatus.ERROR, latency, resp.code, "HTTP ${resp.code}")
                }
            }
        } catch (e: Exception) {
            val latency = (System.nanoTime() - start) / 1_000_000L
            ConnectionTestResult(
                ProviderStatus.DISCONNECTED,
                latency,
                message = MaskingUtils.sanitizeErrorMessage(e.message, listOf(authToken))
            )
        }
    }

    suspend fun discoverLocalModels(
        server: LocalAiServerEntity,
        authToken: String,
        allowLocalHttp: Boolean
    ): Result<List<ModelEntity>> = withContext(Dispatchers.IO) {
        val sec = NetworkAddressClassifier.validateEndpointSecurity(server.serverUrl, allowLocalHttp, resolver = dnsResolver)
        if (!sec.allowed) {
            return@withContext Result.failure(IllegalStateException(sec.errorMessage ?: "Local network access disabled or insecure remote URL"))
        }
        val format = if (server.engineType == LocalEngineType.OLLAMA) ApiFormat.OLLAMA else ApiFormat.OPENAI
        val adapter = ProviderAdapterRegistry.adapterFor(format)
        try {
            val url = adapter.buildModelsUrl(server.serverUrl)
            val reqBuilder = Request.Builder().url(url).get()
            if (authToken.isNotBlank()) reqBuilder.header("Authorization", "Bearer $authToken")
            buildClient(server.timeoutSec, allowLocalHttp).newCall(reqBuilder.build()).execute().use { resp ->
                val body = readBoundedBody(resp)
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IllegalStateException("HTTP ${resp.code}: ${MaskingUtils.sanitizeErrorMessage(body, listOf(authToken))}"))
                }
                Result.success(adapter.parseModelsResponse(body, "local:${server.id}", server.name, isLocal = true))
            }
        } catch (e: Exception) {
            Result.failure(IllegalStateException(MaskingUtils.sanitizeErrorMessage(e.message, listOf(authToken))))
        }
    }

    suspend fun executeChat(
        baseUrl: String,
        apiFormat: ApiFormat,
        apiKey: String,
        customHeadersJson: String,
        params: ChatRequestParams,
        allowLocalHttp: Boolean,
        timeoutSec: Int = 60,
        connectTimeoutSec: Int = minOf(timeoutSec, 10)
    ): ChatExecutionResult = withContext(Dispatchers.IO) {
        if (OpenCodeSelectorIds.isVirtualSelector(params.modelId)) {
            return@withContext ChatExecutionResult(
                success = false,
                statusCode = 400,
                errorCode = "UNSUPPORTED_VIRTUAL_ID",
                safeErrorMessage = "Gateway virtual selector '${params.modelId}' cannot be sent directly to an upstream provider.",
                retryable = false
            )
        }
        val sec = NetworkAddressClassifier.validateEndpointSecurity(baseUrl, allowLocalHttp, resolver = dnsResolver)
        if (!sec.allowed) {
            return@withContext ChatExecutionResult(
                success = false,
                statusCode = 400,
                errorCode = "INSECURE_URL",
                safeErrorMessage = sec.errorMessage ?: "Blocked non-HTTPS remote URL or unauthorized local HTTP request.",
                retryable = false
            )
        }
        val adapter = ProviderAdapterRegistry.adapterFor(apiFormat)
        val caps = params.capabilities ?: adapter.capabilitiesForModel(params.modelId)
        val start = System.nanoTime()
        try {
            val request = adapter.buildChatRequest(baseUrl, apiKey, customHeadersJson, params, caps, stream = false)
            val client = buildClient(
                timeoutSec = timeoutSec,
                allowLocalHttp = allowLocalHttp,
                connectTimeoutSec = connectTimeoutSec,
                callTimeoutSec = timeoutSec
            )
            val call = client.newCall(request)
            val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
                if (cause != null) call.cancel()
            }
            try {
                call.execute().use { resp ->
                    val latency = (System.nanoTime() - start) / 1_000_000L
                    val bodyStr = readBoundedBody(resp)
                    if (!resp.isSuccessful) {
                        val retryAfter = parseRetryAfterSeconds(resp)
                        return@withContext adapter.normalizeHttpError(resp.code, bodyStr, latency, apiKey)
                            .copy(retryAfterSec = retryAfter)
                    }
                    val parsed = adapter.parseChatResponse(bodyStr, latency, resp.code)
                    if (parsed.success && parsed.content.isEmpty() && parsed.toolCalls.isEmpty()) {
                        return@withContext ChatExecutionResult(
                            success = false,
                            latencyMs = latency,
                            statusCode = 502,
                            errorCode = "EMPTY_RESPONSE",
                            safeErrorMessage = "Upstream provider returned an empty response with no text or tool calls",
                            retryable = true
                        )
                    }
                    parsed
                }
            } finally {
                cancelHandle?.dispose()
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: IllegalArgumentException) {
            val latency = (System.nanoTime() - start) / 1_000_000L
            ChatExecutionResult(
                success = false,
                latencyMs = latency,
                statusCode = 400,
                errorCode = "UNSUPPORTED_CAPABILITY",
                safeErrorMessage = MaskingUtils.sanitizeErrorMessage(e.message, listOf(apiKey)),
                retryable = false
            )
        } catch (e: SocketTimeoutException) {
            val latency = (System.nanoTime() - start) / 1_000_000L
            ChatExecutionResult(false, latencyMs = latency, statusCode = 504, errorCode = "TIMEOUT", safeErrorMessage = "Request timed out", retryable = true)
        } catch (e: java.io.InterruptedIOException) {
            coroutineContext.ensureActive()
            val latency = (System.nanoTime() - start) / 1_000_000L
            ChatExecutionResult(false, latencyMs = latency, statusCode = 504, errorCode = "TIMEOUT", safeErrorMessage = "Request timed out", retryable = true)
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            val latency = (System.nanoTime() - start) / 1_000_000L
            ChatExecutionResult(
                success = false,
                latencyMs = latency,
                statusCode = 502,
                errorCode = "CONNECTION_ERROR",
                safeErrorMessage = MaskingUtils.sanitizeErrorMessage(e.message, listOf(apiKey)),
                retryable = true
            )
        }
    }

    fun streamChat(
        baseUrl: String,
        apiFormat: ApiFormat,
        apiKey: String,
        customHeadersJson: String,
        params: ChatRequestParams,
        allowLocalHttp: Boolean,
        timeoutSec: Int = 90,
        connectTimeoutSec: Int = minOf(timeoutSec, 10)
    ): Flow<StreamEvent> = callbackFlow {
        if (OpenCodeSelectorIds.isVirtualSelector(params.modelId)) {
            send(
                StreamEvent.Failed(
                    ChatExecutionResult(
                        success = false,
                        statusCode = 400,
                        errorCode = "UNSUPPORTED_VIRTUAL_ID",
                        safeErrorMessage = "Gateway virtual selector '${params.modelId}' cannot be sent directly to an upstream provider.",
                        retryable = false
                    ),
                    emittedAnyChunk = false
                )
            )
            close()
            return@callbackFlow
        }

        val sec = NetworkAddressClassifier.validateEndpointSecurity(baseUrl, allowLocalHttp, resolver = dnsResolver)
        if (!sec.allowed) {
            send(
                StreamEvent.Failed(
                    ChatExecutionResult(
                        false,
                        statusCode = 400,
                        errorCode = "INSECURE_URL",
                        safeErrorMessage = sec.errorMessage ?: "Blocked insecure endpoint",
                        retryable = false
                    ),
                    emittedAnyChunk = false
                )
            )
            close()
            return@callbackFlow
        }

        val adapter = ProviderAdapterRegistry.adapterFor(apiFormat)
        val caps = params.capabilities ?: adapter.capabilitiesForModel(params.modelId)

        if (!caps.supportsStreaming) {
            val job = launch(Dispatchers.IO) {
                val res = executeChat(
                    baseUrl = baseUrl,
                    apiFormat = apiFormat,
                    apiKey = apiKey,
                    customHeadersJson = customHeadersJson,
                    params = params.copy(stream = false),
                    allowLocalHttp = allowLocalHttp,
                    timeoutSec = timeoutSec,
                    connectTimeoutSec = connectTimeoutSec
                )
                if (res.success) {
                    if (res.content.isNotEmpty() || res.toolCalls.isNotEmpty()) {
                        val tcDeltas = res.toolCalls.map {
                            ToolCallDelta(it.index, it.id, it.name, it.argumentsJson)
                        }
                        send(StreamEvent.Delta(text = res.content, toolCallDeltas = tcDeltas, finishReason = res.finishReason))
                    }
                    send(StreamEvent.Completed(res))
                } else {
                    send(StreamEvent.Failed(res, emittedAnyChunk = false))
                }
                close()
            }
            awaitClose { job.cancel() }
            return@callbackFlow
        }

        val initialRequest = try {
            adapter.buildChatRequest(baseUrl, apiKey, customHeadersJson, params, caps, stream = true)
        } catch (e: IllegalArgumentException) {
            send(
                StreamEvent.Failed(
                    ChatExecutionResult(
                        success = false,
                        statusCode = 400,
                        errorCode = "UNSUPPORTED_CAPABILITY",
                        safeErrorMessage = MaskingUtils.sanitizeErrorMessage(e.message, listOf(apiKey)),
                        retryable = false
                    ),
                    emittedAnyChunk = false
                )
            )
            close()
            return@callbackFlow
        }
        val streamReadTimeoutSec = if (timeoutSec >= 15) maxOf(timeoutSec, 45) else timeoutSec
        val client = buildClient(
            timeoutSec = streamReadTimeoutSec,
            allowLocalHttp = allowLocalHttp,
            connectTimeoutSec = connectTimeoutSec,
            callTimeoutSec = null
        )
        val activeCallRef = AtomicReference<okhttp3.Call?>(null)
        val emittedAny = AtomicBoolean(false)
        val terminalSent = AtomicBoolean(false)
        val timedOutBeforeFirstChunk = AtomicBoolean(false)
        val start = System.nanoTime()

        val preStreamTimeoutJob = launch {
            delay(timeoutSec.toLong().coerceAtLeast(2) * 1000L + 200L)
            if (!emittedAny.get() && terminalSent.compareAndSet(false, true)) {
                timedOutBeforeFirstChunk.set(true)
                activeCallRef.getAndSet(null)?.cancel()
                val latency = (System.nanoTime() - start) / 1_000_000L
                send(
                    StreamEvent.Failed(
                        ChatExecutionResult(
                            success = false,
                            latencyMs = latency,
                            statusCode = 504,
                            errorCode = "TIMEOUT",
                            safeErrorMessage = "Upstream stream timed out before producing output (${timeoutSec}s)",
                            retryable = true
                        ),
                        emittedAnyChunk = false
                    )
                )
                close()
            }
        }

        suspend fun sendTerminal(event: StreamEvent) {
            preStreamTimeoutJob.cancel()
            if (terminalSent.compareAndSet(false, true)) {
                send(event)
            }
        }

        val job = launch(Dispatchers.IO) {
            try {
                val firstCall = client.newCall(initialRequest)
                activeCallRef.set(firstCall)
                var resp = firstCall.execute()
                if (!resp.isSuccessful &&
                    resp.code == 400 &&
                    (apiFormat == ApiFormat.OPENAI || apiFormat == ApiFormat.HUGGINGFACE)
                ) {
                    val errBody = try {
                        readBoundedBody(resp)
                    } finally {
                        resp.close()
                    }
                    val mentionsStreamOptions = errBody.contains("stream_options", ignoreCase = true) ||
                        errBody.contains("include_usage", ignoreCase = true)
                    val fallbackReq = if (mentionsStreamOptions) buildRequestWithoutStreamOptions(initialRequest) else null
                    if (fallbackReq != null) {
                        val retryCall = client.newCall(fallbackReq)
                        activeCallRef.set(retryCall)
                        resp = retryCall.execute()
                    } else {
                        val latency = (System.nanoTime() - start) / 1_000_000L
                        sendTerminal(StreamEvent.Failed(adapter.normalizeHttpError(400, errBody, latency, apiKey), emittedAnyChunk = false))
                        close()
                        return@launch
                    }
                }

                resp.use { okResp ->
                    val latency = (System.nanoTime() - start) / 1_000_000L
                    if (!okResp.isSuccessful) {
                        val errBody = readBoundedBody(okResp)
                        val retryAfter = parseRetryAfterSeconds(okResp)
                        sendTerminal(
                            StreamEvent.Failed(
                                adapter.normalizeHttpError(okResp.code, errBody, latency, apiKey)
                                    .copy(retryAfterSec = retryAfter),
                                emittedAnyChunk = false
                            )
                        )
                        close()
                        return@launch
                    }

                    val contentType = okResp.header("Content-Type").orEmpty().lowercase()
                    if (contentType.contains("application/json") && !contentType.contains("text/event-stream")) {
                        val jsonBody = readBoundedBody(okResp)
                        val nonStreamRes = adapter.parseChatResponse(jsonBody, latency, okResp.code)
                        if (!nonStreamRes.success) {
                            sendTerminal(StreamEvent.Failed(nonStreamRes, emittedAnyChunk = false))
                            close()
                            return@launch
                        }
                        if (nonStreamRes.content.isEmpty() && nonStreamRes.toolCalls.isEmpty()) {
                            sendTerminal(
                                StreamEvent.Failed(
                                    ChatExecutionResult(
                                        success = false,
                                        latencyMs = latency,
                                        statusCode = 502,
                                        errorCode = "EMPTY_RESPONSE",
                                        safeErrorMessage = "Upstream returned empty JSON completion without text or tool calls",
                                        retryable = true
                                    ),
                                    emittedAnyChunk = false
                                )
                            )
                            close()
                            return@launch
                        }
                        emittedAny.set(true)
                        preStreamTimeoutJob.cancel()
                        val tcDeltas = nonStreamRes.toolCalls.map {
                            ToolCallDelta(it.index, it.id, it.name, it.argumentsJson)
                        }
                        send(StreamEvent.Delta(text = nonStreamRes.content, toolCallDeltas = tcDeltas, finishReason = nonStreamRes.finishReason))
                        sendTerminal(StreamEvent.Completed(nonStreamRes))
                        close()
                        return@launch
                    }

                    val stream = okResp.body?.byteStream()
                    if (stream == null) {
                        sendTerminal(
                            StreamEvent.Failed(
                                ChatExecutionResult(false, latencyMs = latency, statusCode = 502, errorCode = "EMPTY_STREAM", safeErrorMessage = "Empty response stream", retryable = true),
                                emittedAnyChunk = false
                            )
                        )
                        close()
                        return@launch
                    }

                    val accumulated = StringBuilder()
                    val streamState = AdapterStreamState()
                    val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
                    var currentEventType: String? = null
                    var sawAnySseLine = false
                    val nonSseBuffer = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        ensureActive()
                        if (timedOutBeforeFirstChunk.get()) break
                        val rawLine = line ?: continue
                        val trimmed = rawLine.trim()
                        if (trimmed.isEmpty()) {
                            currentEventType = null
                            continue
                        }
                        if (trimmed.startsWith("event:")) {
                            sawAnySseLine = true
                            currentEventType = trimmed.removePrefix("event:").trim()
                            continue
                        }
                        if (trimmed.startsWith(":")) {
                            sawAnySseLine = true
                            continue
                        }
                        if (trimmed == "data: [DONE]" || trimmed == "data:[DONE]" || trimmed == "[DONE]") {
                            sawAnySseLine = true
                            break
                        }

                        val jsonText = when {
                            trimmed.startsWith("data:") -> {
                                sawAnySseLine = true
                                trimmed.removePrefix("data:").trim()
                            }
                            apiFormat == ApiFormat.OLLAMA -> {
                                sawAnySseLine = true
                                trimmed
                            }
                            else -> {
                                if (!sawAnySseLine && nonSseBuffer.length < 512 * 1024) {
                                    nonSseBuffer.append(rawLine).append('\n')
                                }
                                continue
                            }
                        }
                        if (jsonText.isEmpty() || jsonText == "[DONE]") continue

                        val parsed = runCatching { adapter.parseStreamLine(currentEventType, jsonText, streamState) }.getOrNull()
                        val inlineObj = if (parsed?.errorMessage == null) {
                            runCatching { org.json.JSONObject(jsonText) }.getOrNull()
                        } else null
                        val parsedInlineFailure = inlineObj?.let { obj ->
                            AdapterHelper.parseInlineErrorIfPresent(
                                root = obj,
                                httpCode = 502,
                                latencyMs = (System.nanoTime() - start) / 1_000_000L,
                                apiKey = apiKey
                            )
                        }
                        val inlineError = parsed?.errorMessage ?: parsedInlineFailure?.safeErrorMessage ?: inlineObj?.let { obj ->
                            when {
                                currentEventType == "error" || obj.optString("type") == "error" ->
                                    obj.optJSONObject("error")?.optString("message")?.ifBlank { null }
                                        ?: obj.optString("message").ifBlank { null }
                                        ?: "Upstream stream error"
                                else -> null
                            }
                        }
                        if (inlineError != null) {
                            val errLatency = (System.nanoTime() - start) / 1_000_000L
                            val hasEmitted = emittedAny.get()
                            val failureResult = if (parsedInlineFailure != null) {
                                parsedInlineFailure.copy(
                                    latencyMs = errLatency,
                                    retryable = !hasEmitted && parsedInlineFailure.retryable
                                )
                            } else {
                                ChatExecutionResult(
                                    success = false,
                                    latencyMs = errLatency,
                                    statusCode = 502,
                                    errorCode = "STREAM_ERROR",
                                    safeErrorMessage = MaskingUtils.sanitizeErrorMessage(inlineError, listOf(apiKey)),
                                    retryable = !hasEmitted
                                )
                            }
                            sendTerminal(
                                StreamEvent.Failed(
                                    failureResult,
                                    emittedAnyChunk = hasEmitted
                                )
                            )
                            close()
                            return@launch
                        }
                        if (parsed != null) {
                            if (parsed.textDelta.isNotEmpty() || parsed.toolCallDeltas.isNotEmpty()) {
                                if (timedOutBeforeFirstChunk.get() || terminalSent.get()) break
                                emittedAny.set(true)
                                preStreamTimeoutJob.cancel()
                                accumulated.append(parsed.textDelta)
                                send(
                                    StreamEvent.Delta(
                                        text = parsed.textDelta,
                                        toolCallDeltas = parsed.toolCallDeltas,
                                        finishReason = parsed.finishReason
                                    )
                                )
                            }
                            if (parsed.isTerminal) break
                        }
                    }

                    if (timedOutBeforeFirstChunk.get() || terminalSent.get()) {
                        return@launch
                    }

                    val totalLatency = (System.nanoTime() - start) / 1_000_000L
                    if (!sawAnySseLine && !emittedAny.get() && nonSseBuffer.isNotBlank()) {
                        val fallbackRes = adapter.parseChatResponse(nonSseBuffer.toString(), totalLatency, okResp.code)
                        if (!fallbackRes.success) {
                            sendTerminal(StreamEvent.Failed(fallbackRes, emittedAnyChunk = false))
                            close()
                            return@launch
                        }
                        if (fallbackRes.content.isNotEmpty() || fallbackRes.toolCalls.isNotEmpty()) {
                            emittedAny.set(true)
                            preStreamTimeoutJob.cancel()
                            val tcDeltas = fallbackRes.toolCalls.map {
                                ToolCallDelta(it.index, it.id, it.name, it.argumentsJson)
                            }
                            send(StreamEvent.Delta(text = fallbackRes.content, toolCallDeltas = tcDeltas, finishReason = fallbackRes.finishReason))
                            sendTerminal(StreamEvent.Completed(fallbackRes))
                            close()
                            return@launch
                        }
                    }

                    val finalizedTools = streamState.finalizedToolCalls()
                    if (!emittedAny.get() && accumulated.isEmpty() && finalizedTools.isEmpty()) {
                        sendTerminal(
                            StreamEvent.Failed(
                                ChatExecutionResult(
                                    success = false,
                                    latencyMs = totalLatency,
                                    statusCode = 502,
                                    errorCode = "EMPTY_RESPONSE",
                                    safeErrorMessage = "Upstream stream finished without generating any response or tool calls",
                                    retryable = true
                                ),
                                emittedAnyChunk = false
                            )
                        )
                        close()
                        return@launch
                    }

                    val finalReason = streamState.finishReason
                        ?: if (finalizedTools.isNotEmpty()) "tool_calls" else "stop"
                    sendTerminal(
                        StreamEvent.Completed(
                            ChatExecutionResult(
                                success = true,
                                content = accumulated.toString(),
                                inputTokens = streamState.inputTokens,
                                outputTokens = streamState.outputTokens,
                                latencyMs = totalLatency,
                                statusCode = okResp.code,
                                finishReason = finalReason,
                                toolCalls = finalizedTools,
                                usageReported = streamState.inputTokens > 0 || streamState.outputTokens > 0
                            )
                        )
                    )
                }
            } catch (ce: CancellationException) {
                preStreamTimeoutJob.cancel()
                activeCallRef.getAndSet(null)?.cancel()
                throw ce
            } catch (e: SocketTimeoutException) {
                if (!timedOutBeforeFirstChunk.get()) {
                    val latency = (System.nanoTime() - start) / 1_000_000L
                    val hasEmitted = emittedAny.get()
                    sendTerminal(
                        StreamEvent.Failed(
                            ChatExecutionResult(
                                success = false,
                                latencyMs = latency,
                                statusCode = 504,
                                errorCode = "TIMEOUT",
                                safeErrorMessage = "Upstream stream timed out",
                                retryable = !hasEmitted
                            ),
                            emittedAnyChunk = hasEmitted
                        )
                    )
                }
            } catch (e: Exception) {
                if (!timedOutBeforeFirstChunk.get()) {
                    val latency = (System.nanoTime() - start) / 1_000_000L
                    val hasEmitted = emittedAny.get()
                    sendTerminal(
                        StreamEvent.Failed(
                            ChatExecutionResult(
                                success = false,
                                latencyMs = latency,
                                statusCode = 502,
                                errorCode = "STREAM_ERROR",
                                safeErrorMessage = MaskingUtils.sanitizeErrorMessage(e.message, listOf(apiKey)),
                                retryable = !hasEmitted
                            ),
                            emittedAnyChunk = hasEmitted
                        )
                    )
                }
            } finally {
                preStreamTimeoutJob.cancel()
            }
            close()
        }

        awaitClose {
            preStreamTimeoutJob.cancel()
            job.cancel()
            activeCallRef.getAndSet(null)?.cancel()
        }
    }

    suspend fun executeRawGatewayForward(
        url: String,
        apiKey: String,
        apiFormat: ApiFormat,
        customHeadersJson: String,
        bodyJson: String,
        allowLocalHttp: Boolean,
        timeoutSec: Int = 60
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val sec = NetworkAddressClassifier.validateEndpointSecurity(url, allowLocalHttp, resolver = dnsResolver)
        if (!sec.allowed) {
            return@withContext 400 to """{"error":{"message":"${(sec.errorMessage ?: "Blocked insecure endpoint").replace("\"", "'")}"}}"""
        }
        val adapter = ProviderAdapterRegistry.adapterFor(apiFormat)
        try {
            val builder = Request.Builder()
                .url(url)
                .post(bodyJson.toRequestBody(jsonMedia))
            adapter.applyAuthHeaders(builder, apiKey)
            AdapterHelper.applyCustomHeaders(builder, customHeadersJson)
            buildClient(timeoutSec, allowLocalHttp).newCall(builder.build()).execute().use { resp ->
                resp.code to readBoundedBody(resp)
            }
        } catch (e: Exception) {
            502 to """{"error":{"message":"${MaskingUtils.sanitizeErrorMessage(e.message, listOf(apiKey)).replace("\"", "'")}"}}"""
        }
    }
}
