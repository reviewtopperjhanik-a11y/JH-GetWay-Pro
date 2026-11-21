package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

enum class ProviderType { BUILT_IN, OPENAI_COMPATIBLE, ANTHROPIC_COMPATIBLE, CUSTOM }
enum class ApiFormat { OPENAI, GEMINI, ANTHROPIC, COHERE, HUGGINGFACE, OLLAMA }
enum class ProviderStatus { CONNECTED, DISCONNECTED, CHECKING, ERROR, RATE_LIMITED, DISABLED, UNKNOWN }

@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: ProviderType = ProviderType.BUILT_IN,
    val presetId: String = "CUSTOM",
    val baseUrl: String,
    val apiFormat: ApiFormat = ApiFormat.OPENAI,
    val defaultModel: String = "",
    val priority: Int = 10,
    val enabled: Boolean = true,
    val customHeadersJson: String = "",
    val notes: String = "",
    val status: ProviderStatus = ProviderStatus.UNKNOWN,
    val lastLatencyMs: Long? = null,
    val lastError: String? = null,
    val lastCheckedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val cooldownUntilMs: Long? = null
)

enum class KeyStatus { ACTIVE, INVALID, EXPIRED, RATE_LIMITED, DISABLED, UNKNOWN }
enum class KeySelectionPolicy { MANUAL, PRIORITY, ROUND_ROBIN, FAILOVER }

@Entity(
    tableName = "api_keys",
    indices = [Index(value = ["providerId"])]
)
data class ApiKeyEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val providerId: String,
    val label: String,
    val encryptedKey: String,
    val maskedPreview: String,
    val priority: Int = 1,
    val isDefault: Boolean = false,
    val enabled: Boolean = true,
    val status: KeyStatus = KeyStatus.UNKNOWN,
    val selectionPolicy: KeySelectionPolicy = KeySelectionPolicy.PRIORITY,
    val usageCount: Long = 0,
    val lastUsedAt: Long? = null,
    val lastTestedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val cooldownUntilMs: Long? = null
)

enum class ModelType {
    GENERAL_CHAT, CODING, REASONING, VISION, IMAGE_GENERATION, EMBEDDING, AUDIO, MULTIMODAL, CUSTOM
}

enum class AccessTier {
    FREE_TIER,
    PAID,
    LOCAL,
    UNKNOWN
}

@Entity(
    tableName = "models",
    indices = [Index(value = ["providerId"]), Index(value = ["modelId"])]
)
data class ModelEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val modelId: String,
    val displayName: String,
    val providerId: String,
    val providerName: String,
    val modelType: ModelType = ModelType.GENERAL_CHAT,
    val capabilitiesCsv: String = "CHAT,STREAMING",
    val contextWindow: Int? = null,
    val supportsTools: Boolean = false,
    val supportsStreaming: Boolean = true,
    val isLocal: Boolean = false,
    val inputPricePer1M: Double? = null,
    val outputPricePer1M: Double? = null,
    val pricingUpdatedAt: String? = null,
    val enabled: Boolean = true,
    val isDefault: Boolean = false,
    val alias: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val accessTier: AccessTier = if (isLocal) AccessTier.LOCAL else AccessTier.UNKNOWN,
    val freeTierVerified: Boolean = false,
    val freeTierSource: String = "",
    val freeTierVerifiedAt: Long? = null,
    val freeTierLimitsNote: String = "",
    val supportsChat: Boolean = true,
    val supportsVision: Boolean = false
)

@Entity(tableName = "routing_rules")
data class RoutingRuleEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val modelAlias: String,
    val allowedProviderIdsCsv: String = "",
    val preferredProviderId: String = "",
    val backupProviderIdsCsv: String = "",
    val priority: Int = 1,
    val requiredCapabilitiesCsv: String = "",
    val maxCostPer1M: Double? = null,
    val maxLatencyMs: Long? = null,
    val localCloudPreference: String = "ANY",
    val enabled: Boolean = true
)

enum class CodingAgentType { OPENCODE, CODEX, CONTINUE, CLINE, AIDER, OTHER }

@Entity(
    tableName = "coding_agents",
    indices = [Index(value = ["enabled"])]
)
data class CodingAgentEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val agentType: CodingAgentType,
    val modelAlias: String = "jh-coder",
    val defaultModelId: String = "",
    val allowedModelsCsv: String = "",
    val streaming: Boolean = true,
    val toolCalling: Boolean = true,
    val dailyTokenLimit: Long? = null,
    val tokensUsedToday: Long = 0,
    val tokensResetDateUtc: String = "",
    val lastUsageSource: String = "UNKNOWN",
    val requestCount: Long = 0,
    val enabled: Boolean = true,
    val encryptedToken: String,
    val tokenPreview: String,
    val lastActiveAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

enum class LocalEngineType { OLLAMA, LLAMA_CPP, LM_STUDIO, LOCAL_AI, OPENAI_COMPATIBLE, CUSTOM }

@Entity(tableName = "local_ai_servers")
data class LocalAiServerEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val engineType: LocalEngineType,
    val serverUrl: String,
    val encryptedAuthToken: String = "",
    val hasAuth: Boolean = false,
    val timeoutSec: Int = 60,
    val defaultModel: String = "",
    val isPrivateNetwork: Boolean = true,
    val status: ProviderStatus = ProviderStatus.UNKNOWN,
    val lastLatencyMs: Long? = null,
    val lastError: String? = null,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastCheckedAt: Long? = null,
    val cooldownUntilMs: Long? = null
)

