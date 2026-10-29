package me.jerryokafor.kadent.ai.lists

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.jerryokafor.kadent.ai.AiClient
import me.jerryokafor.kadent.ai.AiResult
import me.jerryokafor.kadent.ai.ContentBlock
import me.jerryokafor.kadent.ai.Effort
import me.jerryokafor.kadent.ai.MessagesRequest
import me.jerryokafor.kadent.ai.MessagesResponse
import me.jerryokafor.kadent.ai.Models
import me.jerryokafor.kadent.ai.StopReason
import me.jerryokafor.kadent.ai.Usage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ListSentenceReaderTest {

    private class FakeAi(
        val text: String = """{"changes": [], "note": null}""",
        val stopReason: String = StopReason.END_TURN,
        val failure: AiResult.Failed? = null,
    ) : AiClient {
        val requests = mutableListOf<MessagesRequest>()
        override suspend fun send(request: MessagesRequest): AiResult {
            requests += request
            return failure ?: AiResult.Answered(
                MessagesResponse(
                    id = "msg_1",
                    model = Models.SPARK,
                    content = listOf(ContentBlock.Text(text)),
                    stopReason = stopReason,
                    usage = Usage(300, 80),
                ),
            )
        }
    }

    /** The household of the issue's example: a weekly list that already has milk, and a Costco list. */
    private val household = SentenceContext(
        lists = listOf(
            HouseholdList("weekly", "Weekly", listOf(ListItem("milk", "Milk 2%", onTrip = false), ListItem("bread", "Bread", onTrip = true))),
            HouseholdList("costco", "Costco", listOf(ListItem("water", "KS water", onTrip = false))),
        ),
        aisles = listOf("dairy", "produce", "bakery", "eggs"),
    )

    private val sentence = "we're out of milk and eggs, and add bananas to the Costco list"

    private suspend fun read(answer: String, context: SentenceContext = household) =
        ListSentenceReader(FakeAi(answer)).read(sentence, context)

    /** The issue's example: milk is Weekly's own row, eggs are new there, bananas are new on Costco. */
    @Test
    fun theExampleSentenceLandsOnBothLists() = runTest {
        val outcome = read(
            """
            {"changes": [
              {"list_id": "weekly", "items": [
                {"item_id": "milk", "name": "Milk 2%", "aisle": "dairy", "quantity": 1},
                {"item_id": null, "name": "Eggs", "aisle": "eggs", "quantity": 1}]},
              {"list_id": "costco", "items": [
                {"item_id": null, "name": "Bananas", "aisle": "produce", "quantity": 1}]}],
             "note": null}
            """,
        )
        val plan = assertIs<SentenceOutcome.Planned>(outcome).plan
        assertEquals(
            listOf(
                ListChange("weekly", onTrip = listOf("milk"), added = listOf(NewItem("Eggs", "eggs", 1))),
                ListChange("costco", onTrip = emptyList(), added = listOf(NewItem("Bananas", "produce", 1))),
            ),
            plan.changes,
        )
    }

    /** A "new" item the list already has, by name in any case, is that row put on the trip: never a second copy. */
    @Test
    fun anItemTheListAlreadyHasIsNeverAddedTwice() = runTest {
        val outcome = read(
            """{"changes": [{"list_id": "weekly", "items": [
                {"item_id": null, "name": "milk 2%", "aisle": "dairy", "quantity": 1},
                {"item_id": "milk", "name": "Milk 2%", "aisle": "dairy", "quantity": 1}]}], "note": null}""",
        )
        val change = assertIs<SentenceOutcome.Planned>(outcome).plan.changes.single()
        assertEquals(listOf("milk"), change.onTrip)
        assertEquals(emptyList(), change.added)
    }

    /** A row already on the trip stays there, and is said rather than added. */
    @Test
    fun aRowAlreadyOnTheTripIsNotedNotAdded() = runTest {
        val outcome = read(
            """{"changes": [{"list_id": "weekly", "items": [{"item_id": "bread", "name": "Bread", "aisle": "bakery", "quantity": 1}]}], "note": null}""",
        )
        val change = assertIs<SentenceOutcome.Planned>(outcome).plan.changes.single()
        assertEquals(emptyList(), change.onTrip)
        assertEquals(listOf("bread"), change.alreadyOnTrip)
    }

    /**
     * What the model may not invent is dropped: a list the household does not have, an item id from
     * another list (read as a new item by its name), an aisle outside the taxonomy, a count under one.
     */
    @Test
    fun whatTheModelInventsIsChecked() = runTest {
        val outcome = read(
            """{"changes": [
                {"list_id": "target", "items": [{"item_id": null, "name": "Socks", "aisle": null, "quantity": 1}]},
                {"list_id": "costco", "items": [{"item_id": "milk", "name": "Milk", "aisle": "frozen-dessert", "quantity": 0}]}],
               "note": "Kept it short."}""",
        )
        val plan = assertIs<SentenceOutcome.Planned>(outcome).plan
        assertEquals(listOf(ListChange("costco", onTrip = emptyList(), added = listOf(NewItem("Milk", null, 1)))), plan.changes)
        assertEquals("Kept it short.", plan.note)
    }

    /** Two entries for one list are one change, and a list left with nothing to do is left out. */
    @Test
    fun oneListIsOneChange() = runTest {
        val outcome = read(
            """{"changes": [
                {"list_id": "weekly", "items": [{"item_id": "milk", "name": "Milk 2%", "aisle": "dairy", "quantity": 1}]},
                {"list_id": "costco", "items": []},
                {"list_id": "weekly", "items": [{"item_id": null, "name": "Eggs", "aisle": "eggs", "quantity": 2}]}], "note": null}""",
        )
        assertEquals(
            listOf(ListChange("weekly", onTrip = listOf("milk"), added = listOf(NewItem("Eggs", "eggs", 2)))),
            assertIs<SentenceOutcome.Planned>(outcome).plan.changes,
        )
    }

    /** The model is shown the sentence, every list with its rows, the aisles and the list it was typed on, and may name only those. */
    @Test
    fun theRequestCarriesTheHouseholdAndLimitsTheAnswerToIt() = runTest {
        val ai = FakeAi()
        ListSentenceReader(ai).read(sentence, household.copy(currentListId = "costco"))
        val request = ai.requests.single()
        val prompt = (request.messages.single().content.single() as ContentBlock.Text).text
        assertTrue(sentence in prompt, prompt)
        assertTrue("Weekly" in prompt && "KS water" in prompt && "Milk 2%" in prompt, prompt)
        assertTrue("typed on the list \"Costco\"" in prompt, prompt)
        assertEquals(Effort.LOW, request.outputConfig?.effort)
        val listIds = request.outputConfig!!.format!!.schema["properties"]!!.jsonObject["changes"]!!.jsonObject["items"]!!
            .jsonObject["properties"]!!.jsonObject["list_id"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("weekly", "costco"), listIds)
    }

    /** No answer, an answer cut off or not the schema's JSON: nothing is planned, and the reason says which. */
    @Test
    fun anAnswerThatIsNoPlanIsUnplanned() = runTest {
        val failure = AiResult.Failed(AiResult.Reason.UNAVAILABLE, "No connection.")
        assertIs<SentenceOutcome.Failed>(ListSentenceReader(FakeAi(failure = failure)).read(sentence, household))
        assertEquals(
            "cut_off",
            assertIs<SentenceOutcome.Unplanned>(ListSentenceReader(FakeAi("{", StopReason.MAX_TOKENS)).read(sentence, household)).reason,
        )
        assertEquals("unreadable", assertIs<SentenceOutcome.Unplanned>(read("not json")).reason)
    }
}
