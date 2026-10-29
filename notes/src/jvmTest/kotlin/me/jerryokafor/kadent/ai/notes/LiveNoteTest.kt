package me.jerryokafor.kadent.ai.notes

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
 * Reads the issue's note with the real Muse Spark. Runs only when `META_MODEL_API_KEY` is set, so an
 * ordinary run makes no network call:
 *
 *     META_MODEL_API_KEY=... ./gradlew :kadent-ai-notes:jvmTest --tests '*LiveNoteTest*'
 *
 * It calls Meta directly, as the other live tests do. The reading is written to build/live-note.txt.
 */
class LiveNoteTest {

    @Test
    fun museSparkReadsTheIssuesNote() = runTest(timeout = 3.minutes) {
        val key = System.getenv("META_MODEL_API_KEY")?.trim()
        if (key.isNullOrEmpty()) return@runTest

        val http = HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = 120_000 } }
        val ai = FunctionAiClient(http, endpoint = "https://api.meta.ai/v1/messages", idToken = { key })
        val context = NoteContext(
            today = "2026-10-29",
            income = false,
            envelopes = listOf(
                NoteEnvelope("groceries", "Groceries"),
                NoteEnvelope("eating-out", "Eating out"),
                NoteEnvelope("vehicle-maintenance", "Vehicle maintenance"),
            ),
        )

        val started = System.currentTimeMillis()
        val outcome = NoteReader(ai).read("dinner at the Keg with Sam, 86 plus 15% tip, yesterday", context)
        val seconds = (System.currentTimeMillis() - started) / 1000.0
        http.close()

        File("build/live-note.txt").writeText("$seconds s\n$outcome\n")
        val reading = assertIs<NoteOutcome.Read>(outcome).reading
        assertEquals(9_890L, reading.amountMinor, "86 plus 15%")
        assertEquals("2026-10-28", reading.day, "yesterday")
        assertEquals("eating-out", reading.envelopeId)
        assertTrue("keg" in reading.description.lowercase(), reading.description)
    }
}
