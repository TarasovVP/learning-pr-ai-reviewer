# Architecture

Learning PR AI Reviewer has three independently versioned repository roles:

1. This central reviewer repository contains the composite action, trusted prompt, catalog, schemas, and validation code.
2. A parent learning repository contains the starter or template and a small caller workflow pinned to a full reviewer commit SHA.
3. A participant fork contains the submitted implementation and opens a Pull Request back to the parent.

The parent workflow runs through `pull_request_target`, but the privileged job never checks out the participant's branch. It closes the submission first, then retrieves Pull Request metadata, diff, and eligible text blobs by recorded head SHA through the GitHub API. Trusted catalog material and untrusted submission evidence are written into separate context directories.

Routing happens locally before any model request. The title or newest eligible selector comment must identify exactly one allowlisted assessment. The AI receives only bounded `list_files`, `read_file`, and `search_text` tools over the filtered context. It returns a schema-constrained report; the publisher independently checks selection, requirement order, evidence paths, status-derived verdicts, and corrective actions before updating one bot comment and one `ai-verdict/*` label.

The only provider-specific component is the API adapter. Anthropic Messages is the default public adapter, and OpenAI Responses is an alternative. Both use the same prompt, tool semantics, dynamic report contract, publisher, and security boundary.
