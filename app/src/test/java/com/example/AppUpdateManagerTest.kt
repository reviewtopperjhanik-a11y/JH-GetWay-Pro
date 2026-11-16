package com.example

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.Signature
import androidx.test.core.app.ApplicationProvider
import com.example.updater.ApkInstallLaunchResult
import com.example.updater.ApkVerificationFailureReason
import com.example.updater.ApkVerificationResult
import com.example.updater.AppReleaseInfo
import com.example.updater.AppUpdateCheckResult
import com.example.updater.AppUpdateManager
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppUpdateManagerTest {

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun createTempApkFile(context: Context, fileName: String = "JH-GetWay-v1.0.12-release.apk", bytes: ByteArray = "PRODUCTION-APK-PAYLOAD".toByteArray()): File {
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        return File(updatesDir, fileName).apply { writeBytes(bytes) }
    }

    private fun fakePackageInfo(
        packageName: String?,
        versionCode: Long,
        certBytes: ByteArray? = "VALID-PROD-CERT-BYTES".toByteArray()
    ): PackageInfo {
        return PackageInfo().apply {
            this.packageName = packageName ?: ""
            this.longVersionCode = versionCode
            @Suppress("DEPRECATION")
            this.versionCode = versionCode.toInt()
            @Suppress("DEPRECATION")
            this.signatures = if (certBytes != null) arrayOf(Signature(certBytes)) else emptyArray()
        }
    }

    private fun buildValidProductionRelease(
        context: Context,
        apkFile: File,
        certBytes: ByteArray = "VALID-PROD-CERT-BYTES".toByteArray(),
        versionCode: Int = 112
    ): AppReleaseInfo {
        return AppReleaseInfo(
            repoSlug = "jhsoft/jh-getway",
            tagName = "v1.0.12",
            versionName = "1.0.12",
            versionCode = versionCode,
            releaseTitle = "JH GetWay v1.0.12",
            releaseNotes = "Hardened production release",
            htmlUrl = "https://github.com/jhsoft/jh-getway/releases/tag/v1.0.12",
            publishedAt = "2026-01-01T00:00:00Z",
            apkDownloadUrl = "https://github.com/jhsoft/jh-getway/releases/download/v1.0.12/${apkFile.name}",
            apkFileName = apkFile.name,
            apkSizeBytes = apkFile.length(),
            expectedSha256 = sha256Hex(apkFile.readBytes()),
            signingMode = "production",
            packageId = context.packageName,
            expectedSigningCertSha256 = sha256Hex(certBytes),
            isDebugBuild = false
        )
    }

    private fun assertInstallBlocked(
        context: Context,
        apkFile: File,
        release: AppReleaseInfo?,
        installedVersionCode: Long = 100L,
        archiveInfoProvider: ((File) -> PackageInfo?)? = null,
        installedInfoProvider: ((Context) -> PackageInfo?)? = null,
        expectedReason: ApkVerificationFailureReason? = null,
        expectSigningConflict: Boolean = false
    ) {
        val verification = AppUpdateManager.verifyProductionApkForInstallInternal(
            context = context,
            apkFile = apkFile,
            release = release,
            installedVersionCode = installedVersionCode,
            archiveInfoProvider = archiveInfoProvider,
            installedInfoProvider = installedInfoProvider
        )
        if (expectSigningConflict) {
            assertTrue("Expected SigningConflict, got $verification", verification is ApkVerificationResult.SigningConflict)
        } else {
            assertTrue("Expected Invalid, got $verification", verification is ApkVerificationResult.Invalid)
            if (expectedReason != null) {
                assertEquals(expectedReason, (verification as ApkVerificationResult.Invalid).reason)
            }
        }

        var launchedCount = 0
        val launchResult = AppUpdateManager.launchApkInstallerInternal(
            context = context,
            apkFile = apkFile,
            release = release,
            installedVersionCode = installedVersionCode,
            archiveInfoProvider = archiveInfoProvider,
            installedInfoProvider = installedInfoProvider,
            canRequestInstallsProvider = { true },
            activityLauncher = { launchedCount++ }
        )
        assertEquals("Installer MUST NOT launch on verification failure", 0, launchedCount)
        if (expectSigningConflict) {
            assertTrue("Expected ApkInstallLaunchResult.SigningConflict, got $launchResult", launchResult is ApkInstallLaunchResult.SigningConflict)
        } else {
            assertTrue("Expected ApkInstallLaunchResult.Failed, got $launchResult", launchResult is ApkInstallLaunchResult.Failed)
        }
    }

    @Test
    fun normalizeGithubRepo_handlesSlugAndFullUrls() {
        assertEquals("jhsoft/jh-getway", AppUpdateManager.normalizeGithubRepo("jhsoft/jh-getway"))
        assertEquals("jhsoft/jh-getway", AppUpdateManager.normalizeGithubRepo("https://github.com/jhsoft/jh-getway"))
        assertEquals("jhsoft/jh-getway", AppUpdateManager.normalizeGithubRepo("https://github.com/jhsoft/jh-getway.git"))
        assertEquals("jhsoft/jh-getway", AppUpdateManager.normalizeGithubRepo("github.com/jhsoft/jh-getway/releases"))
        assertNull(AppUpdateManager.normalizeGithubRepo(""))
        assertNull(AppUpdateManager.normalizeGithubRepo("invalid-slug"))
        assertNull(AppUpdateManager.normalizeGithubRepo("owner/bad repo"))
    }

    @Test
    fun isRemoteNewer_comparesVersionCodeAndSemVerCorrectly() {
        assertTrue(
            AppUpdateManager.isRemoteNewer(
                currentVersionCode = 101,
                currentVersionName = "1.0.1",
                remoteVersionCode = 102,
                remoteVersionNameOrTag = "v1.0.2"
            )
        )
        assertFalse(
            AppUpdateManager.isRemoteNewer(
                currentVersionCode = 102,
                currentVersionName = "1.0.2",
                remoteVersionCode = 102,
                remoteVersionNameOrTag = "v1.0.2"
            )
        )
        assertTrue(
            AppUpdateManager.isRemoteNewer(
                currentVersionCode = 1,
                currentVersionName = "1.0.0",
                remoteVersionCode = null,
                remoteVersionNameOrTag = "v1.0.10"
            )
        )
        assertTrue(AppUpdateManager.compareVersionNames("1.0.10", "1.0.2") > 0)
        assertEquals(0, AppUpdateManager.compareVersionNames("v1.2.3", "1.2.3"))
    }

    @Test
    fun hasMatchingSigningCertificate_checksSha256Intersection() {
        assertTrue(AppUpdateManager.hasMatchingSigningCertificate(setOf("aabb"), setOf("aabb", "ccdd")))
        assertFalse(AppUpdateManager.hasMatchingSigningCertificate(setOf("aabb"), setOf("eeff")))
        assertFalse(AppUpdateManager.hasMatchingSigningCertificate(emptySet(), setOf("aabb")))
    }

    @Test
    fun checkForUpdate_selectsProductionReleaseAndSeparatesDevelopmentDebugPath() = runBlocking {
        val relSha = "a".repeat(64)
        val certSha = "F7:C8:A5:C9:C6:96:B9:7B:2B:47:DC:B4:27:B4:0E:DE:CE:FF:39:27:EF:45:42:82:E0:A1:77:1D:C3:C4:BD:F5"
        val dbgSha = "b".repeat(64)

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port
        server.createContext("/repos/jhsoft/jh-getway/releases/latest") { exchange ->
            val response = """
                {
                  "tag_name": "v1.0.5",
                  "name": "JH GetWay v1.0.5",
                  "body": "Hardened production release",
                  "html_url": "https://github.com/jhsoft/jh-getway/releases/tag/v1.0.5",
                  "assets": [
                    {
                      "name": "JH-GetWay-v1.0.5-release.apk",
                      "browser_download_url": "http://127.0.0.1:$port/dl/release.apk",
                      "size": 1000
                    },
                    {
                      "name": "JH-GetWay-v1.0.5-debug.apk",
                      "browser_download_url": "http://127.0.0.1:$port/dl/debug.apk",
                      "size": 1200
                    },
                    {
                      "name": "update-manifest.json",
                      "browser_download_url": "http://127.0.0.1:$port/dl/update-manifest.json",
                      "size": 350
                    }
                  ]
                }
            """.trimIndent().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.createContext("/dl/update-manifest.json") { exchange ->
            val response = """
                {
                  "versionCode": 105,
                  "versionName": "1.0.5",
                  "tag": "v1.0.5",
                  "packageId": "com.aistudio.jhgetway.iffndf",
                  "apkName": "JH-GetWay-v1.0.5-release.apk",
                  "sha256": "$relSha",
                  "debugApkName": "JH-GetWay-v1.0.5-debug.apk",
                  "debugSha256": "$dbgSha",
                  "signingMode": "production",
                  "signingCertSha256": "$certSha"
                }
            """.trimIndent().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }

        server.start()
        try {
            val base = "http://127.0.0.1:$port"

            // Production check rejects preferDebugApk=true unless using explicit dev API
            val blockedDebugPref = AppUpdateManager.checkForUpdate(
                repoInput = "jhsoft/jh-getway",
                currentVersionCode = 104,
                currentVersionName = "1.0.4",
                preferDebugApk = true,
                apiBaseUrl = base,
                expectedPackageId = "com.aistudio.jhgetway.iffndf"
            )
            assertTrue(blockedDebugPref is AppUpdateCheckResult.Error)

            // Valid production release
            val rawRelRes = AppUpdateManager.checkForUpdate(
                repoInput = "jhsoft/jh-getway",
                currentVersionCode = 104,
                currentVersionName = "1.0.4",
                preferDebugApk = false,
                apiBaseUrl = base,
                expectedPackageId = "com.aistudio.jhgetway.iffndf"
            )
            assertTrue("Expected UpdateAvailable for release APK, got $rawRelRes", rawRelRes is AppUpdateCheckResult.UpdateAvailable)
            val relRes = rawRelRes as AppUpdateCheckResult.UpdateAvailable
            assertEquals("JH-GetWay-v1.0.5-release.apk", relRes.release.apkFileName)
            assertEquals(relSha, relRes.release.expectedSha256)
            assertEquals("production", relRes.release.signingMode)
            assertEquals("f7c8a5c9c696b97b2b47dcb427b40edeceff3927ef454282e0a1771dc3c4bdf5", relRes.release.expectedSigningCertSha256)
            assertFalse(relRes.release.isDebugBuild)

            // Explicit separate development debug path still works when explicitly invoked
            val rawDbgRes = AppUpdateManager.checkForDevelopmentDebugUpdate(
                repoInput = "jhsoft/jh-getway",
                currentVersionCode = 104,
                currentVersionName = "1.0.4",
                apiBaseUrl = base,
                expectedPackageId = "com.aistudio.jhgetway.iffndf"
            )
            assertTrue("Expected UpdateAvailable for dev debug APK, got $rawDbgRes", rawDbgRes is AppUpdateCheckResult.UpdateAvailable)
            val dbgRes = rawDbgRes as AppUpdateCheckResult.UpdateAvailable
            assertEquals("JH-GetWay-v1.0.5-debug.apk", dbgRes.release.apkFileName)
            assertEquals(dbgSha, dbgRes.release.expectedSha256)
            assertEquals("debug-persistent", dbgRes.release.signingMode)
            assertTrue(dbgRes.release.isDebugBuild)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun checkForUpdate_failsClosedOnMissingOrMalformedManifestAndInvalidMetadata() = runBlocking {
        val validSha = "a".repeat(64)
        val validCert = "c".repeat(64)

        var manifestMode = "missing_asset"
        var releasePayloadOverride: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port

        server.createContext("/repos/jhsoft/jh-getway/releases/latest") { exchange ->
            val assetsArr = JSONArray().put(
                JSONObject()
                    .put("name", "JH-GetWay-v1.0.12-release.apk")
                    .put("browser_download_url", "http://127.0.0.1:$port/dl/release.apk")
                    .put("size", 1024)
            )
            if (manifestMode != "missing_asset") {
                assetsArr.put(
                    JSONObject()
                        .put("name", "update-manifest.json")
                        .put("browser_download_url", "http://127.0.0.1:$port/dl/update-manifest.json")
                        .put("size", 250)
                )
            }
            val response = (releasePayloadOverride ?: JSONObject()
                .put("tag_name", "v1.0.12")
                .put("name", "JH GetWay v1.0.12")
                .put("html_url", "https://github.com/jhsoft/jh-getway/releases/tag/v1.0.12")
                .put("assets", assetsArr)
                .toString()).toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }

        server.createContext("/dl/update-manifest.json") { exchange ->
            val body = when (manifestMode) {
                "malformed_json" -> "{not-valid-json"
                "wrong_pkg" -> """{"versionCode":112,"versionName":"1.0.12","packageId":"com.wrong.package","apkName":"JH-GetWay-v1.0.12-release.apk","sha256":"$validSha","signingMode":"production","signingCertSha256":"$validCert"}"""
                "missing_pkg" -> """{"versionCode":112,"versionName":"1.0.12","apkName":"JH-GetWay-v1.0.12-release.apk","sha256":"$validSha","signingMode":"production","signingCertSha256":"$validCert"}"""
                "bad_sha" -> """{"versionCode":112,"versionName":"1.0.12","packageId":"com.aistudio.jhgetway.iffndf","apkName":"JH-GetWay-v1.0.12-release.apk","sha256":"abc123","signingMode":"production","signingCertSha256":"$validCert"}"""
                "bad_cert" -> """{"versionCode":112,"versionName":"1.0.12","packageId":"com.aistudio.jhgetway.iffndf","apkName":"JH-GetWay-v1.0.12-release.apk","sha256":"$validSha","signingMode":"production","signingCertSha256":"short"}"""
                "ephemeral_mode" -> """{"versionCode":112,"versionName":"1.0.12","packageId":"com.aistudio.jhgetway.iffndf","apkName":"JH-GetWay-v1.0.12-release.apk","sha256":"$validSha","signingMode":"ci-ephemeral","signingCertSha256":"$validCert"}"""
                else -> "{}"
            }.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        server.start()
        try {
            val base = "http://127.0.0.1:$port"
            for (mode in listOf("missing_asset", "malformed_json", "wrong_pkg", "missing_pkg", "bad_sha", "bad_cert", "ephemeral_mode")) {
                manifestMode = mode
                val res = AppUpdateManager.checkForUpdate(
                    repoInput = "jhsoft/jh-getway",
                    currentVersionCode = 111,
                    currentVersionName = "1.0.11",
                    preferDebugApk = false,
                    apiBaseUrl = base,
                    expectedPackageId = "com.aistudio.jhgetway.iffndf"
                )
                assertTrue("Mode $mode must fail closed with Error, got $res", res is AppUpdateCheckResult.Error)
            }

            // Debug-only asset cannot be selected as production release
            val debugOnlyAssets = JSONArray()
                .put(JSONObject().put("name", "app-debug.apk").put("browser_download_url", "https://example.com/app-debug.apk").put("size", 100))
            assertNull(AppUpdateManager.selectPreferredApkAsset(debugOnlyAssets, preferDebugApk = false))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun downloadApkWithProgress_verifiesSha256AndRejectsCorruptedOrDebugArtifact() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val payload = "FAKE-APK-BYTES-FOR-HASH-CHECK".toByteArray()
        val expectedSha = sha256Hex(payload)

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port
        server.createContext("/dl/app.apk") { exchange ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
        try {
            val goodRelease = AppReleaseInfo(
                repoSlug = "jhsoft/jh-getway",
                tagName = "v1.0.12",
                versionName = "1.0.12",
                versionCode = 112,
                releaseTitle = "v1.0.12",
                releaseNotes = "",
                htmlUrl = "https://github.com/jhsoft/jh-getway/releases/tag/v1.0.12",
                publishedAt = "",
                apkDownloadUrl = "http://127.0.0.1:$port/dl/app.apk",
                apkFileName = "JH-GetWay-v1.0.12-release.apk",
                apkSizeBytes = payload.size.toLong(),
                expectedSha256 = expectedSha,
                signingMode = "production",
                packageId = context.packageName,
                expectedSigningCertSha256 = "d".repeat(64)
            )
            val okResult = AppUpdateManager.downloadApkWithProgress(
                context = context,
                release = goodRelease,
                onProgress = {},
                allowLocalHttpForTesting = true
            )
            assertTrue("Expected download with matching SHA-256 to succeed", okResult.isSuccess)
            assertTrue(okResult.getOrThrow().exists())

            val badShaResult = AppUpdateManager.downloadApkWithProgress(
                context = context,
                release = goodRelease.copy(expectedSha256 = "0".repeat(64)),
                onProgress = {},
                allowLocalHttpForTesting = true
            )
            assertTrue("Expected download with mismatched SHA-256 to fail", badShaResult.isFailure)

            val debugAsProdResult = AppUpdateManager.downloadApkWithProgress(
                context = context,
                release = goodRelease.copy(apkFileName = "JH-GetWay-v1.0.12-debug.apk"),
                onProgress = {},
                allowLocalHttpForTesting = true
            )
            assertTrue("Expected debug filename in production flow to fail", debugAsProdResult.isFailure)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun verifyAndLaunchInstaller_blocksAllFailClosedConditionsAndAllowsValidProductionApk() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val certBytes = "PERMANENT-UPLOAD-KEY-CERT".toByteArray()
        val otherCertBytes = "WRONG-ATTACKER-CERT".toByteArray()
        val apkBytes = "VALID-PRODUCTION-APK-BINARY".toByteArray()
        val apkFile = createTempApkFile(context, "JH-GetWay-v1.0.12-release.apk", apkBytes)
        val validRelease = buildValidProductionRelease(context, apkFile, certBytes, versionCode = 112)
        val validArchiveInfo = fakePackageInfo(context.packageName, 112L, certBytes)
        val validInstalledInfo = fakePackageInfo(context.packageName, 100L, certBytes)

        // 1. Missing APK file
        val missingFile = File(context.cacheDir, "updates/does-not-exist.apk").apply { delete() }
        assertInstallBlocked(
            context = context,
            apkFile = missingFile,
            release = validRelease,
            installedVersionCode = 100L,
            archiveInfoProvider = { validArchiveInfo },
            installedInfoProvider = { validInstalledInfo },
            expectedReason = ApkVerificationFailureReason.FILE_MISSING_OR_UNREADABLE
        )

        // 2. Unreadable / corrupt APK archive (getPackageArchiveInfo returns null)
        assertInstallBlocked(
            context = context,
            apkFile = apkFile,
            release = validRelease,
            installedVersionCode = 100L,
            archiveInfoProvider = { null },
            installedInfoProvider = { validInstalledInfo },
            expectedReason = ApkVerificationFailureReason.CORRUPT_ARCHIVE
        )

        // 3. Missing or blank package ID (archive & manifest)
        assertInstallBlocked(
            context = context,
            apkFile = apkFile,
            release = validRelease,
            installedVersionCode = 100L,
            archiveInfoProvider = { fakePackageInfo("   ", 112L, certBytes) },
            installedInfoProvider = { validInstalledInfo },
            expectedReason = ApkVerificationFailureReason.MISSING_PACKAGE_ID
        )
        assertInstallBlocked(
            context = context,
            apkFile = apkFile,
            release = validRelease.copy(packageId = ""),
            installedVersionCode = 100L,
            archiveInfoProvider = { validArchiveInfo },
            installedInfoProvider = { validInstalledInfo },
            expectedReason = ApkVerificationFailureReason.MISSING_MANIFEST_METADATA
        )

        // 4. Wrong package ID
        assertInstallBlocked(
            context = context,
            apkFile = apkFile,
            release = validRelease,
            installedVersionCode = 100L,
            archiveInfoProvider = { fakePackageInfo("com.evil.other.app", 112L, certBytes) },
            installedInfoProvider = { validInstalledInfo },
            expectedReason = ApkVerificationFailureReason.PACKAGE_ID_MISMATCH
        )
        assertInstallBlocked(
            context = context,
            apkFile = apkFile,
            release = validRelease.copy(packageId = "com.evil.other.app"),
            installedVersionCode = 100L,
            archiveInfoProvider = { validArchiveInfo },
            installedInfoProvider = { validInstalledInfo },
            expectedReason = ApkVerificationFailureReason.PACKAGE_ID_MISMATCH
        )

        // 5. Missing manifest
        assertInstallBlocked(
            context = context,
            apkFile = apkFile,
            release = null,
            installedVersionCode = 100L,
            archiveInfoProvider = { validArchiveInfo },
            installedInfoProvider = { validInstalledInfo },
            expectedReason = ApkVerificationFailureReason.MISSING_MANIFEST_METADATA
        )
        // Also ensure legacy 3-arg overload never launches installer without manifest verification
        val legacyLaunch = AppUpdateManager.launchApkInstaller(context, apkFile, validRelease.expectedSigningCertSha256)
        assertTrue(legacyLaunch is ApkInstallLaunchResult.Failed)

        // 6 & 7. Missing / malformed APK SHA-256
        assertInstallBlocked(
            context = context,
            apkFile = apkFile,
            release = validRelease.copy(expectedSha256 = "not-64-hex"),
            installedVersionCode = 100L,
            archiveInfoProvider = { validArchiveInfo },
            installedInfoProvider = { validInstalledInfo },
            expectedReason = ApkVerificationFailureReason.MALFORMED_APK_SHA256
        )

        // 8. APK SHA-256 mismatch
        assertInstallBlocked(
            context = context,
            apkFile = apkFile,
            release = validRelease.copy(expectedSha256 = "e".repeat(64)),
            installedVersionCode = 100L,
            archiveInfoProvider = { validArchiveInfo },
            installedInfoProvider = { validInstalledInfo },
            expectedReason = ApkVerificationFailureReason.APK_SHA256_MISMATCH
        )

        // 9. Missing / malformed certificate fingerprint
        assertInstallBlocked(
            context = context,
            apkFile = apkFile,
            release = validRelease.copy(expectedSigningCertSha256 = ""),
            installedVersionCode = 100L,
            archiveInfoProvider = { validArchiveInfo },
            installedInfoProvider = { validInstalledInfo },
            expectedReason = ApkVerificationFailureReason.MALFORMED_SIGNING_CERT_SHA256
        )

        // 10. Certificate fingerprint mismatch (archive signer != manifest cert pin)
        assertInstallBlocked(
            context = context,
            apkFile = apkFile,
            release = validRelease.copy(expectedSigningCertSha256 = "f".repeat(64)),
            installedVersionCode = 100L,
            archiveInfoProvider = { validArchiveInfo },
            installedInfoProvider = { validInstalledInfo },
            expectedReason = ApkVerificationFailureReason.SIGNING_CERT_MISMATCH
        )

        // 11. Empty / unavailable signer extraction from APK
        assertInstallBlocked(
            context = context,
            apkFile = apkFile,
            release = validRelease,
            installedVersionCode = 100L,
            archiveInfoProvider = { fakePackageInfo(context.packageName, 112L, certBytes = null) },
            installedInfoProvider = { validInstalledInfo },
            expectedReason = ApkVerificationFailureReason.MISSING_APK_SIGNER
        )

        // 12. Installed-vs-downloaded signer mismatch
        assertInstallBlocked(
            context = context,
            apkFile = apkFile,
            release = validRelease,
            installedVersionCode = 100L,
            archiveInfoProvider = { validArchiveInfo },
            installedInfoProvider = { fakePackageInfo(context.packageName, 100L, otherCertBytes) },
            expectSigningConflict = true
        )

        // 13. Installed-vs-downloaded signer unavailable
        assertInstallBlocked(
            context = context,
            apkFile = apkFile,
            release = validRelease,
            installedVersionCode = 100L,
            archiveInfoProvider = { validArchiveInfo },
            installedInfoProvider = { fakePackageInfo(context.packageName, 100L, certBytes = null) },
            expectedReason = ApkVerificationFailureReason.INSTALLED_SIGNER_UNAVAILABLE
        )
        assertInstallBlocked(
            context = context,
            apkFile = apkFile,
            release = validRelease,
            installedVersionCode = 100L,
            archiveInfoProvider = { validArchiveInfo },
            installedInfoProvider = { null },
            expectedReason = ApkVerificationFailureReason.INSTALLED_SIGNER_UNAVAILABLE
        )

        // 14. Manifest / APK versionCode mismatch
        assertInstallBlocked(
            context = context,
            apkFile = apkFile,
            release = validRelease,
            installedVersionCode = 100L,
            archiveInfoProvider = { fakePackageInfo(context.packageName, 111L, certBytes) },
            installedInfoProvider = { validInstalledInfo },
            expectedReason = ApkVerificationFailureReason.VERSION_CODE_MISMATCH
        )

        // 15. Non-increasing versionCode
        assertInstallBlocked(
            context = context,
            apkFile = apkFile,
            release = validRelease,
            installedVersionCode = 112L,
            archiveInfoProvider = { validArchiveInfo },
            installedInfoProvider = { validInstalledInfo },
            expectedReason = ApkVerificationFailureReason.NON_INCREASING_VERSION_CODE
        )

        // 16. Debug APK presented as a production update
        val debugNamedApk = createTempApkFile(context, "JH-GetWay-v1.0.12-debug.apk", apkBytes)
        assertInstallBlocked(
            context = context,
            apkFile = debugNamedApk,
            release = validRelease,
            installedVersionCode = 100L,
            archiveInfoProvider = { validArchiveInfo },
            installedInfoProvider = { validInstalledInfo },
            expectedReason = ApkVerificationFailureReason.DEBUG_APK_NOT_ALLOWED
        )
        assertInstallBlocked(
            context = context,
            apkFile = apkFile,
            release = validRelease.copy(isDebugBuild = true),
            installedVersionCode = 100L,
            archiveInfoProvider = { validArchiveInfo },
            installedInfoProvider = { validInstalledInfo },
            expectedReason = ApkVerificationFailureReason.DEBUG_APK_NOT_ALLOWED
        )
        assertInstallBlocked(
            context = context,
            apkFile = apkFile,
            release = validRelease.copy(signingMode = "ci-ephemeral"),
            installedVersionCode = 100L,
            archiveInfoProvider = { validArchiveInfo },
            installedInfoProvider = { validInstalledInfo },
            expectedReason = ApkVerificationFailureReason.DEBUG_APK_NOT_ALLOWED
        )

        // Unknown-sources permission denied does not launch installer either
        var deniedLaunchCount = 0
        val permResult = AppUpdateManager.launchApkInstallerInternal(
            context = context,
            apkFile = apkFile,
            release = validRelease,
            installedVersionCode = 100L,
            archiveInfoProvider = { validArchiveInfo },
            installedInfoProvider = { validInstalledInfo },
            canRequestInstallsProvider = { false },
            activityLauncher = { deniedLaunchCount++ }
        )
        assertEquals(0, deniedLaunchCount)
        assertEquals(ApkInstallLaunchResult.UnknownSourcesPermissionRequired, permResult)

        // 17. Valid production path: verification succeeds & ACTION_VIEW installer launches with FileProvider URI
        val verified = AppUpdateManager.verifyProductionApkForInstallInternal(
            context = context,
            apkFile = apkFile,
            release = validRelease,
            installedVersionCode = 100L,
            archiveInfoProvider = { validArchiveInfo },
            installedInfoProvider = { validInstalledInfo }
        )
        assertTrue("Expected Verified, got $verified", verified is ApkVerificationResult.Verified)
        val launchedIntents = mutableListOf<Intent>()
        val launchRes = AppUpdateManager.launchApkInstallerInternal(
            context = context,
            apkFile = apkFile,
            release = validRelease,
            installedVersionCode = 100L,
            archiveInfoProvider = { validArchiveInfo },
            installedInfoProvider = { validInstalledInfo },
            canRequestInstallsProvider = { true },
            activityLauncher = { launchedIntents += it }
        )
        assertEquals(ApkInstallLaunchResult.Launched, launchRes)
        assertEquals(1, launchedIntents.size)
        val intent = launchedIntents.single()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertTrue((intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        assertEquals("${context.packageName}.fileprovider", intent.data?.authority)
    }
}
