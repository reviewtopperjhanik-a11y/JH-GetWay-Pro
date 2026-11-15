# JH GetWay --- Production Upgrade Master Prompt for Google AI Studio

**Organization:** JH Soft Corporation\
**Product:** JH GetWay\
**Target:** Existing native Android project generated in Google AI
Studio\
**Purpose:** Stabilize, repair, improve and prepare the existing project
for production without destroying working code\
**Execution mode:** Incremental engineering; inspect first, change
narrowly, test after every phase

------------------------------------------------------------------------

# 1. YOUR ROLE

Act as a senior Android/Kotlin engineer, backend/API gateway engineer,
application security engineer and QA engineer.

You are working on the **existing JH GetWay Android project** already
open in this Google AI Studio workspace. This is an upgrade task, not a
request to generate a new application.

The project is a native Android application using Kotlin, Jetpack
Compose and Material 3. It already contains meaningful implementations
for provider management, model management, a local gateway, routing,
streaming, workflows, MCP management, device management, analytics,
encrypted credential storage, localization and theme selection.

Your first responsibility is to understand the existing repository and
preserve all working functionality.

## Non-negotiable rules

1.  Do not recreate the project from scratch.
2.  Do not replace the existing application with a new template.
3.  Do not delete existing screens, modules, database data models or
    working functionality merely to simplify implementation.
4.  Do not perform a broad rewrite before inspecting the exact files and
    dependencies involved.
5.  Do not invent successful functionality. A feature is complete only
    when its actual implementation and tests demonstrate it.
6.  Do not use fake data, mock success responses or hard-coded
    "Running", "Connected", "Healthy" or analytics values in production
    paths.
7.  Preserve the current product name **JH GetWay** and organization
    **JH Soft Corporation**.
8.  Preserve Bengali and English language support.
9.  Preserve Light, Dark and System theme support.
10. Preserve the existing simple, professional, mobile-first UI. Improve
    consistency and usability without an unnecessary redesign.
11. Never expose API keys, passwords, tokens, encryption keys or private
    request content in logs, UI, crash reports or test output.
12. Never hard-code secrets in source code or repository files.
13. Do not silently change API contracts, database schemas, application
    ID, package names or persisted data.
14. Before any database change, provide and implement a safe migration
    path.
15. Do not claim a build or test passed unless you actually ran it and
    observed the result.
16. If a requested integration cannot be implemented in the current
    Android environment, explain the limitation and implement a safe,
    clearly labelled bridge or unsupported state instead of pretending
    it works.
17. Do not remove a feature just because it is incomplete. Preserve it
    and clearly distinguish implemented, partial and unavailable states.
18. Keep changes focused. Avoid unrelated refactoring.

------------------------------------------------------------------------

# 2. PRODUCT TARGET

JH GetWay is a complete AI Gateway Management Platform.

Its core purpose is to connect and manage:

-   Cloud AI Providers
-   AI Models
-   Unified API Endpoints
-   Smart API Routing and Fallback
-   Coding Agents
-   Local AI Servers
-   MCP Servers and Tools
-   AI Workflows
-   AI Playground
-   API Analytics
-   Multi-Device Clients
-   Encrypted API Credentials

The application must support a local Android-hosted gateway mode and a
real remote gateway connection mode. These modes must never be confused
in the UI or runtime.

## Main navigation

Preserve and improve the existing navigation for:

-   Dashboard
-   Providers
-   Models
-   API Gateway
-   Smart Router
-   Coding Agents
-   Local AI
-   MCP Servers
-   Workflows
-   Playground
-   Analytics
-   Devices
-   API Key Vault
-   Activity / Logs
-   Settings

Use the existing navigation structure where practical. Do not add
duplicate destinations.

------------------------------------------------------------------------

# 3. REQUIRED ENGINEERING WORKFLOW

Follow this workflow exactly.

## Step 1 --- Repository inspection

Before editing:

1.  Inspect the full project tree.
2.  Read Gradle configuration and dependency versions.
3.  Inspect AndroidManifest and app permissions.
4.  Inspect the database, entities, DAOs and migrations.
5.  Inspect gateway server, request parser, route handlers and
    lifecycle.
