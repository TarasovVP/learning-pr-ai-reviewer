from pathlib import Path
import unittest
import sys

sys.path.insert(0, str(Path(__file__).parents[1] / "scripts"))

from review_result import validate_review, verdict_for


ASSESSMENT = {
    "criteria": [
        {"id": "architecture", "title": "Architecture"},
        {"id": "behavior", "title": "Behavior"},
    ]
}


def criterion(identifier, result, actions=None):
    return {
        "id": identifier,
        "result": result,
        "explanation": "Evidence-based explanation",
        "evidence": ["src/Example.kt"],
        "action_items": actions or [],
    }


class ReviewResultTest(unittest.TestCase):
    def test_verdict_precedence(self) -> None:
        self.assertEqual("PASSED", verdict_for([criterion("one", "MET")]))
        self.assertEqual("PASSED WITH NOTES", verdict_for([criterion("one", "NOT_VERIFIABLE")]))
        self.assertEqual("FAILED", verdict_for([criterion("one", "NOT_MET", ["Implement it"])]))

    def test_requires_exact_criterion_order(self) -> None:
        review = {
            "summary": "Summary",
            "criteria": [criterion("behavior", "MET"), criterion("architecture", "MET")],
        }
        with self.assertRaisesRegex(ValueError, "ids/order"):
            validate_review(review, ASSESSMENT)

    def test_partial_requires_action_item(self) -> None:
        review = {
            "summary": "Summary",
            "criteria": [criterion("architecture", "PARTIAL"), criterion("behavior", "MET")],
        }
        with self.assertRaisesRegex(ValueError, "action item"):
            validate_review(review, ASSESSMENT)

    def test_valid_review_gets_derived_verdict(self) -> None:
        review = {
            "summary": "Summary",
            "criteria": [criterion("architecture", "MET"), criterion("behavior", "PARTIAL", ["Complete fallback"])]
        }
        self.assertEqual("PASSED WITH NOTES", validate_review(review, ASSESSMENT)["verdict"])


if __name__ == "__main__":
    unittest.main()
