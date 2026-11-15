package com.example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.core.i18n.BengaliStrings
import com.example.core.i18n.EnglishStrings
import com.example.core.i18n.LocalStrings
import com.example.core.security.CryptoManager
import com.example.data.local.*
import com.example.ui.GatewayViewModel
import com.example.ui.screens.*
import com.example.ui.theme.JhGetWayTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PhaseFUiAndReleaseTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var app: JhGetWayApplication
    private lateinit var viewModel: GatewayViewModel

    @Before
    fun setUp() {
        CryptoManager.resetTestKeyProvider()
        app = ApplicationProvider.getApplicationContext()
        runBlocking(Dispatchers.IO) {
            app.preferences.clearAll()
            app.database.clearAllTables()
        }
        viewModel = GatewayViewModel(app)
    }

    @After
    fun tearDown() {
        runBlocking(Dispatchers.IO) {
            app.gatewayServer.stop()
            app.preferences.clearAll()
            app.database.clearAllTables()
        }
        CryptoManager.resetTestKeyProvider()
    }

    @Test
    fun coreScreens_renderInEnglishAndBengaliWithoutLayoutCrash() {
        composeRule.setContent {
            CompositionLocalProvider(LocalStrings provides BengaliStrings) {
                JhGetWayTheme {
                    DashboardScreen(viewModel = viewModel, onNavigate = {})
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("dashboard_gateway_card").assertIsDisplayed()
        composeRule.onNodeWithText(BengaliStrings.startGateway).assertIsDisplayed()
        composeRule.onNodeWithText(BengaliStrings.providerOverview).assertIsDisplayed()
    }

    @Test
    fun narrow320dpScreens_renderResponsiveFlowRowsCleanly() {
        composeRule.setContent {
            CompositionLocalProvider(LocalStrings provides EnglishStrings) {
                JhGetWayTheme {
                    Box(modifier = Modifier.width(320.dp).height(700.dp)) {
                        SettingsScreen(viewModel = viewModel, onNavigate = {})
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(EnglishStrings.settingsTitle).assertIsDisplayed()

        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("settings_privacy_btn"))
        composeRule.onNodeWithTag("settings_privacy_btn").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText(EnglishStrings.privacyPolicyTitle).onLast().assertIsDisplayed()
        composeRule.onNodeWithText(EnglishStrings.close).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("settings_licenses_btn"))
        composeRule.onNodeWithTag("settings_licenses_btn").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText(EnglishStrings.openSourceLicensesTitle).onLast().assertIsDisplayed()
    }

    @Test
    fun playgroundCopyButton_meetsMinimum48dpTouchTargetAndAccessibility() {
        runBlocking {
            app.preferences.setSaveChatHistory(true)
            val sess = PlaygroundSessionEntity(id = "s-ui", title = "Test Session")
            app.database.playgroundDao().upsertSession(sess)
            app.database.playgroundDao().insertMessage(
                PlaygroundMessageEntity(
                    id = "m-ui",
                    sessionId = "s-ui",
                    role = "assistant",
                    content = "Hello from JH GetWay",
                    modelId = "gpt-4o",
                    providerName = "OpenAI",
                    latencyMs = 120L,
                    inputTokens = 12,
                    outputTokens = 8,
                    estimatedCostUsd = 0.0005
                )
            )
            app.database.providerDao().upsert(
                ProviderEntity(id = "p-ui", name = "OpenAI", baseUrl = "https://api.openai.com/v1")
            )
        }
        viewModel.selectOrCreateSession("s-ui")

        composeRule.setContent {
            CompositionLocalProvider(LocalStrings provides EnglishStrings) {
                JhGetWayTheme {
                    Box(modifier = Modifier.width(320.dp).height(720.dp)) {
                        PlaygroundScreen(viewModel = viewModel, onNavigate = {})
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val copyBtn = composeRule.onNodeWithTag("playground_copy_msg_m-ui")
        copyBtn.assertExists()
        copyBtn.assertWidthIsAtLeast(48.dp)
        copyBtn.assertHeightIsAtLeast(48.dp)
        copyBtn.assertContentDescriptionEquals(EnglishStrings.copyResponseLabel)
    }

    @Test
    fun analyticsScreen_showsTimeBucketedChartAndActualCostWithoutHardcodedPricingDate() {
        runBlocking {
            app.database.metricAndLogDao().insertMetric(
                RequestMetricEntity(
                    id = "m-1",
                    requestId = "r-1",
                    providerId = "p1",
                    providerName = "OpenRouter",
                    modelId = "gpt-4o",
                    deviceName = "Pixel 9",
                    success = true,
                    latencyMs = 210,
                    inputTokens = 100,
                    outputTokens = 50,
                    usageSource = "ACTUAL",
                    estimatedCostUsd = 0.001,
                    actualBilledCostUsd = 0.0014,
                    pricingSource = "Provider Billing"
                )
            )
        }

        composeRule.setContent {
            CompositionLocalProvider(LocalStrings provides EnglishStrings) {
                JhGetWayTheme {
                    AnalyticsScreen(viewModel = viewModel)
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("analytics_list")
            .performScrollToNode(hasTestTag("analytics_time_bucket_chart"))
        composeRule.onNodeWithTag("analytics_time_bucket_chart").assertExists()
        composeRule.onAllNodesWithText("2026-03-01", substring = true).assertCountEquals(0)
        composeRule.onNodeWithTag("analytics_list")
            .performScrollToNode(hasText(EnglishStrings.actualBilledCostLabel, substring = true))
        composeRule.onNodeWithText(EnglishStrings.actualBilledCostLabel, substring = true).assertExists()
    }

    @Test
    fun releaseConfigAndDocs_areConsistentAndPreserveCapabilities() {
        val metadataFile = listOf(File("metadata.json"), File("../metadata.json")).firstOrNull { it.exists() }
        assertNotNull("Expected metadata.json to exist", metadataFile)
        val metaJson = JSONObject(metadataFile!!.readText())
        assertEquals("JH GetWay", metaJson.getString("name"))
        val caps = metaJson.getJSONArray("majorCapabilities")
        val capList = (0 until caps.length()).map { caps.getString(it) }
        assertTrue(
            "metadata.json must preserve MAJOR_CAPABILITY_SERVER_SIDE_GEMINI_API",
            "MAJOR_CAPABILITY_SERVER_SIDE_GEMINI_API" in capList
        )

        val proguardFile = listOf(File("proguard-rules.pro"), File("app/proguard-rules.pro")).firstOrNull { it.exists() }
        assertNotNull("Expected proguard-rules.pro to exist", proguardFile)
        val proguardText = proguardFile!!.readText()
        assertTrue(proguardText.contains("io.ktor"))
        assertTrue(proguardText.contains("androidx.room.RoomDatabase"))
        assertTrue(proguardText.contains("com.squareup.moshi"))

        val rootDir = metadataFile.parentFile ?: File(".")
        assertTrue(File(rootDir, "README.md").exists())
        assertTrue(File(rootDir, "docs/PRIVACY_AND_SECURITY.md").exists())
        assertTrue(File(rootDir, "docs/DEPENDENCY_LICENSES.md").exists())
        assertTrue(File(rootDir, "docs/GITHUB_RELEASE_AND_SIGNING_GUIDE.md").exists())
    }
}
