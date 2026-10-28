package me.jerryokafor.kadent.ai.receipt

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
 * Reads the synthetic receipt with the real Muse Spark. Runs only when `META_MODEL_API_KEY` is set,
 * so an ordinary run makes no network call and costs nothing:
 *
 *     META_MODEL_API_KEY=... ./gradlew :receipt:jvmTest --tests '*LiveReadingTest*'
 *
 * It calls Meta directly rather than through the `ai` function: FunctionAiClient sends its token as
 * a bearer token, which is exactly how Meta takes its key, and the function only adds sign-in and a
 * quota in front. What was read is written to build/live-reading.txt.
 *
 * The receipt (resources/receipts/superstore-synthetic.txt, drawn by scripts/draw-receipt.swift)
 * adds up: lines 34.15, GST 0.70, an enviro fee 0.12 and a deposit 1.20 make the total 36.17.
 */
class LiveReadingTest {
    @Test
    fun museSparkReadsTheSyntheticSuperstoreReceipt() = runTest(timeout = 3.minutes) {
        val key = System.getenv("META_MODEL_API_KEY")?.trim()
        if (key.isNullOrEmpty()) return@runTest

        val http = HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = 120_000 } }
        val ai = FunctionAiClient(http, endpoint = "https://api.meta.ai/v1/messages", idToken = { key })
        val photo = javaClass.getResourceAsStream("/receipts/superstore-synthetic.jpg")!!.readBytes()
        val context = ReadingContext(
            aisles = listOf("produce", "dairy", "bakery", "meat", "pantry", "frozen", "drinks", "household", "personal-care"),
            envelopes = listOf("Groceries", "Household", "Personal care", "Eating out"),
            today = ReceiptDate(2026, 10, 27),
        )

        val started = System.currentTimeMillis()
        val outcome = ReceiptReader(ai).read(listOf(ReceiptPage.Photo("image/jpeg", photo)), context)
        val seconds = (System.currentTimeMillis() - started) / 1000.0

        File("build/live-reading.txt").writeText(report(outcome, seconds))
        val read = assertIs<ReadOutcome.Read>(outcome, "outcome: $outcome")
        val receipt = read.receipt

        assertTrue("SUPERSTORE" in receipt.store.orEmpty().uppercase(), "store: ${receipt.store}")
        assertEquals(3617L, receipt.totalMinor)
        assertEquals(listOf(ReadTax(TaxKind.GST, "5%", 70)), receipt.taxes)
        assertEquals(120L, receipt.depositMinor)
        assertEquals(12L, receipt.feesMinor)
        assertEquals(ReceiptDate(2026, 10, 25, 14, 32), receipt.boughtAt)
        assertEquals(9, receipt.lines.size, "nine item lines, the void among them; no payment line")
        assertEquals(0L, receipt.checks.totalGapMinor, "every cent read")

        val limes = receipt.lines.single { it.plu == "4048" }
        assertEquals(4, limes.quantity)
        assertEquals(120L, limes.priceMinor)
        val void = receipt.lines.single { it.isVoid }
        assertEquals(-1299L, void.priceMinor)
    }

    private fun report(outcome: ReadOutcome, seconds: Double): String = buildString {
        appendLine("seconds: $seconds")
        when (outcome) {
            is ReadOutcome.Read -> {
                val r = outcome.receipt
                appendLine("usage: ${outcome.usage}")
                appendLine("store: ${r.store} #${r.storeNumber} | ${r.address} | ${r.postalCode} | ${r.storePhone}")
                appendLine("bought: ${r.boughtAt} | transaction: ${r.transactionId}")
                r.lines.forEach { l ->
                    appendLine(
                        "  ${l.tillText.padEnd(34)} -> ${l.name.padEnd(36)} x${l.quantity} ${l.priceMinor}" +
                            " unit=${l.unitPriceMinor}/${l.unitLabel} w=${l.weight} code=${l.code} plu=${l.plu}" +
                            " aisle=${l.aisle} env=${l.envelope}${if (l.isVoid) " VOID" else ""}",
                    )
                }
                appendLine("subtotal ${r.subtotalMinor} taxes ${r.taxes} fees ${r.feesMinor} deposit ${r.depositMinor} total ${r.totalMinor}")
                appendLine("checks: ${r.checks}")
            }
            else -> appendLine("outcome: $outcome")
        }
    }
}
