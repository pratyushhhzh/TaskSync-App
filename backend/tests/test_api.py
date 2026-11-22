import json
from types import SimpleNamespace

import pytest
from pydantic import ValidationError

from tasksync_backend.main import health, interpret
from tasksync_backend.schemas import InterpretationRequest, InterpretationResponse


def request_body():
    return {
        "schema_version": "1", "request_id": "req-123", "transcript": "add a report",
        "locale_hint": "en-IN", "reference_time": "2026-11-17T12:00:00+05:30",
        "time_zone": "Asia/Kolkata",
    }


def test_health_reports_interpreter_not_configured(monkeypatch):
    monkeypatch.delenv("GROQ_API_KEY", raising=False)
    monkeypatch.delenv("TASKSYNC_BACKEND_TOKEN", raising=False)
    assert health() == {"status": "ok", "interpreter": "not_configured"}


def test_interpretation_returns_explicit_unconfigured_error(monkeypatch):
    monkeypatch.delenv("TASKSYNC_BACKEND_TOKEN", raising=False)
    response = interpret(InterpretationRequest.model_validate(request_body()), authorization=None)
    assert response.status_code == 503
    body = json.loads(response.body)
    assert body["error"]["code"] == "INTERPRETER_NOT_CONFIGURED"
    assert body["error"]["message"] == "Backend authentication is not configured."


def test_auth_is_required_when_backend_token_is_configured(monkeypatch):
    monkeypatch.setenv("TASKSYNC_BACKEND_TOKEN", "test-only-token")
    response = interpret(InterpretationRequest.model_validate(request_body()), authorization=None)
    assert response.status_code == 401
    assert json.loads(response.body)["error"]["code"] == "UNAUTHENTICATED"


def test_invalid_request_is_rejected_by_request_schema():
    with pytest.raises(ValidationError):
        InterpretationRequest.model_validate(request_body() | {"unexpected": True})


def test_missing_groq_key_returns_stable_configuration_error(monkeypatch):
    monkeypatch.setenv("TASKSYNC_BACKEND_TOKEN", "test-token")
    monkeypatch.delenv("GROQ_API_KEY", raising=False)
    response = interpret(InterpretationRequest.model_validate(request_body()), authorization="Bearer test-token")
    assert response.status_code == 503
    body = json.loads(response.body)
    assert body["error"]["code"] == "INTERPRETER_NOT_CONFIGURED"
    assert "key" not in json.dumps(body).lower()


def test_configured_groq_endpoint_calls_sdk_and_returns_proposal(monkeypatch):
    import tasksync_backend.groq_service as service

    monkeypatch.setenv("TASKSYNC_BACKEND_TOKEN", "test-backend-token")
    monkeypatch.setenv("GROQ_API_KEY", "test-groq-key")
    monkeypatch.setenv("TASKSYNC_MODEL", "openai/gpt-oss-20b")
    transcript = "add a report"
    model_payload = {
        "status": "proposals",
        "operations": [{
            "type": "ADD", "source_text": transcript, "title": "a report",
            "description": None, "target_text": None, "date_phrase": None,
            "time_phrase": None, "priority": None, "reminder_requested": False,
            "changes": {"title": None, "description": None, "date_phrase": None, "time_phrase": None, "priority": None},
            "change_fields": [],
        }],
        "clarifications": [], "unsupported_items": [],
    }
    calls = []

    class MockGroq:
        def __init__(self, **kwargs):
            calls.append({"constructor": kwargs})
            self.chat = SimpleNamespace(completions=SimpleNamespace(create=self.create))

        def create(self, **kwargs):
            calls.append({"request": kwargs})
            message = SimpleNamespace(content=json.dumps(model_payload))
            return SimpleNamespace(choices=[SimpleNamespace(message=message)])

    monkeypatch.setattr(service, "Groq", MockGroq)
    response = interpret(
        InterpretationRequest.model_validate(request_body()),
        authorization="Bearer test-backend-token",
    )

    assert response.status_code == 200
    body = json.loads(response.body)
    assert body["status"] == "proposals"
    assert body["operations"][0]["title"] == "a report"
    assert "priority" not in body["operations"][0]
    assert "reminder_requested" not in body["operations"][0]
    assert len(calls) == 2
    assert calls[0]["constructor"]["api_key"] == "test-groq-key"
    assert calls[1]["request"]["model"] == "openai/gpt-oss-20b"
    assert calls[1]["request"]["messages"][1]["content"] == transcript
    assert "test-groq-key" not in response.body.decode()