6.  Inspect provider adapters and request/response conversion.
7.  Inspect Smart Router and fallback logic.
8.  Inspect credential encryption and app lock.
9.  Inspect device authentication and permission enforcement.
10. Inspect agent request handling and token accounting.
11. Inspect MCP transport implementation.
12. Inspect workflow validation and execution.
13. Inspect analytics aggregation.
14. Inspect localization and themes.
15. Inspect all existing tests.

Create a concise internal gap list based on the actual repository. Do
not assume that a finding from an earlier static review is still present
if the code has since changed. Verify every finding against the current
source before editing.

## Step 2 --- Establish a baseline

-   Determine whether a Gradle Wrapper exists.
-   Determine whether the project can compile.
-   Run available unit tests.
-   Record exact build/test errors.
-   Do not change application code merely to hide baseline failures.
-   If the environment cannot run Gradle or Android tests, state this
    limitation clearly and continue with source-level validation.

## Step 3 --- Implement in small phases

For each phase:

1.  Identify exact files to change.
2.  Explain the issue briefly.
3.  Implement the smallest safe change.
4.  Add or update tests.
5.  Run relevant tests.
6.  Review for regressions.
7.  Summarize changed files and actual verification results.
8.  Only then proceed to the next phase.

Do not stop after writing a plan. Perform the implementation that the
current environment supports.

------------------------------------------------------------------------

# 4. PRIORITY 0 --- CRITICAL SECURITY AND CORRECTNESS

Complete these items before adding new features.

## P0.1 --- Fix credential encryption fail-closed behavior

Inspect `CryptoManager.kt` and all credential storage call sites.

Required behavior:

-   Use Android Keystore-backed encryption on real Android devices.
-   AES-GCM or an equivalent authenticated encryption construction must
    be used correctly.
-   Never fall back to a fixed, deterministic or hard-coded encryption
    key on a real device.
-   If Keystore creation, access or decryption fails, fail safely and
    show a clear recoverable error.
-   Do not silently store new credentials using weak fallback
    encryption.
-   Restrict any test-only implementation to an explicitly detected test
    environment.
-   Keep encryption version metadata so future key/encryption migrations
    can be supported.
-   Handle key invalidation without exposing credentials.
-   Never log plaintext secrets or decrypted values.

Add tests for: - Successful encrypt/decrypt. - Wrong key or corrupted
ciphertext. - Authentication tag failure. - Keystore unavailable. - Key
invalidation/recovery behavior. - Test-only behavior, if any.

Do not claim that already stored credentials have been migrated unless a
real migration has been implemented and verified.

## P0.2 --- Fix Gateway mode state semantics

Inspect `LocalGatewayHttpServer.kt`, gateway settings and UI state.

There must be distinct states, at minimum:

-   STOPPED
-   STARTING
-   LOCAL_RUNNING
-   REMOTE_CONNECTING
-   REMOTE_CONNECTED
-   REMOTE_UNAVAILABLE
-   ERROR

Rules:

-   A configured HTTPS URL is not proof that a remote gateway is
    running.
-   Remote mode must perform a real health check against the configured
    remote service.
-   Validate TLS and hostname.
-   Authenticate the health check if the remote service requires
    authentication.
-   Use connection and response timeouts.
-   Show the actual remote URL and last successful health-check time,
    without exposing secrets.
-   If the remote service cannot be reached, show `REMOTE_UNAVAILABLE`
    or `ERROR`.
-   Do not start a local socket server when remote mode is selected.
-   Implement a real remote API client/proxy path only if the product
    architecture requires this Android app to forward requests.
    Otherwise, clearly identify this app as a remote gateway management
    client.

Do not mark remote mode as `RUNNING` merely because the URL begins with
`https://`.

## P0.3 --- Secure local network exposure

Inspect the local gateway bind address and Android network security
configuration.

Required behavior:

-   Default local gateway binding must be loopback-only.
-   LAN exposure must be opt-in and clearly explained.
-   Never expose API credentials and prompts over unencrypted LAN HTTP.
-   Do not allow public-interface binding by default.
-   For remote access, require HTTPS through a properly configured
    remote server, reverse proxy or secure tunnel.
-   Validate bind addresses and host configuration.
-   Add request size, header size and connection timeouts.
-   Do not disable TLS certificate validation.
-   Do not permit arbitrary redirects that leak Authorization headers.

Add tests for safe default binding and rejected unsafe configurations.

## P0.4 --- Replace incomplete hand-written HTTP parsing

Inspect `LocalGatewayHttpServer.kt` and its socket/request parsing code.

