@file:OptIn(ExperimentalLayoutApi::class)

package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.i18n.LocalStrings
import com.example.data.local.*
import com.example.ui.GatewayViewModel
import com.example.ui.components.*
import com.example.ui.navigation.Routes
import com.example.ui.theme.MonoTextStyle

@Composable
fun ProvidersScreen(
    viewModel: GatewayViewModel,
    onNavigate: (String) -> Unit
) {
    val s = LocalStrings.current
    val providers by viewModel.providers.collectAsState()
    val fetchedModelsPrompt by viewModel.fetchedModelsPrompt.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.refreshExpiredCooldowns()
    }

    val groups = remember(providers) { ProviderDirectoryHelper.groupProviders(providers) }
    var showEditor by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showEditor = true },
                icon = { Icon(Icons.Default.Add, contentDescription = s.addProvider) },
                text = { Text(s.addProvider) },
                modifier = Modifier.testTag("add_provider_fab")
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("providers_directory_list"),
            contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(s.providersTitle, style = MaterialTheme.typography.headlineSmall)
                        if (groups.isEmpty()) {
                            Text(
                                text = "0 ${s.connectedApisCountLabel} • 0 ${s.distinctProvidersCountLabel}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("providers_connected_apis_count")
                            )
                        }
                    }
                    TextButton(
                        onClick = { onNavigate(Routes.VAULT) },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(s.navVault)
                    }
                }
            }

            if (groups.isNotEmpty()) {
                item {
                    GatewayCard(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.testTag("providers_summary_card")
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(18.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.testTag("providers_connected_apis_count")) {
                                    Text(
                                        text = providers.size.toString(),
                                        style = MaterialTheme.typography.headlineMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = s.connectedApisCountLabel,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Column(modifier = Modifier.testTag("providers_distinct_count")) {
                                    Text(
                                        text = groups.size.toString(),
                                        style = MaterialTheme.typography.headlineMedium,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                    Text(
                                        text = s.distinctProvidersCountLabel,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            FilledTonalButton(
                                onClick = { onNavigate(Routes.PROVIDERS_ALL) },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("providers_all_btn")
                            ) {
                                Text(s.allApisOptionLabel)
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = s.allApisTitle,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (groups.isEmpty()) {
                item {
                    EmptyStatePanel(
                        icon = Icons.Default.CloudQueue,
                        title = s.noProvidersTitle,
                        message = s.noProvidersMessage,
                        actionLabel = s.addProvider,
                        onAction = { showEditor = true },
                        testTag = "empty_add_provider_btn"
                    )
                }
            } else {
                items(groups, key = { it.groupKey }) { group ->
                    val (statusLabel, statusTone) = providerStatusLabelAndTone(group.aggregatedStatus)
                    val apiCountLabel = "${group.providers.size} ${if (group.providers.size == 1) s.apiCountSingular else s.apiCountPlural}"
                    GatewayCard(
                        onClick = { onNavigate(Routes.providerDetail(group.groupKey)) },
                        modifier = Modifier.testTag("provider_group_card_${group.groupKey}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = group.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = apiCountLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                val subtitleParts = buildList {
                                    add("${group.enabledCount}/${group.providers.size} ${s.enabled}")
                                    group.bestLatencyMs?.let { add("${it}ms") }
                                    if (group.errorCount > 0) add("${group.errorCount} ${s.error}")
                                }
                                Text(
                                    text = subtitleParts.joinToString(" • "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (group.errorCount > 0 && group.connectedCount == 0) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                StatusPill(text = statusLabel, tone = statusTone)
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = group.displayName,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    ProviderApiManagementDialogs(
        viewModel = viewModel,
        metrics = emptyList(),
        keys = emptyList(),
        showEditor = showEditor,
        editingProvider = null,
        initialPresetId = null,
        hidePresetSelector = false,
        templateProvider = null,
        managingKeysProvider = null,
        deletingProvider = null,
        detailProvider = null,
        fetchedModelsPrompt = fetchedModelsPrompt,
        onDismissEditor = { showEditor = false },
        onDismissManageKeys = {},
        onDismissDelete = {},
        onDismissDetails = {}
    )
}

@Composable
fun ProviderDetailScreen(
    groupKey: String,
    viewModel: GatewayViewModel
) {
    val s = LocalStrings.current
    val providers by viewModel.providers.collectAsState()
    val metrics by viewModel.metrics.collectAsState()
    val keys by viewModel.apiKeys.collectAsState()
    val fetchingProviderIds by viewModel.fetchingProviderIds.collectAsState()
    val fetchedModelsPrompt by viewModel.fetchedModelsPrompt.collectAsState()

    val groupProviders = remember(providers, groupKey) {
        ProviderDirectoryHelper.filterProvidersForGroup(providers, groupKey)
    }
    val groupSummary = remember(providers, groupKey) {
        ProviderDirectoryHelper.findGroup(providers, groupKey)
    }
    val providerTitle = groupSummary?.displayName
        ?: groupProviders.firstOrNull()?.name
        ?: s.providersTitle
    val keysCountByProvider = remember(keys) {
        keys.filter { it.enabled }.groupingBy { it.providerId }.eachCount()
    }
    val requestsCountByProvider = remember(metrics) {
        metrics.groupingBy { it.providerId }.eachCount()
    }

    var showEditor by remember { mutableStateOf(false) }
    var editingProvider by remember { mutableStateOf<ProviderEntity?>(null) }
    var managingKeysProvider by remember { mutableStateOf<ProviderEntity?>(null) }
    var deletingProvider by remember { mutableStateOf<ProviderEntity?>(null) }
    var detailProvider by remember { mutableStateOf<ProviderEntity?>(null) }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editingProvider = null; showEditor = true },
                icon = { Icon(Icons.Default.Add, contentDescription = s.addProvider) },
                text = { Text(s.addProvider) },
                modifier = Modifier.testTag("add_provider_fab")
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("provider_detail_list"),
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                val aggregated = groupSummary?.aggregatedStatus
                    ?: ProviderDirectoryHelper.computeAggregatedStatus(groupProviders)
                val (statusLabel, statusTone) = providerStatusLabelAndTone(aggregated)
                GatewayCard(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.testTag("provider_detail_header")
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(providerTitle, style = MaterialTheme.typography.headlineSmall)
                            Text(
                                text = "${groupProviders.size} ${if (groupProviders.size == 1) s.apiCountSingular else s.apiCountPlural}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        StatusPill(text = statusLabel, tone = statusTone)
                    }
                }
            }

            if (groupProviders.isEmpty()) {
                item {
                    EmptyStatePanel(
                        icon = Icons.Default.CloudQueue,
                        title = providerTitle,
                        message = s.noProvidersMessage,
                        actionLabel = s.addProvider,
                        onAction = { editingProvider = null; showEditor = true },
                        testTag = "empty_add_provider_btn"
                    )
                }
            } else {
                items(groupProviders, key = { it.id }) { provider ->
                    val pKeys = keysCountByProvider[provider.id] ?: 0
                    val pRequests = requestsCountByProvider[provider.id] ?: 0
                    ProviderApiConfigCard(
                        provider = provider,
                        providerBadge = null,
                        keysCount = pKeys,
                        requestsCount = pRequests,
                        isFetching = provider.id in fetchingProviderIds,
                        onTestConnection = { viewModel.testProviderConnection(provider) },
                        onFetchModels = { viewModel.fetchProviderModels(provider) },
                        onManageKeys = { managingKeysProvider = provider },
                        onToggleEnabled = { viewModel.toggleProviderEnabled(provider) },
                        onEdit = { editingProvider = provider; showEditor = true },
                        onViewDetails = { detailProvider = provider },
                        onDelete = { deletingProvider = provider }
                    )
                }
            }
        }
    }

    ProviderApiManagementDialogs(
        viewModel = viewModel,
        metrics = metrics,
        keys = keys,
        showEditor = showEditor,
        editingProvider = editingProvider,
        initialPresetId = groupSummary?.presetId,
        hidePresetSelector = true,
        templateProvider = groupProviders.firstOrNull(),
        managingKeysProvider = managingKeysProvider,
        deletingProvider = deletingProvider,
        detailProvider = detailProvider,
        fetchedModelsPrompt = fetchedModelsPrompt,
        onDismissEditor = { showEditor = false },
        onDismissManageKeys = { managingKeysProvider = null },
        onDismissDelete = { deletingProvider = null },
        onDismissDetails = { detailProvider = null }
    )
}

@Composable
fun AllApisScreen(viewModel: GatewayViewModel) {
    val s = LocalStrings.current
    val providers by viewModel.providers.collectAsState()
    val metrics by viewModel.metrics.collectAsState()
    val keys by viewModel.apiKeys.collectAsState()
    val fetchingProviderIds by viewModel.fetchingProviderIds.collectAsState()
    val fetchedModelsPrompt by viewModel.fetchedModelsPrompt.collectAsState()

    val sortedProviders = remember(providers) {
        providers.sortedWith(
            compareBy<ProviderEntity> { !it.enabled }
                .thenBy { ProviderDirectoryHelper.resolveProviderIdentity(it).displayName.lowercase() }
                .thenBy { it.priority }
                .thenBy { it.name.lowercase() }
        )
    }
    val identitiesById = remember(sortedProviders) {
        sortedProviders.associate { it.id to ProviderDirectoryHelper.resolveProviderIdentity(it).displayName }
    }
    val keysCountByProvider = remember(keys) {
        keys.filter { it.enabled }.groupingBy { it.providerId }.eachCount()
    }
    val requestsCountByProvider = remember(metrics) {
        metrics.groupingBy { it.providerId }.eachCount()
    }

    var showEditor by remember { mutableStateOf(false) }
    var editingProvider by remember { mutableStateOf<ProviderEntity?>(null) }
    var managingKeysProvider by remember { mutableStateOf<ProviderEntity?>(null) }
    var deletingProvider by remember { mutableStateOf<ProviderEntity?>(null) }
    var detailProvider by remember { mutableStateOf<ProviderEntity?>(null) }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editingProvider = null; showEditor = true },
                icon = { Icon(Icons.Default.Add, contentDescription = s.addProvider) },
                text = { Text(s.addProvider) },
                modifier = Modifier.testTag("add_provider_fab")
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("all_apis_list"),
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(s.allApisTitle, style = MaterialTheme.typography.headlineSmall)
                    StatusPill(
                        text = "${sortedProviders.size} ${if (sortedProviders.size == 1) s.apiCountSingular else s.apiCountPlural}",
                        tone = StatusTone.INFO
                    )
                }
            }

            if (sortedProviders.isEmpty()) {
                item {
                    EmptyStatePanel(
                        icon = Icons.Default.CloudQueue,
                        title = s.noProvidersTitle,
                        message = s.noProvidersMessage,
                        actionLabel = s.addProvider,
                        onAction = { editingProvider = null; showEditor = true },
                        testTag = "empty_add_provider_btn"
                    )
                }
            } else {
                items(sortedProviders, key = { it.id }) { provider ->
                    val identityName = identitiesById[provider.id]
                    val pKeys = keysCountByProvider[provider.id] ?: 0
                    val pRequests = requestsCountByProvider[provider.id] ?: 0
                    ProviderApiConfigCard(
                        provider = provider,
                        providerBadge = identityName,
                        keysCount = pKeys,
                        requestsCount = pRequests,
                        isFetching = provider.id in fetchingProviderIds,
                        onTestConnection = { viewModel.testProviderConnection(provider) },
                        onFetchModels = { viewModel.fetchProviderModels(provider) },
                        onManageKeys = { managingKeysProvider = provider },
                        onToggleEnabled = { viewModel.toggleProviderEnabled(provider) },
                        onEdit = { editingProvider = provider; showEditor = true },
                        onViewDetails = { detailProvider = provider },
                        onDelete = { deletingProvider = provider }
                    )
                }
            }
        }
    }

    ProviderApiManagementDialogs(
        viewModel = viewModel,
        metrics = metrics,
        keys = keys,
        showEditor = showEditor,
        editingProvider = editingProvider,
        initialPresetId = null,
        hidePresetSelector = editingProvider != null,
        templateProvider = null,
        managingKeysProvider = managingKeysProvider,
        deletingProvider = deletingProvider,
        detailProvider = detailProvider,
        fetchedModelsPrompt = fetchedModelsPrompt,
        onDismissEditor = { showEditor = false },
        onDismissManageKeys = { managingKeysProvider = null },
        onDismissDelete = { deletingProvider = null },
        onDismissDetails = { detailProvider = null }
    )
}

@Composable
fun VaultScreen(viewModel: GatewayViewModel) {
    val s = LocalStrings.current
    val keys by viewModel.apiKeys.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val providerNamesById = remember(providers) {
        providers.associate { it.id to it.name }
    }
    var showAddDialog by remember { mutableStateOf(false) }
    var rotatingKey by remember { mutableStateOf<ApiKeyEntity?>(null) }
    var deletingKey by remember { mutableStateOf<ApiKeyEntity?>(null) }

    Scaffold(
        floatingActionButton = {
            if (providers.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { showAddDialog = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = s.addKey) },
                    text = { Text(s.addKey) },
                    modifier = Modifier.testTag("add_vault_key_fab")
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(s.vaultTitle, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                GatewayCard(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        Text(s.policyComplianceNote, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (keys.isEmpty()) {
                item {
                    EmptyStatePanel(
                        icon = Icons.Default.VpnKey,
                        title = s.vaultTitle,
                        message = s.noKeysMessage,
                        actionLabel = if (providers.isNotEmpty()) s.addKey else null,
                        onAction = if (providers.isNotEmpty()) ({ showAddDialog = true }) else null
                    )
                }
            } else {
                items(keys, key = { it.id }) { keyItem ->
                    val providerName = providerNamesById[keyItem.providerId] ?: s.unknownProviderLabel
                    val (statusTxt, tone) = keyStatusLabelAndTone(keyItem.status)
                    GatewayCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(keyItem.label, style = MaterialTheme.typography.titleMedium)
                                Text("$providerName • ${keyItem.selectionPolicy.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            StatusPill(text = statusTxt, tone = tone)
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(keyItem.maskedPreview, style = MonoTextStyle, color = MaterialTheme.colorScheme.primary)
                            if (keyItem.isDefault) {
                                AssistChip(onClick = {}, label = { Text(s.defaultBadge, style = MaterialTheme.typography.labelSmall) })
                            }
                        }
                        Text("${s.requestsUnit}: ${keyItem.usageCount} • ${s.priorityLabel}: ${keyItem.priority}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { viewModel.testApiKey(keyItem) }) { Text(s.test) }
                            OutlinedButton(onClick = { rotatingKey = keyItem }) { Text(s.rotateKey) }
                            if (!keyItem.isDefault) {
                                TextButton(onClick = { viewModel.setDefaultApiKey(keyItem) }) { Text(s.setDefaultKey) }
                            }
                            IconButton(onClick = { viewModel.toggleApiKeyEnabled(keyItem) }) {
                                Icon(
                                    imageVector = if (keyItem.enabled) Icons.Default.ToggleOn else Icons.Default.ToggleOff,
                                    contentDescription = if (keyItem.enabled) s.disable else s.enable
                                )
                            }
                            IconButton(onClick = { deletingKey = keyItem }) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = s.delete, tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog && providers.isNotEmpty()) {
        var selectedProv by remember { mutableStateOf(providers.first()) }
        var label by remember { mutableStateOf("") }
        var plainKey by remember { mutableStateOf("") }
        var policy by remember { mutableStateOf(KeySelectionPolicy.PRIORITY) }
        var priority by remember { mutableStateOf("1") }
        var isDefault by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(s.addKey) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(providers) { p ->
                            FilterChip(selected = selectedProv.id == p.id, onClick = { selectedProv = p }, label = { Text(p.name) })
                        }
                    }
                    OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text(s.keyLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = plainKey, onValueChange = { plainKey = it }, label = { Text(s.apiKeyLabel) }, textStyle = MonoTextStyle, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = priority, onValueChange = { priority = it.filter { c -> c.isDigit() } }, label = { Text(s.priorityLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text(s.keyPolicy, style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(KeySelectionPolicy.entries) { pol ->
                            FilterChip(selected = policy == pol, onClick = { policy = pol }, label = { Text(pol.name) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = isDefault, onCheckedChange = { isDefault = it })
                        Text(s.setDefaultKey)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (plainKey.isNotBlank()) {
                        viewModel.addApiKey(selectedProv.id, label, plainKey, priority.toIntOrNull() ?: 1, isDefault, policy)
                        showAddDialog = false
                    }
                }) { Text(s.save) }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text(s.cancel) } }
        )
    }

    rotatingKey?.let { k ->
        var newVal by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { rotatingKey = null },
            title = { Text("${s.rotateKey}: ${k.label}") },
            text = {
                OutlinedTextField(value = newVal, onValueChange = { newVal = it }, label = { Text(s.apiKeyLabel) }, textStyle = MonoTextStyle, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                Button(onClick = {
                    if (newVal.isNotBlank()) {
                        viewModel.rotateApiKey(k, newVal)
                        rotatingKey = null
                    }
                }) { Text(s.save) }
            },
            dismissButton = { TextButton(onClick = { rotatingKey = null }) { Text(s.cancel) } }
        )
    }

    deletingKey?.let { k ->
        ConfirmDeleteDialog(
            title = "${s.delete} ${k.label}?",
            message = s.deleteConfirmMessage,
            onConfirm = { viewModel.deleteApiKey(k); deletingKey = null },
            onDismiss = { deletingKey = null }
        )
    }
}
