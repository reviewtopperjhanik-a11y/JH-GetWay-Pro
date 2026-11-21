package com.example.gateway

import com.example.core.security.CryptoManager
import com.example.core.security.DeviceScopePolicy
import com.example.core.security.NetworkAddressClassifier
import com.example.data.local.AccessTier
import com.example.data.local.ApiFormat
import com.example.data.local.ApiKeyEntity
import com.example.data.local.CodingAgentEntity
import com.example.data.local.GatewaySettingsState
import com.example.data.local.KeySelectionPolicy
import com.example.data.local.KeyStatus
import com.example.data.local.LocalAiServerEntity
import com.example.data.local.LocalEngineType
import com.example.data.local.LocalRoutingPolicy
import com.example.data.local.ModelEntity
import com.example.data.local.ModelType
import com.example.data.local.ProviderEntity
import com.example.data.local.ProviderStatus
import com.example.data.local.RoutingMode
import com.example.data.local.RoutingRuleEntity
import com.example.network.ProviderPresets
import com.example.network.adapters.ProviderAdapterRegistry
import com.example.network.adapters.ProviderCapabilities
import java.util.Locale

object OpenCodeSelectorIds {
    const val AUTO = "auto"
    const val DEFAULT = "default"
    const val BEST_CODING = "best-coding"
    const val FREE = "free"

    const val STABLE_AUTO_ID = "jh-getway/auto"
    const val DISPLAY_NAME_AUTO = "JH GetWay Auto"
    const val DISPLAY_PROVIDER_NAME = "JH GetWay"
    const val DISPLAY_DESCRIPTION_AUTO = "Automatically selects the best available model with fallback."

    val ALL_VIRTUAL: Set<String> = setOf(AUTO, DEFAULT, BEST_CODING, FREE)

    fun stripGatewayPrefix(raw: String): String {
        val trimmed = raw.trim()
        return when {
            trimmed.startsWith("jhgetway/", ignoreCase = true) ->
                trimmed.substring("jhgetway/".length).trim()
            else -> trimmed
        }
    }

    fun normalizeSelectorId(raw: String): String {
        val stripped = stripGatewayPrefix(raw)
        return when (stripped.lowercase(Locale.US)) {
            "auto", "auto-smart", "jhgetway-auto", "jh-auto", "jh-getway/auto", "jhgetway/auto" -> AUTO
            "default", "default-model", "jhgetway-default", "jh-default", "jh-getway/default" -> DEFAULT
            "best-coding", "best_coding", "best-code", "jhgetway-best-coding", "jh-getway/best-coding" -> BEST_CODING
            "free", "free-models", "auto-free", "auto_free", "jhgetway-free", "jh-free", "jh-getway/free" -> FREE
            else -> stripped
        }
    }

    fun isVirtualSelector(raw: String): Boolean =
        normalizeSelectorId(raw).lowercase(Locale.US) in ALL_VIRTUAL

    fun displayTitleForSelector(selectorId: String): String = when (normalizeSelectorId(selectorId)) {
        AUTO -> DISPLAY_NAME_AUTO
        DEFAULT -> "Default Model — Your configured default"
        BEST_CODING -> "Best Coding — Best eligible coding model"
        FREE -> "Free Models — Verified free models"
        else -> selectorId
    }

    fun descriptionForSelector(selectorId: String): String = when (normalizeSelectorId(selectorId)) {
        AUTO -> DISPLAY_DESCRIPTION_AUTO
        DEFAULT -> "Routes to the canonical default model configured in provider or gateway settings."
        BEST_CODING -> "Selects the highest-ranked eligible coding-capable model."
        FREE -> "Restricts selection strictly to verified free or explicitly allowed local models."
        else -> ""
    }
}

data class CodingCapabilityProfile(
    val isCodingCapable: Boolean,
    val tierScore: Int,
    val provenance: String,
    val familyId: String? = null
)

object ModelCapabilityRegistry {
    const val REGISTRY_PROVENANCE = "JH GetWay Coding Capability Registry (2026.03)"
    const val METADATA_PROVENANCE = "Model Metadata"

    private data class KnownCodingFamily(
        val prefixOrToken: String,
        val tierScore: Int,
        val defaultContextWindow: Int
    )

    // Maintainable table with explicit provenance for models whose remote /models endpoint omits task metadata.
    private val knownCodingFamilies = listOf(
        KnownCodingFamily("claude-opus-4", 98, 200_000),
        KnownCodingFamily("claude-sonnet-4", 96, 200_000),
        KnownCodingFamily("claude-3-7-sonnet", 95, 200_000),
        KnownCodingFamily("claude-3-5-sonnet", 92, 200_000),
        KnownCodingFamily("claude-haiku-4", 82, 200_000),
        KnownCodingFamily("claude-3-5-haiku", 78, 200_000),
        KnownCodingFamily("gpt-5", 97, 256_000),
        KnownCodingFamily("gpt-4.1", 93, 1_000_000),
        KnownCodingFamily("codex", 94, 192_000),
        KnownCodingFamily("o4-mini", 90, 200_000),
        KnownCodingFamily("o3", 91, 200_000),
        KnownCodingFamily("gpt-4o", 86, 128_000),
        KnownCodingFamily("gpt-4o-mini", 76, 128_000),
        KnownCodingFamily("gemini-2.5-pro", 94, 1_000_000),
        KnownCodingFamily("gemini-2.5-flash", 84, 1_000_000),
        KnownCodingFamily("gemini-2.0-flash", 74, 1_000_000),
        KnownCodingFamily("deepseek-coder", 90, 128_000),
        KnownCodingFamily("deepseek-r1", 89, 128_000),
        KnownCodingFamily("deepseek-v3", 88, 128_000),
        KnownCodingFamily("deepseek-chat", 83, 64_000),
        KnownCodingFamily("qwen3-coder", 91, 256_000),
        KnownCodingFamily("qwen2.5-coder", 88, 128_000),
        KnownCodingFamily("qwen-2.5-coder", 88, 128_000),
        KnownCodingFamily("codestral", 89, 256_000),
        KnownCodingFamily("devstral", 90, 128_000),
        KnownCodingFamily("qwq", 82, 32_768),
        KnownCodingFamily("starcoder", 80, 16_384),
        KnownCodingFamily("codellama", 75, 16_384),
        KnownCodingFamily("codegemma", 72, 8_192),
        KnownCodingFamily("command-r-plus", 76, 128_000)
    )

