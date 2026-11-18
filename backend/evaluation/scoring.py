from __future__ import annotations

from collections import Counter
from typing import Any


def score_case(case: dict[str, Any], actual: dict[str, Any] | None) -> dict[str, Any]:
    expected = case.get("expected_operations", [])
    actual_ops = (actual or {}).get("operations", [])
    expected_types = [op.get("type") for op in expected]
    actual_types = [op.get("type") for op in actual_ops]

    date_checks: list[dict[str, Any]] = []
    time_checks: list[dict[str, Any]] = []
    for index, expected_op in enumerate(expected):
        actual_op = actual_ops[index] if index < len(actual_ops) else {}
        for field, checks in (("date_phrase", date_checks), ("time_phrase", time_checks)):
            phrase = expected_op.get(field)
            if phrase is not None:
                checks.append({
                    "operation_index": index,
                    "expected": phrase,
                    "actual": actual_op.get(field),
                    "preserved_exactly": actual_op.get(field) == phrase,
                })

    expected_clarification = case.get("expected_clarification", False)
    expected_unsupported = case.get("expected_unsupported", False)
    actual_clarification = bool((actual or {}).get("clarifications"))
    actual_unsupported = bool((actual or {}).get("unsupported_items"))
    return {
        "expected_operation_count": len(expected),
        "actual_operation_count": len(actual_ops),
        "operation_count_matches": len(expected) == len(actual_ops),
        "expected_operation_types": expected_types,
        "actual_operation_types": actual_types,
        "operation_types_match_in_order": expected_types == actual_types,
        "operation_type_counts_match": Counter(expected_types) == Counter(actual_types),
        "task_separation_count_matches": len(expected) == len(actual_ops),
        "date_phrase_checks": date_checks,
        "time_phrase_checks": time_checks,
        "all_date_phrases_preserved": all(item["preserved_exactly"] for item in date_checks),
        "all_time_phrases_preserved": all(item["preserved_exactly"] for item in time_checks),
        "expected_clarification": expected_clarification,
        "actual_clarification_present": actual_clarification,
        "clarification_presence_matches_expectation": expected_clarification == actual_clarification,
        "expected_unsupported": expected_unsupported,
        "actual_unsupported_present": actual_unsupported,
        "unsupported_presence_matches_expectation": expected_unsupported == actual_unsupported,
        "semantic_correctness": None,
        "human_review_required": True,
    }
