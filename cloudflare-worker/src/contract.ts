export type Priority = "HIGH" | "MEDIUM" | "LOW";
export type OperationType = "ADD" | "UPDATE" | "COMPLETE" | "DELETE";
export type ResponseStatus = "proposals" | "needs_clarification" | "unsupported" | "partial";

export interface InterpretationRequest {
	schema_version: "1" | "2";
	request_id: string;
	transcript: string;
	locale_hint: string | null;
	reference_time: string;
	time_zone: string;
}

export interface InterpretationResponse {
	schema_version: "1" | "2";
	request_id: string;
	status: ResponseStatus;
	operations: Array<Record<string, unknown>>;
	clarifications: Array<{ source_text: string; question: string }>;
	unsupported_items: Array<{ source_text: string; reason: string }>;
}

export interface ErrorBody {
	error: {
		code:
			| "UNAUTHENTICATED"
			| "INVALID_REQUEST"
			| "RATE_LIMITED"
			| "UPSTREAM_UNAVAILABLE"
			| "UPSTREAM_TIMEOUT"
			| "UPSTREAM_REJECTED"
			| "INVALID_MODEL_RESPONSE"
			| "INTERPRETER_NOT_CONFIGURED";
		message: string;
		retryable: boolean;
		request_id: string | null;
	};
}

export class ContractError extends Error {
	constructor(readonly stage: string, readonly validation?: SafeValidationDetails) {
		super(stage);
		this.name = "ContractError";
	}
}

export interface SafeValidationDetails {
	field_path: string;
	error_type: string;
	error_code: string;
	operation_type?: string;
	missing_fields?: string[];
	unexpected_fields?: string[];
	expected_json_type?: "object" | "array" | "string" | "number" | "boolean" | "null" | "enum";
	actual_json_type?: "object" | "array" | "string" | "number" | "boolean" | "null";
	error_count: number;
}

const REQUEST_FIELDS = new Set([
	"schema_version",
	"request_id",
	"transcript",
	"locale_hint",
	"reference_time",
	"time_zone",
]);

export function isRecord(value: unknown): value is Record<string, unknown> {
	return typeof value === "object" && value !== null && !Array.isArray(value);
}

function exactKeys(value: Record<string, unknown>, expected: Set<string>, stage: string, fieldPath = stage): void {
	const unexpected = Object.keys(value).filter((key) => !expected.has(key));
	if (unexpected.length) throw new ContractError(stage, { field_path: fieldPath, error_type: "extra_forbidden", error_code: "unexpected_fields", unexpected_fields: unexpected, expected_json_type: "object", actual_json_type: "object", error_count: unexpected.length });
}

function requiredKeys(value: Record<string, unknown>, required: string[], stage: string, fieldPath = stage): void {
	const missing = required.filter((key) => !Object.prototype.hasOwnProperty.call(value, key));
	if (missing.length) throw new ContractError(stage, { field_path: fieldPath, error_type: "missing", error_code: "missing_fields", missing_fields: missing, expected_json_type: "object", actual_json_type: "object", error_count: missing.length });
}

function jsonValueType(value: unknown): SafeValidationDetails["actual_json_type"] | undefined {
	if (value === null) return "null";
	if (Array.isArray(value)) return "array";
	if (typeof value === "object") return "object";
	if (typeof value === "string") return "string";
	if (typeof value === "number") return "number";
	if (typeof value === "boolean") return "boolean";
	return undefined;
}

function isIsoWithOffset(value: string): boolean {
	const match = /^(\d{4})-(\d{2})-(\d{2})[Tt ](\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:?\d{2})$/.exec(value);
	if (!match) return false;
	const [, ys, mos, ds, hs, mins, ss, , zone] = match;
	const y = Number(ys), mo = Number(mos), d = Number(ds);
	const h = Number(hs), min = Number(mins), sec = Number(ss);
	const calendar = new Date(0);
	calendar.setUTCHours(0, 0, 0, 0);
	calendar.setUTCFullYear(y, mo - 1, d);
	if (calendar.getUTCFullYear() !== y || calendar.getUTCMonth() !== mo - 1 || calendar.getUTCDate() !== d) return false;
	if (h > 23 || min > 59 || sec > 59) return false;
	if (zone !== "Z") {
		const offset = /[+-](\d{2}):?(\d{2})$/.exec(zone);
		if (!offset || Number(offset[1]) > 23 || Number(offset[2]) > 59) return false;
	}
	return Number.isFinite(Date.parse(value));
}

