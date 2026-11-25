package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.core.i18n.LocalStrings
import com.example.data.local.GatewayMode
import com.example.data.local.ProviderStatus
import com.example.gateway.GatewayRunStatus
import com.example.ui.GatewayViewModel
import com.example.ui.components.*
import com.example.ui.navigation.Routes
import com.example.ui.theme.MonoTextStyle
import com.example.ui.theme.StatusConnected
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusRateLimit
import java.util.Calendar
import java.util.Locale

@Composable
fun DashboardScreen(
    viewModel: GatewayViewModel,
    onNavigate: (String) -> Unit
) {
    val s = LocalStrings.current
    val settings by viewModel.settings.collectAsState()
    val runtime by viewModel.gatewayRuntime.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val modelCounts by viewModel.modelSummaryCounts.collectAsState()
    val devices by viewModel.devices.collectAsState()
    val metrics by viewModel.metrics.collectAsState()
    val logs by viewModel.activityLogs.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.refreshExpiredCooldowns()
    }

    val startOfDay = remember {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    val metricStats = remember(metrics, startOfDay) {
        var today = 0
        var totalIn = 0L
        var totalOut = 0L
        var knownCostSum = 0.0
        var hasKnownCost = false
        var billedSum = 0.0
        var hasBilled = false
        var latencySum = 0L
        var successCount = 0
        var fallbacks = 0
        for (m in metrics) {
            if (m.timestamp >= startOfDay) today++
            totalIn += m.inputTokens.toLong()
            totalOut += m.outputTokens.toLong()
            m.estimatedCostUsd?.let {
                knownCostSum += it
                hasKnownCost = true
            }
            m.actualBilledCostUsd?.let {
                billedSum += it
                hasBilled = true
            }
            latencySum += m.latencyMs
            if (m.success) successCount++
            if (m.fallbackAttempts > 0) fallbacks++
        }
        val size = metrics.size
        DashboardMetricSummary(
            todayCount = today,
            totalReq = size,
            totalIn = totalIn,
            totalOut = totalOut,
            displayCost = if (hasBilled) billedSum else if (hasKnownCost) knownCostSum else null,
            hasActualBilled = hasBilled,
            avgLatency = if (size == 0) null else latencySum / size,
            successRate = if (size == 0) null else (successCount * 100.0 / size),
            fallbackCount = fallbacks
        )
    }

    val providerCounts = remember(providers) {
        var active = 0
        var failed = 0
        var rateLimited = 0
        for (p in providers) {
            when {
                p.enabled && p.status == ProviderStatus.CONNECTED -> active++
                p.status == ProviderStatus.ERROR || p.status == ProviderStatus.DISCONNECTED -> failed++
                p.status == ProviderStatus.RATE_LIMITED -> rateLimited++
            }
        }
        Triple(active, failed, rateLimited)
    }
    val activeProviders = providerCounts.first
    val failedProviders = providerCounts.second
    val rateLimitedProviders = providerCounts.third
    val connectedDeviceCount = remember(devices) { devices.count { !it.revoked && it.enabled } }

    val (gwLabel, gwTone) = gatewayStatusLabelAndTone(runtime.status)
    val context = LocalContext.current
    var pendingStartAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val notifLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        pendingStartAction?.invoke()
        pendingStartAction = null
    }

    fun startWithOptionalNotifPermission(action: () -> Unit) {
        if (settings.gatewayMode == GatewayMode.LOCAL &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingStartAction = action
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("dashboard_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Hero Gateway Status Card
        item {
            Surface(
                onClick = { onNavigate(Routes.GATEWAY) },
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth().testTag("dashboard_gateway_card")
            ) {
                Column(
                    modifier = Modifier
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                                    MaterialTheme.colorScheme.surface
                                )
                            )
                        )
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(settings.gatewayName, style = MaterialTheme.typography.titleLarge)
                            Text(
                                text = runtime.boundAddress,
                                style = MonoTextStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            runtime.lastHealthCheckAt?.let { checkedAt ->
                                Text(
                                    text = "${s.lastCheckedLabel}: ${formatTimestamp(checkedAt)}" +
                                        (runtime.lastHealthHttpCode?.let { " (HTTP $it)" } ?: ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        StatusPill(text = gwLabel, tone = gwTone)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        MetricMiniTile(
                            label = s.connectedDevices,
                            value = connectedDeviceCount.toString(),
                            modifier = Modifier.weight(1f)
                        )
                        MetricMiniTile(
                            label = s.activeRequests,
                            value = runtime.activeRequests.toString(),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    val isBusyOrActive = runtime.isActive ||
                        runtime.status == GatewayRunStatus.STARTING ||
                        runtime.status == GatewayRunStatus.REMOTE_CONNECTING
                    Button(
                        onClick = {
                            if (isBusyOrActive) {
                                viewModel.toggleGatewayServer()
                            } else {
                                startWithOptionalNotifPermission { viewModel.toggleGatewayServer() }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("dashboard_gateway_toggle"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isBusyOrActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = if (isBusyOrActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (isBusyOrActive) s.stopGateway else s.startGateway)
                    }
                }
            }
        }

        // Provider Overview Card
        item {
            GatewayCard(onClick = { onNavigate(Routes.PROVIDERS) }, modifier = Modifier.testTag("dashboard_provider_card")) {
                SectionHeader(title = s.providerOverview) {
                    Icon(Icons.Default.ChevronRight, contentDescription = s.providersTitle)
                }
                Spacer(Modifier.height(8.dp))
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val cols = if (maxWidth < 360.dp) 2 else 4
                    val tiles = listOf(
                        Triple(s.totalProviders, providers.size.toString(), MaterialTheme.colorScheme.primary),
                        Triple(s.activeProviders, activeProviders.toString(), StatusConnected),
                        Triple(s.failedProviders, failedProviders.toString(), StatusError),
                        Triple(s.rateLimitedProviders, rateLimitedProviders.toString(), StatusRateLimit)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        tiles.chunked(cols).forEach { rowTiles ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                rowTiles.forEach { (lbl, value, accent) ->
                                    MetricMiniTile(lbl, value, accent = accent, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Model Overview Card
        item {
            GatewayCard(onClick = { onNavigate(Routes.MODELS) }, modifier = Modifier.testTag("dashboard_model_card")) {
                SectionHeader(title = s.modelOverview) {
                    Icon(Icons.Default.ChevronRight, contentDescription = s.modelsTitle)
                }
                Spacer(Modifier.height(8.dp))
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val cols = if (maxWidth < 360.dp) 2 else 4
                    val tiles = listOf(
                        Triple(s.totalModels, modelCounts.totalCount.toString(), MaterialTheme.colorScheme.primary),
                        Triple(s.availableModels, modelCounts.enabledCount.toString(), StatusConnected),
                        Triple(s.localModels, modelCounts.localCount.toString(), MaterialTheme.colorScheme.primary),
                        Triple(s.cloudModels, modelCounts.cloudCount.toString(), MaterialTheme.colorScheme.primary)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        tiles.chunked(cols).forEach { rowTiles ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                rowTiles.forEach { (lbl, value, accent) ->
                                    MetricMiniTile(lbl, value, accent = accent, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Usage & Performance Overview
        item {
            val displayCost = metricStats.displayCost
            val displayCostLabel = if (metricStats.hasActualBilled) s.actualBilledCostLabel else s.estimatedCost
            GatewayCard(onClick = { onNavigate(Routes.ANALYTICS) }, modifier = Modifier.testTag("dashboard_usage_card")) {
                SectionHeader(title = s.usageOverview) {
                    Icon(Icons.Default.ChevronRight, contentDescription = s.analyticsTitle)
                }
                Spacer(Modifier.height(8.dp))
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val cols = if (maxWidth < 360.dp) 2 else 4
                    val row1 = listOf(
                        Triple(s.todayRequests, metricStats.todayCount.toString(), MaterialTheme.colorScheme.primary),
                        Triple(s.totalRequests, metricStats.totalReq.toString(), MaterialTheme.colorScheme.primary),
                        Triple(s.inputTokens, formatNumberCompact(metricStats.totalIn), MaterialTheme.colorScheme.primary),
                        Triple(s.outputTokens, formatNumberCompact(metricStats.totalOut), MaterialTheme.colorScheme.primary)
                    )
                    val row2 = listOf(
                        Triple(displayCostLabel, formatCurrency(displayCost, settings.analyticsCurrency, s.unknown), MaterialTheme.colorScheme.primary),
                        Triple(s.avgLatency, metricStats.avgLatency?.let { "${it}ms" } ?: "—", MaterialTheme.colorScheme.primary),
                        Triple(s.successRate, metricStats.successRate?.let { String.format(Locale.US, "%.0f%%", it) } ?: "—", StatusConnected),
                        Triple(s.fallbackCount, metricStats.fallbackCount.toString(), MaterialTheme.colorScheme.primary)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        row1.chunked(cols).forEach { chunk ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                chunk.forEach { (lbl, value, accent) ->
                                    MetricMiniTile(lbl, value, accent = accent, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                        row2.chunked(cols).forEach { chunk ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                chunk.forEach { (lbl, value, accent) ->
                                    MetricMiniTile(lbl, value, accent = accent, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Infrastructure Quick Access Grid
        item {
            SectionHeader(title = s.quickAccess)
            val tiles = listOf(
                Triple(s.navSmartRouter, Icons.AutoMirrored.Filled.AltRoute, Routes.SMART_ROUTER),
                Triple(s.navCodingAgents, Icons.Default.Code, Routes.CODING_AGENTS),
                Triple(s.navLocalAi, Icons.Default.Computer, Routes.LOCAL_AI),
                Triple(s.navMcpServers, Icons.Default.Extension, Routes.MCP_SERVERS),
                Triple(s.navWorkflows, Icons.Default.AccountTree, Routes.WORKFLOWS),
                Triple(s.navVault, Icons.Default.VpnKey, Routes.VAULT),
                Triple(s.navDevices, Icons.Default.Devices, Routes.DEVICES),
                Triple(s.navSearchPools, Icons.Default.Search, Routes.SEARCH_POOLS),
                Triple(s.navActivityLogs, Icons.Default.History, Routes.ACTIVITY_LOGS)
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tiles.chunked(2).forEach { rowTiles ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        rowTiles.forEach { (title, icon, route) ->
                            QuickModuleButton(
                                title = title,
                                icon = icon,
                                onClick = { onNavigate(route) },
                                modifier = Modifier.weight(1f).testTag("quick_$route")
                            )
                        }
                    }
                }
            }
        }

        // Recent Activity
        item {
            SectionHeader(title = s.recentActivity) {
                TextButton(onClick = { onNavigate(Routes.ACTIVITY_LOGS) }) {
                    Text(s.details)
                }
            }
        }

        if (logs.isEmpty()) {
            item {
                EmptyStatePanel(
                    icon = Icons.Default.History,
                    title = s.recentActivity,
                    message = s.noActivityYet,
                    actionLabel = s.addProvider,
                    onAction = { onNavigate(Routes.PROVIDERS) }
                )
            }
        } else {
            items(logs.take(5), key = { it.id }) { log ->
                GatewayCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(log.relatedEntity, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        StatusPill(
                            text = log.status,
                            tone = if (log.status.contains("ERROR") || log.status.contains("FAIL")) StatusTone.ERROR else StatusTone.INFO
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(log.safeMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatTimestamp(log.timestamp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun QuickModuleButton(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 56.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(icon, contentDescription = title, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private data class DashboardMetricSummary(
    val todayCount: Int,
    val totalReq: Int,
    val totalIn: Long,
    val totalOut: Long,
    val displayCost: Double?,
    val hasActualBilled: Boolean,
    val avgLatency: Long?,
    val successRate: Double?,
    val fallbackCount: Int
)
