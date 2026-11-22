import { afterEach, describe, expect, it, vi } from "vitest";
import { handleRequest, type RateLimiterBinding, type WorkerEnvironment } from "../src/index";
import { SYSTEM_PROMPT } from "../src/prompt";

const TRANSCRIPT = "Add dentist appointment tomorrow. Update my report title to final report. Complete the old draft. Delete the duplicate draft. I may have two similar appointments.";
const REQUEST = {
	schema_version: "2",
	request_id: "req-test-123",
	transcript: TRANSCRIPT,
	locale_hint: "en-IN",
	reference_time: "2026-11-22T12:00:00+05:30",
	time_zone: "Asia/Kolkata",
};

function rawOperation(
	type: "ADD" | "UPDATE" | "COMPLETE" | "DELETE",
	source_text: string,
	options: {
		title?: string;
		description?: string | null;
		target_text?: string;
		changes?: Record<string, unknown>;
		change_fields?: string[];
		date_phrase?: string | null;
		time_phrase?: string | null;
		priority?: "HIGH" | "MEDIUM" | "LOW" | null;
		reminder_requested?: boolean;
	} = {},
) {
	return {
		type,
		source_text,
		title: options.title ?? null,
		description: options.description ?? null,
		target_text: options.target_text ?? null,
		date_phrase: options.date_phrase ?? null,
		time_phrase: options.time_phrase ?? null,
		priority: options.priority ?? null,
		reminder_requested: options.reminder_requested ?? false,
		changes: { title: null, description: null, date_phrase: null, time_phrase: null, priority: null, ...options.changes },
		change_fields: options.change_fields ?? [],
	};
}

function providerBody(raw: unknown, preserveEnvelope = false): Response {
	let modelOutput = raw;
	if (!preserveEnvelope && typeof raw === "object" && raw !== null && !Array.isArray(raw) && "status" in raw) {
		const { status: _modelStatus, ...output } = raw as Record<string, unknown>;
		modelOutput = output;
	}
	return new Response(JSON.stringify({ choices: [{ message: { content: JSON.stringify(modelOutput) } }] }), {
		status: 200,
		headers: { "content-type": "application/json" },
	});
}

function providerContent(content: string): Response {
	return new Response(JSON.stringify({ choices: [{ message: { content } }] }), { status: 200, headers: { "content-type": "application/json" } });
}

function okRaw(overrides: Record<string, unknown> = {}) {
	return { status: "proposals", operations: [rawOperation("ADD", "Add dentist appointment tomorrow", { title: "dentist appointment", date_phrase: "tomorrow" })], clarifications: [], unsupported_items: [], ...overrides };
}

function limiter(result = true) {
	const limit = vi.fn(async () => ({ success: result }));
	return { binding: { limit } satisfies RateLimiterBinding, limit };
}

function environment(options: { key?: string; limited?: boolean } = {}) {
	const rate = limiter(options.limited ?? true);
	return {
		env: { GROQ_API_KEY: options.key ?? "test-groq-secret", INTERPRETATION_LIMITER: rate.binding } satisfies WorkerEnvironment,
		limit: rate.limit,
	};
}

function incoming(body: unknown = REQUEST, options: { headers?: Record<string, string>; raw?: string } = {}) {
	return new Request("https://tasksync.example/v1/interpretations", {
		method: "POST",
		headers: { "content-type": "application/json", "cf-connecting-ip": "203.0.113.10", ...options.headers },
		body: options.raw ?? JSON.stringify(body),
	});
}

async function readJson(response: Response): Promise<any> {
	return response.json();
}

