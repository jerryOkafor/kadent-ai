package me.jerryokafor.kadent.ai.ask

import kotlinx.coroutines.test.runTest
import me.jerryokafor.kadent.ai.AiClient
import me.jerryokafor.kadent.ai.AiResult
import me.jerryokafor.kadent.ai.ContentBlock
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

/** Insight's weekly card (avontech/Kadent#280): the canvas's week, written, checked and restored. */
class WeekWriterTest {

    private class FakeAi(val text: String, val failure: AiResult.Failed? = null) : AiClient {
        val requests = mutableListOf<MessagesRequest>()
        override suspend fun send(request: MessagesRequest): AiResult {
            requests += request
            return failure ?: AiResult.Answered(
                MessagesResponse(id = "msg_1", model = Models.SPARK, content = listOf(ContentBlock.Text(text)), stopReason = StopReason.END_TURN, usage = Usage(300, 60)),
            )
        }
    }

    /** The canvas's 1a: Groceries up $64.30 on two Costco receipts, Dining out down $18.50. */
    private val data = WeekData(
        week = WeekSpan("2026-10-19", "2026-10-25"),
        before = WeekSpan("2026-10-12", "2026-10-18"),
        envelopes = listOf(EnvelopeWeek("groceries", "Groceries", 21_240, 14_810), EnvelopeWeek("dining", "Dining out", 4_230, 6_080)),
        stores = listOf(StoreWeek("Costco", 2, 17_188, 1, 9_870)),
    )

    // f1 last week, f2 the week before, f3 their change; f4–f6 Groceries; f7–f9 Dining out; f10–f11 Costco.
    private val answer = """{"title": "Groceries up {f6}", "reason": "Two Costco trips instead of one",
        "text": "You spent {f1} last week, {f3} more than the week before. Groceries was up {f6}: two Costco receipts instead of one."}"""

    private suspend fun write(text: String) = WeekWriter(FakeAi(text)).write(data)

    @Test
    fun theCanvasWeekIsWrittenWithKadentsAmounts() = runTest {
        val note = assertIs<WeekOutcome.Written>(write(answer)).note
        assertEquals(AnswerPart.Text("Groceries up "), note.title[0])
        assertEquals(6_430L, (note.title[1] as AnswerPart.Amount).figure.amountMinor)
        assertEquals(listOf<AnswerPart>(AnswerPart.Text("Two Costco trips instead of one")), note.reason)
        assertEquals(listOf("f6", "f1", "f3"), note.figures.map { it.id })
        assertEquals(FigureSource.Days(null, null, "2026-10-19", "2026-10-25"), note.figures[1].source)
    }

    @Test
    fun anAmountWrittenAsTextIsNotShown() = runTest {
        assertEquals(WeekOutcome.Unread("unchecked"), write(answer.replace("up {f6}\"", "up \$64\"")))
    }

    @Test
    fun anIdNeverSentIsNotShown() = runTest {
        assertEquals(WeekOutcome.Unread("unchecked"), write(answer.replace("{f1}", "{f40}")))
    }

    @Test
    fun aTitleTooLongForTheCardIsNotShown() = runTest {
        assertEquals(WeekOutcome.Unread("unchecked"), write(answer.replace("Groceries up {f6}", "Groceries went up a great deal by {f6}")))
    }

    @Test
    fun aTextWithNoFigureIsNotShown() = runTest {
        assertEquals(WeekOutcome.Unread("unchecked"), write(answer.replace(Regex(""""text": "[^"]*""""), "\"text\": \"A week like any other.\"")))
    }

    @Test
    fun theStoredAnswerRestoresTheSameCard() = runTest {
        val written = assertIs<WeekOutcome.Written>(write(answer))
        assertEquals(written.note, WeekWriter(FakeAi("")).restore(data, written.written))
        assertNull(WeekWriter(FakeAi("")).restore(data, "not json"))
    }

    @Test
    fun theFingerprintMovesWithAnyFigure() {
        val writer = WeekWriter(FakeAi(""))
        val later = data.copy(envelopes = listOf(EnvelopeWeek("groceries", "Groceries", 30_540, 14_810), data.envelopes[1]))
        assertTrue(writer.fingerprint(data) != writer.fingerprint(later))
        assertEquals(writer.fingerprint(data), writer.fingerprint(data.copy()))
    }

    @Test
    fun thePromptListsTheFiguresAndNothingElse() = runTest {
        val ai = FakeAi(answer)
        WeekWriter(ai).write(data)
        val prompt = (ai.requests.single().messages.single().content.single() as ContentBlock.Text).text
        assertTrue("\"id\":\"f6\"" in prompt && "\"amount\":\"64.30\"" in prompt, prompt)
        assertTrue("Some envelopes changed." in prompt, prompt)
        assertEquals(11, Regex(""""id":"f\d+"""").findAll(prompt).count())
    }

    @Test
    fun aWeekWithNoChangeOfTenDollarsAndTenPercentIsSteady() {
        assertTrue(WeekWriter.steady(data.copy(envelopes = listOf(EnvelopeWeek("groceries", "Groceries", 21_240, 20_810)))))
    }

    @Test
    fun aFailureIsPassedOn() = runTest {
        val failure = AiResult.Failed(AiResult.Reason.UNAVAILABLE, "offline")
        assertEquals(WeekOutcome.Failed(failure), WeekWriter(FakeAi("", failure)).write(data))
    }
}
