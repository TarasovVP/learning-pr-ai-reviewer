from __future__ import annotations

from typing import Any


RESULTS = {"MET", "PARTIAL", "NOT_MET", "NOT_VERIFIABLE"}


def verdict_for(criteria: list[dict[str, Any]]) -> str:
    values = {item["result"] for item in criteria}
    if "NOT_MET" in values:
        return "FAILED"
    if values & {"PARTIAL", "NOT_VERIFIABLE"}:
        return "PASSED WITH NOTES"
    return "PASSED"


def validate_review(review: Any, assessment: dict[str, Any]) -> dict[str, Any]:
    if not isinstance(review, dict):
        raise ValueError("Review result must be an object")
    if not isinstance(review.get("summary"), str) or not review["summary"].strip():
        raise ValueError("Review summary must be a non-empty string")
    criteria = review.get("criteria")
    if not isinstance(criteria, list):
        raise ValueError("Review criteria must be an array")
    expected = [item["id"] for item in assessment["criteria"]]
    actual = [item.get("id") for item in criteria if isinstance(item, dict)]
    if actual != expected:
        raise ValueError(f"Review criterion ids/order do not match assessment: {actual} != {expected}")
    for item in criteria:
        if item.get("result") not in RESULTS:
            raise ValueError(f"Invalid criterion result for {item.get('id')}")
        if not isinstance(item.get("explanation"), str) or not item["explanation"].strip():
            raise ValueError(f"Criterion {item['id']} needs an explanation")
        for key in ("evidence", "action_items"):
            if not isinstance(item.get(key), list) or any(
                not isinstance(value, str) or not value.strip() for value in item[key]
            ):
                raise ValueError(f"Criterion {item['id']} has invalid {key}")
        if item["result"] in {"PARTIAL", "NOT_MET"} and not item["action_items"]:
            raise ValueError(f"Criterion {item['id']} needs an action item")
        if item["result"] in {"MET", "NOT_VERIFIABLE"} and item["action_items"]:
            raise ValueError(f"Criterion {item['id']} must not contain action items")
    return {**review, "verdict": verdict_for(criteria)}
