#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
from pathlib import Path, PurePosixPath
from typing import Any

from common import require_env, select_catalog_entry, set_output, validate_activity_id, write_json
from github_api import GitHubApi, quote


MAX_FILES = 4_000
MAX_TOTAL_BYTES = 40 * 1024 * 1024
MAX_FILE_BYTES = 512 * 1024

EXCLUDED_PARTS = {
    ".git", ".github", ".gradle", ".idea", ".ai-review", "build", "dist",
    "node_modules", "Pods", "DerivedData", "vendor", "secrets",
}
EXCLUDED_NAMES = {
    ".env", "local.properties", "google-services.json", "GoogleService-Info.plist",
}
EXCLUDED_SUFFIXES = {
    ".apk", ".aab", ".class", ".dex", ".jar", ".keystore", ".jks", ".key",
    ".p12", ".png", ".jpg", ".jpeg", ".gif", ".webp", ".pdf", ".zip",
    ".gz", ".tar", ".so", ".dylib", ".dll", ".exe", ".woff", ".woff2",
}
SECRET_NAME = re.compile(r"(^|[-_.])(secret|token|credential|password|private)([-_.]|$)", re.I)
MARKER_PREFIX = "<!-- learning-pr-ai-reviewer:"


def safe_path(path: str) -> bool:
    candidate = PurePosixPath(path)
    if candidate.is_absolute() or ".." in candidate.parts:
        return False
    if any(part in EXCLUDED_PARTS for part in candidate.parts):
        return False
    if candidate.name in EXCLUDED_NAMES or SECRET_NAME.search(candidate.name):
        return False
    return candidate.suffix.lower() not in EXCLUDED_SUFFIXES


def changed_paths(api: GitHubApi, repository: str, number: int) -> set[str]:
    files = api.paginate(f"/repos/{repository}/pulls/{number}/files")
    return {item["filename"] for item in files if item.get("status") != "removed"}


def repository_tree(api: GitHubApi, repository: str, head_sha: str) -> list[dict[str, Any]]:
    tree = api.get(f"/repos/{repository}/git/trees/{quote(head_sha)}?recursive=1")
    if tree.get("truncated"):
        raise ValueError("Repository tree is too large for a complete deterministic review")
    return tree.get("tree", [])


def already_reviewed(api: GitHubApi, repository: str, number: int, fingerprint: str) -> bool:
    comments = api.paginate(f"/repos/{repository}/issues/{number}/comments")
    marker = f"{MARKER_PREFIX}{fingerprint} -->"
    return any(marker in comment.get("body", "") for comment in comments)


def authorized_manual_request(api: GitHubApi, repository: str, pull: dict[str, Any]) -> None:
    trigger = os.environ.get("REVIEW_TRIGGER", "").strip()
    requested_by = os.environ.get("REVIEW_REQUESTED_BY", "").strip()
    if trigger != "manual" or not requested_by:
        return
    if requested_by == pull["user"]["login"]:
        return
    permission = api.get(f"/repos/{repository}/collaborators/{quote(requested_by)}/permission")
    if permission.get("permission") not in {"write", "maintain", "admin"}:
        raise ValueError("Manual review requester is not the author or an authorized collaborator")


def copy_trusted_files(root: Path, context: Path, assignment: Path, assessment: Path) -> None:
    trusted = context / "trusted"
    trusted.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(assignment, trusted / "assignment.md")
    shutil.copyfile(assessment, trusted / "assessment.json")
    prompt = root / "prompts" / "review.md"
    schema = root / "schemas" / "review.schema.json"
    shutil.copyfile(prompt, trusted / "review-prompt.md")
    shutil.copyfile(schema, trusted / "review-schema.json")


