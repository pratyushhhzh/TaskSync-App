from __future__ import annotations

from collections import Counter
import json
import os
import sys

try:
    from .evaluate_production import (
        _request_locale, case_tags, evaluate_cases, load_checkpoint_with_recovery, main,
        render_markdown, safe_endpoint_host, summarize, write_json_atomic,
    )
except ImportError:
    try:
        from backend.evaluation.evaluate_production import (
            _request_locale, case_tags, evaluate_cases, load_checkpoint_with_recovery, main,
            render_markdown, safe_endpoint_host, summarize, write_json_atomic,
        )
    except ImportError:  # Support pytest invoked from backend/evaluation/.
        from evaluate_production import (
            _request_locale, case_tags, evaluate_cases, load_checkpoint_with_recovery, main,
            render_markdown, safe_endpoint_host, summarize, write_json_atomic,
        )


def _checkpoint(run_id: str, cases: list[dict], pending: list[str]) -> dict:
    return {
        "run_id": run_id,
        "dataset_sha256": "dataset-hash",
        "base_url": "http://127.0.0.1:8000",
        "reference_time": "2026-11-22T10:00:00+05:30",
        "time_zone": "Asia/Kolkata",
        "health_check_count": 1,
        "cases": cases,
        "in_flight_case_ids": pending,
    }


def _raise_windows_access_denied(source, destination):
    error = PermissionError("simulated Windows replace interference")
    error.winerror = 5
    raise error


def test_atomic_checkpoint_replace_retries_transient_windows_access_denied(tmp_path):
    checkpoint_path = tmp_path / "checkpoint.json"
    write_json_atomic(checkpoint_path, {"version": 1})
    delays = []
    attempts = []

    def fail_twice_then_replace(source, destination):
        attempts.append((source, destination))
        if len(attempts) < 3:
            _raise_windows_access_denied(source, destination)
        os.replace(source, destination)

    write_json_atomic(
        checkpoint_path,
        {"version": 2},
        replace_fn=fail_twice_then_replace,
        sleep_fn=delays.append,
    )

    assert len(attempts) == 3
    assert delays == [0.05, 0.1]
    assert json.loads(checkpoint_path.read_text(encoding="utf-8")) == {"version": 2}
    assert not list(tmp_path.glob(".checkpoint.json.*.tmp"))


def test_denied_checkpoint_replace_preserves_old_file_and_recovers_complete_temp_without_resending(tmp_path):
    checkpoint_path = tmp_path / "checkpoint.json"
    completed_case = {"id": "completed", "metrics": None, "api_error": {"http_status": 429}}
    previous = _checkpoint("run-1", [completed_case], [])
    write_json_atomic(checkpoint_path, previous)

    advanced = _checkpoint("run-1", [completed_case], ["outcome-unknown"])
    try:
        write_json_atomic(
            checkpoint_path,
            advanced,
            replace_fn=_raise_windows_access_denied,
            sleep_fn=lambda _delay: None,
        )
    except PermissionError:
        pass
    else:
        raise AssertionError("Persistent Windows replace denial should be surfaced")

    # The old checkpoint remains intact, and the fully written same-directory temp is retained.
    assert json.loads(checkpoint_path.read_text(encoding="utf-8")) == previous
    temporary_snapshots = list(tmp_path.glob(".checkpoint.json.*.tmp"))
    assert len(temporary_snapshots) == 1
    assert json.loads(temporary_snapshots[0].read_text(encoding="utf-8")) == advanced

    recovered, recovered_from, ignored = load_checkpoint_with_recovery(
        checkpoint_path,
        dataset_sha256="dataset-hash",
        base_url="http://127.0.0.1:8000/",
        expected_case_ids={"completed", "outcome-unknown", "next"},
    )
    assert recovered == advanced
    assert recovered_from == temporary_snapshots[0]
    assert ignored == 0

    calls = []

    def fake_request(_base, _token, body):
        calls.append(body["request_id"])
        return 429, '{"error":{"code":"RATE_LIMITED"}}'

    results = evaluate_cases(
        [
            {"id": "completed", "transcript": "saved", "expected_operations": []},
            {"id": "outcome-unknown", "transcript": "possibly sent", "expected_operations": []},
            {"id": "next", "transcript": "not yet sent", "expected_operations": []},
        ],
        "http://127.0.0.1:8000", "test-token", "2026-11-22T10:00:00+05:30", "Asia/Kolkata",
        request_fn=fake_request,
        run_id="run-1",
        previous_results=recovered["cases"],
        in_flight_ids=set(recovered["in_flight_case_ids"]),
        max_requests=1,
        request_delay_seconds=0,
    )

    assert calls == ["eval-run-1-next"]
    by_id = {item["id"]: item for item in results}
    assert by_id["completed"]["api_error"] == {"http_status": 429}
    assert by_id["outcome-unknown"]["api_error"]["kind"] == "REQUEST_OUTCOME_UNKNOWN"
    assert by_id["next"]["http_status"] == 429
    # Recovery reads and selects snapshots but does not remove or rewrite either source file.
    assert checkpoint_path.exists() and temporary_snapshots[0].exists()