    fun isNonChatModel(modelType: ModelType, modelId: String, supportsChat: Boolean): Boolean {
        if (!supportsChat) return true
        if (modelType == ModelType.EMBEDDING || modelType == ModelType.IMAGE_GENERATION || modelType == ModelType.AUDIO) {
            return true
        }
        val lower = modelId.lowercase(Locale.US)
        return lower.contains("embed") ||
            lower.contains("whisper") ||
            lower.contains("tts-") ||
            lower.startsWith("tts") ||
            lower.contains("dall-e") ||
            lower.contains("imagen") ||
            lower.contains("flux")
    }

    fun assessCodingCapability(model: ModelEntity): CodingCapabilityProfile {
        if (isNonChatModel(model.modelType, model.modelId, model.supportsChat)) {
            return CodingCapabilityProfile(
                isCodingCapable = false,
                tierScore = 0,
                provenance = METADATA_PROVENANCE
            )
        }
        val capTokens = model.capabilitiesCsv
            .split(",", ";", "|")
            .map { it.trim().uppercase(Locale.US) }
            .filter { it.isNotEmpty() }
            .toSet()

        val hasMetadataCoding = model.modelType == ModelType.CODING ||
            "CODING" in capTokens ||
            "CODE" in capTokens

        val lower = model.modelId.lowercase(Locale.US)
        val matchedFamily = knownCodingFamilies.firstOrNull { fam ->
            lower.contains(fam.prefixOrToken)
        }

        return when {
            hasMetadataCoding && matchedFamily != null -> CodingCapabilityProfile(
                isCodingCapable = true,
                tierScore = maxOf(88, matchedFamily.tierScore),
                provenance = "$METADATA_PROVENANCE + $REGISTRY_PROVENANCE",
                familyId = matchedFamily.prefixOrToken
            )
            hasMetadataCoding -> CodingCapabilityProfile(
                isCodingCapable = true,
                tierScore = 88,
                provenance = METADATA_PROVENANCE
            )
            matchedFamily != null -> CodingCapabilityProfile(
                isCodingCapable = true,
                tierScore = matchedFamily.tierScore,
                provenance = REGISTRY_PROVENANCE,
                familyId = matchedFamily.prefixOrToken
            )
            else -> CodingCapabilityProfile(
                isCodingCapable = false,
                tierScore = 0,
                provenance = "Unverified for coding"
            )
        }
    }

    fun effectiveContextWindow(model: ModelEntity): Int {
        model.contextWindow?.takeIf { it > 0 }?.let { return it }
        val lower = model.modelId.lowercase(Locale.US)
        return knownCodingFamilies.firstOrNull { lower.contains(it.prefixOrToken) }?.defaultContextWindow ?: 32_768
    }

    fun estimateCodingScore(
        model: ModelEntity,
        profile: CodingCapabilityProfile,
        caps: ProviderCapabilities,
        providerStatus: ProviderStatus,
        providerPriority: Int,
        lastLatencyMs: Long
    ): Int {
        if (!profile.isCodingCapable) return -1
        var score = profile.tierScore * 10
        if (caps.supportsTools) score += 120
        if (caps.supportsJsonOutput) score += 45
        if (caps.supportsStreaming) score += 35
        val ctx = effectiveContextWindow(model)
        score += when {
            ctx >= 500_000 -> 60
            ctx >= 180_000 -> 45
            ctx >= 100_000 -> 30
            ctx >= 32_000 -> 15
            else -> 0
        }
        score += when (providerStatus) {
            ProviderStatus.CONNECTED -> 50
            ProviderStatus.UNKNOWN, ProviderStatus.CHECKING -> 25
            ProviderStatus.DISCONNECTED -> -40
            ProviderStatus.RATE_LIMITED, ProviderStatus.ERROR -> -200
            ProviderStatus.DISABLED -> -500
        }
        score -= providerPriority.coerceIn(0, 50) * 2
        if (lastLatencyMs in 1..1500) score += 15
        if (model.isDefault) score += 10
        return score
    }
}

