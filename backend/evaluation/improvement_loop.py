"""Compare immutable production-evaluation reports for one bounded improvement iteration."""

from __future__ import annotations

import argparse
import json
import os
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from uuid import uuid4


METRICS = (
    "exact_task_count_accuracy",
    "task_extraction_precision_exact_text_alignment",
    "task_extraction_recall_exact_text_alignment",
    "task_extraction_f1_exact_text_alignment",
    "operation_type_accuracy",
    "date_phrase_accuracy",
    "time_phrase_accuracy",
    "priority_accuracy",
    "evidence_grounding_accuracy",
    "clarification_presence_accuracy",
    "unsupported_presence_accuracy",
    "response_status_accuracy",
    "title_content_token_coverage",
    "description_accuracy",
)
REGRESSION_GUARDS = {
    "exact_task_count_accuracy",
    "task_extraction_f1_exact_text_alignment",
    "task_extraction_recall_exact_text_alignment",
    "operation_type_accuracy",
    "evidence_grounding_accuracy",
    "clarification_presence_accuracy",
    "unsupported_presence_accuracy",
}


def read_report(path: Path) -> dict[str, Any]:
    report = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(report, dict):
        raise ValueError(f"Evaluation report must be a JSON object: {path}")
    if report.get("run_complete") is not True:
        raise ValueError(f"Evaluation report is incomplete: {path}")
    if not isinstance(report.get("summary"), dict) or not isinstance(report.get("cases"), list):
        raise ValueError(f"Evaluation report has an invalid structure: {path}")
    if not isinstance(report.get("run_id"), str) or not isinstance(report.get("dataset_sha256"), str):
        raise ValueError(f"Evaluation report is missing run or dataset identity: {path}")
    return report


def next_iteration_number(state: dict[str, Any], max_iterations: int, requested: int | None = None) -> int:
    if max_iterations < 1 or max_iterations > 5:
        raise ValueError("Maximum iterations must be between 1 and 5")
    iterations = state.get("iterations")
    if not isinstance(iterations, list):
        raise ValueError("state manifest has an invalid iterations list")
    next_iteration = len(iterations) + 1
    iteration = requested if requested is not None else next_iteration
    if iteration != next_iteration:
        raise ValueError(f"expected the next sequential iteration: {next_iteration}")
    if iteration > max_iterations:
        raise ValueError(f"maximum iterations ({max_iterations}) already reached")
    return iteration


def compare_pair(baseline: dict[str, Any], candidate: dict[str, Any], max_regression: float) -> dict[str, Any]:
    if baseline.get("dataset_sha256") != candidate.get("dataset_sha256"):
        raise ValueError("Baseline and candidate must use the exact same dataset")
    metric_deltas: dict[str, Any] = {}
    regressions = []
    for metric in METRICS:
        before = baseline["summary"].get(metric)
        after = candidate["summary"].get(metric)
        delta = round(after - before, 4) if isinstance(before, (int, float)) and isinstance(after, (int, float)) else None
        metric_deltas[metric] = {"baseline": before, "candidate": after, "delta": delta}
        if metric in REGRESSION_GUARDS and delta is not None and delta < -max_regression:
            regressions.append({"metric": metric, "delta": delta, "allowed_regression": max_regression})
    category_deltas: dict[str, Any] = {}
    base_categories = baseline["summary"].get("category_results", {})
    candidate_categories = candidate["summary"].get("category_results", {})
    for category in sorted(set(base_categories) | set(candidate_categories)):
        before_data = base_categories.get(category, {})
        after_data = candidate_categories.get(category, {})
        category_deltas[category] = {}
        for metric in METRICS:
            before = before_data.get(metric)
            after = after_data.get(metric)
            delta = round(after - before, 4) if isinstance(before, (int, float)) and isinstance(after, (int, float)) else None
            category_deltas[category][metric] = {"baseline": before, "candidate": after, "delta": delta}
            if metric in REGRESSION_GUARDS and delta is not None and delta < -max_regression:
                regressions.append({"category": category, "metric": metric, "delta": delta, "allowed_regression": max_regression})
    before_f1 = baseline["summary"].get("task_extraction_f1_exact_text_alignment")
    after_f1 = candidate["summary"].get("task_extraction_f1_exact_text_alignment")
    improved = isinstance(before_f1, (int, float)) and isinstance(after_f1, (int, float)) and after_f1 > before_f1
    return {
        "baseline_run_id": baseline["run_id"],
        "candidate_run_id": candidate["run_id"],
        "dataset_sha256": baseline["dataset_sha256"],
        "metric_deltas": metric_deltas,
        "category_deltas": category_deltas,
        "failure_categories_before": baseline["summary"].get("failure_category_counts", {}),
        "failure_categories_after": candidate["summary"].get("failure_category_counts", {}),
        "guardrail_regressions": regressions,
        "diagnostic_f1_improved": improved,
        "eligible_for_human_review": not regressions and improved,
        "automatic_acceptance": False,
    }


