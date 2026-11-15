package com.example.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.JhGetWayApplication
import com.example.MainActivity
import com.example.R
import com.example.data.local.ActivityCategory
import com.example.data.local.ActivityLogEntity
import com.example.data.local.GatewayMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Owns the long-running Android-hosted Local Gateway foreground lifecycle.
 * Delegates server start/stop/restart to the Application-owned [LocalGatewayHttpServer]
 * singleton to prevent duplicate bound instances across ViewModels or configuration changes.
 */
class GatewayForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var stateObserverJob: Job? = null
    private var commandJob: Job? = null

    private val appInstance: JhGetWayApplication
        get() = application as JhGetWayApplication

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        stateObserverJob = serviceScope.launch {
            appInstance.gatewayServer.runtimeState.collect { state ->
                if (state.status == GatewayRunStatus.LOCAL_RUNNING || state.status == GatewayRunStatus.STARTING) {
                    val manager = getSystemService(NotificationManager::class.java)
                    manager?.notify(NOTIFICATION_ID, buildNotification(state))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val currentState = appInstance.gatewayServer.runtimeState.value

        when (action) {
            ACTION_STOP -> {
                promoteForeground(currentState)
                commandJob?.cancel()
                commandJob = serviceScope.launch {
                    appInstance.preferences.setGatewayServiceDesiredRunning(false)
                    appInstance.gatewayServer.stop()
                    ServiceCompat.stopForeground(this@GatewayForegroundService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }

            ACTION_RESTART -> {
                promoteForeground(currentState.copy(status = GatewayRunStatus.STARTING))
                commandJob?.cancel()
                commandJob = serviceScope.launch {
                    val settings = appInstance.preferences.settingsFlow.first()
                    if (settings.gatewayMode != GatewayMode.LOCAL) {
                        appInstance.preferences.setGatewayServiceDesiredRunning(false)
                        appInstance.gatewayServer.stop()
                        ServiceCompat.stopForeground(this@GatewayForegroundService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return@launch
                    }
                    appInstance.preferences.setGatewayServiceDesiredRunning(true)
                    appInstance.gatewayServer.restart(settings)
                    val after = appInstance.gatewayServer.runtimeState.value
                    if (after.status == GatewayRunStatus.LOCAL_RUNNING) {
                        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(after))
                    } else {
                        appInstance.preferences.setGatewayServiceDesiredRunning(false)
                        ServiceCompat.stopForeground(this@GatewayForegroundService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
                return START_STICKY
            }

            ACTION_START -> {
                promoteForeground(currentState.copy(status = GatewayRunStatus.STARTING))
                commandJob?.cancel()
                commandJob = serviceScope.launch {
                    val settings = appInstance.preferences.settingsFlow.first()
                    if (settings.gatewayMode != GatewayMode.LOCAL) {
                        appInstance.preferences.setGatewayServiceDesiredRunning(false)
                        ServiceCompat.stopForeground(this@GatewayForegroundService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return@launch
                    }
                    appInstance.preferences.setGatewayServiceDesiredRunning(true)
                    appInstance.gatewayServer.start(settings)
                    val after = appInstance.gatewayServer.runtimeState.value
                    if (after.status == GatewayRunStatus.LOCAL_RUNNING) {
                        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(after))
                    } else {
                        appInstance.preferences.setGatewayServiceDesiredRunning(false)
                        ServiceCompat.stopForeground(this@GatewayForegroundService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
                return START_STICKY
            }

            null -> {
                // Process death / START_STICKY service recreation
                promoteForeground(currentState.copy(status = GatewayRunStatus.STARTING))
                commandJob?.cancel()
                commandJob = serviceScope.launch {
                    val settings = appInstance.preferences.settingsFlow.first()
                    if (settings.gatewayServiceDesiredRunning && settings.gatewayMode == GatewayMode.LOCAL) {
                        appInstance.database.metricAndLogDao().insertLog(
                            ActivityLogEntity(
                                category = ActivityCategory.SYSTEM_EVENT,
                                relatedEntity = settings.gatewayName,
                                status = "SERVICE_RECREATED",
                                safeMessage = "Local Gateway service recreated after OS kill; restoring listener"
                            )
                        )
                        appInstance.gatewayServer.start(settings)
                        val after = appInstance.gatewayServer.runtimeState.value
                        if (after.status == GatewayRunStatus.LOCAL_RUNNING) {
                            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(after))
                        } else {
                            appInstance.preferences.setGatewayServiceDesiredRunning(false)
                            ServiceCompat.stopForeground(this@GatewayForegroundService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    } else {
                        ServiceCompat.stopForeground(this@GatewayForegroundService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
                return START_STICKY
            }

            else -> {
                promoteForeground(currentState)
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        val current = appInstance.gatewayServer.runtimeState.value
        if (current.mode == GatewayMode.LOCAL &&
            (current.status == GatewayRunStatus.LOCAL_RUNNING || current.status == GatewayRunStatus.STARTING)
        ) {
            appInstance.appScope.launch {
                appInstance.gatewayServer.stop()
            }
        }
        stateObserverJob?.cancel()
        commandJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun promoteForeground(state: GatewayRuntimeState) {
        ensureNotificationChannel()
        val fgsType = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(state), fgsType)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Local AI Gateway Service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Persistent status and controls for the JH GetWay local HTTP server"
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    fun buildNotification(state: GatewayRuntimeState): Notification {
        val statusText = when (state.status) {
            GatewayRunStatus.LOCAL_RUNNING -> "Running on ${state.boundAddress} • Active: ${state.activeRequests}"
            GatewayRunStatus.STARTING -> "Starting local gateway on ${state.boundAddress}..."
            GatewayRunStatus.ERROR -> state.lastError ?: "Gateway error"
            GatewayRunStatus.STOPPED -> "Stopped"
            else -> "${state.status.name} (${state.boundAddress})"
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_gateway)
            .setContentTitle("JH GetWay Local Gateway")
            .setContentText(statusText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(statusText))
            .setOngoing(state.status == GatewayRunStatus.LOCAL_RUNNING || state.status == GatewayRunStatus.STARTING)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)

        val isRunningOrStarting = state.status == GatewayRunStatus.LOCAL_RUNNING || state.status == GatewayRunStatus.STARTING
        if (isRunningOrStarting) {
            builder.addAction(
                NotificationCompat.Action.Builder(
                    0,
                    "Stop",
                    actionPendingIntent(ACTION_STOP, 101)
                ).build()
            )
        } else {
            builder.addAction(
                NotificationCompat.Action.Builder(
                    0,
                    "Start",
                    actionPendingIntent(ACTION_START, 102)
                ).build()
            )
        }
        builder.addAction(
            NotificationCompat.Action.Builder(
                0,
                "Restart",
                actionPendingIntent(ACTION_RESTART, 103)
            ).build()
        )
        return builder.build()
    }

    private fun actionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, GatewayForegroundService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val ACTION_START = "com.example.gateway.action.START"
        const val ACTION_STOP = "com.example.gateway.action.STOP"
        const val ACTION_RESTART = "com.example.gateway.action.RESTART"
        const val NOTIFICATION_CHANNEL_ID = "jh_getway_gateway_service"
        const val NOTIFICATION_ID = 1001

        fun startGatewayIntent(context: Context): Intent =
            Intent(context, GatewayForegroundService::class.java).setAction(ACTION_START)

        fun stopGatewayIntent(context: Context): Intent =
            Intent(context, GatewayForegroundService::class.java).setAction(ACTION_STOP)

        fun restartGatewayIntent(context: Context): Intent =
            Intent(context, GatewayForegroundService::class.java).setAction(ACTION_RESTART)

        fun dispatchStart(context: Context) {
            ContextCompat.startForegroundService(context, startGatewayIntent(context))
        }

        fun dispatchStop(context: Context) {
            val intent = stopGatewayIntent(context)
            runCatching { context.startService(intent) }.onFailure {
                runCatching { context.stopService(Intent(context, GatewayForegroundService::class.java)) }
            }
        }

        fun dispatchRestart(context: Context) {
            ContextCompat.startForegroundService(context, restartGatewayIntent(context))
        }
    }
}
