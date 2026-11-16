# JH GetWay — Production Hardening Implementation (Priority 1–3)

## Objective

Implement the three agreed high-priority improvements in the existing JH GetWay Android project:

1. Make the in-app APK updater strictly fail-closed.
2. Correct GitHub Actions release automation so releases are intentional and tag handling works.
3. Make encrypted Backup/Restore preserve actual Playground attachment files, not only their metadata.

This is a source-code implementation task. Inspect the current project and make the smallest safe changes. Do not merely write recommendations.

## Baseline and strict preservation rules

- Preserve the existing `applicationId`: `com.aistudio.jhgetway.iffndf`.
- Preserve the existing permanent PKCS12 signing keystore, alias `upload`, and all GitHub Secrets/Variables.
- Never generate, replace, rotate, or request a new signing key.
- Never print or expose passwords, Base64 keystore content, private key material, or secret values in logs.
- Do not change the completed Playground UI, layout, navigation, or behavior. Only change attachment file backup/restore plumbing where necessary.
- Preserve existing features, Room schema/data compatibility, and current backup format compatibility.
- Do not uninstall the app, publish a release, create/push tags, or modify GitHub repository settings.
- Do not change app identity or downgrade/rewrite release version policy outside the specific workflow trigger/tag correctness described below.
- Do not disable security checks to make tests pass.
- Avoid unrelated refactors, redesigns, or new dependencies unless demonstrably necessary.

---

# Phase 1 — In-App Updater: strict fail-closed verification

## Exact source locations to inspect

- `app/src/main/java/com/example/updater/AppUpdateManager.kt`
- Existing updater tests, especially `AppUpdateManagerTest.kt` and any release/update contract tests.
- The update manifest generation in `.github/workflows/android-release.yml`.

## Confirmed source-code weaknesses to fix

In `AppUpdateManager.kt`:

1. `verifyApkPackageAndSigner(...)` currently returns `null` when `getPackageArchiveInfo(...)` returns null. That treats an unreadable APK as if verification succeeded.
2. It only rejects a package ID when the archive package name is nonblank and different. A blank/missing package ID must also be rejected.
3. If `expectedSigningCertSha256` is blank/missing, signer pinning is skipped. For production releases, a missing expected certificate fingerprint must be a verification failure.
4. If `extractSigningDigests(...)` returns an empty set, the current method can continue without rejecting.
5. `launchApkInstaller(...)` rejects only `Mismatch`; it currently allows `ApkSignatureCheckResult.Unavailable` through the `else -> Unit` branch.
6. `AppReleaseInfo` contains expected APK SHA-256, package ID, version code and signer fingerprint. Ensure these are mandatory for a production APK update and are actually enforced before launching the installer.

## Required behavior

- Every downloaded production APK must pass all checks before installer launch:
  - File exists and is a regular readable file.
  - APK archive metadata can be parsed.
  - APK package ID is present and exactly equals `context.packageName`.
  - Release manifest exists, parses, and contains valid required fields.
  - Manifest package ID matches the app's expected package ID.
  - Manifest APK SHA-256 is present, correctly formatted (64 hex characters), and matches the downloaded APK bytes.
  - Manifest signing certificate SHA-256 is present, correctly formatted (64 hex characters after removing colons), and matches the APK signing certificate digest.
  - APK signing certificate can be extracted; empty/unavailable signer data is a hard failure.
  - APK signer is compatible with the currently installed app signer; `Mismatch` and `Unavailable` must both block installation.
  - Manifest versionCode is a valid positive integer and matches the APK's actual versionCode.
  - Remote versionCode is greater than installed versionCode for an update.
  - APK file size is within a safe configured maximum and matches the downloaded asset metadata when available.
- Do not rely only on the GitHub release asset filename or URL.
- Do not treat a missing manifest, malformed JSON, missing checksum, missing signer fingerprint, malformed package metadata, or unavailable Android PackageManager result as success.
- Keep debug-asset behavior explicitly separate from production. A debug APK must never be accepted as a production update. If debug updates are retained for development, require a separate explicit development-only path and never silently downgrade production validation.
- Validate SHA-256 from the exact downloaded APK file immediately before installer launch, not just during the initial download.
- Keep error messages user-readable and safe; do not include secrets or sensitive local paths.

## Implementation guidance

