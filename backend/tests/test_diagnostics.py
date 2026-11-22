from __future__ import annotations

import json
import logging
from pathlib import Path

from tasksync_backend.diagnostics import configure_diagnostics, log_diagnostic


def test_diagnostics_allow_only_safe_fields_and_sanitize_values(tmp_path: Path):
    path = tmp_path / "diagnostics.jsonl"
    logger = logging.getLogger("tasksync_backend.safe_diagnostics")
    old_propagate = logger.propagate
    try:
        configure_diagnostics(path)
        logger.propagate = False
        log_diagnostic(
            "provider_attempt_failed",
            request_id="req-test\nprivate transcript",
            attempt=1,
            upstream_status=429,
            category="RATE_LIMITED",
            rate_limits={
                "remaining_tokens": 42,
                "retry_after_seconds": 1.5,
                "authorization": "Bearer private-token",
                "provider_body": "private provider body",
                "reset_tokens_seconds": float("nan"),
            },
            retrying=True,
        )
        text = path.read_text(encoding="utf-8")
        record = json.loads(text)
        assert "request_id" not in record
        assert record["provider_attempt"] == 1
        assert record["upstream_status"] == 429
        assert record["category"] == "RATE_LIMITED"
        assert record["rate_limits"] == {"remaining_tokens": 42, "retry_after_seconds": 1.5}
        assert record["retrying"] is True
        assert "private-token" not in text
        assert "private provider body" not in text
        assert "authorization" not in text
        assert "provider_body" not in text
        assert "private transcript" not in text
        assert "nan" not in text
    finally:
        logger.propagate = old_propagate
        configure_diagnostics()


def test_diagnostic_rotation_has_bounded_file_count_and_size(tmp_path: Path):
    path = tmp_path / "diagnostics.jsonl"
    logger = logging.getLogger("tasksync_backend.safe_diagnostics")
    old_propagate = logger.propagate
    try:
        configure_diagnostics(path, max_bytes=512, backup_count=2)
        logger.propagate = False
        for attempt in range(1, 31):
            log_diagnostic(
                "provider_attempt_failed", request_id="req-rotation", attempt=1,
                upstream_status=502, category="UPSTREAM_UNAVAILABLE", retrying=False,
            )
        files = list(tmp_path.glob("diagnostics.jsonl*"))
        assert 1 <= len(files) <= 3
        assert sum(file.stat().st_size for file in files) <= 512 * 3
        assert any(json.loads(line)["request_id"] == "req-rotation" for line in path.read_text(encoding="utf-8").splitlines())
    finally:
        logger.propagate = old_propagate
        configure_diagnostics()


def test_missing_or_unknown_diagnostic_values_are_omitted(tmp_path: Path):
    path = tmp_path / "diagnostics.jsonl"
    logger = logging.getLogger("tasksync_backend.safe_diagnostics")
    old_propagate = logger.propagate
    try:
        configure_diagnostics(path)
        logger.propagate = False
        log_diagnostic(
            "provider_attempt_failed", request_id=None, attempt=7,
            upstream_status=700, category="RAW_PROVIDER_ERROR",
            validation_stage="free-form exception text", rate_limits=None,
        )
        record = json.loads(path.read_text(encoding="utf-8"))
        assert set(record) == {"timestamp", "event"}
    finally:
        logger.propagate = old_propagate
        configure_diagnostics()