def build_comparison(
    baseline: dict[str, Any], candidate: dict[str, Any],
    holdout_baseline: dict[str, Any], holdout_candidate: dict[str, Any],
    iteration: int, max_iterations: int, max_regression: float,
) -> dict[str, Any]:
    if max_iterations < 1 or max_iterations > 5 or iteration < 1 or iteration > max_iterations:
        raise ValueError("Iteration must be within a configured limit of 1 to 5")
    if not 0 <= max_regression <= 1:
        raise ValueError("Maximum regression tolerance must be between 0 and 1")
    development = compare_pair(baseline, candidate, max_regression)
    holdout = compare_pair(holdout_baseline, holdout_candidate, max_regression)
    return {
        "report_version": "1",
        "created_at": datetime.now(timezone.utc).isoformat(),
        "iteration": iteration,
        "max_iterations": max_iterations,
        "max_allowed_guardrail_regression": max_regression,
        "development": development,
        "holdout": holdout,
        "eligible_for_human_review": development["eligible_for_human_review"] and not holdout["guardrail_regressions"],
        "automatic_acceptance": False,
        "human_review_required": True,
        "notes": [
            "Candidate prompt/backend changes are made and tested outside this report tool.",
            "Heuristic alignment metrics are not semantic ground truth.",
            "Do not promote a candidate based only on aggregate scores; inspect category and case-level differences.",
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--holdout-baseline", type=Path, required=True)
    parser.add_argument("--holdout-candidate", type=Path, required=True)
    parser.add_argument("--iteration", type=int)
    parser.add_argument("--max-iterations", type=int, default=3)
    parser.add_argument("--state-file", type=Path, required=True, help="Persistent manifest enforcing sequential bounded iterations.")
    parser.add_argument("--max-regression", type=float, default=0.02)
    parser.add_argument("--output-dir", type=Path, default=Path(__file__).resolve().parent / "reports" / "improvement-reviews")
    args = parser.parse_args()
    try:
        baseline_report = read_report(args.baseline)
        holdout_baseline_report = read_report(args.holdout_baseline)
        candidate_report = read_report(args.candidate)
        holdout_candidate_report = read_report(args.holdout_candidate)
        state_path = args.state_file.resolve()
        if state_path.exists():
            state = json.loads(state_path.read_text(encoding="utf-8"))
            if not isinstance(state, dict) or state.get("manifest_version") != "1" or not isinstance(state.get("iterations"), list):
                raise ValueError("state manifest is malformed")
            if state.get("max_iterations") != args.max_iterations or state.get("baseline_run_id") != baseline_report["run_id"] or state.get("holdout_baseline_run_id") != holdout_baseline_report["run_id"]:
                raise ValueError("state manifest is tied to a different baseline or iteration limit")
        else:
            state = {
                "manifest_version": "1",
                "baseline_run_id": baseline_report["run_id"],
                "holdout_baseline_run_id": holdout_baseline_report["run_id"],
                "max_iterations": args.max_iterations,
                "iterations": [],
            }
        iteration = next_iteration_number(state, args.max_iterations, args.iteration)
        input_reports = [args.baseline.resolve(), args.candidate.resolve(), args.holdout_baseline.resolve(), args.holdout_candidate.resolve()]
        if state_path in input_reports:
            raise ValueError("state manifest must not overwrite an evaluation report")
        result = build_comparison(
            baseline_report, candidate_report,
            holdout_baseline_report, holdout_candidate_report,
            iteration, args.max_iterations, args.max_regression,
        )
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        parser.error(f"Cannot compare evaluation reports: {type(exc).__name__}: {exc}")
    run_dir = args.output_dir / f"iteration-{iteration}-{uuid4().hex[:8]}"
    run_dir.mkdir(parents=True, exist_ok=False)
    (run_dir / "comparison.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    lines = [
        f"# TaskSync improvement review — iteration {iteration}/{args.max_iterations}", "",
        f"- Eligible for human review: {result['eligible_for_human_review']}",
        "- Automatically accepted: no", "",
        "## Development metrics", "",
        "| Metric | Baseline | Candidate | Delta |", "|---|---:|---:|---:|",
    ]
    for metric, values in result["development"]["metric_deltas"].items():
        lines.append(f"| {metric} | {values['baseline']} | {values['candidate']} | {values['delta']} |")
    lines += ["", "## Holdout guardrail regressions", "", json.dumps(result["holdout"]["guardrail_regressions"], ensure_ascii=False, indent=2), "", "## Review", "", "Inspect the linked run reports and case-level errors before accepting any prompt or backend change.", ""]
    (run_dir / "comparison.md").write_text("\n".join(lines), encoding="utf-8")
    state["iterations"].append({
        "iteration": iteration,
        "candidate_run_id": candidate_report["run_id"],
        "holdout_candidate_run_id": holdout_candidate_report["run_id"],
        "review_report": str(run_dir / "comparison.json"),
        "eligible_for_human_review": result["eligible_for_human_review"],
    })
    state_path.parent.mkdir(parents=True, exist_ok=True)
    temp = state_path.with_name(f".{state_path.name}.{uuid4().hex}.tmp")
    try:
        temp.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")
        os.replace(temp, state_path)
    finally:
        temp.unlink(missing_ok=True)
    print(f"Comparison JSON: {run_dir / 'comparison.json'}")
    print(f"Comparison Markdown: {run_dir / 'comparison.md'}")
    print(f"Eligible for human review: {result['eligible_for_human_review']}; automatic acceptance: false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
