package me.jerryokafor.kadent.ai.ask

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.jerryokafor.kadent.ai.AiClient
import me.jerryokafor.kadent.ai.AiJson
import me.jerryokafor.kadent.ai.AiResult
import me.jerryokafor.kadent.ai.ContentBlock
import me.jerryokafor.kadent.ai.Effort
import me.jerryokafor.kadent.ai.LoopOutcome
import me.jerryokafor.kadent.ai.Message
import me.jerryokafor.kadent.ai.MessagesRequest
import me.jerryokafor.kadent.ai.Models
import me.jerryokafor.kadent.ai.OutputConfig
import me.jerryokafor.kadent.ai.Role
import me.jerryokafor.kadent.ai.Tool
import me.jerryokafor.kadent.ai.ToolAnswer
import me.jerryokafor.kadent.ai.ToolChoice
import me.jerryokafor.kadent.ai.ToolLoop
import me.jerryokafor.kadent.ai.Usage

sealed interface AskOutcome {
    /** [session] continues the conversation for [KadentAsker.followUp]. */
    data class Answered(val answer: Answer, val session: AskSession, val usage: Usage) : AskOutcome

    /**
     * The model answered, twice, with something [KadentAsker] could not check: an id it never read, or an
     * amount written outside one. Nothing of it is shown. [reason] is the check's, for the log.
     */
    data class Refused(val reason: String) : AskOutcome

    /** No answer from the client: signed out, offline, rate limited. The client's own reasons. */
    data class Failed(val failure: AiResult.Failed) : AskOutcome

    /** The model stopped without answering: it stopped, was cut off, or ran out of turns. */
    data class Unanswered(val reason: String) : AskOutcome
}

/** An answer's conversation and the figures read in it, kept for a follow-up. Opaque to Kadent. */
class AskSession internal constructor(
    internal val messages: List<Message>,
    internal val answerCall: ContentBlock.ToolUse,
    internal val figures: Figures,
)

/**
 * Ask Kadent (#279): Muse Spark answers a question about the household's spending, receipts and tax by
 * calling read-only tools over [AskData], then `answer`.
 *
 * **Every amount in an answer is this library's or Kadent's, never the model's.** Each row a tool returns
 * carries an id (`f3`); the model writes its text with those ids in braces (`October is {f12} higher`),
 * and [Answer.parts] puts the [Figure] back in. Arithmetic is two more tools, `sum` and `difference`,
 * computed here. So a figure on screen always has a row behind it that Kadent read.
 *
 * **An answer is checked before it is returned**: every id it cites must be one this conversation read,
 * and its text must hold no money of its own (`$12`, `12 dollars`). The first answer that fails is sent
 * back as an error, so the model can fix it; a second is [AskOutcome.Refused], and nothing is shown.
 *
 * Effort is low and `tool_choice` is `any`, as the trip planner's: every turn calls a tool, the person is
 * waiting, and the loop ends at `answer`.
 */
class KadentAsker(private val ai: AiClient, private val model: String = Models.SPARK) {

    suspend fun ask(question: String, data: AskData, onStep: (AskStep) -> Unit = {}): AskOutcome {
        val opening = Message.user(ContentBlock.Text("Today is ${data.today}. The question: $question"))
        return run(listOf(opening), Figures(), data, onStep)
    }

    /**
     * The person's next question, in the same conversation: the last answer is answered "shown to the
     * person", and the question follows it. Figures read for the last answer can be cited again.
     */
    suspend fun followUp(session: AskSession, question: String, data: AskData, onStep: (AskStep) -> Unit = {}): AskOutcome {
        // Every call of the turn that answered gets a result, as the API requires: the loop stopped at
        // `answer`, so a call made beside it in the same turn was never run.
        val calls = session.messages.last().content.filterIsInstance<ContentBlock.ToolUse>()
        val results = calls.map { call ->
            if (call.id == session.answerCall.id) {
                ContentBlock.ToolResult(call.id, "Shown to the person. They asked next, below.")
            } else {
                ContentBlock.ToolResult(call.id, "Not run: answer ended the turn.", isError = true)
            }
        }
        val next = Message(Role.USER, results + ContentBlock.Text(question))
        return run(session.messages + next, session.figures.copy(), data, onStep)
    }

