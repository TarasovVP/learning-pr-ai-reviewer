#!/usr/bin/env python3
from __future__ import annotations

import os

from github_api import GitHubApi


def protect(api: GitHubApi, repository: str, number: int) -> None:
    api.request("PATCH", f"/repos/{repository}/pulls/{number}", {"state": "closed"})


def main() -> None:
    protect(
        GitHubApi(os.environ["GITHUB_TOKEN"]),
        os.environ["REVIEW_REPOSITORY"],
        int(os.environ["PR_NUMBER"]),
    )


if __name__ == "__main__":
    main()
