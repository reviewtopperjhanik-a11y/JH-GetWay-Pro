package com.example.updater

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.VisibleForTesting
import androidx.core.content.FileProvider
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class AppReleaseInfo(
    val repoSlug: String,
    val tagName: String,
    val versionName: String,
    val versionCode: Int?,
    val releaseTitle: String,
    val releaseNotes: String,
    val htmlUrl: String,
    val publishedAt: String,
    val apkDownloadUrl: String,
    val apkFileName: String,
    val apkSizeBytes: Long,
    val expectedSha256: String? = null,
    val signingMode: String? = null,
    val packageId: String? = null,
    val expectedSigningCertSha256: String? = null,
    val isDebugBuild: Boolean = false
)

data class SelectedApkAsset(
    val downloadUrl: String,
    val fileName: String,
    val sizeBytes: Long,
    val isDebugAsset: Boolean
)

sealed interface ApkSignatureCheckResult {
    data object Compatible : ApkSignatureCheckResult
    data class Mismatch(val installedSha256: String, val apkSha256: String) : ApkSignatureCheckResult
    data object Unavailable : ApkSignatureCheckResult
}

enum class ApkVerificationFailureReason {
    FILE_MISSING_OR_UNREADABLE,
    INVALID_FILE_SIZE,
    DEBUG_APK_NOT_ALLOWED,
    CORRUPT_ARCHIVE,
    MISSING_PACKAGE_ID,
    PACKAGE_ID_MISMATCH,
    MISSING_MANIFEST_METADATA,
    MALFORMED_APK_SHA256,
    APK_SHA256_MISMATCH,
    MALFORMED_SIGNING_CERT_SHA256,
    MISSING_APK_SIGNER,
    SIGNING_CERT_MISMATCH,
    INSTALLED_SIGNER_UNAVAILABLE,
    INSTALLED_SIGNER_MISMATCH,
    VERSION_CODE_MISMATCH,
    NON_INCREASING_VERSION_CODE
}

sealed interface ApkVerificationResult {
    data class Verified(
        val packageName: String,
        val versionCode: Long,
        val apkSha256: String,
        val signingCertSha256: String
    ) : ApkVerificationResult

    data class Invalid(
        val reason: ApkVerificationFailureReason,
        val message: String
    ) : ApkVerificationResult

    data class SigningConflict(
        val installedSha256: String,
        val apkSha256: String
    ) : ApkVerificationResult
}

sealed interface AppUpdateCheckResult {
    data class UpdateAvailable(
        val release: AppReleaseInfo,
        val fromCache: Boolean = false,
        val checkedAtMs: Long = System.currentTimeMillis()
    ) : AppUpdateCheckResult

    data class UpToDate(
        val currentVersionName: String,
        val latestTag: String,
        val fromCache: Boolean = false,
        val checkedAtMs: Long = System.currentTimeMillis()
    ) : AppUpdateCheckResult

    data class NoReleasesPublished(
        val repoSlug: String,
        val checkedAtMs: Long = System.currentTimeMillis()
    ) : AppUpdateCheckResult

    data class RateLimited(
        val repoSlug: String,
        val retryAfterEpochMs: Long,
        val message: String,
        val cachedRelease: AppReleaseInfo? = null,
        val releasesUrl: String = "https://github.com/$repoSlug/releases"
    ) : AppUpdateCheckResult

    data class Error(
        val message: String,
        val httpCode: Int? = null,
        val cachedRelease: AppReleaseInfo? = null,
        val releasesUrl: String? = null
    ) : AppUpdateCheckResult
}

sealed interface ApkInstallLaunchResult {
    data object Launched : ApkInstallLaunchResult
    data object UnknownSourcesPermissionRequired : ApkInstallLaunchResult
    data class SigningConflict(val installedSha256: String, val apkSha256: String) : ApkInstallLaunchResult
    data class Failed(val message: String) : ApkInstallLaunchResult
}

data class UpdateCacheEntry(
    val etag: String? = null,
    val cachedRelease: AppReleaseInfo? = null,
    val cachedLatestTag: String? = null,
    val cachedVersionCode: Int? = null,
    val cachedHtmlUrl: String? = null,
    val lastCheckedAtMs: Long = 0L,
    val rateLimitRemaining: Int? = null,
    val rateLimitResetAtMs: Long = 0L,
    val retryAfterUntilMs: Long = 0L
)

interface UpdateCacheStore {
    fun get(cacheKey: String): UpdateCacheEntry?
    fun put(cacheKey: String, entry: UpdateCacheEntry)
    fun clearAll()
}

class InMemoryUpdateCacheStore : UpdateCacheStore {
    private val map = ConcurrentHashMap<String, UpdateCacheEntry>()
    override fun get(cacheKey: String): UpdateCacheEntry? = map[cacheKey]
    override fun put(cacheKey: String, entry: UpdateCacheEntry) {
        map[cacheKey] = entry
    }
    override fun clearAll() = map.clear()
}

class SharedPreferencesUpdateCacheStore(context: Context) : UpdateCacheStore {
    private val prefs = context.applicationContext.getSharedPreferences(
        "jh_getway_github_update_cache",
        Context.MODE_PRIVATE
    )

    override fun get(cacheKey: String): UpdateCacheEntry? {
        val raw = prefs.getString(cacheKey, null) ?: return null
        return runCatching { decodeEntry(JSONObject(raw)) }.getOrNull()
    }

    override fun put(cacheKey: String, entry: UpdateCacheEntry) {
        prefs.edit().putString(cacheKey, encodeEntry(entry).toString()).apply()
    }

    override fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        internal fun encodeEntry(entry: UpdateCacheEntry): JSONObject {
            return JSONObject().apply {
                if (!entry.etag.isNullOrBlank()) put("etag", entry.etag)
                if (entry.cachedRelease != null) put("cachedRelease", encodeRelease(entry.cachedRelease))
                if (!entry.cachedLatestTag.isNullOrBlank()) put("cachedLatestTag", entry.cachedLatestTag)
                if (entry.cachedVersionCode != null) put("cachedVersionCode", entry.cachedVersionCode)
                if (!entry.cachedHtmlUrl.isNullOrBlank()) put("cachedHtmlUrl", entry.cachedHtmlUrl)
                put("lastCheckedAtMs", entry.lastCheckedAtMs)
                if (entry.rateLimitRemaining != null) put("rateLimitRemaining", entry.rateLimitRemaining)
                put("rateLimitResetAtMs", entry.rateLimitResetAtMs)
                put("retryAfterUntilMs", entry.retryAfterUntilMs)
            }
        }

        internal fun decodeEntry(obj: JSONObject): UpdateCacheEntry {
            val relObj = obj.optJSONObject("cachedRelease")
            return UpdateCacheEntry(
                etag = obj.optString("etag", "").takeIf { it.isNotBlank() },
                cachedRelease = relObj?.let { decodeRelease(it) },
                cachedLatestTag = obj.optString("cachedLatestTag", "").takeIf { it.isNotBlank() },
                cachedVersionCode = if (obj.has("cachedVersionCode")) obj.optInt("cachedVersionCode").takeIf { it > 0 } else null,
                cachedHtmlUrl = obj.optString("cachedHtmlUrl", "").takeIf { it.isNotBlank() },
                lastCheckedAtMs = obj.optLong("lastCheckedAtMs", 0L),
                rateLimitRemaining = if (obj.has("rateLimitRemaining")) obj.optInt("rateLimitRemaining") else null,
                rateLimitResetAtMs = obj.optLong("rateLimitResetAtMs", 0L),
                retryAfterUntilMs = obj.optLong("retryAfterUntilMs", 0L)
            )
        }

        internal fun encodeRelease(rel: AppReleaseInfo): JSONObject {
            return JSONObject().apply {
                put("repoSlug", rel.repoSlug)
                put("tagName", rel.tagName)
                put("versionName", rel.versionName)
                if (rel.versionCode != null) put("versionCode", rel.versionCode)
                put("releaseTitle", rel.releaseTitle)
                put("releaseNotes", rel.releaseNotes)
                put("htmlUrl", rel.htmlUrl)
                put("publishedAt", rel.publishedAt)
                put("apkDownloadUrl", rel.apkDownloadUrl)
                put("apkFileName", rel.apkFileName)
                put("apkSizeBytes", rel.apkSizeBytes)
                if (!rel.expectedSha256.isNullOrBlank()) put("expectedSha256", rel.expectedSha256)
                if (!rel.signingMode.isNullOrBlank()) put("signingMode", rel.signingMode)
                if (!rel.packageId.isNullOrBlank()) put("packageId", rel.packageId)
                if (!rel.expectedSigningCertSha256.isNullOrBlank()) put("expectedSigningCertSha256", rel.expectedSigningCertSha256)
                put("isDebugBuild", rel.isDebugBuild)
            }
        }

