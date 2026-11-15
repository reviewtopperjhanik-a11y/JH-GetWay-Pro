package com.example.network.adapters

import com.example.data.local.AccessTier
import com.example.data.local.ApiFormat
import com.example.data.local.ModelEntity
import com.example.network.ChatExecutionResult
import com.example.network.ChatRequestParams
import com.example.network.ProviderPresets
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class AnthropicProviderAdapter : ProviderAdapter {
    override val apiFormat: ApiFormat = ApiFormat.ANTHROPIC

    override fun capabilitiesForModel(modelId: String, modelEntity: ModelEntity?): ProviderCapabilities {
        val lower = modelId.lowercase()
        val inferredVision = lower.contains("claude-3") ||
            lower.contains("claude-sonnet-4") ||
            lower.contains("claude-opus-4") ||
            lower.contains("claude-haiku-4") ||
            lower.contains("claude-4") ||
            lower.contains("vision")
        return ProviderCapabilities(
            supportsTools = modelEntity?.supportsTools ?: true,
            supportsToolChoice = modelEntity?.supportsTools ?: true,
            supportsJsonOutput = false,
            supportsStreaming = modelEntity?.supportsStreaming ?: true,
            supportsSystemRole = true,
            supportsDeveloperRole = false,
            supportsVision = modelEntity == null || modelEntity.supportsVision || inferredVision,
            supportsStopSequences = true,
            supportsDocumentFiles = true,
            supportedDocumentMimeTypes = setOf(
                "application/pdf",
                "text/plain",
                "text/markdown",
                "text/csv",
                "application/json"
            )
        )
    }

    private fun v1Base(baseUrl: String): String {
        val clean = baseUrl.trimEnd('/')
        return when {
            clean.endsWith("/messages") -> clean.removeSuffix("/messages")
            clean.endsWith("/v1") -> clean
            else -> "$clean/v1"
        }
    }

    override fun buildModelsUrl(baseUrl: String): String = "${v1Base(baseUrl)}/models"

    override fun applyAuthHeaders(builder: Request.Builder, apiKey: String) {
        if (apiKey.isNotBlank()) {
            builder.header("x-api-key", apiKey)
        }
        builder.header("anthropic-version", "2023-06-01")
    }

    override fun parseModelsResponse(
        bodyStr: String,
        providerId: String,
        providerName: String,
        isLocal: Boolean
    ): List<ModelEntity> {
        val root = JSONObject(bodyStr)
        val arr = root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
        val result = mutableListOf<ModelEntity>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id").ifBlank { obj.optString("name") }
            if (id.isBlank()) continue
            val display = obj.optString("display_name").ifBlank { id }
            val pricing = if (isLocal) null else ProviderPresets.lookupPricing(id)
            result += ModelEntity(
                id = "$providerId::$id",
                modelId = id,
                displayName = display,
                providerId = providerId,
                providerName = providerName,
                modelType = ProviderPresets.inferModelType(id),
                capabilitiesCsv = "CHAT,STREAMING,TOOLS,VISION,PDF",
                contextWindow = obj.optInt("context_length", 200_000),
                supportsTools = true,
                supportsStreaming = true,
                isLocal = isLocal,
                inputPricePer1M = pricing?.inputPer1M,
                outputPricePer1M = pricing?.outputPer1M,
                pricingUpdatedAt = pricing?.updatedAt,
                accessTier = when {
                    isLocal -> AccessTier.LOCAL
                    pricing != null && (pricing.inputPer1M > 0.0 || pricing.outputPer1M > 0.0) -> AccessTier.PAID
                    else -> AccessTier.UNKNOWN
                },
                freeTierVerified = false,
                freeTierSource = if (pricing != null) "Catalog Pricing" else "",
                supportsChat = true,
                supportsVision = true
            )
        }
        return result
    }

    override fun buildChatRequest(
        baseUrl: String,
        apiKey: String,
        customHeadersJson: String,
        params: ChatRequestParams,
        capabilities: ProviderCapabilities,
        stream: Boolean
    ): Request {
        val clean = baseUrl.trimEnd('/')
        val sysText = params.messages
            .filter { it.role == "system" || it.role == "developer" }
            .joinToString("\n\n") { AdapterHelper.plainTextFromMessageParts(it.contentParts, it.content) }

        val anthropicMessages = JSONArray()
        val nonSystem = params.messages.filter { it.role != "system" && it.role != "developer" }
        var idx = 0
        while (idx < nonSystem.size) {
            val msg = nonSystem[idx]
            if (msg.role == "tool") {
                // Combine consecutive tool results into one user message with tool_result content blocks
                val toolBlocks = JSONArray()
                while (idx < nonSystem.size && nonSystem[idx].role == "tool") {
                    val toolMsg = nonSystem[idx]
                    val toolText = AdapterHelper.plainTextFromMessageParts(toolMsg.contentParts, toolMsg.content)
                    toolBlocks.put(
                        JSONObject()
                            .put("type", "tool_result")
                            .put("tool_use_id", toolMsg.toolCallId.orEmpty())
                            .put("content", toolText)
                    )
                    idx++
                }
                anthropicMessages.put(JSONObject().put("role", "user").put("content", toolBlocks))
                continue
            }

            val role = if (msg.role == "assistant") "assistant" else "user"
            val blocks = JSONArray()

            if (msg.contentParts.isNotEmpty()) {
                for (part in msg.contentParts) {
                    when (part) {
                        is ContentPart.Text -> if (part.text.isNotEmpty()) {
                            blocks.put(JSONObject().put("type", "text").put("text", part.text))
                        }
                        is ContentPart.ImageUrl -> {
                            if (!capabilities.supportsVision) {
                                throw IllegalArgumentException("Model ${params.modelId} does not support image attachments.")
                            }
                            val srcObj = parseAnthropicImageSource(part.url)
                                ?: throw IllegalArgumentException("Unsupported image URL format for Anthropic.")
                            blocks.put(JSONObject().put("type", "image").put("source", srcObj))
                        }
                        is ContentPart.FileAttachment -> {
                            when {
                                part.mimeType == "application/pdf" && part.base64Data != null -> {
                                    blocks.put(
                                        JSONObject()
                                            .put("type", "document")
                                            .put(
                                                "source",
                                                JSONObject()
                                                    .put("type", "base64")
                                                    .put("media_type", "application/pdf")
                                                    .put("data", part.base64Data)
                                            )
                                            .put("title", part.fileName)
                                    )
                                }
                                part.mimeType in capabilities.supportedDocumentMimeTypes && part.textContent != null -> {
                                    blocks.put(
                                        JSONObject()
                                            .put("type", "document")
                                            .put(
                                                "source",
                                                JSONObject()
                                                    .put("type", "text")
                                                    .put("media_type", "text/plain")
                                                    .put("data", part.textContent)
                                            )
                                            .put("title", part.fileName)
                                    )
                                }
                                else -> throw IllegalArgumentException(
                                    "Anthropic model ${params.modelId} does not support attachment '${part.fileName}' (${part.mimeType})."
                                )
                            }
                        }
                        is ContentPart.ToolUse -> {
                            val inputObj = runCatching { JSONObject(part.argumentsJson) }.getOrDefault(JSONObject())
                            blocks.put(
                                JSONObject()
                                    .put("type", "tool_use")
                                    .put("id", part.id)
                                    .put("name", part.name)
                                    .put("input", inputObj)
                            )
                        }
                        is ContentPart.ToolResult -> {
                            blocks.put(
                                JSONObject()
                                    .put("type", "tool_result")
                                    .put("tool_use_id", part.toolCallId)
                                    .put("content", part.content)
                                    .apply { if (part.isError) put("is_error", true) }
                            )
                        }
                    }
                }
            } else if (msg.content.isNotEmpty()) {
                blocks.put(JSONObject().put("type", "text").put("text", msg.content))
            }

            if (role == "assistant" && msg.toolCalls.isNotEmpty()) {
                for (tc in msg.toolCalls) {
                    val inputObj = runCatching { JSONObject(tc.argumentsJson) }.getOrDefault(JSONObject())
                    blocks.put(
                        JSONObject()
                            .put("type", "tool_use")
                            .put("id", tc.id)
                            .put("name", tc.name)
                            .put("input", inputObj)
                    )
                }
            }

            if (blocks.length() == 0) {
                blocks.put(JSONObject().put("type", "text").put("text", ""))
            }
            anthropicMessages.put(JSONObject().put("role", role).put("content", blocks))
            idx++
        }

        val root = JSONObject().apply {
            put("model", params.modelId)
            put("messages", anthropicMessages)
            put("max_tokens", params.maxTokens ?: 1024)
            if (sysText.isNotBlank()) put("system", sysText)
            params.temperature?.let { put("temperature", it.toDouble()) }
            params.topP?.let { put("top_p", it.toDouble()) }
            if (params.stopSequences.isNotEmpty()) put("stop_sequences", JSONArray(params.stopSequences))

            val tools = AdapterHelper.effectiveTools(params)
            if (capabilities.supportsTools && tools.isNotEmpty()) {
                val toolsArr = JSONArray()
                tools.forEach { t ->
                    toolsArr.put(
                        JSONObject().apply {
                            put("name", t.name)
                            if (t.description.isNotBlank()) put("description", t.description)
                            put("input_schema", t.parametersSchema)
                        }
                    )
                }
                put("tools", toolsArr)

                when (val tc = params.toolChoice) {
                    is NormalizedToolChoice.Auto -> put("tool_choice", JSONObject().put("type", "auto"))
                    is NormalizedToolChoice.Required -> put("tool_choice", JSONObject().put("type", "any"))
                    is NormalizedToolChoice.None -> put("tool_choice", JSONObject().put("type", "none"))
                    is NormalizedToolChoice.SpecificFunction -> put(
                        "tool_choice",
                        JSONObject().put("type", "tool").put("name", tc.name)
                    )
                    null -> Unit
                }
            }

            put("stream", stream && capabilities.supportsStreaming)
        }

        val endpoint = if (clean.endsWith("/messages")) clean else "${v1Base(clean)}/messages"
        val builder = Request.Builder()
            .url(endpoint)
            .post(root.toString().toRequestBody(AdapterHelper.jsonMedia))
        applyAuthHeaders(builder, apiKey)
        AdapterHelper.applyCustomHeaders(builder, customHeadersJson)
        return builder.build()
    }

    private fun parseAnthropicImageSource(url: String): JSONObject? {
        if (url.startsWith("data:", ignoreCase = true)) {
            val commaIdx = url.indexOf(',')
            if (commaIdx > 5) {
                val meta = url.substring(5, commaIdx)
                val mediaType = meta.substringBefore(';').ifBlank { "image/png" }
                val base64 = url.substring(commaIdx + 1)
                return JSONObject()
                    .put("type", "base64")
                    .put("media_type", mediaType)
                    .put("data", base64)
            }
        } else if (url.startsWith("https://", ignoreCase = true)) {
            return JSONObject().put("type", "url").put("url", url)
        }
        return null
    }

    override fun parseChatResponse(bodyStr: String, latencyMs: Long, statusCode: Int): ChatExecutionResult {
        val root = JSONObject(bodyStr)
        val contentArr = root.optJSONArray("content")
        val text = StringBuilder()
        val toolCalls = mutableListOf<ToolCallPayload>()

        if (contentArr != null) {
            for (i in 0 until contentArr.length()) {
                val block = contentArr.optJSONObject(i) ?: continue
                when (block.optString("type")) {
                    "text" -> text.append(block.optString("text"))
                    "tool_use" -> {
                        val name = block.optString("name")
                        if (name.isNotBlank()) {
                            val inputJson = block.optJSONObject("input")?.toString() ?: "{}"
                            toolCalls += ToolCallPayload(
                                id = block.optString("id").ifBlank { "toolu_${toolCalls.size}" },
                                name = name,
                                argumentsJson = inputJson,
                                index = toolCalls.size
                            )
                        }
                    }
                }
            }
        }

        val usageObj = root.optJSONObject("usage")
        val stopReason = mapStopReason(root.optString("stop_reason", "end_turn"))
        return ChatExecutionResult(
            success = true,
            content = text.toString(),
            inputTokens = usageObj?.optInt("input_tokens", 0) ?: 0,
            outputTokens = usageObj?.optInt("output_tokens", 0) ?: 0,
            latencyMs = latencyMs,
            statusCode = statusCode,
            finishReason = stopReason,
            toolCalls = toolCalls,
            usageReported = usageObj != null,
            modelUsed = root.optString("model").takeIf { it.isNotBlank() }
        )
    }

    override fun parseStreamLine(
        eventType: String?,
        dataJson: String,
        state: AdapterStreamState
    ): NormalizedStreamDelta {
        val json = JSONObject(dataJson)
        val type = json.optString("type").ifBlank { eventType.orEmpty() }
        return when (type) {
            "message_start" -> {
                val u = json.optJSONObject("message")?.optJSONObject("usage")
                val inTok = u?.optInt("input_tokens", 0) ?: 0
                if (inTok > 0) state.inputTokens = inTok
                NormalizedStreamDelta(inputTokens = inTok)
            }
            "content_block_start" -> {
                val blockIdx = json.optInt("index", 0)
                val cb = json.optJSONObject("content_block")
                if (cb?.optString("type") == "tool_use") {
                    val toolIndex = state.nextToolCallIndex++
                    state.anthropicToolBlockIndices[blockIdx] = toolIndex
                    val id = cb.optString("id").takeIf { it.isNotBlank() }
                    val name = cb.optString("name").takeIf { it.isNotBlank() }
                    val delta = ToolCallDelta(index = toolIndex, id = id, name = name, argumentsChunk = null)
                    state.recordToolDeltas(listOf(delta))
                    NormalizedStreamDelta(toolCallDeltas = listOf(delta))
                } else {
                    NormalizedStreamDelta()
                }
            }
            "content_block_delta" -> {
                val blockIdx = json.optInt("index", 0)
                val delta = json.optJSONObject("delta")
                when (delta?.optString("type")) {
                    "text_delta" -> NormalizedStreamDelta(textDelta = delta.optString("text"))
                    "input_json_delta" -> {
                        val toolIdx = state.anthropicToolBlockIndices[blockIdx] ?: 0
                        val partial = delta.optString("partial_json")
                        val tcDelta = ToolCallDelta(index = toolIdx, argumentsChunk = partial)
                        state.recordToolDeltas(listOf(tcDelta))
                        NormalizedStreamDelta(toolCallDeltas = listOf(tcDelta))
                    }
                    else -> NormalizedStreamDelta()
                }
            }
            "message_delta" -> {
                val deltaObj = json.optJSONObject("delta")
                val rawStop = deltaObj?.optString("stop_reason")?.takeIf { it.isNotBlank() && it != "null" }
                val mappedStop = rawStop?.let { mapStopReason(it) }
                if (mappedStop != null) state.finishReason = mappedStop
                val outTok = json.optJSONObject("usage")?.optInt("output_tokens", 0) ?: 0
                if (outTok > 0) state.outputTokens = outTok
                NormalizedStreamDelta(outputTokens = outTok, finishReason = mappedStop)
            }
            "message_stop" -> NormalizedStreamDelta(isTerminal = true)
            else -> NormalizedStreamDelta()
        }
    }

    private fun mapStopReason(raw: String): String = when (raw) {
        "tool_use" -> "tool_calls"
        "max_tokens" -> "length"
        "end_turn", "stop_sequence" -> "stop"
        else -> raw
    }

    override fun normalizeHttpError(
        httpCode: Int,
        bodyStr: String,
        latencyMs: Long,
        apiKey: String
    ): ChatExecutionResult = AdapterHelper.defaultNormalizeHttpError(httpCode, bodyStr, latencyMs, apiKey)
}
