#!/usr/bin/env python3
"""Release policy, tag validation, and monotonic versionCode enforcement."""

from __future__ import annotations

from dataclasses import dataclass
import json
import os
import re
import subprocess
import sys
from typing import Callable, Optional, Tuple
import urllib.error
import urllib.request

SEMVER_TAG_RE = re.compile(
    r"^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$"
)


class ReleasePolicyError(RuntimeError):
    """Raised when a release event, tag, manifest, or versionCode violates policy."""


@dataclass(frozen=True)
class ReleasePlan:
    release_tag: str
    version_name: str
    version_code: int
    should_publish: bool
    previous_version_code: int


GhGetFn = Callable[[str, str], Tuple[int, str]]
TagTargetResolverFn = Callable[[str], str]


def make_github_get(token: str = "") -> GhGetFn:
    def gh_get(url: str, accept: str = "application/vnd.github+json") -> Tuple[int, str]:
        req = urllib.request.Request(
            url,
            headers={
                "Accept": accept,
                "User-Agent": "JH-GetWay-Release-Policy",
            },
        )
        if token and url.startswith("https://api.github.com/"):
            req.add_header("Authorization", f"Bearer {token}")
        try:
            with urllib.request.urlopen(req, timeout=15) as resp:
                return resp.status, resp.read().decode("utf-8")
        except urllib.error.HTTPError as exc:
            return exc.code, exc.read().decode("utf-8", errors="ignore")
        except Exception as exc:
            raise ReleasePolicyError(f"GitHub API request failed for {url}: {exc}") from exc

    return gh_get


def resolve_git_tag_commit(tag: str) -> str:
    try:
        out = subprocess.check_output(
            ["git", "rev-list", "-n", "1", f"refs/tags/{tag}"],
            stderr=subprocess.STDOUT,
            text=True,
        ).strip()
        return out
    except subprocess.CalledProcessError as exc:
        raise ReleasePolicyError(
            f"Unable to resolve local Git tag 'refs/tags/{tag}': {exc.output.strip()}"
        ) from exc


def fetch_latest_manifest_info(repo: str, gh_get: GhGetFn) -> Tuple[int, str]:
    latest_url = f"https://api.github.com/repos/{repo}/releases/latest"
    status, body = gh_get(latest_url, "application/vnd.github+json")
    if status == 404:
        return 0, ""
    if status != 200:
        raise ReleasePolicyError(
            f"Unable to query latest GitHub release (HTTP {status}). Failing closed."
        )

    try:
        latest_json = json.loads(body)
    except json.JSONDecodeError as exc:
        raise ReleasePolicyError(f"Latest GitHub release response is malformed JSON: {exc}") from exc

    prev_ver = str(latest_json.get("tag_name") or "").lstrip("vV").strip()
    assets = latest_json.get("assets") or []
    manifest_asset = next(
        (a for a in assets if str(a.get("name", "")).lower() == "update-manifest.json"),
        None,
    )
    if manifest_asset is None:
        raise ReleasePolicyError(
            "Latest published GitHub release is missing 'update-manifest.json'. Failing closed to protect versionCode monotonicity."
        )

    asset_url = manifest_asset.get("url") or manifest_asset.get("browser_download_url") or ""
    if not asset_url:
        raise ReleasePolicyError("Latest release 'update-manifest.json' asset has no download URL.")

    m_status, m_body = gh_get(asset_url, "application/octet-stream")
    if m_status != 200:
        raise ReleasePolicyError(
            f"Unable to download previous 'update-manifest.json' (HTTP {m_status}). Failing closed."
        )

    try:
        m_json = json.loads(m_body)
    except json.JSONDecodeError as exc:
        raise ReleasePolicyError(f"Previous 'update-manifest.json' is malformed JSON: {exc}") from exc

    raw_code = m_json.get("versionCode")
    if isinstance(raw_code, bool) or not isinstance(raw_code, int) or raw_code <= 0:
        raise ReleasePolicyError(
            f"Previous 'update-manifest.json' has invalid versionCode ({raw_code!r}). Failing closed."
        )

    manifest_ver = str(m_json.get("versionName") or "").lstrip("vV").strip()
    return raw_code, (manifest_ver or prev_ver)


def bump_patch_version(base_ver: str) -> str:
    m = re.match(r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)", (base_ver or "").strip())
    if not m:
        return "1.0.1"
    major, minor, patch = int(m.group(1)), int(m.group(2)), int(m.group(3))
    return f"{major}.{minor}.{patch + 1}"


def github_release_exists(repo: str, tag: str, gh_get: GhGetFn) -> bool:
    status, _ = gh_get(f"https://api.github.com/repos/{repo}/releases/tags/{tag}", "application/vnd.github+json")
    if status == 200:
        return True
    if status == 404:
        return False
    raise ReleasePolicyError(
        f"Failed to check whether GitHub Release '{tag}' exists (HTTP {status})."
    )


def git_tag_ref_exists(repo: str, tag: str, gh_get: GhGetFn) -> bool:
    status, _ = gh_get(f"https://api.github.com/repos/{repo}/git/ref/tags/{tag}", "application/vnd.github+json")
    if status == 200:
        return True
    if status == 404:
        return False
    raise ReleasePolicyError(
        f"Failed to check whether Git tag ref '{tag}' exists (HTTP {status})."
    )


