package me.jerryokafor.kadent.ai.lists

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.test.runTest
import me.jerryokafor.kadent.ai.FunctionAiClient
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Reads the issue's example sentence with the real Muse Spark. Runs only when `META_MODEL_API_KEY` is
 * set, so an ordinary run makes no network call:
 *
 *     META_MODEL_API_KEY=... ./gradlew :kadent-ai-lists:jvmTest --tests '*LiveSentenceTest*'
 *
 * It calls Meta directly, as the receipt's live test does. The plan is written to build/live-sentence.txt.
 */
class LiveSentenceTest {

    @Test
    fun museSparkPutsTheExampleSentenceOnBothLists() = runTest(timeout = 3.minutes) {
        val key = System.getenv("META_MODEL_API_KEY")?.trim()
        if (key.isNullOrEmpty()) return@runTest

        val http = HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = 120_000 } }
        val ai = FunctionAiClient(http, endpoint = "https://api.meta.ai/v1/messages", idToken = { key })
        val household = SentenceContext(
            lists = listOf(
                HouseholdList(
                    "weekly",
                    "Weekly",
                    listOf(ListItem("milk", "Milk 2% · 4 L", onTrip = false), ListItem("bread", "Sourdough", onTrip = true)),
                ),
                HouseholdList("costco", "Costco", listOf(ListItem("water", "Kirkland water · 40", onTrip = false))),
            ),
            aisles = listOf("dairy", "eggs", "produce", "bakery", "pantry", "frozen", "drinks", "household"),
            currentListId = "weekly",
        )

        val started = System.currentTimeMillis()
        val outcome = ListSentenceReader(ai).read("we're out of milk and eggs, and add bananas to the Costco list", household)
        val seconds = (System.currentTimeMillis() - started) / 1000.0
        http.close()

        File("build/live-sentence.txt").writeText("$seconds s\n$outcome\n")
        val plan = assertIs<SentenceOutcome.Planned>(outcome).plan
        val weekly = plan.changes.single { it.listId == "weekly" }
        val costco = plan.changes.single { it.listId == "costco" }
        assertEquals(listOf("milk"), weekly.onTrip, "milk is Weekly's own row")
        assertTrue(weekly.added.any { "egg" in it.name.lowercase() }, "eggs are new on Weekly: $weekly")
        assertTrue(costco.added.any { "banana" in it.name.lowercase() }, "bananas are new on Costco: $costco")
    }
}