The current raw socket parser must not be treated as a production-grade
HTTP implementation if it lacks complete HTTP framing and lifecycle
support.

Preferred solution: - Replace the hand-written HTTP parser with a
maintained, compatible HTTP server engine supported by the project's
Kotlin/Android environment; or - Move the gateway runtime to a dedicated
backend service and keep Android as the control client.

Before selecting a library: 1. Inspect current Gradle and Android
compatibility. 2. Check the library's maintenance and license. 3. Avoid
adding unnecessary dependencies. 4. Explain the choice. 5. Update Gradle
dependencies safely.

Preserve existing route/service logic behind interfaces so the migration
does not require rewriting every feature.

Required HTTP behavior: - Correct Content-Length handling. - Correct
chunked transfer handling where supported by the selected engine. -
Header validation. - Request body limits. - Connection timeout and idle
timeout. - Concurrent request handling with bounded resources. - Client
disconnect/cancellation propagation. - Streaming without buffering the
entire response. - Graceful shutdown. - Safe error responses.

Add integration tests using an actual HTTP client for normal requests,
streaming, chunked requests where supported, malformed requests,
oversized bodies, concurrent requests and client disconnects.

## P0.5 --- Remove false API capability claims

Inspect every registered route and UI capability.

The following must not be shown as supported unless implemented
end-to-end:

-   Embeddings
-   Image generation
-   Audio transcription
-   Text-to-speech
-   OpenAI Responses API
-   Anthropic Messages API

If not implemented: - Return a correct, documented unsupported
response. - Mark the feature as Not Supported or Not Configured in the
UI. - Do not return fake success. - Do not silently convert a complex
request into a different request that loses meaning.

Implement capability-aware provider adapters only when the provider
supports the requested operation.

------------------------------------------------------------------------

# 5. PRIORITY 1 --- API CONTRACT AND PROVIDER COMPATIBILITY

## P1.1 --- OpenAI Chat Completions

Implement and test the supported request/response contract.

Support where applicable:

-   `model`
-   `messages`
-   system/developer/user/assistant roles
-   text content
-   supported multimodal content blocks
-   `temperature`
-   `top_p`
-   `max_tokens` or the relevant supported token field
-   `stream`
-   `stop`
-   `tools`
-   `tool_choice`
-   `response_format` where supported
-   usage metadata
-   finish reasons
-   normalized errors

Do not forward unsupported parameters blindly. Use model capability
metadata and provider adapter rules.

## P1.2 --- Tool calling and Coding Agent compatibility

Inspect the full path from public gateway request to provider adapter.

Required behavior:

1.  Parse incoming tool definitions.
2.  Validate tool schemas.
3.  Pass tools and tool choice through the routing layer.
4.  Convert provider tool calls to the public API response format.
5.  Preserve tool-call IDs and arguments.
6.  Accept subsequent tool result messages.
7.  Support multi-turn tool execution.
8.  Track usage and errors.
9.  Enforce configured permissions for any tool executed by the gateway.

Add fixtures and integration tests for tool-call requests and responses.

Do not claim universal Codex compatibility based only on Chat
Completions. Treat each Coding Agent as a separately tested
compatibility target. Use the exact API contract required by that
client.

## P1.3 --- OpenAI Responses API

Implement the real Responses API contract only after reviewing its
official schema and the existing implementation.

At minimum, assess: - Input item structures. - Message and content item
types. - Output item structures. - Tool calls and tool results. -
Response status and IDs. - Usage. - Streaming event sequence. - Error
semantics.

Do not reduce every input to one plain text string.

If the full contract is not yet implemented, mark it partial/unsupported
rather than claiming compatibility.

## P1.4 --- Anthropic Messages API

Implement and test: - System prompt. - Message content blocks. - Text
blocks. - Tool use and tool result blocks. - Model and max token
fields. - Stop sequences. - Usage. - Stop reason. - Streaming event
sequence. - Error mapping.

Do not flatten all content blocks into a string.

## P1.5 --- Provider adapter architecture

Keep provider-specific logic out of UI and gateway route handlers.

Each adapter should expose a consistent contract for: - Connection
validation. - Model discovery where supported. - Capability discovery. -
Request mapping. - Streaming parsing. - Response mapping. - Error
normalization. - Usage extraction.

A new provider must be addable without modifying unrelated gateway
modules.

------------------------------------------------------------------------

