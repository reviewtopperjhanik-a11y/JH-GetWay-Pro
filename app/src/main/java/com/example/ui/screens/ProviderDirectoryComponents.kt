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
import com.example.network.ProviderPreset
import com.example.network.ProviderPresets
import com.example.ui.FetchedModelsPrompt
import com.example.ui.GatewayViewModel
import com.example.ui.components.*
import com.example.ui.theme.MonoTextStyle
import kotlinx.coroutines.launch

@Composable
internal fun ProviderApiConfigCard(
    provider: ProviderEntity,
    providerBadge: String?,
    keysCount: Int,
    requestsCount: Int,
    isFetching: Boolean,
    onTestConnection: () -> Unit,
    onFetchModels: () -> Unit,
    onManageKeys: () -> Unit,
    onToggleEnabled: () -> Unit,
    onEdit: () -> Unit,
    onViewDetails: () -> Unit,
    onDelete: () -> Unit
) {
    val s = LocalStrings.current
    val (statusLabel, statusTone) = providerStatusLabelAndTone(provider.status)

    GatewayCard(modifier = Modifier.testTag("provider_api_card_${provider.id}")) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(provider.name, style = MaterialTheme.typography.titleMedium)
                if (!providerBadge.isNullOrBlank()) {
                    Text(
                        text = providerBadge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = "${provider.apiFormat.name} • P${provider.priority} • $keysCount ${s.keysUnit} • $requestsCount ${s.requestsUnit}",
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
                onClick = onTestConnection,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("test_provider_${provider.id}")
            ) {
                Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(s.testConnection)
            }
            OutlinedButton(
                enabled = !isFetching,
                onClick = onFetchModels,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("fetch_models_${provider.id}")
            ) {
                if (isFetching) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(4.dp))
                Text(if (isFetching) s.fetchingModelsLabel else s.fetchModels)
            }
            OutlinedButton(
                onClick = onManageKeys,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("manage_keys_${provider.id}")
            ) {
                Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(s.manageKeysAction)
            }
            IconButton(
                onClick = onToggleEnabled,
                modifier = Modifier.testTag("toggle_provider_${provider.id}")
            ) {
                Icon(
                    imageVector = if (provider.enabled) Icons.Default.ToggleOn else Icons.Default.ToggleOff,
                    contentDescription = if (provider.enabled) s.disable else s.enable,
                    tint = if (provider.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onEdit,
                modifier = Modifier.testTag("edit_provider_${provider.id}")
            ) {
                Icon(Icons.Default.Edit, contentDescription = s.edit)
            }
            IconButton(
                onClick = onViewDetails,
                modifier = Modifier.testTag("view_details_${provider.id}")
            ) {
                Icon(Icons.Default.Info, contentDescription = s.details)
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.testTag("delete_provider_${provider.id}")
            ) {
                Icon(Icons.Default.DeleteOutline, contentDescription = s.delete, tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
internal fun ProviderApiManagementDialogs(
    viewModel: GatewayViewModel,
    metrics: List<RequestMetricEntity>,
    keys: List<ApiKeyEntity>,
    showEditor: Boolean,
    editingProvider: ProviderEntity?,
    initialPresetId: String?,
    hidePresetSelector: Boolean,
    templateProvider: ProviderEntity?,
    managingKeysProvider: ProviderEntity?,
    deletingProvider: ProviderEntity?,
    detailProvider: ProviderEntity?,
    fetchedModelsPrompt: FetchedModelsPrompt?,
    onDismissEditor: () -> Unit,
    onDismissManageKeys: () -> Unit,
    onDismissDelete: () -> Unit,
    onDismissDetails: () -> Unit
) {
    val s = LocalStrings.current

    if (showEditor) {
        ProviderEditorDialog(
            existing = editingProvider,
            initialPresetId = initialPresetId,
            hidePresetSelector = hidePresetSelector,
            templateProvider = templateProvider,
            onFetchDraftModels = { id, pName, url, fmt, keyInput, headersText ->
                viewModel.fetchModelsForDraft(id, pName, url, fmt, keyInput, headersText)
            },
            onDismiss = onDismissEditor,
            onSave = { name, type, presetId, url, format, model, prio, enabled, headers, notes, key ->
                viewModel.saveProvider(editingProvider?.id, name, type, presetId, url, format, model, prio, enabled, headers, notes, key)
                onDismissEditor()
            }
        )
    }

    managingKeysProvider?.let { target ->
        val providerKeys = keys.filter { it.providerId == target.id }
        ProviderKeysDialog(
            provider = target,
            providerKeys = providerKeys,
            onAddKey = { label, plainKey, priority, isDefault, policy ->
                viewModel.addApiKey(target.id, label, plainKey, priority, isDefault, policy)
            },
            onRotateKey = { k, newKey -> viewModel.rotateApiKey(k, newKey) },
            onTestKey = { k -> viewModel.testApiKey(k) },
            onSetDefaultKey = { k -> viewModel.setDefaultApiKey(k) },
            onToggleKeyEnabled = { k -> viewModel.toggleApiKeyEnabled(k) },
            onDeleteKey = { k -> viewModel.deleteApiKey(k) },
            onDismiss = onDismissManageKeys
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
            onConfirm = { viewModel.deleteProvider(target); onDismissDelete() },
            onDismiss = onDismissDelete
        )
    }

    detailProvider?.let { target ->
        val pMetrics = metrics.filter { it.providerId == target.id }
        val pKeys = keys.count { it.providerId == target.id }
        AlertDialog(
            onDismissRequest = onDismissDetails,
            title = { Text("${target.name} — ${s.details}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${s.apiBaseUrl}: ${target.baseUrl}", style = MonoTextStyle)
                    Text("${s.apiFormat}: ${target.apiFormat.name} • P${target.priority}")
                    Text("${s.defaultModel}: ${target.defaultModel.ifBlank { s.notSelectedLabel }}", style = MonoTextStyle)
                    Text("${s.keysUnit}: $pKeys • ${s.totalRequests}: ${pMetrics.size}")
                    Text("${s.errorCount}: ${pMetrics.count { !it.success }}")
                    Text("${s.avgLatency}: ${if (pMetrics.isEmpty()) "—" else "${pMetrics.map { it.latencyMs }.average().toLong()}ms"}")
                    if (target.notes.isNotBlank()) {
                        Text("${s.notes}: ${target.notes}", style = MaterialTheme.typography.bodySmall)
                    }
                    target.lastError?.let {
                        HorizontalDivider()
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissDetails) { Text(s.close) }
            }
        )
    }
}

@Composable
internal fun ProviderKeysDialog(
    provider: ProviderEntity,
    providerKeys: List<ApiKeyEntity>,
    onAddKey: (String, String, Int, Boolean, KeySelectionPolicy) -> Unit,
    onRotateKey: (ApiKeyEntity, String) -> Unit,
    onTestKey: (ApiKeyEntity) -> Unit,
    onSetDefaultKey: (ApiKeyEntity) -> Unit,
    onToggleKeyEnabled: (ApiKeyEntity) -> Unit,
    onDeleteKey: (ApiKeyEntity) -> Unit,
    onDismiss: () -> Unit
) {
    val s = LocalStrings.current
    var showAddForm by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf("") }
    var plainKey by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf("1") }
    var isDefault by remember { mutableStateOf(providerKeys.isEmpty()) }
    var policy by remember { mutableStateOf(KeySelectionPolicy.PRIORITY) }
    var rotatingKey by remember { mutableStateOf<ApiKeyEntity?>(null) }
    var newKeyText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("${provider.name} — ${s.manageKeysAction}")
                Text(
                    text = "${providerKeys.size} ${s.keysUnit}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (providerKeys.isEmpty()) {
                    Text(
                        text = s.noKeysMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    providerKeys.forEach { keyItem ->
                        val (statusTxt, tone) = keyStatusLabelAndTone(keyItem.status)
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            tonalElevation = 1.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(keyItem.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                    StatusPill(text = statusTxt, tone = tone)
                                }
                                Text(keyItem.maskedPreview, style = MonoTextStyle, color = MaterialTheme.colorScheme.primary)
                                Text(
                                    "${keyItem.selectionPolicy.name} • P${keyItem.priority} • ${s.requestsUnit}: ${keyItem.usageCount}" +
                                        if (keyItem.isDefault) " • ${s.defaultBadge}" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(onClick = { onTestKey(keyItem) }) { Text(s.test) }
                                    OutlinedButton(onClick = { rotatingKey = keyItem; newKeyText = "" }) { Text(s.rotateKey) }
                                    if (!keyItem.isDefault) {
                                        TextButton(onClick = { onSetDefaultKey(keyItem) }) { Text(s.setDefaultKey) }
                                    }
                                    IconButton(onClick = { onToggleKeyEnabled(keyItem) }) {
                                        Icon(
                                            imageVector = if (keyItem.enabled) Icons.Default.ToggleOn else Icons.Default.ToggleOff,
                                            contentDescription = if (keyItem.enabled) s.disable else s.enable
                                        )
                                    }
                                    IconButton(onClick = { onDeleteKey(keyItem) }) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = s.delete, tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }

                rotatingKey?.let { targetKey ->
                    HorizontalDivider()
                    Text("${s.rotateKey}: ${targetKey.label}", style = MaterialTheme.typography.labelMedium)
                    OutlinedTextField(
                        value = newKeyText,
                        onValueChange = { newKeyText = it },
                        label = { Text(s.apiKeyLabel) },
                        textStyle = MonoTextStyle,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (newKeyText.isNotBlank()) {
                                    onRotateKey(targetKey, newKeyText)
                                    rotatingKey = null
                                    newKeyText = ""
                                }
                            }
                        ) { Text(s.save) }
                        TextButton(onClick = { rotatingKey = null }) { Text(s.cancel) }
                    }
                }

                if (showAddForm) {
                    HorizontalDivider()
                    OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text(s.keyLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = plainKey, onValueChange = { plainKey = it }, label = { Text(s.apiKeyLabel) }, textStyle = MonoTextStyle, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = priority, onValueChange = { priority = it.filter { c -> c.isDigit() } }, label = { Text(s.priorityLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(KeySelectionPolicy.entries) { pol ->
                            FilterChip(selected = policy == pol, onClick = { policy = pol }, label = { Text(pol.name) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = isDefault, onCheckedChange = { isDefault = it })
                        Text(s.setDefaultKey)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (plainKey.isNotBlank()) {
                                    onAddKey(label, plainKey, priority.toIntOrNull() ?: 1, isDefault, policy)
                                    label = ""
                                    plainKey = ""
                                    showAddForm = false
                                }
                            },
                            modifier = Modifier.testTag("scoped_save_key_btn")
                        ) { Text(s.save) }
                        TextButton(onClick = { showAddForm = false }) { Text(s.cancel) }
                    }
                } else {
                    OutlinedButton(
                        onClick = { showAddForm = true },
                        modifier = Modifier.fillMaxWidth().testTag("scoped_add_key_btn")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(s.addKey)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(s.close) }
        }
    )
}

@Composable
internal fun FetchedModelsPickerDialog(
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
internal fun ProviderEditorDialog(
    existing: ProviderEntity?,
    initialPresetId: String? = null,
    hidePresetSelector: Boolean = false,
    templateProvider: ProviderEntity? = null,
    onFetchDraftModels: suspend (String?, String, String, ApiFormat, String, String) -> Result<List<ModelEntity>>,
    onDismiss: () -> Unit,
    onSave: (String, ProviderType, String, String, ApiFormat, String, Int, Boolean, String, String, String) -> Unit
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val defaultPreset: ProviderPreset = remember(existing?.id, initialPresetId, templateProvider?.id) {
        ProviderPresets.all.firstOrNull { it.id == (existing?.presetId ?: initialPresetId ?: templateProvider?.presetId) }
            ?: ProviderPresets.all.first()
    }
    var selectedPreset by remember { mutableStateOf(defaultPreset) }
    var name by remember { mutableStateOf(existing?.name ?: templateProvider?.name ?: selectedPreset.name) }
    var type by remember { mutableStateOf(existing?.type ?: templateProvider?.type ?: selectedPreset.type) }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl ?: templateProvider?.baseUrl ?: selectedPreset.baseUrl) }
    var format by remember { mutableStateOf(existing?.apiFormat ?: templateProvider?.apiFormat ?: selectedPreset.apiFormat) }
    var defaultModel by remember { mutableStateOf(existing?.defaultModel ?: templateProvider?.defaultModel ?: selectedPreset.defaultModel) }
    var priorityText by remember { mutableStateOf((existing?.priority ?: 10).toString()) }
    var enabled by remember { mutableStateOf(existing?.enabled ?: true) }
    var customHeaders by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf(existing?.notes ?: templateProvider?.notes ?: "") }
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
                if (existing == null && !hidePresetSelector) {
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
