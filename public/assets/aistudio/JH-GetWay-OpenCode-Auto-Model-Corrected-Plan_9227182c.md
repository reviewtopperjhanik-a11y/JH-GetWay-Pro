# JH GetWay — OpenCode Single Auto Model & Gateway-Controlled Fallback
## Corrected AI Studio Implementation Specification

**Project:** JH GetWay  
**Client:** OpenCode  
**Priority:** Critical  
**Task:** Fix the existing OpenCode model-list and request-routing integration without disturbing saved data.

## 1. Exact requirement

OpenCode currently displays approximately 526 upstream models in one long selector. This is not the intended workflow. The user does not want to choose among hundreds of provider models.

The required workflow is:
1. Open the OpenCode model selector.
2. See one primary entry: **JH GetWay Auto**.
3. Select it once.
4. OpenCode sends every request using the same stable Auto model ID.
5. JH GetWay's Smart Router chooses the actual upstream provider/model internally.
6. If that upstream model fails or becomes unavailable, JH GetWay automatically tries another eligible model.
7. OpenCode continues using the same Auto ID. The user never needs to manually change upstream models during fallback.

**Critical distinction:** Auto is not merely a filter, a decorative option, or one item among 526 models. It is one Gateway-owned logical model whose actual upstream model can change internally for each request.

## 2. Expected OpenCode selector

The default OpenCode-facing model catalogue should show one primary entry:

- **Name:** JH GetWay Auto
- **Provider:** JH GetWay
- **Description:** Automatically selects the best available model with fallback, if the client supports descriptions.

Do not expose all 500+ upstream models in the default OpenCode selector. If manual/diagnostic model selection is retained, it must be separate and opt-in; it must not clutter the default selector.

The screenshot shows upstream entries such as `aion-labs/aion-2.0`, `aion-labs/aion-3.0`, and many others. Those must not all appear in the default selector when the user expects the single Auto model.

## 3. Stable Auto ID and protocol compatibility

Inspect the actual OpenCode integration before coding.

- Identify the exact model-list endpoint OpenCode calls.
- Identify the model ID OpenCode sends in requests.
- Define one stable Gateway-owned ID, for example `jh-getway/auto`, consistent with project conventions.
- Return that ID from the OpenCode-facing model-list endpoint.
- Ensure the request handler recognizes that exact ID.
- Resolve it internally to a real provider and upstream model ID before sending the request upstream.
- Never send the virtual Auto ID directly to an upstream provider.
- Keep the Auto ID unchanged even when the selected upstream model changes.
- Ensure model-list and request-handler mappings agree exactly.
- Do not assume OpenCode accepts arbitrary virtual IDs; verify its actual protocol and implement a compatible mapping. Include all required model fields.

## 4. Real Gateway-side Auto routing

Auto must work in the actual request-routing path, not only in the UI or model-list response.

For each request using the Auto ID:
1. Read current provider/model configuration.
2. Exclude deleted, disabled, invalid, unconfigured, or unusable candidates.
3. Determine request requirements and capabilities.
4. Rank eligible models using saved preferences and available metadata.
5. Prefer the configured default when eligible and suitable.
6. For coding requests, prioritize eligible coding-capable models.
7. Respect free-only and other saved routing preferences.
8. Resolve the selected provider, credential, endpoint, and actual upstream model ID.
9. Send the request and return the response format OpenCode expects.
10. Record safe routing diagnostics.

Never choose randomly or merely select the first database record. If request intent is unclear, prefer the eligible configured default; otherwise use the highest-ranked generally capable candidate.

## 5. Configured default model

Reuse the existing canonical provider/global default setting; do not create a conflicting second default.

- Preserve the model selected as default during provider/API setup.
- Auto should prefer it when suitable and available.
- If it is unsuitable for the request, choose a more suitable eligible model.
- If it is deleted, disabled, invalid, or unavailable, choose the next eligible candidate.
- Never route to a stale model ID.

OpenCode must continue showing **JH GetWay Auto** even when the internal default/upstream model changes.

## 6. Best coding model logic

For coding-oriented requests, prefer the best eligible coding model using actual capability metadata: coding ability, tool/function calling, context length, structured output, streaming, enabled/health state, recent failures, and configured cost/free status.

Use maintainable capability metadata or a capability registry when provider metadata is incomplete. Do not rely only on fragile model-name matching. If no coding model is eligible, use a documented compatible general model or return a clear error.

## 7. Free/paid constraints

Use explicit pricing/free-tier metadata or verified configuration. Unknown price is **unknown**, never automatically free.

- Respect existing free-only preferences.
- When free-only is active, Auto and every fallback attempt must use verified free candidates only.
- Never silently switch to paid when the user explicitly requires free-only.
- If no eligible free model exists, return a clear error.
- Do not make a temporary hard-coded free list the sole source of truth.

## 8. Internal fallback

Fallback is performed inside JH GetWay. OpenCode keeps using the same Auto ID.

Handle appropriate recoverable failures:
- Upstream model missing/removed or disabled.
- Provider disabled or temporarily unavailable.
- Rate limit or quota exhaustion.
- Temporary upstream 5xx or network/connection failure.
- Required capability unsupported by the selected candidate.

