#!/usr/bin/env python3
"""Create a bounded review context without checking out or executing PR content."""

from __future__ import annotations

import base64
import json
import os
import re
import sys
import urllib.error
from pathlib import Path, PurePosixPath
from typing import Any

from common import require_env, set_output
from github_api import GitHubApi, quote


MARKER = "<!-- learning-pr-ai-reviewer:pr -->"
SAFE_ID = re.compile(r"^[a-z0-9][a-z0-9-]*$")
ACTIVITIES_PATH = Path(".ai-review/activities")
MAX_FILE_BYTES = 1_048_576
MAX_TRUSTED_BYTES = 1_048_576
MAX_DIFF_BYTES = 5_242_880
LOCAL_VALIDATION_PATH = Path(".local-validation/summary.json")
UTC_TIMESTAMP = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")
VALIDATION_TARGETS = {"Android", "Desktop/JVM", "Web (JS)", "Web (Wasm)", "iOS Simulator"}

EXCLUDED_DIRECTORIES = {
    ".git", ".gradle", ".idea", ".kotlin", ".claude", ".ai-review",
    "build", "generated", "node_modules", "dist", "out", "target", "vendor",
}
EXCLUDED_FILES = {
    "claude.md", "claude.local.md", ".mcp.json", "google-services.json",
    "googleservice-info.plist", "local.properties", "keystore.properties",
    ".npmrc", ".pypirc", "credentials.json", "secrets.json", "secrets.yaml",
    "secrets.yml", "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519",
}
EXCLUDED_SUFFIXES = {
    ".7z", ".a", ".aar", ".apk", ".avi", ".bin", ".bmp", ".class",
    ".dex", ".dmg", ".doc", ".docx", ".eot", ".exe", ".gif", ".gz",
    ".ico", ".jar", ".jpeg", ".jpg", ".jks", ".keystore", ".key",
    ".lockb", ".mov", ".mp3", ".mp4", ".o", ".otf", ".p12", ".pdf",
    ".pem", ".png", ".pyc", ".rar", ".so", ".tar", ".ttf", ".wav",
    ".webm", ".webp", ".woff", ".woff2", ".xcarchive", ".zip",
}


def safe_relative_path(path: str) -> bool:
    candidate = PurePosixPath(path)
    return bool(path) and not candidate.is_absolute() and ".." not in candidate.parts


def exclusion_reason(path: str, mode: str = "100644") -> str | None:
    if not safe_relative_path(path):
        return "unsafe path"
    normalized = path.replace("\\", "/").lower().removeprefix("./")
    parts = PurePosixPath(normalized).parts
    basename = parts[-1] if parts else ""
    if mode == "120000":
        return "symbolic link"
    if normalized.startswith(".github/workflows/"):
        return "pull request workflow"
    if any(part in EXCLUDED_DIRECTORIES for part in parts[:-1]):
        return "excluded directory"
    if basename in EXCLUDED_FILES:
        return "secret-bearing or AI-control file"
    if basename == ".env" or basename.startswith(".env."):
        return "environment secret file"
    if PurePosixPath(basename).suffix.lower() in EXCLUDED_SUFFIXES:
        return "binary or archive extension"
    return None


def decode_blob(encoded: str) -> tuple[bytes | None, str | None]:
    try:
        data = base64.b64decode(encoded, validate=True)
    except ValueError:
        return None, "invalid base64 blob"
    if b"\x00" in data:
        return None, "binary content"
    try:
        data.decode("utf-8")
    except UnicodeDecodeError:
        return None, "non-UTF-8 content"
    return data, None


def safe_write(root: Path, relative: str, data: bytes) -> None:
    resolved_root = root.resolve()
    target = (resolved_root / relative).resolve()
    if os.path.commonpath((str(resolved_root), str(target))) != str(resolved_root):
        raise ValueError(f"Refusing to write outside context: {relative}")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)


