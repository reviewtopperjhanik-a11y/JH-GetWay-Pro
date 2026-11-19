package com.example.ui.navigation

import android.net.Uri

object Routes {
    const val DASHBOARD = "dashboard"
    const val PROVIDERS = "providers"
    const val PROVIDERS_ALL = "providers/all"
    const val PROVIDER_DETAIL_PATTERN = "providers/detail/{groupKey}"
    const val MODELS = "models"
    const val GATEWAY = "gateway"
    const val GATEWAY_CONFIG = "settings/gateway_config"
    const val SMART_ROUTER = "smart_router"
    const val CODING_AGENTS = "coding_agents"
    const val LOCAL_AI = "local_ai"
    const val MCP_SERVERS = "mcp_servers"
    const val WORKFLOWS = "workflows"
    const val PLAYGROUND = "playground"
    const val ANALYTICS = "analytics"
    const val DEVICES = "devices"
    const val VAULT = "vault"
    const val SEARCH_POOLS = "search_pools"
    const val ACTIVITY_LOGS = "activity_logs"
    const val SETTINGS = "settings"

    fun providerDetail(groupKey: String): String =
        "providers/detail/${Uri.encode(groupKey)}"
}
