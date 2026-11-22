"""Bounded, privacy-filtered diagnostics for Groq interpretation requests."""

from __future__ import annotations

from datetime import datetime, timezone
import json
import logging
import math
import re
from logging.handlers import RotatingFileHandler
from pathlib import Path
from typing import Any


DEFAULT_LOG_PATH = Path(__file__).resolve().parents[2] / "logs" / "groq-diagnostics.jsonl"
MAX_LOG_BYTES = 2_000_000
BACKUP_COUNT = 3

_LOGGER_NAME = "tasksync_backend.safe_diagnostics"
_REQUEST_ID = re.compile(
    r"(?:eval-[A-Za-z0-9_.:-]{1,96}|req-[A-Za-z0-9_.:-]{1,96}|request-[A-Za-z0-9_.:-]{1,95}|"
    r"[A-Fa-f0-9]{8}-(?:[A-Fa-f0-9]{4}-){3}[A-Fa-f0-9]{12})"
)
_EVENTS = {"provider_attempt_started", "provider_attempt_succeeded", "provider_attempt_failed", "task_error"}
_CATEGORIES = {
    "RATE_LIMITED", "UPSTREAM_TIMEOUT", "UPSTREAM_UNAVAILABLE", "UPSTREAM_REJECTED",
    "INVALID_MODEL_RESPONSE", "INTERPRETER_NOT_CONFIGURED", "UNAUTHENTICATED",
    "INVALID_REQUEST",
}
_VALIDATION_STAGES = {"completion_shape", "json_parse", "normalization", "pydantic_validation"}
_RATE_LIMIT_FIELDS = {
    "limit_requests", "remaining_requests", "limit_tokens", "remaining_tokens",
    "reset_requests_seconds", "reset_tokens_seconds", "retry_after_seconds",
}


def configure_diagnostics(
    log_path: Path = DEFAULT_LOG_PATH,
    *,
    max_bytes: int = MAX_LOG_BYTES,
    backup_count: int = BACKUP_COUNT,
) -> None:
    """Install one rotating JSONL handler; old rotations are bounded by size and count."""
    if max_bytes < 1 or backup_count < 0:
        raise ValueError("diagnostic log bounds must be positive")
    path = Path(log_path)
    path.parent.mkdir(parents=True, exist_ok=True)
    logger = logging.getLogger(_LOGGER_NAME)
    logger.setLevel(logging.INFO)
    logger.propagate = True
    for handler in tuple(logger.handlers):
        if getattr(handler, "_tasksync_diagnostic_handler", False):
            logger.removeHandler(handler)
            handler.close()
    handler = RotatingFileHandler(path, maxBytes=max_bytes, backupCount=backup_count, encoding="utf-8")
    handler.setFormatter(logging.Formatter("%(message)s"))
    handler._tasksync_diagnostic_handler = True  # type: ignore[attr-defined]
    logger.addHandler(handler)


def log_diagnostic(
    event: str,
    *,
    request_id: str | None = None,
    attempt: int | None = None,
    upstream_status: int | None = None,
    category: str | None = None,
    validation_stage: str | None = None,
    rate_limits: dict[str, Any] | None = None,
    retrying: bool | None = None,
    task_http_status: int | None = None,
    task_retryable: bool | None = None,
) -> None:
    """Serialize only explicitly allowed, bounded diagnostic values."""
    if event not in _EVENTS:
        return
    record: dict[str, Any] = {
        "timestamp": datetime.now(timezone.utc).isoformat(timespec="milliseconds"),
        "event": event,
    }
    if request_id is not None:
        candidate_id = str(request_id)
        if _REQUEST_ID.fullmatch(candidate_id):
            record["request_id"] = candidate_id
    if attempt is not None and isinstance(attempt, int) and 1 <= attempt <= 2:
        record["provider_attempt"] = attempt
    if upstream_status is not None and isinstance(upstream_status, int) and 100 <= upstream_status <= 599:
        record["upstream_status"] = upstream_status
    if category in _CATEGORIES:
        record["category"] = category
    if validation_stage in _VALIDATION_STAGES:
        record["validation_stage"] = validation_stage
    safe_limits = _safe_rate_limits(rate_limits)
    if safe_limits:
        record["rate_limits"] = safe_limits
    if retrying is not None and isinstance(retrying, bool):
        record["retrying"] = retrying
    if task_http_status is not None and isinstance(task_http_status, int) and 100 <= task_http_status <= 599:
        record["task_http_status"] = task_http_status
    if task_retryable is not None and isinstance(task_retryable, bool):
        record["task_retryable"] = task_retryable
    level = logging.WARNING if event in {"provider_attempt_failed", "task_error"} else logging.INFO
    logging.getLogger(_LOGGER_NAME).log(level, json.dumps(record, separators=(",", ":"), sort_keys=True))


def _safe_rate_limits(values: dict[str, Any] | None) -> dict[str, int | float]:
    if not isinstance(values, dict):
        return {}
    safe: dict[str, int | float] = {}
    for key, value in values.items():
        if key not in _RATE_LIMIT_FIELDS or isinstance(value, bool) or not isinstance(value, (int, float)):
            continue
        number = float(value)
        if not math.isfinite(number) or number < 0:
            continue
        safe[key] = int(number) if number.is_integer() else round(number, 3)
    return safe
