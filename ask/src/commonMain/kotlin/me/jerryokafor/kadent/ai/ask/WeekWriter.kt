package me.jerryokafor.kadent.ai.ask

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.add
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
 * Insight's weekly card (avontech/Kadent#280): what changed in a household's spending last week against
 * the week before, "Groceries up {f6} · Two Costco trips instead of one".
 *
 * **One request, not a tool loop.** Kadent works out every figure first ([WeekData]), so the question is
 * always the same and one call a week answers it; [KadentAsker]'s loop would spend several calls on reads
 * it can be handed. No tools, so no `tool_choice` (#309).
 *
 * **Every amount is Kadent's.** Each figure gets an id in a fixed order ([figures]), the model writes `{f6}`
 * where an amount goes, and Ask's [check] refuses an id it was not sent or money written as text. The
 * figures are deterministic, so [restore] rebuilds the same card from the stored answer and the same data,
 * and [fingerprint] tells Kadent when they are no longer the same.
 */
class WeekWriter(private val ai: AiClient, private val model: String = Models.SPARK) {

    suspend fun write(data: WeekData): WeekOutcome {
        val (figures, listed) = figures(data)
        val request = MessagesRequest(
            model = model,
            maxTokens = MAX_TOKENS,
            system = SYSTEM,
            messages = listOf(Message.user(ContentBlock.Text(prompt(data, listed)))),
            temperature = 0.0,
            outputConfig = OutputConfig.jsonSchema(SCHEMA, Effort.LOW),
        )
        val response = when (val result = ai.send(request)) {
            is AiResult.Failed -> return WeekOutcome.Failed(result)
            is AiResult.Answered -> result.response
        }
        when (response.stopReason) {
            StopReason.MAX_TOKENS -> return WeekOutcome.Unread("cut_off")
            StopReason.REFUSAL -> return WeekOutcome.Unread("refused")
        }
        val note = parse(response.text, figures) ?: return WeekOutcome.Unread("unchecked")
        return WeekOutcome.Written(note, response.text, response.usage)
    }

    /** [written], an answer [write] returned for [data], checked again: the card a phone kept. Null if it no longer checks. */
    fun restore(data: WeekData, written: String): WeekNote? = parse(written, figures(data).first)

    /**
     * Every figure as the prompt lists it, ids, names and amounts, as one string. Kadent keeps it beside the
     * answer and restores only while it is the same, so neither a changed figure nor a kadent-ai update that
     * lays the ids out differently can put `{f6}` on another figure's amount.
     */
    fun fingerprint(data: WeekData): String = figures(data).second.joinToString("|") { "${it.id}=${it.what}=${it.amount}" }

    private fun parse(written: String, figures: Figures): WeekNote? {
        val wire = try {
            AiJson.decodeFromString(WeekWire.serializer(), written)
        } catch (unreadable: SerializationException) {
            return null
        } catch (unreadable: IllegalArgumentException) {
            return null
        }
        val title = checked(wire.title, figures, TITLE_LONGEST) ?: return null
        val reason = checked(wire.reason, figures, REASON_LONGEST) ?: return null
        val text = checked(wire.text, figures, TEXT_LONGEST)?.takeIf { it.figures.isNotEmpty() } ?: return null
        return WeekNote(title.parts, reason.parts, text.parts, (title.figures + reason.figures + text.figures).distinct())
    }

    /** [text] through Ask's [check], and no longer than [longest] characters with its ids taken out. */
    private fun checked(text: String, figures: Figures, longest: Int): Answer? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || ID.replace(trimmed, "").length > longest) return null
        return (check(Written(trimmed), figures) as? Checked.Ok)?.answer
    }

    /**
     * Every figure, in a fixed order: the two weeks' totals and their difference, then each envelope's two
     * weeks and difference, then each store's two weeks. The listing is what the prompt shows the model.
     */
    internal fun figures(data: WeekData): Pair<Figures, List<WeekFigureWire>> {
        val figures = Figures()
        val listed = mutableListOf<WeekFigureWire>()
        fun add(amount: Long, source: FigureSource, what: String): Figure =
            figures.add(amount, source).also { listed += WeekFigureWire(it.id, what, dollars(amount)) }
        val (week, before) = data.week to data.before
        val all = add(data.envelopes.sumOf { it.weekMinor }, FigureSource.Days(null, null, week.from, week.through), "Spending, last week")
        val allBefore = add(data.envelopes.sumOf { it.beforeMinor }, FigureSource.Days(null, null, before.from, before.through), "Spending, the week before")
        add(all.amountMinor - allBefore.amountMinor, FigureSource.Difference("Spending, last week against the week before", all, allBefore), "Change in spending")
        data.envelopes.take(MOST_ENVELOPES).forEach { envelope ->
            val now = add(envelope.weekMinor, FigureSource.Days(envelope.envelopeId, envelope.envelope, week.from, week.through), "${envelope.envelope}, last week")
            val then = add(envelope.beforeMinor, FigureSource.Days(envelope.envelopeId, envelope.envelope, before.from, before.through), "${envelope.envelope}, the week before")
            add(now.amountMinor - then.amountMinor, FigureSource.Difference("${envelope.envelope}, last week against the week before", now, then), "Change in ${envelope.envelope}")
        }
        data.stores.take(MOST_STORES).forEach { store ->
            add(store.weekMinor, FigureSource.Store(store.store, week.from, week.through, store.weekReceipts), "${store.store}, ${store.weekReceipts} receipts last week")
            add(store.beforeMinor, FigureSource.Store(store.store, before.from, before.through, store.beforeReceipts), "${store.store}, ${store.beforeReceipts} receipts the week before")
        }
        return figures to listed
    }

    private fun prompt(data: WeekData, listed: List<WeekFigureWire>): String = buildString {
        appendLine("Last week was ${data.week.from} to ${data.week.through}; the week before was ${data.before.from} to ${data.before.through}.")
        // Kadent's rule, not the model's: a week where no envelope moved by $10 and 10% is a steady one.
        appendLine(if (steady(data)) "No envelope changed by both 10 dollars and 10 percent: this was a steady week." else "Some envelopes changed.")
        appendLine("The figures:")
        appendLine(AiJson.encodeToString(ListSerializer(WeekFigureWire.serializer()), listed))
    }

    companion object {
        /** Envelopes and stores sent: the largest changes are what a card can say. */
        const val MOST_ENVELOPES = 8
        const val MOST_STORES = 5

        /** The card's lines: a title at 20sp in 176dp, a sub line, and the sheet's sentences. */
        private const val TITLE_LONGEST = 24
        private const val REASON_LONGEST = 60
        private const val TEXT_LONGEST = 360

        private const val MAX_TOKENS = 2_048
        private val ID = Regex("""\{f\d+\}""")

        /** No envelope moved by both $10 and 10% of what it spent the week before. */
        internal fun steady(data: WeekData): Boolean = data.envelopes.none { envelope ->
            val change = kotlin.math.abs(envelope.weekMinor - envelope.beforeMinor)
            change >= 1_000 && change * 10 >= envelope.beforeMinor
        }

        private val SCHEMA = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                listOf("title", "reason", "text").forEach { name -> putJsonObject(name) { put("type", "string") } }
            }
            putJsonArray("required") { add("title"); add("reason"); add("text") }
            put("additionalProperties", false)
        }

        val SYSTEM = """
            You write one card about a Canadian household's spending: what changed last week against the week before.

            Rules
            - Write every amount as its figure's id in braces, {f6}. Never write an amount, a percentage or a
              currency yourself. Counts of receipts and days are fine as words.
            - title: the one change that matters most, at most 24 characters besides the id: "Groceries up {f6}".
              On a steady week, "A steady week".
            - reason: the likely reason in plain words, at most 60 characters, from the store figures when they
              explain it: "Two Costco trips instead of one". No amount needed.
            - text: two or three sentences for the household: the total and its change, then the changes that
              matter, each with its figure. Say "up", "down", "more" or "less" in words; a change's id is its size.
            - Never guess at a reason the figures do not show.
        """.trimIndent()
    }
}
