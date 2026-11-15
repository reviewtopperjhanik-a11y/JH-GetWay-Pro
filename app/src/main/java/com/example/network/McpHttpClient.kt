package com.example.network

import com.example.core.security.DnsResolver
import com.example.core.security.MaskingUtils
import com.example.core.security.NetworkAddressClassifier
import com.example.core.security.SystemDnsResolver
import com.example.data.local.McpServerEntity
import com.example.data.local.McpToolEntity
import com.example.data.local.McpTransportType
import com.example.data.local.ToolPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class McpDiscoveryResult(
    val success: Boolean,
    val tools: List<McpToolEntity> = emptyList(),
    val resourcesJson: String = "[]",
    val promptsJson: String = "[]",
    val errorMessage: String? = null
)

data class McpExecutionResult(
    val success: Boolean,
    val output: String,
    val errorMessage: String? = null
)

class McpHttpClient(
    private val dnsResolver: DnsResolver = SystemDnsResolver
) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val sessionIds = ConcurrentHashMap<String, String>()

    companion object {
        const val MAX_MCP_RESPONSE_BYTES = 262_144 // 256 KB strict cap

        private val dangerousToolKeywords = Regex(
            "(exec|shell|bash|cmd|command|delete|remove|rmdir|drop|truncate|write_file|overwrite|sudo|eval|chmod|chown|kill|shutdown|reboot|sql_write)",
            RegexOption.IGNORE_CASE
        )

        fun isDangerousTool(toolName: String, description: String = ""): Boolean {
            return dangerousToolKeywords.containsMatchIn(toolName) ||
                dangerousToolKeywords.containsMatchIn(description)
        }
    }

    private fun client(timeoutSec: Int, allowLocalHttp: Boolean) = OkHttpClient.Builder()
        .connectTimeout(timeoutSec.toLong().coerceIn(5, 60), TimeUnit.SECONDS)
        .readTimeout(timeoutSec.toLong().coerceIn(10, 120), TimeUnit.SECONDS)
        .dns(NetworkAddressClassifier.pinnedSafeDns(allowLocalHttp, dnsResolver))
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun connectAndDiscover(
        server: McpServerEntity,
        authToken: String,
        envVarsJson: String,
        allowLocalHttp: Boolean
    ): McpDiscoveryResult = withContext(Dispatchers.IO) {
        when (server.transportType) {
            McpTransportType.STREAMABLE_HTTP -> discoverViaHttp(server, authToken, allowLocalHttp)
            McpTransportType.STDIO -> discoverViaStdioBridgeOrReject(server, authToken, allowLocalHttp)
        }
    }

    suspend fun executeTool(
        server: McpServerEntity,
        toolName: String,
        argumentsJson: String,
        authToken: String,
        envVarsJson: String,
        allowLocalHttp: Boolean
    ): McpExecutionResult = withContext(Dispatchers.IO) {
        val argsObj = runCatching {
            if (argumentsJson.isBlank()) JSONObject() else JSONObject(argumentsJson)
        }.getOrElse {
            return@withContext McpExecutionResult(false, "", "Invalid JSON arguments: ${it.message}")
        }

        val requestId = UUID.randomUUID().toString()
        val rpc = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", requestId)
            put("method", "tools/call")
            put("params", JSONObject().apply {
                put("name", toolName)
                put("arguments", argsObj)
            })
        }

        if (server.transportType == McpTransportType.STDIO && !isHttpBridgeUrl(server.endpointOrCommand)) {
            return@withContext McpExecutionResult(
                false,
                "",
                "Android sandbox forbids spawning desktop STDIO binaries. Configure a local/remote MCP-to-HTTP bridge URL or switch transport to Streamable HTTP."
            )
        }

        val sec = NetworkAddressClassifier.validateEndpointSecurity(
            urlString = server.endpointOrCommand,
            allowLocalHttp = allowLocalHttp,
            resolver = dnsResolver
        )
        if (!sec.allowed) {
            return@withContext McpExecutionResult(
                false,
                "",
                sec.errorMessage ?: "HTTPS required or Local Network Access disabled."
            )
        }

        val respResult = postJsonRpc(server, rpc, authToken, allowLocalHttp, expectedId = requestId)
        respResult.fold(
            onSuccess = { json ->
                val err = json.optJSONObject("error")
                if (err != null) {
                    McpExecutionResult(false, "", err.optString("message", "Tool execution error"))
                } else {
                    val resultObj = json.optJSONObject("result")
                    val contentArr = resultObj?.optJSONArray("content")
                    val text = buildString {
                        if (contentArr != null) {
                            for (i in 0 until contentArr.length()) {
                                val item = contentArr.optJSONObject(i)
                                append(item?.optString("text") ?: item?.toString().orEmpty())
                                if (i < contentArr.length() - 1) append("\n")
                            }
                        } else {
                            append(resultObj?.toString(2) ?: json.toString(2))
                        }
                    }.take(8192)
                    val toolErrored = resultObj?.optBoolean("isError", false) == true
                    if (toolErrored) {
                        McpExecutionResult(false, text, text.ifBlank { "MCP tool reported an error (isError=true)" }.take(300))
                    } else {
                        McpExecutionResult(true, text)
                    }
                }
            },
            onFailure = { McpExecutionResult(false, "", MaskingUtils.sanitizeErrorMessage(it.message, listOf(authToken))) }
        )
    }

    private fun discoverViaStdioBridgeOrReject(
        server: McpServerEntity,
        authToken: String,
        allowLocalHttp: Boolean
    ): McpDiscoveryResult {
        if (isHttpBridgeUrl(server.endpointOrCommand)) {
            return discoverViaHttp(server, authToken, allowLocalHttp)
        }
        return McpDiscoveryResult(
            success = false,
            errorMessage = "Direct STDIO binary execution is disabled in Android sandbox. Point endpoint to a local MCP HTTP bridge (e.g., http://127.0.0.1:8931/mcp) or use Streamable HTTP."
        )
    }

    private fun isHttpBridgeUrl(endpoint: String): Boolean {
        val trimmed = endpoint.trim().lowercase()
        return trimmed.startsWith("http://") || trimmed.startsWith("https://")
    }

    private fun discoverViaHttp(
        server: McpServerEntity,
        authToken: String,
        allowLocalHttp: Boolean
    ): McpDiscoveryResult {
        val sec = NetworkAddressClassifier.validateEndpointSecurity(
            urlString = server.endpointOrCommand,
            allowLocalHttp = allowLocalHttp,
            resolver = dnsResolver
        )
        if (!sec.allowed) {
            return McpDiscoveryResult(
                false,
                errorMessage = sec.errorMessage
                    ?: "HTTPS required for remote MCP server, or enable Local Network Access for local server."
            )
        }

        val initId = "init-1"
        val initReq = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", initId)
            put("method", "initialize")
            put("params", JSONObject().apply {
                put("protocolVersion", "2024-11-05")
                put("capabilities", JSONObject())
                put("clientInfo", JSONObject().put("name", "JH GetWay").put("version", "1.0.0"))
            })
        }
        val initResp = postJsonRpc(server, initReq, authToken, allowLocalHttp, expectedId = initId)
        if (initResp.isFailure) {
            return McpDiscoveryResult(
                false,
                errorMessage = MaskingUtils.sanitizeErrorMessage(initResp.exceptionOrNull()?.message, listOf(authToken))
            )
        }
        val initJson = initResp.getOrNull()
        val initErr = initJson?.optJSONObject("error")
        if (initErr != null) {
            return McpDiscoveryResult(false, errorMessage = initErr.optString("message", "MCP initialize rejected"))
        }

        val toolsId = "tools-1"
        val toolsReq = JSONObject().put("jsonrpc", "2.0").put("id", toolsId).put("method", "tools/list")
        val toolsResp = postJsonRpc(server, toolsReq, authToken, allowLocalHttp, expectedId = toolsId)
        if (toolsResp.isFailure) {
            return McpDiscoveryResult(
                false,
                errorMessage = MaskingUtils.sanitizeErrorMessage(toolsResp.exceptionOrNull()?.message, listOf(authToken))
            )
        }
        val tools = mutableListOf<McpToolEntity>()
        toolsResp.getOrNull()?.optJSONObject("result")?.optJSONArray("tools")?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name")
                if (name.isBlank()) continue
                val desc = obj.optString("description")
                tools += McpToolEntity(
                    id = "${server.id}::$name",
                    serverId = server.id,
                    toolName = name,
                    description = desc,
                    inputSchemaJson = obj.optJSONObject("inputSchema")?.toString() ?: "{}",
                    // Strict default: all discovered tools (especially dangerous ones) require explicit approval before running.
                    permission = ToolPermission.ASK_BEFORE_RUNNING
                )
            }
        }

        val resId = "res-1"
        val resReq = JSONObject().put("jsonrpc", "2.0").put("id", resId).put("method", "resources/list")
        val resourcesArr = postJsonRpc(server, resReq, authToken, allowLocalHttp, expectedId = resId).getOrNull()
            ?.optJSONObject("result")?.optJSONArray("resources") ?: JSONArray()

        val prmId = "prm-1"
        val promptsReq = JSONObject().put("jsonrpc", "2.0").put("id", prmId).put("method", "prompts/list")
        val promptsArr = postJsonRpc(server, promptsReq, authToken, allowLocalHttp, expectedId = prmId).getOrNull()
            ?.optJSONObject("result")?.optJSONArray("prompts") ?: JSONArray()

        return McpDiscoveryResult(
            success = true,
            tools = tools,
            resourcesJson = resourcesArr.toString(),
            promptsJson = promptsArr.toString()
        )
    }

    private fun postJsonRpc(
        server: McpServerEntity,
        payload: JSONObject,
        authToken: String,
        allowLocalHttp: Boolean,
        expectedId: String
    ): Result<JSONObject> {
        return try {
            val builder = Request.Builder()
                .url(server.endpointOrCommand.trim())
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(jsonMedia))
            if (authToken.isNotBlank()) builder.header("Authorization", "Bearer $authToken")
            sessionIds[server.id]?.let { builder.header("Mcp-Session-Id", it) }

            client(server.timeoutSec, allowLocalHttp).newCall(builder.build()).execute().use { resp ->
                resp.header("Mcp-Session-Id")?.let { sessionIds[server.id] = it }
                val raw = readBoundedBody(resp)
                if (!resp.isSuccessful) {
                    return Result.failure(IllegalStateException("HTTP ${resp.code}: ${MaskingUtils.sanitizeErrorMessage(raw, listOf(authToken))}"))
                }
                val parsed = parseSseOrJson(raw)
                val rpcVersion = parsed.optString("jsonrpc")
                if (rpcVersion != "2.0") {
                    return Result.failure(IllegalStateException("Invalid JSON-RPC response version: '$rpcVersion'"))
                }
                if (parsed.has("id") && !parsed.isNull("id")) {
                    val actualId = parsed.opt("id")?.toString()
                    if (actualId != expectedId) {
                        return Result.failure(IllegalStateException("Mismatched JSON-RPC response id '$actualId' (expected '$expectedId')"))
                    }
                }
                Result.success(parsed)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun readBoundedBody(resp: okhttp3.Response): String {
        val body = resp.body ?: return ""
        val source = body.source()
        source.request((MAX_MCP_RESPONSE_BYTES + 1).toLong())
        val buffer = source.buffer
        if (buffer.size > MAX_MCP_RESPONSE_BYTES) {
            throw IllegalStateException("MCP response exceeded $MAX_MCP_RESPONSE_BYTES byte safety limit")
        }
        return buffer.clone().readString(buffer.size, Charsets.UTF_8)
    }

    private fun parseSseOrJson(raw: String): JSONObject {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) return JSONObject(trimmed)
        val dataLines = trimmed.lines().filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }
        val lastJson = dataLines.lastOrNull { it.startsWith("{") } ?: "{}"
        return JSONObject(lastJson)
    }
}
