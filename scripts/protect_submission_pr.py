#!/usr/bin/env python3
"""Close a learning submission before any untrusted evidence is processed."""

from __future__ import annotations

import os
import sys

from github_api import GitHubApi, GitHubApiError, quote


LEGACY_LABEL = "ai-review/do-not-merge"


def protect_submission(api: GitHubApi, repository: str, number: int) -> None:
    api.patch(f"/repos/{repository}/pulls/{number}", {"state": "closed"})
    try:
        api.delete(f"/repos/{repository}/issues/{number}/labels/{quote(LEGACY_LABEL)}")
    except GitHubApiError as error:
        if error.status != 404:
            raise


def main() -> int:
    protect_submission(
        GitHubApi(os.environ["GITHUB_TOKEN"]),
        os.environ["REVIEW_REPOSITORY"],
        int(os.environ["PR_NUMBER"]),
    )
    print("Submission Pull Request closed before review.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (GitHubApiError, KeyError, ValueError) as error:
        print(f"Submission protection failed: {error}", file=sys.stderr)
        raise SystemExit(1)
