@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.i18n.AppStrings
import com.example.core.i18n.LocalStrings
import com.example.data.local.*
import com.example.ui.components.StatusPill
import com.example.ui.components.StatusTone
import com.example.ui.navigation.Routes
import com.example.ui.theme.MonoTextStyle

@Composable
fun PlaygroundAttachBottomSheet(
    onDismiss: () -> Unit,
    onPickCamera: () -> Unit,
    onPickPhotos: () -> Unit,
    onPickFiles: () -> Unit
) {
    val s = LocalStrings.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("playground_attach_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(s.attachMenuTitle, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            AttachActionRow(
                icon = Icons.Default.PhotoCamera,
                title = s.cameraOption,
                subtitle = s.cameraSubtitle,
                testTag = "playground_attach_camera",
                onClick = onPickCamera
            )
            AttachActionRow(
                icon = Icons.Default.PhotoLibrary,
                title = s.photosOption,
                subtitle = s.photosSubtitle,
                testTag = "playground_attach_photos",
                onClick = onPickPhotos
            )
            AttachActionRow(
                icon = Icons.Default.AttachFile,
                title = s.filesOption,
                subtitle = s.filesSubtitle,
                testTag = "playground_attach_files",
                onClick = onPickFiles
            )
        }
    }
}

@Composable
private fun AttachActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun PlaygroundModelSelectorSheet(
    isAutoMode: Boolean,
    selectedProviderId: String?,
    selectedModelId: String,
    eligibleFreeCount: Int,
    providers: List<ProviderEntity>,
    localServers: List<LocalAiServerEntity>,
    models: List<ModelEntity>,
    settings: GatewaySettingsState,
    onToggleAutoFreeIncludeLocal: (Boolean) -> Unit,
    onToggleManualFallback: (Boolean) -> Unit,
    onSelectAuto: () -> Unit,
    onSelectModel: (providerId: String, modelId: String) -> Unit,
    onOpenCustomModelDialog: () -> Unit,
    onNavigate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val s = LocalStrings.current
    var searchQuery by remember { mutableStateOf("") }

    val enabledProviders = remember(providers) { providers.filter { it.enabled } }
    val enabledLocal = remember(localServers) { localServers.filter { it.enabled } }

    val providerGroups = remember(enabledProviders, models, searchQuery) {
        enabledProviders.mapNotNull { prov ->
            val dbModels = models.filter { it.providerId == prov.id && it.enabled && it.supportsChat }
            val withFallback = if (prov.defaultModel.isNotBlank() &&
                dbModels.none { it.modelId.equals(prov.defaultModel, true) }
            ) {
                dbModels + ModelEntity(
                    id = "${prov.id}::${prov.defaultModel}",
                    modelId = prov.defaultModel,
                    displayName = prov.defaultModel,
                    providerId = prov.id,
                    providerName = prov.name,
                    accessTier = if (prov.defaultModel.endsWith(":free", true)) AccessTier.FREE_TIER else AccessTier.UNKNOWN,
                    freeTierVerified = prov.defaultModel.endsWith(":free", true),
                    freeTierSource = if (prov.defaultModel.endsWith(":free", true)) "Provider Catalog (:free)" else ""
                )
            } else dbModels

            val filtered = withFallback.filter { m ->
                searchQuery.isBlank() ||
                    m.displayName.contains(searchQuery, true) ||
                    m.modelId.contains(searchQuery, true) ||
                    m.alias.contains(searchQuery, true) ||
                    prov.name.contains(searchQuery, true)
            }
            if (filtered.isEmpty() && searchQuery.isNotBlank()) null else (prov to filtered)
        }
    }

    val localModels = remember(enabledLocal, models, searchQuery) {
        enabledLocal.flatMap { srv ->
            val lid = "local:${srv.id}"
            val dbLocal = models.filter { it.providerId == lid && it.enabled && it.supportsChat }
            val list = if (dbLocal.isNotEmpty()) {
                dbLocal
            } else if (srv.defaultModel.isNotBlank()) {
                listOf(
                    ModelEntity(
                        id = "$lid::${srv.defaultModel}",
                        modelId = srv.defaultModel,
                        displayName = srv.defaultModel,
                        providerId = lid,
                        providerName = srv.name,
                        isLocal = true,
                        accessTier = AccessTier.LOCAL
                    )
                )
            } else emptyList()
            list.filter { m ->
                searchQuery.isBlank() ||
                    m.displayName.contains(searchQuery, true) ||
                    m.modelId.contains(searchQuery, true) ||
                    srv.name.contains(searchQuery, true)
            }.map { srv to it }
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("playground_model_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 580.dp)
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(s.modelSelectorTitle, style = MaterialTheme.typography.titleMedium)
                TextButton(
                    onClick = onOpenCustomModelDialog,
                    modifier = Modifier.testTag("playground_custom_model_btn")
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(s.modelOrAliasLabel)
                }
            }

            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(s.search) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("playground_model_search")
            )
            Spacer(Modifier.height(10.dp))

            LazyColumn(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .testTag("playground_model_list"),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Section A: Auto — Free Models
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        tonalElevation = if (isAutoMode) 4.dp else 1.dp,
                        color = if (isAutoMode) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onSelectAuto)
                            .testTag("playground_model_option_auto")
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Text(s.autoFreeTitle, style = MaterialTheme.typography.titleSmall)
                                }
                                if (isAutoMode) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Text(
                                text = s.autoFreeDescription,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            StatusPill(
                                text = "$eligibleFreeCount ${s.eligibleFreeModelsCount}",
                                tone = if (eligibleFreeCount > 0) StatusTone.SUCCESS else StatusTone.WARNING
                            )
                            if (eligibleFreeCount == 0) {
                                Text(
                                    text = s.noEligibleFreeModelsHint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { onDismiss(); onNavigate(Routes.PROVIDERS) }) {
                                        Text(s.reviewProvidersLabel)
                                    }
                                    OutlinedButton(onClick = { onDismiss(); onNavigate(Routes.MODELS) }) {
                                        Text(s.reviewModelsLabel)
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(s.autoFreeIncludeLocalLabel, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                Switch(
                                    checked = settings.autoFreeIncludeLocal,
                                    onCheckedChange = onToggleAutoFreeIncludeLocal,
                                    modifier = Modifier.testTag("playground_auto_include_local_switch")
                                )
                            }
                        }
                    }
                }

                // Fallback setting for manual selection
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(s.manualFallbackLabel, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Switch(
                            checked = settings.manualModelFallbackEnabled,
                            onCheckedChange = onToggleManualFallback,
                            modifier = Modifier.testTag("playground_manual_fallback_switch")
                        )
                    }
                }

                // Section B: Cloud Provider Models
                providerGroups.forEach { (provider, groupModels) ->
                    item(key = "header_${provider.id}") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp, bottom = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = provider.name,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            StatusPill(
                                text = provider.status.name,
                                tone = when (provider.status) {
                                    ProviderStatus.CONNECTED -> StatusTone.SUCCESS
                                    ProviderStatus.ERROR, ProviderStatus.RATE_LIMITED -> StatusTone.ERROR
                                    else -> StatusTone.NEUTRAL
                                }
                            )
                        }
                    }
                    items(groupModels, key = { "sheet_${it.id}" }) { model ->
                        val isSelected = !isAutoMode &&
                            selectedProviderId == model.providerId &&
                            selectedModelId.equals(model.modelId, ignoreCase = true)
                        ModelSelectorRow(
                            model = model,
                            isSelected = isSelected,
                            s = s,
                            onClick = { onSelectModel(model.providerId, model.modelId) }
                        )
                    }
                }

                // Local AI models group
                if (localModels.isNotEmpty()) {
                    item(key = "header_local_ai") {
                        Text(
                            text = s.localSectionTitle,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                        )
                    }
                    items(localModels, key = { "sheet_local_${it.second.id}" }) { (srv, model) ->
                        val isSelected = !isAutoMode &&
                            selectedProviderId == "local:${srv.id}" &&
                            selectedModelId.equals(model.modelId, ignoreCase = true)
                        ModelSelectorRow(
                            model = model,
                            isSelected = isSelected,
                            s = s,
                            onClick = { onSelectModel("local:${srv.id}", model.modelId) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelSelectorRow(
    model: ModelEntity,
    isSelected: Boolean,
    s: AppStrings,
    onClick: () -> Unit
) {
    val (tierLabel, tierTone) = when {
        model.isLocal || model.accessTier == AccessTier.LOCAL -> s.tierLocal to StatusTone.INFO
        model.accessTier == AccessTier.FREE_TIER && model.freeTierVerified && model.freeTierSource == "User Confirmed" ->
            s.tierUserConfirmed to StatusTone.INFO
        model.accessTier == AccessTier.FREE_TIER && model.freeTierVerified -> s.tierFree to StatusTone.SUCCESS
        model.accessTier == AccessTier.PAID -> s.tierPaid to StatusTone.WARNING
        else -> s.tierUnknown to StatusTone.NEUTRAL
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = if (isSelected) 3.dp else 0.dp,
        color = if (isSelected) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick)
            .testTag("playground_sheet_model_${model.modelId}")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatusPill(text = tierLabel, tone = tierTone)
                    if (model.supportsVision) {
                        StatusPill(text = s.visionBadge, tone = StatusTone.NEUTRAL)
                    }
                    if (model.supportsTools) {
                        StatusPill(text = s.toolsBadge, tone = StatusTone.NEUTRAL)
                    }
                }
            }
            if (isSelected) {
                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun PlaygroundHistoryBottomSheet(
    sessions: List<PlaygroundSessionEntity>,
    activeSessionId: String?,
    onSelectSession: (String) -> Unit,
    onDeleteSession: (PlaygroundSessionEntity) -> Unit,
    onNewChat: () -> Unit,
    onDismiss: () -> Unit
) {
    val s = LocalStrings.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("playground_history_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(s.sessionHistory, style = MaterialTheme.typography.titleMedium)
                FilledTonalButton(onClick = { onNewChat(); onDismiss() }) {
                    Icon(Icons.Default.AddComment, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(s.newChat)
                }
            }
            if (sessions.isEmpty()) {
                Text(
                    text = s.emptyChatSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(sessions, key = { it.id }) { sess ->
                        val active = sess.id == activeSessionId
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectSession(sess.id); onDismiss() }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = sess.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { onDeleteSession(sess) },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = s.delete)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PlaygroundParamsBottomSheet(
    systemPrompt: String,
    onSystemPromptChange: (String) -> Unit,
    temperature: Float,
    onTemperatureChange: (Float) -> Unit,
    topP: Float,
    onTopPChange: (Float) -> Unit,
    maxTokensText: String,
    onMaxTokensChange: (String) -> Unit,
    stopSeqText: String,
    onStopSeqChange: (String) -> Unit,
    streamingEnabled: Boolean,
    onStreamingChange: (Boolean) -> Unit,
    streamSupported: Boolean,
    jsonOutput: Boolean,
    onJsonOutputChange: (Boolean) -> Unit,
    jsonSupported: Boolean,
    onDismiss: () -> Unit
) {
    val s = LocalStrings.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(s.parameters, style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = systemPrompt,
                onValueChange = onSystemPromptChange,
                label = { Text(s.systemPromptLabel) },
                maxLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
            Text("${s.temperature}: ${String.format("%.2f", temperature)}", style = MaterialTheme.typography.labelMedium)
            Slider(value = temperature, onValueChange = onTemperatureChange, valueRange = 0f..2f)
            Text("${s.topP}: ${String.format("%.2f", topP)}", style = MaterialTheme.typography.labelMedium)
            Slider(value = topP, onValueChange = onTopPChange, valueRange = 0.05f..1f)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = maxTokensText,
                    onValueChange = { onMaxTokensChange(it.filter { c -> c.isDigit() }) },
                    label = { Text(s.maxOutputTokens) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = stopSeqText,
                    onValueChange = onStopSeqChange,
                    label = { Text(s.stopSequences) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = streamingEnabled && streamSupported,
                    enabled = streamSupported,
                    onCheckedChange = onStreamingChange
                )
                Spacer(Modifier.width(8.dp))
                Text(if (streamSupported) s.streamingLabel else "${s.streamingLabel} (${s.unsupportedParam})")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = jsonOutput && jsonSupported,
                    enabled = jsonSupported,
                    onCheckedChange = onJsonOutputChange
                )
                Spacer(Modifier.width(8.dp))
                Text(if (jsonSupported) s.jsonOutput else "${s.jsonOutput} (${s.unsupportedParam})")
            }
        }
    }
}
