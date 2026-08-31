#!/usr/bin/env python3
"""Run a provider-neutral, read-only assessment agent with a constrained output schema."""

from __future__ import annotations

import copy
import json
import os
import sys
from pathlib import Path
from typing import Any

from agent_tools import ReadOnlyReviewTools
from common import set_output
from provider_clients import client_for


BASE_SCHEMA: dict[str, Any] = {
    "type": "object", "additionalProperties": False,
    "required": ["summary", "verdict", "assessments", "findings", "action_items"],
    "properties": {
        "summary": {"type": "string", "minLength": 1},
        "verdict": {"type": "string", "enum": ["PASSED", "PASSED WITH NOTES", "FAILED"]},
        "assessments": {
            "type": "array", "minItems": 1, "maxItems": 1,
            "items": {
                "type": "object", "additionalProperties": False,
                "required": ["id", "title", "verdict", "requirements"],
                "properties": {
                    "id": {"type": "string", "pattern": "^[a-z0-9][a-z0-9-]*$"},
                    "title": {"type": "string", "minLength": 1},
                    "verdict": {"type": "string", "enum": ["PASSED", "PASSED WITH NOTES", "FAILED"]},
                    "requirements": {
                        "type": "array", "minItems": 1,
                        "items": {
                            "type": "object", "additionalProperties": False,
                            "required": ["id", "status", "evidence"],
                            "properties": {
                                "id": {"type": "string", "pattern": "^[a-z0-9][a-z0-9-]*$"},
                                "status": {"type": "string", "enum": ["MET", "PARTIAL", "NOT_MET", "NOT_VERIFIABLE"]},
                                "evidence": {"type": "string", "minLength": 1},
                            },
                        },
                    },
                },
            },
        },
        "findings": {
            "type": "array",
            "items": {
                "type": "object", "additionalProperties": False,
                "required": ["title", "details", "evidence"],
                "properties": {
                    "title": {"type": "string", "minLength": 1},
                    "details": {"type": "string", "minLength": 1},
                    "evidence": {"type": "array", "items": {"type": "string", "minLength": 1}},
                },
            },
        },
        "action_items": {"type": "array", "items": {"type": "string", "minLength": 1}},
    },
}


def schema_for_manifest(manifest: dict[str, Any]) -> dict[str, Any]:
    selection = manifest.get("assessment_selection", {})
    selected = selection.get("assessment_ids", [])
    if selection.get("mode") != "deterministic" or len(selected) != 1:
        raise ValueError("Manifest must contain one deterministic assessment selection")
    identifier = selected[0]
    candidates = {item.get("id"): item for item in manifest.get("candidate_assessments", [])}
    candidate = candidates.get(identifier)
    if not isinstance(candidate, dict):
        raise ValueError("Selected assessment is missing from the manifest")
    requirement_ids = candidate.get("requirement_ids", [])
    if not requirement_ids or any(not isinstance(item, str) for item in requirement_ids):
        raise ValueError("Selected assessment has no trusted requirement IDs")
    schema = copy.deepcopy(BASE_SCHEMA)
    assessment = schema["properties"]["assessments"]["items"]
    assessment["properties"]["id"] = {"type": "string", "enum": [identifier]}
    if candidate.get("title"):
        assessment["properties"]["title"] = {"type": "string", "enum": [candidate["title"]]}
    requirements = assessment["properties"]["requirements"]
    requirements["minItems"] = len(requirement_ids)
    requirements["maxItems"] = len(requirement_ids)
    requirements["items"]["properties"]["id"] = {"type": "string", "enum": requirement_ids}
    return schema


def main() -> int:
    api_key = os.environ.get("AI_API_KEY", "").strip()
    if not api_key:
        raise ValueError("AI_API_KEY is required")
    provider = os.environ.get("AI_PROVIDER", "anthropic").strip().lower()
    model = os.environ.get("REVIEW_MODEL", "").strip()
    if not model:
        raise ValueError("REVIEW_MODEL is required")
    effort = os.environ.get("REASONING_EFFORT", "medium").strip()
    if effort not in {"none", "low", "medium", "high", "xhigh", "max"}:
        raise ValueError("REASONING_EFFORT is invalid")
    prompt = Path(os.environ["REVIEW_PROMPT_FILE"]).read_text(encoding="utf-8")
    context = Path(os.environ.get("REVIEW_CONTEXT", "review-context"))
    output = Path(os.environ.get("REVIEW_OUTPUT", "ai-review.json"))
    manifest = json.loads((context / "manifest.json").read_text(encoding="utf-8"))
    review = client_for(provider, api_key).review(model, effort, prompt, schema_for_manifest(manifest), ReadOnlyReviewTools(context))
    output.write_text(json.dumps(review, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    set_output("review_file", str(output.resolve()))
    print(f"AI review completed with {provider}/{model}; structured report saved.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, ValueError, KeyError, json.JSONDecodeError) as error:
        print(f"AI review failed: {error}", file=sys.stderr)
        raise SystemExit(1)
