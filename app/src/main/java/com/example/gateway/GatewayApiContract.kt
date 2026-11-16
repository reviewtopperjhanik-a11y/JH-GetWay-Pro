package com.example.gateway

import com.example.network.ChatExecutionResult
import com.example.network.ChatMessagePayload
import com.example.network.StreamEvent
import com.example.network.adapters.ContentPart
import com.example.network.adapters.NormalizedResponseFormat
import com.example.network.adapters.NormalizedToolChoice
import com.example.network.adapters.NormalizedToolDefinition
import com.example.network.adapters.ToolCallPayload
import io.ktor.http.HttpStatusCode
import org.json.JSONArray
import org.json.JSONObject
import java.io.Writer
import java.util.UUID

data class ParsedGatewayChatRequest(
    val model: String,
    val messages: List<ChatMessagePayload>,
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    val stopSequences: List<String> = emptyList(),
    val jsonOutput: Boolean = false,
    val responseFormat: NormalizedResponseFormat? = null,
    val tools: List<NormalizedToolDefinition> = emptyList(),
    val toolChoice: NormalizedToolChoice? = null,
    val stream: Boolean = false
)

sealed interface ContractParseResult<out T> {
    data class Valid<T>(val value: T) : ContractParseResult<T>
    data class Invalid(
        val statusCode: HttpStatusCode,
        val errorType: String,
        val errorCode: String,
        val message: String
    ) : ContractParseResult<Nothing>
}

object GatewayApiContract {

    val supportedEndpoints: List<String> = listOf(
        "/health",
        "/v1/models",
        "/v1/chat/completions",
        "/v1/responses",
        "/v1/messages"
    )

    val unsupportedEndpoints: List<String> = listOf(
        "/v1/embeddings",
        "/v1/images/generations",
        "/v1/audio/transcriptions",
        "/v1/audio/speech"
    )

    // -------------------------------------------------------------------------
    // OpenAI Chat Completions (/v1/chat/completions)
    // -------------------------------------------------------------------------

    fun parseOpenAiChatRequest(body: String, streamingEnabledSetting: Boolean): ContractParseResult<ParsedGatewayChatRequest> {
        val req = runCatching { JSONObject(body) }.getOrNull()
            ?: return ContractParseResult.Invalid(
                HttpStatusCode.BadRequest, "invalid_request_error", "invalid_json", "Invalid JSON body"
            )

        val model = req.optString("model").trim()
        val msgsArr = req.optJSONArray("messages")
            ?: return ContractParseResult.Invalid(
                HttpStatusCode.BadRequest, "invalid_request_error", "missing_messages", "Missing required 'messages' array"
            )
        if (msgsArr.length() == 0) {
            return ContractParseResult.Invalid(
                HttpStatusCode.BadRequest, "invalid_request_error", "empty_messages", "'messages' array must not be empty"
            )
        }

        val parsedMessages = mutableListOf<ChatMessagePayload>()
        val allowedRoles = setOf("system", "developer", "user", "assistant", "tool")
        for (i in 0 until msgsArr.length()) {
            val m = msgsArr.optJSONObject(i)
                ?: return ContractParseResult.Invalid(
                    HttpStatusCode.BadRequest, "invalid_request_error", "invalid_message", "Message at index $i must be an object"
                )
            val role = m.optString("role").trim().lowercase()
            if (role !in allowedRoles) {
                return ContractParseResult.Invalid(
                    HttpStatusCode.BadRequest, "invalid_request_error", "invalid_role", "Unsupported message role '$role' at index $i"
                )
            }

            val name = m.optString("name").takeIf { it.isNotBlank() }
            val toolCallId = m.optString("tool_call_id").takeIf { it.isNotBlank() }
            if (role == "tool" && toolCallId == null) {
                return ContractParseResult.Invalid(
                    HttpStatusCode.BadRequest, "invalid_request_error", "missing_tool_call_id", "Tool message at index $i requires 'tool_call_id'"
                )
            }

            val contentVal = m.opt("content")
            val contentParts = mutableListOf<ContentPart>()
            val plainContent = when (contentVal) {
                null, JSONObject.NULL -> ""
                is String -> contentVal
                is JSONArray -> {
                    val sb = StringBuilder()
                    for (pIdx in 0 until contentVal.length()) {
                        val partObj = contentVal.optJSONObject(pIdx)
                            ?: return ContractParseResult.Invalid(
                                HttpStatusCode.BadRequest, "invalid_request_error", "invalid_content_part", "Content part must be an object"
                            )
                        when (val pType = partObj.optString("type").lowercase()) {
                            "text", "input_text", "output_text" -> {
                                val t = partObj.optString("text")
                                contentParts += ContentPart.Text(t)
                                if (sb.isNotEmpty()) sb.append("\n")
                                sb.append(t)
                            }
                            "image_url" -> {
                                val img = partObj.optJSONObject("image_url")
                                val url = img?.optString("url") ?: partObj.optString("image_url")
                                if (url.isBlank()) {
                                    return ContractParseResult.Invalid(
                                        HttpStatusCode.BadRequest, "invalid_request_error", "invalid_image_url", "image_url block requires a non-empty url"
                                    )
                                }
                                contentParts += ContentPart.ImageUrl(url = url, detail = img?.optString("detail")?.takeIf { it.isNotBlank() })
                            }
                            else -> return ContractParseResult.Invalid(
                                HttpStatusCode.BadRequest, "invalid_request_error", "unsupported_content_part", "Unsupported content part type '$pType'"
                            )
                        }
                    }
                    sb.toString()
                }
                else -> return ContractParseResult.Invalid(
                    HttpStatusCode.BadRequest, "invalid_request_error", "invalid_content", "Message content must be a string, array, or null"
                )
            }

            val toolCalls = mutableListOf<ToolCallPayload>()
            m.optJSONArray("tool_calls")?.let { tcArr ->
                for (tcIdx in 0 until tcArr.length()) {
                    val tcObj = tcArr.optJSONObject(tcIdx) ?: continue
                    val fnObj = tcObj.optJSONObject("function")
                        ?: return ContractParseResult.Invalid(
                            HttpStatusCode.BadRequest, "invalid_request_error", "invalid_tool_call", "Assistant tool_call at index $tcIdx requires 'function'"
                        )
                    val fnName = fnObj.optString("name").trim()
                    if (fnName.isBlank()) {
                        return ContractParseResult.Invalid(
                            HttpStatusCode.BadRequest, "invalid_request_error", "invalid_tool_call", "Assistant tool_call missing function name"
                        )
                    }
                    toolCalls += ToolCallPayload(
                        id = tcObj.optString("id").ifBlank { "call_$tcIdx" },
                        name = fnName,
                        argumentsJson = fnObj.optString("arguments", "{}"),
                        index = tcIdx
                    )
                }
            }

            parsedMessages += ChatMessagePayload(
                role = role,
                content = plainContent,
                contentParts = contentParts,
                name = name,
                toolCallId = toolCallId,
                toolCalls = toolCalls
            )
        }

        val tools = parseOpenAiTools(req.optJSONArray("tools"))
            .let { res ->
                when (res) {
                    is ContractParseResult.Invalid -> return res
                    is ContractParseResult.Valid -> res.value
                }
            }

        val toolChoice = parseOpenAiToolChoice(req.opt("tool_choice"), tools)
            .let { res ->
                when (res) {
                    is ContractParseResult.Invalid -> return res
                    is ContractParseResult.Valid -> res.value
                }
            }

        val responseFormat = parseOpenAiResponseFormat(req.optJSONObject("response_format"))
        val stopSequences = parseStopParam(req.opt("stop"))
        val maxTokens = when {
            req.has("max_completion_tokens") && !req.isNull("max_completion_tokens") -> req.optInt("max_completion_tokens")
            req.has("max_tokens") && !req.isNull("max_tokens") -> req.optInt("max_tokens")
            else -> null
        }

        return ContractParseResult.Valid(
            ParsedGatewayChatRequest(
                model = model,
                messages = parsedMessages,
                temperature = if (req.has("temperature") && !req.isNull("temperature")) req.optDouble("temperature").toFloat() else null,
                topP = if (req.has("top_p") && !req.isNull("top_p")) req.optDouble("top_p").toFloat() else null,
                maxTokens = maxTokens,
                stopSequences = stopSequences,
                jsonOutput = responseFormat?.type == "json_object",
                responseFormat = responseFormat,
                tools = tools,
                toolChoice = toolChoice,
                stream = req.optBoolean("stream", false) && streamingEnabledSetting
            )
        )
    }

