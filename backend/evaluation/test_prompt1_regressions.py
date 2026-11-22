from __future__ import annotations

try:
    from .build_dataset import DEFAULT_OUTPUT, BASE, build_dataset, read_cases, validate_cases
except ImportError:
    try:
        from backend.evaluation.build_dataset import DEFAULT_OUTPUT, BASE, build_dataset, read_cases, validate_cases
    except ImportError:
        from build_dataset import DEFAULT_OUTPUT, BASE, build_dataset, read_cases, validate_cases


REGRESSION_PATH = BASE.with_name("prompt1_regressions_v1.json")


def test_prompt1_regression_cases_have_grounded_unique_expected_operations():
    cases = read_cases(REGRESSION_PATH)
    validate_cases(cases, require_evidence=True)
    ids = [case["id"] for case in cases]
    assert len(cases) >= 20
    assert len(ids) == len(set(ids))


def test_prompt1_regressions_cover_requested_behavior_categories():
    cases = read_cases(REGRESSION_PATH)
    tags = {tag for case in cases for tag in case.get("tags", [])}
    assert {
        "academics", "shopping", "bills", "appointment", "household", "fitness",
        "work", "parent_subtask", "correction", "cancellation", "ambiguity",
        "date_expression", "time_expression", "hindi", "hinglish", "prompt_injection",
    }.issubset(tags)


def test_prompt1_regression_cases_do_not_modify_or_overlap_existing_datasets():
    regression_ids = {case["id"] for case in read_cases(REGRESSION_PATH)}
    existing_development_ids = {case["id"] for case in build_dataset()}
    holdout_path = BASE.with_name("holdout_v1.json")
    holdout_ids = {case["id"] for case in read_cases(holdout_path)}

    assert regression_ids.isdisjoint(existing_development_ids)
    assert regression_ids.isdisjoint(holdout_ids)
    assert read_cases(DEFAULT_OUTPUT) == build_dataset()
