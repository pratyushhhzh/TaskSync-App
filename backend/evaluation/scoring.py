from __future__ import annotations

from collections import Counter
from difflib import SequenceMatcher
import re
from typing import Any


IMPROVEMENT_HINTS = {
    "MISSING_TASK_OR_MERGED_TASKS": "Review responsibility segmentation and long-transcript coverage; add a minimal regression case for the missing responsibility.",
    "INVENTED_OR_UNMATCHED_TASKS": "Review intent versus history/uncertainty handling and require transcript-grounded task evidence.",
    "DUPLICATE_PROPOSALS": "Review repeated-mention deduplication while retaining genuinely distinct obligations.",
    "OPERATION_TYPE": "Review the distinction between ADD, UPDATE, COMPLETE, and DELETE using the exact target evidence.",
    "TASK_TITLE_REVIEW": "Human-review the title paraphrase; string similarity alone cannot decide semantic equivalence.",
    "DATE_PRESERVATION": "Review temporal phrase scope and preserve only the date governed by that task clause.",
    "TIME_PRESERVATION": "Review time extraction separately from date extraction; do not infer an exact time from a broad period.",
    "PRIORITY": "Review explicit priority evidence and keep deadlines distinct from priority.",
    "DESCRIPTION": "Review whether optional task details were preserved without turning unrelated context into a description.",
    "UNGROUNDED_EVIDENCE": "Review source_text and require an exact supporting transcript span.",
    "CLARIFICATION_OR_AMBIGUITY": "Review whether unresolved or ambiguous intent should have produced a clarification instead of a guess.",
    "UNSUPPORTED_INTENT": "Review unsupported-item handling and make partial interpretation visible where appropriate.",
    "RESPONSE_STATUS": "Review consistency between proposals, clarifications, unsupported items, and the response status.",
    "CANCELLED_INTENT_REINTRODUCED": "Review chronological correction and cancellation handling so a retracted task is not reintroduced.",
}


def recommendation_for(categories: list[str]) -> str | None:
    hints = [IMPROVEMENT_HINTS[category] for category in categories if category in IMPROVEMENT_HINTS]
    return " ".join(dict.fromkeys(hints)) or None


def _text(value: Any) -> str:
    return re.sub(r"[^\w\s]", " ", str(value or "").casefold(), flags=re.UNICODE).strip()


_TITLE_STOPWORDS = {"a", "an", "and", "at", "by", "for", "in", "my", "of", "on", "the", "to", "task", "please"}


def _content_tokens(value: Any) -> set[str]:
    return {token for token in re.findall(r"\w+", _text(value), flags=re.UNICODE) if token not in _TITLE_STOPWORDS}


def _label(operation: dict[str, Any]) -> str:
    if operation.get("type") == "UPDATE":
        return _text(operation.get("target_text") or operation.get("changes", {}).get("title"))
    return _text(operation.get("title") or operation.get("target_text"))


def _field(operation: dict[str, Any], field: str) -> Any:
    if operation.get("type") == "UPDATE":
        return operation.get("changes", {}).get(field, operation.get(field))
    return operation.get(field)


def _align(expected: list[dict[str, Any]], actual: list[dict[str, Any]]) -> list[tuple[int, int, float]]:
    """Greedily align by human-readable task text; semantic correctness remains human scored."""
    candidates = []
    for expected_index, expected_op in enumerate(expected):
        expected_label = _label(expected_op)
        for actual_index, actual_op in enumerate(actual):
            actual_label = _label(actual_op)
            if not expected_label or not actual_label:
                continue
            label_score = SequenceMatcher(None, expected_label, actual_label).ratio()
            target_score = 0.0
            if expected_op.get("target_text") and actual_op.get("target_text"):
                target_score = SequenceMatcher(None, _text(expected_op["target_text"]), _text(actual_op["target_text"])).ratio()
            score = max(label_score, target_score)
            if score >= 0.72:
                candidates.append((score, expected_index, actual_index))
    # Stable tie-breaking prefers the earliest generated operation; otherwise an exact
    # duplicate can consume the expected match and make the original look unmatched.
    candidates.sort(key=lambda candidate: (-candidate[0], candidate[1], candidate[2]))
    expected_used: set[int] = set()
    actual_used: set[int] = set()
    aligned = []
    for score, expected_index, actual_index in candidates:
        if expected_index in expected_used or actual_index in actual_used:
            continue
        expected_used.add(expected_index)
        actual_used.add(actual_index)
        aligned.append((expected_index, actual_index, score))
    return sorted(aligned)


