package me.jerryokafor.kadent.ai.ask

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.jerryokafor.kadent.ai.AiClient
import me.jerryokafor.kadent.ai.AiResult
import me.jerryokafor.kadent.ai.ContentBlock
import me.jerryokafor.kadent.ai.MessagesRequest
import me.jerryokafor.kadent.ai.MessagesResponse
import me.jerryokafor.kadent.ai.Models
import me.jerryokafor.kadent.ai.StopReason
import me.jerryokafor.kadent.ai.ToolChoice
import me.jerryokafor.kadent.ai.Usage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Ask Kadent's tools, the figures they give ids to, and the check every answer passes (#279). */
class KadentAskerTest {

    /** October against September, a Costco receipt with a snack line and one matched by nothing, and GST. */
    private class Household(override val hasBudget: Boolean = true, private val manyLines: Int = 0) : AskData {
        override val today = "2026-10-28"
        val asked = mutableListOf<String>()
        override suspend fun spending(fromMonth: String, throughMonth: String, throughDay: Int?) = listOf(
            EnvelopeMonth("groc", "Groceries", "2026-09", 80_800, 90_000),
            EnvelopeMonth("dine", "Dining out", "2026-09", 18_800, 20_000),
            EnvelopeMonth("groc", "Groceries", "2026-10", 101_200, 90_000),
            EnvelopeMonth("dine", "Dining out", "2026-10", 25_900, 20_000),
        ).also { asked += "spending $fromMonth $throughMonth $throughDay" }
        override suspend fun receipts(from: String, through: String, store: String?) =
            listOf(ReceiptRow("r1", "Costco Wholesale", "2026-10-04", 18_740, 23)).also { asked += "receipts $store" }
        override suspend fun receiptsWithoutLines(from: String, through: String, store: String?) = 2
        override suspend fun receiptLines(from: String, through: String, store: String?, aisle: String?, text: String?) = if (manyLines > 0) {
            List(manyLines) { LineRow("l$it", "r$it", "CHIPS", "Snacks & Crackers", "Costco Wholesale", "2026-09-01", 399) }
        } else listOf(
            LineRow("r1#3", "r1", "KS MIXED NUTS", "Snacks & Crackers", "Costco Wholesale", "2026-09-13", 2_199),
            LineRow("r1#4", "r1", "KS TRAIL MIX", null, "Costco Wholesale", "2026-08-02", 1_849),
        ).also { asked += "lines $store $aisle $text" }
        override suspend fun tax(from: String, through: String) = listOf(
            TaxRow("GST", "2026-10", 412),
            TaxRow("GST", "2026-10", 188),
            TaxRow("PST", "2026-10", 96),
        )
    }

    private class Scripted(vararg answers: List<ContentBlock>) : AiClient {
        private val left = answers.toMutableList()
        val sent = mutableListOf<MessagesRequest>()
        override suspend fun send(request: MessagesRequest): AiResult {
            sent += request
            val blocks = left.removeAt(0)
            val stop = if (blocks.any { it is ContentBlock.ToolUse }) StopReason.TOOL_USE else StopReason.END_TURN
            return AiResult.Answered(MessagesResponse("m", Models.SPARK, blocks, stop, Usage(10, 5)))
        }
    }

    private fun call(id: String, name: String, vararg input: Pair<String, Any>) = ContentBlock.ToolUse(
        id, name,
        JsonObject(
            input.associate { (key, value) ->
                key to when (value) {
                    is List<*> -> JsonArray(value.map { JsonPrimitive(it as String) })
                    is Int -> JsonPrimitive(value)
                    else -> JsonPrimitive(value as String)
                }
            },
        ),
    )

    private fun answer(id: String, text: String, figures: List<String> = emptyList(), byName: List<String> = emptyList()) =
        call(id, KadentAsker.ANSWER, "text" to text, "figures" to figures, "by_name" to byName)

    private fun toolResults(ai: Scripted, turn: Int) = ai.sent[turn].messages.last().content.filterIsInstance<ContentBlock.ToolResult>()

    private val october = call("a", KadentAsker.SPENDING, "from_month" to "2026-09", "through_month" to "2026-10", "through_day" to 28)

