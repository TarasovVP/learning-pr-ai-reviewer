from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch
import base64
import contextlib
import io
import json
import sys
import unittest

sys.path.insert(0, str(Path(__file__).parents[1] / "scripts"))

import prepare_pr_context as context_module
from prepare_pr_context import (
    activity_root, assessment_files, decode_blob, exclusion_reason,
    diagnostic, local_validation, requirement_metadata, safe_relative_path, select_assessment,
)


CANDIDATES = [
    {"id": "module-1-design", "title": "Module 1. System Design"},
    {"id": "module-10-ai", "title": "Module 10. AI Engineering"},
]


class PrepareContextTest(unittest.TestCase):
    def test_accepts_regular_text_files(self):
        self.assertIsNone(exclusion_reason("shared/src/commonMain/App.kt"))
        self.assertIsNone(exclusion_reason("README.md"))

    def test_rejects_path_traversal(self):
        self.assertFalse(safe_relative_path("../secret"))
        self.assertEqual("unsafe path", exclusion_reason("../secret"))

    def test_rejects_workflows_ai_controls_and_symlinks(self):
        self.assertEqual("pull request workflow", exclusion_reason(".github/workflows/attack.yml"))
        self.assertEqual("excluded directory", exclusion_reason(".ai-review/prompt.md"))
        self.assertEqual("excluded directory", exclusion_reason(".claude/settings.json"))
        self.assertEqual("symbolic link", exclusion_reason("safe.txt", "120000"))

    def test_rejects_build_outputs_secrets_and_binary_extensions(self):
        self.assertEqual("excluded directory", exclusion_reason("app/build/result.txt"))
        self.assertEqual("secret-bearing or AI-control file", exclusion_reason("local.properties"))
        self.assertEqual("environment secret file", exclusion_reason(".env.production"))
        self.assertEqual("binary or archive extension", exclusion_reason("release.apk"))

    def test_blob_decoder_accepts_utf8_and_rejects_binary(self):
        import base64
        self.assertEqual((b"hello", None), decode_blob(base64.b64encode(b"hello").decode()))
        self.assertEqual("binary content", decode_blob(base64.b64encode(b"a\x00b").decode())[1])
        self.assertEqual("invalid base64 blob", decode_blob("not base64!")[1])

    def test_valid_local_validation_is_loaded(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / ".local-validation/summary.json"
            path.parent.mkdir()
            path.write_text(json.dumps({
                "schema_version": 1, "source": "local-pre-commit", "status": "PASSED",
                "generated_at": "2026-08-31T12:00:00Z", "duration_seconds": 12,
                "platforms": ["Android", "Desktop/JVM"],
                "tests": {"total": 3, "passed": 2, "skipped": 1, "failed": 0},
            }))
            self.assertTrue(local_validation(root)["available"])

    def test_invalid_local_validation_is_ignored(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / ".local-validation/summary.json"
            path.parent.mkdir()
            path.write_text(json.dumps({
                "schema_version": 1, "source": "local-pre-commit", "status": "PASSED",
                "generated_at": "yesterday", "duration_seconds": 12, "platforms": [],
                "tests": {"total": 1, "passed": 0, "skipped": 0, "failed": 1},
            }))
            self.assertFalse(local_validation(root)["available"])

    def test_selects_exact_title(self):
        result = select_assessment("Module 1. System Design", [], CANDIDATES, "student")
        self.assertEqual(["module-1-design"], result["assessment_ids"])

    def test_selects_explicit_assessment_id(self):
        result = select_assessment("submission for module-10-ai", [], CANDIDATES, "student")
        self.assertEqual(["module-10-ai"], result["assessment_ids"])

    def test_module_number_does_not_prefix_match(self):
        result = select_assessment("Module 1", [], CANDIDATES, "student")
        self.assertEqual(["module-1-design"], result["assessment_ids"])

    def test_rejects_multiple_modules(self):
        result = select_assessment("Module 1 and Module 10", [], CANDIDATES, "student")
        self.assertEqual("invalid", result["mode"])

    def test_rejects_unknown_module(self):
        result = select_assessment("Module 99", [], CANDIDATES, "student")
        self.assertIn("unavailable", result["reason"])

    def test_latest_author_comment_can_override_title(self):
        comments = [{"body": "module-10-ai", "user": {"login": "student"}, "author_association": "NONE"}]
        result = select_assessment("Module 1", comments, CANDIDATES, "student")
        self.assertEqual(["module-10-ai"], result["assessment_ids"])

    def test_ignores_unrelated_comment(self):
        comments = [{"body": "module-10-ai", "user": {"login": "outsider"}, "author_association": "NONE"}]
        result = select_assessment("Module 1", comments, CANDIDATES, "student")
        self.assertEqual(["module-1-design"], result["assessment_ids"])

    def test_authorized_collaborator_comment_can_select(self):
        comments = [{"body": "module-10-ai", "user": {"login": "mentor"}, "author_association": "MEMBER"}]
        result = select_assessment("Module 1", comments, CANDIDATES, "student")
        self.assertEqual(["module-10-ai"], result["assessment_ids"])

    def test_no_selector_is_invalid(self):
        self.assertEqual("invalid", select_assessment("My work", [], CANDIDATES, "student")["mode"])

    def test_extracts_indented_requirement_ids_in_order(self):
        data = b"requirements:\n    - id: first\n      title: First\n    - id: second\n      title: Second\n"
        self.assertEqual(["first", "second"], [item["id"] for item in requirement_metadata(data)])

    def test_activity_path_rejects_invalid_id(self):
        with TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                activity_root(Path(directory), "../bad")

    def test_assessment_listing_accepts_yaml_only(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            assessments = root / "assessments"
            assessments.mkdir()
            (assessments / "one.yaml").write_text("id: one")
            (assessments / "two.json").write_text("{}")
            self.assertEqual(["one"], list(assessment_files(root)))

    def test_diagnostic_uses_natural_module_order(self):
        with TemporaryDirectory() as directory, contextlib.redirect_stdout(io.StringIO()):
            output = Path(directory)
            manifest = {"base_sha": "base", "head_sha": "head", "scope": "full_submission"}
            diagnostic(output, manifest, "Bad selector", {
                "ten": "Module 10. Ten", "two": "Module 2. Two", "one": "Module 1. One",
            })
            text = (output / "diagnostic.md").read_text()
            self.assertLess(text.index("Module 1"), text.index("Module 2"))
            self.assertLess(text.index("Module 2"), text.index("Module 10"))

    def test_main_reads_fork_commit_through_parent_repository(self):
        encoded = base64.b64encode(b"application source\n").decode()

        class FakeApi:
            def __init__(self, _token):
                self.paths = []
            def get(self, path):
                self.paths.append(path)
                if path == "/repos/owner/activity/pulls/7":
                    return {
                        "title": "Architecture", "body": "done", "user": {"login": "student"},
                        "base": {"ref": "main", "sha": "base", "repo": {"full_name": "owner/activity"}},
                        "head": {"ref": "solution", "sha": "head", "repo": {"full_name": "student/activity"}},
                    }
                if path == "/repos/owner/activity/git/trees/head?recursive=1":
                    return {"truncated": False, "tree": [{"type": "blob", "path": "src/App.kt", "sha": "blob", "size": 19, "mode": "100644"}]}
                if path == "/repos/owner/activity/git/blobs/blob":
                    return {"content": encoded}
                raise AssertionError(path)
            def paginate(self, path):
                self.paths.append(path)
                return []
            def request_bytes(self, method, path, payload=None, accept="application/vnd.github+json"):
                self.paths.append(path)
                return b"diff"

        with TemporaryDirectory() as directory, contextlib.redirect_stdout(io.StringIO()):
            reviewer = Path(directory) / "reviewer"
            activity = reviewer / ".ai-review/activities/example"
            (activity / "assessments").mkdir(parents=True)
            (activity / "assignments").mkdir()
            (activity / "assessments/architecture.yaml").write_text("id: architecture\nversion: 1.0.0\ntitle: Architecture\nrequirements:\n  - id: boundaries\n    title: Boundaries\n")
            (activity / "assignments/architecture.md").write_text("Task")
            output = Path(directory) / "context"
            env = {
                "GITHUB_TOKEN": "token", "EXPECTED_REPOSITORY": "owner/activity", "REVIEW_REPOSITORY": "owner/activity",
                "PR_NUMBER": "7", "ACTIVITY_ID": "example", "REVIEWER_ROOT": str(reviewer), "REVIEWER_SHA": "reviewer",
                "REVIEW_SCOPE": "full_submission", "MAX_FILES": "100", "MAX_TOTAL_BYTES": "10000", "OUTPUT_DIRECTORY": str(output),
            }
            fake = FakeApi("token")
            with patch.dict(context_module.os.environ, env, clear=True), patch.object(context_module, "GitHubApi", return_value=fake):
                self.assertEqual(0, context_module.main())
            self.assertTrue((output / "submission/files/src/App.kt").is_file())
            self.assertIn("/repos/owner/activity/git/trees/head?recursive=1", fake.paths)
            self.assertFalse(any("/repos/student/activity/git/" in path for path in fake.paths))
            manifest = json.loads((output / "manifest.json").read_text())
            self.assertEqual(["architecture"], manifest["assessment_selection"]["assessment_ids"])
            self.assertEqual(["boundaries"], manifest["candidate_assessments"][0]["requirement_ids"])


if __name__ == "__main__":
    unittest.main()
