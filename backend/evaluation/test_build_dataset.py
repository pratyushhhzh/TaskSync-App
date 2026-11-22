from __future__ import annotations

try:
    from .build_dataset import BASE, DEFAULT_OUTPUT, build_dataset, read_cases, validate_cases
except ImportError:
    try:
        from backend.evaluation.build_dataset import BASE, DEFAULT_OUTPUT, build_dataset, read_cases, validate_cases
    except ImportError:
        from build_dataset import BASE, DEFAULT_OUTPUT, build_dataset, read_cases, validate_cases


def test_versioned_dataset_keeps_all_original_gold_cases_and_adds_curated_cases():
    original = read_cases(BASE)
    expanded = build_dataset()
    assert len(original) == 50
    assert expanded[: len(original)] == original
    assert len(expanded) > len(original)
    assert len({case["id"] for case in expanded}) == len(expanded)
    assert read_cases(DEFAULT_OUTPUT) == expanded


def test_case_generator_rejects_expected_evidence_not_in_transcript():
    try:
        validate_cases([{
            "id": "bad", "transcript": "Pay rent tomorrow.",
            "expected_operations": [{"type": "ADD", "title": "pay rent", "source_text": "pay rent Friday"}],
        }])
    except ValueError as exc:
        assert "exact transcript span" in str(exc)
    else:
        raise AssertionError("Invalid gold evidence should not pass validation")


def test_holdout_cases_are_disjoint_from_development_case_ids():
    development_ids = {item["id"] for item in build_dataset()}
    holdout_path = BASE.with_name("holdout_v1.json")
    holdout = read_cases(holdout_path)
    validate_cases(holdout)
    holdout_ids = {item["id"] for item in holdout}
    assert holdout_ids
    assert development_ids.isdisjoint(holdout_ids)
