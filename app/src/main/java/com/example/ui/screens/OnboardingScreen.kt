@file:OptIn(ExperimentalLayoutApi::class)

package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.core.i18n.AppLanguage
import com.example.core.i18n.LocalStrings
import com.example.data.local.*
import com.example.gateway.GatewayRunStatus
import com.example.network.ProviderPresets
import com.example.ui.GatewayViewModel
import com.example.ui.components.GatewayCard
import com.example.ui.components.StatusPill
import com.example.ui.components.gatewayStatusLabelAndTone
import com.example.ui.theme.MonoTextStyle
import com.example.ui.theme.ThemeMode

@Composable
fun OnboardingScreen(
    viewModel: GatewayViewModel,
    onCompleted: () -> Unit
) {
    val s = LocalStrings.current
    val settings by viewModel.settings.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val runtime by viewModel.gatewayRuntime.collectAsState()

    var step by remember { mutableIntStateOf(0) }
    var selectedPreset by remember { mutableStateOf(ProviderPresets.all.first()) }
    var providerName by remember { mutableStateOf(selectedPreset.name) }
    var baseUrl by remember { mutableStateOf(selectedPreset.baseUrl) }
    var defaultModel by remember { mutableStateOf(selectedPreset.defaultModel) }
    var apiKeyInput by remember { mutableStateOf("") }
    var createdProviderId by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    var pendingLocalStart by remember { mutableStateOf<(() -> Unit)?>(null) }
    val notifLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        pendingLocalStart?.invoke()
        pendingLocalStart = null
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                shadowElevation = 4.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (step > 0) {
                        OutlinedButton(onClick = { step-- }) { Text(s.back) }
                    } else {
                        TextButton(
                            onClick = {
                                viewModel.completeOnboarding()
                                onCompleted()
                            },
                            modifier = Modifier.testTag("onboarding_skip_all")
                        ) { Text(s.skip) }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (step == 2 || step == 3) {
                            TextButton(onClick = { step++ }) { Text(s.skip) }
                        }
                        Button(
                            onClick = {
                                when (step) {
                                    3 -> {
                                        if (providerName.isNotBlank() && baseUrl.isNotBlank() && createdProviderId == null) {
                                            viewModel.saveProvider(
                                                existingId = null,
                                                name = providerName,
                                                type = selectedPreset.type,
                                                presetId = selectedPreset.id,
                                                baseUrl = baseUrl,
                                                apiFormat = selectedPreset.apiFormat,
                                                defaultModel = defaultModel,
                                                priority = 1,
                                                enabled = true,
                                                customHeadersText = "",
                                                notes = selectedPreset.notes,
                                                initialApiKey = apiKeyInput
                                            )
                                            createdProviderId = "saved"
                                        }
                                        step++
                                    }
                                    5 -> {
                                        viewModel.completeOnboarding()
                                        onCompleted()
                                    }
                                    else -> step++
                                }
                            },
                            modifier = Modifier.testTag("onboarding_next_button")
                        ) {
                            Text(if (step == 5) s.goToDashboard else s.next)
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header with Brand + Step Indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = s.appName,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = s.orgName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "${step + 1} / 6",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            LinearProgressIndicator(
                progress = { (step + 1) / 6f },
                modifier = Modifier.fillMaxWidth()
            )

            when (step) {
                0 -> {
                    // Step 1: Welcome, Language & Theme
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primaryContainer,
                                        MaterialTheme.colorScheme.secondaryContainer
                                    )
                                ),
                                shape = MaterialTheme.shapes.large
                            )
                            .padding(20.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = s.onboardingWelcomeTitle,
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = s.onboardingWelcomeSubtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.88f)
                            )
                        }
                    }

                    GatewayCard {
                        Text(s.selectLanguage, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxWidth()
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
                                    modifier = Modifier.testTag("onboard_lang_${lang.name}")
                                )
                            }
                        }
                    }

                    GatewayCard {
                        Text(s.selectTheme, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxWidth()
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
                                    modifier = Modifier.testTag("onboard_theme_${mode.name}")
                                )
                            }
                        }
                    }
                }

                1 -> {
                    // Step 2: Gateway Mode
                    Text(s.onboardingGatewayTitle, style = MaterialTheme.typography.titleLarge)
                    listOf(
                        GatewayMode.LOCAL to s.localGatewayMode,
                        GatewayMode.REMOTE to s.remoteGatewayMode,
                        GatewayMode.EXISTING to s.existingGatewayMode
                    ).forEach { (mode, title) ->
                        val selected = settings.gatewayMode == mode
                        Surface(
                            onClick = { viewModel.setGatewayMode(mode) },
                            shape = MaterialTheme.shapes.medium,
                            border = BorderStroke(
                                if (selected) 2.dp else 1.dp,
                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                            ),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                RadioButton(selected = selected, onClick = { viewModel.setGatewayMode(mode) })
                                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }

                2 -> {
                    // Step 3: Provider Setup
                    Text(s.onboardingProviderTitle, style = MaterialTheme.typography.titleLarge)
                    Text(s.onboardingProviderDesc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        ProviderPresets.all.take(4).forEach { preset ->
                            FilterChip(
                                selected = selectedPreset.id == preset.id,
                                onClick = {
                                    selectedPreset = preset
                                    providerName = preset.name
                                    baseUrl = preset.baseUrl
                                    defaultModel = preset.defaultModel
                                },
                                label = { Text(preset.name.substringAfter(" ")) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = providerName,
                        onValueChange = { providerName = it },
                        label = { Text(s.providerName) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text(s.apiBaseUrl) },
                        textStyle = MonoTextStyle,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = defaultModel,
                        onValueChange = { defaultModel = it },
                        label = { Text(s.defaultModel) },
                        textStyle = MonoTextStyle,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                3 -> {
                    // Step 4: API Key Setup
                    Text(s.onboardingKeyTitle, style = MaterialTheme.typography.titleLarge)
                    Text(s.onboardingKeyDesc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        label = { Text("$providerName ${s.apiKeyLabel}") },
                        textStyle = MonoTextStyle,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                4 -> {
                    // Step 5: Connection Test
                    Text(s.onboardingTestTitle, style = MaterialTheme.typography.titleLarge)

                    GatewayCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(s.gatewayStatus, style = MaterialTheme.typography.titleSmall)
                                Text(runtime.boundAddress, style = MonoTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            val (gwLabel, gwTone) = gatewayStatusLabelAndTone(runtime.status)
                            StatusPill(
                                text = gwLabel,
                                tone = gwTone
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                val runTest: () -> Unit = {
                                    if (!runtime.isActive &&
                                        runtime.status != GatewayRunStatus.STARTING &&
                                        runtime.status != GatewayRunStatus.REMOTE_CONNECTING
                                    ) {
                                        viewModel.toggleGatewayServer()
                                    }
                                    providers.firstOrNull()?.let { viewModel.testProviderConnection(it) }
                                }
                                if (settings.gatewayMode == GatewayMode.LOCAL &&
                                    !runtime.isActive &&
                                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    pendingLocalStart = runTest
                                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    runTest()
                                }
                            },
                            modifier = Modifier.fillMaxWidth().testTag("onboarding_run_test")
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(s.runConnectionTest)
                        }
                    }

                    providers.forEach { p ->
                        GatewayCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(p.name, style = MaterialTheme.typography.titleSmall)
                                Text(p.status.name, style = MaterialTheme.typography.labelMedium)
                            }
                            p.lastError?.let {
                                Spacer(Modifier.height(4.dp))
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                5 -> {
                    // Step 6: Summary
                    Text(s.onboardingSummaryTitle, style = MaterialTheme.typography.titleLarge)
                    GatewayCard {
                        Text("${s.gatewaySection}: ${settings.gatewayMode.name}", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(6.dp))
                        Text("${s.totalProviders}: ${providers.size}", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(s.tagline, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

        }
    }
}
