package me.jerryokafor.kadent.ai.notes

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.jerryokafor.kadent.ai.AiClient
import me.jerryokafor.kadent.ai.AiJson
import me.jerryokafor.kadent.ai.AiResult
import me.jerryokafor.kadent.ai.ContentBlock
import me.jerryokafor.kadent.ai.Effort
import me.jerryokafor.kadent.ai.Message
import me.jerryokafor.kadent.ai.MessagesRequest
import me.jerryokafor.kadent.ai.Models
import me.jerryokafor.kadent.ai.OutputConfig
import me.jerryokafor.kadent.ai.StopReason

/**
 * Reads one budget note into a [NoteReading] (#276): "dinner at the Keg with Sam, 86 plus 15% tip,
 * yesterday" is $98.90, yesterday, The Keg, Eating out. Typed, or spoken through the phone keyboard's own
 * dictation, which hands Kadent only text.
 *
 * **One request, not a tool loop**, for `ListSentenceReader`'s reason: all the model needs is the note,
 * today's date and a handful of envelope names, and the person is waiting with Save off. There are no
 * tools, so no `tool_choice` is sent, which is also the only safe one with Meta's API (#309).
 *
 * **The answer is checked, not trusted** ([checked]). An amount of zero or less or past nine whole
 * dollars' digits, a day that is not a real `YYYY-MM-DD` or falls after today, a blank description, or an
 * envelope id that was not sent (which the schema's `enum` already makes unlikely) is a note not read
 * (`unchecked`), never a half-filled line. A description past [MAX_DESCRIPTION] is cut. A null envelope is
 * the model saying none fits.
 *
 * Effort is low, as the other readers' is: the reasoning counts in `max_tokens` and the person is waiting.
 */
class NoteReader(private val ai: AiClient, private val model: String = Models.SPARK) {

    suspend fun read(note: String, context: NoteContext): NoteOutcome {
        val request = MessagesRequest(
            model = model,
            maxTokens = MAX_TOKENS,
            system = SYSTEM,
            messages = listOf(Message.user(ContentBlock.Text(prompt(note, context)))),
            temperature = 0.0,
            outputConfig = OutputConfig.jsonSchema(schema(context), Effort.LOW),
        )
        val response = when (val result = ai.send(request)) {
            is AiResult.Failed -> return NoteOutcome.Failed(result)
            is AiResult.Answered -> result.response
        }
        when (response.stopReason) {
            StopReason.MAX_TOKENS -> return NoteOutcome.Unread("cut_off")
            StopReason.REFUSAL -> return NoteOutcome.Unread("refused")
        }
        val wire = try {
            AiJson.decodeFromString(NoteWire.serializer(), response.text)
        } catch (unreadable: SerializationException) {
            return NoteOutcome.Unread("unreadable")
        } catch (unreadable: IllegalArgumentException) {
            return NoteOutcome.Unread("unreadable")
        }
        val reading = checked(wire, context) ?: return NoteOutcome.Unread("unchecked")
        return NoteOutcome.Read(reading, response.usage)
    }

    private fun checked(wire: NoteWire, context: NoteContext): NoteReading? {
        if (wire.amountCents <= 0 || wire.amountCents > MAX_CENTS) return null
        // ISO days compare as text, so a later day is a larger string.
        if (!isRealDay(wire.day) || wire.day > context.today) return null
        val description = wire.description.trim().replace(Regex("""\s+"""), " ").take(MAX_DESCRIPTION).trim()
        if (description.isEmpty()) return null
        // Null is the model saying none fits, which Kadent answers with the phone's own pick. An id that
        // was not sent is a wrong answer, and a wrong answer is not half used.
        if (wire.envelopeId != null && context.envelopes.none { it.id == wire.envelopeId }) return null
        return NoteReading(wire.amountCents, wire.day, description, wire.envelopeId)
    }

    private fun prompt(note: String, context: NoteContext): String = buildString {
        appendLine("Today is ${context.today}.")
        appendLine(if (context.income) "The line is income: money that came in." else "The line is an expense: money that went out.")
        if (context.envelopes.isEmpty()) {
            // Either the line's envelope is already chosen or the household has none of this kind.
            appendLine("No envelopes are given, so envelope_id is null.")
        } else {
            val envelopes = buildJsonArray {
                context.envelopes.forEach { envelope ->
                    addJsonObject {
                        put("envelope_id", envelope.id)
                        put("name", envelope.name)
                    }
                }
            }
            appendLine("The envelopes it may go in:")
            appendLine(AiJson.encodeToString(JsonArray.serializer(), envelopes))
        }
        appendLine()
        appendLine("The note:")
        append(note.trim())
    }

    private fun schema(context: NoteContext) = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("amount_cents") { put("type", "integer") }
            putJsonObject("day") { put("type", "string") }
            putJsonObject("description") { put("type", "string") }
            putJsonObject("envelope_id") {
                putJsonArray("type") { add("string"); add("null") }
                put("enum", JsonArray(context.envelopes.map { JsonPrimitive(it.id) } + JsonNull))
            }
        }
        putJsonArray("required") { add("amount_cents"); add("day"); add("description"); add("envelope_id") }
        put("additionalProperties", false)
    }

    companion object {
        /** The longest description kept: a ledger line, not the note. */
        const val MAX_DESCRIPTION = 60

        /** Nine whole dollars' digits, as Add transaction's own parser allows. */
        private const val MAX_CENTS = 99_999_999_999L

        /** One line; this leaves the reasoning room. */
        private const val MAX_TOKENS = 2_048

        val SYSTEM = """
            You turn one note from a Canadian household, typed or said, into one line of its budget: how much,
            which day, what it was for, and which envelope it goes in.

            Rules
            - amount_cents is the total in cents after any tip, tax, discount or split the note works out:
              "86 plus 15% tip" is 9890, "40 split with Sam" is 2000. Never zero or negative.
            - day is YYYY-MM-DD, read against today's date. "yesterday", a weekday ("monday" is the latest one,
              today included) and a date with no year (the latest one not after today) all name a day. A note
              with no day is today. Never a day after today.
            - description is the place or the thing, as a person would write it in a ledger, short and
              capitalised: "The Keg", not the whole note. No amount, no day, no people.
            - envelope_id is the given envelope that fits what the money was for, or null when none fits or none
              were given.
        """.trimIndent()
    }
}

/** `YYYY-MM-DD` naming a day that exists: not `2026-02-30`, not `yesterday`. */
internal fun isRealDay(day: String): Boolean {
    val match = Regex("""(\d{4})-(\d{2})-(\d{2})""").matchEntire(day) ?: return false
    val (year, month, date) = match.destructured.toList().map { it.toInt() }
    if (month !in 1..12 || date < 1) return false
    val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
    val days = when (month) {
        2 -> if (leap) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }
    return date <= days
}
