# JH GetWay — OpenCode Smart Model Selector & Auto Routing
## AI Studio Implementation Specification

**Project:** JH GetWay  
**Target:** OpenCode integration  
**Priority:** High  
**Task:** Correct the existing model catalogue, selection, and routing behavior without disturbing saved user data.

## 1. Current Problem and Root Objective

After connecting JH GetWay to OpenCode, the OpenCode model selector displays an enormous unfiltered catalogue—approximately 50 providers and 500+ models in one long list. The intended Smart Model Selection experience is not working:

- There is no usable **Auto** option.
- The model selected as a provider's default during provider/API setup is not reliably surfaced or prioritized.
- If the selected model is deleted, disabled, rate-limited, unavailable, or its provider fails, the next eligible model is not reliably selected.
- Free and coding-capable models are not being meaningfully filtered or prioritized.

The objective is not simply to hide rows in a UI. The model catalogue exposed by the Gateway API and the actual request-routing implementation must agree. Every selectable entry must resolve to a valid upstream route.

## 2. Required OpenCode Model Selector

Provide a concise, curated selector with these logical choices:

1. **Auto** — Gateway chooses an eligible model for each request according to routing preferences, capabilities, and availability.
2. **Default Model** — uses the model explicitly selected as the default during provider/API configuration.
3. **Best Coding** — chooses the best eligible coding-capable model based on capability metadata and current availability.
4. **Free Models** — shows only models whose free status is verified or explicitly configured.
5. **Recommended Models** — a short list of eligible models, grouped by provider where useful.

Do not show every model from every connected provider in one undifferentiated list by default. Do not delete hidden models from storage; this is catalogue filtering, not data deletion.

## 3. Auto Must Be a Real Routing Mode

Auto must be functional, not a decorative label or a fixed alias.

When OpenCode sends a request using Auto, the Gateway must resolve that logical selection to a real provider/model route. The routing decision should consider:

- The saved default model, when eligible and suitable.
- Request intent, especially coding-oriented requests.
- Required capabilities: tool/function calling, structured output, streaming, image input, and context length where relevant.
- Provider/model enabled state and current availability.
- Recent failures, rate limits, and cooldown state.
- User routing preferences, including any free-only restriction.

If request intent is unclear, prefer the saved default if eligible; otherwise choose the highest-ranked generally capable eligible model. Never choose randomly or simply use the first database record.

Record the selected provider/model and routing reason in safe diagnostics, without logging secrets.

## 4. Preserve and Honor the Configured Default Model

Inspect how the existing application stores the model selected as a provider's default. Reuse that canonical source of truth.

- Preserve the existing provider-level default selection.
- Make it available through the OpenCode integration using a valid model mapping.
- **Default Model** must route to the configured default.
- Auto should prefer the configured default when it is eligible and appropriate.
- If no provider-level default exists, use an existing global default if available; otherwise apply the defined ranking rules.
- If the saved default is deleted, disabled, invalid, or unavailable, resolve a valid fallback instead of routing to a stale model ID.
- Do not introduce a second conflicting default setting.

## 5. Best Coding Selection

Implement capability-based coding model selection using existing metadata wherever possible. Relevant signals include coding capability, tool/function calling, context length, structured output, streaming, enabled state, recent success/failure, configured cost/free status, and user preferences.

If provider metadata is incomplete, use a maintainable capability map with clear provenance. Do not rely only on fragile model-name substring matching. Do not claim a model is coding-capable without evidence in metadata or a defined capability record.

If no eligible coding model exists, show a clear unavailable state or use a documented general-purpose fallback. Never silently select an incompatible model.

## 6. Free Model Filtering

Create a trustworthy Free Models filter.

- Use explicit pricing/free-tier metadata already available in the app or provider data.
- Distinguish **Free**, **Paid**, and **Unknown/Unverified** wherever the data model permits.
- Unknown pricing must never be treated as free.
- Exclude deleted, disabled, invalid, or unrouteable models.
- If the user explicitly chooses free-only routing, Auto and fallback must remain within verified free candidates.
- If no eligible free model is available, report that clearly. Never silently switch to a paid model when free-only mode is active.
- Do not make a temporary hard-coded list the sole source of truth.

