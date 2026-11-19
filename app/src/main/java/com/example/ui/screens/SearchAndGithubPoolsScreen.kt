@file:OptIn(ExperimentalLayoutApi::class)

package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.i18n.LocalStrings
import com.example.data.local.KeyStatus
import com.example.data.local.ServicePoolKeyEntity
import com.example.data.local.ServicePoolType
import com.example.gateway.WebSearchRouterService
import com.example.ui.GatewayViewModel
import com.example.ui.components.*
import com.example.ui.theme.MonoTextStyle
import java.util.Locale

private enum class PoolFilter {
    ALL, WEB_SEARCH, GITHUB_SEARCH
}

@Composable
fun SearchAndGithubPoolsScreen(viewModel: GatewayViewModel) {
    val s = LocalStrings.current
    val keys by viewModel.servicePoolKeys.collectAsState()
    val settings by viewModel.settings.collectAsState()

    var filter by remember { mutableStateOf(PoolFilter.ALL) }
    var showAddDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ServicePoolKeyEntity?>(null) }

    val now = remember(keys) { System.currentTimeMillis() }
    val webKeys = remember(keys) { keys.filter { it.serviceType == ServicePoolType.WEB_SEARCH } }
    val githubKeys = remember(keys) { keys.filter { it.serviceType == ServicePoolType.GITHUB_SEARCH } }
    val filteredKeys = remember(keys, filter) {
        when (filter) {
            PoolFilter.ALL -> keys
            PoolFilter.WEB_SEARCH -> webKeys
            PoolFilter.GITHUB_SEARCH -> githubKeys
        }
    }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                icon = { Icon(Icons.Default.Add, contentDescription = s.addPoolKeyAction) },
                text = { Text(s.addPoolKeyAction) },
                modifier = Modifier.testTag("add_pool_key_fab")
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("search_pools_list"),
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(s.searchPoolsTitle, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = s.searchPoolsSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Summary metrics card
            item {
                val activeWebCount = webKeys.count { it.enabled && (it.cooldownUntilMs == null || it.cooldownUntilMs <= now) }
                val healthyPatCount = githubKeys.count {
                    it.enabled &&
                        (it.cooldownUntilMs == null || it.cooldownUntilMs <= now) &&
                        (it.rateLimitRemaining == null || it.rateLimitRemaining > 0)
                }
                GatewayCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        MetricMiniTile(
                            label = s.searchPoolFilterWeb,
                            value = "$activeWebCount/${webKeys.size}",
                            modifier = Modifier.weight(1f)
                        )
                        MetricMiniTile(
                            label = s.searchPoolFilterGithub,
                            value = "$healthyPatCount/${githubKeys.size}",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(s.poolDuckDuckGoFallbackTitle, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = s.poolDuckDuckGoFallbackDesc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.webSearchDuckDuckGoFallbackEnabled,
                            onCheckedChange = { viewModel.setWebSearchDuckDuckGoFallbackEnabled(it) },
                            modifier = Modifier.testTag("ddg_fallback_switch")
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.clearSearchCaches() },
                        modifier = Modifier.testTag("clear_search_cache_btn")
                    ) {
                        Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(s.poolClearCacheAction)
                    }
                }
            }

            // Filter tabs
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = filter == PoolFilter.ALL,
                            onClick = { filter = PoolFilter.ALL },
                            label = { Text("${s.searchPoolFilterAll} (${keys.size})") }
                        )
                    }
                    item {
                        FilterChip(
                            selected = filter == PoolFilter.WEB_SEARCH,
                            onClick = { filter = PoolFilter.WEB_SEARCH },
                            label = { Text("${s.searchPoolFilterWeb} (${webKeys.size})") }
                        )
                    }
                    item {
                        FilterChip(
                            selected = filter == PoolFilter.GITHUB_SEARCH,
                            onClick = { filter = PoolFilter.GITHUB_SEARCH },
                            label = { Text("${s.searchPoolFilterGithub} (${githubKeys.size})") }
                        )
                    }
                }
            }

            if (filteredKeys.isEmpty()) {
                item {
                    EmptyStatePanel(
                        icon = Icons.Default.Search,
                        title = s.poolEmptyTitle,
                        message = s.poolEmptyMessage,
                        actionLabel = s.addPoolKeyAction,
                        onAction = { showAddDialog = true }
                    )
                }
            } else {
                items(filteredKeys, key = { it.id }) { keyItem ->
                    val cooldownRemainingSec = keyItem.cooldownUntilMs
                        ?.let { ((it - now + 999L) / 1000L).toInt() }
                        ?.takeIf { it > 0 }

                    val (badgeText, badgeTone) = when {
                        !keyItem.enabled -> s.disabled to StatusTone.NEUTRAL
                        cooldownRemainingSec != null -> "${s.poolStatusCooldownPrefix} ${cooldownRemainingSec}s" to StatusTone.WARNING
                        keyItem.status == KeyStatus.ACTIVE -> "ACTIVE" to StatusTone.SUCCESS
                        keyItem.status == KeyStatus.RATE_LIMITED -> "RATE LIMITED" to StatusTone.WARNING
                        keyItem.status == KeyStatus.INVALID || keyItem.status == KeyStatus.EXPIRED -> keyItem.status.name to StatusTone.ERROR
                        !keyItem.lastError.isNullOrBlank() -> "ERROR" to StatusTone.ERROR
                        else -> "READY" to StatusTone.INFO
                    }

                    GatewayCard(modifier = Modifier.testTag("pool_key_card_${keyItem.id}")) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = keyItem.label,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${keyItem.providerId.uppercase(Locale.US)} • ${keyItem.serviceType.name} • P${keyItem.priority}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            StatusPill(text = badgeText, tone = badgeTone)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = keyItem.maskedPreview,
                            style = MonoTextStyle,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = String.format(Locale.US, s.poolUsageCountFormat, keyItem.usageCount),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (keyItem.serviceType == ServicePoolType.GITHUB_SEARCH && keyItem.rateLimitRemaining != null) {
                                Text(
                                    text = String.format(Locale.US, s.poolQuotaRemainingFormat, keyItem.rateLimitRemaining),
                                    style = MonoTextStyle,
                                    color = if (keyItem.rateLimitRemaining <= 2) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        if (!keyItem.lastError.isNullOrBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = keyItem.lastError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                OutlinedButton(
                                    onClick = { viewModel.testServicePoolKey(keyItem) },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Text(s.poolTestKeyAction)
                                }
                                if (cooldownRemainingSec != null || keyItem.status == KeyStatus.RATE_LIMITED || !keyItem.lastError.isNullOrBlank()) {
                                    OutlinedButton(
                                        onClick = { viewModel.resetServicePoolKeyCooldown(keyItem) },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Text(s.poolResetCooldownAction)
                                    }
                                }
                                TextButton(
                                    onClick = { deleteTarget = keyItem },
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Text(s.delete)
                                }
                            }
                            Switch(
                                checked = keyItem.enabled,
                                onCheckedChange = { viewModel.toggleServicePoolKeyEnabled(keyItem, it) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        var selectedService by remember {
            mutableStateOf(
                if (filter == PoolFilter.GITHUB_SEARCH) ServicePoolType.GITHUB_SEARCH else ServicePoolType.WEB_SEARCH
            )
        }
        var selectedProvider by remember { mutableStateOf("tavily") }
        var baseLabel by remember { mutableStateOf("") }
        var keysInput by remember { mutableStateOf("") }
        var priorityStr by remember { mutableStateOf("1") }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(s.addPoolKeyAction) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = s.addPoolKeysBatchHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(s.poolProviderLabel, style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(
                            selected = selectedService == ServicePoolType.WEB_SEARCH,
                            onClick = { selectedService = ServicePoolType.WEB_SEARCH },
                            label = { Text(s.searchPoolFilterWeb) }
                        )
                        FilterChip(
                            selected = selectedService == ServicePoolType.GITHUB_SEARCH,
                            onClick = {
                                selectedService = ServicePoolType.GITHUB_SEARCH
                                selectedProvider = "github"
                            },
                            label = { Text(s.searchPoolFilterGithub) }
                        )
                    }
                    if (selectedService == ServicePoolType.WEB_SEARCH) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(WebSearchRouterService.SUPPORTED_WEB_SEARCH_PROVIDERS) { p ->
                                FilterChip(
                                    selected = selectedProvider == p,
                                    onClick = { selectedProvider = p },
                                    label = { Text(p.replaceFirstChar { it.uppercase() }) }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = baseLabel,
                        onValueChange = { baseLabel = it },
                        label = { Text(s.poolKeyLabelHint) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = keysInput,
                        onValueChange = { keysInput = it },
                        label = { Text(s.poolKeysInputLabel) },
                        textStyle = MonoTextStyle,
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth().testTag("pool_keys_batch_input")
                    )
                    if (selectedService == ServicePoolType.WEB_SEARCH) {
                        OutlinedTextField(
                            value = priorityStr,
                            onValueChange = { next -> if (next.all { it.isDigit() } && next.length <= 3) priorityStr = next },
                            label = { Text(s.poolPriorityLabel) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.addServicePoolKeysBatch(
                            serviceType = selectedService,
                            providerId = if (selectedService == ServicePoolType.GITHUB_SEARCH) "github" else selectedProvider,
                            baseLabel = baseLabel,
                            rawKeysMultiLine = keysInput,
                            priority = priorityStr.toIntOrNull() ?: 1
                        )
                        showAddDialog = false
                    },
                    enabled = keysInput.isNotBlank(),
                    modifier = Modifier.testTag("save_pool_keys_btn")
                ) {
                    Text(s.save)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text(s.cancel) }
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(s.deletePoolKeyConfirmTitle) },
            text = { Text(String.format(Locale.US, s.deletePoolKeyConfirmMessage, target.label)) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteServicePoolKey(target)
                        deleteTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(s.delete)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(s.cancel) }
            }
        )
    }
}
