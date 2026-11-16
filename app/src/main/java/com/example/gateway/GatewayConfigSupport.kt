package com.example.gateway

import com.example.core.security.NetworkAddressClassifier
import com.example.data.local.GatewayMode
import com.example.data.local.LoggingLevel

data class GatewayConfigDraft(
    val name: String,
    val mode: GatewayMode,
    val host: String,
    val port: Int,
    val remoteUrl: String,
    val authRequired: Boolean,
    val timeoutSec: Int,
    val maxRequestKb: Int,
    val streamingEnabled: Boolean,
    val rateLimitPerMin: Int,
    val loggingLevel: LoggingLevel,
    val allowLanExposure: Boolean,
    val allowExistingLocalHttp: Boolean,
    val remoteAuthTokenInput: String,
    val clearSavedRemoteToken: Boolean
)

data class GatewayDraftValidationResult(
    val valid: Boolean,
    val normalizedHost: String = "127.0.0.1",
    val normalizedPort: Int = 8787,
    val normalizedRemoteUrl: String = "",
    val errorMessage: String? = null
)

data class GatewayConnectionTestResult(
    val isTesting: Boolean = false,
    val success: Boolean? = null,
    val message: String = "",
    val runtimeStatus: GatewayRunStatus? = null,
    val httpCode: Int? = null,
    val testedAtMs: Long? = null
)

object GatewayConfigValidator {

    fun validate(draft: GatewayConfigDraft): GatewayDraftValidationResult {
        val cleanName = draft.name.trim()
        if (cleanName.isBlank()) {
            return GatewayDraftValidationResult(
                valid = false,
                errorMessage = "Gateway name is required."
            )
        }
        if (draft.timeoutSec !in 5..300) {
            return GatewayDraftValidationResult(
                valid = false,
                errorMessage = "Request timeout must be between 5 and 300 seconds."
            )
        }
        if (draft.maxRequestKb !in 64..32768) {
            return GatewayDraftValidationResult(
                valid = false,
                errorMessage = "Max request size must be between 64 KB and 32768 KB."
            )
        }
        if (draft.rateLimitPerMin !in 1..10000) {
            return GatewayDraftValidationResult(
                valid = false,
                errorMessage = "Rate limit must be between 1 and 10000 requests/min."
            )
        }

        return when (draft.mode) {
            GatewayMode.LOCAL -> {
                if (draft.port !in 1024..65535) {
                    return GatewayDraftValidationResult(
                        valid = false,
                        errorMessage = "Local Gateway port must be between 1024 and 65535."
                    )
                }
                val bindCheck = NetworkAddressClassifier.validateLocalGatewayBindConfig(
                    hostInput = draft.host,
                    port = draft.port,
                    allowLanExposure = draft.allowLanExposure
                )
                if (!bindCheck.allowed) {
                    GatewayDraftValidationResult(
                        valid = false,
                        normalizedHost = bindCheck.normalizedHost,
                        normalizedPort = bindCheck.normalizedPort,
                        errorMessage = bindCheck.errorMessage ?: "Invalid Local Gateway host or port."
                    )
                } else {
                    GatewayDraftValidationResult(
                        valid = true,
                        normalizedHost = bindCheck.normalizedHost,
                        normalizedPort = bindCheck.normalizedPort,
                        normalizedRemoteUrl = draft.remoteUrl.trim().trimEnd('/')
                    )
                }
            }

            GatewayMode.REMOTE -> {
                val urlCheck = NetworkAddressClassifier.validateRemoteGatewayUrl(
                    urlString = draft.remoteUrl,
                    allowExistingPrivateHttp = false
                )
                if (!urlCheck.valid) {
                    GatewayDraftValidationResult(
                        valid = false,
                        normalizedHost = draft.host.trim().ifBlank { "127.0.0.1" },
                        normalizedPort = draft.port.coerceIn(1024, 65535),
                        errorMessage = urlCheck.errorMessage ?: "Invalid Remote Gateway HTTPS URL."
                    )
                } else {
                    GatewayDraftValidationResult(
                        valid = true,
                        normalizedHost = draft.host.trim().ifBlank { "127.0.0.1" },
                        normalizedPort = draft.port.coerceIn(1024, 65535),
                        normalizedRemoteUrl = urlCheck.normalizedUrl
                    )
                }
            }

            GatewayMode.EXISTING -> {
                val urlCheck = NetworkAddressClassifier.validateRemoteGatewayUrl(
                    urlString = draft.remoteUrl,
                    allowExistingPrivateHttp = draft.allowExistingLocalHttp
                )
                if (!urlCheck.valid) {
                    GatewayDraftValidationResult(
                        valid = false,
                        normalizedHost = draft.host.trim().ifBlank { "127.0.0.1" },
                        normalizedPort = draft.port.coerceIn(1024, 65535),
                        errorMessage = urlCheck.errorMessage ?: "Invalid Existing Gateway URL."
                    )
                } else {
                    GatewayDraftValidationResult(
                        valid = true,
                        normalizedHost = draft.host.trim().ifBlank { "127.0.0.1" },
                        normalizedPort = draft.port.coerceIn(1024, 65535),
                        normalizedRemoteUrl = urlCheck.normalizedUrl
                    )
                }
            }
        }
    }
}
