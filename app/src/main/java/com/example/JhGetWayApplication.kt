package com.example

import android.app.Application
import com.example.data.local.AppPreferences
import com.example.data.local.BackupManager
import com.example.data.local.JhGetWayDatabase
import com.example.gateway.LocalGatewayHttpServer
import com.example.gateway.SmartRouterEngine
import com.example.gateway.WorkflowEngine
import com.example.network.McpHttpClient
import com.example.network.ProviderHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class JhGetWayApplication : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database by lazy { JhGetWayDatabase.getInstance(this) }
    val preferences by lazy { AppPreferences(this) }
    val providerHttpClient by lazy { ProviderHttpClient() }
    val mcpHttpClient by lazy { McpHttpClient() }
    val smartRouter by lazy { SmartRouterEngine(database, providerHttpClient) }
    val gatewayServer by lazy { LocalGatewayHttpServer(database, smartRouter, preferences) }
    val workflowEngine by lazy { WorkflowEngine(database, smartRouter, mcpHttpClient) }
    val backupManager by lazy { BackupManager(database, preferences) }

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            // Seed built-in editable workflow templates if not yet present
            val existing = database.workflowDao().getWorkflowsOnce()
            if (existing.none { it.isTemplate }) {
                workflowEngine.builtInTemplates().forEach { tpl ->
                    database.workflowDao().upsertWorkflow(tpl)
                }
            }
        }
    }
}
