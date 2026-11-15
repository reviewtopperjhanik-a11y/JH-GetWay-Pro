# Privacy & Security Architecture — JH GetWay

**Organization:** JH Soft Corporation  
**Application ID:** `com.aistudio.jhgetway.iffndf`

---

## 1. Local-First Data Storage & Zero Telemetry

- JH GetWay operates strictly **local-first** on the user's Android device.
- JH Soft Corporation operates **no cloud backend, analytics collector, crash uploader, or telemetry server**.
- Request prompts and model responses are sent **only** to the upstream AI providers, local inference servers, or MCP endpoints explicitly configured by the user.
- Persistent Playground chat history is gated by the user toggle **Save Chat History** (`saveChatHistoryAllowed`); when disabled, messages remain in volatile memory only.
- Cloud backups are disabled at the Android backup manifest level (`android:allowBackup="false"`, `backup_rules.xml`, `data_extraction_rules.xml`).

---

## 2. Cryptographic Key Vault & App Lock

- **Hardware-Backed Encryption (`CryptoManager`)**: API keys, device bearer tokens, coding agent tokens, remote gateway tokens, local AI server tokens, and MCP credentials are encrypted at rest with **AES-256-GCM** keys stored in the Android Keystore.
- **No Unmasked Secret Persistence**: UI and activity logs show masked previews (`MaskingUtils.maskSecret`), and error strings are stripped of bearer tokens, `sk-*`, `AIza*`, `jhgw_*`, and query `?key=` secrets via `MaskingUtils.sanitizeErrorMessage`.
- **App Lock (`AppLockManager`)**: Optional 4–8 digit PIN hashed with PBKDF2-HMAC-SHA256 (`120,000` iterations, random 16-byte salt), constant-time verification, exponential lockout after repeated failures, automatic lock on backgrounding, and optional `BiometricPrompt` integration (`USE_BIOMETRIC`).
- **Encrypted Export/Restore (`BackupManager`)**: Version 2 (`JHBACKUP_V2`) backup payloads derive keys from a user-supplied passphrase via PBKDF2 (`150,000` iterations) and encrypt using AES-256-GCM. Passphrases are never stored.

---

## 3. Network, Gateway & SSRF Protections

- **Loopback-First Gateway**: The local Ktor CIO server binds exclusively to `127.0.0.1` by default, blocks wildcard binds (`0.0.0.0`, `::`), and enforces bearer authentication (`gatewayAuthRequired = true`), device scopes (`CHAT`, `MODELS_READ`, `ADMIN`), model allowlists, and sliding-window rate limits.
- **SSRF & DNS Rebinding Defense (`NetworkAddressClassifier`)**:
  - Outbound `ProviderHttpClient`, `McpHttpClient`, `WorkflowEngine` HTTP nodes, and `OkHttpRemoteHealthChecker` validate URLs before every connection and apply `pinnedSafeDns` inside OkHttp.
  - Blocks cloud metadata and link-local/unspecified ranges (`169.254.0.0/16`, `0.0.0.0/8`, `100.64.0.0/10`, `fe80::/10`, `fd00:ec2::/32`, IPv4-mapped IPv6 equivalents, `metadata.google.internal`, `*.internal`), embedded `user:pass@` URL credentials, HTTP redirects across clients, and DNS rebinding from public hostnames to loopback/private IPs when Local Network Access is disabled.
  - Cleartext HTTP to public endpoints is rejected; private/LAN HTTP is permitted only when **Local Network Access** is explicitly enabled by the user (`network_security_config.xml` + application policy).

---

## 4. MCP & Workflow Sandboxing

- **No Subprocess Spawning**: `ProcessBuilder` / native shell execution is disabled in `McpHttpClient`. `STDIO` MCP tools must be bridged via an HTTPS or consented local HTTP JSON-RPC bridge.
- **Tool Guardrails**: Discovered tools matching destructive/shell/filesystem/SQL-write keywords (`McpHttpClient.isDangerousTool`) and newly discovered tools default to `ToolPermission.ASK_BEFORE_RUNNING`, triggering a blocking UI consent modal before execution.
- **Bounded Parsing**: HTTP upstream and MCP JSON-RPC responses are size-bounded (`2 MB` for providers, `256 KB` for MCP) to prevent out-of-memory denial-of-service.

---

## 5. Android Permissions Used

| Permission | Purpose |
| :--- | :--- |
| `INTERNET` | Communicating with user-configured AI providers, MCP bridges, and local gateway loops. |
| `ACCESS_NETWORK_STATE` | Checking network availability before upstream calls. |
| `FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_SPECIAL_USE` | Keeping the user-initiated local HTTP AI gateway running reliably while active. |
| `POST_NOTIFICATIONS` | Displaying the active gateway foreground service notification on Android 13+. |
| `USE_BIOMETRIC` | Optional device biometric unlock for App Lock. |
| `REQUEST_INSTALL_PACKAGES` | Optional user-confirmed installation of signed APK updates downloaded from the configured GitHub Releases repository (verified via HTTPS and SHA-256). |

No storage (`READ_EXTERNAL_STORAGE` / `READ_MEDIA_*`), location, camera, microphone, or contacts permissions are requested.
