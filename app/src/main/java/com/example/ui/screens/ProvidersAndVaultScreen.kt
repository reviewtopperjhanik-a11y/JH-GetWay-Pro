@file:OptIn(ExperimentalLayoutApi::class)

package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.i18n.LocalStrings
import com.example.data.local.*
import com.example.network.ProviderPresets
import com.example.ui.FetchedModelsPrompt
import com.example.ui.GatewayViewModel
import com.example.ui.components.*
import com.example.ui.navigation.Routes
import com.example.ui.theme.MonoTextStyle
import kotlinx.coroutines.launch

@Composable
fun ProvidersScreen(
    viewModel: GatewayViewModel,
    onNavigate: (String) -> Unit
) {
    val s = LocalStrings.current
    val providers by viewModel.providers.collectAsState()
    val metrics by viewModel.metrics.collectAsState()
    val keys by viewModel.apiKeys.collectAsState()
    val fetchingProviderIds by viewModel.fetchingProviderIds.collectAsState()
    val fetchedModelsPrompt by viewModel.fetchedModelsPrompt.collectAsState()

    var showEditor by remember { mutableStateOf(false) }
    var editingProvider by remember { mutableStateOf<ProviderEntity?>(null) }
    var deletingProvider by remember { mutableStateOf<ProviderEntity?>(null) }
    var errorDetailProvider by remember { mutableStateOf<ProviderEntity?>(null) }

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
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(s.providersTitle, style = MaterialTheme.typography.headlineSmall)
                    TextButton(onClick = { onNavigate(Routes.VAULT) }) {
                        Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(s.navVault)
                    }
                }
            }

            if (providers.isEmpty()) {
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
                items(providers, key = { it.id }) { provider ->
                    val (statusLabel, statusTone) = providerStatusLabelAndTone(provider.status)
                    val pKeys = keys.count { it.providerId == provider.id && it.enabled }
                    val pRequests = metrics.count { it.providerId == provider.id }
                    val isFetching = provider.id in fetchingProviderIds

                    GatewayCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(provider.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = "${provider.apiFormat.name} • P${provider.priority} • $pKeys ${s.keysUnit} • $pRequests ${s.requestsUnit}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            StatusPill(text = statusLabel, tone = statusTone)
                        }

                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = provider.baseUrl,
                            style = MonoTextStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "${s.defaultModel}: ${provider.defaultModel.ifBlank { s.notSelectedLabel }}",
                            style = MonoTextStyle,
                            color = if (provider.defaultModel.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (provider.lastLatencyMs != null) {
                            Text(
                                text = "${s.latencyMsLabel}: ${provider.lastLatencyMs} ms",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (!provider.lastError.isNullOrBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = provider.lastError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(Modifier.height(10.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilledTonalButton(
                                onClick = { viewModel.testProviderConnection(provider) },
                                modifier = Modifier.testTag("test_provider_${provider.id}")
                            ) {
                                Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(s.testConnection)
                            }
                            OutlinedButton(
                                enabled = !isFetching,
                                onClick = { viewModel.fetchProviderModels(provider) },
                                modifier = Modifier.testTag("fetch_models_${provider.id}")
                            ) {
                                if (isFetching) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                                Spacer(Modifier.width(4.dp))
                                Text(if (isFetching) s.fetchingModelsLabel else s.fetchModels)
                            }
                            IconButton(onClick = { viewModel.toggleProviderEnabled(provider) }) {
                                Icon(
                                    imageVector = if (provider.enabled) Icons.Default.ToggleOn else Icons.Default.ToggleOff,
                                    contentDescription = if (provider.enabled) s.disable else s.enable,
                                    tint = if (provider.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { editingProvider = provider; showEditor = true }) {
                                Icon(Icons.Default.Edit, contentDescription = s.edit)
                            }
                            IconButton(onClick = { errorDetailProvider = provider }) {
                                Icon(Icons.Default.Info, contentDescription = s.viewErrors)
                            }
                            IconButton(onClick = { deletingProvider = provider }) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = s.delete, tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEditor) {
        ProviderEditorDialog(
            existing = editingProvider,
            onFetchDraftModels = { id, pName, url, fmt, keyInput, headersText ->
                viewModel.fetchModelsForDraft(id, pName, url, fmt, keyInput, headersText)
            },
            onDismiss = { showEditor = false },
            onSave = { name, type, presetId, url, format, model, prio, enabled, headers, notes, key ->
                viewModel.saveProvider(editingProvider?.id, name, type, presetId, url, format, model, prio, enabled, headers, notes, key)
                showEditor = false
            }
        )
    }

    fetchedModelsPrompt?.let { prompt ->
        FetchedModelsPickerDialog(
            prompt = prompt,
            onSelectModel = { model ->
                viewModel.selectModelForProvider(prompt.provider.id, model)
            },
            onToggleEnabled = { model ->
                viewModel.toggleFetchedModelEnabled(model)
            },
            onDismiss = { viewModel.dismissFetchedModelsPrompt() }
        )
    }

    deletingProvider?.let { target ->
        ConfirmDeleteDialog(
            title = "${s.delete} ${target.name}?",
            message = s.providerDeleteImpactWarning,
            onConfirm = { viewModel.deleteProvider(target); deletingProvider = null },
            onDismiss = { deletingProvider = null }
        )
    }

    errorDetailProvider?.let { target ->
        val pMetrics = metrics.filter { it.providerId == target.id }
        AlertDialog(
            onDismissRequest = { errorDetailProvider = null },
            title = { Text("${target.name} — ${s.details}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${s.totalRequests}: ${pMetrics.size}")
                    Text("${s.errorCount}: ${pMetrics.count { !it.success }}")
                    Text("${s.avgLatency}: ${if (pMetrics.isEmpty()) "—" else "${pMetrics.map { it.latencyMs }.average().toLong()}ms"}")
                    target.lastError?.let {
                        HorizontalDivider()
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { errorDetailProvider = null }) { Text(s.close) }
            }
        )
    }
}

@Composable
private fun FetchedModelsPickerDialog(
    prompt: FetchedModelsPrompt,
    onSelectModel: (ModelEntity) -> Unit,
    onToggleEnabled: (ModelEntity) -> Unit,
    onDismiss: () -> Unit
) {
    val s = LocalStrings.current
    var search by remember(prompt.provider.id) { mutableStateOf("") }
    val filtered = remember(prompt.models, search) {
        prompt.models.filter { m ->
            search.isBlank() ||
                m.modelId.contains(search, ignoreCase = true) ||
                m.displayName.contains(search, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(s.fetchedModelsTitle)
                Text(
                    "${prompt.provider.name} (${prompt.models.size})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text(s.search) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("fetched_models_search_input")
                )
                if (filtered.isEmpty()) {
                    Text(s.noFetchedModelsMatch, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filtered, key = { it.id }) { model ->
                            val isSelected = model.isDefault || prompt.provider.defaultModel.equals(model.modelId, ignoreCase = true)
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                tonalElevation = if (isSelected) 3.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = model.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = model.modelId,
                                            style = MonoTextStyle,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Switch(
                                        checked = model.enabled,
                                        onCheckedChange = { onToggleEnabled(model) }
                                    )
                                    FilledTonalButton(
                                        onClick = { onSelectModel(model) },
                                        modifier = Modifier.testTag("select_fetched_model_${model.modelId}")
                                    ) {
                                        Text(if (isSelected) s.activeModelBadge else s.selectModelAction)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, modifier = Modifier.testTag("fetched_models_done_btn")) {
                Text(s.finish)
            }
        }
    )
}

@Composable
private fun ProviderEditorDialog(
    existing: ProviderEntity?,
    onFetchDraftModels: suspend (String?, String, String, ApiFormat, String, String) -> Result<List<ModelEntity>>,
    onDismiss: () -> Unit,
    onSave: (String, ProviderType, String, String, ApiFormat, String, Int, Boolean, String, String, String) -> Unit
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    var selectedPreset by remember { mutableStateOf(ProviderPresets.all.firstOrNull { it.id == existing?.presetId } ?: ProviderPresets.all.first()) }
    var name by remember { mutableStateOf(existing?.name ?: selectedPreset.name) }
    var type by remember { mutableStateOf(existing?.type ?: selectedPreset.type) }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl ?: selectedPreset.baseUrl) }
    var format by remember { mutableStateOf(existing?.apiFormat ?: selectedPreset.apiFormat) }
    var defaultModel by remember { mutableStateOf(existing?.defaultModel ?: selectedPreset.defaultModel) }
    var priorityText by remember { mutableStateOf((existing?.priority ?: 10).toString()) }
    var enabled by remember { mutableStateOf(existing?.enabled ?: true) }
    var customHeaders by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }
    var apiKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }

    var isFetchingDraft by remember { mutableStateOf(false) }
    var draftFetchError by remember { mutableStateOf<String?>(null) }
    var draftModels by remember { mutableStateOf<List<ModelEntity>>(emptyList()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) s.addProvider else s.editProvider) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (existing == null) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(ProviderPresets.all) { preset ->
                            FilterChip(
                                selected = selectedPreset.id == preset.id,
                                onClick = {
                                    selectedPreset = preset
                                    name = preset.name
                                    type = preset.type
                                    baseUrl = preset.baseUrl
                                    format = preset.apiFormat
                                    defaultModel = preset.defaultModel
                                    notes = preset.notes
                                    draftModels = emptyList()
                                    draftFetchError = null
                                },
                                label = { Text(preset.name) }
                            )
                        }
                    }
                }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(s.providerName) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("provider_name_input"))
                OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text(s.apiBaseUrl) }, textStyle = MonoTextStyle, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("provider_url_input"))

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(if (existing == null) s.apiKeyLabel else "${s.apiKeyLabel} (${s.savedLeaveBlankHint})") },
                    textStyle = MonoTextStyle,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = s.showHideSecretLabel)
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("provider_key_input")
                )

                Text(s.apiFormat, style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(ApiFormat.entries) { f ->
                        FilterChip(selected = format == f, onClick = { format = f }, label = { Text(f.name) })
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = defaultModel,
                        onValueChange = { defaultModel = it },
                        label = { Text("${s.defaultModel} (${s.optional})") },
                        textStyle = MonoTextStyle,
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("provider_default_model_input")
                    )
                    OutlinedButton(
                        enabled = !isFetchingDraft && baseUrl.startsWith("http"),
                        onClick = {
                            scope.launch {
                                isFetchingDraft = true
                                draftFetchError = null
                                val res = onFetchDraftModels(existing?.id, name, baseUrl, format, apiKey, customHeaders)
                                res.fold(
                                    onSuccess = { fetched ->
                                        draftModels = fetched
                                        if (defaultModel.isBlank()) {
                                            defaultModel = fetched.firstOrNull { it.enabled }?.modelId ?: fetched.firstOrNull()?.modelId.orEmpty()
                                        }
                                    },
                                    onFailure = { err ->
                                        draftFetchError = err.message ?: s.error
                                    }
                                )
                                isFetchingDraft = false
                            }
                        },
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("editor_fetch_models_btn")
                    ) {
                        if (isFetchingDraft) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Download, contentDescription = s.fetchModels, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                draftFetchError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                if (draftModels.isNotEmpty()) {
                    Text("${s.fetchedModelsTitle} (${draftModels.size})", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(draftModels.filter { it.enabled }.ifEmpty { draftModels }.take(30), key = { it.id }) { m ->
                            FilterChip(
                                selected = defaultModel.equals(m.modelId, ignoreCase = true),
                                onClick = { defaultModel = m.modelId },
                                label = { Text(m.modelId, style = MonoTextStyle) },
                                modifier = Modifier.testTag("editor_pick_model_${m.modelId}")
                            )
                        }
                    }
                }

                OutlinedTextField(value = priorityText, onValueChange = { priorityText = it.filter { c -> c.isDigit() } }, label = { Text(s.priorityLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = customHeaders, onValueChange = { customHeaders = it }, label = { Text("${s.customHeaders} (${s.optional})") }, textStyle = MonoTextStyle, minLines = 2, maxLines = 3, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("${s.notes} (${s.optional})") }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = enabled, onCheckedChange = { enabled = it })
                    Text(s.enabled)
                }
                validationError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isBlank() || !baseUrl.startsWith("http")) {
                        validationError = s.requiredField
                    } else {
                        onSave(name, type, selectedPreset.id, baseUrl, format, defaultModel, priorityText.toIntOrNull() ?: 10, enabled, customHeaders, notes, apiKey)
                    }
                },
                modifier = Modifier.testTag("save_provider_btn")
            ) { Text(s.save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } }
    )
}

@Composable
fun VaultScreen(viewModel: GatewayViewModel) {
    val s = LocalStrings.current
    val keys by viewModel.apiKeys.collectAsState()
    val providers by viewModel.providers.collectAsState()
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
                    val providerName = providers.firstOrNull { it.id == keyItem.providerId }?.name ?: s.unknownProviderLabel
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