# 6. PRIORITY 1 --- ANDROID SERVICE AND LIFECYCLE

Inspect Application, ViewModel and gateway lifecycle.

For Android-hosted Local Gateway:

-   Implement a Foreground Service when continuous user-visible
    operation is required.
-   Add a persistent notification with Gateway status.
-   Add Start, Stop and Restart actions.
-   Use the required Android-version-specific foreground service
    permissions and declarations.
-   Do not request unrelated permissions.
-   Handle process death and service recreation safely.
-   Handle port conflicts.
-   Prevent duplicate server instances.
-   Stop cleanly when the user stops the service.
-   Propagate cancellation and release sockets/resources.
-   Explain battery optimization and Android background restrictions
    honestly.

The service, not a screen ViewModel, should own the long-running gateway
lifecycle.

If a reliable always-on gateway cannot be guaranteed on the device,
clearly state that limitation and recommend Remote Gateway for
continuous use.

------------------------------------------------------------------------

# 7. PRIORITY 1 --- DEVICE AUTHORIZATION AND AGENT USAGE

## Device permissions

Inspect device token authentication.

A device token being valid is not sufficient. The associated device
permissions must be checked for every protected request.

Implement scopes such as: - `CHAT` - `MODELS_READ` - `WORKFLOWS_READ` -
`WORKFLOWS_RUN` - `MCP_READ` - `MCP_EXECUTE` - `ANALYTICS_READ` -
`ADMIN`

Requirements: - Default deny. - Validate scope on every endpoint. -
Apply model allowlists where configured. - Revoke tokens immediately. -
Never trust permissions supplied by the client request. - Audit
permission-denied events without logging secrets.

## Coding Agent token accounting

Inspect daily usage limits and update logic.

Requirements: - Use provider-reported usage when available. -
Distinguish actual, estimated and unknown token usage. - Update counters
atomically. - Handle concurrent requests safely. - Define a consistent
daily reset boundary, preferably UTC unless the user explicitly selects
another timezone. - Enforce limits before a request when possible. -
Reconcile usage after completion. - Do not count missing usage as
zero. - Display the source and accuracy of usage data.

------------------------------------------------------------------------

# 8. PRIORITY 1 --- APP LOCK

Inspect PIN verification and app lock logic.

Requirements: - Do not use a plain unsalted SHA-256 digest as the PIN
password storage mechanism. - Use a salted, deliberately slow password
hashing/KDF construction suitable for local verification and supported
by the Android environment. - Add attempt throttling and timed
lockout. - Avoid leaking whether a particular PIN prefix or account
value is correct. - Clear or mask sensitive UI state when the app
backgrounds if the user enables that behavior. - Biometric unlock may be
added as an optional convenience. - Do not treat biometric UI as a
replacement for secure credential encryption.

Add tests for incorrect PIN, throttling, lockout and successful unlock.

------------------------------------------------------------------------

# 9. PRIORITY 1 --- DATABASE AND BACKUP

Inspect Room database entities, DAOs and current database version.

Requirements: - Enable Room schema export. - Commit schema snapshots. -
Add explicit migrations for every schema version change. - Do not use
destructive migration in production. - Test migration from the existing
database version. - Use transactions for multi-table writes and
restore. - Add indexes and constraints where useful. - Define retention
policies for logs, usage and Playground history. - Preserve existing
user data.

Backup requirements: - Version the backup format. - Encrypt backups with
a strong passphrase-derived key and authenticated encryption. - Use a
suitable, benchmarked KDF configuration. - Never write plaintext
credential payloads to disk or logs. - Validate archive integrity before
restore. - Provide a restore preview and conflict handling. - Restore
transactionally. - On failure, leave the previous database intact. -
Explain that losing the backup password may make the backup
unrecoverable.

Do not claim backup recovery works until export, corruption detection
and restore tests pass.

------------------------------------------------------------------------

# 10. PRIORITY 2 --- MCP AND LOCAL AI

## MCP

Inspect `McpHttpClient.kt` and MCP configuration.

For Android: - Support Streamable HTTP as the primary direct remote
transport. - Treat STDIO as a bridge-based capability where desktop
processes are not available in the Android sandbox. - Do not assume
arbitrary desktop executables exist on Android. - Validate server URL,
TLS, authentication and redirect behavior. - Add tool permission
checks. - Add per-tool timeout and output-size limits. - Audit tool
execution. - Require user approval for dangerous/destructive tools. -
Handle JSON-RPC errors and connection lifecycle correctly.

