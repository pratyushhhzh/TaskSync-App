from __future__ import annotations

import os
import secrets

from fastapi import FastAPI, Header, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from .groq_service import (
    GroqInterpreter,
    GroqRateLimited,
    GroqTimeout,
    GroqUnavailable,
    InterpreterNotConfigured,
    ModelResponseInvalid,
)
from .schemas import ErrorDetail, ErrorResponse, InterpretationRequest


app = FastAPI(
    title="TaskSync Interpretation API",
    version="0.1.0",
    description="TaskSync task-interpretation proposal API. Proposals are never executed here.",
)


@app.exception_handler(RequestValidationError)
async def invalid_request_handler(_: Request, exc: RequestValidationError) -> JSONResponse:
    return JSONResponse(
        status_code=422,
        content=ErrorResponse(error=ErrorDetail(
            code="INVALID_REQUEST",
            message="The request does not match the interpretation contract.",
            retryable=False,
        )).model_dump(),
    )


@app.get("/health")
def health() -> dict[str, str]:
    configured = bool(os.environ.get("GROQ_API_KEY", "").strip()) and bool(os.environ.get("TASKSYNC_BACKEND_TOKEN", "").strip())
    return {"status": "ok", "interpreter": "groq" if configured else "not_configured"}


@app.post(
    "/v1/interpretations",
    status_code=200,
    responses={
        401: {"model": ErrorResponse}, 429: {"model": ErrorResponse},
        502: {"model": ErrorResponse}, 503: {"model": ErrorResponse},
        504: {"model": ErrorResponse},
    },
)
def interpret(
    request: InterpretationRequest,
    authorization: str | None = Header(default=None),
) -> JSONResponse:
    """Interpret a transcript into proposals; never accesses or executes task data."""
    token = os.environ.get("TASKSYNC_BACKEND_TOKEN", "")
    if not token:
        return _error(503, "INTERPRETER_NOT_CONFIGURED", "Backend authentication is not configured.", False, request.request_id)
    expected = f"Bearer {token}"
    if authorization is None or not secrets.compare_digest(authorization, expected):
        return _error(401, "UNAUTHENTICATED", "Authentication is required.", False, request.request_id)
    if not os.environ.get("GROQ_API_KEY", "").strip():
        return _error(503, "INTERPRETER_NOT_CONFIGURED", "The interpretation provider is not configured.", False, request.request_id)
    try:
        response = GroqInterpreter().interpret(request)
    except InterpreterNotConfigured:
        return _error(503, "INTERPRETER_NOT_CONFIGURED", "The interpretation provider is not configured.", False, request.request_id)
    except GroqTimeout:
        return _error(504, "UPSTREAM_TIMEOUT", "The interpretation service timed out. Please try again.", True, request.request_id)
    except GroqRateLimited:
        return _error(429, "RATE_LIMITED", "The interpretation service is busy. Please try again shortly.", True, request.request_id)
    except GroqUnavailable:
        return _error(503, "UPSTREAM_UNAVAILABLE", "The interpretation service is temporarily unavailable.", True, request.request_id)
    except ModelResponseInvalid:
        return _error(502, "INVALID_MODEL_RESPONSE", "The interpretation service returned an unusable proposal. Please try again.", False, request.request_id)
    return JSONResponse(status_code=200, content=response.model_dump(mode="json"))


def _error(status_code: int, code: str, message: str, retryable: bool, request_id: str) -> JSONResponse:
    body = ErrorResponse(error=ErrorDetail(
        code=code, message=message, retryable=retryable, request_id=request_id,
    ))
    return JSONResponse(status_code=status_code, content=body.model_dump())
