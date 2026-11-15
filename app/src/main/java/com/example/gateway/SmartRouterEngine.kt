package com.example.gateway

import com.example.core.security.CryptoManager
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
import kotlinx.coroutines.flow.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

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
    val timestamp: Long = System.currentTimeMillis()
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
    val freeTierVerified: Boolean = false
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
    }

    private val roundRobinCounter = AtomicInteger(0)
    private val keyRoundRobinCounter = AtomicInteger(0)

    private val _recentDecisions = MutableStateFlow<List<RouterDecisionSnapshot>>(emptyList())
    val recentDecisions: StateFlow<List<RouterDecisionSnapshot>> = _recentDecisions.asStateFlow()

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

    private fun isProviderCoolingDown(provider: ProviderEntity, nowMs: Long): Boolean {
        if (provider.status != ProviderStatus.ERROR && provider.status != ProviderStatus.RATE_LIMITED) return false
        val checkedAt = provider.lastCheckedAt ?: return false
        return (nowMs - checkedAt) in 0 until COOLDOWN_WINDOW_MS
    }

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
        val (needsVision, needsDocs) = extractMessageCapabilities(messages, requireVision, requiredDocumentMimeTypes)
        val (candidates, reason) = resolveCandidates(
            requestedModelOrAlias = requestedModelOrAlias,
            settings = settings,
            mode = effectiveMode,
            explicitProviderId = explicitProviderId,
            requireVision = needsVision,
            requiredDocumentMimeTypes = needsDocs
        )

        if (candidates.isEmpty()) {
            val diagnostic = diagnoseNoCandidates(
                requestedModelOrAlias = requestedModelOrAlias,
                settings = settings,
                mode = effectiveMode,
                explicitProviderId = explicitProviderId,
                requireVision = needsVision,
                requiredDocumentMimeTypes = needsDocs
            )
            val err = ChatExecutionResult(
                success = false,
                statusCode = 503,
                errorCode = "NO_ELIGIBLE_PROVIDER",
                safeErrorMessage = diagnostic
            )
            val resolved = TokenUsageAccounting.resolveUsage(0, 0, false, messages, "", false)
            val decision = RouterDecisionSnapshot(
                requestId = requestId,
                selectedProvider = "None",
                selectedModel = requestedModelOrAlias.ifBlank { if (effectiveMode == RoutingMode.AUTO_FREE) "Auto-Free" else "Unassigned" },
                routingReason = reason,
                fallbackUsed = false,
                attemptCount = 0,
                latencyMs = 0,
                finalStatus = "FAILED (NO_PROVIDER)",
                resolvedUsage = resolved
            )
            recordDecision(decision, err, null, deviceName, endpoint, effectiveMode.name, resolved)
            return RoutedChatResult(err, decision)
        }

        val allowFallback = fallbackOverride ?: if (explicitProviderId != null) {
            settings.manualModelFallbackEnabled && settings.fallbackEnabled
        } else {
            settings.fallbackEnabled || effectiveMode == RoutingMode.AUTO_FREE
        }
        val maxAttempts = if (allowFallback) (1 + settings.maxRetries).coerceAtMost(candidates.size) else 1
        var attemptCount = 0
        var lastResult = ChatExecutionResult(false, errorCode = "UNKNOWN", safeErrorMessage = "No attempts made")
        var winningCandidate = candidates.first()

        for (i in 0 until maxAttempts) {
            val candidate = candidates[i]
            winningCandidate = candidate
            attemptCount++

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
                timeoutSec = settings.gatewayTimeoutSec
            )
            lastResult = res

            updateProviderAndKeyHealth(candidate, res)
            if (res.success) break

            if (attemptCount > 1 || (res.retryable && i + 1 < maxAttempts)) {
                db.metricAndLogDao().insertLog(
                    ActivityLogEntity(
                        category = ActivityCategory.FALLBACK_EVENT,
                        relatedEntity = candidate.displayName,
                        status = res.errorCode ?: "ERROR",
                        safeMessage = "Fallback triggered from ${candidate.displayName} (${res.safeErrorMessage})"
                    )
                )
            }
            if (!res.retryable) break
        }

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
            latencyMs = lastResult.latencyMs,
            finalStatus = finalStatus,
            resolvedUsage = resolved
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
        val (needsVision, needsDocs) = extractMessageCapabilities(messages, requireVision, requiredDocumentMimeTypes)
        val (candidates, reason) = resolveCandidates(
            requestedModelOrAlias = requestedModelOrAlias,
            settings = settings,
            mode = effectiveMode,
            explicitProviderId = explicitProviderId,
            requireVision = needsVision,
            requiredDocumentMimeTypes = needsDocs
        )

        if (candidates.isEmpty()) {
            val diagnostic = diagnoseNoCandidates(
                requestedModelOrAlias = requestedModelOrAlias,
                settings = settings,
                mode = effectiveMode,
                explicitProviderId = explicitProviderId,
                requireVision = needsVision,
                requiredDocumentMimeTypes = needsDocs
            )
            val err = ChatExecutionResult(false, statusCode = 503, errorCode = "NO_ELIGIBLE_PROVIDER", safeErrorMessage = diagnostic)
            val resolved = TokenUsageAccounting.resolveUsage(0, 0, false, messages, "", false)
            val decision = RouterDecisionSnapshot(
                requestId,
                "None",
                requestedModelOrAlias.ifBlank { if (effectiveMode == RoutingMode.AUTO_FREE) "Auto-Free" else "Unassigned" },
                reason,
                false,
                0,
                0,
                "FAILED",
                resolved
            )
            recordDecision(decision, err, null, deviceName, endpoint, effectiveMode.name, resolved)
            emit(StreamEvent.Failed(err, false) to decision)
            return@flow
        }

        val allowFallback = fallbackOverride ?: if (explicitProviderId != null) {
            settings.manualModelFallbackEnabled && settings.fallbackEnabled
        } else {
            settings.fallbackEnabled || effectiveMode == RoutingMode.AUTO_FREE
        }
        val maxAttempts = if (allowFallback) (1 + settings.maxRetries).coerceAtMost(candidates.size) else 1
        var attempt = 0

        for (i in 0 until maxAttempts) {
            val candidate = candidates[i]
            attempt++
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
                    timeoutSec = settings.gatewayTimeoutSec
                ).collect { event ->
                    when (event) {
                        is StreamEvent.Delta -> emit(event to null)
                        is StreamEvent.Completed -> {
                            finishedCleanly = true
                            latestResult = event.result
                            updateProviderAndKeyHealth(candidate, event.result)
                            val resolved = TokenUsageAccounting.resolveUsage(
                                reportedInputTokens = event.result.inputTokens,
                                reportedOutputTokens = event.result.outputTokens,
                                usageReported = event.result.usageReported,
                                promptMessages = messages,
                                completionText = event.result.content,
                                requestSucceeded = event.result.success
                            )
                            val decision = RouterDecisionSnapshot(
                                requestId = requestId,
                                selectedProvider = candidate.displayName,
                                selectedModel = candidate.modelId,
                                routingReason = reason,
                                fallbackUsed = attempt > 1,
                                attemptCount = attempt,
                                latencyMs = event.result.latencyMs,
                                finalStatus = if (attempt > 1) "SUCCESS (FALLBACK)" else "SUCCESS",
                                resolvedUsage = resolved
                            )
                            recordDecision(decision, event.result, candidate, deviceName, endpoint, effectiveMode.name, resolved)
                            emit(event to decision)
                        }
                        is StreamEvent.Failed -> {
                            latestResult = event.error
                            updateProviderAndKeyHealth(candidate, event.error)
                            // Rule 7.3: Never restart mid-stream after chunks have been emitted
                            if (!event.emittedAnyChunk && event.error.retryable && i + 1 < maxAttempts) {
                                canRetryNext = true
                                db.metricAndLogDao().insertLog(
                                    ActivityLogEntity(
                                        category = ActivityCategory.FALLBACK_EVENT,
                                        relatedEntity = candidate.displayName,
                                        status = event.error.errorCode ?: "STREAM_FAIL",
                                        safeMessage = "Pre-stream fallback from ${candidate.displayName}: ${event.error.safeErrorMessage}"
                                    )
                                )
                            } else {
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
                                    latencyMs = event.error.latencyMs,
                                    finalStatus = "FAILED (${event.error.errorCode ?: event.error.statusCode})",
                                    resolvedUsage = resolved
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
        val providers = db.providerDao().getAllOnce().filter { it.enabled }
        val localServers = db.localAiServerDao().getAllOnce().filter { it.enabled }
        val allModels = db.modelDao().getAllOnce().filter { it.enabled }
        val rules = db.routingRuleDao().getAllOnce().filter { it.enabled }
        val allKeys = db.apiKeyDao().getAllOnce().filter { it.enabled }

        if (mode == RoutingMode.AUTO_FREE && explicitProviderId == null) {
            return resolveAutoFreeCandidates(
                providers = providers,
                localServers = localServers,
                allModels = allModels,
                allKeys = allKeys,
                settings = settings,
                requireVision = requireVision,
                requiredDocumentMimeTypes = requiredDocumentMimeTypes,
                nowMs = nowMs
            )
        }

        val targetAliasOrModel = requestedModelOrAlias.ifBlank { settings.defaultModelId }.trim()
        val matchedRule = rules.firstOrNull { it.modelAlias.equals(targetAliasOrModel, ignoreCase = true) }
        val aliasedModels = allModels.filter {
            it.alias.equals(targetAliasOrModel, ignoreCase = true) ||
                it.modelId.equals(targetAliasOrModel, ignoreCase = true)
        }
        val isAliasRequest = targetAliasOrModel.isBlank() ||
            matchedRule != null ||
            targetAliasOrModel.startsWith("jh-", ignoreCase = true) ||
            aliasedModels.any { it.alias.equals(targetAliasOrModel, ignoreCase = true) }
        val isSpecificModelRequest = !isAliasRequest

        // Identify which enabled & credentialed providers/local servers explicitly host targetAliasOrModel
        val matchedProviderIds = buildSet {
            aliasedModels.forEach { add(it.providerId) }
            providers.forEach { p ->
                if (p.defaultModel.equals(targetAliasOrModel, ignoreCase = true)) add(p.id)
            }
            localServers.forEach { srv ->
                if (srv.defaultModel.equals(targetAliasOrModel, ignoreCase = true)) add("local:${srv.id}")
            }
        }
        val hasEligibleCatalogMatch = isSpecificModelRequest && (
            providers.any { p ->
                p.id in matchedProviderIds && selectKeysForProvider(allKeys.filter { it.providerId == p.id }).isNotEmpty()
            } || localServers.any { srv ->
                "local:${srv.id}" in matchedProviderIds &&
                    (!srv.hasAuth || CryptoManager.decryptResult(srv.encryptedAuthToken).getOrNull() != null)
            }
        )

        val candidates = mutableListOf<RouteCandidate>()

        // Cloud providers
        for (provider in providers) {
            if (explicitProviderId != null && provider.id != explicitProviderId) continue
            if (matchedRule != null && matchedRule.allowedProviderIdsCsv.isNotBlank()) {
                val allowed = matchedRule.allowedProviderIdsCsv.split(",").map { it.trim() }
                if (provider.id !in allowed && provider.name !in allowed) continue
            }
            if (explicitProviderId == null && hasEligibleCatalogMatch && provider.id !in matchedProviderIds) {
                continue
            }

            val selectedKeys = selectKeysForProvider(allKeys.filter { it.providerId == provider.id })
            if (selectedKeys.isEmpty()) continue

            val providerModels = allModels.filter { it.providerId == provider.id }
            val matchedCatalogModel = aliasedModels.firstOrNull { it.providerId == provider.id }
            val resolvedModel = when {
                explicitProviderId != null && isSpecificModelRequest -> matchedCatalogModel
                isSpecificModelRequest -> matchedCatalogModel
                else -> matchedCatalogModel
                    ?: providerModels.firstOrNull { it.isDefault }
                    ?: provider.defaultModel.takeIf { it.isNotBlank() }?.let { defId ->
                        providerModels.firstOrNull { it.modelId.equals(defId, ignoreCase = true) }
                    }
                    ?: providerModels.firstOrNull {
                        it.modelType != ModelType.EMBEDDING && it.modelType != ModelType.IMAGE_GENERATION
                    }
                    ?: providerModels.firstOrNull()
            }
            val modelId = when {
                isSpecificModelRequest && (explicitProviderId != null || !hasEligibleCatalogMatch) ->
                    resolvedModel?.modelId?.ifBlank { null } ?: targetAliasOrModel
                else ->
                    resolvedModel?.modelId?.ifBlank { null }
                        ?: provider.defaultModel.ifBlank { null }
                        ?: targetAliasOrModel.takeIf { it.isNotBlank() && !it.startsWith("jh-", ignoreCase = true) }
                        ?: ""
            }

            if (modelId.isBlank()) continue
            val caps = ProviderAdapterRegistry.adapterFor(provider.apiFormat).capabilitiesForModel(modelId, resolvedModel)
            if (explicitProviderId == null && !candidateSupportsAttachments(caps, requireVision, requiredDocumentMimeTypes)) {
                continue
            }
            val pricing = ProviderPresets.lookupPricing(modelId)
            val estCost = pricing?.let { (it.inputPer1M + it.outputPer1M) / 2.0 }

            for (key in selectedKeys) {
                val decrypted = CryptoManager.decryptResult(key.encryptedKey).getOrNull().orEmpty()
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
                    freeTierVerified = resolvedModel?.freeTierVerified ?: false
                )
            }
        }

        // Local AI servers (strictly isolated: only local server encryptedAuthToken is used; never cloud keys or headers)
        val localCandidates = mutableListOf<RouteCandidate>()
        for (srv in localServers) {
            val localKey = "local:${srv.id}"
            if (explicitProviderId != null && explicitProviderId != localKey) continue
            if (explicitProviderId == null && hasEligibleCatalogMatch && localKey !in matchedProviderIds) {
                continue
            }
            val srvModels = allModels.filter { it.providerId == localKey }
            val matchedLocalModel = aliasedModels.firstOrNull { it.providerId == localKey }
            val resolvedModel = when {
                explicitProviderId != null && isSpecificModelRequest -> matchedLocalModel
                isSpecificModelRequest -> matchedLocalModel
                else -> matchedLocalModel
                    ?: srvModels.firstOrNull { it.isDefault }
                    ?: srv.defaultModel.takeIf { it.isNotBlank() }?.let { defId ->
                        srvModels.firstOrNull { it.modelId.equals(defId, ignoreCase = true) }
                    }
                    ?: srvModels.firstOrNull {
                        it.modelType != ModelType.EMBEDDING && it.modelType != ModelType.IMAGE_GENERATION
                    }
                    ?: srvModels.firstOrNull()
            }
            val modelId = when {
                isSpecificModelRequest && (explicitProviderId != null || !hasEligibleCatalogMatch) ->
                    resolvedModel?.modelId?.ifBlank { null } ?: targetAliasOrModel
                else ->
                    resolvedModel?.modelId?.ifBlank { null }
                        ?: srv.defaultModel.ifBlank { null }
                        ?: ""
            }
            if (modelId.isBlank()) continue
            val authToken = if (srv.hasAuth) {
                CryptoManager.decryptResult(srv.encryptedAuthToken).getOrNull() ?: continue
            } else ""
            val format = if (srv.engineType == LocalEngineType.OLLAMA) ApiFormat.OLLAMA else ApiFormat.OPENAI
            val localCaps = ProviderAdapterRegistry.adapterFor(format).capabilitiesForModel(modelId, resolvedModel)
            if (explicitProviderId == null && !candidateSupportsAttachments(localCaps, requireVision, requiredDocumentMimeTypes)) {
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
                freeTierVerified = false
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
        }

        val baseComparator = compareBy<RouteCandidate> { it.priority }.thenBy { it.lastLatencyMs }
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

    private fun resolveAutoFreeCandidates(
        providers: List<ProviderEntity>,
        localServers: List<LocalAiServerEntity>,
        allModels: List<ModelEntity>,
        allKeys: List<ApiKeyEntity>,
        settings: GatewaySettingsState,
        requireVision: Boolean,
        requiredDocumentMimeTypes: Set<String>,
        nowMs: Long
    ): Pair<List<RouteCandidate>, String> {
        val candidates = mutableListOf<RouteCandidate>()

        for (provider in providers) {
            if (isProviderCoolingDown(provider, nowMs)) continue
            val selectedKeys = selectKeysForProvider(allKeys.filter { it.providerId == provider.id })
            if (selectedKeys.isEmpty()) continue
            val primaryKey = selectedKeys.firstOrNull { key ->
                CryptoManager.decryptResult(key.encryptedKey).getOrNull()?.isNotBlank() == true
            } ?: continue
            val decryptedKey = CryptoManager.decryptResult(primaryKey.encryptedKey).getOrNull().orEmpty()
            if (decryptedKey.isBlank()) continue

            val freeModels = allModels.filter { m ->
                m.providerId == provider.id &&
                    m.enabled &&
                    m.supportsChat &&
                    m.modelType != ModelType.EMBEDDING &&
                    m.modelType != ModelType.IMAGE_GENERATION &&
                    m.accessTier == AccessTier.FREE_TIER &&
                    m.freeTierVerified
            }

            val adapter = ProviderAdapterRegistry.adapterFor(provider.apiFormat)
            for (model in freeModels) {
                val caps = adapter.capabilitiesForModel(model.modelId, model)
                if (!candidateSupportsAttachments(caps, requireVision, requiredDocumentMimeTypes)) continue
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
                    freeTierVerified = model.freeTierVerified
                )
            }
        }

        if (settings.autoFreeIncludeLocal) {
            for (srv in localServers) {
                if (srv.status == ProviderStatus.ERROR || srv.status == ProviderStatus.DISCONNECTED) continue
                val localKey = "local:${srv.id}"
                val authToken = if (srv.hasAuth) {
                    CryptoManager.decryptResult(srv.encryptedAuthToken).getOrNull() ?: continue
                } else ""
                val format = if (srv.engineType == LocalEngineType.OLLAMA) ApiFormat.OLLAMA else ApiFormat.OPENAI
                val adapter = ProviderAdapterRegistry.adapterFor(format)
                val srvModels = allModels.filter { m ->
                    m.providerId == localKey &&
                        m.enabled &&
                        m.supportsChat &&
                        m.modelType != ModelType.EMBEDDING &&
                        m.modelType != ModelType.IMAGE_GENERATION &&
                        m.accessTier == AccessTier.LOCAL
                }
                val modelsToEvaluate = if (srvModels.isNotEmpty()) {
                    srvModels
                } else if (srv.defaultModel.isNotBlank()) {
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
                    if (!candidateSupportsAttachments(caps, requireVision, requiredDocumentMimeTypes)) continue
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
                        freeTierVerified = false
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
        )
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

        if (mode == RoutingMode.AUTO_FREE && explicitProviderId == null) {
            if (requireVision || requiredDocumentMimeTypes.isNotEmpty()) {
                val (unfiltered, _) = resolveCandidates(
                    requestedModelOrAlias = "",
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
        if (!candidate.isLocal) {
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
            candidate.keyEntityId?.let { kid ->
                db.apiKeyDao().getAllOnce().firstOrNull { it.id == kid }?.let { k ->
                    val kStatus = when {
                        res.success -> KeyStatus.ACTIVE
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
        } else {
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
                latencyMs = result.latencyMs,
                inputTokens = resolvedUsage.inputTokens,
                outputTokens = resolvedUsage.outputTokens,
                usageSource = resolvedUsage.source.name,
                estimatedCostUsd = estCost,
                actualBilledCostUsd = result.actualBilledCostUsd,
                pricingSource = pricingSource,
                pricingUpdatedAt = pricing?.updatedAt,
                fallbackAttempts = (decision.attemptCount - 1).coerceAtLeast(0),
                routingMode = routingModeName,
                routingReason = decision.routingReason,
                errorCode = result.errorCode,
                safeErrorMessage = result.safeErrorMessage
            )
        )

        db.metricAndLogDao().insertLog(
            ActivityLogEntity(
                category = if (result.success) ActivityCategory.GATEWAY_REQUEST else ActivityCategory.PROVIDER_ERROR,
                relatedEntity = "${decision.selectedProvider} (${decision.selectedModel})",
                status = decision.finalStatus,
                safeMessage = if (result.success) {
                    "$endpoint completed in ${result.latencyMs}ms (${resolvedUsage.inputTokens}+${resolvedUsage.outputTokens} tok, ${resolvedUsage.source.name})"
                } else {
                    "$endpoint failed: ${result.safeErrorMessage ?: result.errorCode}"
                }
            )
        )
    }
}
