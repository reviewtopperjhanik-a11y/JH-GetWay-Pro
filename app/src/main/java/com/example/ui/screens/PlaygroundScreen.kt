@file:OptIn(ExperimentalLayoutApi::class)

package com.example.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.i18n.AppStrings
import com.example.core.i18n.LocalStrings
import com.example.data.local.*
import com.example.ui.GatewayViewModel
import com.example.ui.components.*
import com.example.ui.navigation.Routes
import com.example.ui.theme.MonoTextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun PlaygroundScreen(
    viewModel: GatewayViewModel,
    onNavigate: (String) -> Unit
) {
    val s = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    val settings by viewModel.settings.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val localServers by viewModel.localServers.collectAsState()
    val models by viewModel.models.collectAsState()
    val eligibleFreeCount by viewModel.eligibleAutoFreeCount.collectAsState()
    val playgroundTarget by viewModel.playgroundTarget.collectAsState()
    val sessions by viewModel.playgroundSessions.collectAsState()
    val activeSessionId by viewModel.activeSessionId.collectAsState()
    val savedMessages by viewModel.activeSessionMessages.collectAsState()
    val ephemeralMessages by viewModel.ephemeralMessages.collectAsState()
    val streamingBuffer by viewModel.streamingBuffer.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()
    val compareState by viewModel.compareState.collectAsState()

    val messages = if (settings.saveChatHistoryAllowed) savedMessages else ephemeralMessages

    var compareMode by rememberSaveable { mutableStateOf(false) }
    var isAutoMode by rememberSaveable { mutableStateOf(true) }
    var selectedProviderId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedModelId by rememberSaveable { mutableStateOf("") }
    var systemPrompt by rememberSaveable { mutableStateOf("") }
    var promptInput by rememberSaveable { mutableStateOf("") }
    var pendingAttachments by remember { mutableStateOf<List<PendingAttachment>>(emptyList()) }
    var inlineError by remember { mutableStateOf<String?>(null) }
    var pendingCameraPath by rememberSaveable { mutableStateOf<String?>(null) }

    var showAttachSheet by remember { mutableStateOf(false) }
    var showModelSheet by remember { mutableStateOf(false) }
    var showHistorySheet by remember { mutableStateOf(false) }
    var showParamsSheet by remember { mutableStateOf(false) }
    var customModelDialogVisible by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

    var temperature by rememberSaveable { mutableFloatStateOf(0.7f) }
    var topP by rememberSaveable { mutableFloatStateOf(0.95f) }
    var maxTokensText by rememberSaveable { mutableStateOf("1024") }
    var stopSeqText by rememberSaveable { mutableStateOf("") }
    var streamingEnabled by rememberSaveable { mutableStateOf(true) }
    var jsonOutput by rememberSaveable { mutableStateOf(false) }

    val enabledProviderIds = remember(providers, localServers) {
        buildSet {
            providers.filter { it.enabled }.forEach { add(it.id) }
            localServers.filter { it.enabled }.forEach { add("local:${it.id}") }
        }
    }

    val compareAvailableModels = remember(compareMode, models, localServers, enabledProviderIds) {
        if (!compareMode) {
            emptyList()
        } else {
            val dbModels = models.filter { it.enabled && it.supportsChat && it.providerId in enabledProviderIds }
            val localFallbacks = localServers.filter { it.enabled && it.defaultModel.isNotBlank() }.mapNotNull { srv ->
                val lid = "local:${srv.id}"
                val hasDbLocal = dbModels.any { it.providerId == lid }
                if (hasDbLocal) null
                else ModelEntity(
                    id = "$lid::${srv.defaultModel}",
                    modelId = srv.defaultModel,
                    displayName = srv.defaultModel,
                    providerId = lid,
                    providerName = srv.name,
                    isLocal = true,
                    accessTier = AccessTier.LOCAL
                )
            }
            dbModels + localFallbacks
        }
    }
    val compareAvailableById = remember(compareAvailableModels) {
        if (compareAvailableModels.isEmpty()) emptyMap() else compareAvailableModels.associateBy { it.id }
    }
    val compareSelectedSet = remember(compareState.selectedModelKeys) { compareState.selectedModelKeys.toSet() }
    val compareSelectedModels = remember(compareMode, compareState.selectedModelKeys, compareAvailableById, compareState.entries) {
        if (!compareMode && compareState.selectedModelKeys.isEmpty()) {
            emptyList()
        } else {
            compareState.selectedModelKeys.mapNotNull { key ->
                compareAvailableById[key] ?: compareState.entries.firstOrNull { it.entryKey == key }?.let { entry ->
                    ModelEntity(
                        id = entry.entryKey,
                        modelId = entry.modelId,
                        displayName = entry.displayName,
                        providerId = entry.providerId,
                        providerName = entry.providerName,
                        supportsStreaming = entry.supportsStreaming
                    )
                }
            }
        }
    }

    LaunchedEffect(playgroundTarget) {
        val target = playgroundTarget
        if (target != null) {
            isAutoMode = false
            selectedProviderId = target.providerId?.takeIf { it in enabledProviderIds }
            selectedModelId = target.modelId
            viewModel.consumePlaygroundTarget()
        }
    }

    LaunchedEffect(selectedProviderId, enabledProviderIds) {
        if (!isAutoMode && selectedProviderId != null && selectedProviderId !in enabledProviderIds) {
            isAutoMode = true
            selectedProviderId = null
            selectedModelId = ""
        }
    }

    val selectedModelEntity = remember(isAutoMode, models, selectedProviderId, selectedModelId) {
        if (isAutoMode || selectedModelId.isBlank()) {
            null
        } else {
            models.firstOrNull {
                (selectedProviderId == null || it.providerId == selectedProviderId) &&
                    it.modelId.equals(selectedModelId, ignoreCase = true)
            }
        }
    }

    fun mapImportErrorMessage(err: AttachmentImportError): String = when (err) {
        AttachmentImportError.UNSUPPORTED_TYPE -> s.unsupportedFileTypeError
        AttachmentImportError.FILE_TOO_LARGE -> s.fileTooLargeError
        AttachmentImportError.LIMIT_EXCEEDED -> s.attachmentLimitError
        AttachmentImportError.READ_FAILED -> s.attachmentReadError
    }

    fun handleImportedUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            var currentList = pendingAttachments
            var firstErr: String? = null
            for (uri in uris) {
                val res = withContext(Dispatchers.IO) {
                    PlaygroundAttachmentManager.importPickedUri(
                        context = context,
                        uri = uri,
                        saveHistory = settings.saveChatHistoryAllowed,
                        existingCount = currentList.size,
                        existingTotalBytes = currentList.sumOf { it.sizeBytes }
                    )
                }
                when (res) {
                    is AttachmentImportResult.Success -> currentList = currentList + res.attachment
                    is AttachmentImportResult.Error -> {
                        if (firstErr == null) firstErr = mapImportErrorMessage(res.reason)
                    }
                }
            }
            pendingAttachments = currentList
            inlineError = firstErr
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        val camFile = pendingCameraPath?.let { File(it) }
        pendingCameraPath = null
        if (!captured || camFile == null) {
            camFile?.let { runCatching { it.delete() } }
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                PlaygroundAttachmentManager.importCameraFile(
                    context = context,
                    sourceFile = camFile,
                    saveHistory = settings.saveChatHistoryAllowed,
                    existingCount = pendingAttachments.size,
                    existingTotalBytes = pendingAttachments.sumOf { it.sizeBytes }
                )
            }
            when (res) {
                is AttachmentImportResult.Success -> {
                    pendingAttachments = pendingAttachments + res.attachment
                    inlineError = null
                }
                is AttachmentImportResult.Error -> {
                    inlineError = mapImportErrorMessage(res.reason)
                }
            }
        }
    }

    val photosLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(PlaygroundAttachmentManager.MAX_ATTACHMENTS)
    ) { uris ->
        handleImportedUris(uris)
    }

    val filesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        handleImportedUris(uris)
    }

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, isStreaming, compareMode) {
        val totalItems = messages.size + if (isStreaming) 1 else 0
        if (!compareMode && totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }
    LaunchedEffect(isStreaming, compareMode) {
        if (!isStreaming || compareMode) return@LaunchedEffect
        snapshotFlow { streamingBuffer.length }
            .collect {
                val totalItems = messages.size + 1
                val layoutInfo = listState.layoutInfo
                val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()
                val nearBottom = lastVisible == null || lastVisible.index >= (totalItems - 2)
                if (nearBottom) {
                    if (lastVisible != null && lastVisible.index == totalItems - 1) {
                        val overflow = (lastVisible.offset + lastVisible.size) - layoutInfo.viewportEndOffset
                        if (overflow > 0) {
                            listState.scrollBy(overflow.toFloat())
                        }
                    } else {
                        listState.scrollToItem(totalItems - 1)
                    }
                }
                delay(120)
            }
    }

    fun dispatchChatSend(promptText: String, attachmentsToSend: List<PendingAttachment>) {
        inlineError = null
        viewModel.sendPlaygroundPrompt(
            prompt = promptText,
            systemPrompt = systemPrompt,
            modelIdOrAlias = selectedModelId,
            explicitProviderId = selectedProviderId,
            temperature = temperature,
            topP = topP,
            maxTokens = maxTokensText.toIntOrNull() ?: 1024,
            stopSequences = stopSeqText.split(",").map { it.trim() }.filter { it.isNotBlank() },
            jsonOutput = jsonOutput,
            streaming = streamingEnabled,
            isAutoMode = isAutoMode,
            attachments = attachmentsToSend,
            onRequestAccepted = {
                if (attachmentsToSend == pendingAttachments) {
                    pendingAttachments = emptyList()
                }
                if (promptText == promptInput) {
                    promptInput = ""
                }
            },
            onValidationError = { err ->
                inlineError = err
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        // Compact Top Utility Bar (no Provider/Model LazyRows)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = !compareMode,
                    onClick = { compareMode = false },
                    label = { Text(s.singleChatMode, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.testTag("playground_chat_mode")
                )
                FilterChip(
                    selected = compareMode,
                    onClick = { compareMode = true },
                    label = { Text(s.multiModelCompareMode, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.testTag("playground_compare_mode")
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { showHistorySheet = true },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("playground_history_btn")
                ) {
                    Icon(Icons.Default.History, contentDescription = s.sessionHistory)
                }
                IconButton(
                    onClick = { viewModel.selectOrCreateSession(null) },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("playground_new_chat_btn")
                ) {
                    Icon(Icons.Default.AddComment, contentDescription = s.newChat)
                }
                Box {
                    IconButton(
                        onClick = { showMoreMenu = true },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("playground_more_btn")
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = s.settingsTitle)
                    }
                    DropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(s.parameters) },
                            leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null) },
                            onClick = { showMoreMenu = false; showParamsSheet = true }
                        )
                        DropdownMenuItem(
                            text = { Text(s.clearChat) },
                            leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null) },
                            onClick = { showMoreMenu = false; viewModel.clearActiveChat() }
                        )
                        DropdownMenuItem(
                            text = { Text(s.exportChat) },
                            leadingIcon = { Icon(Icons.Default.IosShare, contentDescription = null) },
                            onClick = {
                                showMoreMenu = false
                                val exported = messages.joinToString("\n\n") { "[${it.role.uppercase()}] ${it.content}" }
                                clipboard.setText(AnnotatedString(exported))
                                viewModel.postBanner(s.copied)
                            }
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        // Main Chat / Empty / Compare Area
        if (providers.none { it.enabled } && localServers.none { it.enabled }) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                EmptyStatePanel(
                    icon = Icons.AutoMirrored.Filled.Chat,
                    title = s.noProvidersTitle,
                    message = s.playgroundNoActiveProviderHint,
                    actionLabel = s.addProvider,
                    onAction = { onNavigate(Routes.PROVIDERS) }
                )
            }
        } else if (!compareMode) {
            if (messages.isEmpty() && !isStreaming) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                        Text(s.emptyChatTitle, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            text = s.emptyChatSubtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val showFreePoolWarning = isAutoMode &&
                            (settings.defaultRoutingMode == RoutingMode.AUTO_FREE || !settings.autoAllowPaidModels) &&
                            eligibleFreeCount == 0
                        if (showFreePoolWarning) {
                            Spacer(Modifier.height(4.dp))
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(s.noEligibleFreeModels, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                                    Text(s.noEligibleFreeModelsHint, style = MaterialTheme.typography.bodySmall)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        OutlinedButton(onClick = { onNavigate(Routes.PROVIDERS) }) {
                                            Text(s.reviewProvidersLabel)
                                        }
                                        OutlinedButton(onClick = { onNavigate(Routes.MODELS) }) {
                                            Text(s.reviewModelsLabel)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                val lastAssistantId = remember(messages) { messages.lastOrNull { it.role != "user" }?.id }
                val lastUserMsg = remember(messages) { messages.lastOrNull { it.role == "user" } }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(messages, key = { it.id }) { msg ->
                        val isUser = msg.role == "user"
                        val isLastAssistant = !isUser && msg.id == lastAssistantId
                        if (isUser) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.End
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f),
                                    modifier = Modifier.widthIn(max = 310.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        val attachedMeta = remember(msg.attachmentsMetadataJson) {
                                            PlaygroundAttachmentManager.parseMetadataJson(msg.attachmentsMetadataJson)
                                        }
                                        if (attachedMeta.isNotEmpty()) {
                                            FlowRow(
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                attachedMeta.forEach { meta ->
                                                    Surface(
                                                        shape = RoundedCornerShape(8.dp),
                                                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = if (meta.category == AttachmentCategory.IMAGE) {
                                                                    Icons.Default.Image
                                                                } else Icons.Default.Description,
                                                                contentDescription = null,
                                                                modifier = Modifier.size(14.dp)
                                                            )
                                                            Text(
                                                                text = meta.displayName,
                                                                style = MaterialTheme.typography.labelSmall,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        if (msg.content.isNotBlank()) {
                                            Text(msg.content, style = MaterialTheme.typography.bodyLarge)
                                        }
                                    }
                                }
                            }
                        } else {
                            // Clean ChatGPT-style flat assistant block
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                val headerTitle = buildString {
                                    if (msg.providerName.isNotBlank()) append(msg.providerName)
                                    if (msg.modelId.isNotBlank()) {
                                        if (isNotEmpty()) append(" • ")
                                        append(msg.modelId)
                                    }
                                }.ifBlank { s.autoShortLabel }
                                Text(
                                    text = headerTitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (msg.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = msg.content,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (msg.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (msg.latencyMs != null) {
                                        Text(
                                            text = "${msg.latencyMs}ms • ${msg.inputTokens ?: 0}/${msg.outputTokens ?: 0} • ${formatCurrency(msg.estimatedCostUsd, settings.analyticsCurrency, s.unknown)}",
                                            style = MonoTextStyle,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                    } else {
                                        Spacer(Modifier.weight(1f))
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = {
                                                clipboard.setText(AnnotatedString(msg.content))
                                                viewModel.postBanner(s.copied)
                                            },
                                            modifier = Modifier
                                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                                .testTag("playground_copy_msg_${msg.id}")
                                        ) {
                                            Icon(
                                                Icons.Default.ContentCopy,
                                                contentDescription = s.copyResponseLabel,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        if (isLastAssistant && !isStreaming) {
                                            if (lastUserMsg != null && lastUserMsg.content.isNotBlank()) {
                                                IconButton(
                                                    onClick = { dispatchChatSend(lastUserMsg.content, emptyList()) },
                                                    modifier = Modifier
                                                        .minimumInteractiveComponentSize()
                                                        .testTag("playground_regenerate_btn")
                                                ) {
                                                    Icon(
                                                        Icons.Default.Refresh,
                                                        contentDescription = s.regenerate,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (isStreaming) {
                        item(key = "streaming_indicator") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Text(
                                        text = s.streamingIndicator,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                if (streamingBuffer.isNotBlank()) {
                                    Text(streamingBuffer, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Multi-Model Compare Mode — Swipe-Based Model Comparison Workspace
            PlaygroundCompareWorkspace(
                compareState = compareState,
                availableModels = compareAvailableModels,
                currency = settings.analyticsCurrency,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                onToggleModel = { model ->
                    val nextList = if (model.id in compareSelectedSet) {
                        compareSelectedModels.filterNot { it.id == model.id }
                    } else {
                        compareSelectedModels + model
                    }
                    viewModel.syncCompareModelSelection(nextList)
                },
                onClearSelection = { viewModel.syncCompareModelSelection(emptyList()) },
                onSelectEntry = { entryKey -> viewModel.selectCompareEntry(entryKey) },
                onToggleDetails = { entryKey -> viewModel.toggleCompareDetails(entryKey) },
                onRetryModel = { entryKey -> viewModel.retryCompareModel(entryKey) },
                onStopModel = { entryKey -> viewModel.stopCompareModel(entryKey) },
                onStopAll = { viewModel.stopAllCompareRequests() },
                onShowBanner = { msg -> viewModel.postBanner(msg) }
            )
        }

        // Inline Error Banner above Composer
        inlineError?.let { errMsg ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .testTag("playground_inline_error"),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(18.dp))
                    Text(
                        text = errMsg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { inlineError = null },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.Default.Close, contentDescription = s.close, tint = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }

        // ChatGPT-Style Bottom Rounded Composer
        Surface(
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 3.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (pendingAttachments.isNotEmpty()) {
                    AttachmentPreviewStrip(
                        attachments = pendingAttachments,
                        s = s,
                        onRemove = { att ->
                            pendingAttachments = pendingAttachments.filterNot { it.id == att.id }
                            PlaygroundAttachmentManager.deleteFileIfOwned(context, att.localFilePath)
                        }
                    )
                    Text(
                        text = s.cloudUploadDisclosure,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )
                }

                // Composer Row 1: [+]  [Expanding Multiline Input]  [Send / Stop]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (!compareMode) {
                        IconButton(
                            onClick = { showAttachSheet = true },
                            enabled = !isStreaming,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("playground_plus_btn")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = s.attachActionDesc)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp, max = 140.dp)
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (promptInput.isEmpty()) {
                            Text(
                                text = s.messageInputHint,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        BasicTextField(
                            value = promptInput,
                            onValueChange = { promptInput = it },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            maxLines = 5,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("playground_prompt_input")
                        )
                    }

                    val isActiveGeneration = if (compareMode) compareState.isRunning else isStreaming
                    if (isActiveGeneration) {
                        FilledIconButton(
                            onClick = {
                                if (compareMode) {
                                    viewModel.stopAllCompareRequests()
                                } else {
                                    viewModel.stopPlaygroundStreaming()
                                }
                            },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            ),
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("playground_stop_btn")
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = s.stopGenerating)
                        }
                    } else {
                        FilledIconButton(
                            onClick = {
                                if (promptInput.isBlank() && (compareMode || pendingAttachments.isEmpty())) {
                                    inlineError = s.emptyPromptError
                                    return@FilledIconButton
                                }
                                if (compareMode) {
                                    if (compareSelectedModels.isEmpty()) {
                                        inlineError = s.compareSelectModelsHint
                                        return@FilledIconButton
                                    }
                                    inlineError = null
                                    val textToCompare = promptInput
                                    promptInput = ""
                                    viewModel.runModelComparison(
                                        prompt = textToCompare,
                                        systemPrompt = systemPrompt,
                                        selectedModels = compareSelectedModels,
                                        temperature = temperature,
                                        topP = topP,
                                        maxTokens = maxTokensText.toIntOrNull() ?: 1024,
                                        stopSequences = stopSeqText.split(",").map { it.trim() }.filter { it.isNotBlank() },
                                        jsonOutput = jsonOutput,
                                        streaming = streamingEnabled
                                    )
                                } else {
                                    dispatchChatSend(promptInput, pendingAttachments)
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("playground_send_btn")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = s.send)
                        }
                    }
                }

                // Composer Row 2: [Model Selector Chip (Auto ▾ / model ▾)]  [attachment count / Params]
                if (!compareMode) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val chipText = if (isAutoMode || selectedModelId.isBlank()) {
                            s.autoShortLabel
                        } else {
                            selectedModelEntity?.displayName?.ifBlank { selectedModelId } ?: selectedModelId
                        }
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .widthIn(max = 220.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { showModelSheet = true }
                                .testTag("playground_model_selector_chip")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = if (isAutoMode) Icons.Default.AutoAwesome else Icons.Default.Memory,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = chipText,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = s.modelSelectorTitle,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (pendingAttachments.isNotEmpty()) {
                                Text(
                                    text = "${pendingAttachments.size} ${s.attachmentsCountLabel}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(
                                onClick = { showParamsSheet = true },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("playground_params_btn")
                            ) {
                                Icon(
                                    Icons.Default.Tune,
                                    contentDescription = s.parameters,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAttachSheet) {
        PlaygroundAttachBottomSheet(
            onDismiss = { showAttachSheet = false },
            onPickCamera = {
                val hasCamera = runCatching {
                    context.packageManager.resolveActivity(Intent(MediaStore.ACTION_IMAGE_CAPTURE), 0) != null
                }.getOrDefault(false)
                if (!hasCamera) {
                    showAttachSheet = false
                    inlineError = s.cameraUnavailableError
                    return@PlaygroundAttachBottomSheet
                }
                val target = runCatching {
                    PlaygroundAttachmentManager.createCameraCaptureTarget(context)
                }.getOrElse {
                    showAttachSheet = false
                    inlineError = s.cameraCaptureFailedError
                    return@PlaygroundAttachBottomSheet
                }
                pendingCameraPath = target.first.absolutePath
                showAttachSheet = false
                try {
                    cameraLauncher.launch(target.second)
                } catch (_: ActivityNotFoundException) {
                    pendingCameraPath = null
                    runCatching { target.first.delete() }
                    inlineError = s.cameraUnavailableError
                }
            },
            onPickPhotos = {
                showAttachSheet = false
                photosLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onPickFiles = {
                showAttachSheet = false
                filesLauncher.launch(
                    arrayOf(
                        "application/pdf",
                        "text/plain",
                        "text/markdown",
                        "text/csv",
                        "application/json",
                        "image/*"
                    )
                )
            }
        )
    }

    if (showModelSheet) {
        PlaygroundModelSelectorSheet(
            isAutoMode = isAutoMode,
            selectedProviderId = selectedProviderId,
            selectedModelId = selectedModelId,
            eligibleFreeCount = eligibleFreeCount,
            providers = providers,
            localServers = localServers,
            models = models,
            settings = settings,
            onToggleAutoFreeIncludeLocal = { viewModel.setAutoFreeIncludeLocal(it) },
            onToggleManualFallback = { viewModel.setManualModelFallbackEnabled(it) },
            onSelectAuto = {
                isAutoMode = true
                selectedProviderId = null
                selectedModelId = ""
                showModelSheet = false
            },
            onSelectModel = { provId, modId ->
                isAutoMode = false
                selectedProviderId = provId
                selectedModelId = modId
                showModelSheet = false
            },
            onOpenCustomModelDialog = {
                showModelSheet = false
                customModelDialogVisible = true
            },
            onNavigate = onNavigate,
            onDismiss = { showModelSheet = false }
        )
    }

    if (showHistorySheet) {
        PlaygroundHistoryBottomSheet(
            sessions = sessions,
            activeSessionId = activeSessionId,
            onSelectSession = { viewModel.selectOrCreateSession(it) },
            onDeleteSession = { viewModel.deleteSession(it) },
            onNewChat = { viewModel.selectOrCreateSession(null) },
            onDismiss = { showHistorySheet = false }
        )
    }

    if (showParamsSheet) {
        PlaygroundParamsBottomSheet(
            systemPrompt = systemPrompt,
            onSystemPromptChange = { systemPrompt = it },
            temperature = temperature,
            onTemperatureChange = { temperature = it },
            topP = topP,
            onTopPChange = { topP = it },
            maxTokensText = maxTokensText,
            onMaxTokensChange = { maxTokensText = it },
            stopSeqText = stopSeqText,
            onStopSeqChange = { stopSeqText = it },
            streamingEnabled = streamingEnabled,
            onStreamingChange = { streamingEnabled = it },
            streamSupported = selectedModelEntity?.supportsStreaming ?: true,
            jsonOutput = jsonOutput,
            onJsonOutputChange = { jsonOutput = it },
            jsonSupported = !selectedModelId.contains("cohere", true),
            onDismiss = { showParamsSheet = false }
        )
    }

    if (customModelDialogVisible) {
        var customInput by remember(selectedModelId) { mutableStateOf(selectedModelId) }
        AlertDialog(
            onDismissRequest = { customModelDialogVisible = false },
            title = { Text(s.modelOrAliasLabel) },
            text = {
                OutlinedTextField(
                    value = customInput,
                    onValueChange = { customInput = it },
                    label = { Text(s.customModelHint) },
                    textStyle = MonoTextStyle,
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("playground_custom_model_input")
                )
            },
            confirmButton = {
                Button(onClick = {
                    val trimmed = customInput.trim()
                    if (trimmed.isNotBlank()) {
                        isAutoMode = false
                        selectedModelId = trimmed
                    }
                    customModelDialogVisible = false
                }) { Text(s.confirm) }
            },
            dismissButton = {
                TextButton(onClick = { customModelDialogVisible = false }) { Text(s.cancel) }
            }
        )
    }
}

@Composable
private fun AttachmentPreviewStrip(
    attachments: List<PendingAttachment>,
    s: AppStrings,
    onRemove: (PendingAttachment) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
        modifier = Modifier.testTag("playground_attachment_preview_strip")
    ) {
        items(attachments, key = { it.id }) { att ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.widthIn(min = 120.dp, max = 200.dp)
            ) {
                Row(
                    modifier = Modifier.padding(start = 6.dp, top = 4.dp, bottom = 4.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (att.category == AttachmentCategory.IMAGE) {
                        val thumb by produceState<ImageBitmap?>(initialValue = null, att.localFilePath) {
                            value = withContext(Dispatchers.IO) {
                                decodeSampledBitmap(att.localFilePath)
                            }
                        }
                        if (thumb != null) {
                            Image(
                                bitmap = thumb!!,
                                contentDescription = att.displayName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(20.dp))
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(20.dp))
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = att.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = PlaygroundAttachmentManager.formatReadableSize(att.sizeBytes),
                            style = MonoTextStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }

                    IconButton(
                        onClick = { onRemove(att) },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("playground_remove_attachment_${att.id}")
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = s.removeAttachment,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun decodeSampledBitmap(path: String): ImageBitmap? = runCatching {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, opts)
    val maxDim = maxOf(opts.outWidth, opts.outHeight).coerceAtLeast(1)
    var sample = 1
    while (maxDim / sample > 256) sample *= 2
    val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeFile(path, decodeOpts)?.asImageBitmap()
}.getOrNull()
