const REQUEST_ID = /^(?:eval-[A-Za-z0-9_.:-]{1,96}|req-[A-Za-z0-9_.:-]{1,96}|request-[A-Za-z0-9_.:-]{1,95}|[A-Fa-f0-9]{8}-(?:[A-Fa-f0-9]{4}-){3}[A-Fa-f0-9]{12})$/u;
const CATEGORIES = new Set([
	"RATE_LIMITED", "UPSTREAM_TIMEOUT", "UPSTREAM_UNAVAILABLE", "UPSTREAM_REJECTED",
	"INVALID_MODEL_RESPONSE", "INTERPRETER_NOT_CONFIGURED", "INVALID_REQUEST", "UNAUTHENTICATED",
]);
const STAGES = new Set([
	"completion_shape", "json_parse", "normalization", "pydantic_validation", "source_text_grounding",
	"title_grounding", "description_grounding", "date_phrase_grounding", "time_phrase_grounding",
	"target_text_grounding", "update_value_grounding", "text_record_grounding", "response_shape",
	"response_fields", "response_status", "operation_shape", "operation_fields", "change_shape",
	"change_fields", "operation_type", "priority_evidence", "reminder_evidence", "add_field_consistency",
	"description_shape", "date_phrase_shape", "time_phrase_shape", "update_change_fields",
	"priority_clear_evidence", "update_clear_evidence", "update_field_consistency", "update_reminder_consistency",
	"terminal_operation_field_consistency", "clarification_validation", "unsupported_validation",
	"clarification_grounding", "unsupported_grounding",
	"empty_model_output", "update_field_conflict",
]);
const PROVIDER_ERROR_CODES = new Set([
	"invalid_request_error", "model_not_found", "context_length_exceeded", "rate_limit_exceeded",
	"insufficient_quota", "invalid_api_key", "unsupported_value", "json_validate_failed", "other",
]);
const PROVIDER_ERROR_TYPES = new Set([
	"invalid_request_error", "authentication_error", "permission_error", "not_found_error",
	"request_timeout", "rate_limit_error", "server_error", "insufficient_quota", "other",
]);
const PROVIDER_ERROR_PARAMETERS = new Set(["model", "messages", "response_format", "response_format.type", "response_format.json_schema", "response_format.json_schema.schema", "max_completion_tokens"]);
const NUMERIC_HEADERS: Record<string, string> = {
	"x-ratelimit-limit-requests": "limit_requests",
	"x-ratelimit-remaining-requests": "remaining_requests",
	"x-ratelimit-limit-tokens": "limit_tokens",
	"x-ratelimit-remaining-tokens": "remaining_tokens",
};

function parseReset(value: string | null): number | null {
	if (!value) return null;
	const pieces = [...value.matchAll(/(\d+(?:\.\d+)?)([hms])/gu)];
	if (!pieces.length || pieces.map((piece) => piece[0]).join("") !== value.trim()) return null;
	const multiplier: Record<string, number> = { h: 3600, m: 60, s: 1 };
	const seconds = pieces.reduce((sum, piece) => sum + Number(piece[1]) * multiplier[piece[2]], 0);
	return Number.isFinite(seconds) && seconds >= 0 ? seconds : null;
}

function parseRetryAfter(value: string | null): number | null {
	if (!value) return null;
	const numeric = Number(value);
	if (Number.isFinite(numeric) && numeric >= 0) return numeric;
	const date = Date.parse(value);
	return Number.isFinite(date) ? Math.max(0, (date - Date.now()) / 1000) : null;
}

export function safeRateLimitHeaders(headers: Headers): Record<string, number> {
	const values: Record<string, number> = {};
	for (const [header, key] of Object.entries(NUMERIC_HEADERS)) {
		const raw = headers.get(header)?.trim();
		if (raw && /^\d{1,12}$/u.test(raw)) values[key] = Number(raw);
	}
	const resetRequests = parseReset(headers.get("x-ratelimit-reset-requests"));
	const resetTokens = parseReset(headers.get("x-ratelimit-reset-tokens"));
	const retryAfter = parseRetryAfter(headers.get("retry-after"));
	if (resetRequests !== null) values.reset_requests_seconds = Math.round(resetRequests * 1000) / 1000;
	if (resetTokens !== null) values.reset_tokens_seconds = Math.round(resetTokens * 1000) / 1000;
	if (retryAfter !== null) values.retry_after_seconds = Math.round(retryAfter * 1000) / 1000;
	return values;
}

export interface Diagnostic {
	event: "provider_attempt_started" | "provider_attempt_succeeded" | "provider_attempt_failed" | "operation_rejected" | "task_error";
	request_id?: string;
	provider_attempt?: number;
	response_format_mode?: "json_schema" | "json_object";
	upstream_status?: number;
	category?: string;
	validation_stage?: string;
	validation_field_path?: string;
	validation_error_type?: string;
	validation_error_code?: string;
	validation_missing_fields?: string[];
	validation_unexpected_fields?: string[];
	validation_expected_json_type?: "object" | "array" | "string" | "number" | "boolean" | "null" | "enum";
	validation_actual_json_type?: "object" | "array" | "string" | "number" | "boolean" | "null";
	validation_error_count?: number;
	validation_operation_type?: "ADD" | "UPDATE" | "COMPLETE" | "DELETE";
	rate_limits?: Record<string, number>;
	retrying?: boolean;
	task_http_status?: number;
	task_retryable?: boolean;
	provider_error_code?: string;
	provider_error_type?: string;
	provider_error_parameter?: string;
	provider_request_id?: string;
	provider_error_category?: "schema_validation" | "authentication" | "quota_or_rate_limit" | "model_configuration" | "other_or_unknown";
	model_output_contract_fingerprint?: string;
	prompt_fingerprint?: string;
}

