from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import defaultdict
from datetime import datetime
from pathlib import Path
from typing import Any, Callable
from uuid import uuid4
from zoneinfo import ZoneInfo

try:
    from .scoring import IMPROVEMENT_HINTS, score_case
except ImportError:  # Support direct invocation from backend/evaluation/.
    from scoring import IMPROVEMENT_HINTS, score_case


HERE = Path(__file__).resolve().parent
DATASET_PATH = HERE / "dataset.json"
DEFAULT_BASE_URL = "http://127.0.0.1:8000"
DEFAULT_REQUEST_DELAY_SECONDS = 2.5
MAX_REQUEST_DELAY_SECONDS = 60.0
ATOMIC_REPLACE_ATTEMPTS = 5
ATOMIC_REPLACE_DELAYS = (0.05, 0.1, 0.2, 0.4)
RETRYABLE_WINDOWS_REPLACE_ERRORS = {5, 32, 33}


def safe_endpoint_host(base_url: str) -> str:
    parsed = urllib.parse.urlparse(base_url)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("Evaluation endpoint must be an HTTP(S) origin without embedded credentials or query data")
    return f"{parsed.hostname}:{parsed.port}" if parsed.port else parsed.hostname


def load_dataset(path: Path = DATASET_PATH) -> list[dict[str, Any]]:
    cases = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(cases, list) or not 1 <= len(cases) <= 250:
        raise ValueError("Dataset must contain between 1 and 250 cases")
    ids = [case.get("id") for case in cases]
    if len(set(ids)) != len(ids) or any(not case.get("transcript") for case in cases):
        raise ValueError("Every case needs a unique id and non-empty transcript")
    return cases


def _request_locale(case: dict[str, Any]) -> str:
    if case.get("locale_hint"):
        return case["locale_hint"]
    case_tags_value = set(case.get("tags", []))
    case_id = str(case["id"]).lower()
    if case_id.startswith("hi_") or "hindi" in case_tags_value or "hinglish" in case_tags_value:
        return "hi-IN"
    return "en-IN"


def case_tags(case: dict[str, Any]) -> list[str]:
    tags = set(case.get("tags", []))
    case_id = str(case.get("id", "")).casefold()
    transcript = str(case.get("transcript", "")).casefold()
    if case_id.startswith("hinglish_") or "hinglish" in tags:
        tags.add("hinglish")
    elif case_id.startswith("hi_") or "hindi" in tags:
        tags.add("hindi")
    else:
        tags.add("english_or_mixed")
    operations = case.get("expected_operations", [])
    count = len(operations)
    tags.add("no_operation_expected" if count == 0 else "single_operation" if count == 1 else "multiple_operations_2_to_4" if count <= 4 else "long_multi_operation_5_plus")
    types = {op.get("type") for op in operations}
    tags.update(f"operation_{item.lower()}" for item in types if item)
    if len(types) > 1:
        tags.add("mixed_operation_types")
    if any(op.get("date_phrase") or op.get("changes", {}).get("date_phrase") for op in operations):
        tags.add("date_expression")
    if any(op.get("time_phrase") or op.get("changes", {}).get("time_phrase") for op in operations):
        tags.add("time_expression")
    if any(op.get("priority") or op.get("changes", {}).get("priority") for op in operations):
        tags.add("explicit_priority")
    if any(op.get("description") or op.get("changes", {}).get("description") for op in operations):
        tags.add("description_details")
    if case.get("expected_clarification"):
        tags.add("clarification_expected")
    if case.get("expected_unsupported"):
        tags.add("unsupported_expected")
    if len(str(case.get("transcript", ""))) >= 300:
        tags.add("long_transcript")
    for word, tag in (
        ("ambiguous", "ambiguous_target"), ("injection", "prompt_injection"),
        ("correction", "self_correction"), ("cancel", "cancellation"),
        ("unrelated", "unrelated_chatter"), ("parent", "parent_subtask_limit"),
        ("uncertain", "uncertain_intent"), ("duplicate", "repeat_or_duplicate"),
    ):
        if word in case_id:
            tags.add(tag)
    return sorted(tags)