    private suspend fun run(messages: List<Message>, figures: Figures, data: AskData, onStep: (AskStep) -> Unit): AskOutcome {
        val request = MessagesRequest(
            model = model,
            maxTokens = MAX_TOKENS,
            system = SYSTEM,
            messages = messages,
            tools = if (data.hasBudget) TOOLS else TOOLS.filter { it.name != SPENDING },
            toolChoice = ToolChoice.ANY,
            outputConfig = OutputConfig(effort = Effort.LOW),
        )
        var answered: Answer? = null
        var refused: String? = null
        var rejected = 0
        val outcome = ToolLoop(ai, MAX_TURNS).run(request) { call ->
            try {
                when (call.name) {
                    SPENDING -> if (!data.hasBudget) noBudget() else spending(call.input, data, figures, onStep)
                    RECEIPTS -> receipts(call.input, data, figures, onStep)
                    RECEIPT_LINES -> lines(call.input, data, figures, onStep)
                    TAX_PAID -> tax(call.input, data, figures, onStep)
                    SUM -> sum(call.input, figures)
                    DIFFERENCE -> difference(call.input, figures)
                    ANSWER -> {
                        val written = AiJson.decodeFromJsonElement(Written.serializer(), call.input)
                        when (val checked = check(written, figures)) {
                            is Checked.Ok -> {
                                answered = checked.answer
                                ToolAnswer.Finish
                            }
                            is Checked.Bad -> if (rejected++ == 0) {
                                ToolAnswer.Result("${checked.why} Call answer again.", isError = true)
                            } else {
                                refused = checked.why
                                ToolAnswer.Finish
                            }
                        }
                    }
                    else -> ToolAnswer.Result("There is no tool called ${call.name}.", isError = true)
                }
            } catch (bad: SerializationException) {
                ToolAnswer.Result("Not valid input for ${call.name}: ${bad.message}", isError = true)
            } catch (bad: IllegalArgumentException) {
                ToolAnswer.Result("Not valid input for ${call.name}: ${bad.message}", isError = true)
            }
        }
        return when (outcome) {
            is LoopOutcome.Failed -> AskOutcome.Failed(outcome.failure)
            is LoopOutcome.Stopped -> AskOutcome.Unanswered(outcome.reason)
            is LoopOutcome.Finished -> refused?.let { AskOutcome.Refused(it) }
                ?: answered?.let { AskOutcome.Answered(it, AskSession(outcome.messages, outcome.call, figures), outcome.usage) }
                ?: AskOutcome.Unanswered("no_answer")
        }
    }

    private suspend fun spending(input: JsonObject, data: AskData, figures: Figures, onStep: (AskStep) -> Unit): ToolAnswer {
        val from = month(input, "from_month")
        val through = month(input, "through_month")
        require(from <= through) { "from_month is after through_month" }
        val day = input["through_day"]?.jsonPrimitive?.intOrNull
        require(day == null || day in 1..31) { "through_day is a day of the month, 1 to 31" }
        val rows = data.spending(from, through, day)
        onStep(AskStep(AskTool.SPENDING, from, through, null, rows.size))
        val envelopes = rows.map { row ->
            val figure = figures.add(row.spentMinor, FigureSource.Envelope(row.envelopeId, row.envelope, row.month, day))
            EnvelopeWire(figure.id, row.envelope, row.month, dollars(row.spentMinor), row.limitMinor?.let(::dollars))
        }
        // Every month of the span, a zero included: "nothing in September" is an answer too.
        val months = monthsFrom(from, through).map { month ->
            val figure = figures.add(rows.filter { it.month == month }.sumOf { it.spentMinor }, FigureSource.Month(month, day))
            MonthWire(figure.id, month, dollars(figure.amountMinor))
        }
        return json(SpendingWire(envelopes, months), SpendingWire.serializer())
    }

