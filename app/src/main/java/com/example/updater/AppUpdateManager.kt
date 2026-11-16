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
import java.security.MessageDigest
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
    data class UpdateAvailable(val release: AppReleaseInfo) : AppUpdateCheckResult
    data class UpToDate(val currentVersionName: String, val latestTag: String) : AppUpdateCheckResult
    data class NoReleasesPublished(val repoSlug: String) : AppUpdateCheckResult
    data class Error(val message: String) : AppUpdateCheckResult
}

sealed interface ApkInstallLaunchResult {
    data object Launched : ApkInstallLaunchResult
    data object UnknownSourcesPermissionRequired : ApkInstallLaunchResult
    data class SigningConflict(val installedSha256: String, val apkSha256: String) : ApkInstallLaunchResult
    data class Failed(val message: String) : ApkInstallLaunchResult
}

object AppUpdateManager {

    const val MAX_APK_SIZE_BYTES = 250L * 1024L * 1024L
    private const val PRODUCTION_SIGNING_MODE = "production"
    private val repoSlugRegex = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
    private val sha256HexRegex = Regex("^[0-9a-f]{64}$")

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

    suspend fun checkForUpdate(
        repoInput: String,
        currentVersionCode: Int,
        currentVersionName: String,
        preferDebugApk: Boolean = false,
        apiBaseUrl: String = "https://api.github.com",
        expectedPackageId: String = BuildConfig.APPLICATION_ID,
        allowDevelopmentDebugUpdates: Boolean = false
    ): AppUpdateCheckResult = withContext(Dispatchers.IO) {
        if (preferDebugApk && !allowDevelopmentDebugUpdates) {
            return@withContext AppUpdateCheckResult.Error("Debug updates are disabled for production update verification.")
        }

        val repoSlug = normalizeGithubRepo(repoInput)
            ?: return@withContext AppUpdateCheckResult.Error("Invalid GitHub repository format. Use owner/repo.")

        val cleanBase = apiBaseUrl.trimEnd('/')
        val allowLocalTestHttp = cleanBase.startsWith("http://127.0.0.1") || cleanBase.startsWith("http://localhost")
        val url = "$cleanBase/repos/$repoSlug/releases/latest"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "JH-GetWay-Android/$currentVersionName")
            .get()
            .build()