def test_v2_response_includes_priority_and_reminder_fields_while_v1_shape_stays_compatible(monkeypatch):
    import tasksync_backend.main as main

    monkeypatch.setenv("TASKSYNC_BACKEND_TOKEN", "test-token")
    monkeypatch.setenv("GROQ_API_KEY", "configured")

    class FixedInterpreter:
        def interpret(self, request):
            return InterpretationResponse.model_validate({
                "schema_version": request.schema_version,
                "request_id": request.request_id,
                "status": "proposals",
                "operations": [{
                    "type": "ADD", "operation_id": "op-1", "source_text": request.transcript,
                    "title": "a report", "priority": "HIGH", "reminder_requested": True,
                }],
            })

    monkeypatch.setattr(main, "GroqInterpreter", FixedInterpreter)
    v1_request = InterpretationRequest.model_validate(request_body())
    v1 = main.interpret(v1_request, authorization="Bearer test-token")
    assert "priority" not in json.loads(v1.body)["operations"][0]
    assert "reminder_requested" not in json.loads(v1.body)["operations"][0]

    v2_data = request_body() | {"schema_version": "2", "request_id": "req-v2"}
    v2_request = InterpretationRequest.model_validate(v2_data)
    v2 = main.interpret(v2_request, authorization="Bearer test-token")
    operation = json.loads(v2.body)["operations"][0]
    assert operation["priority"] == "HIGH"
    assert operation["reminder_requested"] is True


@pytest.mark.parametrize(("exception_name", "status", "code"), [
    ("GroqTimeout", 504, "UPSTREAM_TIMEOUT"),
    ("GroqRateLimited", 429, "RATE_LIMITED"),
    ("GroqUnavailable", 503, "UPSTREAM_UNAVAILABLE"),
    ("ModelResponseInvalid", 502, "INVALID_MODEL_RESPONSE"),
])
def test_upstream_errors_use_safe_stable_envelope(monkeypatch, exception_name, status, code):
    import tasksync_backend.main as main

    monkeypatch.setenv("TASKSYNC_BACKEND_TOKEN", "test-token")
    monkeypatch.setenv("GROQ_API_KEY", "test-secret-never-echo")
    exception_type = getattr(main, exception_name)

    class FailingInterpreter:
        def interpret(self, _request):
            raise exception_type

    monkeypatch.setattr(main, "GroqInterpreter", FailingInterpreter)
    response = main.interpret(
        InterpretationRequest.model_validate(request_body()),
        authorization="Bearer test-token",
    )
    body = json.loads(response.body)
    assert response.status_code == status
    assert body["error"]["code"] == code
    assert "test-secret-never-echo" not in response.body.decode()


def test_provider_auth_or_invalid_request_error_is_not_retryable(monkeypatch):
    import tasksync_backend.main as main

    monkeypatch.setenv("TASKSYNC_BACKEND_TOKEN", "test-token")
    monkeypatch.setenv("GROQ_API_KEY", "configured")

    class RejectedInterpreter:
        def interpret(self, _request):
            raise main.GroqRequestRejected(401)

    monkeypatch.setattr(main, "GroqInterpreter", RejectedInterpreter)
    response = main.interpret(
        InterpretationRequest.model_validate(request_body()),
        authorization="Bearer test-token",
    )
    body = json.loads(response.body)
    assert response.status_code == 502
    assert body["error"]["code"] == "UPSTREAM_REJECTED"
    assert body["error"]["retryable"] is False


def test_final_task_error_classification_is_logged_without_request_content(monkeypatch, caplog):
    import tasksync_backend.main as main

    monkeypatch.setenv("TASKSYNC_BACKEND_TOKEN", "private-backend-token")
    monkeypatch.setenv("GROQ_API_KEY", "private-groq-key")

    class FailingInterpreter:
        def interpret(self, _request):
            raise main.GroqRequestRejected(401)

    monkeypatch.setattr(main, "GroqInterpreter", FailingInterpreter)
    data = request_body() | {"request_id": "req-safe-correlation", "transcript": "private task transcript"}
    main.interpret(
        InterpretationRequest.model_validate(data),
        authorization="Bearer private-backend-token",
    )

    events = [
        json.loads(record.getMessage())
        for record in caplog.records
        if record.name == "tasksync_backend.safe_diagnostics"
    ]
    event = next(item for item in events if item["event"] == "task_error")
    assert event["request_id"] == "req-safe-correlation"
    assert event["category"] == "UPSTREAM_REJECTED"
    assert event["task_http_status"] == 502
    assert event["task_retryable"] is False
    serialized = "\n".join(record.getMessage() for record in caplog.records)
    assert "private task transcript" not in serialized
    assert "private-backend-token" not in serialized
    assert "private-groq-key" not in serialized
