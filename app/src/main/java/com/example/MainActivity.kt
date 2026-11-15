package com.example

import android.content.Intent
import android.hardware.biometrics.BiometricPrompt
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.core.i18n.AppLanguage
import com.example.core.i18n.BengaliStrings
import com.example.core.i18n.EnglishStrings
import com.example.core.i18n.LocalStrings
import com.example.ui.AppLockUiState
import com.example.ui.GatewayViewModel
import com.example.ui.components.StatusPill
import com.example.ui.components.gatewayStatusLabelAndTone
import com.example.ui.navigation.Routes
import com.example.ui.screens.ActivityLogsScreen
import com.example.ui.screens.AnalyticsScreen
import com.example.ui.screens.CodingAgentsScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.DevicesScreen
import com.example.ui.screens.GatewayScreen
import com.example.ui.screens.LocalAiScreen
import com.example.ui.screens.McpServersScreen
import com.example.ui.screens.ModelsScreen
import com.example.ui.screens.OnboardingScreen
import com.example.ui.screens.PlaygroundScreen
import com.example.ui.screens.ProvidersScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SmartRouterScreen
import com.example.ui.screens.VaultScreen
import com.example.ui.screens.WorkflowsScreen
import com.example.ui.theme.JhGetWayTheme
import kotlinx.coroutines.delay
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val viewModel: GatewayViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JhGetWayRootApp(
                viewModel = viewModel,
                onRequestBiometricUnlock = { triggerBiometricUnlock() }
            )
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            viewModel.onAppBackgrounded()
        }
    }

    private fun triggerBiometricUnlock() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        runCatching {
            val prompt = BiometricPrompt.Builder(this)
                .setTitle(getString(R.string.app_name))
                .setNegativeButton(
                    getString(android.R.string.cancel),
                    mainExecutor
                ) { _, _ -> }
                .build()
            prompt.authenticate(
                CancellationSignal(),
                mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                        viewModel.unlockWithBiometric()
                    }
                }
            )
        }
    }
}