data class CuratedModelEntry(
    val id: String,
    val displayName: String,
    val ownedBy: String,
    val category: String, // "selector" | "recommended" | "alias" | "catalog"
    val providerId: String? = null,
    val resolvedUpstreamModelId: String? = null,
    val supportsTools: Boolean = true,
    val supportsVision: Boolean = false,
    val supportsStreaming: Boolean = true,
    val contextWindow: Int? = null,
    val accessTier: AccessTier = AccessTier.UNKNOWN,
    val freeTierVerified: Boolean = false,
    val isCodingCapable: Boolean = false,
    val isDefault: Boolean = false,
    val description: String = ""
)

data class CuratedModelCatalog(
    val entries: List<CuratedModelEntry>,
    val totalEligibleModelsCount: Int,
    val totalStoredModelsCount: Int,
    val autoAvailable: Boolean,
    val autoResolvedTarget: String?,
    val defaultAvailable: Boolean,
    val defaultResolvedTarget: String?,
    val defaultFallbackUsed: Boolean,
    val bestCodingAvailable: Boolean,
    val bestCodingResolvedTarget: String?,
    val verifiedFreeCount: Int,
    val freeUnavailableReason: String?,
    val codingUnavailableReason: String?
)

data class EligibleModelRecord(
    val providerId: String,
    val providerName: String,
    val apiFormat: ApiFormat,
    val isLocal: Boolean,
    val providerPriority: Int,
    val providerStatus: ProviderStatus,
    val lastCheckedAt: Long?,
    val lastLatencyMs: Long,
    val model: ModelEntity,
    val capabilities: ProviderCapabilities,
    val codingProfile: CodingCapabilityProfile,
    val codingScore: Int,
    val effectiveCostPer1M: Double?,
    val isSynthesizedFromDefault: Boolean = false,
    val cooldownUntilMs: Long? = null,
    val effectiveStatus: ProviderStatus = providerStatus
)

object OpenCodeModelCatalogResolver {
    const val DEFAULT_RECOMMENDED_LIMIT = 12
    const val DEFAULT_MAX_PER_PROVIDER = 3

    fun isVerifiedFree(model: ModelEntity, includeLocal: Boolean, isLocalServer: Boolean): Boolean {
        if (ModelCapabilityRegistry.isNonChatModel(model.modelType, model.modelId, model.supportsChat)) {
            return false
        }
        if (isLocalServer || model.isLocal || model.accessTier == AccessTier.LOCAL) {
            return includeLocal
        }
        // Strictly reject AccessTier.UNKNOWN and AccessTier.PAID
        return model.accessTier == AccessTier.FREE_TIER && model.freeTierVerified
    }