def trusted_file(path: Path) -> bytes:
    if not path.is_file() or path.is_symlink():
        raise RuntimeError(f"Trusted catalog file is not a regular file: {path}")
    data = path.read_bytes()
    if len(data) > MAX_TRUSTED_BYTES or b"\x00" in data:
        raise RuntimeError(f"Trusted catalog file is unsafe or too large: {path}")
    try:
        data.decode("utf-8")
    except UnicodeDecodeError as error:
        raise RuntimeError(f"Trusted catalog file is not UTF-8: {path}") from error
    return data


def activity_root(reviewer_root: Path, activity_id: str) -> Path:
    if not SAFE_ID.fullmatch(activity_id):
        raise ValueError("ACTIVITY_ID must contain lowercase letters, digits, and hyphens")
    root = reviewer_root.resolve()
    result = (root / ACTIVITIES_PATH / activity_id).resolve()
    if os.path.commonpath((str(root), str(result))) != str(root):
        raise ValueError("ACTIVITY_ID resolves outside the reviewer catalog")
    return result


def assessment_files(root: Path) -> dict[str, Path]:
    directory = root / "assessments"
    result: dict[str, Path] = {}
    duplicates: set[str] = set()
    if not directory.is_dir():
        return result
    for path in sorted(directory.iterdir()):
        if not path.is_file() or path.is_symlink() or path.suffix.lower() not in {".yaml", ".yml"}:
            continue
        identifier = path.stem
        if not SAFE_ID.fullmatch(identifier):
            continue
        if identifier in result:
            duplicates.add(identifier)
        result[identifier] = path
    for identifier in duplicates:
        result.pop(identifier, None)
    return result


def scalar_metadata(data: bytes) -> dict[str, str]:
    text = data.decode("utf-8")
    result: dict[str, str] = {}
    for key in ("id", "version", "title"):
        match = re.search(rf"(?m)^{key}:\s*[\"']?([^\n\"']+)", text)
        if match:
            result[key] = match.group(1).strip()
    return result


def requirement_metadata(data: bytes) -> list[dict[str, str]]:
    requirements: list[dict[str, str]] = []
    current: dict[str, str] | None = None
    for line in data.decode("utf-8").splitlines():
        identifier = re.fullmatch(r"\s*- id:\s*[\"']?([a-z0-9][a-z0-9-]*)[\"']?\s*", line)
        if identifier:
            current = {"id": identifier.group(1), "title": identifier.group(1)}
            requirements.append(current)
            continue
        if current:
            title = re.fullmatch(r"\s+title:\s*(.+?)\s*", line)
            if title:
                current["title"] = title.group(1).strip("\"'")
                current = None
    return requirements


def load_comments(api: GitHubApi, repository: str, number: int) -> list[dict[str, Any]]:
    return api.paginate(f"/repos/{repository}/issues/{number}/comments")


def previous_review(comments: list[dict[str, Any]]) -> str | None:
    matches = [
        comment.get("body", "")
        for comment in comments
        if MARKER in comment.get("body", "")
        and comment.get("user", {}).get("login") == "github-actions[bot]"
    ]
    return matches[-1] if matches else None


def normalized_words(value: str) -> str:
    return " ".join(re.findall(r"[a-z0-9]+", value.casefold()))


