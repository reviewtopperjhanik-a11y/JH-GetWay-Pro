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

class GeminiProviderAdapter : ProviderAdapter {
    override val apiFormat: ApiFormat = ApiFormat.GEMINI

    override fun capabilitiesForModel(modelId: String, modelEntity: ModelEntity?): ProviderCapabilities {
        val lower = modelId.lowercase()
        val isEmbed = lower.contains("embed")
        val inferredVision = !isEmbed && (
            lower.contains("gemini-1.5") ||
                lower.contains("gemini-2") ||
                lower.contains("gemini-3") ||
                lower.contains("gemini-pro-vision") ||
                lower.contains("vision") ||
                lower.contains("gemma-3")
            )
        return ProviderCapabilities(
            supportsTools = !isEmbed && (modelEntity?.supportsTools ?: true),
            supportsToolChoice = !isEmbed && (modelEntity?.supportsTools ?: true),
            supportsJsonOutput = !isEmbed,
            supportsStreaming = !isEmbed && (modelEntity?.supportsStreaming ?: true),
            supportsSystemRole = true,
            supportsDeveloperRole = false,
            supportsVision = !isEmbed && (modelEntity == null || modelEntity.supportsVision || inferredVision),
            supportsStopSequences = true,
            supportsDocumentFiles = !isEmbed,
            supportedDocumentMimeTypes = if (isEmbed) emptySet() else setOf(
                "application/pdf",
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
            builder.header("x-goog-api-key", apiKey)
        }
    }

    override fun parseModelsResponse(
        bodyStr: String,
        providerId: String,
        providerName: String,
        isLocal: Boolean
    ): List<ModelEntity> {
        val root = JSONObject(bodyStr)
        val arr = root.optJSONArray("models") ?: JSONArray()
        val result = mutableListOf<ModelEntity>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val rawName = obj.optString("name").removePrefix("models/")
            if (rawName.isBlank()) continue
            val methodsArr = obj.optJSONArray("supportedGenerationMethods")
            val methods = if (methodsArr != null) {
                (0 until methodsArr.length()).mapNotNull { idx -> methodsArr.optString(idx).takeIf { it.isNotBlank() } }
            } else emptyList()
            val supportsGenerateContent = methods.isEmpty() || methods.any { it.equals("generateContent", ignoreCase = true) }
            val display = obj.optString("displayName").ifBlank { rawName }
            val ctx = obj.optInt("inputTokenLimit", 0).takeIf { it > 0 }
            val pricing = ProviderPresets.lookupPricing(rawName)
            val inferredType = ProviderPresets.inferModelType(rawName)
            val isChatCapable = supportsGenerateContent && inferredType != com.example.data.local.ModelType.EMBEDDING
            result += ModelEntity(
                id = "$providerId::$rawName",
                modelId = rawName,
                displayName = display,
                providerId = providerId,
                providerName = providerName,
                modelType = inferredType,
                capabilitiesCsv = if (isChatCapable) "CHAT,STREAMING,TOOLS,VISION,PDF" else methods.joinToString(",").uppercase(),
                contextWindow = ctx,
                supportsTools = isChatCapable,
                supportsStreaming = isChatCapable,
                isLocal = isLocal,
                inputPricePer1M = pricing?.inputPer1M,
                outputPricePer1M = pricing?.outputPer1M,
                pricingUpdatedAt = pricing?.updatedAt,
                enabled = isChatCapable,
                accessTier = when {
                    isLocal -> AccessTier.LOCAL
                    pricing != null && (pricing.inputPer1M > 0.0 || pricing.outputPer1M > 0.0) -> AccessTier.PAID
                    else -> AccessTier.UNKNOWN
                },
                freeTierVerified = false,
                freeTierSource = if (pricing != null) "Catalog Pricing" else "",
                supportsChat = isChatCapable,
                supportsVision = isChatCapable
            )
        }
        return result.sortedByDescending { it.enabled }
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
        val action = if (stream && capabilities.supportsStreaming) "streamGenerateContent?alt=sse" else "generateContent"
        val modelPath = if (params.modelId.startsWith("models/")) params.modelId else "models/${params.modelId}"
        val sysMessages = params.messages
            .filter { it.role == "system" || it.role == "developer" }
            .joinToString("\n") { AdapterHelper.plainTextFromMessageParts(it.contentParts, it.content) }

        val toolIdToName = mutableMapOf<String, String>()
        params.messages.forEach { m ->
            m.toolCalls.forEach { tc ->
                if (tc.id.isNotBlank() && tc.name.isNotBlank()) toolIdToName[tc.id] = tc.name
            }
        }

        val contents = JSONArray().apply {
            for (msg in params.messages.filter { it.role != "system" && it.role != "developer" }) {
                val partsArr = JSONArray()
                if (msg.role == "tool") {
                    val fnName = msg.name ?: toolIdToName[msg.toolCallId] ?: "tool_function"
                    val respText = AdapterHelper.plainTextFromMessageParts(msg.contentParts, msg.content)
                    val respObj = runCatching { JSONObject(respText) }.getOrNull()
                        ?: JSONObject().put("result", respText)
                    partsArr.put(
                        JSONObject().put(
                            "functionResponse",
                            JSONObject().put("name", fnName).put("response", respObj)
                        )
                    )
                    put(JSONObject().put("role", "user").put("parts", partsArr))
                } else {
                    if (msg.contentParts.isNotEmpty()) {
                        for (p in msg.contentParts) {
                            when (p) {
                                is ContentPart.Text -> if (p.text.isNotEmpty()) partsArr.put(JSONObject().put("text", p.text))
                                is ContentPart.ImageUrl -> {
                                    if (!capabilities.supportsVision) {
                                        throw IllegalArgumentException("Model ${params.modelId} does not support image attachments.")
                                    }
                                    if (p.url.startsWith("data:", true) && p.url.contains(",")) {
                                        val comma = p.url.indexOf(',')
                                        val mime = p.url.substring(5, comma).substringBefore(';').ifBlank { "image/png" }
                                        partsArr.put(
                                            JSONObject().put(
                                                "inlineData",
                                                JSONObject().put("mimeType", mime).put("data", p.url.substring(comma + 1))
                                            )
                                        )
                                    } else {
                                        partsArr.put(
                                            JSONObject().put(
                                                "fileData",
                                                JSONObject().put("mimeType", "image/jpeg").put("fileUri", p.url)
                                            )
                                        )
                                    }
                                }
                                is ContentPart.FileAttachment -> {
                                    when {
                                        p.mimeType == "application/pdf" && p.base64Data != null -> {
                                            partsArr.put(
                                                JSONObject().put(
                                                    "inlineData",
                                                    JSONObject()
                                                        .put("mimeType", "application/pdf")
                                                        .put("data", p.base64Data)
                                                )
                                            )
                                        }
                                        p.mimeType in capabilities.supportedDocumentMimeTypes && p.textContent != null -> {
                                            partsArr.put(
                                                JSONObject().put(
                                                    "text",
                                                    "Attached file (${p.fileName}, ${p.mimeType}):\n```\n${p.textContent}\n```"
                                                )
                                            )
                                        }
                                        else -> throw IllegalArgumentException(
                                            "Gemini model ${params.modelId} does not support attachment '${p.fileName}' (${p.mimeType})."
                                        )
                                    }
                                }
                                is ContentPart.ToolUse -> {
                                    val argsObj = runCatching { JSONObject(p.argumentsJson) }.getOrDefault(JSONObject())
                                    partsArr.put(
                                        JSONObject().put("functionCall", JSONObject().put("name", p.name).put("args", argsObj))
                                    )
                                }
                                is ContentPart.ToolResult -> {
                                    val fnName = toolIdToName[p.toolCallId] ?: "tool_function"
                                    val respObj = runCatching { JSONObject(p.content) }.getOrNull()
                                        ?: JSONObject().put("result", p.content)
                                    partsArr.put(
                                        JSONObject().put("functionResponse", JSONObject().put("name", fnName).put("response", respObj))
                                    )
                                }
                            }
                        }
                    } else if (msg.content.isNotEmpty()) {
                        partsArr.put(JSONObject().put("text", msg.content))
                    }

                    if (msg.role == "assistant" && msg.toolCalls.isNotEmpty()) {
                        msg.toolCalls.forEach { tc ->
                            val argsObj = runCatching { JSONObject(tc.argumentsJson) }.getOrDefault(JSONObject())
                            partsArr.put(
                                JSONObject().put("functionCall", JSONObject().put("name", tc.name).put("args", argsObj))
                            )
                        }
                    }
                    if (partsArr.length() == 0) partsArr.put(JSONObject().put("text", ""))
                    put(
                        JSONObject()
                            .put("role", if (msg.role == "assistant") "model" else "user")
                            .put("parts", partsArr)
                    )
                }
            }
        }

        val root = JSONObject().apply {
            put("contents", contents)
            if (sysMessages.isNotBlank()) {
                put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sysMessages))))
            }
            val genCfg = JSONObject()
            params.temperature?.let { genCfg.put("temperature", it.toDouble()) }
            params.topP?.let { genCfg.put("topP", it.toDouble()) }
            params.maxTokens?.let { genCfg.put("maxOutputTokens", it) }
            if (params.stopSequences.isNotEmpty()) genCfg.put("stopSequences", JSONArray(params.stopSequences))
            if (params.jsonOutput || params.responseFormat?.type == "json_object" || params.responseFormat?.type == "json_schema") {
                genCfg.put("responseMimeType", "application/json")
                params.responseFormat?.schemaJson?.let { genCfg.put("responseSchema", it) }
            }
            if (genCfg.length() > 0) put("generationConfig", genCfg)

            val tools = AdapterHelper.effectiveTools(params)
            if (capabilities.supportsTools && tools.isNotEmpty()) {
                val decls = JSONArray()
                tools.forEach { t ->
                    decls.put(
                        JSONObject().apply {
                            put("name", t.name)
                            if (t.description.isNotBlank()) put("description", t.description)
                            put("parameters", t.parametersSchema)
                        }
                    )
                }
                put("tools", JSONArray().put(JSONObject().put("functionDeclarations", decls)))

                val mode = when (val tc = params.toolChoice) {
                    is NormalizedToolChoice.None -> "NONE"
                    is NormalizedToolChoice.Required -> "ANY"
                    is NormalizedToolChoice.Auto -> "AUTO"
                    is NormalizedToolChoice.SpecificFunction -> "ANY"
                    null -> null
                }
                if (mode != null) {
                    val fnConfig = JSONObject().put("mode", mode)
                    if (params.toolChoice is NormalizedToolChoice.SpecificFunction) {
                        fnConfig.put("allowedFunctionNames", JSONArray().put(params.toolChoice.name))
                    }
                    put("toolConfig", JSONObject().put("functionCallingConfig", fnConfig))
                }
            }
        }

        val builder = Request.Builder()
            .url("$clean/$modelPath:$action")
            .post(root.toString().toRequestBody(AdapterHelper.jsonMedia))
        applyAuthHeaders(builder, apiKey)
        AdapterHelper.applyCustomHeaders(builder, customHeadersJson)
        return builder.build()
    }

    override fun parseChatResponse(bodyStr: String, latencyMs: Long, statusCode: Int): ChatExecutionResult {
        val root = JSONObject(bodyStr)
        val first = root.optJSONArray("candidates")?.optJSONObject(0)
        val parts = first?.optJSONObject("content")?.optJSONArray("parts")
        val text = StringBuilder()
        val toolCalls = mutableListOf<ToolCallPayload>()
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                if (part.has("text")) text.append(part.optString("text"))
                part.optJSONObject("functionCall")?.let { fc ->
                    val name = fc.optString("name")
                    if (name.isNotBlank()) {
                        val args = fc.optJSONObject("args")?.toString() ?: "{}"
                        toolCalls += ToolCallPayload(
                            id = "call_gem_${toolCalls.size}",
                            name = name,
                            argumentsJson = args,
                            index = toolCalls.size
                        )
                    }
                }
            }
        }
        val usage = root.optJSONObject("usageMetadata")
        val rawFinish = first?.optString("finishReason", "STOP") ?: "STOP"
        val finishReason = when {
            toolCalls.isNotEmpty() -> "tool_calls"
            rawFinish.equals("MAX_TOKENS", true) -> "length"
            rawFinish.equals("SAFETY", true) -> "content_filter"
            else -> "stop"
        }
        return ChatExecutionResult(
            success = true,
            content = text.toString(),
            inputTokens = usage?.optInt("promptTokenCount", 0) ?: 0,
            outputTokens = usage?.optInt("candidatesTokenCount", 0) ?: 0,
            latencyMs = latencyMs,
            statusCode = statusCode,
            finishReason = finishReason,
            toolCalls = toolCalls,
            usageReported = usage != null
        )
    }

    override fun parseStreamLine(
        eventType: String?,
        dataJson: String,
        state: AdapterStreamState
    ): NormalizedStreamDelta {
        val json = JSONObject(dataJson)
        val cand = json.optJSONArray("candidates")?.optJSONObject(0)
        val parts = cand?.optJSONObject("content")?.optJSONArray("parts")
        val text = StringBuilder()
        val tcDeltas = mutableListOf<ToolCallDelta>()
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                if (p.has("text")) text.append(p.optString("text"))
                p.optJSONObject("functionCall")?.let { fc ->
                    val name = fc.optString("name")
                    if (name.isNotBlank()) {
                        val idx = state.nextToolCallIndex++
                        val args = fc.optJSONObject("args")?.toString() ?: "{}"
                        tcDeltas += ToolCallDelta(index = idx, id = "call_gem_$idx", name = name, argumentsChunk = args)
                    }
                }
            }
        }
        state.recordToolDeltas(tcDeltas)

        val u = json.optJSONObject("usageMetadata")
        val inTok = u?.optInt("promptTokenCount", 0) ?: 0
        val outTok = u?.optInt("candidatesTokenCount", 0) ?: 0
        if (inTok > 0) state.inputTokens = inTok
        if (outTok > 0) state.outputTokens = outTok

        val rawFinish = cand?.optString("finishReason")?.takeIf { it.isNotBlank() && it != "null" }
        val mappedFinish = rawFinish?.let {
            when {
                state.accumulatedToolCalls.isNotEmpty() || tcDeltas.isNotEmpty() -> "tool_calls"
                it.equals("MAX_TOKENS", true) -> "length"
                it.equals("SAFETY", true) -> "content_filter"
                else -> "stop"
            }
        }
        if (mappedFinish != null) state.finishReason = mappedFinish

        return NormalizedStreamDelta(
            textDelta = text.toString(),
            toolCallDeltas = tcDeltas,
            inputTokens = inTok,
            outputTokens = outTok,
            finishReason = mappedFinish
        )
    }

    override fun normalizeHttpError(httpCode: Int, bodyStr: String, latencyMs: Long, apiKey: String): ChatExecutionResult =
        AdapterHelper.defaultNormalizeHttpError(httpCode, bodyStr, latencyMs, apiKey)
}

