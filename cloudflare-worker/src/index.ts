import { ContractError, isRecord, normalizeAndValidateModelResponse, validateRequest, type ErrorBody, type InterpretationRequest } from "./contract";
import { logDiagnostic } from "./diagnostics";
import { interpretWithGroq } from "./groq";

const MAX_REQUEST_BYTES = 16 * 1024;

export interface RateLimiterBinding {
	limit(options: { key: string }): Promise<{ success: boolean }>;
}

export interface WorkerEnvironment {
	GROQ_API_KEY?: string;
	INTERPRETATION_LIMITER?: RateLimiterBinding;
}

function json(body: unknown, status = 200): Response {
	return new Response(JSON.stringify(body), {
		status,
		headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" },
	});
}

function errorResponse(
	status: number,
	code: ErrorBody["error"]["code"],
	message: string,
	retryable: boolean,
	requestId: string | null = null,
): Response {
	const safeId = typeof requestId === "string" && requestId.length > 0 && requestId.length <= 100 ? requestId : null;
	logDiagnostic({ event: "task_error", request_id: safeId ?? undefined, category: code, task_http_status: status, task_retryable: retryable });
	const body: ErrorBody = { error: { code, message, retryable, request_id: safeId } };
	return json(body, status);
}

function requestIdFrom(value: unknown): string | null {
	if (!isRecord(value) || typeof value.request_id !== "string") return null;
	return value.request_id.length >= 1 && value.request_id.length <= 100 ? value.request_id : null;
}

async function requestBodyText(request: Request): Promise<string> {
	const contentLength = request.headers.get("content-length");
	if (contentLength && /^\d+$/u.test(contentLength) && Number(contentLength) > MAX_REQUEST_BYTES) throw new ContractError("request_size");
	if (!request.body) return "";
	const reader = request.body.getReader();
	const chunks: Uint8Array[] = [];
	let total = 0;
	try {
		while (true) {
			const { done, value } = await reader.read();
			if (done) break;
			total += value.byteLength;
			if (total > MAX_REQUEST_BYTES) {
				await reader.cancel();
				throw new ContractError("request_size");
			}
			chunks.push(value);
		}
	} finally {
		reader.releaseLock();
	}
	const bytes = new Uint8Array(total);
	let offset = 0;
	for (const chunk of chunks) {
		bytes.set(chunk, offset);
		offset += chunk.byteLength;
	}
	return new TextDecoder("utf-8", { fatal: true, ignoreBOM: false }).decode(bytes);
}

async function applyAbuseLimit(request: Request, env: WorkerEnvironment): Promise<"ok" | "limited" | "unavailable"> {
	const binding = env.INTERPRETATION_LIMITER;
	if (!binding) return "unavailable";
	// Cloudflare supplies this header. It is only a coarse abuse key, never user authentication.
	const connectingIp = request.headers.get("cf-connecting-ip")?.trim().slice(0, 64) || "unknown-ip";
	try {
		return (await binding.limit({ key: connectingIp })).success ? "ok" : "limited";
	} catch {
		return "unavailable";
	}
}

async function handleInterpretation(request: Request, env: WorkerEnvironment, fetcher: typeof fetch): Promise<Response> {
	const contentType = request.headers.get("content-type") ?? "";
	if (!/^application\/json(?:\s*;|\s*$)/iu.test(contentType)) return errorResponse(422, "INVALID_REQUEST", "The request does not match the interpretation contract.", false);

	let rawRequest: unknown;
	let requestId: string | null = null;
	try {
		const text = await requestBodyText(request);
		rawRequest = JSON.parse(text);
		requestId = requestIdFrom(rawRequest);
	} catch (error) {
		const oversized = error instanceof ContractError && error.stage === "request_size";
		return errorResponse(oversized ? 413 : 422, "INVALID_REQUEST", "The request does not match the interpretation contract.", false, requestId);
	}

	let validated: InterpretationRequest;
	try {
		validated = validateRequest(rawRequest);
		requestId = validated.request_id;
	} catch {
		return errorResponse(422, "INVALID_REQUEST", "The request does not match the interpretation contract.", false, requestId);
	}
	if (!env.GROQ_API_KEY?.trim()) return errorResponse(503, "INTERPRETER_NOT_CONFIGURED", "The interpretation provider is not configured.", false, validated.request_id);
	const limit = await applyAbuseLimit(request, env);
	if (limit === "limited") return errorResponse(429, "RATE_LIMITED", "The interpretation service is busy. Please try again shortly.", true, validated.request_id);
	if (limit === "unavailable") return errorResponse(503, "UPSTREAM_UNAVAILABLE", "The interpretation service is temporarily unavailable.", true, validated.request_id);

	try {
		const response = await interpretWithGroq(validated, env.GROQ_API_KEY, fetcher);
		return json(response);
	} catch (error) {
		if (error instanceof ContractError) {
			return errorResponse(502, "INVALID_MODEL_RESPONSE", "The interpretation service returned an unusable proposal. Please try again.", false, validated.request_id);
		}
		if (error instanceof Error && "code" in error) {
			const failure = error as Error & { code: string; retryable: boolean; status: number };
			if (failure.code === "RATE_LIMITED") return errorResponse(429, "RATE_LIMITED", "The interpretation service is busy. Please try again shortly.", true, validated.request_id);
			if (failure.code === "UPSTREAM_TIMEOUT") return errorResponse(504, "UPSTREAM_TIMEOUT", "The interpretation service timed out. Please try again.", true, validated.request_id);
			if (failure.code === "UPSTREAM_REJECTED") return errorResponse(502, "UPSTREAM_REJECTED", "The interpretation provider rejected the request. Please check the request and configuration.", false, validated.request_id);
			if (failure.code === "INVALID_MODEL_RESPONSE") return errorResponse(502, "INVALID_MODEL_RESPONSE", "The interpretation service returned an unusable proposal. Please try again.", false, validated.request_id);
			return errorResponse(503, "UPSTREAM_UNAVAILABLE", "The interpretation service is temporarily unavailable.", true, validated.request_id);
		}
		return errorResponse(503, "UPSTREAM_UNAVAILABLE", "The interpretation service is temporarily unavailable.", true, validated.request_id);
	}
}

export async function handleRequest(request: Request, env: WorkerEnvironment, fetcher: typeof fetch = fetch): Promise<Response> {
	const url = new URL(request.url);
	if (url.pathname === "/health" && request.method === "GET") {
		return json({ status: "ok", interpreter: env.GROQ_API_KEY?.trim() ? "groq" : "not_configured" });
	}
	if (url.pathname === "/v1/interpretations" && request.method === "POST") return handleInterpretation(request, env, fetcher);
	if (url.pathname === "/v1/interpretations") return errorResponse(405, "INVALID_REQUEST", "The request does not match the interpretation contract.", false);
	return errorResponse(404, "INVALID_REQUEST", "The request does not match the interpretation contract.", false);
}

export default {
	fetch(request: Request, env: WorkerEnvironment): Promise<Response> {
		return handleRequest(request, env);
	},
} satisfies ExportedHandler<WorkerEnvironment>;