private data class NavMenuItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val testTag: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JhGetWayRootApp(
    viewModel: GatewayViewModel,
    onRequestBiometricUnlock: () -> Unit
) {
    val settings by viewModel.settings.collectAsState()
    val isInitialLoaded by viewModel.isInitialSettingsLoaded.collectAsState()
    val isUnlocked by viewModel.isAppUnlocked.collectAsState()
    val lockUiState by viewModel.appLockUiState.collectAsState()

    val strings = remember(settings.language) {
        when (settings.language) {
            AppLanguage.ENGLISH -> EnglishStrings
            AppLanguage.BENGALI -> BengaliStrings
            AppLanguage.SYSTEM -> {
                if (Locale.getDefault().language.equals("bn", ignoreCase = true)) {
                    BengaliStrings
                } else {
                    EnglishStrings
                }
            }
        }
    }

    CompositionLocalProvider(LocalStrings provides strings) {
        JhGetWayTheme(themeMode = settings.themeMode) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                when {
                    !isInitialLoaded -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    !isUnlocked -> {
                        AppLockGateScreen(
                            lockUiState = lockUiState,
                            biometricEnabled = settings.appLockBiometricEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P,
                            onVerifyPin = { viewModel.verifyUnlockPin(it) },
                            onClearError = { viewModel.clearPinInputError() },
                            onUseBiometric = onRequestBiometricUnlock
                        )
                    }

                    !settings.onboardingCompleted -> {
                        OnboardingScreen(
                            viewModel = viewModel,
                            onCompleted = {}
                        )
                    }

                    else -> {
                        MainGatewayScaffold(viewModel = viewModel)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppLockGateScreen(
    lockUiState: AppLockUiState,
    biometricEnabled: Boolean,
    onVerifyPin: (String) -> Unit,
    onClearError: () -> Unit,
    onUseBiometric: () -> Unit
) {
    val s = LocalStrings.current
    var pinInput by remember { mutableStateOf("") }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }

    val isLockedOut = lockUiState.lockedUntilEpochMs > nowMs
    val remainingSec = ((lockUiState.lockedUntilEpochMs - nowMs).coerceAtLeast(0L) + 999L) / 1000L

    LaunchedEffect(lockUiState.lockedUntilEpochMs) {
        while (lockUiState.lockedUntilEpochMs > System.currentTimeMillis()) {
            nowMs = System.currentTimeMillis()
            delay(500L)
        }
        nowMs = System.currentTimeMillis()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = s.appLockLabel,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = s.appName,
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = s.appLockEnterPin,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = pinInput,
                onValueChange = { next ->
                    if (next.length <= 8 && next.all { it.isDigit() }) {
                        pinInput = next
                        onClearError()
                    }
                },
                label = { Text(s.appLockEnterPin) },
                singleLine = true,
                enabled = !isLockedOut && !lockUiState.isVerifying,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (!isLockedOut && pinInput.length >= 4) {
                            onVerifyPin(pinInput)
                        }
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("app_lock_pin_input")
            )

            if (isLockedOut) {
                Text(
                    text = "${s.appLockLockedOut} (${remainingSec}s)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            } else if (lockUiState.isInvalid) {
                Text(
                    text = s.appLockIncorrect,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }

            Button(
                onClick = { onVerifyPin(pinInput) },
                enabled = !isLockedOut && !lockUiState.isVerifying && pinInput.length >= 4,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("app_lock_unlock_btn")
            ) {
                if (lockUiState.isVerifying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(s.appLockUnlock)
                }
            }

            if (biometricEnabled) {
                OutlinedButton(
                    onClick = onUseBiometric,
                    enabled = !isLockedOut,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Fingerprint, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(s.appLockUseBiometric)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainGatewayScaffold(viewModel: GatewayViewModel) {
    val s = LocalStrings.current
    val context = LocalContext.current
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: Routes.DASHBOARD

    val bannerMessage by viewModel.uiBannerMessage.collectAsState()
    val runtime by viewModel.gatewayRuntime.collectAsState()
    val pendingMcpApproval by viewModel.pendingMcpApproval.collectAsState()
    val updateState by viewModel.appUpdateUiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showMoreSheet by remember { mutableStateOf(false) }

    LaunchedEffect(bannerMessage) {
        val msg = bannerMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearBanner()
        }
    }

    val navigateTo: (String) -> Unit = { route ->
        showMoreSheet = false
        if (currentRoute != route) {
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    val primaryTabs = listOf(
        NavMenuItem(Routes.DASHBOARD, s.navHome, Icons.Default.Home, "bottom_nav_dashboard"),
        NavMenuItem(Routes.PROVIDERS, s.navProviders, Icons.Default.Cloud, "bottom_nav_providers"),
        NavMenuItem(Routes.PLAYGROUND, s.navPlayground, Icons.Default.Chat, "bottom_nav_playground"),
        NavMenuItem(Routes.ANALYTICS, s.navAnalytics, Icons.Default.Analytics, "bottom_nav_analytics")
    )

    val secondaryDestinations = listOf(
        NavMenuItem(Routes.MODELS, s.navModels, Icons.Default.Memory, "more_nav_models"),
        NavMenuItem(Routes.GATEWAY, s.navGateway, Icons.Default.Hub, "more_nav_gateway"),
        NavMenuItem(Routes.SMART_ROUTER, s.navSmartRouter, Icons.AutoMirrored.Filled.AltRoute, "more_nav_smart_router"),
        NavMenuItem(Routes.CODING_AGENTS, s.navCodingAgents, Icons.Default.Code, "more_nav_coding_agents"),
        NavMenuItem(Routes.LOCAL_AI, s.navLocalAi, Icons.Default.Computer, "more_nav_local_ai"),
        NavMenuItem(Routes.MCP_SERVERS, s.navMcpServers, Icons.Default.Extension, "more_nav_mcp"),
        NavMenuItem(Routes.WORKFLOWS, s.navWorkflows, Icons.Default.AccountTree, "more_nav_workflows"),
        NavMenuItem(Routes.DEVICES, s.navDevices, Icons.Default.Devices, "more_nav_devices"),
        NavMenuItem(Routes.VAULT, s.navVault, Icons.Default.VpnKey, "more_nav_vault"),
        NavMenuItem(Routes.ACTIVITY_LOGS, s.navActivityLogs, Icons.Default.History, "more_nav_logs"),
        NavMenuItem(Routes.SETTINGS, s.navSettings, Icons.Default.Settings, "more_nav_settings")
    )

    val isPrimaryRoute = primaryTabs.any { it.route == currentRoute }
    val currentTitle = remember(currentRoute, s) {
        when (currentRoute) {
            Routes.DASHBOARD -> s.appName
            Routes.PROVIDERS -> s.navProviders
            Routes.MODELS -> s.navModels
            Routes.GATEWAY -> s.navGateway
            Routes.SMART_ROUTER -> s.navSmartRouter
            Routes.CODING_AGENTS -> s.navCodingAgents
            Routes.LOCAL_AI -> s.navLocalAi
            Routes.MCP_SERVERS -> s.navMcpServers
            Routes.WORKFLOWS -> s.navWorkflows
            Routes.PLAYGROUND -> s.navPlayground
            Routes.ANALYTICS -> s.navAnalytics
            Routes.DEVICES -> s.navDevices
            Routes.VAULT -> s.navVault
            Routes.ACTIVITY_LOGS -> s.navActivityLogs
            Routes.SETTINGS -> s.navSettings
            else -> s.appName
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = currentTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    if (!isPrimaryRoute) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = s.back
                            )
                        }
                    }
                },
                actions = {
                    val (gwLabel, gwTone) = gatewayStatusLabelAndTone(runtime.status)
                    Box(
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .clickable { navigateTo(Routes.GATEWAY) }
                    ) {
                        StatusPill(text = gwLabel, tone = gwTone)
                    }
                    IconButton(onClick = { navigateTo(Routes.SETTINGS) }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = s.navSettings
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                primaryTabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = { navigateTo(tab.route) },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.testTag(tab.testTag)
                    )
                }
                NavigationBarItem(
                    selected = !isPrimaryRoute,
                    onClick = { showMoreSheet = true },
                    icon = { Icon(Icons.Default.Apps, contentDescription = s.navMore) },
                    label = { Text(s.navMore, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.testTag("bottom_nav_more")
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.DASHBOARD,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            composable(Routes.DASHBOARD) {
                DashboardScreen(viewModel = viewModel, onNavigate = navigateTo)
            }
            composable(Routes.PROVIDERS) {
                ProvidersScreen(viewModel = viewModel, onNavigate = navigateTo)
            }
            composable(Routes.MODELS) {
                ModelsScreen(viewModel = viewModel, onNavigate = navigateTo)
            }
            composable(Routes.GATEWAY) {
                GatewayScreen(viewModel = viewModel)
            }
            composable(Routes.SMART_ROUTER) {
                SmartRouterScreen(viewModel = viewModel)
            }
            composable(Routes.CODING_AGENTS) {
                CodingAgentsScreen(viewModel = viewModel)
            }
            composable(Routes.LOCAL_AI) {
                LocalAiScreen(viewModel = viewModel)
            }
            composable(Routes.MCP_SERVERS) {
                McpServersScreen(viewModel = viewModel)
            }
            composable(Routes.WORKFLOWS) {
                WorkflowsScreen(viewModel = viewModel)
            }
            composable(Routes.PLAYGROUND) {
                PlaygroundScreen(viewModel = viewModel, onNavigate = navigateTo)
            }
            composable(Routes.ANALYTICS) {
                AnalyticsScreen(viewModel = viewModel)
            }
            composable(Routes.DEVICES) {
                DevicesScreen(viewModel = viewModel)
            }
            composable(Routes.VAULT) {
                VaultScreen(viewModel = viewModel)
            }
            composable(Routes.ACTIVITY_LOGS) {
                ActivityLogsScreen(viewModel = viewModel)
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(viewModel = viewModel, onNavigate = navigateTo)
            }
        }
    }

    if (showMoreSheet) {
        ModalBottomSheet(
            onDismissRequest = { showMoreSheet = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = s.navMore,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp)
                ) {
                    items(secondaryDestinations) { item ->
                        val selected = currentRoute == item.route
                        Surface(
                            onClick = { navigateTo(item.route) },
                            shape = MaterialTheme.shapes.medium,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(item.testTag)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = null,
                                    tint = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                                Text(
                                    text = item.label,
                                    style = MaterialTheme.typography.labelLarge,
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

    pendingMcpApproval?.let { pending ->
        AlertDialog(
            onDismissRequest = { viewModel.resolveMcpApproval(false) },
            title = { Text(s.approvalRequiredTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(s.approvalRequiredMessage)
                    Text(
                        text = "${pending.server.name} • ${pending.tool.toolName}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.resolveMcpApproval(true) }) {
                    Text(s.approveAndRun)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.resolveMcpApproval(false) }) {
                    Text(s.cancel)
                }
            }
        )
    }

    val availableRelease = updateState.availableRelease
    if (updateState.showPromptDialog && availableRelease != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissUpdateDialog() },
            title = { Text("${s.updateAvailableTitle}: ${availableRelease.tagName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (availableRelease.releaseNotes.isNotBlank()) {
                        Text(
                            text = availableRelease.releaseNotes.take(400),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (updateState.isDownloading) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${s.downloadingUpdateLabel} ${updateState.downloadProgressPct}%",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.downloadAndInstallUpdate() },
                    enabled = !updateState.isDownloading
                ) {
                    Text(s.downloadAndInstallAction)
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { viewModel.skipCurrentUpdate() }) {
                        Text(s.skipThisVersionLabel)
                    }
                    TextButton(onClick = { viewModel.dismissUpdateDialog() }) {
                        Text(s.laterLabel)
                    }
                }
            }
        )
    }

    if (updateState.needsInstallPermission) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissInstallPermissionPrompt() },
            title = { Text(s.installPermissionTitle) },
            text = { Text(s.installPermissionMessage) },
            confirmButton = {
                Button(onClick = {
                    viewModel.dismissInstallPermissionPrompt()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        runCatching {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${context.packageName}")
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        }
                    }
                }) {
                    Text(s.openSettingsLabel)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissInstallPermissionPrompt() }) {
                    Text(s.cancel)
                }
            }
        )
    }
}
