package me.jerryokafor.kadent.ai.planner

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
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
import kotlinx.serialization.json.add

sealed interface PlanOutcome {
    /** [session] continues the conversation for [TripPlanner.revise]. */
    data class Drafted(val draft: TripDraft, val session: PlanSession, val usage: Usage) : PlanOutcome

    /** No answer from the client: signed out, offline, rate limited. The client's own reasons. */
    data class Failed(val failure: AiResult.Failed) : PlanOutcome

    /** The model answered without a draft: it stopped, was cut off, or ran out of turns. [reason] is the loop's. */
    data class Unplanned(val reason: String) : PlanOutcome
}

/** A draft's conversation, kept so the person can ask for a change in a sentence. Opaque to Kadent. */
class PlanSession internal constructor(
    internal val messages: List<Message>,
    internal val proposal: ContentBlock.ToolUse,
)

/**
 * An agent that drafts a list's next trip (#273): Muse Spark with three read-only tools over the
 * household's data, `list_items`, `price_history` and `budget_left`, and a fourth, `propose_trip`,
 * whose input is the draft.
 *
 * **Every due row is on the draft.** The planning view already offers them (`Due by your usual
 * rhythm`), so a draft that dropped one would be worse than the screen it sits on. The model is told
 * so; a due row it leaves out anyway is put back with no reason of its own ([DraftItem.reason] null).
 * What the model decides is which other rows to add, the reason for each, and what to say about the
 * budget.
 *
 * **The draft is checked before it is returned**: ids that are not rows of the list, rows already on the
 * trip and repeats are dropped. A `propose_trip` whose input does not parse is answered as an error, and
 * the model gets another turn to fix it.
 *
 * Effort is low, as the receipt reader's is, because the reasoning counts in `max_tokens` and the person
 * is waiting. `tool_choice` is `any`, so every turn calls a tool and the loop ends at `propose_trip`.
 */
class TripPlanner(private val ai: AiClient, private val model: String = Models.SPARK) {

    suspend fun draft(listName: String, data: PlannerData): PlanOutcome {
        val opening = Message.user(ContentBlock.Text("Draft the next trip for the list \"$listName\". Today is ${data.today}."))
        return plan(listOf(opening), data)
    }

    /**
     * The person's change to the last draft, in their own words: "skip the snacks this week". The
     * draft's `propose_trip` is answered as shown to the person, and their sentence follows it.
     */
    suspend fun revise(session: PlanSession, request: String, data: PlannerData): PlanOutcome {
        val answer = Message(
            Role.USER,
            listOf(
                ContentBlock.ToolResult(session.proposal.id, "Shown to the person as a draft. They answered below."),
                ContentBlock.Text(request),
            ),
        )
        return plan(session.messages + answer, data)
    }