Fallback rules:
1. Exclude the failed candidate from the current request's retry chain.
2. Select the next eligible candidate according to Auto policy.
3. Use a strict retry/attempt limit and prevent loops.
4. Preserve request body, supported parameters, tools, and response contract.
5. Respect free-only and all other routing constraints.
6. Use cooldown/circuit-breaker state for repeatedly failing candidates where appropriate.
7. Return a clear error if all candidates fail.

Do not retry malformed client requests or non-recoverable errors without a valid reason.

**Streaming safety:** fallback may happen before response content is committed to the client. Once stream content has been sent, do not silently restart on another model and duplicate/corrupt output. Use a safe protocol-compatible error strategy.

## 9. Fix the actual OpenCode model-list endpoint

The endpoint that OpenCode calls must return the single primary Auto entry by default.

- Do not return all 526 upstream models in the default response.
- Do not merely hide models in a frontend while the API still returns them.
- Do not delete upstream model records to shorten the list.
- Preserve the separate internal provider/model catalogue and provider management UI.
- Keep manual/legacy model routing only as a separate compatibility path if required.

## 10. Existing model-ID compatibility

Inspect existing OpenCode configurations before changing behavior.

- Keep existing real model IDs routable if backward compatibility requires it.
- Do not silently reinterpret a real model ID as Auto.
- Do not expose every legacy ID in the default selector.
- Make JH GetWay Auto the recommended/default OpenCode model.
- Document any unavoidable compatibility limitation.

## 11. Preserve all data and workflows

This is a targeted integration fix. Preserve all provider records, API keys/encrypted credentials, upstream model records, relationships, defaults, Smart Router settings, Coding Agent configurations/tokens, Gateway settings, chat/history, analytics, and activity records.

Do not clear/recreate the database, reinstall the app, or ask the user to re-add providers or keys. Any genuinely necessary migration must be backward-compatible, non-destructive, and tested. Existing provider add/edit/delete/test workflows must continue working. Hiding a model from OpenCode must never delete its database record.

## 12. Routing preferences

Extend existing Smart Router settings rather than creating duplicate controls. Respect existing preferences for default model, provider/model priority, free-only routing, and whether paid models may be used. Do not unexpectedly enable paid routing. Preference changes must not require onboarding again or re-entering API keys.

## 13. Safe diagnostics

For each Auto request, record non-secret details:
- Requested stable Auto ID.
- Actual selected provider and upstream model ID.
- Routing reason (default, coding capability, availability, etc.).
- Fallback attempt number and failure category.
- Final status and latency where available.

Never log plaintext API keys, authorization headers, decrypted tokens, or sensitive request content.

## 14. Mandatory tests

Use the existing project test framework. Verify:
1. OpenCode model-list endpoint returns one primary Auto entry by default.
2. It does not return the full 526-model catalogue by default.
3. OpenCode can send a request using the stable Auto ID.
4. Auto ID is resolved internally and never sent upstream as the provider model ID.
5. Auto selects a real eligible upstream model.
6. Configured default is preferred when suitable.
7. Coding requests prefer eligible coding-capable models.
8. Free-only uses verified free candidates only.
9. Unknown-price models are not treated as free.
10. Deleted/disabled defaults fall back.
11. Rate limits and temporary provider failures trigger bounded fallback.
12. A failed candidate is not retried repeatedly in one attempt chain.
13. All-candidates-failed returns a clear error.
14. Streaming output is not duplicated/corrupted.
15. Existing real model IDs remain compatible if required.
16. All existing provider/model records and credentials remain intact.
17. Existing provider management and unrelated Gateway functions still work.

Run unit/integration tests, static checks, and Android build where supported. Report exact commands and actual results. Never claim unexecuted tests/builds passed.

## 15. Implementation workflow and final deliverables

Before editing, inspect and trace:
**Provider configuration → saved model/default → OpenCode model-list endpoint → OpenCode request → Gateway model-ID resolution → Smart Router → upstream provider → fallback → response.**

Identify the actual reason 526 models are returned and why Auto is absent. Then fix the existing implementation. Do not create a disconnected demo, mock router, fake model catalogue, duplicate routing system, or unrelated redesign. Do not leave TODOs/placeholders or non-functional buttons. Preserve branding, theme, localization, and coding conventions.

Final report must state:
- Root cause of the 526-model list and missing Auto entry.
- Files changed and purpose.
- Stable Auto model ID exposed to OpenCode.
- Actual internal selection and fallback behavior.
- How default/coding/free preferences affect routing.
- Exact test commands and pass/fail results.
- Actual build result, if executed.
- Remaining limitations.

### Definition of Done

OpenCode's default model selector shows one primary **JH GetWay Auto** entry instead of the 526-model upstream catalogue. Selecting it sends all requests through the real JH GetWay Smart Router. The Gateway selects an eligible upstream model and performs bounded internal fallback when necessary, while OpenCode continues using the same Auto ID. All existing providers, credentials, model records, defaults, and user data remain intact.