class CohereProviderAdapter : ProviderAdapter {
    override val apiFormat: ApiFormat = ApiFormat.COHERE

    override fun capabilitiesForModel(modelId: String, modelEntity: ModelEntity?): ProviderCapabilities =
        ProviderCapabilities(
            supportsTools = true,
            supportsToolChoice = false,
            supportsJsonOutput = false,
            supportsStreaming = modelEntity?.supportsStreaming ?: true,
            supportsSystemRole = true,
            supportsDeveloperRole = false,
            supportsVision = false,
            supportsStopSequences = true,
            supportsDocumentFiles = true,
            supportedDocumentMimeTypes = setOf("text/plain", "text/markdown", "text/csv", "application/json")
        )

    override fun buildModelsUrl(baseUrl: String): String = "${baseUrl.trimEnd('/')}/models"

    override fun applyAuthHeaders(builder: Request.Builder, apiKey: String) {
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
    }

    override fun parseModelsResponse(bodyStr: String, providerId: String, providerName: String, isLocal: Boolean): List<ModelEntity> =
        OpenAiProviderAdapter(ApiFormat.COHERE).parseModelsResponse(bodyStr, providerId, providerName, isLocal)

    override fun buildChatRequest(
        baseUrl: String,
        apiKey: String,
        customHeadersJson: String,
        params: ChatRequestParams,
        capabilities: ProviderCapabilities,
        stream: Boolean
    ): Request {
        val clean = baseUrl.trimEnd('/')
        val msgs = JSONArray().apply {
            params.messages.forEach { msg ->
                msg.contentParts.forEach { part ->
                    when (part) {
                        is ContentPart.ImageUrl -> throw IllegalArgumentException("Cohere adapter does not support image attachments.")
                        is ContentPart.FileAttachment -> {
                            if (part.mimeType !in capabilities.supportedDocumentMimeTypes || part.textContent == null) {
                                throw IllegalArgumentException("Cohere adapter does not support attachment '${part.fileName}' (${part.mimeType}).")
                            }
                        }
                        else -> Unit
                    }
                }
                put(JSONObject().put("role", msg.role).put("content", AdapterHelper.plainTextFromMessageParts(msg.contentParts, msg.content)))
            }
        }
        val root = JSONObject().apply {
            put("model", params.modelId)
            put("messages", msgs)
            params.temperature?.let { put("temperature", it.toDouble()) }
            params.maxTokens?.let { put("max_tokens", it) }
            if (params.stopSequences.isNotEmpty()) put("stop_sequences", JSONArray(params.stopSequences))
            put("stream", stream && capabilities.supportsStreaming)
        }
        val builder = Request.Builder()
            .url("$clean/chat")
            .post(root.toString().toRequestBody(AdapterHelper.jsonMedia))
        applyAuthHeaders(builder, apiKey)
        AdapterHelper.applyCustomHeaders(builder, customHeadersJson)
        return builder.build()
    }