    private suspend fun receipts(input: JsonObject, data: AskData, figures: Figures, onStep: (AskStep) -> Unit): ToolAnswer {
        val (from, through) = days(input)
        val store = text(input, "store")
        val rows = data.receipts(from, through, store)
        // The store as the model wrote it is shown only once it matched a receipt: it is the model's text.
        onStep(AskStep(AskTool.RECEIPTS, from, through, store.takeIf { rows.isNotEmpty() }, rows.size))
        val wire = rows.map { row ->
            val figure = figures.add(row.totalMinor, FigureSource.Receipt(row.receiptId, row.store, row.day, row.lines))
            ReceiptWire(figure.id, row.store, row.day, dollars(row.totalMinor), row.lines)
        }
        return json(wire, kotlinx.serialization.builtins.ListSerializer(ReceiptWire.serializer()))
    }

    private suspend fun lines(input: JsonObject, data: AskData, figures: Figures, onStep: (AskStep) -> Unit): ToolAnswer {
        val (from, through) = days(input)
        val store = text(input, "store")
        val rows = data.receiptLines(from, through, store, text(input, "aisle"), text(input, "text"))
        onStep(AskStep(AskTool.RECEIPT_LINES, from, through, store.takeIf { rows.isNotEmpty() }, rows.size))
        val truncated = rows.size > MAX_LINES
        val wire = rows.take(MAX_LINES).map { row ->
            val figure = figures.add(row.amountMinor, FigureSource.Line(row.lineId, row.receiptId, row.name, row.store, row.day), partial = truncated)
            LineWire(figure.id, row.name, row.aisle, row.store, row.day, dollars(row.amountMinor))
        }
        return json(LinesWire(wire, truncated, data.receiptsWithoutLines(from, through, store)), LinesWire.serializer())
    }

    private suspend fun tax(input: JsonObject, data: AskData, figures: Figures, onStep: (AskStep) -> Unit): ToolAnswer {
        val (from, through) = days(input)
        val rows = data.tax(from, through)
        onStep(AskStep(AskTool.TAX, from, through, null, rows.size))
        val wire = rows.groupBy { it.kind to it.month }.map { (key, own) ->
            val figure = figures.add(own.sumOf { it.amountMinor }, FigureSource.Tax(key.first, key.second))
            TaxWire(figure.id, key.first, key.second, dollars(figure.amountMinor))
        }
        val total = figures.add(rows.sumOf { it.amountMinor }, FigureSource.TaxTotal(from, through))
        return json(TaxPaidWire(wire, MonthWire(total.id, "$from to $through", dollars(total.amountMinor))), TaxPaidWire.serializer())
    }

