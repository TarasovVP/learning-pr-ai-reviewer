from __future__ import annotations

import json
import os
import re
from pathlib import Path
from typing import Any


def require_env(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise ValueError(f"{name} is required")
    return value


def load_json(path: Path) -> Any:
    with path.open(encoding="utf-8") as source:
        return json.load(source)


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def set_output(name: str, value: str) -> None:
    output_path = os.environ.get("GITHUB_OUTPUT")
    if output_path:
        with open(output_path, "a", encoding="utf-8") as output:
            output.write(f"{name}={value}\n")
    else:
        print(f"{name}={value}")


def slugify(value: str) -> str:
    value = value.strip().lower()
    value = re.sub(r"[^a-z0-9]+", "-", value)
    return value.strip("-")


def validate_activity_id(value: str) -> str:
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{1,62}", value):
        raise ValueError("activity_id must contain lowercase letters, digits, and hyphens")
    return value


def select_catalog_entry(root: Path, activity_id: str, pr_title: str) -> tuple[Path, Path, dict[str, Any]]:
    activity = root / ".ai-review" / "activities" / activity_id
    assignment_dir = activity / "assignments"
    assessment_dir = activity / "assessments"
    requested_slug = slugify(pr_title)
    assignment_path = assignment_dir / f"{requested_slug}.md"
    assessment_path = assessment_dir / f"{requested_slug}.json"
    if not assignment_path.is_file() or not assessment_path.is_file():
        available = sorted(path.stem for path in assignment_dir.glob("*.md")) if assignment_dir.is_dir() else []
        raise ValueError(
            f"No trusted assignment matches Pull Request title {pr_title!r}. "
            f"Expected slug {requested_slug!r}; available: {available}"
        )
    assessment = load_json(assessment_path)
    if assessment.get("title") != pr_title.strip():
        raise ValueError("Assessment title must exactly match the Pull Request title")
    criteria = assessment.get("criteria")
    if not isinstance(criteria, list) or not criteria:
        raise ValueError("Assessment must contain a non-empty criteria array")
    identifiers = [criterion.get("id") for criterion in criteria]
    if any(not isinstance(identifier, str) or not identifier for identifier in identifiers):
        raise ValueError("Every assessment criterion must have a non-empty id")
    if len(set(identifiers)) != len(identifiers):
        raise ValueError("Assessment criterion ids must be unique")
    return assignment_path, assessment_path, assessment
