from __future__ import annotations

import fnmatch
import copy
import os
from pathlib import Path, PurePosixPath
from typing import Any


MAX_READ_LINES = 400
MAX_TOOL_OUTPUT_CHARS = 60_000


GENERIC_TOOL_SPECS: list[dict[str, Any]] = [
    {
        "name": "list_files",
        "description": "List regular files inside review-context using a case-sensitive glob. Use **/* to list everything. Returns at most 500 paths.",
        "schema": {
            "type": "object", "additionalProperties": False, "required": ["pattern"],
            "properties": {"pattern": {"type": "string", "minLength": 1}},
        },
    },
    {
        "name": "read_file",
        "description": f"Read an inclusive line range from one UTF-8 file inside review-context. At most {MAX_READ_LINES} lines are returned.",
        "schema": {
            "type": "object", "additionalProperties": False,
            "required": ["path", "start_line", "end_line"],
            "properties": {
                "path": {"type": "string", "minLength": 1},
                "start_line": {"type": "integer", "minimum": 1},
                "end_line": {"type": "integer", "minimum": 1},
            },
        },
    },
    {
        "name": "search_text",
        "description": "Search for literal case-insensitive text in UTF-8 review-context files and return exact path:line evidence.",
        "schema": {
            "type": "object", "additionalProperties": False,
            "required": ["query", "pattern", "max_results"],
            "properties": {
                "query": {"type": "string", "minLength": 1, "maxLength": 200},
                "pattern": {"type": "string", "minLength": 1},
                "max_results": {"type": "integer", "minimum": 1, "maximum": 100},
            },
        },
    },
]


def openai_tools() -> list[dict[str, Any]]:
    return [
        {"type": "function", "name": item["name"], "description": item["description"], "strict": True, "parameters": item["schema"]}
        for item in GENERIC_TOOL_SPECS
    ]


def anthropic_tools() -> list[dict[str, Any]]:
    return [
        {"name": item["name"], "description": item["description"], "strict": True, "input_schema": anthropic_schema(item["schema"])}
        for item in GENERIC_TOOL_SPECS
    ]


def anthropic_schema(schema: dict[str, Any]) -> dict[str, Any]:
    """Return the strict JSON Schema subset currently accepted by Claude."""
    result = copy.deepcopy(schema)

    def visit(value: Any) -> None:
        if isinstance(value, dict):
            for unsupported in ("minLength", "maxLength", "minimum", "maximum", "maxItems"):
                value.pop(unsupported, None)
            if isinstance(value.get("minItems"), int) and value["minItems"] > 1:
                value["minItems"] = 1
            for child in value.values():
                visit(child)
        elif isinstance(value, list):
            for child in value:
                visit(child)

    visit(result)
    return result


def truncate(value: str) -> str:
    if len(value) <= MAX_TOOL_OUTPUT_CHARS:
        return value
    return value[:MAX_TOOL_OUTPUT_CHARS] + "\n[tool output truncated]"


class ReadOnlyReviewTools:
    def __init__(self, root: Path) -> None:
        self.root = root.resolve()
        if not self.root.is_dir():
            raise ValueError(f"Review context does not exist: {root}")

    def _path(self, relative: str) -> Path:
        candidate = PurePosixPath(relative)
        if candidate.is_absolute() or ".." in candidate.parts:
            raise ValueError("Path must remain inside review-context")
        resolved = (self.root / Path(*candidate.parts)).resolve()
        if os.path.commonpath((str(self.root), str(resolved))) != str(self.root):
            raise ValueError("Path resolves outside review-context")
        if not resolved.is_file() or resolved.is_symlink():
            raise ValueError(f"Not a readable regular file: {relative}")
        return resolved

    def _files(self, pattern: str) -> list[tuple[str, Path]]:
        files: list[tuple[str, Path]] = []
        for path in sorted(self.root.rglob("*")):
            if not path.is_file() or path.is_symlink():
                continue
            relative = path.relative_to(self.root).as_posix()
            if pattern in {"*", "**", "**/*"} or fnmatch.fnmatchcase(relative, pattern):
                files.append((relative, path))
        return files

    def call(self, name: str, arguments: dict[str, Any]) -> str:
        if name == "list_files":
            files = self._files(str(arguments["pattern"]))
            output = [relative for relative, _ in files[:500]]
            if len(files) > 500:
                output.append(f"[truncated: {len(files) - 500} additional files]")
            return "\n".join(output) or "[no matching files]"
        if name == "read_file":
            path = self._path(str(arguments["path"]))
            start = int(arguments["start_line"])
            end = int(arguments["end_line"])
            if start < 1 or end < start:
                raise ValueError("Invalid line range")
            end = min(end, start + MAX_READ_LINES - 1)
            lines = path.read_text(encoding="utf-8").splitlines()[start - 1 : end]
            return truncate("\n".join(f"{number}: {line}" for number, line in enumerate(lines, start)) or "[requested range is empty]")
        if name == "search_text":
            query = str(arguments["query"]).casefold()
            limit = int(arguments["max_results"])
            matches: list[str] = []
            for relative, path in self._files(str(arguments["pattern"])):
                for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
                    if query in line.casefold():
                        matches.append(f"{relative}:{number}: {line}")
                        if len(matches) >= limit:
                            return truncate("\n".join(matches))
            return truncate("\n".join(matches) or "[no matches]")
        raise ValueError(f"Unknown tool: {name}")
