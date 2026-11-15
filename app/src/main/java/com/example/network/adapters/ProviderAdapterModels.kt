package com.example.network.adapters

import com.example.core.security.MaskingUtils
import com.example.data.local.ApiFormat
import com.example.data.local.ModelEntity
import com.example.network.ChatExecutionResult
import com.example.network.ChatRequestParams
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

sealed interface ContentPart {
    data class Text(val text: String) : ContentPart
    data class ImageUrl(val url: String, val detail: String? = null) : ContentPart
    data class FileAttachment(
        val fileName: String,
        val mimeType: String,
        val base64Data: String? = null,
        val textContent: String? = null,
        val sizeBytes: Long = 0L
    ) : ContentPart
    data class ToolUse(val id: String, val name: String, val argumentsJson: String) : ContentPart
    data class ToolResult(val toolCallId: String, val content: String, val isError: Boolean = false) : ContentPart
}

data class ToolCallPayload(
    val id: String,
    val name: String,
    val argumentsJson: String,
    val index: Int = 0
)

data class ToolCallDelta(
    val index: Int,
    val id: String? = null,
    val name: String? = null,
    val argumentsChunk: String? = null
)

data class NormalizedToolDefinition(
    val name: String,
    val description: String = "",
    val parametersSchema: JSONObject = JSONObject().put("type", "object").put("properties", JSONObject()),
    val strict: Boolean? = null
)

sealed interface NormalizedToolChoice {
    data object Auto : NormalizedToolChoice
    data object None : NormalizedToolChoice
    data object Required : NormalizedToolChoice
    data class SpecificFunction(val name: String) : NormalizedToolChoice
}

data class NormalizedResponseFormat(
    val type: String, // "text", "json_object", "json_schema"
    val schemaName: String? = null,
    val schemaJson: JSONObject? = null,
    val strict: Boolean? = null
)

data class ProviderCapabilities(
    val supportsTools: Boolean = true,
    val supportsToolChoice: Boolean = true,
    val supportsJsonOutput: Boolean = true,
    val supportsStreaming: Boolean = true,
    val supportsSystemRole: Boolean = true,
    val supportsDeveloperRole: Boolean = false,
    val supportsVision: Boolean = true,
    val supportsStopSequences: Boolean = true,
    val supportsDocumentFiles: Boolean = false,
    val supportedDocumentMimeTypes: Set<String> = emptySet()
)

class AdapterStreamState {
    var inputTokens: Int = 0
    var outputTokens: Int = 0
    var finishReason: String? = null
    val anthropicToolBlockIndices = mutableMapOf<Int, Int>()
    var nextToolCallIndex: Int = 0
    val accumulatedToolCalls = linkedMapOf<Int, MutableToolCallBuilder>()

    class MutableToolCallBuilder(
        val index: Int,
        var id: String = "",
        var name: String = "",
        val args: StringBuilder = StringBuilder()
    ) {
        fun toPayload(): ToolCallPayload = ToolCallPayload(
            id = id.ifBlank { "call_$index" },
            name = name,
            argumentsJson = args.toString().ifBlank { "{}" },
            index = index
        )
    }

    fun recordToolDeltas(deltas: List<ToolCallDelta>) {
        for (d in deltas) {
            val builder = accumulatedToolCalls.getOrPut(d.index) {
                MutableToolCallBuilder(index = d.index)
            }
            if (!d.id.isNullOrBlank()) builder.id = d.id
            if (!d.name.isNullOrBlank()) builder.name = d.name
            if (!d.argumentsChunk.isNullOrEmpty()) builder.args.append(d.argumentsChunk)
        }
    }

    fun finalizedToolCalls(): List<ToolCallPayload> =
        accumulatedToolCalls.entries.sortedBy { it.key }.map { it.value.toPayload() }
}

data class NormalizedStreamDelta(
    val textDelta: String = "",
    val toolCallDeltas: List<ToolCallDelta> = emptyList(),
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val finishReason: String? = null,
    val isTerminal: Boolean = false,
    val errorMessage: String? = null
)

interface ProviderAdapter {
    val apiFormat: ApiFormat

    fun capabilitiesForModel(
        modelId: String,
        modelEntity: ModelEntity? = null
    ): ProviderCapabilities

    fun buildModelsUrl(baseUrl: String): String

    fun applyAuthHeaders(
        builder: Request.Builder,
        apiKey: String
    )

    fun parseModelsResponse(
        bodyStr: String,
        providerId: String,
        providerName: String,
        isLocal: Boolean
    ): List<ModelEntity>

    fun buildChatRequest(
        baseUrl: String,
        apiKey: String,
        customHeadersJson: String,
        params: ChatRequestParams,
        capabilities: ProviderCapabilities,
        stream: Boolean
    ): Request

