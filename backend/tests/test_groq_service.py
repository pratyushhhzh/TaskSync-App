import json
from types import SimpleNamespace

import httpx
import pytest
from groq import APIStatusError, RateLimitError

from tasksync_backend.groq_service import (
    GroqInterpreter,
    GroqRateLimited,
    GroqRequestRejected,
    GroqUnavailable,
    ModelResponseInvalid,
    groq_response_schema,
    rate_limit_header_diagnostics,
)
from tasksync_backend.schemas import InterpretationRequest


def make_request(transcript):
    return InterpretationRequest.model_validate({
        "schema_version": "1", "request_id": "req-test", "transcript": transcript,
        "locale_hint": "en-IN", "reference_time": "2026-11-17T12:00:00+05:30",
        "time_zone": "Asia/Kolkata",
    })


def operation(type_, source_text, **values):
    result = {
        "type": type_, "source_text": source_text, "title": None, "description": None,
        "target_text": None, "date_phrase": None, "time_phrase": None,
        "priority": None, "reminder_requested": False,
        "changes": {"title": None, "description": None, "date_phrase": None, "time_phrase": None, "priority": None},
        "change_fields": [],
    }
    result.update(values)
    if "changes" in values:
        result["changes"] = {"title": None, "description": None, "date_phrase": None, "time_phrase": None, "priority": None} | values["changes"]
    return result


def output(operations, status="proposals", clarifications=None, unsupported=None):
    return json.dumps({
        "status": status, "operations": operations,
        "clarifications": clarifications or [], "unsupported_items": unsupported or [],
    })


class FakeClient:
    def __init__(self, content):
        self.content = content
        self.calls = []
        self.chat = SimpleNamespace(completions=SimpleNamespace(create=self.create))

    def create(self, **kwargs):
        self.calls.append(kwargs)
        return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content=self.content))])


class SequenceClient:
    def __init__(self, results):
        self.results = list(results)
        self.calls = []
        self.chat = SimpleNamespace(completions=SimpleNamespace(create=self.create))

    def create(self, **kwargs):
        self.calls.append(kwargs)
        result = self.results.pop(0)
        if isinstance(result, Exception):
            raise result
        return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content=result))])


def test_all_four_operations_are_normalized_and_pydantic_validated():
    transcript = "Add call the dentist tomorrow, update the grocery task to buy apples, complete the bank task, delete the duplicate reminder."
    raw_ops = [
        operation("ADD", "call the dentist tomorrow", title="call the dentist", date_phrase="tomorrow"),
        operation("UPDATE", "update the grocery task to buy apples", target_text="the grocery task", changes={"title": "buy apples", "description": None, "date_phrase": None, "time_phrase": None}, change_fields=["title"]),
        operation("COMPLETE", "complete the bank task", target_text="the bank task"),
        operation("DELETE", "delete the duplicate reminder", target_text="the duplicate reminder"),
    ]
    client = FakeClient(output(raw_ops))
    result = GroqInterpreter(client_factory=lambda **_: client, api_key="mock-key").interpret(make_request(transcript))
    assert [item.type for item in result.operations] == ["ADD", "UPDATE", "COMPLETE", "DELETE"]
    assert result.operations[0].date_phrase == "tomorrow"
    assert result.operations[1].changes.title == "buy apples"
    assert [item.operation_id for item in result.operations] == ["op-1", "op-2", "op-3", "op-4"]
    assert len(client.calls) == 1
    assert client.calls[0]["model"] == "openai/gpt-oss-20b"
    assert client.calls[0]["response_format"]["json_schema"]["strict"] is True
    assert client.calls[0]["messages"][1]["content"] == transcript


