package com.example.gateway

import com.example.core.security.MaskingUtils
import com.example.core.security.NetworkAddressClassifier
import com.example.data.local.CodingAgentEntity
import com.example.data.local.CodingAgentType
import com.example.data.local.GatewayMode
import com.example.data.local.GatewaySettingsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

data class GatewayEndpointResolution(
    val mode: GatewayMode,
    val gatewayRootUrl: String,
    val baseV1Url: String,
    val isUrlValid: Boolean,
    val isGatewayActive: Boolean,
    val errorMessage: String? = null
) {
    val isReadyToConnect: Boolean
        get() = isUrlValid && isGatewayActive
}

data class CodingAgentTestStatus(
    val isTesting: Boolean = false,
    val success: Boolean? = null,
    val httpCode: Int? = null,
    val endpointTested: String = "",
    val message: String = "",
    val testedAtMs: Long? = null
)

object CodingAgentConfigBuilder {

    fun normalizeGatewayUrls(rawUrl: String): Pair<String, String> {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return "" to ""
        val uri = URI(trimmed)
        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.rawAuthority?.takeIf { it.isNotBlank() } ?: return "" to ""
        val rawPath = uri.rawPath.orEmpty().replace(Regex("/+"), "/").trimEnd('/')
        val rootPath = if (rawPath.equals("/v1", ignoreCase = true)) {
            ""
        } else if (rawPath.endsWith("/v1", ignoreCase = true)) {
            rawPath.dropLast(3).trimEnd('/')
        } else {
            rawPath
        }
        val rootUrl = "$scheme://$host$rootPath"
        val baseV1Url = "$rootUrl/v1"
        return rootUrl to baseV1Url
    }

    fun resolveGatewayEndpoint(
        settings: GatewaySettingsState,
        runtime: GatewayRuntimeState
    ): GatewayEndpointResolution {
        return when (settings.gatewayMode) {
            GatewayMode.LOCAL -> {
                val bindValidation = NetworkAddressClassifier.validateLocalGatewayBindConfig(
                    hostInput = settings.gatewayHost,
                    port = settings.gatewayPort,
                    allowLanExposure = settings.gatewayAllowLanExposure
                )
                if (!bindValidation.allowed) {
                    return GatewayEndpointResolution(
                        mode = GatewayMode.LOCAL,
                        gatewayRootUrl = "",
                        baseV1Url = "",
                        isUrlValid = false,
                        isGatewayActive = false,
                        errorMessage = bindValidation.errorMessage ?: "Invalid Local Gateway bind configuration."
                    )
                }
                val activeLocal = runtime.mode == GatewayMode.LOCAL && runtime.isActive
                val candidateUrl = if (activeLocal) {
                    runtime.boundAddress
                } else {
                    "http://${bindValidation.normalizedHost}:${bindValidation.normalizedPort}"
                }
                val (rootUrl, baseV1) = runCatching { normalizeGatewayUrls(candidateUrl) }.getOrElse {
                    return GatewayEndpointResolution(
                        mode = GatewayMode.LOCAL,
                        gatewayRootUrl = "",
                        baseV1Url = "",
                        isUrlValid = false,
                        isGatewayActive = false,
                        errorMessage = "Malformed Local Gateway URL."
                    )
                }
                val inactiveMsg = if (!activeLocal) {
                    runtime.lastError ?: "Local Gateway is stopped (${runtime.status.name}). Start the Gateway before connecting coding agents."
                } else null
                GatewayEndpointResolution(
                    mode = GatewayMode.LOCAL,
                    gatewayRootUrl = rootUrl,
                    baseV1Url = baseV1,
                    isUrlValid = baseV1.isNotBlank(),
                    isGatewayActive = activeLocal,
                    errorMessage = inactiveMsg
                )
            }

            GatewayMode.REMOTE, GatewayMode.EXISTING -> {
                val rawRemote = if (runtime.mode == settings.gatewayMode && runtime.isActive && runtime.boundAddress.isNotBlank()) {
                    runtime.boundAddress
                } else {
                    settings.gatewayRemoteUrl
                }
                if (rawRemote.isBlank()) {
                    return GatewayEndpointResolution(
                        mode = settings.gatewayMode,
                        gatewayRootUrl = "",
                        baseV1Url = "",
                        isUrlValid = false,
                        isGatewayActive = false,
                        errorMessage = "Remote Gateway URL is not configured. Set it in Gateway settings."
                    )
                }
                val allowExistingLoopbackHttp =
                    settings.gatewayMode == GatewayMode.EXISTING && settings.localNetworkAccessAllowed
                val validation = NetworkAddressClassifier.validateRemoteGatewayUrl(
                    urlString = rawRemote,
                    allowExistingPrivateHttp = allowExistingLoopbackHttp
                )
                if (!validation.valid) {
                    return GatewayEndpointResolution(
                        mode = settings.gatewayMode,
                        gatewayRootUrl = "",
                        baseV1Url = "",
                        isUrlValid = false,
                        isGatewayActive = false,
                        errorMessage = validation.errorMessage ?: "Invalid Remote Gateway URL."
                    )
                }
                val (rootUrl, baseV1) = runCatching { normalizeGatewayUrls(validation.normalizedUrl) }.getOrElse {
                    return GatewayEndpointResolution(
                        mode = settings.gatewayMode,
                        gatewayRootUrl = "",
                        baseV1Url = "",
                        isUrlValid = false,
                        isGatewayActive = false,
                        errorMessage = "Malformed Remote Gateway URL."
                    )
                }
                val activeRemote = runtime.mode == settings.gatewayMode && runtime.isActive
                val inactiveMsg = if (!activeRemote) {
                    runtime.lastError ?: "Remote Gateway health has not connected (${runtime.status.name}). Verify Remote Health in Gateway settings."
                } else null
                GatewayEndpointResolution(
                    mode = settings.gatewayMode,
                    gatewayRootUrl = rootUrl,
                    baseV1Url = baseV1,
                    isUrlValid = baseV1.isNotBlank(),
                    isGatewayActive = activeRemote,
                    errorMessage = inactiveMsg
                )
            }
        }
    }

