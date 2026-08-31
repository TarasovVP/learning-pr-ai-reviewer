# Security policy

Please report suspected vulnerabilities privately through GitHub's security advisory feature rather than opening a public issue.

## Trust boundary

- A caller pins this action to a reviewed full commit SHA; action code, prompt, schema, and catalog are one trusted versioned unit.
- `activity_id` comes from the trusted parent workflow. Participant content cannot replace catalog criteria or reviewer instructions.
- Pull Request metadata, comments, patches, files, previous reports, and local-validation summaries are untrusted evidence.
- The participant head is never checked out or executed. Eligible UTF-8 blobs are fetched by the PR's recorded head SHA through the GitHub API.
- Workflows, AI-control files, likely secrets, generated outputs, binaries, archives, symbolic links, oversized files, and unsafe paths are excluded.
- The model receives only bounded read-only file tools scoped to the prepared context.
- Provider credentials are sent only in authorization headers, never included in prompts or logs. OpenAI requests explicitly set `store: false`; Anthropic structured outputs use the provider's documented zero-data-retention treatment for prompt and response content.
- The publisher does not trust model output: it validates the selected assessment, requirement IDs and order, evidence paths, verdict derivation, and action-item rules.

## Integration requirements

Keep `AI_API_KEY` in the trusted parent or organization secret store. Grant only the permissions documented by the caller example. Never add a participant checkout, build, test, shell command, or workflow execution to the privileged `pull_request_target` job.

The action closes submission PRs before processing evidence. This safeguard does not replace native branch protection where it is available. The automated result is advisory and must not be the sole authorization mechanism for merging, certification, deployment, or access decisions.