def test_checkpoint_recovery_ignores_corrupt_and_mismatched_temporary_snapshots(tmp_path):
    checkpoint_path = tmp_path / "checkpoint.json"
    valid = _checkpoint("run-2", [], ["pending"])
    write_json_atomic(checkpoint_path, valid)
    corrupt_path = tmp_path / ".checkpoint.json.corrupt.tmp"
    corrupt_path.write_text("{incomplete", encoding="utf-8")
    foreign_path = tmp_path / ".checkpoint.json.foreign.tmp"
    write_json_atomic(
        foreign_path,
        {**_checkpoint("other-run", [{"id": "pending"}], []), "dataset_sha256": "other-dataset"},
    )

    recovered, source, ignored = load_checkpoint_with_recovery(
        checkpoint_path,
        dataset_sha256="dataset-hash",
        base_url="http://127.0.0.1:8000",
        expected_case_ids={"pending"},
    )

    assert recovered == valid
    assert source == checkpoint_path
    assert ignored == 2
    assert corrupt_path.exists() and foreign_path.exists()


def test_production_evaluator_sends_exact_transcript_once_and_records_response():
    calls = []

    def fake_request(base_url, token, body):
        calls.append((base_url, token, body))
        return 200, '{"schema_version":"2","request_id":"' + body["request_id"] + '","status":"unsupported","operations":[],"clarifications":[],"unsupported_items":[{"source_text":"x","reason":"x"}]}'

    cases = [
        {"id": "a", "transcript": "  Keep my exact words.  ", "expected_operations": []},
        {"id": "b", "transcript": "हिंदी transcript", "expected_operations": []},
    ]
    results = evaluate_cases(
        cases, "http://localhost:8000", "secret-test-token", "2026-11-22T10:00:00+05:30", "Asia/Kolkata",
        fake_request, "test", request_delay_seconds=0,
    )

    assert len(calls) == len(cases)
    assert Counter(call[2]["transcript"] for call in calls) == Counter(case["transcript"] for case in cases)
    assert all(call[0] == "http://localhost:8000" and call[1] == "secret-test-token" for call in calls)
    assert all(call[2]["schema_version"] == "2" for call in calls)
    assert [result["actual_response"]["operations"] for result in results] == [[], []]
    assert all(result["api_error"] is None for result in results)
    assert all("secret-test-token" not in str(result) for result in results)


def test_production_evaluator_preserves_http_and_transport_failures_without_retry():
    calls = []

    def fake_request(base_url, token, body):
        calls.append(body["request_id"])
        if body["transcript"] == "rate-limited":
            return 429, '{"error":{"code":"RATE_LIMITED"}}'
        raise TimeoutError("details deliberately omitted")

    cases = [
        {"id": "a", "transcript": "rate-limited", "expected_operations": []},
        {"id": "b", "transcript": "timeout", "expected_operations": []},
    ]
    results = evaluate_cases(
        cases, "http://localhost:8000", "secret", "2026-11-22T10:00:00+05:30", "Asia/Kolkata",
        fake_request, "test", request_delay_seconds=0,
    )

    assert len(calls) == 2
    assert results[0]["api_error"] == {"http_status": 429, "code": "RATE_LIMITED"}
    assert results[1]["api_error"] == {"kind": "TimeoutError"}
    assert all(result["metrics"] is None for result in results)
    assert "details deliberately omitted" not in str(results)
    assert "secret" not in str(results)
    assert summarize(results)["api_error_count"] == 2


def test_success_status_with_invalid_response_shape_is_not_scored_as_accuracy():
    case = {"id": "bad", "transcript": "hello", "expected_operations": []}
    result = evaluate_cases(
        [case], "http://localhost:8000", "secret", "2026-11-22T10:00:00+05:30", "Asia/Kolkata",
        lambda *_: (200, '{"schema_version":"2","request_id":"eval-test-bad","status":"proposals","operations":"bad","clarifications":[],"unsupported_items":[]}'), "test",
        request_delay_seconds=0,
    )[0]
    assert result["api_error"] == {"http_status": 200, "code": "INVALID_RESPONSE_SHAPE"}
    assert result["metrics"] is None