    fun resolveAgentModelId(agent: CodingAgentEntity, settings: GatewaySettingsState): String {
        val strippedAlias = OpenCodeSelectorIds.stripGatewayPrefix(agent.modelAlias)
        if (agent.agentType == CodingAgentType.OPENCODE &&
            (strippedAlias.isBlank() || OpenCodeSelectorIds.isVirtualSelector(strippedAlias))
        ) {
            return OpenCodeSelectorIds.STABLE_AUTO_ID
        }
        val raw = strippedAlias
            .ifBlank { OpenCodeSelectorIds.stripGatewayPrefix(agent.defaultModelId) }
            .ifBlank { OpenCodeSelectorIds.stripGatewayPrefix(settings.openCodeSelectionMode) }
            .ifBlank { settings.defaultModelId.trim() }
            .ifBlank { OpenCodeSelectorIds.STABLE_AUTO_ID }
        return if (OpenCodeSelectorIds.normalizeSelectorId(raw) == OpenCodeSelectorIds.AUTO) {
            OpenCodeSelectorIds.STABLE_AUTO_ID
        } else {
            OpenCodeSelectorIds.normalizeSelectorId(raw)
        }
    }

    fun collectAgentModelIds(agent: CodingAgentEntity, settings: GatewaySettingsState): List<String> {
        val primary = resolveAgentModelId(agent, settings)
        val extra = agent.allowedModelsCsv
            .split(",", ";", "\n")
            .map { OpenCodeSelectorIds.normalizeSelectorId(it) }
            .filter { it.isNotEmpty() && it != "*" }
        return (listOf(primary) + extra).distinct()
    }

