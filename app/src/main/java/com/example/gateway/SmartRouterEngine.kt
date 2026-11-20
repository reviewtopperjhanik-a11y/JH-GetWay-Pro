package com.example.gateway

import com.example.core.security.CryptoManager
import com.example.core.security.MaskingUtils
import com.example.core.security.ResolvedTokenUsage
import com.example.core.security.TokenUsageAccounting
import com.example.core.security.UsageSource
import com.example.data.local.*
import com.example.network.*
import com.example.network.adapters.NormalizedResponseFormat
import com.example.network.adapters.NormalizedToolChoice
import com.example.network.adapters.NormalizedToolDefinition
import com.example.network.adapters.ProviderAdapterRegistry
import com.example.network.adapters.ProviderCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

data class RouterDecisionSnapshot(
    val requestId: String,
    val selectedProvider: String,
    val selectedModel: String,
    val routingReason: String,
    val fallbackUsed: Boolean,
    val attemptCount: Int,
    val latencyMs: Long,
    val finalStatus: String,
    val resolvedUsage: ResolvedTokenUsage = ResolvedTokenUsage(0, 0, UsageSource.UNKNOWN),
    val timestamp: Long = System.currentTimeMillis(),
    val requestedModelOrAlias: String = "",
    val usedDefaultModel: Boolean = false,
    val fallbackReason: String? = null
)

data class RouteCandidate(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val apiFormat: ApiFormat,
    val apiKey: String,
    val keyEntityId: String?,
    val customHeadersJson: String,
    val modelId: String,
    val isLocal: Boolean,
    val priority: Int,
    val lastLatencyMs: Long,
    val estimatedCostPer1M: Double?,
    val capabilities: ProviderCapabilities = ProviderCapabilities(),
    val providerStatus: ProviderStatus = ProviderStatus.UNKNOWN,
    val accessTier: AccessTier = if (isLocal) AccessTier.LOCAL else AccessTier.UNKNOWN,
    val freeTierVerified: Boolean = false,
    val isCanonicalDefault: Boolean = false,
    val isCodingCapable: Boolean = false,
    val codingScore: Int = 0
)

data class RoutingRequestContext(
    val messages: List<ChatMessagePayload> = emptyList(),
    val tools: List<NormalizedToolDefinition> = emptyList(),
    val toolChoice: NormalizedToolChoice? = null,
    val jsonOutput: Boolean = false,
    val responseFormat: NormalizedResponseFormat? = null,
    val stream: Boolean = false,
    val requireVision: Boolean = false,
    val requiredDocumentMimeTypes: Set<String> = emptySet()
)

data class RoutedChatResult(
    val execution: ChatExecutionResult,
    val decision: RouterDecisionSnapshot
)