    fun resolveEligibleRecords(
        providers: List<ProviderEntity>,
        localServers: List<LocalAiServerEntity>,
        allModels: List<ModelEntity>,
        allKeys: List<ApiKeyEntity>,
        settings: GatewaySettingsState,
        nowMs: Long = System.currentTimeMillis()
    ): Pair<List<EligibleModelRecord>, EligibleModelRecord?> {
        val disabledModelKeys = HashSet<String>()
        val storedModelKeys = HashSet<String>(allModels.size)
        val enabledModelsByProvider = HashMap<String, MutableList<ModelEntity>>()
        for (m in allModels) {
            val compositeKey = "${m.providerId}::${m.modelId.lowercase(Locale.US)}"
            storedModelKeys += compositeKey
            if (!m.enabled) {
                disabledModelKeys += compositeKey
            } else {
                enabledModelsByProvider.getOrPut(m.providerId) { mutableListOf() }.add(m)
            }
        }
        val keysByProvider = allKeys.groupBy { it.providerId }
        val decryptedKeyValidCache = HashMap<String, Boolean>(allKeys.size)
        fun isValidApiKey(key: ApiKeyEntity): Boolean {
            if (!key.enabled ||
                key.status == KeyStatus.DISABLED ||
                key.status == KeyStatus.INVALID ||
                key.status == KeyStatus.EXPIRED
            ) {
                return false
            }
            return decryptedKeyValidCache.getOrPut(key.id) {
                CryptoManager.decryptResult(key.encryptedKey).getOrNull()?.isNotBlank() == true
            }
        }

        val eligibleRecords = mutableListOf<EligibleModelRecord>()

        // Cloud providers
        val includeCloud = settings.localRoutingPolicy != LocalRoutingPolicy.LOCAL_ONLY
        if (includeCloud) {
            for (provider in providers) {
                if (!provider.enabled) continue
                val cleanUrl = provider.baseUrl.trim()
                if (cleanUrl.isBlank()) continue
                val allowLocalHttp = settings.localNetworkAccessAllowed
                if (!allowLocalHttp &&
                    cleanUrl.startsWith("http://", ignoreCase = true) &&
                    !NetworkAddressClassifier.isLoopbackAddress(NetworkAddressClassifier.parseHost(cleanUrl).orEmpty())
                ) {
                    continue
                }
                val providerKeys = keysByProvider[provider.id].orEmpty()
                if (providerKeys.none { isValidApiKey(it) }) continue

                val adapter = ProviderAdapterRegistry.adapterFor(provider.apiFormat)
                val providerModels = enabledModelsByProvider[provider.id]
                    .orEmpty()
                    .distinctBy { it.modelId.lowercase(Locale.US) }
                    .toMutableList()

                val defModelId = provider.defaultModel.trim()
                if (defModelId.isNotBlank() &&
                    !disabledModelKeys.contains("${provider.id}::${defModelId.lowercase(Locale.US)}") &&
                    providerModels.none { it.modelId.equals(defModelId, ignoreCase = true) }
                ) {
                    val pricing = ProviderPresets.lookupPricing(defModelId)
                    val mType = ProviderPresets.inferModelType(defModelId)
                    if (!ModelCapabilityRegistry.isNonChatModel(mType, defModelId, true)) {
                        val isCatalogFree = defModelId.endsWith(":free", ignoreCase = true)
                        val hasPosPrice = pricing != null && (pricing.inputPer1M > 0.0 || pricing.outputPer1M > 0.0)
                        providerModels += ModelEntity(
                            id = "${provider.id}::$defModelId",
                            modelId = defModelId,
                            displayName = defModelId,
                            providerId = provider.id,
                            providerName = provider.name,
                            modelType = mType,
                            inputPricePer1M = pricing?.inputPer1M,
                            outputPricePer1M = pricing?.outputPer1M,
                            pricingUpdatedAt = pricing?.updatedAt,
                            isDefault = provider.id == settings.defaultProviderId &&
                                defModelId.equals(settings.defaultModelId, ignoreCase = true),
                            enabled = true,
                            accessTier = when {
                                isCatalogFree -> AccessTier.FREE_TIER
                                hasPosPrice -> AccessTier.PAID
                                else -> AccessTier.UNKNOWN
                            },
                            freeTierVerified = isCatalogFree,
                            freeTierSource = if (isCatalogFree) "Provider Catalog (:free)" else "",
                            supportsChat = true,
                            supportsVision = adapter.capabilitiesForModel(defModelId, null).supportsVision
                        )
                    }
                }

                val effectiveStatus = effectiveRoutingStatus(
                    status = provider.status,
                    cooldownUntilMs = provider.cooldownUntilMs,
                    lastCheckedAt = provider.lastCheckedAt,
                    isLocal = false,
                    nowMs = nowMs
                )
                for (model in providerModels) {
                    if (ModelCapabilityRegistry.isNonChatModel(model.modelType, model.modelId, model.supportsChat)) {
                        continue
                    }
                    val caps = adapter.capabilitiesForModel(model.modelId, model)
                    val codingProfile = ModelCapabilityRegistry.assessCodingCapability(model)
                    val codingScore = ModelCapabilityRegistry.estimateCodingScore(
                        model = model,
                        profile = codingProfile,
                        caps = caps,
                        providerStatus = effectiveStatus,
                        providerPriority = provider.priority,
                        lastLatencyMs = provider.lastLatencyMs ?: 5000L
                    )
                    val pricing = ProviderPresets.lookupPricing(model.modelId)
                    val avgCost = when {
                        model.accessTier == AccessTier.FREE_TIER && model.freeTierVerified -> 0.0
                        model.inputPricePer1M != null && model.outputPricePer1M != null ->
                            (model.inputPricePer1M + model.outputPricePer1M) / 2.0
                        pricing != null -> (pricing.inputPer1M + pricing.outputPer1M) / 2.0
                        else -> null
                    }
                    val compositeKey = "${provider.id}::${model.modelId.lowercase(Locale.US)}"
                    eligibleRecords += EligibleModelRecord(
                        providerId = provider.id,
                        providerName = provider.name,
                        apiFormat = provider.apiFormat,
                        isLocal = false,
                        providerPriority = provider.priority,
                        providerStatus = provider.status,
                        lastCheckedAt = provider.lastCheckedAt,
                        lastLatencyMs = provider.lastLatencyMs ?: 5000L,
                        model = model,
                        capabilities = caps,
                        codingProfile = codingProfile,
                        codingScore = codingScore,
                        effectiveCostPer1M = avgCost,
                        isSynthesizedFromDefault = !storedModelKeys.contains(compositeKey),
                        cooldownUntilMs = provider.cooldownUntilMs,
                        effectiveStatus = effectiveStatus
                    )
                }
            }
        }

        // Local AI servers
        for (srv in localServers) {
            if (!srv.enabled) continue
            val localId = "local:${srv.id}"
            val authOk = !srv.hasAuth || CryptoManager.decryptResult(srv.encryptedAuthToken).getOrNull() != null
            if (!authOk) continue
            val cleanUrl = srv.serverUrl.trim()
            if (cleanUrl.isBlank()) continue
            if (!srv.isPrivateNetwork && cleanUrl.startsWith("http://", ignoreCase = true)) continue
            if (srv.isPrivateNetwork &&
                !NetworkAddressClassifier.isLoopbackAddress(NetworkAddressClassifier.parseHost(cleanUrl).orEmpty()) &&
                !settings.localNetworkAccessAllowed
            ) {
                continue
            }
            val format = if (srv.engineType == LocalEngineType.OLLAMA) ApiFormat.OLLAMA else ApiFormat.OPENAI
            val adapter = ProviderAdapterRegistry.adapterFor(format)
            val srvModels = enabledModelsByProvider[localId]
                .orEmpty()
                .distinctBy { it.modelId.lowercase(Locale.US) }
                .toMutableList()

            val defModel = srv.defaultModel.trim()
            if (defModel.isNotBlank() &&
                !disabledModelKeys.contains("$localId::${defModel.lowercase(Locale.US)}") &&
                srvModels.none { it.modelId.equals(defModel, ignoreCase = true) }
            ) {
                val mType = ProviderPresets.inferModelType(defModel)
                if (!ModelCapabilityRegistry.isNonChatModel(mType, defModel, true)) {
                    val caps = adapter.capabilitiesForModel(defModel, null)
                    srvModels += ModelEntity(
                        id = "$localId::$defModel",
                        modelId = defModel,
                        displayName = defModel,
                        providerId = localId,
                        providerName = srv.name,
                        modelType = mType,
                        isLocal = true,
                        enabled = true,
                        accessTier = AccessTier.LOCAL,
                        freeTierVerified = false,
                        freeTierSource = "Local Runtime",
                        supportsTools = caps.supportsTools,
                        supportsStreaming = caps.supportsStreaming,
                        supportsChat = true,
                        supportsVision = caps.supportsVision
                    )
                }
            }

            val effectiveLocalStatus = effectiveRoutingStatus(
                status = srv.status,
                cooldownUntilMs = srv.cooldownUntilMs,
                lastCheckedAt = srv.lastCheckedAt,
                isLocal = true,
                nowMs = nowMs
            )
            for (model in srvModels) {
                if (ModelCapabilityRegistry.isNonChatModel(model.modelType, model.modelId, model.supportsChat)) {
                    continue
                }
                val caps = adapter.capabilitiesForModel(model.modelId, model)
                val codingProfile = ModelCapabilityRegistry.assessCodingCapability(model)
                val codingScore = ModelCapabilityRegistry.estimateCodingScore(
                    model = model,
                    profile = codingProfile,
                    caps = caps,
                    providerStatus = effectiveLocalStatus,
                    providerPriority = if (settings.localRoutingPolicy == LocalRoutingPolicy.CLOUD_FIRST) 25 else 5,
                    lastLatencyMs = srv.lastLatencyMs ?: 1000L
                )
                eligibleRecords += EligibleModelRecord(
                    providerId = localId,
                    providerName = srv.name,
                    apiFormat = format,
                    isLocal = true,
                    providerPriority = if (settings.localRoutingPolicy == LocalRoutingPolicy.CLOUD_FIRST) 25 else 5,
                    providerStatus = srv.status,
                    lastCheckedAt = srv.lastCheckedAt,
                    lastLatencyMs = srv.lastLatencyMs ?: 1000L,
                    model = model,
                    capabilities = caps,
                    codingProfile = codingProfile,
                    codingScore = codingScore,
                    effectiveCostPer1M = 0.0,
                    cooldownUntilMs = srv.cooldownUntilMs,
                    effectiveStatus = effectiveLocalStatus
                )
            }
        }

        val canonicalDefault = resolveCanonicalDefaultRecord(
            eligibleRecords = eligibleRecords,
            providers = providers,
            localServers = localServers,
            settings = settings,
            nowMs = nowMs
        )
        return eligibleRecords to canonicalDefault
    }