    fun buildOpenCodeConfigJson(
        agent: CodingAgentEntity,
        baseV1Url: String,
        apiKeyValue: String,
        settings: GatewaySettingsState,
        curatedCatalog: CuratedModelCatalog? = null
    ): String {
        val primaryModel = resolveAgentModelId(agent, settings)
        val modelsObj = JSONObject()

        fun putModelMeta(
            modelId: String,
            displayName: String = modelId,
            description: String = "",
            toolCall: Boolean = agent.toolCalling,
            attachment: Boolean = false,
            contextLimit: Int? = null
        ) {
            val clean = modelId.trim()
            if (clean.isBlank() || modelsObj.has(clean)) return
            val obj = JSONObject()
                .put("name", displayName.ifBlank { clean })
                .put("tool_call", toolCall)
            if (description.isNotBlank()) {
                obj.put("description", description)
            }
            if (attachment) {
                obj.put("attachment", true)
            }
            if (contextLimit != null && contextLimit > 0) {
                obj.put("limit", JSONObject().put("context", contextLimit))
            }
            modelsObj.put(clean, obj)
        }

        val allowlistRestricted = agent.allowedModelsCsv.isNotBlank() && agent.allowedModelsCsv.trim() != "*"
        val isSingleAutoMode = !allowlistRestricted && primaryModel == OpenCodeSelectorIds.STABLE_AUTO_ID

        if (isSingleAutoMode) {
            val autoEntry = curatedCatalog?.entries?.firstOrNull {
                it.id == OpenCodeSelectorIds.STABLE_AUTO_ID
            }
            putModelMeta(
                modelId = OpenCodeSelectorIds.STABLE_AUTO_ID,
                displayName = autoEntry?.displayName ?: OpenCodeSelectorIds.DISPLAY_NAME_AUTO,
                description = autoEntry?.description?.ifBlank { null } ?: OpenCodeSelectorIds.DISPLAY_DESCRIPTION_AUTO,
                toolCall = agent.toolCalling && (autoEntry?.supportsTools ?: true),
                attachment = autoEntry?.supportsVision ?: false,
                contextLimit = autoEntry?.contextWindow
            )
        } else if (curatedCatalog != null) {
            curatedCatalog.entries.forEach { entry ->
                val resolvedReal = if (entry.category == "selector" || entry.category == "alias") null else entry.resolvedUpstreamModelId ?: entry.id
                val allowed = !allowlistRestricted || com.example.core.security.DeviceScopePolicy.isModelAllowed(
                    allowedModelsCsv = agent.allowedModelsCsv,
                    requestedModelOrAlias = entry.id,
                    resolvedModelId = resolvedReal
                )
                if (allowed) {
                    putModelMeta(
                        modelId = entry.id,
                        displayName = entry.displayName,
                        description = entry.description,
                        toolCall = agent.toolCalling && entry.supportsTools,
                        attachment = entry.supportsVision,
                        contextLimit = entry.contextWindow
                    )
                }
            }
            collectAgentModelIds(agent, settings).forEach { explicitId ->
                putModelMeta(modelId = explicitId)
            }
        } else {
            collectAgentModelIds(agent, settings).forEach { modelId ->
                putModelMeta(modelId = modelId)
            }
        }

        val jhGetWayProvider = JSONObject()
            .put("npm", "@ai-sdk/openai-compatible")
            .put("name", "JH GetWay")
            .put(
                "options",
                JSONObject()
                    .put("baseURL", baseV1Url)
                    .put("apiKey", apiKeyValue)
            )
            .put("models", modelsObj)

        return JSONObject()
            .put("\$schema", "https://opencode.ai/config.json")
            .put("provider", JSONObject().put("jhgetway", jhGetWayProvider))
            .put("model", "jhgetway/$primaryModel")
            .toString(2)
    }

    fun buildCodexConfigToml(
        agent: CodingAgentEntity,
        baseV1Url: String,
        settings: GatewaySettingsState
    ): String {
        val modelId = resolveAgentModelId(agent, settings)
        return buildString {
            appendLine("model = \"${escapeTomlString(modelId)}\"")
            appendLine("model_provider = \"jhgetway\"")
            appendLine()
            appendLine("[model_providers.jhgetway]")
            appendLine("name = \"JH GetWay\"")
            appendLine("base_url = \"${escapeTomlString(baseV1Url)}\"")
            appendLine("env_key = \"OPENAI_API_KEY\"")
            append("wire_api = \"responses\"")
        }
    }

    fun buildCodexEnvCommand(tokenValue: String): String {
        val escaped = tokenValue
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("$", "\\$")
            .replace("`", "\\`")
        return "export OPENAI_API_KEY=\"$escaped\""
    }

