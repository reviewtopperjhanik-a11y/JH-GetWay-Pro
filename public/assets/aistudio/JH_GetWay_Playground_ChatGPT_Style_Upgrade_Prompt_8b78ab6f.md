# JH GetWay --- ChatGPT-Style Playground Upgrade Prompt

## Existing Android Project · UI/UX + Functional Implementation

**Product:** JH GetWay\
**Organization:** JH Soft Corporation\
**Target:** The currently open, existing JH GetWay Android/Kotlin
project in Google AI Studio\
**Scope:** Playground screen redesign and real
attachment/model-selection functionality\
**Reference:** User-provided Playground screenshot from 14 November
2026\
**Priority:** High\
**Rule:** Modify the existing project incrementally. Do not recreate the
app.

------------------------------------------------------------------------

# 1. OBJECTIVE

Upgrade the existing JH GetWay Playground into a clean, modern,
ChatGPT-inspired mobile chat experience.

The current Playground places provider chips (Auto Router, Groq, etc.)
and model chips in rows near the top of the screen. This consumes
valuable conversation space and does not provide the expected
chat-composer experience.

Change the layout so that:

-   The conversation occupies nearly all available vertical space.
-   Provider and model selection are moved into the bottom composer
    area.
-   A Plus (+) button opens an attachment/action menu.
-   The menu supports Camera, Photos/Gallery and Files.
-   A compact model selector sits beside the composer controls.
-   `Auto` is the default model selection.
-   Auto mode routes only through models/providers explicitly verified
    as available on a free tier.
-   The user can also choose an individual available model manually.
-   Manual model selection must use exactly that selected model/provider
    and must not silently switch to a different provider.
-   The existing Chat, streaming, compare, sessions, copy, regenerate,
    parameters and history behavior must be preserved.

This is a real functional upgrade, not a static UI mockup.

# 2. IMPORTANT SOURCE-CODE CONTEXT

The current archive contains these relevant files. Inspect the current
workspace versions before changing anything:

-   `app/src/main/java/com/example/ui/screens/PlaygroundScreen.kt`
-   `app/src/main/java/com/example/ui/GatewayViewModel.kt`
-   `app/src/main/java/com/example/gateway/SmartRouterEngine.kt`
-   `app/src/main/java/com/example/network/ProviderHttpClient.kt`
-   `app/src/main/java/com/example/network/ProviderPresets.kt`
-   `app/src/main/java/com/example/network/adapters/OpenAiProviderAdapter.kt`
-   `app/src/main/java/com/example/network/adapters/AnthropicProviderAdapter.kt`
-   `app/src/main/java/com/example/network/adapters/GeminiCohereOllamaAdapters.kt`
-   `app/src/main/java/com/example/data/local/Entities.kt`
-   `app/src/main/java/com/example/data/local/Daos.kt`
-   `app/src/main/java/com/example/data/local/JhGetWayDatabase.kt`
-   `app/src/main/java/com/example/core/i18n/AppStrings.kt`
-   `app/src/main/java/com/example/core/i18n/StringsEn.kt`
-   `app/src/main/java/com/example/core/i18n/StringsBn.kt`
-   `app/src/main/AndroidManifest.xml`
-   `app/build.gradle.kts`
-   Version catalog files under `gradle/`

Current Playground behavior observed in source: - Provider selection
uses `selectedProviderId`. - `selectedProviderId == null` represents the
current automatic routing selection. - Model selection uses
`selectedModelId`. - Provider and model selectors are rendered above the
conversation. - `sendPlaygroundPrompt()` receives prompt, model ID/alias
and explicit provider ID. - `SmartRouterEngine` performs provider
candidate selection and fallback. - `ModelEntity` stores model ID,
provider ID, enabled status, capabilities, streaming/tool support,
input/output price fields and pricing update timestamp. -
`ChatMessagePayload` currently uses text content. It must be reviewed
before implementing image/file attachments. - Existing language and
theme infrastructure must be reused.

Do not assume this list is still identical to the current workspace.
Verify before editing.

------------------------------------------------------------------------

# 3. TARGET PLAYGROUND SCREEN

## 3.1 Overall layout

Use this vertical structure:

``` text
┌──────────────────────────────────────┐
│ ☰  Playground                ● Status│
├──────────────────────────────────────┤
│                                      │
│              CHAT AREA               │
│                                      │
│   User message                       │
│                                      │
│                 Assistant response   │
│                                      │
│   User message                       │
│                                      │
│                 Streaming response   │
│                                      │
│                                      │
├──────────────────────────────────────┤
│  [Attachment preview chips, if any]  │
│                                      │
│  [ + ] [ Message input         ] [➤] │
│       [Auto ▾] [Optional controls]   │
└──────────────────────────────────────┘
│ Home | Providers | Playground | ... │
└──────────────────────────────────────┘
```

