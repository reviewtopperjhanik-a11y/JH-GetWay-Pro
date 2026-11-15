package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class EnumConverters {
    @TypeConverter fun fromProviderType(v: ProviderType): String = v.name
    @TypeConverter fun toProviderType(v: String): ProviderType = ProviderType.valueOf(v)
    @TypeConverter fun fromApiFormat(v: ApiFormat): String = v.name
    @TypeConverter fun toApiFormat(v: String): ApiFormat = ApiFormat.valueOf(v)
    @TypeConverter fun fromProviderStatus(v: ProviderStatus): String = v.name
    @TypeConverter fun toProviderStatus(v: String): ProviderStatus = ProviderStatus.valueOf(v)
    @TypeConverter fun fromKeyStatus(v: KeyStatus): String = v.name
    @TypeConverter fun toKeyStatus(v: String): KeyStatus = KeyStatus.valueOf(v)
    @TypeConverter fun fromKeyPolicy(v: KeySelectionPolicy): String = v.name
    @TypeConverter fun toKeyPolicy(v: String): KeySelectionPolicy = KeySelectionPolicy.valueOf(v)
    @TypeConverter fun fromModelType(v: ModelType): String = v.name
    @TypeConverter fun toModelType(v: String): ModelType = ModelType.valueOf(v)
    @TypeConverter fun fromAgentType(v: CodingAgentType): String = v.name
    @TypeConverter fun toAgentType(v: String): CodingAgentType = CodingAgentType.valueOf(v)
    @TypeConverter fun fromLocalEngineType(v: LocalEngineType): String = v.name
    @TypeConverter fun toLocalEngineType(v: String): LocalEngineType = LocalEngineType.valueOf(v)
    @TypeConverter fun fromMcpTransportType(v: McpTransportType): String = v.name
    @TypeConverter fun toMcpTransportType(v: String): McpTransportType = McpTransportType.valueOf(v)
    @TypeConverter fun fromToolPermission(v: ToolPermission): String = v.name
    @TypeConverter fun toToolPermission(v: String): ToolPermission = ToolPermission.valueOf(v)
    @TypeConverter fun fromDeviceConnType(v: DeviceConnectionType): String = v.name
    @TypeConverter fun toDeviceConnType(v: String): DeviceConnectionType = DeviceConnectionType.valueOf(v)
    @TypeConverter fun fromActivityCategory(v: ActivityCategory): String = v.name
    @TypeConverter fun toActivityCategory(v: String): ActivityCategory = ActivityCategory.valueOf(v)
    @TypeConverter fun fromAccessTier(v: AccessTier): String = v.name
    @TypeConverter fun toAccessTier(v: String): AccessTier = runCatching { AccessTier.valueOf(v) }.getOrDefault(AccessTier.UNKNOWN)
}

@Database(
    entities = [
        ProviderEntity::class,
        ApiKeyEntity::class,
        ModelEntity::class,
        RoutingRuleEntity::class,
        CodingAgentEntity::class,
        LocalAiServerEntity::class,
        McpServerEntity::class,
        McpToolEntity::class,
        WorkflowEntity::class,
        WorkflowRunEntity::class,
        DeviceEntity::class,
        RequestMetricEntity::class,
        ActivityLogEntity::class,
        PlaygroundSessionEntity::class,
        PlaygroundMessageEntity::class
    ],
    version = 5,
    exportSchema = true
)
@TypeConverters(EnumConverters::class)
abstract class JhGetWayDatabase : RoomDatabase() {
    abstract fun providerDao(): ProviderDao
    abstract fun apiKeyDao(): ApiKeyDao
    abstract fun modelDao(): ModelDao
    abstract fun routingRuleDao(): RoutingRuleDao
    abstract fun codingAgentDao(): CodingAgentDao
    abstract fun localAiServerDao(): LocalAiServerDao
    abstract fun mcpDao(): McpDao
    abstract fun workflowDao(): WorkflowDao
    abstract fun deviceDao(): DeviceDao
    abstract fun metricAndLogDao(): MetricAndLogDao
    abstract fun playgroundDao(): PlaygroundDao

    companion object {
        @Volatile private var INSTANCE: JhGetWayDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE devices ADD COLUMN allowedModelsCsv TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "UPDATE devices SET permissionsCsv = REPLACE(permissionsCsv, 'MODELS', 'MODELS_READ') " +
                        "WHERE permissionsCsv LIKE '%MODELS%' AND permissionsCsv NOT LIKE '%MODELS_READ%'"
                )
                db.execSQL("ALTER TABLE coding_agents ADD COLUMN tokensResetDateUtc TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE coding_agents ADD COLUMN lastUsageSource TEXT NOT NULL DEFAULT 'UNKNOWN'")
                db.execSQL("ALTER TABLE request_metrics ADD COLUMN usageSource TEXT NOT NULL DEFAULT 'UNKNOWN'")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_api_keys_providerId` ON `api_keys` (`providerId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_models_providerId` ON `models` (`providerId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_models_modelId` ON `models` (`modelId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_coding_agents_enabled` ON `coding_agents` (`enabled`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_mcp_tools_serverId` ON `mcp_tools` (`serverId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_workflow_runs_workflowId` ON `workflow_runs` (`workflowId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_workflow_runs_startedAt` ON `workflow_runs` (`startedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_devices_enabled` ON `devices` (`enabled`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_devices_revoked` ON `devices` (`revoked`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_request_metrics_timestamp` ON `request_metrics` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_request_metrics_providerId` ON `request_metrics` (`providerId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_request_metrics_modelId` ON `request_metrics` (`modelId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_request_metrics_requestId` ON `request_metrics` (`requestId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_logs_timestamp` ON `activity_logs` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_logs_category` ON `activity_logs` (`category`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playground_sessions_updatedAt` ON `playground_sessions` (`updatedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playground_messages_sessionId` ON `playground_messages` (`sessionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playground_messages_timestamp` ON `playground_messages` (`timestamp`)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workflows ADD COLUMN version INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE request_metrics ADD COLUMN actualBilledCostUsd REAL")
                db.execSQL("ALTER TABLE request_metrics ADD COLUMN pricingSource TEXT")
                db.execSQL("ALTER TABLE request_metrics ADD COLUMN pricingUpdatedAt TEXT")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE models ADD COLUMN accessTier TEXT NOT NULL DEFAULT 'UNKNOWN'")
                db.execSQL("ALTER TABLE models ADD COLUMN freeTierVerified INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE models ADD COLUMN freeTierSource TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE models ADD COLUMN freeTierVerifiedAt INTEGER")
                db.execSQL("ALTER TABLE models ADD COLUMN freeTierLimitsNote TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE models ADD COLUMN supportsChat INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE models ADD COLUMN supportsVision INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE models SET accessTier = 'LOCAL' WHERE isLocal = 1 AND accessTier = 'UNKNOWN'")
                db.execSQL("UPDATE models SET supportsChat = CASE WHEN modelType IN ('EMBEDDING', 'IMAGE_GENERATION', 'AUDIO') THEN 0 ELSE 1 END")
                db.execSQL("UPDATE models SET supportsVision = CASE WHEN modelType IN ('VISION', 'MULTIMODAL') OR UPPER(capabilitiesCsv) LIKE '%VISION%' THEN 1 ELSE 0 END")
                db.execSQL("ALTER TABLE playground_messages ADD COLUMN attachmentsMetadataJson TEXT NOT NULL DEFAULT '[]'")
            }
        }

        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

        fun getInstance(context: Context): JhGetWayDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    JhGetWayDatabase::class.java,
                    "jh_getway_db"
                ).addMigrations(*ALL_MIGRATIONS)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
