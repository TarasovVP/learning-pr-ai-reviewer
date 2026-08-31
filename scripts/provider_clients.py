from __future__ import annotations

import json
import time
import urllib.error
import urllib.request
from typing import Any, Callable

from agent_tools import ReadOnlyReviewTools, anthropic_schema, anthropic_tools, openai_tools


MAX_TOOL_TURNS = 40
Opener = Callable[..., Any]


def _request_with_retries(
    url: str,
    headers: dict[str, str],
    payload: dict[str, Any],
    opener: Opener,
    sleeper: Callable[[float], None],
) -> dict[str, Any]:
    request = urllib.request.Request(
        url,
        data=json.dumps(payload).encode(),
        method="POST",
        headers={**headers, "Content-Type": "application/json", "User-Agent": "learning-pr-ai-reviewer"},
    )
    for attempt in range(4):
        try:
            with opener(request, timeout=120) as response:
                return json.loads(response.read().decode())
        except urllib.error.HTTPError as error:
            detail = error.read().decode(errors="replace")
            if error.code != 429 and error.code < 500:
                raise RuntimeError(f"AI provider returned HTTP {error.code}: {detail}") from error
            if attempt == 3:
                raise RuntimeError(f"AI provider returned HTTP {error.code} after retries: {detail}") from error
        except urllib.error.URLError as error:
            if attempt == 3:
                raise RuntimeError(f"AI provider request failed after retries: {error}") from error
        sleeper(2**attempt)
    raise AssertionError("unreachable")


def _json_object(text: str) -> dict[str, Any]:
    try:
        parsed = json.loads(text)
    except json.JSONDecodeError as error:
        raise ValueError("AI provider returned invalid structured JSON") from error
    if not isinstance(parsed, dict):
        raise ValueError("AI provider returned a non-object result")
    return parsed


class OpenAIReviewClient:
    endpoint = "https://api.openai.com/v1/responses"

    def __init__(self, api_key: str, opener: Opener = urllib.request.urlopen, sleeper: Callable[[float], None] = time.sleep) -> None:
        self.api_key = api_key
        self.opener = opener
        self.sleeper = sleeper

    def _create(self, payload: dict[str, Any]) -> dict[str, Any]:
        return _request_with_retries(self.endpoint, {"Authorization": f"Bearer {self.api_key}"}, payload, self.opener, self.sleeper)

    def review(self, model: str, effort: str, instructions: str, schema: dict[str, Any], tools: ReadOnlyReviewTools) -> dict[str, Any]:
        history: list[dict[str, Any]] = [{"role": "user", "content": "Review the prepared Pull Request context. First inspect the manifest, all trusted assignments and assessments, and then the relevant safe submission evidence."}]
        for _ in range(MAX_TOOL_TURNS):
            response = self._create({
                "model": model, "instructions": instructions, "input": history,
                "reasoning": {"effort": effort}, "tools": openai_tools(),
                "tool_choice": "auto", "parallel_tool_calls": True,
                "include": ["reasoning.encrypted_content"],
                "text": {"format": {"type": "json_schema", "name": "learning_submission_review", "strict": True, "schema": schema}, "verbosity": "medium"},
                "max_output_tokens": 32_000, "store": False,
            })
            if response.get("error"):
                raise RuntimeError(f"OpenAI response failed: {response['error']}")
            if response.get("status") not in {"completed", "in_progress"}:
                raise RuntimeError(f"OpenAI response ended with status {response.get('status')!r}: {response.get('incomplete_details')}")
            output = response.get("output", [])
            calls = [item for item in output if item.get("type") == "function_call"]
            if not calls:
                parts = [part.get("text", "") for item in output if item.get("type") == "message" for part in item.get("content", []) if part.get("type") == "output_text"]
                text = "\n".join(parts).strip()
                if not text:
                    raise ValueError("OpenAI response contains no final output text")
                return _json_object(text)
            history.extend(output)
            for call in calls:
                try:
                    result = tools.call(call["name"], json.loads(call.get("arguments", "{}")))
                except (KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
                    result = json.dumps({"error": str(error)})
                history.append({"type": "function_call_output", "call_id": call["call_id"], "output": result})
        raise RuntimeError(f"OpenAI review exceeded {MAX_TOOL_TURNS} tool turns")


class AnthropicReviewClient:
    endpoint = "https://api.anthropic.com/v1/messages"

    def __init__(self, api_key: str, opener: Opener = urllib.request.urlopen, sleeper: Callable[[float], None] = time.sleep) -> None:
        self.api_key = api_key
        self.opener = opener
        self.sleeper = sleeper

    def _create(self, payload: dict[str, Any]) -> dict[str, Any]:
        return _request_with_retries(self.endpoint, {"x-api-key": self.api_key, "anthropic-version": "2023-06-01"}, payload, self.opener, self.sleeper)

    def review(self, model: str, effort: str, instructions: str, schema: dict[str, Any], tools: ReadOnlyReviewTools) -> dict[str, Any]:
        messages: list[dict[str, Any]] = [{"role": "user", "content": "Review the prepared Pull Request context. First inspect the manifest, all trusted assignments and assessments, and then the relevant safe submission evidence."}]
        output_config: dict[str, Any] = {"format": {"type": "json_schema", "schema": anthropic_schema(schema)}}
        if effort != "none":
            output_config["effort"] = effort
        for _ in range(MAX_TOOL_TURNS):
            response = self._create({"model": model, "max_tokens": 32_000, "system": instructions, "messages": messages, "tools": anthropic_tools(), "output_config": output_config})
            if response.get("error"):
                raise RuntimeError(f"Anthropic response failed: {response['error']}")
            content = response.get("content", [])
            calls = [item for item in content if item.get("type") == "tool_use"]
            if not calls:
                if response.get("stop_reason") not in {"end_turn", None}:
                    raise RuntimeError(f"Anthropic response ended with stop reason {response.get('stop_reason')!r}")
                text = "".join(item.get("text", "") for item in content if item.get("type") == "text").strip()
                if not text:
                    raise ValueError("Anthropic response contains no final output text")
                return _json_object(text)
            messages.append({"role": "assistant", "content": content})
            results = []
            for call in calls:
                try:
                    result = tools.call(call["name"], call.get("input", {}))
                    failed = False
                except (KeyError, TypeError, ValueError) as error:
                    result = json.dumps({"error": str(error)})
                    failed = True
                results.append({"type": "tool_result", "tool_use_id": call["id"], "content": result, "is_error": failed})
            messages.append({"role": "user", "content": results})
        raise RuntimeError(f"Anthropic review exceeded {MAX_TOOL_TURNS} tool turns")


def client_for(provider: str, api_key: str) -> OpenAIReviewClient | AnthropicReviewClient:
    if provider == "openai":
        return OpenAIReviewClient(api_key)
    if provider == "anthropic":
        return AnthropicReviewClient(api_key)
    raise ValueError("AI_PROVIDER must be anthropic or openai")
