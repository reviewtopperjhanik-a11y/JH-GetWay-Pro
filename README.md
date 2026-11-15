# JH GetWay

**Personal AI API Gateway, Key Vault, Smart Router, MCP Bridge & Workflow Orchestrator for Android**  
Built by **JH Soft Corporation**

---

## Overview

**JH GetWay** turns your Android device into a local-first AI gateway and control center. It unifies OpenAI, Anthropic Claude, Google Gemini, OpenRouter, Groq, DeepSeek, Mistral, Cohere, Hugging Face, Ollama, LM Studio, and custom OpenAI-compatible endpoints behind unified API surfaces (`/v1/chat/completions`, `/v1/responses`, `/v1/messages`, `/v1/models`, and `/health`) with automatic smart routing, multi-key rotation, token telemetry, MCP tool bridging, and DAG workflow execution.

---

## Key Capabilities

- **Unified Local HTTP Gateway (Ktor CIO)**:
  - Runs an embedded Ktor CIO server inside an explicit user-initiated `GatewayForegroundService` (`specialUse`).
  - Defaults strictly to loopback binding (`127.0.0.1:8787`) with fail-closed bearer authentication, per-device scopes (`CHAT`, `MODELS_READ`, `ADMIN`), model allowlists, and token-bucket rate limiting.
  - Supports **Remote Gateway** HTTPS client mode with encrypted bearer token storage and health checks.
- **Encrypted API Key Vault**:
  - Encrypts all upstream API keys, device tokens, agent tokens, local AI server tokens, and MCP environment variables with **Android Keystore AES-256-GCM** (`CryptoManager`).
  - Supports multiple keys per provider with `PRIORITY`, `ROUND_ROBIN`, and `FAILOVER` selection policies.
- **Smart Router Engine**:
  - Modes: `AUTOMATIC`, `LOWEST_COST`, `FASTEST`, `PRIORITY_ORDER`, `ROUND_ROBIN`, and `MANUAL`.
  - Custom alias & provider routing rules (`RoutingRuleEntity`), automatic fallback chains, and live routing decision monitoring.
- **Local AI & Coding Agent Companion**:
  - Connects to Ollama, LM Studio, llama.cpp, vLLM, or LocalAI with explicit Local Network Access consent and policies (`LOCAL_ONLY`, `LOCAL_FIRST`, `LOCAL_CLOUD_FALLBACK`, `CLOUD_FIRST`).
  - Provisions scoped tokens, UTC daily token quota tracking (with `ACTUAL` / `ESTIMATED` / `UNKNOWN` provenance), and ready-to-copy config snippets for **Codex CLI**, **OpenCode**, **Cline**, **Continue**, and **Aider**.
- **MCP (Model Context Protocol) Bridge (Hardened)**:
  - Discovers tools, resources, and prompts over Streamable HTTP / SSE JSON-RPC 2.0 endpoints.
  - Enforces Android process sandboxing (no native subprocess spawning for `STDIO`), 256 KB bounded response parsing, JSON-RPC `id` / `2.0` validation, `isError` checking, and mandatory interactive approval prompts (`ASK_BEFORE_RUNNING`) for dangerous tools.
- **Visual DAG Workflow Engine**:
  - Executes multi-node workflows (`INPUT`, `PROMPT`, `LLM_CALL`, `CONDITION`, `ROUTER`, `MCP_TOOL`, `HTTP_REQUEST`, `CODE_TRANSFORM`, `MERGE`, `OUTPUT`) with cycle detection, reachability/config validation, bounded parallel fan-out, branch-skip propagation, per-node retries, cancellation, and workflow versioning.
- **Analytics, Playground & Bilingual UI**:
  - Single-chat streaming and multi-model side-by-side comparison Playground.
  - Time-bucketed request/latency Canvas chart, provider/model/device/range filters, Actual Billed Cost vs Estimated Cost separation, and token telemetry accuracy breakdown.
  - Full **English** and **Bengali (বাংলা)** localization with Light/Dark/System themes.
- **App Lock & Encrypted Backup (V2)**:
  - PBKDF2WithHmacSHA256 App Lock PIN with exponential lockout and optional `BiometricPrompt`.
  - Passphrase-encrypted `JHBACKUP_V2` export/preview/restore (PBKDF2 + AES-256-GCM) with conflict resolution and configurable retention pruning.

---

## Architecture

- **UI**: Jetpack Compose + Material Design 3 + Navigation Compose (`app/src/main/java/com/example/ui/`)
- **State**: `GatewayViewModel` (`StateFlow` / `MutableStateFlow`) + `AppPreferences` (`DataStore`)
- **Storage**: AndroidX Room (`JhGetWayDatabase`, schema v5 with exported schemas under `app/schemas/`)
- **Networking**: OkHttp 4 (`ProviderHttpClient`, `McpHttpClient`) with SSRF & DNS-rebinding protection (`NetworkAddressClassifier.validateEndpointSecurity` & `pinnedSafeDns`), Ktor CIO (`LocalGatewayHttpServer`)
- **Security**: `CryptoManager` (Android Keystore AES-256-GCM), `AppLockManager` (PBKDF2 + Biometric), `AccessControl` (`DeviceScopePolicy`), `MaskingUtils`

---

## Building, GitHub Releases & In-App Auto-Updates

### Local Build & Verification
Run verification via Gradle (uses container `gradle` binary):

```bash
gradle :app:compileDebugKotlin
gradle :app:testDebugUnitTest --console=plain
```

### GitHub Auto-Releases & Permanent Signing
- Every push to `main`/`master` runs `.github/workflows/android-release.yml`:
  1. **Test job:** Runs `:app:testDebugUnitTest` and `:app:assembleDebug` (PRs run tests only without publishing).
  2. **Release job:** Auto-increments semantic `versionName` and `versionCode` from the latest release, signs `:app:assembleRelease` using fail-closed permanent GitHub Secrets (`KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_PASSWORD` for alias `upload`), verifies `applicationId` + signer certificate via `aapt2`/`apksigner`, and publishes `JH-GetWay-v*-release.apk`, `update-manifest.json`, and `SHA256SUMS.txt` to GitHub Releases.
- In-app updater (`AppUpdateManager`) verifies `packageId`, `SHA-256`, and signing certificate before launching Android's in-place update installer so future updates install without uninstalling.
- Full setup & permanent keystore instructions (Bengali + English): [`docs/GITHUB_RELEASE_AND_SIGNING_GUIDE.md`](docs/GITHUB_RELEASE_AND_SIGNING_GUIDE.md).

For privacy details see [`docs/PRIVACY_AND_SECURITY.md`](docs/PRIVACY_AND_SECURITY.md). For third-party library licenses see [`docs/DEPENDENCY_LICENSES.md`](docs/DEPENDENCY_LICENSES.md).