def select_assessment(
    title: str,
    comments: list[dict[str, Any]],
    candidates: list[dict[str, Any]],
    author: str,
) -> dict[str, Any]:
    sources: list[tuple[str, str]] = []
    for comment in reversed(comments):
        body = (comment.get("body") or "").strip()
        login = comment.get("user", {}).get("login", "")
        association = comment.get("author_association", "")
        if (
            not body or body == "/ai-review" or MARKER in body
            or login == "github-actions[bot]"
            or (login != author and association not in {"OWNER", "MEMBER", "COLLABORATOR"})
        ):
            continue
        sources.append((f"comment by @{login}", body))
    sources.append(("title", title or ""))

    for source, text in sources:
        normalized = normalized_words(text)
        declared_modules = set(re.findall(r"(?i)\b(?:module|модуль)\s*#?\s*(\d{1,3})\b", text))
        matches: dict[str, list[str]] = {}
        recognized_modules: set[str] = set()
        for candidate in candidates:
            signals: list[str] = []
            identifier = candidate["id"]
            candidate_title = candidate.get("title", "")
            if re.search(rf"(?<![a-z0-9-]){re.escape(identifier)}(?![a-z0-9-])", text, re.I):
                signals.append("assessment ID")
            normalized_title = normalized_words(candidate_title)
            if normalized_title and (
                normalized == normalized_title
                or (len(normalized_title.split()) >= 2 and re.search(rf"(?:^| ){re.escape(normalized_title)}(?: |$)", normalized))
            ):
                signals.append("assessment title")
            module = re.match(r"(?i)^module\s+(\d{1,3})\b", candidate_title)
            if module and module.group(1) in declared_modules:
                signals.append(f"module number {module.group(1)}")
                recognized_modules.add(module.group(1))
            if signals:
                matches[identifier] = signals
        selected = [candidate["id"] for candidate in candidates if candidate["id"] in matches]
        unknown = declared_modules - recognized_modules
        if unknown:
            names = ", ".join(f"Module {item}" for item in sorted(unknown, key=int))
            return {"mode": "invalid", "source": source, "assessment_ids": selected, "reason": f"The assignment named in the Pull Request {source} is unavailable: {names}. Name exactly one available assignment."}
        if len(selected) == 1:
            return {"mode": "deterministic", "source": source, "assessment_ids": selected, "matches": [{"id": item, "signals": matches[item]} for item in selected]}
        if len(selected) > 1:
            names = [candidate.get("title") or candidate["id"] for candidate in candidates if candidate["id"] in matches]
            return {"mode": "invalid", "source": source, "assessment_ids": selected, "reason": "One Pull Request may select only one assignment, but multiple assignments were identified: " + ", ".join(names)}
    return {"mode": "invalid", "assessment_ids": [], "matches": [], "reason": "No valid assignment was identified. Name exactly one available practical assignment in the Pull Request title or an authorized comment."}


def local_validation(files_root: Path) -> dict[str, Any]:
    path = files_root / LOCAL_VALIDATION_PATH
    unavailable = {"available": False}
    if not path.is_file():
        return unavailable
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return unavailable
    tests = payload.get("tests") if isinstance(payload, dict) else None
    platforms = payload.get("platforms") if isinstance(payload, dict) else None
    duration = payload.get("duration_seconds") if isinstance(payload, dict) else None
    if (
        not isinstance(payload, dict) or payload.get("schema_version") != 1
        or payload.get("source") != "local-pre-commit" or payload.get("status") != "PASSED"
        or not isinstance(payload.get("generated_at"), str) or not UTC_TIMESTAMP.fullmatch(payload["generated_at"])
        or isinstance(duration, bool) or not isinstance(duration, int) or not 0 <= duration <= 86_400
        or not isinstance(platforms, list) or len(platforms) != len(set(platforms))
        or len(platforms) > len(VALIDATION_TARGETS) or any(item not in VALIDATION_TARGETS for item in platforms)
        or not isinstance(tests, dict)
    ):
        return unavailable
    values: dict[str, int] = {}
    for key in ("total", "passed", "skipped", "failed"):
        value = tests.get(key)
        if isinstance(value, bool) or not isinstance(value, int) or not 0 <= value <= 1_000_000:
            return unavailable
        values[key] = value
    if values["total"] != values["passed"] + values["skipped"] + values["failed"] or values["failed"] != 0:
        return unavailable
    return {"available": True, "trusted": False, "source": "local-pre-commit", "status": "PASSED", "generated_at": payload["generated_at"], "duration_seconds": duration, "platforms": platforms, "tests": values}


def changed_paths(api: GitHubApi, repository: str, number: int) -> set[str]:
    return {item["filename"] for item in api.paginate(f"/repos/{repository}/pulls/{number}/files") if item.get("status") != "removed"}


