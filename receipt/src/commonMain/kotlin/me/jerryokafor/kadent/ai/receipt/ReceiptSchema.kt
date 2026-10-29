package me.jerryokafor.kadent.ai.receipt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * What Muse Spark is asked to answer with, and what it is told. The schema goes in
 * `output_config.format`, so the answer is JSON in exactly this shape; [Wire] is that shape in
 * Kotlin.
 */
object ReceiptSchema {

    /**
     * The system prompt. Canadian till receipts, because Kadent's households are Canadian and the
     * tax rules below are Canada's.
     */
    val SYSTEM = """
        You read photographs and PDFs of Canadian till receipts for a household budgeting app.
        Report only what is printed. Never invent a line, a price or a store. When something is
        unreadable, use null rather than a guess.

        Lines
        - One entry per priced item line. A count (LIMES 4 @ 0.30) or a weight
          (BANANAS 1.12 kg @ 1.52/kg) is part of its item's line, never a line of its own.
        - till_text: the item line as printed, abbreviations and all, with the count or weight.
          Tills shorten words by dropping their vowels (BRWN, UNSWT, SDLS), so an unclear letter in
          such a word is more likely W or D than U, V or O.
        - name: what the item is in plain words a shopper would write on a list, with its size after
          " · " when printed: SHRD CHEDDAR 16Z is "Shredded cheddar · 16oz", KS ORG CORN is
          "Kirkland Signature organic corn". Spell out store brands: PC is President's Choice, NN is
          No Name, KS is Kirkland Signature, GV is Great Value. Write sizes in lower case with a
          space: "12 × 355 mL", "4 L", "6 rolls". Do not add a brand or size that is not printed.
        - price: the line's total as printed on the right. quantity: how many the line counted, 1
          when it printed no count; a weighed line is 1. A digit or letter printed after the price is
          the line's tax code, never a count: Costco's 1234567 RED WINE 750ML 19.99 2 is one bottle at
          19.99. A count is printed with @ or AT, and the count times its unit price is the line's
          price. Costco prints a count on its own row above the item it counts: 3 @ 4.99 over
          1234567 DISH SOAP 14.97 is three at 4.99, never a count of the item above it.
          unit_price and unit_label: the shelf price and its unit (ea, lb, kg) when printed. weight:
          the weighed number alone, "1.12", with its unit in unit_label.
        - code: the product code printed on the line, digits only. plu: a 4 or 5 digit produce
          sticker number printed with the item.
        - Loblaw prints each product of one multi-buy on its own unpriced row, with a mark such as
          (1) before its code, then one deal row, 2 @ 2/$5.00 5.00. That is one entry per product,
          quantity 1, each at an even share of the deal's total, the last taking any odd cent.
        - A fuel pump receipt is one line: its till_text is the grade printed after Grade:
          (Regular, Premium, Diesel), and its price is the Fuel Sale amount. Its GST Included is a
          tax entry, and its subtotal is null.
        - A void, refund or coupon line is its own entry with void true and a negative price.
        - Not lines: payment lines (VISA, DEBIT, CASH, CHANGE), loyalty points, savings summaries,
          subtotal, tax, total, recycling fees and container deposits.

        Charges
        - taxes: one entry per tax line printed, kind GST, HST, PST, QST (TVQ is QST, RST is PST),
          or OTHER for a combined line. rate_label is the rate as printed, such as "5%".
        - fees: environmental or recycling fees (ECO FEE, ENVIRO FEE, EHF), summed.
        - deposit: container deposits (DEPOSIT, DEP, BOTTLE DEP), summed.
        - subtotal and total as printed.

        Every amount is text with two decimals and no currency sign: "5.49", "-2.00".
        bought_at is the printed date and time as 2026-10-25T14:32, without seconds, or just the day when
        no time is printed. Real Canadian Superstore prints a two-digit year first: 26/09/22 is
        2026-09-22, never 2022-09-26. Costco prints the month first: 06/09/2026 is 2026-06-09, never
        2026-09-06. postal_code is upper case with one space.
        If the image is not a till receipt, answer is_receipt false with no lines.
    """.trimIndent()

