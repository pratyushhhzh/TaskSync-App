package com.example.tasksync.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class InterpretationRequest(
    @SerialName("schema_version") val schemaVersion: String,
    @SerialName("request_id") val requestId: String,
    val transcript: String,
    @SerialName("locale_hint") val localeHint: String? = null,
    @SerialName("reference_time") val referenceTime: String,
    @SerialName("time_zone") val timeZone: String,
)

@Serializable
data class InterpretationResponse(
    @SerialName("schema_version") val schemaVersion: String,
    @SerialName("request_id") val requestId: String,
    val status: InterpretationStatus,
    val operations: List<ProposedOperation>,
    val clarifications: List<Clarification>,
    @SerialName("unsupported_items") val unsupportedItems: List<UnsupportedItem>,
) {
    fun validateFor(requestId: String) {
        require(schemaVersion == "1") { "The backend returned an unsupported response version." }
        require(this.requestId == requestId) { "The backend response did not match this request." }
        val hasOperations = operations.isNotEmpty()
        val hasClarifications = clarifications.isNotEmpty()
        val hasUnsupported = unsupportedItems.isNotEmpty()
        val valid = when (status) {
            InterpretationStatus.PROPOSALS -> hasOperations && !hasClarifications && !hasUnsupported
            InterpretationStatus.NEEDS_CLARIFICATION -> hasClarifications && !hasOperations && !hasUnsupported
            InterpretationStatus.UNSUPPORTED -> hasUnsupported && !hasOperations && !hasClarifications
            InterpretationStatus.PARTIAL -> hasOperations && (hasClarifications || hasUnsupported)
        }
        require(valid) { "The backend returned an inconsistent interpretation response." }
        require(operations.map { it.operationId }.distinct().size == operations.size) {
            "The backend returned duplicate proposal identifiers."
        }
    }
}

@Serializable
enum class InterpretationStatus {
    @SerialName("proposals") PROPOSALS,
    @SerialName("needs_clarification") NEEDS_CLARIFICATION,
    @SerialName("unsupported") UNSUPPORTED,
    @SerialName("partial") PARTIAL,
}

@Serializable
sealed class ProposedOperation {
    @SerialName("operation_id") abstract val operationId: String
    @SerialName("source_text") abstract val sourceText: String
    abstract val confidence: Double?
}

@Serializable
@SerialName("ADD")
data class AddProposal(
    @SerialName("operation_id") override val operationId: String,
    @SerialName("source_text") override val sourceText: String,
    override val confidence: Double? = null,
    val title: String,
    val description: String? = null,
    @SerialName("date_phrase") val datePhrase: String? = null,
    @SerialName("time_phrase") val timePhrase: String? = null,
) : ProposedOperation()

@Serializable
@SerialName("UPDATE")
data class UpdateProposal(
    @SerialName("operation_id") override val operationId: String,
    @SerialName("source_text") override val sourceText: String,
    override val confidence: Double? = null,
    @SerialName("target_text") val targetText: String,
    val changes: UpdateChanges,
) : ProposedOperation()

@Serializable
data class UpdateChanges(
    val title: String? = null,
    val description: String? = null,
    @SerialName("date_phrase") val datePhrase: String? = null,
    @SerialName("time_phrase") val timePhrase: String? = null,
)

@Serializable
@SerialName("COMPLETE")
data class CompleteProposal(
    @SerialName("operation_id") override val operationId: String,
    @SerialName("source_text") override val sourceText: String,
    override val confidence: Double? = null,
    @SerialName("target_text") val targetText: String,
) : ProposedOperation()

@Serializable
@SerialName("DELETE")
data class DeleteProposal(
    @SerialName("operation_id") override val operationId: String,
    @SerialName("source_text") override val sourceText: String,
    override val confidence: Double? = null,
    @SerialName("target_text") val targetText: String,
) : ProposedOperation()

@Serializable
data class Clarification(
    @SerialName("source_text") val sourceText: String,
    val question: String,
)

@Serializable
data class UnsupportedItem(
    @SerialName("source_text") val sourceText: String,
    val reason: String,
)

@Serializable
data class BackendErrorResponse(val error: BackendErrorDetail)

@Serializable
data class BackendErrorDetail(
    val code: String,
    val message: String,
    val retryable: Boolean,
    @SerialName("request_id") val requestId: String? = null,
)