    fun isStatusCoolingDown(
        status: ProviderStatus,
        cooldownUntilMs: Long?,
        lastCheckedAt: Long?,
        isLocal: Boolean,
        nowMs: Long
    ): Boolean {
        val inFailureState = status == ProviderStatus.ERROR ||
            status == ProviderStatus.RATE_LIMITED ||
            (isLocal && status == ProviderStatus.DISCONNECTED)
        if (!inFailureState) return false
        cooldownUntilMs?.let { until -> return nowMs < until }
        val checked = lastCheckedAt ?: return isLocal
        return (nowMs - checked) in 0 until SmartRouterEngine.COOLDOWN_WINDOW_MS
    }

    fun effectiveRoutingStatus(
        status: ProviderStatus,
        cooldownUntilMs: Long?,
        lastCheckedAt: Long?,
        isLocal: Boolean,
        nowMs: Long
    ): ProviderStatus {
        if (isStatusCoolingDown(status, cooldownUntilMs, lastCheckedAt, isLocal, nowMs)) {
            return status
        }
        return when {
            status == ProviderStatus.RATE_LIMITED -> ProviderStatus.UNKNOWN
            status == ProviderStatus.ERROR && (cooldownUntilMs != null || lastCheckedAt != null) ->
                ProviderStatus.UNKNOWN
            isLocal && status == ProviderStatus.DISCONNECTED && (cooldownUntilMs != null || lastCheckedAt != null) ->
                ProviderStatus.UNKNOWN
            else -> status
        }
    }

    fun isRecordCoolingDown(record: EligibleModelRecord, nowMs: Long): Boolean =
        isStatusCoolingDown(
            status = record.providerStatus,
            cooldownUntilMs = record.cooldownUntilMs,
            lastCheckedAt = record.lastCheckedAt,
            isLocal = record.isLocal,
            nowMs = nowMs
        )

    private fun healthOrder(status: ProviderStatus): Int = when (status) {
        ProviderStatus.CONNECTED -> 0
        ProviderStatus.UNKNOWN, ProviderStatus.CHECKING -> 1
        ProviderStatus.DISCONNECTED -> 2
        ProviderStatus.RATE_LIMITED, ProviderStatus.ERROR -> 3
        ProviderStatus.DISABLED -> 4
    }