    @Test
    fun spendingGivesEveryEnvelopeAndEveryMonthAnIdInDollars() = runTest {
        val data = Household()
        val ai = Scripted(listOf(october), listOf(answer("b", "So far October is {f6} higher.", listOf("f6"))))

        KadentAsker(ai).ask("Why was October higher?", data)

        assertEquals(listOf("spending 2026-09 2026-10 28"), data.asked)
        val spending = toolResults(ai, 1).single().content
        assertTrue("\"id\":\"f1\",\"envelope\":\"Groceries\",\"month\":\"2026-09\",\"spent\":\"808.00\",\"limit\":\"900.00\"" in spending, spending)
        assertTrue("{\"id\":\"f5\",\"what\":\"2026-09\",\"amount\":\"996.00\"}" in spending, spending)
        assertTrue("{\"id\":\"f6\",\"what\":\"2026-10\",\"amount\":\"1271.00\"}" in spending, spending)
    }

    @Test
    fun anAnswerPutsKadentsOwnAmountsWhereTheModelCitedThem() = runTest {
        val ai = Scripted(
            listOf(october),
            listOf(call("b", KadentAsker.DIFFERENCE, "a" to "f6", "b" to "f5", "label" to "October against September")),
            listOf(answer("c", "So far October is {f7} higher: {f6} against {f5}.", listOf("f7", "f3"))),
        )

        val outcome = assertIs<AskOutcome.Answered>(KadentAsker(ai).ask("Why was October higher?", Household()))

        assertEquals("{\"id\":\"f7\",\"what\":\"October against September\",\"amount\":\"275.00\"}", toolResults(ai, 2).single().content)
        val amounts = outcome.answer.parts.filterIsInstance<AnswerPart.Amount>().map { it.figure.amountMinor }
        assertEquals(listOf(27_500L, 127_100L, 99_600L), amounts)
        assertEquals(listOf("f7", "f6", "f5", "f3"), outcome.answer.figures.map { it.id })
        assertEquals(FigureSource.Envelope("groc", "Groceries", "2026-10", 28), outcome.answer.figures.last().source)
        assertEquals("So far October is ", (outcome.answer.parts.first() as AnswerPart.Text).text)
    }

    @Test
    fun aSumOfLinesKeepsItsPartsAndTheLinesCountedByName() = runTest {
        val ai = Scripted(
            listOf(call("a", KadentAsker.RECEIPT_LINES, "from" to "2026-01-01", "through" to "2026-10-28", "store" to "Costco")),
            listOf(call("b", KadentAsker.SUM, "ids" to listOf("f1", "f2"), "label" to "Snacks at Costco")),
            listOf(answer("c", "{f3} on 2 snack lines at Costco this year.", listOf("f3"), byName = listOf("f2"))),
        )

        val outcome = assertIs<AskOutcome.Answered>(KadentAsker(ai).ask("Snacks at Costco this year?", Household()))

        val sum = outcome.answer.figures.single()
        assertEquals(4_048L, sum.amountMinor)
        assertEquals(listOf("r1#3", "r1#4"), (sum.source as FigureSource.Sum).parts.map { (it.source as FigureSource.Line).lineId })
        assertEquals(setOf("f2"), outcome.answer.byName)
    }

    @Test
    fun anAnswerCitingAnIdNeverReadIsSentBackOnceThenRefused() = runTest {
        val ai = Scripted(
            listOf(october),
            listOf(answer("b", "October is {f9} higher.", listOf("f9"))),
            listOf(answer("c", "October is {f99} higher.")),
        )

        val outcome = KadentAsker(ai).ask("Why was October higher?", Household())

        val sentBack = toolResults(ai, 2).single()
        assertEquals(true, sentBack.isError)
        assertEquals("f9 is not an id you read. Call answer again.", sentBack.content)
        assertEquals(AskOutcome.Refused("f99 is not an id you read."), outcome)
    }

