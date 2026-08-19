from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
import sys

sys.path.insert(0, str(Path(__file__).parents[1] / "scripts"))

from agent_tools import ReadOnlyTools


class AgentToolsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = TemporaryDirectory()
        root = Path(self.temp.name)
        files = root / "submission/files/src"
        files.mkdir(parents=True)
        (files / "Example.kt").write_text("class Example\nfun answer() = 42\n", encoding="utf-8")
        self.tools = ReadOnlyTools(root)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_lists_reads_and_searches(self) -> None:
        self.assertEqual(["src/Example.kt"], self.tools.list_files("*.kt")["files"])
        self.assertIn("2: fun answer", self.tools.read_file("src/Example.kt", 2, 2)["content"])
        self.assertEqual(2, self.tools.search_text("answer")["matches"][0]["line"])

    def test_cannot_escape_submission_root(self) -> None:
        with self.assertRaisesRegex(ValueError, "outside"):
            self.tools.read_file("../../metadata.json")


if __name__ == "__main__":
    unittest.main()