    fun resolveCanonicalDefaultRecord(
        eligibleRecords: List<EligibleModelRecord>,
        providers: List<ProviderEntity>,
        localServers: List<LocalAiServerEntity>,
        settings: GatewaySettingsState,
        nowMs: Long = System.currentTimeMillis()
    ): EligibleModelRecord? {
        if (eligibleRecords.isEmpty()) return null
        val availableRecords = eligibleRecords.filter { !isRecordCoolingDown(it, nowMs) }
            .ifEmpty { eligibleRecords }

        // 1. Canonical global default in GatewaySettingsState (providerId + modelId)
        val defModelId = settings.defaultModelId.trim()
        val defProvId = settings.defaultProviderId.trim()
        if (defModelId.isNotBlank()) {
            availableRecords.firstOrNull { rec ->
                (defProvId.isBlank() || rec.providerId == defProvId) &&
                    rec.model.modelId.equals(defModelId, ignoreCase = true)
            }?.let { return it }
        }

        // 2. ModelEntity.isDefault
        availableRecords.firstOrNull { it.model.isDefault }?.let { return it }

        // 3. Highest-priority healthy ProviderEntity.defaultModel
        val orderedProviders = providers.filter { it.enabled }
            .sortedWith(
                compareBy<ProviderEntity> {
                    healthOrder(
                        effectiveRoutingStatus(
                            status = it.status,
                            cooldownUntilMs = it.cooldownUntilMs,
                            lastCheckedAt = it.lastCheckedAt,
                            isLocal = false,
                            nowMs = nowMs
                        )
                    )
                }.thenBy { it.priority }.thenBy { it.lastLatencyMs ?: 5000L }
            )
        for (p in orderedProviders) {
            val pDef = p.defaultModel.trim()
            if (pDef.isNotBlank()) {
                availableRecords.firstOrNull {
                    it.providerId == p.id && it.model.modelId.equals(pDef, ignoreCase = true)
                }?.let { return it }
            }
        }

        // 4. LocalAiServerEntity.defaultModel if local server enabled
        for (srv in localServers.filter { it.enabled }) {
            val sDef = srv.defaultModel.trim()
            if (sDef.isNotBlank()) {
                availableRecords.firstOrNull {
                    it.providerId == "local:${srv.id}" && it.model.modelId.equals(sDef, ignoreCase = true)
                }?.let { return it }
            }
        }
        return null
    }

