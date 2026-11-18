from __future__ import annotations

from datetime import datetime
from typing import Annotated, Literal, Union
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, model_validator


NonBlank = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=500)]
Transcript = Annotated[str, StringConstraints(min_length=1, max_length=5000)]
OperationId = Annotated[str, StringConstraints(pattern=r"^op-[A-Za-z0-9_-]{1,64}$")]


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class InterpretationRequest(StrictModel):
    schema_version: Literal["1"]
    request_id: Annotated[str, StringConstraints(min_length=1, max_length=100)]
    transcript: Transcript
    locale_hint: Annotated[str, StringConstraints(min_length=2, max_length=35)] | None = None
    reference_time: Annotated[str, StringConstraints(min_length=20, max_length=40)]
    time_zone: Annotated[str, StringConstraints(min_length=1, max_length=100)]

    @model_validator(mode="after")
    def validate_context(self) -> "InterpretationRequest":
        if not self.transcript.strip():
            raise ValueError("transcript must contain non-whitespace text")
        try:
            parsed = datetime.fromisoformat(self.reference_time.replace("Z", "+00:00"))
        except ValueError as exc:
            raise ValueError("reference_time must be an ISO 8601 timestamp") from exc
        if parsed.tzinfo is None or parsed.utcoffset() is None:
            raise ValueError("reference_time must include a UTC offset")
        try:
            ZoneInfo(self.time_zone)
        except (ZoneInfoNotFoundError, ValueError) as exc:
            raise ValueError("time_zone must be a valid IANA time-zone name") from exc
        return self


class OperationBase(StrictModel):
    operation_id: OperationId
    source_text: Annotated[str, StringConstraints(min_length=1, max_length=1000)]
    confidence: float | None = Field(default=None, ge=0.0, le=1.0)

    @model_validator(mode="after")
    def validate_source(self) -> "OperationBase":
        if not self.source_text.strip():
            raise ValueError("source_text must contain non-whitespace text")
        return self


class AddOperation(OperationBase):
    type: Literal["ADD"]
    title: NonBlank
    description: Annotated[str, StringConstraints(max_length=2000)] | None = None
    date_phrase: Annotated[str, StringConstraints(max_length=200)] | None = None
    time_phrase: Annotated[str, StringConstraints(max_length=100)] | None = None


class UpdateChanges(StrictModel):
    # An explicitly supplied null means clear that field; an omitted field is unchanged.
    title: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=500)] | None = None
    description: Annotated[str, StringConstraints(max_length=2000)] | None = None
    date_phrase: Annotated[str, StringConstraints(max_length=200)] | None = None
    time_phrase: Annotated[str, StringConstraints(max_length=100)] | None = None

    @model_validator(mode="after")
    def require_change(self) -> "UpdateChanges":
        if not self.model_fields_set:
            raise ValueError("UPDATE must include at least one explicitly requested change")
        return self


class UpdateOperation(OperationBase):
    type: Literal["UPDATE"]
    target_text: NonBlank
    changes: UpdateChanges


class CompleteOperation(OperationBase):
    type: Literal["COMPLETE"]
    target_text: NonBlank


class DeleteOperation(OperationBase):
    type: Literal["DELETE"]
    target_text: NonBlank


Operation = Annotated[
    Union[AddOperation, UpdateOperation, CompleteOperation, DeleteOperation],
    Field(discriminator="type"),
]


class Clarification(StrictModel):
    source_text: Annotated[str, StringConstraints(min_length=1, max_length=1000)]
    question: Annotated[str, StringConstraints(min_length=1, max_length=500)]


class UnsupportedItem(StrictModel):
    source_text: Annotated[str, StringConstraints(min_length=1, max_length=1000)]
    reason: Annotated[str, StringConstraints(min_length=1, max_length=500)]


class InterpretationResponse(StrictModel):
    schema_version: Literal["1"]
    request_id: Annotated[str, StringConstraints(min_length=1, max_length=100)]
    status: Literal["proposals", "needs_clarification", "unsupported", "partial"]
    operations: list[Operation] = Field(default_factory=list, max_length=20)
    clarifications: list[Clarification] = Field(default_factory=list, max_length=20)
    unsupported_items: list[UnsupportedItem] = Field(default_factory=list, max_length=20)

    @model_validator(mode="after")
    def validate_status_contents(self) -> "InterpretationResponse":
        has_ops = bool(self.operations)
        has_questions = bool(self.clarifications)
        has_unsupported = bool(self.unsupported_items)
        valid = {
            "proposals": has_ops and not has_questions and not has_unsupported,
            "needs_clarification": has_questions and not has_ops and not has_unsupported,
            "unsupported": has_unsupported and not has_ops and not has_questions,
            "partial": has_ops and (has_questions or has_unsupported),
        }[self.status]
        if not valid:
            raise ValueError("status does not match the supplied operations, clarifications, and unsupported items")
        return self


class ErrorDetail(StrictModel):
    code: Literal[
        "UNAUTHENTICATED", "INVALID_REQUEST", "RATE_LIMITED", "UPSTREAM_UNAVAILABLE",
        "UPSTREAM_TIMEOUT", "INVALID_MODEL_RESPONSE", "INTERPRETER_NOT_CONFIGURED",
    ]
    message: Annotated[str, StringConstraints(min_length=1, max_length=500)]
    retryable: bool
    request_id: Annotated[str, StringConstraints(min_length=1, max_length=100)] | None = None


class ErrorResponse(StrictModel):
    error: ErrorDetail
