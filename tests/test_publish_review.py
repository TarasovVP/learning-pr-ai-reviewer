from pathlib import Path
import copy
import sys
import unittest

sys.path.insert(0, str(Path(__file__).parents[1] / "scripts"))

from publish_review import (
    MARKER, build_comment, build_diagnostic, coverage_warnings,
    normalize_evidence_paths, render_review, requirement_verdict, validate_review,
)


MANIFEST = {
    "base_sha": "base", "head_sha": "123456789abcdef", "activity_id": "sample",
    "reviewer_sha": "reviewer", "author": "student", "scope": "full_submission",
    "assessment_selection": {"mode": "deterministic", "assessment_ids": ["sample"]},
    "candidate_assessments": [{
        "id": "sample", "title": "Sample Assignment", "requirement_ids": ["one", "two"],
        "requirements": [{"id": "one", "title": "One"}, {"id": "two", "title": "Two"}],
    }],
    "downloaded_files": ["src/App.kt", "other/Util.kt"],
}


def report(statuses=("MET", "MET")):
    verdict = requirement_verdict(list(statuses))
    return {
        "summary": "Summary", "verdict": verdict,
        "assessments": [{
            "id": "sample", "title": "Sample Assignment", "verdict": verdict,
            "requirements": [
                {"id": "one", "status": statuses[0], "evidence": "submission/files/src/App.kt:1"},
                {"id": "two", "status": statuses[1], "evidence": "submission/files/other/Util.kt:2"},
            ],
        }],
        "findings": [],
        "action_items": ["Fix one"] if any(item in {"PARTIAL", "NOT_MET"} for item in statuses) else [],
    }


class PublishReviewTest(unittest.TestCase):
    def test_verdict_precedence(self):
        self.assertEqual("PASSED", requirement_verdict(["MET"]))
        self.assertEqual("PASSED WITH NOTES", requirement_verdict(["MET", "NOT_VERIFIABLE"]))
        self.assertEqual("FAILED", requirement_verdict(["PARTIAL", "NOT_MET"]))

    def test_validates_complete_report_and_normalizes_evidence(self):
        value = report()
        verdict, selected = validate_review(value, copy.deepcopy(MANIFEST))
        self.assertEqual("PASSED", verdict)
        self.assertEqual(["sample"], selected)
        self.assertEqual("submission/files/src/App.kt:1", value["assessments"][0]["requirements"][0]["evidence"])

    def test_rejects_missing_requirement(self):
        value = report()
        value["assessments"][0]["requirements"].pop()
        with self.assertRaisesRegex(ValueError, "requirement IDs"):
            validate_review(value, copy.deepcopy(MANIFEST))

    def test_rejects_multiple_assessments(self):
        value = report()
        value["assessments"].append(copy.deepcopy(value["assessments"][0]))
        with self.assertRaisesRegex(ValueError, "exactly one"):
            validate_review(value, copy.deepcopy(MANIFEST))

    def test_rejects_model_override_of_selection(self):
        value = report()
        value["assessments"][0]["id"] = "other"
        with self.assertRaisesRegex(ValueError, "outside"):
            validate_review(value, copy.deepcopy(MANIFEST))

    def test_rejects_title_mismatch(self):
        value = report()
        value["assessments"][0]["title"] = "Invented"
        with self.assertRaisesRegex(ValueError, "title"):
            validate_review(value, copy.deepcopy(MANIFEST))

    def test_rejects_inconsistent_verdict(self):
        value = report()
        value["assessments"][0]["verdict"] = "FAILED"
        with self.assertRaisesRegex(ValueError, "statuses"):
            validate_review(value, copy.deepcopy(MANIFEST))

    def test_partial_requires_action_item(self):
        value = report(("PARTIAL", "MET"))
        value["action_items"] = []
        with self.assertRaisesRegex(ValueError, "action items"):
            validate_review(value, copy.deepcopy(MANIFEST))

    def test_passed_report_rejects_action_item(self):
        value = report()
        value["action_items"] = ["Unrelated change"]
        with self.assertRaisesRegex(ValueError, "allowed only"):
            validate_review(value, copy.deepcopy(MANIFEST))

    def test_unique_short_evidence_path_is_resolved(self):
        value = report()
        value["assessments"][0]["requirements"][0]["evidence"] = "submission/files/App.kt:1"
        normalize_evidence_paths(value, copy.deepcopy(MANIFEST))
        self.assertEqual("submission/files/src/App.kt:1", value["assessments"][0]["requirements"][0]["evidence"])

    def test_unknown_evidence_path_is_rejected(self):
        value = report()
        value["assessments"][0]["requirements"][0]["evidence"] = "submission/files/Missing.kt:1"
        with self.assertRaisesRegex(ValueError, "outside"):
            normalize_evidence_paths(value, copy.deepcopy(MANIFEST))

    def test_ambiguous_short_evidence_path_is_rejected(self):
        manifest = copy.deepcopy(MANIFEST)
        manifest["downloaded_files"].append("second/App.kt")
        value = report()
        value["assessments"][0]["requirements"][0]["evidence"] = "submission/files/App.kt:1"
        with self.assertRaisesRegex(ValueError, "ambiguous"):
            normalize_evidence_paths(value, manifest)

    def test_comment_contains_marker_metadata_provider_and_author(self):
        value = report()
        validate_review(value, copy.deepcopy(MANIFEST))
        body = build_comment(render_review(value, MANIFEST), MANIFEST, ["sample"], "anthropic", "claude", "https://run", "PASSED")
        self.assertIn(MARKER, body)
        self.assertIn("learning-pr-ai-reviewer:meta", body)
        self.assertIn("anthropic/claude", body)
        self.assertIn("for @student", body)

    def test_failed_comment_contains_update_instructions(self):
        value = report(("NOT_MET", "MET"))
        validate_review(value, copy.deepcopy(MANIFEST))
        body = build_comment(render_review(value, MANIFEST), MANIFEST, ["sample"], "openai", "model", "https://run", "FAILED")
        self.assertIn("Updating this submission", body)
        self.assertIn("new Pull Request", body)

    def test_diagnostic_says_model_was_not_called(self):
        self.assertIn("No model request was made", build_diagnostic("Bad selector", MANIFEST, "https://run"))

    def test_real_coverage_warnings_are_rendered(self):
        manifest = copy.deepcopy(MANIFEST)
        manifest["github_tree_truncated"] = True
        manifest["diff_truncated"] = True
        self.assertEqual(2, len(coverage_warnings(manifest)))
        self.assertIn("Review coverage", render_review(report(), manifest))

    def test_participant_local_validation_is_distinguished_from_ci(self):
        manifest = copy.deepcopy(MANIFEST)
        manifest["local_validation"] = {
            "available": True, "status": "PASSED", "platforms": ["Android"],
            "duration_seconds": 12, "generated_at": "2026-08-31T12:00:00Z",
            "tests": {"passed": 2, "skipped": 0, "failed": 0},
        }
        rendered = render_review(report(), manifest)
        self.assertIn("participant", rendered)
        self.assertIn("not trusted CI", rendered)


if __name__ == "__main__":
    unittest.main()
