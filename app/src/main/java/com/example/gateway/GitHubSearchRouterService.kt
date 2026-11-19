package com.example.gateway

import com.example.core.security.CryptoManager
import com.example.core.security.MaskingUtils
import com.example.core.security.NetworkAddressClassifier
import com.example.data.local.KeyStatus
import com.example.data.local.ServicePoolDao
import com.example.data.local.ServicePoolKeyEntity
import com.example.data.local.ServicePoolType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

sealed interface GitHubSearchGatewayResult {
    data class Success(
        val statusCode: Int = 200,
        val payload: JSONObject,
        val cached: Boolean,
        val latencyMs: Long,
        val attempts: Int
    ) : GitHubSearchGatewayResult

    data class Failure(
        val statusCode: Int,
        val errorCode: String,
        val safeMessage: String,
        val retryAfterSeconds: Int? = null,
        val attempts: Int = 0
    ) : GitHubSearchGatewayResult
}

class GitHubSearchRouterService(
    private val servicePoolDao: ServicePoolDao,
    private val githubApiBaseUrl: String = "https://api.github.com",
    private val allowLocalEndpointsForTests: Boolean = false,
    private val searchCacheTtlMs: Long = 20L * 60L * 1000L,
    private val readmeCacheTtlMs: Long = 30L * 60L * 1000L,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private data class SearchCacheKey(
        val normalizedQuery: String,
        val mode: String,
        val language: String?,
        val sort: String,
        val maxResults: Int,
        val includeReadme: Boolean
    )

    private data class SearchCacheEntry(
        val totalCount: Int,
        val results: List<JSONObject>,
        val expiresAtMs: Long
    )

    private data class ReadmeCacheEntry(
        val excerpt: String,
        val expiresAtMs: Long
    )

    private val searchCache = object : LinkedHashMap<SearchCacheKey, SearchCacheEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SearchCacheKey, SearchCacheEntry>?): Boolean = size > 200
    }
    private val readmeCache = object : LinkedHashMap<String, ReadmeCacheEntry>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ReadmeCacheEntry>?): Boolean = size > 400
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
        val pats = servicePoolDao.getByServiceOnce(ServicePoolType.GITHUB_SEARCH)
        val healthyPats = pats.filter { isPatAvailable(it, now) }
        val cacheCount = synchronized(cacheLock) {
            purgeExpiredLocked(now)
            searchCache.size
        }
        return JSONObject().apply {
            put("status", "ok")
            put("service", "github_search")
            put("pat_pool_size", pats.size)
            put("healthy_tokens", healthyPats.size)
            put("cache_enabled", true)
            put("cache_entries", cacheCount)
        }
    }

    fun clearCache(): Int = synchronized(cacheLock) {
        val total = searchCache.size + readmeCache.size
        searchCache.clear()
        readmeCache.clear()
        total
    }

    suspend fun search(request: ParsedGitHubSearchRequest): GitHubSearchGatewayResult {
        val startedAt = nowMs()
        val cacheKey = SearchCacheKey(
            normalizedQuery = request.query.trim().lowercase(Locale.US).replace(Regex("\\s+"), " "),
            mode = request.mode,
            language = request.language?.trim()?.lowercase(Locale.US),
            sort = request.sort,
            maxResults = request.maxResults,
            includeReadme = request.includeReadme
        )

        synchronized(cacheLock) {
            purgeExpiredLocked(startedAt)
            val hit = searchCache[cacheKey]
            if (hit != null && hit.expiresAtMs > startedAt) {
                val latency = (nowMs() - startedAt).coerceAtLeast(0L)
                return GitHubSearchGatewayResult.Success(
                    statusCode = 200,
                    payload = buildResponseJson(
                        query = request.query,
                        mode = request.mode,
                        cached = true,
                        latencyMs = latency,
                        totalCount = hit.totalCount,
                        items = hit.results
                    ),
                    cached = true,
                    latencyMs = latency,
                    attempts = 0
                )
            }
        }

        val allPats = servicePoolDao.getByServiceOnce(ServicePoolType.GITHUB_SEARCH)
        val enabledPats = allPats.filter { it.enabled }
        if (enabledPats.isEmpty()) {
            return GitHubSearchGatewayResult.Failure(
                statusCode = 503,
                errorCode = "github_pats_not_configured",
                safeMessage = "No GitHub Personal Access Tokens are configured in the GitHub Search pool.",
                retryAfterSeconds = null,
                attempts = 0
            )
        }

        val candidates = selectOrderedPats(enabledPats, startedAt)
        if (candidates.isEmpty()) {
            val minWaitSec = enabledPats
                .mapNotNull { pat ->
                    val until = pat.cooldownUntilMs ?: pat.rateLimitResetAtMs
                    until?.let { ((it - startedAt + 999L) / 1000L).toInt() }
                }
                .filter { it > 0 }
                .minOrNull() ?: 60
            return GitHubSearchGatewayResult.Failure(
                statusCode = 429,
                errorCode = "rate_limit_exhausted",
                safeMessage = "All configured GitHub PATs are currently rate-limited or cooling down.",
                retryAfterSeconds = minWaitSec,
                attempts = 0
            )
        }

        var attempts = 0
        var sawRateLimit = false
        var minRetryAfterSec: Int? = null
        var lastSafeError = "GitHub Search API request failed."

        for (patEntity in candidates) {
            attempts++
            val plainPat = CryptoManager.decryptResult(patEntity.encryptedKey).getOrElse { err ->
                val msg = MaskingUtils.sanitizeErrorMessage("PAT decryption failed: ${err.message}")
                servicePoolDao.recordFailureOrCooldown(
                    id = patEntity.id,
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

            when (val searchOutcome = executeSearchCall(plainPat, request)) {
                is GitHubAttemptResult.Success -> {
                    val callNow = nowMs()
                    val exhaustedByHeader = searchOutcome.rateLimitRemaining == 0
                    val nextCooldown = if (exhaustedByHeader) {
                        searchOutcome.rateLimitResetAtMs?.takeIf { it > callNow } ?: (callNow + 60_000L)
                    } else null
                    servicePoolDao.recordSuccess(
                        id = patEntity.id,
                        status = if (exhaustedByHeader) KeyStatus.RATE_LIMITED else KeyStatus.ACTIVE,
                        nowMs = callNow,
                        cooldownUntilMs = nextCooldown,
                        rateLimitRemaining = searchOutcome.rateLimitRemaining,
                        rateLimitResetAtMs = searchOutcome.rateLimitResetAtMs
                    )

                    val finalResults = if (request.mode == "repositories" && request.includeReadme) {
                        searchOutcome.items.map { item ->
                            val fullName = item.optString("full_name").trim()
                            val excerpt = if (fullName.contains("/")) fetchReadmeExcerptWithRotation(fullName) else ""
                            JSONObject(item.toString()).put("readme_excerpt", excerpt)
                        }
                    } else {
                        searchOutcome.items
                    }

                    val finishedAt = nowMs()
                    synchronized(cacheLock) {
                        searchCache[cacheKey] = SearchCacheEntry(
                            totalCount = searchOutcome.totalCount,
                            results = finalResults.map { JSONObject(it.toString()) },
                            expiresAtMs = finishedAt + searchCacheTtlMs
                        )
                    }

                    val latency = (finishedAt - startedAt).coerceAtLeast(1L)
                    return GitHubSearchGatewayResult.Success(
                        statusCode = 200,
                        payload = buildResponseJson(
                            query = request.query,
                            mode = request.mode,
                            cached = false,
                            latencyMs = latency,
                            totalCount = searchOutcome.totalCount,
                            items = finalResults
                        ),
                        cached = false,
                        latencyMs = latency,
                        attempts = attempts
                    )
                }

                is GitHubAttemptResult.Failure -> {
                    val failNow = nowMs()
                    val cooldownSec = searchOutcome.cooldownSeconds.coerceAtLeast(30)
                    val cooldownUntil = searchOutcome.cooldownUntilMs?.takeIf { it > failNow }
                        ?: (failNow + cooldownSec * 1000L)
                    if (searchOutcome.httpCode == 403 || searchOutcome.httpCode == 429) {
                        sawRateLimit = true
                    }
                    minRetryAfterSec = listOfNotNull(minRetryAfterSec, ((cooldownUntil - failNow + 999L) / 1000L).toInt()).minOrNull()
                    val safeErr = MaskingUtils.sanitizeErrorMessage(searchOutcome.safeError, listOf(plainPat))
                    lastSafeError = safeErr
                    servicePoolDao.recordFailureOrCooldown(
                        id = patEntity.id,
                        status = searchOutcome.keyStatus,
                        nowMs = failNow,
                        lastError = safeErr,
                        cooldownUntilMs = cooldownUntil,
                        rateLimitRemaining = searchOutcome.rateLimitRemaining,
                        rateLimitResetAtMs = searchOutcome.rateLimitResetAtMs
                    )
                }
            }
        }

        val retryAfter = minRetryAfterSec ?: if (sawRateLimit) 60 else null
        return GitHubSearchGatewayResult.Failure(
            statusCode = if (sawRateLimit) 429 else 502,
            errorCode = if (sawRateLimit) "rate_limit_exhausted" else "github_upstream_error",
            safeMessage = lastSafeError,
            retryAfterSeconds = retryAfter,
            attempts = attempts
        )
    }

    suspend fun testPat(keyId: String): Result<String> {
        val entity = servicePoolDao.getByIdOnce(keyId)
            ?: return Result.failure(IllegalArgumentException("GitHub PAT record not found."))
        val plain = CryptoManager.decryptResult(entity.encryptedKey).getOrElse { err ->
            return Result.failure(IllegalStateException("Decryption failed: ${err.message}"))
        }
        val probeUrl = "${githubApiBaseUrl.trimEnd('/')}/rate_limit"
        val sec = NetworkAddressClassifier.validateEndpointSecurity(probeUrl, allowLocalHttp = allowLocalEndpointsForTests)
        if (!sec.allowed) {
            return Result.failure(IllegalStateException(sec.errorMessage ?: "Blocked GitHub API URL"))
        }
        val req = Request.Builder()
            .url(probeUrl)
            .header("Authorization", "Bearer $plain")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "JH-GetWay-Gateway")
            .get()
            .build()

        return try {
            httpClient.newCall(req).execute().use { resp ->
                val remaining = resp.header("X-RateLimit-Remaining")?.trim()?.toIntOrNull()
                val resetAtMs = resp.header("X-RateLimit-Reset")?.trim()?.toLongOrNull()?.let { it * 1000L }
                val rawBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val safeErr = MaskingUtils.sanitizeErrorMessage("GitHub HTTP ${resp.code}: $rawBody", listOf(plain))
                    val status = when (resp.code) {
                        401 -> KeyStatus.INVALID
                        403, 429 -> KeyStatus.RATE_LIMITED
                        else -> KeyStatus.UNKNOWN
                    }
                    servicePoolDao.recordFailureOrCooldown(
                        id = entity.id,
                        status = status,
                        nowMs = nowMs(),
                        lastError = safeErr,
                        cooldownUntilMs = resetAtMs ?: (nowMs() + 120_000L),
                        rateLimitRemaining = remaining,
                        rateLimitResetAtMs = resetAtMs
                    )
                    return Result.failure(IllegalStateException(safeErr))
                }
                val parsedRemaining = remaining ?: runCatching {
                    JSONObject(rawBody).optJSONObject("resources")?.optJSONObject("search")?.optInt("remaining")
                }.getOrNull()
                servicePoolDao.recordSuccess(
                    id = entity.id,
                    status = KeyStatus.ACTIVE,
                    nowMs = nowMs(),
                    cooldownUntilMs = null,
                    rateLimitRemaining = parsedRemaining,
                    rateLimitResetAtMs = resetAtMs
                )
                Result.success(
                    "Verified GitHub PAT" + (parsedRemaining?.let { " ($it requests remaining)" } ?: ".")
                )
            }
        } catch (err: Exception) {
            val safeErr = MaskingUtils.sanitizeErrorMessage(err.message, listOf(plain))
            servicePoolDao.recordFailureOrCooldown(
                id = entity.id,
                status = KeyStatus.UNKNOWN,
                nowMs = nowMs(),
                lastError = safeErr,
                cooldownUntilMs = nowMs() + 60_000L,
                rateLimitRemaining = null,
                rateLimitResetAtMs = null
            )
            Result.failure(IllegalStateException(safeErr))
        }
    }

    private suspend fun fetchReadmeExcerptWithRotation(fullName: String): String {
        val normRepo = fullName.trim().lowercase(Locale.US)
        val now = nowMs()
        synchronized(cacheLock) {
            purgeExpiredLocked(now)
            val hit = readmeCache[normRepo]
            if (hit != null && hit.expiresAtMs > now) {
                return hit.excerpt
            }
        }

        val pats = selectOrderedPats(
            servicePoolDao.getByServiceOnce(ServicePoolType.GITHUB_SEARCH).filter { it.enabled },
            now
        )
        for (patEntity in pats) {
            val plainPat = CryptoManager.decryptResult(patEntity.encryptedKey).getOrNull() ?: continue
            val segments = fullName.trim().split("/", limit = 2)
            if (segments.size != 2) return ""
            val url = "${githubApiBaseUrl.trimEnd('/')}/repos/${segments[0]}/${segments[1]}/readme"
            val sec = NetworkAddressClassifier.validateEndpointSecurity(url, allowLocalHttp = allowLocalEndpointsForTests)
            if (!sec.allowed) return ""
            val httpReq = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $plainPat")
                .header("Accept", "application/vnd.github.raw+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "JH-GetWay-Gateway")
                .get()
                .build()
            try {
                httpClient.newCall(httpReq).execute().use { resp ->
                    val remaining = resp.header("X-RateLimit-Remaining")?.trim()?.toIntOrNull()
                    val resetAtMs = resp.header("X-RateLimit-Reset")?.trim()?.toLongOrNull()?.let { it * 1000L }
                    val callNow = nowMs()
                    if (resp.isSuccessful) {
                        val raw = resp.body?.string().orEmpty()
                        val cleaned = cleanMarkdownExcerpt(raw, maxChars = 2000)
                        val exhausted = remaining == 0
                        servicePoolDao.recordSuccess(
                            id = patEntity.id,
                            status = if (exhausted) KeyStatus.RATE_LIMITED else KeyStatus.ACTIVE,
                            nowMs = callNow,
                            cooldownUntilMs = if (exhausted) (resetAtMs ?: (callNow + 60_000L)) else null,
                            rateLimitRemaining = remaining,
                            rateLimitResetAtMs = resetAtMs
                        )
                        synchronized(cacheLock) {
                            readmeCache[normRepo] = ReadmeCacheEntry(cleaned, callNow + readmeCacheTtlMs)
                        }
                        return cleaned
                    } else if (resp.code == 404) {
                        servicePoolDao.recordSuccess(
                            id = patEntity.id,
                            status = KeyStatus.ACTIVE,
                            nowMs = callNow,
                            cooldownUntilMs = null,
                            rateLimitRemaining = remaining,
                            rateLimitResetAtMs = resetAtMs
                        )
                        synchronized(cacheLock) {
                            readmeCache[normRepo] = ReadmeCacheEntry("", callNow + readmeCacheTtlMs)
                        }
                        return ""
                    } else if (resp.code == 401 || resp.code == 403 || resp.code == 429) {
                        val cooldownUntil = resetAtMs?.takeIf { it > callNow } ?: (callNow + 60_000L)
                        servicePoolDao.recordFailureOrCooldown(
                            id = patEntity.id,
                            status = if (resp.code == 401) KeyStatus.INVALID else KeyStatus.RATE_LIMITED,
                            nowMs = callNow,
                            lastError = "GitHub README HTTP ${resp.code}",
                            cooldownUntilMs = cooldownUntil,
                            rateLimitRemaining = remaining ?: 0,
                            rateLimitResetAtMs = resetAtMs
                        )
                    } else {
                        return ""
                    }
                }
            } catch (_: Exception) {
                continue
            }
        }
        return ""
    }

    private fun isPatAvailable(pat: ServicePoolKeyEntity, now: Long): Boolean {
        if (!pat.enabled) return false
        val cd = pat.cooldownUntilMs
        if (cd != null && cd > now) return false
        val rem = pat.rateLimitRemaining
        val resetAt = pat.rateLimitResetAtMs
        if (rem != null && rem <= 0 && (resetAt == null || resetAt > now)) return false
        return true
    }

    private fun selectOrderedPats(enabledPats: List<ServicePoolKeyEntity>, now: Long): List<ServicePoolKeyEntity> {
        val available = enabledPats.filter { isPatAvailable(it, now) }
        if (available.size <= 1) return available
        val sorted = available.sortedWith(
            compareByDescending<ServicePoolKeyEntity> { it.rateLimitRemaining ?: Int.MAX_VALUE / 2 }
                .thenBy { it.priority }
                .thenBy { it.lastUsedAt ?: 0L }
                .thenBy { it.usageCount }
        )
        // Rotate among PATs tied at highest remaining & priority to spread load smoothly
        val first = sorted.first()
        val firstRem = first.rateLimitRemaining ?: Int.MAX_VALUE / 2
        val topTier = sorted.takeWhile {
            (it.rateLimitRemaining ?: Int.MAX_VALUE / 2) == firstRem && it.priority == first.priority
        }
        if (topTier.size <= 1) return sorted
        val shift = ((roundRobinCursor.getAndIncrement() and 0x7FFFFFFFL) % topTier.size).toInt()
        val rotated = (0 until topTier.size).map { idx -> topTier[(idx + shift) % topTier.size] }
        return rotated + sorted.drop(topTier.size)
    }

    private sealed interface GitHubAttemptResult {
        data class Success(
            val totalCount: Int,
            val items: List<JSONObject>,
            val rateLimitRemaining: Int?,
            val rateLimitResetAtMs: Long?
        ) : GitHubAttemptResult

        data class Failure(
            val httpCode: Int?,
            val keyStatus: KeyStatus,
            val safeError: String,
            val cooldownSeconds: Int,
            val cooldownUntilMs: Long?,
            val rateLimitRemaining: Int?,
            val rateLimitResetAtMs: Long?
        ) : GitHubAttemptResult
    }

    private fun executeSearchCall(plainPat: String, req: ParsedGitHubSearchRequest): GitHubAttemptResult {
        val queryParts = buildString {
            append(req.query.trim())
            if (!req.language.isNullOrBlank() && !req.query.contains("language:", ignoreCase = true)) {
                append(" language:")
                append(req.language.trim())
            }
        }
        val endpointPath = if (req.mode == "code") "/search/code" else "/search/repositories"
        val parsed = "${githubApiBaseUrl.trimEnd('/')}$endpointPath".toHttpUrlOrNull()
            ?: return GitHubAttemptResult.Failure(null, KeyStatus.UNKNOWN, "Invalid GitHub API base URL.", 60, null, null, null)

        val urlBuilder = parsed.newBuilder()
            .addQueryParameter("q", queryParts)
            .addQueryParameter("per_page", req.maxResults.toString())
        if (req.mode == "repositories") {
            val mappedSort = when (req.sort) {
                "stars", "forks", "updated", "help-wanted-issues" -> req.sort
                else -> "stars"
            }
            urlBuilder.addQueryParameter("sort", mappedSort)
            urlBuilder.addQueryParameter("order", "desc")
        }
        val finalUrl = urlBuilder.build().toString()
        val sec = NetworkAddressClassifier.validateEndpointSecurity(finalUrl, allowLocalHttp = allowLocalEndpointsForTests)
        if (!sec.allowed) {
            return GitHubAttemptResult.Failure(null, KeyStatus.UNKNOWN, sec.errorMessage ?: "Blocked GitHub URL", 60, null, null, null)
        }

        val acceptHeader = if (req.mode == "code") {
            "application/vnd.github.text-match+json"
        } else {
            "application/vnd.github+json"
        }
        val httpReq = Request.Builder()
            .url(finalUrl)
            .header("Authorization", "Bearer $plainPat")
            .header("Accept", acceptHeader)
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "JH-GetWay-Gateway")
            .get()
            .build()

        return try {
            httpClient.newCall(httpReq).execute().use { resp ->
                val rawBody = resp.body?.string().orEmpty()
                val remaining = resp.header("X-RateLimit-Remaining")?.trim()?.toIntOrNull()
                val resetAtMs = resp.header("X-RateLimit-Reset")?.trim()?.toLongOrNull()?.let { it * 1000L }
                val retryAfterSec = resp.header("Retry-After")?.trim()?.toIntOrNull()
                val now = nowMs()

                if (!resp.isSuccessful) {
                    val status = when (resp.code) {
                        401 -> KeyStatus.INVALID
                        403, 429 -> KeyStatus.RATE_LIMITED
                        else -> KeyStatus.UNKNOWN
                    }
                    val computedCooldownUntil = when {
                        resetAtMs != null && resetAtMs > now && (resp.code == 403 || resp.code == 429 || remaining == 0) -> resetAtMs
                        retryAfterSec != null && retryAfterSec > 0 -> now + retryAfterSec.coerceIn(30, 600) * 1000L
                        resp.code == 401 -> now + 300_000L
                        else -> now + 60_000L
                    }
                    val cooldownSec = ((computedCooldownUntil - now + 999L) / 1000L).toInt().coerceAtLeast(30)
                    val safeMsg = MaskingUtils.sanitizeErrorMessage(
                        "GitHub returned HTTP ${resp.code}: ${extractGitHubErrorMessage(rawBody)}",
                        listOf(plainPat)
                    )
                    return GitHubAttemptResult.Failure(
                        httpCode = resp.code,
                        keyStatus = status,
                        safeError = safeMsg,
                        cooldownSeconds = cooldownSec,
                        cooldownUntilMs = computedCooldownUntil,
                        rateLimitRemaining = if (resp.code == 403 || resp.code == 429) (remaining ?: 0) else remaining,
                        rateLimitResetAtMs = resetAtMs
                    )
                }

                val root = JSONObject(rawBody)
                val totalCount = root.optInt("total_count", 0)
                val arr = root.optJSONArray("items") ?: JSONArray()
                val items = if (req.mode == "code") {
                    parseCodeItems(arr, req.maxResults)
                } else {
                    parseRepositoryItems(arr, req.maxResults)
                }
                GitHubAttemptResult.Success(
                    totalCount = totalCount,
                    items = items,
                    rateLimitRemaining = remaining,
                    rateLimitResetAtMs = resetAtMs
                )
            }
        } catch (err: Exception) {
            GitHubAttemptResult.Failure(
                httpCode = null,
                keyStatus = KeyStatus.UNKNOWN,
                safeError = "GitHub request failed: ${MaskingUtils.sanitizeErrorMessage(err.message, listOf(plainPat))}",
                cooldownSeconds = 60,
                cooldownUntilMs = nowMs() + 60_000L,
                rateLimitRemaining = null,
                rateLimitResetAtMs = null
            )
        }
    }

    private fun parseRepositoryItems(arr: JSONArray, maxResults: Int): List<JSONObject> = buildList {
        for (i in 0 until minOf(arr.length(), maxResults)) {
            val o = arr.optJSONObject(i) ?: continue
            val licenseObj = o.optJSONObject("license")
            val licenseName = licenseObj?.optString("spdx_id")?.takeIf { it.isNotBlank() && it != "NOASSERTION" }
                ?: licenseObj?.optString("name")?.takeIf { it.isNotBlank() }
                ?: ""
            val topicsArr = JSONArray()
            o.optJSONArray("topics")?.let { t ->
                for (j in 0 until t.length()) {
                    t.optString(j).trim().takeIf { it.isNotEmpty() }?.let { topicsArr.put(it) }
                }
            }
            add(
                JSONObject().apply {
                    put("full_name", o.optString("full_name"))
                    put("html_url", o.optString("html_url"))
                    put("description", if (o.isNull("description")) "" else o.optString("description"))
                    put("stars", o.optInt("stargazers_count", 0))
                    put("forks", o.optInt("forks_count", 0))
                    put("language", if (o.isNull("language")) "" else o.optString("language"))
                    put("license", licenseName)
                    put("updated_at", o.optString("updated_at"))
                    put("topics", topicsArr)
                    put("readme_excerpt", "")
                }
            )
        }
    }

    private fun parseCodeItems(arr: JSONArray, maxResults: Int): List<JSONObject> = buildList {
        for (i in 0 until minOf(arr.length(), maxResults)) {
            val o = arr.optJSONObject(i) ?: continue
            val repo = o.optJSONObject("repository")
            val fullName = repo?.optString("full_name").orEmpty()
            val snippet = o.optJSONArray("text_matches")?.let { tm ->
                (0 until tm.length()).mapNotNull { idx ->
                    tm.optJSONObject(idx)?.optString("fragment")?.trim()?.takeIf { it.isNotEmpty() }
                }.joinToString("\n---\n")
            }.orEmpty().take(1500)
            add(
                JSONObject().apply {
                    put("full_name", fullName)
                    put("name", o.optString("name"))
                    put("path", o.optString("path"))
                    put("html_url", o.optString("html_url"))
                    put("description", if (repo != null && !repo.isNull("description")) repo.optString("description") else "")
                    put("snippet", snippet)
                    put("stars", repo?.optInt("stargazers_count", 0) ?: 0)
                    put("forks", repo?.optInt("forks_count", 0) ?: 0)
                    put("language", if (repo != null && !repo.isNull("language")) repo.optString("language") else "")
                    put("license", "")
                    put("updated_at", repo?.optString("updated_at").orEmpty())
                    put("topics", JSONArray())
                    put("readme_excerpt", "")
                }
            )
        }
    }

    private fun extractGitHubErrorMessage(rawBody: String): String {
        val obj = runCatching { JSONObject(rawBody) }.getOrNull()
        val msg = obj?.optString("message")?.ifBlank { rawBody } ?: rawBody
        return msg.replace(Regex("\\s+"), " ").trim().take(180)
    }

    private fun buildResponseJson(
        query: String,
        mode: String,
        cached: Boolean,
        latencyMs: Long,
        totalCount: Int,
        items: List<JSONObject>
    ): JSONObject {
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        return JSONObject().apply {
            put("query", query)
            put("mode", mode)
            put("cached", cached)
            put("latency_ms", latencyMs)
            put("total_count", totalCount)
            put("results", arr)
        }
    }

    private fun cleanMarkdownExcerpt(rawMarkdown: String, maxChars: Int = 2000): String {
        if (rawMarkdown.isBlank()) return ""
        return rawMarkdown
            .replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), "")
            .replace(Regex("\\[!\\[[^\\]]*]\\([^)]*\\)]\\([^)]*\\)"), "")
            .replace(Regex("<img[^>]*>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("\\r\\n?"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
            .take(maxChars)
    }

    private fun purgeExpiredLocked(now: Long) {
        val sIter = searchCache.entries.iterator()
        while (sIter.hasNext()) {
            if (sIter.next().value.expiresAtMs <= now) sIter.remove()
        }
        val rIter = readmeCache.entries.iterator()
        while (rIter.hasNext()) {
            if (rIter.next().value.expiresAtMs <= now) rIter.remove()
        }
    }
}