    /** The user message that follows the pages: the household's own lists, and today. */
    fun instructions(context: ReadingContext): String = buildString {
        appendLine("Read this receipt.")
        appendLine("For each line, pick the aisle it is shelved in from: ${context.aisles.joinToString()}; null if none fits.")
        appendLine("Pick the budget envelope it was paid from: ${context.envelopes.joinToString()}; null if none fits.")
        context.today?.let { appendLine("Today is ${it.isoDay}. A receipt is never dated after today.") }
    }.trimEnd()

    fun schema(context: ReadingContext): JsonObject = obj(
        "is_receipt" to type("boolean"),
        "store" to nullable("string"),
        "store_number" to nullable("string"),
        "address" to nullable("string"),
        "postal_code" to nullable("string"),
        "store_phone" to nullable("string"),
        "bought_at" to nullable("string"),
        "transaction_id" to nullable("string"),
        "lines" to buildJsonObject {
            put("type", "array")
            put(
                "items",
                obj(
                    "till_text" to type("string"),
                    "name" to type("string"),
                    "quantity" to type("integer"),
                    "price" to money(nullable = false),
                    "unit_price" to money(nullable = true),
                    "unit_label" to nullable("string"),
                    "weight" to nullable("string"),
                    "code" to nullable("string"),
                    "plu" to nullable("string"),
                    "aisle" to choice(context.aisles),
                    "envelope" to choice(context.envelopes),
                    "void" to type("boolean"),
                ),
            )
        },
        "subtotal" to money(nullable = true),
        "taxes" to buildJsonObject {
            put("type", "array")
            put(
                "items",
                obj(
                    "kind" to buildJsonObject {
                        put("type", "string")
                        putJsonArray("enum") { TaxKind.entries.forEach { add(it.name) } }
                    },
                    "rate_label" to nullable("string"),
                    "amount" to money(nullable = false),
                ),
            )
        },
        "fees" to money(nullable = true),
        "deposit" to money(nullable = true),
        "total" to money(nullable = true),
    )

    /** An object whose every property is required and nothing else allowed: the strict form. */
    private fun obj(vararg properties: Pair<String, JsonElement>) = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { properties.forEach { (name, schema) -> put(name, schema) } }
        putJsonArray("required") { properties.forEach { add(it.first) } }
        put("additionalProperties", false)
    }

    private fun type(name: String) = buildJsonObject { put("type", name) }

    private fun nullable(name: String) = buildJsonObject {
        putJsonArray("type") { add(name); add("null") }
    }

    private fun money(nullable: Boolean) = buildJsonObject {
        if (nullable) putJsonArray("type") { add("string"); add("null") } else put("type", "string")
        put("pattern", Money.PATTERN)
    }

    /** One of [options], or null. */
    private fun choice(options: List<String>) = buildJsonObject {
        putJsonArray("type") { add("string"); add("null") }
        put("enum", JsonArray(options.map { JsonPrimitive(it) } + JsonNull))
    }

    /** The schema's shape, read back. Amounts stay text here; [ReceiptReader] turns them into cents. */
    @Serializable
    internal data class Wire(
        @SerialName("is_receipt") val isReceipt: Boolean,
        val store: String? = null,
        @SerialName("store_number") val storeNumber: String? = null,
        val address: String? = null,
        @SerialName("postal_code") val postalCode: String? = null,
        @SerialName("store_phone") val storePhone: String? = null,
        @SerialName("bought_at") val boughtAt: String? = null,
        @SerialName("transaction_id") val transactionId: String? = null,
        val lines: List<Line> = emptyList(),
        val subtotal: String? = null,
        val taxes: List<Tax> = emptyList(),
        val fees: String? = null,
        val deposit: String? = null,
        val total: String? = null,
    ) {
        @Serializable
        data class Line(
            @SerialName("till_text") val tillText: String,
            val name: String,
            val quantity: Int = 1,
            val price: String,
            @SerialName("unit_price") val unitPrice: String? = null,
            @SerialName("unit_label") val unitLabel: String? = null,
            val weight: String? = null,
            val code: String? = null,
            val plu: String? = null,
            val aisle: String? = null,
            val envelope: String? = null,
            val void: Boolean = false,
        )

        @Serializable
        data class Tax(
            val kind: String,
            @SerialName("rate_label") val rateLabel: String? = null,
            val amount: String,
        )
    }
}