def test_resume_skips_saved_and_uncertain_requests_and_obeys_request_budget():
    calls = []
    cases = [
        {"id": "done", "transcript": "already saved", "expected_operations": []},
        {"id": "uncertain", "transcript": "possibly sent before crash", "expected_operations": []},
        {"id": "next", "transcript": "new work", "expected_operations": []},
    ]
    previous = [{"id": "done", "request_id": "eval-run-done", "metrics": None, "api_error": {"http_status": 429}}]

    def fake_request(_base, _token, body):
        calls.append(body["request_id"])
        return 503, '{"error":{"code":"UPSTREAM_UNAVAILABLE"}}'

    results = evaluate_cases(
        cases, "http://localhost:8000", "secret", "2026-11-22T10:00:00+05:30", "Asia/Kolkata",
        fake_request, "run", previous_results=previous, in_flight_ids={"uncertain"}, max_requests=1,
        request_delay_seconds=0,
    )

    assert calls == ["eval-run-next"]
    by_id = {item["id"]: item for item in results}
    assert by_id["done"]["api_error"]["http_status"] == 429
    assert by_id["uncertain"]["api_error"]["kind"] == "REQUEST_OUTCOME_UNKNOWN"
    assert by_id["next"]["api_error"] == {"http_status": 503, "code": "UPSTREAM_UNAVAILABLE"}


def test_endpoint_host_report_never_includes_embedded_credentials():
    assert safe_endpoint_host("https://worker.example.com:8443") == "worker.example.com:8443"
    try:
        safe_endpoint_host("https://secret@worker.example.com")
    except ValueError:
        pass
    else:
        raise AssertionError("Credential-bearing endpoint URLs must be rejected")


def test_inferred_language_categories_drive_explicit_locale_hint():
    hindi = {"id": "v2_hindi_correction", "tags": ["hindi"], "transcript": "काम"}
    hinglish = {"id": "v2_hinglish_study_and_bill", "tags": ["hinglish"], "transcript": "kal kaam"}
    assert _request_locale(hindi) == "hi-IN"
    assert _request_locale(hinglish) == "hi-IN"
    assert "hindi" in case_tags(hindi) and "english_or_mixed" not in case_tags(hindi)
    assert "hinglish" in case_tags(hinglish) and "english_or_mixed" not in case_tags(hinglish)


def test_live_evaluation_requires_explicit_confirmation(monkeypatch, capsys):
    monkeypatch.setattr(sys, "argv", ["evaluate_production.py"])
    assert main() == 2
    assert "No requests sent" in capsys.readouterr().err


def test_human_review_markdown_includes_failure_classification_and_recommendation():
    case = {"id": "review", "transcript": "Buy apples.", "expected_operations": [{"type": "ADD", "title": "buy apples"}]}
    result = evaluate_cases(
        [case], "http://localhost:8000", "secret", "2026-11-22T10:00:00+05:30", "Asia/Kolkata",
        lambda *_: (200, '{"schema_version":"2","request_id":"eval-test-review","status":"proposals","operations":[],"clarifications":[],"unsupported_items":[]}'), "test",
        request_delay_seconds=0,
    )
    rendered = render_markdown({"run_id": "test", "endpoint_host": "localhost", "summary": summarize(result), "cases": result})
    assert "Recurring failure categories" in rendered
    assert "MISSING_TASK_OR_MERGED_TASKS" in rendered
    assert "Review responsibility segmentation" in rendered


def test_evaluator_paces_only_between_actual_request_starts():
    class FakeClock:
        now = 0.0
        sleeps = []

        def monotonic(self):
            return self.now

        def sleep(self, seconds):
            self.sleeps.append(seconds)
            self.now += seconds

    clock = FakeClock()
    request_starts = []

    def fake_request(_base, _token, body):
        request_starts.append(clock.now)
        clock.now += 0.4
        return 429, '{"error":{"code":"RATE_LIMITED"}}'

    cases = [
        {"id": f"case-{index}", "transcript": f"test {index}", "expected_operations": []}
        for index in range(3)
    ]
    evaluate_cases(
        cases, "http://localhost:8000", "secret", "2026-11-22T10:00:00+05:30", "Asia/Kolkata",
        request_fn=fake_request,
        request_delay_seconds=2.5,
        sleep_fn=clock.sleep,
        monotonic_fn=clock.monotonic,
    )

    assert request_starts == [0.0, 2.5, 5.0]
    assert clock.sleeps == [2.1, 2.1]


def test_evaluator_rejects_invalid_request_delay_without_waiting():
    for invalid_delay in (-0.1, 60.1, float("nan"), float("inf")):
        try:
            evaluate_cases([], "http://localhost:8000", "secret", "now", "UTC", request_delay_seconds=invalid_delay)
        except ValueError as exc:
            assert "request_delay_seconds" in str(exc)
        else:
            raise AssertionError(f"Invalid delay {invalid_delay} should be rejected")
