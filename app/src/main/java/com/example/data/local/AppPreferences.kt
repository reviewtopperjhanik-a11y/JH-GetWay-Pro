package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.core.i18n.AppLanguage
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "jh_getway_prefs")

enum class GatewayMode { LOCAL, REMOTE, EXISTING }
enum class RoutingMode {
    MANUAL, AUTOMATIC, PRIORITY, ROUND_ROBIN, LOWEST_LATENCY, LOWEST_COST, LOCAL_FIRST, CLOUD_FIRST, AUTO_FREE
}
enum class LocalRoutingPolicy { LOCAL_ONLY, LOCAL_FIRST, CLOUD_FIRST, LOCAL_CLOUD_FALLBACK }
enum class LoggingLevel { MINIMAL, METADATA_ONLY, STANDARD }

data class GatewaySettingsState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val language: AppLanguage = AppLanguage.ENGLISH,
    val onboardingCompleted: Boolean = false,
    val gatewayName: String = "JH GetWay Primary",
    val gatewayMode: GatewayMode = GatewayMode.LOCAL,
    val gatewayHost: String = "127.0.0.1",
    val gatewayPort: Int = 8787,
    val gatewayRemoteUrl: String = "",
    val gatewayAuthRequired: Boolean = true,
    val gatewayTimeoutSec: Int = 60,
    val gatewayMaxRequestKb: Int = 2048,
    val gatewayStreamingEnabled: Boolean = true,
    val gatewayRateLimitPerMin: Int = 60,
    val gatewayLoggingLevel: LoggingLevel = LoggingLevel.METADATA_ONLY,
    val gatewayAllowLanExposure: Boolean = false,
    val gatewayRemoteAuthTokenEnc: String = "",
    val defaultModelId: String = "",
    val defaultProviderId: String = "",
    val defaultRoutingMode: RoutingMode = RoutingMode.AUTOMATIC,
    val fallbackEnabled: Boolean = true,
    val maxRetries: Int = 2,
    val localRoutingPolicy: LocalRoutingPolicy = LocalRoutingPolicy.LOCAL_CLOUD_FALLBACK,
    val allowCloudFallbackForLocal: Boolean = true,
    val localNetworkAccessAllowed: Boolean = false,
    val saveChatHistoryAllowed: Boolean = true,
    val appLockEnabled: Boolean = false,
    val appLockPinHash: String = "",
    val appLockFailedAttempts: Int = 0,
    val appLockLockedUntilMs: Long = 0L,
    val appLockAutoLockOnBackground: Boolean = true,
    val appLockBiometricEnabled: Boolean = false,
    val gatewayServiceDesiredRunning: Boolean = false,
    val analyticsCurrency: String = "USD",
    val logRetentionDays: Int = 30,
    val usageRetentionDays: Int = 90,
    val playgroundRetentionDays: Int = 60,
    val notifyProviderFailure: Boolean = true,
    val notifyGatewayDisconnected: Boolean = true,
    val notifyRateLimit: Boolean = true,
    val notifyWorkflowCompleted: Boolean = true,
    val notifySecurityEvent: Boolean = true,
    val githubUpdateRepo: String = "",
    val autoCheckUpdates: Boolean = true,
    val skippedUpdateTag: String = "",
    val manualModelFallbackEnabled: Boolean = false,
    val autoFreeIncludeLocal: Boolean = false
)