    private fun sum(input: JsonObject, figures: Figures): ToolAnswer {
        val ids = input["ids"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        require(ids.isNotEmpty()) { "ids is empty" }
        val parts = ids.distinct().map { figures[it] ?: throw IllegalArgumentException("$it is not an id you read") }
        // A sum over a read that was cut off would be a confident total of part of the lines.
        require(parts.none { figures.isPartial(it.id) }) {
            "some of these lines come from a receipt_lines read that was truncated at $MAX_LINES. Narrow it by aisle, text, " +
                "store or dates until it is not truncated, then sum again"
        }
        val figure = figures.add(parts.sumOf { it.amountMinor }, FigureSource.Sum(label(input), parts))
        return json(MonthWire(figure.id, figure.source.let { (it as FigureSource.Sum).label }, dollars(figure.amountMinor)), MonthWire.serializer())
    }

    private fun difference(input: JsonObject, figures: Figures): ToolAnswer {
        val a = figures[text(input, "a").orEmpty()] ?: throw IllegalArgumentException("a is not an id you read")
        val b = figures[text(input, "b").orEmpty()] ?: throw IllegalArgumentException("b is not an id you read")
        val figure = figures.add(a.amountMinor - b.amountMinor, FigureSource.Difference(label(input), a, b))
        return json(MonthWire(figure.id, (figure.source as FigureSource.Difference).label, dollars(figure.amountMinor)), MonthWire.serializer())
    }

    private fun noBudget() = ToolAnswer.Result("This household keeps no budget, so there is no spending by envelope.", isError = true)

    private fun <T> json(value: T, serializer: kotlinx.serialization.KSerializer<T>) = ToolAnswer.Result(AiJson.encodeToString(serializer, value))

    private fun month(input: JsonObject, key: String): String {
        val value = text(input, key) ?: throw IllegalArgumentException("$key is required")
        require(MONTH.matches(value)) { "$key is a month, like 2026-10" }
        return value
    }

    private fun days(input: JsonObject): Pair<String, String> {
        val from = text(input, "from") ?: throw IllegalArgumentException("from is required")
        val through = text(input, "through") ?: throw IllegalArgumentException("through is required")
        require(DAY.matches(from) && DAY.matches(through)) { "from and through are days, like 2026-10-28" }
        require(from <= through) { "from is after through" }
        return from to through
    }

    /** A sum's or a difference's label, drawn as a row's title, so it is held to the answer's money rule. */
    private fun label(input: JsonObject): String {
        val label = text(input, "label")?.take(MAX_LABEL) ?: throw IllegalArgumentException("label is required")
        require(!writesMoney(label)) { "label writes an amount. Name the figure in words, like Snacks at Costco" }
        return label
    }

    private fun text(input: JsonObject, key: String): String? = input[key]?.jsonPrimitive?.takeIf { it.isString }?.content?.trim()?.ifEmpty { null }

    companion object {
        const val SPENDING = "spending"
        const val RECEIPTS = "receipts"
        const val RECEIPT_LINES = "receipt_lines"
        const val TAX_PAID = "tax_paid"
        const val SUM = "sum"
        const val DIFFERENCE = "difference"
        const val ANSWER = "answer"

        /** A question is a handful of reads and a sum or two; a model still calling tools after ten is lost. */
        private const val MAX_TURNS = 10

        private const val MAX_TOKENS = 8_192

        /** A year of a family's receipts is a few thousand lines; the model is told to narrow past this. */
        private const val MAX_LINES = 300

        /** A sum's label is a heading on a row, "Snacks at Costco", not a sentence. */
        private const val MAX_LABEL = 40

        private val MONTH = Regex("""\d{4}-\d{2}""")
        private val DAY = Regex("""\d{4}-\d{2}-\d{2}""")

        val SYSTEM = """
            You answer a Canadian household's questions about its own spending, receipts and tax, for a
            budgeting app. Read the household's data with the tools, then call answer exactly once.

            Rules
            - Every amount a tool returns comes with an id, like f3. In answer.text, write an amount only
              as its id in braces: "October is {f12} higher than September". Never write a dollar amount,
              a price or a total yourself, and never do arithmetic: call sum or difference, which return
              a new id.
            - Counts (14 lines, 6 receipts), dates, stores and names may be written as words.
            - Call only the tools the question needs, with the narrowest dates.
            - A month still in progress is compared with another month through the same day: pass
              through_day as today's day of the month, and say "so far".
            - "This year" is January 1 to today. "Last month" is the calendar month before today's.
            - Spending by envelope comes from spending; what was bought and where, from receipt_lines and
              receipts; tax from tax_paid.
            - A kind of item ("snacks", "cleaning") is the lines whose aisle names it. A line whose aisle is
              null may be counted by its name; put its id in by_name.
            - To find lines counted by name, call receipt_lines with aisle "none". If a read is truncated,
              narrow it before you sum; sum refuses lines from a truncated read.
            - If receipt_lines says receipts_without_lines is more than 0, say that many receipts' lines
              could not be read, rather than that nothing was bought.
            - Labels for sum and difference are words, never amounts.
            - If nothing in the data answers the question, say so, cite the zero or the count you read,
              and say what you searched.
            - answer.text is two or three plain sentences. answer.figures lists every id the person should
              see beneath the answer, most important first.
            - The person may ask a follow-up. Answer it the same way, citing figures you already read or
              new ones.
        """.trimIndent()

        val TOOLS: List<Tool> = listOf(
            Tool(
                SPENDING,
                "What each Budget envelope spent in each month of a span, and each month's total, as Budget " +
                    "counts it. through_day cuts every month at that day of the month.",
                schema(required = listOf("from_month", "through_month")) {
                    string("from_month", "First month, like 2026-09")
                    string("through_month", "Last month, like 2026-10")
                    putJsonObject("through_day") {
                        put("type", "integer")
                        put("description", "Count each month only up to this day of the month")
                    }
                },
            ),
            Tool(
                RECEIPTS,
                "Every filed receipt in a span of days: store as printed, day, total, and how many lines.",
                schema(required = listOf("from", "through")) {
                    string("from", "First day, like 2026-01-01")
                    string("through", "Last day, like 2026-10-28")
                    string("store", "Only receipts whose store name contains this")
                }
            ),
            Tool(
                RECEIPT_LINES,
                "Every line of the filed receipts in a span of days: name, the item's aisle (null when the line " +
                    "matched no item), store, day and what it cost with its tax. At most $MAX_LINES; truncated " +
                    "is true when there were more, and you must narrow the search before you sum them. " +
                    "receipts_without_lines counts receipts in the span whose lines cannot be read.",
                schema(required = listOf("from", "through")) {
                    string("from", "First day, like 2026-01-01")
                    string("through", "Last day, like 2026-10-28")
                    string("store", "Only lines from stores whose name contains this")
                    string("aisle", "Only lines whose item is in this aisle, by its name, like Snacks & Crackers; none for lines whose item has no aisle")
                    string("text", "Only lines whose name contains this")
                },
            ),
            Tool(
                TAX_PAID,
                "Tax the household's receipts printed in a span of days, by kind (GST, HST, PST, QST, Other) and " +
                    "month, and the total.",
                schema(required = listOf("from", "through")) {
                    string("from", "First day, like 2026-01-01")
                    string("through", "Last day, like 2026-10-28")
                },
            ),
            Tool(
                SUM,
                "Adds up amounts you read, by their ids, and returns the sum with a new id. label names it in a " +
                    "few words, like Snacks at Costco.",
                schema(required = listOf("ids", "label")) {
                    putJsonObject("ids") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                    }
                    string("label", "A few words naming the sum")
                },
            ),
            Tool(
                DIFFERENCE,
                "Subtracts one amount you read from another, by their ids (a minus b), and returns the result " +
                    "with a new id. label names it in a few words, like Groceries, October against September.",
                schema(required = listOf("a", "b", "label")) {
                    string("a", "The id to subtract from")
                    string("b", "The id to subtract")
                    string("label", "A few words naming the difference")
                },
            ),
            Tool(
                ANSWER,
                "The answer shown to the person. Call it once, last.",
                schema(required = listOf("text", "figures")) {
                    string("text", "Two or three sentences, every amount written as {id}")
                    putJsonObject("figures") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                    }
                    putJsonObject("by_name") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                    }
                },
            ),
        )

        private fun schema(required: List<String>, properties: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties", properties)
            putJsonArray("required") { required.forEach { add(it) } }
        }

        private fun JsonObjectBuilder.string(name: String, description: String) = putJsonObject(name) {
            put("type", "string")
            put("description", description)
        }
    }
}