def collect_submission(
    api: GitHubApi,
    repository: str,
    number: int,
    head_sha: str,
    scope: str,
    destination: Path,
) -> list[dict[str, Any]]:
    changed = changed_paths(api, repository, number) if scope == "pull_request_changes" else None
    entries = repository_tree(api, repository, head_sha)
    blobs = [entry for entry in entries if entry.get("type") == "blob"]
    if changed is not None:
        blobs = [entry for entry in blobs if entry.get("path") in changed]
    selected = [entry for entry in blobs if safe_path(entry["path"])]
    if len(selected) > MAX_FILES:
        raise ValueError(f"Submission contains more than {MAX_FILES} reviewable files")

    total = 0
    manifest: list[dict[str, Any]] = []
    for entry in sorted(selected, key=lambda value: value["path"]):
        path = entry["path"]
        size = int(entry.get("size", 0))
        item = {"path": path, "size": size, "status": "included"}
        if size > MAX_FILE_BYTES:
            item["status"] = "excluded:file-too-large"
            manifest.append(item)
            continue
        try:
            text = api.read_text_file(repository, path, head_sha)
        except (UnicodeDecodeError, ValueError):
            item["status"] = "excluded:not-utf8-text"
            manifest.append(item)
            continue
        encoded_size = len(text.encode("utf-8"))
        total += encoded_size
        if total > MAX_TOTAL_BYTES:
            raise ValueError(f"Reviewable content exceeds {MAX_TOTAL_BYTES} bytes")
        target = destination / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")
        item["size"] = encoded_size
        manifest.append(item)
    return manifest


def optional_local_validation(api: GitHubApi, repository: str, head_sha: str) -> dict[str, Any] | None:
    path = ".local-validation/summary.json"
    try:
        raw = api.read_text_file(repository, path, head_sha)
    except Exception:
        return None
    try:
        value = json.loads(raw)
    except json.JSONDecodeError:
        return {"valid": False, "error": "Invalid JSON"}
    if not isinstance(value, dict):
        return {"valid": False, "error": "Expected a JSON object"}
    return {"valid": True, "participant_provided": True, "summary": value}


def main() -> None:
    token = require_env("GITHUB_TOKEN")
    repository = require_env("REVIEW_REPOSITORY")
    number = int(require_env("PR_NUMBER"))
    activity_id = validate_activity_id(require_env("ACTIVITY_ID"))
    scope = require_env("REVIEW_SCOPE")
    if scope not in {"full_submission", "pull_request_changes"}:
        raise ValueError("REVIEW_SCOPE must be full_submission or pull_request_changes")
    root = Path(require_env("REVIEWER_ROOT")).resolve()
    context = Path(require_env("REVIEW_CONTEXT")).resolve()
    if context.exists():
        shutil.rmtree(context)
    (context / "submission" / "files").mkdir(parents=True)

    api = GitHubApi(token)
    pull = api.get(f"/repos/{repository}/pulls/{number}")
    authorized_manual_request(api, repository, pull)
    assignment_path, assessment_path, assessment = select_catalog_entry(
        root, activity_id, pull["title"]
    )
    head_sha = pull["head"]["sha"]
    base_sha = pull["base"]["sha"]
    version = str(assessment.get("version", 1))
    fingerprint = hashlib.sha256(
        f"{activity_id}\0{pull['title']}\0{version}\0{head_sha}\0{base_sha}".encode()
    ).hexdigest()[:24]
    if already_reviewed(api, repository, number, fingerprint):
        set_output("ready", "false")
        set_output("fingerprint", fingerprint)
        return

    copy_trusted_files(root, context, assignment_path, assessment_path)
    manifest = collect_submission(
        api, repository, number, head_sha, scope, context / "submission" / "files"
    )
    metadata = {
        "repository": repository,
        "pull_request": number,
        "title": pull["title"],
        "author": pull["user"]["login"],
        "head_sha": head_sha,
        "base_sha": base_sha,
        "activity_id": activity_id,
        "assessment_version": version,
        "review_scope": scope,
        "fingerprint": fingerprint,
        "local_validation": optional_local_validation(api, repository, head_sha),
    }
    write_json(context / "metadata.json", metadata)
    write_json(context / "submission" / "manifest.json", manifest)
    set_output("ready", "true")
    set_output("fingerprint", fingerprint)


if __name__ == "__main__":
    main()
