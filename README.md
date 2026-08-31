# Learning PR AI Reviewer

A reusable, platform-neutral GitHub composite action for preliminary AI review of practical learning submissions. It evaluates a Pull Request against a trusted assignment and assessment catalog, publishes an evidence-based advisory report, and leaves final approval to a human reviewer.

The reviewer is designed for courses, internships, development plans, and other repository-based learning activities. Its sample catalog uses a Kotlin Multiplatform capstone, but the engine does not depend on Kotlin, Android, or any other platform.

## How it works

1. A participant opens a Pull Request whose title, or an authorized selector comment, names exactly one catalog assignment.
2. A small workflow in the parent learning repository invokes this action at an immutable commit SHA.
3. The action closes the submission Pull Request before processing its untrusted evidence, preventing accidental merges into the parent learning repository.
4. Trusted assignment and assessment files are selected from this repository.
5. Submission text is collected through the GitHub API at the Pull Request head SHA. Submitted workflows, reviewer controls, secrets, binaries, and build outputs are excluded.
6. An AI model inspects the evidence through bounded read-only tools and returns a structured criterion-level result.
7. The action validates trusted IDs, ordering, evidence paths, status/verdict consistency, and corrective actions before publishing one sticky report and one verdict label.

The available verdicts are `PASSED`, `PASSED WITH NOTES`, and `FAILED`. They are advisory: the mentor, professor, or other qualified reviewer remains responsible for the final assessment.

## Security model

The caller normally uses `pull_request_target` so provider credentials remain in the trusted parent repository context. The action never checks out or executes code from the Pull Request. It reads eligible files as untrusted text through the GitHub API and exposes only three bounded tools to the model: file listing, file reading, and literal text search.

The workflow should reference this action by a reviewed full commit SHA. Do not reference a mutable branch from a repository that stores AI credentials.

Repository files are filtered before review. The collector excludes:

- submitted workflows, `.ai-review/`, AI-control files, build directories, dependency directories, and IDE state;
- common credential, key, and machine-configuration filenames;
- binary and packaged formats;
- files above the configured per-file and total-context limits.

An optional `.local-validation/summary.json` is treated as participant-provided evidence. It is displayed separately and is never considered an independently executed check.

## Provider support

The action supports:

- Anthropic Messages API, selected with `provider: anthropic`;
- OpenAI Responses API, selected with `provider: openai`.

Provider choice and model name are caller configuration. The default provider is Anthropic, while no model name is hard-coded. This keeps the review workflow independent of a specific commercial model.

## Catalog structure

Each learning activity has its own trusted catalog:

```text
.ai-review/
└── activities/
    └── activity-id/
        ├── assignments/
        │   └── exact-pull-request-title.md
        └── assessments/
            └── assessment-id.yaml
```

An optional assignment and its required assessment share one stable assessment ID as their filename. The YAML assessment declares that same `id`, a semantic `version`, a participant-facing `title`, and an ordered list of requirements with criteria, acceptable evidence, and explicit status rules. The reviewer can select it by assessment ID, exact title, or an unambiguous module number.

The repository includes an independently written public example under `kmp-reading-list`.

## Caller workflow

Copy [docs/caller-workflow.yml](docs/caller-workflow.yml) to `.github/workflows/learning-ai-review.yml` in the parent learning repository. Replace:

- `REVIEWER_COMMIT_SHA` with a reviewed full commit SHA from this repository;
- `kmp-reading-list` with the required activity ID;
- the provider secret and model with values available to the parent repository.

The workflow responds automatically when a non-draft submission is opened or becomes ready and supports an exact `/ai-review` comment for a manual rerun of the Pull Request's recorded head commit. GitHub author association restricts manual reruns to repository owners, members, and collaborators.

If a submission Pull Request was automatically closed and its implementation changes, open a new Pull Request. GitHub keeps the closed Pull Request associated with its recorded head SHA, so `/ai-review` on that Pull Request intentionally evaluates the same commit again.

## Action inputs

| Input | Required | Purpose |
|---|---:|---|
| `repository` | yes | Parent repository in `owner/name` form |
| `pr_number` | yes | Submission Pull Request number |
| `activity_id` | yes | Trusted catalog activity identifier |
| `trigger` | yes | `opened`, `ready_for_review`, or `manual` |
| `github_token` | yes | Short-lived token from the caller workflow |
| `ai_api_key` | yes | Secret for the selected AI provider |
| `provider` | no | `anthropic` by default, or `openai` |
| `model` | yes | Model identifier accepted by the provider |
| `reasoning_effort` | no | Provider-specific reasoning setting |
| `review_scope` | no | `full_submission` or `pull_request_changes` |
| `requested_by` | no | Login that issued a manual request |
| `max_files` | no | Maximum eligible text files collected after filtering |
| `max_total_bytes` | no | Maximum total eligible text bytes collected after filtering |

## Development

The implementation uses the Python standard library and requires Python 3.10 or newer.

```bash
python3 -m unittest discover -s tests -v
ruby scripts/validate_catalog.rb
PYTHONPYCACHEPREFIX=/tmp/learning-pr-ai-reviewer-pycache \
  python3 -m compileall -q scripts tests
```

Never commit API keys, personal access tokens, private keys, or `.env` files.

## License

Licensed under the [Apache License 2.0](LICENSE).
