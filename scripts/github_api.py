from __future__ import annotations

import json
import base64
import urllib.error
import urllib.parse
import urllib.request
from typing import Any


class GitHubApiError(RuntimeError):
    def __init__(self, status: int, path: str, detail: str) -> None:
        super().__init__(f"GitHub API {status} for {path}: {detail}")
        self.status = status


class GitHubApi:
    def __init__(self, token: str, api_root: str = "https://api.github.com") -> None:
        self.token = token
        self.api_root = api_root.rstrip("/")

    def request(self, method: str, path: str, payload: Any | None = None) -> Any:
        data = None if payload is None else json.dumps(payload).encode()
        request = urllib.request.Request(
            f"{self.api_root}{path}",
            data=data,
            method=method,
            headers={
                "Accept": "application/vnd.github+json",
                "Authorization": f"Bearer {self.token}",
                "Content-Type": "application/json",
                "User-Agent": "learning-pr-ai-reviewer",
                "X-GitHub-Api-Version": "2022-11-28",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                body = response.read()
                return json.loads(body) if body else None
        except urllib.error.HTTPError as error:
            detail = error.read().decode(errors="replace")
            raise GitHubApiError(error.code, path, detail[:500]) from error

    def get(self, path: str) -> Any:
        return self.request("GET", path)

    def post(self, path: str, payload: Any) -> Any:
        return self.request("POST", path, payload)

    def patch(self, path: str, payload: Any) -> Any:
        return self.request("PATCH", path, payload)

    def delete(self, path: str) -> Any:
        return self.request("DELETE", path)

    def paginate(self, path: str) -> list[Any]:
        result: list[Any] = []
        page = 1
        while True:
            separator = "&" if "?" in path else "?"
            values = self.get(f"{path}{separator}per_page=100&page={page}")
            result.extend(values)
            if len(values) < 100:
                return result
            page += 1

    def read_text_file(self, repository: str, path: str, ref: str) -> str:
        encoded_path = "/".join(quote(part) for part in path.split("/"))
        item = self.get(
            f"/repos/{repository}/contents/{encoded_path}?ref={quote(ref)}"
        )
        if item.get("type") != "file" or item.get("encoding") != "base64":
            raise ValueError(f"Unsupported repository item: {path}")
        return base64.b64decode(item["content"]).decode("utf-8")


def quote(value: str) -> str:
    return urllib.parse.quote(value, safe="")