    fun buildPreviewSnippet(
        agent: CodingAgentEntity,
        baseV1Url: String,
        settings: GatewaySettingsState,
        curatedCatalog: CuratedModelCatalog? = null
    ): String {
        val resolvedBase = baseV1Url.ifBlank { "<gateway-url-not-configured>/v1" }
        val modelId = resolveAgentModelId(agent, settings)
        return when (agent.agentType) {
            CodingAgentType.OPENCODE -> buildOpenCodeConfigJson(
                agent = agent,
                baseV1Url = resolvedBase,
                apiKeyValue = agent.tokenPreview.ifBlank { "{env:JH_GETWAY_API_KEY}" },
                settings = settings,
                curatedCatalog = curatedCatalog
            )

            CodingAgentType.CODEX -> buildString {
                appendLine("# ~/.codex/config.toml")
                appendLine(buildCodexConfigToml(agent, resolvedBase, settings))
                appendLine()
                append("# Shell env: export OPENAI_API_KEY=\"${agent.tokenPreview}\"")
            }

            CodingAgentType.CLINE -> """
                API Provider: OpenAI Compatible
                Base URL: $resolvedBase
                API Key: ${agent.tokenPreview}
                Model ID: $modelId
            """.trimIndent()

            CodingAgentType.CONTINUE -> """
                models:
                  - title: ${agent.name}
                    provider: openai
                    model: $modelId
                    apiBase: $resolvedBase
                    apiKey: ${agent.tokenPreview}
            """.trimIndent()

            CodingAgentType.AIDER -> """
                export OPENAI_API_BASE="$resolvedBase"
                export OPENAI_API_KEY="${agent.tokenPreview}"
                aider --model openai/$modelId
            """.trimIndent()

            CodingAgentType.OTHER -> "Base URL: $resolvedBase\nAPI Key: ${agent.tokenPreview}\nModel: $modelId"
        }
    }

    fun buildCopyConfigForAgent(
        agent: CodingAgentEntity,
        baseV1Url: String,
        decryptedToken: String,
        settings: GatewaySettingsState,
        curatedCatalog: CuratedModelCatalog? = null
    ): String {
        val modelId = resolveAgentModelId(agent, settings)
        return when (agent.agentType) {
            CodingAgentType.OPENCODE -> buildOpenCodeConfigJson(
                agent = agent,
                baseV1Url = baseV1Url,
                apiKeyValue = decryptedToken,
                settings = settings,
                curatedCatalog = curatedCatalog
            )
            CodingAgentType.CODEX -> buildCodexConfigToml(agent, baseV1Url, settings)
            CodingAgentType.CLINE -> """
                API Provider: OpenAI Compatible
                Base URL: $baseV1Url
                API Key: $decryptedToken
                Model ID: $modelId
            """.trimIndent()
            CodingAgentType.CONTINUE -> """
                models:
                  - title: ${agent.name}
                    provider: openai
                    model: $modelId
                    apiBase: $baseV1Url
                    apiKey: $decryptedToken
            """.trimIndent()
            CodingAgentType.AIDER -> """
                export OPENAI_API_BASE="$baseV1Url"
                export OPENAI_API_KEY="$decryptedToken"
                aider --model openai/$modelId
            """.trimIndent()
            CodingAgentType.OTHER -> "Base URL: $baseV1Url\nAPI Key: $decryptedToken\nModel: $modelId"
        }
    }

    private fun escapeTomlString(value: String): String {
        return buildString(value.length) {
            value.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
        }
    }
}

class CodingAgentGatewayTester {

