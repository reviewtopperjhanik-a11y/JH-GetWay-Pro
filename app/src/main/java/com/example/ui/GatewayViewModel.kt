package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.example.BuildConfig
import com.example.JhGetWayApplication
import com.example.core.i18n.AppLanguage
import com.example.core.security.AppLockManager
import com.example.core.security.CryptoManager
import com.example.core.security.DeviceScopePolicy
import com.example.core.security.EndpointLocality
import com.example.core.security.LockoutState
import com.example.core.security.MaskingUtils
import com.example.core.security.NetworkAddressClassifier
import com.example.core.security.PinVerifyResult
import com.example.core.security.TokenUsageAccounting
import com.example.data.local.*
import com.example.gateway.*
import com.example.network.ChatMessagePayload
import com.example.network.ProviderPresets
import com.example.network.StreamEvent
import com.example.network.adapters.ContentPart
import com.example.network.adapters.ProviderAdapterRegistry
import com.example.ui.theme.ThemeMode
import com.example.updater.ApkInstallLaunchResult
import com.example.updater.AppReleaseInfo
import com.example.updater.AppUpdateCheckResult
import com.example.updater.AppUpdateManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class CompareModelOutput(
    val modelId: String,
    val providerName: String,
    val responseText: String,
    val latencyMs: Long,
    val inputTokens: Int,
    val outputTokens: Int,
    val estimatedCostUsd: Double?,
    val error: String?
)

enum class CompareRequestStatus {
    PENDING,
    QUEUED,
    RUNNING,
    STREAMING,
    COMPLETED,
    FAILED,
    CANCELLED;

    val isActive: Boolean
        get() = this == PENDING || this == QUEUED || this == RUNNING || this == STREAMING
}

data class CompareModelEntry(
    val entryKey: String,
    val modelId: String,
    val displayName: String,
    val providerId: String,
    val providerName: String,
    val supportsStreaming: Boolean = true,
    val requestId: String? = null,
    val status: CompareRequestStatus = CompareRequestStatus.PENDING,
    val responseText: String = "",
    val isStreaming: Boolean = false,
    val errorMessage: String? = null,
    val latencyMs: Long? = null,
    val ttftMs: Long? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
    val usageEstimated: Boolean = false,
    val estimatedCostUsd: Double? = null,
    val actualCostUsd: Double? = null,
    val finishReason: String? = null,
    val httpStatus: Int? = null
)

data class CompareWorkspaceState(
    val selectedModelKeys: List<String> = emptyList(),
    val prompt: String = "",
    val systemPrompt: String = "",
    val entries: List<CompareModelEntry> = emptyList(),
    val activeEntryKey: String? = null,
    val expandedDetailsKeys: Set<String> = emptySet(),
    val isRunning: Boolean = false
)

data class PendingMcpApproval(
    val server: McpServerEntity,
    val tool: McpToolEntity,
    val deferred: CompletableDeferred<Boolean>
)

data class AppLockUiState(
    val isInvalid: Boolean = false,
    val failedAttempts: Int = 0,
    val lockedUntilEpochMs: Long = 0L,
    val isVerifying: Boolean = false
)

data class AppUpdateUiState(
    val isChecking: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgressPct: Int = 0,
    val availableRelease: AppReleaseInfo? = null,
    val downloadedApkFile: File? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val showPromptDialog: Boolean = false,
    val needsInstallPermission: Boolean = false,
    val signingConflict: Boolean = false
)

data class FetchedModelsPrompt(
    val provider: ProviderEntity,
    val models: List<ModelEntity>
)

data class PlaygroundTarget(
    val providerId: String?,
    val modelId: String
)

class GatewayViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as JhGetWayApplication
    private val db = app.database
    private val prefs = app.preferences
    private val httpClient = app.providerHttpClient
    private val mcpClient = app.mcpHttpClient
    val router = app.smartRouter
    val gatewayServer = app.gatewayServer
    val workflowEngine = app.workflowEngine
    private val backupManager = app.backupManager
    private val retentionManager = DataRetentionManager(db)

    val settings: StateFlow<GatewaySettingsState> = prefs.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, GatewaySettingsState())

    val gatewayRuntime: StateFlow<GatewayRuntimeState> = gatewayServer.runtimeState
    val recentRouterDecisions: StateFlow<List<RouterDecisionSnapshot>> = router.recentDecisions

    val providers: StateFlow<List<ProviderEntity>> = db.providerDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val apiKeys: StateFlow<List<ApiKeyEntity>> = db.apiKeyDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val models: StateFlow<List<ModelEntity>> = db.modelDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val routingRules: StateFlow<List<RoutingRuleEntity>> = db.routingRuleDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val codingAgents: StateFlow<List<CodingAgentEntity>> = db.codingAgentDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val localServers: StateFlow<List<LocalAiServerEntity>> = db.localAiServerDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val eligibleAutoFreeCount: StateFlow<Int> = combine(
        providers,
        localServers,
        models,
        apiKeys,
        settings
    ) { _, _, _, _, s -> s }
        .mapLatest { s -> router.countEligibleAutoFreeModels(s) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val mcpServers: StateFlow<List<McpServerEntity>> = db.mcpDao().observeServers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val mcpTools: StateFlow<List<McpToolEntity>> = db.mcpDao().observeTools()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val workflows: StateFlow<List<WorkflowEntity>> = db.workflowDao().observeWorkflows()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val workflowRuns: StateFlow<List<WorkflowRunEntity>> = db.workflowDao().observeRuns()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val devices: StateFlow<List<DeviceEntity>> = db.deviceDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val metrics: StateFlow<List<RequestMetricEntity>> = db.metricAndLogDao().observeMetrics()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activityLogs: StateFlow<List<ActivityLogEntity>> = db.metricAndLogDao().observeLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playgroundSessions: StateFlow<List<PlaygroundSessionEntity>> = db.playgroundDao().observeSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val activeSessionMessages: StateFlow<List<PlaygroundMessageEntity>> = _activeSessionId
        .flatMapLatest { sid ->
            if (sid == null) flowOf(emptyList()) else db.playgroundDao().observeMessages(sid)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _ephemeralMessages = MutableStateFlow<List<PlaygroundMessageEntity>>(emptyList())
    val ephemeralMessages: StateFlow<List<PlaygroundMessageEntity>> = _ephemeralMessages.asStateFlow()

    private val _streamingBuffer = MutableStateFlow("")
    val streamingBuffer: StateFlow<String> = _streamingBuffer.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _compareState = MutableStateFlow(CompareWorkspaceState())
    val compareState: StateFlow<CompareWorkspaceState> = _compareState.asStateFlow()

    val compareResults: StateFlow<List<CompareModelOutput>> = _compareState
        .map { ws ->
            ws.entries.map { entry ->
                CompareModelOutput(
                    modelId = entry.modelId,
                    providerName = entry.providerName,
                    responseText = entry.responseText,
                    latencyMs = entry.latencyMs ?: 0L,
                    inputTokens = entry.inputTokens ?: 0,
                    outputTokens = entry.outputTokens ?: 0,
                    estimatedCostUsd = entry.estimatedCostUsd,
                    error = entry.errorMessage
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val isComparing: StateFlow<Boolean> = _compareState
        .map { it.isRunning }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _workflowNodeProgress = MutableStateFlow<List<NodeExecutionResult>>(emptyList())
    val workflowNodeProgress: StateFlow<List<NodeExecutionResult>> = _workflowNodeProgress.asStateFlow()

    private val _isWorkflowRunning = MutableStateFlow(false)
    val isWorkflowRunning: StateFlow<Boolean> = _isWorkflowRunning.asStateFlow()

    private val _pendingMcpApproval = MutableStateFlow<PendingMcpApproval?>(null)
    val pendingMcpApproval: StateFlow<PendingMcpApproval?> = _pendingMcpApproval.asStateFlow()

    private val _uiBannerMessage = MutableStateFlow<String?>(null)
    val uiBannerMessage: StateFlow<String?> = _uiBannerMessage.asStateFlow()

    private val _isInitialSettingsLoaded = MutableStateFlow(false)
    val isInitialSettingsLoaded: StateFlow<Boolean> = _isInitialSettingsLoaded.asStateFlow()

    private val _isAppUnlocked = MutableStateFlow(true)
    val isAppUnlocked: StateFlow<Boolean> = _isAppUnlocked.asStateFlow()

    private val _appLockUiState = MutableStateFlow(AppLockUiState())
    val appLockUiState: StateFlow<AppLockUiState> = _appLockUiState.asStateFlow()

    private val _appUpdateUiState = MutableStateFlow(AppUpdateUiState())
    val appUpdateUiState: StateFlow<AppUpdateUiState> = _appUpdateUiState.asStateFlow()

    private val _fetchingProviderIds = MutableStateFlow<Set<String>>(emptySet())
    val fetchingProviderIds: StateFlow<Set<String>> = _fetchingProviderIds.asStateFlow()

    private val _fetchedModelsPrompt = MutableStateFlow<FetchedModelsPrompt?>(null)
    val fetchedModelsPrompt: StateFlow<FetchedModelsPrompt?> = _fetchedModelsPrompt.asStateFlow()

    private val _playgroundTarget = MutableStateFlow<PlaygroundTarget?>(null)
    val playgroundTarget: StateFlow<PlaygroundTarget?> = _playgroundTarget.asStateFlow()

    private val _agentConnectionStatuses = MutableStateFlow<Map<String, CodingAgentTestStatus>>(emptyMap())
    val agentConnectionStatuses: StateFlow<Map<String, CodingAgentTestStatus>> = _agentConnectionStatuses.asStateFlow()

    private val agentGatewayTester = CodingAgentGatewayTester()
    private val appLockManager = AppLockManager()
    private var streamingJob: Job? = null
    private val compareJobs = mutableMapOf<String, Job>()
    private val compareRunCounter = java.util.concurrent.atomic.AtomicLong(0L)
    private var lastCompareRequestConfig = CompareRequestConfig()
    private var workflowJob: Job? = null
    private var updateJob: Job? = null

    private data class CompareRequestConfig(
        val temperature: Float? = null,
        val topP: Float? = null,
        val maxTokens: Int? = null,
        val stopSequences: List<String> = emptyList(),
        val jsonOutput: Boolean = false,
        val streaming: Boolean = true
    )

    init {
        viewModelScope.launch {
            val s = prefs.settingsFlow.first()
            if (s.appLockEnabled && s.appLockPinHash.isNotBlank()) {
                _isAppUnlocked.value = false
                _appLockUiState.value = AppLockUiState(
                    failedAttempts = s.appLockFailedAttempts,
                    lockedUntilEpochMs = s.appLockLockedUntilMs
                )
            }
            _isInitialSettingsLoaded.value = true
            retentionManager.enforcePolicies(
                logRetentionDays = s.logRetentionDays,
                usageRetentionDays = s.usageRetentionDays,
                playgroundRetentionDays = s.playgroundRetentionDays,
                onExpiredAttachments = { metaList ->
                    PlaygroundAttachmentManager.deleteAttachmentsFromMetadata(app, metaList)
                }
            )
            val isRobolectric = android.os.Build.FINGERPRINT.equals("robolectric", ignoreCase = true)
            if (!isRobolectric && s.autoCheckUpdates && effectiveGithubUpdateRepo(s).isNotBlank()) {
                checkForAppUpdates(silent = true)
            }
        }
    }

    fun clearBanner() { _uiBannerMessage.value = null }
    fun postBanner(msg: String) { _uiBannerMessage.value = msg }

    // Theme & Language & Onboarding
    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { prefs.setThemeMode(mode) }
    fun setLanguage(lang: AppLanguage) = viewModelScope.launch { prefs.setLanguage(lang) }
    fun setGatewayMode(mode: GatewayMode) = viewModelScope.launch {
        prefs.setGatewayMode(mode)
        if (mode != GatewayMode.LOCAL) {
            stopLocalGatewayServiceIfNeeded()
        }
    }

    private suspend fun stopLocalGatewayServiceIfNeeded() {
        prefs.setGatewayServiceDesiredRunning(false)
        GatewayForegroundService.dispatchStop(app)
        val rt = gatewayRuntime.value
        if (rt.mode == GatewayMode.LOCAL &&
            (rt.status == GatewayRunStatus.LOCAL_RUNNING || rt.status == GatewayRunStatus.STARTING)
        ) {
            gatewayServer.stop()
        }
    }
    fun completeOnboarding() = viewModelScope.launch { prefs.setOnboardingCompleted(true) }

    fun toggleGatewayServer() = viewModelScope.launch {
        val currentSettings = settings.value
        val st = gatewayRuntime.value.status
        val isBusyOrActive = gatewayRuntime.value.isActive ||
            st == GatewayRunStatus.STARTING ||
            st == GatewayRunStatus.REMOTE_CONNECTING
        if (currentSettings.gatewayMode == GatewayMode.LOCAL) {
            if (isBusyOrActive) {
                prefs.setGatewayServiceDesiredRunning(false)
                GatewayForegroundService.dispatchStop(app)
            } else {
                prefs.setGatewayServiceDesiredRunning(true)
                GatewayForegroundService.dispatchStart(app)
            }
        } else {
            if (isBusyOrActive) {
                gatewayServer.stop()
            } else {
                gatewayServer.start(currentSettings)
            }
        }
    }

    fun restartGatewayServer() = viewModelScope.launch {
        val currentSettings = settings.value
        if (currentSettings.gatewayMode == GatewayMode.LOCAL) {
            prefs.setGatewayServiceDesiredRunning(true)
            GatewayForegroundService.dispatchRestart(app)
        } else {
            gatewayServer.restart(currentSettings)
        }
    }

    fun refreshRemoteGatewayHealth() = viewModelScope.launch {
        gatewayServer.refreshRemoteHealth(settings.value)
    }

    fun resetInvalidatedVaultKey() = viewModelScope.launch {
        CryptoManager.resetInvalidatedMasterKey().fold(
            onSuccess = {
                logEvent(ActivityCategory.KEY_UPDATE, "Vault Keystore", "KEY_RESET", "Vault master key reset; re-save credentials")
                postBanner("Vault key reset. Please re-enter stored API keys and device tokens.")
            },
            onFailure = { err ->
                postBanner("Vault key reset failed: ${MaskingUtils.sanitizeErrorMessage(err.message)}")
            }
        )
    }

    // Providers & Keys
    fun saveProvider(
        existingId: String?,
        name: String,
        type: ProviderType,
        presetId: String,
        baseUrl: String,
        apiFormat: ApiFormat,
        defaultModel: String,
        priority: Int,
        enabled: Boolean,
        customHeadersText: String,
        notes: String,
        initialApiKey: String
    ) = viewModelScope.launch {
        val existing = existingId?.let { db.providerDao().getById(it) }
        val headersJson = if (customHeadersText.isBlank() && existing != null) {
            existing.customHeadersJson
        } else {
            buildHeadersJson(customHeadersText)
        }
        val trimmedModel = defaultModel.trim()
        val provider = ProviderEntity(
            id = existingId ?: UUID.randomUUID().toString(),
            name = name.trim(),
            type = type,
            presetId = presetId,
            baseUrl = baseUrl.trim().trimEnd('/'),
            apiFormat = apiFormat,
            defaultModel = trimmedModel,
            priority = priority,
            enabled = enabled,
            customHeadersJson = headersJson,
            notes = notes.trim(),
            status = existing?.status ?: ProviderStatus.UNKNOWN,
            lastLatencyMs = existing?.lastLatencyMs,
            lastError = existing?.lastError,
            lastCheckedAt = existing?.lastCheckedAt,
            createdAt = existing?.createdAt ?: System.currentTimeMillis()
        )
        db.providerDao().upsert(provider)
        if (initialApiKey.isNotBlank()) {
            addApiKey(provider.id, "${provider.name} Primary Key", initialApiKey.trim(), 1, isDefault = true, KeySelectionPolicy.PRIORITY)
        }
        if (trimmedModel.isNotBlank()) {
            val existingModels = db.modelDao().getByProvider(provider.id)
            val shouldSetGlobalDefault = settings.value.defaultModelId.isBlank()
            val matched = existingModels.firstOrNull { it.modelId == trimmedModel }
            if (matched == null) {
                val pricing = ProviderPresets.lookupPricing(trimmedModel)
                val caps = ProviderAdapterRegistry.adapterFor(apiFormat).capabilitiesForModel(trimmedModel, null)
                val modelType = ProviderPresets.inferModelType(trimmedModel)
                val isChatCapable = modelType != ModelType.EMBEDDING &&
                    modelType != ModelType.IMAGE_GENERATION &&
                    modelType != ModelType.AUDIO
                val isCatalogFree = trimmedModel.endsWith(":free", ignoreCase = true)
                val hasPositivePricing = pricing != null && (pricing.inputPer1M > 0.0 || pricing.outputPer1M > 0.0)
                val entity = ModelEntity(
                    id = "${provider.id}::$trimmedModel",
                    modelId = trimmedModel,
                    displayName = trimmedModel,
                    providerId = provider.id,
                    providerName = provider.name,
                    modelType = modelType,
                    inputPricePer1M = pricing?.inputPer1M,
                    outputPricePer1M = pricing?.outputPer1M,
                    pricingUpdatedAt = pricing?.updatedAt,
                    isDefault = shouldSetGlobalDefault,
                    enabled = true,
                    accessTier = when {
                        isCatalogFree -> AccessTier.FREE_TIER
                        hasPositivePricing -> AccessTier.PAID
                        else -> AccessTier.UNKNOWN
                    },
                    freeTierVerified = isCatalogFree,
                    freeTierSource = when {
                        isCatalogFree -> "Provider Catalog (:free)"
                        hasPositivePricing -> "Catalog Pricing"
                        else -> ""
                    },
                    freeTierVerifiedAt = if (isCatalogFree) System.currentTimeMillis() else null,
                    freeTierLimitsNote = if (isCatalogFree) "Provider free-tier rate/daily limits apply" else "",
                    supportsChat = isChatCapable,
                    supportsVision = caps.supportsVision
                )
                if (shouldSetGlobalDefault) {
                    db.withTransaction {
                        db.modelDao().clearDefaultFlag()
                        db.modelDao().upsert(entity)
                    }
                    prefs.setDefaultModelAndProvider(trimmedModel, provider.id)
                } else {
                    db.modelDao().upsert(entity)
                }
            } else if (shouldSetGlobalDefault) {
                db.withTransaction {
                    db.modelDao().clearDefaultFlag()
                    db.modelDao().upsert(matched.copy(isDefault = true, enabled = true))
                }
                prefs.setDefaultModelAndProvider(trimmedModel, provider.id)
            }
        }
        logEvent(ActivityCategory.PROVIDER_CONNECTION, provider.name, "SAVED", "Provider configuration saved")
    }

    fun toggleProviderEnabled(provider: ProviderEntity) = viewModelScope.launch {
        db.providerDao().upsert(
            provider.copy(
                enabled = !provider.enabled,
                status = if (!provider.enabled) ProviderStatus.UNKNOWN else ProviderStatus.DISABLED
            )
        )
    }

    fun deleteProvider(provider: ProviderEntity) = viewModelScope.launch {
        db.withTransaction {
            db.providerDao().deleteById(provider.id)
            db.apiKeyDao().deleteByProviderId(provider.id)
            db.modelDao().deleteByProviderId(provider.id)
            logEvent(ActivityCategory.PROVIDER_CONNECTION, provider.name, "DELETED", "Provider and linked models/keys removed")
        }
    }

    fun testProviderConnection(provider: ProviderEntity) = viewModelScope.launch {
        db.providerDao().upsert(provider.copy(status = ProviderStatus.CHECKING))
        val keys = db.apiKeyDao().getForProvider(provider.id).filter { it.enabled }
        val activeKey = keys.firstOrNull { it.isDefault } ?: keys.firstOrNull()
        val plainKey = if (activeKey != null) {
            CryptoManager.decryptResult(activeKey.encryptedKey).getOrElse { err ->
                val msg = MaskingUtils.sanitizeErrorMessage(err.message)
                db.providerDao().upsert(provider.copy(status = ProviderStatus.ERROR, lastError = msg, lastCheckedAt = System.currentTimeMillis()))
                logEvent(ActivityCategory.KEY_UPDATE, provider.name, "DECRYPT_FAILED", msg)
                postBanner("${provider.name}: $msg")
                return@launch
            }
        } else ""

        val result = httpClient.testProviderConnection(
            provider = provider,
            apiKey = plainKey,
            allowLocalHttp = settings.value.localNetworkAccessAllowed,
            timeoutSec = settings.value.gatewayTimeoutSec
        )
        db.providerDao().upsert(
            provider.copy(
                status = result.status,
                lastLatencyMs = result.latencyMs,
                lastError = if (result.status == ProviderStatus.CONNECTED) null else result.message,
                lastCheckedAt = System.currentTimeMillis()
            )
        )
        if (activeKey != null) {
            val kStatus = when (result.status) {
                ProviderStatus.CONNECTED -> KeyStatus.ACTIVE
                ProviderStatus.RATE_LIMITED -> KeyStatus.RATE_LIMITED
                ProviderStatus.ERROR -> if (result.httpCode == 401 || result.httpCode == 403) KeyStatus.INVALID else KeyStatus.UNKNOWN
                else -> activeKey.status
            }
            db.apiKeyDao().upsert(activeKey.copy(status = kStatus, lastTestedAt = System.currentTimeMillis()))
        }
        logEvent(ActivityCategory.PROVIDER_CONNECTION, provider.name, result.status.name, result.message)
        postBanner("${provider.name}: ${result.message}")
    }

    fun fetchProviderModels(provider: ProviderEntity, openPickerOnSuccess: Boolean = true) = viewModelScope.launch {
        _fetchingProviderIds.update { it + provider.id }
        try {
            val keys = db.apiKeyDao().getForProvider(provider.id).filter { it.enabled }
            val activeKey = keys.firstOrNull { it.isDefault } ?: keys.firstOrNull()
            val plainKey = if (activeKey != null) {
                CryptoManager.decryptResult(activeKey.encryptedKey).getOrElse { err ->
                    val msg = MaskingUtils.sanitizeErrorMessage(err.message)
                    postBanner("${provider.name}: $msg")
                    logEvent(ActivityCategory.KEY_UPDATE, provider.name, "DECRYPT_FAILED", msg)
                    return@launch
                }
            } else ""
            val result = httpClient.fetchProviderModels(provider, plainKey, settings.value.localNetworkAccessAllowed)
            result.fold(
                onSuccess = { fetched ->
                    val existing = db.modelDao().getByProvider(provider.id).associateBy { it.modelId }
                    val merged = fetched.map { m ->
                        val prev = existing[m.modelId]
                        if (prev != null) {
                            val preserveUserConfirmedFree = prev.freeTierSource == "User Confirmed" && prev.freeTierVerified
                            m.copy(
                                alias = prev.alias,
                                isDefault = prev.isDefault,
                                enabled = prev.enabled,
                                accessTier = if (preserveUserConfirmedFree) AccessTier.FREE_TIER else m.accessTier,
                                freeTierVerified = if (preserveUserConfirmedFree) true else m.freeTierVerified,
                                freeTierSource = if (preserveUserConfirmedFree) prev.freeTierSource else m.freeTierSource,
                                freeTierVerifiedAt = if (preserveUserConfirmedFree) prev.freeTierVerifiedAt else m.freeTierVerifiedAt,
                                freeTierLimitsNote = prev.freeTierLimitsNote.ifBlank { m.freeTierLimitsNote }
                            )
                        } else m
                    }
                    db.modelDao().upsertAll(merged)
                    if (openPickerOnSuccess && merged.isNotEmpty()) {
                        val latestProvider = db.providerDao().getById(provider.id) ?: provider
                        _fetchedModelsPrompt.value = FetchedModelsPrompt(latestProvider, merged)
                    }
                    postBanner("${provider.name}: Fetched ${merged.size} models")
                    logEvent(ActivityCategory.PROVIDER_CONNECTION, provider.name, "MODELS_FETCHED", "Discovered ${merged.size} models")
                },
                onFailure = { err ->
                    postBanner("${provider.name}: ${err.message}")
                    logEvent(ActivityCategory.PROVIDER_ERROR, provider.name, "FETCH_FAILED", err.message ?: "Failed to fetch models")
                }
            )
        } finally {
            _fetchingProviderIds.update { it - provider.id }
        }
    }

    suspend fun fetchModelsForDraft(
        existingProviderId: String?,
        name: String,
        baseUrl: String,
        apiFormat: ApiFormat,
        apiKeyInput: String,
        customHeadersText: String
    ): Result<List<ModelEntity>> {
        val resolvedKey = if (apiKeyInput.isNotBlank()) {
            apiKeyInput.trim()
        } else if (existingProviderId != null) {
            val keys = db.apiKeyDao().getForProvider(existingProviderId).filter { it.enabled }
            val activeKey = keys.firstOrNull { it.isDefault } ?: keys.firstOrNull()
            if (activeKey != null) {
                CryptoManager.decryptResult(activeKey.encryptedKey).getOrElse { err ->
                    return Result.failure(IllegalStateException(MaskingUtils.sanitizeErrorMessage(err.message)))
                }
            } else ""
        } else ""

        val draft = ProviderEntity(
            id = existingProviderId ?: "draft",
            name = name.trim().ifBlank { apiFormat.name },
            baseUrl = baseUrl.trim().trimEnd('/'),
            apiFormat = apiFormat,
            customHeadersJson = buildHeadersJson(customHeadersText)
        )
        return httpClient.fetchProviderModels(draft, resolvedKey, settings.value.localNetworkAccessAllowed)
    }

    fun dismissFetchedModelsPrompt() {
        _fetchedModelsPrompt.value = null
    }

    fun selectModelForProvider(providerId: String, model: ModelEntity) = viewModelScope.launch {
        val updatedModel = model.copy(isDefault = true, enabled = true)
        var updatedProvider: ProviderEntity? = null
        db.withTransaction {
            db.modelDao().clearDefaultFlag()
            db.modelDao().upsert(updatedModel)
            db.providerDao().getById(providerId)?.let { prov ->
                val nextProv = prov.copy(defaultModel = model.modelId)
                db.providerDao().upsert(nextProv)
                updatedProvider = nextProv
            }
        }
        prefs.setDefaultModelAndProvider(model.modelId, providerId)
        _fetchedModelsPrompt.update { prompt ->
            if (prompt != null && prompt.provider.id == providerId) {
                prompt.copy(
                    provider = updatedProvider ?: prompt.provider.copy(defaultModel = model.modelId),
                    models = prompt.models.map {
                        it.copy(
                            isDefault = it.id == model.id,
                            enabled = if (it.id == model.id) true else it.enabled
                        )
                    }
                )
            } else prompt
        }
        postBanner("${updatedProvider?.name ?: model.providerName}: ${model.modelId}")
    }

    fun toggleFetchedModelEnabled(model: ModelEntity) = viewModelScope.launch {
        val updated = model.copy(enabled = !model.enabled)
        db.modelDao().upsert(updated)
        _fetchedModelsPrompt.update { prompt ->
            if (prompt != null && prompt.provider.id == model.providerId) {
                prompt.copy(models = prompt.models.map { if (it.id == model.id) updated else it })
            } else prompt
        }
    }

    // API Key Vault
    fun addApiKey(
        providerId: String,
        label: String,
        plainKey: String,
        priority: Int,
        isDefault: Boolean,
        policy: KeySelectionPolicy
    ) = viewModelScope.launch {
        val encrypted = runCatching { CryptoManager.encrypt(plainKey.trim()) }.getOrElse { err ->
            val msg = MaskingUtils.sanitizeErrorMessage(err.message, listOf(plainKey))
            logEvent(ActivityCategory.KEY_UPDATE, label.ifBlank { "API Key" }, "ENCRYPTION_FAILED", msg)
            postBanner(msg)
            return@launch
        }
        db.withTransaction {
            val existing = db.apiKeyDao().getForProvider(providerId)
            if (isDefault) {
                existing.filter { it.isDefault }.forEach { db.apiKeyDao().upsert(it.copy(isDefault = false)) }
            }
            val entity = ApiKeyEntity(
                providerId = providerId,
                label = label.trim().ifBlank { "Key #${existing.size + 1}" },
                encryptedKey = encrypted,
                maskedPreview = MaskingUtils.maskSecret(plainKey.trim()),
                priority = priority,
                isDefault = isDefault || existing.isEmpty(),
                enabled = true,
                status = KeyStatus.UNKNOWN,
                selectionPolicy = policy
            )
            db.apiKeyDao().upsert(entity)
            logEvent(ActivityCategory.KEY_UPDATE, entity.label, "ADDED", "Encrypted key stored in Vault")
        }
    }

    fun rotateApiKey(keyEntity: ApiKeyEntity, newPlainKey: String) = viewModelScope.launch {
        val encrypted = runCatching { CryptoManager.encrypt(newPlainKey.trim()) }.getOrElse { err ->
            val msg = MaskingUtils.sanitizeErrorMessage(err.message, listOf(newPlainKey))
            logEvent(ActivityCategory.KEY_UPDATE, keyEntity.label, "ROTATE_FAILED", msg)
            postBanner(msg)
            return@launch
        }
        db.apiKeyDao().upsert(
            keyEntity.copy(
                encryptedKey = encrypted,
                maskedPreview = MaskingUtils.maskSecret(newPlainKey.trim()),
                status = KeyStatus.UNKNOWN
            )
        )
        logEvent(ActivityCategory.KEY_UPDATE, keyEntity.label, "ROTATED", "API key rotated securely")
    }

    fun testApiKey(keyEntity: ApiKeyEntity) = viewModelScope.launch {
        val provider = db.providerDao().getById(keyEntity.providerId) ?: return@launch
        val plain = CryptoManager.decryptResult(keyEntity.encryptedKey).getOrElse { err ->
            val msg = MaskingUtils.sanitizeErrorMessage(err.message)
            logEvent(ActivityCategory.KEY_UPDATE, keyEntity.label, "DECRYPT_FAILED", msg)
            postBanner("${keyEntity.label}: $msg")
            return@launch
        }
        val res = httpClient.testProviderConnection(provider, plain, settings.value.localNetworkAccessAllowed)
        val status = when (res.status) {
            ProviderStatus.CONNECTED -> KeyStatus.ACTIVE
            ProviderStatus.RATE_LIMITED -> KeyStatus.RATE_LIMITED
            ProviderStatus.ERROR -> if (res.httpCode == 401 || res.httpCode == 403) KeyStatus.INVALID else KeyStatus.UNKNOWN
            else -> KeyStatus.UNKNOWN
        }
        db.apiKeyDao().upsert(keyEntity.copy(status = status, lastTestedAt = System.currentTimeMillis()))
        logEvent(ActivityCategory.KEY_UPDATE, keyEntity.label, status.name, res.message)
        postBanner("${keyEntity.label}: ${res.message}")
    }

    fun setDefaultApiKey(keyEntity: ApiKeyEntity) = viewModelScope.launch {
        db.withTransaction {
            db.apiKeyDao().getForProvider(keyEntity.providerId).forEach {
                db.apiKeyDao().upsert(it.copy(isDefault = it.id == keyEntity.id))
            }
        }
    }

    fun toggleApiKeyEnabled(keyEntity: ApiKeyEntity) = viewModelScope.launch {
        db.apiKeyDao().upsert(
            keyEntity.copy(
                enabled = !keyEntity.enabled,
                status = if (!keyEntity.enabled) KeyStatus.UNKNOWN else KeyStatus.DISABLED
            )
        )
    }

    fun deleteApiKey(keyEntity: ApiKeyEntity) = viewModelScope.launch {
        db.apiKeyDao().deleteById(keyEntity.id)
        logEvent(ActivityCategory.KEY_UPDATE, keyEntity.label, "DELETED", "Key removed from Vault")
    }

    // Models
    fun toggleModelEnabled(model: ModelEntity) = viewModelScope.launch {
        db.modelDao().upsert(model.copy(enabled = !model.enabled))
    }

    fun setDefaultModel(model: ModelEntity) = viewModelScope.launch {
        db.withTransaction {
            db.modelDao().clearDefaultFlag()
            db.modelDao().upsert(model.copy(isDefault = true, enabled = true))
        }
        prefs.setDefaultModelAndProvider(model.modelId, model.providerId)
    }

    fun setModelAlias(model: ModelEntity, alias: String) = viewModelScope.launch {
        db.modelDao().upsert(model.copy(alias = alias.trim()))
    }

    fun setModelFreeTierConfirmed(model: ModelEntity, confirmed: Boolean, limitsNote: String = "") = viewModelScope.launch {
        val hasPositivePrice = (model.inputPricePer1M ?: 0.0) > 0.0 || (model.outputPricePer1M ?: 0.0) > 0.0
        val updated = if (confirmed) {
            model.copy(
                accessTier = AccessTier.FREE_TIER,
                freeTierVerified = true,
                freeTierSource = "User Confirmed",
                freeTierVerifiedAt = System.currentTimeMillis(),
                freeTierLimitsNote = limitsNote.trim().ifBlank { model.freeTierLimitsNote }
            )
        } else {
            val isCatalogFree = model.modelId.endsWith(":free", ignoreCase = true) && !hasPositivePrice
            model.copy(
                accessTier = when {
                    model.isLocal -> AccessTier.LOCAL
                    isCatalogFree -> AccessTier.FREE_TIER
                    hasPositivePrice -> AccessTier.PAID
                    else -> AccessTier.UNKNOWN
                },
                freeTierVerified = isCatalogFree,
                freeTierSource = if (isCatalogFree) "Provider Catalog (:free)" else "",
                freeTierVerifiedAt = if (isCatalogFree) System.currentTimeMillis() else null
            )
        }
        db.modelDao().upsert(updated)
    }

    fun setManualModelFallbackEnabled(enabled: Boolean) = viewModelScope.launch {
        prefs.setManualModelFallbackEnabled(enabled)
    }

    fun setAutoFreeIncludeLocal(enabled: Boolean) = viewModelScope.launch {
        prefs.setAutoFreeIncludeLocal(enabled)
    }

    // Smart Router & Rules
    fun updateRoutingSettings(mode: RoutingMode, fallbackEnabled: Boolean, maxRetries: Int) = viewModelScope.launch {
        prefs.updateRoutingSettings(mode, fallbackEnabled, maxRetries)
    }

    fun saveRoutingRule(rule: RoutingRuleEntity) = viewModelScope.launch {
        db.routingRuleDao().upsert(rule)
    }

    fun deleteRoutingRule(rule: RoutingRuleEntity) = viewModelScope.launch {
        db.routingRuleDao().deleteById(rule.id)
    }

    // Coding Agents
    fun saveCodingAgent(
        existingId: String?,
        name: String,
        type: CodingAgentType,
        modelAlias: String,
        streaming: Boolean,
        toolCalling: Boolean,
        dailyTokenLimit: Long?,
        allowedModelsCsv: String = ""
    ) = viewModelScope.launch {
        val existing = existingId?.let { id ->
            codingAgents.value.firstOrNull { it.id == id } ?: db.codingAgentDao().getById(id)
        }
        val (encToken, tokenPreview) = if (existing != null) {
            existing.encryptedToken to existing.tokenPreview
        } else {
            val rawToken = CryptoManager.generateGatewayToken("jhgw_ag")
            val encrypted = runCatching { CryptoManager.encrypt(rawToken) }.getOrElse { err ->
                val msg = MaskingUtils.sanitizeErrorMessage(err.message, listOf(rawToken))
                logEvent(ActivityCategory.KEY_UPDATE, name.trim(), "ENCRYPTION_FAILED", msg)
                postBanner(msg)
                return@launch
            }
            encrypted to MaskingUtils.maskSecret(rawToken)
        }
        val cleanAlias = modelAlias.trim().ifBlank { "jh-coder" }
        val agent = CodingAgentEntity(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name.trim(),
            agentType = type,
            modelAlias = cleanAlias,
            defaultModelId = existing?.defaultModelId ?: "",
            allowedModelsCsv = allowedModelsCsv.trim(),
            streaming = streaming,
            toolCalling = toolCalling,
            dailyTokenLimit = dailyTokenLimit,
            tokensUsedToday = existing?.tokensUsedToday ?: 0,
            tokensResetDateUtc = existing?.tokensResetDateUtc?.ifBlank { TokenUsageAccounting.utcDateString() }
                ?: TokenUsageAccounting.utcDateString(),
            lastUsageSource = existing?.lastUsageSource ?: "UNKNOWN",
            requestCount = existing?.requestCount ?: 0,
            enabled = existing?.enabled ?: true,
            encryptedToken = encToken,
            tokenPreview = tokenPreview,
            lastActiveAt = existing?.lastActiveAt,
            createdAt = existing?.createdAt ?: System.currentTimeMillis()
        )
        db.codingAgentDao().upsert(agent)
        if (existing != null) {
            _agentConnectionStatuses.update { it - agent.id }
        }
    }

    fun decryptAgentTokenResult(agent: CodingAgentEntity): Result<String> =
        CryptoManager.decryptResult(agent.encryptedToken)

    fun toggleAgentEnabled(agent: CodingAgentEntity) = viewModelScope.launch {
        db.codingAgentDao().upsert(agent.copy(enabled = !agent.enabled))
    }

    fun deleteAgent(agent: CodingAgentEntity) = viewModelScope.launch {
        db.codingAgentDao().deleteById(agent.id)
        _agentConnectionStatuses.update { it - agent.id }
    }

    fun testCodingAgentGateway(agent: CodingAgentEntity) = viewModelScope.launch {
        val currentSettings = settings.value
        val currentRuntime = gatewayRuntime.value
        _agentConnectionStatuses.update {
            it + (agent.id to CodingAgentTestStatus(isTesting = true))
        }
        val plainToken = CryptoManager.decryptResult(agent.encryptedToken).getOrElse { err ->
            val msg = MaskingUtils.sanitizeErrorMessage(err.message)
            val failed = CodingAgentTestStatus(
                isTesting = false,
                success = false,
                message = msg,
                testedAtMs = System.currentTimeMillis()
            )
            _agentConnectionStatuses.update { it + (agent.id to failed) }
            logEvent(ActivityCategory.KEY_UPDATE, agent.name, "DECRYPT_FAILED", msg)
            postBanner("${agent.name}: $msg")
            return@launch
        }
        val status = agentGatewayTester.testConnection(
            agent = agent,
            decryptedAgentToken = plainToken,
            settings = currentSettings,
            runtime = currentRuntime
        )
        val safeMsg = MaskingUtils.sanitizeErrorMessage(status.message, listOf(plainToken))
        val safeStatus = status.copy(message = safeMsg)
        _agentConnectionStatuses.update { it + (agent.id to safeStatus) }
        logEvent(
            ActivityCategory.AUTH_EVENT,
            agent.name,
            if (safeStatus.success == true) "AGENT_TEST_OK" else "AGENT_TEST_FAILED",
            safeMsg
        )
        postBanner("${agent.name}: $safeMsg")
    }

    // Local AI Bridge
    fun saveLocalServer(
        name: String,
        engineType: LocalEngineType,
        serverUrl: String,
        authToken: String,
        timeoutSec: Int,
        defaultModel: String
    ) = viewModelScope.launch {
        val cleanUrl = serverUrl.trim().trimEnd('/')
        val locality = NetworkAddressClassifier.classifyEndpointLocality(cleanUrl)
        val isPrivate = locality != EndpointLocality.PUBLIC_REMOTE
        val encAuth = if (authToken.isNotBlank()) {
            runCatching { CryptoManager.encrypt(authToken.trim()) }.getOrElse { err ->
                postBanner(MaskingUtils.sanitizeErrorMessage(err.message, listOf(authToken)))
                return@launch
            }
        } else ""
        val server = LocalAiServerEntity(
            name = name.trim(),
            engineType = engineType,
            serverUrl = cleanUrl,
            encryptedAuthToken = encAuth,
            hasAuth = authToken.isNotBlank(),
            timeoutSec = timeoutSec,
            defaultModel = defaultModel.trim(),
            isPrivateNetwork = isPrivate
        )
        db.localAiServerDao().upsert(server)
    }

    fun testLocalServer(server: LocalAiServerEntity) = viewModelScope.launch {
        db.localAiServerDao().upsert(server.copy(status = ProviderStatus.CHECKING))
        val token = if (server.hasAuth) {
            CryptoManager.decryptResult(server.encryptedAuthToken).getOrElse { err ->
                val msg = MaskingUtils.sanitizeErrorMessage(err.message)
                db.localAiServerDao().upsert(server.copy(status = ProviderStatus.ERROR, lastError = msg))
                postBanner("${server.name}: $msg")
                return@launch
            }
        } else ""
        val res = httpClient.testLocalServer(server, token, settings.value.localNetworkAccessAllowed)
        db.localAiServerDao().upsert(
            server.copy(status = res.status, lastLatencyMs = res.latencyMs, lastError = if (res.status == ProviderStatus.CONNECTED) null else res.message)
        )
        postBanner("${server.name}: ${res.message}")
    }

    fun discoverLocalModels(server: LocalAiServerEntity) = viewModelScope.launch {
        val token = if (server.hasAuth) {
            CryptoManager.decryptResult(server.encryptedAuthToken).getOrElse { err ->
                postBanner("${server.name}: ${MaskingUtils.sanitizeErrorMessage(err.message)}")
                return@launch
            }
        } else ""
        httpClient.discoverLocalModels(server, token, settings.value.localNetworkAccessAllowed).fold(
            onSuccess = { list ->
                db.modelDao().upsertAll(list)
                postBanner("${server.name}: Discovered ${list.size} local models")
            },
            onFailure = { err -> postBanner("${server.name}: ${err.message}") }
        )
    }

    fun toggleLocalServer(server: LocalAiServerEntity) = viewModelScope.launch { db.localAiServerDao().upsert(server.copy(enabled = !server.enabled)) }
    fun deleteLocalServer(server: LocalAiServerEntity) = viewModelScope.launch {
        db.withTransaction {
            db.localAiServerDao().deleteById(server.id)
            db.modelDao().deleteByProviderId("local:${server.id}")
        }
    }
    fun updateLocalAiSettings(policy: LocalRoutingPolicy, allowCloud: Boolean, allowLocalNet: Boolean) = viewModelScope.launch {
        prefs.updateLocalAiSettings(policy, allowCloud, allowLocalNet)
    }

    // MCP Servers
    fun saveMcpServer(
        name: String,
        transport: McpTransportType,
        endpointOrCommand: String,
        arguments: String,
        envText: String,
        authToken: String,
        timeoutSec: Int
    ) = viewModelScope.launch {
        val envJson = buildEnvJson(envText)
        val encEnv = if (envJson.isNotBlank()) {
            runCatching { CryptoManager.encrypt(envJson) }.getOrElse { err ->
                postBanner(MaskingUtils.sanitizeErrorMessage(err.message))
                return@launch
            }
        } else ""
        val encAuth = if (authToken.isNotBlank()) {
            runCatching { CryptoManager.encrypt(authToken.trim()) }.getOrElse { err ->
                postBanner(MaskingUtils.sanitizeErrorMessage(err.message, listOf(authToken)))
                return@launch
            }
        } else ""
        val server = McpServerEntity(
            name = name.trim(),
            transportType = transport,
            endpointOrCommand = endpointOrCommand.trim(),
            arguments = arguments.trim(),
            encryptedEnvJson = encEnv,
            encryptedAuthToken = encAuth,
            timeoutSec = timeoutSec
        )
        db.mcpDao().upsertServer(server)
    }

    fun connectAndDiscoverMcp(server: McpServerEntity) = viewModelScope.launch {
        db.mcpDao().upsertServer(server.copy(status = ProviderStatus.CHECKING))
        val authTok = CryptoManager.decryptResult(server.encryptedAuthToken).getOrElse { err ->
            val msg = MaskingUtils.sanitizeErrorMessage(err.message)
            db.mcpDao().upsertServer(server.copy(status = ProviderStatus.ERROR, lastError = msg))
            postBanner("${server.name}: $msg")
            return@launch
        }
        val envJson = CryptoManager.decryptResult(server.encryptedEnvJson).getOrElse { err ->
            val msg = MaskingUtils.sanitizeErrorMessage(err.message)
            db.mcpDao().upsertServer(server.copy(status = ProviderStatus.ERROR, lastError = msg))
            postBanner("${server.name}: $msg")
            return@launch
        }
        val res = mcpClient.connectAndDiscover(
            server = server,
            authToken = authTok,
            envVarsJson = envJson,
            allowLocalHttp = settings.value.localNetworkAccessAllowed
        )
        if (res.success) {
            db.withTransaction {
                db.mcpDao().upsertServer(
                    server.copy(
                        status = ProviderStatus.CONNECTED,
                        lastConnectedAt = System.currentTimeMillis(),
                        lastError = null,
                        resourcesJson = res.resourcesJson,
                        promptsJson = res.promptsJson
                    )
                )
                db.mcpDao().deleteToolsForServer(server.id)
                db.mcpDao().upsertTools(res.tools)
            }
            postBanner("${server.name}: Discovered ${res.tools.size} tools")
        } else {
            db.mcpDao().upsertServer(server.copy(status = ProviderStatus.ERROR, lastError = res.errorMessage))
            postBanner("${server.name}: ${res.errorMessage}")
        }
    }

    fun disconnectMcpServer(server: McpServerEntity) = viewModelScope.launch {
        db.mcpDao().upsertServer(server.copy(status = ProviderStatus.DISCONNECTED))
    }

    fun updateToolPermission(tool: McpToolEntity, permission: ToolPermission) = viewModelScope.launch {
        db.mcpDao().upsertTool(tool.copy(permission = permission))
    }

    fun executeMcpTool(server: McpServerEntity, tool: McpToolEntity, argsJson: String) = viewModelScope.launch {
        if (tool.permission == ToolPermission.DENIED) {
            logEvent(ActivityCategory.MCP_TOOL, "${server.name}/${tool.toolName}", "DENIED", "Execution blocked by DENIED permission policy")
            postBanner("Permission denied for ${tool.toolName}")
            return@launch
        }
        if (tool.permission == ToolPermission.ASK_BEFORE_RUNNING) {
            val approved = requestMcpApprovalFromUi(server, tool)
            if (!approved) {
                logEvent(ActivityCategory.MCP_TOOL, "${server.name}/${tool.toolName}", "DECLINED", "User declined MCP tool execution prompt")
                return@launch
            }
        }
        val authTok = CryptoManager.decryptResult(server.encryptedAuthToken).getOrElse { err ->
            postBanner("Credential error: ${MaskingUtils.sanitizeErrorMessage(err.message)}")
            return@launch
        }
        val envJson = CryptoManager.decryptResult(server.encryptedEnvJson).getOrElse { err ->
            postBanner("Credential error: ${MaskingUtils.sanitizeErrorMessage(err.message)}")
            return@launch
        }
        val res = mcpClient.executeTool(
            server = server,
            toolName = tool.toolName,
            argumentsJson = argsJson,
            authToken = authTok,
            envVarsJson = envJson,
            allowLocalHttp = settings.value.localNetworkAccessAllowed
        )
        val preview = if (res.success) res.output else "ERROR: ${res.errorMessage}"
        db.mcpDao().upsertTool(tool.copy(lastResultPreview = preview, lastExecutedAt = System.currentTimeMillis()))
        logEvent(ActivityCategory.MCP_TOOL, "${server.name}/${tool.toolName}", if (res.success) "SUCCESS" else "ERROR", preview.take(180))
        postBanner(if (res.success) "${tool.toolName} executed" else "Tool error: ${res.errorMessage}")
    }

    fun deleteMcpServer(server: McpServerEntity) = viewModelScope.launch {
        db.withTransaction {
            db.mcpDao().deleteServerById(server.id)
            db.mcpDao().deleteToolsForServer(server.id)
        }
    }

    private suspend fun requestMcpApprovalFromUi(server: McpServerEntity, tool: McpToolEntity): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        _pendingMcpApproval.value = PendingMcpApproval(server, tool, deferred)
        val result = deferred.await()
        _pendingMcpApproval.value = null
        return result
    }

    fun resolveMcpApproval(approved: Boolean) {
        _pendingMcpApproval.value?.deferred?.complete(approved)
    }

    // Workflows
    fun saveWorkflow(workflow: WorkflowEntity) = viewModelScope.launch {
        val existing = db.workflowDao().getWorkflowsOnce().firstOrNull { it.id == workflow.id }
        val nextVersion = when {
            existing == null -> workflow.version.coerceAtLeast(1)
            !workflow.isTemplate && (
                existing.nodesJson != workflow.nodesJson ||
                    existing.edgesJson != workflow.edgesJson ||
                    existing.name != workflow.name ||
                    existing.description != workflow.description
                ) -> maxOf(existing.version + 1, workflow.version)
            else -> workflow.version.coerceAtLeast(existing.version)
        }
        db.workflowDao().upsertWorkflow(
            workflow.copy(
                isTemplate = false,
                version = nextVersion,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    fun duplicateWorkflow(workflow: WorkflowEntity) = viewModelScope.launch {
        db.workflowDao().upsertWorkflow(
            workflow.copy(
                id = UUID.randomUUID().toString(),
                name = "${workflow.name} (Copy)",
                isTemplate = false,
                lastRunStatus = null,
                lastRunAt = null
            )
        )
    }

    fun deleteWorkflow(workflow: WorkflowEntity) = viewModelScope.launch {
        db.workflowDao().deleteWorkflow(workflow.id)
    }

    fun runWorkflow(workflow: WorkflowEntity, inputText: String) {
        workflowJob?.cancel()
        workflowJob = viewModelScope.launch {
            _isWorkflowRunning.value = true
            try {
                val run = workflowEngine.executeWorkflow(
                    workflow = workflow,
                    inputText = inputText,
                    settings = settings.value,
                    onProgress = { _workflowNodeProgress.value = it },
                    onRequestMcpApproval = { srv, tool -> requestMcpApprovalFromUi(srv, tool) }
                )
                postBanner("Workflow '${workflow.name}': ${run.status}")
            } catch (_: CancellationException) {
                postBanner("Workflow stopped")
            } finally {
                _isWorkflowRunning.value = false
            }
        }
    }

    fun stopWorkflow() {
        workflowJob?.cancel()
        _isWorkflowRunning.value = false
    }

    // Playground
    fun selectOrCreateSession(sessionId: String?) = viewModelScope.launch {
        if (sessionId != null) {
            _activeSessionId.value = sessionId
        } else {
            val s = PlaygroundSessionEntity(title = "Session ${playgroundSessions.value.size + 1}")
            if (settings.value.saveChatHistoryAllowed) {
                db.playgroundDao().upsertSession(s)
            }
            _activeSessionId.value = s.id
            _ephemeralMessages.value = emptyList()
        }
    }

    fun clearActiveChat() = viewModelScope.launch {
        _activeSessionId.value?.let { sid ->
            val metaList = db.playgroundDao().getAttachmentMetadataForSession(sid)
            PlaygroundAttachmentManager.deleteAttachmentsFromMetadata(app, metaList)
            db.playgroundDao().clearMessagesForSession(sid)
        }
        _ephemeralMessages.value = emptyList()
        PlaygroundAttachmentManager.cleanupEphemeralCache(app)
    }

    fun deleteSession(session: PlaygroundSessionEntity) = viewModelScope.launch {
        val metaList = db.playgroundDao().getAttachmentMetadataForSession(session.id)
        PlaygroundAttachmentManager.deleteAttachmentsFromMetadata(app, metaList)
        db.withTransaction {
            db.playgroundDao().deleteSession(session.id)
            db.playgroundDao().clearMessagesForSession(session.id)
        }
        if (_activeSessionId.value == session.id) _activeSessionId.value = null
    }

    fun openModelInPlayground(model: ModelEntity) {
        _playgroundTarget.value = PlaygroundTarget(
            providerId = model.providerId.takeIf { it.isNotBlank() },
            modelId = model.modelId
        )
    }

    fun consumePlaygroundTarget() {
        _playgroundTarget.value = null
    }

    fun stopPlaygroundStreaming() {
        val partial = _streamingBuffer.value
        val sid = _activeSessionId.value
        val saveHistory = settings.value.saveChatHistoryAllowed
        streamingJob?.cancel()
        streamingJob = null
        _streamingBuffer.value = ""
        _isStreaming.value = false
        if (partial.isNotBlank() && sid != null) {
            viewModelScope.launch {
                val partialMsg = PlaygroundMessageEntity(
                    sessionId = sid,
                    role = "assistant",
                    content = partial,
                    modelId = "Stopped",
                    providerName = ""
                )
                if (saveHistory) db.playgroundDao().insertMessage(partialMsg)
                else _ephemeralMessages.update { it + partialMsg }
            }
        }
    }

    private suspend fun validateManualAttachmentCapabilities(
        explicitProviderId: String?,
        modelIdOrAlias: String,
        attachments: List<PendingAttachment>
    ): String? {
        if (attachments.isEmpty() || explicitProviderId.isNullOrBlank()) return null
        val modelEntity = db.modelDao().getByProvider(explicitProviderId)
            .firstOrNull { it.modelId.equals(modelIdOrAlias, ignoreCase = true) }
        val apiFormat = if (explicitProviderId.startsWith("local:")) {
            val srvId = explicitProviderId.removePrefix("local:")
            val srv = db.localAiServerDao().getAllOnce().firstOrNull { it.id == srvId } ?: return null
            if (srv.engineType == LocalEngineType.OLLAMA) ApiFormat.OLLAMA else ApiFormat.OPENAI
        } else {
            val prov = db.providerDao().getById(explicitProviderId) ?: return null
            prov.apiFormat
        }
        val caps = ProviderAdapterRegistry.adapterFor(apiFormat).capabilitiesForModel(modelIdOrAlias, modelEntity)
        for (att in attachments) {
            if (att.category == AttachmentCategory.IMAGE && !caps.supportsVision) {
                return "Model '$modelIdOrAlias' (${apiFormat.name}) does not support image attachments."
            }
            if (att.category == AttachmentCategory.DOCUMENT && att.mimeType !in caps.supportedDocumentMimeTypes) {
                return "Model '$modelIdOrAlias' (${apiFormat.name}) does not support ${att.mimeType} files ('${att.displayName}')."
            }
        }
        return null
    }

    fun sendPlaygroundPrompt(
        prompt: String,
        systemPrompt: String,
        modelIdOrAlias: String,
        explicitProviderId: String?,
        temperature: Float,
        topP: Float,
        maxTokens: Int,
        stopSequences: List<String>,
        jsonOutput: Boolean,
        streaming: Boolean,
        isAutoMode: Boolean = false,
        attachments: List<PendingAttachment> = emptyList(),
        onRequestAccepted: () -> Unit = {},
        onValidationError: (String) -> Unit = {}
    ) {
        if (prompt.isBlank() && attachments.isEmpty()) return
        streamingJob?.cancel()
        streamingJob = viewModelScope.launch {
            val cleanExplicitProviderId = explicitProviderId?.takeIf { it.isNotBlank() }
            val needsVision = attachments.any { it.category == AttachmentCategory.IMAGE }
            val neededDocMimes = attachments.filter { it.category == AttachmentCategory.DOCUMENT }
                .map { it.mimeType }.toSet()

            // Pre-send capability validation before mutating conversation history or clearing draft
            if (isAutoMode || cleanExplicitProviderId == null) {
                val (autoCandidates, _) = router.resolveCandidates(
                    requestedModelOrAlias = "",
                    settings = settings.value,
                    mode = RoutingMode.AUTO_FREE,
                    explicitProviderId = null,
                    requireVision = needsVision,
                    requiredDocumentMimeTypes = neededDocMimes
                )
                if (autoCandidates.isEmpty()) {
                    val (unfilteredFree, _) = router.resolveCandidates(
                        requestedModelOrAlias = "",
                        settings = settings.value,
                        mode = RoutingMode.AUTO_FREE,
                        explicitProviderId = null
                    )
                    val errText = if (attachments.isNotEmpty() && unfilteredFree.isNotEmpty()) {
                        "No verified free models support the attached file type(s)."
                    } else {
                        "No verified free models available. Configure a free-tier provider, confirm free access in Models, or select a specific model."
                    }
                    onValidationError(errText)
                    postBanner(errText)
                    return@launch
                }
            } else {
                val unsupportedMsg = validateManualAttachmentCapabilities(
                    explicitProviderId = cleanExplicitProviderId,
                    modelIdOrAlias = modelIdOrAlias,
                    attachments = attachments
                )
                if (unsupportedMsg != null) {
                    onValidationError(unsupportedMsg)
                    postBanner(unsupportedMsg)
                    return@launch
                }
            }

            val userParts: List<ContentPart> = if (attachments.isNotEmpty()) {
                try {
                    withContext(Dispatchers.IO) {
                        PlaygroundAttachmentManager.buildContentParts(prompt, attachments)
                    }
                } catch (e: Exception) {
                    val safeMsg = MaskingUtils.sanitizeErrorMessage(e.message)
                    onValidationError(safeMsg)
                    postBanner(safeMsg)
                    return@launch
                }
            } else {
                listOf(ContentPart.Text(prompt))
            }

            onRequestAccepted()

            val saveHistory = settings.value.saveChatHistoryAllowed
            var sid = _activeSessionId.value
            val sessionTitle = prompt.trim().ifBlank { attachments.firstOrNull()?.displayName ?: "Attachment" }.take(28)
            if (sid == null) {
                val newSession = PlaygroundSessionEntity(title = sessionTitle, modelId = modelIdOrAlias, systemPrompt = systemPrompt)
                sid = newSession.id
                _activeSessionId.value = sid
                if (saveHistory) db.playgroundDao().upsertSession(newSession)
            }

            val metaJson = if (saveHistory) PlaygroundAttachmentManager.toMetadataJson(attachments) else "[]"
            val userMsg = PlaygroundMessageEntity(
                sessionId = sid,
                role = "user",
                content = prompt,
                attachmentsMetadataJson = metaJson
            )
            if (saveHistory) db.playgroundDao().insertMessage(userMsg)
            else _ephemeralMessages.update { it + userMsg }

            val history = if (saveHistory) db.playgroundDao().getMessagesOnce(sid) else _ephemeralMessages.value
            val payloadMsgs = buildList {
                if (systemPrompt.isNotBlank()) add(ChatMessagePayload("system", systemPrompt))
                val priorHistory = if (history.lastOrNull()?.id == userMsg.id) history.dropLast(1) else history
                priorHistory.takeLast(11).forEach { add(ChatMessagePayload(it.role, it.content)) }
                add(ChatMessagePayload(role = "user", content = prompt, contentParts = userParts))
            }

            val routingModeForRequest = if (isAutoMode || cleanExplicitProviderId == null) {
                RoutingMode.AUTO_FREE
            } else {
                RoutingMode.MANUAL
            }
            val allowFallbackForRequest = if (routingModeForRequest == RoutingMode.AUTO_FREE) {
                true
            } else {
                settings.value.manualModelFallbackEnabled && settings.value.fallbackEnabled
            }

            _isStreaming.value = true
            _streamingBuffer.value = ""

            try {
                if (streaming) {
                    router.streamRoutedChat(
                        requestedModelOrAlias = if (routingModeForRequest == RoutingMode.AUTO_FREE) "" else modelIdOrAlias,
                        messages = payloadMsgs,
                        settings = settings.value,
                        routingModeOverride = routingModeForRequest,
                        explicitProviderId = cleanExplicitProviderId,
                        deviceName = "Playground",
                        temperature = temperature,
                        topP = topP,
                        maxTokens = maxTokens,
                        stopSequences = stopSequences,
                        jsonOutput = jsonOutput,
                        requireVision = needsVision,
                        requiredDocumentMimeTypes = neededDocMimes,
                        fallbackOverride = allowFallbackForRequest
                    ).collect { (event, decision) ->
                        when (event) {
                            is StreamEvent.Delta -> _streamingBuffer.update { it + event.text }
                            is StreamEvent.Completed -> {
                                val pricing = ProviderPresets.lookupPricing(decision?.selectedModel.orEmpty())
                                val estCost = pricing?.let {
                                    (event.result.inputTokens / 1_000_000.0) * it.inputPer1M +
                                        (event.result.outputTokens / 1_000_000.0) * it.outputPer1M
                                }
                                val assistantMsg = PlaygroundMessageEntity(
                                    sessionId = sid,
                                    role = "assistant",
                                    content = event.result.content,
                                    modelId = decision?.selectedModel ?: modelIdOrAlias,
                                    providerName = decision?.selectedProvider.orEmpty(),
                                    latencyMs = event.result.latencyMs,
                                    inputTokens = event.result.inputTokens,
                                    outputTokens = event.result.outputTokens,
                                    estimatedCostUsd = estCost
                                )
                                if (saveHistory) db.playgroundDao().insertMessage(assistantMsg)
                                else _ephemeralMessages.update { it + assistantMsg }
                                _streamingBuffer.value = ""
                            }
                            is StreamEvent.Failed -> {
                                val errMsg = PlaygroundMessageEntity(
                                    sessionId = sid,
                                    role = "assistant",
                                    content = event.error.safeErrorMessage ?: "Request failed",
                                    modelId = decision?.selectedModel ?: modelIdOrAlias,
                                    providerName = decision?.selectedProvider.orEmpty(),
                                    latencyMs = event.error.latencyMs,
                                    isError = true
                                )
                                if (saveHistory) db.playgroundDao().insertMessage(errMsg)
                                else _ephemeralMessages.update { it + errMsg }
                                _streamingBuffer.value = ""
                            }
                        }
                    }
                } else {
                    val res = router.executeRoutedChat(
                        requestedModelOrAlias = if (routingModeForRequest == RoutingMode.AUTO_FREE) "" else modelIdOrAlias,
                        messages = payloadMsgs,
                        settings = settings.value,
                        routingModeOverride = routingModeForRequest,
                        explicitProviderId = cleanExplicitProviderId,
                        deviceName = "Playground",
                        temperature = temperature,
                        topP = topP,
                        maxTokens = maxTokens,
                        stopSequences = stopSequences,
                        jsonOutput = jsonOutput,
                        requireVision = needsVision,
                        requiredDocumentMimeTypes = neededDocMimes,
                        fallbackOverride = allowFallbackForRequest
                    )
                    val pricing = ProviderPresets.lookupPricing(res.decision.selectedModel)
                    val estCost = pricing?.let {
                        (res.execution.inputTokens / 1_000_000.0) * it.inputPer1M +
                            (res.execution.outputTokens / 1_000_000.0) * it.outputPer1M
                    }
                    val msg = PlaygroundMessageEntity(
                        sessionId = sid,
                        role = "assistant",
                        content = if (res.execution.success) res.execution.content else (res.execution.safeErrorMessage ?: "Error"),
                        modelId = res.decision.selectedModel,
                        providerName = res.decision.selectedProvider,
                        latencyMs = res.execution.latencyMs,
                        inputTokens = res.execution.inputTokens,
                        outputTokens = res.execution.outputTokens,
                        estimatedCostUsd = estCost,
                        isError = !res.execution.success
                    )
                    if (saveHistory) db.playgroundDao().insertMessage(msg)
                    else _ephemeralMessages.update { it + msg }
                }
            } finally {
                _isStreaming.value = false
                if (!saveHistory) {
                    attachments.forEach { att ->
                        PlaygroundAttachmentManager.deleteFileIfOwned(app, att.localFilePath)
                    }
                }
            }
        }
    }

    fun syncCompareModelSelection(selectedModels: List<ModelEntity>) {
        val uniqueModels = selectedModels.distinctBy { it.id }
        val selectedKeys = uniqueModels.map { it.id }
        val selectedKeySet = selectedKeys.toSet()

        val cancelledKeys = compareJobs.keys.filter { it !in selectedKeySet }
        cancelledKeys.forEach { key -> compareJobs.remove(key)?.cancel() }

        _compareState.update { prev ->
            val existingByKey = prev.entries.associateBy { it.entryKey }
            val reconciledEntries = uniqueModels.map { model ->
                existingByKey[model.id]?.copy(
                    displayName = model.displayName.ifBlank { model.modelId },
                    providerName = model.providerName,
                    supportsStreaming = model.supportsStreaming
                ) ?: CompareModelEntry(
                    entryKey = model.id,
                    modelId = model.modelId,
                    displayName = model.displayName.ifBlank { model.modelId },
                    providerId = model.providerId,
                    providerName = model.providerName,
                    supportsStreaming = model.supportsStreaming,
                    status = CompareRequestStatus.PENDING
                )
            }
            val prevActiveIdx = prev.entries.indexOfFirst { it.entryKey == prev.activeEntryKey }
            val reconciledActive = when {
                reconciledEntries.isEmpty() -> null
                prev.activeEntryKey != null && reconciledEntries.any { it.entryKey == prev.activeEntryKey } -> prev.activeEntryKey
                prevActiveIdx >= 0 -> reconciledEntries[prevActiveIdx.coerceIn(0, reconciledEntries.lastIndex)].entryKey
                else -> reconciledEntries.first().entryKey
            }
            val activeRunExists = prev.prompt.isNotBlank() && reconciledEntries.any { it.entryKey in compareJobs && it.status.isActive }
            prev.copy(
                selectedModelKeys = selectedKeys,
                entries = reconciledEntries,
                activeEntryKey = reconciledActive,
                expandedDetailsKeys = prev.expandedDetailsKeys.intersect(selectedKeySet),
                isRunning = activeRunExists
            )
        }
    }

    fun selectCompareEntry(entryKey: String) {
        _compareState.update { prev ->
            if (prev.entries.none { it.entryKey == entryKey } || prev.activeEntryKey == entryKey) prev
            else prev.copy(activeEntryKey = entryKey)
        }
    }

    fun toggleCompareDetails(entryKey: String) {
        _compareState.update { prev ->
            if (prev.entries.none { it.entryKey == entryKey }) prev
            else {
                val next = if (entryKey in prev.expandedDetailsKeys) {
                    prev.expandedDetailsKeys - entryKey
                } else {
                    prev.expandedDetailsKeys + entryKey
                }
                prev.copy(expandedDetailsKeys = next)
            }
        }
    }

    fun stopAllCompareRequests() {
        val jobs = compareJobs.values.toList()
        compareJobs.clear()
        compareRunCounter.incrementAndGet()
        jobs.forEach { it.cancel() }
        _compareState.update { prev ->
            prev.copy(
                entries = prev.entries.map { entry ->
                    if (entry.status.isActive) {
                        entry.copy(status = CompareRequestStatus.CANCELLED, isStreaming = false)
                    } else entry
                },
                isRunning = false
            )
        }
    }

    fun stopCompareModel(entryKey: String) {
        compareJobs.remove(entryKey)?.cancel()
        _compareState.update { prev ->
            val updated = prev.entries.map { entry ->
                if (entry.entryKey == entryKey && entry.status.isActive) {
                    entry.copy(status = CompareRequestStatus.CANCELLED, isStreaming = false)
                } else entry
            }
            val stillRunning = updated.any { it.entryKey in compareJobs && it.status.isActive }
            prev.copy(entries = updated, isRunning = stillRunning)
        }
    }

    fun retryCompareModel(entryKey: String) {
        val state = _compareState.value
        val prompt = state.prompt
        if (prompt.isBlank()) return
        val existing = state.entries.firstOrNull { it.entryKey == entryKey } ?: return
        val matchedModel = models.value.firstOrNull { it.id == entryKey } ?: ModelEntity(
            id = existing.entryKey,
            modelId = existing.modelId,
            displayName = existing.displayName,
            providerId = existing.providerId,
            providerName = existing.providerName,
            supportsStreaming = existing.supportsStreaming
        )
        compareJobs.remove(entryKey)?.cancel()
        val currentRunId = compareRunCounter.get()
        _compareState.update { prev ->
            prev.copy(
                entries = prev.entries.map { entry ->
                    if (entry.entryKey == entryKey) {
                        entry.copy(
                            status = CompareRequestStatus.QUEUED,
                            responseText = "",
                            isStreaming = false,
                            errorMessage = null,
                            latencyMs = null,
                            ttftMs = null,
                            inputTokens = null,
                            outputTokens = null,
                            totalTokens = null,
                            usageEstimated = false,
                            estimatedCostUsd = null,
                            actualCostUsd = null,
                            finishReason = null,
                            httpStatus = null,
                            requestId = null
                        )
                    } else entry
                },
                isRunning = true
            )
        }
        launchCompareModelJob(
            entryKey = entryKey,
            model = matchedModel,
            prompt = prompt,
            systemPrompt = state.systemPrompt,
            config = lastCompareRequestConfig,
            runId = currentRunId
        )
    }

    fun runModelComparison(
        prompt: String,
        systemPrompt: String,
        selectedModels: List<ModelEntity>,
        temperature: Float? = null,
        topP: Float? = null,
        maxTokens: Int? = null,
        stopSequences: List<String> = emptyList(),
        jsonOutput: Boolean = false,
        streaming: Boolean = true
    ) {
        val uniqueModels = selectedModels.distinctBy { it.id }
        if (prompt.isBlank() || uniqueModels.isEmpty()) return

        compareJobs.values.toList().forEach { it.cancel() }
        compareJobs.clear()

        val runId = compareRunCounter.incrementAndGet()
        val config = CompareRequestConfig(
            temperature = temperature,
            topP = topP,
            maxTokens = maxTokens,
            stopSequences = stopSequences,
            jsonOutput = jsonOutput,
            streaming = streaming
        )
        lastCompareRequestConfig = config

        val initialEntries = uniqueModels.map { m ->
            CompareModelEntry(
                entryKey = m.id,
                modelId = m.modelId,
                displayName = m.displayName.ifBlank { m.modelId },
                providerId = m.providerId,
                providerName = m.providerName,
                supportsStreaming = m.supportsStreaming,
                status = CompareRequestStatus.QUEUED
            )
        }
        _compareState.update { prev ->
            val keptActive = prev.activeEntryKey?.takeIf { key -> initialEntries.any { it.entryKey == key } }
                ?: initialEntries.first().entryKey
            prev.copy(
                selectedModelKeys = uniqueModels.map { it.id },
                prompt = prompt,
                systemPrompt = systemPrompt,
                entries = initialEntries,
                activeEntryKey = keptActive,
                expandedDetailsKeys = emptySet(),
                isRunning = true
            )
        }

        uniqueModels.forEach { m ->
            launchCompareModelJob(
                entryKey = m.id,
                model = m,
                prompt = prompt,
                systemPrompt = systemPrompt,
                config = config,
                runId = runId
            )
        }
    }

    private fun launchCompareModelJob(
        entryKey: String,
        model: ModelEntity,
        prompt: String,
        systemPrompt: String,
        config: CompareRequestConfig,
        runId: Long
    ) {
        val msgs = buildList {
            if (systemPrompt.isNotBlank()) add(ChatMessagePayload("system", systemPrompt))
            add(ChatMessagePayload("user", prompt))
        }
        val job = viewModelScope.launch {
            val requestStartNs = System.nanoTime()
            var firstTokenMs: Long? = null
            updateCompareEntryIfActiveRun(runId, entryKey) {
                it.copy(status = CompareRequestStatus.RUNNING, isStreaming = false, errorMessage = null)
            }
            try {
                if (config.streaming && model.supportsStreaming) {
                    router.streamRoutedChat(
                        requestedModelOrAlias = model.modelId,
                        messages = msgs,
                        settings = settings.value,
                        explicitProviderId = model.providerId,
                        deviceName = "Playground Compare",
                        temperature = config.temperature,
                        topP = config.topP,
                        maxTokens = config.maxTokens,
                        stopSequences = config.stopSequences,
                        jsonOutput = config.jsonOutput
                    ).collect { (event, decision) ->
                        when (event) {
                            is StreamEvent.Delta -> {
                                if (event.text.isNotEmpty() || event.toolCallDeltas.isNotEmpty()) {
                                    if (firstTokenMs == null) {
                                        firstTokenMs = ((System.nanoTime() - requestStartNs) / 1_000_000L).coerceAtLeast(0L)
                                    }
                                    updateCompareEntryIfActiveRun(runId, entryKey) { current ->
                                        current.copy(
                                            status = CompareRequestStatus.STREAMING,
                                            isStreaming = true,
                                            ttftMs = firstTokenMs,
                                            responseText = current.responseText + event.text,
                                            finishReason = event.finishReason ?: current.finishReason
                                        )
                                    }
                                }
                            }
                            is StreamEvent.Completed -> {
                                val resolvedModel = decision?.selectedModel?.ifBlank { model.modelId } ?: model.modelId
                                val resolvedUsage = decision?.resolvedUsage
                                val usageKnown = event.result.usageReported ||
                                    (resolvedUsage != null && resolvedUsage.source != com.example.core.security.UsageSource.UNKNOWN)
                                val inTok = if (usageKnown) (resolvedUsage?.inputTokens ?: event.result.inputTokens) else null
                                val outTok = if (usageKnown) (resolvedUsage?.outputTokens ?: event.result.outputTokens) else null
                                val totalTok = if (inTok != null && outTok != null) inTok + outTok else null
                                val isEstUsage = !event.result.usageReported &&
                                    resolvedUsage?.source == com.example.core.security.UsageSource.ESTIMATED
                                val pricing = ProviderPresets.lookupPricing(resolvedModel)
                                val estCost = if (pricing != null && inTok != null && outTok != null) {
                                    (inTok / 1_000_000.0) * pricing.inputPer1M +
                                        (outTok / 1_000_000.0) * pricing.outputPer1M
                                } else null
                                updateCompareEntryIfActiveRun(runId, entryKey) { current ->
                                    current.copy(
                                        requestId = decision?.requestId ?: current.requestId,
                                        status = CompareRequestStatus.COMPLETED,
                                        isStreaming = false,
                                        responseText = event.result.content.ifEmpty { current.responseText },
                                        errorMessage = null,
                                        latencyMs = event.result.latencyMs,
                                        ttftMs = firstTokenMs,
                                        inputTokens = inTok,
                                        outputTokens = outTok,
                                        totalTokens = totalTok,
                                        usageEstimated = isEstUsage,
                                        estimatedCostUsd = estCost,
                                        actualCostUsd = event.result.actualBilledCostUsd,
                                        finishReason = event.result.finishReason.ifBlank { null },
                                        httpStatus = event.result.statusCode
                                    )
                                }
                            }
                            is StreamEvent.Failed -> {
                                updateCompareEntryIfActiveRun(runId, entryKey) { current ->
                                    current.copy(
                                        requestId = decision?.requestId ?: current.requestId,
                                        status = CompareRequestStatus.FAILED,
                                        isStreaming = false,
                                        errorMessage = event.error.safeErrorMessage ?: "Request failed",
                                        latencyMs = event.error.latencyMs.takeIf { it > 0L },
                                        ttftMs = firstTokenMs,
                                        httpStatus = event.error.statusCode.takeIf { it > 0 },
                                        finishReason = event.error.finishReason.ifBlank { null }
                                    )
                                }
                            }
                        }
                    }
                } else {
                    val routed = router.executeRoutedChat(
                        requestedModelOrAlias = model.modelId,
                        messages = msgs,
                        settings = settings.value,
                        explicitProviderId = model.providerId,
                        deviceName = "Playground Compare",
                        temperature = config.temperature,
                        topP = config.topP,
                        maxTokens = config.maxTokens,
                        stopSequences = config.stopSequences,
                        jsonOutput = config.jsonOutput
                    )
                    if (routed.execution.success) {
                        val resolvedUsage = routed.decision.resolvedUsage
                        val usageKnown = routed.execution.usageReported ||
                            resolvedUsage.source != com.example.core.security.UsageSource.UNKNOWN
                        val inTok = if (usageKnown) resolvedUsage.inputTokens else null
                        val outTok = if (usageKnown) resolvedUsage.outputTokens else null
                        val totalTok = if (inTok != null && outTok != null) inTok + outTok else null
                        val isEstUsage = !routed.execution.usageReported &&
                            resolvedUsage.source == com.example.core.security.UsageSource.ESTIMATED
                        val pricing = ProviderPresets.lookupPricing(model.modelId)
                        val estCost = if (pricing != null && inTok != null && outTok != null) {
                            (inTok / 1_000_000.0) * pricing.inputPer1M +
                                (outTok / 1_000_000.0) * pricing.outputPer1M
                        } else null
                        updateCompareEntryIfActiveRun(runId, entryKey) { current ->
                            current.copy(
                                requestId = routed.decision.requestId,
                                status = CompareRequestStatus.COMPLETED,
                                isStreaming = false,
                                responseText = routed.execution.content,
                                errorMessage = null,
                                latencyMs = routed.execution.latencyMs,
                                ttftMs = null,
                                inputTokens = inTok,
                                outputTokens = outTok,
                                totalTokens = totalTok,
                                usageEstimated = isEstUsage,
                                estimatedCostUsd = estCost,
                                actualCostUsd = routed.execution.actualBilledCostUsd,
                                finishReason = routed.execution.finishReason.ifBlank { null },
                                httpStatus = routed.execution.statusCode
                            )
                        }
                    } else {
                        updateCompareEntryIfActiveRun(runId, entryKey) { current ->
                            current.copy(
                                requestId = routed.decision.requestId,
                                status = CompareRequestStatus.FAILED,
                                isStreaming = false,
                                errorMessage = routed.execution.safeErrorMessage ?: "Request failed",
                                latencyMs = routed.execution.latencyMs.takeIf { it > 0L },
                                httpStatus = routed.execution.statusCode.takeIf { it > 0 },
                                finishReason = routed.execution.finishReason.ifBlank { null }
                            )
                        }
                    }
                }
            } catch (ce: CancellationException) {
                updateCompareEntryIfActiveRun(runId, entryKey) { current ->
                    if (current.status.isActive) {
                        current.copy(status = CompareRequestStatus.CANCELLED, isStreaming = false)
                    } else current
                }
                throw ce
            } catch (e: Exception) {
                val safeMsg = MaskingUtils.sanitizeErrorMessage(e.message)
                updateCompareEntryIfActiveRun(runId, entryKey) { current ->
                    current.copy(
                        status = CompareRequestStatus.FAILED,
                        isStreaming = false,
                        errorMessage = safeMsg
                    )
                }
            } finally {
                if (compareRunCounter.get() == runId && compareJobs[entryKey]?.isActive != true) {
                    compareJobs.remove(entryKey)
                    refreshCompareRunningState()
                }
            }
        }
        compareJobs[entryKey] = job
        refreshCompareRunningState()
    }

    private fun updateCompareEntryIfActiveRun(
        runId: Long,
        entryKey: String,
        transform: (CompareModelEntry) -> CompareModelEntry
    ) {
        if (compareRunCounter.get() != runId) return
        _compareState.update { prev ->
            var changed = false
            val updated = prev.entries.map { entry ->
                if (entry.entryKey == entryKey) {
                    changed = true
                    transform(entry)
                } else entry
            }
            if (!changed) prev else prev.copy(
                entries = updated,
                isRunning = updated.any { it.status.isActive && it.entryKey in compareJobs }
            )
        }
    }

    private fun refreshCompareRunningState() {
        _compareState.update { prev ->
            val active = prev.entries.any { it.status.isActive && it.entryKey in compareJobs }
            if (prev.isRunning == active) prev else prev.copy(isRunning = active)
        }
    }

    // Devices
    fun addDevice(
        name: String,
        type: String,
        connectionType: DeviceConnectionType,
        permissionsCsv: String = DeviceScopePolicy.DEFAULT_SCOPES_CSV,
        allowedModelsCsv: String = ""
    ) = viewModelScope.launch {
        val raw = CryptoManager.generateGatewayToken("jhgw_dev")
        val enc = runCatching { CryptoManager.encrypt(raw) }.getOrElse { err ->
            postBanner(MaskingUtils.sanitizeErrorMessage(err.message, listOf(raw)))
            return@launch
        }
        val normalizedScopes = DeviceScopePolicy.formatScopesCsv(DeviceScopePolicy.parseScopes(permissionsCsv))
        val dev = DeviceEntity(
            name = name.trim(),
            deviceType = type.trim(),
            deviceCode = "DEV-" + UUID.randomUUID().toString().take(6).uppercase(),
            encryptedToken = enc,
            tokenPreview = MaskingUtils.maskSecret(raw),
            permissionsCsv = normalizedScopes,
            allowedModelsCsv = allowedModelsCsv.trim(),
            connectionType = connectionType
        )
        db.deviceDao().upsert(dev)
        logEvent(ActivityCategory.DEVICE_CONNECTION, dev.name, "REGISTERED", "Device token provisioned (scopes: ${normalizedScopes.ifBlank { "NONE" }})")
    }

    fun updateDeviceAccess(device: DeviceEntity, permissionsCsv: String, allowedModelsCsv: String) = viewModelScope.launch {
        val normalizedScopes = DeviceScopePolicy.formatScopesCsv(DeviceScopePolicy.parseScopes(permissionsCsv))
        db.deviceDao().upsert(
            device.copy(
                permissionsCsv = normalizedScopes,
                allowedModelsCsv = allowedModelsCsv.trim()
            )
        )
        logEvent(ActivityCategory.AUTH_EVENT, device.name, "PERMISSIONS_UPDATED", "Updated device scopes (${normalizedScopes.ifBlank { "NONE" }})")
    }

    fun decryptDeviceTokenResult(device: DeviceEntity): Result<String> =
        CryptoManager.decryptResult(device.encryptedToken)
    fun regenerateDeviceToken(device: DeviceEntity) = viewModelScope.launch {
        val raw = CryptoManager.generateGatewayToken("jhgw_dev")
        val enc = runCatching { CryptoManager.encrypt(raw) }.getOrElse { err ->
            postBanner(MaskingUtils.sanitizeErrorMessage(err.message, listOf(raw)))
            return@launch
        }
        db.deviceDao().upsert(device.copy(encryptedToken = enc, tokenPreview = MaskingUtils.maskSecret(raw), revoked = false))
        logEvent(ActivityCategory.DEVICE_CONNECTION, device.name, "TOKEN_ROTATED", "Device token regenerated")
    }
    fun revokeDevice(device: DeviceEntity) = viewModelScope.launch {
        db.deviceDao().upsert(device.copy(revoked = true, enabled = false))
        logEvent(ActivityCategory.AUTH_EVENT, device.name, "REVOKED", "Device access revoked")
    }
    fun toggleDeviceEnabled(device: DeviceEntity) = viewModelScope.launch {
        if (!device.revoked) db.deviceDao().upsert(device.copy(enabled = !device.enabled))
    }
    fun renameDevice(device: DeviceEntity, newName: String) = viewModelScope.launch {
        db.deviceDao().upsert(device.copy(name = newName.trim()))
    }

    // Live Gateway Endpoint Test (calls active Local Ktor CIO server or connected Remote HTTPS Gateway)
    fun testLiveGatewayEndpoint(endpointPath: String, modelId: String, prompt: String, onResult: (String) -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        val s = settings.value
        val rt = gatewayRuntime.value
        if (!rt.isActive) {
            withContext(Dispatchers.Main) { onResult("Gateway is not active (${rt.status.name}). Start Local Gateway or verify Remote Health first.") }
            return@launch
        }
        val base = rt.boundAddress
        val token = if (s.gatewayMode == GatewayMode.LOCAL) {
            val method = if (endpointPath == "/v1/models" || endpointPath == "/health") "GET" else "POST"
            val reqScope = DeviceScopePolicy.requiredScopeForGatewayPath(endpointPath, method)
            val enabledDevices = devices.value.filter { !it.revoked && it.enabled }
            val activeDev = if (reqScope != null) {
                enabledDevices.firstOrNull { dev ->
                    DeviceScopePolicy.hasScope(DeviceScopePolicy.parseScopes(dev.permissionsCsv), reqScope)
                } ?: enabledDevices.firstOrNull()
            } else {
                enabledDevices.firstOrNull()
            }
            if (activeDev != null) {
                CryptoManager.decryptResult(activeDev.encryptedToken).getOrElse { err ->
                    withContext(Dispatchers.Main) { onResult("Device token decryption failed: ${err.message}") }
                    return@launch
                }
            } else ""
        } else {
            if (s.gatewayRemoteAuthTokenEnc.isNotBlank()) {
                CryptoManager.decryptResult(s.gatewayRemoteAuthTokenEnc).getOrNull().orEmpty()
            } else ""
        }
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        val reqBuilder = Request.Builder().url("${base.trimEnd('/')}$endpointPath")
        if (token.isNotBlank()) reqBuilder.header("Authorization", "Bearer $token")

        if (endpointPath == "/v1/models" || endpointPath == "/health") {
            reqBuilder.get()
        } else {
            val payload = when (endpointPath) {
                "/v1/messages" -> JSONObject()
                    .put("model", modelId)
                    .put("max_tokens", 256)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                "/v1/responses" -> JSONObject()
                    .put("model", modelId)
                    .put("input", prompt)
                else -> JSONObject()
                    .put("model", modelId)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            }
            reqBuilder.post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        }
        val outText = runCatching {
            client.newCall(reqBuilder.build()).execute().use { resp ->
                "HTTP ${resp.code}\n${resp.body?.string().orEmpty()}"
            }
        }.getOrElse { "Request failed: ${MaskingUtils.sanitizeErrorMessage(it.message, listOf(token))}" }
        withContext(Dispatchers.Main) { onResult(outText) }
    }

    // Settings, Security, Backup & Logs
    fun updateGatewayConfig(
        name: String,
        mode: GatewayMode,
        host: String,
        port: Int,
        remoteUrl: String,
        authRequired: Boolean,
        timeoutSec: Int,
        maxReqKb: Int,
        streaming: Boolean,
        rateLimit: Int,
        loggingLevel: LoggingLevel,
        allowLanExposure: Boolean = false,
        remoteAuthTokenPlain: String? = null
    ) = viewModelScope.launch {
        val encRemoteToken = when {
            remoteAuthTokenPlain == null -> null
            remoteAuthTokenPlain.isBlank() -> ""
            else -> runCatching { CryptoManager.encrypt(remoteAuthTokenPlain.trim()) }.getOrElse { err ->
                postBanner(MaskingUtils.sanitizeErrorMessage(err.message, listOf(remoteAuthTokenPlain)))
                return@launch
            }
        }
        prefs.updateGatewaySettings(
            name = name,
            mode = mode,
            host = host,
            port = port,
            remoteUrl = remoteUrl,
            authRequired = authRequired,
            timeoutSec = timeoutSec,
            maxRequestKb = maxReqKb,
            streaming = streaming,
            rateLimitPerMin = rateLimit,
            loggingLevel = loggingLevel,
            allowLanExposure = allowLanExposure,
            remoteAuthTokenEnc = encRemoteToken
        )
        if (mode != GatewayMode.LOCAL) {
            stopLocalGatewayServiceIfNeeded()
        }
    }

    fun setSaveChatHistory(allowed: Boolean) = viewModelScope.launch { prefs.setSaveChatHistory(allowed) }
    fun setCurrency(currency: String) = viewModelScope.launch { prefs.setCurrency(currency) }
    fun setLogRetentionDays(days: Int) = viewModelScope.launch { prefs.setLogRetentionDays(days) }
    fun setUsageRetentionDays(days: Int) = viewModelScope.launch { prefs.setUsageRetentionDays(days) }
    fun setPlaygroundRetentionDays(days: Int) = viewModelScope.launch { prefs.setPlaygroundRetentionDays(days) }
    fun runRetentionCleanupNow() = viewModelScope.launch(Dispatchers.IO) {
        val s = settings.value
        val report = retentionManager.enforcePolicies(
            logRetentionDays = s.logRetentionDays,
            usageRetentionDays = s.usageRetentionDays,
            playgroundRetentionDays = s.playgroundRetentionDays,
            onExpiredAttachments = { metaList ->
                PlaygroundAttachmentManager.deleteAttachmentsFromMetadata(app, metaList)
            }
        )
        logEvent(
            ActivityCategory.SYSTEM_EVENT,
            "Retention Policy",
            "CLEANUP_APPLIED",
            "Pruned ${report.deletedLogs} logs, ${report.deletedMetrics} metrics, ${report.deletedWorkflowRuns} runs, ${report.deletedPlaygroundSessions} sessions"
        )
        postBanner("Retention applied: ${report.totalDeleted} expired records removed")
    }
    fun updateNotificationPrefs(pf: Boolean, gd: Boolean, rl: Boolean, wd: Boolean, sec: Boolean) = viewModelScope.launch {
        prefs.updateNotificationPrefs(pf, gd, rl, wd, sec)
    }

    fun setAppLockPin(enabled: Boolean, rawPin: String) = viewModelScope.launch {
        if (enabled && appLockManager.isValidPinFormat(rawPin)) {
            val hash = withContext(Dispatchers.Default) { appLockManager.hashPin(rawPin) }
            prefs.setAppLock(true, hash)
            _appLockUiState.value = AppLockUiState()
            _isAppUnlocked.value = true
            logEvent(ActivityCategory.AUTH_EVENT, "App Lock", "PIN_ENABLED", "Salted PBKDF2 App Lock passcode configured")
        } else if (!enabled) {
            prefs.setAppLock(false, "")
            _appLockUiState.value = AppLockUiState()
            _isAppUnlocked.value = true
            logEvent(ActivityCategory.AUTH_EVENT, "App Lock", "PIN_DISABLED", "App Lock passcode disabled")
        }
    }

    fun setAppLockAutoLockOnBackground(enabled: Boolean) = viewModelScope.launch {
        prefs.setAppLockAutoLockOnBackground(enabled)
    }

    fun setAppLockBiometricEnabled(enabled: Boolean) = viewModelScope.launch {
        prefs.setAppLockBiometricEnabled(enabled)
    }

    fun verifyUnlockPin(rawPin: String) {
        if (_appLockUiState.value.isVerifying) return
        viewModelScope.launch {
            _appLockUiState.update { it.copy(isVerifying = true, isInvalid = false) }
            val current = prefs.settingsFlow.first()
            val result = withContext(Dispatchers.Default) {
                appLockManager.verifyPin(
                    rawPin = rawPin,
                    storedHash = current.appLockPinHash,
                    currentLockout = LockoutState(
                        failedAttempts = current.appLockFailedAttempts,
                        lockedUntilEpochMs = current.appLockLockedUntilMs
                    )
                )
            }
            when (result) {
                is PinVerifyResult.Success -> {
                    result.upgradedHash?.let { prefs.setAppLockPinHashOnly(it) }
                    prefs.setAppLockLockoutState(0, 0L)
                    _appLockUiState.value = AppLockUiState()
                    _isAppUnlocked.value = true
                }
                is PinVerifyResult.InvalidPin -> {
                    prefs.setAppLockLockoutState(result.failedAttempts, result.lockedUntilEpochMs)
                    _appLockUiState.value = AppLockUiState(
                        isInvalid = true,
                        failedAttempts = result.failedAttempts,
                        lockedUntilEpochMs = result.lockedUntilEpochMs,
                        isVerifying = false
                    )
                }
                is PinVerifyResult.LockedOut -> {
                    prefs.setAppLockLockoutState(result.failedAttempts, result.lockedUntilEpochMs)
                    _appLockUiState.value = AppLockUiState(
                        isInvalid = true,
                        failedAttempts = result.failedAttempts,
                        lockedUntilEpochMs = result.lockedUntilEpochMs,
                        isVerifying = false
                    )
                }
            }
        }
    }

    fun clearPinInputError() {
        _appLockUiState.update { it.copy(isInvalid = false) }
    }

    fun unlockWithBiometric() = viewModelScope.launch {
        val current = prefs.settingsFlow.first()
        if (current.appLockLockedUntilMs > System.currentTimeMillis()) {
            _appLockUiState.update {
                it.copy(
                    isInvalid = true,
                    failedAttempts = current.appLockFailedAttempts,
                    lockedUntilEpochMs = current.appLockLockedUntilMs
                )
            }
            return@launch
        }
        prefs.setAppLockLockoutState(0, 0L)
        _appLockUiState.value = AppLockUiState()
        _isAppUnlocked.value = true
    }

    fun onAppBackgrounded() {
        val s = settings.value
        if (s.appLockEnabled && s.appLockAutoLockOnBackground && s.appLockPinHash.isNotBlank()) {
            _isAppUnlocked.value = false
            _streamingBuffer.value = ""
            _ephemeralMessages.value = emptyList()
            _appLockUiState.value = AppLockUiState(
                failedAttempts = s.appLockFailedAttempts,
                lockedUntilEpochMs = s.appLockLockedUntilMs
            )
        }
    }

    suspend fun exportEncryptedBackup(passphrase: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            backupManager.exportEncryptedBackup(passphrase).also {
                logEvent(ActivityCategory.SYSTEM_EVENT, "Encrypted Backup", "EXPORTED", "Encrypted V2 backup archive exported (PBKDF2+AES-GCM)")
            }
        }.recoverCatching { err ->
            throw IllegalStateException(MaskingUtils.sanitizeErrorMessage(err.message, listOf(passphrase)), err)
        }
    }

    suspend fun previewEncryptedBackup(payload: String, passphrase: String): Result<BackupRestorePreview> =
        withContext(Dispatchers.IO) {
            runCatching { backupManager.previewEncryptedBackup(payload, passphrase) }.recoverCatching { err ->
                throw IllegalStateException(MaskingUtils.sanitizeErrorMessage(err.message, listOf(passphrase)), err)
            }
        }

    suspend fun restoreEncryptedBackup(
        payload: String,
        passphrase: String,
        strategy: RestoreConflictStrategy = RestoreConflictStrategy.OVERWRITE_EXISTING
    ): Result<RestoreResult> = withContext(Dispatchers.IO) {
        runCatching {
            backupManager.restoreEncryptedBackupDetailed(payload, passphrase, strategy).also { res ->
                logEvent(
                    ActivityCategory.SYSTEM_EVENT,
                    "Encrypted Backup",
                    "RESTORED",
                    "Restored ${res.importedCount} records (${res.strategy.name}, skipped ${res.skippedConflicts})"
                )
            }
        }.recoverCatching { err ->
            throw IllegalStateException(MaskingUtils.sanitizeErrorMessage(err.message, listOf(passphrase)), err)
        }
    }

    fun clearLogs() = viewModelScope.launch { db.metricAndLogDao().clearLogs() }

    fun resetSafeAppSettings() = viewModelScope.launch {
        runCatching {
            prefs.resetSafeAppSettings()
        }.fold(
            onSuccess = {
                postBanner("App settings reset to defaults. User data, providers, keys, models, agents, and chats were preserved.")
            },
            onFailure = { err ->
                postBanner(MaskingUtils.sanitizeErrorMessage(err.message))
            }
        )
    }

    fun clearAllLocalData() = viewModelScope.launch {
        prefs.setGatewayServiceDesiredRunning(false)
        GatewayForegroundService.dispatchStop(app)
        gatewayServer.stop()
        db.withTransaction {
            db.providerDao().clearAll()
            db.apiKeyDao().clearAll()
            db.modelDao().clearAll()
            db.routingRuleDao().clearAll()
            db.codingAgentDao().clearAll()
            db.localAiServerDao().clearAll()
            db.mcpDao().clearServers()
            db.mcpDao().clearTools()
            db.workflowDao().clearRuns()
            db.workflowDao().clearWorkflows()
            db.deviceDao().clearAll()
            db.metricAndLogDao().clearMetrics()
            db.metricAndLogDao().clearLogs()
            db.playgroundDao().clearMessages()
            db.playgroundDao().clearSessions()
            workflowEngine.builtInTemplates().forEach { db.workflowDao().upsertWorkflow(it) }
        }
        PlaygroundAttachmentManager.cleanupAllOwnedAttachments(app)
        postBanner("Local data cleared")
    }

    private suspend fun logEvent(category: ActivityCategory, related: String, status: String, message: String) {
        db.metricAndLogDao().insertLog(
            ActivityLogEntity(category = category, relatedEntity = related, status = status, safeMessage = message)
        )
    }

    private fun buildHeadersJson(raw: String): String {
        val lines = raw.lines().map { it.trim() }.filter { it.contains(":") }
        if (lines.isEmpty()) return ""
        val obj = JSONObject()
        lines.forEach { line ->
            val idx = line.indexOf(":")
            val k = line.substring(0, idx).trim()
            val v = line.substring(idx + 1).trim()
            if (k.isNotBlank()) obj.put(k, v)
        }
        return obj.toString()
    }

    private fun buildEnvJson(raw: String): String {
        val lines = raw.lines().map { it.trim() }.filter { it.contains("=") }
        if (lines.isEmpty()) return ""
        val obj = JSONObject()
        lines.forEach { line ->
            val idx = line.indexOf("=")
            val k = line.substring(0, idx).trim()
            val v = line.substring(idx + 1).trim()
            if (k.isNotBlank()) obj.put(k, v)
        }
        return obj.toString()
    }

    // In-App GitHub Releases Self-Updater
    fun effectiveGithubUpdateRepo(state: GatewaySettingsState = settings.value): String {
        return state.githubUpdateRepo.trim().ifBlank { BuildConfig.GITHUB_REPO.trim() }
    }

    fun setGithubUpdateRepo(rawInput: String) = viewModelScope.launch {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            prefs.setGithubUpdateRepo("")
            _appUpdateUiState.update { it.copy(errorMessage = null, statusMessage = null) }
            postBanner("GitHub repository cleared")
            return@launch
        }
        val normalized = AppUpdateManager.normalizeGithubRepo(trimmed)
        if (normalized == null) {
            _appUpdateUiState.update { it.copy(errorMessage = "Invalid format. Use owner/repo (e.g. user/jh-getway).") }
            return@launch
        }
        prefs.setGithubUpdateRepo(normalized)
        _appUpdateUiState.update { it.copy(errorMessage = null) }
        postBanner("Update repository saved: $normalized")
    }

    fun setAutoCheckUpdates(enabled: Boolean) = viewModelScope.launch {
        prefs.setAutoCheckUpdates(enabled)
    }

    fun checkForAppUpdates(silent: Boolean = false) {
        if (_appUpdateUiState.value.isChecking || _appUpdateUiState.value.isDownloading) return
        val currentSettings = settings.value
        val repoSlug = effectiveGithubUpdateRepo(currentSettings)
        if (repoSlug.isBlank()) {
            if (!silent) {
                _appUpdateUiState.update {
                    it.copy(errorMessage = "Set a GitHub repository (owner/repo) first.", statusMessage = null)
                }
            }
            return
        }
        updateJob?.cancel()
        updateJob = viewModelScope.launch {
            _appUpdateUiState.update {
                it.copy(isChecking = true, errorMessage = null, statusMessage = null)
            }
            val res = AppUpdateManager.checkForUpdate(
                repoInput = repoSlug,
                currentVersionCode = BuildConfig.VERSION_CODE,
                currentVersionName = BuildConfig.VERSION_NAME
            )
            when (res) {
                is AppUpdateCheckResult.UpdateAvailable -> {
                    val skipped = currentSettings.skippedUpdateTag.isNotBlank() &&
                        currentSettings.skippedUpdateTag == res.release.tagName
                    _appUpdateUiState.update {
                        it.copy(
                            isChecking = false,
                            availableRelease = res.release,
                            downloadedApkFile = null,
                            statusMessage = "Update ${res.release.tagName} available",
                            errorMessage = null,
                            showPromptDialog = !silent || !skipped,
                            signingConflict = false
                        )
                    }
                    logEvent(ActivityCategory.SYSTEM_EVENT, "App Updater", "UPDATE_FOUND", "Release ${res.release.tagName} available from $repoSlug")
                }
                is AppUpdateCheckResult.UpToDate -> {
                    _appUpdateUiState.update {
                        it.copy(
                            isChecking = false,
                            availableRelease = null,
                            statusMessage = "Up to date (v${res.currentVersionName})",
                            errorMessage = null,
                            showPromptDialog = false,
                            signingConflict = false
                        )
                    }
                    if (!silent) {
                        postBanner("You are on the latest version (${res.latestTag})")
                    }
                }
                is AppUpdateCheckResult.NoReleasesPublished -> {
                    _appUpdateUiState.update {
                        it.copy(
                            isChecking = false,
                            availableRelease = null,
                            statusMessage = if (silent) null else "No releases published yet for ${res.repoSlug}.",
                            errorMessage = null,
                            showPromptDialog = false,
                            signingConflict = false
                        )
                    }
                }
                is AppUpdateCheckResult.Error -> {
                    _appUpdateUiState.update {
                        it.copy(
                            isChecking = false,
                            errorMessage = if (silent) null else res.message,
                            showPromptDialog = false,
                            signingConflict = false
                        )
                    }
                }
            }
        }
    }

    fun downloadAndInstallUpdate() {
        val release = _appUpdateUiState.value.availableRelease ?: return
        if (_appUpdateUiState.value.isDownloading) return
        viewModelScope.launch {
            _appUpdateUiState.update {
                it.copy(
                    isDownloading = true,
                    downloadProgressPct = 0,
                    errorMessage = null,
                    signingConflict = false,
                    statusMessage = "Downloading ${release.apkFileName}..."
                )
            }
            val dlRes = AppUpdateManager.downloadApkWithProgress(
                context = app,
                release = release,
                onProgress = { pct ->
                    _appUpdateUiState.update { it.copy(downloadProgressPct = pct) }
                }
            )
            dlRes.fold(
                onSuccess = { apkFile ->
                    _appUpdateUiState.update {
                        it.copy(
                            isDownloading = false,
                            downloadProgressPct = 100,
                            downloadedApkFile = apkFile,
                            statusMessage = "Ready to install ${release.tagName}",
                            errorMessage = null,
                            signingConflict = false
                        )
                    }
                    logEvent(ActivityCategory.SYSTEM_EVENT, "App Updater", "APK_DOWNLOADED", "Downloaded ${release.apkFileName}")
                    launchDownloadedApkInstall()
                },
                onFailure = { err ->
                    val safeMsg = MaskingUtils.sanitizeErrorMessage(err.message)
                    _appUpdateUiState.update {
                        it.copy(
                            isDownloading = false,
                            errorMessage = safeMsg,
                            statusMessage = null,
                            signingConflict = false
                        )
                    }
                    postBanner("Update download failed: $safeMsg")
                }
            )
        }
    }

    fun launchDownloadedApkInstall() {
        val apkFile = _appUpdateUiState.value.downloadedApkFile ?: return
        val release = _appUpdateUiState.value.availableRelease
        when (val launch = AppUpdateManager.launchApkInstaller(app, apkFile, release)) {
            is ApkInstallLaunchResult.Launched -> {
                _appUpdateUiState.update {
                    it.copy(needsInstallPermission = false, errorMessage = null, signingConflict = false)
                }
            }
            is ApkInstallLaunchResult.UnknownSourcesPermissionRequired -> {
                _appUpdateUiState.update { it.copy(needsInstallPermission = true, signingConflict = false) }
            }
            is ApkInstallLaunchResult.SigningConflict -> {
                val msg = "Package signing key mismatch (${launch.installedSha256.take(8)} vs ${launch.apkSha256.take(8)}). Back up data, uninstall old build once, then install the new APK."
                _appUpdateUiState.update {
                    it.copy(signingConflict = true, errorMessage = msg)
                }
                viewModelScope.launch {
                    logEvent(ActivityCategory.SYSTEM_EVENT, "App Updater", "SIGNING_CONFLICT", msg)
                }
                postBanner(msg)
            }
            is ApkInstallLaunchResult.Failed -> {
                _appUpdateUiState.update { it.copy(errorMessage = launch.message, signingConflict = false) }
                postBanner(launch.message)
            }
        }
    }

    fun dismissInstallPermissionPrompt() {
        _appUpdateUiState.update { it.copy(needsInstallPermission = false) }
    }

    fun dismissUpdateDialog() {
        _appUpdateUiState.update { it.copy(showPromptDialog = false) }
    }

    fun skipCurrentUpdate() = viewModelScope.launch {
        val tag = _appUpdateUiState.value.availableRelease?.tagName.orEmpty()
        if (tag.isNotBlank()) {
            prefs.setSkippedUpdateTag(tag)
        }
        _appUpdateUiState.update { it.copy(showPromptDialog = false) }
    }
}
