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

    @Test fun requestUsesExactTranscriptAndBackendContract() = runBlocking {
        val transcript = "  Remind me: kal 5 baje call the doctor.  "
        val transport = FakeTransport(success(response(operations = listOf(add("op-1")))))
        repository(transport).interpret(transcript, "hi-IN")
        val sent = Json.decodeFromString<InterpretationRequest>(transport.body!!)
        assertEquals("1", sent.schemaVersion)
        assertEquals("request-test", sent.requestId)
        assertEquals(transcript, sent.transcript)
        assertEquals("hi-IN", sent.localeHint)
        assertEquals("2026-11-18T15:45:30+05:30", sent.referenceTime)
        assertEquals("Asia/Kolkata", sent.timeZone)
        assertEquals("http://10.42.138.1:8000/v1/interpretations", transport.url)
        assertEquals("Bearer local-token", transport.authorization)
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
        val error = runCatching { repository(FakeTransport(HttpResult(504, errorBody))).interpret("text", null) }.exceptionOrNull()
        assertTrue(error is InterpretationException)
        assertTrue((error as InterpretationException).retryable)
        assertTrue(error.message!!.contains("timed out", ignoreCase = true))
    }

    @Test fun emptyResponseIsAReadableFailure() = runBlocking {
        val error = runCatching { repository(FakeTransport(HttpResult(200, "  "))).interpret("text", null) }.exceptionOrNull()
        assertTrue(error is InterpretationException)
        assertTrue(error!!.message!!.contains("empty response"))
    }

    @Test fun timeoutIsRetryableAndExplained() = runBlocking {
        val transport = object : InterpretationTransport {
            override suspend fun post(url: String, authorization: String, body: String): HttpResult = throw SocketTimeoutException()
        }
        val error = runCatching { repository(transport).interpret("text", null) }.exceptionOrNull()
        assertTrue(error is InterpretationException)
        assertTrue((error as InterpretationException).retryable)
        assertTrue(error.message!!.contains("timed out"))
    }

    @Test fun debugNetworkDiagnosticsExcludeTranscriptAndCredentials() = runBlocking {
        val log = mutableListOf<String>()
        val transcript = "private voice transcript"
        val token = "sensitive-local-token"
        val repository = InterpretationRepository(
            baseUrl = "http://10.42.138.1:8000/",
            backendToken = token,
            transport = FakeTransport(success(response(operations = listOf(add("op-1"))))),
            clock = clock,
            requestId = { "request-test" },
            networkSummary = { "wifi; internet=true; validated=false" },
            debugLog = log::add,
        )
        repository.interpret(transcript, "en-IN")
        val combined = log.joinToString("\n")
        assertTrue(combined.contains("10.42.138.1"))
        assertTrue(combined.contains("wifi"))
        assertTrue(combined.contains("HTTP status=200"))
        assertFalse(combined.contains(transcript))
        assertFalse(combined.contains(token))
    }

    @Test fun backendResponseMustMatchRequestAndStatusContents() = runBlocking {
        val wrongRequest = response(requestId = "other", operations = listOf(add("op-1")))
        val error = runCatching { repository(FakeTransport(success(wrongRequest))).interpret("text", null) }.exceptionOrNull()
        assertTrue(error is InterpretationException)
        assertFalse((error as InterpretationException).retryable)
    }

    private fun repository(transport: InterpretationTransport) = InterpretationRepository(
        baseUrl = "http://10.42.138.1:8000/",
        backendToken = "local-token",
        transport = transport,
        clock = clock,
        requestId = { "request-test" },
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
        var authorization: String? = null
        var body: String? = null
        override suspend fun post(url: String, authorization: String, body: String): HttpResult {
            this.url = url
            this.authorization = authorization
            this.body = body
            return result
        }
    }
}
