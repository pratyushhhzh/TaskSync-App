# TaskSync Interpretation Worker

This Worker ports the current FastAPI interpretation endpoint. It only turns one confirmed transcript into proposals; it has no Room/database connection and cannot execute task operations.

## Local development

Install the locked dependencies with `npm ci`, then create a local-only `cloudflare-worker/.dev.vars` file (already Git-ignored) containing:

```dotenv
GROQ_API_KEY=your-development-key
```

Start `npm run dev`. `GET /health` reports `{"status":"ok","interpreter":"groq"}` when the secret is present, otherwise `interpreter` is `not_configured`. Do not commit `.dev.vars`. In a Cloudflare environment, configure the key with `npx wrangler secret put GROQ_API_KEY`; it is not a `vars` entry and is never included in Android.

## API

- `GET /health`
- `POST /v1/interpretations`

The POST body follows the Python backend contract: `schema_version` `"1"` or `"2"`, `request_id`, exact `transcript`, optional `locale_hint`, offset-bearing ISO `reference_time`, and IANA `time_zone`. Unknown properties are rejected. Transcript size is limited to 5,000 characters and the full JSON body to 16 KiB.

The Worker sends one transcript per Groq chat-completion request using `openai/gpt-oss-20b`, the Python backend's current system prompt, and its strict JSON Schema. It repeats the Python normalization and grounding checks, creates `op-N` operation IDs, validates response status/content consistency, and strips priority/reminder fields from schema v1 responses. It never returns the upstream provider body. The Worker does not resolve dates, inspect task data, or execute proposals.

Errors retain the backend envelope `{ "error": { "code", "message", "retryable", "request_id" } }`. Invalid requests use HTTP 422; Worker abuse limiting uses 429 `RATE_LIMITED`; upstream timeout uses 504; provider rejection or invalid model output uses 502; upstream availability/configuration failures use 503.

## Public-client abuse control and observability

This Worker does not require or embed a shared Bearer token. A static backend token in an APK is not a safe client credential. Any Authorization header sent by an older app is ignored and is not authentication. CORS settings or an app identifier would not authenticate the Android app.

`INTERPRETATION_LIMITER` allows 10 requests per 60 seconds per `CF-Connecting-IP` key. The address is used only as a coarse abuse-control key; it is not treated as user identity. Cloudflare's Workers Rate Limiting binding is approximate and maintained per Cloudflare location, so it is neither a strict global limit nor a spending cap. Shared carrier/NAT addresses may also share a bucket. Before deployment, confirm the configured numeric `namespace_id` is unique in the target account. Use Cloudflare account spending limits and Groq account limits for separate cost controls.

Structured Cloudflare observability events contain only a safe request ID (when it matches the allowlist), provider attempt number, upstream status, fixed category, fixed validation stage, and allowlisted numeric rate-limit metadata. No transcript, task fields, API key, authorization header, provider body, or raw exception text is logged.

## Checks

```powershell
npm run typecheck
npm test -- --run
```

Vitest uses mocked Groq HTTP responses; tests do not call Groq. `npm run deploy` is intentionally not part of local verification.