Never execute arbitrary shell commands from untrusted MCP configuration.

## Local AI

Support configured local endpoints such as: - Ollama - llama.cpp
server - LM Studio - LocalAI - Other OpenAI-compatible local servers

Requirements: - Test connection. - Discover models where supported. -
Validate model capabilities. - Show server status accurately. -
Distinguish LAN, loopback and remote endpoints. - Apply SSRF and
DNS-rebinding protections. - Do not send cloud credentials to local
servers. - Make Local First/Cloud First/Fallback behavior explicit and
user-controlled.

------------------------------------------------------------------------

# 11. PRIORITY 2 --- WORKFLOW ENGINE

Preserve the current workflow implementation and improve it safely.

Required: - Graph validation. - Cycle detection. - Node dependency
resolution. - Input/output schema validation. - Node-level execution
state. - Conditional branches. - Parallel execution with bounded
concurrency. - Merge behavior. - Retry policy. - Timeout. -
Cancellation. - Maximum execution steps. - Execution history. - Safe
error handling. - Workflow versioning.

The current form-based node editor may remain initially. Improve the
graph preview and node configuration before implementing a complex
drag-and-drop canvas.

Never execute arbitrary user-supplied code as a workflow node in this
release.

------------------------------------------------------------------------

# 12. PRIORITY 2 --- ANALYTICS CORRECTNESS

Inspect metrics storage and chart calculations.

Requirements: - Separate request count, token count, latency and cost. -
Store provider-reported token usage when available. - Mark estimated or
unknown usage clearly. - Keep actual billed cost separate from estimated
cost. - Store pricing source and last-updated timestamp. - Show Unknown
when reliable pricing is unavailable. - Use correct chart labels and
axes. - Implement time-bucket aggregation for requests over time. -
Support date, provider, model, device and status filters. - Avoid
storing prompt/response content by default. - Keep analytics queries
bounded and indexed.

No fake statistics or hard-coded pricing dates.

------------------------------------------------------------------------

# 13. UI/UX REQUIREMENTS --- PRESERVE THE SIMPLE DESIGN

Do not redesign the application unnecessarily.

Preserve: - Existing JH GetWay identity. - Simple professional
mobile-first UI. - Bengali and English. - Light, Dark and System
themes. - Existing useful navigation. - Material 3 components.

Improve: - Clear distinction between Local and Remote Gateway. - Real
connection status and last checked time. - Consistent provider/model
status badges. - Search and filters for long lists. - Loading, empty,
success, offline and error states. - Form validation and actionable
errors. - Confirmation for destructive actions. - Small-screen layout
and text wrapping. - Accessibility labels and touch target sizes. -
Localization of all user-facing strings. - No hard-coded English UI text
where a localized resource should be used.

Language requirements: - Bengali. - English. - System Default if
currently supported.

Theme requirements: - Light. - Dark. - System Default.

User preference must persist after app restart. User-generated prompts,
provider names, model IDs and API responses must not be automatically
translated.

------------------------------------------------------------------------

# 14. BUILD, PACKAGE AND RELEASE ENGINEERING

Inspect the current Gradle project before making changes.

Required: - Add a complete Gradle Wrapper if missing, using a compatible
Gradle version. - Do not arbitrarily upgrade Kotlin, AGP, Compose or
dependencies. - Verify dependency compatibility before upgrading. -
Replace generic README instructions with JH GetWay documentation. -
Remove stale environment-variable instructions if the code does not use
them. - Select the final JH Soft Corporation application ID only after
checking the existing package and migration implications. - Do not
casually rename packages or application IDs. - Configure release signing
through secure environment/secret configuration. - Never commit signing
keys or passwords. - Add release build instructions. - Add dependency
license inventory. - Add privacy and security documentation. - Configure
R8 only after testing and adding correct keep rules. - Ensure debug and
release configurations do not leak secrets.

Do not invent a production server URL, signing key, API key or database
credential.

------------------------------------------------------------------------

# 15. TESTING REQUIREMENTS

Add or update tests for every changed area.

## Unit tests

-   Encryption/decryption.
-   Credential failure behavior.
-   PIN hashing and lockout.
-   Model resolution.
-   Router selection.
-   Fallback policy.
-   Usage accounting.
-   Device scope enforcement.
-   Workflow validation and execution.
-   MCP permission checks.
-   Analytics aggregation.