@pytest.mark.parametrize("task_count", [5, 8])
def test_many_independent_tasks_are_returned_from_one_model_request(task_count):
    transcript = "; ".join(f"task {index}" for index in range(1, task_count + 1))
    raw_ops = [operation("ADD", f"task {index}", title=f"task {index}") for index in range(1, task_count + 1)]
    client = FakeClient(output(raw_ops))
    result = GroqInterpreter(client_factory=lambda **_: client, api_key="mock-key").interpret(make_request(transcript))
    assert len(result.operations) == task_count
    assert len(client.calls) == 1


def test_response_does_not_impose_an_arbitrary_twenty_operation_limit():
    count = 25
    transcript = "; ".join(f"task {index}" for index in range(count))
    raw_ops = [operation("ADD", f"task {index}", title=f"task {index}") for index in range(count)]
    result = GroqInterpreter(client_factory=lambda **_: FakeClient(output(raw_ops)), api_key="mock-key").interpret(make_request(transcript))
    assert len(result.operations) == count
    assert result.operations[-1].operation_id == "op-25"


def test_date_and_time_phrases_are_preserved_verbatim():
    transcript = "Call Mira tomorrow, meet Arun next Friday, and pay the bill kal at 5 pm."
    raw_ops = [
        operation("ADD", "Call Mira tomorrow", title="Call Mira", date_phrase="tomorrow"),
        operation("ADD", "meet Arun next Friday", title="meet Arun", date_phrase="next Friday"),
        operation("ADD", "pay the bill kal at 5 pm", title="pay the bill", date_phrase="kal", time_phrase="5 pm"),
    ]
    client = FakeClient(output(raw_ops))
    result = GroqInterpreter(client_factory=lambda **_: client, api_key="mock-key").interpret(make_request(transcript))
    assert [item.date_phrase for item in result.operations] == ["tomorrow", "next Friday", "kal"]
    assert result.operations[-1].time_phrase == "5 pm"


def test_priority_is_explicit_and_reminder_intent_is_preserved():
    transcript = "Add a high priority task to prepare for my programming lab. Remind me to call my friend tomorrow at 10 AM."
    raw_ops = [
        operation("ADD", "Add a high priority task to prepare for my programming lab", title="prepare for my programming lab", priority="HIGH"),
        operation("ADD", "Remind me to call my friend tomorrow at 10 AM", title="call my friend", date_phrase="tomorrow", time_phrase="10 AM", reminder_requested=True),
    ]
    result = GroqInterpreter(client_factory=lambda **_: FakeClient(output(raw_ops)), api_key="mock-key").interpret(make_request(transcript))
    assert result.operations[0].priority == "HIGH"
    assert result.operations[0].reminder_requested is False
    assert result.operations[1].reminder_requested is True


@pytest.mark.parametrize(("evidence", "priority"), [
    ("Do this first: submit the tax return", "HIGH"),
    ("This can wait: organize the photos", "LOW"),
    ("Not very important: sort the old mail", "LOW"),
])
def test_conversational_priority_phrases_are_grounded(evidence, priority):
    raw = operation("ADD", evidence, title=evidence.split(": ", 1)[1], priority=priority)
    result = GroqInterpreter(client_factory=lambda **_: FakeClient(output([raw])), api_key="mock-key").interpret(make_request(evidence))
    assert result.operations[0].priority == priority


@pytest.mark.parametrize("evidence", [
    "This is important: submit the tax return",
    "It's important to renew my passport",
])
def test_explicit_importance_can_support_high_priority(evidence):
    title = "submit the tax return" if "submit" in evidence else "renew my passport"
    raw = operation("ADD", evidence, title=title, priority="HIGH")
    result = GroqInterpreter(client_factory=lambda **_: FakeClient(output([raw])), api_key="mock-key").interpret(make_request(evidence))
    assert result.operations[0].priority == "HIGH"


