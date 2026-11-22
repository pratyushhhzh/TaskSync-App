import { ContractError, isRecord, normalizeAndValidateModelOutput, type InterpretationRequest, type InterpretationResponse } from "./contract";
import { logDiagnostic, safeRateLimitHeaders } from "./diagnostics";
import { SYSTEM_PROMPT } from "./prompt";

export const GROQ_MODEL = "openai/gpt-oss-20b";
export const GROQ_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions";
// Two attempts plus the transient delay must fit Android's 45-second read timeout.
export const REQUEST_TIMEOUT_MS = 20_000;
export const MAX_ATTEMPTS = 2;
const MAX_RATE_LIMIT_WAIT_MS = 2_000;
const RETRY_DELAY_MS = 250;
const MAX_PROVIDER_RESPONSE_BYTES = 1_000_000;
const MAX_PROVIDER_ERROR_BYTES = 16_384;
type ResponseFormatMode = "json_object";

export class ProviderFailure extends Error {
	constructor(
		readonly code: "RATE_LIMITED" | "UPSTREAM_TIMEOUT" | "UPSTREAM_UNAVAILABLE" | "UPSTREAM_REJECTED" | "INVALID_MODEL_RESPONSE",
		readonly retryable: boolean,
		readonly status: number,
		readonly headers?: Headers,
		readonly providerErrorCode?: string,
		readonly providerErrorType?: string,
		readonly providerErrorParameter?: string,
		readonly providerRequestId?: string,
	) {
		super(code);
		this.name = "ProviderFailure";
	}
}

type Fetcher = typeof fetch;

function safeFingerprint(value: string): string {
	let hash = 0x811c9dc5;
	for (let index = 0; index < value.length; index++) {
		hash ^= value.charCodeAt(index);
		hash = Math.imul(hash, 0x01000193) >>> 0;
	}
	return `fnv1a32-${hash.toString(16).padStart(8, "0")}`;
}

function providerErrorCategory(code?: string, type?: string, parameter?: string): "schema_validation" | "authentication" | "quota_or_rate_limit" | "model_configuration" | "other_or_unknown" {
	if (code === "json_validate_failed" || (type === "invalid_request_error" && parameter?.startsWith("response_format"))) return "schema_validation";
	if (["invalid_api_key"].includes(code ?? "") || ["authentication_error", "permission_error"].includes(type ?? "")) return "authentication";
	if (["rate_limit_exceeded", "insufficient_quota"].includes(code ?? "") || type === "rate_limit_error" || type === "insufficient_quota") return "quota_or_rate_limit";
	if (["model_not_found", "unsupported_value"].includes(code ?? "") || type === "not_found_error") return "model_configuration";
	return "other_or_unknown";
}

function retryAfterMs(headers: Headers): number | null {
	const rateLimits = safeRateLimitHeaders(headers);
	// Retry-After is authoritative; token reset is a short-wait fallback for 429s that omit it.
	const seconds = rateLimits.retry_after_seconds ?? rateLimits.reset_tokens_seconds;
	return seconds === undefined ? null : seconds * 1000;
}

function delay(ms: number): Promise<void> {
	return new Promise((resolve) => setTimeout(resolve, ms));
}

function diagnosticStage(stage: string): string {
	if (stage === "response_status") return "pydantic_validation";
	return stage;
}

async function readLimitedText(response: Response, maxBytes = MAX_PROVIDER_RESPONSE_BYTES): Promise<string> {
	if (!response.body) return "";
	const reader = response.body.getReader();
	const chunks: Uint8Array[] = [];
	let length = 0;
	try {
		while (true) {
			const { done, value } = await reader.read();
			if (done) break;
			length += value.byteLength;
			if (length > maxBytes) {
				await reader.cancel();
				throw new ContractError("completion_shape");
			}
			chunks.push(value);
		}
	} finally {
		reader.releaseLock();
	}
	const joined = new Uint8Array(length);
	let offset = 0;
	for (const chunk of chunks) {
		joined.set(chunk, offset);
		offset += chunk.byteLength;
	}
	try {
		return new TextDecoder("utf-8", { fatal: true, ignoreBOM: false }).decode(joined);
	} catch {
		throw new ContractError("completion_shape");
	}
}

