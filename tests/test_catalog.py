from pathlib import Path
from tempfile import TemporaryDirectory
import json
import unittest

import sys

sys.path.insert(0, str(Path(__file__).parents[1] / "scripts"))

from common import select_catalog_entry, slugify


class CatalogTest(unittest.TestCase):
    def test_slugify_is_deterministic(self) -> None:
        self.assertEqual("module-2-engineering-quality", slugify("Module 2. Engineering Quality"))

    def test_exact_title_selects_assignment_and_assessment(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            activity = root / ".ai-review/activities/sample"
            (activity / "assignments").mkdir(parents=True)
            (activity / "assessments").mkdir()
            (activity / "assignments/module-1-example.md").write_text("task", encoding="utf-8")
            (activity / "assessments/module-1-example.json").write_text(
                json.dumps({"title": "Module 1. Example", "criteria": [{"id": "one"}]}),
                encoding="utf-8",
            )
            assignment, assessment, parsed = select_catalog_entry(
                root, "sample", "Module 1. Example"
            )
            self.assertTrue(assignment.is_file())
            self.assertTrue(assessment.is_file())
            self.assertEqual("one", parsed["criteria"][0]["id"])

    def test_title_mismatch_is_rejected(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            activity = root / ".ai-review/activities/sample"
            (activity / "assignments").mkdir(parents=True)
            (activity / "assessments").mkdir()
            (activity / "assignments/module-1-example.md").write_text("task", encoding="utf-8")
            (activity / "assessments/module-1-example.json").write_text(
                json.dumps({"title": "Different title", "criteria": [{"id": "one"}]}),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ValueError, "exactly match"):
                select_catalog_entry(root, "sample", "Module 1. Example")


if __name__ == "__main__":
    unittest.main()
