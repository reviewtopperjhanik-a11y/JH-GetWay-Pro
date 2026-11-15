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

class OpenAiProviderAdapter(
    override val apiFormat: ApiFormat = ApiFormat.OPENAI
) : ProviderAdapter {

    override fun capabilitiesForModel(modelId: String, modelEntity: ModelEntity?): ProviderCapabilities {
        val lower = modelId.lowercase()
        val isEmbed = lower.contains("embed")
        val isReasoning = lower.startsWith("o1") || lower.startsWith("o3") || lower.startsWith("o4") || lower.contains("deepseek-reasoner")
        val inferredVision = lower.contains("gpt-4o") || lower.contains("gpt-4.1") ||
            lower.contains("gpt-4-turbo") || lower.startsWith("o1") || lower.startsWith("o3") ||
            lower.startsWith("o4") || lower.contains("vision") || lower.contains("claude") ||
            lower.contains("gemini") || lower.contains("vl") || lower.contains("pixtral") ||
            lower.contains("llama-4") || lower.contains("gemma-3")
        return ProviderCapabilities(
            supportsTools = !isEmbed && (modelEntity?.supportsTools ?: true),
            supportsToolChoice = !isEmbed && (modelEntity?.supportsTools ?: true),
            supportsJsonOutput = !isEmbed && !lower.startsWith("o1-mini"),
            supportsStreaming = !isEmbed && (modelEntity?.supportsStreaming ?: true),
            supportsSystemRole = !lower.startsWith("o1-mini") && !lower.startsWith("o1-preview"),
            supportsDeveloperRole = true,
            supportsVision = !isEmbed && (modelEntity?.supportsVision == true || inferredVision),
            supportsStopSequences = !isReasoning,
            supportsDocumentFiles = !isEmbed,
            supportedDocumentMimeTypes = if (isEmbed) emptySet() else setOf(
                "text/plain",
                "text/markdown",
                "text/csv",
                "application/json"
            )
        )
    }

    override fun buildModelsUrl(baseUrl: String): String = "${baseUrl.trimEnd('/')}/models"

    override fun applyAuthHeaders(builder: Request.Builder, apiKey: String) {
        if (apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer $apiKey")
        }
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
            val ctx = obj.optInt("context_length", obj.optInt("context_window", 0)).takeIf { it > 0 }
            val pricing = if (isLocal) null else ProviderPresets.lookupPricing(id)
            val objPricing = obj.optJSONObject("pricing")
            val promptPrice = objPricing?.optString("prompt")?.toDoubleOrNull()
            val completionPrice = objPricing?.optString("completion")?.toDoubleOrNull()
            val isCatalogFreeSuffix = id.endsWith(":free", ignoreCase = true)
            val isExplicitCatalogFree = !isLocal && isCatalogFreeSuffix &&
                (promptPrice == null || promptPrice == 0.0) &&
                (completionPrice == null || completionPrice == 0.0)
            val hasPositivePrice = !isLocal && (
                (pricing != null && (pricing.inputPer1M > 0.0 || pricing.outputPer1M > 0.0)) ||
                    (promptPrice != null && promptPrice > 0.0) ||
                    (completionPrice != null && completionPrice > 0.0)
                )
            val accessTier = when {
                isLocal -> AccessTier.LOCAL
                isExplicitCatalogFree -> AccessTier.FREE_TIER
                hasPositivePrice -> AccessTier.PAID
                else -> AccessTier.UNKNOWN
            }
            val lower = id.lowercase()
            val isChatCapable = !lower.contains("embed") && !lower.contains("whisper") && !lower.contains("tts") && !lower.contains("dall-e")
            val archModality = obj.optJSONObject("architecture")?.optString("modality").orEmpty().lowercase()
            val supportsVision = isChatCapable && (
                archModality.contains("image") ||
                    lower.contains("gpt-4o") || lower.contains("gpt-4.1") || lower.contains("vision") ||
                    lower.contains("claude") || lower.contains("gemini") || lower.contains("vl") ||
                    lower.contains("pixtral") || lower.contains("llama-4") || lower.contains("gemma-3")
                )
            val supportsTools = isChatCapable
            val supportsStreaming = isChatCapable
            result += ModelEntity(
                id = "$providerId::$id",
                modelId = id,
                displayName = display,
                providerId = providerId,
                providerName = providerName,
                modelType = ProviderPresets.inferModelType(id),
                capabilitiesCsv = buildList {
                    if (isChatCapable) add("CHAT")
                    if (supportsStreaming) add("STREAMING")
                    if (supportsTools) add("TOOLS")
                    if (supportsVision) add("VISION")
                    if (isLocal) add("LOCAL")
                }.joinToString(","),
                contextWindow = ctx,
                supportsTools = supportsTools,
                supportsStreaming = supportsStreaming,
                isLocal = isLocal,
                inputPricePer1M = pricing?.inputPer1M ?: promptPrice?.let { it * 1_000_000.0 },
                outputPricePer1M = pricing?.outputPer1M ?: completionPrice?.let { it * 1_000_000.0 },
                pricingUpdatedAt = pricing?.updatedAt,
                accessTier = accessTier,
                freeTierVerified = isExplicitCatalogFree,
                freeTierSource = when {
                    isLocal -> "Local Runtime"
                    isExplicitCatalogFree -> "Provider Catalog (:free)"
                    hasPositivePrice -> "Catalog Pricing"
                    else -> ""
                },
                freeTierVerifiedAt = if (isExplicitCatalogFree) System.currentTimeMillis() else null,
                freeTierLimitsNote = if (isExplicitCatalogFree) "Provider free-tier rate/daily limits apply" else "",
                supportsChat = isChatCapable,
                supportsVision = supportsVision
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
        val isReasoning = params.modelId.lowercase().let {
            it.startsWith("o1") || it.startsWith("o3") || it.startsWith("o4")
        }
        val msgs = JSONArray().apply {
            for (msg in params.messages) {
                val role = when {
                    msg.role == "developer" && !capabilities.supportsDeveloperRole -> "system"
                    msg.role == "system" && !capabilities.supportsSystemRole -> "user"
                    else -> msg.role
                }
                val msgObj = JSONObject().put("role", role)
                if (!msg.name.isNullOrBlank()) msgObj.put("name", msg.name)

                if (role == "tool") {
                    msgObj.put("tool_call_id", msg.toolCallId.orEmpty())
                    msgObj.put("content", AdapterHelper.plainTextFromMessageParts(msg.contentParts, msg.content))
                } else if (role == "assistant" && msg.toolCalls.isNotEmpty()) {
                    if (msg.content.isNotBlank()) msgObj.put("content", msg.content)
                    else msgObj.put("content", JSONObject.NULL)
                    val tcArr = JSONArray()
                    msg.toolCalls.forEach { tc ->
                        tcArr.put(
                            JSONObject()
                                .put("id", tc.id)
                                .put("type", "function")
                                .put(
                                    "function",
                                    JSONObject()
                                        .put("name", tc.name)
                                        .put("arguments", tc.argumentsJson)
                                )
                        )
                    }
                    msgObj.put("tool_calls", tcArr)
                } else if (msg.contentParts.isNotEmpty() && msg.contentParts.any { it !is ContentPart.Text }) {
                    val partsArr = JSONArray()
                    for (part in msg.contentParts) {
                        when (part) {
                            is ContentPart.Text -> partsArr.put(
                                JSONObject().put("type", "text").put("text", part.text)
                            )
                            is ContentPart.ImageUrl -> {
                                if (!capabilities.supportsVision) {
                                    throw IllegalArgumentException("Model ${params.modelId} does not support image attachments.")
                                }
                                val imgObj = JSONObject().put("url", part.url)
                                if (!part.detail.isNullOrBlank()) imgObj.put("detail", part.detail)
                                partsArr.put(JSONObject().put("type", "image_url").put("image_url", imgObj))
                            }
                            is ContentPart.FileAttachment -> {
                                if (part.mimeType in capabilities.supportedDocumentMimeTypes && part.textContent != null) {
                                    partsArr.put(
                                        JSONObject().put("type", "text").put(
                                            "text",
                                            "Attached file (${part.fileName}, ${part.mimeType}):\n```\n${part.textContent}\n```"
                                        )
                                    )
                                } else {
                                    throw IllegalArgumentException(
                                        "Model ${params.modelId} (${apiFormat.name}) does not support attachment '${part.fileName}' (${part.mimeType})."
                                    )
                                }
                            }
                            is ContentPart.ToolResult -> partsArr.put(
                                JSONObject().put("type", "text").put("text", part.content)
                            )
                            is ContentPart.ToolUse -> Unit
                        }
                    }
                    msgObj.put("content", partsArr)
                } else {
                    msgObj.put("content", AdapterHelper.plainTextFromMessageParts(msg.contentParts, msg.content))
                }
                put(msgObj)
            }
        }

        val root = JSONObject().apply {
            put("model", params.modelId)
            put("messages", msgs)
            if (!isReasoning) {
                params.temperature?.let { put("temperature", it.toDouble()) }
                params.topP?.let { put("top_p", it.toDouble()) }
            }
            params.maxTokens?.let {
                put(if (isReasoning) "max_completion_tokens" else "max_tokens", it)
            }
            if (capabilities.supportsStopSequences && params.stopSequences.isNotEmpty()) {
                put("stop", JSONArray(params.stopSequences))
            }
            if (capabilities.supportsJsonOutput) {
                val rf = params.responseFormat
                if (rf != null) {
                    val rfObj = JSONObject().put("type", rf.type)
                    if (rf.type == "json_schema" && rf.schemaJson != null) {
                        rfObj.put(
                            "json_schema",
                            JSONObject()
                                .put("name", rf.schemaName ?: "response_schema")
                                .put("schema", rf.schemaJson)
                                .apply { rf.strict?.let { put("strict", it) } }
                        )
                    }
                    put("response_format", rfObj)
                } else if (params.jsonOutput) {
                    put("response_format", JSONObject().put("type", "json_object"))
                }
            }

            val tools = AdapterHelper.effectiveTools(params)
            if (capabilities.supportsTools && tools.isNotEmpty()) {
                val toolsArr = JSONArray()
                tools.forEach { t ->
                    toolsArr.put(
                        JSONObject()
                            .put("type", "function")
                            .put(
                                "function",
                                JSONObject().apply {
                                    put("name", t.name)
                                    if (t.description.isNotBlank()) put("description", t.description)
                                    put("parameters", t.parametersSchema)
                                    t.strict?.let { put("strict", it) }
                                }
                            )
                    )
                }
                put("tools", toolsArr)

                if (capabilities.supportsToolChoice) {
                    when (val tc = params.toolChoice) {
                        is NormalizedToolChoice.Auto -> put("tool_choice", "auto")
                        is NormalizedToolChoice.None -> put("tool_choice", "none")
                        is NormalizedToolChoice.Required -> put("tool_choice", "required")
                        is NormalizedToolChoice.SpecificFunction -> put(
                            "tool_choice",
                            JSONObject()
                                .put("type", "function")
                                .put("function", JSONObject().put("name", tc.name))
                        )
                        null -> Unit
                    }
                }
            }

            val useStream = stream && capabilities.supportsStreaming
            put("stream", useStream)
            if (useStream) {
                put("stream_options", JSONObject().put("include_usage", true))
            }
        }

        val endpoint = if (clean.endsWith("/chat/completions")) clean else "$clean/chat/completions"
        val builder = Request.Builder()
            .url(endpoint)
            .post(root.toString().toRequestBody(AdapterHelper.jsonMedia))
        applyAuthHeaders(builder, apiKey)
        AdapterHelper.applyCustomHeaders(builder, customHeadersJson)
        return builder.build()
    }

    override fun parseChatResponse(bodyStr: String, latencyMs: Long, statusCode: Int): ChatExecutionResult {
        val root = JSONObject(bodyStr)
        val firstChoice = root.optJSONArray("choices")?.optJSONObject(0)
        val messageObj = firstChoice?.optJSONObject("message")
        val contentVal = messageObj?.opt("content")
        val text = when (contentVal) {
            null, JSONObject.NULL -> ""
            is String -> contentVal
            is JSONArray -> buildString {
                for (i in 0 until contentVal.length()) {
                    val item = contentVal.optJSONObject(i)
                    if (item?.optString("type") == "text") append(item.optString("text"))
                }
            }
            else -> contentVal.toString()
        }

        val toolCalls = mutableListOf<ToolCallPayload>()
        val tcArr = messageObj?.optJSONArray("tool_calls")
        if (tcArr != null) {
            for (i in 0 until tcArr.length()) {
                val tcObj = tcArr.optJSONObject(i) ?: continue
                val fn = tcObj.optJSONObject("function") ?: continue
                val name = fn.optString("name")
                if (name.isBlank()) continue
                toolCalls += ToolCallPayload(
                    id = tcObj.optString("id").ifBlank { "call_$i" },
                    name = name,
                    argumentsJson = fn.optString("arguments", "{}"),
                    index = i
                )
            }
        }

        val usageObj = root.optJSONObject("usage")
        val actualBilledCost = usageObj?.let { u ->
            when {
                u.has("cost") && !u.isNull("cost") -> u.optDouble("cost").takeIf { !it.isNaN() && it >= 0.0 }
                u.has("total_cost") && !u.isNull("total_cost") -> u.optDouble("total_cost").takeIf { !it.isNaN() && it >= 0.0 }
                else -> null
            }
        }
        val finishReason = firstChoice?.optString("finish_reason")?.takeIf { it.isNotBlank() && it != "null" }
            ?: if (toolCalls.isNotEmpty()) "tool_calls" else "stop"

        return ChatExecutionResult(
            success = true,
            content = text,
            inputTokens = usageObj?.optInt("prompt_tokens", 0) ?: 0,
            outputTokens = usageObj?.optInt("completion_tokens", 0) ?: 0,
            latencyMs = latencyMs,
            statusCode = statusCode,
            finishReason = finishReason,
            toolCalls = toolCalls,
            usageReported = usageObj != null,
            modelUsed = root.optString("model").takeIf { it.isNotBlank() },
            actualBilledCostUsd = actualBilledCost
        )
    }

    override fun parseStreamLine(
        eventType: String?,
        dataJson: String,
        state: AdapterStreamState
    ): NormalizedStreamDelta {
        val json = JSONObject(dataJson)
        val choice = json.optJSONArray("choices")?.optJSONObject(0)
        val delta = choice?.optJSONObject("delta")

        val textDelta = delta?.opt("content")?.let {
            if (it == JSONObject.NULL) "" else it.toString()
        }.orEmpty()

        val tcDeltas = mutableListOf<ToolCallDelta>()
        val tcArr = delta?.optJSONArray("tool_calls")
        if (tcArr != null) {
            for (i in 0 until tcArr.length()) {
                val tc = tcArr.optJSONObject(i) ?: continue
                val idx = tc.optInt("index", i)
                val id = tc.optString("id").takeIf { it.isNotBlank() }
                val fn = tc.optJSONObject("function")
                val name = fn?.optString("name")?.takeIf { it.isNotBlank() }
                val args = fn?.optString("arguments")?.takeIf { it.isNotEmpty() }
                tcDeltas += ToolCallDelta(index = idx, id = id, name = name, argumentsChunk = args)
            }
        }
        state.recordToolDeltas(tcDeltas)

        val finishReason = choice?.optString("finish_reason")?.takeIf { it.isNotBlank() && it != "null" }
        if (finishReason != null) state.finishReason = finishReason

        val u = json.optJSONObject("usage")
        val inTok = u?.optInt("prompt_tokens", 0) ?: 0
        val outTok = u?.optInt("completion_tokens", 0) ?: 0
        if (inTok > 0) state.inputTokens = inTok
        if (outTok > 0) state.outputTokens = outTok

        return NormalizedStreamDelta(
            textDelta = textDelta,
            toolCallDeltas = tcDeltas,
            inputTokens = inTok,
            outputTokens = outTok,
            finishReason = finishReason
        )
    }

    override fun normalizeHttpError(
        httpCode: Int,
        bodyStr: String,
        latencyMs: Long,
        apiKey: String
    ): ChatExecutionResult = AdapterHelper.defaultNormalizeHttpError(httpCode, bodyStr, latencyMs, apiKey)
}
