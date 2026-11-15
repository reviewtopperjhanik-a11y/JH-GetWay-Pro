package com.example

import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.security.CryptoManager
import com.example.data.local.*
import com.example.gateway.SmartRouterEngine
import com.example.network.ProviderHttpClient
import com.example.network.adapters.GeminiProviderAdapter
import com.example.ui.GatewayViewModel
import io.ktor.http.ContentType
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RouterAndModelSelectionTest {

    private lateinit var app: JhGetWayApplication
    private lateinit var inMemoryDb: JhGetWayDatabase
    private val servers = mutableListOf<EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>>()

    @Before
    fun setUp() {
        CryptoManager.resetTestKeyProvider()
        app = ApplicationProvider.getApplicationContext()
        runBlocking(Dispatchers.IO) {
            app.preferences.clearAll()
            app.database.clearAllTables()
        }
        inMemoryDb = Room.inMemoryDatabaseBuilder(app, JhGetWayDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.stop(100, 300) } }
        servers.clear()
        inMemoryDb.close()
        runBlocking(Dispatchers.IO) {
            app.database.clearAllTables()
            app.preferences.clearAll()
        }
        CryptoManager.resetTestKeyProvider()
    }

    private fun startMockHttpServer(configure: io.ktor.server.routing.Routing.() -> Unit): String {
        val srv = embeddedServer(CIO, configure = {
            connector {
                host = "127.0.0.1"
                port = 0
            }
            reuseAddress = true
        }) {
            routing { configure() }
        }
        srv.start(wait = false)
        servers += srv
        val port = runBlocking { srv.engine.resolvedConnectors().first().port }
        java.net.Socket("127.0.0.1", port).use { }
        return "http://127.0.0.1:$port"
    }

    private suspend fun seedProvider(
        db: JhGetWayDatabase,
        id: String,
        name: String,
        apiFormat: ApiFormat,
        defaultModel: String,
        priority: Int,
        modelIds: List<String> = listOf(defaultModel),
        defaultModelFlagId: String? = null
    ) {
        db.providerDao().upsert(
            ProviderEntity(
                id = id,
                name = name,
                type = ProviderType.CUSTOM,
                baseUrl = "https://example.invalid/v1",
                apiFormat = apiFormat,
                defaultModel = defaultModel,
                priority = priority,
                enabled = true
            )
        )
        db.apiKeyDao().upsert(
            ApiKeyEntity(
                providerId = id,
                label = "$name Key",
                encryptedKey = CryptoManager.encrypt("test-secret-$id"),
                maskedPreview = "test••••$id",
                enabled = true,
                isDefault = true
            )
        )
        modelIds.forEach { mId ->
            db.modelDao().upsert(
                ModelEntity(
                    id = "$id::$mId",
                    providerId = id,
                    providerName = name,
                    modelId = mId,
                    displayName = mId,
                    isDefault = (mId == defaultModelFlagId),
                    enabled = true
                )
            )
        }
    }

    @Test
    fun freshDefaultSettings_routesToCloudProviderWhenNoLocalServerExists() = runBlocking {
        seedProvider(
            db = inMemoryDb,
            id = "prov-gemini",
            name = "Google Gemini",
            apiFormat = ApiFormat.GEMINI,
            defaultModel = "gemini-2.5-flash",
            priority = 1
        )

        val defaultSettings = GatewaySettingsState()
        assertEquals(LocalRoutingPolicy.LOCAL_CLOUD_FALLBACK, defaultSettings.localRoutingPolicy)
        assertTrue(defaultSettings.allowCloudFallbackForLocal)

        val engine = SmartRouterEngine(inMemoryDb, ProviderHttpClient())
        val (candidates, _) = engine.resolveCandidates(
            requestedModelOrAlias = "gemini-2.5-flash",
            settings = defaultSettings,
            mode = RoutingMode.AUTOMATIC,
            explicitProviderId = null
        )

        assertEquals(1, candidates.size)
        assertEquals("prov-gemini", candidates.first().id)
        assertEquals("gemini-2.5-flash", candidates.first().modelId)
    }

    @Test
    fun autoRouter_disambiguatesSpecificModelToMatchingProviderInsteadOfHigherPriorityProvider() = runBlocking {
        seedProvider(
            db = inMemoryDb,
            id = "prov-openai",
            name = "OpenAI",
            apiFormat = ApiFormat.OPENAI,
            defaultModel = "gpt-4o-mini",
            priority = 1,
            modelIds = listOf("gpt-4o-mini", "gpt-4o")
        )
        seedProvider(
            db = inMemoryDb,
            id = "prov-gemini",
            name = "Google Gemini",
            apiFormat = ApiFormat.GEMINI,
            defaultModel = "gemini-2.5-flash",
            priority = 10,
            modelIds = listOf("gemini-2.5-flash", "gemini-2.5-pro")
        )

        val engine = SmartRouterEngine(inMemoryDb, ProviderHttpClient())
        val (candidates, _) = engine.resolveCandidates(
            requestedModelOrAlias = "gemini-2.5-pro",
            settings = GatewaySettingsState(),
            mode = RoutingMode.PRIORITY,
            explicitProviderId = null
        )

        assertEquals(1, candidates.size)
        assertEquals("prov-gemini", candidates.first().id)
        assertEquals("gemini-2.5-pro", candidates.first().modelId)
    }

    @Test
    fun aliasAndBlankRouting_prioritizesProviderDefaultModelOverArbitraryFetchedModels() = runBlocking {
        seedProvider(
            db = inMemoryDb,
            id = "prov-gemini",
            name = "Google Gemini",
            apiFormat = ApiFormat.GEMINI,
            defaultModel = "gemini-2.5-flash",
            priority = 1,
            modelIds = listOf("a-older-model", "gemini-2.5-flash", "z-experimental-model"),
            defaultModelFlagId = null // Simulate newly fetched catalog where isDefault = false
        )

        val engine = SmartRouterEngine(inMemoryDb, ProviderHttpClient())
        val (aliasCandidates, _) = engine.resolveCandidates(
            requestedModelOrAlias = "jh-coder",
            settings = GatewaySettingsState(),
            mode = RoutingMode.AUTOMATIC,
            explicitProviderId = null
        )

        assertEquals(1, aliasCandidates.size)
        assertEquals("gemini-2.5-flash", aliasCandidates.first().modelId)
    }

    @Test
    fun geminiAdapter_disablesNonChatModelsAndOrdersChatModelsFirst() {
        val adapter = GeminiProviderAdapter()
        val payload = """
            {
              "models": [
                {
                  "name": "models/text-embedding-004",
                  "displayName": "Text Embedding 004",
                  "supportedGenerationMethods": ["embedContent"]
                },
                {
                  "name": "models/aqa",
                  "displayName": "Attributed Question Answering",
                  "supportedGenerationMethods": ["generateAnswer"]
                },
                {
                  "name": "models/gemini-2.5-flash",
                  "displayName": "Gemini 2.5 Flash",
                  "inputTokenLimit": 1048576,
                  "supportedGenerationMethods": ["generateContent", "countTokens"]
                }
              ]
            }
        """.trimIndent()

        val parsed = adapter.parseModelsResponse(payload, "gem-1", "Google Gemini", isLocal = false)
        assertEquals(3, parsed.size)
        assertEquals("gemini-2.5-flash", parsed.first().modelId)
        assertTrue(parsed.first().enabled)
        assertTrue(parsed.first().supportsTools)
        assertTrue(parsed.first().supportsStreaming)

        val embed = parsed.first { it.modelId == "text-embedding-004" }
        assertFalse(embed.enabled)
        assertFalse(embed.supportsTools)

        val aqa = parsed.first { it.modelId == "aqa" }
        assertFalse(aqa.enabled)
    }

    @Test
    fun gatewayViewModel_fetchModelsOpensPickerAndSelectModelSyncsProviderAndGlobalDefaults() = runBlocking {
        val baseUrl = startMockHttpServer {
            get("/models") {
                call.respondText(
                    """
                    {
                      "models": [
                        {
                          "name": "models/embedding-001",
                          "displayName": "Embedding 001",
                          "supportedGenerationMethods": ["embedContent"]
                        },
                        {
                          "name": "models/gemini-2.5-flash",
                          "displayName": "Gemini 2.5 Flash",
                          "supportedGenerationMethods": ["generateContent"]
                        },
                        {
                          "name": "models/gemini-2.5-pro",
                          "displayName": "Gemini 2.5 Pro",
                          "supportedGenerationMethods": ["generateContent"]
                        }
                      ]
                    }
                    """.trimIndent(),
                    ContentType.Application.Json
                )
            }
        }

        app.preferences.updateLocalAiSettings(
            policy = LocalRoutingPolicy.LOCAL_CLOUD_FALLBACK,
            allowCloudFallback = true,
            localNetworkAllowed = true
        )

        val provider = ProviderEntity(
            id = "vm-prov-gemini",
            name = "Google Gemini",
            type = ProviderType.BUILT_IN,
            presetId = "GEMINI",
            baseUrl = baseUrl,
            apiFormat = ApiFormat.GEMINI,
            defaultModel = "",
            enabled = true
        )
        app.database.providerDao().upsert(provider)
        app.database.apiKeyDao().upsert(
            ApiKeyEntity(
                providerId = provider.id,
                label = "Default Key",
                encryptedKey = CryptoManager.encrypt("AIzaTestKey123456"),
                maskedPreview = "AIza••••3456",
                enabled = true,
                isDefault = true
            )
        )

        val vmStore = androidx.lifecycle.ViewModelStore()
        val vm = androidx.lifecycle.ViewModelProvider(
            vmStore,
            androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(app)
        )[GatewayViewModel::class.java]
        try {
            // Wait for ViewModel initial settings load
            val initDeadline = System.nanoTime() + 10_000_000_000L
            while (System.nanoTime() < initDeadline && !vm.settings.value.localNetworkAccessAllowed) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(30)
            }
            assertTrue(vm.settings.value.localNetworkAccessAllowed)

            vm.fetchProviderModels(provider, openPickerOnSuccess = true)

            val fetchDeadline = System.nanoTime() + 10_000_000_000L
            while (System.nanoTime() < fetchDeadline && vm.fetchedModelsPrompt.value == null) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(30)
            }

            val prompt = vm.fetchedModelsPrompt.value
            assertNotNull("Expected FetchedModelsPrompt to open after fetching models (banner=${vm.uiBannerMessage.value})", prompt)
            assertEquals(3, prompt!!.models.size)

            val proModel = prompt.models.first { it.modelId == "gemini-2.5-pro" }
            vm.selectModelForProvider(provider.id, proModel)

            val selectDeadline = System.nanoTime() + 10_000_000_000L
            while (System.nanoTime() < selectDeadline) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                val savedProv = app.database.providerDao().getById(provider.id)
                if (savedProv?.defaultModel == "gemini-2.5-pro" && vm.settings.value.defaultModelId == "gemini-2.5-pro") {
                    break
                }
                Thread.sleep(30)
            }

            val savedProv = app.database.providerDao().getById(provider.id)
            assertEquals("gemini-2.5-pro", savedProv?.defaultModel)
            assertEquals("gemini-2.5-pro", vm.settings.value.defaultModelId)
            assertEquals(provider.id, vm.settings.value.defaultProviderId)

            val persistedPro = app.database.modelDao().getByProvider(provider.id).first { it.modelId == "gemini-2.5-pro" }
            assertTrue(persistedPro.isDefault)
            assertTrue(persistedPro.enabled)

            vm.openModelInPlayground(persistedPro)
            assertEquals(provider.id, vm.playgroundTarget.value?.providerId)
            assertEquals("gemini-2.5-pro", vm.playgroundTarget.value?.modelId)
            vm.consumePlaygroundTarget()
            assertNull(vm.playgroundTarget.value)
        } finally {
            vmStore.clear()
        }
    }

    @Test
    fun playgroundDao_getMessagesOnceReturnsInsertedPromptImmediately() = runBlocking {
        val session = PlaygroundSessionEntity(id = "sess-1", title = "Test Session")
        inMemoryDb.playgroundDao().upsertSession(session)
        inMemoryDb.playgroundDao().insertMessage(
            PlaygroundMessageEntity(sessionId = "sess-1", role = "user", content = "Hello JH GetWay")
        )

        val messages = inMemoryDb.playgroundDao().getMessagesOnce("sess-1")
        assertEquals(1, messages.size)
        assertEquals("Hello JH GetWay", messages.first().content)
    }
}
