from pathlib import Path
from tempfile import TemporaryDirectory
import io
import json
import sys
import unittest
import urllib.error

sys.path.insert(0, str(Path(__file__).parents[1] / "scripts"))

from agent_tools import ReadOnlyReviewTools, anthropic_schema, anthropic_tools, openai_tools
from provider_clients import AnthropicReviewClient, OpenAIReviewClient, client_for
from run_ai_review import schema_for_manifest


MANIFEST = {
    "assessment_selection": {"mode": "deterministic", "assessment_ids": ["sample"]},
    "candidate_assessments": [{"id": "sample", "title": "Sample", "requirement_ids": ["one", "two"]}],
}
REPORT = {
    "summary": "ok", "verdict": "PASSED", "assessments": [], "findings": [], "action_items": []
}


class Response:
    def __init__(self, payload):
        self.payload = payload
    def __enter__(self):
        return self
    def __exit__(self, *_):
        return False
    def read(self):
        return json.dumps(self.payload).encode()


class RunReviewTest(unittest.TestCase):
    def tools(self, directory):
        root = Path(directory)
        (root / "submission/files/src").mkdir(parents=True)
        (root / "submission/files/src/App.kt").write_text("class App\nfun run() = Unit\n")
        return ReadOnlyReviewTools(root)

    def test_schema_binds_assessment_title_and_requirement_ids(self):
        schema = schema_for_manifest(MANIFEST)
        assessment = schema["properties"]["assessments"]["items"]
        self.assertEqual(["sample"], assessment["properties"]["id"]["enum"])
        self.assertEqual(["Sample"], assessment["properties"]["title"]["enum"])
        requirements = assessment["properties"]["requirements"]
        self.assertEqual(2, requirements["minItems"])
        self.assertEqual(["one", "two"], requirements["items"]["properties"]["id"]["enum"])

    def test_schema_rejects_non_deterministic_manifest(self):
        with self.assertRaises(ValueError):
            schema_for_manifest({"assessment_selection": {"mode": "invalid"}})

    def test_read_only_tools_list_read_search_and_reject_escape(self):
        with TemporaryDirectory() as directory:
            tools = self.tools(directory)
            self.assertIn("submission/files/src/App.kt", tools.call("list_files", {"pattern": "**/*.kt"}))
            self.assertIn("2: fun run", tools.call("read_file", {"path": "submission/files/src/App.kt", "start_line": 2, "end_line": 2}))
            self.assertIn("App.kt:1", tools.call("search_text", {"query": "class", "pattern": "**/*.kt", "max_results": 10}))
            with self.assertRaises(ValueError):
                tools.call("read_file", {"path": "../secret", "start_line": 1, "end_line": 1})

    def test_tool_definitions_are_strict(self):
        self.assertTrue(all(item["strict"] for item in openai_tools()))
        self.assertTrue(all(item["strict"] for item in anthropic_tools()))
        encoded = json.dumps(anthropic_tools())
        self.assertNotIn("minLength", encoded)
        self.assertNotIn("minimum", encoded)

    def test_anthropic_schema_removes_unsupported_constraints(self):
        schema = anthropic_schema(schema_for_manifest(MANIFEST))
        encoded = json.dumps(schema)
        self.assertNotIn("minLength", encoded)
        self.assertNotIn("maxItems", encoded)
        requirements = schema["properties"]["assessments"]["items"]["properties"]["requirements"]
        self.assertEqual(1, requirements["minItems"])

    def test_openai_disables_storage_and_uses_schema(self):
        captured = []
        def opener(request, timeout):
            captured.append(json.loads(request.data))
            return Response({"status": "completed", "output": [{"type": "message", "content": [{"type": "output_text", "text": json.dumps(REPORT)}]}]})
        with TemporaryDirectory() as directory:
            result = OpenAIReviewClient("secret", opener, lambda _: None).review("model", "medium", "prompt", {"type": "object"}, self.tools(directory))
        self.assertEqual(REPORT, result)
        self.assertFalse(captured[0]["store"])
        self.assertTrue(captured[0]["text"]["format"]["strict"])

    def test_openai_executes_tool_loop(self):
        responses = iter([
            {"status": "completed", "output": [{"type": "function_call", "name": "list_files", "call_id": "call-1", "arguments": "{\"pattern\":\"**/*\"}"}]},
            {"status": "completed", "output": [{"type": "message", "content": [{"type": "output_text", "text": json.dumps(REPORT)}]}]},
        ])
        captured = []
        def opener(request, timeout):
            captured.append(json.loads(request.data))
            return Response(next(responses))
        with TemporaryDirectory() as directory:
            result = OpenAIReviewClient("secret", opener, lambda _: None).review("model", "medium", "prompt", {}, self.tools(directory))
        self.assertEqual(REPORT, result)
        self.assertEqual("function_call_output", captured[1]["input"][-1]["type"])

    def test_anthropic_executes_tool_loop_and_structured_output(self):
        responses = iter([
            {"content": [{"type": "tool_use", "id": "tool-1", "name": "list_files", "input": {"pattern": "**/*"}}], "stop_reason": "tool_use"},
            {"content": [{"type": "text", "text": json.dumps(REPORT)}], "stop_reason": "end_turn"},
        ])
        captured = []
        def opener(request, timeout):
            captured.append(json.loads(request.data))
            return Response(next(responses))
        with TemporaryDirectory() as directory:
            result = AnthropicReviewClient("secret", opener, lambda _: None).review("model", "high", "prompt", {"type": "object"}, self.tools(directory))
        self.assertEqual(REPORT, result)
        self.assertEqual("json_schema", captured[0]["output_config"]["format"]["type"])
        self.assertEqual("tool_result", captured[1]["messages"][-1]["content"][0]["type"])

    def test_rate_limit_is_retried_without_key_in_error(self):
        attempts = []
        def opener(request, timeout):
            attempts.append(request)
            if len(attempts) == 1:
                raise urllib.error.HTTPError(request.full_url, 429, "rate", {}, io.BytesIO(b"slow down"))
            return Response({"status": "completed", "output": [{"type": "message", "content": [{"type": "output_text", "text": json.dumps(REPORT)}]}]})
        with TemporaryDirectory() as directory:
            result = OpenAIReviewClient("top-secret", opener, lambda _: None).review("model", "low", "prompt", {}, self.tools(directory))
        self.assertEqual(REPORT, result)
        self.assertEqual(2, len(attempts))

    def test_provider_selection_is_explicit(self):
        self.assertIsInstance(client_for("anthropic", "key"), AnthropicReviewClient)
        self.assertIsInstance(client_for("openai", "key"), OpenAIReviewClient)
        with self.assertRaises(ValueError):
            client_for("other", "key")


if __name__ == "__main__":
    unittest.main()
