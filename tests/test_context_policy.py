from pathlib import Path
import unittest
import sys

sys.path.insert(0, str(Path(__file__).parents[1] / "scripts"))

from prepare_context import safe_path


class ContextPolicyTest(unittest.TestCase):
    def test_allows_reviewable_source_and_documentation(self) -> None:
        self.assertTrue(safe_path("shared/src/commonMain/kotlin/Repository.kt"))
        self.assertTrue(safe_path("README.md"))

    def test_rejects_control_files_build_outputs_and_secrets(self) -> None:
        rejected = [
            ".github/workflows/review.yml",
            ".ai-review/activities/sample/assessment.json",
            "app/build/generated/output.txt",
            "local.properties",
            "keys/private-key.pem",
            "../outside.txt",
        ]
        for path in rejected:
            with self.subTest(path=path):
                self.assertFalse(safe_path(path))


if __name__ == "__main__":
    unittest.main()
