from __future__ import annotations

try:
    from .improvement_loop import build_comparison, compare_pair, next_iteration_number
except ImportError:
    try:
        from backend.evaluation.improvement_loop import build_comparison, compare_pair, next_iteration_number
    except ImportError:
        from improvement_loop import build_comparison, compare_pair, next_iteration_number


def report(run_id: str, fingerprint: str, *, f1: float, recall: float, grounding: float = 1.0):
    metrics = {
        "exact_task_count_accuracy": f1,
        "task_extraction_precision_exact_text_alignment": f1,
        "task_extraction_recall_exact_text_alignment": recall,
        "task_extraction_f1_exact_text_alignment": f1,
        "operation_type_accuracy": 1.0,
        "date_phrase_accuracy": 1.0,
        "time_phrase_accuracy": 1.0,
        "priority_accuracy": 1.0,
        "evidence_grounding_accuracy": grounding,
        "clarification_presence_accuracy": 1.0,
        "unsupported_presence_accuracy": 1.0,
        "response_status_accuracy": 1.0,
        "failure_category_counts": {},
    }
    return {"run_complete": True, "run_id": run_id, "dataset_sha256": fingerprint, "summary": metrics, "cases": []}


def test_improvement_gate_requires_development_gain_and_no_holdout_guardrail_regression():
    baseline = report("base", "dev", f1=0.6, recall=0.6)
    candidate = report("candidate", "dev", f1=0.7, recall=0.62)
    holdout_base = report("hold-base", "hold", f1=0.6, recall=0.6)
    holdout_candidate = report("hold-candidate", "hold", f1=0.7, recall=0.55)
    result = build_comparison(baseline, candidate, holdout_base, holdout_candidate, 1, 3, 0.02)
    assert result["development"]["eligible_for_human_review"]
    assert result["holdout"]["guardrail_regressions"]
    assert not result["eligible_for_human_review"]
    assert result["automatic_acceptance"] is False


def test_improvement_loop_rejects_dataset_mismatch_and_unbounded_iterations():
    try:
        compare_pair(report("base", "dataset-a", f1=0.6, recall=0.6), report("candidate", "dataset-b", f1=0.7, recall=0.7), 0.02)
    except ValueError as exc:
        assert "same dataset" in str(exc)
    else:
        raise AssertionError("Dataset mismatch must be rejected")

    same = report("same", "dataset", f1=0.6, recall=0.6)
    try:
        build_comparison(same, same, same, same, 6, 6, 0.02)
    except ValueError as exc:
        assert "1 to 5" in str(exc)
    else:
        raise AssertionError("Iteration limit must be enforced")


def test_iteration_manifest_requires_sequential_bounded_review_cycles():
    state = {"iterations": [{"iteration": 1}, {"iteration": 2}], "max_iterations": 3}
    assert next_iteration_number(state, 3) == 3
    try:
        next_iteration_number({"iterations": state["iterations"] + [{"iteration": 3}]}, 3)
    except ValueError as exc:
        assert "maximum iterations" in str(exc)
    else:
        raise AssertionError("A fourth iteration must be rejected")
