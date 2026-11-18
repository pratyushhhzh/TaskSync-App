import json
from types import SimpleNamespace

import pytest
from pydantic import ValidationError

from tasksync_backend.main import health, interpret
from tasksync_backend.schemas import InterpretationRequest


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
            "time_phrase": None,
            "changes": {"title": None, "description": None, "date_phrase": None, "time_phrase": None},
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
    assert len(calls) == 2
    assert calls[0]["constructor"]["api_key"] == "test-groq-key"
    assert calls[1]["request"]["model"] == "openai/gpt-oss-20b"
    assert calls[1]["request"]["messages"][1]["content"] == transcript
    assert "test-groq-key" not in response.body.decode()


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
