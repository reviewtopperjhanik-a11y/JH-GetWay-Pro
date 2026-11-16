@file:OptIn(ExperimentalLayoutApi::class)

package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.core.i18n.AppStrings
import com.example.core.i18n.LocalStrings
import com.example.data.local.GatewayMode
import com.example.data.local.LoggingLevel
import com.example.gateway.GatewayConfigDraft
import com.example.gateway.GatewayConfigValidator
import com.example.ui.GatewayViewModel
import com.example.ui.components.GatewayCard
import com.example.ui.components.StatusPill
import com.example.ui.components.StatusTone
import com.example.ui.components.formatTimestamp
import com.example.ui.components.gatewayStatusLabelAndTone
import com.example.ui.theme.MonoTextStyle
import kotlinx.coroutines.launch

@Composable
fun GatewayConfigScreen(
    viewModel: GatewayViewModel,
    onBack: () -> Unit = {}
) {
    val s = LocalStrings.current
    val settings by viewModel.settings.collectAsState()
    val runtime by viewModel.gatewayRuntime.collectAsState()
    val testResult by viewModel.gatewayConfigTestResult.collectAsState()
    val scope = rememberCoroutineScope()

    var gwName by remember(settings.gatewayName) { mutableStateOf(settings.gatewayName) }
    var selectedMode by remember(settings.gatewayMode) { mutableStateOf(settings.gatewayMode) }
    var host by remember(settings.gatewayHost) { mutableStateOf(settings.gatewayHost) }
    var portText by remember(settings.gatewayPort) { mutableStateOf(settings.gatewayPort.toString()) }
    var remoteUrl by remember(settings.gatewayRemoteUrl) { mutableStateOf(settings.gatewayRemoteUrl) }
    var allowLanExposure by remember(settings.gatewayAllowLanExposure) { mutableStateOf(settings.gatewayAllowLanExposure) }
    var allowExistingLocalHttp by remember(settings.localNetworkAccessAllowed) { mutableStateOf(settings.localNetworkAccessAllowed) }
    var remoteAuthTokenInput by remember { mutableStateOf("") }
    var clearSavedRemoteToken by remember { mutableStateOf(false) }
    var authRequired by remember(settings.gatewayAuthRequired) { mutableStateOf(settings.gatewayAuthRequired) }
    var timeoutText by remember(settings.gatewayTimeoutSec) { mutableStateOf(settings.gatewayTimeoutSec.toString()) }
    var maxKbText by remember(settings.gatewayMaxRequestKb) { mutableStateOf(settings.gatewayMaxRequestKb.toString()) }
    var streamingEnabled by remember(settings.gatewayStreamingEnabled) { mutableStateOf(settings.gatewayStreamingEnabled) }
    var rateLimitText by remember(settings.gatewayRateLimitPerMin) { mutableStateOf(settings.gatewayRateLimitPerMin.toString()) }
    var loggingLevel by remember(settings.gatewayLoggingLevel) { mutableStateOf(settings.gatewayLoggingLevel) }

    var validationError by remember { mutableStateOf<String?>(null) }
    var showModeSwitchConfirm by remember { mutableStateOf(false) }
    var pendingValidatedDraft by remember { mutableStateOf<GatewayConfigDraft?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    fun buildDraft(): GatewayConfigDraft {
        return GatewayConfigDraft(
            name = gwName,
            mode = selectedMode,
            host = host,
            port = portText.toIntOrNull() ?: -1,
            remoteUrl = remoteUrl,
            authRequired = authRequired,
            timeoutSec = timeoutText.toIntOrNull() ?: -1,
            maxRequestKb = maxKbText.toIntOrNull() ?: -1,
            streamingEnabled = streamingEnabled,
            rateLimitPerMin = rateLimitText.toIntOrNull() ?: -1,
            loggingLevel = loggingLevel,
            allowLanExposure = allowLanExposure,
            allowExistingLocalHttp = allowExistingLocalHttp,
            remoteAuthTokenInput = remoteAuthTokenInput,
            clearSavedRemoteToken = clearSavedRemoteToken
        )
    }

    fun executeSave(draft: GatewayConfigDraft) {
        scope.launch {
            isSaving = true
            val res = viewModel.saveAndApplyGatewayConfig(draft)
            isSaving = false
            res.fold(
                onSuccess = {
                    validationError = null
                    remoteAuthTokenInput = ""
                    clearSavedRemoteToken = false
                    showModeSwitchConfirm = false
                    pendingValidatedDraft = null
                },
                onFailure = { err ->
                    validationError = err.message ?: s.error
                    showModeSwitchConfirm = false
                }
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.clearGatewayConfigTestResult() }
    }

    val (statusLabel, statusTone) = gatewayStatusLabelAndTone(runtime.status)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("gateway_config_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(s.gatewayConfigMenuTitle, style = MaterialTheme.typography.headlineSmall)
        }

        // Currently Active Gateway Mode & Live Runtime Card
        item {
            GatewayCard(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = s.gatewayConfigActiveModeLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = gatewayModeDisplayName(settings.gatewayMode, s),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("gw_config_active_mode_text")
                        )
                        Text(
                            text = runtime.boundAddress,
                            style = MonoTextStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    StatusPill(text = statusLabel, tone = statusTone)
                }
                runtime.lastHealthCheckAt?.let { checkedAt ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "${s.lastCheckedLabel}: ${formatTimestamp(checkedAt)}" +
                            (runtime.lastHealthHttpCode?.let { " (HTTP $it)" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                runtime.lastError?.let { err ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        // Mode Selection Cards
        item {
            GatewayCard {
                Text(s.gatewayConfigSelectModeLabel, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GatewayMode.entries.forEach { modeOption ->
                        val isSelected = selectedMode == modeOption
                        val desc = when (modeOption) {
                            GatewayMode.LOCAL -> s.gatewayConfigModeLocalDesc
                            GatewayMode.REMOTE -> s.gatewayConfigModeRemoteDesc
                            GatewayMode.EXISTING -> s.gatewayConfigModeExistingDesc
                        }
                        Surface(
                            onClick = {
                                selectedMode = modeOption
                                validationError = null
                                viewModel.clearGatewayConfigTestResult()
                            },
                            shape = MaterialTheme.shapes.medium,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            },
                            border = if (isSelected) {
                                BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                            } else {
                                null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("gw_config_mode_${modeOption.name}")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = null
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = gatewayModeDisplayName(modeOption, s),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = desc,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Mode-Specific Fields & Gateway Settings
        item {
            GatewayCard {
                Text(s.gatewaySettings, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = gwName,
                    onValueChange = { gwName = it; validationError = null },
                    label = { Text(s.gatewayName) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("gw_config_name_input")
                )
                Spacer(Modifier.height(10.dp))

                if (selectedMode == GatewayMode.LOCAL) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it; validationError = null },
                            label = { Text(s.hostLabel) },
                            textStyle = MonoTextStyle,
                            singleLine = true,
                            modifier = Modifier
                                .weight(1.5f)
                                .testTag("gw_config_host_input")
                        )
                        OutlinedTextField(
                            value = portText,
                            onValueChange = { portText = it.filter { c -> c.isDigit() }; validationError = null },
                            label = { Text(s.portLabel) },
                            textStyle = MonoTextStyle,
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("gw_config_port_input")
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = s.allowLanExposureLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = allowLanExposure,
                            onCheckedChange = { allowLanExposure = it; validationError = null },
                            modifier = Modifier.testTag("gw_config_allow_lan_switch")
                        )
                    }
                    if (allowLanExposure) {
                        Text(
                            text = s.allowLanExposureWarning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = remoteUrl,
                        onValueChange = { remoteUrl = it; validationError = null },
                        label = { Text(s.remoteHttpsUrlLabel) },
                        placeholder = { Text("https://gateway.example.com") },
                        textStyle = MonoTextStyle,
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("gw_config_remote_url_input")
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = remoteAuthTokenInput,
                        onValueChange = {
                            remoteAuthTokenInput = it
                            if (it.isNotBlank()) clearSavedRemoteToken = false
                            validationError = null
                        },
                        label = { Text(s.remoteAuthTokenLabel) },
                        placeholder = {
                            Text(
                                if (settings.gatewayRemoteAuthTokenEnc.isNotBlank() && !clearSavedRemoteToken) {
                                    s.savedLeaveBlankHint
                                } else {
                                    s.optionalBearerHint
                                }
                            )
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        textStyle = MonoTextStyle,
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("gw_config_remote_token_input")
                    )
                    if (settings.gatewayRemoteAuthTokenEnc.isNotBlank() && !clearSavedRemoteToken) {
                        TextButton(onClick = { clearSavedRemoteToken = true; remoteAuthTokenInput = "" }) {
                            Text(s.clear)
                        }
                    }
                    if (selectedMode == GatewayMode.EXISTING) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = s.gatewayConfigAllowExistingLocalLabel,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = allowExistingLocalHttp,
                                onCheckedChange = { allowExistingLocalHttp = it; validationError = null },
                                modifier = Modifier.testTag("gw_config_existing_local_switch")
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = timeoutText,
                        onValueChange = { timeoutText = it.filter { c -> c.isDigit() }; validationError = null },
                        label = { Text(s.requestTimeoutSec) },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("gw_config_timeout_input")
                    )
                    OutlinedTextField(
                        value = rateLimitText,
                        onValueChange = { rateLimitText = it.filter { c -> c.isDigit() }; validationError = null },
                        label = { Text(s.rateLimitPerMin) },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("gw_config_rate_limit_input")
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = maxKbText,
                    onValueChange = { maxKbText = it.filter { c -> c.isDigit() }; validationError = null },
                    label = { Text(s.maxRequestSizeKb) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("gw_config_max_kb_input")
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(s.authRequired, modifier = Modifier.weight(1f))
                    Switch(
                        checked = authRequired,
                        onCheckedChange = { authRequired = it },
                        modifier = Modifier.testTag("gw_config_auth_switch")
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(s.streamingLabel, modifier = Modifier.weight(1f))
                    Switch(
                        checked = streamingEnabled,
                        onCheckedChange = { streamingEnabled = it },
                        modifier = Modifier.testTag("gw_config_streaming_switch")
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(s.loggingLevel, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(LoggingLevel.entries) { lvl ->
                        FilterChip(
                            selected = loggingLevel == lvl,
                            onClick = { loggingLevel = lvl },
                            label = { Text(lvl.name) }
                        )
                    }
                }
            }
        }

        validationError?.let { err ->
            item {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("gw_config_validation_error")
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = s.error, tint = MaterialTheme.colorScheme.error)
                        Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }

        if (testResult.isTesting || testResult.testedAtMs != null) {
            item {
                val tone = when {
                    testResult.isTesting -> StatusTone.INFO
                    testResult.success == true -> StatusTone.SUCCESS
                    else -> StatusTone.ERROR
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("gw_config_test_result_card")
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (testResult.isTesting) s.gatewayConfigTestingLabel else s.testConnection,
                                style = MaterialTheme.typography.labelLarge
                            )
                            StatusPill(
                                text = when {
                                    testResult.isTesting -> s.checking
                                    testResult.success == true -> s.success
                                    else -> s.error
                                },
                                tone = tone
                            )
                        }
                        if (testResult.isTesting) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        if (testResult.message.isNotBlank()) {
                            Text(
                                text = testResult.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (testResult.success == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    enabled = !testResult.isTesting && !isSaving,
                    onClick = {
                        val draft = buildDraft()
                        val check = GatewayConfigValidator.validate(draft)
                        if (!check.valid) {
                            validationError = check.errorMessage ?: s.error
                        } else {
                            validationError = null
                            viewModel.testGatewayDraftConnection(draft)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("gw_config_test_btn")
                ) {
                    Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(s.testConnection)
                }

                Button(
                    enabled = !isSaving,
                    onClick = {
                        val draft = buildDraft()
                        val check = GatewayConfigValidator.validate(draft)
                        if (!check.valid) {
                            validationError = check.errorMessage ?: s.error
                            return@Button
                        }
                        validationError = null
                        if (draft.mode != settings.gatewayMode) {
                            pendingValidatedDraft = draft
                            showModeSwitchConfirm = true
                        } else {
                            executeSave(draft)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("gw_config_save_btn")
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(s.save)
                }
            }
        }
    }

    if (showModeSwitchConfirm && pendingValidatedDraft != null) {
        val targetDraft = pendingValidatedDraft!!
        AlertDialog(
            onDismissRequest = { showModeSwitchConfirm = false },
            modifier = Modifier.testTag("gw_mode_switch_confirm_dialog"),
            title = { Text(s.gatewayConfigModeSwitchTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "${gatewayModeDisplayName(settings.gatewayMode, s)} → ${gatewayModeDisplayName(targetDraft.mode, s)}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = s.gatewayConfigModeSwitchMessage,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { executeSave(targetDraft) },
                    modifier = Modifier.testTag("gw_mode_switch_confirm_btn")
                ) {
                    Text(s.gatewayConfigSwitchConfirmAction)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showModeSwitchConfirm = false },
                    modifier = Modifier.testTag("gw_mode_switch_cancel_btn")
                ) {
                    Text(s.cancel)
                }
            }
        )
    }
}

internal fun gatewayModeDisplayName(mode: GatewayMode, s: AppStrings): String = when (mode) {
    GatewayMode.LOCAL -> s.localGatewayMode
    GatewayMode.REMOTE -> s.remoteGatewayMode
    GatewayMode.EXISTING -> s.existingGatewayMode
}