def diagnostic(output: Path, manifest: dict[str, Any], message: str, titles: dict[str, str]) -> int:
    lines = ["## AI review was not started", "", message]
    if titles:
        def display_order(item: tuple[str, str]) -> tuple[int, int, str]:
            module = re.match(r"(?i)^Module\s+(\d+)\b", item[1])
            return (0, int(module.group(1)), item[1].casefold()) if module else (1, 0, item[1].casefold())

        lines.extend(["", "Available practical assignments:"])
        lines.extend(f"- {title}" for _, title in sorted(titles.items(), key=display_order))
    else:
        lines.extend(["", "No valid assessment files were found in the selected learning activity."])
    lines.extend(["", "Correct the assignment selector, caller, or trusted catalog configuration, then ask an authorized collaborator to rerun `/ai-review`."])
    manifest.update({"ready": False, "diagnostic": message, "available_assessment_ids": list(titles)})
    safe_write(output, "manifest.json", json.dumps(manifest, indent=2).encode())
    safe_write(output, "diagnostic.md", "\n".join(lines).encode())
    set_output("ready", "false")
    print(f"Review context requires configuration: {message}")
    return 0


def main() -> int:
    token = require_env("GITHUB_TOKEN")
    repository = require_env("REVIEW_REPOSITORY")
    expected_repository = require_env("EXPECTED_REPOSITORY")
    number = int(require_env("PR_NUMBER"))
    activity_id = require_env("ACTIVITY_ID")
    reviewer_root = Path(require_env("REVIEWER_ROOT"))
    reviewer_sha = require_env("REVIEWER_SHA")
    scope = require_env("REVIEW_SCOPE")
    max_files = int(require_env("MAX_FILES"))
    max_total_bytes = int(require_env("MAX_TOTAL_BYTES"))
    output = Path(require_env("OUTPUT_DIRECTORY"))
    if repository != expected_repository:
        raise ValueError(f"Caller repository mismatch: requested {repository}, workflow runs for {expected_repository}")
    if scope not in {"full_submission", "pull_request_changes"}:
        raise ValueError("REVIEW_SCOPE must be full_submission or pull_request_changes")
    if max_files <= 0 or max_total_bytes <= 0:
        raise ValueError("Safety ceilings must be positive")

    catalog = activity_root(reviewer_root, activity_id)
    api = GitHubApi(token)
    pull = api.get(f"/repos/{repository}/pulls/{number}")
    base_repository = pull["base"]["repo"]["full_name"]
    if base_repository != repository:
        raise ValueError(f"PR base repository {base_repository} does not match caller {repository}")
    head_repository = pull.get("head", {}).get("repo", {}).get("full_name") if pull.get("head", {}).get("repo") else None
    base_sha = pull["base"]["sha"]
    head_sha = pull["head"]["sha"]
    manifest: dict[str, Any] = {
        "schema_version": 6, "ready": False, "activity_id": activity_id,
        "reviewer_sha": reviewer_sha, "scope": scope, "repository": repository,
        "pull_request": number, "base_ref": pull["base"]["ref"], "base_sha": base_sha,
        "head_repository": head_repository, "head_ref": pull["head"]["ref"],
        "head_sha": head_sha, "author": pull.get("user", {}).get("login", ""),
    }
    if not catalog.is_dir():
        return diagnostic(output, manifest, f"Unknown learning activity ID `{activity_id}`.", {})
    available = assessment_files(catalog)
    if not available:
        return diagnostic(output, manifest, f"Learning activity `{activity_id}` contains no valid assessments.", {})

    candidates: list[dict[str, Any]] = []
    titles: dict[str, str] = {}
    for identifier, path in available.items():
        assessment = trusted_file(path)
        metadata = scalar_metadata(assessment)
        titles[identifier] = metadata.get("title", identifier)
        if metadata.get("id") != identifier or not metadata.get("version"):
            return diagnostic(output, manifest, f"Trusted assessment `{path.name}` must declare `id: {identifier}` and a version.", titles)
        safe_write(output / "trusted/assessments", f"{identifier}.yaml", assessment)
        assignment_path = catalog / "assignments" / f"{identifier}.md"
        assignment_available = assignment_path.is_file() and not assignment_path.is_symlink()
        if assignment_available:
            safe_write(output / "trusted/assignments", f"{identifier}.md", trusted_file(assignment_path))
        requirements = requirement_metadata(assessment)
        candidates.append({**metadata, "requirements": requirements, "requirement_ids": [item["id"] for item in requirements], "path": f"{ACTIVITIES_PATH.as_posix()}/{activity_id}/assessments/{path.name}", "assignment": {"available": assignment_available, "path": f"{ACTIVITIES_PATH.as_posix()}/{activity_id}/assignments/{identifier}.md" if assignment_available else None}})

    pr_metadata = {"number": number, "title": pull.get("title", ""), "body": pull.get("body") or "", "base_ref": pull["base"]["ref"], "base_sha": base_sha, "head_ref": pull["head"]["ref"], "head_sha": head_sha, "head_repository": head_repository, "author": manifest["author"]}
    comments = load_comments(api, repository, number)
    selection = select_assessment(pr_metadata["title"], comments, candidates, manifest["author"])
    safe_write(output / "submission", "pull-request.json", json.dumps(pr_metadata, indent=2, ensure_ascii=False).encode())
    if selection["mode"] == "invalid":
        manifest["assessment_selection"] = selection
        return diagnostic(output, manifest, selection["reason"], titles)

    diff = api.request_bytes("GET", f"/repos/{repository}/pulls/{number}", accept="application/vnd.github.v3.diff")
    diff_truncated = len(diff) > MAX_DIFF_BYTES
    if diff_truncated:
        diff = diff[:MAX_DIFF_BYTES] + b"\n\n[Diff truncated by reviewer safety ceiling]\n"
    safe_write(output / "submission", "pull-request.diff", diff)
    old_review = previous_review(comments)
    if old_review:
        safe_write(output / "submission", "previous-review.md", old_review.encode())

    tree_payload = api.get(f"/repos/{base_repository}/git/trees/{quote(head_sha)}?recursive=1")
    wanted = changed_paths(api, repository, number) if scope == "pull_request_changes" else None
    downloaded: list[str] = []
    skipped: list[dict[str, str]] = []
    total = 0
    ceiling = False
    files_root = output / "submission/files"
    for item in tree_payload.get("tree", []):
        if item.get("type") != "blob":
            continue
        path = item["path"]
        if wanted is not None and path not in wanted:
            continue
        reason = exclusion_reason(path, item.get("mode", ""))
        if reason:
            skipped.append({"path": path, "reason": reason})
            continue
        declared_size = int(item.get("size") or 0)
        if declared_size > MAX_FILE_BYTES:
            skipped.append({"path": path, "reason": "file exceeds 1 MiB safety ceiling"})
            continue
        if len(downloaded) >= max_files or total + declared_size > max_total_bytes:
            skipped.append({"path": path, "reason": "repository safety ceiling reached"})
            ceiling = True
            continue
        blob = api.get(f"/repos/{base_repository}/git/blobs/{item['sha']}")
        data, reason = decode_blob(blob.get("content", "").replace("\n", ""))
        if data is None:
            skipped.append({"path": path, "reason": reason or "unreadable blob"})
            continue
        if total + len(data) > max_total_bytes:
            skipped.append({"path": path, "reason": "repository safety ceiling reached"})
            ceiling = True
            continue
        safe_write(files_root, path, data)
        downloaded.append(path)
        total += len(data)

    manifest.update({"ready": True, "candidate_assessments": candidates, "available_assessment_ids": list(available), "assessment_selection": selection, "downloaded_file_count": len(downloaded), "downloaded_text_bytes": total, "downloaded_files": downloaded, "skipped_files": skipped, "github_tree_truncated": bool(tree_payload.get("truncated")), "diff_truncated": diff_truncated, "safety_ceiling_reached": ceiling, "previous_review_available": old_review is not None, "local_validation": local_validation(files_root)})
    safe_write(output, "manifest.json", json.dumps(manifest, indent=2).encode())
    set_output("ready", "true")
    print(f"Prepared {len(candidates)} candidate assessment(s) and {len(downloaded)} safe text files ({total} bytes); skipped {len(skipped)} files.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, ValueError, KeyError, urllib.error.URLError) as error:
        set_output("ready", "false")
        print(f"Context preparation failed: {error}", file=sys.stderr)
        raise SystemExit(1)