The actual mobile layout may use a two-row composer to keep controls
comfortable on narrow screens:

**Composer row 1** - Plus button. - Expanding message input. - Send/Stop
button.

**Composer row 2** - Model selector chip (default: Auto). - Optional
attachment count/status. - Optional parameter/settings shortcut.

The model selector must remain visually attached to the composer, not
return to the top of the chat.

## 3.2 Header

Keep a compact header: - Navigation drawer/menu. - `Playground` title. -
Real Gateway status indicator.

Do not place Provider or Model selection in the header.

## 3.3 Conversation area

-   Fill all remaining vertical space.
-   Scroll to the newest message when a response arrives.
-   Preserve user/assistant message distinction.
-   Use readable message bubbles or clean ChatGPT-style message blocks.
-   Avoid wrapping every assistant response in a large decorative card.
-   Support long text, code blocks, links and selectable/copyable
    content where existing rendering supports it.
-   Show a small assistant identity/model label only where useful.
-   Show error states in a clear, non-destructive way.
-   Show streaming text incrementally.
-   Provide copy and regenerate actions for assistant responses.
-   Keep session history accessible without taking permanent space from
    the chat.
-   Ensure keyboard appearance does not hide the composer or latest
    message.

## 3.4 Remove the old top selectors

Remove the current top-of-screen Provider LazyRow and Model LazyRow from
normal Chat mode.

Do not delete Provider/Model management screens. Only move Playground
selection into the composer.

Compare Models mode may keep its own model selection area because it has
a different purpose. Keep the Compare mode entry point accessible
without crowding the normal chat.

------------------------------------------------------------------------

# 4. PLUS (+) ATTACHMENT MENU

The Plus button must open a compact Material 3 bottom sheet or anchored
menu.

Menu items, with icons and localized labels:

1.  Camera
2.  Photos / Gallery
3.  Files
4.  (Optional later) Create Image
5.  (Optional later) Connect Tool / MCP action

For this release, implement Camera, Photos and Files. Do not show an
item as working if it is not implemented.

## 4.1 Camera

-   Use Android Activity Result APIs.
-   Prefer a secure FileProvider-backed full-resolution capture flow.
-   Add only the necessary manifest provider declaration and paths XML.
-   Store captured files in app-private cache/files storage.
-   Do not expose broad filesystem access.
-   Handle camera unavailable, user cancellation and capture failure.
-   Delete temporary files when no longer referenced.
-   Do not request camera permission at app startup. Request it only
    when needed if the selected capture API requires it.
-   Never claim a camera image was attached until the returned file/URI
    is valid.

If a reliable secure camera flow cannot be completed in the current
project, implement the system camera picker safely and clearly explain
its limitations.

## 4.2 Photos / Gallery

-   Use Android Photo Picker / `ActivityResultContracts.PickVisualMedia`
    or a compatible multiple-image picker.
-   Prefer system picker permissions over broad storage permission.
-   Allow one or multiple images within a defined limit.
-   Display selected image thumbnails and filenames.
-   Allow removing an attachment before sending.
-   Handle cancellation and revoked URI access.
-   Persist URI access or copy selected content into app-private storage
    as appropriate.

## 4.3 Files

-   Use `ActivityResultContracts.OpenDocument` or a compatible
    multiple-document picker.
-   Support a documented allowlist such as:
    -   PDF
    -   TXT
    -   MD
    -   CSV
    -   JSON
    -   DOCX only if actual parsing support exists
    -   Common image formats
-   Do not pretend that arbitrary file formats are understood.
-   Show filename, type and file size.
-   Enforce file-count and file-size limits.
-   Reject unsupported file types with a localized explanation.
-   Handle URI permission and missing-file errors.
-   Copy content to app-private temporary storage if needed for
    processing.

## 4.4 Attachment preview

Before sending, show a compact preview strip above the composer: - Image
thumbnail for images. - File icon for documents. - Filename. - File
size. - Remove button.

Allow the user to add more than one attachment within safe limits.

Do not clear the selected attachments if the user edits the message.
Clear them only after the request is accepted for sending or the user
explicitly removes them.

------------------------------------------------------------------------

# 5. REAL MULTIMODAL REQUEST SUPPORT

The attachment menu must not be UI-only.

Inspect the current message data model and provider adapter
architecture.

The existing text-only message payload must be extended carefully to
represent typed content parts, for example:

-   Text part
-   Image part
-   Document/file part where supported

