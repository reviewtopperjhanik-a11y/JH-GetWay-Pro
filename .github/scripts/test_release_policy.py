#!/usr/bin/env python3
"""Unit tests for GitHub release policy and workflow configuration."""

from __future__ import annotations

import json
import pathlib
import sys
import unittest

SCRIPT_DIR = pathlib.Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent.parent
WORKFLOW_PATH = REPO_ROOT / ".github" / "workflows" / "android-release.yml"

if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import release_policy  # noqa: E402


class FakeGitHubApi:
    def __init__(
        self,
        *,
        latest_status: int = 404,
        latest_tag: str = "",
        manifest_asset_status: int = 200,
        manifest_asset_body: str | None = None,
        existing_releases: set[str] | None = None,
        existing_tag_refs: set[str] | None = None,
        release_tag_status_override: int | None = None,
    ) -> None:
        self.latest_status = latest_status
        self.latest_tag = latest_tag
        self.manifest_asset_status = manifest_asset_status
        self.manifest_asset_body = manifest_asset_body
        self.existing_releases = existing_releases or set()
        self.existing_tag_refs = existing_tag_refs or set()
        self.release_tag_status_override = release_tag_status_override

    def __call__(self, url: str, accept: str = "application/vnd.github+json") -> tuple[int, str]:
        if url.endswith("/releases/latest"):
            if self.latest_status != 200:
                return self.latest_status, json.dumps({"message": "error"})
            assets = []
            if self.manifest_asset_body is not None or self.manifest_asset_status != 200:
                assets.append(
                    {
                        "name": "update-manifest.json",
                        "url": "https://api.github.com/assets/update-manifest.json",
                        "browser_download_url": "https://example.com/update-manifest.json",
                    }
                )
            return 200, json.dumps({"tag_name": self.latest_tag, "assets": assets})

        if url == "https://api.github.com/assets/update-manifest.json":
            return self.manifest_asset_status, self.manifest_asset_body or ""

        if "/releases/tags/" in url:
            if self.release_tag_status_override is not None:
                return self.release_tag_status_override, ""
            tag = url.rsplit("/releases/tags/", 1)[1]
            return (200, "{}") if tag in self.existing_releases else (404, "{}")

        if "/git/ref/tags/" in url:
            tag = url.rsplit("/git/ref/tags/", 1)[1]
            return (200, "{}") if tag in self.existing_tag_refs else (404, "{}")

        return 404, "{}"


