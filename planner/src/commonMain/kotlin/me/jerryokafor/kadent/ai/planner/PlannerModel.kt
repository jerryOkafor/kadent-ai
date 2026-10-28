package me.jerryokafor.kadent.ai.planner

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// What the trip planner reads and what it answers with (#273). This library's own types, not Kadent's:
// Kadent implements [PlannerData] over its database and turns a [TripDraft] into a trip, so this repo
// never needs Kadent's code to build.
//
// Money is in cents as a Long, Kadent's rule. The model sees dollars as text ("3.29"), written here.

/**
 * One row of the list being planned, as the model is shown it.
 *
 * - [everyDays] is how often the household buys it, the median gap between the days it was bought;
 *   null with fewer than three buying days. [daysSinceBought] is days since the last of them.
 * - [isDue] is Kadent's own rule, the planning view's `Due by your usual rhythm`: bought at least
 *   [everyDays] ago. The planner always puts a due item on the draft (see [TripPlanner]).
 * - [onTrip] rows are already on the next trip and are not drafted again.
 */
data class PlannerItem(
    val id: String,
    val name: String,
    val aisle: String?,
    val priceMinor: Long?,
    val quantity: Int,
    val everyDays: Int?,
    val daysSinceBought: Int?,
    val onTrip: Boolean,
) {
    val isDue: Boolean get() = everyDays != null && daysSinceBought != null && daysSinceBought >= everyDays
}

/** What one item cost at one store on one day, as a receipt recorded it. [day] is `2026-10-12`. */
data class StorePrice(val store: String, val priceMinor: Long, val day: String)

/**
 * A Budget envelope and what it has left this month. [leftMinor] is its limit, with any carry, less
 * what it has spent, and null when it has no limit.
 */
data class EnvelopeLeft(val name: String, val leftMinor: Long?, val limitMinor: Long?)

/**
 * The household's data, read-only, as the planner's tools answer the model. Kadent implements it.
 * Each call is made when the model asks, so it reads what is true then.
 */
interface PlannerData {
    /** Today on the phone's calendar, `2026-10-28`. */
    val today: String

    suspend fun items(): List<PlannerItem>

    /** Each of [ids]' recent prices by store, oldest first. An id with none is absent. */
    suspend fun prices(ids: List<String>): Map<String, List<StorePrice>>

    /** Every envelope the household spends from, the one groceries go to first. Empty with no budget. */
    suspend fun budget(): List<EnvelopeLeft>
}

/**
 * The model's draft of the trip, checked. [items] are rows of the list, each once, none already on the
 * trip, in the order the model gave them, then any due row the model left out. [note] is one or two
 * sentences on the budget or what was left out, or null.
 */
data class TripDraft(val items: List<DraftItem>, val note: String?)

/**
 * One drafted row. [reason] is the model's, one short sentence with the numbers it rests on: "every 7
 * days, last bought 9 days ago", "$2.10 less at No Frills on Oct 12". **Null on a due row the model
 * left out and the planner put back**, whose reason is its rhythm, which Kadent words in its own strings.
 */
data class DraftItem(val id: String, val reason: String?)

/** The `propose_trip` call's input, as the model writes it. */
@Serializable
internal data class ProposedTrip(val items: List<ProposedItem>, val note: String? = null)

@Serializable
internal data class ProposedItem(val id: String, val reason: String)

/** A row as the `list_items` tool answers. Dollars as text, the form the model reads and writes. */
@Serializable
internal data class ItemWire(
    val id: String,
    val name: String,
    val aisle: String?,
    val price: String?,
    val quantity: Int,
    @SerialName("every_days") val everyDays: Int?,
    @SerialName("days_since_bought") val daysSinceBought: Int?,
    val due: Boolean,
    @SerialName("on_trip") val onTrip: Boolean,
)

@Serializable
internal data class PriceWire(val store: String, val price: String, val day: String)

@Serializable
internal data class EnvelopeWire(val name: String, val left: String?, val limit: String?)

/** Cents as the model reads them: `329` is `"3.29"`, `-50` is `"-0.50"`. */
internal fun dollars(minor: Long): String {
    val sign = if (minor < 0) "-" else ""
    val whole = kotlin.math.abs(minor)
    return "$sign${whole / 100}.${(whole % 100).toString().padStart(2, '0')}"
}