    suspend fun testConnection(
        agent: CodingAgentEntity,
        decryptedAgentToken: String,
        settings: GatewaySettingsState,
        runtime: GatewayRuntimeState
    ): CodingAgentTestStatus = withContext(Dispatchers.IO) {
        val resolution = CodingAgentConfigBuilder.resolveGatewayEndpoint(settings, runtime)
        if (!resolution.isUrlValid) {
            return@withContext CodingAgentTestStatus(
                isTesting = false,
                success = false,
                message = resolution.errorMessage ?: "Invalid Gateway endpoint URL.",
                testedAtMs = System.currentTimeMillis()
            )
        }
        if (settings.gatewayMode == GatewayMode.LOCAL && !resolution.isGatewayActive) {
            return@withContext CodingAgentTestStatus(
                isTesting = false,
                success = false,
                endpointTested = resolution.baseV1Url,
                message = resolution.errorMessage ?: "Local Gateway is stopped. Start Local Gateway first.",
                testedAtMs = System.currentTimeMillis()
            )
        }

        val allowLocalHttp = settings.gatewayMode == GatewayMode.LOCAL ||
            (settings.gatewayMode == GatewayMode.EXISTING && settings.localNetworkAccessAllowed)
        val sec = NetworkAddressClassifier.validateEndpointSecurity(
            urlString = resolution.baseV1Url,
            allowLocalHttp = allowLocalHttp
        )
        if (!sec.allowed) {
            return@withContext CodingAgentTestStatus(
                isTesting = false,
                success = false,
                endpointTested = resolution.baseV1Url,
                message = sec.errorMessage ?: "Gateway endpoint blocked by security policy.",
                testedAtMs = System.currentTimeMillis()
            )
        }

        val timeoutSec = settings.gatewayTimeoutSec.toLong().coerceIn(5, 60)
        val client = OkHttpClient.Builder()
            .connectTimeout(timeoutSec, TimeUnit.SECONDS)
            .readTimeout(timeoutSec, TimeUnit.SECONDS)
            .dns(NetworkAddressClassifier.pinnedSafeDns(allowLocalHttp = allowLocalHttp))
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        val secrets = listOf(decryptedAgentToken)
        val healthUrl = "${resolution.gatewayRootUrl}/health"
        val modelsUrl = "${resolution.baseV1Url}/models"
        val modelId = CodingAgentConfigBuilder.resolveAgentModelId(agent, settings)
        val isCodex = agent.agentType == CodingAgentType.CODEX
        val inferenceEndpoint = if (isCodex) "${resolution.baseV1Url}/responses" else "${resolution.baseV1Url}/chat/completions"

        try {
            // 1. Check /health (non-fatal if remote /v1 proxy doesn't expose /health, but blocks on transport errors)
            client.newCall(Request.Builder().url(healthUrl).get().build()).execute().use { healthResp ->
                if (healthResp.code >= 500) {
                    return@withContext CodingAgentTestStatus(
                        isTesting = false,
                        success = false,
                        httpCode = healthResp.code,
                        endpointTested = healthUrl,
                        message = "Gateway health endpoint failed (HTTP ${healthResp.code}).",
                        testedAtMs = System.currentTimeMillis()
                    )
                }
            }

            // 2. Check /v1/models with the Coding Agent's own Bearer token
            client.newCall(
                Request.Builder()
                    .url(modelsUrl)
                    .header("Authorization", "Bearer $decryptedAgentToken")
                    .get()
                    .build()
            ).execute().use { modelsResp ->
                val rawBody = modelsResp.body?.string().orEmpty()
                if (modelsResp.code == 401 || modelsResp.code == 403) {
                    val boundaryHint = buildAuthFailureHint(
                        agent = agent,
                        mode = settings.gatewayMode,
                        httpCode = modelsResp.code,
                        rawBody = rawBody,
                        secrets = secrets
                    )
                    return@withContext CodingAgentTestStatus(
                        isTesting = false,
                        success = false,
                        httpCode = modelsResp.code,
                        endpointTested = modelsUrl,
                        message = boundaryHint,
                        testedAtMs = System.currentTimeMillis()
                    )
                }
                if (!modelsResp.isSuccessful && modelsResp.code != 404) {
                    return@withContext CodingAgentTestStatus(
                        isTesting = false,
                        success = false,
                        httpCode = modelsResp.code,
                        endpointTested = modelsUrl,
                        message = extractGatewayErrorMessage(rawBody, modelsResp.code, secrets),
                        testedAtMs = System.currentTimeMillis()
                    )
                }
            }

            // 3. Minimal live inference probe to verify routing, model allowlist, and OpenAI /v1/chat/completions or /v1/responses
            val payload = if (isCodex) {
                JSONObject()
                    .put("model", modelId)
                    .put("input", "Reply with OK.")
                    .put("max_output_tokens", 16)
                    .put("stream", false)
            } else {
                JSONObject()
                    .put("model", modelId)
                    .put(
                        "messages",
                        JSONArray().put(
                            JSONObject()
                                .put("role", "user")
                                .put("content", "Reply with OK.")
                        )
                    )
                    .put("max_tokens", 16)
                    .put("stream", false)
            }

            val startNs = System.nanoTime()
            client.newCall(
                Request.Builder()
                    .url(inferenceEndpoint)
                    .header("Authorization", "Bearer $decryptedAgentToken")
                    .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            ).execute().use { resp ->
                val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                val bodyStr = resp.body?.string().orEmpty()
                val checkedAt = System.currentTimeMillis()
                return@withContext when {
                    resp.isSuccessful -> {
                        val endpointShort = if (isCodex) "/v1/responses" else "/v1/chat/completions"
                        CodingAgentTestStatus(
                            isTesting = false,
                            success = true,
                            httpCode = resp.code,
                            endpointTested = inferenceEndpoint,
                            message = "Verified $endpointShort via Gateway in ${elapsedMs}ms (HTTP ${resp.code}).",
                            testedAtMs = checkedAt
                        )
                    }
                    resp.code == 401 || resp.code == 403 -> CodingAgentTestStatus(
                        isTesting = false,
                        success = false,
                        httpCode = resp.code,
                        endpointTested = inferenceEndpoint,
                        message = buildAuthFailureHint(
                            agent = agent,
                            mode = settings.gatewayMode,
                            httpCode = resp.code,
                            rawBody = bodyStr,
                            secrets = secrets
                        ),
                        testedAtMs = checkedAt
                    )
                    else -> CodingAgentTestStatus(
                        isTesting = false,
                        success = false,
                        httpCode = resp.code,
                        endpointTested = inferenceEndpoint,
                        message = extractGatewayErrorMessage(bodyStr, resp.code, secrets),
                        testedAtMs = checkedAt
                    )
                }
            }
        } catch (e: SSLException) {
            CodingAgentTestStatus(
                isTesting = false,
                success = false,
                endpointTested = inferenceEndpoint,
                message = "TLS verification failed: ${MaskingUtils.sanitizeErrorMessage(e.message, secrets)}",
                testedAtMs = System.currentTimeMillis()
            )
        } catch (_: SocketTimeoutException) {
            CodingAgentTestStatus(
                isTesting = false,
                success = false,
                endpointTested = inferenceEndpoint,
                message = "Gateway request timed out reaching ${resolution.baseV1Url}.",
                testedAtMs = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            CodingAgentTestStatus(
                isTesting = false,
                success = false,
                endpointTested = inferenceEndpoint,
                message = MaskingUtils.sanitizeErrorMessage(e.message, secrets),
                testedAtMs = System.currentTimeMillis()
            )
        }
    }

    private fun buildAuthFailureHint(
        agent: CodingAgentEntity,
        mode: GatewayMode,
        httpCode: Int,
        rawBody: String,
        secrets: List<String>
    ): String {
        val baseMsg = extractGatewayErrorMessage(rawBody, httpCode, secrets)
        if (!agent.enabled && mode == GatewayMode.LOCAL) {
            return "Agent '${agent.name}' is disabled (HTTP $httpCode). Enable this agent to allow Gateway requests."
        }
        return if (mode != GatewayMode.LOCAL && httpCode == 401) {
            "Remote Gateway rejected this agent's local token (HTTP 401). Local agent tokens are stored only in JH GetWay's local database and must be provisioned on the remote gateway."
        } else {
            baseMsg
        }
    }

    private fun extractGatewayErrorMessage(rawBody: String, httpCode: Int, secrets: List<String>): String {
        val jsonMsg = runCatching {
            JSONObject(rawBody).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
        }.getOrNull()
        val msg = jsonMsg ?: "Gateway returned HTTP $httpCode"
        return MaskingUtils.sanitizeErrorMessage(msg, secrets)
    }
}