## Integration tests

-   Local gateway lifecycle.
-   Remote health check.
-   Provider request mapping.
-   Streaming.
-   Tool calling.
-   Database migration.
-   Backup/restore.
-   Device revocation.
-   MCP HTTP connection.

## API contract tests

-   OpenAI Chat Completions.
-   OpenAI Responses, only when implemented.
-   Anthropic Messages, only when implemented.
-   Streaming event sequences.
-   Error schemas.
-   Tool calls and tool results.

## Security tests

-   Invalid/revoked token.
-   Missing scope.
-   Credential leakage.
-   Unsafe host/redirect.
-   SSRF attempts.
-   Oversized request.
-   Keystore unavailable.
-   Corrupted backup.
-   MCP permission bypass.
-   Rate-limit bypass.

## UI tests

-   Bengali/English switching.
-   Light/Dark/System theme.
-   Dashboard empty and populated states.
-   Provider add/edit/test.
-   Gateway start/stop.
-   Form validation.
-   Small-screen layout.
-   Error and loading states.

------------------------------------------------------------------------

# 16. DEFINITION OF DONE

A task is complete only when:

1.  The actual source code implements it.
2.  The UI accurately represents its state.
3.  Inputs are validated.
4.  Errors are handled safely.
5.  Sensitive data is protected.
6.  Relevant tests are added or updated.
7.  Tests have been run where the environment allows.
8.  No unrelated feature has regressed.
9.  Documentation is updated.
10. Remaining limitations are explicitly stated.

A button, screen or label alone does not make a feature complete.

------------------------------------------------------------------------

# 17. EXECUTION PHASES

Perform these phases in order. Do not jump to new UI features while P0
issues remain.

## Phase A --- Baseline and P0 Stabilization

-   Repository and build baseline.
-   Credential encryption fail-closed.
-   Gateway state correctness.
-   Secure local bind defaults.
-   Replace incomplete HTTP parsing.
-   Correct unsupported endpoint behavior.

## Phase B --- Core API Reliability

-   OpenAI Chat Completions contract.
-   Streaming correctness.
-   Tool calling.
-   Provider adapter normalization.
-   Responses API assessment/implementation.
-   Anthropic Messages assessment/implementation.

## Phase C --- Android Lifecycle and Access Control

-   Foreground Service.
-   Gateway lifecycle.
-   Device permission enforcement.
-   Agent token usage accounting.
-   App lock security.

## Phase D --- Data Safety

-   Room schema export.
-   Migrations.
-   Backup versioning.
-   Transactional restore.
-   Retention policies.

## Phase E --- Advanced Integrations

-   Coding Agent compatibility.
-   Local AI.
-   MCP HTTP and bridge.
-   Workflow execution hardening.
-   Analytics correctness.

## Phase F --- UI Polish and Release

-   Localization audit.
-   Theme audit.
-   Accessibility.
-   Error/empty/loading states.
-   Build and release configuration.
-   Full test report.
-   Documentation.

------------------------------------------------------------------------

# 18. REQUIRED RESPONSE AFTER EACH PHASE

After completing each phase, provide:

## Phase result

-   Completed / Partially completed / Blocked.

## Files changed

List exact file paths and what changed.

## Tests

-   Test command.
-   Actual result.
-   Tests not run and why.

## Remaining issues

List only verified outstanding issues.

## Regression check

State which existing features were checked and whether they still work.

Do not claim success without evidence.

------------------------------------------------------------------------

# 19. FIRST ACTION --- START NOW

Do not generate a new project and do not begin by changing the UI.

Start by inspecting the currently open JH GetWay repository. Verify the
critical findings against the current code, establish the build/test
baseline, then implement **Phase A --- P0 Stabilization**.

Work directly in the existing files. Preserve all working functionality.
Make the smallest safe changes, add tests, and report exact results.

If a required tool, dependency, network permission or Android capability
is unavailable, do not fabricate an implementation. Explain the blocker
and complete the safe work that can be done.

**Final objective:** A reliable, secure, testable JH GetWay
application---not merely a visually complete demo.

------------------------------------------------------------------------

**JH GetWay**\
**JH Soft Corporation**\
**Copyright © 2026 JH Soft Corporation. All Rights Reserved.**
