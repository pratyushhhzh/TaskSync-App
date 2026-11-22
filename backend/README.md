# TaskSync Stage 2B Backend Foundation

Independent FastAPI/Pydantic interpretation backend. It sends one transcript per request to Groq and returns structured task proposals. It does not access Room or execute task operations.

## Requirements

- Python 3.11 or newer (IANA time-zone data is used for request validation).
- Install dependencies with `python -m pip install -r requirements-dev.txt`.

## Run locally

PowerShell:

```powershell
cd backend
$env:TASKSYNC_BACKEND_TOKEN = "a-long-random-local-token"
$env:GROQ_API_KEY = "your-groq-key"
$env:TASKSYNC_MODEL = "openai/gpt-oss-20b"
python -m uvicorn tasksync_backend.main:app --app-dir src --reload
```

The backend reads `GROQ_API_KEY`, `TASKSYNC_BACKEND_TOKEN`, and optional `TASKSYNC_MODEL` from environment variables only. It does not load `.env` automatically. Use shell environment variables or a deployment secret manager; `.env` remains ignored by Git. The standalone evaluator has its own optional `.env` loading path and does not call the backend endpoint.

`GET /health` reports service availability and whether the Groq provider is configured. `POST /v1/interpretations` validates the request, requires a configured bearer token, sends the transcript once to Groq with Strict Structured Outputs, grounds proposal fields against transcript text, and validates the result with the response models. The endpoint returns proposals only; it never accesses or executes tasks.

## Safe Groq diagnostics

The backend retains correlated JSONL diagnostics at `backend/logs/groq-diagnostics.jsonl`. The log rotates at 2 MB and keeps three backups. Entries include request IDs, provider attempt numbers, fixed error categories, validation stages, upstream status codes, final TaskSync HTTP/retryability classification, and only allowlisted numeric rate-limit values. Transcripts, task content, credentials, provider response bodies, and raw exception messages are excluded. Invalid or unsupported request-ID formats are omitted. This rotating file is intended for the default single-process local Uvicorn setup; use centralized structured logging if deploying multiple worker processes.

## Contract

Requests contain the exact confirmed transcript, optional locale hint, an offset-aware reference timestamp, and an IANA timezone. The request schema rejects unknown fields, whitespace-only transcripts, missing timezone context, and oversized input. Transcript content is preserved as supplied.

Responses use strict Pydantic models for `ADD`, `UPDATE`, `COMPLETE`, and `DELETE` proposals, plus clarification and unsupported-item records. They forbid unknown fields, including task/database IDs. Targets use transcript-derived `target_text`; the contract does not accept internal task IDs. Date and time phrases are returned as phrases for future local Android resolution; the backend schema does not contain a model-computed task date.

Status meanings:

- `proposals`: at least one operation, with no unresolved items.
- `needs_clarification`: clarification required and no operation proposed.
- `unsupported`: unsupported content and no operation proposed.
- `partial`: at least one operation and at least one clarification or unsupported item.

Backend authentication here is a minimal bearer-token foundation, not a complete production identity system. Production deployment must use TLS, user-scoped authentication/authorization, rate limiting, request limits, and secret management. Never put backend or Groq credentials in the Android app. Transcript logs must be redacted. Date and time phrases remain transcript phrases for local Android resolution.

## Tests

From `backend/`:

```powershell
$env:PYTHONPATH = "src"
python -m pytest
```

Tests cover strict request/response validation, all operation types, mixed proposals/clarifications, ambiguous and Hinglish input preservation, injection-like transcript content, status consistency, authentication, and the intentionally unconfigured endpoint.