enum class McpTransportType { STDIO, STREAMABLE_HTTP }
enum class ToolPermission { ALLOWED, ASK_BEFORE_RUNNING, DENIED }

@Entity(tableName = "mcp_servers")
data class McpServerEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val transportType: McpTransportType,
    val endpointOrCommand: String,
    val arguments: String = "",
    val encryptedEnvJson: String = "",
    val encryptedAuthToken: String = "",
    val timeoutSec: Int = 30,
    val status: ProviderStatus = ProviderStatus.DISCONNECTED,
    val lastConnectedAt: Long? = null,
    val lastError: String? = null,
    val resourcesJson: String = "[]",
    val promptsJson: String = "[]",
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "mcp_tools",
    indices = [Index(value = ["serverId"])]
)
data class McpToolEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val serverId: String,
    val toolName: String,
    val description: String = "",
    val inputSchemaJson: String = "{}",
    val permission: ToolPermission = ToolPermission.ASK_BEFORE_RUNNING,
    val lastResultPreview: String = "",
    val lastExecutedAt: Long? = null
)

@Entity(tableName = "workflows")
data class WorkflowEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val nodesJson: String,
    val edgesJson: String,
    val isTemplate: Boolean = false,
    val version: Int = 1,
    val lastRunStatus: String? = null,
    val lastRunAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "workflow_runs",
    indices = [Index(value = ["workflowId"]), Index(value = ["startedAt"])]
)
data class WorkflowRunEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val workflowId: String,
    val workflowName: String,
    val status: String, // RUNNING, COMPLETED, FAILED, CANCELLED
    val inputText: String,
    val finalOutput: String = "",
    val nodeResultsJson: String = "[]",
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val errorMessage: String? = null
)

enum class DeviceConnectionType { LOCAL_NETWORK, REMOTE_SERVER, SECURE_TUNNEL }

@Entity(
    tableName = "devices",
    indices = [Index(value = ["enabled"]), Index(value = ["revoked"])]
)
data class DeviceEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val deviceType: String,
    val deviceCode: String,
    val encryptedToken: String,
    val tokenPreview: String,
    val permissionsCsv: String = "CHAT,MODELS_READ",
    val allowedModelsCsv: String = "",
    val connectionType: DeviceConnectionType = DeviceConnectionType.LOCAL_NETWORK,
    val requestCount: Long = 0,
    val enabled: Boolean = true,
    val revoked: Boolean = false,
    val lastActiveAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "request_metrics",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["providerId"]),
        Index(value = ["modelId"]),
        Index(value = ["requestId"])
    ]
)
data class RequestMetricEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val requestId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val deviceName: String = "Local Playground",
    val endpoint: String = "/v1/chat/completions",
    val statusCode: Int = 200,
    val success: Boolean = true,
    val latencyMs: Long = 0,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val usageSource: String = "UNKNOWN",
    val estimatedCostUsd: Double? = null,
    val actualBilledCostUsd: Double? = null,
    val pricingSource: String? = null,
    val pricingUpdatedAt: String? = null,
    val fallbackAttempts: Int = 0,
    val routingMode: String = "AUTOMATIC",
    val routingReason: String = "",
    val errorCode: String? = null,
    val safeErrorMessage: String? = null
)

enum class ActivityCategory {
    GATEWAY_REQUEST, PROVIDER_CONNECTION, PROVIDER_ERROR, FALLBACK_EVENT,
    DEVICE_CONNECTION, KEY_UPDATE, MCP_TOOL, WORKFLOW_EXECUTION, AUTH_EVENT, SYSTEM_EVENT
}

@Entity(
    tableName = "activity_logs",
    indices = [Index(value = ["timestamp"]), Index(value = ["category"])]
)
data class ActivityLogEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val eventId: String = UUID.randomUUID().toString().take(8).uppercase(),
    val timestamp: Long = System.currentTimeMillis(),
    val category: ActivityCategory,
    val relatedEntity: String,
    val status: String,
    val safeMessage: String
)

@Entity(
    tableName = "playground_sessions",
    indices = [Index(value = ["updatedAt"])]
)
data class PlaygroundSessionEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val modelId: String = "",
    val providerId: String = "",
    val systemPrompt: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playground_messages",
    indices = [Index(value = ["sessionId"]), Index(value = ["timestamp"])]
)
data class PlaygroundMessageEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val role: String, // user, assistant, system
    val content: String,
    val modelId: String = "",
    val providerName: String = "",
    val latencyMs: Long? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val estimatedCostUsd: Double? = null,
    val isError: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val attachmentsMetadataJson: String = "[]"
)

enum class ServicePoolType {
    WEB_SEARCH,
    GITHUB_SEARCH
}

@Entity(
    tableName = "service_pool_keys",
    indices = [
        Index(value = ["serviceType", "providerId"]),
        Index(value = ["enabled"])
    ]
)
data class ServicePoolKeyEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val serviceType: ServicePoolType,
    val providerId: String, // tavily, brave, serper, exa, github
    val label: String,
    val encryptedKey: String,
    val maskedPreview: String,
    val priority: Int = 1,
    val enabled: Boolean = true,
    val status: KeyStatus = KeyStatus.UNKNOWN,
    val usageCount: Long = 0,
    val lastUsedAt: Long? = null,
    val lastError: String? = null,
    val cooldownUntilMs: Long? = null,
    val rateLimitRemaining: Int? = null,
    val rateLimitResetAtMs: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

