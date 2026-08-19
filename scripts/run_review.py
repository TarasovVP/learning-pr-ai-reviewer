#!/usr/bin/env python3
from __future__ import annotations

import json
import os
from pathlib import Path

from agent_tools import ReadOnlyTools
from common import load_json, require_env, write_json
from provider_clients import create_client


def build_user_message(context: Path) -> str:
    assignment = (context / "trusted" / "assignment.md").read_text(encoding="utf-8")
    assessment = load_json(context / "trusted" / "assessment.json")
    metadata = load_json(context / "metadata.json")
    manifest = load_json(context / "submission" / "manifest.json")
    return "\n\n".join(
        [
            "# Trusted assignment\n" + assignment,
            "# Trusted assessment\n" + json.dumps(assessment, indent=2, ensure_ascii=False),
            "# Submission metadata\n" + json.dumps(metadata, indent=2, ensure_ascii=False),
            "# Available submission files\n" + json.dumps(manifest, indent=2, ensure_ascii=False),
            "Inspect the submission with the read-only tools and return the structured review.",
        ]
    )


def main() -> None:
    context = Path(require_env("REVIEW_CONTEXT"))
    output = Path(require_env("REVIEW_OUTPUT"))
    provider = require_env("AI_PROVIDER").lower()
    client = create_client(
        provider,
        require_env("AI_API_KEY"),
        require_env("AI_MODEL"),
        os.environ.get("AI_REASONING_EFFORT", "medium"),
    )
    system = (context / "trusted" / "review-prompt.md").read_text(encoding="utf-8")
    schema = load_json(context / "trusted" / "review-schema.json")
    result = client.review(system, build_user_message(context), schema, ReadOnlyTools(context))
    write_json(output, result)


if __name__ == "__main__":
    main()