        internal fun decodeRelease(obj: JSONObject): AppReleaseInfo? {
            val repoSlug = obj.optString("repoSlug", "").trim()
            val tagName = obj.optString("tagName", "").trim()
            val apkDownloadUrl = obj.optString("apkDownloadUrl", "").trim()
            if (repoSlug.isBlank() || tagName.isBlank() || apkDownloadUrl.isBlank()) return null
            return AppReleaseInfo(
                repoSlug = repoSlug,
                tagName = tagName,
                versionName = obj.optString("versionName", tagName),
                versionCode = if (obj.has("versionCode")) obj.optInt("versionCode").takeIf { it > 0 } else null,
                releaseTitle = obj.optString("releaseTitle", tagName),
                releaseNotes = obj.optString("releaseNotes", ""),
                htmlUrl = obj.optString("htmlUrl", "https://github.com/$repoSlug/releases"),
                publishedAt = obj.optString("publishedAt", ""),
                apkDownloadUrl = apkDownloadUrl,
                apkFileName = obj.optString("apkFileName", ""),
                apkSizeBytes = obj.optLong("apkSizeBytes", 0L),
                expectedSha256 = obj.optString("expectedSha256", "").takeIf { it.isNotBlank() },
                signingMode = obj.optString("signingMode", "").takeIf { it.isNotBlank() },
                packageId = obj.optString("packageId", "").takeIf { it.isNotBlank() },
                expectedSigningCertSha256 = obj.optString("expectedSigningCertSha256", "").takeIf { it.isNotBlank() },
                isDebugBuild = obj.optBoolean("isDebugBuild", false)
            )
        }
    }
}

internal data class ParsedRateLimitInfo(
    val isRateLimited: Boolean,
    val remaining: Int?,
    val resetAtMs: Long?,
    val retryAfterEpochMs: Long?
)

object AppUpdateManager {

    const val MAX_APK_SIZE_BYTES = 250L * 1024L * 1024L
    const val DEFAULT_CACHE_TTL_MS = 15L * 60L * 1000L
    const val DEFAULT_RATE_LIMIT_COOLDOWN_MS = 5L * 60L * 1000L
    private const val PRODUCTION_SIGNING_MODE = "production"
    private val repoSlugRegex = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
    private val sha256HexRegex = Regex("^[0-9a-f]{64}$")
    private val fallbackMemoryStore = InMemoryUpdateCacheStore()