    fun buildCuratedCatalog(
        providers: List<ProviderEntity>,
        localServers: List<LocalAiServerEntity>,
        allModels: List<ModelEntity>,
        allKeys: List<ApiKeyEntity>,
        routingRules: List<RoutingRuleEntity>,
        settings: GatewaySettingsState,
        allowedModelsCsv: String = "",
        codingAgent: CodingAgentEntity? = null,
        includeAllEligible: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ): CuratedModelCatalog {
        val (eligible, canonicalDefault) = resolveEligibleRecords(
            providers = providers,
            localServers = localServers,
            allModels = allModels,
            allKeys = allKeys,
            settings = settings,
            nowMs = nowMs
        )

        val freeOnlyAuto = settings.defaultRoutingMode == RoutingMode.AUTO_FREE || !settings.autoAllowPaidModels
        val autoEligiblePool = if (freeOnlyAuto) {
            eligible.filter { isVerifiedFree(it.model, settings.autoFreeIncludeLocal, it.isLocal) }
        } else {
            eligible
        }

        val verifiedFreeRecords = eligible
            .filter { isVerifiedFree(it.model, settings.autoFreeIncludeLocal, it.isLocal) }
            .sortedWith(rankComparator(nowMs, settings))

        val codingRecords = autoEligiblePool
            .filter { it.codingProfile.isCodingCapable }
            .sortedWith(
                compareByDescending<EligibleModelRecord> { !isRecordCoolingDown(it, nowMs) }
                    .thenByDescending { it.codingScore }
                    .thenBy { it.providerPriority }
                    .thenBy { it.lastLatencyMs }
            )

        val generalRanked = autoEligiblePool.sortedWith(rankComparator(nowMs, settings))
        val hasExplicitConfiguredDefault = settings.defaultModelId.isNotBlank() ||
            allModels.any { it.isDefault } ||
            providers.any { it.enabled && it.defaultModel.isNotBlank() }
        val defaultResolved = canonicalDefault ?: eligible.sortedWith(rankComparator(nowMs, settings)).firstOrNull()
        val defaultFallbackUsed = hasExplicitConfiguredDefault &&
            defaultResolved != null &&
            defaultResolved != canonicalDefault

        val activePolicy = OpenCodeSelectorIds.normalizeSelectorId(settings.openCodeSelectionMode)
        val effectiveAutoDefault = when (activePolicy) {
            OpenCodeSelectorIds.DEFAULT -> canonicalDefault ?: generalRanked.firstOrNull()
            OpenCodeSelectorIds.BEST_CODING -> codingRecords.firstOrNull() ?: canonicalDefault ?: generalRanked.firstOrNull()
            OpenCodeSelectorIds.FREE -> verifiedFreeRecords.firstOrNull()
            else -> when {
                settings.preferConfiguredDefaultInAuto &&
                    canonicalDefault != null &&
                    (!freeOnlyAuto || isVerifiedFree(canonicalDefault.model, settings.autoFreeIncludeLocal, canonicalDefault.isLocal)) &&
                    !isRecordCoolingDown(canonicalDefault, nowMs) -> canonicalDefault
                else -> generalRanked.firstOrNull()
            }
        }

        val entries = mutableListOf<CuratedModelEntry>()
        val seenIds = linkedSetOf<String>()

        fun tryAddEntry(entry: CuratedModelEntry, resolvedRealIdForAllowlist: String?) {
            val idClean = entry.id.trim()
            if (idClean.isBlank()) return
            val allowed = DeviceScopePolicy.isModelAllowed(
                allowedModelsCsv = allowedModelsCsv,
                requestedModelOrAlias = idClean,
                resolvedModelId = resolvedRealIdForAllowlist
            )
            if (!allowed) return
            if (seenIds.add(idClean.lowercase(Locale.US))) {
                entries += entry
            }
        }

        val hasExplicitAllowlist = allowedModelsCsv.isNotBlank() && allowedModelsCsv.trim() != "*"

        // 1. Primary Gateway-owned Auto entry ("jh-getway/auto") — single entry shown by default in OpenCode
        val stableAutoEntry = CuratedModelEntry(
            id = OpenCodeSelectorIds.STABLE_AUTO_ID,
            displayName = OpenCodeSelectorIds.DISPLAY_NAME_AUTO,
            ownedBy = OpenCodeSelectorIds.DISPLAY_PROVIDER_NAME,
            category = "selector",
            providerId = effectiveAutoDefault?.providerId,
            resolvedUpstreamModelId = effectiveAutoDefault?.model?.modelId,
            supportsTools = effectiveAutoDefault?.capabilities?.supportsTools ?: true,
            supportsVision = autoEligiblePool.any { it.capabilities.supportsVision },
            supportsStreaming = true,
            contextWindow = effectiveAutoDefault?.let { ModelCapabilityRegistry.effectiveContextWindow(it.model) } ?: 128_000,
            accessTier = effectiveAutoDefault?.model?.accessTier ?: AccessTier.UNKNOWN,
            freeTierVerified = effectiveAutoDefault?.model?.freeTierVerified ?: false,
            isCodingCapable = effectiveAutoDefault?.codingProfile?.isCodingCapable ?: codingRecords.isNotEmpty(),
            isDefault = true,
            description = OpenCodeSelectorIds.DISPLAY_DESCRIPTION_AUTO
        )

        if (!hasExplicitAllowlist || DeviceScopePolicy.isModelAllowed(allowedModelsCsv, OpenCodeSelectorIds.STABLE_AUTO_ID, null)) {
            tryAddEntry(stableAutoEntry, resolvedRealIdForAllowlist = null)
        }

        // 2. Expose upstream / allowlisted models ONLY when:
        //    (a) explicitly opted-in via includeAllEligible = true (?full=true / ?all=true / ?manual=true), OR
        //    (b) the client device/agent uses an explicit restricted model allowlist (legacy backward compatibility).
        if (includeAllEligible || hasExplicitAllowlist) {
            val recordsToExpose = if (includeAllEligible) {
                eligible.sortedWith(rankComparator(nowMs, settings))
            } else {
                val allowedTokens = DeviceScopePolicy.parseAllowedModels(allowedModelsCsv)
                eligible.filter { rec ->
                    val rawId = rec.model.modelId.trim()
                    val alias = rec.model.alias.trim()
                    allowedTokens.any { it.equals(rawId, true) || (alias.isNotBlank() && it.equals(alias, true)) }
                }.sortedWith(rankComparator(nowMs, settings))
            }

            recordsToExpose.forEach { rec ->
                val rawId = rec.model.modelId.trim()
                val label = "${rec.providerName} / ${rec.model.displayName.ifBlank { rawId }}"
                tryAddEntry(
                    CuratedModelEntry(
                        id = rawId,
                        displayName = label,
                        ownedBy = rec.providerName,
                        category = if (includeAllEligible) "catalog" else "recommended",
                        providerId = rec.providerId,
                        resolvedUpstreamModelId = rawId,
                        supportsTools = rec.capabilities.supportsTools,
                        supportsVision = rec.capabilities.supportsVision,
                        supportsStreaming = rec.capabilities.supportsStreaming,
                        contextWindow = ModelCapabilityRegistry.effectiveContextWindow(rec.model),
                        accessTier = rec.model.accessTier,
                        freeTierVerified = rec.model.freeTierVerified,
                        isCodingCapable = rec.codingProfile.isCodingCapable,
                        isDefault = rec == canonicalDefault
                    ),
                    resolvedRealIdForAllowlist = rawId
                )
                val alias = rec.model.alias.trim()
                if (alias.isNotBlank() && !alias.equals(rawId, ignoreCase = true) && !OpenCodeSelectorIds.isVirtualSelector(alias)) {
                    tryAddEntry(
                        CuratedModelEntry(
                            id = alias,
                            displayName = "$alias ($label)",
                            ownedBy = rec.providerName,
                            category = "alias",
                            providerId = rec.providerId,
                            resolvedUpstreamModelId = rawId,
                            supportsTools = rec.capabilities.supportsTools,
                            supportsVision = rec.capabilities.supportsVision,
                            supportsStreaming = rec.capabilities.supportsStreaming,
                            contextWindow = ModelCapabilityRegistry.effectiveContextWindow(rec.model),
                            accessTier = rec.model.accessTier,
                            freeTierVerified = rec.model.freeTierVerified,
                            isCodingCapable = rec.codingProfile.isCodingCapable
                        ),
                        resolvedRealIdForAllowlist = rawId
                    )
                }
            }

            if (eligible.isNotEmpty() && !hasExplicitAllowlist) {
                routingRules.filter { it.enabled }.forEach { rule ->
                    val alias = rule.modelAlias.trim()
                    if (alias.isNotBlank() && !OpenCodeSelectorIds.isVirtualSelector(alias)) {
                        tryAddEntry(
                            CuratedModelEntry(
                                id = alias,
                                displayName = "${rule.name} ($alias)",
                                ownedBy = "jhgetway-router",
                                category = "alias"
                            ),
                            resolvedRealIdForAllowlist = null
                        )
                    }
                }
            }
            if (eligible.isNotEmpty()) {
                codingAgent?.let { ag ->
                    val primaryAlias = ag.modelAlias.trim()
                    if (primaryAlias.isNotBlank() && !OpenCodeSelectorIds.isVirtualSelector(primaryAlias)) {
                        tryAddEntry(
                            CuratedModelEntry(
                                id = primaryAlias,
                                displayName = "${ag.name} ($primaryAlias)",
                                ownedBy = "JH GetWay",
                                category = "alias",
                                supportsTools = ag.toolCalling,
                                supportsStreaming = ag.streaming
                            ),
                            resolvedRealIdForAllowlist = null
                        )
                    }
                }
            }
        }

        val freeUnavailableReason = when {
            verifiedFreeRecords.isNotEmpty() -> null
            eligible.isEmpty() -> "No eligible providers configured."
            else -> "No verified free models found. Mark free models in Models or enable local models."
        }
        val codingUnavailableReason = when {
            codingRecords.isNotEmpty() -> null
            eligible.isEmpty() -> "No eligible providers configured."
            else -> "No verified coding-capable models found in metadata or registry."
        }

        return CuratedModelCatalog(
            entries = entries,
            totalEligibleModelsCount = eligible.distinctBy { "${it.providerId}::${it.model.modelId.lowercase(Locale.US)}" }.size,
            totalStoredModelsCount = allModels.size,
            autoAvailable = autoEligiblePool.isNotEmpty(),
            autoResolvedTarget = effectiveAutoDefault?.let { "${it.providerName} / ${it.model.modelId}" },
            defaultAvailable = defaultResolved != null,
            defaultResolvedTarget = defaultResolved?.let { "${it.providerName} / ${it.model.modelId}" },
            defaultFallbackUsed = defaultFallbackUsed,
            bestCodingAvailable = codingRecords.isNotEmpty(),
            bestCodingResolvedTarget = codingRecords.firstOrNull()?.let { "${it.providerName} / ${it.model.modelId}" },
            verifiedFreeCount = verifiedFreeRecords.size,
            freeUnavailableReason = freeUnavailableReason,
            codingUnavailableReason = codingUnavailableReason
        )
    }

