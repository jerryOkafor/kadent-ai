package me.jerryokafor.kadent.ai.receipt

import kotlinx.serialization.SerializationException
import me.jerryokafor.kadent.ai.AiClient
import me.jerryokafor.kadent.ai.AiJson
import me.jerryokafor.kadent.ai.AiResult
import me.jerryokafor.kadent.ai.ContentBlock
import me.jerryokafor.kadent.ai.Effort
import me.jerryokafor.kadent.ai.MediaSource
import me.jerryokafor.kadent.ai.Message
import me.jerryokafor.kadent.ai.MessagesRequest
import me.jerryokafor.kadent.ai.Models
import me.jerryokafor.kadent.ai.OutputConfig
import me.jerryokafor.kadent.ai.StopReason
import me.jerryokafor.kadent.ai.Usage

/** One page of a receipt: a photo from the camera or the library, or a PDF another app shared. */
sealed interface ReceiptPage {
    class Photo(val mediaType: String, val bytes: ByteArray) : ReceiptPage

    /** A whole PDF, every page of it. Muse Spark reads PDFs itself, so no page is rendered first. */
    class Pdf(val bytes: ByteArray) : ReceiptPage
}

/**
 * What the household already has, so each line can be placed in it: Kadent's aisle names
 * (`produce`, `dairy`) and the household's envelope names. [today] keeps the model from reading a
 * two-digit year into the future.
 */
data class ReadingContext(
    val aisles: List<String>,
    val envelopes: List<String>,
    val today: ReceiptDate? = null,
)

sealed interface ReadOutcome {
    data class Read(val receipt: ReadReceipt, val usage: Usage) : ReadOutcome

    /** The pages were read and are not a till receipt: a photo of something else. */
    data object NotAReceipt : ReadOutcome

    /** The model answered, but not with a receipt Kadent can use. */
    data class Unread(val reason: UnreadReason, val detail: String) : ReadOutcome

    /** No answer at all: signed out, rate limited, offline. The client's own reasons, unchanged. */
    data class AiFailed(val failure: AiResult.Failed) : ReadOutcome
}

enum class UnreadReason {
    /** The answer reached `max_tokens` before its JSON closed. A very long receipt. */
    CUT_OFF,

    /** The answer is not the schema's JSON, or holds an amount that is not two decimal places. */
    UNREADABLE,

    /** The model declined to answer. */
    REFUSED,
}

/**
 * Reads a receipt's pages with Muse Spark into a [ReadReceipt].
 *
 * One request per receipt, every page in it, so a line split across two photos is read once. Effort
 * is [Effort.LOW]: Muse Spark always reasons first and the reasoning counts in `max_tokens`, so at
 * Meta's default a short receipt's JSON was cut off (2026-10-27); low still reasons, and is the
 * lowest Meta accepts. Temperature 0, because the same receipt should read the same way twice.
 *
 * Every failure is an outcome, never an exception, so Kadent can fall back to its on-device reader
 * on any of them. The one exception is reading no pages, which is a bug in the caller.
 */
class ReceiptReader(private val ai: AiClient, private val model: String = Models.SPARK) {

    suspend fun read(pages: List<ReceiptPage>, context: ReadingContext): ReadOutcome {
        require(pages.isNotEmpty()) { "A receipt has at least one page" }

        val content = pages.map { page ->
            when (page) {
                is ReceiptPage.Photo -> ContentBlock.Image(MediaSource.base64(page.mediaType, page.bytes))
                is ReceiptPage.Pdf -> ContentBlock.Document(MediaSource.base64("application/pdf", page.bytes))
            }
        } + ContentBlock.Text(ReceiptSchema.instructions(context))

        val request = MessagesRequest(
            model = model,
            maxTokens = MAX_TOKENS,
            system = ReceiptSchema.SYSTEM,
            messages = listOf(Message.user(*content.toTypedArray())),
            temperature = 0.0,
            outputConfig = OutputConfig.jsonSchema(ReceiptSchema.schema(context), Effort.LOW),
        )

        val response = when (val result = ai.send(request)) {
            is AiResult.Failed -> return ReadOutcome.AiFailed(result)
            is AiResult.Answered -> result.response
        }
        when (response.stopReason) {
            StopReason.MAX_TOKENS -> return ReadOutcome.Unread(UnreadReason.CUT_OFF, "The answer was cut off at $MAX_TOKENS tokens.")
            StopReason.REFUSAL -> return ReadOutcome.Unread(UnreadReason.REFUSED, "The model declined to read it.")
        }

        val wire = try {
            AiJson.decodeFromString(ReceiptSchema.Wire.serializer(), response.text)
        } catch (unreadable: SerializationException) {
            return ReadOutcome.Unread(UnreadReason.UNREADABLE, unreadable.message ?: "Not the schema's JSON.")
        } catch (unreadable: IllegalArgumentException) {
            return ReadOutcome.Unread(UnreadReason.UNREADABLE, unreadable.message ?: "Not the schema's JSON.")
        }
        if (!wire.isReceipt) return ReadOutcome.NotAReceipt

        return try {
            ReadOutcome.Read(wire.toReceipt(context), response.usage)
        } catch (bad: BadAmount) {
            ReadOutcome.Unread(UnreadReason.UNREADABLE, "An amount is not two decimal places: ${bad.text}")
        }
    }

    private fun ReceiptSchema.Wire.toReceipt(context: ReadingContext) = ReadReceipt(
        store = store,
        storeNumber = storeNumber,
        address = address,
        postalCode = postalCode,
        storePhone = storePhone,
        boughtAt = boughtAt?.let(ReceiptDate::parse),
        transactionId = transactionId,
        lines = lines.map { line ->
            ReadLine(
                tillText = line.tillText,
                name = line.name,
                quantity = line.quantity,
                priceMinor = cents(line.price),
                unitPriceMinor = line.unitPrice?.let(::cents),
                unitLabel = line.unitLabel,
                weight = line.weight,
                code = line.code,
                plu = line.plu,
                // The schema lists the household's own names, but a model can still stray from an
                // enum. A name Kadent does not have would file nowhere, so it is dropped.
                aisle = line.aisle?.takeIf { it in context.aisles },
                envelope = line.envelope?.takeIf { it in context.envelopes },
                isVoid = line.void,
            )
        },
        subtotalMinor = subtotal?.let(::cents),
        taxes = taxes.map { tax ->
            ReadTax(
                kind = TaxKind.entries.firstOrNull { it.name == tax.kind } ?: TaxKind.OTHER,
                rateLabel = tax.rateLabel,
                amountMinor = cents(tax.amount),
            )
        },
        feesMinor = fees?.let(::cents),
        depositMinor = deposit?.let(::cents),
        totalMinor = total?.let(::cents),
    )

    private class BadAmount(val text: String) : Exception()

    private fun cents(text: String): Long = Money.toMinor(text) ?: throw BadAmount(text)

    private companion object {
        /**
         * Room for a long receipt's JSON (a 60-line Costco receipt is a few thousand tokens) and the
         * reasoning before it, which was 233 to 475 tokens on a short one. The function's ceiling.
         */
        const val MAX_TOKENS = 16_384
    }
}
