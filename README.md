# Android PR AI Reviewer Actions

One-shot Kotlin CLI for advisory AI review of Android pull requests from GitHub Actions. It reads a pull request through the GitHub API, evaluates the selected module against a trusted rubric with OpenAI, publishes a `COMMENT` review, and exits.

The project is a separate GitHub Actions implementation of Android PR AI Reviewer. It does not run an HTTP server and does not require Smee, webhooks, a GitHub App, a private key, or a permanently running machine.

## Current scope

- Sample Assignment
- Sample Assignment
- Sample Assignment
- Sample Assignment
- Automatic review when a draft pull request becomes ready for review
- Manual rerun through the exact `/ai-review` comment
- Deduplication by module rubric, rubric version, and commit SHA
- Direct GitHub API access with the workflow's short-lived `GITHUB_TOKEN`
- OpenAI Responses API with structured JSON output and `store: false`
- Maximum 60 text files and 400 KB of context
- Advisory `COMMENT` reviews only

The reviewer does not clone or execute the submitted project. It does not build Android code, run tests, use an emulator, or validate TalkBack behavior.

## Local run

Set the required environment variables:

```bash
export GITHUB_TOKEN=github-token
export OPENAI_API_KEY=openai-api-key
export GITHUB_REPOSITORY=owner/repository
export PR_NUMBER=1
export GITHUB_ACTOR=github-username
export REVIEW_TRIGGER=ready_for_review
```

Then run:

```bash
./gradlew run
```

Optional CLI flags override repository, PR, actor, and trigger values:

```bash
./gradlew run --args="--repository owner/repository --pr 1 --trigger manual --requested-by github-username"
```

`REVIEW_TRIGGER` accepts `ready_for_review` or `manual`. A manual request is accepted only from the PR author or a collaborator with `write`, `maintain`, or `admin` permission.

## Centralized GitHub Actions model

The reviewer repository owns `.github/workflows/reusable-review.yml`. Each engineer repository contains only a small caller workflow based on [docs/caller-workflow.yml](docs/caller-workflow.yml).

The caller reacts to `pull_request_target.ready_for_review` and the `/ai-review` issue comment. It invokes the centralized workflow, which downloads this reviewer, runs the CLI with the caller repository's `GITHUB_TOKEN`, and publishes the result back to that pull request. The caller passes only `OPENAI_API_KEY`, rather than inheriting every available repository secret.

`pull_request_target` is used so that repository secrets are not taken from or controlled by the PR branch. The workflow never checks out or executes submitted PR code; source is read as untrusted text through the GitHub API.

For multiple engineer repositories, configure `OPENAI_API_KEY` as an organization secret with access only to approved training repositories. Install the caller workflow in each repository's default branch. Engineers never receive or configure the OpenAI key themselves.

## Required repository configuration

The engineer repository needs:

- the caller workflow under `.github/workflows/android-pr-ai-review.yml`;
- access to the organization or repository secret `OPENAI_API_KEY`;
- Actions permission to read contents and write pull request reviews.

No GitHub App installation is required.

## Pull request contract

The pull request must target `main` and contain one supported module marker in its description:

```text
Module: 1-mobile-architecture
```

or:

```text
Module: 2-android-builds-with-gradle
```

or:

```text
Module: 3-compose-ui
```

or:

```text
Module: 4-networking
```

## Configuration

| Variable | Purpose |
|---|---|
| `GITHUB_TOKEN` | Short-lived token supplied by GitHub Actions |
| `OPENAI_API_KEY` | OpenAI API key stored as a GitHub secret |
| `GITHUB_REPOSITORY` | Repository in `owner/name` format |
| `PR_NUMBER` | Pull request number |
| `GITHUB_ACTOR` | User who triggered or requested review |
| `REVIEW_TRIGGER` | `ready_for_review` or `manual` |
| `OPENAI_MODEL` | Defaults to `provider-model` |
| `OPENAI_REASONING_EFFORT` | Defaults to `medium` |
| `ALLOWED_REPOSITORIES` | Optional comma-separated allowlist |

Never commit API keys, personal access tokens, or `.env` files.

## Validation

```bash
./gradlew clean test build
```