@pytest.mark.parametrize(("transcript", "title"), [
    ("Don't forget to pay the water bill tonight.", "pay the water bill"),
    ("Electricity bill aaj raat pay karna mat bhoolna.", "Electricity bill aaj raat pay karna"),
    ("दवा लेने जाना याद रखना।", "दवा लेने जाना"),
])
def test_natural_reminder_phrasings_are_recognized_without_extra_operation(transcript, title):
    raw = operation("ADD", transcript, title=title)
    result = GroqInterpreter(client_factory=lambda **_: FakeClient(output([raw])), api_key="mock-key").interpret(make_request(transcript))

    assert len(result.operations) == 1
    assert result.operations[0].reminder_requested is True


def test_rejects_priority_not_explicit_in_operation_evidence():
    transcript = "Add a task to prepare for my programming lab."
    raw = operation("ADD", transcript, title="prepare for my programming lab", priority="HIGH")
    with pytest.raises(ModelResponseInvalid):
        GroqInterpreter(client_factory=lambda **_: FakeClient(output([raw])), api_key="mock-key").interpret(make_request(transcript))


def test_shared_schedule_is_preserved_on_each_model_proposal():
    transcript = "On Monday, attend the DSA lecture at 9 AM and submit my lab record by 5 AM."
    raw_ops = [
        operation("ADD", "attend the DSA lecture at 9 AM", title="attend the DSA lecture", date_phrase="Monday", time_phrase="9 AM"),
        operation("ADD", "submit my lab record by 5 AM", title="submit my lab record", date_phrase="Monday", time_phrase="5 AM"),
    ]
    result = GroqInterpreter(client_factory=lambda **_: FakeClient(output(raw_ops)), api_key="mock-key").interpret(make_request(transcript))
    assert [(op.date_phrase, op.time_phrase) for op in result.operations] == [("Monday", "9 AM"), ("Monday", "5 AM")]


def test_schedule_from_one_clause_is_not_inherited_by_another_proposal():
    transcript = "Add call the dentist tomorrow and pay the water bill Friday."
    raw_ops = [
        operation("ADD", "call the dentist tomorrow", title="call the dentist", date_phrase="tomorrow"),
        # If the model omits the second schedule, normalization must leave it unset rather than copy tomorrow.
        operation("ADD", "pay the water bill Friday", title="pay the water bill"),
    ]
    result = GroqInterpreter(client_factory=lambda **_: FakeClient(output(raw_ops)), api_key="mock-key").interpret(make_request(transcript))
    assert result.operations[0].date_phrase == "tomorrow"
    assert result.operations[1].date_phrase is None


def test_extraction_policy_covers_corrections_uncertainty_and_hierarchy_limit():
    from tasksync_backend.groq_service import SYSTEM_PROMPT

    assert "chronologically" in SYSTEM_PROMPT
    assert "cancellation retract" in SYSTEM_PROMPT
    assert "parent/subtask links" in SYSTEM_PROMPT
    assert "uncertain plans" in SYSTEM_PROMPT
    assert "silently audit" in SYSTEM_PROMPT
    assert "final intended state" in SYSTEM_PROMPT
    assert "Cancelling an uncreated intention" in SYSTEM_PROMPT
    assert "backend cannot see Android" in SYSTEM_PROMPT
    assert "local matching/review" in SYSTEM_PROMPT
    assert "code-switching alone is not uncertainty" in SYSTEM_PROMPT


def test_shared_date_does_not_cross_independent_sentences():
    transcript = "Tomorrow, finish the DBMS assignment. Friday, submit my lab record."
    raw_ops = [
        operation("ADD", "finish the DBMS assignment", title="finish the DBMS assignment", date_phrase="tomorrow"),
        operation("ADD", "submit my lab record", title="submit my lab record"),
    ]
    result = GroqInterpreter(client_factory=lambda **_: FakeClient(output(raw_ops)), api_key="mock-key").interpret(make_request(transcript))
    assert [op.date_phrase for op in result.operations] == ["tomorrow", None]


