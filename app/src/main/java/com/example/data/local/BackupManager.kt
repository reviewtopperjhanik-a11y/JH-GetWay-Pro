package com.example.data.local

import androidx.annotation.VisibleForTesting
import androidx.room.withTransaction
import com.example.BuildConfig
import com.example.core.i18n.AppLanguage
import com.example.core.security.CryptoManager
import com.example.core.security.MaskingUtils
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

enum class RestoreConflictStrategy {
    OVERWRITE_EXISTING,
    SKIP_CONFLICTS,
    REPLACE_ALL
}

data class BackupManifest(
    val backupFormatVersion: Int,
    val schemaVersion: Int,
    val createdAt: Long,
    val appVersionName: String,
    val appVersionCode: Int,
    val applicationId: String,
    val includedCategories: List<String> = emptyList(),
    val nonPortableCategories: List<String> = emptyList()
)

data class BackupEntityCounts(
    val providers: Int = 0,
    val apiKeys: Int = 0,
    val models: Int = 0,
    val routingRules: Int = 0,
    val codingAgents: Int = 0,
    val localAiServers: Int = 0,
    val mcpServers: Int = 0,
    val mcpTools: Int = 0,
    val workflows: Int = 0,
    val devices: Int = 0,
    val workflowRuns: Int = 0,
    val playgroundSessions: Int = 0,
    val playgroundMessages: Int = 0,
    val requestMetrics: Int = 0,
    val preferences: Int = 0
) {
    val total: Int
        get() = providers + apiKeys + models + routingRules + codingAgents +
            localAiServers + mcpServers + mcpTools + workflows + devices +
            workflowRuns + playgroundSessions + playgroundMessages + requestMetrics + preferences
}

data class BackupConflictSummary(
    val providers: Int = 0,
    val apiKeys: Int = 0,
    val models: Int = 0,
    val routingRules: Int = 0,
    val codingAgents: Int = 0,
    val localAiServers: Int = 0,
    val mcpServers: Int = 0,
    val mcpTools: Int = 0,
    val workflows: Int = 0,
    val devices: Int = 0,
    val workflowRuns: Int = 0,
    val playgroundSessions: Int = 0,
    val playgroundMessages: Int = 0,
    val requestMetrics: Int = 0,
    val preferences: Int = 0
) {
    val totalConflicts: Int
        get() = providers + apiKeys + models + routingRules + codingAgents +
            localAiServers + mcpServers + mcpTools + workflows + devices +
            workflowRuns + playgroundSessions + playgroundMessages + requestMetrics + preferences
}

data class BackupRestorePreview(
    val formatVersion: Int,
    val schemaVersion: Int,
    val exportedAt: Long,
    val kdfAlgorithm: String,
    val kdfIterations: Int,
    val integrityVerified: Boolean,
    val counts: BackupEntityCounts,
    val conflicts: BackupConflictSummary,
    val manifest: BackupManifest? = null
)

data class RestoreResult(
    val importedCount: Int,
    val skippedConflicts: Int,
    val overwrittenCount: Int,
    val strategy: RestoreConflictStrategy,
    val preferencesRestored: Boolean = false,
    val nonPortableCategories: List<String> = emptyList()
)

