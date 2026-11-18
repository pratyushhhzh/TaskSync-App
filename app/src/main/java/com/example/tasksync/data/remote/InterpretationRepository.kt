package com.example.tasksync.data.remote

import java.io.IOException
import java.time.Clock
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

internal data class HttpResult(val statusCode: Int, val body: String?)

internal fun interface InterpretationTransport {
    suspend fun post(url: String, authorization: String, body: String): HttpResult
}

internal class InterpretationException(
    message: String,
    val code: String? = null,
    val retryable: Boolean = true,
) : Exception(message)

internal class InterpretationRepository(
    private val baseUrl: String,
    private val backendToken: String,
    private val transport: InterpretationTransport = OkHttpInterpretationTransport(),
    private val json: Json = interpretationJson,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val requestId: () -> String = { UUID.randomUUID().toString() },
    private val networkSummary: (() -> String)? = null,
    private val debugLog: ((String) -> Unit)? = null,
) {
    suspend fun interpret(transcript: String, localeHint: String?): InterpretationResponse {
        if (transcript.isBlank()) throw InterpretationException("The confirmed transcript is empty.", retryable = false)
        if (baseUrl.isBlank()) throw InterpretationException("The interpretation server URL is not configured.", retryable = false)
        if (backendToken.isBlank()) throw InterpretationException("Backend authentication is not configured for this build.", retryable = false)

        val id = requestId()
        val now = clock.instant().atZone(clock.zone)
        val request = InterpretationRequest(
            schemaVersion = "1",
            requestId = id,
            transcript = transcript,
            localeHint = localeHint,
            referenceTime = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now),
            timeZone = clock.zone.id,
        )
        val body = json.encodeToString(request)
        debugLog?.invoke("Interpretation request host=${runCatching { java.net.URI(baseUrl).host }.getOrNull() ?: "invalid"}; network=${networkSummary?.invoke() ?: "unavailable"}")
        val result = try {
            transport.post(
                url = "${baseUrl.trimEnd('/')}/v1/interpretations",
                authorization = "Bearer $backendToken",
                body = body,
            )
        } catch (exception: IOException) {
            debugLog?.invoke("Interpretation transport failure: ${exception.javaClass.simpleName}")
            val message = if (exception is java.net.SocketTimeoutException) {
                "The interpretation request timed out. Check your connection and retry."
            } else {
                "Could not reach the interpretation server. Check that it is running and retry."
            }
            throw InterpretationException(message, retryable = true)
        }
        debugLog?.invoke("Interpretation HTTP status=${result.statusCode}")

        val responseBody = result.body?.takeIf { it.isNotBlank() }
            ?: throw InterpretationException("The interpretation server returned an empty response.", retryable = true)
        if (result.statusCode !in 200..299) {
            val error = runCatching { json.decodeFromString<BackendErrorResponse>(responseBody).error }.getOrNull()
            val friendly = when (error?.code) {
                "UNAUTHENTICATED" -> "Backend authentication failed. Check the local backend token."
                "INTERPRETER_NOT_CONFIGURED" -> error.message
                "RATE_LIMITED" -> "The interpretation service is busy. Please retry shortly."
                "UPSTREAM_TIMEOUT" -> "The interpretation service timed out. Please retry."
                "UPSTREAM_UNAVAILABLE" -> "The interpretation service is temporarily unavailable. Please retry."
                "INVALID_MODEL_RESPONSE" -> "The interpretation service returned a response that could not be reviewed. Please retry."
                "INVALID_REQUEST" -> "The interpretation request was rejected. Please close and try again."
                else -> "Interpretation failed (HTTP ${result.statusCode}). Please retry."
            }
            throw InterpretationException(friendly, error?.code, error?.retryable ?: (result.statusCode >= 500))
        }

        val response = try {
            json.decodeFromString<InterpretationResponse>(responseBody)
        } catch (exception: SerializationException) {
            throw InterpretationException("The interpretation server returned data the app could not read.", "INVALID_RESPONSE", false)
        }
        try {
            response.validateFor(id)
        } catch (exception: IllegalArgumentException) {
            throw InterpretationException("The interpretation server returned an inconsistent proposal response.", "INVALID_RESPONSE", false)
        }
        return response
    }
}

private class OkHttpInterpretationTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(55, TimeUnit.SECONDS)
        .build(),
) : InterpretationTransport {
    override suspend fun post(url: String, authorization: String, body: String): HttpResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authorization)
            .header("Accept", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { response ->
            HttpResult(response.code, response.body?.string())
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

internal val interpretationJson = Json {
    ignoreUnknownKeys = false
    explicitNulls = true
    classDiscriminator = "type"
}

internal fun deviceLocaleHint(): String = Locale.getDefault().toLanguageTag()