    override fun parseChatResponse(bodyStr: String, latencyMs: Long, statusCode: Int): ChatExecutionResult {
        val root = JSONObject(bodyStr)
        val contentArr = root.optJSONObject("message")?.optJSONArray("content")
        val text = contentArr?.optJSONObject(0)?.optString("text").orEmpty()
        val usage = root.optJSONObject("usage")?.optJSONObject("tokens")
        return ChatExecutionResult(
            success = true,
            content = text,
            inputTokens = usage?.optInt("input_tokens", 0) ?: 0,
            outputTokens = usage?.optInt("output_tokens", 0) ?: 0,
            latencyMs = latencyMs,
            statusCode = statusCode,
            finishReason = if (root.optString("finish_reason") == "MAX_TOKENS") "length" else "stop",
            usageReported = usage != null
        )
    }

    override fun parseStreamLine(eventType: String?, dataJson: String, state: AdapterStreamState): NormalizedStreamDelta {
        val json = JSONObject(dataJson)
        val type = json.optString("type").ifBlank { eventType.orEmpty() }
        return when (type) {
            "content-delta" -> NormalizedStreamDelta(
                textDelta = json.optJSONObject("delta")?.optJSONObject("message")?.optJSONObject("content")?.optString("text").orEmpty()
            )
            "message-end" -> {
                val u = json.optJSONObject("delta")?.optJSONObject("usage")?.optJSONObject("tokens")
                val inTok = u?.optInt("input_tokens", 0) ?: 0
                val outTok = u?.optInt("output_tokens", 0) ?: 0
                if (inTok > 0) state.inputTokens = inTok
                if (outTok > 0) state.outputTokens = outTok
                state.finishReason = "stop"
                NormalizedStreamDelta(inputTokens = inTok, outputTokens = outTok, finishReason = "stop", isTerminal = true)
            }
            else -> NormalizedStreamDelta()
        }
    }

