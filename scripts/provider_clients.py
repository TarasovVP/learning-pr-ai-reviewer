from __future__ import annotations

import json
import urllib.error
import urllib.request
from typing import Any, Callable

from agent_tools import ReadOnlyTools, TOOL_SPECS


JsonTransport = Callable[[str, str, dict[str, str], dict[str, Any]], dict[str, Any]]


def http_json(method: str, url: str, headers: dict[str, str], payload: dict[str, Any]) -> dict[str, Any]:
    request = urllib.request.Request(
        url,
        data=json.dumps(payload).encode(),
        method=method,
        headers={**headers, "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            return json.loads(response.read())
    except urllib.error.HTTPError as error:
        detail = error.read().decode(errors="replace")
        raise RuntimeError(f"AI provider returned HTTP {error.code}: {detail[:1000]}") from error


def parse_json_text(text: str) -> dict[str, Any]:
    try:
        value = json.loads(text)
    except json.JSONDecodeError as error:
        raise ValueError("AI provider did not return valid structured JSON") from error
    if not isinstance(value, dict):
        raise ValueError("AI provider returned a non-object result")
    return value


class AnthropicClient:
    def __init__(self, api_key: str, model: str, transport: JsonTransport = http_json) -> None:
        self.api_key = api_key
        self.model = model
        self.transport = transport

    def review(
        self,
        system: str,
        user: str,
        schema: dict[str, Any],
        tools: ReadOnlyTools,
        max_turns: int = 40,
    ) -> dict[str, Any]:
        messages: list[dict[str, Any]] = [{"role": "user", "content": user}]
        for _ in range(max_turns):
            response = self.transport(
                "POST",
                "https://api.anthropic.com/v1/messages",
                {
                    "x-api-key": self.api_key,
                    "anthropic-version": "2023-06-01",
                    "anthropic-beta": "structured-outputs-2025-11-13",
                },
                {
                    "model": self.model,
                    "max_tokens": 8192,
                    "system": system,
                    "messages": messages,
                    "tools": TOOL_SPECS,
                    "output_config": {
                        "format": {"type": "json_schema", "schema": schema}
                    },
                },
            )
            content = response.get("content", [])
            tool_uses = [block for block in content if block.get("type") == "tool_use"]
            if not tool_uses:
                text = "".join(
                    block.get("text", "") for block in content if block.get("type") == "text"
                )
                return parse_json_text(text)
            messages.append({"role": "assistant", "content": content})
            results = []
            for call in tool_uses:
                try:
                    result = tools.call(call["name"], call.get("input", {}))
                    is_error = False
                except Exception as error:
                    result = {"error": str(error)}
                    is_error = True
                results.append(
                    {
                        "type": "tool_result",
                        "tool_use_id": call["id"],
                        "content": json.dumps(result, ensure_ascii=False),
                        "is_error": is_error,
                    }
                )
            messages.append({"role": "user", "content": results})
        raise RuntimeError("Anthropic review exceeded the tool-turn limit")


class OpenAIClient:
    def __init__(
        self,
        api_key: str,
        model: str,
        reasoning_effort: str,
        transport: JsonTransport = http_json,
    ) -> None:
        self.api_key = api_key
        self.model = model
        self.reasoning_effort = reasoning_effort
        self.transport = transport

    def _tools(self) -> list[dict[str, Any]]:
        return [
            {
                "type": "function",
                "name": tool["name"],
                "description": tool["description"],
                "parameters": tool["input_schema"],
                "strict": True,
            }
            for tool in TOOL_SPECS
        ]

    def review(
        self,
        system: str,
        user: str,
        schema: dict[str, Any],
        tools: ReadOnlyTools,
        max_turns: int = 40,
    ) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "model": self.model,
            "instructions": system,
            "input": user,
            "tools": self._tools(),
            "text": {
                "format": {
                    "type": "json_schema",
                    "name": "learning_submission_review",
                    "strict": True,
                    "schema": schema,
                }
            },
        }
        if self.reasoning_effort:
            payload["reasoning"] = {"effort": self.reasoning_effort}
        for _ in range(max_turns):
            response = self.transport(
                "POST",
                "https://api.openai.com/v1/responses",
                {"Authorization": f"Bearer {self.api_key}"},
                payload,
            )
            calls = [item for item in response.get("output", []) if item.get("type") == "function_call"]
            if not calls:
                text = response.get("output_text") or "".join(
                    part.get("text", "")
                    for item in response.get("output", [])
                    for part in item.get("content", [])
                    if part.get("type") == "output_text"
                )
                return parse_json_text(text)
            outputs = []
            for call in calls:
                try:
                    arguments = json.loads(call.get("arguments", "{}"))
                    result = tools.call(call["name"], arguments)
                except Exception as error:
                    result = {"error": str(error)}
                outputs.append(
                    {
                        "type": "function_call_output",
                        "call_id": call["call_id"],
                        "output": json.dumps(result, ensure_ascii=False),
                    }
                )
            payload = {
                "model": self.model,
                "previous_response_id": response["id"],
                "input": outputs,
                "tools": self._tools(),
                "text": payload["text"],
            }
            if self.reasoning_effort:
                payload["reasoning"] = {"effort": self.reasoning_effort}
        raise RuntimeError("OpenAI review exceeded the tool-turn limit")


def create_client(provider: str, api_key: str, model: str, reasoning_effort: str) -> Any:
    if provider == "anthropic":
        return AnthropicClient(api_key, model)
    if provider == "openai":
        return OpenAIClient(api_key, model, reasoning_effort)
    raise ValueError("AI_PROVIDER must be anthropic or openai")
