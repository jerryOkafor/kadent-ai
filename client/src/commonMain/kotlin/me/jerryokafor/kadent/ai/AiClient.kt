package me.jerryokafor.kadent.ai

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Sends one Messages request to a model and returns its answer or why there is none. */
fun interface AiClient {
    suspend fun send(request: MessagesRequest): AiResult
}

sealed interface AiResult {
    data class Answered(val response: MessagesResponse) : AiResult

    /**
     * No answer. [message] is the function's, in words a person can read. [retryAfter] is set when
     * the function said how long to wait, and was longer than this client waits by itself.
     */
    data class Failed(val reason: Reason, val message: String, val retryAfter: Duration? = null) : AiResult

    enum class Reason {
        /** Nobody is signed in, or the account's token was refused twice. Sign-in fixes it. */
        SIGNED_OUT,

        /** The function refused the model. A bug in the caller, not something a person can fix. */
        NOT_ALLOWED,

        /** The request was wrong: a bad field, a schema the model refused. Also the caller's bug. */
        REJECTED,

        /** Too many photos or pages in one request. */
        TOO_LARGE,

        /** The person's quota or the model's rate limit, longer than this client waits. */
        RATE_LIMITED,

        /** The model or the function could not be reached, after the client's retries. */
        UNAVAILABLE,

        /** The model took longer than the function waits. A smaller request may get through. */
        TIMEOUT,
    }
}

/**
 * Calls Kadent's `ai` Cloud Function, which holds the model key (functions/ in this repo). The phone
 * sends its Firebase ID token in the key's place.
 *
 * Retries what can change by waiting: Meta's rate limit, the model being overloaded, the network.
 * Three attempts, waiting 1 s then 2 s, or the `Retry-After` the function sent when that is no longer
 * than [maxWait]; a longer wait is handed back as [AiResult.Failed.retryAfter] rather than spent here,
 * because a person is usually looking at a spinner. Nothing else is retried: a refusal that cannot
 * change by waiting answers the same the second time.
 *
 * @param http the app's client, with its own engine per platform. This library names none.
 * @param idToken the signed-in person's Firebase ID token, or null when nobody is signed in.
 *   Called with `true` after the function refused the token, to force a fresh one.
 */
class FunctionAiClient(
    private val http: HttpClient,
    private val endpoint: String,
    private val idToken: suspend (forceRefresh: Boolean) -> String?,
    private val maxAttempts: Int = 3,
    private val maxWait: Duration = 10.seconds,
) : AiClient {

    override suspend fun send(request: MessagesRequest): AiResult {
        val body = AiJson.encodeToString(MessagesRequest.serializer(), request)
        var token = idToken(false) ?: return signedOut()
        var refreshed = false
        var attempt = 1
        while (true) {
            val response = try {
                http.post(endpoint) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (attempt >= maxAttempts) return AiResult.Failed(AiResult.Reason.UNAVAILABLE, "Couldn't reach Kadent's AI. Check the connection.")
                delay(backoff(attempt++))
                continue
            }

            when (val status = response.status.value) {
                200 -> return answered(response)
                401 -> {
                    // An ID token lasts an hour; one that expired between the read and the call is
                    // refused once, and a fresh one fixes it. A second refusal is the account.
                    if (refreshed) return signedOut()
                    refreshed = true
                    token = idToken(true) ?: return signedOut()
                }
                429, 503 -> {
                    val reason = if (status == 429) AiResult.Reason.RATE_LIMITED else AiResult.Reason.UNAVAILABLE
                    val retryAfter = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.seconds
                    if (retryAfter != null && retryAfter > maxWait) return failed(response, reason, retryAfter)
                    if (attempt >= maxAttempts) return failed(response, reason, retryAfter)
                    delay(retryAfter ?: backoff(attempt))
                    attempt++
                }
                else -> return failed(response, reasonFor(status), retryAfter = null)
            }
        }
    }

    private suspend fun answered(response: HttpResponse): AiResult = try {
        AiResult.Answered(AiJson.decodeFromString(MessagesResponse.serializer(), response.bodyAsText()))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (unreadable: Exception) {
        AiResult.Failed(AiResult.Reason.UNAVAILABLE, "Kadent's AI sent an answer it couldn't read.")
    }

    private suspend fun failed(response: HttpResponse, reason: AiResult.Reason, retryAfter: Duration?): AiResult.Failed {
        // The function's refusals are {"error": {"type", "message"}}. Anything else, such as a proxy's
        // HTML page, keeps the reason and says so in general words.
        val message = try {
            AiJson.parseToJsonElement(response.bodyAsText()).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        return AiResult.Failed(reason, message ?: "Kadent's AI is unavailable right now.", retryAfter)
    }

    private fun signedOut() = AiResult.Failed(AiResult.Reason.SIGNED_OUT, "Sign in to use AI features.")

    private fun backoff(attempt: Int): Duration = (1L shl (attempt - 1)).seconds

    private fun reasonFor(status: Int) = when (status) {
        403 -> AiResult.Reason.NOT_ALLOWED
        413 -> AiResult.Reason.TOO_LARGE
        504 -> AiResult.Reason.TIMEOUT
        in 400..499 -> AiResult.Reason.REJECTED
        // 502 is Kadent's key or model access broken at Meta; waiting does not fix it.
        else -> AiResult.Reason.UNAVAILABLE
    }
}
