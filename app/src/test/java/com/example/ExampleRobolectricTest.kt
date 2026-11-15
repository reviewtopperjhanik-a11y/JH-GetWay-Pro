package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.core.security.CryptoManager
import com.example.data.local.JhGetWayDatabase
import com.example.gateway.SmartRouterEngine
import com.example.gateway.WorkflowEdge
import com.example.gateway.WorkflowEngine
import com.example.gateway.WorkflowNode
import com.example.gateway.WorkflowNodeType
import com.example.network.McpHttpClient
import com.example.network.ProviderHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @After
    fun tearDown() {
        val app = ApplicationProvider.getApplicationContext<JhGetWayApplication>()
        runBlocking(Dispatchers.IO) {
            app.gatewayServer.stop()
            app.preferences.clearAll()
            app.database.clearAllTables()
        }
        CryptoManager.resetTestKeyProvider()
    }

    @Test
    fun appName_isJhGetWay() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("JH GetWay", context.getString(R.string.app_name))
    }

    @Test
    fun passphraseBackupEncryption_roundtripSucceeds() {
        val payload = """{"providers":[{"name":"Gemini"}]}"""
        val encrypted = CryptoManager.encryptWithPassphrase(payload, "strongPass123")
        assertTrue(encrypted.startsWith("JHBACKUP_V1:"))
        val decrypted = CryptoManager.decryptWithPassphrase(encrypted, "strongPass123")
        assertEquals(payload, decrypted)
    }

    @Test
    fun workflowEngine_detectsCycles() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = JhGetWayDatabase.getInstance(context)
        val router = SmartRouterEngine(db, ProviderHttpClient())
        val engine = WorkflowEngine(db, router, McpHttpClient())

        val nodes = listOf(
            WorkflowNode("n1", WorkflowNodeType.INPUT, "Start"),
            WorkflowNode("n2", WorkflowNodeType.AI_MODEL, "Model"),
            WorkflowNode("n3", WorkflowNodeType.OUTPUT, "End")
        )
        val cyclicEdges = listOf(
            WorkflowEdge("e1", "n1", "n2"),
            WorkflowEdge("e2", "n2", "n1"),
            WorkflowEdge("e3", "n2", "n3")
        )
        val validation = engine.validateGraph(nodes, cyclicEdges)
        assertFalse(validation.valid)
        assertEquals("CYCLE_DETECTED", validation.errorKey)
    }

    private fun waitForTag(tag: String, timeoutMs: Long = 10_000L) {
        val deadlineNs = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadlineNs) {
            val exists = runCatching {
                composeTestRule.onNodeWithTag(tag).assertExists()
                true
            }.getOrDefault(false)
            if (exists) return
            Thread.sleep(50)
            composeTestRule.waitForIdle()
        }
    }

    @Test
    fun onboardingSkip_navigatesToDashboard() {
        composeTestRule.waitForIdle()
        waitForTag("onboarding_skip_all")
        composeTestRule.onNodeWithTag("onboarding_skip_all").assertIsDisplayed().performClick()
        waitForTag("dashboard_gateway_card")
        composeTestRule.onNodeWithTag("dashboard_gateway_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("bottom_nav_providers").assertIsDisplayed().performClick()
        waitForTag("empty_add_provider_btn")
        composeTestRule.onNodeWithTag("empty_add_provider_btn").assertIsDisplayed()
    }
}