    fun parseChatResponse(
        bodyStr: String,
        latencyMs: Long,
        statusCode: Int
    ): ChatExecutionResult

    fun parseStreamLine(
        eventType: String?,
        dataJson: String,
        state: AdapterStreamState
    ): NormalizedStreamDelta

    fun normalizeHttpError(
        httpCode: Int,
        bodyStr: String,
        latencyMs: Long,
        apiKey: String
    ): ChatExecutionResult
}

internal object AdapterHelper {
    val jsonMedia = "application/json; charset=utf-8".toMediaType()

    fun applyCustomHeaders(builder: Request.Builder, customHeadersJson: String) {
        if (customHeadersJson.isBlank()) return
        runCatching {
            val obj = JSONObject(customHeadersJson)
            obj.keys().forEach { key ->
                val lower = key.lowercase()
                if (lower != "host" && lower != "content-length") {
                    builder.header(key, obj.optString(key))
                }
            }
        }
    }

    fun extractUpstreamErrorMessage(bodyStr: String, httpCode: Int, apiKey: String): String {
        val parsed = runCatching {
            val root = JSONObject(bodyStr)
            root.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: root.optString("message").takeIf { it.isNotBlank() }
                ?: root.optString("detail").takeIf { it.isNotBlank() }
        }.getOrNull()
        val raw = parsed ?: bodyStr.ifBlank { "Upstream provider returned HTTP $httpCode" }
        return MaskingUtils.sanitizeErrorMessage(raw, listOf(apiKey))
    }

    fun defaultNormalizeHttpError(
        httpCode: Int,
        bodyStr: String,
        latencyMs: Long,
        apiKey: String
    ): ChatExecutionResult {
        val safeMsg = extractUpstreamErrorMessage(bodyStr, httpCode, apiKey)
        val (errCode, retryable) = when (httpCode) {
            400 -> "INVALID_REQUEST" to false
            401, 403 -> "INVALID_CREDENTIAL" to true
            404 -> "MODEL_UNAVAILABLE" to true
            408 -> "TIMEOUT" to true
            429 -> "RATE_LIMITED" to true
            in 500..599 -> "PROVIDER_SERVER_ERROR" to true
            else -> "HTTP_$httpCode" to false
        }
        return ChatExecutionResult(
            success = false,
            latencyMs = latencyMs,
            statusCode = httpCode,
            errorCode = errCode,
            safeErrorMessage = safeMsg,
            retryable = retryable
        )
    }

    fun effectiveTools(params: ChatRequestParams): List<NormalizedToolDefinition> {
        if (params.tools.isNotEmpty()) return params.tools
        val arr = params.toolsJson ?: return emptyList()
        val list = mutableListOf<NormalizedToolDefinition>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val fn = obj.optJSONObject("function") ?: obj
            val name = fn.optString("name").trim()
            if (name.isBlank()) continue
            list += NormalizedToolDefinition(
                name = name,
                description = fn.optString("description"),
                parametersSchema = fn.optJSONObject("parameters")
                    ?: JSONObject().put("type", "object").put("properties", JSONObject()),
                strict = if (fn.has("strict") && !fn.isNull("strict")) fn.optBoolean("strict") else null
            )
        }
        return list
    }

    fun plainTextFromMessageParts(parts: List<ContentPart>, fallbackText: String): String {
        if (parts.isEmpty()) return fallbackText
        val joined = parts.mapNotNull {
            when (it) {
                is ContentPart.Text -> it.text
                is ContentPart.FileAttachment -> it.textContent?.let { txt ->
                    "Attached file (${it.fileName}, ${it.mimeType}):\n```\n$txt\n```"
                }
                is ContentPart.ToolResult -> it.content
                else -> null
            }
        }.joinToString("\n")
        return joined.ifEmpty { fallbackText }
    }
}

object ProviderAdapterRegistry {
    private val openAiAdapter = OpenAiProviderAdapter()
    private val geminiAdapter = GeminiProviderAdapter()
    private val anthropicAdapter = AnthropicProviderAdapter()
    private val cohereAdapter = CohereProviderAdapter()
    private val ollamaAdapter = OllamaProviderAdapter()

    fun adapterFor(format: ApiFormat): ProviderAdapter = when (format) {
        ApiFormat.GEMINI -> geminiAdapter
        ApiFormat.ANTHROPIC -> anthropicAdapter
        ApiFormat.COHERE -> cohereAdapter
        ApiFormat.OLLAMA -> ollamaAdapter
        ApiFormat.OPENAI, ApiFormat.HUGGINGFACE -> openAiAdapter
    }
}