async function upstreamAttempt(
	request: InterpretationRequest,
	apiKey: string,
	fetcher: Fetcher,
): Promise<{ response: Response; body: unknown }> {
	const controller = new AbortController();
	const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
	try {
		const response = await fetcher(GROQ_ENDPOINT, {
			method: "POST",
			headers: {
				Authorization: `Bearer ${apiKey}`,
				"Content-Type": "application/json",
			},
			body: JSON.stringify({
				model: GROQ_MODEL,
				messages: [
					{ role: "system", content: SYSTEM_PROMPT },
					{ role: "user", content: request.transcript },
				],
				response_format: { type: "json_object" },
				max_completion_tokens: 4096,
			}),
			signal: controller.signal,
		});
		if (!response.ok) throw await classifyStatus(response);
		const text = await readLimitedText(response);
		let body: unknown;
		try {
			body = JSON.parse(text);
		} catch {
			throw new ContractError("completion_shape");
		}
		return { response, body };
	} catch (error) {
		if (error instanceof ContractError || error instanceof ProviderFailure) throw error;
		const timedOut = controller.signal.aborted || (error instanceof Error && error.name === "AbortError");
		throw new ProviderFailure(timedOut ? "UPSTREAM_TIMEOUT" : "UPSTREAM_UNAVAILABLE", true, timedOut ? 504 : 503);
	} finally {
		clearTimeout(timeout);
	}
}

async function safeProviderErrorMetadata(response: Response): Promise<{ code?: string; type?: string; parameter?: string; requestId?: string }> {
	try {
		const body: unknown = JSON.parse(await readLimitedText(response, MAX_PROVIDER_ERROR_BYTES));
		if (!isRecord(body) || !isRecord(body.error)) return {};
		return {
			code: typeof body.error.code === "string" ? body.error.code.toLowerCase() : undefined,
			type: typeof body.error.type === "string" ? body.error.type.toLowerCase() : undefined,
			parameter: typeof body.error.param === "string" ? body.error.param : undefined,
			requestId: typeof body.error.request_id === "string" ? body.error.request_id : response.headers.get("x-groq-request-id") ?? undefined,
		};
	} catch {
		// Provider bodies are diagnostic input only; malformed/large bodies never escape this boundary.
		return {};
	}
}

async function classifyStatus(response: Response): Promise<ProviderFailure> {
	const providerError = await safeProviderErrorMetadata(response);
	const metadata = [response.headers, providerError.code, providerError.type, providerError.parameter, providerError.requestId] as const;
	if (response.status === 429) return new ProviderFailure("RATE_LIMITED", true, response.status, ...metadata);
	if (response.status === 408 || response.status === 504) return new ProviderFailure("UPSTREAM_TIMEOUT", true, response.status, ...metadata);
	if (response.status >= 500 && response.status <= 599) return new ProviderFailure("UPSTREAM_UNAVAILABLE", true, response.status, ...metadata);
	return new ProviderFailure("UPSTREAM_REJECTED", false, response.status, ...metadata);
}

function extractContent(body: unknown): string {
	if (!isRecord(body) || !Array.isArray(body.choices) || !isRecord(body.choices[0])) throw new ContractError("completion_shape");
	const message = body.choices[0].message;
	if (!isRecord(message) || typeof message.content !== "string" || !message.content) throw new ContractError("completion_shape");
	return message.content;
}

function validateModelBody(body: unknown, request: InterpretationRequest, attempt: number): InterpretationResponse {
	let stage = "completion_shape";
	try {
		const content = extractContent(body);
		stage = "json_parse";
		const raw: unknown = JSON.parse(content);
		stage = "normalization";
		return normalizeAndValidateModelOutput(raw, request, (issue) => {
			const details = issue.validation;
			logDiagnostic({
				event: "operation_rejected", request_id: request.request_id, provider_attempt: attempt,
				response_format_mode: "json_object", validation_stage: issue.stage,
				validation_field_path: details.field_path, validation_error_type: details.error_type,
				validation_error_code: details.error_code, validation_missing_fields: details.missing_fields,
				validation_unexpected_fields: details.unexpected_fields,
				validation_expected_json_type: details.expected_json_type,
				validation_actual_json_type: details.actual_json_type,
				validation_error_count: details.error_count,
				validation_operation_type: details.operation_type as "ADD" | "UPDATE" | "COMPLETE" | "DELETE" | undefined,
			});
		});
	} catch (error) {
		const failedStage = error instanceof ContractError ? error.stage : stage;
		if (error instanceof ContractError) throw new ContractError(diagnosticStage(failedStage), error.validation ?? {
			field_path: failedStage === "response_status" ? "$response.status" : "$response",
			error_type: failedStage === "response_status" ? "status_content_mismatch" : "contract_validation",
			error_code: failedStage === "response_status" ? "status_content_mismatch" : failedStage,
			expected_json_type: failedStage === "response_status" ? "string" : undefined,
			actual_json_type: failedStage === "response_status" ? "string" : undefined,
			error_count: 1,
		});
		throw new ContractError(diagnosticStage(failedStage));
	}
}

