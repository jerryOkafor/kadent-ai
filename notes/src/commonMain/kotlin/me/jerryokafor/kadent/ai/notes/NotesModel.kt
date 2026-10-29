package me.jerryokafor.kadent.ai.notes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.jerryokafor.kadent.ai.AiResult
import me.jerryokafor.kadent.ai.Usage

// What one budget note is read against, and what it becomes (#276). This library's own types, not
// Kadent's, as `lists` keeps its own: Kadent builds a [NoteContext] from its envelopes and turns a
// [NoteReading] into Add transaction's rows, so this repo never needs Kadent's code to build.

/** An envelope the note may go in: its id and the name the household gave it. */
data class NoteEnvelope(val id: String, val name: String)

/**
 * What the model is shown with a note: [today] as `YYYY-MM-DD`, which "yesterday" and "monday" are read
 * against, whether the line is [income], and the [envelopes] it may pick from. Empty when the line's
 * envelope is already chosen, so no envelope is sent that the answer could not change.
 */
data class NoteContext(val today: String, val income: Boolean, val envelopes: List<NoteEnvelope>)

/**
 * One line, checked: [amountMinor] in cents and above zero, [day] a real day not after today,
 * [description] at most [NoteReader.MAX_DESCRIPTION] characters, [envelopeId] one of those sent or null.
 */
data class NoteReading(val amountMinor: Long, val day: String, val description: String, val envelopeId: String?)

sealed interface NoteOutcome {
    data class Read(val reading: NoteReading, val usage: Usage) : NoteOutcome

    /** No answer from the client: signed out, offline, rate limited. The client's own reasons. */
    data class Failed(val failure: AiResult.Failed) : NoteOutcome

    /** An answer with no line Kadent can use: `cut_off`, `refused`, `unreadable` or `unchecked`. */
    data class Unread(val reason: String) : NoteOutcome
}

/** The answer's JSON, as the schema shapes it. */
@Serializable
internal data class NoteWire(
    @SerialName("amount_cents") val amountCents: Long,
    val day: String,
    val description: String,
    @SerialName("envelope_id") val envelopeId: String? = null,
)
