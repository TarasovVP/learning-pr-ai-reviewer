# Connect a learning repository

## 1. Add the trusted catalog entry

Choose a lowercase activity ID and create `.ai-review/activities/<activity-id>/assessments/<assessment-id>.yaml`. Use [the example assessment](../examples/assessment.example.yaml) as the contract. Add a matching assignment Markdown file when the model needs the full practical-task text as trusted context.

Run `ruby scripts/validate_catalog.rb`, have the change reviewed, and record the resulting full commit SHA. That SHA pins the action implementation and catalog together.

## 2. Configure the parent repository

Copy [caller-workflow.yml](caller-workflow.yml) into `.github/workflows/learning-ai-review.yml` on the parent repository's base branch. Replace the reviewer SHA, activity ID, provider, and model. Add `AI_API_KEY` under **Settings → Secrets and variables → Actions**. Keep the key out of forks, variables, source files, comments, and logs.

The recommended workflow grants only `contents: read`, `pull-requests: write`, and `issues: write`. It handles a regular non-draft PR when opened, a draft when marked ready, and an exact `/ai-review` command from an owner, member, or collaborator.

## 3. Submission lifecycle

The participant forks the parent repository, completes one assignment, and opens a Pull Request that identifies exactly one trusted assessment. The action closes the Pull Request before collecting untrusted evidence. The report remains available under **Pull requests → Closed**.

A closed Pull Request remains tied to its recorded head commit. After changing the implementation, the participant must open a new Pull Request to review the new commit. `/ai-review` reruns the recorded commit and is intended for selector corrections or temporary infrastructure failures.

## 4. Pilot checklist

- A regular PR runs automatically and a draft waits for Ready for review.
- The PR closes before context collection and model execution.
- Unknown, missing, and ambiguous selectors create diagnostics without a model call.
- The report contains every selected requirement exactly once and the matching verdict label.
- No participant workflow, script, build, or test is executed by the reviewer.
- A rerun updates the existing bot comment rather than creating another.
- A corrected implementation is reviewed through a new PR and new recorded head SHA.