    @Test
    fun moneyWrittenOutsideAnIdIsRefusedButCountsAndDatesAreWords() = runTest {
        val written = Scripted(
            listOf(october),
            listOf(answer("b", "October is $275 higher.")),
            listOf(answer("c", "October is 275 dollars higher.")),
        )
        assertEquals(
            AskOutcome.Refused("The text writes an amount itself. Write every amount as {id}."),
            KadentAsker(written).ask("Why?", Household()),
        )

        val words = Scripted(listOf(october), listOf(answer("b", "On Oct 4 and Oct 18, 2 Costco trips, 23 lines: {f6}.")))
        assertIs<AskOutcome.Answered>(KadentAsker(words).ask("Why?", Household()))
    }

    @Test
    fun byNameMayListOnlyLines() = runTest {
        val ai = Scripted(
            listOf(october),
            listOf(answer("b", "{f6}.", byName = listOf("f6"))),
            listOf(answer("c", "{f6}.", byName = listOf("f6"))),
        )

        assertEquals(AskOutcome.Refused("by_name may list only receipt lines, not f6."), KadentAsker(ai).ask("Why?", Household()))
    }

    @Test
    fun aHouseholdWithNoBudgetIsNotOfferedSpending() = runTest {
        val ai = Scripted(listOf(answer("a", "Nothing to read.")))

        KadentAsker(ai).ask("Why?", Household(hasBudget = false))

        val request = ai.sent.single()
        assertEquals(listOf("receipts", "receipt_lines", "tax_paid", "sum", "difference", "answer"), request.tools!!.map { it.name })
        assertEquals(ToolChoice.AUTO, request.toolChoice)
    }

    @Test
    fun taxIsSummedByKindAndMonthWithATotal() = runTest {
        val ai = Scripted(
            listOf(call("a", KadentAsker.TAX_PAID, "from" to "2026-10-01", "through" to "2026-10-28")),
            listOf(answer("b", "{f3} in tax this month.", listOf("f3"))),
        )

        val outcome = assertIs<AskOutcome.Answered>(KadentAsker(ai).ask("Tax this month?", Household()))

        assertEquals(
            """{"by_kind_and_month":[{"id":"f1","kind":"GST","month":"2026-10","amount":"6.00"},{"id":"f2","kind":"PST","month":"2026-10","amount":"0.96"}],"total":{"id":"f3","what":"2026-10-01 to 2026-10-28","amount":"6.96"}}""",
            toolResults(ai, 1).single().content,
        )
        assertEquals(696L, outcome.answer.figures.single().amountMinor)
    }

    @Test
    fun eachReadIsAStepWithHowManyRowsItFound() = runTest {
        val steps = mutableListOf<AskStep>()
        val ai = Scripted(
            listOf(october, call("b", KadentAsker.RECEIPTS, "from" to "2026-10-01", "through" to "2026-10-28", "store" to "Costco")),
            listOf(answer("c", "{f7}.", listOf("f7"))),
        )

        KadentAsker(ai).ask("Why?", Household(), onStep = { steps += it })

        assertEquals(
            listOf(
                AskStep(AskTool.SPENDING, "2026-09", "2026-10", null, 4),
                AskStep(AskTool.RECEIPTS, "2026-10-01", "2026-10-28", "Costco", 1),
            ),
            steps,
        )
    }

    @Test
    fun aFollowUpCitesFiguresTheFirstAnswerRead() = runTest {
        val ai = Scripted(
            listOf(october),
            listOf(answer("b", "{f6} so far.", listOf("f6"))),
            listOf(answer("c", "Groceries was {f3} of it.", listOf("f3"))),
        )
        val asker = KadentAsker(ai)
        val first = assertIs<AskOutcome.Answered>(asker.ask("October so far?", Household()))

        val next = assertIs<AskOutcome.Answered>(asker.followUp(first.session, "And groceries?", Household()))

        assertEquals(101_200L, next.answer.figures.single().amountMinor)
        val turn = ai.sent[2].messages.last().content
        assertEquals("b", (turn[0] as ContentBlock.ToolResult).toolUseId)
        assertEquals("And groceries?", (turn[1] as ContentBlock.Text).text)
    }

    @Test
    fun badInputIsAnErrorTheModelCanFix() = runTest {
        val ai = Scripted(
            listOf(call("a", KadentAsker.SPENDING, "from_month" to "October", "through_month" to "2026-10")),
            listOf(answer("b", "Nothing.")),
        )

        KadentAsker(ai).ask("Why?", Household())

        val result = toolResults(ai, 1).single()
        assertEquals(true, result.isError)
        assertEquals("Not valid input for spending: from_month is a month, like 2026-10", result.content)
    }

