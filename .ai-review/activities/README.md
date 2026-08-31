# Learning activity catalog

Each child directory is one trusted learning activity:

```text
.ai-review/activities/<activity-id>/
├── assessments/
│   └── <assessment-id>.yaml
└── assignments/
    └── <assessment-id>.md   optional
```

The caller supplies `activity_id`. The reviewer then resolves exactly one allowlisted assessment ID, title, or module number from the Pull Request title or an eligible human comment. It never chooses an assignment by inspecting submitted code. Missing, unknown, or ambiguous selectors produce a diagnostic without calling the model.

Assessment filenames must equal their declared `id`. When an assignment document is provided, its filename must use the same ID. Do not store credentials or participant data in this catalog. Callers pin the action to a full commit SHA, so code and catalog always form one immutable reviewed unit.