    fun formatOpenAiChatResponse(requestId: String, model: String, result: ChatExecutionResult): JSONObject {
        val msgObj = JSONObject().put("role", "assistant")
        if (result.toolCalls.isNotEmpty()) {
            msgObj.put("content", if (result.content.isNotEmpty()) result.content else JSONObject.NULL)
            val tcArr = JSONArray()
            result.toolCalls.forEach { tc ->
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
        } else {
            msgObj.put("content", result.content)
        }

        return JSONObject().apply {
            put("id", "chatcmpl-$requestId")
            put("object", "chat.completion")
            put("created", System.currentTimeMillis() / 1000)
            put("model", result.modelUsed ?: model)
            put(
                "choices",
                JSONArray().put(
                    JSONObject()
                        .put("index", 0)
                        .put("message", msgObj)
                        .put("finish_reason", result.finishReason)
                )
            )
            put(
                "usage",
                JSONObject()
                    .put("prompt_tokens", result.inputTokens)
                    .put("completion_tokens", result.outputTokens)
                    .put("total_tokens", result.inputTokens + result.outputTokens)
            )
        }
    }

    fun writeOpenAiSseEvent(
        writer: Writer,
        cid: String,
        model: String,
        event: StreamEvent,
        isFirstChunk: Boolean
    ): Boolean {
        var emitted = false
        when (event) {
            is StreamEvent.Delta -> {
                val deltaObj = JSONObject()
                if (isFirstChunk) deltaObj.put("role", "assistant")
                if (event.text.isNotEmpty()) deltaObj.put("content", event.text)
                if (event.toolCallDeltas.isNotEmpty()) {
                    val tcArr = JSONArray()
                    event.toolCallDeltas.forEach { d ->
                        val fnObj = JSONObject()
                        if (!d.name.isNullOrBlank()) fnObj.put("name", d.name)
                        if (d.argumentsChunk != null) fnObj.put("arguments", d.argumentsChunk)
                        val item = JSONObject().put("index", d.index)
                        if (!d.id.isNullOrBlank()) {
                            item.put("id", d.id)
                            item.put("type", "function")
                        }
                        if (fnObj.length() > 0) item.put("function", fnObj)
                        tcArr.put(item)
                    }
                    deltaObj.put("tool_calls", tcArr)
                }
                if (deltaObj.length() > 0) {
                    val chunk = JSONObject()
                        .put("id", cid)
                        .put("object", "chat.completion.chunk")
                        .put("created", System.currentTimeMillis() / 1000)
                        .put("model", model)
                        .put(
                            "choices",
                            JSONArray().put(
                                JSONObject()
                                    .put("index", 0)
                                    .put("delta", deltaObj)
                                    .put("finish_reason", JSONObject.NULL)
                            )
                        )
                    writer.write("data: $chunk\n\n")
                    writer.flush()
                    emitted = true
                }
            }
            is StreamEvent.Completed -> {
                val endChunk = JSONObject()
                    .put("id", cid)
                    .put("object", "chat.completion.chunk")
                    .put("created", System.currentTimeMillis() / 1000)
                    .put("model", event.result.modelUsed ?: model)
                    .put(
                        "choices",
                        JSONArray().put(
                            JSONObject()
                                .put("index", 0)
                                .put("delta", JSONObject())
                                .put("finish_reason", event.result.finishReason)
                        )
                    )
                    .put(
                        "usage",
                        JSONObject()
                            .put("prompt_tokens", event.result.inputTokens)
                            .put("completion_tokens", event.result.outputTokens)
                            .put("total_tokens", event.result.inputTokens + event.result.outputTokens)
                    )
                writer.write("data: $endChunk\n\n")
                writer.write("data: [DONE]\n\n")
                writer.flush()
            }
            is StreamEvent.Failed -> {
                val errObj = JSONObject().put(
                    "error",
                    JSONObject()
                        .put("message", event.error.safeErrorMessage ?: "Upstream error")
                        .put("type", "upstream_error")
                        .put("code", event.error.errorCode ?: "gateway_error")
                )
                writer.write("data: $errObj\n\n")
                writer.write("data: [DONE]\n\n")
                writer.flush()
            }
        }
        return emitted
    }

    // -------------------------------------------------------------------------
    // Anthropic Messages API (/v1/messages)
    // -------------------------------------------------------------------------

    fun parseAnthropicMessagesRequest(body: String, streamingEnabledSetting: Boolean): ContractParseResult<ParsedGatewayChatRequest> {
        val req = runCatching { JSONObject(body) }.getOrNull()
            ?: return ContractParseResult.Invalid(
                HttpStatusCode.BadRequest, "invalid_request_error", "invalid_json", "Invalid JSON body"
            )

        val model = req.optString("model").trim()
        if (model.isBlank()) {
            return ContractParseResult.Invalid(
                HttpStatusCode.BadRequest, "invalid_request_error", "missing_model", "Anthropic Messages API requires 'model'"
            )
        }
        if (!req.has("max_tokens") || req.isNull("max_tokens") || req.optInt("max_tokens", 0) <= 0) {
            return ContractParseResult.Invalid(
                HttpStatusCode.BadRequest, "invalid_request_error", "missing_max_tokens", "Anthropic Messages API requires positive integer 'max_tokens'"
            )
        }
        val maxTokens = req.optInt("max_tokens")

        val parsedMessages = mutableListOf<ChatMessagePayload>()
        when (val sysVal = req.opt("system")) {
            is String -> if (sysVal.isNotBlank()) parsedMessages += ChatMessagePayload("system", sysVal)
            is JSONArray -> {
                val sysParts = mutableListOf<ContentPart>()
                val sb = StringBuilder()
                for (i in 0 until sysVal.length()) {
                    val b = sysVal.optJSONObject(i) ?: continue
                    if (b.optString("type") == "text") {
                        val t = b.optString("text")
                        sysParts += ContentPart.Text(t)
                        if (sb.isNotEmpty()) sb.append("\n")
                        sb.append(t)
                    }
                }
                if (sb.isNotBlank()) parsedMessages += ChatMessagePayload("system", sb.toString(), contentParts = sysParts)
            }
        }

        val msgsArr = req.optJSONArray("messages")
            ?: return ContractParseResult.Invalid(
                HttpStatusCode.BadRequest, "invalid_request_error", "missing_messages", "Missing required 'messages' array"
            )
        if (msgsArr.length() == 0) {
            return ContractParseResult.Invalid(
                HttpStatusCode.BadRequest, "invalid_request_error", "empty_messages", "'messages' array must not be empty"
            )
        }

        for (i in 0 until msgsArr.length()) {
            val m = msgsArr.optJSONObject(i)
                ?: return ContractParseResult.Invalid(
                    HttpStatusCode.BadRequest, "invalid_request_error", "invalid_message", "Message at index $i must be an object"
                )
            val role = m.optString("role").trim().lowercase()
            if (role != "user" && role != "assistant") {
                return ContractParseResult.Invalid(
                    HttpStatusCode.BadRequest, "invalid_request_error", "invalid_role", "Anthropic message role must be 'user' or 'assistant'"
                )
            }

            when (val contentVal = m.opt("content")) {
                is String -> parsedMessages += ChatMessagePayload(role = role, content = contentVal)
                is JSONArray -> {
                    val regularParts = mutableListOf<ContentPart>()
                    val plainTextSb = StringBuilder()
                    val assistantTools = mutableListOf<ToolCallPayload>()
                    val toolResults = mutableListOf<ChatMessagePayload>()

                    for (bIdx in 0 until contentVal.length()) {
                        val blk = contentVal.optJSONObject(bIdx) ?: continue
                        when (blk.optString("type").lowercase()) {
                            "text" -> {
                                val t = blk.optString("text")
                                regularParts += ContentPart.Text(t)
                                if (plainTextSb.isNotEmpty()) plainTextSb.append("\n")
                                plainTextSb.append(t)
                            }
                            "image" -> {
                                val src = blk.optJSONObject("source")
                                val srcType = src?.optString("type").orEmpty()
                                val url = when (srcType) {
                                    "base64" -> "data:${src?.optString("media_type", "image/png")};base64,${src?.optString("data").orEmpty()}"
                                    "url" -> src?.optString("url").orEmpty()
                                    else -> ""
                                }
                                if (url.isNotBlank()) regularParts += ContentPart.ImageUrl(url)
                            }
                            "tool_use" -> {
                                val id = blk.optString("id").ifBlank { "toolu_$bIdx" }
                                val name = blk.optString("name").trim()
                                val inputJson = blk.optJSONObject("input")?.toString() ?: "{}"
                                if (name.isBlank()) {
                                    return ContractParseResult.Invalid(
                                        HttpStatusCode.BadRequest, "invalid_request_error", "invalid_tool_use", "tool_use block missing 'name'"
                                    )
                                }
                                assistantTools += ToolCallPayload(id = id, name = name, argumentsJson = inputJson, index = assistantTools.size)
                            }
                            "tool_result" -> {
                                val toolUseId = blk.optString("tool_use_id").trim()
                                if (toolUseId.isBlank()) {
                                    return ContractParseResult.Invalid(
                                        HttpStatusCode.BadRequest, "invalid_request_error", "invalid_tool_result", "tool_result block requires 'tool_use_id'"
                                    )
                                }
                                val resText = when (val c = blk.opt("content")) {
                                    is String -> c
                                    is JSONArray -> (0 until c.length()).mapNotNull { idx ->
                                        c.optJSONObject(idx)?.optString("text")
                                    }.joinToString("\n")
                                    else -> c?.toString().orEmpty()
                                }
                                toolResults += ChatMessagePayload(
                                    role = "tool",
                                    content = resText,
                                    toolCallId = toolUseId
                                )
                            }
                        }
                    }

                    // Emit tool_result messages first in sequence, then any text/tool_use message
                    parsedMessages.addAll(toolResults)
                    if (regularParts.isNotEmpty() || assistantTools.isNotEmpty() || toolResults.isEmpty()) {
                        parsedMessages += ChatMessagePayload(
                            role = role,
                            content = plainTextSb.toString(),
                            contentParts = regularParts,
                            toolCalls = assistantTools
                        )
                    }
                }
                else -> return ContractParseResult.Invalid(
                    HttpStatusCode.BadRequest, "invalid_request_error", "invalid_content", "Anthropic message 'content' must be string or array"
                )
            }
        }

        val tools = mutableListOf<NormalizedToolDefinition>()
        req.optJSONArray("tools")?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name").trim()
                if (name.isBlank()) {
                    return ContractParseResult.Invalid(
                        HttpStatusCode.BadRequest, "invalid_request_error", "invalid_tool_schema", "Tool at index $i missing 'name'"
                    )
                }
                val schema = obj.optJSONObject("input_schema")
                    ?: JSONObject().put("type", "object").put("properties", JSONObject())
                tools += NormalizedToolDefinition(
                    name = name,
                    description = obj.optString("description"),
                    parametersSchema = schema
                )
            }
        }

