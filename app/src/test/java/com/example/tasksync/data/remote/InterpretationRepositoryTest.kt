package com.example.tasksync.data.remote

import java.net.SocketTimeoutException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterpretationRepositoryTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val clock = Clock.fixed(Instant.parse("2026-11-18T10:15:30Z"), zone)

    @Test fun requestUsesExactTranscriptAndWorkerContractWithoutBearerAuthentication() = runBlocking {
        val transcript = "  Remind me: kal 5 baje call the doctor.  "
        val transport = FakeTransport(success(response(operations = listOf(add("op-1")))))
        repository(transport).interpret(transcript, "hi-IN")
        val sent = Json.decodeFromString<InterpretationRequest>(transport.body!!)
        assertEquals("2", sent.schemaVersion)
        assertEquals("request-test", sent.requestId)
        assertEquals(transcript, sent.transcript)
        assertEquals("hi-IN", sent.localeHint)
        assertEquals("2026-11-18T15:45:30+05:30", sent.referenceTime)
        assertEquals("Asia/Kolkata", sent.timeZone)
        assertEquals("https://cloudflare-worker.work-spaceeeee2.workers.dev/v1/interpretations", transport.url)
    }

    @Test fun decodesFiveAndEightSeparateOperations() = runBlocking {
        for (count in listOf(5, 8)) {
            val proposals = (1..count).map { add("op-$it") }
            val transport = FakeTransport(success(response(operations = proposals)))
            val result = repository(transport).interpret("Add $count tasks", "en-IN")
            assertEquals(count, result.operations.size)
            assertEquals(count, result.operations.map { it.operationId }.distinct().size)
        }
    }

    @Test fun mixedOperationsAndProposalFieldsDeserialize() = runBlocking {
        val mixed = listOf(
            add("op-add"),
            UpdateProposal("op-update", "move the review", null, "quarterly review", UpdateChanges(title = "annual review", datePhrase = "next Friday")),
            CompleteProposal("op-complete", "finish the draft", null, "draft"),
            DeleteProposal("op-delete", "remove old reminder", null, "old reminder"),
        )
        val result = repository(FakeTransport(success(response(operations = mixed))))
            .interpret("Add, update, finish, and delete", "en-US")
        assertTrue(result.operations[0] is AddProposal)
        assertTrue(result.operations[1] is UpdateProposal)
        assertTrue(result.operations[2] is CompleteProposal)
        assertTrue(result.operations[3] is DeleteProposal)
        assertEquals("next Friday", (result.operations[1] as UpdateProposal).changes.datePhrase)
    }

    @Test fun explicitPriorityAndReminderIntentDeserialize() = runBlocking {
        val proposed = AddProposal(
            "op-add", "Remind me to call the friend", null, "call the friend",
            datePhrase = "tomorrow", timePhrase = "10 AM", priority = TaskPriority.HIGH, reminderRequested = true,
        )
        val result = repository(FakeTransport(success(response(operations = listOf(proposed)))))
            .interpret("Remind me to call the friend tomorrow at 10 AM", "en-IN")
        val add = result.operations.single() as AddProposal
        assertEquals(TaskPriority.HIGH, add.priority)
        assertTrue(add.reminderRequested)
    }

    @Test fun clarificationsAndPartialInterpretationArePreserved() = runBlocking {
        val partial = response(
            status = InterpretationStatus.PARTIAL,
            operations = listOf(add("op-1")),
            clarifications = listOf(Clarification("move it", "Which task should be moved?")),
            unsupported = listOf(UnsupportedItem("make the app faster", "Not a task operation.")),
        )
        val result = repository(FakeTransport(success(partial))).interpret("transcript", null)
        assertEquals(1, result.operations.size)
        assertEquals("Which task should be moved?", result.clarifications.single().question)
        assertEquals("make the app faster", result.unsupportedItems.single().sourceText)
        assertEquals(InterpretationStatus.PARTIAL, result.status)
    }

    @Test fun backendErrorEnvelopeIsPresentedWithoutLosingRetryability() = runBlocking {
        val errorBody = """{"error":{"code":"UPSTREAM_TIMEOUT","message":"timed out","retryable":true,"request_id":"request-test"}}"""
        val log = mutableListOf<String>()
        val error = runCatching {
            repository(FakeTransport(HttpResult(504, errorBody)), debugLog = log::add).interpret("text", null)
        }.exceptionOrNull()
        assertTrue(error is InterpretationException)
        assertTrue((error as InterpretationException).retryable)
        assertTrue(error.message!!.contains("timed out", ignoreCase = true))
        assertTrue(log.contains("Interpretation HTTP status=504"))
        assertTrue(log.contains("Interpretation backend error code=UPSTREAM_TIMEOUT"))
        assertFalse(log.joinToString("\n").contains("timed out"))
    }

    @Test fun providerRequestRejectionUsesMeaningfulNonRetryableMessage() = runBlocking {
        val errorBody = """{"error":{"code":"UPSTREAM_REJECTED","message":"provider rejected","retryable":false,"request_id":"request-test"}}"""
        val error = runCatching {
            repository(FakeTransport(HttpResult(502, errorBody))).interpret("exact transcript", null)
        }.exceptionOrNull()
        assertTrue(error is InterpretationException)
        assertEquals("UPSTREAM_REJECTED", (error as InterpretationException).code)
        assertFalse(error.retryable)
        assertTrue(error.message!!.contains("provider rejected this request"))
    }

    @Test fun emptyResponseIsAReadableFailure() = runBlocking {
        val error = runCatching { repository(FakeTransport(HttpResult(200, "  "))).interpret("text", null) }.exceptionOrNull()
        assertTrue(error is InterpretationException)
        assertTrue(error!!.message!!.contains("empty response"))
    }

    @Test fun timeoutIsRetryableAndExplained() = runBlocking {
        val transport = object : InterpretationTransport {
            override suspend fun post(url: String, body: String): HttpResult = throw SocketTimeoutException()
        }
        val error = runCatching { repository(transport).interpret("text", null) }.exceptionOrNull()
        assertTrue(error is InterpretationException)
        assertTrue((error as InterpretationException).retryable)
        assertTrue(error.message!!.contains("timed out"))
    }

    @Test fun debugNetworkDiagnosticsExcludeTranscript() = runBlocking {
        val log = mutableListOf<String>()
        val transcript = "private voice transcript"
        val repository = InterpretationRepository(
            baseUrl = "https://cloudflare-worker.work-spaceeeee2.workers.dev/",
            transport = FakeTransport(success(response(operations = listOf(add("op-1"))))),
            clock = clock,
            requestId = { "request-test" },
            networkSummary = { "wifi; internet=true; validated=false" },
            debugLog = log::add,
        )
        repository.interpret(transcript, "en-IN")
        val combined = log.joinToString("\n")
        assertTrue(combined.contains("10.11.60.1"))
        assertTrue(combined.contains("wifi"))
        assertTrue(combined.contains("HTTP status=200"))
        assertFalse(combined.contains(transcript))
    }

    @Test fun backendResponseMustMatchRequestAndStatusContents() = runBlocking {
        val wrongRequest = response(requestId = "other", operations = listOf(add("op-1")))
        val error = runCatching { repository(FakeTransport(success(wrongRequest))).interpret("text", null) }.exceptionOrNull()
        assertTrue(error is InterpretationException)
        assertFalse((error as InterpretationException).retryable)
    }

    @Test fun retryCanResendTheExactConfirmedTranscriptAndSucceedAfterFailure() = runBlocking {
        val transcript = "  Remind me to call the dentist tomorrow.  "
        val transport = SequenceTransport(
            HttpResult(200, "{malformed"),
            success(response(operations = listOf(add("op-retry")))),
        )
        val repository = repository(transport)
        val first = runCatching { repository.interpret(transcript, "en-IN") }.exceptionOrNull()
        assertTrue(first is InterpretationException)
        val result = repository.interpret(transcript, "en-IN")
        assertEquals(listOf(transcript, transcript), transport.transcripts)
        assertEquals("op-retry", result.operations.single().operationId)
    }

    @Test fun consecutiveInterpretationFailuresKeepRetryAvailableForSameTranscript() = runBlocking {
        val transcript = "keep this exact transcript"
        val transport = SequenceTransport(HttpResult(200, ""), HttpResult(503, ""), success(response(operations = listOf(add("op-later")))))
        val repository = repository(transport)
        repeat(2) { assertTrue(runCatching { repository.interpret(transcript, "en-IN") }.exceptionOrNull() is InterpretationException) }
        assertEquals(listOf(transcript, transcript), transport.transcripts)
        assertEquals("op-later", repository.interpret(transcript, "en-IN").operations.single().operationId)
        assertEquals(listOf(transcript, transcript, transcript), transport.transcripts)
    }

    private fun repository(transport: InterpretationTransport, debugLog: ((String) -> Unit)? = null) = InterpretationRepository(
        baseUrl = "https://cloudflare-worker.work-spaceeeee2.workers.dev/",
        transport = transport,
        clock = clock,
        requestId = { "request-test" },
        debugLog = debugLog,
    )

    private fun success(value: InterpretationResponse) = HttpResult(200, interpretationJson.encodeToString(value))

    private fun response(
        requestId: String = "request-test",
        status: InterpretationStatus = InterpretationStatus.PROPOSALS,
        operations: List<ProposedOperation>,
        clarifications: List<Clarification> = emptyList(),
        unsupported: List<UnsupportedItem> = emptyList(),
    ) = InterpretationResponse("1", requestId, status, operations, clarifications, unsupported)

    private fun add(id: String) = AddProposal(id, "add task $id", null, "Task $id")

    private class FakeTransport(private val result: HttpResult) : InterpretationTransport {
        var url: String? = null
        var body: String? = null
        override suspend fun post(url: String, body: String): HttpResult {
            this.url = url
            this.body = body
            return result
        }
    }

    private class SequenceTransport(vararg results: HttpResult) : InterpretationTransport {
        private val remaining = ArrayDeque(results.toList())
        val transcripts = mutableListOf<String>()
        override suspend fun post(url: String, body: String): HttpResult {
            transcripts += Json.decodeFromString<InterpretationRequest>(body).transcript
            return remaining.removeFirst()
        }
    }
}
