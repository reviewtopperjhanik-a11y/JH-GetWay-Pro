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
import kotlinx.coroutines.channels.awaitClose
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

data class ConnectionTestResult(
    val status: ProviderStatus,
    val latencyMs: Long,
    val httpCode: Int? = null,
    val message: String
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
    val actualBilledCostUsd: Double? = null
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

    companion object {
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024 // 2 MB cap for non-streaming bodies
    }

    private fun buildClient(timeoutSec: Int, allowLocalHttp: Boolean): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeoutSec.toLong().coerceIn(5, 120), TimeUnit.SECONDS)
        .readTimeout(timeoutSec.toLong().coerceIn(10, 300), TimeUnit.SECONDS)
        .writeTimeout(timeoutSec.toLong().coerceIn(10, 120), TimeUnit.SECONDS)
        .dns(NetworkAddressClassifier.pinnedSafeDns(allowLocalHttp, dnsResolver))
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private fun readBoundedBody(resp: okhttp3.Response): String {
        val body = resp.body ?: return ""
        val source = body.source()
        source.request((MAX_RESPONSE_BYTES + 1).toLong())
        val buffer = source.buffer
        val readCount = minOf(buffer.size, MAX_RESPONSE_BYTES.toLong())
        return buffer.clone().readString(readCount, Charsets.UTF_8)
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
                        message = "Rate limited (HTTP 429)"
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
        timeoutSec: Int = 60
    ): ChatExecutionResult = withContext(Dispatchers.IO) {
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
            buildClient(timeoutSec, allowLocalHttp).newCall(request).execute().use { resp ->
                val latency = (System.nanoTime() - start) / 1_000_000L
                val bodyStr = readBoundedBody(resp)
                if (!resp.isSuccessful) {
                    return@withContext adapter.normalizeHttpError(resp.code, bodyStr, latency, apiKey)
                }
                adapter.parseChatResponse(bodyStr, latency, resp.code)
            }
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
        } catch (e: Exception) {
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
        timeoutSec: Int = 90
    ): Flow<StreamEvent> = callbackFlow {
        val sec = NetworkAddressClassifier.validateEndpointSecurity(baseUrl, allowLocalHttp, resolver = dnsResolver)
        if (!sec.allowed) {
            trySend(
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
                val res = executeChat(baseUrl, apiFormat, apiKey, customHeadersJson, params.copy(stream = false), allowLocalHttp, timeoutSec)
                if (res.success) {
                    if (res.content.isNotEmpty() || res.toolCalls.isNotEmpty()) {
                        val tcDeltas = res.toolCalls.map {
                            ToolCallDelta(it.index, it.id, it.name, it.argumentsJson)
                        }
                        trySend(StreamEvent.Delta(text = res.content, toolCallDeltas = tcDeltas, finishReason = res.finishReason))
                    }
                    trySend(StreamEvent.Completed(res))
                } else {
                    trySend(StreamEvent.Failed(res, emittedAnyChunk = false))
                }
                close()
            }
            awaitClose { job.cancel() }
            return@callbackFlow
        }

        val request = try {
            adapter.buildChatRequest(baseUrl, apiKey, customHeadersJson, params, caps, stream = true)
        } catch (e: IllegalArgumentException) {
            trySend(
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
        val call = buildClient(timeoutSec, allowLocalHttp).newCall(request)

        val job = launch(Dispatchers.IO) {
            val start = System.nanoTime()
            var emittedAny = false
            try {
                call.execute().use { resp ->
                    val latency = (System.nanoTime() - start) / 1_000_000L
                    if (!resp.isSuccessful) {
                        val errBody = readBoundedBody(resp)
                        trySend(StreamEvent.Failed(adapter.normalizeHttpError(resp.code, errBody, latency, apiKey), emittedAnyChunk = false))
                        close()
                        return@launch
                    }

                    val stream = resp.body?.byteStream()
                    if (stream == null) {
                        trySend(
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
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        ensureActive()
                        val rawLine = line ?: continue
                        val trimmed = rawLine.trim()
                        if (trimmed.isEmpty()) {
                            currentEventType = null
                            continue
                        }
                        if (trimmed.startsWith("event:")) {
                            currentEventType = trimmed.removePrefix("event:").trim()
                            continue
                        }
                        if (trimmed == "data: [DONE]" || trimmed == "data:[DONE]" || trimmed == "[DONE]") break

                        val jsonText = when {
                            trimmed.startsWith("data:") -> trimmed.removePrefix("data:").trim()
                            apiFormat == ApiFormat.OLLAMA -> trimmed
                            else -> continue
                        }
                        if (jsonText.isEmpty() || jsonText == "[DONE]") continue

                        val parsed = runCatching { adapter.parseStreamLine(currentEventType, jsonText, streamState) }.getOrNull()
                        val inlineError = parsed?.errorMessage ?: runCatching {
                            val obj = org.json.JSONObject(jsonText)
                            when {
                                currentEventType == "error" || obj.optString("type") == "error" ->
                                    obj.optJSONObject("error")?.optString("message")?.ifBlank { null }
                                        ?: obj.optString("message").ifBlank { null }
                                        ?: "Upstream stream error"
                                obj.has("error") && !obj.isNull("error") ->
                                    obj.optJSONObject("error")?.optString("message")?.ifBlank { null }
                                        ?: obj.optString("error").ifBlank { null }
                                        ?: "Upstream stream error"
                                else -> null
                            }
                        }.getOrNull()
                        if (inlineError != null) {
                            val errLatency = (System.nanoTime() - start) / 1_000_000L
                            trySend(
                                StreamEvent.Failed(
                                    ChatExecutionResult(
                                        success = false,
                                        latencyMs = errLatency,
                                        statusCode = 502,
                                        errorCode = "STREAM_ERROR",
                                        safeErrorMessage = MaskingUtils.sanitizeErrorMessage(inlineError, listOf(apiKey)),
                                        retryable = !emittedAny
                                    ),
                                    emittedAnyChunk = emittedAny
                                )
                            )
                            close()
                            return@launch
                        }
                        if (parsed != null) {
                            if (parsed.textDelta.isNotEmpty() || parsed.toolCallDeltas.isNotEmpty()) {
                                emittedAny = true
                                accumulated.append(parsed.textDelta)
                                trySend(
                                    StreamEvent.Delta(
                                        text = parsed.textDelta,
                                        toolCallDeltas = parsed.toolCallDeltas,
                                        finishReason = parsed.finishReason
                                    )
                                )
                            } else if (parsed.finishReason != null && !emittedAny) {
                                // Track terminal finishReason on state even without delta text
                            }
                            if (parsed.isTerminal) break
                        }
                    }

                    val totalLatency = (System.nanoTime() - start) / 1_000_000L
                    val finalizedTools = streamState.finalizedToolCalls()
                    val finalReason = streamState.finishReason
                        ?: if (finalizedTools.isNotEmpty()) "tool_calls" else "stop"
                    trySend(
                        StreamEvent.Completed(
                            ChatExecutionResult(
                                success = true,
                                content = accumulated.toString(),
                                inputTokens = streamState.inputTokens,
                                outputTokens = streamState.outputTokens,
                                latencyMs = totalLatency,
                                statusCode = resp.code,
                                finishReason = finalReason,
                                toolCalls = finalizedTools,
                                usageReported = streamState.inputTokens > 0 || streamState.outputTokens > 0
                            )
                        )
                    )
                }
            } catch (ce: CancellationException) {
                call.cancel()
                throw ce
            } catch (e: Exception) {
                val latency = (System.nanoTime() - start) / 1_000_000L
                trySend(
                    StreamEvent.Failed(
                        ChatExecutionResult(
                            success = false,
                            latencyMs = latency,
                            statusCode = 502,
                            errorCode = "STREAM_ERROR",
                            safeErrorMessage = MaskingUtils.sanitizeErrorMessage(e.message, listOf(apiKey)),
                            retryable = !emittedAny
                        ),
                        emittedAnyChunk = emittedAny
                    )
                )
            }
            close()
        }

        awaitClose {
            job.cancel()
            call.cancel()
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
