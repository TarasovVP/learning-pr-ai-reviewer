from pathlib import Path
import json
import unittest


class PublicCatalogTest(unittest.TestCase):
    def test_every_assessment_has_matching_assignment_and_unique_ids(self) -> None:
        root = Path(__file__).parents[1] / ".ai-review/activities"
        assessments = list(root.glob("*/assessments/*.json"))
        self.assertTrue(assessments)
        for assessment_path in assessments:
            with self.subTest(assessment=assessment_path):
                assignment = assessment_path.parents[1] / "assignments" / f"{assessment_path.stem}.md"
                self.assertTrue(assignment.is_file())
                parsed = json.loads(assessment_path.read_text(encoding="utf-8"))
                ids = [criterion["id"] for criterion in parsed["criteria"]]
                self.assertEqual(len(ids), len(set(ids)))


if __name__ == "__main__":
    unittest.main()