    private suspend fun plan(messages: List<Message>, data: PlannerData): PlanOutcome {
        val request = MessagesRequest(
            model = model,
            maxTokens = MAX_TOKENS,
            system = SYSTEM,
            messages = messages,
            tools = TOOLS,
            toolChoice = ToolChoice.ANY,
            outputConfig = OutputConfig(effort = Effort.LOW),
        )
        var proposed: ProposedTrip? = null
        val outcome = ToolLoop(ai, MAX_TURNS).run(request) { call ->
            when (call.name) {
                LIST_ITEMS -> ToolAnswer.Result(AiJson.encodeToString(ListSerializer(ItemWire.serializer()), data.items().map { it.wire() }))
                PRICE_HISTORY -> {
                    val ids = call.input["item_ids"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                    val prices = data.prices(ids).mapValues { (_, list) -> list.map { PriceWire(it.store, dollars(it.priceMinor), it.day) } }
                    ToolAnswer.Result(AiJson.encodeToString(MapSerializer(String.serializer(), ListSerializer(PriceWire.serializer())), prices))
                }
                BUDGET_LEFT -> ToolAnswer.Result(
                    AiJson.encodeToString(
                        ListSerializer(EnvelopeWire.serializer()),
                        data.budget().map { EnvelopeWire(it.name, it.leftMinor?.let(::dollars), it.limitMinor?.let(::dollars)) },
                    ),
                )
                PROPOSE_TRIP -> try {
                    proposed = AiJson.decodeFromJsonElement(ProposedTrip.serializer(), call.input)
                    ToolAnswer.Finish
                } catch (bad: SerializationException) {
                    ToolAnswer.Result("Not a valid trip: ${bad.message}. Call propose_trip again.", isError = true)
                } catch (bad: IllegalArgumentException) {
                    ToolAnswer.Result("Not a valid trip: ${bad.message}. Call propose_trip again.", isError = true)
                }
                else -> ToolAnswer.Result("There is no tool called ${call.name}.", isError = true)
            }
        }
        return when (outcome) {
            is LoopOutcome.Failed -> PlanOutcome.Failed(outcome.failure)
            is LoopOutcome.Stopped -> PlanOutcome.Unplanned(outcome.reason)
            is LoopOutcome.Finished -> PlanOutcome.Drafted(
                draft = checked(proposed ?: return PlanOutcome.Unplanned("no_proposal"), data.items()),
                session = PlanSession(outcome.messages, outcome.call),
                usage = outcome.usage,
            )
        }
    }

    /** [proposed] as a [TripDraft]: rows of the list only, none on the trip, each once, every due row. */
    private fun checked(proposed: ProposedTrip, items: List<PlannerItem>): TripDraft {
        val rows = items.associateBy { it.id }
        val drafted = proposed.items
            .filter { rows[it.id]?.onTrip == false }
            .distinctBy { it.id }
            .map { DraftItem(it.id, it.reason.trim().ifEmpty { null }) }
        val left = items.filter { it.isDue && !it.onTrip && drafted.none { d -> d.id == it.id } }.map { DraftItem(it.id, null) }
        return TripDraft(drafted + left, proposed.note?.trim()?.ifEmpty { null })
    }

    private fun PlannerItem.wire() = ItemWire(
        id = id, name = name, aisle = aisle, price = priceMinor?.let(::dollars), quantity = quantity,
        everyDays = everyDays, daysSinceBought = daysSinceBought, due = isDue, onTrip = onTrip,
    )

    companion object {
        const val LIST_ITEMS = "list_items"
        const val PRICE_HISTORY = "price_history"
        const val BUDGET_LEFT = "budget_left"
        const val PROPOSE_TRIP = "propose_trip"

        /** A planning run is a handful of tool calls; a model still calling tools after eight is lost. */
        private const val MAX_TURNS = 8

        /** Reasoning and a draft of a few dozen rows, each with a sentence. */
        private const val MAX_TOKENS = 8_192

        val SYSTEM = """
            You plan a Canadian household's next grocery trip for a budgeting app. Read the household's
            data with the tools, then call propose_trip exactly once with the draft.

            Rules
            - Call list_items first. Draft only rows it returns, by their id. Skip rows with on_trip true.
            - Every row with due true goes on the draft: it was bought at least every_days ago.
            - Add a row that is not due only with a reason from the data, such as a price that is lower now
              or an item usually bought with a due one. Do not add rows on a hunch.
            - Call budget_left once. If the drafted rows' prices come to more than the groceries envelope
              has left, keep the due rows, add nothing optional, and say so in note.
            - Call price_history for rows whose price matters to the reason, at most once.
            - Each reason is one short sentence, at most 80 characters, with the numbers it rests on:
              "every 7 days, last bought 9 days ago", "$2.10 less at No Frills on Oct 12".
              Never invent a price, a store, a date or a habit the tools did not give.
            - note: one or two sentences about the budget or what was left out and why, or null.
            - When the person asks for a change, apply it and call propose_trip again with the whole
              new draft. Due rows stay unless the person asks to drop them.
            - Money is Canadian dollars, written like $3.29.
        """.trimIndent()

        val TOOLS: List<Tool> = listOf(
            Tool(
                LIST_ITEMS,
                "The rows of the list being planned: id, name, aisle, last price, quantity, how often it is " +
                    "bought (every_days), days since it was last bought, whether it is due, and whether it is " +
                    "already on the next trip.",
                emptyObject(),
            ),
            Tool(
                PRICE_HISTORY,
                "What each of the given rows cost at each store the household bought it at, oldest first, " +
                    "as receipts recorded it.",
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("item_ids") {
                            put("type", "array")
                            putJsonObject("items") { put("type", "string") }
                        }
                    }
                    putJsonArray("required") { add("item_ids") }
                },
            ),
            Tool(
                BUDGET_LEFT,
                "The household's Budget envelopes with what each has left this month and its limit. " +
                    "Empty when the household keeps no budget.",
                emptyObject(),
            ),
            Tool(
                PROPOSE_TRIP,
                "The draft of the next trip, shown to the person to accept or change. Call it once, last.",
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("items") {
                            put("type", "array")
                            putJsonObject("items") {
                                put("type", "object")
                                putJsonObject("properties") {
                                    putJsonObject("id") { put("type", "string") }
                                    putJsonObject("reason") { put("type", "string") }
                                }
                                putJsonArray("required") {
                                    add("id")
                                    add("reason")
                                }
                            }
                        }
                        putJsonObject("note") {
                            putJsonArray("type") {
                                add("string")
                                add("null")
                            }
                        }
                    }
                    putJsonArray("required") { add("items") }
                },
            ),
        )

        private fun emptyObject(): JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
        }
    }
}