/** Every figure one conversation has read, by id, and the next id to give. */
internal class Figures(
    private val byId: MutableMap<String, Figure> = linkedMapOf(),
    private var next: Int = 1,
    private val partial: MutableSet<String> = mutableSetOf(),
) {
    operator fun get(id: String): Figure? = byId[id]

    /** [partial]: read by a `receipt_lines` call that was cut off, so it may be shown but never summed. */
    fun add(amountMinor: Long, source: FigureSource, partial: Boolean = false): Figure =
        Figure("f${next++}", amountMinor, source).also {
            byId[it.id] = it
            if (partial) this.partial += it.id
        }

    fun isPartial(id: String): Boolean = id in partial

    /** A copy for a follow-up, so a follow-up that fails leaves the answer it follows as it was. */
    fun copy() = Figures(LinkedHashMap(byId), next, partial.toMutableSet())
}

internal sealed interface Checked {
    data class Ok(val answer: Answer) : Checked

    data class Bad(val why: String) : Checked
}

/**
 * [written] as an [Answer], or why it cannot be shown: an id this conversation never read, an id in
 * `by_name` that is not a line, or money in the text outside an id. Counts and dates are words; `$12`,
 * `12 dollars` and `12 cents` are not.
 */
internal fun check(written: Written, figures: Figures): Checked {
    val cited = PLACEHOLDER.findAll(written.text).map { it.groupValues[1] }.toList()
    val unknown = (cited + written.figures + written.byName).distinct().filter { figures[it] == null }
    if (unknown.isNotEmpty()) return Checked.Bad("${unknown.joinToString()} ${if (unknown.size == 1) "is not an id" else "are not ids"} you read.")
    val notLines = written.byName.filter { figures[it]?.source !is FigureSource.Line }
    if (notLines.isNotEmpty()) return Checked.Bad("by_name may list only receipt lines, not ${notLines.joinToString()}.")
    if (STUCK.containsMatchIn(written.text) || writesMoney(PLACEHOLDER.replace(written.text, " "))) {
        return Checked.Bad("The text writes an amount itself. Write every amount as {id}.")
    }
    val parts = mutableListOf<AnswerPart>()
    var at = 0
    for (match in PLACEHOLDER.findAll(written.text)) {
        if (match.range.first > at) parts += AnswerPart.Text(written.text.substring(at, match.range.first))
        parts += AnswerPart.Amount(figures[match.groupValues[1]]!!)
        at = match.range.last + 1
    }
    if (at < written.text.length) parts += AnswerPart.Text(written.text.substring(at))
    val shown = (cited + written.figures).distinct().map { figures[it]!! }
    return Checked.Ok(Answer(parts, shown, written.byName.toSet()))
}

