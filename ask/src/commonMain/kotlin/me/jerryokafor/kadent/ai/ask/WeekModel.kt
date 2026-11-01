package me.jerryokafor.kadent.ai.ask

import kotlinx.serialization.Serializable
import me.jerryokafor.kadent.ai.AiResult
import me.jerryokafor.kadent.ai.Usage

// What Insight's weekly card is written from, and what it is (avontech/Kadent#280). This library's own
// types: Kadent works the figures out with Budget's maths and draws a [WeekNote].

/** Seven days, `YYYY-MM-DD` both ends. */
data class WeekSpan(val from: String, val through: String)

/** What one envelope the person sees spent in each week. Either may be 0, not both. */
data class EnvelopeWeek(val envelopeId: String, val envelope: String, val weekMinor: Long, val beforeMinor: Long)

/** One store's filed receipts in each week: how many, and what they came to. */
data class StoreWeek(val store: String, val weekReceipts: Int, val weekMinor: Long, val beforeReceipts: Int, val beforeMinor: Long)

/**
 * Last [week] against the one [before] it. [envelopes] is in the order Kadent wants them weighed, the
 * largest change first; [WeekWriter] sends at most [WeekWriter.MOST_ENVELOPES] and [WeekWriter.MOST_STORES].
 */
data class WeekData(val week: WeekSpan, val before: WeekSpan, val envelopes: List<EnvelopeWeek>, val stores: List<StoreWeek>)

/**
 * The card, checked: a [title] and a [reason] for the card, a [text] of two or three sentences for the
 * sheet, each with its amounts as Kadent's [Figure]s, and every [figures] cited, in the order first cited.
 */
data class WeekNote(val title: List<AnswerPart>, val reason: List<AnswerPart>, val text: List<AnswerPart>, val figures: List<Figure>)

sealed interface WeekOutcome {
    /** [written] is the model's answer as sent, which [WeekWriter.restore] turns back into [note] later. */
    data class Written(val note: WeekNote, val written: String, val usage: Usage) : WeekOutcome

    /** No answer from the client: signed out, offline, rate limited. */
    data class Failed(val failure: AiResult.Failed) : WeekOutcome

    /** An answer that cannot be shown: `cut_off`, `refused`, `unreadable` or `unchecked`. */
    data class Unread(val reason: String) : WeekOutcome
}

/** The answer's JSON, as the schema shapes it. */
@Serializable
internal data class WeekWire(val title: String, val reason: String, val text: String)

/** One figure as the prompt lists it. */
@Serializable
internal data class WeekFigureWire(val id: String, val what: String, val amount: String)