class AppPreferences(private val context: Context) {
    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val LANGUAGE = stringPreferencesKey("language")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val GATEWAY_NAME = stringPreferencesKey("gateway_name")
        val GATEWAY_MODE = stringPreferencesKey("gateway_mode")
        val GATEWAY_HOST = stringPreferencesKey("gateway_host")
        val GATEWAY_PORT = intPreferencesKey("gateway_port")
        val GATEWAY_REMOTE_URL = stringPreferencesKey("gateway_remote_url")
        val GATEWAY_AUTH_REQUIRED = booleanPreferencesKey("gateway_auth_required")
        val GATEWAY_TIMEOUT_SEC = intPreferencesKey("gateway_timeout_sec")
        val GATEWAY_MAX_REQ_KB = intPreferencesKey("gateway_max_req_kb")
        val GATEWAY_STREAMING = booleanPreferencesKey("gateway_streaming")
        val GATEWAY_RATE_LIMIT = intPreferencesKey("gateway_rate_limit")
        val GATEWAY_LOGGING_LEVEL = stringPreferencesKey("gateway_logging_level")
        val GATEWAY_ALLOW_LAN = booleanPreferencesKey("gateway_allow_lan")
        val GATEWAY_REMOTE_TOKEN_ENC = stringPreferencesKey("gateway_remote_token_enc")
        val DEFAULT_MODEL_ID = stringPreferencesKey("default_model_id")
        val DEFAULT_PROVIDER_ID = stringPreferencesKey("default_provider_id")
        val DEFAULT_ROUTING_MODE = stringPreferencesKey("default_routing_mode")
        val FALLBACK_ENABLED = booleanPreferencesKey("fallback_enabled")
        val MAX_RETRIES = intPreferencesKey("max_retries")
        val LOCAL_ROUTING_POLICY = stringPreferencesKey("local_routing_policy")
        val ALLOW_CLOUD_FALLBACK = booleanPreferencesKey("allow_cloud_fallback")
        val LOCAL_NETWORK_ALLOWED = booleanPreferencesKey("local_network_allowed")
        val SAVE_CHAT_HISTORY = booleanPreferencesKey("save_chat_history")
        val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
        val APP_LOCK_PIN_HASH = stringPreferencesKey("app_lock_pin_hash")
        val APP_LOCK_FAILED_ATTEMPTS = intPreferencesKey("app_lock_failed_attempts")
        val APP_LOCK_LOCKED_UNTIL_MS = longPreferencesKey("app_lock_locked_until_ms")
        val APP_LOCK_AUTO_LOCK_BG = booleanPreferencesKey("app_lock_auto_lock_bg")
        val APP_LOCK_BIOMETRIC = booleanPreferencesKey("app_lock_biometric")
        val GATEWAY_SERVICE_DESIRED_RUNNING = booleanPreferencesKey("gateway_service_desired_running")
        val ANALYTICS_CURRENCY = stringPreferencesKey("analytics_currency")
        val LOG_RETENTION_DAYS = intPreferencesKey("log_retention_days")
        val USAGE_RETENTION_DAYS = intPreferencesKey("usage_retention_days")
        val PLAYGROUND_RETENTION_DAYS = intPreferencesKey("playground_retention_days")
        val NOTIFY_PROVIDER_FAILURE = booleanPreferencesKey("notify_provider_failure")
        val NOTIFY_GATEWAY_DISC = booleanPreferencesKey("notify_gateway_disc")
        val NOTIFY_RATE_LIMIT = booleanPreferencesKey("notify_rate_limit")
        val NOTIFY_WORKFLOW_DONE = booleanPreferencesKey("notify_workflow_done")
        val NOTIFY_SECURITY = booleanPreferencesKey("notify_security")
        val GITHUB_UPDATE_REPO = stringPreferencesKey("github_update_repo")
        val AUTO_CHECK_UPDATES = booleanPreferencesKey("auto_check_updates")
        val SKIPPED_UPDATE_TAG = stringPreferencesKey("skipped_update_tag")
        val MANUAL_MODEL_FALLBACK = booleanPreferencesKey("manual_model_fallback_enabled")
        val AUTO_FREE_INCLUDE_LOCAL = booleanPreferencesKey("auto_free_include_local")
    }

    val settingsFlow: Flow<GatewaySettingsState> = context.dataStore.data.map { p ->
        GatewaySettingsState(
            themeMode = runCatching { ThemeMode.valueOf(p[Keys.THEME_MODE] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
            language = runCatching { AppLanguage.valueOf(p[Keys.LANGUAGE] ?: "ENGLISH") }.getOrDefault(AppLanguage.ENGLISH),
            onboardingCompleted = p[Keys.ONBOARDING_COMPLETED] ?: false,
            gatewayName = p[Keys.GATEWAY_NAME] ?: "JH GetWay Primary",
            gatewayMode = runCatching { GatewayMode.valueOf(p[Keys.GATEWAY_MODE] ?: "LOCAL") }.getOrDefault(GatewayMode.LOCAL),
            gatewayHost = p[Keys.GATEWAY_HOST] ?: "127.0.0.1",
            gatewayPort = p[Keys.GATEWAY_PORT] ?: 8787,
            gatewayRemoteUrl = p[Keys.GATEWAY_REMOTE_URL] ?: "",
            gatewayAuthRequired = p[Keys.GATEWAY_AUTH_REQUIRED] ?: true,
            gatewayTimeoutSec = p[Keys.GATEWAY_TIMEOUT_SEC] ?: 60,
            gatewayMaxRequestKb = p[Keys.GATEWAY_MAX_REQ_KB] ?: 2048,
            gatewayStreamingEnabled = p[Keys.GATEWAY_STREAMING] ?: true,
            gatewayRateLimitPerMin = p[Keys.GATEWAY_RATE_LIMIT] ?: 60,
            gatewayLoggingLevel = runCatching { LoggingLevel.valueOf(p[Keys.GATEWAY_LOGGING_LEVEL] ?: "METADATA_ONLY") }.getOrDefault(LoggingLevel.METADATA_ONLY),
            gatewayAllowLanExposure = p[Keys.GATEWAY_ALLOW_LAN] ?: false,
            gatewayRemoteAuthTokenEnc = p[Keys.GATEWAY_REMOTE_TOKEN_ENC] ?: "",
            defaultModelId = p[Keys.DEFAULT_MODEL_ID] ?: "",
            defaultProviderId = p[Keys.DEFAULT_PROVIDER_ID] ?: "",
            defaultRoutingMode = runCatching { RoutingMode.valueOf(p[Keys.DEFAULT_ROUTING_MODE] ?: "AUTOMATIC") }.getOrDefault(RoutingMode.AUTOMATIC),
            fallbackEnabled = p[Keys.FALLBACK_ENABLED] ?: true,
            maxRetries = p[Keys.MAX_RETRIES] ?: 2,
            localRoutingPolicy = runCatching { LocalRoutingPolicy.valueOf(p[Keys.LOCAL_ROUTING_POLICY] ?: "LOCAL_CLOUD_FALLBACK") }.getOrDefault(LocalRoutingPolicy.LOCAL_CLOUD_FALLBACK),
            allowCloudFallbackForLocal = p[Keys.ALLOW_CLOUD_FALLBACK] ?: true,
            localNetworkAccessAllowed = p[Keys.LOCAL_NETWORK_ALLOWED] ?: false,
            saveChatHistoryAllowed = p[Keys.SAVE_CHAT_HISTORY] ?: true,
            appLockEnabled = p[Keys.APP_LOCK_ENABLED] ?: false,
            appLockPinHash = p[Keys.APP_LOCK_PIN_HASH] ?: "",
            appLockFailedAttempts = p[Keys.APP_LOCK_FAILED_ATTEMPTS] ?: 0,
            appLockLockedUntilMs = p[Keys.APP_LOCK_LOCKED_UNTIL_MS] ?: 0L,
            appLockAutoLockOnBackground = p[Keys.APP_LOCK_AUTO_LOCK_BG] ?: true,
            appLockBiometricEnabled = p[Keys.APP_LOCK_BIOMETRIC] ?: false,
            gatewayServiceDesiredRunning = p[Keys.GATEWAY_SERVICE_DESIRED_RUNNING] ?: false,
            analyticsCurrency = p[Keys.ANALYTICS_CURRENCY] ?: "USD",
            logRetentionDays = p[Keys.LOG_RETENTION_DAYS] ?: 30,
            usageRetentionDays = p[Keys.USAGE_RETENTION_DAYS] ?: 90,
            playgroundRetentionDays = p[Keys.PLAYGROUND_RETENTION_DAYS] ?: 60,
            notifyProviderFailure = p[Keys.NOTIFY_PROVIDER_FAILURE] ?: true,
            notifyGatewayDisconnected = p[Keys.NOTIFY_GATEWAY_DISC] ?: true,
            notifyRateLimit = p[Keys.NOTIFY_RATE_LIMIT] ?: true,
            notifyWorkflowCompleted = p[Keys.NOTIFY_WORKFLOW_DONE] ?: true,
            notifySecurityEvent = p[Keys.NOTIFY_SECURITY] ?: true,
            githubUpdateRepo = p[Keys.GITHUB_UPDATE_REPO] ?: "",
            autoCheckUpdates = p[Keys.AUTO_CHECK_UPDATES] ?: true,
            skippedUpdateTag = p[Keys.SKIPPED_UPDATE_TAG] ?: "",
            manualModelFallbackEnabled = p[Keys.MANUAL_MODEL_FALLBACK] ?: false,
            autoFreeIncludeLocal = p[Keys.AUTO_FREE_INCLUDE_LOCAL] ?: false
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    suspend fun setLanguage(lang: AppLanguage) = context.dataStore.edit { it[Keys.LANGUAGE] = lang.name }
    suspend fun setOnboardingCompleted(done: Boolean) = context.dataStore.edit { it[Keys.ONBOARDING_COMPLETED] = done }
    suspend fun setGatewayMode(mode: GatewayMode) = context.dataStore.edit { it[Keys.GATEWAY_MODE] = mode.name }

    suspend fun updateGatewaySettings(
        name: String,
        mode: GatewayMode,
        host: String,
        port: Int,
        remoteUrl: String,
        authRequired: Boolean,
        timeoutSec: Int,
        maxRequestKb: Int,
        streaming: Boolean,
        rateLimitPerMin: Int,
        loggingLevel: LoggingLevel,
        allowLanExposure: Boolean = false,
        remoteAuthTokenEnc: String? = null
    ) = context.dataStore.edit {
        it[Keys.GATEWAY_NAME] = name
        it[Keys.GATEWAY_MODE] = mode.name
        it[Keys.GATEWAY_HOST] = host
        it[Keys.GATEWAY_PORT] = port
        it[Keys.GATEWAY_REMOTE_URL] = remoteUrl
        it[Keys.GATEWAY_AUTH_REQUIRED] = authRequired
        it[Keys.GATEWAY_TIMEOUT_SEC] = timeoutSec
        it[Keys.GATEWAY_MAX_REQ_KB] = maxRequestKb
        it[Keys.GATEWAY_STREAMING] = streaming
        it[Keys.GATEWAY_RATE_LIMIT] = rateLimitPerMin
        it[Keys.GATEWAY_LOGGING_LEVEL] = loggingLevel.name
        it[Keys.GATEWAY_ALLOW_LAN] = allowLanExposure
        if (remoteAuthTokenEnc != null) {
            it[Keys.GATEWAY_REMOTE_TOKEN_ENC] = remoteAuthTokenEnc
        }
    }

    suspend fun updateRoutingSettings(mode: RoutingMode, fallbackEnabled: Boolean, maxRetries: Int) =
        context.dataStore.edit {
            it[Keys.DEFAULT_ROUTING_MODE] = mode.name
            it[Keys.FALLBACK_ENABLED] = fallbackEnabled
            it[Keys.MAX_RETRIES] = maxRetries
        }

    suspend fun updateLocalAiSettings(
        policy: LocalRoutingPolicy,
        allowCloudFallback: Boolean,
        localNetworkAllowed: Boolean
    ) = context.dataStore.edit {
        it[Keys.LOCAL_ROUTING_POLICY] = policy.name
        it[Keys.ALLOW_CLOUD_FALLBACK] = allowCloudFallback
        it[Keys.LOCAL_NETWORK_ALLOWED] = localNetworkAllowed
    }

    suspend fun setDefaultModelAndProvider(modelId: String, providerId: String) = context.dataStore.edit {
        it[Keys.DEFAULT_MODEL_ID] = modelId
        it[Keys.DEFAULT_PROVIDER_ID] = providerId
    }

    suspend fun setSaveChatHistory(allowed: Boolean) = context.dataStore.edit { it[Keys.SAVE_CHAT_HISTORY] = allowed }
    suspend fun setAppLock(enabled: Boolean, pinHash: String) = context.dataStore.edit {
        it[Keys.APP_LOCK_ENABLED] = enabled
        it[Keys.APP_LOCK_PIN_HASH] = pinHash
        it[Keys.APP_LOCK_FAILED_ATTEMPTS] = 0
        it[Keys.APP_LOCK_LOCKED_UNTIL_MS] = 0L
        if (!enabled) {
            it[Keys.APP_LOCK_BIOMETRIC] = false
        }
    }
    suspend fun setAppLockPinHashOnly(pinHash: String) = context.dataStore.edit {
        it[Keys.APP_LOCK_PIN_HASH] = pinHash
    }
    suspend fun setAppLockLockoutState(failedAttempts: Int, lockedUntilMs: Long) = context.dataStore.edit {
        it[Keys.APP_LOCK_FAILED_ATTEMPTS] = failedAttempts
        it[Keys.APP_LOCK_LOCKED_UNTIL_MS] = lockedUntilMs
    }
    suspend fun setAppLockAutoLockOnBackground(enabled: Boolean) = context.dataStore.edit {
        it[Keys.APP_LOCK_AUTO_LOCK_BG] = enabled
    }
    suspend fun setAppLockBiometricEnabled(enabled: Boolean) = context.dataStore.edit {
        it[Keys.APP_LOCK_BIOMETRIC] = enabled
    }
    suspend fun setGatewayServiceDesiredRunning(running: Boolean) = context.dataStore.edit {
        it[Keys.GATEWAY_SERVICE_DESIRED_RUNNING] = running
    }
    suspend fun setCurrency(currency: String) = context.dataStore.edit { it[Keys.ANALYTICS_CURRENCY] = currency }
    suspend fun setLogRetentionDays(days: Int) = context.dataStore.edit { it[Keys.LOG_RETENTION_DAYS] = days }
    suspend fun setUsageRetentionDays(days: Int) = context.dataStore.edit { it[Keys.USAGE_RETENTION_DAYS] = days }
    suspend fun setPlaygroundRetentionDays(days: Int) = context.dataStore.edit { it[Keys.PLAYGROUND_RETENTION_DAYS] = days }

    suspend fun updateNotificationPrefs(
        providerFailure: Boolean,
        gatewayDisc: Boolean,
        rateLimit: Boolean,
        workflowDone: Boolean,
        security: Boolean
    ) = context.dataStore.edit {
        it[Keys.NOTIFY_PROVIDER_FAILURE] = providerFailure
        it[Keys.NOTIFY_GATEWAY_DISC] = gatewayDisc
        it[Keys.NOTIFY_RATE_LIMIT] = rateLimit
        it[Keys.NOTIFY_WORKFLOW_DONE] = workflowDone
        it[Keys.NOTIFY_SECURITY] = security
    }

    suspend fun setGithubUpdateRepo(repo: String) = context.dataStore.edit {
        it[Keys.GITHUB_UPDATE_REPO] = repo.trim()
    }
    suspend fun setAutoCheckUpdates(enabled: Boolean) = context.dataStore.edit {
        it[Keys.AUTO_CHECK_UPDATES] = enabled
    }
    suspend fun setSkippedUpdateTag(tag: String) = context.dataStore.edit {
        it[Keys.SKIPPED_UPDATE_TAG] = tag.trim()
    }
    suspend fun setManualModelFallbackEnabled(enabled: Boolean) = context.dataStore.edit {
        it[Keys.MANUAL_MODEL_FALLBACK] = enabled
    }
    suspend fun setAutoFreeIncludeLocal(enabled: Boolean) = context.dataStore.edit {
        it[Keys.AUTO_FREE_INCLUDE_LOCAL] = enabled
    }

    suspend fun restorePortablePreferences(
        snapshot: GatewaySettingsState,
        remoteAuthTokenEnc: String?
    ) = context.dataStore.edit { p ->
        p[Keys.THEME_MODE] = snapshot.themeMode.name
        p[Keys.LANGUAGE] = snapshot.language.name
        p[Keys.ONBOARDING_COMPLETED] = snapshot.onboardingCompleted
        p[Keys.GATEWAY_NAME] = snapshot.gatewayName
        p[Keys.GATEWAY_MODE] = snapshot.gatewayMode.name
        p[Keys.GATEWAY_HOST] = snapshot.gatewayHost
        p[Keys.GATEWAY_PORT] = snapshot.gatewayPort
        p[Keys.GATEWAY_REMOTE_URL] = snapshot.gatewayRemoteUrl
        p[Keys.GATEWAY_AUTH_REQUIRED] = snapshot.gatewayAuthRequired
        p[Keys.GATEWAY_TIMEOUT_SEC] = snapshot.gatewayTimeoutSec
        p[Keys.GATEWAY_MAX_REQ_KB] = snapshot.gatewayMaxRequestKb
        p[Keys.GATEWAY_STREAMING] = snapshot.gatewayStreamingEnabled
        p[Keys.GATEWAY_RATE_LIMIT] = snapshot.gatewayRateLimitPerMin
        p[Keys.GATEWAY_LOGGING_LEVEL] = snapshot.gatewayLoggingLevel.name
        p[Keys.GATEWAY_ALLOW_LAN] = snapshot.gatewayAllowLanExposure
        if (remoteAuthTokenEnc != null) {
            p[Keys.GATEWAY_REMOTE_TOKEN_ENC] = remoteAuthTokenEnc
        }
        p[Keys.DEFAULT_MODEL_ID] = snapshot.defaultModelId
        p[Keys.DEFAULT_PROVIDER_ID] = snapshot.defaultProviderId
        p[Keys.DEFAULT_ROUTING_MODE] = snapshot.defaultRoutingMode.name
        p[Keys.FALLBACK_ENABLED] = snapshot.fallbackEnabled
        p[Keys.MAX_RETRIES] = snapshot.maxRetries
        p[Keys.LOCAL_ROUTING_POLICY] = snapshot.localRoutingPolicy.name
        p[Keys.ALLOW_CLOUD_FALLBACK] = snapshot.allowCloudFallbackForLocal
        p[Keys.LOCAL_NETWORK_ALLOWED] = snapshot.localNetworkAccessAllowed
        p[Keys.SAVE_CHAT_HISTORY] = snapshot.saveChatHistoryAllowed
        p[Keys.ANALYTICS_CURRENCY] = snapshot.analyticsCurrency
        p[Keys.LOG_RETENTION_DAYS] = snapshot.logRetentionDays
        p[Keys.USAGE_RETENTION_DAYS] = snapshot.usageRetentionDays
        p[Keys.PLAYGROUND_RETENTION_DAYS] = snapshot.playgroundRetentionDays
        p[Keys.NOTIFY_PROVIDER_FAILURE] = snapshot.notifyProviderFailure
        p[Keys.NOTIFY_GATEWAY_DISC] = snapshot.notifyGatewayDisconnected
        p[Keys.NOTIFY_RATE_LIMIT] = snapshot.notifyRateLimit
        p[Keys.NOTIFY_WORKFLOW_DONE] = snapshot.notifyWorkflowCompleted
        p[Keys.NOTIFY_SECURITY] = snapshot.notifySecurityEvent
        p[Keys.GITHUB_UPDATE_REPO] = snapshot.githubUpdateRepo
        p[Keys.AUTO_CHECK_UPDATES] = snapshot.autoCheckUpdates
        p[Keys.MANUAL_MODEL_FALLBACK] = snapshot.manualModelFallbackEnabled
        p[Keys.AUTO_FREE_INCLUDE_LOCAL] = snapshot.autoFreeIncludeLocal
    }

    suspend fun resetSafeAppSettings() {
        context.dataStore.edit { p ->
            // Explicitly reset ONLY non-destructive UI, notification, updater-UI, and default routing toggle preferences.
            // Never call p.clear(), and never reset onboarding, language, gateway connection/auth/remote config,
            // app lock, retention policies, chat privacy, default model/provider, or update repository.
            p[Keys.THEME_MODE] = ThemeMode.SYSTEM.name
            p[Keys.ANALYTICS_CURRENCY] = "USD"
            p[Keys.NOTIFY_PROVIDER_FAILURE] = true
            p[Keys.NOTIFY_GATEWAY_DISC] = true
            p[Keys.NOTIFY_RATE_LIMIT] = true
            p[Keys.NOTIFY_WORKFLOW_DONE] = true
            p[Keys.NOTIFY_SECURITY] = true
            p[Keys.AUTO_CHECK_UPDATES] = true
            p[Keys.SKIPPED_UPDATE_TAG] = ""
            p[Keys.GATEWAY_LOGGING_LEVEL] = LoggingLevel.METADATA_ONLY.name
            p[Keys.DEFAULT_ROUTING_MODE] = RoutingMode.AUTOMATIC.name
            p[Keys.FALLBACK_ENABLED] = true
            p[Keys.MAX_RETRIES] = 2
            p[Keys.MANUAL_MODEL_FALLBACK] = false
            p[Keys.AUTO_FREE_INCLUDE_LOCAL] = false
        }
    }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }

    companion object {
        val SAFE_RESET_KEY_NAMES: Set<String> = setOf(
            "theme_mode",
            "analytics_currency",
            "notify_provider_failure",
            "notify_gateway_disc",
            "notify_rate_limit",
            "notify_workflow_done",
            "notify_security",
            "auto_check_updates",
            "skipped_update_tag",
            "gateway_logging_level",
            "default_routing_mode",
            "fallback_enabled",
            "max_retries",
            "manual_model_fallback_enabled",
            "auto_free_include_local"
        )

        val PROTECTED_KEY_NAMES: Set<String> = setOf(
            "language",
            "onboarding_completed",
            "gateway_name",
            "gateway_mode",
            "gateway_host",
            "gateway_port",
            "gateway_remote_url",
            "gateway_auth_required",
            "gateway_timeout_sec",
            "gateway_max_req_kb",
            "gateway_streaming",
            "gateway_rate_limit",
            "gateway_allow_lan",
            "gateway_remote_token_enc",
            "default_model_id",
            "default_provider_id",
            "local_routing_policy",
            "allow_cloud_fallback",
            "local_network_allowed",
            "save_chat_history",
            "app_lock_enabled",
            "app_lock_pin_hash",
            "app_lock_failed_attempts",
            "app_lock_locked_until_ms",
            "app_lock_auto_lock_bg",
            "app_lock_biometric",
            "gateway_service_desired_running",
            "log_retention_days",
            "usage_retention_days",
            "playground_retention_days",
            "github_update_repo"
        )
    }
}


