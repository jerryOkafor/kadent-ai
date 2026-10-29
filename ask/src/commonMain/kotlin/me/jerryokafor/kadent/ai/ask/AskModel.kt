package me.jerryokafor.kadent.ai.ask

// What Ask Kadent's agent reads and what it answers with (#279). This library's own types, not Kadent's:
// Kadent implements [AskData] over its database and Budget, and draws an [Answer], so this repo never
// needs Kadent's code to build.
//
// Money is in cents as a Long, Kadent's rule. The model sees dollars as text ("3.29"), written here.

/**
 * The household's data, read-only, as the agent's tools answer the model. Kadent implements it, **with
 * Budget's own maths and as the person asking may see it**: an envelope not shared with them is never
 * returned, and neither are receipt lines posted to one. Each call is made when the model asks, so it
 * reads what is true then.
 *
 * Days are `2026-10-28`, months `2026-10`, both on the phone's calendar.
 */
interface AskData {
    /** Today on the phone's calendar. */
    val today: String

    /** Whether the household keeps a budget. False drops the `spending` tool: there is nothing to read. */
    val hasBudget: Boolean

    /**
     * What each envelope the person sees spent in each month from [fromMonth] through [throughMonth], as
     * Budget's own screens count it. [throughDay], when set, cuts every month at that day of the month,
     * so October 1–28 can be set against September 1–28. An envelope that spent nothing in a month is absent.
     */
    suspend fun spending(fromMonth: String, throughMonth: String, throughDay: Int?): List<EnvelopeMonth>

    /** Every filed receipt dated [from] through [through], oldest first; [store] matches its name in any case. */
    suspend fun receipts(from: String, through: String, store: String?): List<ReceiptRow>

    /**
     * Every line of a filed receipt dated [from] through [through] that the person sees, oldest first.
     * [store] and [text] match the store and the line's name in any case; [aisle] is an aisle's name, or
     * [NO_AISLE] for the lines whose item has none, which the model may count by their name.
     */
    suspend fun receiptLines(from: String, through: String, store: String?, aisle: String?, text: String?): List<LineRow>

    /**
     * How many filed receipts dated [from] through [through], [store] matched as [receipts] matches it, have
     * no line to read: filed while the household kept no budget, which keeps no lines (#279's plan review).
     * The model says so rather than answering "none" about what was on them.
     */
    suspend fun receiptsWithoutLines(from: String, through: String, store: String?): Int

    /** Every tax amount receipts dated [from] through [through] printed, by kind and month. */
    suspend fun tax(from: String, through: String): List<TaxRow>
}

/** [AskData.receiptLines]' `aisle` for the lines whose item has no aisle, or that matched no item. */
const val NO_AISLE = "none"

/** What one envelope spent in one month. [limitMinor] is its limit that month, or null with none. */
data class EnvelopeMonth(val envelopeId: String, val envelope: String, val month: String, val spentMinor: Long, val limitMinor: Long?)

/** One filed receipt. [store] is its name as printed, or null when none was read. */
data class ReceiptRow(val receiptId: String, val store: String?, val day: String, val totalMinor: Long, val lines: Int)

/**
 * One receipt line: what it cost with its tax and any change made on the phone. [name] is the line's
 * name, null when none was read; [aisle] is its item's aisle, null when the line matched no item.
 */
data class LineRow(
    val lineId: String,
    val receiptId: String,
    val name: String?,
    val aisle: String?,
    val store: String?,
    val day: String,
    val amountMinor: Long,
)

/** Tax of one [kind] (`GST`, `HST`, `PST`, `QST`, `Other`) printed in one month. */
data class TaxRow(val kind: String, val month: String, val amountMinor: Long)

/**
 * One number the agent read, with the id the model cites it by (`f3`). **Every amount an answer shows is
 * one of these**, computed by this library or by Kadent, never written by the model.
 */
data class Figure(val id: String, val amountMinor: Long, val source: FigureSource)

/** Where a [Figure] came from, which is how Kadent words it and where tapping it goes. */
sealed interface FigureSource {
    /** What one envelope spent in [month]. */
    data class Envelope(val envelopeId: String, val envelope: String, val month: String, val throughDay: Int?) : FigureSource

    /** What every envelope the person sees spent in [month]. */
    data class Month(val month: String, val throughDay: Int?) : FigureSource

    /** One filed receipt's total. */
    data class Receipt(val receiptId: String, val store: String?, val day: String, val lines: Int) : FigureSource

    /** One receipt line. */
    data class Line(val lineId: String, val receiptId: String, val name: String?, val store: String?, val day: String) : FigureSource

    /** Tax of [kind] in [month]. */
    data class Tax(val kind: String, val month: String) : FigureSource

    /** Every tax amount from [from] through [through]. */
    data class TaxTotal(val from: String, val through: String) : FigureSource

    /** The sum of [parts], under the model's own short [label]: "Snacks at Costco". */
    data class Sum(val label: String, val parts: List<Figure>) : FigureSource

    /** [a] less [b]. Negative when [b] is the larger. */
    data class Difference(val label: String, val a: Figure, val b: Figure) : FigureSource
}

/**
 * The answer, checked. [parts] is the model's text with each amount Kadent's own [Figure]; [figures] is
 * every figure it used, in the order it first cites them; [byName] are the lines the model counted by
 * their name alone, because they matched no item.
 */
data class Answer(val parts: List<AnswerPart>, val figures: List<Figure>, val byName: Set<String>)

sealed interface AnswerPart {
    data class Text(val text: String) : AnswerPart

    data class Amount(val figure: Figure) : AnswerPart
}

/** A tool the agent ran, for the sheet's "Read 2 receipts from Costco" while it works. */
data class AskStep(val tool: AskTool, val from: String?, val through: String?, val store: String?, val rows: Int)

enum class AskTool { SPENDING, RECEIPTS, RECEIPT_LINES, TAX }