    private fun selectRecommendedRecords(
        eligible: List<EligibleModelRecord>,
        canonicalDefault: EligibleModelRecord?,
        topCoding: List<EligibleModelRecord>,
        topFree: List<EligibleModelRecord>,
        nowMs: Long,
        settings: GatewaySettingsState
    ): List<EligibleModelRecord> {
        val selected = mutableListOf<EligibleModelRecord>()
        val selectedModelIds = mutableSetOf<String>()
        val perProviderCounts = mutableMapOf<String, Int>()

        fun tryPick(rec: EligibleModelRecord): Boolean {
            if (selected.size >= DEFAULT_RECOMMENDED_LIMIT) return false
            val provCount = perProviderCounts[rec.providerId] ?: 0
            if (provCount >= DEFAULT_MAX_PER_PROVIDER) return false
            val normModelId = rec.model.modelId.lowercase(Locale.US)
            if (normModelId in selectedModelIds) return false
            selected += rec
            selectedModelIds += normModelId
            perProviderCounts[rec.providerId] = provCount + 1
            return true
        }

        canonicalDefault?.let { tryPick(it) }

        // Ensure top coding model from each distinct provider gets a fair slot
        topCoding.forEach { if (perProviderCounts[it.providerId] ?: 0 == 0) tryPick(it) }
        // Ensure top verified free model from each distinct provider gets a fair slot
        topFree.forEach { if (perProviderCounts[it.providerId] ?: 0 < 2) tryPick(it) }
        // Fill remaining coding and general slots up to cap
        topCoding.forEach { tryPick(it) }
        topFree.forEach { tryPick(it) }
        eligible.sortedWith(rankComparator(nowMs, settings)).forEach { tryPick(it) }

        return selected
    }

    private fun rankComparator(nowMs: Long, settings: GatewaySettingsState): Comparator<EligibleModelRecord> =
        compareBy<EligibleModelRecord> { isRecordCoolingDown(it, nowMs) }
            .thenBy {
                when (settings.localRoutingPolicy) {
                    LocalRoutingPolicy.CLOUD_FIRST -> it.isLocal
                    LocalRoutingPolicy.LOCAL_FIRST, LocalRoutingPolicy.LOCAL_CLOUD_FALLBACK -> !it.isLocal
                    LocalRoutingPolicy.LOCAL_ONLY -> !it.isLocal
                }
            }
            .thenBy { healthOrder(it.effectiveStatus) }
            .thenByDescending { it.model.isDefault }
            .thenByDescending { it.codingScore }
            .thenBy { it.providerPriority }
            .thenBy { it.lastLatencyMs }
            .thenBy { it.model.modelId.lowercase(Locale.US) }
}
