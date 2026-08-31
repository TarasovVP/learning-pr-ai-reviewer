from pathlib import Path
import json
import re
import subprocess
import unittest


ROOT = Path(__file__).parents[1]


class RepositoryPolicyTest(unittest.TestCase):
    def test_workflow_actions_are_pinned_to_full_sha(self):
        workflow = (ROOT / ".github/workflows/ci.yml").read_text()
        uses = re.findall(r"uses:\s*([^\s]+)", workflow)
        self.assertTrue(uses)
        for value in uses:
            self.assertRegex(value, r"@[0-9a-f]{40}$")

    def test_assessment_schema_is_valid_json(self):
        parsed = json.loads((ROOT / "schemas/assessment.schema.json").read_text())
        self.assertEqual("object", parsed["type"])

    def test_catalog_validator_passes(self):
        result = subprocess.run(["ruby", "scripts/validate_catalog.rb"], cwd=ROOT, text=True, capture_output=True)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_public_catalog_contains_yaml_and_assignments_are_matched(self):
        assessments = list((ROOT / ".ai-review/activities").glob("*/assessments/*.yaml"))
        self.assertTrue(assessments)
        for assignment in (ROOT / ".ai-review/activities").glob("*/assignments/*.md"):
            assessment = assignment.parents[1] / "assessments" / f"{assignment.stem}.yaml"
            self.assertTrue(assessment.is_file())

    def test_action_uses_context_without_checkout_or_execution(self):
        action = (ROOT / "action.yml").read_text()
        self.assertIn("prepare_pr_context.py", action)
        self.assertIn("run_ai_review.py", action)
        self.assertNotIn("actions/checkout", action)
        self.assertNotIn("./gradlew", action)

    def test_caller_uses_separate_automatic_and_manual_jobs(self):
        workflow = (ROOT / "docs/caller-workflow.yml").read_text()
        self.assertIn("automatic-review:", workflow)
        self.assertIn("manual-review:", workflow)
        self.assertIn("github.event.comment.body == '/ai-review'", workflow)
        self.assertIn('OWNER", "MEMBER", "COLLABORATOR', workflow)

    def test_action_and_docs_are_platform_neutral(self):
        corpus = (ROOT / "action.yml").read_text() + (ROOT / "prompts/review.md").read_text()
        self.assertNotIn("android", corpus.casefold())
        self.assertIn("learning", corpus.casefold())


if __name__ == "__main__":
    unittest.main()