class ReleasePolicyTests(unittest.TestCase):
    def test_branch_push_does_not_release_or_publish(self) -> None:
        gh = FakeGitHubApi()
        for branch in ("main", "master", "feature/xyz"):
            with self.subTest(branch=branch):
                with self.assertRaises(release_policy.ReleasePolicyError):
                    release_policy.resolve_release_plan(
                        event_name="push",
                        ref_type="branch",
                        ref_name=branch,
                        head_sha="abc123",
                        repo="owner/repo",
                        gh_get=gh,
                    )

    def test_pull_request_does_not_sign_or_publish(self) -> None:
        gh = FakeGitHubApi()
        with self.assertRaises(release_policy.ReleasePolicyError):
            release_policy.resolve_release_plan(
                event_name="pull_request",
                ref_type="branch",
                ref_name="main",
                head_sha="abc123",
                repo="owner/repo",
                gh_get=gh,
            )

    def test_valid_pushed_tag_continues_even_when_git_ref_already_exists(self) -> None:
        manifest = json.dumps({"versionCode": 111, "versionName": "1.0.11"})
        gh = FakeGitHubApi(
            latest_status=200,
            latest_tag="v1.0.11",
            manifest_asset_body=manifest,
            existing_releases=set(),  # No GitHub release for v1.0.12 yet
            existing_tag_refs={"v1.0.12"},  # Triggering Git tag ref ALREADY exists!
        )
        plan = release_policy.resolve_release_plan(
            event_name="push",
            ref_type="tag",
            ref_name="v1.0.12",
            head_sha="deadbeef1234",
            repo="owner/repo",
            gh_get=gh,
            tag_target_sha_resolver=lambda tag: "deadbeef1234" if tag == "v1.0.12" else "",
        )
        self.assertEqual(plan.release_tag, "v1.0.12")
        self.assertEqual(plan.version_name, "1.0.12")
        self.assertEqual(plan.version_code, 112)
        self.assertTrue(plan.should_publish)

    def test_tag_pointing_to_wrong_commit_is_rejected(self) -> None:
        manifest = json.dumps({"versionCode": 111, "versionName": "1.0.11"})
        gh = FakeGitHubApi(latest_status=200, latest_tag="v1.0.11", manifest_asset_body=manifest)
        with self.assertRaises(release_policy.ReleasePolicyError):
            release_policy.resolve_release_plan(
                event_name="push",
                ref_type="tag",
                ref_name="v1.0.12",
                head_sha="deadbeef1234",
                repo="owner/repo",
                gh_get=gh,
                tag_target_sha_resolver=lambda _: "differentcommit",
            )

    def test_existing_github_release_for_tag_is_rejected(self) -> None:
        manifest = json.dumps({"versionCode": 111, "versionName": "1.0.11"})
        gh = FakeGitHubApi(
            latest_status=200,
            latest_tag="v1.0.11",
            manifest_asset_body=manifest,
            existing_releases={"v1.0.12"},
            existing_tag_refs={"v1.0.12"},
        )
        with self.assertRaises(release_policy.ReleasePolicyError) as ctx:
            release_policy.resolve_release_plan(
                event_name="push",
                ref_type="tag",
                ref_name="v1.0.12",
                head_sha="deadbeef",
                repo="owner/repo",
                gh_get=gh,
                tag_target_sha_resolver=lambda _: "deadbeef",
            )
        self.assertIn("already exists", str(ctx.exception))

    def test_malformed_tag_is_rejected(self) -> None:
        gh = FakeGitHubApi()
        for bad_tag in ("1.0.12", "v1.0", "v01.0.0", "v1.0.0_bad", "v-foo", "release-1"):
            with self.subTest(bad_tag=bad_tag):
                with self.assertRaises(release_policy.ReleasePolicyError):
                    release_policy.resolve_release_plan(
                        event_name="push",
                        ref_type="tag",
                        ref_name=bad_tag,
                        head_sha="deadbeef",
                        repo="owner/repo",
                        gh_get=gh,
                        tag_target_sha_resolver=lambda _: "deadbeef",
                    )

    def test_version_code_must_increase_relative_to_previous_manifest(self) -> None:
        manifest = json.dumps({"versionCode": 120, "versionName": "1.0.12"})
        gh = FakeGitHubApi(
            latest_status=200,
            latest_tag="v1.0.12",
            manifest_asset_body=manifest,
        )
        with self.assertRaises(release_policy.ReleasePolicyError):
            release_policy.resolve_release_plan(
                event_name="push",
                ref_type="tag",
                ref_name="v1.0.13",
                head_sha="deadbeef",
                repo="owner/repo",
                input_version_code="120",
                gh_get=gh,
                tag_target_sha_resolver=lambda _: "deadbeef",
            )
        with self.assertRaises(release_policy.ReleasePolicyError):
            release_policy.resolve_release_plan(
                event_name="push",
                ref_type="tag",
                ref_name="v1.0.13",
                head_sha="deadbeef",
                repo="owner/repo",
                input_version_code="50",
                gh_get=gh,
                tag_target_sha_resolver=lambda _: "deadbeef",
            )

    def test_missing_or_unreadable_previous_manifest_fails_closed(self) -> None:
        # 1. Latest release exists but has no update-manifest.json asset
        gh_missing_asset = FakeGitHubApi(latest_status=200, latest_tag="v1.0.11", manifest_asset_body=None)
        with self.assertRaises(release_policy.ReleasePolicyError):
            release_policy.resolve_release_plan(
                event_name="push",
                ref_type="tag",
                ref_name="v1.0.12",
                head_sha="abc",
                repo="owner/repo",
                gh_get=gh_missing_asset,
                tag_target_sha_resolver=lambda _: "abc",
            )

        # 2. Latest release API returns HTTP 500
        gh_api_error = FakeGitHubApi(latest_status=500)
        with self.assertRaises(release_policy.ReleasePolicyError):
            release_policy.resolve_release_plan(
                event_name="push",
                ref_type="tag",
                ref_name="v1.0.12",
                head_sha="abc",
                repo="owner/repo",
                gh_get=gh_api_error,
                tag_target_sha_resolver=lambda _: "abc",
            )

        # 3. Manifest download returns malformed JSON
        gh_bad_json = FakeGitHubApi(latest_status=200, latest_tag="v1.0.11", manifest_asset_body="{broken")
        with self.assertRaises(release_policy.ReleasePolicyError):
            release_policy.resolve_release_plan(
                event_name="push",
                ref_type="tag",
                ref_name="v1.0.12",
                head_sha="abc",
                repo="owner/repo",
                gh_get=gh_bad_json,
                tag_target_sha_resolver=lambda _: "abc",
            )

        # 4. Manifest download returns HTTP 403
        gh_asset_http_err = FakeGitHubApi(
            latest_status=200,
            latest_tag="v1.0.11",
            manifest_asset_status=403,
            manifest_asset_body="forbidden",
        )
        with self.assertRaises(release_policy.ReleasePolicyError):
            release_policy.resolve_release_plan(
                event_name="push",
                ref_type="tag",
                ref_name="v1.0.12",
                head_sha="abc",
                repo="owner/repo",
                gh_get=gh_asset_http_err,
                tag_target_sha_resolver=lambda _: "abc",
            )

    def test_workflow_dispatch_publishes_only_when_explicitly_requested(self) -> None:
        manifest = json.dumps({"versionCode": 115, "versionName": "1.0.15"})
        gh = FakeGitHubApi(
            latest_status=200,
            latest_tag="v1.0.15",
            manifest_asset_body=manifest,
            existing_releases={"v1.0.15"},
            existing_tag_refs={"v1.0.15"},
        )
        no_pub = release_policy.resolve_release_plan(
            event_name="workflow_dispatch",
            ref_type="branch",
            ref_name="main",
            head_sha="abc",
            repo="owner/repo",
            input_version_name="1.0.16",
            input_publish_release="false",
            gh_get=gh,
        )
        self.assertEqual(no_pub.release_tag, "v1.0.16")
        self.assertEqual(no_pub.version_code, 116)
        self.assertFalse(no_pub.should_publish)

        yes_pub = release_policy.resolve_release_plan(
            event_name="workflow_dispatch",
            ref_type="branch",
            ref_name="main",
            head_sha="abc",
            repo="owner/repo",
            input_version_name="1.0.16",
            input_publish_release="true",
            gh_get=gh,
        )
        self.assertTrue(yes_pub.should_publish)

        # Reject duplicate tag on manual dispatch
        with self.assertRaises(release_policy.ReleasePolicyError):
            release_policy.resolve_release_plan(
                event_name="workflow_dispatch",
                ref_type="branch",
                ref_name="main",
                head_sha="abc",
                repo="owner/repo",
                input_version_name="v1.0.15",
                input_publish_release="true",
                gh_get=gh,
            )

    def test_workflow_yaml_permissions_and_release_gating(self) -> None:
        yml_text = WORKFLOW_PATH.read_text(encoding="utf-8")
        self.assertNotIn("ci-ephemeral", yml_text)
        self.assertIn("permissions: {}", yml_text)
        self.assertIn("com.aistudio.jhgetway.iffndf", yml_text)
        self.assertIn("github.ref_type == 'tag'", yml_text)
        self.assertIn("startsWith(github.ref_name, 'v')", yml_text)
        self.assertIn("python3 .github/scripts/release_policy.py", yml_text)

        # Ensure release job section does NOT generate debug.keystore
        release_section = yml_text.split("  release:", 1)[1]
        self.assertNotIn("debug.keystore", release_section)
        self.assertIn("contents: write", release_section)

        test_section = yml_text.split("  release:", 1)[0]
        self.assertIn("contents: read", test_section)
        self.assertNotIn("contents: write", test_section)


if __name__ == "__main__":
    unittest.main()
