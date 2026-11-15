package com.example.gateway

import com.example.core.security.CryptoManager
import com.example.core.security.DnsResolver
import com.example.core.security.MaskingUtils
import com.example.core.security.NetworkAddressClassifier
import com.example.core.security.SystemDnsResolver
import com.example.data.local.*
import com.example.network.ChatMessagePayload
import com.example.network.McpHttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

enum class WorkflowNodeType {
    INPUT, AI_MODEL, PROVIDER, CONDITION, ROUTER, HTTP_REQUEST,
    MCP_TOOL, TEXT_TRANSFORM, PARALLEL, MERGE, OUTPUT
}

data class WorkflowNode(
    val id: String,
    val type: WorkflowNodeType,
    val title: String,
    val config: Map<String, String> = emptyMap()
)

data class WorkflowEdge(
    val id: String = UUID.randomUUID().toString().take(8),
    val fromNodeId: String,
    val toNodeId: String,
    val branchLabel: String = ""
)

data class NodeExecutionResult(
    val nodeId: String,
    val title: String,
    val type: WorkflowNodeType,
    val status: String, // PENDING, RUNNING, COMPLETED, FAILED, SKIPPED
    val output: String = "",
    val error: String? = null,
    val durationMs: Long = 0
)

data class WorkflowValidationResult(
    val valid: Boolean,
    val errorKey: String? = null,
    val details: String? = null
)