    @Test
    fun aSumOverATruncatedReadIsSentBackToBeNarrowed() = runTest {
        val ai = Scripted(
            listOf(call("a", KadentAsker.RECEIPT_LINES, "from" to "2026-01-01", "through" to "2026-10-28")),
            listOf(call("b", KadentAsker.SUM, "ids" to listOf("f1", "f2"), "label" to "Snacks")),
            listOf(answer("c", "{f1}.")),
        )

        KadentAsker(ai).ask("Snacks this year?", Household(manyLines = 301))

        val lines = toolResults(ai, 1).single().content
        assertTrue("\"truncated\":true,\"receipts_without_lines\":2" in lines, lines.takeLast(80))
        val sum = toolResults(ai, 2).single()
        assertEquals(true, sum.isError)
        assertTrue("truncated at 300" in sum.content, sum.content)
    }

    @Test
    fun aLabelThatWritesAnAmountIsSentBack() = runTest {
        val ai = Scripted(
            listOf(october),
            listOf(call("b", KadentAsker.SUM, "ids" to listOf("f5"), "label" to "$500 on snacks")),
            listOf(answer("c", "{f5}.")),
        )

        KadentAsker(ai).ask("Why?", Household())

        assertEquals(
            "Not valid input for sum: label writes an amount. Name the figure in words, like Snacks at Costco",
            toolResults(ai, 2).single().content,
        )
    }

    @Test
    fun moneyInAnyShapeOrADigitAgainstAnIdIsRefused() = runTest {
        listOf("{f6}0 so far.", "Up 17% so far.", "About 1,204 so far.", "Up 12.50 so far.", "CAD 12 more.", "$-12 less.").forEach { text ->
            val ai = Scripted(listOf(october), listOf(answer("b", text)), listOf(answer("c", text)))
            assertIs<AskOutcome.Refused>(KadentAsker(ai).ask("Why?", Household()), text)
        }
    }

    @Test
    fun aFollowUpAnswersEveryCallOfTheTurnThatAnswered() = runTest {
        val ai = Scripted(
            listOf(october),
            listOf(call("b", KadentAsker.RECEIPTS, "from" to "2026-10-01", "through" to "2026-10-28"), answer("c", "{f6} so far.")),
            listOf(answer("d", "{f3}.")),
        )
        val asker = KadentAsker(ai)
        val first = assertIs<AskOutcome.Answered>(asker.ask("October?", Household()))

        asker.followUp(first.session, "And groceries?", Household())

        val results = ai.sent[2].messages.last().content.filterIsInstance<ContentBlock.ToolResult>()
        assertEquals(listOf("b", "c"), results.map { it.toolUseId })
        assertEquals(true, results.first().isError)
    }

    @Test
    fun aStoreTheModelSearchedIsAStepOnlyOnceItMatched() = runTest {
        val steps = mutableListOf<AskStep>()
        val nothing = object : AskData by Household() {
            override suspend fun receipts(from: String, through: String, store: String?) = emptyList<ReceiptRow>()
        }
        val ai = Scripted(
            listOf(call("a", KadentAsker.RECEIPTS, "from" to "2026-10-01", "through" to "2026-10-28", "store" to "Walmart $500")),
            listOf(answer("b", "Nothing.")),
        )

        KadentAsker(ai).ask("Walmart?", nothing, onStep = { steps += it })

        assertEquals(AskStep(AskTool.RECEIPTS, "2026-10-01", "2026-10-28", null, 0), steps.single())
    }

    @Test
    fun aReplyInTextWithNoToolCallIsUnansweredAndItsTextIsNeverShown() = runTest {
        // `tool_choice` is `auto`, the only one Meta's API accepts, so the model may write instead of
        // calling answer. That text could hold amounts nobody checked, so it ends the question unanswered.
        val ai = Scripted(listOf(october), listOf(ContentBlock.Text("October was $253 higher.")))

        assertEquals(AskOutcome.Unanswered(StopReason.END_TURN), KadentAsker(ai).ask("Why?", Household()))
        assertEquals(2, ai.sent.size)
    }
}
