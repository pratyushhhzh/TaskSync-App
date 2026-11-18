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

`dataset.json` contains 40 English, Hindi, and Hinglish cases, including 3-, 5-, and 8-task transcripts, mixed operation types, dates/times, ambiguity, missing details, unrelated chatter, and injection-like text.

Every record captures the original transcript, expected operations, actual response text and parsed response, operation counts, operation-type matching, task-splitting count, exact date/time phrase preservation, expected/actual clarification presence, input/output/total token usage, latency, and API errors. Semantic correctness is intentionally left unscored automatically. The Markdown report has space for a human score and notes for every case. JSON validity or schema adherence alone is not an accuracy score.

## Safety

- The evaluator calls Groq directly; it does not call `/v1/interpretations`.
- Never paste a production backend credential here. Use a Groq key restricted to evaluation and rotate it if exposed.
- Keys are read from `GROQ_API_KEY` or local `backend/.env`, are not logged, and are excluded from exception text where detectable.
- Transcript text is sent to Groq. Use synthetic/evaluation data without sensitive personal content.