    override fun normalizeHttpError(httpCode: Int, bodyStr: String, latencyMs: Long, apiKey: String): ChatExecutionResult =
        AdapterHelper.defaultNormalizeHttpError(httpCode, bodyStr, latencyMs, apiKey)
}

class OllamaProviderAdapter : ProviderAdapter {
    override val apiFormat: ApiFormat = ApiFormat.OLLAMA

    override fun capabilitiesForModel(modelId: String, modelEntity: ModelEntity?): ProviderCapabilities {
        val isEmbed = modelId.contains("embed", true)
        val hasVision = !isEmbed && (
            modelEntity?.supportsVision == true ||
                modelId.contains("llava", true) ||
                modelId.contains("vl", true) ||
                modelId.contains("gemma3", true) ||
                modelId.contains("vision", true)
            )
        return ProviderCapabilities(
            supportsTools = !isEmbed && (modelEntity?.supportsTools ?: (modelId.contains("qwen", true) || modelId.contains("llama3", true) || modelId.contains("mistral", true))),
            supportsToolChoice = false,
            supportsJsonOutput = !isEmbed,
            supportsStreaming = !isEmbed,
            supportsSystemRole = true,
            supportsDeveloperRole = false,
            supportsVision = hasVision,
            supportsStopSequences = true,
            supportsDocumentFiles = !isEmbed,
            supportedDocumentMimeTypes = if (isEmbed) emptySet() else setOf("text/plain", "text/markdown", "text/csv", "application/json")
        )
    }

