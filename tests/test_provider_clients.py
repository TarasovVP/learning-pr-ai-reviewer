from pathlib import Path
from tempfile import TemporaryDirectory
import json
import unittest
import sys

sys.path.insert(0, str(Path(__file__).parents[1] / "scripts"))

from agent_tools import ReadOnlyTools
from provider_clients import AnthropicClient, OpenAIClient


RESULT = {"summary": "Complete", "criteria": [{"id": "one", "result": "MET", "explanation": "Found", "evidence": [], "action_items": []}]}


class ProviderClientsTest(unittest.TestCase):
    def tools(self, directory: str) -> ReadOnlyTools:
        root = Path(directory)
        (root / "submission/files").mkdir(parents=True)
        return ReadOnlyTools(root)

    def test_anthropic_parses_structured_text(self) -> None:
        transport = lambda *_: {"content": [{"type": "text", "text": json.dumps(RESULT)}]}
        with TemporaryDirectory() as directory:
            result = AnthropicClient("secret", "model", transport).review(
                "system", "user", {"type": "object"}, self.tools(directory)
            )
        self.assertEqual("Complete", result["summary"])

    def test_openai_executes_read_only_tool_then_returns_result(self) -> None:
        responses = iter(
            [
                {"id": "response-1", "output": [{"type": "function_call", "name": "list_files", "call_id": "call-1", "arguments": "{}"}]},
                {"id": "response-2", "output": [], "output_text": json.dumps(RESULT)},
            ]
        )
        captured = []
        def transport(method, url, headers, payload):
            captured.append(payload)
            return next(responses)
        with TemporaryDirectory() as directory:
            result = OpenAIClient("secret", "model", "medium", transport).review(
                "system", "user", {"type": "object"}, self.tools(directory)
            )
        self.assertEqual("Complete", result["summary"])
        self.assertEqual("response-1", captured[1]["previous_response_id"])


if __name__ == "__main__":
    unittest.main()
