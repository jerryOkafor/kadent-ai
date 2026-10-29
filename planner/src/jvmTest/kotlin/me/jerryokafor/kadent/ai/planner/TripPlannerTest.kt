package me.jerryokafor.kadent.ai.planner

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.jerryokafor.kadent.ai.AiClient
import me.jerryokafor.kadent.ai.AiResult
import me.jerryokafor.kadent.ai.ContentBlock
import me.jerryokafor.kadent.ai.Effort
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

/** The trip planner's tools, its checks on the draft, and asking for a change (#273). */
class TripPlannerTest {

    /** Weekly groceries: milk is due, eggs are not, bread is on the trip, and coffee has no rhythm. */
    private val data = object : PlannerData {
        override val today = "2026-10-28"
        override suspend fun items() = listOf(
            PlannerItem("milk", "Milk · 4 L", "dairy", 649, 1, everyDays = 7, daysSinceBought = 9, onTrip = false),
            PlannerItem("eggs", "Eggs · 12", "dairy", 399, 1, everyDays = 14, daysSinceBought = 3, onTrip = false),
            PlannerItem("bread", "Bread", "bakery", 299, 1, everyDays = 5, daysSinceBought = 6, onTrip = true),
            PlannerItem("coffee", "Coffee", "pantry", 1299, 1, everyDays = null, daysSinceBought = null, onTrip = false),
        )
        override suspend fun prices(ids: List<String>) = ids.associateWith { listOf(StorePrice("No Frills", 549, "2026-10-12")) }
        override suspend fun budget() = listOf(EnvelopeLeft("Groceries", leftMinor = 8_400, limitMinor = 40_000))
    }

    private class Scripted(vararg answers: List<ContentBlock>) : AiClient {
        private val left = answers.toMutableList()
        val sent = mutableListOf<MessagesRequest>()
        override suspend fun send(request: MessagesRequest): AiResult {
            sent += request
            val content = left.removeAt(0)
            return AiResult.Answered(MessagesResponse("m", Models.SPARK, content, StopReason.TOOL_USE, Usage(10, 5)))
        }
    }

    private fun call(id: String, name: String, input: JsonObject = JsonObject(emptyMap())) = ContentBlock.ToolUse(id, name, input)

    private fun proposal(id: String, vararg items: Pair<String, String>, note: String? = null) = call(
        id, TripPlanner.PROPOSE_TRIP,
        JsonObject(
            mapOf(
                "items" to JsonArray(items.map { (item, why) -> JsonObject(mapOf("id" to JsonPrimitive(item), "reason" to JsonPrimitive(why))) }),
                "note" to (note?.let(::JsonPrimitive) ?: JsonNull),
            ),
        ),
    )

    private fun toolResults(ai: Scripted, turn: Int) = ai.sent[turn].messages.last().content.filterIsInstance<ContentBlock.ToolResult>()

    @Test
    fun theToolsAnswerFromTheHouseholdsDataInDollars() = runTest {
        val ai = Scripted(
            listOf(call("a", TripPlanner.LIST_ITEMS), call("b", TripPlanner.BUDGET_LEFT)),
            listOf(call("c", TripPlanner.PRICE_HISTORY, JsonObject(mapOf("item_ids" to JsonArray(listOf(JsonPrimitive("eggs"))))))),
            listOf(proposal("d", "milk" to "every 7 days, last bought 9 days ago")),
        )

        TripPlanner(ai).draft("Weekly groceries", data)

        val (items, budget) = toolResults(ai, 1)
        assertTrue("\"price\":\"6.49\"" in items.content && "\"due\":true" in items.content && "\"on_trip\":true" in items.content, items.content)
        assertEquals("""[{"name":"Groceries","left":"84.00","limit":"400.00"}]""", budget.content)
        assertEquals("""{"eggs":[{"store":"No Frills","price":"5.49","day":"2026-10-12"}]}""", toolResults(ai, 2).single().content)
    }