def test_reminder_intent_applies_to_the_coordinated_list_only():
    transcript = "Remind me tomorrow to call the dentist and buy groceries. Add a separate task to water the plants."
    raw_ops = [
        operation("ADD", "Remind me tomorrow to call the dentist", title="call the dentist", date_phrase="tomorrow", reminder_requested=True),
        operation("ADD", "buy groceries", title="buy groceries"),
        operation("ADD", "water the plants", title="water the plants"),
    ]
    result = GroqInterpreter(client_factory=lambda **_: FakeClient(output(raw_ops)), api_key="mock-key").interpret(make_request(transcript))
    assert [op.reminder_requested for op in result.operations] == [True, True, False]


def test_negated_reminder_intent_is_not_treated_as_a_request():
    transcript = "Don't remind me to call the dentist tomorrow."
    raw = operation("ADD", transcript, title="call the dentist", date_phrase="tomorrow")
    result = GroqInterpreter(client_factory=lambda **_: FakeClient(output([raw])), api_key="mock-key").interpret(make_request(transcript))
    assert result.operations[0].reminder_requested is False


def test_ambiguous_target_returns_clarification_without_guessing():
    transcript = "Move it to Friday."
    client = FakeClient(output([], status="needs_clarification", clarifications=[
        {"source_text": "Move it", "question": "Which task should be moved?"},
    ]))
    result = GroqInterpreter(client_factory=lambda **_: client, api_key="mock-key").interpret(make_request(transcript))
    assert result.operations == []
    assert len(result.clarifications) == 1


def test_rejects_invented_source_evidence_or_operation_values():
    transcript = "Add call the dentist tomorrow."
    client = FakeClient(output([
        operation("ADD", "Add call the dentist tomorrow", title="buy a boat"),
    ]))
    with pytest.raises(ModelResponseInvalid):
        GroqInterpreter(client_factory=lambda **_: client, api_key="mock-key").interpret(make_request(transcript))


def test_rejects_invalid_json_and_inconsistent_response_status():
    for content in ("not json", output([], status="proposals")):
        client = FakeClient(content)
        with pytest.raises(ModelResponseInvalid):
            GroqInterpreter(client_factory=lambda **_: client, api_key="mock-key").interpret(make_request("hello"))


@pytest.mark.parametrize("location", ["top_level", "operation", "changes"])
def test_unknown_model_fields_are_rejected_instead_of_silently_discarded(location):
    transcript = "Add call the dentist tomorrow."
    raw = json.loads(output([operation("ADD", transcript, title="call the dentist", date_phrase="tomorrow")]))
    if location == "top_level":
        raw["invented_task_id"] = "task-123"
    elif location == "operation":
        raw["operations"][0]["invented_task_id"] = "task-123"
    else:
        raw["operations"][0]["changes"]["invented_task_id"] = "task-123"

    with pytest.raises(ModelResponseInvalid):
        GroqInterpreter(client_factory=lambda **_: FakeClient(json.dumps(raw)), api_key="mock-key").interpret(make_request(transcript))


def test_transient_upstream_failure_retries_once_and_recovers():
    transcript = "Add call the dentist"
    valid = output([operation("ADD", transcript, title="call the dentist")])
    client = SequenceClient([GroqUnavailable(), valid])
    delays = []
    result = GroqInterpreter(client_factory=lambda **_: client, api_key="mock-key", sleep=delays.append).interpret(make_request(transcript))
    assert result.operations[0].title == "call the dentist"
    assert len(client.calls) == 2
    assert delays == [0.25]


def test_transient_retry_exhaustion_stops_after_one_retry():
    client = SequenceClient([GroqUnavailable(), GroqUnavailable(), "unused"])
    delays = []
    with pytest.raises(GroqUnavailable):
        GroqInterpreter(client_factory=lambda **_: client, api_key="mock-key", sleep=delays.append).interpret(make_request("private transcript"))
    assert len(client.calls) == 2
    assert delays == [0.25]