class BackupManager(
    private val db: JhGetWayDatabase,
    private val prefs: AppPreferences? = null,
    private val appVersionName: String = BuildConfig.VERSION_NAME,
    private val appVersionCode: Int = BuildConfig.VERSION_CODE,
    private val applicationId: String = BuildConfig.APPLICATION_ID
) {

    companion object {
        const val CURRENT_BACKUP_FORMAT_VERSION = 2
        const val CURRENT_DB_SCHEMA_VERSION = 5
        const val MAX_BACKUP_WORKFLOW_RUNS = 500
        const val MAX_BACKUP_METRICS = 2000

        val DEFAULT_NON_PORTABLE_CATEGORIES = listOf(
            "android_keystore_master_key",
            "app_lock_pin_and_biometric",
            "playground_attachment_files",
            "activity_logs",
            "ephemeral_runtime_state"
        )
    }

    private data class StagedPreferences(
        val state: GatewaySettingsState,
        val remoteAuthTokenPlain: String?
    )

    private data class StagedArchive(
        val formatVersion: Int,
        val schemaVersion: Int,
        val exportedAt: Long,
        val kdfAlgorithm: String,
        val kdfIterations: Int,
        val integrityVerified: Boolean,
        val manifest: BackupManifest,
        val providers: List<ProviderEntity>,
        val apiKeysPlain: List<Pair<ApiKeyEntity, String>>,
        val models: List<ModelEntity>,
        val routingRules: List<RoutingRuleEntity>,
        val codingAgentsPlain: List<Pair<CodingAgentEntity, String>>,
        val localAiServersPlain: List<Pair<LocalAiServerEntity, String>>,
        val mcpServersPlain: List<Triple<McpServerEntity, String, String>>,
        val mcpTools: List<McpToolEntity>,
        val workflows: List<WorkflowEntity>,
        val devicesPlain: List<Pair<DeviceEntity, String>>,
        val workflowRuns: List<WorkflowRunEntity>,
        val playgroundSessions: List<PlaygroundSessionEntity>,
        val playgroundMessages: List<PlaygroundMessageEntity>,
        val requestMetrics: List<RequestMetricEntity>,
        val preferences: StagedPreferences?
    ) {
        fun counts(): BackupEntityCounts = BackupEntityCounts(
            providers = providers.size,
            apiKeys = apiKeysPlain.size,
            models = models.size,
            routingRules = routingRules.size,
            codingAgents = codingAgentsPlain.size,
            localAiServers = localAiServersPlain.size,
            mcpServers = mcpServersPlain.size,
            mcpTools = mcpTools.size,
            workflows = workflows.size,
            devices = devicesPlain.size,
            workflowRuns = workflowRuns.size,
            playgroundSessions = playgroundSessions.size,
            playgroundMessages = playgroundMessages.size,
            requestMetrics = requestMetrics.size,
            preferences = if (preferences != null) 1 else 0
        )
    }

    suspend fun exportEncryptedBackup(passphrase: String): String {
        require(passphrase.length >= CryptoManager.MIN_BACKUP_V2_PASSPHRASE_LEN) {
            "Backup passphrase must be at least ${CryptoManager.MIN_BACKUP_V2_PASSPHRASE_LEN} characters"
        }

        val nowMs = System.currentTimeMillis()
        val dataObj = JSONObject()
        val includedCategories = mutableListOf<String>()
        val nonPortableCategories = DEFAULT_NON_PORTABLE_CATEGORIES.toMutableList()

        val providersArr = JSONArray()
        db.providerDao().getAllOnce().forEach { p ->
            providersArr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("type", p.type.name)
                put("presetId", p.presetId)
                put("baseUrl", p.baseUrl)
                put("apiFormat", p.apiFormat.name)
                put("defaultModel", p.defaultModel)
                put("priority", p.priority)
                put("enabled", p.enabled)
                put("customHeadersJson", p.customHeadersJson)
                put("notes", p.notes)
            })
        }
        dataObj.put("providers", providersArr)
        includedCategories += "providers"

        val keysArr = JSONArray()
        db.apiKeyDao().getAllOnce().forEach { k ->
            val plainKey = CryptoManager.decrypt(k.encryptedKey)
            keysArr.put(JSONObject().apply {
                put("id", k.id)
                put("providerId", k.providerId)
                put("label", k.label)
                put("plainKey", plainKey)
                put("maskedPreview", k.maskedPreview.ifBlank { MaskingUtils.maskSecret(plainKey) })
                put("priority", k.priority)
                put("isDefault", k.isDefault)
                put("enabled", k.enabled)
                put("selectionPolicy", k.selectionPolicy.name)
            })
        }
        dataObj.put("apiKeys", keysArr)
        includedCategories += "api_keys_reencrypted"

        val modelsArr = JSONArray()
        db.modelDao().getAllOnce().forEach { m ->
            modelsArr.put(JSONObject().apply {
                put("id", m.id)
                put("modelId", m.modelId)
                put("displayName", m.displayName)
                put("providerId", m.providerId)
                put("providerName", m.providerName)
                put("modelType", m.modelType.name)
                put("capabilitiesCsv", m.capabilitiesCsv)
                put("contextWindow", m.contextWindow ?: JSONObject.NULL)
                put("supportsTools", m.supportsTools)
                put("supportsStreaming", m.supportsStreaming)
                put("isLocal", m.isLocal)
                put("inputPricePer1M", m.inputPricePer1M ?: JSONObject.NULL)
                put("outputPricePer1M", m.outputPricePer1M ?: JSONObject.NULL)
                put("pricingUpdatedAt", m.pricingUpdatedAt ?: JSONObject.NULL)
                put("enabled", m.enabled)
                put("isDefault", m.isDefault)
                put("alias", m.alias)
                put("accessTier", m.accessTier.name)
                put("freeTierVerified", m.freeTierVerified)
                put("freeTierSource", m.freeTierSource)
                put("freeTierVerifiedAt", m.freeTierVerifiedAt ?: JSONObject.NULL)
                put("freeTierLimitsNote", m.freeTierLimitsNote)
                put("supportsChat", m.supportsChat)
                put("supportsVision", m.supportsVision)
            })
        }
        dataObj.put("models", modelsArr)
        includedCategories += "models"

        val rulesArr = JSONArray()
        db.routingRuleDao().getAllOnce().forEach { r ->
            rulesArr.put(JSONObject().apply {
                put("id", r.id)
                put("name", r.name)
                put("modelAlias", r.modelAlias)
                put("allowedProviderIdsCsv", r.allowedProviderIdsCsv)
                put("preferredProviderId", r.preferredProviderId)
                put("backupProviderIdsCsv", r.backupProviderIdsCsv)
                put("priority", r.priority)
                put("requiredCapabilitiesCsv", r.requiredCapabilitiesCsv)
                put("maxCostPer1M", r.maxCostPer1M ?: JSONObject.NULL)
                put("maxLatencyMs", r.maxLatencyMs ?: JSONObject.NULL)
                put("localCloudPreference", r.localCloudPreference)
                put("enabled", r.enabled)
            })
        }
        dataObj.put("routingRules", rulesArr)
        includedCategories += "routing_rules"

        val agentsArr = JSONArray()
        db.codingAgentDao().getAllOnce().forEach { a ->
            val plainToken = CryptoManager.decrypt(a.encryptedToken)
            agentsArr.put(JSONObject().apply {
                put("id", a.id)
                put("name", a.name)
                put("agentType", a.agentType.name)
                put("modelAlias", a.modelAlias)
                put("defaultModelId", a.defaultModelId)
                put("allowedModelsCsv", a.allowedModelsCsv)
                put("streaming", a.streaming)
                put("toolCalling", a.toolCalling)
                put("dailyTokenLimit", a.dailyTokenLimit ?: JSONObject.NULL)
                put("enabled", a.enabled)
                put("plainToken", plainToken)
                put("tokenPreview", a.tokenPreview.ifBlank { MaskingUtils.maskSecret(plainToken) })
            })
        }
        dataObj.put("codingAgents", agentsArr)
        includedCategories += "coding_agents"

        val localServersArr = JSONArray()
        db.localAiServerDao().getAllOnce().forEach { s ->
            val plainAuth = if (s.encryptedAuthToken.isNotBlank()) CryptoManager.decrypt(s.encryptedAuthToken) else ""
            localServersArr.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("engineType", s.engineType.name)
                put("serverUrl", s.serverUrl)
                put("plainAuthToken", plainAuth)
                put("hasAuth", s.hasAuth)
                put("timeoutSec", s.timeoutSec)
                put("defaultModel", s.defaultModel)
                put("isPrivateNetwork", s.isPrivateNetwork)
                put("enabled", s.enabled)
            })
        }
        dataObj.put("localAiServers", localServersArr)
        includedCategories += "local_ai_servers"

        val mcpServersArr = JSONArray()
        db.mcpDao().getServersOnce().forEach { s ->
            val plainEnv = if (s.encryptedEnvJson.isNotBlank()) CryptoManager.decrypt(s.encryptedEnvJson) else ""
            val plainAuth = if (s.encryptedAuthToken.isNotBlank()) CryptoManager.decrypt(s.encryptedAuthToken) else ""
            mcpServersArr.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("transportType", s.transportType.name)
                put("endpointOrCommand", s.endpointOrCommand)
                put("arguments", s.arguments)
                put("plainEnvJson", plainEnv)
                put("plainAuthToken", plainAuth)
                put("timeoutSec", s.timeoutSec)
                put("resourcesJson", s.resourcesJson)
                put("promptsJson", s.promptsJson)
                put("enabled", s.enabled)
            })
        }
        dataObj.put("mcpServers", mcpServersArr)
        includedCategories += "mcp_servers"

        val mcpToolsArr = JSONArray()
        db.mcpDao().getServersOnce().forEach { srv ->
            db.mcpDao().getToolsForServer(srv.id).forEach { t ->
                mcpToolsArr.put(JSONObject().apply {
                    put("id", t.id)
                    put("serverId", t.serverId)
                    put("toolName", t.toolName)
                    put("description", t.description)
                    put("inputSchemaJson", t.inputSchemaJson)
                    put("permission", t.permission.name)
                })
            }
        }
        dataObj.put("mcpTools", mcpToolsArr)
        includedCategories += "mcp_tools"

        val workflowsArr = JSONArray()
        db.workflowDao().getWorkflowsOnce().filter { !it.isTemplate }.forEach { w ->
            workflowsArr.put(JSONObject().apply {
                put("id", w.id)
                put("name", w.name)
                put("description", w.description)
                put("nodesJson", w.nodesJson)
                put("edgesJson", w.edgesJson)
                put("version", w.version)
            })
        }
        dataObj.put("workflows", workflowsArr)
        includedCategories += "workflows"

        val workflowRuns = db.workflowDao().getRunsOnce().take(MAX_BACKUP_WORKFLOW_RUNS)
        if (workflowRuns.isNotEmpty()) {
            val runsArr = JSONArray()
            workflowRuns.forEach { r ->
                runsArr.put(JSONObject().apply {
                    put("id", r.id)
                    put("workflowId", r.workflowId)
                    put("workflowName", r.workflowName)
                    put("status", r.status)
                    put("inputText", r.inputText)
                    put("finalOutput", r.finalOutput)
                    put("nodeResultsJson", r.nodeResultsJson)
                    put("startedAt", r.startedAt)
                    put("finishedAt", r.finishedAt ?: JSONObject.NULL)
                    put("errorMessage", r.errorMessage ?: JSONObject.NULL)
                })
            }
            dataObj.put("workflowRuns", runsArr)
            includedCategories += "workflow_runs"
        }

        val devicesArr = JSONArray()
        db.deviceDao().getAllOnce().forEach { d ->
            val plainToken = CryptoManager.decrypt(d.encryptedToken)
            devicesArr.put(JSONObject().apply {
                put("id", d.id)
                put("name", d.name)
                put("deviceType", d.deviceType)
                put("deviceCode", d.deviceCode)
                put("plainToken", plainToken)
                put("tokenPreview", d.tokenPreview.ifBlank { MaskingUtils.maskSecret(plainToken) })
                put("permissionsCsv", d.permissionsCsv)
                put("allowedModelsCsv", d.allowedModelsCsv)
                put("connectionType", d.connectionType.name)
                put("enabled", d.enabled)
                put("revoked", d.revoked)
            })
        }
        dataObj.put("devices", devicesArr)
        includedCategories += "devices"

        val sessions = db.playgroundDao().getSessionsOnce()
        if (sessions.isNotEmpty()) {
            val sessArr = JSONArray()
            sessions.forEach { s ->
                sessArr.put(JSONObject().apply {
                    put("id", s.id)
                    put("title", s.title)
                    put("modelId", s.modelId)
                    put("providerId", s.providerId)
                    put("systemPrompt", s.systemPrompt)
                    put("createdAt", s.createdAt)
                    put("updatedAt", s.updatedAt)
                })
            }
            dataObj.put("playgroundSessions", sessArr)
            includedCategories += "playground_sessions"
        }

        val messages = db.playgroundDao().getAllMessagesOnce()
        if (messages.isNotEmpty()) {
            val msgArr = JSONArray()
            messages.forEach { m ->
                msgArr.put(JSONObject().apply {
                    put("id", m.id)
                    put("sessionId", m.sessionId)
                    put("role", m.role)
                    put("content", m.content)
                    put("modelId", m.modelId)
                    put("providerName", m.providerName)
                    put("latencyMs", m.latencyMs ?: JSONObject.NULL)
                    put("inputTokens", m.inputTokens ?: JSONObject.NULL)
                    put("outputTokens", m.outputTokens ?: JSONObject.NULL)
                    put("estimatedCostUsd", m.estimatedCostUsd ?: JSONObject.NULL)
                    put("isError", m.isError)
                    put("timestamp", m.timestamp)
                    put("attachmentsMetadataJson", sanitizeAttachmentsMetadataForBackup(m.attachmentsMetadataJson))
                })
            }
            dataObj.put("playgroundMessages", msgArr)
            includedCategories += "playground_messages"
        }

        val metrics = db.metricAndLogDao().getMetricsOnce().take(MAX_BACKUP_METRICS)
        if (metrics.isNotEmpty()) {
            val metricsArr = JSONArray()
            metrics.forEach { m ->
                metricsArr.put(JSONObject().apply {
                    put("id", m.id)
                    put("requestId", m.requestId)
                    put("timestamp", m.timestamp)
                    put("providerId", m.providerId)
                    put("providerName", m.providerName)
                    put("modelId", m.modelId)
                    put("deviceName", m.deviceName)
                    put("endpoint", m.endpoint)
                    put("statusCode", m.statusCode)
                    put("success", m.success)
                    put("latencyMs", m.latencyMs)
                    put("inputTokens", m.inputTokens)
                    put("outputTokens", m.outputTokens)
                    put("usageSource", m.usageSource)
                    put("estimatedCostUsd", m.estimatedCostUsd ?: JSONObject.NULL)
                    put("actualBilledCostUsd", m.actualBilledCostUsd ?: JSONObject.NULL)
                    put("pricingSource", m.pricingSource ?: JSONObject.NULL)
                    put("pricingUpdatedAt", m.pricingUpdatedAt ?: JSONObject.NULL)
                    put("fallbackAttempts", m.fallbackAttempts)
                    put("routingMode", m.routingMode)
                    put("routingReason", m.routingReason)
                    put("errorCode", m.errorCode ?: JSONObject.NULL)
                    put("safeErrorMessage", m.safeErrorMessage ?: JSONObject.NULL)
                })
            }
            dataObj.put("requestMetrics", metricsArr)
            includedCategories += "analytics_metrics"
        }

        if (prefs != null) {
            val s = prefs.settingsFlow.first()
            var remoteTokenPlain: String? = null
            if (s.gatewayRemoteAuthTokenEnc.isNotBlank()) {
                val dec = runCatching { CryptoManager.decrypt(s.gatewayRemoteAuthTokenEnc) }.getOrNull()
                if (dec != null) {
                    remoteTokenPlain = dec
                } else {
                    nonPortableCategories += "gateway_remote_auth_token"
                }
            }
            dataObj.put("preferences", JSONObject().apply {
                put("themeMode", s.themeMode.name)
                put("language", s.language.name)
                put("onboardingCompleted", s.onboardingCompleted)
                put("gatewayName", s.gatewayName)
                put("gatewayMode", s.gatewayMode.name)
                put("gatewayHost", s.gatewayHost)
                put("gatewayPort", s.gatewayPort)
                put("gatewayRemoteUrl", s.gatewayRemoteUrl)
                put("gatewayAuthRequired", s.gatewayAuthRequired)
                put("gatewayTimeoutSec", s.gatewayTimeoutSec)
                put("gatewayMaxRequestKb", s.gatewayMaxRequestKb)
                put("gatewayStreamingEnabled", s.gatewayStreamingEnabled)
                put("gatewayRateLimitPerMin", s.gatewayRateLimitPerMin)
                put("gatewayLoggingLevel", s.gatewayLoggingLevel.name)
                put("gatewayAllowLanExposure", s.gatewayAllowLanExposure)
                if (remoteTokenPlain != null) {
                    put("gatewayRemoteAuthTokenPlain", remoteTokenPlain)
                }
                put("defaultModelId", s.defaultModelId)
                put("defaultProviderId", s.defaultProviderId)
                put("defaultRoutingMode", s.defaultRoutingMode.name)
                put("fallbackEnabled", s.fallbackEnabled)
                put("maxRetries", s.maxRetries)
                put("localRoutingPolicy", s.localRoutingPolicy.name)
                put("allowCloudFallbackForLocal", s.allowCloudFallbackForLocal)
                put("localNetworkAccessAllowed", s.localNetworkAccessAllowed)
                put("saveChatHistoryAllowed", s.saveChatHistoryAllowed)
                put("analyticsCurrency", s.analyticsCurrency)
                put("logRetentionDays", s.logRetentionDays)
                put("usageRetentionDays", s.usageRetentionDays)
                put("playgroundRetentionDays", s.playgroundRetentionDays)
                put("notifyProviderFailure", s.notifyProviderFailure)
                put("notifyGatewayDisconnected", s.notifyGatewayDisconnected)
                put("notifyRateLimit", s.notifyRateLimit)
                put("notifyWorkflowCompleted", s.notifyWorkflowCompleted)
                put("notifySecurityEvent", s.notifySecurityEvent)
                put("githubUpdateRepo", s.githubUpdateRepo)
                put("autoCheckUpdates", s.autoCheckUpdates)
                put("manualModelFallbackEnabled", s.manualModelFallbackEnabled)
                put("autoFreeIncludeLocal", s.autoFreeIncludeLocal)
            })
            includedCategories += "preferences"
        }

        val manifestObj = JSONObject().apply {
            put("backupFormatVersion", CURRENT_BACKUP_FORMAT_VERSION)
            put("schemaVersion", CURRENT_DB_SCHEMA_VERSION)
            put("createdAt", nowMs)
            put("appVersionName", appVersionName)
            put("appVersionCode", appVersionCode)
            put("applicationId", applicationId)
            put("includedCategories", JSONArray(includedCategories))
            put("nonPortableCategories", JSONArray(nonPortableCategories.distinct()))
        }

        val integrityDigest = sha256Hex(canonicalJson(dataObj))
        val root = JSONObject().apply {
            put("backupFormatVersion", CURRENT_BACKUP_FORMAT_VERSION)
            put("version", CURRENT_BACKUP_FORMAT_VERSION)
            put("schemaVersion", CURRENT_DB_SCHEMA_VERSION)
            put("exportedAt", nowMs)
            put("kdf", "PBKDF2WithHmacSHA256")
            put("kdfIterations", CryptoManager.BENCHMARKED_BACKUP_KDF_ITERATIONS)
            put("manifest", manifestObj)
            put("integritySha256", integrityDigest)
            put("data", dataObj)
        }

        return CryptoManager.encryptBackupArchive(root.toString(), passphrase)
    }

    suspend fun previewEncryptedBackup(encryptedPayload: String, passphrase: String): BackupRestorePreview {
        val staged = parseAndValidateArchive(encryptedPayload, passphrase)
        val conflicts = computeConflicts(staged)
        return BackupRestorePreview(
            formatVersion = staged.formatVersion,
            schemaVersion = staged.schemaVersion,
            exportedAt = staged.exportedAt,
            kdfAlgorithm = staged.kdfAlgorithm,
            kdfIterations = staged.kdfIterations,
            integrityVerified = staged.integrityVerified,
            counts = staged.counts(),
            conflicts = conflicts,
            manifest = staged.manifest
        )
    }

    suspend fun restoreEncryptedBackup(
        encryptedPayload: String,
        passphrase: String,
        strategy: RestoreConflictStrategy = RestoreConflictStrategy.OVERWRITE_EXISTING
    ): Int {
        return restoreEncryptedBackupDetailed(encryptedPayload, passphrase, strategy).importedCount
    }

    suspend fun restoreEncryptedBackupDetailed(
        encryptedPayload: String,
        passphrase: String,
        strategy: RestoreConflictStrategy = RestoreConflictStrategy.OVERWRITE_EXISTING
    ): RestoreResult {
        return restoreEncryptedBackupInternal(encryptedPayload, passphrase, strategy, beforeCommitHook = null)
    }

    @VisibleForTesting
    internal suspend fun restoreEncryptedBackupInternal(
        encryptedPayload: String,
        passphrase: String,
        strategy: RestoreConflictStrategy,
        beforeCommitHook: (suspend () -> Unit)?
    ): RestoreResult {
        val staged = parseAndValidateArchive(encryptedPayload, passphrase)

        // Pre-encrypt all sensitive credentials before mutating any Room table so a Keystore error
        // never partially modifies the database.
        val reEncryptedKeys = staged.apiKeysPlain.map { (entity, plainKey) ->
            entity.copy(encryptedKey = CryptoManager.encrypt(plainKey))
        }
        val reEncryptedAgents = staged.codingAgentsPlain.map { (entity, plainToken) ->
            entity.copy(encryptedToken = CryptoManager.encrypt(plainToken))
        }
        val reEncryptedLocalServers = staged.localAiServersPlain.map { (entity, plainAuth) ->
            entity.copy(
                encryptedAuthToken = if (plainAuth.isNotBlank()) CryptoManager.encrypt(plainAuth) else "",
                hasAuth = plainAuth.isNotBlank()
            )
        }
        val reEncryptedMcpServers = staged.mcpServersPlain.map { (entity, plainEnv, plainAuth) ->
            entity.copy(
                encryptedEnvJson = if (plainEnv.isNotBlank()) CryptoManager.encrypt(plainEnv) else "",
                encryptedAuthToken = if (plainAuth.isNotBlank()) CryptoManager.encrypt(plainAuth) else ""
            )
        }
        val reEncryptedDevices = staged.devicesPlain.map { (entity, plainToken) ->
            entity.copy(encryptedToken = CryptoManager.encrypt(plainToken))
        }
        val reEncryptedRemoteGatewayToken = staged.preferences?.remoteAuthTokenPlain?.let { plain ->
            if (plain.isNotBlank()) CryptoManager.encrypt(plain) else ""
        }

        val dbCounts = db.withTransaction {
            var importedCount = 0
            var skippedConflicts = 0
            var overwrittenCount = 0

            val existingProviderIds = if (strategy == RestoreConflictStrategy.REPLACE_ALL) emptySet() else db.providerDao().getAllOnce().map { it.id }.toSet()
            val existingKeyIds = if (strategy == RestoreConflictStrategy.REPLACE_ALL) emptySet() else db.apiKeyDao().getAllOnce().map { it.id }.toSet()
            val existingModelIds = if (strategy == RestoreConflictStrategy.REPLACE_ALL) emptySet() else db.modelDao().getAllOnce().map { it.id }.toSet()
            val existingRuleIds = if (strategy == RestoreConflictStrategy.REPLACE_ALL) emptySet() else db.routingRuleDao().getAllOnce().map { it.id }.toSet()
            val existingAgentIds = if (strategy == RestoreConflictStrategy.REPLACE_ALL) emptySet() else db.codingAgentDao().getAllOnce().map { it.id }.toSet()
            val existingLocalIds = if (strategy == RestoreConflictStrategy.REPLACE_ALL) emptySet() else db.localAiServerDao().getAllOnce().map { it.id }.toSet()
            val existingMcpServerIds = if (strategy == RestoreConflictStrategy.REPLACE_ALL) emptySet() else db.mcpDao().getServersOnce().map { it.id }.toSet()
            val existingMcpToolIds = if (strategy == RestoreConflictStrategy.REPLACE_ALL) emptySet() else db.mcpDao().getServersOnce().flatMap { db.mcpDao().getToolsForServer(it.id) }.map { it.id }.toSet()
            val existingWorkflowIds = if (strategy == RestoreConflictStrategy.REPLACE_ALL) emptySet() else db.workflowDao().getWorkflowsOnce().map { it.id }.toSet()
            val existingWorkflowRunIds = if (strategy == RestoreConflictStrategy.REPLACE_ALL) emptySet() else db.workflowDao().getRunsOnce().map { it.id }.toSet()
            val existingDeviceIds = if (strategy == RestoreConflictStrategy.REPLACE_ALL) emptySet() else db.deviceDao().getAllOnce().map { it.id }.toSet()
            val existingSessionIds = if (strategy == RestoreConflictStrategy.REPLACE_ALL) emptySet() else db.playgroundDao().getSessionsOnce().map { it.id }.toSet()
            val existingMessageIds = if (strategy == RestoreConflictStrategy.REPLACE_ALL) emptySet() else db.playgroundDao().getAllMessagesOnce().map { it.id }.toSet()
            val existingMetricIds = if (strategy == RestoreConflictStrategy.REPLACE_ALL) emptySet() else db.metricAndLogDao().getMetricsOnce().map { it.id }.toSet()

            if (strategy == RestoreConflictStrategy.REPLACE_ALL) {
                val templatesToPreserve = db.workflowDao().getWorkflowsOnce().filter { it.isTemplate }
                db.providerDao().clearAll()
                db.apiKeyDao().clearAll()
                db.modelDao().clearAll()
                db.routingRuleDao().clearAll()
                db.codingAgentDao().clearAll()
                db.localAiServerDao().clearAll()
                db.mcpDao().clearTools()
                db.mcpDao().clearServers()
                db.workflowDao().clearRuns()
                db.workflowDao().clearWorkflows()
                db.deviceDao().clearAll()
                db.playgroundDao().clearMessages()
                db.playgroundDao().clearSessions()
                db.metricAndLogDao().clearMetrics()
                templatesToPreserve.forEach { db.workflowDao().upsertWorkflow(it) }
            }

            for (p in staged.providers) {
                val collides = p.id in existingProviderIds
                if (collides && strategy == RestoreConflictStrategy.SKIP_CONFLICTS) {
                    skippedConflicts++
                } else {
                    if (collides) overwrittenCount++
                    db.providerDao().upsert(p)
                    importedCount++
                }
            }
            for (k in reEncryptedKeys) {
                val collides = k.id in existingKeyIds
                if (collides && strategy == RestoreConflictStrategy.SKIP_CONFLICTS) {
                    skippedConflicts++
                } else {
                    if (collides) overwrittenCount++
                    db.apiKeyDao().upsert(k)
                    importedCount++
                }
            }
            for (m in staged.models) {
                val collides = m.id in existingModelIds
                if (collides && strategy == RestoreConflictStrategy.SKIP_CONFLICTS) {
                    skippedConflicts++
                } else {
                    if (collides) overwrittenCount++
                    db.modelDao().upsert(m)
                    importedCount++
                }
            }
            for (r in staged.routingRules) {
                val collides = r.id in existingRuleIds
                if (collides && strategy == RestoreConflictStrategy.SKIP_CONFLICTS) {
                    skippedConflicts++
                } else {
                    if (collides) overwrittenCount++
                    db.routingRuleDao().upsert(r)
                    importedCount++
                }
            }
            for (a in reEncryptedAgents) {
                val collides = a.id in existingAgentIds
                if (collides && strategy == RestoreConflictStrategy.SKIP_CONFLICTS) {
                    skippedConflicts++
                } else {
                    if (collides) overwrittenCount++
                    db.codingAgentDao().upsert(a)
                    importedCount++
                }
            }
            for (s in reEncryptedLocalServers) {
                val collides = s.id in existingLocalIds
                if (collides && strategy == RestoreConflictStrategy.SKIP_CONFLICTS) {
                    skippedConflicts++
                } else {
                    if (collides) overwrittenCount++
                    db.localAiServerDao().upsert(s)
                    importedCount++
                }
            }
            for (s in reEncryptedMcpServers) {
                val collides = s.id in existingMcpServerIds
                if (collides && strategy == RestoreConflictStrategy.SKIP_CONFLICTS) {
                    skippedConflicts++
                } else {
                    if (collides) overwrittenCount++
                    db.mcpDao().upsertServer(s)
                    importedCount++
                }
            }
            for (t in staged.mcpTools) {
                val collides = t.id in existingMcpToolIds
                if (collides && strategy == RestoreConflictStrategy.SKIP_CONFLICTS) {
                    skippedConflicts++
                } else {
                    if (collides) overwrittenCount++
                    db.mcpDao().upsertTool(t)
                    importedCount++
                }
            }
            for (w in staged.workflows) {
                val collides = w.id in existingWorkflowIds
                if (collides && strategy == RestoreConflictStrategy.SKIP_CONFLICTS) {
                    skippedConflicts++
                } else {
                    if (collides) overwrittenCount++
                    db.workflowDao().upsertWorkflow(w)
                    importedCount++
                }
            }
            for (run in staged.workflowRuns) {
                val collides = run.id in existingWorkflowRunIds
                if (collides && strategy == RestoreConflictStrategy.SKIP_CONFLICTS) {
                    skippedConflicts++
                } else {
                    if (collides) overwrittenCount++
                    db.workflowDao().upsertRun(run)
                    importedCount++
                }
            }
            for (d in reEncryptedDevices) {
                val collides = d.id in existingDeviceIds
                if (collides && strategy == RestoreConflictStrategy.SKIP_CONFLICTS) {
                    skippedConflicts++
                } else {
                    if (collides) overwrittenCount++
                    db.deviceDao().upsert(d)
                    importedCount++
                }
            }
            for (sess in staged.playgroundSessions) {
                val collides = sess.id in existingSessionIds
                if (collides && strategy == RestoreConflictStrategy.SKIP_CONFLICTS) {
                    skippedConflicts++
                } else {
                    if (collides) overwrittenCount++
                    db.playgroundDao().upsertSession(sess)
                    importedCount++
                }
            }
            for (msg in staged.playgroundMessages) {
                val collides = msg.id in existingMessageIds
                if (collides && strategy == RestoreConflictStrategy.SKIP_CONFLICTS) {
                    skippedConflicts++
                } else {
                    if (collides) overwrittenCount++
                    db.playgroundDao().insertMessage(msg)
                    importedCount++
                }
            }
            for (metric in staged.requestMetrics) {
                val collides = metric.id in existingMetricIds
                if (collides && strategy == RestoreConflictStrategy.SKIP_CONFLICTS) {
                    skippedConflicts++
                } else {
                    if (collides) overwrittenCount++
                    db.metricAndLogDao().insertMetric(metric)
                    importedCount++
                }
            }

            beforeCommitHook?.invoke()

            Triple(importedCount, skippedConflicts, overwrittenCount)
        }

        var importedCount = dbCounts.first
        var skippedConflicts = dbCounts.second
        var overwrittenCount = dbCounts.third
        var preferencesRestored = false

        if (staged.preferences != null && prefs != null) {
            val existingSettings = prefs.settingsFlow.first()
            val hasPrefConflict = hasCustomizedPortablePreferences(existingSettings)
            if (hasPrefConflict && strategy == RestoreConflictStrategy.SKIP_CONFLICTS) {
                skippedConflicts++
            } else {
                if (hasPrefConflict && strategy != RestoreConflictStrategy.REPLACE_ALL) {
                    overwrittenCount++
                }
                prefs.restorePortablePreferences(
                    snapshot = staged.preferences.state,
                    remoteAuthTokenEnc = reEncryptedRemoteGatewayToken
                )
                importedCount++
                preferencesRestored = true
            }
        }

        return RestoreResult(
            importedCount = importedCount,
            skippedConflicts = skippedConflicts,
            overwrittenCount = overwrittenCount,
            strategy = strategy,
            preferencesRestored = preferencesRestored,
            nonPortableCategories = staged.manifest.nonPortableCategories
        )
    }

    private suspend fun computeConflicts(staged: StagedArchive): BackupConflictSummary {
        val providerIds = db.providerDao().getAllOnce().map { it.id }.toSet()
        val keyIds = db.apiKeyDao().getAllOnce().map { it.id }.toSet()
        val modelIds = db.modelDao().getAllOnce().map { it.id }.toSet()
        val ruleIds = db.routingRuleDao().getAllOnce().map { it.id }.toSet()
        val agentIds = db.codingAgentDao().getAllOnce().map { it.id }.toSet()
        val localIds = db.localAiServerDao().getAllOnce().map { it.id }.toSet()
        val mcpServerIds = db.mcpDao().getServersOnce().map { it.id }.toSet()
        val mcpToolIds = db.mcpDao().getServersOnce().flatMap { db.mcpDao().getToolsForServer(it.id) }.map { it.id }.toSet()
        val workflowIds = db.workflowDao().getWorkflowsOnce().map { it.id }.toSet()
        val workflowRunIds = db.workflowDao().getRunsOnce().map { it.id }.toSet()
        val deviceIds = db.deviceDao().getAllOnce().map { it.id }.toSet()
        val sessionIds = db.playgroundDao().getSessionsOnce().map { it.id }.toSet()
        val messageIds = db.playgroundDao().getAllMessagesOnce().map { it.id }.toSet()
        val metricIds = db.metricAndLogDao().getMetricsOnce().map { it.id }.toSet()
        val prefConflict = if (staged.preferences != null && prefs != null) {
            if (hasCustomizedPortablePreferences(prefs.settingsFlow.first())) 1 else 0
        } else 0

        return BackupConflictSummary(
            providers = staged.providers.count { it.id in providerIds },
            apiKeys = staged.apiKeysPlain.count { it.first.id in keyIds },
            models = staged.models.count { it.id in modelIds },
            routingRules = staged.routingRules.count { it.id in ruleIds },
            codingAgents = staged.codingAgentsPlain.count { it.first.id in agentIds },
            localAiServers = staged.localAiServersPlain.count { it.first.id in localIds },
            mcpServers = staged.mcpServersPlain.count { it.first.id in mcpServerIds },
            mcpTools = staged.mcpTools.count { it.id in mcpToolIds },
            workflows = staged.workflows.count { it.id in workflowIds },
            devices = staged.devicesPlain.count { it.first.id in deviceIds },
            workflowRuns = staged.workflowRuns.count { it.id in workflowRunIds },
            playgroundSessions = staged.playgroundSessions.count { it.id in sessionIds },
            playgroundMessages = staged.playgroundMessages.count { it.id in messageIds },
            requestMetrics = staged.requestMetrics.count { it.id in metricIds },
            preferences = prefConflict
        )
    }

    private fun hasCustomizedPortablePreferences(current: GatewaySettingsState): Boolean {
        val baseline = GatewaySettingsState()
        return current.copy(
            appLockEnabled = baseline.appLockEnabled,
            appLockPinHash = baseline.appLockPinHash,
            appLockFailedAttempts = baseline.appLockFailedAttempts,
            appLockLockedUntilMs = baseline.appLockLockedUntilMs,
            appLockAutoLockOnBackground = baseline.appLockAutoLockOnBackground,
            appLockBiometricEnabled = baseline.appLockBiometricEnabled,
            gatewayServiceDesiredRunning = baseline.gatewayServiceDesiredRunning,
            skippedUpdateTag = baseline.skippedUpdateTag
        ) != baseline
    }

    private fun parseAndValidateArchive(encryptedPayload: String, passphrase: String): StagedArchive {
        val envelope = CryptoManager.decryptBackupArchive(encryptedPayload, passphrase)
        val root = runCatching { JSONObject(envelope.plainJson) }.getOrElse {
            throw IllegalArgumentException("Backup payload contains invalid JSON.")
        }

        val formatVersion = root.optInt("backupFormatVersion", root.optInt("version", -1))
        if (formatVersion !in 1..CURRENT_BACKUP_FORMAT_VERSION) {
            throw IllegalArgumentException("Unsupported backup archive version: $formatVersion")
        }
        val schemaVersion = root.optInt("schemaVersion", if (formatVersion == 1) 1 else CURRENT_DB_SCHEMA_VERSION)
        val exportedAt = root.optLong("exportedAt", 0L)

        val manifestJson = root.optJSONObject("manifest")
        val manifestAppId = manifestJson?.optString("applicationId", "")?.trim().orEmpty()
        if (manifestAppId.isNotBlank() && manifestAppId != applicationId) {
            throw IllegalArgumentException("Backup belongs to a different application ID ($manifestAppId).")
        }

        val dataObj: JSONObject
        val integrityVerified: Boolean
        if (formatVersion >= 2) {
            dataObj = root.optJSONObject("data")
                ?: throw IllegalArgumentException("Backup v2 archive missing 'data' section.")
            val expectedSha = root.optString("integritySha256", "")
            if (expectedSha.isBlank()) {
                throw IllegalArgumentException("Backup archive integrity checksum missing.")
            }
            val actualSha = sha256Hex(canonicalJson(dataObj))
            val matches = MessageDigest.isEqual(
                expectedSha.lowercase().toByteArray(Charsets.US_ASCII),
                actualSha.lowercase().toByteArray(Charsets.US_ASCII)
            )
            if (!matches) {
                throw IllegalArgumentException("Backup archive integrity checksum mismatch.")
            }
            integrityVerified = true
        } else {
            dataObj = root
            integrityVerified = true // V1 integrity covered by AES-GCM tag
        }

        val providers = buildList {
            dataObj.optJSONArray("providers")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: throw IllegalArgumentException("Invalid provider record at index $i")
                    val id = o.requireNonBlank("id", "provider")
                    val name = o.requireNonBlank("name", "provider")
                    val baseUrl = o.requireNonBlank("baseUrl", "provider")
                    require(baseUrl.startsWith("http://", true) || baseUrl.startsWith("https://", true)) {
                        "Invalid provider URL in backup for '$name'"
                    }
                    add(
                        ProviderEntity(
                            id = id,
                            name = name,
                            type = parseEnum(o.optString("type"), ProviderType.CUSTOM),
                            presetId = o.optString("presetId", "CUSTOM"),
                            baseUrl = baseUrl,
                            apiFormat = parseEnum(o.optString("apiFormat"), ApiFormat.OPENAI),
                            defaultModel = o.optString("defaultModel"),
                            priority = o.optInt("priority", 10),
                            enabled = o.optBoolean("enabled", true),
                            customHeadersJson = o.optString("customHeadersJson"),
                            notes = o.optString("notes")
                        )
                    )
                }
            }
        }

        val apiKeysPlain = buildList {
            dataObj.optJSONArray("apiKeys")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: throw IllegalArgumentException("Invalid API key record at index $i")
                    val id = o.requireNonBlank("id", "apiKey")
                    val providerId = o.requireNonBlank("providerId", "apiKey")
                    val label = o.requireNonBlank("label", "apiKey")
                    val plain = o.optString("plainKey")
                    require(plain.isNotBlank()) { "Backup API key '$label' is empty or corrupted" }
                    add(
                        ApiKeyEntity(
                            id = id,
                            providerId = providerId,
                            label = label,
                            encryptedKey = "",
                            maskedPreview = o.optString("maskedPreview").ifBlank { MaskingUtils.maskSecret(plain) },
                            priority = o.optInt("priority", 1),
                            isDefault = o.optBoolean("isDefault", false),
                            enabled = o.optBoolean("enabled", true),
                            selectionPolicy = parseEnum(o.optString("selectionPolicy"), KeySelectionPolicy.PRIORITY)
                        ) to plain
                    )
                }
            }
        }

        val models = buildList {
            dataObj.optJSONArray("models")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: throw IllegalArgumentException("Invalid model record at index $i")
                    add(
                        ModelEntity(
                            id = o.requireNonBlank("id", "model"),
                            modelId = o.requireNonBlank("modelId", "model"),
                            displayName = o.requireNonBlank("displayName", "model"),
                            providerId = o.requireNonBlank("providerId", "model"),
                            providerName = o.optString("providerName"),
                            modelType = parseEnum(o.optString("modelType"), ModelType.GENERAL_CHAT),
                            capabilitiesCsv = o.optString("capabilitiesCsv", "CHAT,STREAMING"),
                            contextWindow = if (o.isNull("contextWindow")) null else o.optInt("contextWindow"),
                            supportsTools = o.optBoolean("supportsTools", false),
                            supportsStreaming = o.optBoolean("supportsStreaming", true),
                            isLocal = o.optBoolean("isLocal", false),
                            inputPricePer1M = if (o.isNull("inputPricePer1M")) null else o.optDouble("inputPricePer1M"),
                            outputPricePer1M = if (o.isNull("outputPricePer1M")) null else o.optDouble("outputPricePer1M"),
                            pricingUpdatedAt = if (o.isNull("pricingUpdatedAt")) null else o.optString("pricingUpdatedAt"),
                            enabled = o.optBoolean("enabled", true),
                            isDefault = o.optBoolean("isDefault", false),
                            alias = o.optString("alias"),
                            accessTier = parseEnum(o.optString("accessTier"), if (o.optBoolean("isLocal", false)) AccessTier.LOCAL else AccessTier.UNKNOWN),
                            freeTierVerified = o.optBoolean("freeTierVerified", false),
                            freeTierSource = o.optString("freeTierSource"),
                            freeTierVerifiedAt = if (o.isNull("freeTierVerifiedAt") || !o.has("freeTierVerifiedAt")) null else o.optLong("freeTierVerifiedAt"),
                            freeTierLimitsNote = o.optString("freeTierLimitsNote"),
                            supportsChat = o.optBoolean("supportsChat", true),
                            supportsVision = o.optBoolean("supportsVision", false)
                        )
                    )
                }
            }
        }

        val routingRules = buildList {
            dataObj.optJSONArray("routingRules")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: throw IllegalArgumentException("Invalid routing rule at index $i")
                    add(
                        RoutingRuleEntity(
                            id = o.requireNonBlank("id", "routingRule"),
                            name = o.requireNonBlank("name", "routingRule"),
                            modelAlias = o.requireNonBlank("modelAlias", "routingRule"),
                            allowedProviderIdsCsv = o.optString("allowedProviderIdsCsv"),
                            preferredProviderId = o.optString("preferredProviderId"),
                            backupProviderIdsCsv = o.optString("backupProviderIdsCsv"),
                            priority = o.optInt("priority", 1),
                            requiredCapabilitiesCsv = o.optString("requiredCapabilitiesCsv"),
                            maxCostPer1M = if (o.isNull("maxCostPer1M")) null else o.optDouble("maxCostPer1M"),
                            maxLatencyMs = if (o.isNull("maxLatencyMs")) null else o.optLong("maxLatencyMs"),
                            localCloudPreference = o.optString("localCloudPreference", "ANY"),
                            enabled = o.optBoolean("enabled", true)
                        )
                    )
                }
            }
        }

        val codingAgentsPlain = buildList {
            dataObj.optJSONArray("codingAgents")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: throw IllegalArgumentException("Invalid coding agent at index $i")
                    val plainToken = o.optString("plainToken")
                    require(plainToken.isNotBlank()) { "Coding agent token missing in backup" }
                    add(
                        CodingAgentEntity(
                            id = o.requireNonBlank("id", "codingAgent"),
                            name = o.requireNonBlank("name", "codingAgent"),
                            agentType = parseEnum(o.optString("agentType"), CodingAgentType.OTHER),
                            modelAlias = o.optString("modelAlias", "jh-coder"),
                            defaultModelId = o.optString("defaultModelId"),
                            allowedModelsCsv = o.optString("allowedModelsCsv"),
                            streaming = o.optBoolean("streaming", true),
                            toolCalling = o.optBoolean("toolCalling", true),
                            dailyTokenLimit = if (o.isNull("dailyTokenLimit")) null else o.optLong("dailyTokenLimit"),
                            enabled = o.optBoolean("enabled", true),
                            encryptedToken = "",
                            tokenPreview = o.optString("tokenPreview").ifBlank { MaskingUtils.maskSecret(plainToken) }
                        ) to plainToken
                    )
                }
            }
        }

        val localAiServersPlain = buildList {
            dataObj.optJSONArray("localAiServers")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: throw IllegalArgumentException("Invalid local server at index $i")
                    val serverUrl = o.requireNonBlank("serverUrl", "localAiServer")
                    require(serverUrl.startsWith("http://", true) || serverUrl.startsWith("https://", true)) {
                        "Invalid local server URL in backup"
                    }
                    val plainAuth = o.optString("plainAuthToken")
                    add(
                        LocalAiServerEntity(
                            id = o.requireNonBlank("id", "localAiServer"),
                            name = o.requireNonBlank("name", "localAiServer"),
                            engineType = parseEnum(o.optString("engineType"), LocalEngineType.CUSTOM),
                            serverUrl = serverUrl,
                            encryptedAuthToken = "",
                            hasAuth = plainAuth.isNotBlank(),
                            timeoutSec = o.optInt("timeoutSec", 60),
                            defaultModel = o.optString("defaultModel"),
                            isPrivateNetwork = o.optBoolean("isPrivateNetwork", true),
                            enabled = o.optBoolean("enabled", true)
                        ) to plainAuth
                    )
                }
            }
        }

        val mcpServersPlain = buildList {
            dataObj.optJSONArray("mcpServers")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: throw IllegalArgumentException("Invalid MCP server at index $i")
                    val plainEnv = o.optString("plainEnvJson")
                    val plainAuth = o.optString("plainAuthToken")
                    add(
                        Triple(
                            McpServerEntity(
                                id = o.requireNonBlank("id", "mcpServer"),
                                name = o.requireNonBlank("name", "mcpServer"),
                                transportType = parseEnum(o.optString("transportType"), McpTransportType.STREAMABLE_HTTP),
                                endpointOrCommand = o.requireNonBlank("endpointOrCommand", "mcpServer"),
                                arguments = o.optString("arguments"),
                                encryptedEnvJson = "",
                                encryptedAuthToken = "",
                                timeoutSec = o.optInt("timeoutSec", 30),
                                resourcesJson = o.optString("resourcesJson", "[]"),
                                promptsJson = o.optString("promptsJson", "[]"),
                                enabled = o.optBoolean("enabled", true)
                            ),
                            plainEnv,
                            plainAuth
                        )
                    )
                }
            }
        }

        val mcpTools = buildList {
            dataObj.optJSONArray("mcpTools")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: throw IllegalArgumentException("Invalid MCP tool at index $i")
                    val schema = o.optString("inputSchemaJson", "{}")
                    runCatching { JSONObject(schema) }.getOrElse {
                        throw IllegalArgumentException("Invalid MCP tool inputSchemaJson in backup")
                    }
                    add(
                        McpToolEntity(
                            id = o.requireNonBlank("id", "mcpTool"),
                            serverId = o.requireNonBlank("serverId", "mcpTool"),
                            toolName = o.requireNonBlank("toolName", "mcpTool"),
                            description = o.optString("description"),
                            inputSchemaJson = schema,
                            permission = parseEnum(o.optString("permission"), ToolPermission.ASK_BEFORE_RUNNING)
                        )
                    )
                }
            }
        }

        val workflows = buildList {
            dataObj.optJSONArray("workflows")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: throw IllegalArgumentException("Invalid workflow at index $i")
                    val nodesJson = o.requireNonBlank("nodesJson", "workflow")
                    val edgesJson = o.requireNonBlank("edgesJson", "workflow")
                    runCatching { JSONArray(nodesJson); JSONArray(edgesJson) }.getOrElse {
                        throw IllegalArgumentException("Corrupted workflow graph JSON in backup")
                    }
                    add(
                        WorkflowEntity(
                            id = o.requireNonBlank("id", "workflow"),
                            name = o.requireNonBlank("name", "workflow"),
                            description = o.optString("description"),
                            nodesJson = nodesJson,
                            edgesJson = edgesJson,
                            isTemplate = false,
                            version = o.optInt("version", 1).coerceAtLeast(1)
                        )
                    )
                }
            }
        }

        val workflowRuns = buildList {
            dataObj.optJSONArray("workflowRuns")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: throw IllegalArgumentException("Invalid workflow run record at index $i")
                    add(
                        WorkflowRunEntity(
                            id = o.requireNonBlank("id", "workflowRun"),
                            workflowId = o.requireNonBlank("workflowId", "workflowRun"),
                            workflowName = o.optString("workflowName", "Workflow"),
                            status = o.optString("status", "COMPLETED"),
                            inputText = o.optString("inputText"),
                            finalOutput = o.optString("finalOutput"),
                            nodeResultsJson = o.optString("nodeResultsJson", "[]"),
                            startedAt = o.optLong("startedAt", System.currentTimeMillis()),
                            finishedAt = if (o.isNull("finishedAt") || !o.has("finishedAt")) null else o.optLong("finishedAt"),
                            errorMessage = if (o.isNull("errorMessage") || !o.has("errorMessage")) null else o.optString("errorMessage")
                        )
                    )
                }
            }
        }

        val devicesPlain = buildList {
            dataObj.optJSONArray("devices")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: throw IllegalArgumentException("Invalid device record at index $i")
                    val plainToken = o.optString("plainToken")
                    require(plainToken.isNotBlank()) { "Device token missing in backup" }
                    add(
                        DeviceEntity(
                            id = o.requireNonBlank("id", "device"),
                            name = o.requireNonBlank("name", "device"),
                            deviceType = o.optString("deviceType", "Client"),
                            deviceCode = o.optString("deviceCode", "DEV-RESTORED"),
                            encryptedToken = "",
                            tokenPreview = o.optString("tokenPreview").ifBlank { MaskingUtils.maskSecret(plainToken) },
                            permissionsCsv = o.optString("permissionsCsv", ""),
                            allowedModelsCsv = o.optString("allowedModelsCsv", ""),
                            connectionType = parseEnum(o.optString("connectionType"), DeviceConnectionType.LOCAL_NETWORK),
                            enabled = o.optBoolean("enabled", true),
                            revoked = o.optBoolean("revoked", false)
                        ) to plainToken
                    )
                }
            }
        }

        val playgroundSessions = buildList {
            dataObj.optJSONArray("playgroundSessions")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: throw IllegalArgumentException("Invalid playground session record at index $i")
                    add(
                        PlaygroundSessionEntity(
                            id = o.requireNonBlank("id", "playgroundSession"),
                            title = o.optString("title", "Chat"),
                            modelId = o.optString("modelId"),
                            providerId = o.optString("providerId"),
                            systemPrompt = o.optString("systemPrompt"),
                            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                            updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
                        )
                    )
                }
            }
        }

        val playgroundMessages = buildList {
            dataObj.optJSONArray("playgroundMessages")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: throw IllegalArgumentException("Invalid playground message record at index $i")
                    add(
                        PlaygroundMessageEntity(
                            id = o.requireNonBlank("id", "playgroundMessage"),
                            sessionId = o.requireNonBlank("sessionId", "playgroundMessage"),
                            role = o.optString("role", "user"),
                            content = o.optString("content"),
                            modelId = o.optString("modelId"),
                            providerName = o.optString("providerName"),
                            latencyMs = if (o.isNull("latencyMs") || !o.has("latencyMs")) null else o.optLong("latencyMs"),
                            inputTokens = if (o.isNull("inputTokens") || !o.has("inputTokens")) null else o.optInt("inputTokens"),
                            outputTokens = if (o.isNull("outputTokens") || !o.has("outputTokens")) null else o.optInt("outputTokens"),
                            estimatedCostUsd = if (o.isNull("estimatedCostUsd") || !o.has("estimatedCostUsd")) null else o.optDouble("estimatedCostUsd"),
                            isError = o.optBoolean("isError", false),
                            timestamp = o.optLong("timestamp", System.currentTimeMillis()),
                            attachmentsMetadataJson = sanitizeAttachmentsMetadataForBackup(o.optString("attachmentsMetadataJson", "[]"))
                        )
                    )
                }
            }
        }

        val requestMetrics = buildList {
            dataObj.optJSONArray("requestMetrics")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: throw IllegalArgumentException("Invalid metric record at index $i")
                    add(
                        RequestMetricEntity(
                            id = o.requireNonBlank("id", "requestMetric"),
                            requestId = o.requireNonBlank("requestId", "requestMetric"),
                            timestamp = o.optLong("timestamp", System.currentTimeMillis()),
                            providerId = o.optString("providerId"),
                            providerName = o.optString("providerName"),
                            modelId = o.optString("modelId"),
                            deviceName = o.optString("deviceName", "Local Playground"),
                            endpoint = o.optString("endpoint", "/v1/chat/completions"),
                            statusCode = o.optInt("statusCode", 200),
                            success = o.optBoolean("success", true),
                            latencyMs = o.optLong("latencyMs", 0L),
                            inputTokens = o.optInt("inputTokens", 0),
                            outputTokens = o.optInt("outputTokens", 0),
                            usageSource = o.optString("usageSource", "UNKNOWN"),
                            estimatedCostUsd = if (o.isNull("estimatedCostUsd") || !o.has("estimatedCostUsd")) null else o.optDouble("estimatedCostUsd"),
                            actualBilledCostUsd = if (o.isNull("actualBilledCostUsd") || !o.has("actualBilledCostUsd")) null else o.optDouble("actualBilledCostUsd"),
                            pricingSource = if (o.isNull("pricingSource") || !o.has("pricingSource")) null else o.optString("pricingSource"),
                            pricingUpdatedAt = if (o.isNull("pricingUpdatedAt") || !o.has("pricingUpdatedAt")) null else o.optString("pricingUpdatedAt"),
                            fallbackAttempts = o.optInt("fallbackAttempts", 0),
                            routingMode = o.optString("routingMode", "AUTOMATIC"),
                            routingReason = o.optString("routingReason"),
                            errorCode = if (o.isNull("errorCode") || !o.has("errorCode")) null else o.optString("errorCode"),
                            safeErrorMessage = if (o.isNull("safeErrorMessage") || !o.has("safeErrorMessage")) null else o.optString("safeErrorMessage")
                        )
                    )
                }
            }
        }

        val preferences = dataObj.optJSONObject("preferences")?.let { o ->
            val state = GatewaySettingsState(
                themeMode = parseEnum(o.optString("themeMode"), ThemeMode.SYSTEM),
                language = parseEnum(o.optString("language"), AppLanguage.ENGLISH),
                onboardingCompleted = o.optBoolean("onboardingCompleted", false),
                gatewayName = o.optString("gatewayName", "JH GetWay Primary"),
                gatewayMode = parseEnum(o.optString("gatewayMode"), GatewayMode.LOCAL),
                gatewayHost = o.optString("gatewayHost", "127.0.0.1"),
                gatewayPort = o.optInt("gatewayPort", 8787).coerceIn(1024, 65535),
                gatewayRemoteUrl = o.optString("gatewayRemoteUrl", ""),
                gatewayAuthRequired = o.optBoolean("gatewayAuthRequired", true),
                gatewayTimeoutSec = o.optInt("gatewayTimeoutSec", 60),
                gatewayMaxRequestKb = o.optInt("gatewayMaxRequestKb", 2048),
                gatewayStreamingEnabled = o.optBoolean("gatewayStreamingEnabled", true),
                gatewayRateLimitPerMin = o.optInt("gatewayRateLimitPerMin", 60),
                gatewayLoggingLevel = parseEnum(o.optString("gatewayLoggingLevel"), LoggingLevel.METADATA_ONLY),
                gatewayAllowLanExposure = o.optBoolean("gatewayAllowLanExposure", false),
                defaultModelId = o.optString("defaultModelId", ""),
                defaultProviderId = o.optString("defaultProviderId", ""),
                defaultRoutingMode = parseEnum(o.optString("defaultRoutingMode"), RoutingMode.AUTOMATIC),
                fallbackEnabled = o.optBoolean("fallbackEnabled", true),
                maxRetries = o.optInt("maxRetries", 2),
                localRoutingPolicy = parseEnum(o.optString("localRoutingPolicy"), LocalRoutingPolicy.LOCAL_CLOUD_FALLBACK),
                allowCloudFallbackForLocal = o.optBoolean("allowCloudFallbackForLocal", true),
                localNetworkAccessAllowed = o.optBoolean("localNetworkAccessAllowed", false),
                saveChatHistoryAllowed = o.optBoolean("saveChatHistoryAllowed", true),
                analyticsCurrency = o.optString("analyticsCurrency", "USD"),
                logRetentionDays = o.optInt("logRetentionDays", 30),
                usageRetentionDays = o.optInt("usageRetentionDays", 90),
                playgroundRetentionDays = o.optInt("playgroundRetentionDays", 60),
                notifyProviderFailure = o.optBoolean("notifyProviderFailure", true),
                notifyGatewayDisconnected = o.optBoolean("notifyGatewayDisconnected", true),
                notifyRateLimit = o.optBoolean("notifyRateLimit", true),
                notifyWorkflowCompleted = o.optBoolean("notifyWorkflowCompleted", true),
                notifySecurityEvent = o.optBoolean("notifySecurityEvent", true),
                githubUpdateRepo = o.optString("githubUpdateRepo", ""),
                autoCheckUpdates = o.optBoolean("autoCheckUpdates", true),
                manualModelFallbackEnabled = o.optBoolean("manualModelFallbackEnabled", false),
                autoFreeIncludeLocal = o.optBoolean("autoFreeIncludeLocal", false)
            )
            val remotePlain = o.optString("gatewayRemoteAuthTokenPlain").takeIf { o.has("gatewayRemoteAuthTokenPlain") && it.isNotBlank() }
            StagedPreferences(state = state, remoteAuthTokenPlain = remotePlain)
        }

        val manifest = BackupManifest(
            backupFormatVersion = manifestJson?.optInt("backupFormatVersion", formatVersion) ?: formatVersion,
            schemaVersion = manifestJson?.optInt("schemaVersion", schemaVersion) ?: schemaVersion,
            createdAt = manifestJson?.optLong("createdAt", exportedAt) ?: exportedAt,
            appVersionName = manifestJson?.optString("appVersionName", "") ?: "",
            appVersionCode = manifestJson?.optInt("appVersionCode", 0) ?: 0,
            applicationId = manifestAppId,
            includedCategories = manifestJson?.optJSONArray("includedCategories")?.toStringList() ?: emptyList(),
            nonPortableCategories = manifestJson?.optJSONArray("nonPortableCategories")?.toStringList()
                ?: DEFAULT_NON_PORTABLE_CATEGORIES
        )

        return StagedArchive(
            formatVersion = formatVersion,
            schemaVersion = schemaVersion,
            exportedAt = exportedAt,
            kdfAlgorithm = envelope.kdfAlgorithm,
            kdfIterations = envelope.kdfIterations,
            integrityVerified = integrityVerified,
            manifest = manifest,
            providers = providers,
            apiKeysPlain = apiKeysPlain,
            models = models,
            routingRules = routingRules,
            codingAgentsPlain = codingAgentsPlain,
            localAiServersPlain = localAiServersPlain,
            mcpServersPlain = mcpServersPlain,
            mcpTools = mcpTools,
            workflows = workflows,
            devicesPlain = devicesPlain,
            workflowRuns = workflowRuns,
            playgroundSessions = playgroundSessions,
            playgroundMessages = playgroundMessages,
            requestMetrics = requestMetrics,
            preferences = preferences
        )
    }

    private fun sanitizeAttachmentsMetadataForBackup(rawJson: String): String {
        if (rawJson.isBlank() || rawJson == "[]") return "[]"
        return runCatching {
            val arr = JSONArray(rawJson)
            val sanitized = JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                sanitized.put(JSONObject().apply {
                    put("id", obj.optString("id"))
                    put("displayName", obj.optString("displayName", "file"))
                    put("mimeType", obj.optString("mimeType", "application/octet-stream"))
                    put("sizeBytes", obj.optLong("sizeBytes", 0L))
                    put("category", obj.optString("category", AttachmentCategory.DOCUMENT.name))
                    put("localFilePath", "")
                })
            }
            sanitized.toString()
        }.getOrDefault("[]")
    }

    private fun JSONArray.toStringList(): List<String> = buildList {
        for (i in 0 until length()) {
            val v = optString(i, "").trim()
            if (v.isNotEmpty()) add(v)
        }
    }

    private fun JSONObject.requireNonBlank(key: String, entityLabel: String): String {
        val value = optString(key, "").trim()
        require(value.isNotEmpty()) { "Backup $entityLabel record missing required '$key'" }
        return value
    }

    private inline fun <reified E : Enum<E>> parseEnum(raw: String, fallback: E): E {
        return runCatching { enumValueOf<E>(raw.trim()) }.getOrDefault(fallback)
    }

    private fun canonicalJson(obj: JSONObject): String {
        val keys = obj.keys().asSequence().toList().sorted()
        val sb = StringBuilder("{")
        keys.forEachIndexed { idx, key ->
            if (idx > 0) sb.append(',')
            sb.append(JSONObject.quote(key)).append(':').append(canonicalValue(obj.get(key)))
        }
        sb.append('}')
        return sb.toString()
    }

    private fun canonicalValue(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> canonicalJson(value)
        is JSONArray -> {
            val sb = StringBuilder("[")
            for (i in 0 until value.length()) {
                if (i > 0) sb.append(',')
                sb.append(canonicalValue(value.get(i)))
            }
            sb.append(']')
            sb.toString()
        }
        is String -> JSONObject.quote(value)
        is Number, is Boolean -> value.toString()
        else -> JSONObject.quote(value.toString())
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
