"""Assemble a deterministic, versioned evaluation corpus from independently authored cases."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


HERE = Path(__file__).resolve().parent
BASE = HERE / "dataset.json"
ADDITIONS = HERE / "case_additions_v2.json"
DEFAULT_OUTPUT = HERE / "dataset_v2.json"


def read_cases(path: Path) -> list[dict[str, Any]]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, list):
        raise ValueError(f"{path.name} must contain a JSON array")
    return value


def validate_cases(cases: list[dict[str, Any]], require_evidence: bool = True) -> None:
    ids: set[str] = set()
    allowed_operations = {"ADD", "UPDATE", "COMPLETE", "DELETE"}
    for case in cases:
        case_id = case.get("id")
        transcript = case.get("transcript")
        operations = case.get("expected_operations")
        if not isinstance(case_id, str) or not case_id or case_id in ids:
            raise ValueError("Every case needs a unique non-empty id")
        ids.add(case_id)
        if not isinstance(transcript, str) or not transcript.strip() or not isinstance(operations, list):
            raise ValueError(f"Case {case_id} requires a transcript and independently authored expected_operations")
        for operation in operations:
            if operation.get("type") not in allowed_operations:
                raise ValueError(f"Case {case_id} has an unsupported expected operation type")
            evidence = operation.get("source_text")
            if require_evidence and (not isinstance(evidence, str) or evidence not in transcript):
                raise ValueError(f"Case {case_id} expected operation evidence must be an exact transcript span")
            if evidence is not None and evidence not in transcript:
                raise ValueError(f"Case {case_id} expected operation evidence must be an exact transcript span")
            if operation["type"] == "ADD" and not operation.get("title"):
                raise ValueError(f"Case {case_id} ADD requires an expected title")
            if operation["type"] in {"UPDATE", "COMPLETE", "DELETE"} and not operation.get("target_text"):
                raise ValueError(f"Case {case_id} {operation['type']} requires an expected target")


def build_dataset() -> list[dict[str, Any]]:
    original = read_cases(BASE)
    additions = read_cases(ADDITIONS)
    validate_cases(original, require_evidence=False)
    validate_cases(additions, require_evidence=True)
    cases = original + additions
    if len({case.get("id") for case in cases}) != len(cases):
        raise ValueError("Evaluation case IDs must remain unique across corpus versions")
    return cases


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    cases = build_dataset()
    if args.output.exists():
        raise SystemExit(f"Refusing to overwrite existing dataset: {args.output}")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(cases, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Created {len(cases)} cases at {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
