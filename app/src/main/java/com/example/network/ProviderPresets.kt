package com.example.network

import com.example.data.local.ApiFormat
import com.example.data.local.LocalEngineType
import com.example.data.local.ModelType
import com.example.data.local.ProviderType

data class ProviderPreset(
    val id: String,
    val name: String,
    val type: ProviderType,
    val baseUrl: String,
    val apiFormat: ApiFormat,
    val defaultModel: String,
    val notes: String
)

data class ModelPricingMeta(
    val inputPer1M: Double,
    val outputPer1M: Double,
    val updatedAt: String
)

object ProviderPresets {
    val all: List<ProviderPreset> = listOf(
        ProviderPreset(
            id = "GEMINI",
            name = "Google Gemini",
            type = ProviderType.BUILT_IN,
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            apiFormat = ApiFormat.GEMINI,
            defaultModel = "gemini-2.5-flash",
            notes = "Google Gemini Developer API (v1beta)"
        ),
        ProviderPreset(
            id = "OPENAI",
            name = "OpenAI",
            type = ProviderType.BUILT_IN,
            baseUrl = "https://api.openai.com/v1",
            apiFormat = ApiFormat.OPENAI,
            defaultModel = "gpt-4o-mini",
            notes = "OpenAI REST API v1"
        ),
        ProviderPreset(
            id = "ANTHROPIC",
            name = "Anthropic",
            type = ProviderType.ANTHROPIC_COMPATIBLE,
            baseUrl = "https://api.anthropic.com/v1",
            apiFormat = ApiFormat.ANTHROPIC,
            defaultModel = "claude-3-5-sonnet-latest",
            notes = "Anthropic Messages API"
        ),
        ProviderPreset(
            id = "OPENROUTER",
            name = "OpenRouter",
            type = ProviderType.OPENAI_COMPATIBLE,
            baseUrl = "https://openrouter.ai/api/v1",
            apiFormat = ApiFormat.OPENAI,
            defaultModel = "openai/gpt-4o-mini",
            notes = "Unified multi-provider OpenAI-compatible router"
        ),
        ProviderPreset(
            id = "GROQ",
            name = "Groq",
            type = ProviderType.OPENAI_COMPATIBLE,
            baseUrl = "https://api.groq.com/openai/v1",
            apiFormat = ApiFormat.OPENAI,
            defaultModel = "llama-3.3-70b-versatile",
            notes = "Groq LPU low-latency inference"
        ),
        ProviderPreset(
            id = "MISTRAL",
            name = "Mistral",
            type = ProviderType.OPENAI_COMPATIBLE,
            baseUrl = "https://api.mistral.ai/v1",
            apiFormat = ApiFormat.OPENAI,
            defaultModel = "mistral-small-latest",
            notes = "Mistral AI Platform API"
        ),
        ProviderPreset(
            id = "HUGGINGFACE",
            name = "Hugging Face",
            type = ProviderType.OPENAI_COMPATIBLE,
            baseUrl = "https://router.huggingface.co/v1",
            apiFormat = ApiFormat.OPENAI,
            defaultModel = "Qwen/Qwen2.5-72B-Instruct",
            notes = "Hugging Face Inference Providers Router"
        ),
        ProviderPreset(
            id = "DEEPINFRA",
            name = "DeepInfra",
            type = ProviderType.OPENAI_COMPATIBLE,
            baseUrl = "https://api.deepinfra.com/v1/openai",
            apiFormat = ApiFormat.OPENAI,
            defaultModel = "meta-llama/Llama-3.3-70B-Instruct",
            notes = "DeepInfra OpenAI-compatible API"
        ),
        ProviderPreset(
            id = "COHERE",
            name = "Cohere",
            type = ProviderType.CUSTOM,
            baseUrl = "https://api.cohere.com/v2",
            apiFormat = ApiFormat.COHERE,
            defaultModel = "command-r-plus",
            notes = "Cohere v2 Chat API"
        ),
        ProviderPreset(
            id = "CUSTOM",
            name = "Custom OpenAI-Compatible",
            type = ProviderType.OPENAI_COMPATIBLE,
            baseUrl = "https://",
            apiFormat = ApiFormat.OPENAI,
            defaultModel = "",
            notes = "Custom OpenAI-compatible endpoint"
        )
    )

    fun defaultUrlForLocalEngine(engineType: LocalEngineType): String = when (engineType) {
        LocalEngineType.OLLAMA -> "http://127.0.0.1:11434"
        LocalEngineType.LLAMA_CPP -> "http://127.0.0.1:8080/v1"
        LocalEngineType.LM_STUDIO -> "http://127.0.0.1:1234/v1"
        LocalEngineType.LOCAL_AI -> "http://127.0.0.1:8080/v1"
        LocalEngineType.OPENAI_COMPATIBLE -> "http://127.0.0.1:8000/v1"
        LocalEngineType.CUSTOM -> "http://127.0.0.1:8080"
    }

    // Reference pricing table (USD per 1M tokens) when known; unknown models display Unknown
    private val referencePricing = mapOf(
        "gpt-4o" to ModelPricingMeta(2.50, 10.00, "2026-03-01"),
        "gpt-4o-mini" to ModelPricingMeta(0.15, 0.60, "2026-03-01"),
        "o3-mini" to ModelPricingMeta(1.10, 4.40, "2026-03-01"),
        "gemini-2.5-pro" to ModelPricingMeta(1.25, 10.00, "2026-03-01"),
        "gemini-2.5-flash" to ModelPricingMeta(0.15, 0.60, "2026-03-01"),
        "gemini-2.0-flash" to ModelPricingMeta(0.10, 0.40, "2026-03-01"),
        "claude-3-5-sonnet" to ModelPricingMeta(3.00, 15.00, "2026-03-01"),
        "claude-3-5-haiku" to ModelPricingMeta(0.80, 4.00, "2026-03-01"),
        "claude-3-7-sonnet" to ModelPricingMeta(3.00, 15.00, "2026-03-01"),
        "llama-3.3-70b-versatile" to ModelPricingMeta(0.59, 0.79, "2026-03-01"),
        "mistral-small-latest" to ModelPricingMeta(0.20, 0.60, "2026-03-01"),
        "mistral-large-latest" to ModelPricingMeta(2.00, 6.00, "2026-03-01")
    )

    fun lookupPricing(modelId: String): ModelPricingMeta? {
        val lower = modelId.lowercase()
        return referencePricing.entries.firstOrNull { lower.contains(it.key) }?.value
    }

    fun inferModelType(modelId: String): ModelType {
        val lower = modelId.lowercase()
        return when {
            lower.contains("embed") -> ModelType.EMBEDDING
            lower.contains("dall-e") || lower.contains("imagen") || lower.contains("flux") -> ModelType.IMAGE_GENERATION
            lower.contains("whisper") || lower.contains("tts") || lower.contains("audio") -> ModelType.AUDIO
            lower.contains("coder") || lower.contains("codestral") || lower.contains("code") -> ModelType.CODING
            lower.contains("o1") || lower.contains("o3") || lower.contains("r1") || lower.contains("reason") -> ModelType.REASONING
            lower.contains("vision") || lower.contains("vl") -> ModelType.VISION
            lower.contains("gemini") || lower.contains("gpt-4o") || lower.contains("claude-3") -> ModelType.MULTIMODAL
            else -> ModelType.GENERAL_CHAT
        }
    }
}