        val toolChoice = req.optJSONObject("tool_choice")?.let { tcObj ->
            when (tcObj.optString("type").lowercase()) {
                "auto" -> NormalizedToolChoice.Auto
                "any" -> NormalizedToolChoice.Required
                "none" -> NormalizedToolChoice.None
                "tool" -> {
                    val name = tcObj.optString("name").trim()
                    if (name.isBlank()) {
                        return ContractParseResult.Invalid(
                            HttpStatusCode.BadRequest, "invalid_request_error", "invalid_tool_choice", "tool_choice type 'tool' requires 'name'"
                        )
                    }
                    NormalizedToolChoice.SpecificFunction(name)
                }
                else -> null
            }
        }

        val stops = mutableListOf<String>()
        req.optJSONArray("stop_sequences")?.let { arr ->
            for (i in 0 until arr.length()) stops += arr.optString(i)
        }

        return ContractParseResult.Valid(
            ParsedGatewayChatRequest(
                model = model,
                messages = parsedMessages,
                temperature = if (req.has("temperature") && !req.isNull("temperature")) req.optDouble("temperature").toFloat() else null,
                topP = if (req.has("top_p") && !req.isNull("top_p")) req.optDouble("top_p").toFloat() else null,
                maxTokens = maxTokens,
                stopSequences = stops,
                tools = tools,
                toolChoice = toolChoice,
                stream = req.optBoolean("stream", false) && streamingEnabledSetting
            )
        )
    }

    fun formatAnthropicMessagesResponse(requestId: String, model: String, result: ChatExecutionResult): JSONObject {
        val contentBlocks = JSONArray()
        if (result.content.isNotEmpty() || result.toolCalls.isEmpty()) {
            contentBlocks.put(JSONObject().put("type", "text").put("text", result.content))
        }
        result.toolCalls.forEach { tc ->
            val inputObj = runCatching { JSONObject(tc.argumentsJson) }.getOrDefault(JSONObject())
            contentBlocks.put(
                JSONObject()
                    .put("type", "tool_use")
                    .put("id", tc.id)
                    .put("name", tc.name)
                    .put("input", inputObj)
            )
        }
        val stopReason = when {
            result.toolCalls.isNotEmpty() || result.finishReason == "tool_calls" -> "tool_use"
            result.finishReason == "length" -> "max_tokens"
            else -> "end_turn"
        }
        return JSONObject().apply {
            put("id", "msg_$requestId")
            put("type", "message")
            put("role", "assistant")
            put("model", result.modelUsed ?: model)
            put("content", contentBlocks)
            put("stop_reason", stopReason)
            put("stop_sequence", JSONObject.NULL)
            put(
                "usage",
                JSONObject()
                    .put("input_tokens", result.inputTokens)
                    .put("output_tokens", result.outputTokens)
            )
        }
    }

    fun formatAnthropicError(type: String, message: String): String = JSONObject()
        .put("type", "error")
        .put("error", JSONObject().put("type", type).put("message", message))
        .toString()

    class AnthropicSseWriter(
        private val writer: Writer,
        private val msgId: String,
        private val model: String
    ) {
        private var messageStarted = false
        private var textBlockStarted = false
        private var textBlockIndex = -1
        private var nextBlockIndex = 0
        private val openToolBlockIndices = mutableMapOf<Int, Int>()

        private fun ensureMessageStart() {
            if (messageStarted) return
            messageStarted = true
            val payload = JSONObject().put("type", "message_start").put(
                "message",
                JSONObject()
                    .put("id", msgId)
                    .put("type", "message")
                    .put("role", "assistant")
                    .put("model", model)
                    .put("content", JSONArray())
                    .put("stop_reason", JSONObject.NULL)
                    .put("stop_sequence", JSONObject.NULL)
                    .put("usage", JSONObject().put("input_tokens", 0).put("output_tokens", 0))
            )
            emit("message_start", payload)
        }

        fun onEvent(event: StreamEvent) {
            when (event) {
                is StreamEvent.Delta -> {
                    ensureMessageStart()
                    if (event.text.isNotEmpty()) {
                        if (!textBlockStarted) {
                            textBlockIndex = nextBlockIndex++
                            textBlockStarted = true
                            emit(
                                "content_block_start",
                                JSONObject()
                                    .put("type", "content_block_start")
                                    .put("index", textBlockIndex)
                                    .put("content_block", JSONObject().put("type", "text").put("text", ""))
                            )
                        }
                        emit(
                            "content_block_delta",
                            JSONObject()
                                .put("type", "content_block_delta")
                                .put("index", textBlockIndex)
                                .put("delta", JSONObject().put("type", "text_delta").put("text", event.text))
                        )
                    }
                    event.toolCallDeltas.forEach { d ->
                        val blockIdx = openToolBlockIndices.getOrPut(d.index) {
                            val allocated = nextBlockIndex++
                            emit(
                                "content_block_start",
                                JSONObject()
                                    .put("type", "content_block_start")
                                    .put("index", allocated)
                                    .put(
                                        "content_block",
                                        JSONObject()
                                            .put("type", "tool_use")
                                            .put("id", d.id ?: "toolu_${d.index}")
                                            .put("name", d.name ?: "tool")
                                            .put("input", JSONObject())
                                    )
                            )
                            allocated
                        }
                        if (!d.argumentsChunk.isNullOrEmpty()) {
                            emit(
                                "content_block_delta",
                                JSONObject()
                                    .put("type", "content_block_delta")
                                    .put("index", blockIdx)
                                    .put(
                                        "delta",
                                        JSONObject()
                                            .put("type", "input_json_delta")
                                            .put("partial_json", d.argumentsChunk)
                                    )
                            )
                        }
                    }
                }
                is StreamEvent.Completed -> {
                    ensureMessageStart()
                    if (textBlockStarted) {
                        emit(
                            "content_block_stop",
                            JSONObject().put("type", "content_block_stop").put("index", textBlockIndex)
                        )
                    }
                    openToolBlockIndices.values.sorted().forEach { idx ->
                        emit(
                            "content_block_stop",
                            JSONObject().put("type", "content_block_stop").put("index", idx)
                        )
                    }
                    val stopReason = when {
                        event.result.toolCalls.isNotEmpty() || event.result.finishReason == "tool_calls" -> "tool_use"
                        event.result.finishReason == "length" -> "max_tokens"
                        else -> "end_turn"
                    }
                    emit(
                        "message_delta",
                        JSONObject()
                            .put("type", "message_delta")
                            .put("delta", JSONObject().put("stop_reason", stopReason).put("stop_sequence", JSONObject.NULL))
                            .put("usage", JSONObject().put("output_tokens", event.result.outputTokens))
                    )
                    emit("message_stop", JSONObject().put("type", "message_stop"))
                }
                is StreamEvent.Failed -> {
                    emit(
                        "error",
                        JSONObject()
                            .put("type", "error")
                            .put(
                                "error",
                                JSONObject()
                                    .put("type", "api_error")
                                    .put("message", event.error.safeErrorMessage ?: "Upstream error")
                            )
                    )
                }
            }
        }

        private fun emit(eventName: String, json: JSONObject) {
            writer.write("event: $eventName\ndata: $json\n\n")
            writer.flush()
        }
    }

    // -------------------------------------------------------------------------
    // OpenAI Responses API (/v1/responses - Stateless + Tool Calling Contract)
    // -------------------------------------------------------------------------

    fun parseOpenAiResponsesRequest(body: String, streamingEnabledSetting: Boolean): ContractParseResult<ParsedGatewayChatRequest> {
        val req = runCatching { JSONObject(body) }.getOrNull()
            ?: return ContractParseResult.Invalid(
                HttpStatusCode.BadRequest, "invalid_request_error", "invalid_json", "Invalid JSON body"
            )

        if (req.has("previous_response_id") && !req.isNull("previous_response_id") && req.optString("previous_response_id").isNotBlank()) {
            return ContractParseResult.Invalid(
                HttpStatusCode.NotImplemented,
                "not_implemented_error",
                "stateful_previous_response_unsupported",
                "Stateful 'previous_response_id' chaining is not supported on this stateless gateway; pass full 'input' items."
            )
        }
        if (req.optBoolean("background", false)) {
            return ContractParseResult.Invalid(
                HttpStatusCode.NotImplemented,
                "not_implemented_error",
                "background_responses_unsupported",
                "Background async responses are not supported in this version."
            )
        }

        val model = req.optString("model").trim()
        val parsedMessages = mutableListOf<ChatMessagePayload>()
        val instructions = req.optString("instructions").trim()
        if (instructions.isNotBlank()) {
            parsedMessages += ChatMessagePayload(role = "developer", content = instructions)
        }

        val inputVal = req.opt("input")
            ?: return ContractParseResult.Invalid(
                HttpStatusCode.BadRequest, "invalid_request_error", "missing_input", "Responses API requires 'input'"
            )

        when (inputVal) {
            is String -> parsedMessages += ChatMessagePayload(role = "user", content = inputVal)
            is JSONArray -> {
                if (inputVal.length() == 0) {
                    return ContractParseResult.Invalid(
                        HttpStatusCode.BadRequest, "invalid_request_error", "empty_input", "'input' array must not be empty"
                    )
                }
                for (i in 0 until inputVal.length()) {
                    val item = inputVal.optJSONObject(i)
                        ?: return ContractParseResult.Invalid(
                            HttpStatusCode.BadRequest, "invalid_request_error", "invalid_input_item", "Input item at index $i must be an object"
                        )
                    val itemType = item.optString("type").lowercase()
                    when (itemType) {
                        "item_reference" -> return ContractParseResult.Invalid(
                            HttpStatusCode.NotImplemented,
                            "not_implemented_error",
                            "item_reference_unsupported",
                            "Server-side 'item_reference' lookup is not supported on this stateless gateway."
                        )
                        "function_call" -> {
                            val callId = item.optString("call_id").ifBlank { item.optString("id") }
                            val fnName = item.optString("name").trim()
                            val args = item.optString("arguments", "{}")
                            if (fnName.isBlank()) {
                                return ContractParseResult.Invalid(
                                    HttpStatusCode.BadRequest, "invalid_request_error", "invalid_function_call", "function_call item requires 'name'"
                                )
                            }
                            parsedMessages += ChatMessagePayload(
                                role = "assistant",
                                content = "",
                                toolCalls = listOf(ToolCallPayload(id = callId.ifBlank { "call_$i" }, name = fnName, argumentsJson = args))
                            )
                        }
                        "function_call_output" -> {
                            val callId = item.optString("call_id").trim()
                            if (callId.isBlank()) {
                                return ContractParseResult.Invalid(
                                    HttpStatusCode.BadRequest, "invalid_request_error", "missing_call_id", "function_call_output item requires 'call_id'"
                                )
                            }
                            val rawOutput = item.opt("output")
                            val outputStr = if (rawOutput == null || rawOutput == JSONObject.NULL) "" else rawOutput.toString()
                            parsedMessages += ChatMessagePayload(
                                role = "tool",
                                content = outputStr,
                                toolCallId = callId
                            )
                        }
                        "", "message" -> {
                            val role = item.optString("role", "user").trim().lowercase()
                            val contentVal = item.opt("content")
                            val parts = mutableListOf<ContentPart>()
                            val sb = StringBuilder()
                            when (contentVal) {
                                is String -> sb.append(contentVal)
                                is JSONArray -> {
                                    for (cIdx in 0 until contentVal.length()) {
                                        val cObj = contentVal.optJSONObject(cIdx) ?: continue
                                        when (cObj.optString("type").lowercase()) {
                                            "input_text", "output_text", "text" -> {
                                                val t = cObj.optString("text")
                                                parts += ContentPart.Text(t)
                                                if (sb.isNotEmpty()) sb.append("\n")
                                                sb.append(t)
                                            }
                                            "input_image" -> {
                                                val imgUrl = cObj.optString("image_url")
                                                if (imgUrl.isBlank()) {
                                                    return ContractParseResult.Invalid(
                                                        HttpStatusCode.BadRequest, "invalid_request_error", "invalid_image_url", "input_image requires 'image_url'"
                                                    )
                                                }
                                                parts += ContentPart.ImageUrl(imgUrl, cObj.optString("detail").takeIf { it.isNotBlank() })
                                            }
                                        }
                                    }
                                }
                                else -> return ContractParseResult.Invalid(
                                    HttpStatusCode.BadRequest, "invalid_request_error", "invalid_content", "Message item at index $i requires 'content'"
                                )
                            }
                            parsedMessages += ChatMessagePayload(
                                role = role,
                                content = sb.toString(),
                                contentParts = parts
                            )
                        }
                        else -> return ContractParseResult.Invalid(
                            HttpStatusCode.BadRequest, "invalid_request_error", "unsupported_input_type", "Unsupported input item type '$itemType'"
                        )
                    }
                }
            }
            else -> return ContractParseResult.Invalid(
                HttpStatusCode.BadRequest, "invalid_request_error", "invalid_input", "'input' must be a string or an array of items"
            )
        }

        val tools = parseResponsesTools(req.optJSONArray("tools"))
            .let { res ->
                when (res) {
                    is ContractParseResult.Invalid -> return res
                    is ContractParseResult.Valid -> res.value
                }
            }

        val toolChoice = parseOpenAiToolChoice(req.opt("tool_choice"), tools)
            .let { res ->
                when (res) {
                    is ContractParseResult.Invalid -> return res
                    is ContractParseResult.Valid -> res.value
                }
            }

        val responseFormat = req.optJSONObject("text")?.optJSONObject("format")?.let { parseOpenAiResponseFormat(it) }
        val maxTokens = if (req.has("max_output_tokens") && !req.isNull("max_output_tokens")) req.optInt("max_output_tokens") else null

        return ContractParseResult.Valid(
            ParsedGatewayChatRequest(
                model = model,
                messages = parsedMessages,
                temperature = if (req.has("temperature") && !req.isNull("temperature")) req.optDouble("temperature").toFloat() else null,
                topP = if (req.has("top_p") && !req.isNull("top_p")) req.optDouble("top_p").toFloat() else null,
                maxTokens = maxTokens,
                jsonOutput = responseFormat?.type == "json_object",
                responseFormat = responseFormat,
                tools = tools,
                toolChoice = toolChoice,
                stream = req.optBoolean("stream", false) && streamingEnabledSetting
            )
        )
    }

    fun formatOpenAiResponsesResponse(responseId: String, model: String, result: ChatExecutionResult): JSONObject {
        val outputArr = JSONArray()
        if (result.content.isNotEmpty() || result.toolCalls.isEmpty()) {
            outputArr.put(
                JSONObject()
                    .put("id", "msg_${responseId.take(8)}")
                    .put("type", "message")
                    .put("status", "completed")
                    .put("role", "assistant")
                    .put(
                        "content",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "output_text")
                                .put("text", result.content)
                                .put("annotations", JSONArray())
                        )
                    )
            )
        }
        result.toolCalls.forEach { tc ->
            outputArr.put(
                JSONObject()
                    .put("id", "fc_${tc.id.take(12)}")
                    .put("type", "function_call")
                    .put("status", "completed")
                    .put("call_id", tc.id)
                    .put("name", tc.name)
                    .put("arguments", tc.argumentsJson)
            )
        }

        val status = if (result.finishReason == "length") "incomplete" else "completed"
        return JSONObject().apply {
            put("id", responseId)
            put("object", "response")
            put("created_at", System.currentTimeMillis() / 1000)
            put("status", status)
            if (status == "incomplete") {
                put("incomplete_details", JSONObject().put("reason", "max_output_tokens"))
            } else {
                put("incomplete_details", JSONObject.NULL)
            }
            put("error", JSONObject.NULL)
            put("model", result.modelUsed ?: model)
            put("output", outputArr)
            put("output_text", result.content)
            put(
                "usage",
                JSONObject()
                    .put("input_tokens", result.inputTokens)
                    .put("output_tokens", result.outputTokens)
                    .put("total_tokens", result.inputTokens + result.outputTokens)
            )
        }
    }

    class ResponsesSseWriter(
        private val writer: Writer,
        private val responseId: String,
        private val model: String
    ) {
        private var initialized = false
        private var textItemAdded = false
        private val textItemId = "msg_${UUID.randomUUID().toString().take(8)}"
        private val textContent = StringBuilder()
        private var nextOutputIndex = 0
        private var textOutputIndex = -1
        private val openFunctionItems = mutableMapOf<Int, Pair<Int, String>>() // toolIdx -> (outputIdx, fcId)

        private fun ensureCreated() {
            if (initialized) return
            initialized = true
            val baseResp = JSONObject()
                .put("id", responseId)
                .put("object", "response")
                .put("created_at", System.currentTimeMillis() / 1000)
                .put("status", "in_progress")
                .put("model", model)
                .put("output", JSONArray())
            emit("response.created", JSONObject().put("type", "response.created").put("response", baseResp))
            emit("response.in_progress", JSONObject().put("type", "response.in_progress").put("response", baseResp))
        }

        fun onEvent(event: StreamEvent) {
            when (event) {
                is StreamEvent.Delta -> {
                    ensureCreated()
                    if (event.text.isNotEmpty()) {
                        if (!textItemAdded) {
                            textItemAdded = true
                            textOutputIndex = nextOutputIndex++
                            emit(
                                "response.output_item.added",
                                JSONObject()
                                    .put("type", "response.output_item.added")
                                    .put("output_index", textOutputIndex)
                                    .put(
                                        "item",
                                        JSONObject()
                                            .put("id", textItemId)
                                            .put("type", "message")
                                            .put("status", "in_progress")
                                            .put("role", "assistant")
                                            .put("content", JSONArray())
                                    )
                            )
                            emit(
                                "response.content_part.added",
                                JSONObject()
                                    .put("type", "response.content_part.added")
                                    .put("item_id", textItemId)
                                    .put("output_index", textOutputIndex)
                                    .put("content_index", 0)
                                    .put("part", JSONObject().put("type", "output_text").put("text", "").put("annotations", JSONArray()))
                            )
                        }
                        textContent.append(event.text)
                        emit(
                            "response.output_text.delta",
                            JSONObject()
                                .put("type", "response.output_text.delta")
                                .put("item_id", textItemId)
                                .put("output_index", textOutputIndex)
                                .put("content_index", 0)
                                .put("delta", event.text)
                        )
                    }
                    event.toolCallDeltas.forEach { d ->
                        val (outIdx, fcId) = openFunctionItems.getOrPut(d.index) {
                            val idx = nextOutputIndex++
                            val id = "fc_${(d.id ?: UUID.randomUUID().toString()).take(12)}"
                            emit(
                                "response.output_item.added",
                                JSONObject()
                                    .put("type", "response.output_item.added")
                                    .put("output_index", idx)
                                    .put(
                                        "item",
                                        JSONObject()
                                            .put("id", id)
                                            .put("type", "function_call")
                                            .put("status", "in_progress")
                                            .put("call_id", d.id ?: "call_${d.index}")
                                            .put("name", d.name ?: "tool")
                                            .put("arguments", "")
                                    )
                            )
                            idx to id
                        }
                        if (!d.argumentsChunk.isNullOrEmpty()) {
                            emit(
                                "response.function_call_arguments.delta",
                                JSONObject()
                                    .put("type", "response.function_call_arguments.delta")
                                    .put("item_id", fcId)
                                    .put("output_index", outIdx)
                                    .put("delta", d.argumentsChunk)
                            )
                        }
                    }
                }
                is StreamEvent.Completed -> {
                    ensureCreated()
                    if (textItemAdded) {
                        val finalTxt = textContent.toString()
                        emit(
                            "response.output_text.done",
                            JSONObject()
                                .put("type", "response.output_text.done")
                                .put("item_id", textItemId)
                                .put("output_index", textOutputIndex)
                                .put("content_index", 0)
                                .put("text", finalTxt)
                        )
                        val donePart = JSONObject().put("type", "output_text").put("text", finalTxt).put("annotations", JSONArray())
                        emit(
                            "response.content_part.done",
                            JSONObject()
                                .put("type", "response.content_part.done")
                                .put("item_id", textItemId)
                                .put("output_index", textOutputIndex)
                                .put("content_index", 0)
                                .put("part", donePart)
                        )
                        emit(
                            "response.output_item.done",
                            JSONObject()
                                .put("type", "response.output_item.done")
                                .put("output_index", textOutputIndex)
                                .put(
                                    "item",
                                    JSONObject()
                                        .put("id", textItemId)
                                        .put("type", "message")
                                        .put("status", "completed")
                                        .put("role", "assistant")
                                        .put("content", JSONArray().put(donePart))
                                )
                        )
                    }
                    event.result.toolCalls.forEach { tc ->
                        val pair = openFunctionItems[tc.index] ?: run {
                            val idx = nextOutputIndex++
                            val id = "fc_${tc.id.ifBlank { UUID.randomUUID().toString() }.take(12)}"
                            emit(
                                "response.output_item.added",
                                JSONObject()
                                    .put("type", "response.output_item.added")
                                    .put("output_index", idx)
                                    .put(
                                        "item",
                                        JSONObject()
                                            .put("id", id)
                                            .put("type", "function_call")
                                            .put("status", "in_progress")
                                            .put("call_id", tc.id)
                                            .put("name", tc.name)
                                            .put("arguments", "")
                                    )
                            )
                            idx to id
                        }
                        emit(
                            "response.function_call_arguments.done",
                            JSONObject()
                                .put("type", "response.function_call_arguments.done")
                                .put("item_id", pair.second)
                                .put("output_index", pair.first)
                                .put("arguments", tc.argumentsJson)
                        )
                        emit(
                            "response.output_item.done",
                            JSONObject()
                                .put("type", "response.output_item.done")
                                .put("output_index", pair.first)
                                .put(
                                    "item",
                                    JSONObject()
                                        .put("id", pair.second)
                                        .put("type", "function_call")
                                        .put("status", "completed")
                                        .put("call_id", tc.id)
                                        .put("name", tc.name)
                                        .put("arguments", tc.argumentsJson)
                                )
                        )
                    }
                    val fullResp = formatOpenAiResponsesResponse(responseId, model, event.result)
                    emit("response.completed", JSONObject().put("type", "response.completed").put("response", fullResp))
                }
                is StreamEvent.Failed -> {
                    ensureCreated()
                    val failedResp = JSONObject()
                        .put("id", responseId)
                        .put("object", "response")
                        .put("status", "failed")
                        .put(
                            "error",
                            JSONObject()
                                .put("code", event.error.errorCode ?: "gateway_error")
                                .put("message", event.error.safeErrorMessage ?: "Upstream error")
                        )
                    emit("response.failed", JSONObject().put("type", "response.failed").put("response", failedResp))
                }
            }
        }

        private fun emit(eventName: String, json: JSONObject) {
            writer.write("event: $eventName\ndata: $json\n\n")
            writer.flush()
        }
    }

    // -------------------------------------------------------------------------
    // Shared Schema Validation Helpers
    // -------------------------------------------------------------------------

    private fun parseOpenAiTools(arr: JSONArray?): ContractParseResult<List<NormalizedToolDefinition>> {
        if (arr == null) return ContractParseResult.Valid(emptyList())
        val tools = mutableListOf<NormalizedToolDefinition>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i)
                ?: return ContractParseResult.Invalid(
                    HttpStatusCode.BadRequest, "invalid_request_error", "invalid_tool", "Tool entry at index $i must be an object"
                )
            val type = obj.optString("type").lowercase()
            if (type != "function") {
                return ContractParseResult.Invalid(
                    HttpStatusCode.BadRequest, "invalid_request_error", "unsupported_tool_type", "Only 'function' tools are supported (got '$type')"
                )
            }
            val fn = obj.optJSONObject("function")
                ?: return ContractParseResult.Invalid(
                    HttpStatusCode.BadRequest, "invalid_request_error", "invalid_tool", "Function tool at index $i requires 'function' object"
                )
            val name = fn.optString("name").trim()
            if (name.isBlank()) {
                return ContractParseResult.Invalid(
                    HttpStatusCode.BadRequest, "invalid_request_error", "invalid_tool_name", "Function tool at index $i requires non-empty 'name'"
                )
            }
            val paramsObj = if (fn.has("parameters") && !fn.isNull("parameters")) {
                fn.optJSONObject("parameters")
                    ?: return ContractParseResult.Invalid(
                        HttpStatusCode.BadRequest, "invalid_request_error", "invalid_tool_schema", "Tool '$name' 'parameters' must be a JSON Schema object"
                    )
            } else {
                JSONObject().put("type", "object").put("properties", JSONObject())
            }
            tools += NormalizedToolDefinition(
                name = name,
                description = fn.optString("description"),
                parametersSchema = paramsObj,
                strict = if (fn.has("strict") && !fn.isNull("strict")) fn.optBoolean("strict") else null
            )
        }
        return ContractParseResult.Valid(tools)
    }

    private fun parseResponsesTools(arr: JSONArray?): ContractParseResult<List<NormalizedToolDefinition>> {
        if (arr == null) return ContractParseResult.Valid(emptyList())
        val tools = mutableListOf<NormalizedToolDefinition>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i)
                ?: return ContractParseResult.Invalid(
                    HttpStatusCode.BadRequest, "invalid_request_error", "invalid_tool", "Tool entry at index $i must be an object"
                )
            val type = obj.optString("type").lowercase()
            if (type != "function") {
                return ContractParseResult.Invalid(
                    HttpStatusCode.NotImplemented,
                    "not_implemented_error",
                    "built_in_tool_unsupported",
                    "Built-in server tool '$type' is not supported; only function tools are supported."
                )
            }
            val fnObj = obj.optJSONObject("function") ?: obj
            val name = fnObj.optString("name").trim()
            if (name.isBlank()) {
                return ContractParseResult.Invalid(
                    HttpStatusCode.BadRequest, "invalid_request_error", "invalid_tool_name", "Function tool at index $i requires 'name'"
                )
            }
            val paramsObj = if (fnObj.has("parameters") && !fnObj.isNull("parameters")) {
                fnObj.optJSONObject("parameters")
                    ?: return ContractParseResult.Invalid(
                        HttpStatusCode.BadRequest, "invalid_request_error", "invalid_tool_schema", "Tool '$name' 'parameters' must be a JSON Schema object"
                    )
            } else {
                JSONObject().put("type", "object").put("properties", JSONObject())
            }
            tools += NormalizedToolDefinition(
                name = name,
                description = fnObj.optString("description"),
                parametersSchema = paramsObj,
                strict = if (fnObj.has("strict") && !fnObj.isNull("strict")) fnObj.optBoolean("strict") else null
            )
        }
        return ContractParseResult.Valid(tools)
    }

    private fun parseOpenAiToolChoice(
        raw: Any?,
        tools: List<NormalizedToolDefinition>
    ): ContractParseResult<NormalizedToolChoice?> {
        if (raw == null || raw == JSONObject.NULL) return ContractParseResult.Valid(null)
        return when (raw) {
            is String -> when (raw.lowercase()) {
                "auto" -> ContractParseResult.Valid(NormalizedToolChoice.Auto)
                "none" -> ContractParseResult.Valid(NormalizedToolChoice.None)
                "required" -> {
                    if (tools.isEmpty()) {
                        ContractParseResult.Invalid(
                            HttpStatusCode.BadRequest, "invalid_request_error", "tool_choice_without_tools", "tool_choice='required' requires non-empty 'tools'"
                        )
                    } else ContractParseResult.Valid(NormalizedToolChoice.Required)
                }
                else -> ContractParseResult.Invalid(
                    HttpStatusCode.BadRequest, "invalid_request_error", "invalid_tool_choice", "Unsupported tool_choice value '$raw'"
                )
            }
            is JSONObject -> {
                val type = raw.optString("type").lowercase()
                if (type != "function") {
                    return ContractParseResult.Invalid(
                        HttpStatusCode.BadRequest, "invalid_request_error", "invalid_tool_choice", "Unsupported tool_choice type '$type'"
                    )
                }
                val fnName = raw.optJSONObject("function")?.optString("name")?.trim().orEmpty()
                    .ifBlank { raw.optString("name").trim() }
                if (fnName.isBlank()) {
                    ContractParseResult.Invalid(
                        HttpStatusCode.BadRequest, "invalid_request_error", "invalid_tool_choice", "tool_choice requires function name"
                    )
                } else {
                    ContractParseResult.Valid(NormalizedToolChoice.SpecificFunction(fnName))
                }
            }
            else -> ContractParseResult.Invalid(
                HttpStatusCode.BadRequest, "invalid_request_error", "invalid_tool_choice", "Invalid tool_choice format"
            )
        }
    }

    private fun parseOpenAiResponseFormat(obj: JSONObject?): NormalizedResponseFormat? {
        if (obj == null) return null
        val type = obj.optString("type").lowercase().ifBlank { return null }
        val jsObj = obj.optJSONObject("json_schema")
        return NormalizedResponseFormat(
            type = type,
            schemaName = jsObj?.optString("name")?.takeIf { it.isNotBlank() } ?: obj.optString("name").takeIf { it.isNotBlank() },
            schemaJson = jsObj?.optJSONObject("schema") ?: obj.optJSONObject("schema"),
            strict = if (jsObj?.has("strict") == true) jsObj.optBoolean("strict") else null
        )
    }

    private fun parseStopParam(raw: Any?): List<String> = when (raw) {
        is String -> if (raw.isNotEmpty()) listOf(raw) else emptyList()
        is JSONArray -> (0 until raw.length()).mapNotNull { raw.optString(it).takeIf(String::isNotEmpty) }
        else -> emptyList()
    }
}
