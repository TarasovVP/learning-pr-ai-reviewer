#!/usr/bin/env python3
"""Validate, render, and publish a structured advisory review."""

from __future__ import annotations

import json
import os
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from github_api import GitHubApi, GitHubApiError, quote

MARKER = "<!-- learning-pr-ai-reviewer:pr -->"
SUBMISSION_FOOTER = """### Updating this submission

This Pull Request is automatically closed to protect the parent learning repository from accidental merges. Find it under **Pull requests → Closed**. A closed Pull Request remains attached to its recorded commit. After changing the implementation, push the correction to the fork and open a new Pull Request to review the new commit. Use `/ai-review` here only to rerun that recorded commit, for example after correcting the assignment selector or a temporary reviewer failure."""
MAX_COMMENT_CHARS = 60_000
SAFE_ID = re.compile(r"^[a-z0-9][a-z0-9-]*$")
SUBMISSION_REFERENCE = re.compile(r"(?P<path>submission/files/[^\n]+?):(?P<line>\d+)(?P<range>-\d+)?")
VERDICTS = {
    "FAILED": ("ai-verdict/failed", "b60205"),
    "PASSED WITH NOTES": ("ai-verdict/passed-with-notes", "fbca04"),
    "PASSED": ("ai-verdict/passed", "0e8a16"),
}


def requirement_verdict(statuses: list[str]) -> str:
    if "NOT_MET" in statuses:
        return "FAILED"
    if any(status in {"PARTIAL", "NOT_VERIFIABLE"} for status in statuses):
        return "PASSED WITH NOTES"
    return "PASSED"


def _normalize_evidence(text: str, downloaded: list[str]) -> str:
    allowed = {f"submission/files/{path}": path for path in downloaded if path}

    def replace(match: re.Match[str]) -> str:
        reference = match.group("path")
        if reference in allowed:
            resolved = reference
        else:
            short = reference.removeprefix("submission/files/")
            matches = [path for path in allowed if path.endswith(f"/{short}")]
            if not matches:
                raise ValueError(f"Evidence references a file outside the submitted context: {reference}")
            if len(matches) > 1:
                raise ValueError(f"Evidence uses an ambiguous shortened path: {reference}")
            resolved = matches[0]
        return f"{resolved}:{match.group('line')}{match.group('range') or ''}"

    return SUBMISSION_REFERENCE.sub(replace, text)


def normalize_evidence_paths(review: dict[str, Any], manifest: dict[str, Any]) -> None:
    downloaded = manifest.get("downloaded_files")
    if not isinstance(downloaded, list):
        return
    for assessment in review.get("assessments", []):
        for requirement in assessment.get("requirements", []):
            evidence = requirement.get("evidence")
            if isinstance(evidence, str):
                requirement["evidence"] = _normalize_evidence(evidence, downloaded)
    for finding in review.get("findings", []):
        evidence = finding.get("evidence")
        if isinstance(evidence, list):
            finding["evidence"] = [
                _normalize_evidence(item, downloaded) if isinstance(item, str) else item
                for item in evidence
            ]