Design a normalized internal message representation that can be mapped
by each provider adapter.

## Provider capability rules

-   OpenAI-compatible providers may support image content depending on
    model and endpoint.
-   Gemini may support image and file content depending on model/API.
-   Anthropic may support image content depending on model/API.
-   PDF/file support differs by provider and endpoint.
-   Do not assume every model supports every attachment type.

Before sending: 1. Resolve the selected provider/model. 2. Inspect its
declared capabilities. 3. Validate every attachment type against those
capabilities. 4. If unsupported, show a clear message and do not
silently drop the attachment. 5. Map content into the provider's real
request schema. 6. Preserve text and attachment order. 7. Keep streaming
behavior correct.

## Storage and privacy

-   Do not persist large binary data in Room message rows.
-   Store attachment metadata and app-private file references only if
    chat history is enabled and the user has allowed it.
-   When chat history is disabled, keep attachment data ephemeral and
    clean it after request completion or cancellation.
-   Apply a retention/cleanup policy.
-   Do not upload files to a third-party service except the selected AI
    provider required for the request.
-   Clearly disclose when a file is sent to a cloud provider.
-   Do not log file contents or base64 payloads.
-   Enforce MIME type, file size and total request size limits.

If full binary attachment support cannot be implemented for a provider,
do not simulate it. Show the exact supported formats and providers.

------------------------------------------------------------------------

# 6. MODEL SELECTOR BESIDE THE COMPOSER

The model selector is a compact dropdown/bottom sheet opened from a chip
beside the composer.

Default selected value:

**Auto**

Example:

``` text
[ + ] [ Message...                    ] [➤]
      [ ✨ Auto ▾ ] [Parameters]
```

When opened, show:

## Section A --- Auto

-   `Auto (Free Models)`
-   Explain: "Automatically chooses an eligible free-tier model and
    tries the next eligible model only when a retryable failure occurs."
-   Show how many verified free models are currently eligible.
-   If no eligible free model exists, show a clear empty state and a
    shortcut to Providers.
-   Never silently route to a paid or unknown-cost model.

## Section B --- Individual models

Group available models by Provider.

Example:

``` text
AUTO
  Auto — Free Models

GOOGLE GEMINI
  Gemini model A     Free tier
  Gemini model B     Paid / not free

GROQ
  Model C            Free tier
  Model D            Free tier

OPENROUTER
  Model E            Free tier
  Model F            Paid / not free

LOCAL AI
  Local model A      Local
```

The example names above are layout examples only. Populate the list from
actual configured providers and model catalog data. Never invent a model
or claim availability without verification.

Each item should show: - Model display name. - Provider name. -
Free-tier/paid/unknown status. - Capability indicators where useful
(Vision, Tools, Streaming). - Availability/health status if known. -
Selected state.

Add search/filter when the model list is long.

## Manual selection behavior

When the user selects an individual model: - Store the exact model ID
and provider ID. - Set the explicit provider selection. - Send only to
that provider/model. - Do not automatically switch to another
provider. - If it fails, show the error unless the user has explicitly
enabled manual-model fallback. - Do not silently substitute another
model.

When the user selects Auto: - Clear the explicit provider ID. - Use a
dedicated Auto-Free routing request/policy. - Do not leave a previously
selected model ID active in a way that forces a single model. - Show the
actual provider/model selected for each response in message details.

------------------------------------------------------------------------

# 7. FREE MODEL ELIGIBILITY --- IMPORTANT

The user specifically wants models that are available to use for free.

Do not confuse these concepts: - A model with zero listed API price. - A
provider offering a limited free API quota. - A free trial or
promotional credit. - A local model with no provider API charge. - A
model whose pricing is unknown.

Implement explicit model access metadata.

Recommended fields or equivalent metadata: - `accessTier`: `FREE_TIER`,
`PAID`, `LOCAL`, `UNKNOWN` - `freeTierVerified`: Boolean -
`freeTierSource`: provider documentation/manual verification/catalog
source - `freeTierVerifiedAt`: timestamp/date - `freeTierLimitsNote`:
optional rate/quota note - `supportsChat` - `supportsVision` -
`supportsTools` - `supportsStreaming`

Use a Room migration if schema changes are required. Preserve existing
data.

Rules: 1. `UNKNOWN` must never be treated as free. 2. Missing pricing is
not proof of free access. 3. Zero cost metadata alone does not prove a
provider account has free quota. 4. Provider free-tier eligibility
should be verified from provider/catalog data or explicitly confirmed by
the user. 5. Show the verification date/source where possible. 6. Local
models may be shown in a separate Local section and should not be
described as cloud free-tier models. 7. A provider can impose request,
token or daily limits even on free models. Display known limits and
handle 429 responses.

