try:
    from .scoring import score_case
except ImportError:  # Support pytest invoked directly on this file from backend/.
    from scoring import score_case


def test_scoring_aligns_operations_and_reports_coverage_without_claiming_semantic_accuracy():
    case = {
        "transcript": "Next Friday, call the clinic at 9 am and finish the report.",
        "expected_operations": [
            {"type": "ADD", "title": "call the clinic", "date_phrase": "Next Friday", "time_phrase": "9 am"},
            {"type": "ADD", "title": "finish the report"},
        ],
        "expected_clarification": True,
    }
    actual = {
        "operations": [
            {"type": "ADD", "operation_id": "op-1", "source_text": "call the clinic at 9 am", "title": "call the clinic", "date_phrase": "Next Friday", "time_phrase": "9 am"},
            {"type": "ADD", "operation_id": "op-2", "source_text": "finish the report", "title": "finish the report"},
        ],
        "clarifications": [{"source_text": "finish the report", "question": "When should this be done?"}],
        "unsupported_items": [],
    }
    score = score_case(case, actual)
    assert score["expected_operation_count"] == score["actual_operation_count"] == 2
    assert score["task_extraction_precision_exact_text_alignment"] == 1.0
    assert score["task_extraction_recall_exact_text_alignment"] == 1.0
    assert score["operation_type_accuracy"] == 1.0
    assert score["all_date_phrases_preserved"] and score["all_time_phrases_preserved"]
    assert score["evidence_grounding_accuracy"] == 1.0
    assert score["clarification_presence_matches_expectation"]
    assert score["semantic_correctness"] is None
    assert score["human_review_required"]


def test_update_fields_are_scored_from_the_versioned_changes_object():
    case = {
        "transcript": "Move the dentist task to Monday at 10 am.",
        "expected_operations": [{
            "type": "UPDATE", "target_text": "dentist task", "date_phrase": "Monday", "time_phrase": "10 am",
        }],
    }
    actual = {"operations": [{
        "type": "UPDATE", "target_text": "dentist task", "changes": {"date_phrase": "Monday", "time_phrase": "10 am"},
        "source_text": "Move the dentist task to Monday at 10 am.",
    }]}
    score = score_case(case, actual)
    assert score["all_date_phrases_preserved"]
    assert score["all_time_phrases_preserved"]
    assert score["operation_type_accuracy"] == 1.0


def test_missing_duplicate_extra_and_ungrounded_operations_are_reported():
    case = {
        "transcript": "Call the dentist tomorrow.",
        "expected_operations": [{"type": "ADD", "title": "call the dentist", "date_phrase": "tomorrow"}],
    }
    actual = {"operations": [
        {"type": "ADD", "title": "call the dentist", "date_phrase": "tomorrow", "source_text": "Call the dentist tomorrow."},
        {"type": "ADD", "title": "call the dentist", "date_phrase": "tomorrow", "source_text": "invented source"},
        {"type": "ADD", "title": "buy a boat", "source_text": "buy a boat"},
    ]}
    score = score_case(case, actual)
    assert score["duplicate_task_count"] == 1
    assert score["invented_or_unmatched_task_count"] == 1
    assert score["evidence_grounding_accuracy"] == 1 / 3


def test_missing_operations_are_counted_as_extraction_recall_failure():
    score = score_case({"expected_operations": [{"type": "ADD", "title": "call the dentist"}]}, {"operations": []})
    assert score["task_separation_count_matches"] is False
    assert score["task_extraction_recall_exact_text_alignment"] == 0.0
    assert score["missing_expected_operation_indices"] == [0]


def test_description_status_and_cancellation_failures_are_classified_for_review():
    case = {
        "transcript": "I already called Dev, so forget the call reminder.",
        "expected_operations": [],
        "expected_cancelled_intents": ["call Dev"],
    }
    actual = {
        "status": "proposals",
        "operations": [{"type": "ADD", "title": "call Dev", "source_text": "called Dev"}],
        "clarifications": [],
        "unsupported_items": [],
    }
    score = score_case(case, actual)
    assert "CANCELLED_INTENT_REINTRODUCED" in score["failure_categories"]
    assert score["review_priority"] == "high"
    assert score["suggested_improvement"]


def test_description_details_are_checked_separately_from_title_similarity():
    case = {
        "transcript": "Prepare the monthly stock report using regional figures.",
        "expected_operations": [{"type": "ADD", "title": "Prepare the monthly stock report", "description": "using regional figures"}],
    }
    actual = {"operations": [{"type": "ADD", "title": "Prepare the monthly stock report", "description": "using guessed figures", "source_text": "Prepare the monthly stock report"}]}
    score = score_case(case, actual)
    assert score["title_similarity_average"] == 1.0
    assert not score["all_descriptions_preserved"]
    assert "DESCRIPTION" in score["failure_categories"]