private val PLACEHOLDER = Regex("""\{(f\d+)\}""")

/** A digit against an id, `{f3}0` or `2{f3}`, which would draw as `$148.620`. */
private val STUCK = Regex("""\d\{f\d+\}|\{f\d+\}\d""")

/**
 * Money, or a figure, written as text: `$12`, `$-12`, `12 dollars`, `CAD 12`, `1,204`, `12.50`, `17%`.
 * Counts ("14 lines"), days ("Oct 4") and years are words. A quantity with two decimals ("1.13 kg") is
 * refused too, the price of catching `12.50`; the model is sent back once and can write it in words.
 */
private val MONEY = Regex(
    """\$\s?-?\d|\d\s?(dollars?|cents?|bucks)\b|\b(CAD|USD)\s?\d|\d,\d{3}|\d[.,]\d{2}\b|\d\s?%""",
    RegexOption.IGNORE_CASE,
)

internal fun writesMoney(text: String): Boolean = MONEY.containsMatchIn(text)

/** The `answer` call's input, as the model writes it. */
@Serializable
internal data class Written(
    val text: String,
    val figures: List<String> = emptyList(),
    @SerialName("by_name") val byName: List<String> = emptyList(),
)

@Serializable
internal data class EnvelopeWire(val id: String, val envelope: String, val month: String, val spent: String, val limit: String?)

/** A month's total, a sum, a difference or a span's tax: an id, what it is, and the amount. */
@Serializable
internal data class MonthWire(val id: String, val what: String, val amount: String)

@Serializable
internal data class SpendingWire(val envelopes: List<EnvelopeWire>, val months: List<MonthWire>)

@Serializable
internal data class ReceiptWire(val id: String, val store: String?, val day: String, val total: String, val lines: Int)

@Serializable
internal data class LineWire(val id: String, val name: String?, val aisle: String?, val store: String?, val day: String, val amount: String)

@Serializable
internal data class LinesWire(
    val lines: List<LineWire>,
    val truncated: Boolean,
    @SerialName("receipts_without_lines") val receiptsWithoutLines: Int,
)

@Serializable
internal data class TaxWire(val id: String, val kind: String, val month: String, val amount: String)

@Serializable
internal data class TaxPaidWire(@SerialName("by_kind_and_month") val byKindAndMonth: List<TaxWire>, val total: MonthWire)

/** `2026-09` through `2026-11`, each month once. */
internal fun monthsFrom(from: String, through: String): List<String> {
    val months = mutableListOf<String>()
    var year = from.take(4).toInt()
    var month = from.substring(5, 7).toInt()
    while (true) {
        val key = "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}"
        months += key
        if (key >= through || months.size >= MAX_MONTHS) return months
        if (++month > 12) {
            month = 1
            year++
        }
    }
}

/** Three years of months: a question about longer is answered from the first three. */
private const val MAX_MONTHS = 36

/** Cents as the model reads them: `329` is `"3.29"`, `-50` is `"-0.50"`. */
internal fun dollars(minor: Long): String {
    val sign = if (minor < 0) "-" else ""
    val whole = kotlin.math.abs(minor)
    return "$sign${whole / 100}.${(whole % 100).toString().padStart(2, '0')}"
}
