#!/usr/bin/env python3
from __future__ import annotations

import html
import json
import os
from pathlib import Path
from typing import Any

from common import load_json, require_env
from github_api import GitHubApi, GitHubApiError, quote
from review_result import validate_review


MARKER_PREFIX = "<!-- learning-pr-ai-reviewer:"
LABELS = {
    "PASSED": ("ai-review/passed", "2da44e"),
    "PASSED WITH NOTES": ("ai-review/passed-with-notes", "bf8700"),
    "FAILED": ("ai-review/failed", "cf222e"),
}
ICONS = {"MET": "✅", "PARTIAL": "⚠️", "NOT_MET": "❌", "NOT_VERIFIABLE": "ℹ️"}


def safe(value: Any, limit: int = 4000) -> str:
    return html.escape(str(value).replace("<!--", "&lt;!--")[:limit])


def criterion_titles(assessment: dict[str, Any]) -> dict[str, str]:
    return {item["id"]: item.get("title", item["id"]) for item in assessment["criteria"]}


def render_review(
    review: dict[str, Any],
    assessment: dict[str, Any],
    metadata: dict[str, Any],
    provider: str,
    model: str,
    run_url: str,
) -> str:
    titles = criterion_titles(assessment)
    lines = [
        f"{MARKER_PREFIX}{metadata['fingerprint']} -->",
        "## Automated learning submission review",
        "",
        f"**Advisory result: {review['verdict']}**",
        "",
        safe(review["summary"]),
        "",
        "### Criteria",
    ]
    for item in review["criteria"]:
        lines.extend(
            [
                "",
                f"#### {ICONS[item['result']]} {safe(titles[item['id']])} — {item['result']}",
                "",
                safe(item["explanation"]),
            ]
        )
        if item["evidence"]:
            lines.extend(["", "Evidence:"] + [f"- {safe(value, 1000)}" for value in item["evidence"]])
        if item["action_items"]:
            lines.extend(["", "Suggested actions:"] + [f"- {safe(value, 1000)}" for value in item["action_items"]])
    validation = metadata.get("local_validation")
    if validation:
        lines.extend(
            [
                "",
                "### Participant-provided local validation",
                "",
                "This information was submitted with the repository and was not independently executed by the reviewer.",
                "",
                "```json",
                json.dumps(validation, indent=2, ensure_ascii=False)[:6000].replace("```", "` ` `"),
                "```",
            ]
        )
    lines.extend(
        [
            "",
            "---",
            f"Reviewed `{metadata['head_sha'][:12]}` against assessment version `{safe(metadata['assessment_version'])}` ",
            f"using `{safe(provider)}/{safe(model)}`. [Workflow run]({run_url})",
            "",
            "This automated result is advisory; a qualified human reviewer makes the final assessment.",
        ]
    )
    return "\n".join(lines)


def render_failure(metadata: dict[str, Any], run_url: str, detail: str) -> str:
    return "\n".join(
        [
            f"{MARKER_PREFIX}{metadata['fingerprint']} -->",
            "## Automated learning submission review",
            "",
            "The reviewer could not produce a valid assessment report.",
            "",
            f"Reason: {safe(detail, 1500)}",
            "",
            f"[Inspect the workflow run]({run_url}) and retry after resolving the infrastructure or configuration problem.",
        ]
    )


def upsert_comment(api: GitHubApi, repository: str, number: int, body: str) -> None:
    comments = api.paginate(f"/repos/{repository}/issues/{number}/comments")
    existing = next(
        (comment for comment in comments if MARKER_PREFIX in comment.get("body", "")),
        None,
    )
    if existing:
        api.patch(f"/repos/{repository}/issues/comments/{existing['id']}", {"body": body})
    else:
        api.post(f"/repos/{repository}/issues/{number}/comments", {"body": body})


def apply_verdict_label(api: GitHubApi, repository: str, number: int, verdict: str) -> None:
    desired, color = LABELS[verdict]
    existing = {item["name"] for item in api.paginate(f"/repos/{repository}/labels")}
    if desired not in existing:
        api.post(f"/repos/{repository}/labels", {"name": desired, "color": color})
    for label, _ in LABELS.values():
        if label == desired:
            continue
        try:
            api.delete(f"/repos/{repository}/issues/{number}/labels/{quote(label)}")
        except GitHubApiError as error:
            if error.status != 404:
                raise
    api.post(f"/repos/{repository}/issues/{number}/labels", {"labels": [desired]})


def main() -> None:
    context = Path(require_env("REVIEW_CONTEXT"))
    output = Path(require_env("REVIEW_OUTPUT"))
    if not (context / "metadata.json").is_file():
        print("No new review context was prepared; publication is skipped.")
        return
    metadata = load_json(context / "metadata.json")
    assessment = load_json(context / "trusted" / "assessment.json")
    api = GitHubApi(require_env("GITHUB_TOKEN"))
    repository = require_env("REVIEW_REPOSITORY")
    number = int(require_env("PR_NUMBER"))
    run_url = os.environ.get("WORKFLOW_RUN_URL", "")
    try:
        if not output.is_file():
            raise ValueError("The AI review step did not create a result file")
        review = validate_review(load_json(output), assessment)
        body = render_review(
            review,
            assessment,
            metadata,
            os.environ.get("REVIEW_PROVIDER", "unknown"),
            os.environ.get("REVIEW_MODEL", "unknown"),
            run_url,
        )
        upsert_comment(api, repository, number, body)
        apply_verdict_label(api, repository, number, review["verdict"])
    except Exception as error:
        upsert_comment(api, repository, number, render_failure(metadata, run_url, str(error)))
        raise


if __name__ == "__main__":
    main()