describe("TaskSync Cloudflare interpretation Worker", () => {
	afterEach(() => vi.restoreAllMocks());

	it("reports Groq health without exposing configuration", async () => {
		const { env } = environment();
		const healthy = await handleRequest(new Request("https://tasksync.example/health"), env);
		expect(await readJson(healthy)).toEqual({ status: "ok", interpreter: "groq" });
		const missing = await handleRequest(new Request("https://tasksync.example/health"), { INTERPRETATION_LIMITER: env.INTERPRETATION_LIMITER });
		expect(await readJson(missing)).toEqual({ status: "ok", interpreter: "not_configured" });
	});

	it("interprets a valid v2 request using JSON Object Mode without requiring client auth", async () => {
		const log = vi.spyOn(console, "log").mockImplementation(() => undefined);
		const { env, limit } = environment();
		let outgoing: RequestInit | undefined;
		const fetcher: typeof fetch = vi.fn(async (_input, init) => { outgoing = init; return providerBody(okRaw()); }) as typeof fetch;
		const response = await handleRequest(incoming(REQUEST), env, fetcher);
		const body = await readJson(response);
		expect(response.status).toBe(200);
		expect(body.schema_version).toBe("2");
		expect(body.request_id).toBe(REQUEST.request_id);
		expect(body.operations[0]).toMatchObject({ operation_id: "op-1", type: "ADD", title: "dentist appointment", date_phrase: "tomorrow", reminder_requested: false, confidence: null });
		expect(body.operations[0].source_text).toBe("Add dentist appointment tomorrow");
		expect(limit).toHaveBeenCalledWith({ key: "203.0.113.10" });
		expect(new Headers(outgoing?.headers).get("authorization")).toBe("Bearer test-groq-secret");
		const sent = JSON.parse(String(outgoing?.body));
		expect(sent.model).toBe("openai/gpt-oss-20b");
		expect(sent.messages).toEqual([{ role: "system", content: SYSTEM_PROMPT }, { role: "user", content: TRANSCRIPT }]);
		expect(sent.response_format).toEqual({ type: "json_object" });
		expect(sent).not.toHaveProperty("response_format.json_schema");
		expect(sent.max_completion_tokens).toBe(4096);
		expect(JSON.stringify(log.mock.calls)).not.toContain(TRANSCRIPT);
		expect(JSON.stringify(log.mock.calls)).not.toContain("test-groq-secret");
	});

	it("normalizes the exact simple Rahul request in primary JSON Object Mode", async () => {
		const log = vi.spyOn(console, "log").mockImplementation(() => undefined);
		const { env } = environment();
		const transcript = "Add a task to call Rahul tomorrow at 5 PM.";
		const request = { ...REQUEST, request_id: "req-rahul-simple", transcript };
		const raw = okRaw({ operations: [rawOperation("ADD", "call Rahul tomorrow at 5 PM", {
			title: "Call Rahul", date_phrase: "tomorrow", time_phrase: "5 PM",
		})] });
		const fetcher = vi.fn(async () => providerBody(raw)) as unknown as typeof fetch;
		const response = await handleRequest(incoming(request), env, fetcher);
		const body = await readJson(response);
		expect(response.status).toBe(200);
		expect(body).toMatchObject({ schema_version: "2", request_id: request.request_id, status: "proposals" });
		expect(body.operations).toEqual([{
			type: "ADD", operation_id: "op-1", source_text: "call Rahul tomorrow at 5 PM", confidence: null,
			title: "Call Rahul", description: null, date_phrase: "tomorrow", time_phrase: "5 PM",
			priority: null, reminder_requested: false,
		}]);
		expect(fetcher).toHaveBeenCalledTimes(1);
		const outgoing = fetcher.mock.calls.map(([, init]) => JSON.parse(String(init?.body)));
		for (const sent of outgoing) {
			expect(sent.messages[1].content).toBe(transcript);
			expect(sent.response_format).toEqual({ type: "json_object" });
		}
		const startEvents = log.mock.calls.map(([event]) => event as { event?: string; response_format_mode?: string })
			.filter((event) => event.event === "provider_attempt_started");
		expect(startEvents.map((event) => event.response_format_mode)).toEqual(["json_object"]);
	});

	it("does not accept model-supplied protocol metadata over the validated request", async () => {
		const log = vi.spyOn(console, "log").mockImplementation(() => undefined);
		const { env } = environment();
		const { status: _status, ...baseOutput } = okRaw();
		const modelOutput = { ...baseOutput, schema_version: "1", request_id: "req-spoofed" };
		const response = await handleRequest(incoming(REQUEST), env, vi.fn(async () => providerBody(modelOutput, true)) as typeof fetch);
		expect(response.status).toBe(502);
		expect((await readJson(response)).error.code).toBe("INVALID_MODEL_RESPONSE");
		expect(JSON.stringify(log.mock.calls)).toContain('"validation_unexpected_fields":["schema_version","request_id"]');
		const forgedOperationIds = {
			...baseOutput,
			operations: (baseOutput.operations as Array<Record<string, unknown>>).map((operation) => ({ ...operation, operation_id: "op-forged" })),
		};
		const forgedResponse = await handleRequest(incoming(REQUEST), env, vi.fn(async () => providerBody(forgedOperationIds, true)) as typeof fetch);
		const forgedBody = await readJson(forgedResponse);
		expect(forgedResponse.status).toBe(200);
		expect(forgedBody.operations).toHaveLength(0);
		expect(forgedBody.unsupported_items).toHaveLength(1);
		expect(JSON.stringify(forgedBody)).not.toContain("op-forged");
	});

	it("rejects missing model-output envelope fields with safe missing-field diagnostics", async () => {
		const log = vi.spyOn(console, "log").mockImplementation(() => undefined);
		const { env } = environment();
		const incomplete = JSON.stringify({ operations: [], clarifications: [] });
		const fetcher = vi.fn(async () => providerContent(incomplete)) as unknown as typeof fetch;
		const response = await handleRequest(incoming(REQUEST), env, fetcher);
		const diagnostics = JSON.stringify(log.mock.calls);
		expect(response.status).toBe(502);
		expect((await readJson(response)).error.code).toBe("INVALID_MODEL_RESPONSE");
		expect(diagnostics).toContain('"validation_missing_fields":["unsupported_items"]');
		expect(diagnostics).not.toContain(TRANSCRIPT);
	});

	it("rejects malformed model JSON and exact duplicate operations", async () => {
		const { env } = environment();
		const malformedFetcher = vi.fn(async () => providerContent("{not-json")) as unknown as typeof fetch;
		const malformedResponse = await handleRequest(incoming(REQUEST), env, malformedFetcher);
		expect(malformedResponse.status).toBe(502);
		expect((await readJson(malformedResponse)).error.code).toBe("INVALID_MODEL_RESPONSE");
		expect(malformedFetcher).toHaveBeenCalledTimes(2);

		const transcript = "Add dentist appointment tomorrow.";
		const duplicate = rawOperation("ADD", transcript, { title: "Dentist appointment", date_phrase: "tomorrow" });
		const duplicateFetcher = vi.fn(async () => providerBody({ operations: [duplicate, duplicate], clarifications: [], unsupported_items: [] })) as unknown as typeof fetch;
		const duplicateResponse = await handleRequest(incoming({ ...REQUEST, transcript }), env, duplicateFetcher);
		const duplicateBody = await readJson(duplicateResponse);
		expect(duplicateResponse.status).toBe(200);
		expect(duplicateBody.operations).toHaveLength(1);
		expect(duplicateBody.unsupported_items).toHaveLength(1);
		expect(duplicateFetcher).toHaveBeenCalledTimes(1);
	});

	it("preserves v1 response compatibility by omitting v2-only fields", async () => {
		const { env } = environment();
		const raw = okRaw({ operations: [rawOperation("ADD", "Add dentist appointment tomorrow. This is urgent, and remind me", { title: "dentist appointment", date_phrase: "tomorrow" })] });
		(raw.operations as any[])[0].priority = "HIGH";
		(raw.operations as any[])[0].reminder_requested = true;
		const response = await handleRequest(incoming({ ...REQUEST, schema_version: "1", transcript: "Add dentist appointment tomorrow. This is urgent, and remind me", request_id: "req-v1" }), env, vi.fn(async () => providerBody(raw)) as typeof fetch);
		const body = await readJson(response);
		expect(response.status, JSON.stringify(body)).toBe(200);
		expect(body.schema_version).toBe("1");
		expect(body.operations[0]).not.toHaveProperty("priority");
		expect(body.operations[0]).not.toHaveProperty("reminder_requested");
	});

	it("supports all four operations and partial status with clarification and unsupported items", async () => {
		const { env } = environment();
		const raw = {
			status: "partial",
			operations: [
				rawOperation("ADD", "Add dentist appointment tomorrow", { title: "dentist appointment", date_phrase: "tomorrow" }),
				rawOperation("UPDATE", "Update my report title to final report", { target_text: "my report", changes: { title: "final report" }, change_fields: ["title"] }),
				rawOperation("COMPLETE", "Complete the old draft", { target_text: "old draft" }),
				rawOperation("DELETE", "Delete the duplicate draft", { target_text: "duplicate draft" }),
			],
			clarifications: [{ source_text: "I may have two similar appointments", question: "Which appointment do you mean?" }],
			unsupported_items: [{ source_text: "I may have two similar appointments", reason: "The intended appointment cannot be identified." }],
		};
		const response = await handleRequest(incoming(REQUEST), env, vi.fn(async () => providerBody(raw)) as typeof fetch);
		const body = await readJson(response);
		expect(response.status).toBe(200);
		expect(body.operations.map((item: any) => item.type)).toEqual(["ADD", "UPDATE", "COMPLETE", "DELETE"]);
		expect(body.operations.map((item: any) => item.operation_id)).toEqual(["op-1", "op-2", "op-3", "op-4"]);
		expect(body.operations[1].changes).toEqual({ title: "final report", description: null, date_phrase: null, time_phrase: null, priority: null });
		expect(body.clarifications).toHaveLength(1);
		expect(body.unsupported_items).toHaveLength(1);
	});

	it("normalizes COMPLETE and DELETE targets in one multi-operation response", async () => {
		const { env } = environment();
		const transcript = "Mark the old draft complete, then delete the duplicate reminder.";
		const raw = okRaw({ operations: [
			rawOperation("COMPLETE", "Mark the old draft complete", { target_text: "old draft" }),
			rawOperation("DELETE", "delete the duplicate reminder.", { target_text: "duplicate reminder" }),
		] });
		const response = await handleRequest(incoming({ ...REQUEST, transcript }), env,
			vi.fn(async () => providerBody(raw)) as typeof fetch);
		const body = await readJson(response);

		expect(response.status).toBe(200);
		expect(body.operations).toEqual([
			{ type: "COMPLETE", operation_id: "op-1", source_text: "Mark the old draft complete", confidence: null, target_text: "old draft" },
			{ type: "DELETE", operation_id: "op-2", source_text: "delete the duplicate reminder.", confidence: null, target_text: "duplicate reminder" },
		]);
	});

	it("rejects model-supplied status and records only safe field-level diagnostics", async () => {
		const log = vi.spyOn(console, "log").mockImplementation(() => undefined);
		const { env } = environment();
		const withUntrustedStatus = okRaw({ status: "needs_clarification" });
		const fetcher = vi.fn(async () => providerBody(withUntrustedStatus, true)) as unknown as typeof fetch;
		const response = await handleRequest(incoming(REQUEST), env, fetcher);
		const diagnostics = JSON.stringify(log.mock.calls);
		expect(response.status).toBe(502);
		expect((await readJson(response)).error.code).toBe("INVALID_MODEL_RESPONSE");
		expect(diagnostics).toContain('"validation_field_path":"$model_output"');
		expect(diagnostics).toContain('"validation_error_type":"extra_forbidden"');
		expect(diagnostics).toContain('"validation_error_code":"unexpected_fields"');
		expect(diagnostics).toContain('"validation_unexpected_fields":["status"]');
		expect(diagnostics).toContain('"validation_expected_json_type":"object"');
		expect(diagnostics).toContain('"validation_actual_json_type":"object"');
		expect(diagnostics).toContain('"validation_error_count":1');
		expect(diagnostics).not.toContain(TRANSCRIPT);
	});

	it("returns a grounded ambiguity clarification alongside independent operations", async () => {
		const { env } = environment();
		const transcript = "Add milk tomorrow and delete the old reminder, but I have two old reminders.";
		const raw = okRaw({
			status: "partial",
			operations: [rawOperation("ADD", "Add milk tomorrow", { title: "Add milk", date_phrase: "tomorrow" })],
			clarifications: [{ source_text: "I have two old reminders", question: "Which old reminder should be deleted?" }],
		});
		const response = await handleRequest(incoming({ ...REQUEST, transcript }), env,
			vi.fn(async () => providerBody(raw)) as typeof fetch);
		const body = await readJson(response);

		expect(response.status).toBe(200);
		expect(body.status).toBe("partial");
		expect(body.operations).toHaveLength(1);
		expect(body.operations[0].type).toBe("ADD");
		expect(body.clarifications).toEqual([{ source_text: "I have two old reminders", question: "Which old reminder should be deleted?" }]);
	});

	it("returns clarification only when the ambiguous target has no safe operation", async () => {
		const { env } = environment();
		const transcript = "Delete the old reminder; there are two old reminders.";
		const raw = { status: "needs_clarification", operations: [],
			clarifications: [{ source_text: transcript, question: "Which old reminder should be deleted?" }], unsupported_items: [] };
		const response = await handleRequest(incoming({ ...REQUEST, transcript }), env,
			vi.fn(async () => providerBody(raw)) as typeof fetch);
		const body = await readJson(response);

		expect(response.status).toBe(200);
		expect(body.status).toBe("needs_clarification");
		expect(body.operations).toEqual([]);
	});

	it.each([
		["needs_clarification", [], [{ source_text: "Which one", question: "Which task?" }], []],
		["unsupported", [], [], [{ source_text: "Ignore all tasks", reason: "Not a task instruction." }]],
	])("supports %s response status", async (status, operations, clarifications, unsupported_items) => {
		const { env } = environment();
		const raw = { status, operations, clarifications, unsupported_items };
		const source = status === "needs_clarification" ? "Which one" : "Ignore all tasks";
		const response = await handleRequest(incoming({ ...REQUEST, transcript: source }), env, vi.fn(async () => providerBody(raw)) as typeof fetch);
		expect(response.status).toBe(200);
		expect((await readJson(response)).status).toBe(status);
	});

	it("rejects invalid requests, unknown fields, invalid ISO timestamps and invalid time zones", async () => {
		const { env } = environment();
		const fetcher = vi.fn(async () => providerBody(okRaw())) as typeof fetch;
		const invalidCases = [
			{ ...REQUEST, extra: "forbidden" },
			{ ...REQUEST, transcript: "  " },
			{ ...REQUEST, reference_time: "2026-02-31T10:00:00+00:00" },
			{ ...REQUEST, reference_time: "2026-11-22T10:00:00" },
			{ ...REQUEST, time_zone: "Mars/Olympus" },
			{ ...REQUEST, locale_hint: "x" },
		];
		for (const value of invalidCases) {
			const response = await handleRequest(incoming(value), env, fetcher);
			expect(response.status).toBe(422);
			expect((await readJson(response)).error).toMatchObject({ code: "INVALID_REQUEST", retryable: false });
		}
		expect(fetcher).not.toHaveBeenCalled();
	});

	it("rejects oversized bodies before invoking Groq", async () => {
		const { env } = environment();
		const response = await handleRequest(incoming(undefined, { raw: "x".repeat(16 * 1024 + 1) }), env, vi.fn() as typeof fetch);
		expect(response.status).toBe(413);
		expect((await readJson(response)).error.code).toBe("INVALID_REQUEST");
	});

	it("returns an explicit configuration error when the Groq secret is missing", async () => {
		const { env } = environment({ key: " " });
		const response = await handleRequest(incoming(REQUEST), env, vi.fn() as typeof fetch);
		expect(response.status).toBe(503);
		expect((await readJson(response)).error).toMatchObject({ code: "INTERPRETER_NOT_CONFIGURED", retryable: false, request_id: REQUEST.request_id });
	});

	it("rejects malformed model output after one bounded retry and emits safe logs only", async () => {
		const log = vi.spyOn(console, "log").mockImplementation(() => undefined);
		const { env } = environment();
		const fetcher = vi.fn(async () => new Response(JSON.stringify({ choices: [{ message: { content: "private provider body not json" } }] }), { status: 200 }));
		const response = await handleRequest(incoming(REQUEST), env, fetcher as typeof fetch);
		const serializedLogs = JSON.stringify(log.mock.calls);
		const errorResponse = JSON.stringify(await response.clone().json());
		expect(response.status).toBe(502);
		expect((await readJson(response)).error).toMatchObject({ code: "INVALID_MODEL_RESPONSE", retryable: false, request_id: REQUEST.request_id });
		expect(fetcher).toHaveBeenCalledTimes(2);
		expect(serializedLogs).toContain("json_parse");
		expect(serializedLogs).not.toContain(TRANSCRIPT);
		expect(serializedLogs).not.toContain("test-groq-secret");
		expect(serializedLogs).not.toContain("private provider body");
		expect(errorResponse).not.toContain("test-groq-secret");
		expect(errorResponse).not.toContain("private provider body");
	});

	it("falls back to a grounded evidence-derived title", async () => {
		const { env } = environment();
		const raw = okRaw({ operations: [rawOperation("ADD", "Add dentist appointment tomorrow", { title: "unmentioned medical procedure" })] });
		const response = await handleRequest(incoming(REQUEST), env, vi.fn(async () => providerBody(raw)) as typeof fetch);
		expect(response.status).toBe(200);
		const operation = (await readJson(response)).operations[0];
		expect(operation.title).toBe("Add dentist appointment");
		expect(operation.date_phrase).toBe("tomorrow");
	});

	it("accepts concise titles for the reported reminder and call transcript with per-task dates", async () => {
		const { env } = environment();
		const transcript = "Remind me to study Java tomorrow at 6 PM and call Rahul on Friday.";
		const raw = okRaw({
			operations: [
				{ ...rawOperation("ADD", "Remind me to study Java tomorrow at 6 PM", { title: "Study Java", date_phrase: "tomorrow" }), time_phrase: "6 PM", reminder_requested: true },
				rawOperation("ADD", "call Rahul on Friday.", { title: "Call Rahul", date_phrase: "Friday" }),
			],
		});
		const response = await handleRequest(
			incoming({ ...REQUEST, transcript }), env,
			vi.fn(async () => providerBody(raw)) as typeof fetch,
		);
		const body = await readJson(response);

		expect(response.status, JSON.stringify(body)).toBe(200);
		expect(body.operations.map((operation: any) => operation.title)).toEqual(["Study Java", "Call Rahul"]);
		expect(body.operations.map((operation: any) => operation.date_phrase)).toEqual(["tomorrow", "Friday"]);
		expect(body.operations[0].time_phrase).toBe("6 PM");
		expect(body.operations.map((operation: any) => operation.source_text)).toEqual([
			"Remind me to study Java tomorrow at 6 PM", "call Rahul on Friday.",
		]);
	});

	it("keeps titles and date phrases matched to their own source in multiple different tasks", async () => {
		const { env } = environment();
		const transcript = "Buy milk tomorrow and pay the electricity bill next Friday.";
		const raw = okRaw({
			operations: [
				rawOperation("ADD", "Buy milk tomorrow", { title: "Buy milk", date_phrase: "tomorrow" }),
				rawOperation("ADD", "pay the electricity bill next Friday.", { title: "Pay electricity bill", date_phrase: "next Friday" }),
			],
		});
		const response = await handleRequest(
			incoming({ ...REQUEST, transcript }), env,
			vi.fn(async () => providerBody(raw)) as typeof fetch,
		);
		const body = await readJson(response);

		expect(response.status, JSON.stringify(body)).toBe(200);
		expect(body.operations.map((operation: any) => [operation.title, operation.date_phrase])).toEqual([
			["Buy milk", "tomorrow"], ["Pay electricity bill", "next Friday"],
		]);
	});

	it("accepts punctuation and omitted lead-in words only when title tokens stay source-grounded", async () => {
		const { env } = environment();
		const transcript = "Please remind me to study Java tomorrow.";
		const raw = okRaw({ operations: [rawOperation("ADD", transcript, { title: "Study, Java!", date_phrase: "tomorrow" })] });
		const response = await handleRequest(
			incoming({ ...REQUEST, transcript }), env,
			vi.fn(async () => providerBody(raw)) as typeof fetch,
		);
		const body = await readJson(response);

		expect(response.status, JSON.stringify(body)).toBe(200);
		expect(body.operations[0].title).toBe("Study, Java!");
		expect(body.operations[0].source_text).toBe(transcript);
	});

	it.each([
		["invented subject", "Remind me to study Java tomorrow", "Study Python"],
		["missing action", "Remind me to study Java tomorrow", "Java"],
		["altered action", "Remind me to study Java tomorrow", "Call Java"],
	])("uses evidence-derived title when model title has %s", async (_caseName, sourceText, title) => {
		const { env } = environment();
		const raw = okRaw({ operations: [rawOperation("ADD", sourceText, { title })] });
		const response = await handleRequest(incoming({ ...REQUEST, transcript: sourceText }), env,
			vi.fn(async () => providerBody(raw)) as typeof fetch);
		const body = await readJson(response);
		expect(response.status).toBe(200);
		expect(body.operations[0].title).toBe("Study Java");
		expect(body.operations[0].title).not.toContain("Python");
	});

	it("accepts multiple independent ADDs with separately grounded dates and actions", async () => {
		const { env } = environment();
		const transcript = "Add a study session tomorrow and a grocery run Friday.";
		const raw = okRaw({ operations: [
			rawOperation("ADD", "Add a study session tomorrow", { title: "Study session", date_phrase: "tomorrow" }),
			rawOperation("ADD", "a grocery run Friday.", { title: "Grocery run", date_phrase: "Friday" }),
		] });
		const response = await handleRequest(
			incoming({ ...REQUEST, transcript }), env,
			vi.fn(async () => providerBody(raw)) as typeof fetch,
		);
		const body = await readJson(response);

		expect(response.status, JSON.stringify(body)).toBe(200);
		expect(body.operations.map((operation: any) => [operation.title, operation.date_phrase])).toEqual([
			["Study session", "tomorrow"], ["Grocery run", "Friday"],
		]);
	});

	it("accepts an UPDATE that changes only the existing call task time", async () => {
		const { env } = environment();
		const transcript = "Update the existing call task's time to today at 9 PM.";
		const raw = okRaw({ operations: [rawOperation("UPDATE", transcript, {
			target_text: "existing call task",
			changes: { time_phrase: "today at 9 PM" },
			change_fields: ["time_phrase"],
		})] });
		const response = await handleRequest(
			incoming({ ...REQUEST, transcript }), env,
			vi.fn(async () => providerBody(raw)) as typeof fetch,
		);
		const body = await readJson(response);

		expect(response.status, JSON.stringify(body)).toBe(200);
		expect(body.operations[0]).toMatchObject({
			type: "UPDATE", target_text: "existing call task",
			changes: { title: null, description: null, date_phrase: null, time_phrase: "today at 9 PM", priority: null },
		});
	});

	it("updates date and time without changing description or priority", async () => {
		const { env } = environment();
		const transcript = "Move the existing call task to tomorrow at 9 PM.";
		const raw = okRaw({ operations: [rawOperation("UPDATE", transcript, {
			target_text: "existing call task",
			changes: { date_phrase: "tomorrow", time_phrase: "9 PM" },
			change_fields: ["date_phrase", "time_phrase"],
		})] });
		const response = await handleRequest(
			incoming({ ...REQUEST, transcript }), env,
			vi.fn(async () => providerBody(raw)) as typeof fetch,
		);
		const body = await readJson(response);

		expect(response.status, JSON.stringify(body)).toBe(200);
		expect(body.operations[0].changes).toEqual({ title: null, description: null, date_phrase: "tomorrow", time_phrase: "9 PM", priority: null });
	});

	it("accepts descriptions that omit conversational lead-ins while staying inside source evidence", async () => {
		const { env } = environment();
		const transcript = "I need to pay the electricity bill tomorrow at 5 PM.";
		const raw = okRaw({ operations: [rawOperation("ADD", transcript, {
			title: "Pay electricity bill", description: "pay the electricity bill", date_phrase: "tomorrow", time_phrase: "5 PM",
		})] });
		const response = await handleRequest(
			incoming({ ...REQUEST, transcript }), env,
			vi.fn(async () => providerBody(raw)) as typeof fetch,
		);
		const body = await readJson(response);

		expect(response.status, JSON.stringify(body)).toBe(200);
		expect(body.operations[0].description).toBe("pay the electricity bill");
	});

	it("requires source evidence for reminder and priority and rejects unsupported values", async () => {
		const { env } = environment();
		const transcript = "Remind me to submit the report tomorrow; it is urgent.";
		const valid = okRaw({ operations: [rawOperation("ADD", transcript, {
			title: "Submit report", date_phrase: "tomorrow", priority: "HIGH", reminder_requested: true,
		})] });
		const validResponse = await handleRequest(
			incoming({ ...REQUEST, transcript }), env,
			vi.fn(async () => providerBody(valid)) as typeof fetch,
		);
		expect(validResponse.status).toBe(200);
		expect((await readJson(validResponse)).operations[0]).toMatchObject({ priority: "HIGH", reminder_requested: true });

		const invalidCases = [
			{ transcript: "Submit the report tomorrow", operation: rawOperation("ADD", "Submit the report tomorrow", { title: "Submit report", priority: "HIGH" }), stage: "priority_evidence" },
			{ transcript: "Submit the report tomorrow", operation: rawOperation("ADD", "Submit the report tomorrow", { title: "Submit report", reminder_requested: true }), stage: "reminder_evidence" },
		];
		for (const invalid of invalidCases) {
			const log = vi.spyOn(console, "log").mockImplementation(() => undefined);
			const response = await handleRequest(
				incoming({ ...REQUEST, transcript: invalid.transcript }), env,
				vi.fn(async () => providerBody(okRaw({ operations: [invalid.operation] }))) as typeof fetch,
			);
			const body = await readJson(response);
			expect(response.status).toBe(200);
			expect(body.operations).toHaveLength(0);
			expect(body.unsupported_items).toHaveLength(1);
			expect(JSON.stringify(log.mock.calls)).toContain(`"validation_stage":"${invalid.stage}"`);
			vi.restoreAllMocks();
		}
	});

	it("rejects ADD-only nested changes and drops an invented description", async () => {
		const { env } = environment();
		const cases = [
			{
				transcript: "Add a call task tomorrow.",
				operation: rawOperation("ADD", "Add a call task tomorrow.", {
					title: "call task", date_phrase: "tomorrow", changes: { title: "call task" }, change_fields: ["title"],
				}),
				stage: "operation_fields",
			},
		];
		for (const current of cases) {
			const log = vi.spyOn(console, "log").mockImplementation(() => undefined);
			const response = await handleRequest(
				incoming({ ...REQUEST, transcript: current.transcript }), env,
				vi.fn(async () => providerBody(okRaw({ operations: [current.operation] }))) as typeof fetch,
			);
			const body = await readJson(response);
			expect(response.status).toBe(200);
			expect(body.operations).toHaveLength(0);
			expect(body.unsupported_items).toHaveLength(1);
			expect(JSON.stringify(log.mock.calls)).toContain(`"validation_stage":"${current.stage}"`);
			expect(JSON.stringify(log.mock.calls)).toContain('"validation_unexpected_fields":["change_fields"]');
			vi.restoreAllMocks();
		}
		const transcript = "Call Rahul tomorrow.";
		const response = await handleRequest(incoming({ ...REQUEST, transcript }), env,
			vi.fn(async () => providerBody(okRaw({ operations: [rawOperation("ADD", transcript, {
				title: "Call Rahul", description: "book a flight to Paris",
			})] }))) as typeof fetch);
		const body = await readJson(response);
		expect(response.status).toBe(200);
		expect(body.operations).toHaveLength(1);
		expect(body.operations[0].description).toBeNull();
		expect(body.operations[0].title).toBe("Call Rahul");
	});

	it("splits the reported hackathon transcript into four tasks with exact source and local deadlines", async () => {
		const { env } = environment();
		const transcript = "I have to prepare problem statement for Odisha hackathon I also need to prepare it PPT apart from that I need to present a prototype before next week Friday and also I have to design a poster by Tuesday";
		const evidence = [
			"prepare problem statement for Odisha hackathon",
			"prepare it PPT",
			"present a prototype before next week Friday",
			"design a poster by Tuesday",
		];
		const raw = okRaw({ operations: [
			rawOperation("ADD", evidence[0], { title: "Prepare Odisha hackathon problem statement" }),
			rawOperation("ADD", evidence[1], { title: "Prepare the PPT", description: "invented project background" }),
			rawOperation("ADD", evidence[2], { title: "Present the prototype", date_phrase: "next week Friday" }),
			rawOperation("ADD", evidence[3], { title: "Design the poster", date_phrase: "Tuesday" }),
		] });
		const response = await handleRequest(incoming({ ...REQUEST, transcript }), env,
			vi.fn(async () => providerBody(raw)) as typeof fetch);
		const body = await readJson(response);
		expect(response.status, JSON.stringify(body)).toBe(200);
		expect(body.operations).toHaveLength(4);
		expect(body).toMatchObject({ schema_version: "2", request_id: REQUEST.request_id, status: "proposals" });
		expect(body.operations.map((op: any) => op.operation_id)).toEqual(["op-1", "op-2", "op-3", "op-4"]);
		expect(body.operations.map((op: any) => op.source_text)).toEqual(evidence);
		expect(body.operations.map((op: any) => op.title)).toEqual([
			"Prepare Odisha hackathon problem statement", "Prepare the PPT", "Present the prototype", "Design the poster",
		]);
		expect(body.operations.map((op: any) => op.date_phrase)).toEqual([null, null, "next week Friday", "Tuesday"]);
		expect(body.operations[1].description).toBeNull();
	});

	it("keeps four independent ADD operations when the model emits empty cross-operation placeholders", async () => {
		const { env } = environment();
		const transcript = "Call Rahul tomorrow at 5 PM, buy milk, pay the bill Friday, and email Neha at 7 PM.";
		const rawOperations = [
			rawOperation("ADD", "Call Rahul tomorrow at 5 PM", { title: "Call Rahul", date_phrase: "tomorrow", time_phrase: "5 PM" }),
			rawOperation("ADD", "buy milk", { title: "Buy milk" }),
			rawOperation("ADD", "pay the bill Friday", { title: "Pay the bill", date_phrase: "Friday" }),
			rawOperation("ADD", "email Neha at 7 PM.", { title: "Email Neha", time_phrase: "7 PM" }),
		];
		for (const operation of rawOperations) {
			operation.target_text = null;
			operation.changes = { title: null, description: null, date_phrase: null, time_phrase: null, priority: null };
			operation.change_fields = [];
			(operation as any).task_id = "";
		}
		const response = await handleRequest(incoming({ ...REQUEST, transcript }), env,
			vi.fn(async () => providerBody({ operations: rawOperations, clarifications: [], unsupported_items: [] })) as typeof fetch);
		const body = await readJson(response);

		expect(response.status).toBe(200);
		expect(body.status).toBe("proposals");
		expect(body.operations).toHaveLength(4);
		expect(body.operations.map((operation: any) => operation.source_text)).toEqual(rawOperations.map((operation) => operation.source_text));
		expect(body.operations.map((operation: any) => [operation.date_phrase, operation.time_phrase])).toEqual([
			["tomorrow", "5 PM"], [null, null], ["Friday", null], [null, "7 PM"],
		]);
	});

	it.each([0, 2, 4])("isolates an unknown meaningful field in operation %i while retaining independent proposals", async (badIndex) => {
		const log = vi.spyOn(console, "log").mockImplementation(() => undefined);
		const { env } = environment();
		const transcript = "Call Rahul, buy milk, email Neha, pay the bill, and book a dentist visit.";
		const sources = ["Call Rahul", "buy milk", "email Neha", "pay the bill", "book a dentist visit."];
		const operations = sources.map((source, index) => rawOperation("ADD", source, {
			title: ["Call Rahul", "Buy milk", "Email Neha", "Pay the bill", "Book dentist visit"][index],
		}));
		const secretLikeSentinel = "must-not-appear-in-diagnostics";
		(operations[badIndex] as any).model_comment = secretLikeSentinel;
		const response = await handleRequest(incoming({ ...REQUEST, transcript }), env,
			vi.fn(async () => providerBody({ operations, clarifications: [], unsupported_items: [] })) as typeof fetch);
		const body = await readJson(response);
		const diagnostics = JSON.stringify(log.mock.calls);

		expect(response.status).toBe(200);
		expect(body.status).toBe("partial");
		expect(body.operations).toHaveLength(4);
		expect(body.unsupported_items).toHaveLength(1);
		expect(body.unsupported_items[0].source_text).toBe(sources[badIndex]);
		expect(diagnostics).toContain('"validation_unexpected_fields":["model_comment"]');
		expect(diagnostics).toContain('"validation_operation_type":"ADD"');
		expect(diagnostics).not.toContain(secretLikeSentinel);
		vi.restoreAllMocks();
	});

	it("maps unambiguous top-level UPDATE date and time fields into changes only", async () => {
		const { env } = environment();
		const transcript = "Update my dentist appointment to tomorrow at 5 PM.";
		const update = rawOperation("UPDATE", transcript, { target_text: "my dentist appointment" });
		(update as any).date_phrase = "tomorrow";
		(update as any).time_phrase = "5 PM";
		const response = await handleRequest(incoming({ ...REQUEST, transcript }), env,
			vi.fn(async () => providerBody({ operations: [update], clarifications: [], unsupported_items: [] })) as typeof fetch);
		const body = await readJson(response);

		expect(response.status).toBe(200);
		expect(body.operations).toHaveLength(1);
		expect(body.operations[0].changes).toEqual({
			title: null, description: null, date_phrase: "tomorrow", time_phrase: "5 PM", priority: null,
		});
		expect(body.operations[0]).not.toHaveProperty("change_fields");
	});

	it("preserves valid independent operations and reports one unsafe operation as unsupported", async () => {
		const { env } = environment();
		const transcript = "Call Rahul tomorrow at 5 PM, then delete the old draft, and buy milk.";
		const unsafe = rawOperation("DELETE", "delete the old draft", { target_text: "old draft" }) as any;
		unsafe.database_command = "DROP TABLE tasks";
		const response = await handleRequest(incoming({ ...REQUEST, transcript }), env,
			vi.fn(async () => providerBody({ operations: [
				rawOperation("ADD", "Call Rahul tomorrow at 5 PM", { title: "Call Rahul", date_phrase: "tomorrow", time_phrase: "5 PM" }),
				unsafe,
				rawOperation("ADD", "buy milk.", { title: "Buy milk" }),
			], clarifications: [], unsupported_items: [] })) as typeof fetch);
		const body = await readJson(response);

		expect(response.status).toBe(200);
		expect(body.status).toBe("partial");
		expect(body.operations.map((operation: any) => operation.title)).toEqual(["Call Rahul", "Buy milk"]);
		expect(body.operations[0]).toMatchObject({ date_phrase: "tomorrow", time_phrase: "5 PM", source_text: "Call Rahul tomorrow at 5 PM" });
		expect(body.unsupported_items).toHaveLength(1);
		expect(body.unsupported_items[0].source_text).toBe("delete the old draft");
	});

	it("does not silently discard unknown meaningful operation fields", async () => {
		const log = vi.spyOn(console, "log").mockImplementation(() => undefined);
		const { env } = environment();
		const transcript = "Add dentist appointment tomorrow.";
		const operation = rawOperation("ADD", transcript, { title: "Dentist appointment", date_phrase: "tomorrow" }) as any;
		operation.internal_task_id = "room-row-17";
		const response = await handleRequest(incoming({ ...REQUEST, transcript }), env,
			vi.fn(async () => providerBody({ operations: [operation], clarifications: [], unsupported_items: [] })) as typeof fetch);
		const body = await readJson(response);
		const diagnostics = JSON.stringify(log.mock.calls);

		expect(response.status).toBe(200);
		expect(body.operations).toHaveLength(0);
		expect(body.unsupported_items).toHaveLength(1);
		expect(JSON.stringify(body)).not.toContain("room-row-17");
		expect(diagnostics).toContain('"validation_unexpected_fields":["internal_task_id"]');
		expect(diagnostics).not.toContain("room-row-17");
		vi.restoreAllMocks();
	});

	it("keeps independent responsibilities separate in a conversational multi-task sentence", async () => {
		const { env } = environment();
		const transcript = "On my way home I should pick up milk, and also call the dentist tomorrow, then pay the electricity bill next Friday.";
		const raw = okRaw({ operations: [
			rawOperation("ADD", "pick up milk", { title: "Pick up milk" }),
			rawOperation("ADD", "call the dentist tomorrow", { title: "Call the dentist", date_phrase: "tomorrow" }),
			rawOperation("ADD", "pay the electricity bill next Friday.", { title: "Pay the electricity bill", date_phrase: "next Friday" }),
		] });
		const response = await handleRequest(incoming({ ...REQUEST, transcript }), env,
			vi.fn(async () => providerBody(raw)) as typeof fetch);
		const body = await readJson(response);
		expect(response.status, JSON.stringify(body)).toBe(200);
		expect(body.operations.map((op: any) => [op.title, op.date_phrase])).toEqual([
			["Pick up milk", null], ["Call the dentist", "tomorrow"], ["Pay the electricity bill", "next Friday"],
		]);
	});

	it("rejects UPDATE clears unless the transcript explicitly asks to clear the field", async () => {
		const { env } = environment();
		const transcript = "Update the call task's time.";
		const invalid = okRaw({ operations: [rawOperation("UPDATE", transcript, {
			target_text: "call task", changes: { time_phrase: null }, change_fields: ["time_phrase"],
		})] });
		const log = vi.spyOn(console, "log").mockImplementation(() => undefined);
		const response = await handleRequest(
			incoming({ ...REQUEST, transcript }), env,
			vi.fn(async () => providerBody(invalid)) as typeof fetch,
		);
		const invalidBody = await readJson(response);
		expect(response.status).toBe(200);
		expect(invalidBody.operations).toHaveLength(0);
		expect(invalidBody.unsupported_items).toHaveLength(1);
		expect(JSON.stringify(log.mock.calls)).toContain('"validation_stage":"update_clear_evidence"');

		vi.restoreAllMocks();
		const clearTranscript = "Clear the time for the call task.";
		const validClear = okRaw({ operations: [rawOperation("UPDATE", clearTranscript, {
			target_text: "call task", changes: { time_phrase: null }, change_fields: ["time_phrase"],
		})] });
		const cleared = await handleRequest(
			incoming({ ...REQUEST, transcript: clearTranscript }), env,
			vi.fn(async () => providerBody(validClear)) as typeof fetch,
		);
		expect(cleared.status).toBe(200);
		expect((await readJson(cleared)).operations[0].changes.time_phrase).toBeNull();
	});

	it("captures only allowlisted Groq error code and type for a non-retryable 400", async () => {
		const log = vi.spyOn(console, "log").mockImplementation(() => undefined);
		const { env } = environment();
		const providerBodyText = JSON.stringify({ error: {
			message: "private provider message", code: "context_length_exceeded", type: "invalid_request_error",
		} });
		const fetcher = vi.fn(async () => new Response(providerBodyText, { status: 400 })) as typeof fetch;

		const response = await handleRequest(incoming(REQUEST), env, fetcher);

		expect(response.status).toBe(502);
		expect((await readJson(response)).error).toMatchObject({ code: "UPSTREAM_REJECTED", retryable: false });
		expect(fetcher).toHaveBeenCalledTimes(1);
		const diagnostics = JSON.stringify(log.mock.calls);
		expect(diagnostics).toContain('"provider_error_code":"context_length_exceeded"');
		expect(diagnostics).toContain('"provider_error_type":"invalid_request_error"');
		expect(diagnostics).not.toContain("private provider message");
		expect(diagnostics).not.toContain(TRANSCRIPT);
		expect(diagnostics).not.toContain("test-groq-secret");
		expect(diagnostics).toContain('"provider_error_category":"other_or_unknown"');
		expect(diagnostics).toMatch(/"model_output_contract_fingerprint":"fnv1a32-[a-f0-9]{8}"/u);
		expect(diagnostics).toMatch(/"prompt_fingerprint":"fnv1a32-[a-f0-9]{8}"/u);
	});

	it.each([
		["json_validate_failed", "invalid_request_error", "schema_validation"],
		["invalid_api_key", "authentication_error", "authentication"],
		["insufficient_quota", "insufficient_quota", "quota_or_rate_limit"],
		["model_not_found", "not_found_error", "model_configuration"],
		["secret-bearing-unknown-value", "private-error-type", "other_or_unknown"],
	])("safely classifies HTTP 400 provider category %s", async (code, type, expectedCategory) => {
		const log = vi.spyOn(console, "log").mockImplementation(() => undefined);
		const { env } = environment();
		const fetcher = vi.fn(async () => new Response(JSON.stringify({ error: {
			message: "private provider body and transcript must not be logged", code, type,
		} }), { status: 400 }));
		const response = await handleRequest(incoming(REQUEST), env, fetcher as typeof fetch);
		const diagnostics = JSON.stringify(log.mock.calls);
		expect(response.status).toBe(502);
		expect(diagnostics).toContain(`"provider_error_category":"${expectedCategory}"`);
		expect(diagnostics).not.toContain("private provider body");
		expect(diagnostics).not.toContain(TRANSCRIPT);
		expect(diagnostics).not.toContain("test-groq-secret");
	});

	it("classifies provider timeout, rate limit and 5xx with bounded attempts", async () => {
		const { env } = environment();
		const cases = [
			{ response: () => Promise.reject(new DOMException("private timeout detail", "AbortError")), status: 504, code: "UPSTREAM_TIMEOUT", retryable: true },
			{ response: () => Promise.resolve(new Response("private body", { status: 429, headers: { "retry-after": "10", "x-ratelimit-remaining-requests": "2" } })), status: 429, code: "RATE_LIMITED", retryable: true },
			{ response: () => Promise.resolve(new Response("private body", { status: 502 })), status: 503, code: "UPSTREAM_UNAVAILABLE", retryable: true },
			{ response: () => Promise.resolve(new Response("private body", { status: 401 })), status: 502, code: "UPSTREAM_REJECTED", retryable: false },
		];
		for (const current of cases) {
			const fetcher = vi.fn(current.response) as unknown as typeof fetch;
			const response = await handleRequest(incoming(REQUEST), env, fetcher);
			expect(response.status).toBe(current.status);
			expect((await readJson(response)).error).toMatchObject({ code: current.code, retryable: current.retryable });
			expect(fetcher).toHaveBeenCalledTimes(current.code === "RATE_LIMITED" || current.code === "UPSTREAM_REJECTED" ? 1 : 2);
		}
	});

	it("retries a Groq 502 once and succeeds on the second attempt", async () => {
		const log = vi.spyOn(console, "log").mockImplementation(() => undefined);
		const { env } = environment();
		const fetcher = vi.fn()
			.mockResolvedValueOnce(new Response("private upstream body", { status: 502 }))
			.mockImplementationOnce(async () => providerBody(okRaw())) as unknown as typeof fetch;

		const response = await handleRequest(incoming(REQUEST), env, fetcher);

		expect(response.status).toBe(200);
		expect((await readJson(response)).operations).toHaveLength(1);
		expect(fetcher).toHaveBeenCalledTimes(2);
		const diagnostics = JSON.stringify(log.mock.calls);
		expect(diagnostics).toContain('"upstream_status":502');
		expect(diagnostics).toContain('"category":"UPSTREAM_UNAVAILABLE"');
		expect(diagnostics).not.toContain("private upstream body");
	});

	it("maps two consecutive Groq 502 responses to retryable upstream unavailable", async () => {
		const log = vi.spyOn(console, "log").mockImplementation(() => undefined);
		const { env } = environment();
		const fetcher = vi.fn(async () => new Response("private upstream body", { status: 502 })) as typeof fetch;

		const response = await handleRequest(incoming(REQUEST), env, fetcher);

		expect(response.status).toBe(503);
		expect((await readJson(response)).error).toMatchObject({ code: "UPSTREAM_UNAVAILABLE", retryable: true });
		expect(fetcher).toHaveBeenCalledTimes(2);
		const failedAttempts = log.mock.calls.map(([event]) => event as { event?: string; provider_attempt?: number; upstream_status?: number; category?: string })
			.filter((event) => event.event === "provider_attempt_failed");
		expect(failedAttempts.map(({ provider_attempt, upstream_status, category }) => [provider_attempt, upstream_status, category])).toEqual([
			[1, 502, "UPSTREAM_UNAVAILABLE"],
			[2, 502, "UPSTREAM_UNAVAILABLE"],
		]);
		expect(JSON.stringify(log.mock.calls)).not.toContain("private upstream body");
	});

	it("retries a Groq 503 once and succeeds on the second attempt", async () => {
		const { env } = environment();
		const fetcher = vi.fn()
			.mockResolvedValueOnce(new Response("private upstream body", { status: 503 }))
			.mockImplementationOnce(async () => providerBody(okRaw())) as unknown as typeof fetch;

		const response = await handleRequest(incoming(REQUEST), env, fetcher);

		expect(response.status).toBe(200);
		expect(fetcher).toHaveBeenCalledTimes(2);
	});

	it("does not retry a Groq 401 response", async () => {
		const log = vi.spyOn(console, "log").mockImplementation(() => undefined);
		const { env } = environment();
		const fetcher = vi.fn(async () => new Response("private upstream body", { status: 401 })) as typeof fetch;

		const response = await handleRequest(incoming(REQUEST), env, fetcher);

		expect(response.status).toBe(502);
		expect((await readJson(response)).error).toMatchObject({ code: "UPSTREAM_REJECTED", retryable: false });
		expect(fetcher).toHaveBeenCalledTimes(1);
		const diagnostics = JSON.stringify(log.mock.calls);
		expect(diagnostics).toContain('"upstream_status":401');
		expect(diagnostics).toContain('"category":"UPSTREAM_REJECTED"');
		expect(diagnostics).not.toContain("private upstream body");
	});

	it("retries a short Retry-After once and captures only numeric allowlisted headers", async () => {
		const log = vi.spyOn(console, "log").mockImplementation(() => undefined);
		const { env } = environment();
		const fetcher = vi.fn()
			.mockResolvedValueOnce(new Response("do not log", { status: 429, headers: {
				"retry-after": "0.001", "x-ratelimit-limit-requests": "100", "x-ratelimit-remaining-requests": "8",
				"x-ratelimit-reset-tokens": "1m", authorization: "Bearer never-log-this", "x-secret": "no",
			} }))
			.mockImplementationOnce(async () => providerBody(okRaw())) as unknown as typeof fetch;
		const response = await handleRequest(incoming(REQUEST), env, fetcher);
		expect(response.status).toBe(200);
		expect(fetcher).toHaveBeenCalledTimes(2);
		const logText = JSON.stringify(log.mock.calls);
		expect(logText).toContain("reset_tokens_seconds");
		expect(logText).toContain("remaining_requests");
		expect(logText).not.toContain("never-log-this");
		expect(logText).not.toContain("do not log");
	});

	it("uses a short token reset as 429 retry guidance when Retry-After is absent", async () => {
		const { env } = environment();
		const fetcher = vi.fn()
			.mockResolvedValueOnce(new Response("private provider body", { status: 429, headers: { "x-ratelimit-reset-tokens": "0.001s" } }))
			.mockImplementationOnce(async () => providerBody(okRaw())) as unknown as typeof fetch;

		const response = await handleRequest(incoming(REQUEST), env, fetcher);

		expect(response.status).toBe(200);
		expect(fetcher).toHaveBeenCalledTimes(2);
	});

	it("does not retry 429 when Retry-After or token reset exceeds the bounded wait budget", async () => {
		const { env } = environment();
		const fetcher = vi.fn(async () => new Response("private provider body", {
			status: 429,
			headers: { "retry-after": "8", "x-ratelimit-reset-tokens": "40s" },
		})) as typeof fetch;

		const response = await handleRequest(incoming(REQUEST), env, fetcher);

		expect(response.status).toBe(429);
		expect((await readJson(response)).error).toMatchObject({ code: "RATE_LIMITED", retryable: true });
		expect(fetcher).toHaveBeenCalledTimes(1);
	});

	it("does not call Groq when the approximate per-IP limiter rejects the request", async () => {
		const { env, limit } = environment({ limited: false });
		const fetcher = vi.fn() as typeof fetch;
		const response = await handleRequest(incoming(REQUEST), env, fetcher);
		expect(response.status).toBe(429);
		expect((await readJson(response)).error).toMatchObject({ code: "RATE_LIMITED", retryable: true, request_id: REQUEST.request_id });
		expect(limit).toHaveBeenCalledWith({ key: "203.0.113.10" });
		expect(fetcher).not.toHaveBeenCalled();
	});

	it("fails closed if the rate-limit binding is unavailable", async () => {
		const response = await handleRequest(incoming(REQUEST), { GROQ_API_KEY: "secret" }, vi.fn() as typeof fetch);
		expect(response.status).toBe(503);
		expect((await readJson(response)).error.code).toBe("UPSTREAM_UNAVAILABLE");
	});

	it("rejects malformed output fields and response status inconsistency", async () => {
		const { env } = environment();
		const missingOperationField = rawOperation("ADD", "Add dentist appointment tomorrow", { title: "Dentist appointment" }) as Record<string, unknown>;
		delete missingOperationField.time_phrase;
		const invalid = [
			{ raw: okRaw({ unknown: true }), preserve: false, status: 502, code: "INVALID_MODEL_RESPONSE" },
			{ raw: okRaw({ status: "needs_clarification" }), preserve: true, status: 502, code: "INVALID_MODEL_RESPONSE" },
			{ raw: okRaw({ operations: [rawOperation("COMPLETE", "Complete the old draft", { target_text: "old draft", title: "forbidden" })] }), preserve: false, status: 200, operationCount: 0, unsupportedCount: 1 },
			{ raw: { operations: [missingOperationField], clarifications: [], unsupported_items: [] }, preserve: false, status: 200, operationCount: 1, unsupportedCount: 0 },
		];
		for (const expected of invalid) {
			const { raw, preserve } = expected;
			const response = await handleRequest(incoming(REQUEST), env, vi.fn(async () => providerBody(raw, preserve)) as typeof fetch);
			expect(response.status).toBe(expected.status);
			const body = await readJson(response);
			if (response.status === 502) expect(body.error.code).toBe(expected.code);
			else {
				expect(body.operations).toHaveLength(expected.operationCount);
				expect(body.unsupported_items).toHaveLength(expected.unsupportedCount);
			}
		}
	});
});