def validate_review(review: Any, manifest: dict[str, Any]) -> tuple[str, list[str]]:
    if not isinstance(review, dict):
        raise ValueError("Structured review must be a JSON object")
    normalize_evidence_paths(review, manifest)
    assessments = review.get("assessments")
    if not isinstance(assessments, list) or len(assessments) != 1:
        raise ValueError("Structured review must contain exactly one assessment")
    candidates = {
        item["id"]: item for item in manifest.get("candidate_assessments", [])
        if isinstance(item, dict) and isinstance(item.get("id"), str)
    }
    selected = [assessment.get("id") for assessment in assessments]
    if any(not isinstance(item, str) or not SAFE_ID.fullmatch(item) for item in selected):
        raise ValueError("Structured review contains an invalid assessment ID")
    if len(set(selected)) != len(selected):
        raise ValueError("Structured review contains a duplicated assessment ID")
    unknown = [item for item in selected if item not in candidates]
    if unknown:
        raise ValueError("Structured review selected assessments outside the trusted catalog: " + ", ".join(unknown))
    selection = manifest.get("assessment_selection", {})
    if selection.get("mode") == "deterministic" and selected != selection.get("assessment_ids", []):
        raise ValueError(
            "Structured review assessment IDs do not match the deterministic selection: "
            f"expected {selection.get('assessment_ids', [])}, got {selected}"
        )

    fixable = False
    for assessment in assessments:
        identifier = assessment["id"]
        expected = candidates[identifier]
        if assessment.get("title") != expected.get("title"):
            raise ValueError(f"Assessment {identifier} title does not match the trusted assessment")
        requirements = assessment.get("requirements")
        if not isinstance(requirements, list) or not requirements:
            raise ValueError(f"Assessment {identifier} contains no requirement results")
        actual_ids = [item.get("id") for item in requirements]
        expected_ids = expected.get("requirement_ids", [])
        if actual_ids != expected_ids:
            raise ValueError(
                f"Assessment {identifier} requirement IDs do not match the trusted assessment: "
                f"expected {expected_ids}, got {actual_ids}"
            )
        statuses = [item.get("status") for item in requirements]
        if any(status not in {"MET", "PARTIAL", "NOT_MET", "NOT_VERIFIABLE"} for status in statuses):
            raise ValueError(f"Assessment {identifier} contains an invalid requirement status")
        fixable = fixable or any(status in {"PARTIAL", "NOT_MET"} for status in statuses)
        expected_verdict = requirement_verdict(statuses)
        if assessment.get("verdict") != expected_verdict:
            raise ValueError(
                f"Assessment {identifier} verdict {assessment.get('verdict')!r} does not match "
                f"its requirement statuses ({expected_verdict!r})"
            )
    verdict = assessments[0]["verdict"]
    if review.get("verdict") != verdict:
        raise ValueError(f"Report verdict {review.get('verdict')!r} does not match assessment verdict {verdict!r}")
    actions = review.get("action_items")
    if not isinstance(actions, list) or any(not isinstance(item, str) or not item.strip() for item in actions):
        raise ValueError("Structured review action_items must be a list of non-empty strings")
    if fixable and not actions:
        raise ValueError("PARTIAL and NOT_MET requirements must produce action items")
    if not fixable and actions:
        raise ValueError("Action items are allowed only for PARTIAL or NOT_MET requirements")
    return verdict, selected


def coverage_warnings(manifest: dict[str, Any]) -> list[str]:
    warnings: list[str] = []
    if manifest.get("github_tree_truncated"):
        warnings.append("GitHub returned a truncated repository tree, so some submitted files may not have been analyzed.")
    if manifest.get("diff_truncated"):
        warnings.append("The Pull Request diff was truncated, so some changed-line evidence may be unavailable.")
    if manifest.get("safety_ceiling_reached"):
        warnings.append("Some safe text files were not analyzed because the configured context ceiling was reached.")
    return warnings


def about_review(provider: str, model: str) -> str:
    return f"""> [!NOTE]
> This report was generated by **Learning PR AI Reviewer** using `{provider}/{model}`. It is a preliminary static review against the trusted assignment and assessment configured for this learning activity.
>
> The reviewer reads Pull Request metadata and safe text files. It does not execute project code, builds, tests, scripts, or workflows. Build and test claims remain unverified unless trusted CI provides them.
>
> If the wrong assignment was selected, update the Pull Request title or add an authorized comment that clearly names one assignment, then rerun `/ai-review`."""


def local_validation_section(manifest: dict[str, Any]) -> list[str]:
    validation = manifest.get("local_validation")
    if not isinstance(validation, dict) or not validation.get("available"):
        return []
    tests = validation["tests"]
    platforms = validation.get("platforms") or []
    targets = ", ".join(platforms) if platforms else "No platform tasks were reported"
    return [
        "## Local validation evidence", "",
        "> [!NOTE]", "> This summary was generated and committed by the participant. It is contextual evidence, not trusted CI.", "",
        f"- **Result:** ✅ {validation['status']}", f"- **Targets:** {targets}",
        f"- **Unit tests:** {tests['passed']} passed, {tests['skipped']} skipped, {tests['failed']} failed",
        f"- **Duration:** {validation['duration_seconds']}s", f"- **Generated:** {validation['generated_at']}", "",
    ]


