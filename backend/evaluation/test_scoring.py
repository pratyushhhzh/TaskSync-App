try:
    from .scoring import score_case
except ImportError:  # Support pytest invoked directly on this file from backend/.
    from scoring import score_case


def test_scoring_reports_counts_type_date_time_and_requires_human_semantic_review():
    case = {
        "expected_operations": [
            {"type": "ADD", "date_phrase": "next Friday", "time_phrase": "9 am"},
            {"type": "COMPLETE"},
        ],
        "expected_clarification": True,
    }
    actual = {
        "operations": [
            {"type": "ADD", "date_phrase": "next Friday", "time_phrase": "9 am"},
            {"type": "COMPLETE"},
        ],
        "clarifications": [{"question": "Which task?"}],
        "unsupported_items": [],
    }
    score = score_case(case, actual)
    assert score["expected_operation_count"] == score["actual_operation_count"] == 2
    assert score["operation_types_match_in_order"]
    assert score["all_date_phrases_preserved"] and score["all_time_phrases_preserved"]
    assert score["clarification_presence_matches_expectation"]
    assert score["semantic_correctness"] is None
    assert score["human_review_required"]


def test_missing_operations_are_counted_as_separation_mismatch():
    score = score_case({"expected_operations": [{"type": "ADD"}]}, {"operations": []})
    assert score["task_separation_count_matches"] is False