For providers whose free tier is account-dependent, let the user mark a
model as "Free access confirmed for my account" after
testing/confirmation. Clearly label this as user-confirmed, not
universally free.

Do not hard-code a stale list of free models as permanent truth.

------------------------------------------------------------------------

# 8. AUTO FREE ROUTING --- ACTUAL ROUTER BEHAVIOR

The current `SmartRouterEngine` must be inspected and extended. Do not
implement Auto by changing only a UI label.

Create a dedicated routing mode/policy such as `AUTO_FREE`.

Required behavior:

1.  Build a candidate list from enabled providers and enabled models.
2.  Exclude disabled providers/models.
3.  Exclude providers without a usable credential.
4.  Exclude `PAID` and `UNKNOWN` models.
5.  Include only verified free-tier models and eligible local models
    according to the selected Auto policy.
6.  Exclude models that do not support the requested task/capabilities.
7.  Exclude unhealthy/cooling-down providers where reliable health data
    exists.
8.  Rank eligible candidates by a documented policy, such as health,
    latency, priority and recent failure rate.
9.  Select one candidate for the request.
10. On a retryable failure before a response stream has started, try the
    next eligible candidate.
11. Do not send the same prompt to all models in parallel.
12. Do not duplicate a non-idempotent request.
13. Do not retry after streaming output has already been delivered to
    the user.
14. Stop after the configured retry budget.
15. Show the final selected provider/model and whether fallback
    occurred.

Auto must never use a paid or unknown-cost model as an automatic
fallback.

If there are no verified free candidates: - Do not fall back to paid
models. - Show "No verified free models available." - Provide a button
to review Providers/Models. - Let the user manually select a paid model
if they choose.

Add unit tests for: - Only verified free models are selected. - Paid and
unknown models are excluded. - Disabled models are excluded. - Missing
API key is excluded. - Unsupported capability is excluded. - First
candidate failure triggers eligible fallback. - Paid fallback never
occurs. - Streaming started prevents duplicate fallback. - No eligible
free model produces a clear failure.

------------------------------------------------------------------------

# 9. CHAT COMPOSER INTERACTION

The composer must feel like a modern AI chat app.

Required: - Rounded composer container. - Plus button at the leading
side. - Multiline text field. - Send button. - Stop button while
streaming. - Model selector chip close to the composer. - Optional
parameters shortcut. - Attachment preview strip. - Keyboard-aware
positioning. - Proper IME send behavior. - Disabled state while request
is being submitted. - Clear error when sending an empty message. -
Preserve draft text when an attachment picker is opened/cancelled.

The send button should change to Stop during active streaming if the
existing ViewModel supports safe cancellation. If it does not, add a
proper cancellation method.

Do not create a second parallel send implementation. Reuse the existing
ViewModel/router path.

------------------------------------------------------------------------

# 10. CHATGPT-STYLE VISUAL DIRECTION

Use the user's screenshot as the layout reference, not as a request to
copy ChatGPT branding.

JH GetWay must keep its own brand identity.

Design: - Minimal, clean, calm interface. - White/light neutral surfaces
in Light Mode. - Dark charcoal/navy surfaces in Dark Mode. - Existing JH
GetWay accent color. - Subtle borders and restrained elevation. -
Consistent spacing. - Rounded composer. - Clear icons and touch
targets. - No unnecessary provider chips at the top. - No oversized
decorative cards around every message. - No excessive gradients or
glow. - Do not use ChatGPT logos or imply the app is an official OpenAI
product.

Preserve: - Bengali. - English. - Light. - Dark. - System theme.

All new UI strings must be added to both `StringsBn.kt` and
`StringsEn.kt` (and the central string interface if required). Do not
hard-code new user-facing strings in the Composable.

------------------------------------------------------------------------

# 11. ACCESSIBILITY AND MOBILE LAYOUT

Test on narrow Android phones.

Requirements: - Composer fits on a narrow display. - Model chip text
truncates safely. - Long model IDs wrap or scroll without pushing the
composer off-screen. - Attachment preview can scroll horizontally. -
Bottom sheet does not exceed screen height. - Keyboard does not cover
the send button. - Screen reader labels for Plus, Camera, Gallery,
Files, Model selector, Send and Stop. - Touch targets should be
comfortably usable. - Light and Dark contrast must be adequate. - No
horizontal overflow.

------------------------------------------------------------------------

# 12. DATA AND MIGRATION SAFETY

If adding model access-tier metadata or attachment metadata:

-   Update Room entities.
-   Add a forward Room migration from the actual current database
    version.
-   Update schema exports.
-   Preserve existing provider/model/chat/session data.
-   Do not use destructive migration.
-   Add migration tests.
-   Avoid storing binary attachment content in Room.
-   Store only metadata and secure app-private references as
    appropriate.
-   Respect the existing chat-history privacy setting.

Do not change application ID or package name as part of this Playground
task.

------------------------------------------------------------------------

# 13. TESTING CHECKLIST

## UI tests

-   Provider/model selector no longer appears at top of normal Chat
    screen.
-   Auto is the default model selection.
-   Model selector appears beside/below composer.
-   Plus menu opens and closes.
-   Camera picker launches/cancels safely.
-   Gallery picker selects and removes images.
-   File picker selects and removes supported files.
-   Attachment previews render correctly.
-   Unsupported file types are rejected.
-   Bengali and English labels render.
-   Light and Dark themes render.
-   Keyboard does not hide composer.
-   Compare Models still works.

## Router tests

-   Auto Free mode only selects verified free models.
-   Manual model selection pins provider and model.
-   Manual selection does not silently fall back.
-   Auto fallback uses only eligible free models.
-   No free candidates produces a clear error.
-   Rate limit and timeout handling obey retry policy.
-   No retry occurs after stream output has begun.

## Provider tests

-   Text-only chat remains working.
-   Image content maps correctly for supported models.
-   File content maps correctly only for supported providers.
-   Unsupported attachments produce clear errors.
-   Streaming text remains incremental.
-   Usage metadata is still recorded.

## Regression tests

-   New chat.
-   Session switching.
-   Clear chat.
-   Save history enabled/disabled.
-   Copy response.
-   Regenerate response.
-   Compare Models.
-   Parameters.
-   Provider selection.
-   Local AI selection.
-   Existing routing/fallback.
-   Existing analytics.

Do not claim tests passed unless they were executed.

------------------------------------------------------------------------

# 14. IMPLEMENTATION ORDER

Implement in this exact order:

### Phase 1 --- Playground layout

-   Remove top provider/model rows from Chat mode.
-   Keep chat area full-height.
-   Add bottom composer layout.
-   Add Plus button and model selector UI.
-   Preserve existing chat/send/streaming behavior.
-   Add Bengali/English strings.

### Phase 2 --- Model selection behavior

-   Implement Auto and manual model selector.
-   Show provider-grouped models.
-   Add free-tier metadata and safe filtering.
-   Add Room migration if needed.
-   Add Auto-Free router policy and tests.

### Phase 3 --- Attachments

-   Implement Photos/Gallery picker.
-   Implement Files picker.
-   Implement secure Camera capture.
-   Add preview/remove controls.
-   Add size/type validation.

### Phase 4 --- Multimodal backend

-   Extend normalized message representation.
-   Update provider adapters for supported image/file inputs.
-   Persist attachment metadata safely when history is enabled.
-   Add capability validation and tests.

### Phase 5 --- Final QA

-   Test all screens, languages and themes.
-   Test on a narrow Android phone.
-   Verify existing features have not regressed.
-   Report exact files changed and tests actually run.

Do not implement all phases as one giant rewrite. Complete and verify
each phase before moving forward.

------------------------------------------------------------------------

# 15. REQUIRED FINAL REPORT

After implementation, report:

1.  What changed in Playground layout.
2.  Which attachment types actually work.
3.  Which providers/models actually support image/file input.
4.  How free-tier eligibility is verified.
5.  How Auto-Free routing selects and falls back.
6.  Whether manual model selection is pinned correctly.
7.  Exact files changed.
8.  Room migration version, if changed.
9.  Tests executed and actual results.
10. Remaining limitations.

Clearly label any incomplete feature as Partial or Not Supported.

------------------------------------------------------------------------

# 16. START NOW

Inspect the existing JH GetWay source code and implement Phase 1 first.

Do not merely describe the UI. Modify the actual existing Compose
screen.

Preserve all working functionality. After Phase 1 is implemented and
tested, continue with Phase 2, then attachments and multimodal support.

**Final desired experience:**

A user opens Playground, sees a clean conversation, taps Plus to attach
a camera image/photo/file, chooses `Auto` or a specific available model
beside the composer, writes a prompt and sends it. Auto uses only
verified free-tier eligible models and performs safe sequential fallback
when possible. Manual selection uses only the selected model/provider.
The user sees the actual model used for the response.

**JH GetWay --- One Gateway. Complete AI Infrastructure.**

**JH Soft Corporation --- Copyright © 2026. All Rights Reserved.**