def render_review(review: dict[str, Any], manifest: dict[str, Any]) -> str:
    candidates = {item["id"]: item for item in manifest.get("candidate_assessments", [])}
    assessment = review["assessments"][0]
    status_icons = {"MET": "✅", "PARTIAL": "🟡", "NOT_MET": "❌", "NOT_VERIFIABLE": "⚪"}
    verdict_icons = {"PASSED": "✅", "PASSED WITH NOTES": "🟡", "FAILED": "❌"}
    lines = [
        "## Summary", "", review["summary"], "",
        f"**Verdict:** {verdict_icons[review['verdict']]} {review['verdict']}", "",
        *local_validation_section(manifest), "## Assessment criteria",
    ]
    titles = {item.get("id"): item.get("title") for item in candidates[assessment["id"]].get("requirements", [])}
    for requirement in assessment["requirements"]:
        identifier = requirement["id"]
        title = titles.get(identifier) or identifier.replace("-", " ").title()
        status = requirement["status"]
        lines.extend(["", f"### {title} — {status_icons[status]} {status.replace('_', ' ')}", "", "**Evidence**", "", requirement["evidence"]])
    lines.extend(["", "## Findings", ""])
    if review["findings"]:
        for finding in review["findings"]:
            suffix = f" Evidence: {'; '.join(finding['evidence'])}" if finding["evidence"] else ""
            lines.append(f"- **{finding['title']}** — {finding['details']}{suffix}")
    elif all(item["status"] == "MET" for item in assessment["requirements"]):
        lines.append("_None. All assessment criteria are met._")
    else:
        lines.append("_No blocking findings. See the per-requirement statuses and action items._")
    warnings = coverage_warnings(manifest)
    if warnings:
        lines.extend(["", "## Review coverage", ""])
        lines.extend(f"- ⚠️ {warning}" for warning in warnings)
    if review["action_items"]:
        lines.extend(["", "## Action items", ""])
        lines.extend(f"- [ ] {item}" for item in review["action_items"])
    return "\n".join(lines)


def find_comment(api: GitHubApi, repository: str, number: int) -> dict[str, Any] | None:
    matches = [
        comment for comment in api.paginate(f"/repos/{repository}/issues/{number}/comments")
        if MARKER in comment.get("body", "") and comment.get("user", {}).get("login") == "github-actions[bot]"
    ]
    return matches[-1] if matches else None


def build_comment(rendered: str, manifest: dict[str, Any], selected: list[str], provider: str, model: str, run_url: str, verdict: str) -> str:
    candidates = {item["id"]: item for item in manifest.get("candidate_assessments", [])}
    assessment = candidates[selected[0]]
    metadata = {
        "base_sha": manifest["base_sha"], "head_sha": manifest["head_sha"],
        "activity_id": manifest["activity_id"], "reviewer_sha": manifest["reviewer_sha"],
        "submission_author": manifest.get("author", ""), "selected_assessment": assessment,
        "scope": manifest["scope"],
    }
    now = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    title = f"## 🤖 {assessment.get('title') or selected[0]} — AI Review"
    if manifest.get("author", "").strip():
        title += f" for @{manifest['author'].strip()}"
    body = "\n".join([
        MARKER, f"<!-- learning-pr-ai-reviewer:meta {json.dumps(metadata, separators=(',', ':'))} -->",
        title, f"_Updated **{now}** for commit `{manifest['head_sha'][:7]}` · [workflow run]({run_url})_",
        "", about_review(provider, model), "", rendered.rstrip(),
        *(["", SUBMISSION_FOOTER] if verdict != "PASSED" else []),
    ])
    if len(body) > MAX_COMMENT_CHARS:
        body = body[:MAX_COMMENT_CHARS] + "\n\n_Report truncated to fit the GitHub comment limit._"
    return body