export function logDiagnostic(value: Diagnostic): void {
	if (!new Set(["provider_attempt_started", "provider_attempt_succeeded", "provider_attempt_failed", "operation_rejected", "task_error"]).has(value.event)) return;
	const output: Record<string, unknown> = { timestamp: new Date().toISOString(), event: value.event };
	if (typeof value.request_id === "string" && REQUEST_ID.test(value.request_id)) output.request_id = value.request_id;
	if (Number.isInteger(value.provider_attempt) && value.provider_attempt! >= 1 && value.provider_attempt! <= 2) output.provider_attempt = value.provider_attempt;
	if (value.response_format_mode === "json_schema" || value.response_format_mode === "json_object") output.response_format_mode = value.response_format_mode;
	if (Number.isInteger(value.upstream_status) && value.upstream_status! >= 100 && value.upstream_status! <= 599) output.upstream_status = value.upstream_status;
	if (typeof value.category === "string" && CATEGORIES.has(value.category)) output.category = value.category;
	if (typeof value.validation_stage === "string" && STAGES.has(value.validation_stage)) output.validation_stage = value.validation_stage;
	if (typeof value.validation_field_path === "string" && /^\$[A-Za-z0-9_.\[\]-]{1,120}$/u.test(value.validation_field_path)) output.validation_field_path = value.validation_field_path;
	if (["type_error", "enum_error", "status_content_mismatch", "missing", "extra_forbidden", "contract_validation", "duplicate_operation", "empty_model_output"].includes(value.validation_error_type ?? "")) output.validation_error_type = value.validation_error_type;
	if (["invalid_type", "invalid_enum", "status_content_mismatch", "missing_fields", "unexpected_fields", "duplicate_operation", "empty_model_output"].includes(value.validation_error_code ?? "") || STAGES.has(value.validation_error_code ?? "")) output.validation_error_code = value.validation_error_code;
	for (const key of ["validation_missing_fields", "validation_unexpected_fields"] as const) {
		const names = value[key]?.filter((name) => /^[A-Za-z_][A-Za-z0-9_]{0,63}$/u.test(name)).slice(0, 12);
		if (names?.length) output[key] = names;
	}
	if (["object", "array", "string", "number", "boolean", "null", "enum"].includes(value.validation_expected_json_type ?? "")) output.validation_expected_json_type = value.validation_expected_json_type;
	if (["object", "array", "string", "number", "boolean", "null"].includes(value.validation_actual_json_type ?? "")) output.validation_actual_json_type = value.validation_actual_json_type;
	if (Number.isInteger(value.validation_error_count) && value.validation_error_count! >= 1 && value.validation_error_count! <= 100) output.validation_error_count = value.validation_error_count;
	if (["ADD", "UPDATE", "COMPLETE", "DELETE"].includes(value.validation_operation_type ?? "")) output.validation_operation_type = value.validation_operation_type;
	if (typeof value.retrying === "boolean") output.retrying = value.retrying;
	if (Number.isInteger(value.task_http_status) && value.task_http_status! >= 100 && value.task_http_status! <= 599) output.task_http_status = value.task_http_status;
	if (typeof value.task_retryable === "boolean") output.task_retryable = value.task_retryable;
	if (typeof value.provider_error_code === "string" && PROVIDER_ERROR_CODES.has(value.provider_error_code)) output.provider_error_code = value.provider_error_code;
	if (typeof value.provider_error_type === "string" && PROVIDER_ERROR_TYPES.has(value.provider_error_type)) output.provider_error_type = value.provider_error_type;
	if (typeof value.provider_error_parameter === "string" && PROVIDER_ERROR_PARAMETERS.has(value.provider_error_parameter)) output.provider_error_parameter = value.provider_error_parameter;
	if (typeof value.provider_request_id === "string" && /^[A-Za-z0-9_-]{1,80}$/u.test(value.provider_request_id)) output.provider_request_id = value.provider_request_id;
	if (["schema_validation", "authentication", "quota_or_rate_limit", "model_configuration", "other_or_unknown"].includes(value.provider_error_category ?? "")) output.provider_error_category = value.provider_error_category;
	for (const key of ["model_output_contract_fingerprint", "prompt_fingerprint"] as const) {
		const fingerprint = value[key];
		if (typeof fingerprint === "string" && /^fnv1a32-[a-f0-9]{8}$/u.test(fingerprint)) output[key] = fingerprint;
	}
	if (value.rate_limits) {
		const allowed = new Set(["limit_requests", "remaining_requests", "limit_tokens", "remaining_tokens", "reset_requests_seconds", "reset_tokens_seconds", "retry_after_seconds"]);
		const limits: Record<string, number> = {};
		for (const [key, number] of Object.entries(value.rate_limits)) {
			if (allowed.has(key) && Number.isFinite(number) && number >= 0) limits[key] = number;
		}
		if (Object.keys(limits).length) output.rate_limits = limits;
	}
	// Never pass request/provider bodies, headers, credentials, or exception text to observability.
	console.log(output);
}