    @Test
    fun theRequestOffersTheFourToolsAndLeavesTheChoiceToTheModel() = runTest {
        val ai = Scripted(listOf(proposal("a", "milk" to "due")))
        TripPlanner(ai).draft("Weekly groceries", data)

        val request = ai.sent.single()
        assertEquals(listOf("list_items", "price_history", "budget_left", "propose_trip"), request.tools?.map { it.name })
        assertEquals(ToolChoice.AUTO, request.toolChoice)
        assertEquals(Effort.LOW, request.outputConfig?.effort)
        assertTrue("Weekly groceries" in (request.messages.single().content.single() as ContentBlock.Text).text)
    }

    @Test
    fun theDraftKeepsTheListsRowsOnceOffTheTripAndPutsBackADueRowLeftOut() = runTest {
        val ai = Scripted(
            listOf(
                proposal(
                    "a",
                    "coffee" to "$1.50 less at No Frills on Oct 12",
                    "bread" to "already on the trip",
                    "chips" to "not on this list",
                    "coffee" to "again",
                    note = "Groceries has $84 left.",
                ),
            ),
        )

        val drafted = assertIs<PlanOutcome.Drafted>(TripPlanner(ai).draft("Weekly groceries", data))

        assertEquals(
            listOf(DraftItem("coffee", "$1.50 less at No Frills on Oct 12"), DraftItem("milk", null)),
            drafted.draft.items,
            "milk is due and was left out, so it is put back with no reason of the model's",
        )
        assertEquals("Groceries has $84 left.", drafted.draft.note)
    }

    @Test
    fun aDraftThatDoesNotParseIsSentBackAsAnErrorAndTheModelTriesAgain() = runTest {
        val ai = Scripted(
            listOf(call("a", TripPlanner.PROPOSE_TRIP, JsonObject(mapOf("items" to JsonPrimitive("milk"))))),
            listOf(proposal("b", "milk" to "due")),
        )

        val drafted = assertIs<PlanOutcome.Drafted>(TripPlanner(ai).draft("Weekly groceries", data))

        assertEquals(listOf(DraftItem("milk", "due")), drafted.draft.items)
        assertEquals(true, toolResults(ai, 1).single().isError)
    }

    @Test
    fun aChangeInASentenceAnswersTheDraftAndAsksAgain() = runTest {
        val ai = Scripted(
            listOf(proposal("a", "milk" to "due", "coffee" to "cheaper now")),
            listOf(proposal("b", "milk" to "due")),
        )
        val planner = TripPlanner(ai)
        val first = assertIs<PlanOutcome.Drafted>(planner.draft("Weekly groceries", data))

        val second = assertIs<PlanOutcome.Drafted>(planner.revise(first.session, "No coffee this week.", data))

        assertEquals(listOf(DraftItem("milk", "due")), second.draft.items)
        val asked = ai.sent[1].messages.last().content
        assertEquals("a", (asked[0] as ContentBlock.ToolResult).toolUseId, "the first draft's call answered before the sentence")
        assertEquals(ContentBlock.Text("No coffee this week."), asked[1])
    }

    @Test
    fun noAnswerAndNoDraftAreSaidApart() = runTest {
        val failure = AiResult.Failed(AiResult.Reason.UNAVAILABLE, "offline")
        val offline = object : AiClient { override suspend fun send(request: MessagesRequest) = failure }
        assertEquals(PlanOutcome.Failed(failure), TripPlanner(offline).draft("Weekly groceries", data))

        val chatty = object : AiClient {
            override suspend fun send(request: MessagesRequest) =
                AiResult.Answered(MessagesResponse("m", Models.SPARK, listOf(ContentBlock.Text("Buy milk.")), StopReason.END_TURN, Usage(1, 1)))
        }
        assertEquals(PlanOutcome.Unplanned(StopReason.END_TURN), TripPlanner(chatty).draft("Weekly groceries", data))
    }
}
