package com.example.ui.screens

import com.example.data.local.ProviderEntity
import com.example.data.local.ProviderStatus
import com.example.network.ProviderPreset
import com.example.network.ProviderPresets
import java.net.URI
import java.util.Locale

data class ProviderIdentity(
    val groupKey: String,
    val displayName: String,
    val presetId: String?
)

data class ProviderGroupSummary(
    val groupKey: String,
    val displayName: String,
    val presetId: String?,
    val providers: List<ProviderEntity>,
    val aggregatedStatus: ProviderStatus,
    val enabledCount: Int,
    val connectedCount: Int,
    val errorCount: Int,
    val bestLatencyMs: Long?
)

object ProviderDirectoryHelper {

    private val knownPresetsById: Map<String, ProviderPreset> =
        ProviderPresets.all
            .filter { !it.id.equals("CUSTOM", ignoreCase = true) }
            .associateBy { it.id.uppercase(Locale.US) }

    private val knownPresetHosts: Map<String, ProviderPreset> by lazy {
        buildMap {
            knownPresetsById.values.forEach { preset ->
                extractNormalizedHost(preset.baseUrl)?.let { host ->
                    put(host, preset)
                }
            }
            put("huggingface.co", knownPresetsById.getValue("HUGGINGFACE"))
            put("api-inference.huggingface.co", knownPresetsById.getValue("HUGGINGFACE"))
        }
    }

    fun extractNormalizedHost(baseUrl: String): String? {
        return runCatching {
            URI(baseUrl.trim()).host
                ?.trim()
                ?.lowercase(Locale.US)
                ?.removePrefix("www.")
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun resolveProviderIdentity(provider: ProviderEntity): ProviderIdentity {
        val rawPresetId = provider.presetId.trim().uppercase(Locale.US)
        val matchedPreset = knownPresetsById[rawPresetId]
            ?: extractNormalizedHost(provider.baseUrl)?.let { host ->
                knownPresetHosts[host] ?: knownPresetHosts.entries.firstOrNull { (knownHost, _) ->
                    host.endsWith(".$knownHost")
                }?.value
            }

        if (matchedPreset != null) {
            return ProviderIdentity(
                groupKey = "preset:${matchedPreset.id}",
                displayName = matchedPreset.name,
                presetId = matchedPreset.id
            )
        }

        val host = extractNormalizedHost(provider.baseUrl)
        val cleanName = provider.name.trim()
        if (host != null && host != "127.0.0.1" && host != "localhost") {
            return ProviderIdentity(
                groupKey = "custom_host:$host",
                displayName = cleanName.ifBlank { host },
                presetId = null
            )
        }

        val fallbackName = cleanName.ifBlank { "Custom Provider" }
        return ProviderIdentity(
            groupKey = "custom_name:${fallbackName.lowercase(Locale.US)}",
            displayName = fallbackName,
            presetId = null
        )
    }

    fun groupProviders(providers: List<ProviderEntity>): List<ProviderGroupSummary> {
        if (providers.isEmpty()) return emptyList()
        val grouped = linkedMapOf<String, Pair<ProviderIdentity, MutableList<ProviderEntity>>>()
        for (p in providers) {
            val identity = resolveProviderIdentity(p)
            val entry = grouped[identity.groupKey]
            if (entry == null) {
                grouped[identity.groupKey] = identity to mutableListOf(p)
            } else {
                entry.second.add(p)
            }
        }
        return grouped.values.map { (identity, items) ->
            val sortedItems = items.sortedWith(
                compareBy<ProviderEntity> { !it.enabled }
                    .thenBy { it.priority }
                    .thenBy { it.name.lowercase(Locale.US) }
            )
            val displayName = if (identity.presetId != null) {
                identity.displayName
            } else {
                val distinctNames = sortedItems.map { it.name.trim() }.filter { it.isNotBlank() }.distinct()
                when {
                    distinctNames.size == 1 -> distinctNames.first()
                    identity.groupKey.startsWith("custom_host:") -> {
                        val hostLabel = identity.groupKey.removePrefix("custom_host:")
                        distinctNames.minByOrNull { it.length } ?: hostLabel
                    }
                    else -> identity.displayName
                }
            }
            ProviderGroupSummary(
                groupKey = identity.groupKey,
                displayName = displayName,
                presetId = identity.presetId,
                providers = sortedItems,
                aggregatedStatus = computeAggregatedStatus(sortedItems),
                enabledCount = sortedItems.count { it.enabled },
                connectedCount = sortedItems.count { it.enabled && it.status == ProviderStatus.CONNECTED },
                errorCount = sortedItems.count { it.enabled && it.status == ProviderStatus.ERROR },
                bestLatencyMs = sortedItems.mapNotNull { it.lastLatencyMs }.minOrNull()
            )
        }.sortedWith(
            compareByDescending<ProviderGroupSummary> { it.connectedCount > 0 }
                .thenBy { it.displayName.lowercase(Locale.US) }
        )
    }

    fun filterProvidersForGroup(providers: List<ProviderEntity>, groupKey: String): List<ProviderEntity> {
        return providers
            .filter { resolveProviderIdentity(it).groupKey == groupKey }
            .sortedWith(
                compareBy<ProviderEntity> { !it.enabled }
                    .thenBy { it.priority }
                    .thenBy { it.name.lowercase(Locale.US) }
            )
    }

    fun findGroup(providers: List<ProviderEntity>, groupKey: String): ProviderGroupSummary? {
        return groupProviders(providers).firstOrNull { it.groupKey == groupKey }
    }

    fun computeAggregatedStatus(items: List<ProviderEntity>): ProviderStatus {
        if (items.isEmpty()) return ProviderStatus.UNKNOWN
        val enabledItems = items.filter { it.enabled && it.status != ProviderStatus.DISABLED }
        if (enabledItems.isEmpty()) return ProviderStatus.DISABLED
        return when {
            enabledItems.any { it.status == ProviderStatus.CHECKING } -> ProviderStatus.CHECKING
            enabledItems.any { it.status == ProviderStatus.CONNECTED } -> ProviderStatus.CONNECTED
            enabledItems.any { it.status == ProviderStatus.RATE_LIMITED } -> ProviderStatus.RATE_LIMITED
            enabledItems.any { it.status == ProviderStatus.ERROR } -> ProviderStatus.ERROR
            enabledItems.any { it.status == ProviderStatus.DISCONNECTED } -> ProviderStatus.DISCONNECTED
            else -> ProviderStatus.UNKNOWN
        }
    }
}