export function validateRequest(value: unknown): InterpretationRequest {
	if (!isRecord(value)) throw new ContractError("request_shape");
	exactKeys(value, REQUEST_FIELDS, "request_fields");
	requiredKeys(value, ["schema_version", "request_id", "transcript", "reference_time", "time_zone"], "request_fields");
	if (value.schema_version !== "1" && value.schema_version !== "2") throw new ContractError("schema_version");
	if (typeof value.request_id !== "string" || value.request_id.length < 1 || value.request_id.length > 100) throw new ContractError("request_id");
	if (typeof value.transcript !== "string" || value.transcript.length < 1 || value.transcript.length > 5000 || !value.transcript.trim()) throw new ContractError("transcript");
	if (value.locale_hint !== undefined && value.locale_hint !== null && (typeof value.locale_hint !== "string" || value.locale_hint.length < 2 || value.locale_hint.length > 35)) throw new ContractError("locale_hint");
	if (typeof value.reference_time !== "string" || value.reference_time.length < 20 || value.reference_time.length > 40 || !isIsoWithOffset(value.reference_time)) throw new ContractError("reference_time");
	if (typeof value.time_zone !== "string" || value.time_zone.length < 1 || value.time_zone.length > 100) throw new ContractError("time_zone");
	try {
		new Intl.DateTimeFormat("en", { timeZone: value.time_zone }).format(0);
	} catch {
		throw new ContractError("time_zone");
	}
	return {
		schema_version: value.schema_version,
		request_id: value.request_id,
		transcript: value.transcript,
		locale_hint: (value.locale_hint as string | null | undefined) ?? null,
		reference_time: value.reference_time,
		time_zone: value.time_zone,
	};
}

function grounded(value: unknown, transcript: string, min: number, max: number): value is string {
	if (typeof value !== "string" || value.length < min || value.length > max || !value.trim()) return false;
	return foldWhitespace(value).length > 0 && foldWhitespace(transcript).includes(foldWhitespace(value));
}

function titleTokens(value: string): string[] {
	return foldWhitespace(value).match(/[\p{L}\p{N}]+/gu) ?? [];
}

