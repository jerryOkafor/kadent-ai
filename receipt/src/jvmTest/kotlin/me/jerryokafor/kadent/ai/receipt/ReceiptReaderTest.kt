package me.jerryokafor.kadent.ai.receipt

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReceiptReaderTest {
    private val context = ReadingContext(
        aisles = listOf("produce", "dairy", "bakery"),
        envelopes = listOf("Groceries", "Household"),
        today = ReceiptDate(2026, 10, 27),
    )

    private val photo = ReceiptPage.Photo("image/jpeg", byteArrayOf(1, 2, 3))

    /** A client that records the request and answers with [text], or with [failure]. */
    private class FakeAi(
        val text: String = "{}",
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
                    usage = Usage(100, 50),
                ),
            )
        }
    }

    private val fullAnswer = """
        {
          "is_receipt": true,
          "store": "Real Canadian Superstore",
          "store_number": "1517",
          "address": "4410 17 St NW, Edmonton AB",
          "postal_code": "T6T 0C1",
          "store_phone": "780-555-0142",
          "bought_at": "2026-10-25T14:32",
          "transaction_id": null,
          "lines": [
            {"till_text": "SHRD CHEDDAR 16Z", "name": "Shredded cheddar · 16oz", "quantity": 1, "price": "6.99",
             "unit_price": null, "unit_label": null, "weight": null, "code": "068700115004", "plu": null,
             "aisle": "dairy", "envelope": "Groceries", "void": false},
            {"till_text": "LIMES 4 @ 0.30", "name": "Limes", "quantity": 4, "price": "1.20",
             "unit_price": "0.30", "unit_label": "ea", "weight": null, "code": null, "plu": "4048",
             "aisle": "produce", "envelope": "Groceries", "void": false},
            {"till_text": "BANANAS 1.12 kg @ 1.52/kg", "name": "Bananas", "quantity": 1, "price": "1.70",
             "unit_price": "1.52", "unit_label": "kg", "weight": "1.12", "code": null, "plu": "4011",
             "aisle": "produce", "envelope": "Groceries", "void": false},
            {"till_text": "PAPER TOWEL", "name": "Paper towel", "quantity": 1, "price": "8.49",
             "unit_price": null, "unit_label": null, "weight": null, "code": null, "plu": null,
             "aisle": null, "envelope": "Household", "void": false},
            {"till_text": "VOID PAPER TOWEL", "name": "Paper towel", "quantity": 1, "price": "-8.49",
             "unit_price": null, "unit_label": null, "weight": null, "code": null, "plu": null,
             "aisle": null, "envelope": "Household", "void": true}
          ],
          "subtotal": "9.89",
          "taxes": [{"kind": "GST", "rate_label": "5%", "amount": "0.35"}],
          "fees": "0.10",
          "deposit": "0.25",
          "total": "10.59"
        }
    """

    @Test
    fun aPhotoIsSentToMuseSparkWithTheSchemaAtLowEffort() = runTest {
        val ai = FakeAi(fullAnswer)

        ReceiptReader(ai).read(listOf(photo, photo), context)

        val request = ai.requests.single()
        assertEquals(Models.SPARK, request.model)
        assertEquals(Effort.LOW, request.outputConfig?.effort)
        assertEquals(0.0, request.temperature)
        val content = request.messages.single().content
        assertEquals(2, content.filterIsInstance<ContentBlock.Image>().size, "every page, in order")
        assertIs<ContentBlock.Image>(content[0])
        assertIs<ContentBlock.Text>(content.last(), "the instructions come after the pages")
        assertEquals(ReceiptSchema.schema(context), request.outputConfig?.format?.schema)
    }

    @Test
    fun aPdfIsSentAsADocument() = runTest {
        val ai = FakeAi(fullAnswer)

        ReceiptReader(ai).read(listOf(ReceiptPage.Pdf(byteArrayOf(9))), context)

        val document = assertIs<ContentBlock.Document>(ai.requests.single().messages.single().content.first())
        assertEquals("application/pdf", document.source.mediaType)
    }

    @Test
    fun theSchemaOffersOnlyTheHouseholdsAislesAndEnvelopes() {
        val line = ReceiptSchema.schema(context)["properties"]!!.jsonObject["lines"]!!.jsonObject["items"]!!.jsonObject
        val properties = line["properties"]!!.jsonObject
        assertEquals(
            JsonArray(listOf(JsonPrimitive("produce"), JsonPrimitive("dairy"), JsonPrimitive("bakery"), JsonNull)),
            properties["aisle"]!!.jsonObject["enum"],
        )
        assertEquals(
            JsonArray(listOf(JsonPrimitive("Groceries"), JsonPrimitive("Household"), JsonNull)),
            properties["envelope"]!!.jsonObject["enum"],
        )
    }

    @Test
    fun theInstructionsNameTheHouseholdsListsAndToday() = runTest {
        val ai = FakeAi(fullAnswer)

        ReceiptReader(ai).read(listOf(photo), context)

        val instructions = assertIs<ContentBlock.Text>(ai.requests.single().messages.single().content.last()).text
        assertTrue("Groceries" in instructions && "produce" in instructions && "2026-10-27" in instructions)
    }

    @Test
    fun anAnswerIsReadIntoAReceiptWithMoneyInCents() = runTest {
        val outcome = ReceiptReader(FakeAi(fullAnswer)).read(listOf(photo), context)

        val receipt = assertIs<ReadOutcome.Read>(outcome).receipt
        assertEquals("Real Canadian Superstore", receipt.store)
        assertEquals("T6T 0C1", receipt.postalCode)
        assertEquals(ReceiptDate(2026, 10, 25, 14, 32), receipt.boughtAt)
        assertEquals(989L, receipt.subtotalMinor)
        assertEquals(1059L, receipt.totalMinor)
        assertEquals(10L, receipt.feesMinor)
        assertEquals(25L, receipt.depositMinor)
        assertEquals(listOf(ReadTax(TaxKind.GST, "5%", 35)), receipt.taxes)

        val limes = receipt.lines[1]
        assertEquals("LIMES 4 @ 0.30", limes.tillText)
        assertEquals("Limes", limes.name)
        assertEquals(4, limes.quantity)
        assertEquals(120L, limes.priceMinor)
        assertEquals(30L, limes.unitPriceMinor)
        assertEquals("4048", limes.plu)
        assertEquals("produce", limes.aisle)
        assertEquals("Groceries", limes.envelope)

        val void = receipt.lines[4]
        assertTrue(void.isVoid)
        assertEquals(-849L, void.priceMinor)
    }

    @Test
    fun theChecksSayWhenEverythingAddsUpToThePrintedTotal() = runTest {
        val receipt = assertIs<ReadOutcome.Read>(ReceiptReader(FakeAi(fullAnswer)).read(listOf(photo), context)).receipt

        // Lines 6.99 + 1.20 + 1.70 + 8.49 - 8.49 = 9.89; tax 0.35, fees 0.10, deposit 0.25: 10.59.
        assertEquals(989L, receipt.checks.linesMinor)
        assertEquals(0L, receipt.checks.subtotalGapMinor)
        assertEquals(0L, receipt.checks.totalGapMinor)
    }

    @Test
    fun theChecksMeasureAMissingLine() = runTest {
        val missingLimes = fullAnswer.replace(
            """{"till_text": "LIMES 4 @ 0.30", "name": "Limes", "quantity": 4, "price": "1.20",
             "unit_price": "0.30", "unit_label": "ea", "weight": null, "code": null, "plu": "4048",
             "aisle": "produce", "envelope": "Groceries", "void": false},""",
            "",
        )
        val receipt = assertIs<ReadOutcome.Read>(ReceiptReader(FakeAi(missingLimes)).read(listOf(photo), context)).receipt

        assertEquals(120L, receipt.checks.totalGapMinor, "printed total minus what was read")
        assertEquals(120L, receipt.checks.subtotalGapMinor)
    }

    @Test
    fun anAnswerWithoutAPrintedTotalHasNoGapToMeasure() = runTest {
        val noTotal = fullAnswer.replace(""""total": "10.59"""", """"total": null""")
        val receipt = assertIs<ReadOutcome.Read>(ReceiptReader(FakeAi(noTotal)).read(listOf(photo), context)).receipt

        assertNull(receipt.checks.totalGapMinor)
    }

    @Test
    fun anAisleOrEnvelopeNotOnTheHouseholdsListIsDropped() = runTest {
        val strayed = fullAnswer
            .replace(""""aisle": "dairy"""", """"aisle": "cheese"""")
            .replace(""""envelope": "Household", "void": false""", """"envelope": "Cleaning", "void": false""")
        val receipt = assertIs<ReadOutcome.Read>(ReceiptReader(FakeAi(strayed)).read(listOf(photo), context)).receipt

        assertNull(receipt.lines[0].aisle)
        assertNull(receipt.lines[3].envelope)
    }

    @Test
    fun aPhotoOfSomethingElseIsNotAReceipt() = runTest {
        val outcome = ReceiptReader(FakeAi("""{"is_receipt": false, "lines": []}""")).read(listOf(photo), context)

        assertIs<ReadOutcome.NotAReceipt>(outcome)
    }

    @Test
    fun anAnswerCutOffByMaxTokensIsAFailedRead() = runTest {
        val outcome = ReceiptReader(FakeAi("""{"is_receipt": true, "lines": [""", StopReason.MAX_TOKENS)).read(listOf(photo), context)

        assertEquals(UnreadReason.CUT_OFF, assertIs<ReadOutcome.Unread>(outcome).reason)
    }

    @Test
    fun anAnswerThatIsNotTheSchemasJsonIsAFailedRead() = runTest {
        val outcome = ReceiptReader(FakeAi("Here is the receipt you asked about.")).read(listOf(photo), context)

        assertEquals(UnreadReason.UNREADABLE, assertIs<ReadOutcome.Unread>(outcome).reason)
    }

    @Test
    fun aPriceWithThreeDecimalsIsAFailedRead() = runTest {
        val outcome = ReceiptReader(FakeAi(fullAnswer.replace("\"6.99\"", "\"6.999\""))).read(listOf(photo), context)

        assertEquals(UnreadReason.UNREADABLE, assertIs<ReadOutcome.Unread>(outcome).reason)
    }

    @Test
    fun aRefusalIsAFailedRead() = runTest {
        val outcome = ReceiptReader(FakeAi("", StopReason.REFUSAL)).read(listOf(photo), context)

        assertEquals(UnreadReason.REFUSED, assertIs<ReadOutcome.Unread>(outcome).reason)
    }

    @Test
    fun theClientsFailureIsHandedBackUnchanged() = runTest {
        val failure = AiResult.Failed(AiResult.Reason.SIGNED_OUT, "Sign in to use AI features.")

        val outcome = ReceiptReader(FakeAi(failure = failure)).read(listOf(photo), context)

        assertEquals(failure, assertIs<ReadOutcome.AiFailed>(outcome).failure)
    }

    @Test
    fun readingNoPagesIsABug() = runTest {
        assertFailsWith<IllegalArgumentException> { ReceiptReader(FakeAi()).read(emptyList(), context) }
    }
}

class MoneyTest {
    @Test
    fun amountsAreReadIntoCentsWithoutAFloatingPointNumber() {
        assertEquals(549L, Money.toMinor("5.49"))
        assertEquals(5L, Money.toMinor("0.05"))
        assertEquals(-250L, Money.toMinor("-2.50"))
        assertEquals(123456L, Money.toMinor("1234.56"))
    }

    @Test
    fun anythingButTwoDecimalPlacesIsRefused() {
        for (bad in listOf("5.4", "5", "6.999", "$5.49", "5,49", "", " 5.49")) {
            assertNull(Money.toMinor(bad), bad)
        }
    }
}
