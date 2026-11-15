@file:OptIn(ExperimentalLayoutApi::class)

package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.i18n.LocalStrings
import com.example.data.local.*
import com.example.gateway.WorkflowEdge
import com.example.gateway.WorkflowNode
import com.example.gateway.WorkflowNodeType
import com.example.network.McpHttpClient
import com.example.ui.GatewayViewModel
import com.example.ui.components.*
import com.example.ui.theme.MonoTextStyle
import java.util.UUID

@Composable
fun McpServersScreen(viewModel: GatewayViewModel) {
    val s = LocalStrings.current
    val servers by viewModel.mcpServers.collectAsState()
    val tools by viewModel.mcpTools.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var runToolTarget by remember { mutableStateOf<Pair<McpServerEntity, McpToolEntity>?>(null) }
    var deletingServer by remember { mutableStateOf<McpServerEntity?>(null) }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                icon = { Icon(Icons.Default.Add, contentDescription = s.addMcpServer) },
                text = { Text(s.addMcpServer) },
                modifier = Modifier.testTag("add_mcp_fab")
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(s.mcpTitle, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text(s.mcpStdioSandboxNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (servers.isEmpty()) {
                item {
                    EmptyStatePanel(
                        icon = Icons.Default.Extension,
                        title = s.mcpTitle,
                        message = s.noMcpServersMessage,
                        actionLabel = s.addMcpServer,
                        onAction = { showAddDialog = true }
                    )
                }
            } else {
                items(servers, key = { it.id }) { srv ->
                    val srvTools = tools.filter { it.serverId == srv.id }
                    val (stTxt, tone) = providerStatusLabelAndTone(srv.status)
                    GatewayCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(srv.name, style = MaterialTheme.typography.titleMedium)
                                Text("${srv.transportType.name} • ${srvTools.size} ${s.toolsCountUnit}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            StatusPill(text = stTxt, tone = tone)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(srv.endpointOrCommand, style = MonoTextStyle, color = MaterialTheme.colorScheme.primary)
                        srv.lastError?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilledTonalButton(onClick = { viewModel.connectAndDiscoverMcp(srv) }) { Text(s.discoverTools) }
                            OutlinedButton(onClick = { viewModel.disconnectMcpServer(srv) }) { Text(s.disconnected) }
                            IconButton(onClick = { deletingServer = srv }) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = s.delete, tint = MaterialTheme.colorScheme.error)
                            }
                        }

                        if (srvTools.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(8.dp))
                            srvTools.forEach { tool ->
                                val isDangerous = McpHttpClient.isDangerousTool(tool.toolName, tool.description)
                                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(tool.toolName, style = MonoTextStyle, color = MaterialTheme.colorScheme.onSurface)
                                                if (isDangerous) {
                                                    StatusPill(text = s.dangerousToolBadge, tone = StatusTone.WARNING)
                                                }
                                            }
                                            if (tool.description.isNotBlank()) {
                                                Text(tool.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                        OutlinedButton(onClick = { runToolTarget = srv to tool }) { Text(s.executeTool) }
                                    }
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                                        items(ToolPermission.entries) { perm ->
                                            FilterChip(
                                                selected = tool.permission == perm,
                                                onClick = { viewModel.updateToolPermission(tool, perm) },
                                                label = { Text(perm.name.replace("_", " "), style = MaterialTheme.typography.labelSmall) }
                                            )
                                        }
                                    }
                                    if (tool.lastResultPreview.isNotBlank()) {
                                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.extraSmall, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                            Text(tool.lastResultPreview.take(260), style = MonoTextStyle, modifier = Modifier.padding(8.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        var name by remember { mutableStateOf("") }
        var transport by remember { mutableStateOf(McpTransportType.STREAMABLE_HTTP) }
        var endpoint by remember { mutableStateOf("https://") }
        var args by remember { mutableStateOf("") }
        var env by remember { mutableStateOf("") }
        var token by remember { mutableStateOf("") }
        var timeoutText by remember { mutableStateOf("30") }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(s.addMcpServer) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(McpTransportType.entries) { t ->
                            FilterChip(selected = transport == t, onClick = { transport = t }, label = { Text(t.name) })
                        }
                    }
                    if (transport == McpTransportType.STDIO) {
                        Text(s.mcpStdioSandboxNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(s.serverName) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = endpoint, onValueChange = { endpoint = it }, label = { Text(s.commandOrUrl) }, textStyle = MonoTextStyle, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (transport == McpTransportType.STDIO) {
                        OutlinedTextField(value = args, onValueChange = { args = it }, label = { Text(s.argumentsLabel) }, textStyle = MonoTextStyle, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = env, onValueChange = { env = it }, label = { Text(s.envVarsLabel) }, textStyle = MonoTextStyle, minLines = 2, modifier = Modifier.fillMaxWidth())
                    } else {
                        OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("${s.bearerTokenLabel} (${s.optional})") }, textStyle = MonoTextStyle, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    OutlinedTextField(value = timeoutText, onValueChange = { timeoutText = it.filter { c -> c.isDigit() } }, label = { Text(s.requestTimeoutSec) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (name.isNotBlank() && endpoint.isNotBlank()) {
                        viewModel.saveMcpServer(name, transport, endpoint, args, env, token, timeoutText.toIntOrNull() ?: 30)
                        showAddDialog = false
                    }
                }) { Text(s.save) }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text(s.cancel) } }
        )
    }

    runToolTarget?.let { (srv, tool) ->
        var argsJson by remember { mutableStateOf("{}") }
        AlertDialog(
            onDismissRequest = { runToolTarget = null },
            title = { Text("${s.executeTool}: ${tool.toolName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${s.inputSchemaLabel}: ${tool.inputSchemaJson.take(180)}", style = MonoTextStyle)
                    OutlinedTextField(value = argsJson, onValueChange = { argsJson = it }, label = { Text(s.jsonArgumentsLabel) }, textStyle = MonoTextStyle, minLines = 3, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.executeMcpTool(srv, tool, argsJson); runToolTarget = null }) { Text(s.executeTool) }
            },
            dismissButton = { TextButton(onClick = { runToolTarget = null }) { Text(s.cancel) } }
        )
    }

    deletingServer?.let { srv ->
        ConfirmDeleteDialog(
            title = "${s.delete} ${srv.name}?",
            message = s.deleteConfirmMessage,
            onConfirm = { viewModel.deleteMcpServer(srv); deletingServer = null },
            onDismiss = { deletingServer = null }
        )
    }
}

@Composable
fun WorkflowsScreen(viewModel: GatewayViewModel) {
    val s = LocalStrings.current
    val workflows by viewModel.workflows.collectAsState()
    val runs by viewModel.workflowRuns.collectAsState()
    val progress by viewModel.workflowNodeProgress.collectAsState()
    val isRunning by viewModel.isWorkflowRunning.collectAsState()

    var selectedWorkflow by remember { mutableStateOf<WorkflowEntity?>(null) }
    var editingWorkflow by remember { mutableStateOf<WorkflowEntity?>(null) }
    var runInputText by remember { mutableStateOf("Analyze how JH GetWay handles multi-provider fallback.") }

    LaunchedEffect(workflows) {
        if (selectedWorkflow == null && workflows.isNotEmpty()) {
            selectedWorkflow = workflows.first()
        } else if (selectedWorkflow != null) {
            selectedWorkflow = workflows.firstOrNull { it.id == selectedWorkflow?.id } ?: workflows.firstOrNull()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(s.workflowsTitle, style = MaterialTheme.typography.headlineSmall)
                FilledTonalButton(onClick = {
                    val newNodes = listOf(
                        WorkflowNode("n1", WorkflowNodeType.INPUT, "Input"),
                        WorkflowNode("n2", WorkflowNodeType.AI_MODEL, "AI Processor", mapOf("prompt" to "{{input}}")),
                        WorkflowNode("n3", WorkflowNodeType.OUTPUT, "Output")
                    )
                    val newEdges = listOf(WorkflowEdge("e1", "n1", "n2"), WorkflowEdge("e2", "n2", "n3"))
                    val (nj, ej) = viewModel.workflowEngine.serializeGraph(newNodes, newEdges)
                    val wf = WorkflowEntity(name = "Custom Workflow ${workflows.size + 1}", description = "User-created AI pipeline", nodesJson = nj, edgesJson = ej)
                    viewModel.saveWorkflow(wf)
                    selectedWorkflow = wf
                    editingWorkflow = wf
                }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(s.createWorkflow)
                }
            }
        }

        // Workflow & Templates selector strip
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(workflows, key = { it.id }) { wf ->
                    FilterChip(
                        selected = selectedWorkflow?.id == wf.id,
                        onClick = { selectedWorkflow = wf },
                        label = { Text(if (wf.isTemplate) "★ ${wf.name}" else "${wf.name} (${s.versionPrefix}${wf.version})") }
                    )
                }
            }
        }

        selectedWorkflow?.let { wf ->
            val (nodes, edges) = viewModel.workflowEngine.parseGraph(wf.nodesJson, wf.edgesJson)
            val validation = viewModel.workflowEngine.validateGraph(nodes, edges)

            item {
                GatewayCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(wf.name, style = MaterialTheme.typography.titleMedium)
                                StatusPill(text = "${s.versionPrefix}${wf.version}", tone = StatusTone.INFO)
                            }
                            Text(wf.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        wf.lastRunStatus?.let { st ->
                            StatusPill(text = st, tone = if (st == "COMPLETED") StatusTone.SUCCESS else StatusTone.ERROR)
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    // Visual DAG Flow with Incoming Dependencies & Retries
                    nodes.forEachIndexed { idx, node ->
                        val nodeProg = progress.firstOrNull { it.nodeId == node.id }
                        val incoming = edges.filter { it.toNodeId == node.id }
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("${idx + 1}. ${node.title}", style = MaterialTheme.typography.titleSmall)
                                        val retries = node.config["retryCount"]?.toIntOrNull()?.takeIf { it > 0 }
                                        Text(
                                            text = buildString {
                                                append(node.type.name)
                                                if (retries != null) append(" • retry×$retries")
                                            },
                                            style = MonoTextStyle,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    if (nodeProg != null) {
                                        StatusPill(
                                            text = if (nodeProg.durationMs > 0) "${nodeProg.status} (${nodeProg.durationMs}ms)" else nodeProg.status,
                                            tone = when (nodeProg.status) {
                                                "COMPLETED" -> StatusTone.SUCCESS
                                                "FAILED" -> StatusTone.ERROR
                                                "RUNNING" -> StatusTone.INFO
                                                else -> StatusTone.NEUTRAL
                                            }
                                        )
                                    }
                                }
                                if (incoming.isNotEmpty()) {
                                    val fromSummary = incoming.joinToString(", ") { e ->
                                        val fromName = nodes.firstOrNull { it.id == e.fromNodeId }?.title ?: e.fromNodeId
                                        if (e.branchLabel.isNotBlank()) "$fromName [${e.branchLabel}]" else fromName
                                    }
                                    Text("↳ $fromSummary → ${node.title}", style = MonoTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        if (idx < nodes.lastIndex) {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.ArrowDownward, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    if (!validation.valid) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = if (validation.errorKey == "CYCLE_DETECTED") s.cycleDetectedError else (validation.details ?: s.missingStartEndError),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = runInputText,
                        onValueChange = { runInputText = it },
                        label = { Text(s.workflowInputLabel) },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            enabled = validation.valid && !isRunning,
                            onClick = { viewModel.runWorkflow(wf, runInputText) },
                            modifier = Modifier.testTag("run_workflow_btn")
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(s.runWorkflow)
                        }
                        if (isRunning) {
                            OutlinedButton(onClick = { viewModel.stopWorkflow() }) { Text(s.stopWorkflow) }
                        }
                        OutlinedButton(onClick = { editingWorkflow = wf }) { Text(s.edit) }
                        OutlinedButton(onClick = { viewModel.duplicateWorkflow(wf) }) { Text(s.duplicateLabel) }
                        if (!wf.isTemplate) {
                            IconButton(onClick = { viewModel.deleteWorkflow(wf) }) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = s.delete, tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }

        // Execution History
        item { SectionHeader(title = s.executionHistory) }
        if (runs.isEmpty()) {
            item {
                GatewayCard {
                    Text(s.noWorkflowRunsYet, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items(runs.take(8), key = { it.id }) { run ->
                GatewayCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(run.workflowName, style = MaterialTheme.typography.titleSmall)
                        StatusPill(text = run.status, tone = if (run.status == "COMPLETED") StatusTone.SUCCESS else StatusTone.ERROR)
                    }
                    Text(formatTimestamp(run.startedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (run.finalOutput.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(run.finalOutput.take(400), style = MaterialTheme.typography.bodySmall)
                    }
                    run.errorMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    editingWorkflow?.let { wf ->
        WorkflowGraphEditorDialog(
            workflow = wf,
            viewModel = viewModel,
            onDismiss = { editingWorkflow = null },
            onSave = { updated -> viewModel.saveWorkflow(updated); selectedWorkflow = updated; editingWorkflow = null }
        )
    }
}

@Composable
private fun WorkflowGraphEditorDialog(
    workflow: WorkflowEntity,
    viewModel: GatewayViewModel,
    onDismiss: () -> Unit,
    onSave: (WorkflowEntity) -> Unit
) {
    val s = LocalStrings.current
    val initial = remember(workflow.id) { viewModel.workflowEngine.parseGraph(workflow.nodesJson, workflow.edgesJson) }
    var name by remember { mutableStateOf(workflow.name) }
    var nodes by remember { mutableStateOf(initial.first) }
    var edges by remember { mutableStateOf(initial.second) }
    var addNodeType by remember { mutableStateOf(WorkflowNodeType.AI_MODEL) }
    var newNodeTitle by remember { mutableStateOf("") }
    var newNodePrompt by remember { mutableStateOf("{{input}}") }
    var newNodeRetries by remember { mutableStateOf("0") }
    var editingNodeId by remember { mutableStateOf<String?>(null) }
    var editNodeTitle by remember { mutableStateOf("") }
    var editNodePrimaryCfg by remember { mutableStateOf("") }
    var editNodeRetries by remember { mutableStateOf("0") }
    var connectFrom by remember { mutableStateOf(nodes.firstOrNull()?.id.orEmpty()) }
    var connectTo by remember { mutableStateOf(nodes.lastOrNull()?.id.orEmpty()) }

    val validation = remember(nodes, edges) { viewModel.workflowEngine.validateGraph(nodes, edges) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${s.edit}: ${workflow.name}") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(s.workflowNameLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())

                // Select Existing Node to Edit Config
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(nodes, key = { it.id }) { n ->
                        FilterChip(
                            selected = editingNodeId == n.id,
                            onClick = {
                                if (editingNodeId == n.id) {
                                    editingNodeId = null
                                } else {
                                    editingNodeId = n.id
                                    editNodeTitle = n.title
                                    editNodePrimaryCfg = n.config["prompt"]
                                        ?: n.config["url"]
                                        ?: n.config["contains"]
                                        ?: n.config["toolName"]
                                        ?: ""
                                    editNodeRetries = n.config["retryCount"] ?: "0"
                                }
                            },
                            label = { Text("${n.title} (${n.type.name})") }
                        )
                    }
                }

                editingNodeId?.let { targetId ->
                    val target = nodes.firstOrNull { it.id == targetId }
                    if (target != null) {
                        OutlinedTextField(value = editNodeTitle, onValueChange = { editNodeTitle = it }, label = { Text(s.nodeTitleLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = editNodePrimaryCfg, onValueChange = { editNodePrimaryCfg = it }, label = { Text(s.nodeConfigLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = editNodeRetries, onValueChange = { editNodeRetries = it.filter { c -> c.isDigit() }.take(1) }, label = { Text(s.retryCountLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedButton(onClick = {
                            val updatedCfg = target.config.toMutableMap()
                            val key = when (target.type) {
                                WorkflowNodeType.HTTP_REQUEST -> "url"
                                WorkflowNodeType.CONDITION -> "contains"
                                WorkflowNodeType.MCP_TOOL -> "toolName"
                                else -> "prompt"
                            }
                            if (editNodePrimaryCfg.isNotBlank()) updatedCfg[key] = editNodePrimaryCfg
                            updatedCfg["retryCount"] = (editNodeRetries.toIntOrNull() ?: 0).coerceIn(0, 3).toString()
                            nodes = nodes.map {
                                if (it.id == targetId) it.copy(title = editNodeTitle.ifBlank { it.title }, config = updatedCfg) else it
                            }
                            editingNodeId = null
                        }) { Text(s.save) }
                    }
                }

                HorizontalDivider()
                Text(s.addNode, style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(WorkflowNodeType.entries) { nt ->
                        FilterChip(selected = addNodeType == nt, onClick = { addNodeType = nt }, label = { Text(nt.name) })
                    }
                }
                OutlinedTextField(value = newNodeTitle, onValueChange = { newNodeTitle = it }, label = { Text(s.nodeTitleLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = newNodePrompt, onValueChange = { newNodePrompt = it }, label = { Text(s.nodeConfigLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = newNodeRetries, onValueChange = { newNodeRetries = it.filter { c -> c.isDigit() }.take(1) }, label = { Text(s.retryCountLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = {
                    val nid = "n_${UUID.randomUUID().toString().take(5)}"
                    val retryStr = (newNodeRetries.toIntOrNull() ?: 0).coerceIn(0, 3).toString()
                    val cfg = when (addNodeType) {
                        WorkflowNodeType.HTTP_REQUEST -> mapOf("url" to newNodePrompt, "method" to "GET", "retryCount" to retryStr)
                        WorkflowNodeType.CONDITION -> mapOf("contains" to newNodePrompt, "retryCount" to retryStr)
                        else -> mapOf("prompt" to newNodePrompt, "retryCount" to retryStr)
                    }
                    nodes = nodes + WorkflowNode(nid, addNodeType, newNodeTitle.ifBlank { addNodeType.name }, cfg)
                    newNodeTitle = ""
                }) { Text(s.addNode) }

                HorizontalDivider()
                Text(s.connectNodes, style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(nodes) { n ->
                        FilterChip(selected = connectFrom == n.id, onClick = { connectFrom = n.id }, label = { Text("${s.fromNodeLabel} ${n.title}") })
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(nodes) { n ->
                        FilterChip(selected = connectTo == n.id, onClick = { connectTo = n.id }, label = { Text("${s.toNodeLabel} ${n.title}") })
                    }
                }
                OutlinedButton(onClick = {
                    if (connectFrom.isNotBlank() && connectTo.isNotBlank()) {
                        edges = edges + WorkflowEdge(fromNodeId = connectFrom, toNodeId = connectTo)
                    }
                }) { Text(s.connectNodes) }

                Text("${s.edgesLabel} (${edges.size}):", style = MaterialTheme.typography.labelMedium)
                edges.forEach { e ->
                    val fTitle = nodes.firstOrNull { it.id == e.fromNodeId }?.title ?: e.fromNodeId
                    val tTitle = nodes.firstOrNull { it.id == e.toNodeId }?.title ?: e.toNodeId
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("$fTitle → $tTitle", style = MonoTextStyle)
                        TextButton(onClick = { edges = edges.filterNot { it.id == e.id } }) { Text(s.delete) }
                    }
                }
                if (!validation.valid) {
                    Text(validation.details ?: s.invalidGraphError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val (nj, ej) = viewModel.workflowEngine.serializeGraph(nodes, edges)
                onSave(workflow.copy(name = name, nodesJson = nj, edgesJson = ej))
            }) { Text(s.save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } }
    )
}
