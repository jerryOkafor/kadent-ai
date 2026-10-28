package me.jerryokafor.kadent.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

class FunctionAiClientTest {
    private val endpoint = "https://example.test/ai"

    private val request = MessagesRequest(
        model = Models.SPARK,
        maxTokens = 64,
        messages = listOf(Message.user(ContentBlock.Text("hello"))),
    )

    private val answer = """
        {"id": "msg_1", "type": "message", "role": "assistant", "model": "muse-spark-1.3",
         "stop_reason": "end_turn", "content": [{"type": "text", "text": "hi"}],
         "usage": {"input_tokens": 3, "output_tokens": 1}}
    """

    /** A client over a function that answers each call with the next of [responses]. */
    private class Harness(
        responses: List<MockRequestHandleScope.(HttpRequestData) -> HttpResponseData>,
        val tokens: MutableList<String?> = mutableListOf("token-1"),
    ) {
        val seen = mutableListOf<HttpRequestData>()
        val tokenAsks = mutableListOf<Boolean>()
        private val queue = ArrayDeque(responses)
        val engine = MockEngine { call ->
            seen += call
            queue.removeFirst()(this, call)
        }

        fun client(endpoint: String) = FunctionAiClient(
            http = HttpClient(engine),
            endpoint = endpoint,
            idToken = { forceRefresh ->
                tokenAsks += forceRefresh
                if (tokens.size > 1) tokens.removeFirst() else tokens.first()
            },
        )
    }

    private fun ok(body: String): MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = {
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private fun failure(status: Int, type: String, message: String, retryAfter: String? = null): MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = {
        val headers = buildList {
            add(HttpHeaders.ContentType to listOf("application/json"))
            if (retryAfter != null) add(HttpHeaders.RetryAfter to listOf(retryAfter))
        }
        respond("""{"error": {"type": "$type", "message": "$message"}}""", HttpStatusCode.fromValue(status), headersOf(*headers.toTypedArray()))
    }

    @Test
    fun aCallPostsTheRequestWithThePersonsIdToken() = runTest {
        val harness = Harness(listOf(ok(answer)))

        val result = harness.client(endpoint).send(request)

        assertEquals("hi", assertIs<AiResult.Answered>(result).response.text)
        val call = harness.seen.single()
        assertEquals(HttpMethod.Post, call.method)
        assertEquals(endpoint, call.url.toString())
        assertEquals("Bearer token-1", call.headers[HttpHeaders.Authorization])
        assertEquals(
            AiJson.encodeToJsonElement(MessagesRequest.serializer(), request),
            AiJson.parseToJsonElement(call.body.toByteArray().decodeToString()),
        )
    }

    @Test
    fun withNoSignedInPersonNothingIsSent() = runTest {
        val harness = Harness(emptyList(), tokens = mutableListOf(null))

        val result = harness.client(endpoint).send(request)

        assertEquals(AiResult.Reason.SIGNED_OUT, assertIs<AiResult.Failed>(result).reason)
        assertEquals(0, harness.seen.size)
    }

    @Test
    fun aRefusedTokenIsRefreshedOnceAndTheCallRepeated() = runTest {
        val harness = Harness(
            listOf(failure(401, "unauthenticated", "Sign in again."), ok(answer)),
            tokens = mutableListOf("expired", "fresh"),
        )

        val result = harness.client(endpoint).send(request)

        assertIs<AiResult.Answered>(result)
        assertEquals(listOf(false, true), harness.tokenAsks)
        assertEquals("Bearer fresh", harness.seen[1].headers[HttpHeaders.Authorization])
    }

    @Test
    fun aTokenRefusedTwiceMeansSignedOut() = runTest {
        val harness = Harness(listOf(failure(401, "unauthenticated", "no"), failure(401, "unauthenticated", "no")))

        val result = harness.client(endpoint).send(request)

        assertEquals(AiResult.Reason.SIGNED_OUT, assertIs<AiResult.Failed>(result).reason)
        assertEquals(2, harness.seen.size)
    }

    @Test
    fun aShortRateLimitIsWaitedOutAndTheCallRepeated() = runTest {
        val harness = Harness(listOf(failure(429, "rate_limited", "busy", retryAfter = "2"), ok(answer)))

        val result = harness.client(endpoint).send(request)

        assertIs<AiResult.Answered>(result)
        assertEquals(2_000, currentTime)
    }

    @Test
    fun aLongRateLimitIsReportedWithoutWaiting() = runTest {
        val harness = Harness(listOf(failure(429, "rate_limited", "busy", retryAfter = "60")))

        val result = harness.client(endpoint).send(request)

        val failed = assertIs<AiResult.Failed>(result)
        assertEquals(AiResult.Reason.RATE_LIMITED, failed.reason)
        assertEquals(60.seconds, failed.retryAfter)
        assertEquals(0, currentTime)
        assertEquals(1, harness.seen.size)
    }

    @Test
    fun anUnavailableModelIsTriedThreeTimesWithGrowingWaits() = runTest {
        val busy = failure(503, "unavailable", "busy")
        val harness = Harness(listOf(busy, busy, busy))

        val result = harness.client(endpoint).send(request)

        assertEquals(AiResult.Reason.UNAVAILABLE, assertIs<AiResult.Failed>(result).reason)
        assertEquals(3, harness.seen.size)
        assertEquals(3_000, currentTime, "1 s, then 2 s")
    }

    @Test
    fun aNetworkFailureIsRetriedLikeAnUnavailableModel() = runTest {
        val harness = Harness(listOf({ throw java.io.IOException("offline") }, ok(answer)))

        assertIs<AiResult.Answered>(harness.client(endpoint).send(request))
    }

    @Test
    fun refusalsThatCannotChangeAreNotRetried() = runTest {
        val cases = mapOf(
            failure(400, "invalid_request", "max_tokens too large") to AiResult.Reason.REJECTED,
            failure(403, "model_not_allowed", "not a model Kadent uses") to AiResult.Reason.NOT_ALLOWED,
            failure(413, "payload_too_large", "too big") to AiResult.Reason.TOO_LARGE,
            failure(502, "upstream_unavailable", "unavailable") to AiResult.Reason.UNAVAILABLE,
            failure(504, "timeout", "too slow") to AiResult.Reason.TIMEOUT,
        )
        for ((response, reason) in cases) {
            val harness = Harness(listOf(response))
            val failed = assertIs<AiResult.Failed>(harness.client(endpoint).send(request))
            assertEquals(reason, failed.reason)
            assertEquals(1, harness.seen.size, "$reason is not retried")
        }
    }

    @Test
    fun aRefusalCarriesTheFunctionsMessage() = runTest {
        val harness = Harness(listOf(failure(400, "invalid_request", "max_tokens too large")))

        val failed = assertIs<AiResult.Failed>(harness.client(endpoint).send(request))

        assertEquals("max_tokens too large", failed.message)
    }
}