def test_malformed_model_output_retries_once_and_recovers():
    transcript = "Add call the dentist"
    valid = output([operation("ADD", transcript, title="call the dentist")])
    client = SequenceClient(["{malformed", valid])
    result = GroqInterpreter(client_factory=lambda **_: client, api_key="mock-key", sleep=lambda _: None).interpret(make_request(transcript))
    assert result.operations[0].title == "call the dentist"
    assert len(client.calls) == 2


def test_retry_exhaustion_returns_last_malformed_response_failure():
    client = SequenceClient(["{bad", "still not json"])
    with pytest.raises(ModelResponseInvalid) as failure:
        GroqInterpreter(client_factory=lambda **_: client, api_key="mock-key", sleep=lambda _: None).interpret(make_request("Add call the dentist"))
    assert failure.value.stage == "json_parse"
    assert len(client.calls) == 2


def test_auth_or_invalid_request_rejection_is_never_retried():
    client = SequenceClient([GroqRequestRejected(401)])
    with pytest.raises(GroqRequestRejected):
        GroqInterpreter(client_factory=lambda **_: client, api_key="mock-key", sleep=lambda _: None).interpret(make_request("private transcript"))
    assert len(client.calls) == 1


def test_rate_limit_retries_only_when_retry_after_is_within_bound():
    short_wait = SequenceClient([GroqRateLimited(1.0), output([operation("ADD", "Add report", title="report")])])
    delays = []
    GroqInterpreter(client_factory=lambda **_: short_wait, api_key="mock-key", sleep=delays.append).interpret(make_request("Add report"))
    assert len(short_wait.calls) == 2
    assert delays == [1.0]

    long_wait = SequenceClient([GroqRateLimited(60.0), "unused"])
    with pytest.raises(GroqRateLimited):
        GroqInterpreter(client_factory=lambda **_: long_wait, api_key="mock-key", sleep=lambda _: None).interpret(make_request("Add report"))
    assert len(long_wait.calls) == 1


@pytest.mark.parametrize("status", [502, 503])
def test_groq_transient_http_status_is_retried_once(status):
    response = httpx.Response(status, request=httpx.Request("POST", "https://api.groq.com/openai/v1/chat/completions"))
    upstream_error = APIStatusError("upstream failure", response=response, body=None)
    valid = output([operation("ADD", "Add report", title="report")])
    client = SequenceClient([upstream_error, valid])
    result = GroqInterpreter(client_factory=lambda **_: client, api_key="mock-key", sleep=lambda _: None).interpret(make_request("Add report"))
    assert result.operations[0].title == "report"
    assert len(client.calls) == 2


def test_groq_auth_http_status_is_not_retried():
    response = httpx.Response(401, request=httpx.Request("POST", "https://api.groq.com/openai/v1/chat/completions"))
    client = SequenceClient([APIStatusError("rejected", response=response, body=None), "unused"])
    with pytest.raises(GroqRequestRejected) as failure:
        GroqInterpreter(client_factory=lambda **_: client, api_key="mock-key", sleep=lambda _: None).interpret(make_request("Add report"))
    assert failure.value.status_code == 401
    assert len(client.calls) == 1


def test_upstream_status_diagnostics_exclude_transcript_and_credentials(caplog):
    request = httpx.Request("POST", "https://api.groq.com/openai/v1/chat/completions")
    errors = [APIStatusError("provider failure", response=httpx.Response(502, request=request), body=None) for _ in range(2)]
    client = SequenceClient(errors)
    with pytest.raises(GroqUnavailable):
        GroqInterpreter(client_factory=lambda **_: client, api_key="secret-api-key", sleep=lambda _: None).interpret(make_request("private transcript"))
    assert '"upstream_status":502' in caplog.text
    assert '"category":"UPSTREAM_UNAVAILABLE"' in caplog.text
    assert "private transcript" not in caplog.text
    assert "secret-api-key" not in caplog.text
    assert len(client.calls) == 2