function safeValidationFields(error: ContractError) {
	const stagePaths: Record<string, string> = {
		response_shape: "$model_output", response_fields: "$model_output", operation_shape: "$model_output.operations",
		operation_fields: "$model_output.operations", change_shape: "$model_output.operations.changes",
		change_fields: "$model_output.operations.changes", response_status: "$model_output",
	};
	const details = error.validation ?? {
		field_path: stagePaths[error.stage] ?? "$model_output",
		error_type: "contract_validation",
		error_code: error.stage,
		error_count: 1,
	};
	return {
		validation_field_path: details.field_path,
		validation_error_type: details.error_type,
		validation_error_code: details.error_code,
		validation_missing_fields: details.missing_fields,
		validation_unexpected_fields: details.unexpected_fields,
		validation_expected_json_type: details.expected_json_type,
		validation_actual_json_type: details.actual_json_type,
		validation_error_count: details.error_count,
	};
}

function logProviderFailure(request: InterpretationRequest, attempt: number, error: ProviderFailure, mode: ResponseFormatMode, retrying: boolean): void {
	const retryHeaders = error.headers;
	logDiagnostic({
		event: "provider_attempt_failed", request_id: request.request_id, provider_attempt: attempt,
		response_format_mode: mode,
		upstream_status: error.code === "RATE_LIMITED" ? 429 : error.status,
		category: error.code, rate_limits: retryHeaders ? safeRateLimitHeaders(retryHeaders) : undefined,
		provider_error_code: error.providerErrorCode,
		provider_error_type: error.providerErrorType,
		provider_error_parameter: error.providerErrorParameter,
		provider_request_id: error.providerRequestId,
		...(error.status === 400 ? {
			provider_error_category: providerErrorCategory(error.providerErrorCode, error.providerErrorType, error.providerErrorParameter),
			model_output_contract_fingerprint: safeFingerprint("tasksync-model-output:operations,clarifications,unsupported_items"),
			prompt_fingerprint: safeFingerprint(SYSTEM_PROMPT),
		} : {}),
		retrying,
	});
}

export async function interpretWithGroq(
	request: InterpretationRequest,
	apiKey: string,
	fetcher: Fetcher = fetch,
): Promise<InterpretationResponse> {
	for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
		logDiagnostic({ event: "provider_attempt_started", request_id: request.request_id, provider_attempt: attempt, response_format_mode: "json_object" });
		try {
			const { response, body } = await upstreamAttempt(request, apiKey, fetcher);
			const result = validateModelBody(body, request, attempt);
			logDiagnostic({ event: "provider_attempt_succeeded", request_id: request.request_id, provider_attempt: attempt, response_format_mode: "json_object", upstream_status: response.status });
			return result;
		} catch (error) {
			if (error instanceof ContractError) {
				logDiagnostic({
					event: "provider_attempt_failed", request_id: request.request_id, provider_attempt: attempt,
					upstream_status: 200, category: "INVALID_MODEL_RESPONSE",
					validation_stage: diagnosticStage(error.stage), ...safeValidationFields(error), retrying: attempt < MAX_ATTEMPTS,
				});
				if (attempt < MAX_ATTEMPTS) continue;
				throw new ProviderFailure("INVALID_MODEL_RESPONSE", false, 502);
			}
			if (error instanceof ProviderFailure) {
				const retryHeaders = error.headers;
				const retryMs = retryHeaders ? retryAfterMs(retryHeaders) : null;
				const mayRetry = attempt < MAX_ATTEMPTS && (
					error.code === "UPSTREAM_TIMEOUT" || error.code === "UPSTREAM_UNAVAILABLE" ||
					(error.code === "RATE_LIMITED" && retryMs !== null && retryMs <= MAX_RATE_LIMIT_WAIT_MS)
				);
				logProviderFailure(request, attempt, error, "json_object", mayRetry);
				if (mayRetry) {
					if (error.code === "RATE_LIMITED") await delay(retryMs!);
					else await delay(RETRY_DELAY_MS);
					continue;
				}
				throw error;
			}
			logDiagnostic({ event: "provider_attempt_failed", request_id: request.request_id, provider_attempt: attempt, category: "UPSTREAM_UNAVAILABLE", retrying: attempt < MAX_ATTEMPTS });
			if (attempt < MAX_ATTEMPTS) {
				await delay(RETRY_DELAY_MS);
				continue;
			}
			throw new ProviderFailure("UPSTREAM_UNAVAILABLE", true, 503);
		}
	}
	throw new ProviderFailure("UPSTREAM_UNAVAILABLE", true, 503);
}
