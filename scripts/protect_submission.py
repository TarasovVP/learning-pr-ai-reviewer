#!/usr/bin/env python3
from __future__ import annotations

import os

from github_api import GitHubApi, quote


def requester_is_authorized(
    api: GitHubApi,
    repository: str,
    pull: dict,
    trigger: str,
    requested_by: str,
) -> bool:
    if trigger != "manual":
        return True
    if not requested_by:
        return False
    if requested_by == pull["user"]["login"]:
        return True
    permission = api.get(f"/repos/{repository}/collaborators/{quote(requested_by)}/permission")
    return permission.get("permission") in {"write", "maintain", "admin"}


def protect(
    api: GitHubApi,
    repository: str,
    number: int,
    trigger: str,
    requested_by: str,
) -> None:
    pull = api.get(f"/repos/{repository}/pulls/{number}")
    if not requester_is_authorized(api, repository, pull, trigger, requested_by):
        raise ValueError("Manual review requester is not the author or an authorized collaborator")
    api.request("PATCH", f"/repos/{repository}/pulls/{number}", {"state": "closed"})


def main() -> None:
    protect(
        GitHubApi(os.environ["GITHUB_TOKEN"]),
        os.environ["REVIEW_REPOSITORY"],
        int(os.environ["PR_NUMBER"]),
        os.environ.get("REVIEW_TRIGGER", ""),
        os.environ.get("REVIEW_REQUESTED_BY", ""),
    )


if __name__ == "__main__":
    main()
