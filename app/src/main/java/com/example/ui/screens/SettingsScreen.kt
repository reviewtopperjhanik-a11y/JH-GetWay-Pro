@file:OptIn(ExperimentalLayoutApi::class)

package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.core.i18n.AppLanguage
import com.example.core.i18n.LocalStrings
import com.example.data.local.BackupRestorePreview
import com.example.data.local.RestoreConflictStrategy
import com.example.ui.GatewayViewModel
import com.example.ui.components.ConfirmDeleteDialog
import com.example.ui.components.GatewayCard
import com.example.ui.components.StatusPill
import com.example.ui.components.StatusTone
import com.example.ui.components.formatTimestamp
import com.example.ui.components.gatewayStatusLabelAndTone
import com.example.ui.navigation.Routes
import com.example.ui.theme.MonoTextStyle
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    viewModel: GatewayViewModel,
    onNavigate: (String) -> Unit
) {
    val s = LocalStrings.current
    val settings by viewModel.settings.collectAsState()
    val updateState by viewModel.appUpdateUiState.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var showPinDialog by remember { mutableStateOf(false) }
    var showBackupExportDialog by remember { mutableStateOf(false) }
    var showBackupRestoreDialog by remember { mutableStateOf(false) }
    var showResetSettingsConfirm by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var infoModalTitle by remember { mutableStateOf<String?>(null) }
    var infoModalBody by remember { mutableStateOf("") }
    val effectiveRepo = viewModel.effectiveGithubUpdateRepo(settings)
    var repoInput by remember(effectiveRepo) {
        mutableStateOf(effectiveRepo)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("settings_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text(s.settingsTitle, style = MaterialTheme.typography.headlineSmall) }

        // General: Language & Theme
        item {
            GatewayCard {
                Text(s.generalSection, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                Text(s.languageLabel, style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(vertical = 6.dp)
                ) {
                    listOf(
                        AppLanguage.ENGLISH to s.langEnglish,
                        AppLanguage.BENGALI to s.langBengali,
                        AppLanguage.SYSTEM to s.langSystem
                    ).forEach { (lang, label) ->
                        FilterChip(
                            selected = settings.language == lang,
                            onClick = { viewModel.setLanguage(lang) },
                            label = { Text(label) },
                            modifier = Modifier.testTag("settings_lang_${lang.name}")
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(s.themeLabel, style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(vertical = 6.dp)
                ) {
                    listOf(
                        ThemeMode.LIGHT to s.themeLight,
                        ThemeMode.DARK to s.themeDark,
                        ThemeMode.SYSTEM to s.themeSystem
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = settings.themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) },
                            label = { Text(label) },
                            modifier = Modifier.testTag("settings_theme_${mode.name}")
                        )
                    }
                }
            }
        }

        // Gateway & Routing Quick Navigation
        item {
            val (gwStatusLabel, gwStatusTone) = gatewayStatusLabelAndTone(viewModel.gatewayRuntime.collectAsState().value.status)
            GatewayCard {
                Text("${s.gatewaySection} & ${s.routingSection}", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Surface(
                    onClick = { onNavigate(Routes.GATEWAY_CONFIG) },
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("settings_gateway_config_btn")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(s.gatewayConfigMenuTitle, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "${s.gatewayConfigActiveModeLabel}: ${gatewayModeDisplayName(settings.gatewayMode, s)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = s.gatewayConfigMenuSubtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            StatusPill(text = gwStatusLabel, tone = gwStatusTone)
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = s.gatewayConfigMenuTitle,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(onClick = { onNavigate(Routes.GATEWAY) }) { Text(s.navGateway) }
                    OutlinedButton(onClick = { onNavigate(Routes.SMART_ROUTER) }) { Text(s.navSmartRouter) }
                    OutlinedButton(onClick = { onNavigate(Routes.VAULT) }) { Text(s.navVault) }
                    OutlinedButton(onClick = { onNavigate(Routes.DEVICES) }) { Text(s.navDevices) }
                }
            }
        }

        // Security & Privacy
        item {
            GatewayCard {
                Text(s.securitySection, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(s.saveChatHistoryLabel, modifier = Modifier.weight(1f))
                    Switch(checked = settings.saveChatHistoryAllowed, onCheckedChange = { viewModel.setSaveChatHistory(it) })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(s.appLockLabel, modifier = Modifier.weight(1f))
                    Switch(
                        checked = settings.appLockEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled) showPinDialog = true else viewModel.setAppLockPin(false, "")
                        },
                        modifier = Modifier.testTag("settings_app_lock_switch")
                    )
                }
                if (settings.appLockEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(s.appLockAutoLockLabel, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Switch(
                            checked = settings.appLockAutoLockOnBackground,
                            onCheckedChange = { viewModel.setAppLockAutoLockOnBackground(it) },
                            modifier = Modifier.testTag("settings_auto_lock_bg_switch")
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(s.appLockBiometricLabel, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Switch(
                            checked = settings.appLockBiometricEnabled,
                            onCheckedChange = { viewModel.setAppLockBiometricEnabled(it) },
                            modifier = Modifier.testTag("settings_biometric_switch")
                        )
                    }
                    OutlinedButton(
                        onClick = { showPinDialog = true },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("settings_change_pin_btn")
                    ) {
                        Icon(Icons.Default.Pin, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(s.changePinLabel)
                    }
                }
                Spacer(Modifier.height(8.dp))
                var logDaysDraft by remember(settings.logRetentionDays) { mutableFloatStateOf(settings.logRetentionDays.toFloat()) }
                Text("${s.logRetentionLabel}: ${logDaysDraft.roundToInt()} ${s.daysUnit}", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = logDaysDraft,
                    onValueChange = { logDaysDraft = it },
                    onValueChangeFinished = { viewModel.setLogRetentionDays(logDaysDraft.roundToInt()) },
                    valueRange = 7f..90f
                )
                var usageDaysDraft by remember(settings.usageRetentionDays) { mutableFloatStateOf(settings.usageRetentionDays.toFloat()) }
                Text("${s.usageRetentionLabel}: ${usageDaysDraft.roundToInt()} ${s.daysUnit}", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = usageDaysDraft,
                    onValueChange = { usageDaysDraft = it },
                    onValueChangeFinished = { viewModel.setUsageRetentionDays(usageDaysDraft.roundToInt()) },
                    valueRange = 7f..365f
                )
                var playgroundDaysDraft by remember(settings.playgroundRetentionDays) { mutableFloatStateOf(settings.playgroundRetentionDays.toFloat()) }
                Text("${s.playgroundRetentionLabel}: ${playgroundDaysDraft.roundToInt()} ${s.daysUnit}", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = playgroundDaysDraft,
                    onValueChange = { playgroundDaysDraft = it },
                    onValueChangeFinished = { viewModel.setPlaygroundRetentionDays(playgroundDaysDraft.roundToInt()) },
                    valueRange = 7f..180f
                )
                OutlinedButton(
                    onClick = { viewModel.runRetentionCleanupNow() },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("settings_run_retention_btn")
                ) {
                    Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(s.runRetentionCleanupLabel)
                }
            }
        }

        // Notifications
        item {
            GatewayCard {
                Text(s.notificationsSection, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                NotificationRow(s.notifProviderFailure, settings.notifyProviderFailure) {
                    viewModel.updateNotificationPrefs(it, settings.notifyGatewayDisconnected, settings.notifyRateLimit, settings.notifyWorkflowCompleted, settings.notifySecurityEvent)
                }
                NotificationRow(s.notifGatewayDisconnected, settings.notifyGatewayDisconnected) {
                    viewModel.updateNotificationPrefs(settings.notifyProviderFailure, it, settings.notifyRateLimit, settings.notifyWorkflowCompleted, settings.notifySecurityEvent)
                }
                NotificationRow(s.notifRateLimit, settings.notifyRateLimit) {
                    viewModel.updateNotificationPrefs(settings.notifyProviderFailure, settings.notifyGatewayDisconnected, it, settings.notifyWorkflowCompleted, settings.notifySecurityEvent)
                }
                NotificationRow(s.notifWorkflowCompleted, settings.notifyWorkflowCompleted) {
                    viewModel.updateNotificationPrefs(settings.notifyProviderFailure, settings.notifyGatewayDisconnected, settings.notifyRateLimit, it, settings.notifySecurityEvent)
                }
                NotificationRow(s.notifSecurityAuth, settings.notifySecurityEvent) {
                    viewModel.updateNotificationPrefs(settings.notifyProviderFailure, settings.notifyGatewayDisconnected, settings.notifyRateLimit, settings.notifyWorkflowCompleted, it)
                }
            }
        }

        // Reset App Settings (Non-Destructive)
        item {
            GatewayCard {
                Text(s.resetAppSettingsTitle, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = s.resetAppSettingsDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { showResetSettingsConfirm = true },
                    modifier = Modifier.fillMaxWidth().testTag("settings_reset_app_settings_btn")
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(s.resetAppSettingsAction)
                }
            }
        }

        // Data & Encrypted Backup
        item {
            GatewayCard {
                Text(s.dataBackupSection, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { showBackupExportDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Lock, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(s.exportEncryptedBackup)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { showBackupRestoreDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Restore, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(s.importEncryptedBackup)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showClearConfirm = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().testTag("settings_clear_local_data_btn")
                ) {
                    Icon(Icons.Default.DeleteForever, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(s.clearLocalData)
                }
            }
        }

        // App Updates (GitHub Releases)
        item {
            GatewayCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(s.updatesSectionTitle, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    StatusPill(
                        text = "v${BuildConfig.VERSION_NAME}",
                        tone = if (updateState.availableRelease != null) StatusTone.INFO else StatusTone.NEUTRAL
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = repoInput,
                    onValueChange = { repoInput = it },
                    label = { Text(s.githubRepoLabel) },
                    placeholder = { Text(s.githubRepoHint) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("settings_github_repo_input")
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.setGithubUpdateRepo(repoInput) },
                        modifier = Modifier.weight(1f).testTag("settings_github_repo_save_btn")
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(s.save)
                    }
                    Button(
                        onClick = {
                            viewModel.checkForAppUpdates(
                                silent = false,
                                repoInputOverride = repoInput
                            )
                        },
                        enabled = (!updateState.isChecking || updateState.isSilentCheck) && !updateState.isDownloading,
                        modifier = Modifier.weight(1f).testTag("settings_check_updates_btn")
                    ) {
                        Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(s.checkForUpdatesAction)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(s.autoCheckUpdatesLabel, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(
                        checked = settings.autoCheckUpdates,
                        onCheckedChange = { viewModel.setAutoCheckUpdates(it) },
                        modifier = Modifier.testTag("settings_auto_update_switch")
                    )
                }
                if (effectiveRepo.isBlank() && updateState.errorMessage == null) {
                    Text(
                        text = s.repoNotConfiguredMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                updateState.lastCheckedAtMs?.let { checkedAt ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${s.updaterLastCheckedPrefix}: ${formatTimestamp(checkedAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (updateState.isChecking) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(s.updateCheckingLabel, style = MaterialTheme.typography.bodySmall)
                }
                if (updateState.signingConflict) {
                    Spacer(Modifier.height(6.dp))
                    Text(s.updateSigningConflictMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                } else {
                    updateState.errorMessage?.let { errMsg ->
                        Spacer(Modifier.height(6.dp))
                        Text(errMsg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                updateState.statusMessage?.let { statusMsg ->
                    if (updateState.availableRelease == null && !updateState.isChecking) {
                        Spacer(Modifier.height(6.dp))
                        Text(statusMsg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                val fallbackUrl = updateState.manualReleasesUrl
                    ?: viewModel.effectiveGithubUpdateRepo(settings)
                        .takeIf { it.isNotBlank() }
                        ?.let { "https://github.com/$it/releases" }
                if (!updateState.isChecking &&
                    fallbackUrl != null &&
                    (updateState.errorMessage != null || updateState.rateLimitResetAtMs != null) &&
                    !updateState.signingConflict
                ) {
                    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { runCatching { uriHandler.openUri(fallbackUrl) } },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_open_releases_fallback_btn")
                    ) {
                        Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(s.openOfficialReleasesAction)
                    }
                }
                updateState.availableRelease?.let { release ->
                    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth().testTag("settings_available_update_card")
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${s.updateAvailableTitle}: ${release.tagName}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f)
                                )
                                if (updateState.isUsingCachedRelease) {
                                    StatusPill(text = s.updaterCachedReleaseBadge, tone = StatusTone.INFO)
                                }
                            }
                            if (!BuildConfig.DEBUG && release.signingMode == "ci-ephemeral") {
                                Text(
                                    text = s.ephemeralReleaseWarning,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                            if (release.releaseNotes.isNotBlank()) {
                                Text(
                                    release.releaseNotes.take(280),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (updateState.isDownloading) {
                                LinearProgressIndicator(
                                    progress = { updateState.downloadProgressPct / 100f },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    "${s.downloadingUpdateLabel} ${updateState.downloadProgressPct}%",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            } else if (updateState.signingConflict) {
                                OutlinedButton(
                                    onClick = { runCatching { uriHandler.openUri(release.htmlUrl) } },
                                    modifier = Modifier.fillMaxWidth().testTag("settings_open_release_page_btn")
                                ) {
                                    Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(s.openReleasePageLabel)
                                }
                            } else {
                                if (updateState.errorMessage == null && !updateState.statusMessage.isNullOrBlank()) {
                                    Text(
                                        text = updateState.statusMessage!!,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                if (updateState.downloadedApkFile != null) {
                                    Button(
                                        onClick = { viewModel.launchDownloadedApkInstall() },
                                        modifier = Modifier.fillMaxWidth().testTag("settings_install_apk_btn")
                                    ) {
                                        Icon(Icons.Default.InstallMobile, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(s.installDownloadedAction)
                                    }
                                } else {
                                    Button(
                                        onClick = { viewModel.downloadAndInstallUpdate() },
                                        modifier = Modifier.fillMaxWidth().testTag("settings_download_install_btn")
                                    ) {
                                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(s.downloadAndInstallAction)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // About JH Soft Corporation
        item {
            GatewayCard {
                Text(s.aboutSection, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text("${s.appName} — ${s.orgName}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Text("v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodySmall)
                Text(s.copyrightLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(
                        onClick = {
                            infoModalTitle = s.privacyPolicyTitle
                            infoModalBody = "${s.privacyPolicySummary}\n\n(docs/PRIVACY_AND_SECURITY.md)"
                        },
                        modifier = Modifier.testTag("settings_privacy_btn")
                    ) { Text(s.privacyPolicyTitle) }
                    TextButton(
                        onClick = {
                            infoModalTitle = s.termsTitle
                            infoModalBody = s.termsSummary
                        },
                        modifier = Modifier.testTag("settings_terms_btn")
                    ) { Text(s.termsTitle) }
                    TextButton(
                        onClick = {
                            infoModalTitle = s.openSourceLicensesTitle
                            infoModalBody = "${s.licensesSummary}\n\n(docs/DEPENDENCY_LICENSES.md)"
                        },
                        modifier = Modifier.testTag("settings_licenses_btn")
                    ) { Text(s.openSourceLicensesTitle) }
                }
            }
        }
    }

    if (showPinDialog) {
        var pin by remember { mutableStateOf("") }
        var showPinError by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showPinDialog = false },
            title = { Text(if (settings.appLockEnabled) s.changePinLabel else s.appLockLabel) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = pin,
                        onValueChange = {
                            pin = it.filter { c -> c.isDigit() }.take(8)
                            showPinError = false
                        },
                        label = { Text(s.pinFormatHint) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        isError = showPinError,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("settings_pin_input")
                    )
                    if (showPinError) {
                        Text(
                            text = s.pinFormatHint,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (pin.length in 4..8) {
                            viewModel.setAppLockPin(true, pin)
                            showPinDialog = false
                        } else {
                            showPinError = true
                        }
                    },
                    modifier = Modifier.testTag("settings_pin_save_btn")
                ) { Text(s.save) }
            },
            dismissButton = { TextButton(onClick = { showPinDialog = false }) { Text(s.cancel) } }
        )
    }

    if (showBackupExportDialog) {
        var passphrase by remember { mutableStateOf("") }
        var exportedText by remember { mutableStateOf<String?>(null) }
        var err by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showBackupExportDialog = false },
            title = { Text(s.exportEncryptedBackup) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it; err = null },
                        label = { Text(s.passphraseLabel) },
                        supportingText = { Text(s.backupPassphraseMinHint) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("settings_backup_passphrase_input")
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = s.backupNonPortableDisclosure,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = s.backupPassphraseLossWarning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    exportedText?.let { payload ->
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                            Text(payload.take(180) + "...", style = MonoTextStyle, modifier = Modifier.padding(8.dp))
                        }
                        OutlinedButton(
                            onClick = { clipboard.setText(AnnotatedString(payload)); viewModel.postBanner(s.copied) },
                            modifier = Modifier.testTag("settings_backup_copy_btn")
                        ) {
                            Text(s.copy)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            viewModel.exportEncryptedBackup(passphrase).fold(
                                onSuccess = { exportedText = it; err = null },
                                onFailure = { err = it.message }
                            )
                        }
                    },
                    modifier = Modifier.testTag("settings_backup_export_btn")
                ) { Text(s.exportLabel) }
            },
            dismissButton = { TextButton(onClick = { showBackupExportDialog = false }) { Text(s.close) } }
        )
    }

    if (showBackupRestoreDialog) {
        var payload by remember { mutableStateOf("") }
        var passphrase by remember { mutableStateOf("") }
        var preview by remember { mutableStateOf<BackupRestorePreview?>(null) }
        var strategy by remember { mutableStateOf(RestoreConflictStrategy.OVERWRITE_EXISTING) }
        var err by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showBackupRestoreDialog = false },
            title = { Text(s.importEncryptedBackup) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    OutlinedTextField(
                        value = payload,
                        onValueChange = { payload = it; preview = null; err = null },
                        label = { Text(s.backupPayloadLabel) },
                        textStyle = MonoTextStyle,
                        minLines = 3,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth().testTag("settings_backup_restore_payload")
                    )
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it; preview = null; err = null },
                        label = { Text(s.passphraseLabel) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("settings_backup_restore_passphrase")
                    )
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                viewModel.previewEncryptedBackup(payload, passphrase).fold(
                                    onSuccess = { preview = it; err = null },
                                    onFailure = { preview = null; err = it.message ?: s.previewFailedLabel }
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("settings_backup_preview_btn")
                    ) {
                        Text(s.previewBackupLabel)
                    }
                    preview?.let { pv ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth().testTag("settings_backup_preview_card")
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(s.backupPreviewTitle, style = MaterialTheme.typography.labelLarge)
                                Text(
                                    text = "Format V${pv.formatVersion} • DB Schema V${pv.schemaVersion} • ${pv.kdfAlgorithm} (${pv.kdfIterations} iter)",
                                    style = MaterialTheme.typography.labelSmall
                                )
                                pv.manifest?.let { m ->
                                    if (m.appVersionName.isNotBlank()) {
                                        Text(
                                            text = "App v${m.appVersionName} (${m.appVersionCode})",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                                if (pv.exportedAt > 0L) {
                                    Text("${s.exportedLabel}: ${formatTimestamp(pv.exportedAt)}", style = MaterialTheme.typography.labelSmall)
                                }
                                if (pv.integrityVerified) {
                                    Text(s.backupIntegrityVerified, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                                Text(
                                    text = "Total: ${pv.counts.total} (Providers ${pv.counts.providers}, Keys ${pv.counts.apiKeys}, Models ${pv.counts.models}, Rules ${pv.counts.routingRules}, Agents ${pv.counts.codingAgents}, Local ${pv.counts.localAiServers}, MCP ${pv.counts.mcpServers}/${pv.counts.mcpTools}, Workflows ${pv.counts.workflows}/${pv.counts.workflowRuns}, Devices ${pv.counts.devices}, Chats ${pv.counts.playgroundSessions}/${pv.counts.playgroundMessages}, Metrics ${pv.counts.requestMetrics}, Prefs ${pv.counts.preferences})",
                                    style = MonoTextStyle
                                )
                                Text(
                                    text = "Attachments: ${pv.attachmentCount} file(s) (${com.example.data.local.PlaygroundAttachmentManager.formatReadableSize(pv.attachmentTotalBytes)})" +
                                        if (pv.unavailableAttachmentCount > 0) " • ${pv.unavailableAttachmentCount} unavailable" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (pv.unavailableAttachmentCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "${s.backupConflictsLabel}: ${pv.conflicts.totalConflicts}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (pv.conflicts.totalConflicts > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = s.backupRestoreDetailsFormat,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Text(s.restoreStrategyLabel, style = MaterialTheme.typography.labelMedium)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(
                                RestoreConflictStrategy.OVERWRITE_EXISTING to s.restoreStrategyOverwrite,
                                RestoreConflictStrategy.SKIP_CONFLICTS to s.restoreStrategySkip,
                                RestoreConflictStrategy.REPLACE_ALL to s.restoreStrategyReplaceAll
                            ).forEach { (opt, label) ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    RadioButton(
                                        selected = strategy == opt,
                                        onClick = { strategy = opt },
                                        modifier = Modifier.testTag("settings_restore_strategy_${opt.name}")
                                    )
                                    Text(label, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            viewModel.restoreEncryptedBackup(payload, passphrase, strategy).fold(
                                onSuccess = { res ->
                                    val attSummary = if (res.restoredAttachmentCount > 0) ", ${res.restoredAttachmentCount} attachment(s)" else ""
                                    val warnSummary = if (res.warnings.isNotEmpty()) " (${res.warnings.joinToString("; ")})" else ""
                                    viewModel.postBanner("Restored ${res.importedCount} items$attSummary (skipped ${res.skippedConflicts})$warnSummary")
                                    showBackupRestoreDialog = false
                                },
                                onFailure = { err = it.message ?: s.restoreFailedLabel }
                            )
                        }
                    },
                    enabled = preview != null,
                    modifier = Modifier.testTag("settings_backup_restore_confirm_btn")
                ) { Text(s.importLabel) }
            },
            dismissButton = { TextButton(onClick = { showBackupRestoreDialog = false }) { Text(s.cancel) } }
        )
    }

    if (showResetSettingsConfirm) {
        AlertDialog(
            onDismissRequest = { showResetSettingsConfirm = false },
            title = { Text(s.resetAppSettingsConfirmTitle) },
            text = { Text(s.resetAppSettingsConfirmMessage) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.resetSafeAppSettings()
                        showResetSettingsConfirm = false
                    },
                    modifier = Modifier.testTag("settings_reset_confirm_btn")
                ) {
                    Text(s.resetAppSettingsConfirmButton)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showResetSettingsConfirm = false },
                    modifier = Modifier.testTag("settings_reset_cancel_btn")
                ) {
                    Text(s.cancel)
                }
            }
        )
    }

    if (showClearConfirm) {
        ConfirmDeleteDialog(
            title = s.clearLocalData,
            message = s.clearLocalDataWarning,
            onConfirm = { viewModel.clearAllLocalData(); showClearConfirm = false },
            onDismiss = { showClearConfirm = false }
        )
    }

    infoModalTitle?.let { title ->
        AlertDialog(
            onDismissRequest = { infoModalTitle = null },
            title = { Text(title) },
            text = { Text(infoModalBody) },
            confirmButton = { TextButton(onClick = { infoModalTitle = null }) { Text(s.close) } }
        )
    }
}

@Composable
private fun NotificationRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