def build_diagnostic(diagnostic: str, manifest: dict[str, Any], run_url: str) -> str:
    now = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    metadata = {
        "base_sha": manifest["base_sha"], "head_sha": manifest["head_sha"],
        "activity_id": manifest.get("activity_id"), "reviewer_sha": manifest.get("reviewer_sha"),
        "scope": manifest["scope"], "ready": False,
    }
    return "\n".join([
        MARKER, f"<!-- learning-pr-ai-reviewer:meta {json.dumps(metadata, separators=(',', ':'))} -->",
        "## 🤖 Learning PR AI Review",
        f"_Updated **{now}** for commit `{manifest['head_sha'][:7]}` · [workflow run]({run_url})_",
        "", diagnostic.strip(), "", "_No model request was made._", "", SUBMISSION_FOOTER,
    ])


def upsert_comment(api: GitHubApi, repository: str, number: int, body: str) -> tuple[dict[str, Any], str]:
    existing = find_comment(api, repository, number)
    if existing:
        return api.patch(f"/repos/{repository}/issues/comments/{existing['id']}", {"body": body}), "updated"
    return api.post(f"/repos/{repository}/issues/{number}/comments", {"body": body}), "created"


def update_labels(api: GitHubApi, repository: str, number: int, verdict: str) -> None:
    selected, color = VERDICTS[verdict]
    try:
        api.post(f"/repos/{repository}/labels", {"name": selected, "color": color})
    except GitHubApiError as error:
        if error.status != 422:
            raise
    for label, _ in VERDICTS.values():
        if label == selected:
            continue
        try:
            api.delete(f"/repos/{repository}/issues/{number}/labels/{quote(label)}")
        except GitHubApiError as error:
            if error.status != 404:
                raise
    api.post(f"/repos/{repository}/issues/{number}/labels", {"labels": [selected]})


def clear_labels(api: GitHubApi, repository: str, number: int) -> None:
    for label, _ in VERDICTS.values():
        try:
            api.delete(f"/repos/{repository}/issues/{number}/labels/{quote(label)}")
        except GitHubApiError as error:
            if error.status != 404:
                raise


def main() -> int:
    repository = os.environ["REVIEW_REPOSITORY"]
    number = int(os.environ["PR_NUMBER"])
    run_url = os.environ["WORKFLOW_RUN_URL"]
    manifest_path = Path(os.environ["CONTEXT_MANIFEST"])
    if not manifest_path.is_file():
        print("Review context manifest is missing; leaving the previous review unchanged.")
        return 0
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    api = GitHubApi(os.environ["GITHUB_TOKEN"])
    diagnostic_value = os.environ.get("CONTEXT_DIAGNOSTIC", "").strip()
    diagnostic_path = Path(diagnostic_value) if diagnostic_value else None
    if diagnostic_path and diagnostic_path.is_file():
        body = build_diagnostic(diagnostic_path.read_text(encoding="utf-8"), manifest, run_url)
        result, operation = upsert_comment(api, repository, number, body)
        clear_labels(api, repository, number)
        print(f"Diagnostic comment {operation}: {result.get('html_url', '')}")
        return 0
    review_value = os.environ.get("REVIEW_FILE", "").strip()
    review_path = Path(review_value) if review_value else None
    if not review_path or not review_path.is_file():
        print("The model did not produce a structured review; leaving the previous review unchanged.")
        return 0
    review = json.loads(review_path.read_text(encoding="utf-8"))
    verdict, selected = validate_review(review, manifest)
    body = build_comment(
        render_review(review, manifest), manifest, selected,
        os.environ.get("REVIEW_PROVIDER", "unknown"), os.environ["REVIEW_MODEL"], run_url, verdict,
    )
    result, operation = upsert_comment(api, repository, number, body)
    update_labels(api, repository, number, verdict)
    print(f"Review comment {operation}: {result.get('html_url', '')}")
    print(f"Advisory verdict: {verdict}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, ValueError, KeyError, json.JSONDecodeError) as error:
        print(f"Review publication failed: {error}", file=sys.stderr)
        raise SystemExit(1)
