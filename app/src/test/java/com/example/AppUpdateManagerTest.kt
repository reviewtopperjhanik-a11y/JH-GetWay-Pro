package com.example

import com.example.updater.AppUpdateCheckResult
import com.example.updater.AppUpdateManager
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppUpdateManagerTest {

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
    fun checkForUpdate_selectsDebugOrReleaseApkAndExpectedSha256() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port
        server.createContext("/repos/jhsoft/jh-getway/releases/latest") { exchange ->
            val response = """
                {
                  "tag_name": "v1.0.5",
                  "name": "JH GetWay v1.0.5",
                  "body": "Fixes package update conflict",
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
                      "size": 250
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
                  "apkName": "JH-GetWay-v1.0.5-release.apk",
                  "sha256": "relsha256",
                  "debugApkName": "JH-GetWay-v1.0.5-debug.apk",
                  "debugSha256": "dbgsha256",
                  "signingMode": "ci-ephemeral"
                }
            """.trimIndent().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }

        server.start()
        try {
            val base = "http://127.0.0.1:$port"
            val rawDbgRes = AppUpdateManager.checkForUpdate(
                repoInput = "jhsoft/jh-getway",
                currentVersionCode = 104,
                currentVersionName = "1.0.4",
                preferDebugApk = true,
                apiBaseUrl = base
            )
            assertTrue("Expected UpdateAvailable for debug APK, got $rawDbgRes", rawDbgRes is AppUpdateCheckResult.UpdateAvailable)
            val dbgRes = rawDbgRes as AppUpdateCheckResult.UpdateAvailable
            assertEquals("JH-GetWay-v1.0.5-debug.apk", dbgRes.release.apkFileName)
            assertEquals("dbgsha256", dbgRes.release.expectedSha256)
            assertEquals("debug-persistent", dbgRes.release.signingMode)

            val rawRelRes = AppUpdateManager.checkForUpdate(
                repoInput = "jhsoft/jh-getway",
                currentVersionCode = 104,
                currentVersionName = "1.0.4",
                preferDebugApk = false,
                apiBaseUrl = base
            )
            assertTrue("Expected UpdateAvailable for release APK, got $rawRelRes", rawRelRes is AppUpdateCheckResult.UpdateAvailable)
            val relRes = rawRelRes as AppUpdateCheckResult.UpdateAvailable
            assertEquals("JH-GetWay-v1.0.5-release.apk", relRes.release.apkFileName)
            assertEquals("relsha256", relRes.release.expectedSha256)
            assertEquals("ci-ephemeral", relRes.release.signingMode)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun checkForUpdate_rejectsMismatchedPackageIdAndDebugOnlyReleaseAsset() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port
        server.createContext("/repos/jhsoft/jh-getway/releases/latest") { exchange ->
            val response = """
                {
                  "tag_name": "v1.0.12",
                  "name": "JH GetWay v1.0.12",
                  "body": "Permanent signed release",
                  "html_url": "https://github.com/jhsoft/jh-getway/releases/tag/v1.0.12",
                  "assets": [
                    {
                      "name": "JH-GetWay-v1.0.12-release.apk",
                      "browser_download_url": "http://127.0.0.1:$port/dl/release.apk",
                      "size": 10
                    },
                    {
                      "name": "update-manifest.json",
                      "browser_download_url": "http://127.0.0.1:$port/dl/update-manifest.json",
                      "size": 250
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
                  "versionCode": 112,
                  "versionName": "1.0.12",
                  "tag": "v1.0.12",
                  "packageId": "com.wrong.package",
                  "apkName": "JH-GetWay-v1.0.12-release.apk",
                  "sha256": "abc123",
                  "signingMode": "production",
                  "signingCertSha256": "deadbeef"
                }
            """.trimIndent().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val res = AppUpdateManager.checkForUpdate(
                repoInput = "jhsoft/jh-getway",
                currentVersionCode = 111,
                currentVersionName = "1.0.11",
                preferDebugApk = false,
                apiBaseUrl = "http://127.0.0.1:$port",
                expectedPackageId = "com.aistudio.jhgetway.iffndf"
            )
            assertTrue("Expected Error on mismatched packageId, got $res", res is AppUpdateCheckResult.Error)
            assertTrue((res as AppUpdateCheckResult.Error).message.contains("package ID mismatch"))

            // Also verify preferDebugApk=false never selects a debug APK
            val debugOnlyAssets = org.json.JSONArray()
                .put(org.json.JSONObject().put("name", "app-debug.apk").put("browser_download_url", "https://example.com/app-debug.apk").put("size", 100))
            assertNull(AppUpdateManager.selectPreferredApkAsset(debugOnlyAssets, preferDebugApk = false))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun downloadApkWithProgress_verifiesSha256AndRejectsCorruptedArtifact() = runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val payload = "FAKE-APK-BYTES-FOR-HASH-CHECK".toByteArray()
        val expectedSha = java.security.MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { "%02x".format(it) }

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port
        server.createContext("/dl/app.apk") { exchange ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
        try {
            val goodRelease = com.example.updater.AppReleaseInfo(
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
                signingMode = "production"
            )
            val okResult = AppUpdateManager.downloadApkWithProgress(
                context = context,
                release = goodRelease,
                onProgress = {},
                allowLocalHttpForTesting = true
            )
            assertTrue("Expected download with matching SHA-256 to succeed", okResult.isSuccess)
            assertTrue(okResult.getOrThrow().exists())

            val badRelease = goodRelease.copy(expectedSha256 = "0".repeat(64))
            val badResult = AppUpdateManager.downloadApkWithProgress(
                context = context,
                release = badRelease,
                onProgress = {},
                allowLocalHttpForTesting = true
            )
            assertTrue("Expected download with mismatched SHA-256 to fail", badResult.isFailure)
        } finally {
            server.stop(0)
        }
    }
}


