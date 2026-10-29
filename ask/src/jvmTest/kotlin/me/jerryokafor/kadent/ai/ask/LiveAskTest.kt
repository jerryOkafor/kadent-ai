package me.jerryokafor.kadent.ai.ask

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.test.runTest
import me.jerryokafor.kadent.ai.FunctionAiClient
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Asks #279's two questions of the real Muse Spark, [ROUNDS] times each, driving the tools. Runs only
 * when `META_MODEL_API_KEY` is set, so an ordinary run makes no network call:
 *
 *     META_MODEL_API_KEY=... ./gradlew :kadent-ai-ask:jvmTest --tests '*LiveAskTest*'
 *
 * It calls Meta directly, as the other live tests do. Every answer, its seconds and every tool call are
 * written to build/live-ask.txt. **It fails when more than one answer in ten is refused**: the rule that
 * an amount the model wrote itself is never shown costs a request each time, and above that rate the
 * prompt needs work before the feature ships (PLAN.md, carried deferral 2).
 */
class LiveAskTest {
    private val calls = mutableListOf<String>()

    /** Two months of a family's budget and a year of Costco receipts, three of them snacks. */
    private val household = object : AskData {
        override val today = "2026-10-28"
        override val hasBudget = true
        override suspend fun spending(fromMonth: String, throughMonth: String, throughDay: Int?) = listOf(
            EnvelopeMonth("groc", "Groceries", "2026-09", 80_800, 90_000),
            EnvelopeMonth("dine", "Dining out", "2026-09", 18_800, 20_000),
            EnvelopeMonth("kids", "Kids", "2026-09", 16_600, 20_000),
            EnvelopeMonth("groc", "Groceries", "2026-10", 101_200, 90_000),
            EnvelopeMonth("dine", "Dining out", "2026-10", 25_900, 20_000),
            EnvelopeMonth("kids", "Kids", "2026-10", 14_400, 20_000),
        ).filter { it.month in fromMonth..throughMonth }.also { calls += "spending $fromMonth..$throughMonth day $throughDay" }
        override suspend fun receipts(from: String, through: String, store: String?) = listOf(
            ReceiptRow("r1", "Costco Wholesale", "2026-10-04", 18_740, 23),
            ReceiptRow("r2", "Costco Wholesale", "2026-10-18", 22_163, 31),
            ReceiptRow("r3", "No Frills", "2026-10-11", 6_412, 14),
        ).filter { it.day in from..through && (store == null || it.store!!.contains(store, ignoreCase = true)) }
            .also { calls += "receipts $from..$through $store" }
        override suspend fun receiptLines(from: String, through: String, store: String?, aisle: String?, text: String?) = listOf(
            LineRow("a#1", "a", "KS MIXED NUTS", "Snacks & Crackers", "Costco Wholesale", "2026-09-13", 2_199),
            LineRow("a#2", "a", "NV GRANOLA BARS", "Snacks & Crackers", "Costco Wholesale", "2026-09-13", 1_698),
            LineRow("b#1", "b", "KS TRAIL MIX", null, "Costco Wholesale", "2026-08-02", 1_849),
            LineRow("b#2", "b", "KS PAPER TOWEL", "Paper & Plastic", "Costco Wholesale", "2026-08-02", 2_499),
            LineRow("c#1", "c", "LAYS CHIPS", "Snacks & Crackers", "No Frills", "2026-07-21", 399),
        ).filter {
            it.day in from..through &&
                (store == null || it.store!!.contains(store, ignoreCase = true)) &&
                (aisle == null || (if (aisle == NO_AISLE) it.aisle == null else it.aisle.equals(aisle, ignoreCase = true))) &&
                (text == null || it.name!!.contains(text, ignoreCase = true))
        }.also { calls += "receipt_lines $from..$through store=$store aisle=$aisle text=$text" }
        override suspend fun tax(from: String, through: String) = listOf(TaxRow("GST", "2026-10", 1_204)).also { calls += "tax_paid" }
        override suspend fun receiptsWithoutLines(from: String, through: String, store: String?) = 0
    }

    @Test
    fun museSparkAnswersTheIssuesTwoQuestionsWithFiguresItRead() = runTest(timeout = 10.minutes) {
        val key = System.getenv("META_MODEL_API_KEY")?.trim()
        if (key.isNullOrEmpty()) return@runTest

        val http = HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = 120_000 } }
        val asker = KadentAsker(FunctionAiClient(http, endpoint = "https://api.meta.ai/v1/messages", idToken = { key }))
        val questions = listOf("Why was October higher than September?", "What did we spend on snacks at Costco this year?")
        val report = StringBuilder()
        val outcomes = mutableListOf<AskOutcome>()
        repeat(ROUNDS) { round ->
            for (question in questions) {
                calls.clear()
                val started = System.currentTimeMillis()
                val outcome = asker.ask(question, household)
                outcomes += outcome
                report.appendLine("round $round · $question · ${(System.currentTimeMillis() - started) / 1000.0} s")
                report.appendLine("  calls: $calls")
                report.appendLine("  ${shown(outcome)}")
            }
        }
        val refused = outcomes.count { it is AskOutcome.Refused }
        report.appendLine("refused: $refused of ${outcomes.size}")
        File("build/live-ask.txt").writeText(report.toString())

        assertTrue(refused * 10 <= outcomes.size, "more than one answer in ten refused; see build/live-ask.txt")
        assertTrue(outcomes.count { it is AskOutcome.Answered } * 10 >= outcomes.size * 8, "fewer than eight in ten answered; see build/live-ask.txt")
    }

    /** An answer with its amounts filled in, as the sheet would draw it. */
    private fun shown(outcome: AskOutcome): String = when (outcome) {
        is AskOutcome.Answered -> outcome.answer.parts.joinToString("") {
            when (it) {
                is AnswerPart.Text -> it.text
                is AnswerPart.Amount -> "$" + dollars(it.figure.amountMinor)
            }
        } + " · figures " + outcome.answer.figures.map { it.id } + " · by name " + outcome.answer.byName
        else -> outcome.toString()
    }

    private companion object {
        /** Five rounds of two questions: ten answers, enough to see a refusal rate above one in ten. */
        const val ROUNDS = 5
    }
}
