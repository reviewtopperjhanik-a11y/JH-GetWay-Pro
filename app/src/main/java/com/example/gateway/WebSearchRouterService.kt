package com.example.gateway

import com.example.core.security.CryptoManager
import com.example.core.security.MaskingUtils
import com.example.core.security.NetworkAddressClassifier
import com.example.data.local.AppPreferences
import com.example.data.local.KeyStatus
import com.example.data.local.ServicePoolKeyEntity
import com.example.data.local.ServicePoolType
import com.example.data.local.ServicePoolDao
import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class WebSearchProviderEndpoints(
    val tavilyBaseUrl: String = "https://api.tavily.com",
    val braveBaseUrl: String = "https://api.search.brave.com",
    val serperBaseUrl: String = "https://google.serper.dev",
    val exaBaseUrl: String = "https://api.exa.ai",
    val duckDuckGoHtmlUrl: String = "https://html.duckduckgo.com/html/"
)

sealed interface WebSearchGatewayResult {
    data class Success(
        val statusCode: Int = 200,
        val payload: JSONObject,
        val providerUsed: String,
        val cached: Boolean,
        val latencyMs: Long,
        val attempts: Int
    ) : WebSearchGatewayResult

    data class Failure(
        val statusCode: Int,
        val errorCode: String,
        val safeMessage: String,
        val retryAfterSeconds: Int? = null,
        val attempts: Int = 0
    ) : WebSearchGatewayResult
}

data class NormalizedWebResultItem(
    val title: String,
    val url: String,
    val snippet: String,
    val content: String = "",
    val publishedDate: String? = null,
    val score: Double? = null
) {
    fun toJson(includeContent: Boolean): JSONObject = JSONObject().apply {
        put("title", title)
        put("url", url)
        put("snippet", snippet)
        if (includeContent) {
            put("content", content.ifBlank { snippet }.take(1500))
        }
        put("published_date", publishedDate ?: "")
        if (score != null) {
            put("score", score)
        }
    }
}