    override fun buildModelsUrl(baseUrl: String): String {
        val clean = baseUrl.trimEnd('/')
        return if (clean.endsWith("/v1")) clean.removeSuffix("/v1") + "/api/tags" else "$clean/api/tags"
    }

    override fun applyAuthHeaders(builder: Request.Builder, apiKey: String) {
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
    }

    override fun parseModelsResponse(bodyStr: String, providerId: String, providerName: String, isLocal: Boolean): List<ModelEntity> {
        val root = JSONObject(bodyStr)
        val arr = root.optJSONArray("models") ?: JSONArray()
        val result = mutableListOf<ModelEntity>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val name = obj.optString("name")
            if (name.isBlank()) continue
            val hasVision = name.contains("llava", true) || name.contains("vl", true) || name.contains("gemma3", true) || name.contains("vision", true)
            val isChat = !name.contains("embed", true)
            result += ModelEntity(
                id = "$providerId::$name",
                modelId = name,
                displayName = name,
                providerId = providerId,
                providerName = providerName,
                modelType = ProviderPresets.inferModelType(name),
                capabilitiesCsv = buildList {
                    if (isChat) add("CHAT")
                    add("STREAMING")
                    add("LOCAL")
                    if (hasVision) add("VISION")
                }.joinToString(","),
                contextWindow = null,
                supportsTools = name.contains("qwen", true) || name.contains("llama3", true) || name.contains("mistral", true),
                supportsStreaming = true,
                isLocal = true,
                accessTier = AccessTier.LOCAL,
                freeTierVerified = false,
                freeTierSource = "Local Runtime",
                supportsChat = isChat,
                supportsVision = hasVision
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
        val ollamaBase = if (clean.endsWith("/v1")) clean.removeSuffix("/v1") else clean
        val msgs = JSONArray().apply {
            params.messages.forEach { m ->
                val images = JSONArray()
                m.contentParts.forEach { part ->
                    when (part) {
                        is ContentPart.ImageUrl -> {
                            if (!capabilities.supportsVision) {
                                throw IllegalArgumentException("Ollama model ${params.modelId} does not support image attachments.")
                            }
                            val base64 = if (part.url.startsWith("data:", true) && part.url.contains(",")) {
                                part.url.substringAfter(',')
                            } else {
                                throw IllegalArgumentException("Ollama requires base64 image payloads.")
                            }
                            images.put(base64)
                        }
                        is ContentPart.FileAttachment -> {
                            if (part.mimeType !in capabilities.supportedDocumentMimeTypes || part.textContent == null) {
                                throw IllegalArgumentException("Ollama model ${params.modelId} does not support attachment '${part.fileName}' (${part.mimeType}).")
                            }
                        }
                        else -> Unit
                    }
                }
                val msgObj = JSONObject()
                    .put("role", m.role)
                    .put("content", AdapterHelper.plainTextFromMessageParts(m.contentParts, m.content))
                if (images.length() > 0) msgObj.put("images", images)
                put(msgObj)
            }
        }
        val root = JSONObject().apply {
            put("model", params.modelId)
            put("messages", msgs)
            put("stream", stream)
            val opts = JSONObject()
            params.temperature?.let { opts.put("temperature", it.toDouble()) }
            params.topP?.let { opts.put("top_p", it.toDouble()) }
            params.maxTokens?.let { opts.put("num_predict", it) }
            if (params.stopSequences.isNotEmpty()) opts.put("stop", JSONArray(params.stopSequences))
            if (opts.length() > 0) put("options", opts)
            if (params.jsonOutput || params.responseFormat?.type == "json_object") put("format", "json")
            val tools = AdapterHelper.effectiveTools(params)
            if (capabilities.supportsTools && tools.isNotEmpty()) {
                val toolsArr = JSONArray()
                tools.forEach { t ->
                    toolsArr.put(
                        JSONObject().put("type", "function").put(
                            "function",
                            JSONObject()
                                .put("name", t.name)
                                .put("description", t.description)
                                .put("parameters", t.parametersSchema)
                        )
                    )
                }
                put("tools", toolsArr)
            }
        }
        val builder = Request.Builder()
            .url("$ollamaBase/api/chat")
            .post(root.toString().toRequestBody(AdapterHelper.jsonMedia))
        applyAuthHeaders(builder, apiKey)
        AdapterHelper.applyCustomHeaders(builder, customHeadersJson)
        return builder.build()
    }

    override fun parseChatResponse(bodyStr: String, latencyMs: Long, statusCode: Int): ChatExecutionResult {
        val root = JSONObject(bodyStr)
        val msgObj = root.optJSONObject("message")
        val text = msgObj?.optString("content").orEmpty()
        val toolCalls = mutableListOf<ToolCallPayload>()
        val tcArr = msgObj?.optJSONArray("tool_calls")
        if (tcArr != null) {
            for (i in 0 until tcArr.length()) {
                val tc = tcArr.optJSONObject(i) ?: continue
                val fn = tc.optJSONObject("function") ?: continue
                val name = fn.optString("name")
                if (name.isNotBlank()) {
                    val args = fn.opt("arguments")?.toString() ?: "{}"
                    toolCalls += ToolCallPayload(
                        id = "call_ollama_$i",
                        name = name,
                        argumentsJson = args,
                        index = i
                    )
                }
            }
        }
        val inTok = root.optInt("prompt_eval_count", 0)
        val outTok = root.optInt("eval_count", 0)
        return ChatExecutionResult(
            success = true,
            content = text,
            inputTokens = inTok,
            outputTokens = outTok,
            latencyMs = latencyMs,
            statusCode = statusCode,
            finishReason = if (toolCalls.isNotEmpty()) "tool_calls" else "stop",
            toolCalls = toolCalls,
            usageReported = root.has("eval_count") || root.has("prompt_eval_count")
        )
    }

    override fun parseStreamLine(eventType: String?, dataJson: String, state: AdapterStreamState): NormalizedStreamDelta {
        val json = JSONObject(dataJson)
        val msg = json.optJSONObject("message")
        val text = msg?.optString("content").orEmpty()
        val tcDeltas = mutableListOf<ToolCallDelta>()
        val tcArr = msg?.optJSONArray("tool_calls")
        if (tcArr != null) {
            for (i in 0 until tcArr.length()) {
                val fn = tcArr.optJSONObject(i)?.optJSONObject("function") ?: continue
                val name = fn.optString("name")
                if (name.isNotBlank()) {
                    val idx = state.nextToolCallIndex++
                    val args = fn.opt("arguments")?.toString() ?: "{}"
                    tcDeltas += ToolCallDelta(index = idx, id = "call_ollama_$idx", name = name, argumentsChunk = args)
                }
            }
        }
        state.recordToolDeltas(tcDeltas)
        val done = json.optBoolean("done", false)
        val inTok = json.optInt("prompt_eval_count", 0)
        val outTok = json.optInt("eval_count", 0)
        if (inTok > 0) state.inputTokens = inTok
        if (outTok > 0) state.outputTokens = outTok
        val finish = if (done) {
            if (state.accumulatedToolCalls.isNotEmpty()) "tool_calls" else "stop"
        } else null
        if (finish != null) state.finishReason = finish
        return NormalizedStreamDelta(
            textDelta = text,
            toolCallDeltas = tcDeltas,
            inputTokens = inTok,
            outputTokens = outTok,
            finishReason = finish,
            isTerminal = done
        )
    }

    override fun normalizeHttpError(httpCode: Int, bodyStr: String, latencyMs: Long, apiKey: String): ChatExecutionResult =
        AdapterHelper.defaultNormalizeHttpError(httpCode, bodyStr, latencyMs, apiKey)
}