def score_case(case: dict[str, Any], actual: dict[str, Any] | None) -> dict[str, Any]:
    expected = case.get("expected_operations", [])
    actual_ops = (actual or {}).get("operations", [])
    expected_types = [op.get("type") for op in expected]
    actual_types = [op.get("type") for op in actual_ops]
    aligned = _align(expected, actual_ops)
    expected_to_actual = {expected_index: actual_index for expected_index, actual_index, _ in aligned}
    matched_actual = {actual_index for _, actual_index, _ in aligned}

    def field_checks(field: str) -> list[dict[str, Any]]:
        checks = []
        for expected_index, expected_op in enumerate(expected):
            actual_index = expected_to_actual.get(expected_index)
            actual_op = actual_ops[actual_index] if actual_index is not None else {}
            wanted = _field(expected_op, field)
            got = _field(actual_op, field)
            checks.append({
                "expected_index": expected_index,
                "actual_index": actual_index,
                "expected": wanted,
                "actual": got,
                "preserved_exactly": wanted == got if wanted is not None else got is None,
                "unexpected_value": wanted is None and got is not None,
            })
        return checks

    date_checks = field_checks("date_phrase")
    time_checks = field_checks("time_phrase")
    priority_checks = field_checks("priority")
    description_checks = field_checks("description")
    title_checks = []
    type_checks = []
    for expected_index, expected_op in enumerate(expected):
        actual_index = expected_to_actual.get(expected_index)
        got = actual_ops[actual_index] if actual_index is not None else {}
        expected_title = _field(expected_op, "title")
        actual_title = _field(got, "title")
        if expected_title is not None:
            title_checks.append({
                "expected_index": expected_index,
                "actual_index": actual_index,
                "expected": expected_title,
                "actual": actual_title,
                "similarity": round(SequenceMatcher(None, _text(expected_title), _text(actual_title)).ratio(), 4),
                "exact_normalized_match": _text(expected_title) == _text(actual_title),
                "expected_content_tokens": sorted(_content_tokens(expected_title)),
                "matched_content_tokens": sorted(_content_tokens(expected_title) & _content_tokens(actual_title)),
                "content_token_coverage": round(
                    len(_content_tokens(expected_title) & _content_tokens(actual_title)) / len(_content_tokens(expected_title)), 4
                ) if _content_tokens(expected_title) else 1.0,
            })
        if actual_index is not None:
            type_checks.append({
                "expected_index": expected_index,
                "actual_index": actual_index,
                "expected": expected_op.get("type"),
                "actual": got.get("type"),
                "correct": expected_op.get("type") == got.get("type"),
            })

    transcript = re.sub(r"\s+", " ", str(case.get("transcript", "")).casefold()).strip()
    evidence_checks = []
    for index, operation in enumerate(actual_ops):
        evidence = re.sub(r"\s+", " ", str(operation.get("source_text", "")).casefold()).strip()
        evidence_checks.append({"actual_index": index, "grounded": bool(evidence) and evidence in transcript})

    expected_evidence_checks = []
    for expected_index, expected_op in enumerate(expected):
        expected_source = expected_op.get("source_text")
        if expected_source:
            expected_evidence_checks.append({
                "expected_index": expected_index,
                "source_text": expected_source,
                "grounded": expected_source.casefold() in transcript,
            })

    duplicate_indices = []
    seen: set[tuple[Any, str]] = set()
    for index, item in enumerate(actual_ops):
        key = (item.get("type"), _label(item))
        if key in seen:
            duplicate_indices.append(index)
        seen.add(key)

    expected_clarification = case.get("expected_clarification", False)
    expected_unsupported = case.get("expected_unsupported", False)
    actual_clarification = bool((actual or {}).get("clarifications"))
    actual_unsupported = bool((actual or {}).get("unsupported_items"))
    expected_status = case.get("expected_status")
    if expected_status is None:
        expected_status = (
            "partial" if expected and (expected_clarification or expected_unsupported or case.get("expected_partial"))
            else "proposals" if expected
            else "needs_clarification" if expected_clarification
            else "unsupported" if expected_unsupported
            else None
        )
    actual_status = (actual or {}).get("status")
    missing = [index for index in range(len(expected)) if index not in expected_to_actual]
    extra = [index for index in range(len(actual_ops)) if index not in matched_actual]
    unmatched_nonduplicate = [index for index in extra if index not in duplicate_indices]
    failure_categories = []
    if missing:
        failure_categories.append("MISSING_TASK_OR_MERGED_TASKS")
    if unmatched_nonduplicate:
        failure_categories.append("INVENTED_OR_UNMATCHED_TASKS")
    if duplicate_indices:
        failure_categories.append("DUPLICATE_PROPOSALS")
    if any(not item["correct"] for item in type_checks):
        failure_categories.append("OPERATION_TYPE")
    if any(not item["exact_normalized_match"] for item in title_checks):
        failure_categories.append("TASK_TITLE_REVIEW")
    if any(not item["preserved_exactly"] for item in date_checks):
        failure_categories.append("DATE_PRESERVATION")
    if any(not item["preserved_exactly"] for item in time_checks):
        failure_categories.append("TIME_PRESERVATION")
    if any(not item["preserved_exactly"] for item in priority_checks):
        failure_categories.append("PRIORITY")
    if any(not item["preserved_exactly"] for item in description_checks):
        failure_categories.append("DESCRIPTION")
    if any(not item["grounded"] for item in evidence_checks):
        failure_categories.append("UNGROUNDED_EVIDENCE")
    if expected_clarification != actual_clarification:
        failure_categories.append("CLARIFICATION_OR_AMBIGUITY")
    if expected_unsupported != actual_unsupported:
        failure_categories.append("UNSUPPORTED_INTENT")
    if expected_status is not None and actual_status != expected_status:
        failure_categories.append("RESPONSE_STATUS")
    cancelled_intents = case.get("expected_cancelled_intents", [])
    cancelled_hallucinations = [
        phrase for phrase in cancelled_intents
        if any(phrase.casefold() in _text(_label(operation)) for operation in actual_ops)
    ]
    if cancelled_hallucinations:
        failure_categories.append("CANCELLED_INTENT_REINTRODUCED")
    precision = len(aligned) / len(actual_ops) if actual_ops else (1.0 if not expected else 0.0)
    recall = len(aligned) / len(expected) if expected else (1.0 if not actual_ops else 0.0)
    title_score = sum(item["similarity"] for item in title_checks) / len(title_checks) if title_checks else None
    return {
        "expected_operation_count": len(expected),
        "actual_operation_count": len(actual_ops),
        "operation_count_matches": len(expected) == len(actual_ops),
        "expected_operation_types": expected_types,
        "actual_operation_types": actual_types,
        "expected_status": expected_status,
        "actual_status": actual_status,
        "response_status_correct": expected_status is None or expected_status == actual_status,
        "operation_types_match_in_order": expected_types == actual_types,
        "operation_type_counts_match": Counter(expected_types) == Counter(actual_types),
        "task_separation_count_matches": len(expected) == len(actual_ops),
        "task_extraction_precision_exact_text_alignment": round(precision, 4),
        "task_extraction_recall_exact_text_alignment": round(recall, 4),
        "task_extraction_f1_exact_text_alignment": round(2 * precision * recall / (precision + recall), 4) if precision + recall else 0.0,
        "missing_expected_operation_indices": missing,
        "extra_actual_operation_indices": extra,
        "unmatched_nonduplicate_actual_operation_indices": unmatched_nonduplicate,
        "failure_categories": failure_categories,
        "segmentation_error": "merge_or_omission" if len(actual_ops) < len(expected) else "split_or_extra" if len(actual_ops) > len(expected) else None,
        "duplicate_actual_operation_indices": duplicate_indices,
        "duplicate_task_count": len(duplicate_indices),
        "invented_or_unmatched_task_count": len(unmatched_nonduplicate),
        "title_similarity_average": round(title_score, 4) if title_score is not None else None,
        "title_content_token_coverage": round(sum(item["content_token_coverage"] for item in title_checks) / len(title_checks), 4) if title_checks else None,
        "title_checks": title_checks,
        "operation_type_checks": type_checks,
        "operation_type_accuracy": round(sum(item["correct"] for item in type_checks) / len(type_checks), 4) if type_checks else None,
        "date_phrase_checks": date_checks,
        "time_phrase_checks": time_checks,
        "priority_checks": priority_checks,
        "description_checks": description_checks,
        "all_date_phrases_preserved": all(item["preserved_exactly"] for item in date_checks),
        "all_time_phrases_preserved": all(item["preserved_exactly"] for item in time_checks),
        "all_descriptions_preserved": all(item["preserved_exactly"] for item in description_checks),
        "description_accuracy": sum(item["preserved_exactly"] for item in description_checks) / len(description_checks) if description_checks else None,
        "priority_accuracy": sum(item["preserved_exactly"] for item in priority_checks) / len(priority_checks) if priority_checks else None,
        "evidence_checks": evidence_checks,
        "expected_evidence_checks": expected_evidence_checks,
        "expected_evidence_grounding_accuracy": sum(item["grounded"] for item in expected_evidence_checks) / len(expected_evidence_checks) if expected_evidence_checks else None,
        "evidence_grounding_accuracy": sum(item["grounded"] for item in evidence_checks) / len(evidence_checks) if evidence_checks else 1.0,
        "expected_clarification": expected_clarification,
        "actual_clarification_present": actual_clarification,
        "clarification_presence_matches_expectation": expected_clarification == actual_clarification,
        "expected_unsupported": expected_unsupported,
        "actual_unsupported_present": actual_unsupported,
        "unsupported_presence_matches_expectation": expected_unsupported == actual_unsupported,
        "cancelled_intent_hallucinations": cancelled_hallucinations,
        "suggested_improvement": recommendation_for(failure_categories),
        "review_priority": "high" if any(category in failure_categories for category in (
            "MISSING_TASK_OR_MERGED_TASKS", "INVENTED_OR_UNMATCHED_TASKS", "DUPLICATE_PROPOSALS",
            "OPERATION_TYPE", "UNGROUNDED_EVIDENCE", "CLARIFICATION_OR_AMBIGUITY", "CANCELLED_INTENT_REINTRODUCED",
        )) else "normal" if failure_categories else "none",
        "semantic_correctness": None,
        "human_review_required": True,
    }