def test_real_groq_rate_limit_honors_retry_after_header():
    request = httpx.Request("POST", "https://api.groq.com/openai/v1/chat/completions")
    response = httpx.Response(429, headers={"retry-after": "1"}, request=request)
    client = SequenceClient([RateLimitError("limited", response=response, body=None), output([operation("ADD", "Add report", title="report")])])
    delays = []
    result = GroqInterpreter(client_factory=lambda **_: client, api_key="mock-key", sleep=delays.append).interpret(make_request("Add report"))
    assert result.operations[0].title == "report"
    assert len(client.calls) == 2
    assert delays == [1.0]


def test_rate_limit_diagnostics_capture_only_allowlisted_numeric_headers(caplog):
    request = httpx.Request("POST", "https://api.groq.com/openai/v1/chat/completions")
    response = httpx.Response(429, headers={
        "retry-after": "1.5",
        "x-ratelimit-limit-requests": "1000",
        "x-ratelimit-remaining-requests": "12",
        "x-ratelimit-limit-tokens": "250000",
        "x-ratelimit-remaining-tokens": "9876",
        "x-ratelimit-reset-requests": "2m30s",
        "x-ratelimit-reset-tokens": "1.25s",
        "authorization": "Bearer never-log-this",
        "x-unrelated-provider-header": "private",
    }, request=request)
    valid = output([operation("ADD", "Add report", title="report")])
    client = SequenceClient([RateLimitError("provider body must not be logged", response=response, body=None), valid])
    result = GroqInterpreter(client_factory=lambda **_: client, api_key="secret-api-key", sleep=lambda _: None).interpret(make_request("Add report"))
    assert result.operations[0].title == "report"

    assert rate_limit_header_diagnostics(response) == {
        "limit_requests": 1000,
        "remaining_requests": 12,
        "limit_tokens": 250000,
        "remaining_tokens": 9876,
        "reset_requests_seconds": 150.0,
        "reset_tokens_seconds": 1.25,
        "retry_after_seconds": 1.5,
    }
    assert '"request_id":"req-test"' in caplog.text
    assert '"upstream_status":429' in caplog.text
    assert '"category":"RATE_LIMITED"' in caplog.text
    assert "remaining_tokens" in caplog.text and "9876" in caplog.text
    assert "never-log-this" not in caplog.text
    assert "private" not in caplog.text
    assert "private transcript" not in caplog.text
    assert "secret-api-key" not in caplog.text
    assert "provider body must not be logged" not in caplog.text


def test_missing_or_malformed_rate_limit_headers_produce_no_raw_values():
    request = httpx.Request("POST", "https://api.groq.com/openai/v1/chat/completions")
    missing = httpx.Response(429, request=request)
    malformed = httpx.Response(429, headers={
        "retry-after": "wait for a while",
        "x-ratelimit-remaining-requests": "twelve",
        "x-ratelimit-reset-requests": "soon",
        "x-ratelimit-reset-tokens": "1m then secret",
    }, request=request)

    assert rate_limit_header_diagnostics(missing) == {}
    assert rate_limit_header_diagnostics(malformed) == {}

    client = SequenceClient([RateLimitError("private provider detail", response=malformed, body=None), "unused"])
    with pytest.raises(GroqRateLimited) as failure:
        GroqInterpreter(client_factory=lambda **_: client, api_key="mock-key", sleep=lambda _: None).interpret(make_request("Add report"))
    assert failure.value.retry_after_seconds is None
    assert len(client.calls) == 1


def test_invalid_model_output_diagnostic_uses_safe_stage_and_never_logs_content(caplog):
    client = SequenceClient(["not json", "still not json"])
    with pytest.raises(ModelResponseInvalid):
        GroqInterpreter(client_factory=lambda **_: client, api_key="mock-key", sleep=lambda _: None).interpret(make_request("private transcript"))

    assert '"upstream_status":200' in caplog.text
    assert '"category":"INVALID_MODEL_RESPONSE"' in caplog.text
    assert '"validation_stage":"json_parse"' in caplog.text
    assert "private transcript" not in caplog.text
    assert "not json" not in caplog.text