        runCatching {
            apiClient.newCall(request).execute().use { resp ->
                if (resp.code == 404) {
                    return@withContext AppUpdateCheckResult.NoReleasesPublished(repoSlug)
                }
                if (resp.code == 403 || resp.code == 429) {
                    return@withContext AppUpdateCheckResult.Error("GitHub API rate limit reached. Try again later.")
                }
                if (!resp.isSuccessful) {
                    return@withContext AppUpdateCheckResult.Error("GitHub check failed (HTTP ${resp.code}).")
                }
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) {
                    return@withContext AppUpdateCheckResult.Error("Empty response from GitHub Releases API.")
                }

                val json = JSONObject(body)
                val tagName = json.optString("tag_name", "").trim()
                val releaseTitle = json.optString("name", tagName).ifBlank { tagName }
                val releaseNotes = json.optString("body", "").trim()
                val htmlUrl = json.optString("html_url", "https://github.com/$repoSlug/releases/latest")
                val publishedAt = json.optString("published_at", "")

                val assets = json.optJSONArray("assets") ?: JSONArray()
                val isDebugFlow = preferDebugApk && allowDevelopmentDebugUpdates
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

                if (selectedApk == null) {
                    return@withContext AppUpdateCheckResult.Error("Latest release ($tagName) has no valid APK asset attached.")
                }
                if (!isDebugFlow && selectedApk.isDebugAsset) {
                    return@withContext AppUpdateCheckResult.Error("Debug APK cannot be installed as a production update.")
                }
                if (selectedApk.sizeBytes <= 0L || selectedApk.sizeBytes > MAX_APK_SIZE_BYTES) {
                    return@withContext AppUpdateCheckResult.Error("Release APK size is missing or exceeds the allowed maximum.")
                }
                if (manifestUrl.isBlank()) {
                    return@withContext AppUpdateCheckResult.Error("Release manifest (update-manifest.json) is missing.")
                }

                val manifestObj = fetchUpdateManifest(manifestUrl, currentVersionName)
                    ?: return@withContext AppUpdateCheckResult.Error("Release manifest is missing or malformed.")

                val manifestVersionCode = manifestObj.optInt("versionCode", -1).takeIf { it > 0 }
                    ?: return@withContext AppUpdateCheckResult.Error("Release manifest has missing or invalid versionCode.")
                val manifestVersionName = manifestObj.optString("versionName", "").trim()
                val manifestPackageId = manifestObj.optString("packageId", manifestObj.optString("applicationId", "")).trim()
                val manifestApkName = manifestObj.optString("apkName", "").trim()
                val releaseSha256 = manifestObj.optString("sha256", "").trim()
                val debugSha256 = manifestObj.optString("debugSha256", "").trim()
                val signingMode = manifestObj.optString("signingMode", "").trim()
                val signingCertSha256 = manifestObj.optString("signingCertSha256", "").trim()

                if (manifestPackageId.isBlank()) {
                    return@withContext AppUpdateCheckResult.Error("Release manifest is missing packageId.")
                }
                if (expectedPackageId.isNotBlank() && manifestPackageId != expectedPackageId) {
                    return@withContext AppUpdateCheckResult.Error(
                        "Release package ID mismatch ($manifestPackageId != $expectedPackageId)."
                    )
                }
                if (!isDebugFlow && manifestApkName.isNotBlank() && manifestApkName != selectedApk.fileName) {
                    return@withContext AppUpdateCheckResult.Error(
                        "Release manifest APK name mismatch ($manifestApkName != ${selectedApk.fileName})."
                    )
                }

                if (!isDebugFlow) {
                    if (signingMode != PRODUCTION_SIGNING_MODE) {
                        return@withContext AppUpdateCheckResult.Error(
                            "Release signing mode '$signingMode' is not valid for production updates."
                        )
                    }
                    val validApkSha = normalizeSha256Hex(releaseSha256)
                        ?: return@withContext AppUpdateCheckResult.Error("Release manifest has missing or malformed APK SHA-256 checksum.")
                    val validCertSha = normalizeSha256Hex(signingCertSha256)
                        ?: return@withContext AppUpdateCheckResult.Error("Release manifest has missing or malformed signing certificate fingerprint.")

                    if (manifestVersionCode <= currentVersionCode) {
                        return@withContext AppUpdateCheckResult.UpToDate(
                            currentVersionName = currentVersionName,
                            latestTag = tagName.ifBlank { manifestVersionName }
                        )
                    }

                    val effectiveVersionName = manifestVersionName.ifBlank { tagName.removePrefix("v").removePrefix("V") }
                    return@withContext AppUpdateCheckResult.UpdateAvailable(
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
                    )
                } else {
                    val validDebugSha = normalizeSha256Hex(debugSha256)
                        ?: return@withContext AppUpdateCheckResult.Error("Development debug manifest has missing or malformed debugSha256.")
                    if (manifestVersionCode <= currentVersionCode) {
                        return@withContext AppUpdateCheckResult.UpToDate(
                            currentVersionName = currentVersionName,
                            latestTag = tagName.ifBlank { manifestVersionName }
                        )
                    }
                    val effectiveVersionName = manifestVersionName.ifBlank { tagName.removePrefix("v").removePrefix("V") }
                    return@withContext AppUpdateCheckResult.UpdateAvailable(
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
                    )
                }
            }
        }.getOrElse { err ->
            AppUpdateCheckResult.Error(err.message ?: "Unable to reach GitHub Releases.")
        }
    }

    suspend fun checkForDevelopmentDebugUpdate(
        repoInput: String,
        currentVersionCode: Int,
        currentVersionName: String,
        apiBaseUrl: String = "https://api.github.com",
        expectedPackageId: String = BuildConfig.APPLICATION_ID
    ): AppUpdateCheckResult {
        return checkForUpdate(
            repoInput = repoInput,
            currentVersionCode = currentVersionCode,
            currentVersionName = currentVersionName,
            preferDebugApk = true,
            apiBaseUrl = apiBaseUrl,
            expectedPackageId = expectedPackageId,
            allowDevelopmentDebugUpdates = true
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
            updatesDir.listFiles()?.forEach { old ->
                if (old.isFile && (old.name.endsWith(".apk") || old.name.endsWith(".part"))) old.delete()
            }

            val safeName = release.apkFileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                .ifBlank { "jh-getway-update.apk" }
            val tempFile = File(updatesDir, "$safeName.part")
            val finalFile = File(updatesDir, safeName)

            val req = Request.Builder()
                .url(release.apkDownloadUrl)
                .header("Accept", "application/octet-stream")
                .header("User-Agent", "JH-GetWay-Android")
                .get()
                .build()

            downloadClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Download failed (HTTP ${resp.code})")
                }
                val body = resp.body ?: throw IllegalStateException("Empty APK response body")
                val headerLength = body.contentLength()
                if (headerLength > MAX_APK_SIZE_BYTES) {
                    throw SecurityException("APK download exceeds maximum allowed size.")
                }
                val totalBytes = headerLength.takeIf { it > 0 } ?: release.apkSizeBytes
                val digest = MessageDigest.getInstance("SHA-256")
                var downloaded = 0L
                var lastReportedPct = -1

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            downloaded += read
                            if (downloaded > MAX_APK_SIZE_BYTES) {
                                tempFile.delete()
                                throw SecurityException("APK download exceeded maximum allowed size.")
                            }
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            if (totalBytes > 0) {
                                val pct = ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                                if (pct != lastReportedPct) {
                                    lastReportedPct = pct
                                    onProgress(pct)
                                }
                            }
                        }
                        output.flush()
                    }
                }

                if (downloaded <= 0L || (release.apkSizeBytes > 0L && downloaded != release.apkSizeBytes)) {
                    tempFile.delete()
                    throw SecurityException("Downloaded APK size mismatch ($downloaded != ${release.apkSizeBytes}).")
                }

                val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                if (!MessageDigest.isEqual(
                        actualSha256.toByteArray(Charsets.US_ASCII),
                        expectedSha.toByteArray(Charsets.US_ASCII)
                    )
                ) {
                    tempFile.delete()
                    throw SecurityException("APK SHA-256 verification failed. File may be corrupted.")
                }

                if (finalFile.exists()) finalFile.delete()
                if (!tempFile.renameTo(finalFile)) {
                    tempFile.copyTo(finalFile, overwrite = true)
                    tempFile.delete()
                }
                onProgress(100)
                finalFile
            }
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