- Replace nullable/ambiguous validation return values with a typed result that distinguishes verified success from specific failure reasons.
- Ensure all call paths that can launch `ACTION_VIEW` use the same strict validation function.
- Normalize fingerprints consistently: remove colons and whitespace, lowercase, then require exactly 64 hexadecimal characters.
- Do not assume Android signing certificate history semantics. Preserve legitimate Android signing-certificate rotation compatibility only when Android's verified signing lineage proves compatibility; do not accept arbitrary matching history strings.
- Keep Android API-level compatibility for package signature extraction.
- Do not change the installer permission UX except where required to prevent unverified APK installation.

## Tests required

Add/update unit or Robolectric tests proving installer launch is blocked for:
- Missing APK file.
- Unreadable/corrupt APK archive.
- Missing or blank package ID.
- Wrong package ID.
- Missing manifest.
- Malformed manifest.
- Missing/malformed APK SHA-256.
- APK SHA-256 mismatch.
- Missing/malformed certificate fingerprint.
- Certificate fingerprint mismatch.
- Empty/unavailable signer extraction.
- Installed-vs-downloaded signer mismatch.
- Installed-vs-downloaded signer unavailable.
- Manifest/APK versionCode mismatch.
- Non-increasing versionCode.
- Debug APK presented as a production update.

Also test the valid production path. Tests must assert that the installer is not launched on every failure case.

---

# Phase 2 — GitHub Actions release automation correctness

## Exact source location

- `.github/workflows/android-release.yml`
- Any versioning/release helper scripts referenced by that workflow.
- `app/build.gradle.kts` only if it consumes workflow-provided version values.

## Confirmed workflow issues to fix

1. Current workflow triggers on both `main`/`master` pushes and `v*` tag pushes. The workflow therefore publishes releases from ordinary branch pushes, which is not the intended controlled release policy.
2. In the tag path, the script calls `tag_exists(release_tag)` after the tag push event has already created that Git tag. The function checks both GitHub Releases and Git refs, so it can reject the very tag that triggered the workflow.
3. Debug keystore generation currently occurs inside the Release job's production keystore setup step. This is unnecessary in the production release job and should not be confused with production signing.

## Required release policy

- `pull_request`: run tests only; never run production signing or publish.
- `push` to `main`/`master`: run CI/tests and any non-publishing validation only; do not create tags, sign production APKs, or publish Releases.
- `push` to tags matching `v*`: run the production release pipeline for that exact existing tag.
- `workflow_dispatch`: allow an explicitly requested release using validated inputs; publishing must be explicitly selected/confirmed by the input.
- No automatic production Release on ordinary branch pushes.
- Keep the current production signing secrets and permanent keystore exactly as they are.

## Correct tag handling

- For a tag-push event, treat `GITHUB_REF_NAME` as the already-existing triggering tag. Do not reject it merely because the Git ref exists.
- Check whether a GitHub Release already exists for that tag. If a Release already exists, stop with a clear duplicate-release error; if only the Git ref exists because it triggered this workflow, continue.
- Validate the tag strictly against the supported semantic version format, such as `vMAJOR.MINOR.PATCH` (and only allow prerelease suffixes if the project intentionally supports them).
- Confirm the tag points to the checked-out commit.
- Do not create or move an existing tag from the tag-push path.
- For `workflow_dispatch`, validate the requested version/tag, ensure it does not already exist as a Git ref or Release, and make publishing conditional on the explicit publish input.
- Do not silently create an unrequested release tag on branch pushes.

## Version-code safety

- Keep versionCode monotonically increasing relative to the latest published release manifest.
- For tag-based releases, reject a versionCode that is not greater than the latest published versionCode.
- If the latest manifest cannot be fetched or parsed, fail closed rather than guessing a low versionCode.
- Do not reset the current project versionCode or silently fall back to run-number-only logic.
- Keep the project's current intended versionName/versionCode policy; do not hardcode a new baseline in unrelated files.

## Least-privilege workflow permissions

- Set job-level permissions:
  - Test job: `contents: read` only, plus any truly required read permissions.
  - Release job: `contents: write` only where required to publish the GitHub Release.
- Do not grant `contents: write` to test jobs.
- Pin third-party Actions to immutable commit SHAs if the repository's existing dependency/update policy permits it; preserve readable version comments.
- Keep secrets scoped only to the release job and only to the steps that need them.
- Move debug keystore setup to test/debug build only, or remove it from the production release path if not required.
- Keep permanent production signing fail-closed. No ephemeral or debug signing fallback is allowed for a production Release APK.

## Release artifact verification

Keep and strengthen the existing checks:
- APK package ID equals `com.aistudio.jhgetway.iffndf`.
- APK versionName and versionCode match the release metadata.
- `apksigner verify` succeeds.
- APK certificate SHA-256 equals the certificate extracted from the permanent keystore.
- APK SHA-256 is generated from the final signed APK.
- `update-manifest.json` includes the exact APK checksum, package ID, version, production signing mode, and signing certificate fingerprint.
- `SHA256SUMS.txt` is generated and verified.
- Never print signing passwords or Base64 values.

