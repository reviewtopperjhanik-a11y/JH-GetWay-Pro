@file:OptIn(ExperimentalLayoutApi::class)

package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.core.i18n.LocalStrings
import com.example.data.local.*
import com.example.gateway.GatewayRunStatus
import com.example.gateway.OpenCodeSelectorIds
import com.example.ui.GatewayViewModel
import com.example.ui.components.*
import com.example.ui.theme.MonoTextStyle

@Composable
fun GatewayScreen(
    viewModel: GatewayViewModel,
    onOpenGatewayConfig: () -> Unit = {}
) {
    val s = LocalStrings.current
    val settings by viewModel.settings.collectAsState()
    val runtime by viewModel.gatewayRuntime.collectAsState()

    var selectedEndpoint by remember { mutableStateOf("/v1/models") }
    var testModel by remember { mutableStateOf("jh-general") }
    var testPrompt by remember { mutableStateOf("Ping JH GetWay") }
    var endpointOutput by remember { mutableStateOf<String?>(null) }

    val (statusLabel, statusTone) = gatewayStatusLabelAndTone(runtime.status)
    val isBusyOrActive = runtime.isActive ||
        runtime.status == GatewayRunStatus.STARTING ||
        runtime.status == GatewayRunStatus.REMOTE_CONNECTING
    val context = LocalContext.current
    var pendingLocalAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val notifLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        pendingLocalAction?.invoke()
        pendingLocalAction = null
    }

    fun runWithOptionalNotifPermission(action: () -> Unit) {
        if (settings.gatewayMode == GatewayMode.LOCAL &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingLocalAction = action
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("gateway_screen_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(s.gatewayTitle, style = MaterialTheme.typography.headlineSmall)
        }

        item {
            GatewayCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(s.gatewayStatus, style = MaterialTheme.typography.titleMedium)
                        Text(runtime.boundAddress, style = MonoTextStyle, color = MaterialTheme.colorScheme.primary)
                        runtime.lastHealthCheckAt?.let { checkedAt ->
                            Text(
                                text = "${s.lastCheckedLabel}: ${formatTimestamp(checkedAt)}" +
                                    (runtime.lastHealthHttpCode?.let { " (HTTP $it)" } ?: ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    StatusPill(text = statusLabel, tone = statusTone)
                }
                if (settings.gatewayMode != GatewayMode.LOCAL) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = s.remoteManagementClientNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (runtime.lanWarningActive) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = s.allowLanExposureWarning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                runtime.lastError?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = {
                            if (isBusyOrActive) {
                                viewModel.toggleGatewayServer()
                            } else {
                                runWithOptionalNotifPermission { viewModel.toggleGatewayServer() }
                            }
                        },
                        modifier = Modifier.testTag("gateway_start_stop_btn")
                    ) {
                        Text(if (isBusyOrActive) s.stopGateway else s.startGateway)
                    }
                    OutlinedButton(
                        onClick = { runWithOptionalNotifPermission { viewModel.restartGatewayServer() } },
                        modifier = Modifier.testTag("gateway_restart_btn")
                    ) {
                        Text(s.restartGateway)
                    }
                    if (settings.gatewayMode != GatewayMode.LOCAL) {
                        OutlinedButton(
                            onClick = { viewModel.refreshRemoteGatewayHealth() },
                            modifier = Modifier.testTag("gateway_check_remote_health_btn")
                        ) {
                            Text(s.checkRemoteHealth)
                        }
                    }
                }
                if (settings.gatewayMode == GatewayMode.LOCAL) {
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(s.batteryRestrictionDisclosureTitle, style = MaterialTheme.typography.labelLarge)
                            Text(s.batteryRestrictionDisclosureBody, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // Unified Endpoints & Live Tester
        item {
            GatewayCard {
                Text(s.unifiedEndpoints, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                val endpoints = listOf(
                    "/health" to true,
                    "/v1/models" to true,
                    "/v1/chat/completions" to true,
                    "/v1/responses" to true,
                    "/v1/messages" to true,
                    "/v1/embeddings" to false,
                    "/v1/images/generations" to false,
                    "/v1/audio/transcriptions" to false,
                    "/v1/audio/speech" to false
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    endpoints.forEach { (ep, supported) ->
                        val badge = if (supported) s.supportedBadge else s.notSupportedBadge
                        FilterChip(
                            selected = selectedEndpoint == ep,
                            onClick = { selectedEndpoint = ep },
                            label = {
                                Text(
                                    text = "$ep ($badge)",
                                    style = MonoTextStyle
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (supported) Icons.Default.CheckCircle else Icons.Default.Block,
                                    contentDescription = badge,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (supported) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (selectedEndpoint != "/v1/models" && selectedEndpoint != "/health") {
                    OutlinedTextField(value = testModel, onValueChange = { testModel = it }, label = { Text(s.modelId) }, textStyle = MonoTextStyle, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(value = testPrompt, onValueChange = { testPrompt = it }, label = { Text(s.payloadPromptLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedButton(
                    onClick = {
                        viewModel.testLiveGatewayEndpoint(selectedEndpoint, testModel, testPrompt) { endpointOutput = it }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("test_live_endpoint_btn")
                ) {
                    Icon(Icons.Default.Terminal, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(s.testEndpoint)
                }
                endpointOutput?.let { out ->
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(out, style = MonoTextStyle, modifier = Modifier.padding(10.dp))
                    }
                }
            }
        }

        // Gateway Configuration Summary & Entry Point to Validated Mode Switcher
        item {
            GatewayCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(s.gatewayConfigMenuTitle, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "${s.gatewayConfigActiveModeLabel}: ${gatewayModeDisplayName(settings.gatewayMode, s)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    StatusPill(text = settings.gatewayMode.name, tone = StatusTone.INFO)
                }
                Spacer(Modifier.height(6.dp))
                val configuredEndpoint = if (settings.gatewayMode == GatewayMode.LOCAL) {
                    "http://${settings.gatewayHost}:${settings.gatewayPort}"
                } else {
                    settings.gatewayRemoteUrl.ifBlank { "—" }
                }
                Text(
                    text = "${settings.gatewayName} • $configuredEndpoint",
                    style = MonoTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${s.requestTimeoutSec}: ${settings.gatewayTimeoutSec}s • ${s.rateLimitPerMin}: ${settings.gatewayRateLimitPerMin} • ${s.loggingLevel}: ${settings.gatewayLoggingLevel.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onOpenGatewayConfig,
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("gateway_open_config_btn")
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(s.gatewayConfigMenuTitle)
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
fun SmartRouterScreen(viewModel: GatewayViewModel) {
    val s = LocalStrings.current
    val settings by viewModel.settings.collectAsState()
    val rules by viewModel.routingRules.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val decisions by viewModel.recentRouterDecisions.collectAsState()
    val curatedCatalog by viewModel.openCodeCuratedCatalog.collectAsState()

    var showRuleDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(s.smartRouterTitle, style = MaterialTheme.typography.headlineSmall)
        }

        // Routing Modes
        item {
            GatewayCard {
                Text(s.routingMode, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    RoutingMode.entries.forEach { rm ->
                        FilterChip(
                            selected = settings.defaultRoutingMode == rm,
                            onClick = { viewModel.updateRoutingSettings(rm, settings.fallbackEnabled, settings.maxRetries) },
                            label = { Text(rm.name.replace("_", " ")) },
                            modifier = Modifier.testTag("routing_mode_${rm.name}")
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(s.enableFallback, style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = settings.fallbackEnabled,
                        onCheckedChange = { viewModel.updateRoutingSettings(settings.defaultRoutingMode, it, settings.maxRetries) }
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${s.maxRetries}: ${settings.maxRetries}", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = settings.maxRetries.toFloat(),
                        onValueChange = { viewModel.updateRoutingSettings(settings.defaultRoutingMode, settings.fallbackEnabled, it.toInt()) },
                        valueRange = 0f..5f,
                        steps = 4,
                        modifier = Modifier.weight(1f)
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(s.localRoutingPolicy, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LocalRoutingPolicy.entries.forEach { policy ->
                        FilterChip(
                            selected = settings.localRoutingPolicy == policy,
                            onClick = {
                                val autoFallback = if (policy == LocalRoutingPolicy.LOCAL_CLOUD_FALLBACK) true else settings.allowCloudFallbackForLocal
                                viewModel.updateLocalAiSettings(policy, autoFallback, settings.localNetworkAccessAllowed)
                            },
                            label = { Text(policy.name.replace("_", " ")) },
                            modifier = Modifier.testTag("router_local_policy_${policy.name}")
                        )
                    }
                }
                if (settings.localRoutingPolicy == LocalRoutingPolicy.LOCAL_FIRST) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(s.allowCloudFallback, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Switch(
                            checked = settings.allowCloudFallbackForLocal,
                            onCheckedChange = {
                                viewModel.updateLocalAiSettings(settings.localRoutingPolicy, it, settings.localNetworkAccessAllowed)
                            }
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(s.openCodeSelectorTitle, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                val selectorOptions = listOf(
                    OpenCodeSelectorIds.AUTO to s.openCodeModeAuto,
                    OpenCodeSelectorIds.DEFAULT to s.openCodeModeDefault,
                    OpenCodeSelectorIds.BEST_CODING to s.openCodeModeBestCoding,
                    OpenCodeSelectorIds.FREE to s.openCodeModeFree
                )
                val activeSelector = OpenCodeSelectorIds.normalizeSelectorId(settings.openCodeSelectionMode)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    selectorOptions.forEach { (selId, label) ->
                        FilterChip(
                            selected = activeSelector == selId,
                            onClick = {
                                viewModel.updateSmartAutoPreferences(
                                    openCodeSelectionMode = selId,
                                    autoAllowPaidModels = settings.autoAllowPaidModels,
                                    preferConfiguredDefaultInAuto = settings.preferConfiguredDefaultInAuto
                                )
                            },
                            label = { Text(label) },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("opencode_selector_chip_$selId")
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                val selectorTargetText = when (activeSelector) {
                    OpenCodeSelectorIds.DEFAULT -> curatedCatalog.defaultResolvedTarget ?: "—"
                    OpenCodeSelectorIds.BEST_CODING -> curatedCatalog.bestCodingResolvedTarget ?: (curatedCatalog.codingUnavailableReason ?: "—")
                    OpenCodeSelectorIds.FREE -> "${curatedCatalog.verifiedFreeCount}"
                    else -> curatedCatalog.autoResolvedTarget ?: "—"
                }
                Text(
                    text = String.format(
                        s.openCodeCuratedCountFormat,
                        curatedCatalog.entries.size,
                        curatedCatalog.totalEligibleModelsCount,
                        curatedCatalog.totalStoredModelsCount
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${s.openCodeResolvedTargetPrefix}: $selectorTargetText",
                    style = MonoTextStyle,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(6.dp))
                val paidSwitchEffective = settings.autoAllowPaidModels && settings.defaultRoutingMode != RoutingMode.AUTO_FREE
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(s.openCodeAllowPaidInAuto, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(
                        checked = paidSwitchEffective,
                        enabled = settings.defaultRoutingMode != RoutingMode.AUTO_FREE,
                        onCheckedChange = {
                            viewModel.updateSmartAutoPreferences(
                                openCodeSelectionMode = settings.openCodeSelectionMode,
                                autoAllowPaidModels = it,
                                preferConfiguredDefaultInAuto = settings.preferConfiguredDefaultInAuto
                            )
                        },
                        modifier = Modifier.testTag("switch_auto_allow_paid")
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(s.openCodePreferDefaultInAuto, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(
                        checked = settings.preferConfiguredDefaultInAuto,
                        onCheckedChange = {
                            viewModel.updateSmartAutoPreferences(
                                openCodeSelectionMode = settings.openCodeSelectionMode,
                                autoAllowPaidModels = settings.autoAllowPaidModels,
                                preferConfiguredDefaultInAuto = it
                            )
                        },
                        modifier = Modifier.testTag("switch_prefer_default_auto")
                    )
                }
            }
        }

        // Routing Rules
        item {
            SectionHeader(title = s.routingRules) {
                FilledTonalButton(onClick = { showRuleDialog = true }, modifier = Modifier.testTag("add_routing_rule_btn")) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(s.addRule)
                }
            }
        }

        if (rules.isEmpty()) {
            item {
                EmptyStatePanel(
                    icon = Icons.AutoMirrored.Filled.AltRoute,
                    title = s.routingRules,
                    message = s.noRulesMessage,
                    actionLabel = s.addRule,
                    onAction = { showRuleDialog = true }
                )
            }
        } else {
            items(rules, key = { it.id }) { rule ->
                GatewayCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(rule.name, style = MaterialTheme.typography.titleMedium)
                            Text("${s.aliasPrefix}: ${rule.modelAlias} • ${rule.localCloudPreference} • P${rule.priority}", style = MonoTextStyle, color = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { viewModel.deleteRoutingRule(rule) }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = s.delete, tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        // Live Router Monitor
        item {
            SectionHeader(title = s.routerMonitoring)
        }

        if (decisions.isEmpty()) {
            item {
                GatewayCard {
                    Text(s.noRoutingDecisionsYet, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items(decisions.take(10)) { d ->
                GatewayCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${d.selectedProvider} → ${d.selectedModel}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (d.usedDefaultModel) {
                                StatusPill(text = s.openCodeDefaultBadge, tone = StatusTone.INFO)
                            }
                            StatusPill(
                                text = d.finalStatus,
                                tone = if (d.finalStatus.startsWith("SUCCESS")) StatusTone.SUCCESS else StatusTone.ERROR
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    if (d.requestedModelOrAlias.isNotBlank()) {
                        Text("${s.modelOrAliasLabel}: ${d.requestedModelOrAlias}", style = MonoTextStyle, color = MaterialTheme.colorScheme.primary)
                    }
                    Text("${s.routingReason}: ${d.routingReason}", style = MaterialTheme.typography.bodySmall)
                    d.fallbackReason?.takeIf { it.isNotBlank() }?.let { fb ->
                        Text(fb, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                    Text("${s.attemptCount}: ${d.attemptCount} • ${s.fallbackUsed}: ${d.fallbackUsed} • ${d.latencyMs}ms", style = MonoTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (showRuleDialog) {
        var name by remember { mutableStateOf("") }
        var alias by remember { mutableStateOf("jh-coder") }
        var preferredId by remember { mutableStateOf(providers.firstOrNull()?.id.orEmpty()) }
        var localCloud by remember { mutableStateOf("ANY") }
        var priority by remember { mutableStateOf("1") }

        AlertDialog(
            onDismissRequest = { showRuleDialog = false },
            title = { Text(s.addRule) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(s.ruleNameLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = alias, onValueChange = { alias = it }, label = { Text(s.aliasHint) }, textStyle = MonoTextStyle, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (providers.isNotEmpty()) {
                        Text(s.preferredProviderLabel, style = MaterialTheme.typography.labelMedium)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(providers) { p ->
                                FilterChip(selected = preferredId == p.id, onClick = { preferredId = p.id }, label = { Text(p.name) })
                            }
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(listOf("ANY", "LOCAL_FIRST", "CLOUD_FIRST", "LOCAL_ONLY")) { pref ->
                            FilterChip(selected = localCloud == pref, onClick = { localCloud = pref }, label = { Text(pref) })
                        }
                    }
                    OutlinedTextField(value = priority, onValueChange = { priority = it.filter { c -> c.isDigit() } }, label = { Text(s.priorityLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (name.isNotBlank() && alias.isNotBlank()) {
                        viewModel.saveRoutingRule(
                            RoutingRuleEntity(
                                name = name.trim(),
                                modelAlias = alias.trim(),
                                preferredProviderId = preferredId,
                                localCloudPreference = localCloud,
                                priority = priority.toIntOrNull() ?: 1
                            )
                        )
                        showRuleDialog = false
                    }
                }) { Text(s.save) }
            },
            dismissButton = { TextButton(onClick = { showRuleDialog = false }) { Text(s.cancel) } }
        )
    }
}
