package com.example.core.security

import com.example.network.ChatMessagePayload
import com.example.network.adapters.ContentPart
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class DeviceScope {
    CHAT,
    MODELS_READ,
    WORKFLOWS_READ,
    WORKFLOWS_RUN,
    MCP_READ,
    MCP_EXECUTE,
    ANALYTICS_READ,
    WEB_SEARCH,
    GITHUB_SEARCH,
    ADMIN
}

enum class UsageSource {
    ACTUAL,
    ESTIMATED,
    UNKNOWN
}

data class ResolvedTokenUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val source: UsageSource
) {
    val totalTokens: Int get() = (inputTokens + outputTokens).coerceAtLeast(0)
}

object DeviceScopePolicy {
    const val DEFAULT_SCOPES_CSV = "CHAT,MODELS_READ"

    fun parseScopes(csv: String): Set<DeviceScope> {
        if (csv.isBlank()) return emptySet() // Default deny
        val result = linkedSetOf<DeviceScope>()
        csv.split(",", ";", "\n").forEach { raw ->
            val token = raw.trim().uppercase(Locale.US)
            if (token.isNotEmpty()) {
                runCatching { DeviceScope.valueOf(token) }.getOrNull()?.let { result.add(it) }
            }
        }
        return result
    }

    fun formatScopesCsv(scopes: Collection<DeviceScope>): String {
        return scopes.distinct().sortedBy { it.ordinal }.joinToString(",") { it.name }
    }

    fun hasScope(grantedScopes: Set<DeviceScope>, requiredScope: DeviceScope): Boolean {
        if (grantedScopes.isEmpty()) return false // Default deny
        return DeviceScope.ADMIN in grantedScopes || requiredScope in grantedScopes
    }

    fun requiredScopeForGatewayPath(path: String, method: String = "GET"): DeviceScope? {
        val clean = path.trim().lowercase(Locale.US).substringBefore('?')
        val upperMethod = method.trim().uppercase(Locale.US)
        return when {
            clean == "/health" -> null
            clean == "/v1/models" -> DeviceScope.MODELS_READ
            clean == "/v1/chat/completions" ||
                clean == "/v1/responses" ||
                clean == "/v1/messages" -> DeviceScope.CHAT
            clean.startsWith("/v1/workflows") -> {
                val isRunPath = clean.endsWith("/run") ||
                    clean.endsWith("/execute") ||
                    (upperMethod == "POST" && !clean.endsWith("/list"))
                if (isRunPath) DeviceScope.WORKFLOWS_RUN else DeviceScope.WORKFLOWS_READ
            }
            clean.startsWith("/v1/mcp") -> {
                val isExecPath = clean.endsWith("/execute") ||
                    clean.endsWith("/call") ||
                    (upperMethod == "POST" && !clean.contains("/discover") && !clean.endsWith("/list"))
                if (isExecPath) DeviceScope.MCP_EXECUTE else DeviceScope.MCP_READ
            }
            clean == "/v1/search/web" ||
                clean == "/v1/search/web/health" -> DeviceScope.WEB_SEARCH
            clean == "/v1/search/github" ||
                clean == "/v1/search/github/health" -> DeviceScope.GITHUB_SEARCH
            clean.startsWith("/v1/analytics") -> DeviceScope.ANALYTICS_READ
            clean.startsWith("/v1/") -> DeviceScope.CHAT
            else -> DeviceScope.ADMIN
        }
    }

    fun parseAllowedModels(allowedModelsCsv: String): List<String> {
        if (allowedModelsCsv.isBlank()) return emptyList()
        return allowedModelsCsv.split(",", "\n", ";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    fun isModelAllowed(
        allowedModelsCsv: String,
        requestedModelOrAlias: String,
        resolvedModelId: String? = null
    ): Boolean {
        val allowlist = parseAllowedModels(allowedModelsCsv)
        if (allowlist.isEmpty()) return true // No model allowlist restriction configured
        if (allowlist.any { it == "*" }) return true
        val candidates = listOfNotNull(
            requestedModelOrAlias.trim().takeIf { it.isNotEmpty() },
            resolvedModelId?.trim()?.takeIf { it.isNotEmpty() }
        )
        if (candidates.isEmpty()) return false
        return candidates.any { cand -> allowlist.any { allowed -> allowed.equals(cand, ignoreCase = true) } }
    }
}

object TokenUsageAccounting {
    fun utcDateString(epochMs: Long = System.currentTimeMillis()): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return fmt.format(Date(epochMs))
    }

    fun resolveUsage(
        reportedInputTokens: Int,
        reportedOutputTokens: Int,
        usageReported: Boolean,
        promptMessages: List<ChatMessagePayload>,
        completionText: String,
        requestSucceeded: Boolean
    ): ResolvedTokenUsage {
        val safeIn = reportedInputTokens.coerceAtLeast(0)
        val safeOut = reportedOutputTokens.coerceAtLeast(0)
        if (usageReported) {
            return ResolvedTokenUsage(
                inputTokens = safeIn,
                outputTokens = safeOut,
                source = UsageSource.ACTUAL
            )
        }

        if (!requestSucceeded) {
            // Do not count failed/missing usage as zero actual usage
            return ResolvedTokenUsage(inputTokens = 0, outputTokens = 0, source = UsageSource.UNKNOWN)
        }

        val promptChars: Int = promptMessages.sumOf { msg ->
            msg.content.length + msg.contentParts.sumOf { part ->
                when (part) {
                    is ContentPart.Text -> part.text.length
                    is ContentPart.FileAttachment -> part.textContent?.length ?: 0
                    is ContentPart.ToolUse -> part.name.length + part.argumentsJson.length
                    is ContentPart.ToolResult -> part.content.length
                    is ContentPart.ImageUrl -> 0
                }
            }
        }
        val outputChars = completionText.length
        if (promptChars == 0 && outputChars == 0) {
            return ResolvedTokenUsage(inputTokens = 0, outputTokens = 0, source = UsageSource.UNKNOWN)
        }

        val estIn = if (promptChars > 0) (promptChars / 4).coerceAtLeast(1) else 0
        val estOut = if (outputChars > 0) (outputChars / 4).coerceAtLeast(1) else 0
        return ResolvedTokenUsage(
            inputTokens = estIn,
            outputTokens = estOut,
            source = UsageSource.ESTIMATED
        )
    }
}