## Tests / validation

- Add testable release-version/tag logic to a small script/module where practical.
- Add automated tests for:
  - branch push does not publish;
  - PR does not sign or publish;
  - valid pushed tag continues despite its Git ref already existing;
  - existing GitHub Release for the tag is rejected;
  - malformed tag is rejected;
  - tag/manifest versionCode must increase;
  - missing/unreadable previous release manifest fails safely;
  - manual dispatch only publishes when explicit publish input is true.
- Validate YAML syntax and shell/Python script syntax.
- Do not publish a real Release during this task.

---

# Phase 3 — Encrypted Backup/Restore: include actual Playground attachment files

## Exact source locations to inspect

- `app/src/main/java/com/example/data/local/BackupManager.kt`
- Playground attachment model/entity/DAO and file storage implementation.
- Attachment import/export and deletion code.
- Existing backup tests and Room migration tests.

## Confirmed current behavior

`BackupManager.kt` serializes Playground message attachment metadata using `sanitizeAttachmentsMetadataForBackup(...)`. That function intentionally writes:

`localFilePath = ""`

The actual attachment file bytes are not included in the backup archive. As a result, restored messages may retain attachment metadata but lose access to the original files.

## Required behavior

- Discover the actual authoritative attachment storage location from the existing source. Do not guess paths.
- Include the actual attachment file bytes in the encrypted backup archive.
- Preserve safe metadata: attachment ID, display name, MIME type, category, byte size and a relative archive path.
- Never include absolute device paths in the archive.
- Never include files outside the app's approved attachment storage roots.
- Reject symlinks/path traversal and prevent archive extraction outside the app's private storage.
- Enforce configured maximum per-file and total archive size limits.
- Calculate and store SHA-256 for each attachment file.
- Verify each file's byte size and SHA-256 before restoring it.
- Restore files atomically into the app's private attachment directory; use temporary files and rename only after successful verification.
- Rebuild attachment metadata with the newly restored local file path.
- Handle duplicate attachment IDs and filename collisions safely without overwriting unrelated files.
- If an attachment is missing or corrupt, do not silently create a fake working attachment. Return a clear restore warning/error and preserve database consistency.
- Ensure backup preview reports attachment count and total bytes.
- Ensure restore rollback cleans up staged files if the database transaction fails.
- Ensure overwrite/merge conflict strategies do not orphan files or delete files still referenced by other messages.
- Preserve backward compatibility with existing backup format versions that contain metadata but no attachment payloads. Old backups should still restore their messages, marking unavailable attachments clearly rather than failing the entire backup.
- Keep all attachment bytes inside the existing encrypted backup container; do not create plaintext sidecar files.

## Tests required

- Backup and restore a message with one attachment.
- Backup and restore multiple attachments.
- Empty attachment list.
- Missing source attachment file.
- Corrupt attachment bytes.
- Wrong attachment SHA-256.
- Path traversal archive entry.
- File outside approved storage root.
- Oversized attachment and total archive limit.
- Duplicate attachment ID.
- Filename collision.
- Database transaction failure cleans staged files.
- Restore overwrite and merge conflict strategies preserve referenced files.
- Restore of a legacy backup with attachment metadata but no file payload remains backward compatible and clearly reports unavailable attachment files.

---

# General implementation constraints

- Inspect the existing source and follow current architecture, naming, Kotlin style, coroutine patterns, Room transactions, and error/result conventions.
- Do not replace complete files with simplified rewrites.
- Do not add mock implementations or placeholder TODOs.
- Keep changes limited to the three phases above.
- Update documentation only where the behavior or release procedure genuinely changes.
- Do not modify the completed Playground UI or its user-facing behavior.
- Do not alter permanent signing key, alias, GitHub Secrets, expected signing fingerprint, application ID, or existing installed app data.

# Deliverables

Return:
1. Complete updated project ZIP.
2. A concise list of exact modified files.
3. Root cause and implementation summary for each phase.
4. Tests added/updated and which ones were actually executed.
5. Any remaining manual GitHub/device verification steps.
6. Explicit confirmation that the permanent signing configuration and Playground UI were preserved.

Do not claim Gradle builds, Android tests, GitHub Actions, or device tests passed unless they were actually executed in an environment capable of running them.

Stop after code changes and source-level validation. Do not publish a release or create/push tags.