class WebSearchRouterService(
    private val servicePoolDao: ServicePoolDao,
    private val prefs: AppPreferences,
    private val endpoints: WebSearchProviderEndpoints = WebSearchProviderEndpoints(),
    private val allowLocalEndpointsForTests: Boolean = false,
    private val cacheTtlMs: Long = 15L * 60L * 1000L,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private data class CacheKey(
        val normalizedQuery: String,
        val maxResults: Int,
        val searchDepth: String,
        val includeContent: Boolean,
        val freshnessDays: Int?
    )

    private data class CacheEntry(
        val providerUsed: String,
        val results: List<NormalizedWebResultItem>,
        val expiresAtMs: Long
    )

    private val cache = object : LinkedHashMap<CacheKey, CacheEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, CacheEntry>?): Boolean {
            return size > 250
        }
    }
    private val cacheLock = Any()
    private val roundRobinCursor = AtomicLong(0L)

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .dns(NetworkAddressClassifier.pinnedSafeDns(allowLocalHttp = allowLocalEndpointsForTests))
            .build()
    }

    suspend fun getHealthStatus(): JSONObject {
        val now = nowMs()
        val keys = servicePoolDao.getByServiceOnce(ServicePoolType.WEB_SEARCH)
        val healthyKeys = keys.filter { isKeyAvailable(it, now) }
        val activeProviders = healthyKeys.map { it.providerId.lowercase(Locale.US) }.distinct()
        val ddgEnabled = runCatching { prefs.settingsFlow.first().webSearchDuckDuckGoFallbackEnabled }.getOrDefault(false)
        val cacheCount = synchronized(cacheLock) {
            purgeExpiredLocked(now)
            cache.size
        }
        return JSONObject().apply {
            put("status", "ok")
            put("service", "web_search")
            put("active_providers", JSONArray(activeProviders))
            put("available_keys_count", healthyKeys.size)
            put("total_keys_count", keys.size)
            put("duckduckgo_fallback_enabled", ddgEnabled)
            put("cache_enabled", true)
            put("cache_entries", cacheCount)
        }
    }

    fun clearCache(): Int = synchronized(cacheLock) {
        val count = cache.size
        cache.clear()
        count
    }

    suspend fun search(request: ParsedWebSearchRequest): WebSearchGatewayResult {
        val startedAt = nowMs()
        val cacheKey = CacheKey(
            normalizedQuery = request.query.trim().lowercase(Locale.US).replace(Regex("\\s+"), " "),
            maxResults = request.maxResults,
            searchDepth = request.searchDepth,
            includeContent = request.includeContent,
            freshnessDays = request.freshnessDays
        )

        synchronized(cacheLock) {
            purgeExpiredLocked(startedAt)
            val hit = cache[cacheKey]
            if (hit != null && hit.expiresAtMs > startedAt) {
                val latency = (nowMs() - startedAt).coerceAtLeast(0L)
                return WebSearchGatewayResult.Success(
                    statusCode = 200,
                    payload = buildResponseJson(
                        query = request.query,
                        providerUsed = hit.providerUsed,
                        cached = true,
                        latencyMs = latency,
                        items = hit.results,
                        includeContent = request.includeContent
                    ),
                    providerUsed = hit.providerUsed,
                    cached = true,
                    latencyMs = latency,
                    attempts = 0
                )
            }
        }

        val allKeys = servicePoolDao.getByServiceOnce(ServicePoolType.WEB_SEARCH)
        val enabledKeys = allKeys.filter { it.enabled }
        val candidates = selectOrderedCandidates(enabledKeys, startedAt)
        val ddgEnabled = runCatching { prefs.settingsFlow.first().webSearchDuckDuckGoFallbackEnabled }.getOrDefault(false)

        if (candidates.isEmpty() && !ddgEnabled) {
            val coolingWaitSec = enabledKeys
                .mapNotNull { it.cooldownUntilMs?.let { until -> ((until - startedAt + 999L) / 1000L).toInt() } }
                .filter { it > 0 }
                .minOrNull()
            return if (enabledKeys.isNotEmpty() && coolingWaitSec != null) {
                WebSearchGatewayResult.Failure(
                    statusCode = 429,
                    errorCode = "rate_limit_exhausted",
                    safeMessage = "All configured Web Search API keys are currently cooling down after rate-limit or error responses.",
                    retryAfterSeconds = coolingWaitSec,
                    attempts = 0
                )
            } else {
                WebSearchGatewayResult.Failure(
                    statusCode = 503,
                    errorCode = "search_providers_unavailable",
                    safeMessage = "No active Web Search provider keys are configured and DuckDuckGo fallback is disabled.",
                    retryAfterSeconds = null,
                    attempts = 0
                )
            }
        }

        var attempts = 0
        var sawRateLimit = false
        var minCooldownSec: Int? = null
        var lastSafeError = "Web Search upstream request failed."

        for (keyEntity in candidates) {
            attempts++
            val plainKey = CryptoManager.decryptResult(keyEntity.encryptedKey).getOrElse { err ->
                val msg = MaskingUtils.sanitizeErrorMessage("Key decryption failed: ${err.message}")
                servicePoolDao.recordFailureOrCooldown(
                    id = keyEntity.id,
                    status = KeyStatus.INVALID,
                    nowMs = nowMs(),
                    lastError = msg,
                    cooldownUntilMs = nowMs() + 300_000L,
                    rateLimitRemaining = null,
                    rateLimitResetAtMs = null
                )
                lastSafeError = msg
                continue
            }

            val outcome = executeProviderCall(
                providerId = keyEntity.providerId.lowercase(Locale.US),
                apiKey = plainKey,
                request = request
            )

            when (outcome) {
                is ProviderAttemptResult.Success -> {
                    val finishedAt = nowMs()
                    servicePoolDao.recordSuccess(
                        id = keyEntity.id,
                        status = KeyStatus.ACTIVE,
                        nowMs = finishedAt,
                        cooldownUntilMs = null,
                        rateLimitRemaining = null,
                        rateLimitResetAtMs = null
                    )
                    synchronized(cacheLock) {
                        cache[cacheKey] = CacheEntry(
                            providerUsed = keyEntity.providerId.lowercase(Locale.US),
                            results = outcome.items,
                            expiresAtMs = finishedAt + cacheTtlMs
                        )
                    }
                    val latency = (finishedAt - startedAt).coerceAtLeast(1L)
                    return WebSearchGatewayResult.Success(
                        statusCode = 200,
                        payload = buildResponseJson(
                            query = request.query,
                            providerUsed = keyEntity.providerId.lowercase(Locale.US),
                            cached = false,
                            latencyMs = latency,
                            items = outcome.items,
                            includeContent = request.includeContent
                        ),
                        providerUsed = keyEntity.providerId.lowercase(Locale.US),
                        cached = false,
                        latencyMs = latency,
                        attempts = attempts
                    )
                }

                is ProviderAttemptResult.Failure -> {
                    val failNow = nowMs()
                    val cooldownSec = outcome.cooldownSeconds.coerceIn(30, 300)
                    val cooldownUntil = failNow + cooldownSec * 1000L
                    if (outcome.httpCode == 429 || outcome.httpCode == 402 || outcome.httpCode == 403) {
                        sawRateLimit = true
                    }
                    minCooldownSec = listOfNotNull(minCooldownSec, cooldownSec).minOrNull()
                    val safeErr = MaskingUtils.sanitizeErrorMessage(outcome.safeError, listOf(plainKey))
                    lastSafeError = safeErr
                    servicePoolDao.recordFailureOrCooldown(
                        id = keyEntity.id,
                        status = outcome.keyStatus,
                        nowMs = failNow,
                        lastError = safeErr,
                        cooldownUntilMs = cooldownUntil,
                        rateLimitRemaining = null,
                        rateLimitResetAtMs = null
                    )
                }
            }
        }

        // Optional keyless DuckDuckGo fallback when enabled
        if (ddgEnabled) {
            attempts++
            when (val ddgOutcome = executeDuckDuckGoFallback(request)) {
                is ProviderAttemptResult.Success -> {
                    val finishedAt = nowMs()
                    synchronized(cacheLock) {
                        cache[cacheKey] = CacheEntry(
                            providerUsed = "duckduckgo",
                            results = ddgOutcome.items,
                            expiresAtMs = finishedAt + cacheTtlMs
                        )
                    }
                    val latency = (finishedAt - startedAt).coerceAtLeast(1L)
                    return WebSearchGatewayResult.Success(
                        statusCode = 200,
                        payload = buildResponseJson(
                            query = request.query,
                            providerUsed = "duckduckgo",
                            cached = false,
                            latencyMs = latency,
                            items = ddgOutcome.items,
                            includeContent = request.includeContent
                        ),
                        providerUsed = "duckduckgo",
                        cached = false,
                        latencyMs = latency,
                        attempts = attempts
                    )
                }
                is ProviderAttemptResult.Failure -> {
                    lastSafeError = ddgOutcome.safeError
                }
            }
        }

        val retryAfter = minCooldownSec ?: if (sawRateLimit) 60 else null
        return WebSearchGatewayResult.Failure(
            statusCode = if (sawRateLimit) 429 else 502,
            errorCode = if (sawRateLimit) "rate_limit_exhausted" else "upstream_search_error",
            safeMessage = lastSafeError,
            retryAfterSeconds = retryAfter,
            attempts = attempts
        )
    }

    suspend fun testKey(keyId: String): Result<String> {
        val entity = servicePoolDao.getByIdOnce(keyId)
            ?: return Result.failure(IllegalArgumentException("Pool key not found."))
        val plain = CryptoManager.decryptResult(entity.encryptedKey).getOrElse { err ->
            return Result.failure(IllegalStateException("Decryption failed: ${err.message}"))
        }
        val probeReq = ParsedWebSearchRequest(
            query = "kotlin coroutines",
            maxResults = 1,
            searchDepth = "basic",
            includeContent = false
        )
        return when (val out = executeProviderCall(entity.providerId.lowercase(Locale.US), plain, probeReq)) {
            is ProviderAttemptResult.Success -> {
                servicePoolDao.recordSuccess(
                    id = entity.id,
                    status = KeyStatus.ACTIVE,
                    nowMs = nowMs(),
                    cooldownUntilMs = null,
                    rateLimitRemaining = null,
                    rateLimitResetAtMs = null
                )
                Result.success("Verified ${entity.providerId} key (${out.items.size} result(s) returned).")
            }
            is ProviderAttemptResult.Failure -> {
                val safeErr = MaskingUtils.sanitizeErrorMessage(out.safeError, listOf(plain))
                servicePoolDao.recordFailureOrCooldown(
                    id = entity.id,
                    status = out.keyStatus,
                    nowMs = nowMs(),
                    lastError = safeErr,
                    cooldownUntilMs = nowMs() + out.cooldownSeconds.coerceIn(30, 300) * 1000L,
                    rateLimitRemaining = null,
                    rateLimitResetAtMs = null
                )
                Result.failure(IllegalStateException(safeErr))
            }
        }
    }

    private fun isKeyAvailable(key: ServicePoolKeyEntity, now: Long): Boolean {
        if (!key.enabled) return false
        val cd = key.cooldownUntilMs
        return cd == null || cd <= now
    }

    private fun selectOrderedCandidates(
        enabledKeys: List<ServicePoolKeyEntity>,
        now: Long
    ): List<ServicePoolKeyEntity> {
        val available = enabledKeys.filter { isKeyAvailable(it, now) }
        if (available.size <= 1) return available
        val sorted = available.sortedWith(
            compareBy<ServicePoolKeyEntity> { it.priority }
                .thenBy { it.lastUsedAt ?: 0L }
                .thenBy { it.usageCount }
                .thenBy { it.createdAt }
        )
        // Fair rotation across top equal-priority keys
        val topPriority = sorted.first().priority
        val topTier = sorted.takeWhile { it.priority == topPriority }
        if (topTier.size <= 1) return sorted
        val shift = ((roundRobinCursor.getAndIncrement() and 0x7FFFFFFFL) % topTier.size).toInt()
        val rotatedTop = (0 until topTier.size).map { idx -> topTier[(idx + shift) % topTier.size] }
        return rotatedTop + sorted.drop(topTier.size)
    }

    private sealed interface ProviderAttemptResult {
        data class Success(val items: List<NormalizedWebResultItem>) : ProviderAttemptResult
        data class Failure(
            val httpCode: Int?,
            val keyStatus: KeyStatus,
            val safeError: String,
            val cooldownSeconds: Int
        ) : ProviderAttemptResult
    }

    private fun executeProviderCall(
        providerId: String,
        apiKey: String,
        request: ParsedWebSearchRequest
    ): ProviderAttemptResult {
        return when (providerId.trim().lowercase(Locale.US)) {
            "tavily" -> callTavily(apiKey, request)
            "brave" -> callBrave(apiKey, request)
            "serper" -> callSerper(apiKey, request)
            "exa" -> callExa(apiKey, request)
            else -> ProviderAttemptResult.Failure(
                httpCode = null,
                keyStatus = KeyStatus.UNKNOWN,
                safeError = "Unsupported web search provider '$providerId'.",
                cooldownSeconds = 300
            )
        }
    }

    private fun callTavily(apiKey: String, req: ParsedWebSearchRequest): ProviderAttemptResult {
        val url = "${endpoints.tavilyBaseUrl.trimEnd('/')}/search"
        val sec = NetworkAddressClassifier.validateEndpointSecurity(url, allowLocalHttp = allowLocalEndpointsForTests)
        if (!sec.allowed) {
            return ProviderAttemptResult.Failure(null, KeyStatus.UNKNOWN, sec.errorMessage ?: "Blocked Tavily endpoint", 120)
        }
        val body = JSONObject().apply {
            put("api_key", apiKey)
            put("query", req.query)
            put("search_depth", req.searchDepth)
            put("max_results", req.maxResults)
            put("include_raw_content", req.includeContent)
            if (req.freshnessDays != null) {
                put("days", req.freshnessDays)
            }
        }
        val httpReq = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        return executeAndParse(httpReq, "Tavily", listOf(apiKey)) { rawBody ->
            val root = JSONObject(rawBody)
            val arr = root.optJSONArray("results") ?: JSONArray()
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val title = o.optString("title").trim()
                    val itemUrl = o.optString("url").trim()
                    if (itemUrl.isBlank()) continue
                    val snippet = o.optString("content").trim().take(500)
                    val fullContent = o.optString("raw_content").trim().ifBlank { o.optString("content").trim() }.take(1500)
                    add(
                        NormalizedWebResultItem(
                            title = title.ifBlank { itemUrl },
                            url = itemUrl,
                            snippet = snippet,
                            content = fullContent,
                            publishedDate = o.optString("published_date").takeIf { it.isNotBlank() && !o.isNull("published_date") },
                            score = if (o.has("score") && !o.isNull("score")) o.optDouble("score") else null
                        )
                    )
                }
            }.take(req.maxResults)
        }
    }

    private fun callBrave(apiKey: String, req: ParsedWebSearchRequest): ProviderAttemptResult {
        val parsed = "${endpoints.braveBaseUrl.trimEnd('/')}/res/v1/web/search".toHttpUrlOrNull()
            ?: return ProviderAttemptResult.Failure(null, KeyStatus.UNKNOWN, "Invalid Brave Search endpoint URL.", 120)
        val urlBuilder = parsed.newBuilder()
            .addQueryParameter("q", req.query)
            .addQueryParameter("count", req.maxResults.toString())
        req.freshnessDays?.let { days ->
            val freshness = when {
                days <= 1 -> "pd"
                days <= 7 -> "pw"
                days <= 31 -> "pm"
                else -> "py"
            }
            urlBuilder.addQueryParameter("freshness", freshness)
        }
        val finalUrl = urlBuilder.build().toString()
        val sec = NetworkAddressClassifier.validateEndpointSecurity(finalUrl, allowLocalHttp = allowLocalEndpointsForTests)
        if (!sec.allowed) {
            return ProviderAttemptResult.Failure(null, KeyStatus.UNKNOWN, sec.errorMessage ?: "Blocked Brave endpoint", 120)
        }
        val httpReq = Request.Builder()
            .url(finalUrl)
            .header("X-Subscription-Token", apiKey)
            .header("Accept", "application/json")
            .get()
            .build()

        return executeAndParse(httpReq, "Brave", listOf(apiKey)) { rawBody ->
            val root = JSONObject(rawBody)
            val arr = root.optJSONObject("web")?.optJSONArray("results") ?: JSONArray()
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val itemUrl = o.optString("url").trim()
                    if (itemUrl.isBlank()) continue
                    val title = o.optString("title").trim()
                    val desc = o.optString("description").trim()
                    val extra = buildList {
                        o.optJSONArray("extra_snippets")?.let { exArr ->
                            for (j in 0 until exArr.length()) {
                                exArr.optString(j).trim().takeIf { it.isNotEmpty() }?.let(::add)
                            }
                        }
                    }
                    val combinedContent = (listOf(desc) + extra).filter { it.isNotBlank() }.joinToString("\n").take(1500)
                    add(
                        NormalizedWebResultItem(
                            title = title.ifBlank { itemUrl },
                            url = itemUrl,
                            snippet = desc.take(500),
                            content = combinedContent,
                            publishedDate = o.optString("page_age").takeIf { it.isNotBlank() }
                                ?: o.optString("age").takeIf { it.isNotBlank() },
                            score = null
                        )
                    )
                }
            }.take(req.maxResults)
        }
    }

    private fun callSerper(apiKey: String, req: ParsedWebSearchRequest): ProviderAttemptResult {
        val url = "${endpoints.serperBaseUrl.trimEnd('/')}/search"
        val sec = NetworkAddressClassifier.validateEndpointSecurity(url, allowLocalHttp = allowLocalEndpointsForTests)
        if (!sec.allowed) {
            return ProviderAttemptResult.Failure(null, KeyStatus.UNKNOWN, sec.errorMessage ?: "Blocked Serper endpoint", 120)
        }
        val body = JSONObject().apply {
            put("q", req.query)
            put("num", req.maxResults)
            req.freshnessDays?.let { days ->
                val tbs = when {
                    days <= 1 -> "qdr:d"
                    days <= 7 -> "qdr:w"
                    days <= 31 -> "qdr:m"
                    else -> "qdr:y"
                }
                put("tbs", tbs)
            }
        }
        val httpReq = Request.Builder()
            .url(url)
            .header("X-API-KEY", apiKey)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        return executeAndParse(httpReq, "Serper", listOf(apiKey)) { rawBody ->
            val root = JSONObject(rawBody)
            val arr = root.optJSONArray("organic") ?: JSONArray()
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val itemUrl = o.optString("link").trim()
                    if (itemUrl.isBlank()) continue
                    val title = o.optString("title").trim()
                    val snippet = o.optString("snippet").trim()
                    add(
                        NormalizedWebResultItem(
                            title = title.ifBlank { itemUrl },
                            url = itemUrl,
                            snippet = snippet.take(500),
                            content = snippet.take(1500),
                            publishedDate = o.optString("date").takeIf { it.isNotBlank() },
                            score = null
                        )
                    )
                }
            }.take(req.maxResults)
        }
    }

    private fun callExa(apiKey: String, req: ParsedWebSearchRequest): ProviderAttemptResult {
        val url = "${endpoints.exaBaseUrl.trimEnd('/')}/search"
        val sec = NetworkAddressClassifier.validateEndpointSecurity(url, allowLocalHttp = allowLocalEndpointsForTests)
        if (!sec.allowed) {
            return ProviderAttemptResult.Failure(null, KeyStatus.UNKNOWN, sec.errorMessage ?: "Blocked Exa endpoint", 120)
        }
        val body = JSONObject().apply {
            put("query", req.query)
            put("numResults", req.maxResults)
            put("type", if (req.searchDepth == "advanced") "deep" else "auto")
            val contentsObj = JSONObject().apply {
                if (req.includeContent) {
                    put("text", JSONObject().put("maxCharacters", 1500))
                }
                put("highlights", JSONObject().put("maxCharacters", 500))
            }
            put("contents", contentsObj)
            req.freshnessDays?.let { days ->
                val sinceMs = nowMs() - days.toLong() * 86_400_000L
                val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                put("startPublishedDate", fmt.format(Date(sinceMs)))
            }
        }
        val httpReq = Request.Builder()
            .url(url)
            .header("x-api-key", apiKey)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        return executeAndParse(httpReq, "Exa", listOf(apiKey)) { rawBody ->
            val root = JSONObject(rawBody)
            val arr = root.optJSONArray("results") ?: JSONArray()
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val itemUrl = o.optString("url").trim()
                    if (itemUrl.isBlank()) continue
                    val title = o.optString("title").trim()
                    val highlights = o.optJSONArray("highlights")?.let { h ->
                        (0 until h.length()).mapNotNull { idx -> h.optString(idx).trim().takeIf { it.isNotEmpty() } }
                            .joinToString(" ")
                    }.orEmpty()
                    val text = o.optString("text").trim()
                    val snippet = highlights.ifBlank { text }.take(500)
                    val content = text.ifBlank { highlights }.take(1500)
                    add(
                        NormalizedWebResultItem(
                            title = title.ifBlank { itemUrl },
                            url = itemUrl,
                            snippet = snippet,
                            content = content,
                            publishedDate = o.optString("publishedDate").takeIf { it.isNotBlank() && !o.isNull("publishedDate") },
                            score = if (o.has("score") && !o.isNull("score")) o.optDouble("score") else null
                        )
                    )
                }
            }.take(req.maxResults)
        }
    }

    private fun executeDuckDuckGoFallback(req: ParsedWebSearchRequest): ProviderAttemptResult {
        val base = endpoints.duckDuckGoHtmlUrl.trim()
        val parsed = base.toHttpUrlOrNull()
            ?: return ProviderAttemptResult.Failure(null, KeyStatus.UNKNOWN, "Invalid DuckDuckGo fallback URL.", 60)
        val url = parsed.newBuilder()
            .addQueryParameter("q", req.query)
            .build()
            .toString()
        val sec = NetworkAddressClassifier.validateEndpointSecurity(url, allowLocalHttp = allowLocalEndpointsForTests)
        if (!sec.allowed) {
            return ProviderAttemptResult.Failure(null, KeyStatus.UNKNOWN, sec.errorMessage ?: "Blocked DuckDuckGo endpoint", 60)
        }
        val httpReq = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android) JHGetWay/1.0")
            .header("Accept", "text/html,application/json")
            .get()
            .build()

        return try {
            httpClient.newCall(httpReq).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return ProviderAttemptResult.Failure(
                        httpCode = resp.code,
                        keyStatus = if (resp.code == 429) KeyStatus.RATE_LIMITED else KeyStatus.UNKNOWN,
                        safeError = "DuckDuckGo fallback HTTP ${resp.code}",
                        cooldownSeconds = 60
                    )
                }
                val items = if (bodyStr.trimStart().startsWith("{")) {
                    parseDuckDuckGoJson(bodyStr, req.maxResults)
                } else {
                    parseDuckDuckGoHtml(bodyStr, req.maxResults)
                }
                if (items.isEmpty()) {
                    ProviderAttemptResult.Failure(resp.code, KeyStatus.UNKNOWN, "DuckDuckGo returned no parsable search results.", 60)
                } else {
                    ProviderAttemptResult.Success(items)
                }
            }
        } catch (err: Exception) {
            ProviderAttemptResult.Failure(null, KeyStatus.UNKNOWN, MaskingUtils.sanitizeErrorMessage(err.message), 60)
        }
    }

    private fun parseDuckDuckGoJson(raw: String, maxResults: Int): List<NormalizedWebResultItem> {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<NormalizedWebResultItem>()
        root.optJSONArray("results")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("url").trim()
                if (url.isNotBlank()) {
                    val snippet = o.optString("snippet").ifBlank { o.optString("content") }.trim()
                    out += NormalizedWebResultItem(
                        title = o.optString("title").ifBlank { url },
                        url = url,
                        snippet = snippet.take(500),
                        content = o.optString("content").ifBlank { snippet }.take(1500)
                    )
                }
            }
        }
        if (out.isEmpty()) {
            val abstractUrl = root.optString("AbstractURL").trim()
            val abstractText = root.optString("AbstractText").trim()
            if (abstractUrl.isNotBlank() && abstractText.isNotBlank()) {
                out += NormalizedWebResultItem(
                    title = root.optString("Heading").ifBlank { abstractUrl },
                    url = abstractUrl,
                    snippet = abstractText.take(500),
                    content = abstractText.take(1500)
                )
            }
            root.optJSONArray("RelatedTopics")?.let { topics ->
                for (i in 0 until topics.length()) {
                    val t = topics.optJSONObject(i) ?: continue
                    val u = t.optString("FirstURL").trim()
                    val txt = t.optString("Text").trim()
                    if (u.isNotBlank() && txt.isNotBlank()) {
                        out += NormalizedWebResultItem(
                            title = txt.substringBefore(" - ").take(120),
                            url = u,
                            snippet = txt.take(500),
                            content = txt.take(1500)
                        )
                    }
                }
            }
        }
        return out.take(maxResults)
    }

    private fun parseDuckDuckGoHtml(html: String, maxResults: Int): List<NormalizedWebResultItem> {
        val linkRegex = Regex(
            """<a[^>]*class=["'][^"']*result__a[^"']*["'][^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val snippetRegex = Regex(
            """class=["'][^"']*result__snippet[^"']*["'][^>]*>(.*?)</(?:a|div|span)>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val links = linkRegex.findAll(html).toList()
        val snippets = snippetRegex.findAll(html).map { stripHtmlTags(it.groupValues[1]) }.toList()
        return links.take(maxResults).mapIndexedNotNull { idx, match ->
            val rawHref = decodeHtmlEntities(match.groupValues[1]).trim()
            val resolvedUrl = resolveDuckDuckGoHref(rawHref)
            if (resolvedUrl.isBlank()) return@mapIndexedNotNull null
            val title = stripHtmlTags(match.groupValues[2]).ifBlank { resolvedUrl }
            val snippet = snippets.getOrNull(idx).orEmpty().take(500)
            NormalizedWebResultItem(
                title = title,
                url = resolvedUrl,
                snippet = snippet,
                content = snippet.take(1500)
            )
        }
    }

    private fun resolveDuckDuckGoHref(rawHref: String): String {
        val normalized = if (rawHref.startsWith("//")) "https:$rawHref" else rawHref
        val uddgMatch = Regex("[?&]uddg=([^&]+)").find(normalized)
        if (uddgMatch != null) {
            return runCatching { URLDecoder.decode(uddgMatch.groupValues[1], "UTF-8") }.getOrDefault(normalized)
        }
        return normalized
    }

    private fun stripHtmlTags(input: String): String {
        return decodeHtmlEntities(input.replace(Regex("<[^>]+>"), " "))
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun decodeHtmlEntities(input: String): String = input
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&nbsp;", " ")

    private fun executeAndParse(
        httpReq: Request,
        providerName: String,
        secretsToStrip: List<String>,
        parser: (String) -> List<NormalizedWebResultItem>
    ): ProviderAttemptResult {
        return try {
            httpClient.newCall(httpReq).execute().use { resp ->
                val rawBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val retryAfterSec = resp.header("Retry-After")?.trim()?.toIntOrNull()?.coerceIn(30, 300)
                    val status = when (resp.code) {
                        401 -> KeyStatus.INVALID
                        402 -> KeyStatus.EXPIRED
                        403 -> KeyStatus.INVALID
                        429 -> KeyStatus.RATE_LIMITED
                        else -> KeyStatus.UNKNOWN
                    }
                    val defaultCooldown = when (resp.code) {
                        401 -> 300
                        402 -> 180
                        403 -> 180
                        429 -> 60
                        else -> 60
                    }
                    return ProviderAttemptResult.Failure(
                        httpCode = resp.code,
                        keyStatus = status,
                        safeError = MaskingUtils.sanitizeErrorMessage(
                            "$providerName returned HTTP ${resp.code}: ${extractSafeErrorText(rawBody)}",
                            secretsToStrip
                        ),
                        cooldownSeconds = retryAfterSec ?: defaultCooldown
                    )
                }
                val parsed = runCatching { parser(rawBody) }.getOrElse { err ->
                    return ProviderAttemptResult.Failure(
                        httpCode = resp.code,
                        keyStatus = KeyStatus.UNKNOWN,
                        safeError = "$providerName response parse error: ${MaskingUtils.sanitizeErrorMessage(err.message, secretsToStrip)}",
                        cooldownSeconds = 60
                    )
                }
                ProviderAttemptResult.Success(parsed)
            }
        } catch (err: Exception) {
            ProviderAttemptResult.Failure(
                httpCode = null,
                keyStatus = KeyStatus.UNKNOWN,
                safeError = "$providerName request failed: ${MaskingUtils.sanitizeErrorMessage(err.message, secretsToStrip)}",
                cooldownSeconds = 60
            )
        }
    }

    private fun extractSafeErrorText(rawBody: String): String {
        val obj = runCatching { JSONObject(rawBody) }.getOrNull()
        val msg = obj?.optJSONObject("error")?.optString("message")
            ?.ifBlank { obj.optString("detail") }
            ?.ifBlank { obj.optString("message") }
            ?: rawBody
        return msg.replace(Regex("\\s+"), " ").trim().take(180)
    }

    private fun buildResponseJson(
        query: String,
        providerUsed: String,
        cached: Boolean,
        latencyMs: Long,
        items: List<NormalizedWebResultItem>,
        includeContent: Boolean
    ): JSONObject {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson(includeContent)) }
        return JSONObject().apply {
            put("query", query)
            put("provider_used", providerUsed)
            put("cached", cached)
            put("latency_ms", latencyMs)
            put("results", arr)
        }
    }

    private fun purgeExpiredLocked(now: Long) {
        val iter = cache.entries.iterator()
        while (iter.hasNext()) {
            if (iter.next().value.expiresAtMs <= now) {
                iter.remove()
            }
        }
    }

    companion object {
        val SUPPORTED_WEB_SEARCH_PROVIDERS = listOf("tavily", "brave", "serper", "exa")
    }
}
