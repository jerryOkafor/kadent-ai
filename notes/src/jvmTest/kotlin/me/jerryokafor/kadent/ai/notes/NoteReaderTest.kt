package me.jerryokafor.kadent.ai.notes

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.jerryokafor.kadent.ai.AiClient
import me.jerryokafor.kadent.ai.AiResult
import me.jerryokafor.kadent.ai.ContentBlock
import me.jerryokafor.kadent.ai.Effort
import me.jerryokafor.kadent.ai.MessagesRequest
import me.jerryokafor.kadent.ai.MessagesResponse
import me.jerryokafor.kadent.ai.Models
import me.jerryokafor.kadent.ai.StopReason
import me.jerryokafor.kadent.ai.Usage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NoteReaderTest {

    private class FakeAi(
        val text: String = "",
        val stopReason: String = StopReason.END_TURN,
        val failure: AiResult.Failed? = null,
    ) : AiClient {
        val requests = mutableListOf<MessagesRequest>()
        override suspend fun send(request: MessagesRequest): AiResult {
            requests += request
            return failure ?: AiResult.Answered(
                MessagesResponse(
                    id = "msg_1",
                    model = Models.SPARK,
                    content = listOf(ContentBlock.Text(text)),
                    stopReason = stopReason,
                    usage = Usage(200, 40),
                ),
            )
        }
    }

    /** The issue's note, read from the log's sheet: no envelope chosen, so three are sent. */
    private val note = "dinner at the Keg with Sam, 86 plus 15% tip, yesterday"
    private val context = NoteContext(
        today = "2026-10-29",
        income = false,
        envelopes = listOf(NoteEnvelope("groceries", "Groceries"), NoteEnvelope("eating-out", "Eating out"), NoteEnvelope("vehicle", "Vehicle maintenance")),
    )

    private fun answer(cents: Long = 9_890, day: String = "2026-10-28", description: String = "The Keg", envelope: String? = "\"eating-out\"") =
        """{"amount_cents": $cents, "day": "$day", "description": "$description", "envelope_id": $envelope}"""

    private suspend fun read(text: String, on: NoteContext = context) = NoteReader(FakeAi(text)).read(note, on)

    /** The issue's acceptance, as the model would answer it. */
    @Test
    fun theIssuesNoteIsReadIntoOneLine() = runTest {
        val reading = assertIs<NoteOutcome.Read>(read(answer())).reading
        assertEquals(NoteReading(9_890, "2026-10-28", "The Keg", "eating-out"), reading)
    }

    @Test
    fun theRequestSendsTheNoteTodayAndTheEnvelopesWithNoTools() = runTest {
        val ai = FakeAi(answer())
        NoteReader(ai).read(note, context)
        val request = ai.requests.single()
        val asked = (request.messages.single().content.single() as ContentBlock.Text).text
        assertTrue(note in asked && "Today is 2026-10-29." in asked && "Eating out" in asked, asked)
        assertEquals(null, request.tools)
        assertEquals(null, request.toolChoice)
        assertEquals(Effort.LOW, request.outputConfig?.effort)
        val ids = request.outputConfig!!.format!!.schema["properties"]!!.jsonObject["envelope_id"]!!.jsonObject["enum"]!!.jsonArray
        assertEquals(listOf("groceries", "eating-out", "vehicle", "null"), ids.map { it.jsonPrimitive.content })
    }

    /** On an envelope's own sheet nothing is sent to pick from, and the model is told so. */
    @Test
    fun withNoEnvelopesSentTheAnswerHasNone() = runTest {
        val ai = FakeAi(answer(envelope = "null"))
        val reading = assertIs<NoteOutcome.Read>(NoteReader(ai).read(note, context.copy(envelopes = emptyList()))).reading
        assertNull(reading.envelopeId)
        assertTrue("No envelopes are given" in (ai.requests.single().messages.single().content.single() as ContentBlock.Text).text)
    }

    /** An id that was not sent is a wrong answer, not "none fits": the whole note is not read. */
    @Test
    fun anEnvelopeThatWasNotSentIsNotRead() = runTest {
        assertEquals(NoteOutcome.Unread("unchecked"), read(answer(envelope = "\"paycheck\"")))
    }

    @Test
    fun anAmountOfZeroOrPastNineDigitsIsNotRead() = runTest {
        assertEquals(NoteOutcome.Unread("unchecked"), read(answer(cents = 0)))
        assertEquals(NoteOutcome.Unread("unchecked"), read(answer(cents = 100_000_000_000)))
    }

    @Test
    fun aDayAfterTodayOrNoRealDayIsNotRead() = runTest {
        for (day in listOf("2026-10-30", "2026-02-30", "yesterday", "2026-13-01")) {
            assertEquals(NoteOutcome.Unread("unchecked"), read(answer(day = day)), day)
        }
        assertIs<NoteOutcome.Read>(read(answer(day = "2024-02-29")))
    }

    @Test
    fun aBlankDescriptionIsNotReadAndALongOneIsCut() = runTest {
        assertEquals(NoteOutcome.Unread("unchecked"), read(answer(description = "   ")))
        val long = "Dinner at the Keg with Sam and the whole team after the quarterly review"
        assertEquals(NoteReader.MAX_DESCRIPTION, assertIs<NoteOutcome.Read>(read(answer(description = long))).reading.description.length)
    }

    @Test
    fun anAnswerCutOffRefusedOrNotJsonIsNotRead() = runTest {
        assertEquals(NoteOutcome.Unread("cut_off"), NoteReader(FakeAi(answer(), StopReason.MAX_TOKENS)).read(note, context))
        assertEquals(NoteOutcome.Unread("refused"), NoteReader(FakeAi(answer(), StopReason.REFUSAL)).read(note, context))
        assertEquals(NoteOutcome.Unread("unreadable"), read("dinner was 98.90"))
    }

    @Test
    fun theClientsFailureIsPassedOn() = runTest {
        val failure = AiResult.Failed(AiResult.Reason.UNAVAILABLE, "offline")
        assertEquals(NoteOutcome.Failed(failure), NoteReader(FakeAi(failure = failure)).read(note, context))
    }
}
