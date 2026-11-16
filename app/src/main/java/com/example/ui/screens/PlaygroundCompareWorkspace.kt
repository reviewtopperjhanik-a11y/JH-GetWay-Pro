@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.example.ui.screens

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.i18n.AppStrings
import com.example.core.i18n.LocalStrings
import com.example.data.local.ModelEntity
import com.example.ui.CompareModelEntry
import com.example.ui.CompareRequestStatus
import com.example.ui.CompareWorkspaceState
import com.example.ui.components.StatusPill
import com.example.ui.components.StatusTone
import com.example.ui.components.formatCurrency
import com.example.ui.theme.MonoTextStyle
import com.example.ui.theme.StatusConnected
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusNeutral
import com.example.ui.theme.StatusWarning
import java.util.Locale

@Composable
fun PlaygroundCompareWorkspace(
    compareState: CompareWorkspaceState,
    availableModels: List<ModelEntity>,
    currency: String,
    modifier: Modifier = Modifier,
    onToggleModel: (ModelEntity) -> Unit,
    onClearSelection: () -> Unit,
    onSelectEntry: (String) -> Unit,
    onToggleDetails: (String) -> Unit,
    onRetryModel: (String) -> Unit,
    onStopModel: (String) -> Unit,
    onStopAll: () -> Unit,
    onShowBanner: (String) -> Unit
) {
    val s = LocalStrings.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var showModelPickerSheet by remember { mutableStateOf(false) }

    val entries = compareState.entries
    val selectedKeySet = remember(compareState.selectedModelKeys) { compareState.selectedModelKeys.toSet() }
    val activeIndex = remember(entries, compareState.activeEntryKey) {
        if (entries.isEmpty()) -1
        else entries.indexOfFirst { it.entryKey == compareState.activeEntryKey }.takeIf { it >= 0 } ?: 0
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize()
    ) {
        val compactHeight = maxHeight < 420.dp
        val activeEntryExpanded = entries.getOrNull(activeIndex)?.entryKey?.let { it in compareState.expandedDetailsKeys } == true

        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (compactHeight && entries.isNotEmpty() && compareState.prompt.isNotBlank()) Modifier.verticalScroll(rememberScrollState()) else Modifier)
        ) {
            if (entries.isEmpty() || compareState.prompt.isBlank()) {
                CompareEmptyWorkspace(
                    availableModels = availableModels,
                    selectedKeySet = selectedKeySet,
                    s = s,
                    onToggleModel = onToggleModel,
                    onClearSelection = onClearSelection,
                    onOpenModelPicker = { showModelPickerSheet = true },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            } else {
                // 2. Shared Prompt / Prompt Context + Model Selection / Stop Control
                CompareTopContextBar(
                    prompt = compareState.prompt,
                    selectedCount = entries.size,
                    isRunning = compareState.isRunning,
                    s = s,
                    onOpenModelPicker = { showModelPickerSheet = true },
                    onStopAll = onStopAll
                )

                // 3. Quick Model Navigation — ALWAYS immediately above progress & response card
                CompareQuickModelNavigation(
                    entries = entries,
                    activeEntryKey = entries.getOrNull(activeIndex)?.entryKey,
                    activeIndex = activeIndex,
                    s = s,
                    onSelectEntry = onSelectEntry
                )

                // 4. Model Navigation Progress Indicator
                CompareProgressIndicator(
                    activeIndex = activeIndex,
                    totalCount = entries.size,
                    s = s
                )

                // 5–8. Single-Card Swipeable Response Area (HorizontalPager)
                val pagerState = rememberPagerState(
                    initialPage = activeIndex.coerceAtLeast(0),
                    pageCount = { entries.size }
                )
                val reducedMotion = remember(context) { isReducedMotionEnabled(context) }
                var programmaticTargetPage by remember { mutableStateOf<Int?>(null) }
                val currentActiveKey by rememberUpdatedState(entries.getOrNull(activeIndex)?.entryKey)

                LaunchedEffect(activeIndex, entries.size) {
                    if (activeIndex in entries.indices && pagerState.currentPage != activeIndex) {
                        programmaticTargetPage = activeIndex
                        try {
                            if (reducedMotion || entries.size <= 1) {
                                pagerState.scrollToPage(activeIndex)
                            } else {
                                pagerState.animateScrollToPage(
                                    page = activeIndex,
                                    animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing)
                                )
                            }
                        } finally {
                            programmaticTargetPage = null
                        }
                    }
                }

                LaunchedEffect(pagerState, entries) {
                    snapshotFlow {
                        Triple(pagerState.settledPage, pagerState.isScrollInProgress, entries)
                    }.collect { (settled, scrolling, currentEntries) ->
                        if (!scrolling && programmaticTargetPage == null) {
                            val settledEntry = currentEntries.getOrNull(settled)
                            if (settledEntry != null && settledEntry.entryKey != currentActiveKey) {
                                onSelectEntry(settledEntry.entryKey)
                            }
                        }
                    }
                }

                val pagerBoxModifier = if (compactHeight) {
                    Modifier
                        .fillMaxWidth()
                        .height(if (activeEntryExpanded) 430.dp else 290.dp)
                } else {
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                }

                Box(
                    modifier = pagerBoxModifier,
                    contentAlignment = Alignment.TopCenter
                ) {
                    HorizontalPager(
                        state = pagerState,
                        key = { idx -> entries[idx].entryKey },
                        beyondViewportPageCount = 1,
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 840.dp)
                            .testTag("compare_pager")
                    ) { pageIndex ->
                        val entry = entries[pageIndex]
                        val isExpanded = entry.entryKey in compareState.expandedDetailsKeys
                        CompareModelResponseCard(
                            entry = entry,
                            positionNumber = pageIndex + 1,
                            hasActivePrompt = compareState.prompt.isNotBlank(),
                            isDetailsExpanded = isExpanded,
                            reducedMotion = reducedMotion,
                            currency = currency,
                            s = s,
                            onToggleDetails = { onToggleDetails(entry.entryKey) },
                            onCopyResponse = {
                                if (entry.responseText.isNotBlank()) {
                                    clipboard.setText(AnnotatedString(entry.responseText))
                                    onShowBanner(s.compareResponseCopied)
                                }
                            },
                            onRetry = { onRetryModel(entry.entryKey) },
                            onStopModel = { onStopModel(entry.entryKey) }
                        )
                    }
                }

                // 9. Previous / Next Supporting Navigation Controls
                ComparePreviousNextBar(
                    activeIndex = activeIndex,
                    entries = entries,
                    s = s,
                    onSelectEntry = onSelectEntry
                )
            }
        }
    }

    if (showModelPickerSheet) {
        CompareModelPickerSheet(
            availableModels = availableModels,
            selectedKeySet = selectedKeySet,
            s = s,
            onToggleModel = onToggleModel,
            onClearSelection = onClearSelection,
            onDismiss = { showModelPickerSheet = false }
        )
    }
}