## 7. Curate the Catalogue; Do Not Expose 500+ Entries by Default

The Gateway's OpenCode-facing model listing must be curated:

- Include Auto, Default Model, Best Coding, and Free Models using a protocol-compatible implementation.
- Include a limited set of eligible recommended models.
- Group real models by provider where supported.
- Exclude deleted, disabled, malformed, unsupported, duplicate, or unconfigured models.
- Exclude models whose provider credentials are missing or whose route cannot be resolved.
- Provide a deliberate route to additional eligible models only if compatible with the existing integration.
- Preserve all provider and model records in the database.

Do not arbitrarily delete, overwrite, or disable user models merely to shorten the selector.

## 8. Fallback and Automatic Recovery

Fallback must handle more than deleted model records. Consider recoverable conditions such as model/provider disabled, missing or invalid credentials, rate limits/quota exhaustion, temporary upstream outages, network errors, and unsupported capabilities.

Required behavior:

1. Exclude a failed candidate from the current request's retry chain.
2. Choose the next eligible candidate according to the active selection mode and preferences.
3. Preserve the original request, parameters, tools, and response contract.
4. Use bounded retries and prevent loops.
5. Do not retry malformed requests or other non-recoverable errors merely by switching models.
6. Never switch from free to paid when free-only mode is active.
7. Do not switch after streaming content has already been sent unless a safe protocol-supported recovery mechanism exists. Prevent duplicated or corrupted output.
8. If all candidates fail, return a clear error; never report false success.

Use a short cooldown/circuit-breaker for repeatedly failing provider/model combinations where appropriate.

## 9. OpenCode Protocol and Model-ID Compatibility

Inspect the actual OpenCode integration and protocol before implementation. Trace:

- The model-list endpoint OpenCode calls (for example `/v1/models` only if that is the endpoint this project actually exposes).
- The model ID OpenCode sends in requests.
- How the Gateway resolves that ID to provider, credentials, base URL, and upstream model ID.
- Whether OpenCode accepts virtual/logical model IDs such as `auto`, and how it displays them.
- How streaming, tool calls, and model metadata are handled.

Do not assume OpenCode automatically understands a custom `auto` ID. If virtual IDs are supported, map them inside the Gateway to real upstream routes. If not, implement another protocol-compatible mechanism. Never send a Gateway-only virtual ID directly to an upstream provider.

The model-list response and request handler must use the same mapping. Every displayed model ID must resolve correctly. Existing real model IDs must continue working.

## 10. Preserve All Existing User Data and Workflows

This is a targeted correction, not a reset or rewrite.

Preserve all existing providers, API credentials, encrypted tokens, model records, provider/model relationships, configured defaults, Coding Agent configurations, Gateway settings, chats/history, analytics, and other user data.

- Do not clear or recreate the database.
- Do not ask the user to reinstall the app.
- Do not ask the user to re-add providers or API keys.
- Do not perform destructive migrations.
- If a schema migration is genuinely necessary, make it backward-compatible, non-destructive, and covered by migration tests.
- Keep existing provider add/edit/delete/test workflows functional.
- Hiding a model from OpenCode must never delete its saved record.

## 11. Routing Preferences and Settings

Extend existing Smart Router or routing preferences rather than creating duplicate settings.

Where supported, allow the user to configure:

- Selection mode: Auto, Default Model, Best Coding, or Free Models.
- Whether Auto may use paid models.
- Whether the configured default should be preferred.
- Existing provider/model priority preferences.

Preserve user intent. Do not enable paid routing by default unexpectedly. Preference changes should take effect without re-entering onboarding, re-adding credentials, or restarting the entire setup flow. Use session-level behavior only if persistence is not supported, and make that scope clear.

## 12. Selector UX and Error States

Use clear, concise labels and descriptions:

- **Auto** — Smart routing by JH GetWay.
- **Default Model** — Your configured default.
- **Best Coding** — Best eligible coding model.
- **Free Models** — Verified free models.
- **Provider / Model** — Clear provider and model identity.

Handle no providers, no eligible models, no verified free models, unavailable/deleted defaults, unavailable coding models, provider authentication errors, quota/rate-limit failures, and exhausted fallback candidates.

Do not show duplicate provider headings, repeated filters, confusing internal IDs, or misleading availability badges. Do not show 500+ models in one long default list.

## 13. Diagnostics and Analytics

Reuse the existing Analytics/Activity Logs architecture where practical. For each request, record non-secret routing information:

- Requested logical/model ID and active mode.
- Selected provider and upstream model ID.
- Whether the configured default was used.
- Whether fallback occurred and its reason/category.
- Attempt count, final status, and latency where available.

Never log plaintext API keys, authorization headers, decrypted tokens, or sensitive request content. Diagnostics should explain why Auto chose a model without exposing secrets.

## 14. Mandatory Tests and Acceptance Scenarios

Add tests using the project's existing test framework. At minimum verify:

1. The OpenCode model listing does not expose the full unfiltered catalogue by default.
2. Auto is selectable through a protocol-compatible mechanism.
3. Auto resolves to a real eligible upstream model.
4. Default Model resolves to the configured provider default.
5. Auto prefers that default when eligible and suitable.
6. Best Coding chooses an eligible coding-capable model.
7. Free Models contains only verified/configured free models.
8. Unknown-price models are not classified as free.
9. Deleted defaults resolve to valid fallback candidates.
10. Disabled models are excluded.
11. Rate-limited models fall back to the next eligible model.
12. Temporary provider failures trigger bounded fallback.
13. Free-only mode never routes to a paid model.
14. Non-recoverable request errors do not cause fallback loops.
15. Streaming responses are not duplicated or corrupted by fallback.
16. Duplicate provider/model records are handled safely.
17. Every listed model ID resolves to the correct upstream route.
18. Existing real model IDs remain compatible with current OpenCode configurations.
19. Existing providers, credentials, defaults, and model records remain intact.
20. Existing provider management, Smart Router, and unrelated workflows still work.

Run relevant unit tests, integration tests, static checks, and an Android build if the environment supports them. Report exact commands and actual results. Never claim a test or build passed unless it was executed.

## 15. Implementation Workflow, Constraints, and Deliverables

Before editing, inspect the complete existing implementation for provider/model storage, provider defaults, Smart Router, OpenCode/Gateway API endpoints, model listing, request routing, fallback, and analytics.

Trace the full path:

**Provider configuration → saved model/default → Gateway model catalogue → OpenCode selector → incoming request → upstream provider.**

Identify the actual root cause of the missing Auto option and unfiltered catalogue. Then implement the correction in the existing architecture.

Strict implementation constraints:

- Do not replace the architecture with a mock or demo implementation.
- Do not create a selector disconnected from the real Gateway API.
- Do not invent fake provider/model records.
- Do not remove existing providers, models, features, or settings.
- Do not change unrelated screens or workflows.
- Do not make destructive database changes.
- Do not leave TODOs, placeholders, or non-functional buttons.
- Do not claim unimplemented capabilities.
- Preserve the existing branding, theme, localization, and coding conventions.

**Final implementation report must include:**

1. Root cause of the missing Auto option and unfiltered model catalogue.
2. Files changed and the purpose of each change.
3. How Auto, Default Model, Best Coding, Free Models, and fallback work.
4. How OpenCode model IDs map to real upstream routes.
5. Exact test commands and pass/fail results.
6. Actual build result, if a build was run.
7. Any remaining limitations.

### Definition of Done

Complete only when OpenCode displays a concise, curated selector with a working **Auto** option; respects the configured default model; provides functional Best Coding and verified Free Models choices; falls back to another eligible model under recoverable failure conditions; and preserves every existing provider, credential, model record, and user setting.
