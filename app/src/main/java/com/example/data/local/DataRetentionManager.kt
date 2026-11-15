package com.example.data.local

import androidx.room.withTransaction

data class RetentionPruneReport(
    val deletedLogs: Int,
    val deletedMetrics: Int,
    val deletedWorkflowRuns: Int,
    val deletedPlaygroundMessages: Int,
    val deletedPlaygroundSessions: Int
) {
    val totalDeleted: Int
        get() = deletedLogs + deletedMetrics + deletedWorkflowRuns +
            deletedPlaygroundMessages + deletedPlaygroundSessions
}

/**
 * Enforces deterministic, transactional retention policies across activity logs,
 * request usage metrics, workflow execution runs, and Playground conversation history.
 */
class DataRetentionManager(private val db: JhGetWayDatabase) {

    companion object {
        const val MIN_RETENTION_DAYS = 7
        const val MAX_RETENTION_DAYS = 365
        const val DEFAULT_LOG_RETENTION_DAYS = 30
        const val DEFAULT_USAGE_RETENTION_DAYS = 90
        const val DEFAULT_PLAYGROUND_RETENTION_DAYS = 60
        const val MAX_ACTIVITY_LOG_ROWS = 10_000
        const val MAX_REQUEST_METRIC_ROWS = 50_000
        private const val MILLIS_PER_DAY = 86_400_000L
    }

    suspend fun enforcePolicies(
        logRetentionDays: Int,
        usageRetentionDays: Int,
        playgroundRetentionDays: Int,
        nowMs: Long = System.currentTimeMillis(),
        onExpiredAttachments: (List<String>) -> Unit = {}
    ): RetentionPruneReport {
        val safeLogDays = logRetentionDays.coerceIn(MIN_RETENTION_DAYS, MAX_RETENTION_DAYS)
        val safeUsageDays = usageRetentionDays.coerceIn(MIN_RETENTION_DAYS, MAX_RETENTION_DAYS)
        val safePlaygroundDays = playgroundRetentionDays.coerceIn(MIN_RETENTION_DAYS, MAX_RETENTION_DAYS)

        val logCutoff = nowMs - safeLogDays * MILLIS_PER_DAY
        val usageCutoff = nowMs - safeUsageDays * MILLIS_PER_DAY
        val playgroundCutoff = nowMs - safePlaygroundDays * MILLIS_PER_DAY

        val (report, expiredAttachments) = db.withTransaction {
            val metricAndLogDao = db.metricAndLogDao()
            val deletedLogsAge = metricAndLogDao.deleteLogsOlderThan(logCutoff)
            val deletedLogsCap = metricAndLogDao.trimLogsToMaxRows(MAX_ACTIVITY_LOG_ROWS)

            val deletedMetricsAge = metricAndLogDao.deleteMetricsOlderThan(usageCutoff)
            val deletedMetricsCap = metricAndLogDao.trimMetricsToMaxRows(MAX_REQUEST_METRIC_ROWS)

            val deletedRuns = db.workflowDao().deleteRunsOlderThan(usageCutoff)

            val playgroundDao = db.playgroundDao()
            val attachmentMetaToPrune = playgroundDao.getAttachmentMetadataOlderThan(playgroundCutoff)
            val deletedMsgsExpiredSessions = playgroundDao.deleteMessagesForExpiredSessions(playgroundCutoff)
            val deletedMsgsOld = playgroundDao.deleteMessagesOlderThan(playgroundCutoff)
            val deletedSessions = playgroundDao.deleteSessionsOlderThan(playgroundCutoff)

            RetentionPruneReport(
                deletedLogs = deletedLogsAge + deletedLogsCap,
                deletedMetrics = deletedMetricsAge + deletedMetricsCap,
                deletedWorkflowRuns = deletedRuns,
                deletedPlaygroundMessages = deletedMsgsExpiredSessions + deletedMsgsOld,
                deletedPlaygroundSessions = deletedSessions
            ) to attachmentMetaToPrune
        }
        if (expiredAttachments.isNotEmpty()) {
            onExpiredAttachments(expiredAttachments)
        }
        return report
    }
}