function titleActionAnchor(sourceText: string): string | null {
	let core = foldWhitespace(sourceText).trim();
	const hadConnector = /^(?:and|also|then)\s+/u.test(core);
	core = core.replace(/^(?:and|also|then)\s+/u, "").replace(/^please[\s,:-]+/u, "");
	const intentPrefixes = [
		/^(?:remind me|set (?:a )?reminder) to\s+/u,
		/(?:^|\b)(?:don't|dont|never) forget to\s+/u,
		/^(?:i need|i have|i should|i must|i want) to\s+/u,
	];
	const prefix = intentPrefixes.find((pattern) => pattern.test(core));
	if (!prefix && !hadConnector) return null;
	if (prefix) core = core.replace(prefix, "");
	return titleTokens(core)[0] ?? null;
}

function titleSupportedBySource(value: unknown, sourceText: string): value is string {
	if (typeof value !== "string" || value.length < 1 || value.length > 500 || !value.trim()) return false;
	const tokens = titleTokens(value).filter((token) => !["a", "an", "the"].includes(token));
	const sourceTokens = titleTokens(sourceText);
	if (tokens.length === 0 || sourceTokens.length === 0) return false;
	const available = new Map<string, number>();
	for (const token of sourceTokens) available.set(token, (available.get(token) ?? 0) + 1);
	for (const token of tokens) {
		const count = available.get(token) ?? 0;
		if (count === 0) return false;
		available.set(token, count - 1);
	}
	const actionAnchor = titleActionAnchor(sourceText);
	return actionAnchor === null || titleTokens(value).includes(actionAnchor);
}

function safeTitleFromEvidence(sourceText: string): string {
	let title = sourceText
		.replace(/^(?:(?:and|also)\s+)*(?:i\s+(?:have to|need to|also need to|also have to)|i\s+also\s+have to|please|remind me to|set (?:a )?reminder to)\s+/iu, "")
		.replace(/\b(?:before|by|on)\s+(?:next\s+week\s+)?(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|today|tomorrow)\b.*$/iu, "")
		.replace(/\b(?:next week|next week's|next weeks)\s+(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b.*$/iu, "")
		.replace(/\b(?:today|tomorrow)\b.*$/iu, "")
		.replace(/\b(?:at|around)\s+\d{1,2}(?::\d{2})?\s*(?:am|pm)\b.*$/iu, "")
		.replace(/\bit\s+(?=ppt\b)/giu, "the ")
		.replace(/\bit\b/giu, " ")
		.replace(/\s+/gu, " ").trim();
	if (!title || !titleTokens(title).length) title = sourceText.trim();
	return title.charAt(0).toLocaleUpperCase() + title.slice(1);
}

function datePhraseFromEvidence(sourceText: string): string | null {
	const match = /\b(?:next\s+week\s+)?(?:today|tomorrow|yesterday|next\s+(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)|(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)(?:\s+next\s+week)?|next\s+week(?:'s)?\s+(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday))\b/iu.exec(sourceText);
	return match?.[0] ?? null;
}

function descriptionSupportedBySource(value: unknown, sourceText: string): value is string {
	if (typeof value !== "string" || value.length > 2000 || !value.trim()) return false;
	const requested = titleTokens(value);
	const available = titleTokens(sourceText);
	if (requested.length === 0 || available.length === 0) return false;
	let cursor = 0;
	for (const token of requested) {
		const found = available.indexOf(token, cursor);
		if (found < 0) return false;
		cursor = found + 1;
	}
	return true;
}

function foldWhitespace(value: string): string {
	return value.replace(/\s+/gu, " ").toLowerCase().replace(/[ßẞ]/gu, "ss").replace(/ς/gu, "σ");
}

function boundedString(value: unknown, max: number): value is string {
	return typeof value === "string" && value.length <= max;
}

const FIELDS = ["title", "description", "date_phrase", "time_phrase", "priority"] as const;
type ChangeField = (typeof FIELDS)[number];
const PRIORITIES = new Set(["HIGH", "MEDIUM", "LOW"]);
const TYPES = new Set<OperationType>(["ADD", "UPDATE", "COMPLETE", "DELETE"]);
const RAW_RESPONSE_KEYS = new Set(["status", "operations", "clarifications", "unsupported_items"]);
const MODEL_OUTPUT_KEYS = new Set(["operations", "clarifications", "unsupported_items"]);
const RAW_OPERATION_KEYS = new Set([
	"type", "source_text", "title", "description", "target_text", "date_phrase", "time_phrase",
	"priority", "reminder_requested", "changes", "change_fields",
]);
const RAW_CHANGE_KEYS = new Set(FIELDS);

function validatePriority(value: unknown, evidence: string): value is Priority {
	if (typeof value !== "string" || !PRIORITIES.has(value)) return false;
	const patterns: Record<Priority, RegExp> = {
		HIGH: /\b(?:high\s+priority|urgent|critical|asap|as soon as possible|do this first|this must come first|this is important|that is important|it is important|it's important)\b|उच्च\s+प्राथमिकता|तत्काल/iu,
		MEDIUM: /\b(?:medium|normal)\s+priority\b|सामान्य\s+प्राथमिकता/iu,
		LOW: /\b(?:low\s+priority|can wait|not very important|not urgent|whenever convenient)\b|कम\s+प्राथमिकता/iu,
	};
	return patterns[value as Priority].test(evidence);
}

function priorityMayBeCleared(evidence: string): boolean {
	return /\b(?:clear|remove|unset)\s+(?:the\s+)?priority\b|priority\s+(?:is|to)\s+(?:none|unset)/iu.test(evidence);
}

function fieldMayBeCleared(field: ChangeField, evidence: string): boolean {
	if (field === "priority") return priorityMayBeCleared(evidence);
	const terms: Record<Exclude<ChangeField, "priority">, string> = {
		title: "title|task name|name",
		description: "description|notes",
		date_phrase: "date|deadline|due date",
		time_phrase: "time",
	};
	const fieldTerms = terms[field];
	return new RegExp(`\\b(?:clear|remove|unset|erase|delete|leave blank)\\s+(?:(?:the|my|its)\\s+)?(?:${fieldTerms})\\b|\\b(?:${fieldTerms})\\s+(?:is|to)\\s+(?:none|unset|blank|empty)\\b`, "iu").test(evidence);
}

function reminderDenied(text: string): boolean {
	return /\b(?:do\s+not|don't|dont|never)\s+remind\s+me\b|\breminder\s+(?:is\s+)?not\s+(?:needed|wanted)\b|(?:याद|yaad)\s+(?:मत|mat|नहीं|nahi)\s+(?:दिलाना|dilana|dila)/iu.test(text);
}

function reminderRequested(text: string): boolean {
	if (reminderDenied(text)) return false;
	return /\b(?:remind\s+me|set\s+(?:a\s+)?reminder|reminder\s+for|(?:do\s+not|don't|dont|never)\s+forget(?:\s+to)?)\b|याद\s+दिल|yaad\s+dila|(?:मत|mat)\s+(?:भूलना|bhoolna)|(?:भूलना|bhoolna)\s+(?:मत|mat)|याद\s+रखना|yaad\s+rakhna/iu.test(text);
}

function sentenceNumber(text: string, position: number): number {
	const protectedPeriods = Array.from(text.matchAll(/\b[ap]\.m\./giu), (match) => [match.index!, match.index! + match[0].length]);
	let number = 0;
	for (let index = 0; index < position; index++) {
		const ch = text[index];
		if ("!?;\n".includes(ch)) number++;
		else if (ch === "." && !protectedPeriods.some(([start, end]) => index >= start && index < end) && (index + 1 === text.length || /\s/u.test(text[index + 1]))) number++;
	}
	return number;
}

function propagateReminders(transcript: string, operations: Array<Record<string, unknown>>): Array<Record<string, unknown>> {
	const folded = transcript.toLowerCase();
	let cursor = 0;
	const located: Array<{ start: number; index: number }> = [];
	operations.forEach((operation, index) => {
		const evidence = operation.source_text as string;
		let start = folded.indexOf(evidence.toLowerCase(), cursor);
		if (start < 0) start = folded.indexOf(evidence.toLowerCase());
		if (start >= 0) {
			cursor = start + evidence.length;
			located.push({ start, index });
		}
	});
	const context = new Map<number, boolean>();
	const output = [...operations];
	for (const { start, index } of located.sort((a, b) => a.start - b.start)) {
		const operation = output[index];
		if (operation.type !== "ADD" && operation.type !== "UPDATE") continue;
		const sentence = sentenceNumber(transcript, start);
		if (operation.type !== "ADD") continue;
		if (operation.reminder_requested === true) context.set(sentence, true);
		else if (reminderDenied(operation.source_text as string)) context.set(sentence, false);
		else if (context.get(sentence) === true) output[index] = { ...operation, reminder_requested: true };
	}
	return output;
}

export function normalizeAndValidateModelResponse(raw: unknown, request: InterpretationRequest): InterpretationResponse {
	if (!isRecord(raw)) throw new ContractError("response_shape");
	exactKeys(raw, RAW_RESPONSE_KEYS, "response_fields", "$response");
	requiredKeys(raw, Array.from(RAW_RESPONSE_KEYS), "response_fields", "$response");
	for (const field of ["operations", "clarifications", "unsupported_items"] as const) {
		if (!Array.isArray(raw[field])) throw new ContractError("response_shape", { field_path: `$response.${field}`, error_type: "type_error", error_code: "invalid_type", expected_json_type: "array", actual_json_type: jsonValueType(raw[field]), error_count: 1 });
	}
	const rawOperations = raw.operations as unknown[];
	const rawClarifications = raw.clarifications as unknown[];
	const rawUnsupported = raw.unsupported_items as unknown[];
	if (!(["proposals", "needs_clarification", "unsupported", "partial"] as unknown[]).includes(raw.status)) throw new ContractError("response_status", { field_path: "$response.status", error_type: "enum_error", error_code: "invalid_enum", expected_json_type: "string", actual_json_type: jsonValueType(raw.status), error_count: 1 });

	const operations: Array<Record<string, unknown>> = [];
	for (const [index, item] of rawOperations.entries()) {
		if (!isRecord(item)) throw new ContractError("operation_shape");
		const operationPath = `$response.operations[${index}]`;
		exactKeys(item, RAW_OPERATION_KEYS, "operation_fields", operationPath);
		requiredKeys(item, Array.from(RAW_OPERATION_KEYS), "operation_fields", operationPath);
		if (!isRecord(item.changes)) throw new ContractError("change_shape", { field_path: `${operationPath}.changes`, error_type: "type_error", error_code: "invalid_type", expected_json_type: "object", actual_json_type: jsonValueType(item.changes), error_count: 1 });
		exactKeys(item.changes, RAW_CHANGE_KEYS, "change_fields", `${operationPath}.changes`);
		requiredKeys(item.changes, Array.from(RAW_CHANGE_KEYS), "change_fields", `${operationPath}.changes`);
		if (typeof item.type !== "string" || !TYPES.has(item.type as OperationType)) throw new ContractError("operation_type");
		if (!grounded(item.source_text, request.transcript, 1, 1000)) throw new ContractError("source_text_grounding");
		const evidence = item.source_text;
		const operationId = `op-${index + 1}`;
		const changesRaw = item.changes;
		let proposal: Record<string, unknown>;
		if (item.type === "ADD") {
			const title = titleSupportedBySource(item.title, evidence) ? (item.title as string).trim() : safeTitleFromEvidence(evidence);
			proposal = {
				type: "ADD", operation_id: operationId, source_text: evidence, confidence: null,
				title,
				description: item.description !== null && descriptionSupportedBySource(item.description, evidence) && boundedString(item.description, 2000) ? item.description : null,
				date_phrase: item.date_phrase === null ? datePhraseFromEvidence(evidence) : item.date_phrase,
				time_phrase: item.time_phrase === null ? null : item.time_phrase,
				priority: item.priority === null ? null : item.priority,
				reminder_requested: item.reminder_requested,
			};
			if (proposal.date_phrase !== null && !grounded(proposal.date_phrase, evidence, 0, 200)) throw new ContractError("date_phrase_grounding");
			if (proposal.time_phrase !== null && !grounded(proposal.time_phrase, evidence, 0, 100)) throw new ContractError("time_phrase_grounding");
			if (proposal.priority !== null && !validatePriority(proposal.priority, evidence)) throw new ContractError("priority_evidence");
			if (typeof item.reminder_requested !== "boolean" || (item.reminder_requested && !reminderRequested(evidence))) throw new ContractError("reminder_evidence");
			if (item.target_text !== null || !Array.isArray(item.change_fields) || item.change_fields.length || Object.values(changesRaw).some((v) => v !== null)) throw new ContractError("add_field_consistency");
			if (![null, undefined].includes(item.date_phrase as null | undefined) && (!boundedString(item.date_phrase, 200))) throw new ContractError("date_phrase_shape");
			if (![null, undefined].includes(item.time_phrase as null | undefined) && (!boundedString(item.time_phrase, 100))) throw new ContractError("time_phrase_shape");
		} else if (item.type === "UPDATE") {
			if (!grounded(item.target_text, evidence, 1, 500)) throw new ContractError("target_text_grounding");
			if (!Array.isArray(item.change_fields) || item.change_fields.length === 0 || new Set(item.change_fields).size !== item.change_fields.length) throw new ContractError("update_change_fields");
			const changeFields = item.change_fields as string[];
			const changes: Record<string, unknown> = { title: null, description: null, date_phrase: null, time_phrase: null, priority: null };
			for (const field of changeFields) {
				if (typeof field !== "string" || !FIELDS.includes(field as ChangeField)) throw new ContractError("update_change_fields");
				const value = changesRaw[field];
				if (value !== null && value !== undefined) {
					if (field === "priority") {
						if (!validatePriority(value, evidence)) throw new ContractError("priority_evidence");
					} else if (field === "description" ? !descriptionSupportedBySource(value, evidence) : !grounded(value, evidence, 1, field === "date_phrase" ? 200 : field === "time_phrase" ? 100 : 500)) throw new ContractError("update_value_grounding");
				} else if (!fieldMayBeCleared(field as ChangeField, evidence)) throw new ContractError("update_clear_evidence");
				changes[field] = field === "title" && typeof value === "string" ? value.trim() : value ?? null;
			}
			if (Object.entries(changesRaw).some(([field, value]) => !changeFields.includes(field) && value !== null)) throw new ContractError("update_change_fields");
			if ([item.title, item.description, item.date_phrase, item.time_phrase, item.priority].some((v) => v !== null)) throw new ContractError("update_field_consistency");
			if (item.reminder_requested !== false) throw new ContractError("update_reminder_consistency");
			proposal = { type: "UPDATE", operation_id: operationId, source_text: evidence, confidence: null, target_text: (item.target_text as string).trim(), changes };
		} else {
			if (!grounded(item.target_text, request.transcript, 1, 500)) throw new ContractError("target_text_grounding");
			if ([item.title, item.description, item.date_phrase, item.time_phrase, item.priority].some((v) => v !== null) || item.reminder_requested !== false || !Array.isArray(item.change_fields) || item.change_fields.length) throw new ContractError("terminal_operation_field_consistency");
			if (Object.values(changesRaw).some((v) => v !== null)) throw new ContractError("terminal_operation_field_consistency");
			proposal = { type: item.type, operation_id: operationId, source_text: evidence, confidence: null, target_text: (item.target_text as string).trim() };
		}
		operations.push(proposal);
	}

	const clarified = validateTextRecords(rawClarifications, "question", request.transcript, 500);
	const unsupported = validateTextRecords(rawUnsupported, "reason", request.transcript, 500);
	const finalOperations = propagateReminders(request.transcript, operations);
	const hasOps = finalOperations.length > 0, hasQuestions = clarified.length > 0, hasUnsupported = unsupported.length > 0;
	const status = raw.status as ResponseStatus;
	const validStatus = status === "proposals" ? hasOps && !hasQuestions && !hasUnsupported
		: status === "needs_clarification" ? hasQuestions && !hasOps && !hasUnsupported
		: status === "unsupported" ? hasUnsupported && !hasOps && !hasQuestions
		: hasOps && (hasQuestions || hasUnsupported);
	if (!validStatus) throw new ContractError("response_status", {
		field_path: "$response.status", error_type: "status_content_mismatch", error_code: "status_content_mismatch",
		expected_json_type: "string", actual_json_type: jsonValueType(raw.status), error_count: 1,
	});

	if (request.schema_version === "1") {
		for (const operation of finalOperations) {
			delete operation.priority;
			delete operation.reminder_requested;
			if (isRecord(operation.changes)) delete operation.changes.priority;
		}
	}
	return {
		schema_version: request.schema_version,
		request_id: request.request_id,
		status,
		operations: finalOperations,
		clarifications: clarified,
		unsupported_items: unsupported,
	};
}

function isNoInformationPlaceholder(value: unknown): boolean {
	return value === null || value === false || (typeof value === "string" && !value.trim())
		|| (Array.isArray(value) && value.length === 0) || (isRecord(value) && Object.keys(value).length === 0);
}

function defaultChanges(): Record<ChangeField, null> {
	return { title: null, description: null, date_phrase: null, time_phrase: null, priority: null };
}

function operationFieldError(index: number, type: unknown, fields: string[], code = "unexpected_fields"): ContractError {
	return new ContractError("operation_fields", {
		field_path: `$model_output.operations[${index}]`, error_type: code === "duplicate_operation" ? code : "extra_forbidden",
		error_code: code, unexpected_fields: fields, expected_json_type: "object", actual_json_type: "object", error_count: fields.length || 1,
		operation_type: typeof type === "string" && TYPES.has(type as OperationType) ? type : undefined,
	});
}

function normalizeOperationPlaceholders(raw: Record<string, unknown>, index: number): Record<string, unknown> {
	const operation = { ...raw };
	const type = operation.type;
	const path = `$model_output.operations[${index}]`;
	for (const key of Object.keys(operation)) {
		if (RAW_OPERATION_KEYS.has(key)) continue;
		if (isNoInformationPlaceholder(operation[key])) delete operation[key];
		else throw operationFieldError(index, type, [key]);
	}
	if (typeof type !== "string" || !TYPES.has(type as OperationType)) throw new ContractError("operation_type", {
		field_path: `${path}.type`, error_type: "enum_error", error_code: "invalid_enum",
		expected_json_type: "string", actual_json_type: jsonValueType(type), error_count: 1,
	});
	if (typeof operation.source_text !== "string") throw new ContractError("source_text_grounding", {
		field_path: `${path}.source_text`, error_type: "type_error", error_code: "invalid_type",
		expected_json_type: "string", actual_json_type: jsonValueType(operation.source_text), error_count: 1,
	});

	for (const field of ["title", "description", "target_text", "date_phrase", "time_phrase", "priority"] as const) {
		if (!(field in operation)) operation[field] = null;
		else if (operation[field] === "") operation[field] = null;
	}
	if (!("reminder_requested" in operation) || operation.reminder_requested === null) operation.reminder_requested = false;
	if (!("changes" in operation) || operation.changes === null || isNoInformationPlaceholder(operation.changes)) operation.changes = defaultChanges();
	if (!isRecord(operation.changes)) throw new ContractError("change_shape", {
		field_path: `${path}.changes`, error_type: "type_error", error_code: "invalid_type",
		expected_json_type: "object", actual_json_type: jsonValueType(operation.changes), error_count: 1,
	});
	const changes = { ...operation.changes };
	for (const key of Object.keys(changes)) {
		if ((FIELDS as readonly string[]).includes(key)) continue;
		if (isNoInformationPlaceholder(changes[key])) delete changes[key];
		else throw new ContractError("change_fields", {
			field_path: `${path}.changes`, error_type: "extra_forbidden", error_code: "unexpected_fields",
			unexpected_fields: [key], expected_json_type: "object", actual_json_type: "object", error_count: 1,
			operation_type: type,
		});
	}
	for (const field of FIELDS) {
		if (!(field in changes) || changes[field] === undefined || changes[field] === "") changes[field] = null;
	}
	operation.changes = changes;
	if (!("change_fields" in operation) || operation.change_fields === null || isNoInformationPlaceholder(operation.change_fields)) operation.change_fields = [];

	if (type === "UPDATE") {
		if (!Array.isArray(operation.change_fields) || operation.change_fields.some((field) => typeof field !== "string" || !(FIELDS as readonly string[]).includes(field))) throw new ContractError("update_change_fields", {
			field_path: `${path}.change_fields`, error_type: "type_error", error_code: "invalid_type",
			expected_json_type: "array", actual_json_type: jsonValueType(operation.change_fields), error_count: 1, operation_type: type,
		});
		const changeFields = [...operation.change_fields] as string[];
		for (const field of FIELDS) {
			const topLevel = operation[field];
			if (topLevel === null) continue;
			const nested = changes[field];
			if (nested !== null && nested !== topLevel) throw operationFieldError(index, type, [field], "update_field_conflict");
			changes[field] = topLevel;
			if (!changeFields.includes(field)) changeFields.push(field);
		}
		for (const field of FIELDS) {
			if (changes[field] !== null && !changeFields.includes(field)) changeFields.push(field);
		}
		operation.change_fields = changeFields;
		for (const field of FIELDS) operation[field] = null;
		if (operation.reminder_requested !== false) throw operationFieldError(index, type, ["reminder_requested"]);
	} else if (type === "ADD") {
		if (operation.target_text !== null) throw operationFieldError(index, type, ["target_text"]);
		if ((operation.change_fields as unknown[]).length > 0) throw operationFieldError(index, type, ["change_fields"]);
		if (Object.values(changes).some((value) => value !== null)) throw operationFieldError(index, type, ["changes"]);
	} else {
		const crossFields = ["title", "description", "date_phrase", "time_phrase", "priority"].filter((field) => operation[field] !== null);
		if (crossFields.length) throw operationFieldError(index, type, crossFields);
		if (operation.reminder_requested !== false) throw operationFieldError(index, type, ["reminder_requested"]);
		if ((operation.change_fields as unknown[]).length > 0) throw operationFieldError(index, type, ["change_fields"]);
		if (Object.values(changes).some((value) => value !== null)) throw operationFieldError(index, type, ["changes"]);
	}
	return operation;
}

export interface OperationNormalizationIssue {
	stage: string;
	validation: SafeValidationDetails;
}

function issueForOperation(error: ContractError, index: number, type: unknown): OperationNormalizationIssue {
	const validation = error.validation ?? {
		field_path: `$model_output.operations[${index}]`, error_type: "contract_validation", error_code: error.stage, error_count: 1,
	};
	return {
		stage: error.stage,
		validation: { ...validation, operation_type: typeof type === "string" && TYPES.has(type as OperationType) ? type : undefined },
	};
}

/** Converts model-only arrays into the public contract and isolates unsafe operations. */
export function normalizeAndValidateModelOutput(
	raw: unknown,
	request: InterpretationRequest,
	onIssue?: (issue: OperationNormalizationIssue) => void,
): InterpretationResponse {
	if (!isRecord(raw)) throw new ContractError("response_shape", { field_path: "$model_output", error_type: "type_error", error_code: "invalid_type", expected_json_type: "object", actual_json_type: jsonValueType(raw), error_count: 1 });
	exactKeys(raw, MODEL_OUTPUT_KEYS, "response_fields", "$model_output");
	requiredKeys(raw, Array.from(MODEL_OUTPUT_KEYS), "response_fields", "$model_output");
	for (const field of ["operations", "clarifications", "unsupported_items"] as const) {
		if (!Array.isArray(raw[field])) throw new ContractError("response_shape", { field_path: `$model_output.${field}`, error_type: "type_error", error_code: "invalid_type", expected_json_type: "array", actual_json_type: jsonValueType(raw[field]), error_count: 1 });
	}
	const rawOperations = raw.operations as unknown[];
	const rawClarifications = raw.clarifications as unknown[];
	const rawUnsupported = raw.unsupported_items as unknown[];
	const operations: Array<Record<string, unknown>> = [];
	const unsupported = [...validateTextRecords(rawUnsupported, "reason", request.transcript, 500)];
	const clarifications = [...validateTextRecords(rawClarifications, "question", request.transcript, 500)];
	const seen = new Set<string>();

	for (const [index, candidate] of rawOperations.entries()) {
		const type = isRecord(candidate) ? candidate.type : undefined;
		try {
			if (!isRecord(candidate)) throw new ContractError("operation_shape", {
				field_path: `$model_output.operations[${index}]`, error_type: "type_error", error_code: "invalid_type",
				expected_json_type: "object", actual_json_type: jsonValueType(candidate), error_count: 1,
			});
			const cleaned = normalizeOperationPlaceholders(candidate, index);
			const one = normalizeAndValidateModelResponse({ status: "proposals", operations: [cleaned], clarifications: [], unsupported_items: [] }, request);
			const normalized = one.operations[0];
			const identity = JSON.stringify([
				normalized.type, foldWhitespace(String(normalized.source_text ?? "")),
				foldWhitespace(String(normalized.title ?? "")), foldWhitespace(String(normalized.target_text ?? "")),
				foldWhitespace(String(normalized.date_phrase ?? "")), foldWhitespace(String(normalized.time_phrase ?? "")),
				isRecord(normalized.changes) ? FIELDS.map((field) => (normalized.changes as Record<string, unknown>)[field] ?? null) : null,
			]);
			if (seen.has(identity)) throw new ContractError("operation_fields", {
				field_path: `$model_output.operations[${index}]`, error_type: "duplicate_operation", error_code: "duplicate_operation", error_count: 1,
				operation_type: typeof type === "string" && TYPES.has(type as OperationType) ? type : undefined,
			});
			seen.add(identity);
			operations.push(normalized);
		} catch (caught) {
			const error = caught instanceof ContractError ? caught : new ContractError("operation_fields");
			const issue = issueForOperation(error, index, type);
			onIssue?.(issue);
			const evidence = isRecord(candidate) && grounded(candidate.source_text, request.transcript, 1, 1000) ? candidate.source_text : null;
			if (evidence !== null) unsupported.push({ source_text: evidence, reason: "This operation could not be safely interpreted and needs review." });
			else clarifications.push({ source_text: request.transcript, question: "Please restate the unclear task so it can be reviewed safely." });
		}
	}

	const finalOperations = propagateReminders(request.transcript, operations).map((operation, index) => ({ ...operation, operation_id: `op-${index + 1}` }));
	const hasOperations = finalOperations.length > 0;
	const hasClarifications = clarifications.length > 0;
	const hasUnsupported = unsupported.length > 0;
	const status: ResponseStatus | null = hasOperations
		? hasClarifications || hasUnsupported ? "partial" : "proposals"
		: hasClarifications && !hasUnsupported ? "needs_clarification"
			: hasUnsupported && !hasClarifications ? "unsupported" : null;
	if (status === null) throw new ContractError("response_status", {
		field_path: "$model_output", error_type: "empty_model_output", error_code: "empty_model_output",
		expected_json_type: "object", actual_json_type: "object", error_count: 1,
	});
	return {
		schema_version: request.schema_version,
		request_id: request.request_id,
		status,
		operations: finalOperations,
		clarifications,
		unsupported_items: unsupported,
	};
}

function validateTextRecords<K extends "question" | "reason">(
	value: unknown[],
	field: K,
	transcript: string,
	max: number,
): Array<{ source_text: string } & Record<K, string>> {
	return value.map((item) => {
		const stage = field === "question" ? "clarification_validation" : "unsupported_validation";
		if (!isRecord(item)) throw new ContractError(stage);
		exactKeys(item, new Set(["source_text", field]), stage);
		requiredKeys(item, ["source_text", field], stage);
		if (!grounded(item.source_text, transcript, 1, 1000) || !boundedString(item[field], max) || item[field].length < 1) throw new ContractError(field === "question" ? "clarification_grounding" : "unsupported_grounding");
		return { source_text: item.source_text, [field]: item[field] } as { source_text: string } & Record<K, string>;
	});
}

export function groqResponseSchema(): Record<string, unknown> {
	const nullableString = { type: ["string", "null"] };
	const nullablePriority = { type: ["string", "null"], enum: ["HIGH", "MEDIUM", "LOW", null] };
	const nullOnlyString = { type: ["string", "null"], enum: [null] };
	const nullOnlyPriority = { type: ["string", "null"], enum: [null] };
	const nullChanges = { title: nullOnlyString, description: nullOnlyString, date_phrase: nullOnlyString, time_phrase: nullOnlyString, priority: nullOnlyPriority };
	const changesSchema = {
		type: "object",
		properties: { title: nullableString, description: nullableString, date_phrase: nullableString, time_phrase: nullableString, priority: nullablePriority },
		required: ["title", "description", "date_phrase", "time_phrase", "priority"], additionalProperties: false,
	};
	const fieldList = { type: "array", items: { type: "string", enum: [...FIELDS] } };
	const common = { source_text: { type: "string" } };
	const operationBranch = (type: OperationType, properties: Record<string, unknown>) => {
		const all = { type: { type: "string", enum: [type] }, ...common, title: nullableString, description: nullableString,
			target_text: nullableString, date_phrase: nullableString, time_phrase: nullableString, priority: nullablePriority,
			reminder_requested: { type: "boolean" }, changes: changesSchema, change_fields: fieldList, ...properties };
		return { type: "object", properties: all, required: Object.keys(all), additionalProperties: false };
	};
	const operation = { anyOf: [
		operationBranch("ADD", { type: { type: "string", enum: ["ADD"] }, target_text: nullOnlyString, changes: { type: "object", properties: nullChanges, required: Object.keys(nullChanges), additionalProperties: false }, change_fields: fieldList }),
		operationBranch("UPDATE", { type: { type: "string", enum: ["UPDATE"] }, title: nullOnlyString, description: nullOnlyString, date_phrase: nullOnlyString, time_phrase: nullOnlyString, priority: nullOnlyPriority, reminder_requested: { type: "boolean", enum: [false] } }),
		...(["COMPLETE", "DELETE"] as const).map((type) => operationBranch(type, { title: nullOnlyString, description: nullOnlyString, target_text: nullableString, date_phrase: nullOnlyString, time_phrase: nullOnlyString, priority: nullOnlyPriority, reminder_requested: { type: "boolean", enum: [false] }, changes: { type: "object", properties: nullChanges, required: Object.keys(nullChanges), additionalProperties: false }, change_fields: fieldList })),
	] };
	return {
		type: "object",
		properties: {
			status: { type: "string", enum: ["proposals", "needs_clarification", "unsupported", "partial"] },
			operations: { type: "array", items: operation },
			clarifications: { type: "array", items: { type: "object", properties: { source_text: { type: "string" }, question: { type: "string" } }, required: ["source_text", "question"], additionalProperties: false } },
			unsupported_items: { type: "array", items: { type: "object", properties: { source_text: { type: "string" }, reason: { type: "string" } }, required: ["source_text", "reason"], additionalProperties: false } },
		},
		required: ["status", "operations", "clarifications", "unsupported_items"],
		additionalProperties: false,
	};
}
