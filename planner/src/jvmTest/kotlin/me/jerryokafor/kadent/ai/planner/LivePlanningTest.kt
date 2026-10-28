package me.jerryokafor.kadent.ai.planner

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.test.runTest
import me.jerryokafor.kadent.ai.FunctionAiClient
import java.io.File
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Drafts a trip with the real Muse Spark, driving the tools. Runs only when `META_MODEL_API_KEY` is set,
 * so an ordinary run makes no network call:
 *
 *     META_MODEL_API_KEY=... ./gradlew :kadent-ai-planner:jvmTest --tests '*LivePlanningTest*'
 *
 * It calls Meta directly, as the receipt's live test does. The draft and every tool call are written to
 * build/live-planning.txt.
 */
class LivePlanningTest {
    private val calls = mutableListOf<String>()

    /** A month of a household's weekly shop: milk and bananas are due, coffee is cheaper at No Frills. */
    private val household = object : PlannerData {
        override val today = "2026-10-28"
        override suspend fun items() = listOf(
            PlannerItem("milk", "Milk 2% · 4 L", "dairy", 649, 1, 7, 9, onTrip = false),
            PlannerItem("bananas", "Bananas", "produce", 178, 1, 7, 7, onTrip = false),
            PlannerItem("eggs", "Eggs · 12", "dairy", 399, 1, 14, 4, onTrip = false),
            PlannerItem("bread", "Sourdough", "bakery", 449, 1, 7, 2, onTrip = true),
            PlannerItem("coffee", "Coffee · 930 g", "pantry", 1499, 1, 30, 26, onTrip = false),
            PlannerItem("chips", "Tortilla chips", "pantry", 399, 1, null, null, onTrip = false),
        ).also { calls += "list_items" }
        override suspend fun prices(ids: List<String>) = ids.associateWith {
            if (it == "coffee") listOf(StorePrice("Superstore", 1499, "2026-09-30"), StorePrice("No Frills", 1299, "2026-10-12")) else emptyList()
        }.filterValues { it.isNotEmpty() }.also { calls += "price_history $ids" }
        override suspend fun budget() = listOf(EnvelopeLeft("Groceries", 8_400, 40_000)).also { calls += "budget_left" }
    }

    @Test
    fun museSparkDraftsATripThroughTheTools() = runTest(timeout = 3.minutes) {
        val key = System.getenv("META_MODEL_API_KEY")?.trim()
        if (key.isNullOrEmpty()) return@runTest

        val http = HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = 120_000 } }
        val planner = TripPlanner(FunctionAiClient(http, endpoint = "https://api.meta.ai/v1/messages", idToken = { key }))
        val started = System.currentTimeMillis()
        val outcome = planner.draft("Weekly groceries", household)
        val revised = (outcome as? PlanOutcome.Drafted)?.let { planner.revise(it.session, "Skip anything optional this week.", household) }
        val seconds = (System.currentTimeMillis() - started) / 1000.0

        File("build/live-planning.txt").writeText(
            buildString {
                appendLine("seconds: $seconds")
                appendLine("calls: $calls")
                appendLine("draft: $outcome")
                appendLine("revised: $revised")
            },
        )
        val drafted = assertIs<PlanOutcome.Drafted>(outcome, "outcome: $outcome")
        val ids = drafted.draft.items.map { it.id }
        assertTrue("milk" in ids && "bananas" in ids, "both due rows: $ids")
        assertTrue("bread" !in ids, "on the trip already: $ids")
        assertTrue("list_items" in calls, "the model read the list: $calls")
        assertIs<PlanOutcome.Drafted>(revised, "revised: $revised")
    }
}
