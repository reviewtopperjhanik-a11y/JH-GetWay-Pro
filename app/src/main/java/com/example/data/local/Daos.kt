package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProviderDao {
    @Query("SELECT * FROM providers ORDER BY priority ASC, createdAt DESC")
    fun observeAll(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers ORDER BY priority ASC, createdAt DESC")
    suspend fun getAllOnce(): List<ProviderEntity>

    @Query("SELECT * FROM providers WHERE id = :id")
    suspend fun getById(id: String): ProviderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(provider: ProviderEntity)

    @Query("DELETE FROM providers WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM providers")
    suspend fun clearAll()
}

@Dao
interface ApiKeyDao {
    @Query("SELECT * FROM api_keys ORDER BY priority ASC, createdAt DESC")
    fun observeAll(): Flow<List<ApiKeyEntity>>

    @Query("SELECT * FROM api_keys WHERE providerId = :providerId ORDER BY isDefault DESC, priority ASC")
    suspend fun getForProvider(providerId: String): List<ApiKeyEntity>

    @Query("SELECT * FROM api_keys")
    suspend fun getAllOnce(): List<ApiKeyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(key: ApiKeyEntity)

    @Query("DELETE FROM api_keys WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM api_keys WHERE providerId = :providerId")
    suspend fun deleteByProviderId(providerId: String)

    @Query("DELETE FROM api_keys")
    suspend fun clearAll()
}

data class ModelSummaryCounts(
    val totalCount: Int,
    val enabledCount: Int,
    val localCount: Int,
    val cloudCount: Int
)

@Dao
interface ModelDao {
    @Query("SELECT * FROM models ORDER BY isDefault DESC, isLocal ASC, displayName ASC")
    fun observeAll(): Flow<List<ModelEntity>>

    @Query(
        """
        SELECT 
            COUNT(*) AS totalCount,
            COALESCE(SUM(CASE WHEN enabled = 1 THEN 1 ELSE 0 END), 0) AS enabledCount,
            COALESCE(SUM(CASE WHEN isLocal = 1 THEN 1 ELSE 0 END), 0) AS localCount,
            COALESCE(SUM(CASE WHEN isLocal = 0 THEN 1 ELSE 0 END), 0) AS cloudCount
        FROM models
        """
    )
    fun observeModelSummaryCounts(): Flow<ModelSummaryCounts>

    @Query("SELECT * FROM models")
    suspend fun getAllOnce(): List<ModelEntity>

    @Query("SELECT * FROM models WHERE providerId = :providerId")
    suspend fun getByProvider(providerId: String): List<ModelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(models: List<ModelEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(model: ModelEntity)

    @Query("UPDATE models SET isDefault = 0")
    suspend fun clearDefaultFlag()

    @Query("DELETE FROM models WHERE providerId = :providerId")
    suspend fun deleteByProviderId(providerId: String)

    @Query("DELETE FROM models WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM models")
    suspend fun clearAll()
}

@Dao
interface RoutingRuleDao {
    @Query("SELECT * FROM routing_rules ORDER BY priority ASC")
    fun observeAll(): Flow<List<RoutingRuleEntity>>

    @Query("SELECT * FROM routing_rules ORDER BY priority ASC")
    suspend fun getAllOnce(): List<RoutingRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: RoutingRuleEntity)

    @Query("DELETE FROM routing_rules WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM routing_rules")
    suspend fun clearAll()
}

@Dao
interface CodingAgentDao {
    @Query("SELECT * FROM coding_agents ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<CodingAgentEntity>>

    @Query("SELECT * FROM coding_agents")
    suspend fun getAllOnce(): List<CodingAgentEntity>

    @Query("SELECT * FROM coding_agents WHERE id = :id")
    suspend fun getById(id: String): CodingAgentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(agent: CodingAgentEntity)

    @Query("UPDATE coding_agents SET tokensUsedToday = 0, tokensResetDateUtc = :utcDate, lastUsageSource = 'UNKNOWN' WHERE id = :id AND tokensResetDateUtc != :utcDate")
    suspend fun resetDailyUsageIfNewUtcDay(id: String, utcDate: String): Int

    @Query("UPDATE coding_agents SET requestCount = requestCount + 1, lastActiveAt = :now WHERE id = :id")
    suspend fun recordRequestStarted(id: String, now: Long)

    @Query("UPDATE coding_agents SET tokensUsedToday = tokensUsedToday + :deltaTokens, lastUsageSource = :usageSource, tokensResetDateUtc = :utcDate, lastActiveAt = :now WHERE id = :id")
    suspend fun addUsageDelta(id: String, deltaTokens: Long, usageSource: String, utcDate: String, now: Long)

    @Transaction
    suspend fun rollUtcDayIfNeeded(id: String, todayUtc: String): CodingAgentEntity? {
        resetDailyUsageIfNewUtcDay(id, todayUtc)
        return getById(id)
    }

    @Transaction
    suspend fun reconcileUsageAtomic(
        id: String,
        deltaTokens: Long,
        usageSource: String,
        todayUtc: String,
        now: Long
    ) {
        resetDailyUsageIfNewUtcDay(id, todayUtc)
        addUsageDelta(id, deltaTokens.coerceAtLeast(0L), usageSource, todayUtc, now)
    }

    @Query("DELETE FROM coding_agents WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM coding_agents")
    suspend fun clearAll()
}

@Dao
interface LocalAiServerDao {
    @Query("SELECT * FROM local_ai_servers ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<LocalAiServerEntity>>

    @Query("SELECT * FROM local_ai_servers")
    suspend fun getAllOnce(): List<LocalAiServerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(server: LocalAiServerEntity)

    @Query("DELETE FROM local_ai_servers WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM local_ai_servers")
    suspend fun clearAll()
}

@Dao
interface McpDao {
    @Query("SELECT * FROM mcp_servers ORDER BY createdAt DESC")
    fun observeServers(): Flow<List<McpServerEntity>>

    @Query("SELECT * FROM mcp_servers")
    suspend fun getServersOnce(): List<McpServerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertServer(server: McpServerEntity)

    @Query("DELETE FROM mcp_servers WHERE id = :id")
    suspend fun deleteServerById(id: String)

    @Query("SELECT * FROM mcp_tools ORDER BY toolName ASC")
    fun observeTools(): Flow<List<McpToolEntity>>

    @Query("SELECT * FROM mcp_tools WHERE serverId = :serverId")
    suspend fun getToolsForServer(serverId: String): List<McpToolEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTools(tools: List<McpToolEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTool(tool: McpToolEntity)

    @Query("DELETE FROM mcp_tools WHERE serverId = :serverId")
    suspend fun deleteToolsForServer(serverId: String)

    @Query("DELETE FROM mcp_servers")
    suspend fun clearServers()

    @Query("DELETE FROM mcp_tools")
    suspend fun clearTools()
}

@Dao
interface WorkflowDao {
    @Query("SELECT * FROM workflows ORDER BY isTemplate ASC, updatedAt DESC")
    fun observeWorkflows(): Flow<List<WorkflowEntity>>

    @Query("SELECT * FROM workflows")
    suspend fun getWorkflowsOnce(): List<WorkflowEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWorkflow(workflow: WorkflowEntity)

    @Query("DELETE FROM workflows WHERE id = :id")
    suspend fun deleteWorkflow(id: String)

    @Query("SELECT * FROM workflow_runs ORDER BY startedAt DESC LIMIT 100")
    fun observeRuns(): Flow<List<WorkflowRunEntity>>

    @Query("SELECT * FROM workflow_runs ORDER BY startedAt DESC")
    suspend fun getRunsOnce(): List<WorkflowRunEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRun(run: WorkflowRunEntity)

    @Query("DELETE FROM workflow_runs WHERE startedAt < :cutoff")
    suspend fun deleteRunsOlderThan(cutoff: Long): Int

    @Query("DELETE FROM workflows")
    suspend fun clearWorkflows()

    @Query("DELETE FROM workflow_runs")
    suspend fun clearRuns()
}

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices")
    suspend fun getAllOnce(): List<DeviceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: DeviceEntity)

    @Query("UPDATE devices SET requestCount = requestCount + 1, lastActiveAt = :now WHERE id = :id")
    suspend fun recordRequestStarted(id: String, now: Long)

    @Query("DELETE FROM devices WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM devices")
    suspend fun clearAll()
}

@Dao
interface MetricAndLogDao {
    @Query("SELECT * FROM request_metrics ORDER BY timestamp DESC LIMIT 500")
    fun observeMetrics(): Flow<List<RequestMetricEntity>>

    @Query("SELECT * FROM request_metrics ORDER BY timestamp DESC")
    suspend fun getMetricsOnce(): List<RequestMetricEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMetric(metric: RequestMetricEntity)

    @Query("SELECT * FROM activity_logs ORDER BY timestamp DESC LIMIT 500")
    fun observeLogs(): Flow<List<ActivityLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: ActivityLogEntity)

    @Query("DELETE FROM activity_logs")
    suspend fun clearLogs()

    @Query("DELETE FROM activity_logs WHERE timestamp < :cutoff")
    suspend fun deleteLogsOlderThan(cutoff: Long): Int

    @Query("DELETE FROM activity_logs WHERE id NOT IN (SELECT id FROM activity_logs ORDER BY timestamp DESC LIMIT :maxRows)")
    suspend fun trimLogsToMaxRows(maxRows: Int): Int

    @Query("DELETE FROM request_metrics WHERE timestamp < :cutoff")
    suspend fun deleteMetricsOlderThan(cutoff: Long): Int

    @Query("DELETE FROM request_metrics WHERE id NOT IN (SELECT id FROM request_metrics ORDER BY timestamp DESC LIMIT :maxRows)")
    suspend fun trimMetricsToMaxRows(maxRows: Int): Int

    @Query("DELETE FROM request_metrics")
    suspend fun clearMetrics()
}

@Dao
interface PlaygroundDao {
    @Query("SELECT * FROM playground_sessions ORDER BY updatedAt DESC")
    fun observeSessions(): Flow<List<PlaygroundSessionEntity>>

    @Query("SELECT * FROM playground_sessions ORDER BY updatedAt DESC")
    suspend fun getSessionsOnce(): List<PlaygroundSessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: PlaygroundSessionEntity)

    @Query("UPDATE playground_sessions SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touchSessionUpdatedAt(id: String, updatedAt: Long): Int

    @Query("DELETE FROM playground_sessions WHERE id = :id")
    suspend fun deleteSession(id: String)

    @Query("SELECT * FROM playground_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun observeMessages(sessionId: String): Flow<List<PlaygroundMessageEntity>>

    @Query("SELECT * FROM playground_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessagesOnce(sessionId: String): List<PlaygroundMessageEntity>

    @Query("SELECT * FROM playground_messages ORDER BY timestamp ASC")
    suspend fun getAllMessagesOnce(): List<PlaygroundMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: PlaygroundMessageEntity)

    @Query("SELECT attachmentsMetadataJson FROM playground_messages WHERE sessionId = :sessionId AND attachmentsMetadataJson != '[]'")
    suspend fun getAttachmentMetadataForSession(sessionId: String): List<String>

    @Query("SELECT attachmentsMetadataJson FROM playground_messages WHERE attachmentsMetadataJson != '[]' AND (timestamp < :cutoff OR sessionId IN (SELECT id FROM playground_sessions WHERE updatedAt < :cutoff))")
    suspend fun getAttachmentMetadataOlderThan(cutoff: Long): List<String>

    @Query("DELETE FROM playground_messages WHERE sessionId = :sessionId")
    suspend fun clearMessagesForSession(sessionId: String)

    @Query("DELETE FROM playground_messages WHERE timestamp < :cutoff")
    suspend fun deleteMessagesOlderThan(cutoff: Long): Int

    @Query("DELETE FROM playground_messages WHERE sessionId IN (SELECT id FROM playground_sessions WHERE updatedAt < :cutoff)")
    suspend fun deleteMessagesForExpiredSessions(cutoff: Long): Int

    @Query("DELETE FROM playground_sessions WHERE updatedAt < :cutoff")
    suspend fun deleteSessionsOlderThan(cutoff: Long): Int

    @Query("DELETE FROM playground_sessions")
    suspend fun clearSessions()

    @Query("DELETE FROM playground_messages")
    suspend fun clearMessages()
}

@Dao
interface ServicePoolDao {
    @Query("SELECT * FROM service_pool_keys ORDER BY priority ASC, createdAt ASC")
    fun observeAll(): Flow<List<ServicePoolKeyEntity>>

    @Query("SELECT * FROM service_pool_keys WHERE serviceType = :serviceType ORDER BY priority ASC, createdAt ASC")
    fun observeByService(serviceType: ServicePoolType): Flow<List<ServicePoolKeyEntity>>

    @Query("SELECT * FROM service_pool_keys ORDER BY priority ASC, createdAt ASC")
    suspend fun getAllOnce(): List<ServicePoolKeyEntity>

    @Query("SELECT * FROM service_pool_keys WHERE serviceType = :serviceType ORDER BY priority ASC, createdAt ASC")
    suspend fun getByServiceOnce(serviceType: ServicePoolType): List<ServicePoolKeyEntity>

    @Query("SELECT * FROM service_pool_keys WHERE id = :id LIMIT 1")
    suspend fun getByIdOnce(id: String): ServicePoolKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(key: ServicePoolKeyEntity)

    @Query("DELETE FROM service_pool_keys WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM service_pool_keys WHERE serviceType = :serviceType")
    suspend fun clearByService(serviceType: ServicePoolType)

    @Query("DELETE FROM service_pool_keys")
    suspend fun clearAll()

    @Query(
        "UPDATE service_pool_keys SET " +
            "status = :status, " +
            "usageCount = usageCount + 1, " +
            "lastUsedAt = :nowMs, " +
            "lastError = NULL, " +
            "cooldownUntilMs = :cooldownUntilMs, " +
            "rateLimitRemaining = :rateLimitRemaining, " +
            "rateLimitResetAtMs = :rateLimitResetAtMs " +
            "WHERE id = :id"
    )
    suspend fun recordSuccess(
        id: String,
        status: KeyStatus,
        nowMs: Long,
        cooldownUntilMs: Long?,
        rateLimitRemaining: Int?,
        rateLimitResetAtMs: Long?
    )

    @Query(
        "UPDATE service_pool_keys SET " +
            "status = :status, " +
            "lastUsedAt = :nowMs, " +
            "lastError = :lastError, " +
            "cooldownUntilMs = :cooldownUntilMs, " +
            "rateLimitRemaining = :rateLimitRemaining, " +
            "rateLimitResetAtMs = :rateLimitResetAtMs " +
            "WHERE id = :id"
    )
    suspend fun recordFailureOrCooldown(
        id: String,
        status: KeyStatus,
        nowMs: Long,
        lastError: String?,
        cooldownUntilMs: Long?,
        rateLimitRemaining: Int?,
        rateLimitResetAtMs: Long?
    )
}