class SmartRouterEngine(
    private val db: JhGetWayDatabase,
    private val httpClient: ProviderHttpClient
) {
    companion object {
        const val COOLDOWN_WINDOW_MS = 60_000L

        private val codingToolKeywords = setOf(
            "read", "write", "edit", "patch", "apply_patch", "bash", "shell", "exec",
            "run_command", "grep", "glob", "find", "lsp", "git", "test", "lint", "compile",
            "read_file", "write_file", "edit_file", "list_dir"
        )
    }

    private val roundRobinCounter = AtomicInteger(0)
    private val keyRoundRobinCounter = AtomicInteger(0)

    private val _recentDecisions = MutableStateFlow<List<RouterDecisionSnapshot>>(emptyList())
    val recentDecisions: StateFlow<List<RouterDecisionSnapshot>> = _recentDecisions.asStateFlow()

    private data class AnalyzedIntent(
        val needsVision: Boolean,
        val requiredDocMimes: Set<String>,
        val needsTools: Boolean,
        val needsJsonOutput: Boolean,
        val needsStreaming: Boolean,
        val isCodingIntent: Boolean
    )

    private fun extractMessageCapabilities(
        messages: List<ChatMessagePayload>,
        requireVision: Boolean,
        requiredDocumentMimeTypes: Set<String>
    ): Pair<Boolean, Set<String>> {
        val needsVision = requireVision || messages.any { m ->
            m.contentParts.any { it is com.example.network.adapters.ContentPart.ImageUrl }
        }
        val docMimes = buildSet {
            addAll(requiredDocumentMimeTypes)
            messages.forEach { m ->
                m.contentParts.forEach { part ->
                    if (part is com.example.network.adapters.ContentPart.FileAttachment) {
                        add(part.mimeType)
                    }
                }
            }
        }
        return needsVision to docMimes
    }

    private fun analyzeIntent(ctx: RoutingRequestContext): AnalyzedIntent {
        val (needsVision, docMimes) = extractMessageCapabilities(
            ctx.messages,
            ctx.requireVision,
            ctx.requiredDocumentMimeTypes
        )
        val needsTools = ctx.tools.isNotEmpty() ||
            (ctx.toolChoice != null && ctx.toolChoice !is NormalizedToolChoice.None)
        val needsJsonOutput = ctx.jsonOutput ||
            (ctx.responseFormat != null && !ctx.responseFormat.type.equals("text", ignoreCase = true))

        val hasCodingTools = ctx.tools.any { t ->
            val lowerName = t.name.lowercase(Locale.US)
            codingToolKeywords.any { kw -> lowerName.contains(kw) }
        }
        val joinedText = ctx.messages.takeLast(6).joinToString("\n") { it.content }.lowercase(Locale.US)
        val hasCodingTextSignals = joinedText.contains("```") ||
            joinedText.contains("stacktrace") ||
            joinedText.contains("refactor") ||
            joinedText.contains("compile") ||
            joinedText.contains("unit test") ||
            joinedText.contains("function ") ||
            joinedText.contains("class ") ||
            joinedText.contains(".kt") ||
            joinedText.contains(".ts") ||
            joinedText.contains(".py") ||
            joinedText.contains(".go") ||
            joinedText.contains(".rs") ||
            joinedText.contains("debug ") ||
            joinedText.contains("fix the bug") ||
            joinedText.contains("opencode")

        return AnalyzedIntent(
            needsVision = needsVision,
            requiredDocMimes = docMimes,
            needsTools = needsTools,
            needsJsonOutput = needsJsonOutput,
            needsStreaming = ctx.stream,
            isCodingIntent = hasCodingTools || hasCodingTextSignals
        )
    }

    private fun candidateSupportsAttachments(
        capabilities: ProviderCapabilities,
        needsVision: Boolean,
        requiredDocMimes: Set<String>
    ): Boolean {
        if (needsVision && !capabilities.supportsVision) return false
        if (requiredDocMimes.isNotEmpty() && !requiredDocMimes.all { it in capabilities.supportedDocumentMimeTypes }) {
            return false
        }
        return true
    }

    private fun candidateSupportsIntent(
        capabilities: ProviderCapabilities,
        intent: AnalyzedIntent,
        enforceStrictToolsAndJson: Boolean
    ): Boolean {
        if (!candidateSupportsAttachments(capabilities, intent.needsVision, intent.requiredDocMimes)) {
            return false
        }
        if (enforceStrictToolsAndJson) {
            if (intent.needsTools && !capabilities.supportsTools) return false
            if (intent.needsJsonOutput && !capabilities.supportsJsonOutput) return false
        }
        return true
    }

    private fun isProviderCoolingDown(provider: ProviderEntity, nowMs: Long): Boolean {
        if (provider.status != ProviderStatus.ERROR && provider.status != ProviderStatus.RATE_LIMITED) return false
        val checkedAt = provider.lastCheckedAt ?: return false
        return (nowMs - checkedAt) in 0 until COOLDOWN_WINDOW_MS
    }

    private fun isLocalServerCoolingDown(server: LocalAiServerEntity): Boolean =
        server.status == ProviderStatus.ERROR ||
            server.status == ProviderStatus.RATE_LIMITED ||
            server.status == ProviderStatus.DISCONNECTED

    private fun healthRank(status: ProviderStatus): Int = when (status) {
        ProviderStatus.CONNECTED -> 0
        ProviderStatus.UNKNOWN -> 1
        ProviderStatus.CHECKING -> 2
        ProviderStatus.DISCONNECTED -> 3
        ProviderStatus.RATE_LIMITED -> 4
        ProviderStatus.ERROR -> 5
        ProviderStatus.DISABLED -> 6
    }

    suspend fun countEligibleAutoFreeModels(
        settings: GatewaySettingsState,
        requireVision: Boolean = false,
        requiredDocumentMimeTypes: Set<String> = emptySet(),
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        val (candidates, _) = resolveCandidates(
            requestedModelOrAlias = "",
            settings = settings,
            mode = RoutingMode.AUTO_FREE,
            explicitProviderId = null,
            requireVision = requireVision,
            requiredDocumentMimeTypes = requiredDocumentMimeTypes,
            nowMs = nowMs
        )
        return candidates.distinctBy { "${it.id}::${it.modelId}" }.size
    }

    suspend fun executeRoutedChat(
        requestedModelOrAlias: String,
        messages: List<ChatMessagePayload>,
        settings: GatewaySettingsState,
        routingModeOverride: RoutingMode? = null,
        explicitProviderId: String? = null,
        deviceName: String = "Playground",
        endpoint: String = "/v1/chat/completions",
        temperature: Float? = null,
        topP: Float? = null,
        maxTokens: Int? = null,
        stopSequences: List<String> = emptyList(),
        jsonOutput: Boolean = false,
        tools: List<NormalizedToolDefinition> = emptyList(),
        toolChoice: NormalizedToolChoice? = null,
        responseFormat: NormalizedResponseFormat? = null,
        requireVision: Boolean = false,
        requiredDocumentMimeTypes: Set<String> = emptySet(),
        fallbackOverride: Boolean? = null
    ): RoutedChatResult {
        val requestId = UUID.randomUUID().toString().take(12)
        val effectiveMode = routingModeOverride ?: settings.defaultRoutingMode
        val reqContext = RoutingRequestContext(
            messages = messages,
            tools = tools,
            toolChoice = toolChoice,
            jsonOutput = jsonOutput,
            responseFormat = responseFormat,
            stream = false,
            requireVision = requireVision,
            requiredDocumentMimeTypes = requiredDocumentMimeTypes
        )
        val normalizedSelector = OpenCodeSelectorIds.normalizeSelectorId(requestedModelOrAlias)
        val intent = analyzeIntent(reqContext)
        val (candidates, reason) = resolveCandidatesWithContext(
            requestedModelOrAlias = requestedModelOrAlias,
            settings = settings,
            mode = effectiveMode,
            explicitProviderId = explicitProviderId,
            requestContext = reqContext
        )

        if (candidates.isEmpty()) {
            val diagnostic = diagnoseNoCandidates(
                requestedModelOrAlias = requestedModelOrAlias,
                settings = settings,
                mode = effectiveMode,
                explicitProviderId = explicitProviderId,
                requireVision = intent.needsVision,
                requiredDocumentMimeTypes = intent.requiredDocMimes
            )
            val err = ChatExecutionResult(
                success = false,
                statusCode = 503,
                errorCode = if (normalizedSelector == OpenCodeSelectorIds.BEST_CODING) {
                    "NO_ELIGIBLE_CODING_MODEL"
                } else {
                    "NO_ELIGIBLE_PROVIDER"
                },
                safeErrorMessage = diagnostic
            )
            val resolved = TokenUsageAccounting.resolveUsage(0, 0, false, messages, "", false)
            val decision = RouterDecisionSnapshot(
                requestId = requestId,
                selectedProvider = "None",
                selectedModel = requestedModelOrAlias.ifBlank {
                    if (effectiveMode == RoutingMode.AUTO_FREE) "Auto-Free" else "Unassigned"
                },
                routingReason = reason,
                fallbackUsed = false,
                attemptCount = 0,
                latencyMs = 0,
                finalStatus = "FAILED (NO_PROVIDER)",
                resolvedUsage = resolved,
                requestedModelOrAlias = requestedModelOrAlias.ifBlank { effectiveMode.name },
                usedDefaultModel = false,
                fallbackReason = diagnostic
            )
            recordDecision(decision, err, null, deviceName, endpoint, effectiveMode.name, resolved)
            return RoutedChatResult(err, decision)
        }

        val allowFallback = fallbackOverride ?: if (explicitProviderId != null) {
            settings.manualModelFallbackEnabled && settings.fallbackEnabled
        } else {
            settings.fallbackEnabled ||
                effectiveMode == RoutingMode.AUTO_FREE ||
                OpenCodeSelectorIds.isVirtualSelector(normalizedSelector)
        }
        val configuredAttempts = 1 + settings.maxRetries
        val effectiveAttemptCap = if (OpenCodeSelectorIds.isVirtualSelector(normalizedSelector)) {
            maxOf(configuredAttempts, 6)
        } else {
            configuredAttempts
        }
        val maxAttempts = if (allowFallback) effectiveAttemptCap.coerceAtMost(candidates.size) else 1
        val requestStartNs = System.nanoTime()
        val totalBudgetSec = settings.gatewayTimeoutSec.coerceIn(2, 75)
        val deadlineNs = requestStartNs + totalBudgetSec * 1_000_000_000L

        var attemptCount = 0
        var lastResult = ChatExecutionResult(false, errorCode = "UNKNOWN", safeErrorMessage = "No attempts made")
        var winningCandidate = candidates.first()
        var triggeredFallbackReason: String? = null
        val attemptedKeys = mutableSetOf<Triple<String, String?, String>>()
        val failedModels = mutableSetOf<Pair<String, String>>()
        val failedProviders = mutableSetOf<String>()

        while (attemptCount < maxAttempts) {
            val candidate = pickNextCandidate(
                candidates = candidates,
                explicitProviderId = explicitProviderId,
                attemptedKeys = attemptedKeys,
                failedModels = failedModels,
                failedProviders = failedProviders
            ) ?: break

            // Protocol safety invariant: never forward Gateway virtual IDs upstream
            check(!OpenCodeSelectorIds.isVirtualSelector(candidate.modelId)) {
                "Virtual selector ID '${candidate.modelId}' cannot be sent upstream"
            }

            val remainingNs = deadlineNs - System.nanoTime()
            if (attemptCount > 0 && remainingNs < 1_000_000_000L) {
                if (triggeredFallbackReason.isNullOrBlank()) {
                    triggeredFallbackReason = "Routing time budget (${totalBudgetSec}s) exhausted before backup attempt"
                }
                break
            }

            attemptedKeys += Triple(candidate.id, candidate.keyEntityId, candidate.modelId.lowercase(Locale.US))
            winningCandidate = candidate
            attemptCount++

            val remainingDistinctModels = countRemainingDistinctModels(
                candidates = candidates,
                explicitProviderId = explicitProviderId,
                attemptedKeys = attemptedKeys,
                failedModels = failedModels
            )
            val hasMoreBackupCandidates = allowFallback && attemptCount < maxAttempts && remainingDistinctModels > 0
            val plannedRemainingAttempts = minOf(remainingDistinctModels, (maxAttempts - attemptCount).coerceAtLeast(0))
            val remainingBudgetSec = ((remainingNs + 999_999_999L) / 1_000_000_000L).toInt().coerceAtLeast(2)
            val attemptTimeoutSec = if (hasMoreBackupCandidates) {
                minOf(
                    remainingBudgetSec,
                    minOf(25, (totalBudgetSec * 2 / (plannedRemainingAttempts + 2)).coerceAtLeast(2))
                )
            } else {
                remainingBudgetSec
            }
            val attemptConnectSec = minOf(attemptTimeoutSec, 8)

            val res = httpClient.executeChat(
                baseUrl = candidate.baseUrl,
                apiFormat = candidate.apiFormat,
                apiKey = candidate.apiKey,
                customHeadersJson = candidate.customHeadersJson,
                params = ChatRequestParams(
                    messages = messages,
                    modelId = candidate.modelId,
                    temperature = temperature,
                    topP = topP,
                    maxTokens = maxTokens,
                    stopSequences = stopSequences,
                    jsonOutput = jsonOutput,
                    stream = false,
                    tools = tools,
                    toolChoice = toolChoice,
                    responseFormat = responseFormat,
                    capabilities = candidate.capabilities
                ),
                allowLocalHttp = settings.localNetworkAccessAllowed,
                timeoutSec = attemptTimeoutSec,
                connectTimeoutSec = attemptConnectSec
            )
            val normalizedRes = if (res.success && res.modelUsed.isNullOrBlank()) {
                res.copy(modelUsed = candidate.modelId)
            } else {
                res
            }
            lastResult = normalizedRes

            updateProviderAndKeyHealth(candidate, normalizedRes)
            if (normalizedRes.success) break

            failedModels += candidate.id to candidate.modelId.lowercase(Locale.US)
            if (normalizedRes.statusCode >= 500 ||
                normalizedRes.statusCode == 429 ||
                normalizedRes.statusCode == 402 ||
                normalizedRes.statusCode == 401 ||
                normalizedRes.statusCode == 403 ||
                normalizedRes.errorCode == "QUOTA_EXCEEDED" ||
                normalizedRes.errorCode == "TIMEOUT" ||
                normalizedRes.errorCode == "CONNECTION_ERROR" ||
                normalizedRes.errorCode == "EMPTY_RESPONSE"
            ) {
                failedProviders += candidate.id
            }

            val failCategory = normalizedRes.errorCode ?: "HTTP_${normalizedRes.statusCode}"
            val nextBackupExists = allowFallback && attemptCount < maxAttempts && normalizedRes.retryable &&
                pickNextCandidate(candidates, explicitProviderId, attemptedKeys, failedModels, failedProviders) != null

            logSafeRouterDiagnostic(
                requestId = requestId,
                endpoint = endpoint,
                requestedAutoId = requestedModelOrAlias,
                normalizedSelector = normalizedSelector,
                selectedProvider = candidate.displayName,
                selectedModel = candidate.modelId,
                upstreamStatus = normalizedRes.statusCode,
                fallbackAttempts = (attemptCount - 1).coerceAtLeast(0),
                elapsedMs = (System.nanoTime() - requestStartNs) / 1_000_000L,
                finalStatus = if (nextBackupExists) "RETRYING ($failCategory)" else "ATTEMPT_FAILED ($failCategory)",
                detail = normalizedRes.safeErrorMessage
            )

            if (attemptCount > 1 || nextBackupExists) {
                triggeredFallbackReason = "$failCategory on ${candidate.displayName}/${candidate.modelId}"
                db.metricAndLogDao().insertLog(
                    ActivityLogEntity(
                        category = ActivityCategory.FALLBACK_EVENT,
                        relatedEntity = candidate.displayName,
                        status = failCategory,
                        safeMessage = "Fallback triggered from ${candidate.displayName}/${candidate.modelId} (${MaskingUtils.sanitizeErrorMessage(normalizedRes.safeErrorMessage)})"
                    )
                )
            }
            if (!normalizedRes.retryable) break
        }

        val totalElapsedMs = ((System.nanoTime() - requestStartNs) / 1_000_000L).coerceAtLeast(lastResult.latencyMs)
        val fallbackUsed = attemptCount > 1
        val finalStatus = if (lastResult.success) {
            if (fallbackUsed) "SUCCESS (FALLBACK)" else "SUCCESS"
        } else {
            "FAILED (${lastResult.errorCode ?: lastResult.statusCode})"
        }
        val resolved = TokenUsageAccounting.resolveUsage(
            reportedInputTokens = lastResult.inputTokens,
            reportedOutputTokens = lastResult.outputTokens,
            usageReported = lastResult.usageReported,
            promptMessages = messages,
            completionText = lastResult.content,
            requestSucceeded = lastResult.success
        )
        val decision = RouterDecisionSnapshot(
            requestId = requestId,
            selectedProvider = winningCandidate.displayName,
            selectedModel = winningCandidate.modelId,
            routingReason = reason,
            fallbackUsed = fallbackUsed,
            attemptCount = attemptCount,
            latencyMs = totalElapsedMs,
            finalStatus = finalStatus,
            resolvedUsage = resolved,
            requestedModelOrAlias = requestedModelOrAlias.ifBlank { effectiveMode.name },
            usedDefaultModel = winningCandidate.isCanonicalDefault,
            fallbackReason = triggeredFallbackReason
        )
        recordDecision(decision, lastResult, winningCandidate, deviceName, endpoint, effectiveMode.name, resolved)
        return RoutedChatResult(lastResult, decision)
    }

    fun streamRoutedChat(
        requestedModelOrAlias: String,
        messages: List<ChatMessagePayload>,
        settings: GatewaySettingsState,
        routingModeOverride: RoutingMode? = null,
        explicitProviderId: String? = null,
        deviceName: String = "Playground",
        endpoint: String = "/v1/chat/completions",
        temperature: Float? = null,
        topP: Float? = null,
        maxTokens: Int? = null,
        stopSequences: List<String> = emptyList(),
        jsonOutput: Boolean = false,
        tools: List<NormalizedToolDefinition> = emptyList(),
        toolChoice: NormalizedToolChoice? = null,
        responseFormat: NormalizedResponseFormat? = null,
        requireVision: Boolean = false,
        requiredDocumentMimeTypes: Set<String> = emptySet(),
        fallbackOverride: Boolean? = null
    ): Flow<Pair<StreamEvent, RouterDecisionSnapshot?>> = flow {
        val requestId = UUID.randomUUID().toString().take(12)
        val effectiveMode = routingModeOverride ?: settings.defaultRoutingMode
        val reqContext = RoutingRequestContext(
            messages = messages,
            tools = tools,
            toolChoice = toolChoice,
            jsonOutput = jsonOutput,
            responseFormat = responseFormat,
            stream = true,
            requireVision = requireVision,
            requiredDocumentMimeTypes = requiredDocumentMimeTypes
        )
        val normalizedSelector = OpenCodeSelectorIds.normalizeSelectorId(requestedModelOrAlias)
        val intent = analyzeIntent(reqContext)
        val (candidates, reason) = resolveCandidatesWithContext(
            requestedModelOrAlias = requestedModelOrAlias,
            settings = settings,
            mode = effectiveMode,
            explicitProviderId = explicitProviderId,
            requestContext = reqContext
        )

        if (candidates.isEmpty()) {
            val diagnostic = diagnoseNoCandidates(
                requestedModelOrAlias = requestedModelOrAlias,
                settings = settings,
                mode = effectiveMode,
                explicitProviderId = explicitProviderId,
                requireVision = intent.needsVision,
                requiredDocumentMimeTypes = intent.requiredDocMimes
            )
            val err = ChatExecutionResult(
                success = false,
                statusCode = 503,
                errorCode = if (normalizedSelector == OpenCodeSelectorIds.BEST_CODING) {
                    "NO_ELIGIBLE_CODING_MODEL"
                } else {
                    "NO_ELIGIBLE_PROVIDER"
                },
                safeErrorMessage = diagnostic
            )
            val resolved = TokenUsageAccounting.resolveUsage(0, 0, false, messages, "", false)
            val decision = RouterDecisionSnapshot(
                requestId = requestId,
                selectedProvider = "None",
                selectedModel = requestedModelOrAlias.ifBlank {
                    if (effectiveMode == RoutingMode.AUTO_FREE) "Auto-Free" else "Unassigned"
                },
                routingReason = reason,
                fallbackUsed = false,
                attemptCount = 0,
                latencyMs = 0,
                finalStatus = "FAILED (NO_PROVIDER)",
                resolvedUsage = resolved,
                requestedModelOrAlias = requestedModelOrAlias.ifBlank { effectiveMode.name },
                usedDefaultModel = false,
                fallbackReason = diagnostic
            )
            recordDecision(decision, err, null, deviceName, endpoint, effectiveMode.name, resolved)
            emit(StreamEvent.Failed(err, false) to decision)
            return@flow
        }

        val allowFallback = fallbackOverride ?: if (explicitProviderId != null) {
            settings.manualModelFallbackEnabled && settings.fallbackEnabled
        } else {
            settings.fallbackEnabled ||
                effectiveMode == RoutingMode.AUTO_FREE ||
                OpenCodeSelectorIds.isVirtualSelector(normalizedSelector)
        }
        val configuredStreamAttempts = 1 + settings.maxRetries
        val effectiveStreamAttemptCap = if (OpenCodeSelectorIds.isVirtualSelector(normalizedSelector)) {
            maxOf(configuredStreamAttempts, 6)
        } else {
            configuredStreamAttempts
        }
        val maxAttempts = if (allowFallback) effectiveStreamAttemptCap.coerceAtMost(candidates.size) else 1
        val requestStartNs = System.nanoTime()
        val totalBudgetSec = settings.gatewayTimeoutSec.coerceIn(2, 75)
        val deadlineNs = requestStartNs + totalBudgetSec * 1_000_000_000L

        var attempt = 0
        var triggeredFallbackReason: String? = null
        val attemptedKeys = mutableSetOf<Triple<String, String?, String>>()
        val failedModels = mutableSetOf<Pair<String, String>>()
        val failedProviders = mutableSetOf<String>()
        var lastFailedEvent: Pair<StreamEvent.Failed, RouteCandidate>? = null

        while (attempt < maxAttempts) {
            val candidate = pickNextCandidate(
                candidates = candidates,
                explicitProviderId = explicitProviderId,
                attemptedKeys = attemptedKeys,
                failedModels = failedModels,
                failedProviders = failedProviders
            ) ?: break

            check(!OpenCodeSelectorIds.isVirtualSelector(candidate.modelId)) {
                "Virtual selector ID '${candidate.modelId}' cannot be sent upstream"
            }

            val remainingNs = deadlineNs - System.nanoTime()
            if (attempt > 0 && remainingNs < 1_000_000_000L) {
                break
            }

            lastFailedEvent = null
            attemptedKeys += Triple(candidate.id, candidate.keyEntityId, candidate.modelId.lowercase(Locale.US))
            attempt++

            val remainingDistinctModels = countRemainingDistinctModels(
                candidates = candidates,
                explicitProviderId = explicitProviderId,
                attemptedKeys = attemptedKeys,
                failedModels = failedModels
            )
            val hasMoreBackupCandidates = allowFallback && attempt < maxAttempts && remainingDistinctModels > 0
            val plannedRemainingAttempts = minOf(remainingDistinctModels, (maxAttempts - attempt).coerceAtLeast(0))
            val remainingBudgetSec = ((remainingNs + 999_999_999L) / 1_000_000_000L).toInt().coerceAtLeast(2)
            val attemptTimeoutSec = if (hasMoreBackupCandidates) {
                minOf(
                    remainingBudgetSec,
                    minOf(25, (totalBudgetSec * 2 / (plannedRemainingAttempts + 2)).coerceAtLeast(2))
                )
            } else {
                remainingBudgetSec
            }
            val attemptConnectSec = minOf(attemptTimeoutSec, 8)

            var finishedCleanly = false
            var canRetryNext = false
            var latestResult: ChatExecutionResult? = null

            try {
                httpClient.streamChat(
                    baseUrl = candidate.baseUrl,
                    apiFormat = candidate.apiFormat,
                    apiKey = candidate.apiKey,
                    customHeadersJson = candidate.customHeadersJson,
                    params = ChatRequestParams(
                        messages = messages,
                        modelId = candidate.modelId,
                        temperature = temperature,
                        topP = topP,
                        maxTokens = maxTokens,
                        stopSequences = stopSequences,
                        jsonOutput = jsonOutput,
                        stream = true,
                        tools = tools,
                        toolChoice = toolChoice,
                        responseFormat = responseFormat,
                        capabilities = candidate.capabilities
                    ),
                    allowLocalHttp = settings.localNetworkAccessAllowed,
                    timeoutSec = attemptTimeoutSec,
                    connectTimeoutSec = attemptConnectSec
                ).collect { event ->
                    when (event) {
                        is StreamEvent.Delta -> {
                            lastFailedEvent = null
                            emit(event to null)
                        }
                        is StreamEvent.Completed -> {
                            lastFailedEvent = null
                            finishedCleanly = true
                            val totalElapsedMs = ((System.nanoTime() - requestStartNs) / 1_000_000L)
                                .coerceAtLeast(event.result.latencyMs)
                            val stampedResult = if (event.result.modelUsed.isNullOrBlank()) {
                                event.result.copy(modelUsed = candidate.modelId)
                            } else {
                                event.result
                            }
                            latestResult = stampedResult
                            updateProviderAndKeyHealth(candidate, stampedResult)
                            val resolved = TokenUsageAccounting.resolveUsage(
                                reportedInputTokens = stampedResult.inputTokens,
                                reportedOutputTokens = stampedResult.outputTokens,
                                usageReported = stampedResult.usageReported,
                                promptMessages = messages,
                                completionText = stampedResult.content,
                                requestSucceeded = stampedResult.success
                            )
                            val decision = RouterDecisionSnapshot(
                                requestId = requestId,
                                selectedProvider = candidate.displayName,
                                selectedModel = candidate.modelId,
                                routingReason = reason,
                                fallbackUsed = attempt > 1,
                                attemptCount = attempt,
                                latencyMs = totalElapsedMs,
                                finalStatus = if (attempt > 1) "SUCCESS (FALLBACK)" else "SUCCESS",
                                resolvedUsage = resolved,
                                requestedModelOrAlias = requestedModelOrAlias.ifBlank { effectiveMode.name },
                                usedDefaultModel = candidate.isCanonicalDefault,
                                fallbackReason = triggeredFallbackReason
                            )
                            recordDecision(decision, stampedResult, candidate, deviceName, endpoint, effectiveMode.name, resolved)
                            emit(StreamEvent.Completed(stampedResult) to decision)
                        }
                        is StreamEvent.Failed -> {
                            latestResult = event.error
                            updateProviderAndKeyHealth(candidate, event.error)
                            failedModels += candidate.id to candidate.modelId.lowercase(Locale.US)
                            if (event.error.statusCode >= 500 ||
                                event.error.statusCode == 429 ||
                                event.error.statusCode == 402 ||
                                event.error.statusCode == 401 ||
                                event.error.statusCode == 403 ||
                                event.error.errorCode == "QUOTA_EXCEEDED" ||
                                event.error.errorCode == "TIMEOUT" ||
                                event.error.errorCode == "CONNECTION_ERROR" ||
                                event.error.errorCode == "EMPTY_RESPONSE"
                            ) {
                                failedProviders += candidate.id
                            }

                            val nextCandidateAvailable = !event.emittedAnyChunk &&
                                event.error.retryable &&
                                allowFallback &&
                                attempt < maxAttempts &&
                                (deadlineNs - System.nanoTime()) >= 1_000_000_000L &&
                                pickNextCandidate(candidates, explicitProviderId, attemptedKeys, failedModels, failedProviders) != null

                            val failCode = event.error.errorCode ?: "STREAM_FAIL"
                            logSafeRouterDiagnostic(
                                requestId = requestId,
                                endpoint = endpoint,
                                requestedAutoId = requestedModelOrAlias,
                                normalizedSelector = normalizedSelector,
                                selectedProvider = candidate.displayName,
                                selectedModel = candidate.modelId,
                                upstreamStatus = event.error.statusCode,
                                fallbackAttempts = (attempt - 1).coerceAtLeast(0),
                                elapsedMs = (System.nanoTime() - requestStartNs) / 1_000_000L,
                                finalStatus = if (nextCandidateAvailable) "RETRYING_STREAM ($failCode)" else "STREAM_FAILED ($failCode)",
                                detail = event.error.safeErrorMessage
                            )

                            // Rule 7.3: Never restart mid-stream after chunks have been emitted
                            if (nextCandidateAvailable) {
                                canRetryNext = true
                                lastFailedEvent = event to candidate
                                triggeredFallbackReason = "$failCode on ${candidate.displayName}/${candidate.modelId}"
                                db.metricAndLogDao().insertLog(
                                    ActivityLogEntity(
                                        category = ActivityCategory.FALLBACK_EVENT,
                                        relatedEntity = candidate.displayName,
                                        status = failCode,
                                        safeMessage = "Pre-stream fallback from ${candidate.displayName}/${candidate.modelId}: ${MaskingUtils.sanitizeErrorMessage(event.error.safeErrorMessage)}"
                                    )
                                )
                            } else {
                                lastFailedEvent = null
                                val totalElapsedMs = ((System.nanoTime() - requestStartNs) / 1_000_000L)
                                    .coerceAtLeast(event.error.latencyMs)
                                val resolved = TokenUsageAccounting.resolveUsage(
                                    reportedInputTokens = event.error.inputTokens,
                                    reportedOutputTokens = event.error.outputTokens,
                                    usageReported = event.error.usageReported,
                                    promptMessages = messages,
                                    completionText = event.error.content,
                                    requestSucceeded = false
                                )
                                val decision = RouterDecisionSnapshot(
                                    requestId = requestId,
                                    selectedProvider = candidate.displayName,
                                    selectedModel = candidate.modelId,
                                    routingReason = reason,
                                    fallbackUsed = attempt > 1,
                                    attemptCount = attempt,
                                    latencyMs = totalElapsedMs,
                                    finalStatus = "FAILED (${event.error.errorCode ?: event.error.statusCode})",
                                    resolvedUsage = resolved,
                                    requestedModelOrAlias = requestedModelOrAlias.ifBlank { effectiveMode.name },
                                    usedDefaultModel = candidate.isCanonicalDefault,
                                    fallbackReason = triggeredFallbackReason
                                )
                                recordDecision(decision, event.error, candidate, deviceName, endpoint, effectiveMode.name, resolved)
                                emit(event to decision)
                            }
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            }

            if (finishedCleanly || !canRetryNext) break
            if (latestResult != null && !latestResult!!.retryable) break
        }

        // If loop exited after a deferred pre-stream failure without finding another candidate or before emitting terminal failure:
        val lingering = lastFailedEvent
        if (lingering != null) {
            val (ev, cand) = lingering
            val totalElapsedMs = ((System.nanoTime() - requestStartNs) / 1_000_000L)
                .coerceAtLeast(ev.error.latencyMs)
            val resolved = TokenUsageAccounting.resolveUsage(
                reportedInputTokens = ev.error.inputTokens,
                reportedOutputTokens = ev.error.outputTokens,
                usageReported = ev.error.usageReported,
                promptMessages = messages,
                completionText = ev.error.content,
                requestSucceeded = false
            )
            val decision = RouterDecisionSnapshot(
                requestId = requestId,
                selectedProvider = cand.displayName,
                selectedModel = cand.modelId,
                routingReason = reason,
                fallbackUsed = attempt > 1,
                attemptCount = attempt,
                latencyMs = totalElapsedMs,
                finalStatus = "FAILED (${ev.error.errorCode ?: ev.error.statusCode})",
                resolvedUsage = resolved,
                requestedModelOrAlias = requestedModelOrAlias.ifBlank { effectiveMode.name },
                usedDefaultModel = cand.isCanonicalDefault,
                fallbackReason = triggeredFallbackReason
            )
            recordDecision(decision, ev.error, cand, deviceName, endpoint, effectiveMode.name, resolved)
            emit(ev to decision)
        }
    }

    suspend fun resolveCandidates(
        requestedModelOrAlias: String,
        settings: GatewaySettingsState,
        mode: RoutingMode,
        explicitProviderId: String? = null,
        requireVision: Boolean = false,
        requiredDocumentMimeTypes: Set<String> = emptySet(),
        nowMs: Long = System.currentTimeMillis()
    ): Pair<List<RouteCandidate>, String> {
        return resolveCandidatesWithContext(
            requestedModelOrAlias = requestedModelOrAlias,
            settings = settings,
            mode = mode,
            explicitProviderId = explicitProviderId,
            requestContext = RoutingRequestContext(
                requireVision = requireVision,
                requiredDocumentMimeTypes = requiredDocumentMimeTypes
            ),
            nowMs = nowMs
        )
    }

    suspend fun resolveCandidatesWithContext(
        requestedModelOrAlias: String,
        settings: GatewaySettingsState,
        mode: RoutingMode,
        explicitProviderId: String? = null,
        requestContext: RoutingRequestContext = RoutingRequestContext(),
        nowMs: Long = System.currentTimeMillis()
    ): Pair<List<RouteCandidate>, String> {
        val allProviders = db.providerDao().getAllOnce()
        val allLocalServers = db.localAiServerDao().getAllOnce()
        val allStoredModels = db.modelDao().getAllOnce()
        val rules = db.routingRuleDao().getAllOnce().filter { it.enabled }
        val allKeys = db.apiKeyDao().getAllOnce().filter { it.enabled }

        val intent = analyzeIntent(requestContext)
        val normalizedRequest = OpenCodeSelectorIds.normalizeSelectorId(requestedModelOrAlias)
        val effectiveAutoPolicy = if (normalizedRequest == OpenCodeSelectorIds.AUTO) {
            OpenCodeSelectorIds.normalizeSelectorId(settings.openCodeSelectionMode)
                .takeIf { it in OpenCodeSelectorIds.ALL_VIRTUAL }
                ?: OpenCodeSelectorIds.AUTO
        } else {
            normalizedRequest
        }
        val isVirtualFree = effectiveAutoPolicy == OpenCodeSelectorIds.FREE ||
            (mode == RoutingMode.AUTO_FREE && explicitProviderId == null && (normalizedRequest.isBlank() || normalizedRequest == OpenCodeSelectorIds.AUTO))

        if (isVirtualFree && explicitProviderId == null) {
            return resolveAutoFreeCandidates(
                providers = allProviders.filter { it.enabled },
                localServers = allLocalServers.filter { it.enabled },
                allModels = allStoredModels,
                allKeys = allKeys,
                settings = settings,
                intent = intent,
                nowMs = nowMs
            )
        }

        // Handle logical selectors: auto, default, best-coding (honoring openCodeSelectionMode for jh-getway/auto)
        if (explicitProviderId == null && (
                effectiveAutoPolicy == OpenCodeSelectorIds.AUTO ||
                    effectiveAutoPolicy == OpenCodeSelectorIds.DEFAULT ||
                    effectiveAutoPolicy == OpenCodeSelectorIds.BEST_CODING
                )
        ) {
            return resolveLogicalSelectorCandidates(
                selector = effectiveAutoPolicy,
                providers = allProviders,
                localServers = allLocalServers,
                allModels = allStoredModels,
                allKeys = allKeys,
                settings = settings,
                mode = mode,
                intent = intent,
                nowMs = nowMs
            )
        }

        val providers = allProviders.filter { it.enabled }
        val localServers = allLocalServers.filter { it.enabled }
        val disabledModelKeys = HashSet<String>()
        val enabledModels = mutableListOf<ModelEntity>()
        val enabledModelsByProvider = HashMap<String, MutableList<ModelEntity>>()
        for (m in allStoredModels) {
            if (!m.enabled) {
                disabledModelKeys += "${m.providerId}::${m.modelId.lowercase(Locale.US)}"
            } else {
                enabledModels += m
                enabledModelsByProvider.getOrPut(m.providerId) { mutableListOf() }.add(m)
            }
        }
        val keysByProvider = allKeys.groupBy { it.providerId }
        val decryptedKeyCache = HashMap<String, String?>(allKeys.size)
        fun decryptKeyOnce(key: ApiKeyEntity): String? =
            decryptedKeyCache.getOrPut(key.id) { CryptoManager.decryptResult(key.encryptedKey).getOrNull() }
        val decryptedLocalCache = HashMap<String, String?>(localServers.size)
        fun decryptLocalOnce(srv: LocalAiServerEntity): String? =
            if (!srv.hasAuth) "" else decryptedLocalCache.getOrPut(srv.id) {
                CryptoManager.decryptResult(srv.encryptedAuthToken).getOrNull()
            }

        val targetAliasOrModel = normalizedRequest.ifBlank { settings.defaultModelId.trim() }
        val matchedRule = rules.firstOrNull { it.modelAlias.equals(targetAliasOrModel, ignoreCase = true) }
        val aliasedModels = enabledModels.filter {
            it.alias.equals(targetAliasOrModel, ignoreCase = true) ||
                it.modelId.equals(targetAliasOrModel, ignoreCase = true)
        }
        val isAliasRequest = targetAliasOrModel.isBlank() ||
            matchedRule != null ||
            targetAliasOrModel.startsWith("jh-", ignoreCase = true) ||
            aliasedModels.any { it.alias.equals(targetAliasOrModel, ignoreCase = true) }
        val isSpecificModelRequest = !isAliasRequest

        // Identify canonical default so diagnostics can note it cleanly
        val (_, canonicalDefaultRec) = OpenCodeModelCatalogResolver.resolveEligibleRecords(
            providers = providers,
            localServers = localServers,
            allModels = allStoredModels,
            allKeys = allKeys,
            settings = settings,
            nowMs = nowMs
        )

        val matchedProviderIds = buildSet {
            aliasedModels.forEach { add(it.providerId) }
            providers.forEach { p ->
                val def = p.defaultModel.trim()
                if (def.isNotBlank() &&
                    def.equals(targetAliasOrModel, ignoreCase = true) &&
                    !disabledModelKeys.contains("${p.id}::${def.lowercase(Locale.US)}")
                ) {
                    add(p.id)
                }
            }
            localServers.forEach { srv ->
                val def = srv.defaultModel.trim()
                if (def.isNotBlank() &&
                    def.equals(targetAliasOrModel, ignoreCase = true) &&
                    !disabledModelKeys.contains("local:${srv.id}::${def.lowercase(Locale.US)}")
                ) {
                    add("local:${srv.id}")
                }
            }
        }
        val hasEligibleCatalogMatch = isSpecificModelRequest && (
            providers.any { p ->
                p.id in matchedProviderIds && selectKeysForProvider(keysByProvider[p.id].orEmpty()).isNotEmpty()
            } || localServers.any { srv ->
                "local:${srv.id}" in matchedProviderIds && decryptLocalOnce(srv) != null
            }
        )

        // Strict safeguard: if user explicitly requested a disabled model in catalog and NO enabled provider/model matches, do not ghost-route it
        if (isSpecificModelRequest && !hasEligibleCatalogMatch && explicitProviderId == null &&
            allStoredModels.any { it.modelId.equals(targetAliasOrModel, ignoreCase = true) && !it.enabled }
        ) {
            return emptyList<RouteCandidate>() to "Requested model '$targetAliasOrModel' is disabled."
        }

        val candidates = mutableListOf<RouteCandidate>()
        for (provider in providers) {
            coroutineContext.ensureActive()
            if (explicitProviderId != null && provider.id != explicitProviderId) continue
            if (matchedRule != null && matchedRule.allowedProviderIdsCsv.isNotBlank()) {
                val allowed = matchedRule.allowedProviderIdsCsv.split(",").map { it.trim() }
                if (provider.id !in allowed && provider.name !in allowed) continue
            }
            if (explicitProviderId == null && hasEligibleCatalogMatch && provider.id !in matchedProviderIds) {
                continue
            }
            if (explicitProviderId == null &&
                mode != RoutingMode.PRIORITY &&
                mode != RoutingMode.MANUAL &&
                isProviderCoolingDown(provider, nowMs)
            ) {
                continue
            }

            val selectedKeys = selectKeysForProvider(keysByProvider[provider.id].orEmpty())
            if (selectedKeys.isEmpty()) continue

            val providerModels = enabledModelsByProvider[provider.id].orEmpty()
            val matchedCatalogModel = aliasedModels.firstOrNull { it.providerId == provider.id }
            val defaultAllowed = provider.defaultModel.trim().let { def ->
                def.isNotBlank() && !disabledModelKeys.contains("${provider.id}::${def.lowercase(Locale.US)}")
            }
            val resolvedModel = when {
                explicitProviderId != null && isSpecificModelRequest -> matchedCatalogModel
                isSpecificModelRequest -> matchedCatalogModel
                else -> matchedCatalogModel
                    ?: providerModels.firstOrNull { it.isDefault }
                    ?: provider.defaultModel.takeIf { defaultAllowed }?.let { defId ->
                        providerModels.firstOrNull { it.modelId.equals(defId, ignoreCase = true) }
                    }
                    ?: providerModels.firstOrNull {
                        !ModelCapabilityRegistry.isNonChatModel(it.modelType, it.modelId, it.supportsChat)
                    }
                    ?: providerModels.firstOrNull()
            }
            val modelId = when {
                isSpecificModelRequest && (explicitProviderId != null || !hasEligibleCatalogMatch) ->
                    resolvedModel?.modelId?.ifBlank { null } ?: targetAliasOrModel
                else ->
                    resolvedModel?.modelId?.ifBlank { null }
                        ?: provider.defaultModel.takeIf { defaultAllowed }
                        ?: targetAliasOrModel.takeIf {
                            it.isNotBlank() && !it.startsWith("jh-", ignoreCase = true) && !OpenCodeSelectorIds.isVirtualSelector(it)
                        }
                        ?: ""
            }

            if (modelId.isBlank() || OpenCodeSelectorIds.isVirtualSelector(modelId)) continue
            if (explicitProviderId == null && disabledModelKeys.contains("${provider.id}::${modelId.lowercase(Locale.US)}")) {
                continue
            }
            val caps = ProviderAdapterRegistry.adapterFor(provider.apiFormat).capabilitiesForModel(modelId, resolvedModel)
            if (explicitProviderId == null && !candidateSupportsAttachments(caps, intent.needsVision, intent.requiredDocMimes)) {
                continue
            }
            val pricing = ProviderPresets.lookupPricing(modelId)
            val estCost = pricing?.let { (it.inputPer1M + it.outputPer1M) / 2.0 }
            val codingProfile = resolvedModel?.let { ModelCapabilityRegistry.assessCodingCapability(it) }
                ?: CodingCapabilityProfile(false, 0, "")

            for (key in selectedKeys) {
                val decrypted = decryptKeyOnce(key).orEmpty()
                if (decrypted.isBlank()) continue
                candidates += RouteCandidate(
                    id = provider.id,
                    displayName = provider.name,
                    baseUrl = provider.baseUrl,
                    apiFormat = provider.apiFormat,
                    apiKey = decrypted,
                    keyEntityId = key.id,
                    customHeadersJson = provider.customHeadersJson,
                    modelId = modelId,
                    isLocal = false,
                    priority = provider.priority,
                    lastLatencyMs = provider.lastLatencyMs ?: 5000L,
                    estimatedCostPer1M = estCost,
                    capabilities = caps,
                    providerStatus = provider.status,
                    accessTier = resolvedModel?.accessTier ?: AccessTier.UNKNOWN,
                    freeTierVerified = resolvedModel?.freeTierVerified ?: false,
                    isCanonicalDefault = canonicalDefaultRec?.providerId == provider.id &&
                        canonicalDefaultRec.model.modelId.equals(modelId, ignoreCase = true),
                    isCodingCapable = codingProfile.isCodingCapable,
                    codingScore = codingProfile.tierScore
                )
            }
        }

        // Local AI servers (strictly isolated: only local server encryptedAuthToken is used; never cloud keys or headers)
        val localCandidates = mutableListOf<RouteCandidate>()
        for (srv in localServers) {
            coroutineContext.ensureActive()
            val localKey = "local:${srv.id}"
            if (explicitProviderId != null && explicitProviderId != localKey) continue
            if (explicitProviderId == null && hasEligibleCatalogMatch && localKey !in matchedProviderIds) {
                continue
            }
            val srvModels = enabledModelsByProvider[localKey].orEmpty()
            val matchedLocalModel = aliasedModels.firstOrNull { it.providerId == localKey }
            val localDefAllowed = srv.defaultModel.trim().let { def ->
                def.isNotBlank() && !disabledModelKeys.contains("$localKey::${def.lowercase(Locale.US)}")
            }
            val resolvedModel = when {
                explicitProviderId != null && isSpecificModelRequest -> matchedLocalModel
                isSpecificModelRequest -> matchedLocalModel
                else -> matchedLocalModel
                    ?: srvModels.firstOrNull { it.isDefault }
                    ?: srv.defaultModel.takeIf { localDefAllowed }?.let { defId ->
                        srvModels.firstOrNull { it.modelId.equals(defId, ignoreCase = true) }
                    }
                    ?: srvModels.firstOrNull {
                        !ModelCapabilityRegistry.isNonChatModel(it.modelType, it.modelId, it.supportsChat)
                    }
                    ?: srvModels.firstOrNull()
            }
            val modelId = when {
                isSpecificModelRequest && (explicitProviderId != null || !hasEligibleCatalogMatch) ->
                    resolvedModel?.modelId?.ifBlank { null } ?: targetAliasOrModel
                else ->
                    resolvedModel?.modelId?.ifBlank { null }
                        ?: srv.defaultModel.takeIf { localDefAllowed }
                        ?: ""
            }
            if (modelId.isBlank() || OpenCodeSelectorIds.isVirtualSelector(modelId)) continue
            if (explicitProviderId == null && disabledModelKeys.contains("$localKey::${modelId.lowercase(Locale.US)}")) {
                continue
            }
            val authToken = decryptLocalOnce(srv) ?: continue
            val format = if (srv.engineType == LocalEngineType.OLLAMA) ApiFormat.OLLAMA else ApiFormat.OPENAI
            val localCaps = ProviderAdapterRegistry.adapterFor(format).capabilitiesForModel(modelId, resolvedModel)
            if (explicitProviderId == null && !candidateSupportsAttachments(localCaps, intent.needsVision, intent.requiredDocMimes)) {
                continue
            }

            localCandidates += RouteCandidate(
                id = localKey,
                displayName = srv.name,
                baseUrl = srv.serverUrl,
                apiFormat = format,
                apiKey = authToken,
                keyEntityId = null,
                customHeadersJson = "",
                modelId = modelId,
                isLocal = true,
                priority = 0,
                lastLatencyMs = srv.lastLatencyMs ?: 1000L,
                estimatedCostPer1M = 0.0,
                capabilities = localCaps,
                providerStatus = srv.status,
                accessTier = AccessTier.LOCAL,
                freeTierVerified = false,
                isCanonicalDefault = canonicalDefaultRec?.providerId == localKey &&
                    canonicalDefaultRec.model.modelId.equals(modelId, ignoreCase = true)
            )
        }

        val combinedBase = when {
            explicitProviderId != null -> localCandidates + candidates
            settings.localRoutingPolicy == LocalRoutingPolicy.LOCAL_ONLY -> localCandidates
            settings.localRoutingPolicy == LocalRoutingPolicy.LOCAL_FIRST -> {
                if (settings.allowCloudFallbackForLocal) localCandidates + candidates else localCandidates
            }
            settings.localRoutingPolicy == LocalRoutingPolicy.LOCAL_CLOUD_FALLBACK -> localCandidates + candidates
            settings.localRoutingPolicy == LocalRoutingPolicy.CLOUD_FIRST -> candidates + localCandidates
            else -> localCandidates + candidates
        }
        val combined = if (explicitProviderId == null && matchedRule?.localCloudPreference.equals("LOCAL_ONLY", true)) {
            combinedBase.filter { it.isLocal }
        } else {
            combinedBase
        }.distinctBy { Triple(it.id, it.keyEntityId, it.modelId.lowercase(Locale.US)) }

        val baseComparator = compareBy<RouteCandidate> { healthRank(it.providerStatus) }
            .thenBy { it.priority }
            .thenBy { it.lastLatencyMs }
        val sorted = when (mode) {
            RoutingMode.MANUAL -> combined
            RoutingMode.AUTO_FREE -> combined
            RoutingMode.PRIORITY -> combined.sortedBy { it.priority }
            RoutingMode.LOWEST_LATENCY -> combined.sortedBy { it.lastLatencyMs }
            RoutingMode.LOWEST_COST -> combined.sortedBy { it.estimatedCostPer1M ?: Double.MAX_VALUE }
            RoutingMode.LOCAL_FIRST -> combined.sortedWith(compareByDescending<RouteCandidate> { it.isLocal }.then(baseComparator))
            RoutingMode.CLOUD_FIRST -> combined.sortedWith(compareBy<RouteCandidate> { it.isLocal }.then(baseComparator))
            RoutingMode.ROUND_ROBIN -> {
                if (combined.size <= 1) combined else {
                    val idx = roundRobinCounter.getAndIncrement() % combined.size
                    combined.drop(idx) + combined.take(idx)
                }
            }
            RoutingMode.AUTOMATIC -> {
                val prefComparator: Comparator<RouteCandidate> = when {
                    matchedRule?.localCloudPreference.equals("LOCAL_ONLY", true) ||
                        matchedRule?.localCloudPreference.equals("LOCAL_FIRST", true) -> {
                        compareByDescending<RouteCandidate> { it.isLocal }.then(baseComparator)
                    }
                    matchedRule?.localCloudPreference.equals("CLOUD_FIRST", true) -> {
                        compareBy<RouteCandidate> { it.isLocal }.then(baseComparator)
                    }
                    settings.localRoutingPolicy == LocalRoutingPolicy.LOCAL_FIRST ||
                        settings.localRoutingPolicy == LocalRoutingPolicy.LOCAL_CLOUD_FALLBACK -> {
                        compareByDescending<RouteCandidate> { it.isLocal }.then(baseComparator)
                    }
                    settings.localRoutingPolicy == LocalRoutingPolicy.CLOUD_FIRST -> {
                        compareBy<RouteCandidate> { it.isLocal }.then(baseComparator)
                    }
                    else -> baseComparator
                }
                if (matchedRule != null) {
                    combined.sortedWith(
                        compareByDescending<RouteCandidate> {
                            it.id == matchedRule.preferredProviderId || it.displayName == matchedRule.preferredProviderId
                        }.then(prefComparator)
                    )
                } else {
                    combined.sortedWith(prefComparator)
                }
            }
        }

        val reason = when {
            explicitProviderId != null -> "Manual Selection (${sorted.firstOrNull()?.displayName ?: explicitProviderId})"
            matchedRule != null -> "Rule '${matchedRule.name}' (${mode.name})"
            else -> "Routing Mode: ${mode.name} (${settings.localRoutingPolicy.name})"
        }
        return sorted to reason
    }

    private suspend fun resolveLogicalSelectorCandidates(
        selector: String,
        providers: List<ProviderEntity>,
        localServers: List<LocalAiServerEntity>,
        allModels: List<ModelEntity>,
        allKeys: List<ApiKeyEntity>,
        settings: GatewaySettingsState,
        mode: RoutingMode,
        intent: AnalyzedIntent,
        nowMs: Long
    ): Pair<List<RouteCandidate>, String> {
        coroutineContext.ensureActive()
        val (eligibleRecords, canonicalDefaultRec) = OpenCodeModelCatalogResolver.resolveEligibleRecords(
            providers = providers,
            localServers = localServers,
            allModels = allModels,
            allKeys = allKeys,
            settings = settings,
            nowMs = nowMs
        )

        val enforceFreeOnly = selector == OpenCodeSelectorIds.FREE ||
            mode == RoutingMode.AUTO_FREE ||
            (selector == OpenCodeSelectorIds.AUTO && !settings.autoAllowPaidModels)

        val providerMap = providers.associateBy { it.id }
        val localMap = localServers.associateBy { "local:${it.id}" }

        // Apply cooldown & capability & local policy & free-only gating across all eligible records
        val gatedRecords = eligibleRecords.filter { rec ->
            if (OpenCodeSelectorIds.isVirtualSelector(rec.model.modelId)) return@filter false
            if (OpenCodeModelCatalogResolver.isRecordCoolingDown(rec, nowMs)) return@filter false
            if (rec.isLocal) {
                val srv = localMap[rec.providerId] ?: return@filter false
                if (isLocalServerCoolingDown(srv)) return@filter false
                if (enforceFreeOnly && !settings.autoFreeIncludeLocal) return@filter false
            } else {
                if (settings.localRoutingPolicy == LocalRoutingPolicy.LOCAL_ONLY) return@filter false
                if (settings.localRoutingPolicy == LocalRoutingPolicy.LOCAL_FIRST && !settings.allowCloudFallbackForLocal) {
                    if (eligibleRecords.any { it.isLocal }) return@filter false
                }
                if (enforceFreeOnly && !OpenCodeModelCatalogResolver.isVerifiedFree(rec.model, settings.autoFreeIncludeLocal, false)) {
                    return@filter false
                }
            }
            if (enforceFreeOnly && !OpenCodeModelCatalogResolver.isVerifiedFree(rec.model, settings.autoFreeIncludeLocal, rec.isLocal)) {
                return@filter false
            }
            candidateSupportsIntent(rec.capabilities, intent, enforceStrictToolsAndJson = intent.needsTools || intent.needsJsonOutput)
        }.ifEmpty {
            // Relax strict tool/json check ONLY if no model supports optional json flag while keeping strict attachments, free-only, & cooldowns
            if (intent.needsTools || intent.needsJsonOutput) {
                eligibleRecords.filter { rec ->
                    if (OpenCodeSelectorIds.isVirtualSelector(rec.model.modelId)) return@filter false
                    if (OpenCodeModelCatalogResolver.isRecordCoolingDown(rec, nowMs)) return@filter false
                    if (rec.isLocal) {
                        if (isLocalServerCoolingDown(localMap[rec.providerId] ?: return@filter false)) return@filter false
                    } else {
                        if (settings.localRoutingPolicy == LocalRoutingPolicy.LOCAL_ONLY) return@filter false
                        if (settings.localRoutingPolicy == LocalRoutingPolicy.LOCAL_FIRST && !settings.allowCloudFallbackForLocal) {
                            if (eligibleRecords.any { it.isLocal }) return@filter false
                        }
                    }
                    if (enforceFreeOnly && !OpenCodeModelCatalogResolver.isVerifiedFree(rec.model, settings.autoFreeIncludeLocal, rec.isLocal)) {
                        return@filter false
                    }
                    candidateSupportsAttachments(rec.capabilities, intent.needsVision, intent.requiredDocMimes) &&
                        (!intent.needsTools || rec.capabilities.supportsTools)
                }
            } else emptyList()
        }

        if (gatedRecords.isEmpty()) {
            val emptyReason = when {
                selector == OpenCodeSelectorIds.BEST_CODING -> "Best Coding: no compatible eligible model available"
                enforceFreeOnly -> "Auto (Free-Only): no verified free models available"
                else -> "Auto: no eligible healthy models available"
            }
            return emptyList<RouteCandidate>() to emptyReason
        }

        val orderedRecords: List<EligibleModelRecord>
        val reason: String

        when (selector) {
            OpenCodeSelectorIds.DEFAULT -> {
                val hasConfiguredDefault = settings.defaultModelId.isNotBlank() ||
                    allModels.any { it.isDefault } ||
                    providers.any { it.enabled && it.defaultModel.isNotBlank() }
                val defaultInPool = canonicalDefaultRec?.let { def ->
                    gatedRecords.firstOrNull {
                        it.providerId == def.providerId && it.model.modelId.equals(def.model.modelId, ignoreCase = true)
                    }
                }
                val rankedOthers = gatedRecords
                    .filter { rec -> defaultInPool == null || rec.providerId != defaultInPool.providerId || !rec.model.modelId.equals(defaultInPool.model.modelId, true) }
                    .sortedWith(smartAutoComparator(settings, mode, intent, canonicalDefaultRec))

                if (defaultInPool != null) {
                    orderedRecords = listOf(defaultInPool) + rankedOthers
                    reason = "Default Model (${defaultInPool.providerName}/${defaultInPool.model.modelId})"
                } else {
                    orderedRecords = rankedOthers
                    val note = if (hasConfiguredDefault) {
                        "Configured default unavailable/disabled; fell back to ${rankedOthers.first().providerName}/${rankedOthers.first().model.modelId}"
                    } else {
                        "No explicit default configured; routed via fallback to ${rankedOthers.first().providerName}/${rankedOthers.first().model.modelId}"
                    }
                    reason = "Default Fallback: $note"
                }
            }

            OpenCodeSelectorIds.BEST_CODING -> {
                val codingCapable = gatedRecords.filter { it.codingProfile.isCodingCapable }
                    .sortedWith(
                        compareByDescending<EligibleModelRecord> { it.codingScore }
                            .thenBy { healthRank(it.providerStatus) }
                            .thenBy { it.providerPriority }
                            .thenBy { it.lastLatencyMs }
                            .thenBy { it.model.modelId.lowercase(Locale.US) }
                    )
                if (codingCapable.isNotEmpty()) {
                    val nonCodingCompatible = gatedRecords
                        .filter { !it.codingProfile.isCodingCapable }
                        .sortedWith(smartAutoComparator(settings, mode, intent, canonicalDefaultRec))
                    orderedRecords = codingCapable + nonCodingCompatible
                    val top = codingCapable.first()
                    reason = "Best Coding: ${top.providerName}/${top.model.modelId} (${top.codingProfile.provenance}, score=${top.codingScore})"
                } else {
                    // No verified coding metadata found -> documented compatible general-purpose fallback
                    orderedRecords = gatedRecords.sortedWith(smartAutoComparator(settings, mode, intent, canonicalDefaultRec))
                    val top = orderedRecords.first()
                    reason = "Best Coding (Documented General Fallback): ${top.providerName}/${top.model.modelId}"
                }
            }

            else -> { // OpenCodeSelectorIds.AUTO
                val defaultInPool = canonicalDefaultRec?.let { def ->
                    gatedRecords.firstOrNull {
                        it.providerId == def.providerId && it.model.modelId.equals(def.model.modelId, ignoreCase = true)
                    }
                }
                val preferDefault = settings.preferConfiguredDefaultInAuto && defaultInPool != null && (
                    !intent.isCodingIntent ||
                        defaultInPool.codingProfile.isCodingCapable ||
                        gatedRecords.none { it.codingProfile.isCodingCapable }
                    )
                val explicitLocalFirst = mode == RoutingMode.LOCAL_FIRST ||
                    settings.localRoutingPolicy == LocalRoutingPolicy.LOCAL_FIRST
                val explicitCloudFirst = mode == RoutingMode.CLOUD_FIRST ||
                    settings.localRoutingPolicy == LocalRoutingPolicy.CLOUD_FIRST
                val ranked = gatedRecords.sortedWith(
                    compareBy<EligibleModelRecord> {
                        when {
                            explicitLocalFirst -> !it.isLocal
                            explicitCloudFirst -> it.isLocal
                            else -> false
                        }
                    }.thenByDescending { rec ->
                        preferDefault && defaultInPool != null &&
                            rec.providerId == defaultInPool.providerId &&
                            rec.model.modelId.equals(defaultInPool.model.modelId, ignoreCase = true)
                    }.then(smartAutoComparator(settings, mode, intent, canonicalDefaultRec))
                )
                orderedRecords = ranked
                val top = ranked.first()
                val usedDefault = defaultInPool != null &&
                    top.providerId == defaultInPool.providerId &&
                    top.model.modelId.equals(defaultInPool.model.modelId, ignoreCase = true)
                reason = buildString {
                    append("Auto (")
                    if (enforceFreeOnly) append("free-only, ")
                    if (usedDefault) {
                        append("configured default")
                    } else if (intent.isCodingIntent && top.codingProfile.isCodingCapable) {
                        append("coding intent")
                    } else {
                        append(mode.name.lowercase(Locale.US))
                    }
                    append(") → ${top.providerName}/${top.model.modelId}")
                }
            }
        }

        // Expand each ordered EligibleModelRecord to RouteCandidate(s) using cached per-provider active keys
        val keysByProvider = allKeys.groupBy { it.providerId }
        val selectedKeysByProvider = HashMap<String, List<ApiKeyEntity>>(providerMap.size)
        val decryptedKeyCache = HashMap<String, String?>(allKeys.size)
        val decryptedLocalCache = HashMap<String, String?>(localMap.size)

        val expanded = mutableListOf<RouteCandidate>()
        for (rec in orderedRecords) {
            coroutineContext.ensureActive()
            if (rec.isLocal) {
                val srv = localMap[rec.providerId] ?: continue
                val authToken = if (srv.hasAuth) {
                    decryptedLocalCache.getOrPut(srv.id) {
                        CryptoManager.decryptResult(srv.encryptedAuthToken).getOrNull()
                    } ?: continue
                } else ""
                expanded += RouteCandidate(
                    id = rec.providerId,
                    displayName = rec.providerName,
                    baseUrl = srv.serverUrl,
                    apiFormat = rec.apiFormat,
                    apiKey = authToken,
                    keyEntityId = null,
                    customHeadersJson = "",
                    modelId = rec.model.modelId,
                    isLocal = true,
                    priority = rec.providerPriority,
                    lastLatencyMs = rec.lastLatencyMs,
                    estimatedCostPer1M = 0.0,
                    capabilities = rec.capabilities,
                    providerStatus = rec.providerStatus,
                    accessTier = AccessTier.LOCAL,
                    freeTierVerified = false,
                    isCanonicalDefault = canonicalDefaultRec?.providerId == rec.providerId &&
                        canonicalDefaultRec.model.modelId.equals(rec.model.modelId, ignoreCase = true),
                    isCodingCapable = rec.codingProfile.isCodingCapable,
                    codingScore = rec.codingScore
                )
            } else {
                val prov = providerMap[rec.providerId] ?: continue
                val selectedKeys = selectedKeysByProvider.getOrPut(prov.id) {
                    selectKeysForProvider(keysByProvider[prov.id].orEmpty())
                }
                for (key in selectedKeys) {
                    val decrypted = decryptedKeyCache.getOrPut(key.id) {
                        CryptoManager.decryptResult(key.encryptedKey).getOrNull()
                    }.orEmpty()
                    if (decrypted.isBlank()) continue
                    expanded += RouteCandidate(
                        id = prov.id,
                        displayName = prov.name,
                        baseUrl = prov.baseUrl,
                        apiFormat = prov.apiFormat,
                        apiKey = decrypted,
                        keyEntityId = key.id,
                        customHeadersJson = prov.customHeadersJson,
                        modelId = rec.model.modelId,
                        isLocal = false,
                        priority = prov.priority,
                        lastLatencyMs = rec.lastLatencyMs,
                        estimatedCostPer1M = rec.effectiveCostPer1M,
                        capabilities = rec.capabilities,
                        providerStatus = prov.status,
                        accessTier = rec.model.accessTier,
                        freeTierVerified = rec.model.freeTierVerified,
                        isCanonicalDefault = canonicalDefaultRec?.providerId == prov.id &&
                            canonicalDefaultRec.model.modelId.equals(rec.model.modelId, ignoreCase = true),
                        isCodingCapable = rec.codingProfile.isCodingCapable,
                        codingScore = rec.codingScore
                    )
                }
            }
        }

        val deduped = expanded.distinctBy { Triple(it.id, it.keyEntityId, it.modelId.lowercase(Locale.US)) }
        return deduped to reason
    }

    private fun smartAutoComparator(
        settings: GatewaySettingsState,
        mode: RoutingMode,
        intent: AnalyzedIntent,
        canonicalDefault: EligibleModelRecord?
    ): Comparator<EligibleModelRecord> {
        return compareBy<EligibleModelRecord> { healthRank(it.providerStatus) }
            .thenBy {
                when {
                    mode == RoutingMode.LOCAL_FIRST -> !it.isLocal
                    mode == RoutingMode.CLOUD_FIRST -> it.isLocal
                    settings.localRoutingPolicy == LocalRoutingPolicy.CLOUD_FIRST -> it.isLocal
                    settings.localRoutingPolicy == LocalRoutingPolicy.LOCAL_FIRST -> !it.isLocal
                    else -> false
                }
            }
            .thenByDescending {
                if (intent.isCodingIntent && it.codingProfile.isCodingCapable) it.codingScore else 0
            }
            .thenByDescending { it.capabilities.supportsTools }
            .thenBy {
                if (mode == RoutingMode.LOWEST_COST) (it.effectiveCostPer1M ?: Double.MAX_VALUE) else 0.0
            }
            .thenBy {
                if (mode == RoutingMode.LOWEST_LATENCY) it.lastLatencyMs else 0L
            }
            .thenByDescending {
                canonicalDefault != null &&
                    it.providerId == canonicalDefault.providerId &&
                    it.model.modelId.equals(canonicalDefault.model.modelId, ignoreCase = true)
            }
            .thenByDescending { it.model.isDefault }
            .thenByDescending { it.codingScore }
            .thenBy { it.providerPriority }
            .thenBy { it.lastLatencyMs }
            .thenBy { it.providerName.lowercase(Locale.US) }
            .thenBy { it.model.modelId.lowercase(Locale.US) }
    }

    private fun resolveAutoFreeCandidates(
        providers: List<ProviderEntity>,
        localServers: List<LocalAiServerEntity>,
        allModels: List<ModelEntity>,
        allKeys: List<ApiKeyEntity>,
        settings: GatewaySettingsState,
        intent: AnalyzedIntent,
        nowMs: Long
    ): Pair<List<RouteCandidate>, String> {
        val candidates = mutableListOf<RouteCandidate>()
        val disabledModelKeys = HashSet<String>()
        val enabledModelsByProvider = HashMap<String, MutableList<ModelEntity>>()
        for (m in allModels) {
            if (!m.enabled) {
                disabledModelKeys += "${m.providerId}::${m.modelId.lowercase(Locale.US)}"
            } else {
                enabledModelsByProvider.getOrPut(m.providerId) { mutableListOf() }.add(m)
            }
        }
        val keysByProvider = allKeys.groupBy { it.providerId }

        for (provider in providers) {
            if (isProviderCoolingDown(provider, nowMs)) continue
            val selectedKeys = selectKeysForProvider(keysByProvider[provider.id].orEmpty())
            if (selectedKeys.isEmpty()) continue
            var decryptedKey = ""
            val primaryKey = selectedKeys.firstOrNull { key ->
                val plain = CryptoManager.decryptResult(key.encryptedKey).getOrNull().orEmpty()
                if (plain.isNotBlank()) {
                    decryptedKey = plain
                    true
                } else false
            } ?: continue
            if (decryptedKey.isBlank()) continue

            val freeModels = enabledModelsByProvider[provider.id].orEmpty().filter { m ->
                m.supportsChat &&
                    !ModelCapabilityRegistry.isNonChatModel(m.modelType, m.modelId, m.supportsChat) &&
                    m.accessTier == AccessTier.FREE_TIER &&
                    m.freeTierVerified
            }.distinctBy { it.modelId.lowercase(Locale.US) }

            val adapter = ProviderAdapterRegistry.adapterFor(provider.apiFormat)
            for (model in freeModels) {
                val caps = adapter.capabilitiesForModel(model.modelId, model)
                if (!candidateSupportsAttachments(caps, intent.needsVision, intent.requiredDocMimes)) continue
                val codingProfile = ModelCapabilityRegistry.assessCodingCapability(model)
                candidates += RouteCandidate(
                    id = provider.id,
                    displayName = provider.name,
                    baseUrl = provider.baseUrl,
                    apiFormat = provider.apiFormat,
                    apiKey = decryptedKey,
                    keyEntityId = primaryKey.id,
                    customHeadersJson = provider.customHeadersJson,
                    modelId = model.modelId,
                    isLocal = false,
                    priority = provider.priority,
                    lastLatencyMs = provider.lastLatencyMs ?: 5000L,
                    estimatedCostPer1M = 0.0,
                    capabilities = caps,
                    providerStatus = provider.status,
                    accessTier = model.accessTier,
                    freeTierVerified = model.freeTierVerified,
                    isCanonicalDefault = model.isDefault,
                    isCodingCapable = codingProfile.isCodingCapable,
                    codingScore = codingProfile.tierScore
                )
            }
        }

        if (settings.autoFreeIncludeLocal) {
            for (srv in localServers) {
                if (isLocalServerCoolingDown(srv)) continue
                val localKey = "local:${srv.id}"
                val authToken = if (srv.hasAuth) {
                    CryptoManager.decryptResult(srv.encryptedAuthToken).getOrNull() ?: continue
                } else ""
                val format = if (srv.engineType == LocalEngineType.OLLAMA) ApiFormat.OLLAMA else ApiFormat.OPENAI
                val adapter = ProviderAdapterRegistry.adapterFor(format)
                val srvModels = enabledModelsByProvider[localKey].orEmpty().filter { m ->
                    m.supportsChat &&
                        !ModelCapabilityRegistry.isNonChatModel(m.modelType, m.modelId, m.supportsChat) &&
                        m.accessTier == AccessTier.LOCAL
                }.distinctBy { it.modelId.lowercase(Locale.US) }
                val defAllowed = srv.defaultModel.isNotBlank() &&
                    !disabledModelKeys.contains("$localKey::${srv.defaultModel.lowercase(Locale.US)}")
                val modelsToEvaluate = if (srvModels.isNotEmpty()) {
                    srvModels
                } else if (defAllowed) {
                    listOf(
                        ModelEntity(
                            id = "$localKey::${srv.defaultModel}",
                            modelId = srv.defaultModel,
                            displayName = srv.defaultModel,
                            providerId = localKey,
                            providerName = srv.name,
                            isLocal = true,
                            accessTier = AccessTier.LOCAL
                        )
                    )
                } else emptyList()

                for (model in modelsToEvaluate) {
                    val caps = adapter.capabilitiesForModel(model.modelId, model)
                    if (!candidateSupportsAttachments(caps, intent.needsVision, intent.requiredDocMimes)) continue
                    val codingProfile = ModelCapabilityRegistry.assessCodingCapability(model)
                    candidates += RouteCandidate(
                        id = localKey,
                        displayName = srv.name,
                        baseUrl = srv.serverUrl,
                        apiFormat = format,
                        apiKey = authToken,
                        keyEntityId = null,
                        customHeadersJson = "",
                        modelId = model.modelId,
                        isLocal = true,
                        priority = 50,
                        lastLatencyMs = srv.lastLatencyMs ?: 1000L,
                        estimatedCostPer1M = 0.0,
                        capabilities = caps,
                        providerStatus = srv.status,
                        accessTier = AccessTier.LOCAL,
                        freeTierVerified = false,
                        isCanonicalDefault = model.isDefault,
                        isCodingCapable = codingProfile.isCodingCapable,
                        codingScore = codingProfile.tierScore
                    )
                }
            }
        }

        val sorted = candidates.sortedWith(
            compareBy<RouteCandidate> { healthRank(it.providerStatus) }
                .thenBy { it.priority }
                .thenBy { it.lastLatencyMs }
                .thenBy { it.displayName }
                .thenBy { it.modelId }
        ).distinctBy { Triple(it.id, it.keyEntityId, it.modelId.lowercase(Locale.US)) }
        return sorted to "Auto — Verified Free Models"
    }

    private suspend fun diagnoseNoCandidates(
        requestedModelOrAlias: String,
        settings: GatewaySettingsState,
        mode: RoutingMode,
        explicitProviderId: String?,
        requireVision: Boolean = false,
        requiredDocumentMimeTypes: Set<String> = emptySet()
    ): String {
        val enabledProviders = db.providerDao().getAllOnce().filter { it.enabled }
        val enabledLocal = db.localAiServerDao().getAllOnce().filter { it.enabled }
        val allKeys = db.apiKeyDao().getAllOnce().filter { it.enabled }
        val normalizedSelector = OpenCodeSelectorIds.normalizeSelectorId(requestedModelOrAlias)
        val isFreeRestricted = normalizedSelector == OpenCodeSelectorIds.FREE ||
            mode == RoutingMode.AUTO_FREE ||
            (normalizedSelector == OpenCodeSelectorIds.AUTO && !settings.autoAllowPaidModels)

        if (isFreeRestricted && explicitProviderId == null) {
            if (requireVision || requiredDocumentMimeTypes.isNotEmpty()) {
                val (unfiltered, _) = resolveCandidates(
                    requestedModelOrAlias = OpenCodeSelectorIds.FREE,
                    settings = settings,
                    mode = RoutingMode.AUTO_FREE,
                    explicitProviderId = null,
                    requireVision = false,
                    requiredDocumentMimeTypes = emptySet()
                )
                if (unfiltered.isNotEmpty()) {
                    return "No verified free models support the attached image or document type."
                }
            }
            return "No verified free models available. Configure a free-tier provider, confirm free access in Models, or pick a specific model."
        }

        if (normalizedSelector == OpenCodeSelectorIds.BEST_CODING && enabledProviders.isNotEmpty()) {
            return "No eligible coding-capable model available with active credentials or required capabilities."
        }

        if (enabledProviders.isEmpty() && enabledLocal.isEmpty()) {
            return "No enabled provider or local AI server available. Enable or add a provider first."
        }
        if (explicitProviderId != null && !explicitProviderId.startsWith("local:")) {
            val prov = enabledProviders.firstOrNull { it.id == explicitProviderId }
                ?: return "Selected provider is disabled or missing."
            val validKeys = selectKeysForProvider(allKeys.filter { it.providerId == prov.id })
            if (validKeys.isEmpty()) {
                return "No active API key configured for ${prov.name}. Add a valid key in API Key Vault."
            }
            if (requestedModelOrAlias.isBlank() && prov.defaultModel.isBlank()) {
                return "No model selected for ${prov.name}. Fetch models and select a default model."
            }
        }
        if (explicitProviderId == null && enabledLocal.isEmpty() &&
            (settings.localRoutingPolicy == LocalRoutingPolicy.LOCAL_ONLY ||
                (settings.localRoutingPolicy == LocalRoutingPolicy.LOCAL_FIRST && !settings.allowCloudFallbackForLocal))
        ) {
            return "Local routing policy (${settings.localRoutingPolicy.name}) excludes cloud providers and no local AI server is enabled."
        }
        if (enabledProviders.isNotEmpty() && enabledProviders.all { p -> selectKeysForProvider(allKeys.filter { it.providerId == p.id }).isEmpty() } && enabledLocal.isEmpty()) {
            return "No active API key found for enabled providers. Add an API key in Providers or API Key Vault."
        }
        val nowMs = System.currentTimeMillis()
        if (explicitProviderId == null && enabledProviders.isNotEmpty() &&
            enabledProviders.all { isProviderCoolingDown(it, nowMs) } &&
            enabledLocal.all { isLocalServerCoolingDown(it) }
        ) {
            return "All enabled providers are in temporary 60s cooldown after recent rate limits or errors. Retry shortly or verify provider status."
        }
        if (requireVision || requiredDocumentMimeTypes.isNotEmpty()) {
            return "No enabled provider or model supports the requested image or document attachment."
        }
        return "No enabled provider or model available for '${requestedModelOrAlias.ifBlank { settings.defaultModelId.ifBlank { "default" } }}'."
    }

    private fun selectKeysForProvider(keys: List<ApiKeyEntity>): List<ApiKeyEntity> {
        val eligible = keys.filter { it.enabled && it.status != KeyStatus.DISABLED && it.status != KeyStatus.INVALID && it.status != KeyStatus.EXPIRED }
        if (eligible.isEmpty()) return emptyList()
        val policy = eligible.first().selectionPolicy
        return when (policy) {
            KeySelectionPolicy.MANUAL -> eligible.filter { it.isDefault }.ifEmpty { listOf(eligible.first()) }
            KeySelectionPolicy.PRIORITY -> eligible.sortedWith(compareByDescending<ApiKeyEntity> { it.isDefault }.thenBy { it.priority })
            KeySelectionPolicy.ROUND_ROBIN -> {
                val idx = keyRoundRobinCounter.getAndIncrement() % eligible.size
                eligible.drop(idx) + eligible.take(idx)
            }
            KeySelectionPolicy.FAILOVER -> eligible.sortedBy { it.priority }
        }
    }

    private suspend fun updateProviderAndKeyHealth(candidate: RouteCandidate, res: ChatExecutionResult) {
        // Non-retryable client validation/schema errors or per-model capability mismatches must NOT poison healthy providers
        val isClientMalformed = !res.success && (
            res.errorCode == "UNSUPPORTED_CAPABILITY" ||
                (!res.retryable && res.statusCode in 400..499 && res.statusCode != 401 && res.statusCode != 402 && res.statusCode != 403 && res.statusCode != 429)
            )
        if (!candidate.isLocal) {
            if (!isClientMalformed) {
                db.providerDao().getById(candidate.id)?.let { p ->
                    val newStatus = when {
                        res.success -> ProviderStatus.CONNECTED
                        res.statusCode == 429 -> ProviderStatus.RATE_LIMITED
                        else -> ProviderStatus.ERROR
                    }
                    db.providerDao().upsert(
                        p.copy(
                            status = newStatus,
                            lastLatencyMs = res.latencyMs,
                            lastError = if (res.success) null else res.safeErrorMessage,
                            lastCheckedAt = System.currentTimeMillis()
                        )
                    )
                }
            }
            candidate.keyEntityId?.let { kid ->
                db.apiKeyDao().getAllOnce().firstOrNull { it.id == kid }?.let { k ->
                    val kStatus = when {
                        res.success -> KeyStatus.ACTIVE
                        isClientMalformed -> k.status
                        res.statusCode == 429 -> KeyStatus.RATE_LIMITED
                        res.statusCode == 401 || res.statusCode == 403 -> KeyStatus.INVALID
                        else -> k.status
                    }
                    db.apiKeyDao().upsert(
                        k.copy(
                            status = kStatus,
                            usageCount = k.usageCount + 1,
                            lastUsedAt = System.currentTimeMillis()
                        )
                    )
                }
            }
        } else if (!isClientMalformed) {
            val srvId = candidate.id.removePrefix("local:")
            db.localAiServerDao().getAllOnce().firstOrNull { it.id == srvId }?.let { srv ->
                db.localAiServerDao().upsert(
                    srv.copy(
                        status = if (res.success) ProviderStatus.CONNECTED else ProviderStatus.ERROR,
                        lastLatencyMs = res.latencyMs,
                        lastError = if (res.success) null else res.safeErrorMessage
                    )
                )
            }
        }
    }

    private fun pickNextCandidate(
        candidates: List<RouteCandidate>,
        explicitProviderId: String?,
        attemptedKeys: Set<Triple<String, String?, String>>,
        failedModels: Set<Pair<String, String>>,
        failedProviders: Set<String>
    ): RouteCandidate? {
        val remaining = candidates.filter {
            Triple(it.id, it.keyEntityId, it.modelId.lowercase(Locale.US)) !in attemptedKeys
        }
        if (remaining.isEmpty()) return null
        if (explicitProviderId != null) {
            // Manual/explicit provider pinning permits multi-key failover within that provider
            return remaining.firstOrNull()
        }
        // Auto fallback must move to a different eligible model and never repeat the same failed (provider, model)
        val differentModels = remaining.filter { (it.id to it.modelId.lowercase(Locale.US)) !in failedModels }
        if (differentModels.isEmpty()) return null
        return differentModels.firstOrNull { it.id !in failedProviders }
            ?: differentModels.firstOrNull()
    }

    private fun countRemainingDistinctModels(
        candidates: List<RouteCandidate>,
        explicitProviderId: String?,
        attemptedKeys: Set<Triple<String, String?, String>>,
        failedModels: Set<Pair<String, String>>
    ): Int {
        val remaining = candidates.filter {
            Triple(it.id, it.keyEntityId, it.modelId.lowercase(Locale.US)) !in attemptedKeys
        }
        if (explicitProviderId != null) return remaining.size
        return remaining
            .map { it.id to it.modelId.lowercase(Locale.US) }
            .filter { it !in failedModels }
            .distinct()
            .size
    }

    private fun logSafeRouterDiagnostic(
        requestId: String,
        endpoint: String,
        requestedAutoId: String,
        normalizedSelector: String,
        selectedProvider: String,
        selectedModel: String,
        upstreamStatus: Int,
        fallbackAttempts: Int,
        elapsedMs: Long,
        finalStatus: String,
        detail: String? = null
    ) {
        val msg = buildString {
            append("reqId=").append(requestId)
            append(" endpoint=").append(endpoint)
            append(" autoId=").append(requestedAutoId.ifBlank { "<empty>" })
            append(" policy=").append(normalizedSelector.ifBlank { "<empty>" })
            append(" selected=").append(selectedProvider).append('/').append(selectedModel)
            append(" upstreamHttp=").append(upstreamStatus)
            append(" fallbackAttempts=").append(fallbackAttempts)
            append(" elapsedMs=").append(elapsedMs)
            append(" finalStatus=").append(finalStatus)
            if (!detail.isNullOrBlank()) {
                append(" detail=").append(MaskingUtils.sanitizeErrorMessage(detail))
            }
        }
        runCatching { android.util.Log.i("JHGetWayRouter", msg) }
    }

    private suspend fun recordDecision(
        decision: RouterDecisionSnapshot,
        result: ChatExecutionResult,
        candidate: RouteCandidate?,
        deviceName: String,
        endpoint: String,
        routingModeName: String,
        resolvedUsage: ResolvedTokenUsage
    ) {
        _recentDecisions.update { (listOf(decision) + it).take(30) }

        val fallbackAttempts = (decision.attemptCount - 1).coerceAtLeast(0)
        logSafeRouterDiagnostic(
            requestId = decision.requestId,
            endpoint = endpoint,
            requestedAutoId = decision.requestedModelOrAlias,
            normalizedSelector = OpenCodeSelectorIds.normalizeSelectorId(decision.requestedModelOrAlias),
            selectedProvider = decision.selectedProvider,
            selectedModel = decision.selectedModel,
            upstreamStatus = result.statusCode,
            fallbackAttempts = fallbackAttempts,
            elapsedMs = decision.latencyMs,
            finalStatus = decision.finalStatus,
            detail = if (result.success) null else (result.safeErrorMessage ?: result.errorCode)
        )

        val pricing = if (candidate != null && !candidate.isLocal) ProviderPresets.lookupPricing(candidate.modelId) else null
        val estCost = when {
            candidate?.isLocal == true -> 0.0
            pricing != null -> (resolvedUsage.inputTokens / 1_000_000.0) * pricing.inputPer1M +
                (resolvedUsage.outputTokens / 1_000_000.0) * pricing.outputPer1M
            else -> null
        }
        val pricingSource = when {
            result.actualBilledCostUsd != null -> "Provider Billing"
            candidate?.isLocal == true -> "Local Runtime"
            pricing != null -> "JH GetWay Reference Catalog"
            else -> "Unknown"
        }

        val enrichedReason = buildString {
            if (decision.requestedModelOrAlias.isNotBlank()) {
                append("[req=")
                append(decision.requestedModelOrAlias)
                append("] ")
            }
            append(decision.routingReason)
            if (decision.usedDefaultModel) append(" [default]")
            decision.fallbackReason?.takeIf { it.isNotBlank() }?.let {
                append(" | fallback: ")
                append(it)
            }
        }

        db.metricAndLogDao().insertMetric(
            RequestMetricEntity(
                requestId = decision.requestId,
                providerId = candidate?.id ?: "none",
                providerName = decision.selectedProvider,
                modelId = decision.selectedModel,
                deviceName = deviceName,
                endpoint = endpoint,
                statusCode = result.statusCode,
                success = result.success,
                latencyMs = decision.latencyMs,
                inputTokens = resolvedUsage.inputTokens,
                outputTokens = resolvedUsage.outputTokens,
                usageSource = resolvedUsage.source.name,
                estimatedCostUsd = estCost,
                actualBilledCostUsd = result.actualBilledCostUsd,
                pricingSource = pricingSource,
                pricingUpdatedAt = pricing?.updatedAt,
                fallbackAttempts = fallbackAttempts,
                routingMode = routingModeName,
                routingReason = enrichedReason,
                errorCode = result.errorCode,
                safeErrorMessage = MaskingUtils.sanitizeErrorMessage(result.safeErrorMessage)
            )
        )

        db.metricAndLogDao().insertLog(
            ActivityLogEntity(
                category = if (result.success) ActivityCategory.GATEWAY_REQUEST else ActivityCategory.PROVIDER_ERROR,
                relatedEntity = "${decision.selectedProvider} (${decision.selectedModel})",
                status = decision.finalStatus,
                safeMessage = if (result.success) {
                    "$endpoint completed [req=${decision.requestedModelOrAlias.ifBlank { routingModeName }} -> ${decision.selectedProvider}/${decision.selectedModel}] http=${result.statusCode} fallbacks=$fallbackAttempts in ${decision.latencyMs}ms (${resolvedUsage.inputTokens}+${resolvedUsage.outputTokens} tok, ${resolvedUsage.source.name})"
                } else {
                    "$endpoint failed [req=${decision.requestedModelOrAlias.ifBlank { routingModeName }} -> ${decision.selectedProvider}/${decision.selectedModel}] http=${result.statusCode} fallbacks=$fallbackAttempts in ${decision.latencyMs}ms: ${MaskingUtils.sanitizeErrorMessage(result.safeErrorMessage ?: result.errorCode)}"
                }
            )
        )
    }
}