def resolve_release_plan(
    *,
    event_name: str,
    ref_type: str,
    ref_name: str,
    head_sha: str,
    repo: str,
    input_version_name: str = "",
    input_version_code: str = "",
    input_publish_release: str = "false",
    gh_get: Optional[GhGetFn] = None,
    tag_target_sha_resolver: Optional[TagTargetResolverFn] = None,
) -> ReleasePlan:
    if not repo or "/" not in repo:
        raise ReleasePolicyError(f"Invalid repository slug '{repo}'.")

    http_get = gh_get or make_github_get("")
    clean_event = (event_name or "").strip()
    clean_ref_type = (ref_type or "").strip()
    clean_ref_name = (ref_name or "").strip()

    if clean_event == "pull_request":
        raise ReleasePolicyError("Pull requests cannot run production release builds or publishing.")

    if clean_event == "push" and clean_ref_type == "tag":
        release_tag = clean_ref_name
        if not SEMVER_TAG_RE.match(release_tag):
            raise ReleasePolicyError(
                f"Invalid release tag '{release_tag}'. Expected semantic version tag vMAJOR.MINOR.PATCH (optional -prerelease)."
            )
        version_name = release_tag[1:]

        if head_sha:
            resolver = tag_target_sha_resolver or resolve_git_tag_commit
            tag_commit = resolver(release_tag).strip()
            if not tag_commit or tag_commit != head_sha.strip():
                raise ReleasePolicyError(
                    f"Tag '{release_tag}' points to '{tag_commit}', which does not match checked-out commit '{head_sha}'."
                )

        # Tag push: existing Git ref is expected because it triggered this run; only reject if a GitHub Release already exists.
        if github_release_exists(repo, release_tag, http_get):
            raise ReleasePolicyError(f"GitHub Release for tag '{release_tag}' already exists.")

        prev_code, _ = fetch_latest_manifest_info(repo, http_get)
        should_publish = True

    elif clean_event == "workflow_dispatch":
        prev_code, prev_ver = fetch_latest_manifest_info(repo, http_get)
        raw_ver = (input_version_name or "").strip()
        if raw_ver:
            candidate_tag = raw_ver if raw_ver.startswith("v") else f"v{raw_ver.lstrip('V')}"
            if not SEMVER_TAG_RE.match(candidate_tag):
                raise ReleasePolicyError(
                    f"Invalid manual version_name '{raw_ver}'. Expected MAJOR.MINOR.PATCH (or vMAJOR.MINOR.PATCH)."
                )
            release_tag = candidate_tag
            version_name = release_tag[1:]
            if github_release_exists(repo, release_tag, http_get) or git_tag_ref_exists(repo, release_tag, http_get):
                raise ReleasePolicyError(f"Release or Git tag '{release_tag}' already exists.")
        else:
            version_name = bump_patch_version(prev_ver) if prev_ver else "1.0.1"
            release_tag = f"v{version_name}"
            attempts = 0
            while github_release_exists(repo, release_tag, http_get) or git_tag_ref_exists(repo, release_tag, http_get):
                attempts += 1
                if attempts > 50:
                    raise ReleasePolicyError("Exceeded maximum patch bump attempts searching for unused tag.")
                version_name = bump_patch_version(version_name)
                release_tag = f"v{version_name}"

        should_publish = (input_publish_release or "").strip().lower() == "true"

    elif clean_event == "push":
        raise ReleasePolicyError(
            f"Branch push '{clean_ref_name}' is CI/test-only and must not build or publish a production release."
        )
    else:
        raise ReleasePolicyError(
            f"Unsupported workflow event '{clean_event}' (ref_type='{clean_ref_type}') for release."
        )

    raw_input_code = (input_version_code or "").strip()
    if raw_input_code:
        if not raw_input_code.isdigit() or int(raw_input_code) <= 0:
            raise ReleasePolicyError("version_code must be a positive integer.")
        version_code = int(raw_input_code)
    else:
        version_code = (prev_code + 1) if prev_code > 0 else 2

    if version_code <= prev_code:
        raise ReleasePolicyError(
            f"versionCode ({version_code}) must be greater than previous published versionCode ({prev_code})."
        )

    return ReleasePlan(
        release_tag=release_tag,
        version_name=version_name,
        version_code=version_code,
        should_publish=should_publish,
        previous_version_code=prev_code,
    )


def main() -> int:
    try:
        token = os.environ.get("GH_TOKEN", "")
        plan = resolve_release_plan(
            event_name=os.environ.get("EVENT_NAME", ""),
            ref_type=os.environ.get("GITHUB_REF_TYPE", ""),
            ref_name=os.environ.get("GITHUB_REF_NAME", ""),
            head_sha=os.environ.get("GITHUB_SHA", ""),
            repo=os.environ.get("REPO", ""),
            input_version_name=os.environ.get("INPUT_VERSION_NAME", ""),
            input_version_code=os.environ.get("INPUT_VERSION_CODE", ""),
            input_publish_release=os.environ.get("INPUT_PUBLISH_RELEASE", "false"),
            gh_get=make_github_get(token),
        )
    except ReleasePolicyError as exc:
        print(f"::error::{exc}", file=sys.stderr)
        return 1

    gh_out = os.environ.get("GITHUB_OUTPUT", "")
    if gh_out:
        with open(gh_out, "a", encoding="utf-8") as fh:
            fh.write(f"version_code={plan.version_code}\n")
            fh.write(f"version_name={plan.version_name}\n")
            fh.write(f"release_tag={plan.release_tag}\n")
            fh.write(f"should_publish={'true' if plan.should_publish else 'false'}\n")

    print(
        f"Prepared release {plan.release_tag} "
        f"(versionName={plan.version_name}, versionCode={plan.version_code}, "
        f"previousVersionCode={plan.previous_version_code}, publish={plan.should_publish})"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