def build_request(case: dict[str, Any], request_id: str, reference_time: str, time_zone: str) -> dict[str, Any]:
    return {
        "schema_version": "2",
        "request_id": request_id,
        "transcript": case["transcript"],
        "locale_hint": _request_locale(case),
        "reference_time": reference_time,
        "time_zone": time_zone,
    }


def post_interpretation(base_url: str, token: str, body: dict[str, Any], timeout: float = 60.0) -> tuple[int, str]:
    request = urllib.request.Request(
        f"{base_url.rstrip('/')}/v1/interpretations",
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={"Authorization": f"Bearer {token}", "Accept": "application/json", "Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, response.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8", errors="replace")


def check_production_endpoint(base_url: str, timeout: float = 5.0) -> dict[str, Any]:
    """Verify the real backend is healthy and Groq-configured before spending requests."""
    request = urllib.request.Request(f"{base_url.rstrip('/')}/health", method="GET")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        body = json.loads(response.read().decode("utf-8", errors="replace"))
    if response.status != 200 or body.get("status") != "ok" or body.get("interpreter") != "groq":
        raise ValueError("Health endpoint did not report status=ok and interpreter=groq")
    return {"http_status": response.status, "status": body["status"], "interpreter": body["interpreter"]}


def dataset_fingerprint(cases: list[dict[str, Any]]) -> str:
    canonical = json.dumps(cases, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _response_shape_error(actual: Any, expected_request_id: str) -> str | None:
    if not isinstance(actual, dict):
        return "INVALID_JSON_RESPONSE"
    if actual.get("schema_version") not in {"1", "2"} or actual.get("request_id") != expected_request_id:
        return "INVALID_RESPONSE_ID_OR_VERSION"
    if actual.get("status") not in {"proposals", "needs_clarification", "unsupported", "partial"}:
        return "INVALID_RESPONSE_STATUS"
    for key in ("operations", "clarifications", "unsupported_items"):
        if not isinstance(actual.get(key), list):
            return "INVALID_RESPONSE_SHAPE"
    for operation in actual["operations"]:
        if not isinstance(operation, dict) or operation.get("type") not in {"ADD", "UPDATE", "COMPLETE", "DELETE"}:
            return "INVALID_OPERATION_SHAPE"
        if not isinstance(operation.get("operation_id"), str) or not isinstance(operation.get("source_text"), str) or not operation["source_text"].strip():
            return "INVALID_OPERATION_EVIDENCE"
    for key, required_fields in (("clarifications", ("source_text", "question")), ("unsupported_items", ("source_text", "reason"))):
        for item in actual[key]:
            if not isinstance(item, dict) or any(not isinstance(item.get(field), str) or not item[field].strip() for field in required_fields):
                return "INVALID_RESPONSE_SHAPE"
    return None


def _atomic_replace_with_retry(
    source: Path,
    destination: Path,
    replace_fn: Callable[[Any, Any], None] = os.replace,
    sleep_fn: Callable[[float], None] = time.sleep,
) -> None:
    for attempt in range(ATOMIC_REPLACE_ATTEMPTS):
        try:
            replace_fn(source, destination)
            return
        except OSError as exc:
            winerror = getattr(exc, "winerror", None)
            if winerror not in RETRYABLE_WINDOWS_REPLACE_ERRORS or attempt + 1 >= ATOMIC_REPLACE_ATTEMPTS:
                raise
            sleep_fn(ATOMIC_REPLACE_DELAYS[min(attempt, len(ATOMIC_REPLACE_DELAYS) - 1)])


def write_json_atomic(
    path: Path,
    value: dict[str, Any],
    *,
    replace_fn: Callable[[Any, Any], None] | None = None,
    sleep_fn: Callable[[float], None] = time.sleep,
) -> None:
    """Write a durable same-directory snapshot, replacing atomically or preserving recovery evidence."""
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{uuid4().hex}.tmp")
    with temporary.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.flush()
        os.fsync(stream.fileno())
    _atomic_replace_with_retry(temporary, path, replace_fn or os.replace, sleep_fn)


def _checkpoint_progress(checkpoint: dict[str, Any], modified_time: float = 0.0) -> tuple[int, int, int, float]:
    cases = checkpoint.get("cases", [])
    in_flight = checkpoint.get("in_flight_case_ids", [])
    return (len(cases) + len(in_flight), len(cases), int(checkpoint.get("health_check_count", 0)), modified_time)


def load_checkpoint_with_recovery(
    checkpoint_path: Path,
    *,
    dataset_sha256: str,
    base_url: str,
    expected_case_ids: set[str],
) -> tuple[dict[str, Any], Path, int]:
    """Select the most advanced valid checkpoint/temp snapshot without changing either file."""
    candidates: list[tuple[dict[str, Any], Path, float]] = []
    ignored = 0
    canonical_run_id: str | None = None
    paths = [checkpoint_path, *checkpoint_path.parent.glob(f".{checkpoint_path.name}.*.tmp")]
    for candidate_path in paths:
        try:
            value = json.loads(candidate_path.read_text(encoding="utf-8"))
            if not isinstance(value, dict):
                raise ValueError("snapshot is not an object")
            if value.get("dataset_sha256") != dataset_sha256 or value.get("base_url") != base_url.rstrip("/"):
                raise ValueError("snapshot belongs to a different dataset or endpoint")
            if not isinstance(value.get("run_id"), str) or not isinstance(value.get("cases"), list):
                raise ValueError("snapshot is missing its run identity or cases")
            if canonical_run_id is None:
                canonical_run_id = value["run_id"]
            elif value["run_id"] != canonical_run_id:
                raise ValueError("snapshot belongs to a different run")
            result_ids = [item.get("id") for item in value["cases"] if isinstance(item, dict)]
            if len(result_ids) != len(value["cases"]) or len(result_ids) != len(set(result_ids)):
                raise ValueError("snapshot contains malformed or duplicate case records")
            if not set(result_ids).issubset(expected_case_ids):
                raise ValueError("snapshot contains case IDs outside the selected dataset")
            in_flight = value.get("in_flight_case_ids", [])
            if not isinstance(in_flight, list) or any(not isinstance(item, str) for item in in_flight):
                raise ValueError("snapshot has an invalid in-flight case list")
            if len(in_flight) != len(set(in_flight)) or set(in_flight) & set(result_ids) or not set(in_flight).issubset(expected_case_ids):
                raise ValueError("snapshot has conflicting in-flight state")
            candidates.append((value, candidate_path, candidate_path.stat().st_mtime))
        except (OSError, ValueError, TypeError, json.JSONDecodeError):
            ignored += 1
    if not candidates:
        raise ValueError("No valid checkpoint or recovery snapshot was found")
    value, selected_path, _ = max(candidates, key=lambda item: _checkpoint_progress(item[0], item[2]))
    return value, selected_path, ignored


def evaluate_cases(
    cases: list[dict[str, Any]],
    base_url: str,
    token: str,
    reference_time: str,
    time_zone: str,
    request_fn: Callable[[str, str, dict[str, Any]], tuple[int, str]] = post_interpretation,
    run_id: str = "evaluation",
    previous_results: list[dict[str, Any]] | None = None,
    in_flight_ids: set[str] | None = None,
    persist: Callable[[list[dict[str, Any]], set[str]], None] | None = None,
    max_requests: int | None = None,
    request_delay_seconds: float = DEFAULT_REQUEST_DELAY_SECONDS,
    sleep_fn: Callable[[float], None] = time.sleep,
    monotonic_fn: Callable[[], float] = time.monotonic,
) -> list[dict[str, Any]]:
    if not math.isfinite(request_delay_seconds) or not 0 <= request_delay_seconds <= MAX_REQUEST_DELAY_SECONDS:
        raise ValueError(f"request_delay_seconds must be between 0 and {MAX_REQUEST_DELAY_SECONDS:g}")
    results_by_id = {item["id"]: item for item in (previous_results or [])}
    in_flight = set(in_flight_ids or set())
    request_count = 0
    last_request_started_at: float | None = None
    for case in cases:
        if case["id"] in results_by_id:
            continue
        request_id = f"eval-{run_id}-{case['id']}"[:100]
        if case["id"] in in_flight:
            # The previous process may have sent the request but died before saving its response.
            # Never resend it automatically: record the uncertain outcome and continue.
            results_by_id[case["id"]] = {
                "id": case["id"], "request_id": request_id, "tags": case_tags(case),
                "original_transcript": case["transcript"], "expected_operations": case.get("expected_operations", []),
                "http_status": None, "actual_response_text": None, "actual_response": None, "metrics": None,
                "latency_ms": None, "usage": None,
                "api_error": {"kind": "REQUEST_OUTCOME_UNKNOWN", "note": "Not resent during resume to avoid a duplicate request."},
            }
            in_flight.remove(case["id"])
            if persist:
                persist(list(results_by_id.values()), in_flight)
            continue
        if max_requests is not None and request_count >= max_requests:
            break
        body = build_request(case, request_id, reference_time, time_zone)
        if last_request_started_at is not None and request_delay_seconds > 0:
            elapsed = monotonic_fn() - last_request_started_at
            remaining_delay = request_delay_seconds - elapsed
            if remaining_delay > 0:
                sleep_fn(remaining_delay)
        in_flight.add(case["id"])
        if persist:
            # Persist before network I/O; a crash during the call will not cause a duplicate on resume.
            persist(list(results_by_id.values()), in_flight)
        request_count += 1
        last_request_started_at = monotonic_fn()
        started = time.perf_counter()
        try:
            status, raw_text = request_fn(base_url, token, body)
            latency = round((time.perf_counter() - started) * 1000, 2)
            try:
                actual = json.loads(raw_text) if raw_text else None
            except json.JSONDecodeError:
                actual = None
            error = None
            if status not in range(200, 300):
                detail = actual.get("error", {}) if isinstance(actual, dict) else {}
                detail = detail if isinstance(detail, dict) else {}
                error = {"http_status": status, "code": detail.get("code", "UNPARSEABLE_ERROR")}
            else:
                shape_error = _response_shape_error(actual, request_id)
                if shape_error:
                    error = {"http_status": status, "code": shape_error}
            result = {
                "id": case["id"],
                "request_id": request_id,
                "tags": case_tags(case),
                "original_transcript": case["transcript"],
                "expected_operations": case.get("expected_operations", []),
                "expected_clarification": case.get("expected_clarification", False),
                "expected_unsupported": case.get("expected_unsupported", False),
                "expected_partial": case.get("expected_partial", False),
                "http_status": status,
                "actual_response_text": raw_text,
                "actual_response": actual,
                "metrics": score_case(case, actual) if error is None else None,
                "latency_ms": latency,
                # Usage is not exposed by the existing response contract.
                "usage": None,
                "api_error": error,
            }
        except Exception as error:  # Preserve one failure record and continue, without exception text.
            result = {
                "id": case["id"],
                "request_id": request_id,
                "tags": case_tags(case),
                "original_transcript": case["transcript"],
                "expected_operations": case.get("expected_operations", []),
                "expected_clarification": case.get("expected_clarification", False),
                "expected_unsupported": case.get("expected_unsupported", False),
                "expected_partial": case.get("expected_partial", False),
                "http_status": None,
                "actual_response_text": None,
                "actual_response": None,
                "metrics": None,
                "latency_ms": round((time.perf_counter() - started) * 1000, 2),
                "usage": None,
                "api_error": {"kind": type(error).__name__},
            }
        results_by_id[case["id"]] = result
        in_flight.discard(case["id"])
        if persist:
            persist(list(results_by_id.values()), in_flight)
    return [results_by_id[case["id"]] for case in cases if case["id"] in results_by_id]


def _average(records: list[dict[str, Any]], path: str) -> float | None:
    values = []
    for record in records:
        value: Any = record.get("metrics")
        for part in path.split("."):
            value = value.get(part) if isinstance(value, dict) else None
        if isinstance(value, (int, float)):
            values.append(float(value))
    return round(sum(values) / len(values), 4) if values else None


def summarize(results: list[dict[str, Any]]) -> dict[str, Any]:
    tags: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for result in results:
        for tag in result.get("tags", []) or ["untagged"]:
            tags[tag].append(result)
    fields = [
        "operation_count_matches",
        "task_extraction_precision_exact_text_alignment",
        "task_extraction_recall_exact_text_alignment",
        "task_extraction_f1_exact_text_alignment",
        "operation_type_accuracy",
        "title_similarity_average",
        "title_content_token_coverage",
        "description_accuracy",
        "all_date_phrases_preserved",
        "all_time_phrases_preserved",
        "priority_accuracy",
        "evidence_grounding_accuracy",
        "clarification_presence_matches_expectation",
        "unsupported_presence_matches_expectation",
        "response_status_correct",
    ]
    category_summary = {
        tag: {"case_count": len(records), **{field: _average(records, field) for field in fields}}
        for tag, records in sorted(tags.items())
    }
    valid_metrics = [result for result in results if result.get("metrics") is not None]
    return {
        "case_count": len(results),
        "successful_responses": len(valid_metrics),
        "api_error_count": sum(result.get("api_error") is not None for result in results),
        "transcript_request_count": len(results),
        "response_quality_failure_case_count": sum(bool((result.get("metrics") or {}).get("failure_categories")) for result in results),
        "exact_task_count_accuracy": _average(valid_metrics, "operation_count_matches"),
        "task_extraction_precision_exact_text_alignment": _average(valid_metrics, "task_extraction_precision_exact_text_alignment"),
        "task_extraction_recall_exact_text_alignment": _average(valid_metrics, "task_extraction_recall_exact_text_alignment"),
        "task_extraction_f1_exact_text_alignment": _average(valid_metrics, "task_extraction_f1_exact_text_alignment"),
        "operation_type_accuracy": _average(valid_metrics, "operation_type_accuracy"),
        "title_similarity_average": _average(valid_metrics, "title_similarity_average"),
        "title_content_token_coverage": _average(valid_metrics, "title_content_token_coverage"),
        "description_accuracy": _average(valid_metrics, "all_descriptions_preserved"),
        "date_phrase_accuracy": _average(valid_metrics, "all_date_phrases_preserved"),
        "time_phrase_accuracy": _average(valid_metrics, "all_time_phrases_preserved"),
        "priority_accuracy": _average(valid_metrics, "priority_accuracy"),
        "evidence_grounding_accuracy": _average(valid_metrics, "evidence_grounding_accuracy"),
        "clarification_presence_accuracy": _average(valid_metrics, "clarification_presence_matches_expectation"),
        "unsupported_presence_accuracy": _average(valid_metrics, "unsupported_presence_matches_expectation"),
        "response_status_accuracy": _average(valid_metrics, "response_status_correct"),
        "failure_category_counts": _failure_category_counts(results),
        "average_latency_ms": round(sum(r["latency_ms"] for r in results if r.get("latency_ms") is not None) / max(1, sum(r.get("latency_ms") is not None for r in results)), 2),
        "category_results": category_summary,
        "semantic_accuracy": None,
        "semantic_accuracy_note": "Text alignment is a diagnostic heuristic; human review is required for semantic task quality.",
        "token_usage_note": "The existing interpretation response contract does not expose provider token usage.",
        "retry_note": "The evaluator never resends a transcript. The production backend may apply its own configured bounded upstream retry policy inside a single HTTP request.",
    }


def _failure_category_counts(results: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    grouped: dict[str, list[str]] = defaultdict(list)
    for result in results:
        for category in (result.get("metrics") or {}).get("failure_categories", []):
            grouped[category].append(result["id"])
    return {
        category: {
            "count": len(ids),
            "case_ids": ids,
            "suggested_improvement": (IMPROVEMENT_HINTS.get(category)),
        }
        for category, ids in sorted(grouped.items())
    }


def render_markdown(report: dict[str, Any]) -> str:
    summary = report["summary"]
    rows = [
        "# TaskSync Production Interpretation Evaluation", "",
        f"- Run: `{report['run_id']}`", f"- Endpoint host: `{report['endpoint_host']}`",
        f"- Dataset cases: {summary['case_count']}", f"- Successful responses: {summary['successful_responses']}",
        f"- API errors: {summary['api_error_count']}", "- Semantic correctness is not automatically scored.",
        "", "## Overall metrics", "",
        "| Metric | Value |", "|---|---:|",
    ]
    for key in ("exact_task_count_accuracy", "task_extraction_precision_exact_text_alignment", "task_extraction_recall_exact_text_alignment", "task_extraction_f1_exact_text_alignment", "operation_type_accuracy", "title_similarity_average", "date_phrase_accuracy", "time_phrase_accuracy", "priority_accuracy", "evidence_grounding_accuracy", "clarification_presence_accuracy", "unsupported_presence_accuracy", "average_latency_ms"):
        value = summary.get(key)
        if value is None and key in {"task_extraction_precision_exact_text_alignment", "task_extraction_recall_exact_text_alignment", "task_extraction_f1_exact_text_alignment", "operation_type_accuracy", "title_similarity_average", "priority_accuracy", "evidence_grounding_accuracy"}:
            value = _average(report["cases"], key)
        rows.append(f"| {key} | {value if value is not None else '—'} |")
    rows.append(f"| title_content_token_coverage | {summary.get('title_content_token_coverage')} |")
    rows.append(f"| description_accuracy | {summary.get('description_accuracy')} |")
    rows.append(f"| response_status_accuracy | {summary.get('response_status_accuracy')} |")
    rows += ["", "## Category metrics", "", "| Category | Cases | Exact count | Precision | Recall | F1 | Type | Date | Time | Priority | Grounding |", "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|"]
    for tag, data in summary["category_results"].items():
        rows.append(f"| {tag} | {data['case_count']} | {data['operation_count_matches']} | {data['task_extraction_precision_exact_text_alignment']} | {data['task_extraction_recall_exact_text_alignment']} | {data['task_extraction_f1_exact_text_alignment']} | {data['operation_type_accuracy']} | {data['all_date_phrases_preserved']} | {data['all_time_phrases_preserved']} | {data['priority_accuracy']} | {data['evidence_grounding_accuracy']} |")
    rows += ["", "## Recurring failure categories", "", "| Category | Cases | Suggested improvement |", "|---|---:|---|"]
    for category, data in summary.get("failure_category_counts", {}).items():
        rows.append(f"| {category} | {', '.join(data['case_ids'])} | {data.get('suggested_improvement') or 'Human review required'} |")
    rows += ["", "## Case review", ""]
    for case in report["cases"]:
        rows += [
            f"### {case['id']}", "", f"**Tags:** {', '.join(case.get('tags', [])) or 'untagged'}", "",
            f"**Transcript:** {case['original_transcript']}", "",
            f"**Expected:** `{json.dumps(case['expected_operations'], ensure_ascii=False)}`", "",
            f"**Actual:** `{json.dumps(case['actual_response'], ensure_ascii=False) if case['actual_response'] is not None else 'Unavailable'}`", "",
            f"**Metrics:** `{json.dumps(case['metrics'], ensure_ascii=False) if case['metrics'] is not None else 'Unavailable'}`", "",
            f"**HTTP / latency / error:** {case.get('http_status')} / {case.get('latency_ms')} ms / `{json.dumps(case.get('api_error'), ensure_ascii=False)}`", "",
            "**Human semantic score / notes:** ______________________________", "",
        ]
    rows += ["", summary["semantic_accuracy_note"], summary["token_usage_note"], summary["retry_note"], ""]
    return "\n".join(rows)


def main() -> int:
    parser = argparse.ArgumentParser(description="Evaluate TaskSync's existing interpretation endpoint on the shared transcript dataset.")
    parser.add_argument("--base-url", default=os.environ.get("TASKSYNC_EVAL_BASE_URL", DEFAULT_BASE_URL))
    parser.add_argument("--dataset", type=Path, default=DATASET_PATH)
    parser.add_argument("--output-dir", type=Path, default=HERE / "reports")
    parser.add_argument("--max-requests", type=int, default=50, help="Maximum transcript requests this invocation may send.")
    parser.add_argument(
        "--request-delay-seconds", type=float, default=DEFAULT_REQUEST_DELAY_SECONDS,
        help=f"Minimum delay between transcript request starts (0 to {MAX_REQUEST_DELAY_SECONDS:g} seconds; default: {DEFAULT_REQUEST_DELAY_SECONDS:g}).",
    )
    parser.add_argument("--resume-from", type=Path, help="Resume from a checkpoint.json created by an earlier run.")
    parser.add_argument("--confirm-live", action="store_true", help="Required to contact the real backend and incur model usage.")
    args = parser.parse_args()
    if not args.confirm_live:
        print("No requests sent. Add --confirm-live to explicitly run the production evaluation.", file=sys.stderr)
        return 2
    if args.max_requests < 1:
        print("--max-requests must be at least 1.", file=sys.stderr)
        return 2
    if not math.isfinite(args.request_delay_seconds) or not 0 <= args.request_delay_seconds <= MAX_REQUEST_DELAY_SECONDS:
        print(f"--request-delay-seconds must be between 0 and {MAX_REQUEST_DELAY_SECONDS:g}.", file=sys.stderr)
        return 2
    token = os.environ.get("TASKSYNC_BACKEND_TOKEN", "").strip()
    if not token:
        print("TASKSYNC_BACKEND_TOKEN must be configured in the environment; no requests were sent.", file=sys.stderr)
        return 2
    try:
        cases = load_dataset(args.dataset)
        time_zone = os.environ.get("TASKSYNC_EVAL_TIME_ZONE", "Asia/Kolkata")
        zone = ZoneInfo(time_zone)
        fingerprint = dataset_fingerprint(cases)
        safe_endpoint_host(args.base_url)
        health = check_production_endpoint(args.base_url)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"Dataset or timezone configuration error: {type(exc).__name__}", file=sys.stderr)
        return 2
    except Exception as exc:
        print(f"Production health preflight failed: {type(exc).__name__}; no transcript requests were sent.", file=sys.stderr)
        return 2

    if args.resume_from:
        checkpoint_path = args.resume_from.resolve()
        try:
            if checkpoint_path.name != "checkpoint.json" or (checkpoint_path.parent / "report.json").exists():
                raise ValueError("resume path must be an unfinished checkpoint.json, not a finalized report")
            checkpoint, recovered_from, ignored_snapshots = load_checkpoint_with_recovery(
                checkpoint_path,
                dataset_sha256=fingerprint,
                base_url=args.base_url,
                expected_case_ids={case["id"] for case in cases},
            )
            run_id = checkpoint["run_id"]
            now = checkpoint["reference_time"]
            time_zone = checkpoint["time_zone"]
            previous_results = checkpoint.get("cases", [])
            in_flight = set(checkpoint.get("in_flight_case_ids", []))
            health_check_count = int(checkpoint.get("health_check_count", 0)) + 1
        except (OSError, KeyError, TypeError, json.JSONDecodeError, ValueError) as exc:
            print(f"Checkpoint could not be resumed: {type(exc).__name__}; no requests were sent.", file=sys.stderr)
            return 2
        report_dir = checkpoint_path.parent
        if recovered_from != checkpoint_path:
            print(f"Recovered the most advanced valid snapshot from {recovered_from.name}; original checkpoint and snapshot were preserved.")
        if ignored_snapshots:
            print(f"Ignored {ignored_snapshots} invalid or mismatched snapshot file(s); they were preserved.")
    else:
        run_id = datetime.now().astimezone().strftime("%Y%m%d-%H%M%S") + "-" + uuid4().hex[:8]
        now = datetime.now(zone).isoformat()
        previous_results = []
        in_flight = set()
        health_check_count = 1
        report_dir = args.output_dir / f"production-evaluation-{run_id}"
        if report_dir.exists():
            print("Run output directory already exists; refusing to overwrite it.", file=sys.stderr)
            return 2
        report_dir.mkdir(parents=True, exist_ok=False)
        checkpoint_path = report_dir / "checkpoint.json"

    def persist(results: list[dict[str, Any]], pending: set[str]) -> None:
        write_json_atomic(checkpoint_path, {
            "run_id": run_id,
            "dataset_path": str(args.dataset),
            "dataset_sha256": fingerprint,
            "base_url": args.base_url.rstrip("/"),
            "reference_time": now,
            "time_zone": time_zone,
            "health_check_count": health_check_count,
            "cases": results,
            "in_flight_case_ids": sorted(pending),
        })

    results = evaluate_cases(
        cases, args.base_url, token, now, time_zone, run_id=run_id,
        previous_results=previous_results, in_flight_ids=in_flight,
        persist=persist, max_requests=args.max_requests,
        request_delay_seconds=args.request_delay_seconds,
    )
    completed_ids = {item["id"] for item in results}
    report = {
        "report_version": "2",
        "run_id": run_id,
        "endpoint_host": safe_endpoint_host(args.base_url),
        "model": "Not disclosed by current endpoint contract",
        "dataset_path": str(args.dataset),
        "dataset_sha256": fingerprint,
        "health_preflight": health,
        "run_complete": len(completed_ids) == len(cases),
        "requests_per_transcript": 1,
        "evaluator_automatic_retries": 0,
        "request_delay_seconds": args.request_delay_seconds,
        "health_check_count": health_check_count,
        "summary": summarize(results),
        "cases": results,
    }
    if not report["run_complete"]:
        print(f"Checkpoint: {checkpoint_path}")
        print(f"Cases: {len(results)}/{len(cases)}; API errors: {report['summary']['api_error_count']}; complete: false")
        print(f"Resume with --resume-from \"{checkpoint_path}\" --confirm-live")
        return 0
    json_path = report_dir / "report.json"
    md_path = report_dir / "report.md"
    if json_path.exists() or md_path.exists():
        print("Final report already exists; refusing to overwrite it.", file=sys.stderr)
        return 2
    write_json_atomic(json_path, report)
    md_path.write_text(render_markdown(report), encoding="utf-8")
    print(f"JSON report: {json_path}")
    print(f"Markdown report: {md_path}")
    print(f"Cases: {len(results)}/{len(cases)}; API errors: {report['summary']['api_error_count']}; complete: {report['run_complete']}")
    if not report["run_complete"]:
        print(f"Resume with --resume-from \"{checkpoint_path}\" --confirm-live")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
