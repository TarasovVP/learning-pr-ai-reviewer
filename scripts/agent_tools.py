from __future__ import annotations

import fnmatch
import re
from pathlib import Path
from typing import Any


class ReadOnlyTools:
    def __init__(self, context_root: Path) -> None:
        self.context_root = context_root.resolve()
        self.files_root = (self.context_root / "submission" / "files").resolve()

    def _resolve(self, relative: str) -> Path:
        candidate = (self.files_root / relative).resolve()
        if candidate != self.files_root and self.files_root not in candidate.parents:
            raise ValueError("Path is outside the submission directory")
        if not candidate.is_file():
            raise ValueError(f"Submission file does not exist: {relative}")
        return candidate

    def list_files(self, pattern: str = "*") -> dict[str, Any]:
        files = [
            path.relative_to(self.files_root).as_posix()
            for path in self.files_root.rglob("*")
            if path.is_file()
        ]
        matches = [path for path in sorted(files) if fnmatch.fnmatch(path, pattern)]
        return {"files": matches[:2000], "truncated": len(matches) > 2000}

    def read_file(self, path: str, start_line: int = 1, end_line: int = 400) -> dict[str, Any]:
        if start_line < 1 or end_line < start_line or end_line - start_line > 799:
            raise ValueError("Requested line range is invalid or too large")
        lines = self._resolve(path).read_text(encoding="utf-8").splitlines()
        selected = lines[start_line - 1 : end_line]
        numbered = [f"{number}: {line}" for number, line in enumerate(selected, start_line)]
        return {"path": path, "content": "\n".join(numbered), "total_lines": len(lines)}

    def search_text(self, query: str, path_pattern: str = "*") -> dict[str, Any]:
        if not query or len(query) > 200:
            raise ValueError("Search query must contain 1-200 characters")
        expression = re.compile(re.escape(query), re.IGNORECASE)
        results: list[dict[str, Any]] = []
        for relative in self.list_files(path_pattern)["files"]:
            for number, line in enumerate(self._resolve(relative).read_text(encoding="utf-8").splitlines(), 1):
                if expression.search(line):
                    results.append({"path": relative, "line": number, "text": line[:500]})
                    if len(results) == 200:
                        return {"matches": results, "truncated": True}
        return {"matches": results, "truncated": False}

    def call(self, name: str, arguments: dict[str, Any]) -> dict[str, Any]:
        if name == "list_files":
            return self.list_files(str(arguments.get("pattern", "*")))
        if name == "read_file":
            return self.read_file(
                str(arguments["path"]),
                int(arguments.get("start_line", 1)),
                int(arguments.get("end_line", 400)),
            )
        if name == "search_text":
            return self.search_text(
                str(arguments["query"]), str(arguments.get("path_pattern", "*"))
            )
        raise ValueError(f"Unknown tool: {name}")


TOOL_SPECS = [
    {
        "name": "list_files",
        "description": "List submission files matching a glob pattern.",
        "input_schema": {
            "type": "object",
            "additionalProperties": False,
            "properties": {"pattern": {"type": "string"}},
        },
    },
    {
        "name": "read_file",
        "description": "Read a bounded line range from one submission file.",
        "input_schema": {
            "type": "object",
            "additionalProperties": False,
            "required": ["path"],
            "properties": {
                "path": {"type": "string"},
                "start_line": {"type": "integer", "minimum": 1},
                "end_line": {"type": "integer", "minimum": 1},
            },
        },
    },
    {
        "name": "search_text",
        "description": "Search case-insensitively for literal text in submission files.",
        "input_schema": {
            "type": "object",
            "additionalProperties": False,
            "required": ["query"],
            "properties": {
                "query": {"type": "string"},
                "path_pattern": {"type": "string"},
            },
        },
    },
]