@Composable
private fun CompareEmptyWorkspace(
    availableModels: List<ModelEntity>,
    selectedKeySet: Set<String>,
    s: AppStrings,
    onToggleModel: (ModelEntity) -> Unit,
    onClearSelection: () -> Unit,
    onOpenModelPicker: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag("compare_empty_state"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            tonalElevation = 1.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 680.dp)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.CompareArrows,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text(s.compareEmptyTitle, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (selectedKeySet.size >= 2) s.compareReadyHint else s.compareSelectModelsHint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selectedKeySet.size >= 2) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onOpenModelPicker,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("compare_choose_models_btn")
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (selectedKeySet.isEmpty()) s.compareChooseModelsAction
                            else "${s.compareChooseModelsAction} (${selectedKeySet.size})"
                        )
                    }
                    if (selectedKeySet.isNotEmpty()) {
                        OutlinedButton(
                            onClick = onClearSelection,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("compare_empty_clear_btn")
                        ) {
                            Text(s.compareClearSelectionAction)
                        }
                    }
                }
                if (availableModels.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Text(
                        text = "${s.selectModelsToCompareLabel} (${selectedKeySet.size})",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Start)
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        availableModels.take(12).forEach { model ->
                            val selected = model.id in selectedKeySet
                            FilterChip(
                                selected = selected,
                                onClick = { onToggleModel(model) },
                                label = {
                                    Text(
                                        text = "${model.providerName}: ${model.displayName.ifBlank { model.modelId }}",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                modifier = Modifier.testTag("compare_quick_pick_${model.id}")
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompareTopContextBar(
    prompt: String,
    selectedCount: Int,
    isRunning: Boolean,
    s: AppStrings,
    onOpenModelPicker: () -> Unit,
    onStopAll: () -> Unit
) {
    var promptExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier
                .weight(1f)
                .then(if (prompt.isNotBlank()) Modifier.clickable { promptExpanded = !promptExpanded } else Modifier)
                .testTag("compare_prompt_context")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ChatBubbleOutline,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = if (prompt.isNotBlank()) "${s.comparePromptLabel}: $prompt" else s.compareReadyHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (prompt.isNotBlank()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (promptExpanded) 4 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (isRunning) {
            FilledTonalButton(
                onClick = onStopAll,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("compare_stop_all_btn")
            ) {
                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(s.compareStopAction, style = MaterialTheme.typography.labelMedium)
            }
        }

        OutlinedButton(
            onClick = onOpenModelPicker,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("compare_choose_models_btn")
        ) {
            Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("$selectedCount", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun CompareQuickModelNavigation(
    entries: List<CompareModelEntry>,
    activeEntryKey: String?,
    activeIndex: Int,
    s: AppStrings,
    onSelectEntry: (String) -> Unit
) {
    val navListState = rememberLazyListState()
    LaunchedEffect(activeIndex) {
        if (activeIndex in entries.indices) {
            navListState.animateScrollToItem(activeIndex)
        }
    }

    LazyRow(
        state = navListState,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("compare_quick_nav")
    ) {
        itemsIndexed(entries, key = { _, item -> item.entryKey }) { _, entry ->
            val selected = entry.entryKey == activeEntryKey
            val statusColor = compareStatusColor(entry.status)
            val statusDesc = compareStatusLabel(entry.status, s)
            val containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
            val borderStroke = if (selected) {
                BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
            } else {
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = containerColor,
                border = borderStroke,
                tonalElevation = if (selected) 2.dp else 0.dp,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onSelectEntry(entry.entryKey) }
                    .semantics {
                        contentDescription = "${entry.displayName}, ${entry.providerName}, $statusDesc"
                    }
                    .testTag("compare_nav_${entry.entryKey}")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Column(modifier = Modifier.widthIn(max = 148.dp)) {
                        Text(
                            text = shortModelDisplayName(entry.displayName, entry.modelId),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (entry.providerName.isNotBlank()) {
                            Text(
                                text = entry.providerName,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompareProgressIndicator(
    activeIndex: Int,
    totalCount: Int,
    s: AppStrings
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("compare_progress_indicator"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            val dotCount = totalCount.coerceAtMost(12)
            for (i in 0 until dotCount) {
                val isCurrent = i == activeIndex.coerceIn(0, (dotCount - 1).coerceAtLeast(0))
                Box(
                    modifier = Modifier
                        .height(5.dp)
                        .width(if (isCurrent) 22.dp else 8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (isCurrent) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
                        )
                )
            }
        }
        Text(
            text = String.format(Locale.getDefault(), s.compareProgressOfFormat, activeIndex + 1, totalCount),
            style = MonoTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("compare_progress_text")
        )
    }
}

@Composable
private fun CompareModelResponseCard(
    entry: CompareModelEntry,
    positionNumber: Int,
    hasActivePrompt: Boolean,
    isDetailsExpanded: Boolean,
    reducedMotion: Boolean,
    currency: String,
    s: AppStrings,
    onToggleDetails: () -> Unit,
    onCopyResponse: () -> Unit,
    onRetry: () -> Unit,
    onStopModel: () -> Unit
) {
    val statusLabel = compareStatusLabel(entry.status, s)
    val statusTone = compareStatusTone(entry.status)
    val positionText = String.format(Locale.US, "%s %02d", s.compareModelIndexPrefix, positionNumber)
    val responseScrollState = rememberScrollState()

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)),
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .testTag("compare_card_${entry.entryKey}")
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 5. Selected Model Information Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = entry.displayName.ifBlank { entry.modelId },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            if (entry.providerName.isNotBlank()) {
                                append(entry.providerName)
                                append(" · ")
                            }
                            append(positionText)
                            append(" · ")
                            append(statusLabel)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("compare_card_meta_${entry.entryKey}")
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (entry.isStreaming || entry.status == CompareRequestStatus.RUNNING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    StatusPill(text = statusLabel, tone = statusTone)
                    if (entry.status.isActive && hasActivePrompt) {
                        IconButton(
                            onClick = onStopModel,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("compare_stop_btn_${entry.entryKey}")
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = s.compareStopAction, modifier = Modifier.size(18.dp))
                        }
                    } else if ((entry.status == CompareRequestStatus.FAILED || entry.status == CompareRequestStatus.CANCELLED) && hasActivePrompt) {
                        TextButton(
                            onClick = onRetry,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("compare_retry_btn_${entry.entryKey}")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(s.compareRetryAction, maxLines = 1)
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))

            // 6. Complete Model Response (Vertically Scrollable)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(responseScrollState)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .testTag("compare_response_scroll_${entry.entryKey}"),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (entry.responseText.isNotBlank()) {
                    CompareRichMarkdownText(text = entry.responseText)
                }

                when (entry.status) {
                    CompareRequestStatus.PENDING,
                    CompareRequestStatus.QUEUED,
                    CompareRequestStatus.RUNNING -> {
                        if (entry.responseText.isBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.padding(vertical = 8.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(
                                    text = if (hasActivePrompt) statusLabel else s.compareReadyHint,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    CompareRequestStatus.STREAMING -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Text(
                                text = s.streamingIndicator,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    CompareRequestStatus.FAILED -> {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("compare_error_panel_${entry.entryKey}")
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ErrorOutline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = entry.errorMessage ?: s.error,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                                if (hasActivePrompt) {
                                    OutlinedButton(
                                        onClick = onRetry,
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("compare_error_retry_btn_${entry.entryKey}")
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(s.compareRetryAction)
                                    }
                                }
                            }
                        }
                    }

                    CompareRequestStatus.CANCELLED -> {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = s.compareStatusCancelled,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (hasActivePrompt) {
                                    TextButton(
                                        onClick = onRetry,
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Text(s.compareRetryAction)
                                    }
                                }
                            }
                        }
                    }

                    CompareRequestStatus.COMPLETED -> {
                        if (entry.responseText.isBlank()) {
                            Text(
                                text = s.compareEmptyResponse,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))

            // 7. Details and Copy Response Actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onToggleDetails,
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("compare_details_btn_${entry.entryKey}")
                ) {
                    Icon(
                        imageVector = if (isDetailsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(s.compareDetailsAction, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                FilledTonalButton(
                    onClick = onCopyResponse,
                    enabled = entry.responseText.isNotBlank(),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("compare_copy_btn_${entry.entryKey}")
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(s.compareCopyResponseAction, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            // 8. Expandable Model Details Panel (Collapsed by default)
            AnimatedVisibility(
                visible = isDetailsExpanded,
                enter = if (reducedMotion) fadeIn(tween(0)) else fadeIn(tween(180)) + expandVertically(tween(220)),
                exit = if (reducedMotion) fadeOut(tween(0)) else fadeOut(tween(150)) + shrinkVertically(tween(180))
            ) {
                CompareModelDetailsPanel(
                    entry = entry,
                    currency = currency,
                    s = s
                )
            }
        }
    }
}

@Composable
private fun CompareModelDetailsPanel(
    entry: CompareModelEntry,
    currency: String,
    s: AppStrings
) {
    val na = s.compareNotAvailable
    val statusText = compareStatusLabel(entry.status, s)
    val tokenSuffix = if (entry.usageEstimated) " (${s.usageSourceEstimated})" else ""

    val rows = listOf(
        s.compareDetailModelName to entry.displayName.ifBlank { na },
        s.compareDetailProvider to entry.providerName.ifBlank { na },
        s.compareDetailModelId to entry.modelId.ifBlank { na },
        s.compareDetailStatus to statusText,
        s.compareDetailLatency to (entry.latencyMs?.let { "$it ms" } ?: na),
        s.compareDetailTtft to (entry.ttftMs?.let { "$it ms" } ?: na),
        s.compareDetailPromptTokens to (entry.inputTokens?.let { "$it$tokenSuffix" } ?: na),
        s.compareDetailCompletionTokens to (entry.outputTokens?.let { "$it$tokenSuffix" } ?: na),
        s.compareDetailTotalTokens to (entry.totalTokens?.let { "$it$tokenSuffix" } ?: na),
        s.compareDetailEstimatedCost to (entry.estimatedCostUsd?.let { formatCurrency(it, currency, na) } ?: na),
        s.compareDetailActualCost to (entry.actualCostUsd?.let { formatCurrency(it, currency, na) } ?: na),
        s.compareDetailFinishReason to (entry.finishReason?.takeIf { it.isNotBlank() } ?: na),
        s.compareDetailHttpStatus to (entry.httpStatus?.toString() ?: na),
        s.compareDetailErrorInfo to (entry.errorMessage?.takeIf { it.isNotBlank() } ?: na)
    )

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 172.dp)
            .testTag("compare_details_panel_${entry.entryKey}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            rows.forEach { (label, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(0.46f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = value,
                        style = MonoTextStyle,
                        color = if (value == na) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(0.54f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ComparePreviousNextBar(
    activeIndex: Int,
    entries: List<CompareModelEntry>,
    s: AppStrings,
    onSelectEntry: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = {
                if (activeIndex > 0) {
                    onSelectEntry(entries[activeIndex - 1].entryKey)
                }
            },
            enabled = activeIndex > 0,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("compare_prev_btn")
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(s.comparePreviousAction, maxLines = 1)
        }

        Text(
            text = String.format(Locale.getDefault(), s.compareProgressOfFormat, activeIndex + 1, entries.size),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("compare_footer_progress")
        )

        OutlinedButton(
            onClick = {
                if (activeIndex in 0 until entries.lastIndex) {
                    onSelectEntry(entries[activeIndex + 1].entryKey)
                }
            },
            enabled = activeIndex in 0 until entries.lastIndex,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("compare_next_btn")
        ) {
            Text(s.compareNextAction, maxLines = 1)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun CompareModelPickerSheet(
    availableModels: List<ModelEntity>,
    selectedKeySet: Set<String>,
    s: AppStrings,
    onToggleModel: (ModelEntity) -> Unit,
    onClearSelection: () -> Unit,
    onDismiss: () -> Unit
) {
    var search by remember { mutableStateOf("") }
    val filtered = remember(availableModels, search) {
        availableModels.filter { m ->
            search.isBlank() ||
                m.displayName.contains(search, true) ||
                m.modelId.contains(search, true) ||
                m.providerName.contains(search, true)
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("compare_model_picker_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${s.selectModelsToCompareLabel} (${selectedKeySet.size})",
                    style = MaterialTheme.typography.titleMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (selectedKeySet.isNotEmpty()) {
                        TextButton(onClick = onClearSelection) {
                            Text(s.compareClearSelectionAction)
                        }
                    }
                    FilledTonalButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("compare_picker_done_btn")
                    ) {
                        Text(s.compareDoneAction)
                    }
                }
            }

            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = { Text(s.search) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("compare_picker_search")
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filtered, key = { "cmp_pick_${it.id}" }) { model ->
                    val checked = model.id in selectedKeySet
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (checked) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .clickable { onToggleModel(model) }
                            .testTag("compare_picker_model_${model.id}")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { onToggleModel(model) }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = model.displayName.ifBlank { model.modelId },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${model.providerName} · ${model.modelId}",
                                    style = MonoTextStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun shortModelDisplayName(displayName: String, modelId: String): String {
    val raw = displayName.ifBlank { modelId }.trim()
    val leaf = raw.substringAfterLast('/')
    return if (leaf.length <= 22) leaf else leaf.take(20) + "…"
}

private fun compareStatusLabel(status: CompareRequestStatus, s: AppStrings): String = when (status) {
    CompareRequestStatus.PENDING -> s.compareStatusPending
    CompareRequestStatus.QUEUED -> s.compareStatusQueued
    CompareRequestStatus.RUNNING -> s.compareStatusRunning
    CompareRequestStatus.STREAMING -> s.compareStatusStreaming
    CompareRequestStatus.COMPLETED -> s.compareStatusCompleted
    CompareRequestStatus.FAILED -> s.compareStatusFailed
    CompareRequestStatus.CANCELLED -> s.compareStatusCancelled
}

private fun compareStatusTone(status: CompareRequestStatus): StatusTone = when (status) {
    CompareRequestStatus.PENDING, CompareRequestStatus.QUEUED -> StatusTone.NEUTRAL
    CompareRequestStatus.RUNNING, CompareRequestStatus.STREAMING -> StatusTone.INFO
    CompareRequestStatus.COMPLETED -> StatusTone.SUCCESS
    CompareRequestStatus.FAILED -> StatusTone.ERROR
    CompareRequestStatus.CANCELLED -> StatusTone.WARNING
}

@Composable
private fun compareStatusColor(status: CompareRequestStatus): Color = when (status) {
    CompareRequestStatus.PENDING, CompareRequestStatus.QUEUED -> StatusNeutral
    CompareRequestStatus.RUNNING, CompareRequestStatus.STREAMING -> MaterialTheme.colorScheme.primary
    CompareRequestStatus.COMPLETED -> StatusConnected
    CompareRequestStatus.FAILED -> StatusError
    CompareRequestStatus.CANCELLED -> StatusWarning
}

private fun isReducedMotionEnabled(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}.getOrDefault(false)
