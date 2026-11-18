import json
from types import SimpleNamespace

import pytest

from tasksync_backend.groq_service import (
    GroqInterpreter,
    ModelResponseInvalid,
    groq_response_schema,
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
        "changes": {"title": None, "description": None, "date_phrase": None, "time_phrase": None},
        "change_fields": [],
    }
    result.update(values)
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