    private val apiClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addNetworkInterceptor { chain ->
                val req = chain.request()
                val host = req.url.host
                val isLocalTest = host == "127.0.0.1" || host == "localhost"
                if (!req.url.isHttps && !isLocalTest) {
                    throw SecurityException("Only HTTPS update endpoints are permitted.")
                }
                chain.proceed(req)
            }
            .build()
    }

    private val downloadClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addNetworkInterceptor { chain ->
                val req = chain.request()
                val host = req.url.host
                val isLocalTest = host == "127.0.0.1" || host == "localhost"
                if (!req.url.isHttps && !isLocalTest) {
                    throw SecurityException("Only HTTPS APK downloads are permitted.")
                }
                chain.proceed(req)
            }
            .build()
    }

    @VisibleForTesting
    fun resetCacheForTesting(context: Context? = null) {
        fallbackMemoryStore.clearAll()
        context?.let { SharedPreferencesUpdateCacheStore(it).clearAll() }
    }

    fun normalizeGithubRepo(rawInput: String): String? {
        var cleaned = rawInput.trim()
        if (cleaned.isEmpty()) return null
        cleaned = cleaned
            .removePrefix("https://github.com/")
            .removePrefix("http://github.com/")
            .removePrefix("github.com/")
            .removeSuffix(".git")
            .trim('/')
        val parts = cleaned.split('/').filter { it.isNotBlank() }
        if (parts.size < 2) return null
        val slug = "${parts[0]}/${parts[1]}"
        return if (repoSlugRegex.matches(slug)) slug else null
    }

    fun officialReleasesUrl(repoSlug: String): String =
        "https://github.com/${repoSlug.trim('/')}/releases"

    fun normalizeSha256Hex(raw: String?): String? {
        val normalized = raw
            ?.replace(":", "")
            ?.replace(Regex("\\s+"), "")
            ?.trim()
            ?.lowercase()
            ?: return null
        return if (sha256HexRegex.matches(normalized)) normalized else null
    }

    fun isRemoteNewer(
        currentVersionCode: Int,
        currentVersionName: String,
        remoteVersionCode: Int?,
        remoteVersionNameOrTag: String
    ): Boolean {
        if (remoteVersionCode != null && currentVersionCode > 1) {
            return remoteVersionCode > currentVersionCode
        }
        val semVerCmp = compareVersionNames(remoteVersionNameOrTag, currentVersionName)
        if (semVerCmp != 0) return semVerCmp > 0
        if (remoteVersionCode != null) {
            return remoteVersionCode > currentVersionCode
        }
        return false
    }

    fun compareVersionNames(aRaw: String, bRaw: String): Int {
        val aParts = parseVersionParts(aRaw)
        val bParts = parseVersionParts(bRaw)
        val maxLen = maxOf(aParts.size, bParts.size)
        for (i in 0 until maxLen) {
            val av = aParts.getOrElse(i) { 0 }
            val bv = bParts.getOrElse(i) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }

    private fun parseVersionParts(raw: String): List<Int> {
        val cleaned = raw.trim().removePrefix("v").removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')
        return cleaned.split('.').mapNotNull { it.trim().toIntOrNull() }
    }

    fun selectPreferredApkAsset(
        assets: JSONArray,
        preferDebugApk: Boolean,
        allowLocalHttpForTesting: Boolean = false
    ): SelectedApkAsset? {
        val apks = mutableListOf<SelectedApkAsset>()
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name", "")
            val dlUrl = asset.optString("browser_download_url", "")
            val size = asset.optLong("size", 0L)
            val urlAllowed = dlUrl.startsWith("https://") ||
                (allowLocalHttpForTesting && (dlUrl.startsWith("http://127.0.0.1") || dlUrl.startsWith("http://localhost")))
            if (name.endsWith(".apk", ignoreCase = true) && urlAllowed) {
                apks += SelectedApkAsset(
                    downloadUrl = dlUrl,
                    fileName = name,
                    sizeBytes = size,
                    isDebugAsset = name.contains("debug", ignoreCase = true) || dlUrl.contains("debug", ignoreCase = true)
                )
            }
        }
        if (apks.isEmpty()) return null
        return if (preferDebugApk) {
            apks.firstOrNull { it.isDebugAsset }
        } else {
            apks.firstOrNull { !it.isDebugAsset && it.fileName.contains("release", ignoreCase = true) }
                ?: apks.firstOrNull { !it.isDebugAsset }
        }
    }

    internal fun parseEpochSecondsOrMsHeader(rawHeader: String?): Long? {
        val v = rawHeader?.trim()?.toLongOrNull() ?: return null
        if (v <= 0L) return null
        return if (v < 10_000_000_000L) v * 1000L else v
    }

    internal fun parseRetryAfterHeaderMs(rawHeader: String?, nowMs: Long): Long? {
        val raw = rawHeader?.trim()?.takeIf { it.isNotBlank() } ?: return null
        raw.toLongOrNull()?.let { sec ->
            return nowMs + sec.coerceAtLeast(1L) * 1000L
        }
        val patterns = listOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEEE, dd-MMM-yy HH:mm:ss zzz",
            "EEE MMM d HH:mm:ss yyyy"
        )
        for (pattern in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("GMT")
                }.parse(raw)?.time
            }.getOrNull()
            if (parsed != null && parsed > nowMs) {
                return parsed
            }
        }
        return null
    }

    @VisibleForTesting
    internal fun parseRateLimitHeadersAndBody(
        httpCode: Int,
        remainingHeader: String?,
        resetHeader: String?,
        retryAfterHeader: String?,
        bodyText: String,
        nowMs: Long
    ): ParsedRateLimitInfo {
        val remaining = remainingHeader?.trim()?.toIntOrNull()
        val resetMs = parseEpochSecondsOrMsHeader(resetHeader)
        val retryAfterMs = parseRetryAfterHeaderMs(retryAfterHeader, nowMs)
        val bodyConfirmsRateLimit = bodyText.contains("rate limit", ignoreCase = true) ||
            bodyText.contains("API rate limit exceeded", ignoreCase = true) ||
            bodyText.contains("secondary rate limit", ignoreCase = true)

        val isRateLimited = when (httpCode) {
            429 -> true
            403 -> remaining == 0 || retryAfterMs != null || bodyConfirmsRateLimit
            else -> false
        }

        val deadline = if (isRateLimited) {
            maxOf(retryAfterMs ?: 0L, resetMs ?: 0L).takeIf { it > nowMs }
                ?: (nowMs + DEFAULT_RATE_LIMIT_COOLDOWN_MS)
        } else null

        return ParsedRateLimitInfo(
            isRateLimited = isRateLimited,
            remaining = remaining,
            resetAtMs = resetMs,
            retryAfterEpochMs = deadline
        )
    }

    private fun formatRetryMessage(retryAfterEpochMs: Long, nowMs: Long): String {
        val remainingSec = ((retryAfterEpochMs - nowMs).coerceAtLeast(1000L) + 999L) / 1000L
        val timeText = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(retryAfterEpochMs))
        val waitText = when {
            remainingSec >= 120L -> "${(remainingSec + 59L) / 60L}m"
            else -> "${remainingSec}s"
        }
        return "GitHub API rate limit reached. Try again in $waitText (at $timeText) or open Official GitHub Releases."
    }

    private fun evaluateReleaseAgainstInstalled(
        release: AppReleaseInfo,
        currentVersionCode: Int,
        currentVersionName: String,
        expectedPackageId: String,
        isDebugFlow: Boolean,
        fromCache: Boolean,
        checkedAtMs: Long
    ): AppUpdateCheckResult? {
        if (release.isDebugBuild != isDebugFlow) return null
        if (expectedPackageId.isNotBlank() && release.packageId != expectedPackageId) return null
        if (!isDebugFlow && release.signingMode != PRODUCTION_SIGNING_MODE) return null
        val remoteCode = release.versionCode
        val newer = if (remoteCode != null) {
            remoteCode > currentVersionCode
        } else {
            isRemoteNewer(currentVersionCode, currentVersionName, null, release.versionName.ifBlank { release.tagName })
        }
        return if (newer) {
            AppUpdateCheckResult.UpdateAvailable(
                release = release,
                fromCache = fromCache,
                checkedAtMs = checkedAtMs
            )
        } else {
            AppUpdateCheckResult.UpToDate(
                currentVersionName = currentVersionName,
                latestTag = release.tagName.ifBlank { release.versionName },
                fromCache = fromCache,
                checkedAtMs = checkedAtMs
            )
        }
    }

    private fun buildCachedUpdateIfStillRelevant(
        cached: UpdateCacheEntry?,
        currentVersionCode: Int,
        currentVersionName: String,
        expectedPackageId: String,
        isDebugFlow: Boolean
    ): AppReleaseInfo? {
        val rel = cached?.cachedRelease ?: return null
        val eval = evaluateReleaseAgainstInstalled(
            release = rel,
            currentVersionCode = currentVersionCode,
            currentVersionName = currentVersionName,
            expectedPackageId = expectedPackageId,
            isDebugFlow = isDebugFlow,
            fromCache = true,
            checkedAtMs = cached.lastCheckedAtMs
        )
        return (eval as? AppUpdateCheckResult.UpdateAvailable)?.release
    }

    suspend fun checkForUpdate(
        repoInput: String,
        currentVersionCode: Int,
        currentVersionName: String,
        preferDebugApk: Boolean = false,
        apiBaseUrl: String = "https://api.github.com",
        expectedPackageId: String = BuildConfig.APPLICATION_ID,
        allowDevelopmentDebugUpdates: Boolean = false,
        context: Context? = null,
        cacheStore: UpdateCacheStore? = null,
        forceRefresh: Boolean = false,
        cacheTtlMs: Long? = null,
        nowMsProvider: () -> Long = { System.currentTimeMillis() }
    ): AppUpdateCheckResult = withContext(Dispatchers.IO) {
        if (preferDebugApk && !allowDevelopmentDebugUpdates) {
            return@withContext AppUpdateCheckResult.Error("Debug updates are disabled for production update verification.")
        }

        val repoSlug = normalizeGithubRepo(repoInput)
            ?: return@withContext AppUpdateCheckResult.Error("Invalid GitHub repository format. Use owner/repo.")

        val cleanBase = apiBaseUrl.trimEnd('/')
        val allowLocalTestHttp = cleanBase.startsWith("http://127.0.0.1") || cleanBase.startsWith("http://localhost")
        val isDebugFlow = preferDebugApk && allowDevelopmentDebugUpdates
        val store = cacheStore ?: context?.let { SharedPreferencesUpdateCacheStore(it) } ?: fallbackMemoryStore
        val effectiveTtlMs = cacheTtlMs ?: if (cacheStore != null || context != null) DEFAULT_CACHE_TTL_MS else 0L
        val cacheKey = "${cleanBase.lowercase(Locale.US)}|${repoSlug.lowercase(Locale.US)}|debug=$isDebugFlow"
        val nowMs = nowMsProvider()
        val existingCache = store.get(cacheKey)
        val releasesUrl = officialReleasesUrl(repoSlug)
        val cachedAvailableUpdate = buildCachedUpdateIfStillRelevant(
            cached = existingCache,
            currentVersionCode = currentVersionCode,
            currentVersionName = currentVersionName,
            expectedPackageId = expectedPackageId,
            isDebugFlow = isDebugFlow
        )

        // Strict rate-limit deadline check: prevent repeated requests before retry deadline
        if (existingCache != null && existingCache.retryAfterUntilMs > nowMs) {
            return@withContext AppUpdateCheckResult.RateLimited(
                repoSlug = repoSlug,
                retryAfterEpochMs = existingCache.retryAfterUntilMs,
                message = formatRetryMessage(existingCache.retryAfterUntilMs, nowMs),
                cachedRelease = cachedAvailableUpdate,
                releasesUrl = releasesUrl
            )
        }

        // Sensible TTL cache check to avoid redundant GitHub API hits
        if (!forceRefresh && effectiveTtlMs > 0L && existingCache != null && existingCache.lastCheckedAtMs > 0L) {
            val ageMs = nowMs - existingCache.lastCheckedAtMs
            if (ageMs in 0L until effectiveTtlMs) {
                existingCache.cachedRelease?.let { cachedRel ->
                    evaluateReleaseAgainstInstalled(
                        release = cachedRel,
                        currentVersionCode = currentVersionCode,
                        currentVersionName = currentVersionName,
                        expectedPackageId = expectedPackageId,
                        isDebugFlow = isDebugFlow,
                        fromCache = true,
                        checkedAtMs = existingCache.lastCheckedAtMs
                    )?.let { return@withContext it }
                }
                if (!existingCache.cachedLatestTag.isNullOrBlank() &&
                    existingCache.cachedVersionCode != null &&
                    existingCache.cachedVersionCode <= currentVersionCode
                ) {
                    return@withContext AppUpdateCheckResult.UpToDate(
                        currentVersionName = currentVersionName,
                        latestTag = existingCache.cachedLatestTag,
                        fromCache = true,
                        checkedAtMs = existingCache.lastCheckedAtMs
                    )
                }
            }
        }

        val url = "$cleanBase/repos/$repoSlug/releases/latest"

        fun buildRequest(includeEtag: Boolean): Request {
            val builder = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "JH-GetWay-Android/$currentVersionName")
                .get()
            if (includeEtag && !existingCache?.etag.isNullOrBlank()) {
                builder.header("If-None-Match", existingCache!!.etag!!)
            }
            return builder.build()
        }

        runCatching {
            var responseResult: AppUpdateCheckResult? = null
            var retryWithoutEtag = false

            apiClient.newCall(buildRequest(includeEtag = true)).execute().use { resp ->
                val remainingHeader = resp.header("X-RateLimit-Remaining")
                val resetHeader = resp.header("X-RateLimit-Reset")
                val retryAfterHeader = resp.header("Retry-After")
                val etagHeader = resp.header("ETag")?.trim()?.takeIf { it.isNotBlank() }

                val remainingVal = remainingHeader?.trim()?.toIntOrNull()
                val resetEpochMs = parseEpochSecondsOrMsHeader(resetHeader) ?: 0L
                val preemptiveCooldownUntil = if (remainingVal == 0 && resetEpochMs > nowMs) resetEpochMs else 0L

                if (resp.code == 304) {
                    val updatedCache = (existingCache ?: UpdateCacheEntry()).copy(
                        etag = etagHeader ?: existingCache?.etag,
                        lastCheckedAtMs = nowMs,
                        rateLimitRemaining = remainingVal ?: existingCache?.rateLimitRemaining,
                        rateLimitResetAtMs = if (resetEpochMs > 0L) resetEpochMs else (existingCache?.rateLimitResetAtMs ?: 0L),
                        retryAfterUntilMs = maxOf(existingCache?.retryAfterUntilMs ?: 0L, preemptiveCooldownUntil)
                    )
                    store.put(cacheKey, updatedCache)

                    val cachedEval = updatedCache.cachedRelease?.let { cachedRel ->
                        evaluateReleaseAgainstInstalled(
                            release = cachedRel,
                            currentVersionCode = currentVersionCode,
                            currentVersionName = currentVersionName,
                            expectedPackageId = expectedPackageId,
                            isDebugFlow = isDebugFlow,
                            fromCache = true,
                            checkedAtMs = nowMs
                        )
                    }
                    when {
                        cachedEval != null -> {
                            responseResult = cachedEval
                        }
                        !updatedCache.cachedLatestTag.isNullOrBlank() &&
                            updatedCache.cachedVersionCode != null &&
                            updatedCache.cachedVersionCode <= currentVersionCode -> {
                            responseResult = AppUpdateCheckResult.UpToDate(
                                currentVersionName = currentVersionName,
                                latestTag = updatedCache.cachedLatestTag,
                                fromCache = true,
                                checkedAtMs = nowMs
                            )
                        }
                        else -> {
                            retryWithoutEtag = true
                        }
                    }
                    return@use
                }

                if (resp.code == 404) {
                    store.put(
                        cacheKey,
                        (existingCache ?: UpdateCacheEntry()).copy(
                            lastCheckedAtMs = nowMs,
                            rateLimitRemaining = remainingVal,
                            rateLimitResetAtMs = resetEpochMs,
                            retryAfterUntilMs = preemptiveCooldownUntil
                        )
                    )
                    responseResult = AppUpdateCheckResult.NoReleasesPublished(repoSlug, nowMs)
                    return@use
                }

                if (resp.code == 403 || resp.code == 429) {
                    val errBody = resp.body?.string().orEmpty()
                    val parsed = parseRateLimitHeadersAndBody(
                        httpCode = resp.code,
                        remainingHeader = remainingHeader,
                        resetHeader = resetHeader,
                        retryAfterHeader = retryAfterHeader,
                        bodyText = errBody,
                        nowMs = nowMs
                    )
                    if (parsed.isRateLimited) {
                        val deadline = parsed.retryAfterEpochMs ?: (nowMs + DEFAULT_RATE_LIMIT_COOLDOWN_MS)
                        store.put(
                            cacheKey,
                            (existingCache ?: UpdateCacheEntry()).copy(
                                rateLimitRemaining = parsed.remaining ?: 0,
                                rateLimitResetAtMs = parsed.resetAtMs ?: deadline,
                                retryAfterUntilMs = deadline
                            )
                        )
                        responseResult = AppUpdateCheckResult.RateLimited(
                            repoSlug = repoSlug,
                            retryAfterEpochMs = deadline,
                            message = formatRetryMessage(deadline, nowMs),
                            cachedRelease = cachedAvailableUpdate,
                            releasesUrl = releasesUrl
                        )
                    } else {
                        val detail = runCatching { JSONObject(errBody).optString("message", "").trim() }
                            .getOrNull()
                            ?.takeIf { it.isNotBlank() }
                        val msg = buildString {
                            append("GitHub access forbidden (HTTP 403)")
                            if (detail != null) append(": $detail")
                            append(". Repository may be private or restricted. Open Official GitHub Releases manually.")
                        }
                        responseResult = AppUpdateCheckResult.Error(
                            message = msg,
                            httpCode = 403,
                            cachedRelease = cachedAvailableUpdate,
                            releasesUrl = releasesUrl
                        )
                    }
                    return@use
                }

                if (!resp.isSuccessful) {
                    responseResult = AppUpdateCheckResult.Error(
                        message = "GitHub check failed (HTTP ${resp.code}).",
                        httpCode = resp.code,
                        cachedRelease = cachedAvailableUpdate,
                        releasesUrl = releasesUrl
                    )
                    return@use
                }

                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) {
                    responseResult = AppUpdateCheckResult.Error(
                        message = "Empty response from GitHub Releases API.",
                        cachedRelease = cachedAvailableUpdate,
                        releasesUrl = releasesUrl
                    )
                    return@use
                }

                responseResult = parseAndValidateReleaseResponse(
                    body = body,
                    repoSlug = repoSlug,
                    currentVersionCode = currentVersionCode,
                    currentVersionName = currentVersionName,
                    expectedPackageId = expectedPackageId,
                    isDebugFlow = isDebugFlow,
                    allowLocalTestHttp = allowLocalTestHttp,
                    etagHeader = etagHeader,
                    remainingVal = remainingVal,
                    resetEpochMs = resetEpochMs,
                    preemptiveCooldownUntil = preemptiveCooldownUntil,
                    nowMs = nowMs,
                    store = store,
                    cacheKey = cacheKey,
                    existingCache = existingCache,
                    releasesUrl = releasesUrl
                )
            }

            if (retryWithoutEtag) {
                apiClient.newCall(buildRequest(includeEtag = false)).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext AppUpdateCheckResult.Error(
                            message = "GitHub check failed (HTTP ${resp.code}).",
                            httpCode = resp.code,
                            cachedRelease = cachedAvailableUpdate,
                            releasesUrl = releasesUrl
                        )
                    }
                    val body = resp.body?.string().orEmpty()
                    if (body.isBlank()) {
                        return@withContext AppUpdateCheckResult.Error(
                            message = "Empty response from GitHub Releases API.",
                            cachedRelease = cachedAvailableUpdate,
                            releasesUrl = releasesUrl
                        )
                    }
                    val remainingVal = resp.header("X-RateLimit-Remaining")?.trim()?.toIntOrNull()
                    val resetEpochMs = parseEpochSecondsOrMsHeader(resp.header("X-RateLimit-Reset")) ?: 0L
                    val etagHeader = resp.header("ETag")?.trim()?.takeIf { it.isNotBlank() }
                    val preemptiveCooldownUntil = if (remainingVal == 0 && resetEpochMs > nowMs) resetEpochMs else 0L
                    return@withContext parseAndValidateReleaseResponse(
                        body = body,
                        repoSlug = repoSlug,
                        currentVersionCode = currentVersionCode,
                        currentVersionName = currentVersionName,
                        expectedPackageId = expectedPackageId,
                        isDebugFlow = isDebugFlow,
                        allowLocalTestHttp = allowLocalTestHttp,
                        etagHeader = etagHeader,
                        remainingVal = remainingVal,
                        resetEpochMs = resetEpochMs,
                        preemptiveCooldownUntil = preemptiveCooldownUntil,
                        nowMs = nowMs,
                        store = store,
                        cacheKey = cacheKey,
                        existingCache = existingCache,
                        releasesUrl = releasesUrl
                    )
                }
            }

            responseResult ?: AppUpdateCheckResult.Error(
                message = "Unable to reach GitHub Releases.",
                cachedRelease = cachedAvailableUpdate,
                releasesUrl = releasesUrl
            )
        }.getOrElse { err ->
            AppUpdateCheckResult.Error(
                message = err.message ?: "Unable to reach GitHub Releases.",
                cachedRelease = cachedAvailableUpdate,
                releasesUrl = releasesUrl
            )
        }
    }

    private fun parseAndValidateReleaseResponse(
        body: String,
        repoSlug: String,
        currentVersionCode: Int,
        currentVersionName: String,
        expectedPackageId: String,
        isDebugFlow: Boolean,
        allowLocalTestHttp: Boolean,
        etagHeader: String?,
        remainingVal: Int?,
        resetEpochMs: Long,
        preemptiveCooldownUntil: Long,
        nowMs: Long,
        store: UpdateCacheStore,
        cacheKey: String,
        existingCache: UpdateCacheEntry?,
        releasesUrl: String
    ): AppUpdateCheckResult {
        val json = JSONObject(body)
        val tagName = json.optString("tag_name", "").trim()
        val releaseTitle = json.optString("name", tagName).ifBlank { tagName }
        val releaseNotes = json.optString("body", "").trim()
        val htmlUrl = json.optString("html_url", "https://github.com/$repoSlug/releases/latest")
        val publishedAt = json.optString("published_at", "")

        val assets = json.optJSONArray("assets") ?: JSONArray()
        val selectedApk = selectPreferredApkAsset(assets, isDebugFlow, allowLocalTestHttp)
        var manifestUrl = ""
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name", "")
            val dlUrl = asset.optString("browser_download_url", "")
            val urlAllowed = dlUrl.startsWith("https://") ||
                (allowLocalTestHttp && (dlUrl.startsWith("http://127.0.0.1") || dlUrl.startsWith("http://localhost")))
            if (name.equals("update-manifest.json", ignoreCase = true) && urlAllowed) {
                manifestUrl = dlUrl
            }
        }

        val fallbackReleasesUrl = htmlUrl.ifBlank { releasesUrl }
        val preservedCached = buildCachedUpdateIfStillRelevant(
            cached = existingCache,
            currentVersionCode = currentVersionCode,
            currentVersionName = currentVersionName,
            expectedPackageId = expectedPackageId,
            isDebugFlow = isDebugFlow
        )

        if (selectedApk == null) {
            return AppUpdateCheckResult.Error(
                message = "Latest release ($tagName) has no valid APK asset attached.",
                cachedRelease = preservedCached,
                releasesUrl = fallbackReleasesUrl
            )
        }
        if (!isDebugFlow && selectedApk.isDebugAsset) {
            return AppUpdateCheckResult.Error(
                message = "Debug APK cannot be installed as a production update.",
                cachedRelease = preservedCached,
                releasesUrl = fallbackReleasesUrl
            )
        }
        if (selectedApk.sizeBytes <= 0L || selectedApk.sizeBytes > MAX_APK_SIZE_BYTES) {
            return AppUpdateCheckResult.Error(
                message = "Release APK size is missing or exceeds the allowed maximum.",
                cachedRelease = preservedCached,
                releasesUrl = fallbackReleasesUrl
            )
        }
        if (manifestUrl.isBlank()) {
            return AppUpdateCheckResult.Error(
                message = "Release manifest (update-manifest.json) is missing.",
                cachedRelease = preservedCached,
                releasesUrl = fallbackReleasesUrl
            )
        }

        val manifestObj = fetchUpdateManifest(manifestUrl, currentVersionName)
            ?: return AppUpdateCheckResult.Error(
                message = "Release manifest is missing or malformed.",
                cachedRelease = preservedCached,
                releasesUrl = fallbackReleasesUrl
            )

        val manifestVersionCode = manifestObj.optInt("versionCode", -1).takeIf { it > 0 }
            ?: return AppUpdateCheckResult.Error(
                message = "Release manifest has missing or invalid versionCode.",
                cachedRelease = preservedCached,
                releasesUrl = fallbackReleasesUrl
            )
        val manifestVersionName = manifestObj.optString("versionName", "").trim()
        val manifestPackageId = manifestObj.optString("packageId", manifestObj.optString("applicationId", "")).trim()
        val manifestApkName = manifestObj.optString("apkName", "").trim()
        val releaseSha256 = manifestObj.optString("sha256", "").trim()
        val debugSha256 = manifestObj.optString("debugSha256", "").trim()
        val signingMode = manifestObj.optString("signingMode", "").trim()
        val signingCertSha256 = manifestObj.optString("signingCertSha256", "").trim()

        if (manifestPackageId.isBlank()) {
            return AppUpdateCheckResult.Error(
                message = "Release manifest is missing packageId.",
                cachedRelease = preservedCached,
                releasesUrl = fallbackReleasesUrl
            )
        }
        if (expectedPackageId.isNotBlank() && manifestPackageId != expectedPackageId) {
            return AppUpdateCheckResult.Error(
                message = "Release package ID mismatch ($manifestPackageId != $expectedPackageId).",
                cachedRelease = preservedCached,
                releasesUrl = fallbackReleasesUrl
            )
        }
        if (!isDebugFlow && manifestApkName.isNotBlank() && manifestApkName != selectedApk.fileName) {
            return AppUpdateCheckResult.Error(
                message = "Release manifest APK name mismatch ($manifestApkName != ${selectedApk.fileName}).",
                cachedRelease = preservedCached,
                releasesUrl = fallbackReleasesUrl
            )
        }

        val effectiveVersionName = manifestVersionName.ifBlank { tagName.removePrefix("v").removePrefix("V") }
        val validatedRelease: AppReleaseInfo = if (!isDebugFlow) {
            if (signingMode != PRODUCTION_SIGNING_MODE) {
                return AppUpdateCheckResult.Error(
                    message = "Release signing mode '$signingMode' is not valid for production updates.",
                    cachedRelease = preservedCached,
                    releasesUrl = fallbackReleasesUrl
                )
            }
            val validApkSha = normalizeSha256Hex(releaseSha256)
                ?: return AppUpdateCheckResult.Error(
                    message = "Release manifest has missing or malformed APK SHA-256 checksum.",
                    cachedRelease = preservedCached,
                    releasesUrl = fallbackReleasesUrl
                )
            val validCertSha = normalizeSha256Hex(signingCertSha256)
                ?: return AppUpdateCheckResult.Error(
                    message = "Release manifest has missing or malformed signing certificate fingerprint.",
                    cachedRelease = preservedCached,
                    releasesUrl = fallbackReleasesUrl
                )

            AppReleaseInfo(
                repoSlug = repoSlug,
                tagName = tagName,
                versionName = effectiveVersionName.ifBlank { tagName },
                versionCode = manifestVersionCode,
                releaseTitle = releaseTitle,
                releaseNotes = releaseNotes,
                htmlUrl = htmlUrl,
                publishedAt = publishedAt,
                apkDownloadUrl = selectedApk.downloadUrl,
                apkFileName = selectedApk.fileName.ifBlank { "JH-GetWay-$tagName.apk" },
                apkSizeBytes = selectedApk.sizeBytes,
                expectedSha256 = validApkSha,
                signingMode = PRODUCTION_SIGNING_MODE,
                packageId = manifestPackageId,
                expectedSigningCertSha256 = validCertSha,
                isDebugBuild = false
            )
        } else {
            val validDebugSha = normalizeSha256Hex(debugSha256)
                ?: return AppUpdateCheckResult.Error("Development debug manifest has missing or malformed debugSha256.")
            AppReleaseInfo(
                repoSlug = repoSlug,
                tagName = tagName,
                versionName = effectiveVersionName.ifBlank { tagName },
                versionCode = manifestVersionCode,
                releaseTitle = releaseTitle,
                releaseNotes = releaseNotes,
                htmlUrl = htmlUrl,
                publishedAt = publishedAt,
                apkDownloadUrl = selectedApk.downloadUrl,
                apkFileName = selectedApk.fileName.ifBlank { "JH-GetWay-$tagName-debug.apk" },
                apkSizeBytes = selectedApk.sizeBytes,
                expectedSha256 = validDebugSha,
                signingMode = "debug-persistent",
                packageId = manifestPackageId,
                expectedSigningCertSha256 = null,
                isDebugBuild = true
            )
        }

        store.put(
            cacheKey,
            UpdateCacheEntry(
                etag = etagHeader ?: existingCache?.etag,
                cachedRelease = validatedRelease,
                cachedLatestTag = tagName.ifBlank { manifestVersionName },
                cachedVersionCode = manifestVersionCode,
                cachedHtmlUrl = htmlUrl,
                lastCheckedAtMs = nowMs,
                rateLimitRemaining = remainingVal,
                rateLimitResetAtMs = resetEpochMs,
                retryAfterUntilMs = preemptiveCooldownUntil
            )
        )

        if (manifestVersionCode <= currentVersionCode) {
            return AppUpdateCheckResult.UpToDate(
                currentVersionName = currentVersionName,
                latestTag = tagName.ifBlank { manifestVersionName },
                fromCache = false,
                checkedAtMs = nowMs
            )
        }
        return AppUpdateCheckResult.UpdateAvailable(
            release = validatedRelease,
            fromCache = false,
            checkedAtMs = nowMs
        )
    }

    suspend fun checkForDevelopmentDebugUpdate(
        repoInput: String,
        currentVersionCode: Int,
        currentVersionName: String,
        apiBaseUrl: String = "https://api.github.com",
        expectedPackageId: String = BuildConfig.APPLICATION_ID,
        context: Context? = null,
        cacheStore: UpdateCacheStore? = null,
        forceRefresh: Boolean = false
    ): AppUpdateCheckResult {
        return checkForUpdate(
            repoInput = repoInput,
            currentVersionCode = currentVersionCode,
            currentVersionName = currentVersionName,
            preferDebugApk = true,
            apiBaseUrl = apiBaseUrl,
            expectedPackageId = expectedPackageId,
            allowDevelopmentDebugUpdates = true,
            context = context,
            cacheStore = cacheStore,
            forceRefresh = forceRefresh
        )
    }

    private fun fetchUpdateManifest(manifestUrl: String, currentVersionName: String): JSONObject? {
        return runCatching {
            val req = Request.Builder()
                .url(manifestUrl)
                .header("User-Agent", "JH-GetWay-Android/$currentVersionName")
                .get()
                .build()
            apiClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val raw = resp.body?.string().orEmpty()
                if (raw.isBlank()) null else JSONObject(raw)
            }
        }.getOrNull()
    }

    suspend fun downloadApkWithProgress(
        context: Context,
        release: AppReleaseInfo,
        onProgress: (Int) -> Unit,
        allowLocalHttpForTesting: Boolean = false
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val isLocalTestUrl = allowLocalHttpForTesting &&
                (release.apkDownloadUrl.startsWith("http://127.0.0.1") || release.apkDownloadUrl.startsWith("http://localhost"))
            if (!release.apkDownloadUrl.startsWith("https://") && !isLocalTestUrl) {
                throw SecurityException("APK download URL must use HTTPS.")
            }
            if (!release.isDebugBuild) {
                if (release.apkFileName.contains("debug", ignoreCase = true) ||
                    release.apkDownloadUrl.contains("debug", ignoreCase = true) ||
                    release.signingMode != PRODUCTION_SIGNING_MODE
                ) {
                    throw SecurityException("Debug APK cannot be downloaded as a production update.")
                }
            }
            val expectedSha = normalizeSha256Hex(release.expectedSha256)
                ?: throw SecurityException("Release manifest is missing a valid SHA-256 checksum.")
            if (release.apkSizeBytes <= 0L || release.apkSizeBytes > MAX_APK_SIZE_BYTES) {
                throw SecurityException("APK file size is missing or exceeds safety limit.")
            }

            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val safeName = release.apkFileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                .ifBlank { "jh-getway-update.apk" }
            val tempFile = File(updatesDir, "$safeName.part")
            val metaFile = File(updatesDir, "$safeName.part.meta")
            val finalFile = File(updatesDir, safeName)

            // Clean up stale artifacts from OTHER releases only; preserve matching resumable .part
            updatesDir.listFiles()?.forEach { old ->
                if (old.isFile &&
                    old.name != tempFile.name &&
                    old.name != metaFile.name &&
                    old.name != finalFile.name &&
                    (old.name.endsWith(".apk") || old.name.endsWith(".part") || old.name.endsWith(".part.meta"))
                ) {
                    old.delete()
                }
            }

            // If final APK already exists and matches exact size & SHA-256, reuse it cleanly
            if (finalFile.exists() && finalFile.isFile && finalFile.length() == release.apkSizeBytes) {
                val existingFinalSha = runCatching { computeFileSha256(finalFile) }.getOrNull()
                if (existingFinalSha != null && MessageDigest.isEqual(
                        existingFinalSha.toByteArray(Charsets.US_ASCII),
                        expectedSha.toByteArray(Charsets.US_ASCII)
                    )
                ) {
                    onProgress(100)
                    return@runCatching finalFile
                } else {
                    finalFile.delete()
                }
            } else if (finalFile.exists()) {
                finalFile.delete()
            }

            var resumeOffset = 0L
            var resumeEtag: String? = null
            if (tempFile.exists() && metaFile.exists()) {
                val meta = runCatching { JSONObject(metaFile.readText()) }.getOrNull()
                val sameTarget = meta != null &&
                    meta.optString("url") == release.apkDownloadUrl &&
                    meta.optString("sha256") == expectedSha &&
                    meta.optLong("sizeBytes", -1L) == release.apkSizeBytes
                val partLen = tempFile.length()
                if (sameTarget && partLen in 1L until release.apkSizeBytes) {
                    resumeOffset = partLen
                    resumeEtag = meta?.optString("etag", "")?.takeIf { it.isNotBlank() }
                } else if (sameTarget && partLen == release.apkSizeBytes) {
                    val partSha = runCatching { computeFileSha256(tempFile) }.getOrNull()
                    if (partSha != null && MessageDigest.isEqual(
                            partSha.toByteArray(Charsets.US_ASCII),
                            expectedSha.toByteArray(Charsets.US_ASCII)
                        )
                    ) {
                        if (!tempFile.renameTo(finalFile)) {
                            tempFile.copyTo(finalFile, overwrite = true)
                            tempFile.delete()
                        }
                        metaFile.delete()
                        onProgress(100)
                        return@runCatching finalFile
                    } else {
                        tempFile.delete()
                        metaFile.delete()
                    }
                } else {
                    tempFile.delete()
                    metaFile.delete()
                }
            } else {
                tempFile.delete()
                metaFile.delete()
            }

            fun executeDownloadAttempt(offsetBytes: Long, etagForRange: String?): File {
                val reqBuilder = Request.Builder()
                    .url(release.apkDownloadUrl)
                    .header("Accept", "application/octet-stream")
                    .header("User-Agent", "JH-GetWay-Android")
                    .get()
                if (offsetBytes > 0L) {
                    reqBuilder.header("Range", "bytes=$offsetBytes-")
                    if (!etagForRange.isNullOrBlank()) {
                        reqBuilder.header("If-Range", etagForRange)
                    }
                }
                return downloadClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (resp.code == 416 && offsetBytes > 0L) {
                        tempFile.delete()
                        metaFile.delete()
                        return@use executeDownloadAttempt(0L, null)
                    }
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("Download failed (HTTP ${resp.code})")
                    }
                    val body = resp.body ?: throw IllegalStateException("Empty APK response body")
                    val appendResume = offsetBytes > 0L && resp.code == 206
                    val headerLength = body.contentLength()
                    if (headerLength > MAX_APK_SIZE_BYTES) {
                        throw SecurityException("APK download exceeds maximum allowed size.")
                    }
                    val expectedTotalBytes = if (release.apkSizeBytes > 0L) {
                        release.apkSizeBytes
                    } else if (appendResume && headerLength > 0L) {
                        offsetBytes + headerLength
                    } else {
                        headerLength
                    }
                    var downloaded = if (appendResume) offsetBytes else 0L
                    var lastReportedPct = if (expectedTotalBytes > 0L) {
                        ((downloaded * 100L) / expectedTotalBytes).toInt().coerceIn(0, 100)
                    } else -1
                    if (lastReportedPct > 0) onProgress(lastReportedPct)

                    val respEtag = resp.header("ETag")?.trim()?.takeIf { it.isNotBlank() } ?: etagForRange
                    metaFile.writeText(
                        JSONObject()
                            .put("url", release.apkDownloadUrl)
                            .put("sha256", expectedSha)
                            .put("sizeBytes", release.apkSizeBytes)
                            .apply { if (!respEtag.isNullOrBlank()) put("etag", respEtag) }
                            .toString()
                    )

                    try {
                        body.byteStream().use { input ->
                            FileOutputStream(tempFile, appendResume).use { output ->
                                val buffer = ByteArray(16 * 1024)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read <= 0) break
                                    downloaded += read
                                    if (downloaded > MAX_APK_SIZE_BYTES) {
                                        tempFile.delete()
                                        metaFile.delete()
                                        throw SecurityException("APK download exceeded maximum allowed size.")
                                    }
                                    output.write(buffer, 0, read)
                                    if (expectedTotalBytes > 0L) {
                                        val pct = ((downloaded * 100L) / expectedTotalBytes).toInt().coerceIn(0, 100)
                                        if (pct != lastReportedPct) {
                                            lastReportedPct = pct
                                            onProgress(pct)
                                        }
                                    }
                                }
                                output.flush()
                            }
                        }
                    } catch (ioe: IOException) {
                        // Keep partial .part and .part.meta intact so subsequent retry can resume cleanly
                        throw ioe
                    }

                    val actualSize = tempFile.length()
                    if (actualSize <= 0L || (release.apkSizeBytes > 0L && actualSize > release.apkSizeBytes)) {
                        tempFile.delete()
                        metaFile.delete()
                        throw SecurityException("Downloaded APK size mismatch ($actualSize != ${release.apkSizeBytes}).")
                    }
                    if (release.apkSizeBytes > 0L && actualSize < release.apkSizeBytes) {
                        throw IllegalStateException("Download interrupted ($actualSize/${release.apkSizeBytes} bytes). Retry to resume.")
                    }

                    val actualSha256 = computeFileSha256(tempFile)
                    if (!MessageDigest.isEqual(
                            actualSha256.toByteArray(Charsets.US_ASCII),
                            expectedSha.toByteArray(Charsets.US_ASCII)
                        )
                    ) {
                        tempFile.delete()
                        metaFile.delete()
                        throw SecurityException("APK SHA-256 verification failed. File may be corrupted.")
                    }

                    if (finalFile.exists()) finalFile.delete()
                    if (!tempFile.renameTo(finalFile)) {
                        tempFile.copyTo(finalFile, overwrite = true)
                        tempFile.delete()
                    }
                    metaFile.delete()
                    onProgress(100)
                    finalFile
                }
            }

            executeDownloadAttempt(resumeOffset, resumeEtag)
        }
    }

    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun createUnknownSourcesSettingsIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun hasMatchingSigningCertificate(
        installedDigests: Set<String>,
        apkDigests: Set<String>
    ): Boolean {
        if (installedDigests.isEmpty() || apkDigests.isEmpty()) return false
        return installedDigests.any { it in apkDigests }
    }

    @Suppress("DEPRECATION")
    private fun extractActiveSigningSignatures(packageInfo: PackageInfo?): Array<Signature> {
        if (packageInfo == null) return emptyArray()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = packageInfo.signingInfo
            if (info != null) {
                info.apkContentsSigners ?: emptyArray()
            } else {
                packageInfo.signatures ?: emptyArray()
            }
        } else {
            packageInfo.signatures ?: emptyArray()
        }
    }

    private fun extractActiveSigningDigests(packageInfo: PackageInfo?): Set<String> {
        return extractActiveSigningSignatures(packageInfo).mapNotNull { sig ->
            runCatching {
                val digest = MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
                digest.joinToString("") { "%02x".format(it) }
            }.getOrNull()
        }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun packageManagerSigningFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
    }

    @Suppress("DEPRECATION")
    fun getInstalledVersionCode(context: Context): Long {
        return runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            getPackageVersionCode(info)
        }.getOrDefault(BuildConfig.VERSION_CODE.toLong())
    }

    @Suppress("DEPRECATION")
    fun getPackageVersionCode(packageInfo: PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
    }

    private fun computeFileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isAndroidVerifiedRotationCompatible(
        context: Context,
        installedInfo: PackageInfo,
        archiveInfo: PackageInfo
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val installedSigning = installedInfo.signingInfo ?: return false
        val archiveSigning = archiveInfo.signingInfo ?: return false
        if (installedSigning.hasMultipleSigners() || archiveSigning.hasMultipleSigners()) return false
        val apkSignatures = archiveSigning.apkContentsSigners ?: return false
        if (apkSignatures.size != 1) return false

        // Only trust Android PackageManager's verified signing lineage check (never arbitrary history arrays)
        val pm = context.packageManager
        val newApkCertRaw = apkSignatures[0].toByteArray()
        return runCatching {
            pm.hasSigningCertificate(context.packageName, newApkCertRaw, PackageManager.CERT_INPUT_RAW_X509)
        }.getOrDefault(false)
    }

    fun verifyApkSignatureMatchesInstalledApp(context: Context, apkFile: File): ApkSignatureCheckResult {
        if (!apkFile.exists() || !apkFile.isFile || !apkFile.canRead()) {
            return ApkSignatureCheckResult.Unavailable
        }
        return runCatching {
            val pm = context.packageManager
            val flags = packageManagerSigningFlags()
            val installedInfo = pm.getPackageInfo(context.packageName, flags)
            val archiveInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
                ?: return@runCatching ApkSignatureCheckResult.Unavailable

            val installedDigests = extractActiveSigningDigests(installedInfo)
            val apkDigests = extractActiveSigningDigests(archiveInfo)
            if (installedDigests.isEmpty() || apkDigests.isEmpty()) {
                return@runCatching ApkSignatureCheckResult.Unavailable
            }

            if (hasMatchingSigningCertificate(installedDigests, apkDigests) ||
                isAndroidVerifiedRotationCompatible(context, installedInfo, archiveInfo)
            ) {
                ApkSignatureCheckResult.Compatible
            } else {
                ApkSignatureCheckResult.Mismatch(
                    installedSha256 = installedDigests.first(),
                    apkSha256 = apkDigests.first()
                )
            }
        }.getOrElse { ApkSignatureCheckResult.Unavailable }
    }

    fun verifyProductionApkForInstall(
        context: Context,
        apkFile: File,
        release: AppReleaseInfo?,
        installedVersionCode: Long = getInstalledVersionCode(context)
    ): ApkVerificationResult {
        return verifyProductionApkForInstallInternal(
            context = context,
            apkFile = apkFile,
            release = release,
            installedVersionCode = installedVersionCode,
            archiveInfoProvider = null,
            installedInfoProvider = null
        )
    }

    @VisibleForTesting
    internal fun verifyProductionApkForInstallInternal(
        context: Context,
        apkFile: File,
        release: AppReleaseInfo?,
        installedVersionCode: Long = getInstalledVersionCode(context),
        archiveInfoProvider: ((File) -> PackageInfo?)? = null,
        installedInfoProvider: ((Context) -> PackageInfo?)? = null
    ): ApkVerificationResult {
        if (!apkFile.exists() || !apkFile.isFile || !apkFile.canRead()) {
            return ApkVerificationResult.Invalid(
                ApkVerificationFailureReason.FILE_MISSING_OR_UNREADABLE,
                "Downloaded APK file is missing or unreadable."
            )
        }

        val fileLength = apkFile.length()
        if (fileLength <= 0L || fileLength > MAX_APK_SIZE_BYTES) {
            return ApkVerificationResult.Invalid(
                ApkVerificationFailureReason.INVALID_FILE_SIZE,
                "Downloaded APK file size is invalid."
            )
        }

        if (release == null) {
            return ApkVerificationResult.Invalid(
                ApkVerificationFailureReason.MISSING_MANIFEST_METADATA,
                "Release manifest metadata is required before installing an update."
            )
        }

        if (release.isDebugBuild ||
            release.apkFileName.contains("debug", ignoreCase = true) ||
            apkFile.name.contains("debug", ignoreCase = true) ||
            release.signingMode != PRODUCTION_SIGNING_MODE
        ) {
            return ApkVerificationResult.Invalid(
                ApkVerificationFailureReason.DEBUG_APK_NOT_ALLOWED,
                "Debug APK cannot be installed as a production update."
            )
        }

        if (release.apkSizeBytes > 0L && fileLength != release.apkSizeBytes) {
            return ApkVerificationResult.Invalid(
                ApkVerificationFailureReason.INVALID_FILE_SIZE,
                "Downloaded APK size does not match release manifest."
            )
        }

        val manifestPkg = release.packageId?.trim().orEmpty()
        if (manifestPkg.isBlank()) {
            return ApkVerificationResult.Invalid(
                ApkVerificationFailureReason.MISSING_MANIFEST_METADATA,
                "Release manifest is missing package ID."
            )
        }
        if (manifestPkg != context.packageName) {
            return ApkVerificationResult.Invalid(
                ApkVerificationFailureReason.PACKAGE_ID_MISMATCH,
                "Release package ID does not match installed app."
            )
        }

        val manifestVersionCode = release.versionCode
        if (manifestVersionCode == null || manifestVersionCode <= 0) {
            return ApkVerificationResult.Invalid(
                ApkVerificationFailureReason.MISSING_MANIFEST_METADATA,
                "Release manifest is missing a valid versionCode."
            )
        }
        if (manifestVersionCode.toLong() <= installedVersionCode) {
            return ApkVerificationResult.Invalid(
                ApkVerificationFailureReason.NON_INCREASING_VERSION_CODE,
                "Update versionCode ($manifestVersionCode) must be greater than installed versionCode ($installedVersionCode)."
            )
        }

        val expectedApkSha = normalizeSha256Hex(release.expectedSha256)
            ?: return ApkVerificationResult.Invalid(
                ApkVerificationFailureReason.MALFORMED_APK_SHA256,
                "Release manifest has missing or malformed APK SHA-256 checksum."
            )

        val expectedCertSha = normalizeSha256Hex(release.expectedSigningCertSha256)
            ?: return ApkVerificationResult.Invalid(
                ApkVerificationFailureReason.MALFORMED_SIGNING_CERT_SHA256,
                "Release manifest has missing or malformed signing certificate fingerprint."
            )

        val actualApkSha = runCatching { computeFileSha256(apkFile) }.getOrElse {
            return ApkVerificationResult.Invalid(
                ApkVerificationFailureReason.FILE_MISSING_OR_UNREADABLE,
                "Unable to read downloaded APK for checksum verification."
            )
        }
        if (!MessageDigest.isEqual(
                actualApkSha.toByteArray(Charsets.US_ASCII),
                expectedApkSha.toByteArray(Charsets.US_ASCII)
            )
        ) {
            return ApkVerificationResult.Invalid(
                ApkVerificationFailureReason.APK_SHA256_MISMATCH,
                "APK SHA-256 checksum verification failed."
            )
        }

        val flags = packageManagerSigningFlags()
        val archiveInfo = runCatching {
            archiveInfoProvider?.invoke(apkFile) ?: context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)
        }.getOrNull() ?: return ApkVerificationResult.Invalid(
            ApkVerificationFailureReason.CORRUPT_ARCHIVE,
            "Unable to inspect downloaded APK archive metadata."
        )

        val archivePkg = archiveInfo.packageName?.trim().orEmpty()
        if (archivePkg.isBlank()) {
            return ApkVerificationResult.Invalid(
                ApkVerificationFailureReason.MISSING_PACKAGE_ID,
                "Downloaded APK archive is missing a package ID."
            )
        }
        if (archivePkg != context.packageName) {
            return ApkVerificationResult.Invalid(
                ApkVerificationFailureReason.PACKAGE_ID_MISMATCH,
                "APK package ID ($archivePkg) does not match installed app (${context.packageName})."
            )
        }

        val archiveVersionCode = getPackageVersionCode(archiveInfo)
        if (archiveVersionCode <= 0L || archiveVersionCode != manifestVersionCode.toLong()) {
            return ApkVerificationResult.Invalid(
                ApkVerificationFailureReason.VERSION_CODE_MISMATCH,
                "APK versionCode ($archiveVersionCode) does not match release manifest ($manifestVersionCode)."
            )
        }

        val apkDigests = extractActiveSigningDigests(archiveInfo)
        if (apkDigests.isEmpty()) {
            return ApkVerificationResult.Invalid(
                ApkVerificationFailureReason.MISSING_APK_SIGNER,
                "Unable to extract signing certificate from downloaded APK."
            )
        }
        if (expectedCertSha !in apkDigests) {
            return ApkVerificationResult.Invalid(
                ApkVerificationFailureReason.SIGNING_CERT_MISMATCH,
                "APK signing certificate does not match release manifest fingerprint."
            )
        }

        val installedInfo = runCatching {
            installedInfoProvider?.invoke(context) ?: context.packageManager.getPackageInfo(context.packageName, flags)
        }.getOrNull() ?: return ApkVerificationResult.Invalid(
            ApkVerificationFailureReason.INSTALLED_SIGNER_UNAVAILABLE,
            "Unable to inspect installed app signing certificate."
        )

        val installedDigests = extractActiveSigningDigests(installedInfo)
        if (installedDigests.isEmpty()) {
            return ApkVerificationResult.Invalid(
                ApkVerificationFailureReason.INSTALLED_SIGNER_UNAVAILABLE,
                "Installed app signing certificate is unavailable."
            )
        }

        val compatibleWithInstalled = hasMatchingSigningCertificate(installedDigests, apkDigests) ||
            isAndroidVerifiedRotationCompatible(context, installedInfo, archiveInfo)
        if (!compatibleWithInstalled) {
            return ApkVerificationResult.SigningConflict(
                installedSha256 = installedDigests.first(),
                apkSha256 = apkDigests.first()
            )
        }

        return ApkVerificationResult.Verified(
            packageName = archivePkg,
            versionCode = archiveVersionCode,
            apkSha256 = actualApkSha,
            signingCertSha256 = expectedCertSha
        )
    }

    fun launchApkInstaller(
        context: Context,
        apkFile: File,
        release: AppReleaseInfo?,
        installedVersionCode: Long = getInstalledVersionCode(context)
    ): ApkInstallLaunchResult {
        return launchApkInstallerInternal(
            context = context,
            apkFile = apkFile,
            release = release,
            installedVersionCode = installedVersionCode,
            archiveInfoProvider = null,
            installedInfoProvider = null,
            activityLauncher = null
        )
    }

    fun launchApkInstaller(
        context: Context,
        apkFile: File,
        expectedSigningCertSha256: String? = null
    ): ApkInstallLaunchResult {
        return ApkInstallLaunchResult.Failed(
            "Complete release manifest verification is required before launching the installer."
        )
    }

    @VisibleForTesting
    internal fun launchApkInstallerInternal(
        context: Context,
        apkFile: File,
        release: AppReleaseInfo?,
        installedVersionCode: Long = getInstalledVersionCode(context),
        archiveInfoProvider: ((File) -> PackageInfo?)? = null,
        installedInfoProvider: ((Context) -> PackageInfo?)? = null,
        canRequestInstallsProvider: ((Context) -> Boolean)? = null,
        activityLauncher: ((Intent) -> Unit)? = null
    ): ApkInstallLaunchResult {
        when (val verification = verifyProductionApkForInstallInternal(
            context = context,
            apkFile = apkFile,
            release = release,
            installedVersionCode = installedVersionCode,
            archiveInfoProvider = archiveInfoProvider,
            installedInfoProvider = installedInfoProvider
        )) {
            is ApkVerificationResult.Invalid -> {
                return ApkInstallLaunchResult.Failed(verification.message)
            }
            is ApkVerificationResult.SigningConflict -> {
                return ApkInstallLaunchResult.SigningConflict(
                    installedSha256 = verification.installedSha256,
                    apkSha256 = verification.apkSha256
                )
            }
            is ApkVerificationResult.Verified -> Unit
        }

        val canInstall = canRequestInstallsProvider?.invoke(context) ?: canRequestPackageInstalls(context)
        if (!canInstall) {
            return ApkInstallLaunchResult.UnknownSourcesPermissionRequired
        }

        return runCatching {
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (activityLauncher != null) {
                activityLauncher(installIntent)
            } else {
                context.startActivity(installIntent)
            }
            ApkInstallLaunchResult.Launched
        }.getOrElse { err ->
            ApkInstallLaunchResult.Failed(err.message ?: "Unable to launch package installer.")
        }
    }
}