def test_rate_limit_retry_stays_bounded_and_uses_only_short_retry_after():
    request = httpx.Request("POST", "https://api.groq.com/openai/v1/chat/completions")
    retryable = httpx.Response(429, headers={"retry-after": "2", "x-ratelimit-reset-requests": "1h"}, request=request)
    valid = output([operation("ADD", "Add report", title="report")])
    client = SequenceClient([RateLimitError("limited", response=retryable, body=None), valid])
    delays = []
    GroqInterpreter(client_factory=lambda **_: client, api_key="mock-key", sleep=delays.append).interpret(make_request("Add report"))
    assert len(client.calls) == 2
    assert delays == [2.0]

    too_long = httpx.Response(429, headers={"retry-after": "2.01", "x-ratelimit-reset-tokens": "0.5s"}, request=request)
    exhausted = SequenceClient([RateLimitError("limited", response=too_long, body=None), "unused"])
    with pytest.raises(GroqRateLimited):
        GroqInterpreter(client_factory=lambda **_: exhausted, api_key="mock-key", sleep=lambda _: None).interpret(make_request("Add report"))
    assert len(exhausted.calls) == 1


def test_provider_attempt_logs_keep_request_id_and_distinct_attempt_numbers(caplog):
    request = httpx.Request("POST", "https://api.groq.com/openai/v1/chat/completions")
    limited = httpx.Response(429, headers={"retry-after": "0.5", "x-ratelimit-remaining-requests": "3"}, request=request)
    client = SequenceClient([
        RateLimitError("private provider detail", response=limited, body=None),
        output([operation("ADD", "private transcript", title="private transcript")]),
    ])
    GroqInterpreter(client_factory=lambda **_: client, api_key="private-api-key", sleep=lambda _: None).interpret(make_request("private transcript"))

    events = [
        json.loads(record.getMessage())
        for record in caplog.records
        if record.name == "tasksync_backend.safe_diagnostics"
    ]
    assert [item["provider_attempt"] for item in events if item["event"] == "provider_attempt_started"] == [1, 2]
    failure = next(item for item in events if item["event"] == "provider_attempt_failed")
    assert failure["request_id"] == "req-test"
    assert failure["upstream_status"] == 429
    assert failure["rate_limits"] == {"remaining_requests": 3, "retry_after_seconds": 0.5}
    assert failure["retrying"] is True
    serialized = "\n".join(record.getMessage() for record in caplog.records)
    assert "private transcript" not in serialized
    assert "private-api-key" not in serialized
    assert "private provider detail" not in serialized


def test_invalid_response_logs_each_validation_attempt_without_model_content(caplog):
    client = SequenceClient(["not json", "still not json"])
    with pytest.raises(ModelResponseInvalid):
        GroqInterpreter(client_factory=lambda **_: client, api_key="mock-key", sleep=lambda _: None).interpret(make_request("private transcript"))

    events = [
        json.loads(record.getMessage())
        for record in caplog.records
        if record.name == "tasksync_backend.safe_diagnostics"
    ]
    failures = [item for item in events if item["event"] == "provider_attempt_failed"]
    assert [(item["provider_attempt"], item["validation_stage"], item["retrying"]) for item in failures] == [
        (1, "json_parse", True), (2, "json_parse", False),
    ]
    assert all(item["request_id"] == "req-test" and item["upstream_status"] == 200 for item in failures)
    serialized = "\n".join(record.getMessage() for record in caplog.records)
    assert "private transcript" not in serialized
    assert "not json" not in serialized
    assert "still not json" not in serialized


def test_strict_schema_marks_all_properties_required_and_forbids_extras():
    def verify(schema):
        if schema.get("type") == "object":
            assert schema["required"] == list(schema["properties"])
            assert schema["additionalProperties"] is False
            for child in schema["properties"].values():
                verify(child)
        if schema.get("type") == "array":
            verify(schema["items"])

    verify(groq_response_schema())