class WorkflowEngine(
    private val db: JhGetWayDatabase,
    private val router: SmartRouterEngine,
    private val mcpClient: McpHttpClient,
    private val dnsResolver: DnsResolver = SystemDnsResolver
) {
    private fun httpClient(allowLocalHttp: Boolean): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .dns(NetworkAddressClassifier.pinnedSafeDns(allowLocalHttp, dnsResolver))
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun parseGraph(nodesJson: String, edgesJson: String): Pair<List<WorkflowNode>, List<WorkflowEdge>> {
        val nodes = mutableListOf<WorkflowNode>()
        val nArr = runCatching { JSONArray(nodesJson) }.getOrDefault(JSONArray())
        for (i in 0 until nArr.length()) {
            val o = nArr.optJSONObject(i) ?: continue
            val cfgMap = mutableMapOf<String, String>()
            o.optJSONObject("config")?.let { c -> c.keys().forEach { k -> cfgMap[k] = c.optString(k) } }
            nodes += WorkflowNode(
                id = o.optString("id"),
                type = runCatching { WorkflowNodeType.valueOf(o.optString("type")) }.getOrDefault(WorkflowNodeType.AI_MODEL),
                title = o.optString("title"),
                config = cfgMap
            )
        }
        val edges = mutableListOf<WorkflowEdge>()
        val eArr = runCatching { JSONArray(edgesJson) }.getOrDefault(JSONArray())
        for (i in 0 until eArr.length()) {
            val o = eArr.optJSONObject(i) ?: continue
            edges += WorkflowEdge(
                id = o.optString("id", UUID.randomUUID().toString().take(8)),
                fromNodeId = o.optString("fromNodeId"),
                toNodeId = o.optString("toNodeId"),
                branchLabel = o.optString("branchLabel", "")
            )
        }
        return nodes to edges
    }

    fun serializeGraph(nodes: List<WorkflowNode>, edges: List<WorkflowEdge>): Pair<String, String> {
        val nArr = JSONArray()
        nodes.forEach { n ->
            nArr.put(JSONObject().apply {
                put("id", n.id)
                put("type", n.type.name)
                put("title", n.title)
                put("config", JSONObject(n.config))
            })
        }
        val eArr = JSONArray()
        edges.forEach { e ->
            eArr.put(JSONObject().apply {
                put("id", e.id)
                put("fromNodeId", e.fromNodeId)
                put("toNodeId", e.toNodeId)
                put("branchLabel", e.branchLabel)
            })
        }
        return nArr.toString() to eArr.toString()
    }

    fun validateGraph(nodes: List<WorkflowNode>, edges: List<WorkflowEdge>): WorkflowValidationResult {
        if (nodes.any { it.id.isBlank() }) {
            return WorkflowValidationResult(false, "INVALID_NODE_ID", "All workflow nodes require a non-blank id.")
        }
        val nodeIds = nodes.map { it.id }.toSet()
        if (nodeIds.size != nodes.size) {
            return WorkflowValidationResult(false, "DUPLICATE_NODE_ID", "Duplicate node IDs are not allowed in workflow graph.")
        }
        val inputNodes = nodes.filter { it.type == WorkflowNodeType.INPUT }
        val outputNodes = nodes.filter { it.type == WorkflowNodeType.OUTPUT }
        if (inputNodes.isEmpty() || outputNodes.isEmpty()) {
            return WorkflowValidationResult(false, "MISSING_START_END", "Input and Output nodes are required.")
        }
        for (e in edges) {
            if (e.fromNodeId !in nodeIds || e.toNodeId !in nodeIds || e.fromNodeId == e.toNodeId) {
                return WorkflowValidationResult(false, "INVALID_EDGE", "Invalid edge ${e.fromNodeId} -> ${e.toNodeId}")
            }
        }
        // Required per-node configuration checks
        for (n in nodes) {
            when (n.type) {
                WorkflowNodeType.HTTP_REQUEST -> {
                    val url = n.config["url"].orEmpty().trim()
                    if (url.isBlank()) {
                        return WorkflowValidationResult(false, "MISSING_NODE_CONFIG", "HTTP Request node '${n.title}' requires a target URL.")
                    }
                }
                WorkflowNodeType.MCP_TOOL -> {
                    val srvId = n.config["serverId"].orEmpty().trim()
                    val toolName = n.config["toolName"].orEmpty().trim()
                    if (srvId.isBlank() || toolName.isBlank()) {
                        return WorkflowValidationResult(false, "MISSING_NODE_CONFIG", "MCP Tool node '${n.title}' requires serverId and toolName.")
                    }
                }
                else -> Unit
            }
        }
        // DFS Cycle Detection
        val adj = edges.groupBy { it.fromNodeId }.mapValues { it.value.map { e -> e.toNodeId } }
        val state = mutableMapOf<String, Int>() // 0=unvisited, 1=visiting, 2=visited
        fun hasCycle(u: String): Boolean {
            state[u] = 1
            for (v in adj[u].orEmpty()) {
                val st = state[v] ?: 0
                if (st == 1) return true
                if (st == 0 && hasCycle(v)) return true
            }
            state[u] = 2
            return false
        }
        for (n in nodes) {
            if ((state[n.id] ?: 0) == 0 && hasCycle(n.id)) {
                return WorkflowValidationResult(false, "CYCLE_DETECTED", "Circular connection detected at node ${n.title}")
            }
        }
        // BFS reachability check from INPUT nodes to at least one OUTPUT node
        val reachable = mutableSetOf<String>()
        val bfs = ArrayDeque<String>()
        inputNodes.forEach {
            reachable += it.id
            bfs.addLast(it.id)
        }
        while (bfs.isNotEmpty()) {
            val cur = bfs.removeFirst()
            for (next in adj[cur].orEmpty()) {
                if (reachable.add(next)) bfs.addLast(next)
            }
        }
        if (outputNodes.none { it.id in reachable }) {
            return WorkflowValidationResult(false, "UNREACHABLE_OUTPUT", "At least one Output node must be reachable from an Input node.")
        }
        return WorkflowValidationResult(true)
    }

    suspend fun executeWorkflow(
        workflow: WorkflowEntity,
        inputText: String,
        settings: GatewaySettingsState,
        onProgress: (List<NodeExecutionResult>) -> Unit,
        onRequestMcpApproval: suspend (McpServerEntity, McpToolEntity) -> Boolean
    ): WorkflowRunEntity {
        val (nodes, edges) = parseGraph(workflow.nodesJson, workflow.edgesJson)
        val validation = validateGraph(nodes, edges)
        val runId = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()
        if (!validation.valid) {
            val failedRun = WorkflowRunEntity(
                id = runId,
                workflowId = workflow.id,
                workflowName = workflow.name,
                status = "FAILED",
                inputText = inputText,
                startedAt = startedAt,
                finishedAt = System.currentTimeMillis(),
                errorMessage = validation.details
            )
            db.workflowDao().upsertRun(failedRun)
            return failedRun
        }

        val stateMutex = Mutex()
        val parallelLimiter = Semaphore(4)
        val resultsMap = linkedMapOf<String, NodeExecutionResult>()
        nodes.forEach { n ->
            resultsMap[n.id] = NodeExecutionResult(n.id, n.title, n.type, "PENDING")
        }
        onProgress(resultsMap.values.toList())

        val incomingParentCount = nodes.associate { n -> n.id to edges.count { it.toNodeId == n.id } }
        val inDegree = incomingParentCount.toMutableMap()
        val skippedIncomingCount = nodes.associate { it.id to 0 }.toMutableMap()
        val readyQueue = ArrayDeque(nodes.filter { (inDegree[it.id] ?: 0) == 0 }.map { it.id })
        val nodeOutputs = mutableMapOf<String, String>()
        val steps = AtomicInteger(0)
        val maxSteps = 40
        var runError: String? = null
        var finalOutput = ""

        try {
            while (readyQueue.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                if (steps.get() >= maxSteps) {
                    runError = "Workflow exceeded maximum step limit ($maxSteps)."
                    break
                }
                val batchIds = mutableListOf<String>()
                while (readyQueue.isNotEmpty()) batchIds += readyQueue.removeFirst()

                coroutineScope {
                    batchIds.map { nodeId ->
                        async {
                            parallelLimiter.withPermit {
                                val stepIndex = steps.incrementAndGet()
                                if (stepIndex > maxSteps) {
                                    stateMutex.withLock {
                                        if (runError == null) runError = "Workflow exceeded maximum step limit ($maxSteps)."
                                    }
                                    return@withPermit
                                }
                                val node = nodes.first { it.id == nodeId }
                                val shouldSkip = stateMutex.withLock {
                                    val totalParents = incomingParentCount[nodeId] ?: 0
                                    val skippedParents = skippedIncomingCount[nodeId] ?: 0
                                    totalParents > 0 && skippedParents >= totalParents
                                }
                                if (shouldSkip) {
                                    val snapshot = stateMutex.withLock {
                                        resultsMap[nodeId] = resultsMap[nodeId]!!.copy(status = "SKIPPED")
                                        edges.filter { it.fromNodeId == nodeId }.forEach { edge ->
                                            skippedIncomingCount[edge.toNodeId] = (skippedIncomingCount[edge.toNodeId] ?: 0) + 1
                                        }
                                        resultsMap.values.toList()
                                    }
                                    onProgress(snapshot)
                                    return@withPermit
                                }

                                val upstreamText = stateMutex.withLock {
                                    val incomingEdges = edges.filter { it.toNodeId == nodeId }
                                    resultsMap[nodeId] = resultsMap[nodeId]!!.copy(status = "RUNNING")
                                    incomingEdges.mapNotNull { nodeOutputs[it.fromNodeId] }
                                        .filter { it.isNotBlank() }
                                        .joinToString("\n\n---\n\n")
                                        .ifBlank { inputText }
                                }
                                val runningSnapshot = stateMutex.withLock { resultsMap.values.toList() }
                                onProgress(runningSnapshot)

                                val t0 = System.currentTimeMillis()
                                val timeoutMs = (node.config["timeoutSec"]?.toLongOrNull() ?: 45L).coerceIn(3L, 300L) * 1000L
                                val maxRetries = (node.config["retryCount"]?.toIntOrNull() ?: 0).coerceIn(0, 3)

                                val (outText, errText, chosenBranch) = withTimeout(timeoutMs) {
                                    var attempt = 0
                                    var lastTriple: Triple<String, String?, String?>
                                    while (true) {
                                        lastTriple = executeSingleNode(node, upstreamText, inputText, settings, onRequestMcpApproval)
                                        if (lastTriple.second == null || attempt >= maxRetries) {
                                            break
                                        }
                                        attempt++
                                        delay(200L * attempt)
                                    }
                                    lastTriple
                                }
                                val dt = System.currentTimeMillis() - t0

                                val postSnapshot = stateMutex.withLock {
                                    if (errText != null) {
                                        resultsMap[nodeId] = resultsMap[nodeId]!!.copy(status = "FAILED", error = errText, durationMs = dt)
                                        if (runError == null) runError = "Node '${node.title}' failed: $errText"
                                    } else {
                                        nodeOutputs[nodeId] = outText
                                        resultsMap[nodeId] = resultsMap[nodeId]!!.copy(status = "COMPLETED", output = outText, durationMs = dt)
                                        if (node.type == WorkflowNodeType.OUTPUT) finalOutput = outText

                                        // Propagate skipped incoming edge count for non-selected conditional branches
                                        val outgoing = edges.filter { it.fromNodeId == nodeId }
                                        if (chosenBranch != null && outgoing.any { it.branchLabel.isNotBlank() }) {
                                            outgoing.filter { it.branchLabel.isNotBlank() && !it.branchLabel.equals(chosenBranch, true) }
                                                .forEach { edge ->
                                                    skippedIncomingCount[edge.toNodeId] = (skippedIncomingCount[edge.toNodeId] ?: 0) + 1
                                                }
                                        }
                                    }
                                    resultsMap.values.toList()
                                }
                                onProgress(postSnapshot)
                            }
                        }
                    }.awaitAll()
                }

                if (runError != null) break

                batchIds.forEach { doneId ->
                    edges.filter { it.fromNodeId == doneId }.forEach { edge ->
                        val next = edge.toNodeId
                        val deg = (inDegree[next] ?: 1) - 1
                        inDegree[next] = deg
                        if (deg == 0) readyQueue.addLast(next)
                    }
                }
            }
            if (runError == null && readyQueue.isNotEmpty() && steps.get() >= maxSteps) {
                runError = "Workflow exceeded maximum step limit ($maxSteps)."
            }
        } catch (ce: CancellationException) {
            val snapshot = stateMutex.withLock { resultsMap.values.toList() }
            val run = WorkflowRunEntity(
                id = runId,
                workflowId = workflow.id,
                workflowName = workflow.name,
                status = "CANCELLED",
                inputText = inputText,
                finalOutput = finalOutput,
                nodeResultsJson = serializeNodeResults(snapshot),
                startedAt = startedAt,
                finishedAt = System.currentTimeMillis(),
                errorMessage = "Cancelled by user"
            )
            db.workflowDao().upsertRun(run)
            throw ce
        } catch (e: Exception) {
            runError = e.message ?: "Workflow execution error"
        }

        val status = if (runError == null) "COMPLETED" else "FAILED"
        if (finalOutput.isBlank() && runError == null) {
            finalOutput = nodeOutputs.values.lastOrNull().orEmpty()
        }
        val finalSnapshot = stateMutex.withLock { resultsMap.values.toList() }
        val run = WorkflowRunEntity(
            id = runId,
            workflowId = workflow.id,
            workflowName = workflow.name,
            status = status,
            inputText = inputText,
            finalOutput = finalOutput,
            nodeResultsJson = serializeNodeResults(finalSnapshot),
            startedAt = startedAt,
            finishedAt = System.currentTimeMillis(),
            errorMessage = runError
        )
        db.workflowDao().upsertRun(run)
        db.workflowDao().upsertWorkflow(workflow.copy(lastRunStatus = status, lastRunAt = System.currentTimeMillis()))
        db.metricAndLogDao().insertLog(
            ActivityLogEntity(
                category = ActivityCategory.WORKFLOW_EXECUTION,
                relatedEntity = workflow.name,
                status = status,
                safeMessage = if (status == "COMPLETED") "Workflow completed (${finalSnapshot.size} nodes)" else "Workflow failed: $runError"
            )
        )
        return run
    }

    private suspend fun executeSingleNode(
        node: WorkflowNode,
        upstreamInput: String,
        rootInput: String,
        settings: GatewaySettingsState,
        onRequestMcpApproval: suspend (McpServerEntity, McpToolEntity) -> Boolean
    ): Triple<String, String?, String?> {
        return when (node.type) {
            WorkflowNodeType.INPUT -> Triple(rootInput, null, null)
            WorkflowNodeType.OUTPUT, WorkflowNodeType.MERGE, WorkflowNodeType.PARALLEL -> Triple(upstreamInput, null, null)
            WorkflowNodeType.TEXT_TRANSFORM -> {
                val op = node.config["operation"] ?: "template"
                val tpl = node.config["template"] ?: "{{input}}"
                val out = when (op) {
                    "uppercase" -> upstreamInput.uppercase()
                    "lowercase" -> upstreamInput.lowercase()
                    "trim" -> upstreamInput.trim()
                    else -> tpl.replace("{{input}}", upstreamInput)
                }
                Triple(out, null, null)
            }
            WorkflowNodeType.CONDITION -> {
                val keyword = node.config["contains"] ?: "code"
                val matched = upstreamInput.contains(keyword, ignoreCase = true)
                Triple(upstreamInput, null, if (matched) "true" else "false")
            }
            WorkflowNodeType.ROUTER -> {
                val isCode = upstreamInput.contains("fun ", true) || upstreamInput.contains("class ", true) ||
                    upstreamInput.contains("code", true) || upstreamInput.contains("bug", true)
                Triple(upstreamInput, null, if (isCode) "code" else "general")
            }
            WorkflowNodeType.AI_MODEL, WorkflowNodeType.PROVIDER -> {
                val promptTpl = node.config["prompt"]?.ifBlank { null } ?: "{{input}}"
                val fullPrompt = promptTpl.replace("{{input}}", upstreamInput)
                val systemPrompt = node.config["systemPrompt"].orEmpty()
                val msgs = buildList {
                    if (systemPrompt.isNotBlank()) add(ChatMessagePayload("system", systemPrompt))
                    add(ChatMessagePayload("user", fullPrompt))
                }
                val routed = router.executeRoutedChat(
                    requestedModelOrAlias = node.config["model"].orEmpty(),
                    messages = msgs,
                    settings = settings,
                    explicitProviderId = node.config["providerId"]?.ifBlank { null },
                    deviceName = "Workflow: ${node.title}",
                    endpoint = "/v1/chat/completions"
                )
                if (routed.execution.success) Triple(routed.execution.content, null, null)
                else Triple("", routed.execution.safeErrorMessage ?: "Model call failed", null)
            }
            WorkflowNodeType.HTTP_REQUEST -> {
                val url = node.config["url"].orEmpty().trim()
                val sec = NetworkAddressClassifier.validateEndpointSecurity(
                    urlString = url,
                    allowLocalHttp = settings.localNetworkAccessAllowed,
                    resolver = dnsResolver
                )
                if (!sec.allowed) {
                    return Triple("", sec.errorMessage ?: "Blocked non-HTTPS or unauthorized local HTTP URL", null)
                }
                val method = (node.config["method"] ?: "GET").uppercase()
                val reqBuilder = Request.Builder().url(url)
                if (method == "POST") {
                    val body = (node.config["body"] ?: "{{input}}").replace("{{input}}", upstreamInput)
                    reqBuilder.post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                } else reqBuilder.get()
                withContext(Dispatchers.IO) {
                    runCatching {
                        httpClient(settings.localNetworkAccessAllowed).newCall(reqBuilder.build()).execute().use { resp ->
                            val respBody = resp.body?.string().orEmpty().take(8192)
                            if (resp.isSuccessful) Triple(respBody, null, null)
                            else Triple("", "HTTP ${resp.code}: ${MaskingUtils.sanitizeErrorMessage(respBody)}", null)
                        }
                    }.getOrElse { Triple("", it.message ?: "HTTP failed", null) }
                }
            }
            WorkflowNodeType.MCP_TOOL -> {
                val serverId = node.config["serverId"].orEmpty()
                val toolName = node.config["toolName"].orEmpty()
                val server = db.mcpDao().getServersOnce().firstOrNull { it.id == serverId }
                    ?: return Triple("", "MCP Server not found", null)
                val tool = db.mcpDao().getToolsForServer(serverId).firstOrNull { it.toolName == toolName }
                    ?: return Triple("", "MCP Tool '$toolName' not found", null)

                when (tool.permission) {
                    ToolPermission.DENIED -> return Triple("", "Permission denied for MCP tool '$toolName'", null)
                    ToolPermission.ASK_BEFORE_RUNNING -> {
                        val approved = onRequestMcpApproval(server, tool)
                        if (!approved) return Triple("", "User declined MCP tool '$toolName'", null)
                    }
                    ToolPermission.ALLOWED -> Unit
                }
                val argsJson = (node.config["argumentsJson"] ?: "{}").replace("{{input}}", JSONObject.quote(upstreamInput))
                val decryptedAuth = CryptoManager.decryptResult(server.encryptedAuthToken).getOrElse {
                    return Triple("", "Credential decryption failed: ${it.message}", null)
                }
                val decryptedEnv = CryptoManager.decryptResult(server.encryptedEnvJson).getOrElse {
                    return Triple("", "Credential decryption failed: ${it.message}", null)
                }
                val res = mcpClient.executeTool(
                    server = server,
                    toolName = tool.toolName,
                    argumentsJson = argsJson,
                    authToken = decryptedAuth,
                    envVarsJson = decryptedEnv,
                    allowLocalHttp = settings.localNetworkAccessAllowed
                )
                if (res.success) Triple(res.output, null, null) else Triple("", res.errorMessage, null)
            }
        }
    }

    private fun serializeNodeResults(list: List<NodeExecutionResult>): String {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(JSONObject().apply {
                put("nodeId", r.nodeId)
                put("title", r.title)
                put("type", r.type.name)
                put("status", r.status)
                put("output", r.output.take(2000))
                put("error", r.error ?: "")
                put("durationMs", r.durationMs)
            })
        }
        return arr.toString()
    }

    fun builtInTemplates(): List<WorkflowEntity> {
        fun tpl(
            id: String,
            name: String,
            desc: String,
            nodes: List<WorkflowNode>,
            edges: List<WorkflowEdge>
        ): WorkflowEntity {
            val (nJson, eJson) = serializeGraph(nodes, edges)
            return WorkflowEntity(id = id, name = name, description = desc, nodesJson = nJson, edgesJson = eJson, isTemplate = true)
        }

        return listOf(
            tpl(
                "tpl_research",
                "AI Research",
                "Analyzes a topic, extracts key research questions, and synthesizes a structured brief.",
                listOf(
                    WorkflowNode("n1", WorkflowNodeType.INPUT, "Research Topic"),
                    WorkflowNode("n2", WorkflowNodeType.AI_MODEL, "Query Planner", mapOf("systemPrompt" to "Break this topic into 3 crisp research angles.", "prompt" to "{{input}}")),
                    WorkflowNode("n3", WorkflowNodeType.AI_MODEL, "Synthesis Brief", mapOf("systemPrompt" to "Synthesize a concise executive research brief.", "prompt" to "{{input}}")),
                    WorkflowNode("n4", WorkflowNodeType.OUTPUT, "Final Brief")
                ),
                listOf(WorkflowEdge("e1", "n1", "n2"), WorkflowEdge("e2", "n2", "n3"), WorkflowEdge("e3", "n3", "n4"))
            ),
            tpl(
                "tpl_code_review",
                "Code Review",
                "Routes input through static analysis & code review model with conditional branching.",
                listOf(
                    WorkflowNode("n1", WorkflowNodeType.INPUT, "Code / Prompt"),
                    WorkflowNode("n2", WorkflowNodeType.ROUTER, "Intent Router"),
                    WorkflowNode("n3", WorkflowNodeType.AI_MODEL, "Coding Reviewer", mapOf("model" to "jh-coder", "systemPrompt" to "Review code for bugs, security issues, and improvements.", "prompt" to "{{input}}")),
                    WorkflowNode("n4", WorkflowNodeType.AI_MODEL, "General Analyst", mapOf("model" to "jh-general", "prompt" to "{{input}}")),
                    WorkflowNode("n5", WorkflowNodeType.MERGE, "Merge Result"),
                    WorkflowNode("n6", WorkflowNodeType.OUTPUT, "Review Output")
                ),
                listOf(
                    WorkflowEdge("e1", "n1", "n2"),
                    WorkflowEdge("e2", "n2", "n3", "code"),
                    WorkflowEdge("e3", "n2", "n4", "general"),
                    WorkflowEdge("e4", "n3", "n5"),
                    WorkflowEdge("e5", "n4", "n5"),
                    WorkflowEdge("e6", "n5", "n6")
                )
            ),
            tpl(
                "tpl_summarize",
                "Text Summarization",
                "Normalizes whitespace and generates bullet summary + action items.",
                listOf(
                    WorkflowNode("n1", WorkflowNodeType.INPUT, "Source Text"),
                    WorkflowNode("n2", WorkflowNodeType.TEXT_TRANSFORM, "Clean Text", mapOf("operation" to "trim")),
                    WorkflowNode("n3", WorkflowNodeType.AI_MODEL, "Summarizer", mapOf("systemPrompt" to "Summarize into 5 concise bullets and key action items.", "prompt" to "{{input}}")),
                    WorkflowNode("n4", WorkflowNodeType.OUTPUT, "Summary")
                ),
                listOf(WorkflowEdge("e1", "n1", "n2"), WorkflowEdge("e2", "n2", "n3"), WorkflowEdge("e3", "n3", "n4"))
            ),
            tpl(
                "tpl_multi_compare",
                "Multi-Model Comparison",
                "Runs the same prompt in parallel across two models and merges outputs.",
                listOf(
                    WorkflowNode("n1", WorkflowNodeType.INPUT, "Prompt"),
                    WorkflowNode("n2", WorkflowNodeType.PARALLEL, "Fan-Out"),
                    WorkflowNode("n3", WorkflowNodeType.AI_MODEL, "Model A (Fast)", mapOf("model" to "jh-fast", "prompt" to "{{input}}")),
                    WorkflowNode("n4", WorkflowNodeType.AI_MODEL, "Model B (Reasoning)", mapOf("model" to "jh-reasoning", "prompt" to "{{input}}")),
                    WorkflowNode("n5", WorkflowNodeType.MERGE, "Side-by-Side Merge"),
                    WorkflowNode("n6", WorkflowNodeType.OUTPUT, "Comparison")
                ),
                listOf(
                    WorkflowEdge("e1", "n1", "n2"),
                    WorkflowEdge("e2", "n2", "n3"),
                    WorkflowEdge("e3", "n2", "n4"),
                    WorkflowEdge("e4", "n3", "n5"),
                    WorkflowEdge("e5", "n4", "n5"),
                    WorkflowEdge("e6", "n5", "n6")
                )
            ),
            tpl(
                "tpl_doc_analysis",
                "Document Analysis",
                "Extracts entities, risks, and structured JSON insights from documents.",
                listOf(
                    WorkflowNode("n1", WorkflowNodeType.INPUT, "Document"),
                    WorkflowNode("n2", WorkflowNodeType.AI_MODEL, "Entity & Risk Extractor", mapOf("systemPrompt" to "Extract key entities, dates, and risks cleanly.", "prompt" to "{{input}}")),
                    WorkflowNode("n3", WorkflowNodeType.OUTPUT, "Extracted Insights")
                ),
                listOf(WorkflowEdge("e1", "n1", "n2"), WorkflowEdge("e2", "n2", "n3"))
            ),
            tpl(
                "tpl_content_proc",
                "AI Content Processing",
                "Drafts content and refines tone and formatting in a two-stage pipeline.",
                listOf(
                    WorkflowNode("n1", WorkflowNodeType.INPUT, "Content Brief"),
                    WorkflowNode("n2", WorkflowNodeType.AI_MODEL, "Draft Writer", mapOf("systemPrompt" to "Write a clear structured draft.", "prompt" to "{{input}}")),
                    WorkflowNode("n3", WorkflowNodeType.AI_MODEL, "Polish Editor", mapOf("systemPrompt" to "Polish clarity, grammar, and structure.", "prompt" to "{{input}}")),
                    WorkflowNode("n4", WorkflowNodeType.OUTPUT, "Published Copy")
                ),
                listOf(WorkflowEdge("e1", "n1", "n2"), WorkflowEdge("e2", "n2", "n3"), WorkflowEdge("e3", "n3", "n4"))
            )
        )
    }
}
