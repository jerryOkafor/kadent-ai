package me.jerryokafor.kadent.ai.lists

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.jerryokafor.kadent.ai.AiResult
import me.jerryokafor.kadent.ai.Usage

// What a sentence about the household's lists is read against, and what it becomes (#275). This
// library's own types, not Kadent's: Kadent builds a [SentenceContext] from its lists and turns a
// [ListPlan] into writes, so this repo never needs Kadent's code to build.

/** One of the household's lists, with its live rows. */
data class HouseholdList(val id: String, val name: String, val items: List<ListItem>)

/** A row on a list. [onTrip] rows are already on its next trip. */
data class ListItem(val id: String, val name: String, val onTrip: Boolean)

/**
 * What the model is shown with a sentence: every list, the aisle slugs a new row may take, and the list
 * the sentence was typed on, if it was typed on one, which takes the items no list was named for.
 */
data class SentenceContext(
    val lists: List<HouseholdList>,
    val aisles: List<String>,
    val currentListId: String? = null,
)

sealed interface SentenceOutcome {
    data class Planned(val plan: ListPlan, val usage: Usage) : SentenceOutcome

    /** No answer from the client: signed out, offline, rate limited. The client's own reasons. */
    data class Failed(val failure: AiResult.Failed) : SentenceOutcome

    /** The model answered without a plan: `cut_off`, `refused` or `unreadable`. */
    data class Unplanned(val reason: String) : SentenceOutcome
}

/**
 * What the sentence does, checked, one [ListChange] per list in the order the model named them. Empty
 * when it named nothing the household's lists can take. [note] is the model's one sentence on what it
 * could not place, or null.
 */
data class ListPlan(val changes: List<ListChange>, val note: String?)

/**
 * What one list gains: [onTrip] are its own rows to put on the next trip, [added] are rows it does not
 * have, to add and put on the trip, and [alreadyOnTrip] are rows the sentence named that are on it
 * already, which nothing changes.
 */
data class ListChange(
    val listId: String,
    val onTrip: List<String>,
    val added: List<NewItem>,
    val alreadyOnTrip: List<String> = emptyList(),
)

/** A row to add: its name as the person would write it, an aisle slug or null, and how many. */
data class NewItem(val name: String, val aisle: String?, val quantity: Int)

/** The answer's JSON, as the schema shapes it. */
@Serializable
internal data class PlanWire(val changes: List<ChangeWire>, val note: String? = null)

@Serializable
internal data class ChangeWire(@SerialName("list_id") val listId: String, val items: List<ItemWire>)

@Serializable
internal data class ItemWire(
    @SerialName("item_id") val itemId: String? = null,
    val name: String,
    val aisle: String? = null,
    val quantity: Int = 1,
)
