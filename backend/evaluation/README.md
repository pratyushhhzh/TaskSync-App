# Standalone Groq Evaluation

This tool evaluates transcript interpretation directly against Groq for human review. It is deliberately separate from the FastAPI interpretation endpoint and Android app. It does not access Room or execute proposals. Each dataset row is sent in exactly one chat completion request; the tool never sends a separate request per extracted operation.

## Prerequisites

- Python 3.10 or newer.
- A Groq API key supplied locally. Do not put it in source, reports, Android files, or Git.
- Groq's official Python SDK (`groq`).

From PowerShell at the repository root:

```powershell
py -3 -m venv backend\.venv
backend\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
python -m pip install -r backend\evaluation\requirements.txt
$env:GROQ_API_KEY = "your-key"
python backend\evaluation\evaluate.py
```

Alternatively, add `GROQ_API_KEY=...` to the locally ignored `backend/.env`; the CLI loads that file without printing its contents. `backend/.env.example` is a template and contains no real key. Reports are written to the ignored `backend/evaluation/reports/` directory.

## Configuration

The default model is `openai/gpt-oss-20b`. To compare another Groq model:

```powershell
python backend\evaluation\evaluate.py --model "model-id"
```

The selected model must support Groq strict Structured Outputs for `--strict` schema mode to work. Groq currently documents strict Structured Outputs support for `openai/gpt-oss-20b`; support for another model should be verified before comparing it with this setting. The SDK call includes one `response_format` JSON Schema request per transcript.

## Dataset and scoring

`dataset.json` is the preserved 50-case legacy evaluation corpus. `case_additions_v2.json` adds 22 independently authored synthetic cases; `build_dataset.py` assembles both into the new, deterministic `dataset_v2.json` without changing the original file. `holdout_v1.json` is a separate 12-case holdout and its IDs do not overlap the development corpus. Every new expected operation includes a verified transcript evidence span. The expanded examples cover mixed domains, corrections, cancellations, uncertain intent, multi-task speech, task hierarchy limitations, Hindi/Hinglish, and injection-like transcript text.

Every record captures the original transcript, expected operations, actual response text and parsed response, operation counts, operation-type matching, task-splitting count, exact date/time phrase preservation, expected/actual clarification presence, input/output/total token usage, latency, and API errors. Semantic correctness is intentionally left unscored automatically. The Markdown report has space for a human score and notes for every case. JSON validity or schema adherence alone is not an accuracy score.

## Safety

- The evaluator calls Groq directly; it does not call `/v1/interpretations`.
- Never paste a production backend credential here. Use a Groq key restricted to evaluation and rotate it if exposed.
- Keys are read from `GROQ_API_KEY` or local `backend/.env`, are not logged, and are excluded from exception text where detectable.
- Transcript text is sent to Groq. Use synthetic/evaluation data without sensitive personal content.

## Existing FastAPI endpoint evaluation

`evaluate_production.py` is the production-endpoint evaluator. It sends each raw transcript once to `POST /v1/interpretations`, uses the backend bearer token, and never reads a Groq key or calls the SDK. It requires explicit `--confirm-live`, performs a `/health` preflight that must report `status=ok` and `interpreter=groq`, applies a per-invocation request cap, and does not retry failed HTTP requests. The production backend may still apply its existing bounded upstream retry policy within that one request. Use synthetic data only. Start the backend separately, verify its health endpoint, then run a development baseline:

```powershell
$env:TASKSYNC_BACKEND_TOKEN = "your-local-backend-token"
$env:TASKSYNC_EVAL_BASE_URL = "http://127.0.0.1:8000"
python backend\evaluation\evaluate_production.py --dataset backend\evaluation\dataset_v2.json --max-requests 100 --confirm-live
```

To generate the expanded development corpus first, only when `dataset_v2.json` does not exist:

```powershell
python backend\evaluation\build_dataset.py
```

Run the separate holdout baseline using `--dataset backend\evaluation\holdout_v1.json --max-requests 12 --confirm-live`. Each run gets a unique report directory under `backend/evaluation/reports/`; completed reports are never overwritten. A checkpoint is written before each network call and after its result. Resume an interrupted run using the printed `checkpoint.json` path and `--resume-from "...\checkpoint.json" --confirm-live`. A case that was in flight at interruption is marked `REQUEST_OUTCOME_UNKNOWN` and is not resent automatically; ask the backend owner to reconcile that request before deciding whether to start a new explicit evaluation.

### Bounded improvement review

The evaluator classifies misses (omissions/merges, extras, duplicates, operation type, temporal fields, priority, grounding, cancellation, and clarification) and provides focused review suggestions. After a human applies and tests a proposed prompt/backend change, run candidate evaluations on both the same development corpus and the unchanged holdout. Then compare all four immutable reports:

```powershell
python backend\evaluation\improvement_loop.py `
  --baseline backend\evaluation\reports\<baseline-run>\report.json `
  --candidate backend\evaluation\reports\<candidate-run>\report.json `
  --holdout-baseline backend\evaluation\reports\<holdout-baseline-run>\report.json `
  --holdout-candidate backend\evaluation\reports\<holdout-candidate-run>\report.json `
  --state-file backend\evaluation\reports\task-extraction-loop-state.json `
  --max-iterations 3 --max-regression 0.02
```

The first comparison records iteration 1 in the state manifest; later calls with the same manifest require the next sequential iteration and cannot exceed `--max-iterations` (1–5). The comparison rejects mismatched datasets/incomplete runs, reports overall and category guardrails, and always requires human review. It does not edit the production prompt or automatically promote a candidate. Keep each prompt/backend version and report together. Do not accept a higher aggregate score if a category or holdout regresses materially.

Reports contain synthetic transcripts and model proposals; keep them private. The endpoint response does not expose token usage. Automated text alignment, field preservation and exact-count figures are transparent diagnostics, not semantic ground truth. Human review remains required, especially for paraphrased titles, task merging/splitting, and ambiguity.
