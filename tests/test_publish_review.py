from pathlib import Path
import unittest
import sys

sys.path.insert(0, str(Path(__file__).parents[1] / "scripts"))

from publish_review import render_review


class PublishReviewTest(unittest.TestCase):
    def test_render_includes_marker_verdict_and_advisory_boundary(self) -> None:
        review = {
            "summary": "Looks good",
            "verdict": "PASSED",
            "criteria": [{"id": "one", "result": "MET", "explanation": "Found", "evidence": ["README.md"], "action_items": []}],
        }
        assessment = {"criteria": [{"id": "one", "title": "First requirement"}]}
        metadata = {"fingerprint": "abc", "head_sha": "1234567890abcdef", "assessment_version": 1, "local_validation": None}
        rendered = render_review(review, assessment, metadata, "anthropic", "claude-example", "https://example.test/run")
        self.assertIn("<!-- learning-pr-ai-reviewer:abc -->", rendered)
        self.assertIn("Advisory result: PASSED", rendered)
        self.assertIn("qualified human reviewer", rendered)


if __name__ == "__main__":
    unittest.main()
