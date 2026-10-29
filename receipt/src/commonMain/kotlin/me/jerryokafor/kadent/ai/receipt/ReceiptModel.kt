package me.jerryokafor.kadent.ai.receipt

// What a receipt was read as. These are this library's own types, not Kadent's: Kadent converts a
// ReadReceipt into its own receipt model, so this repo never needs Kadent's code to build.
//
// Money is in cents (minor units) as a Long, never a Double, the same rule Kadent keeps: a float
// cannot hold 0.10 exactly, and a receipt scanner would make that mistake on every line.

/**
 * A receipt as Muse Spark read it. Every field is what the paper printed, or null when it printed
 * none or the model could not read it. Nothing here is worked out from the other fields, except
 * [checks].
 */
data class ReadReceipt(
    val store: String?,
    val storeNumber: String?,
    val address: String?,
    /** `T6T 0C1`: upper case, one space, when the model followed the instructions. */
    val postalCode: String?,
    val storePhone: String?,
    /** As printed, in the store's own local time. Kadent places it in its calendar. */
    val boughtAt: ReceiptDate?,
    /** The till's own id for the purchase (a Costco `Whse/Trm/Trn` line), as printed. */
    val transactionId: String?,
    val lines: List<ReadLine>,
    val subtotalMinor: Long?,
    val taxes: List<ReadTax>,
    /** Environmental and recycling fees, summed. Kadent files these as an "other" tax line. */
    val feesMinor: Long?,
    /** Container deposits, summed. Never tax: a deposit comes back when the bottle does. */
    val depositMinor: Long?,
    val totalMinor: Long?,
) {
    val checks: ReceiptChecks get() = ReceiptChecks.of(this)
}

/**
 * One priced line.
 *
 * - [tillText] is the line as printed, abbreviations and all: what a person compares with the paper.
 * - [name] is what the item is, in plain words: `SHRD CHEDDAR 16Z` is "Shredded cheddar · 16oz".
 * - [priceMinor] is what the line cost in total. On `LIMES 4 @ 0.30` it is 120, and
 *   [unitPriceMinor] 30 is the shelf price of one; [quantity] is 4.
 * - [weight] is the weighed amount as printed (`1.12`), kept as text because it is shown and never
 *   calculated with. A weighed line is one item.
 * - A void or refund line has [isVoid] and a negative [priceMinor], so the lines still sum.
 * - [aisle] and [envelope] are from the lists Kadent passed in ([ReadingContext]), or null.
 */
data class ReadLine(
    val tillText: String,
    val name: String,
    val quantity: Int,
    val priceMinor: Long,
    val unitPriceMinor: Long?,
    val unitLabel: String?,
    val weight: String?,
    val code: String?,
    val plu: String?,
    val aisle: String?,
    val envelope: String?,
    val isVoid: Boolean,
)

/** One tax line: `GST 5% 0.35` is GST, "5%", 35. The rate stays text; nothing calculates with it. */
data class ReadTax(val kind: TaxKind, val rateLabel: String?, val amountMinor: Long)

/**
 * Canada's sales taxes, kept by kind because an Ontario receipt prints only HST, which includes the
 * federal 5%. PST covers Manitoba's RST, QST is Quebec's TVQ, and OTHER is a combined line that
 * cannot be split.
 */
enum class TaxKind { GST, HST, PST, QST, OTHER }

/** A day, and the time when the receipt printed one. No time zone: the store's own clock. */
data class ReceiptDate(val year: Int, val month: Int, val day: Int, val hour: Int? = null, val minute: Int? = null) {
    /** `2026-10-27`, as the instructions and the schema write a day. */
    val isoDay: String get() = "$year-${month.pad()}-${day.pad()}"

    companion object {
        /**
         * The day, then the time if any, **seconds and a zone accepted and dropped**. Most tills print
         * `18:19:31`, and the model copies it more often than not: the strict form lost 15 of 45 dates
         * in the reader scores (#274), and an undated receipt is counted on the day it is filed. The
         * seconds are dropped because the app keeps the minute; a `Z` or an offset is dropped because
         * the time is the store's clock as printed, never converted.
         */
        private val ISO = Regex("""(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::\d{2}(?:\.\d+)?)?(?:Z|[+-]\d{2}:?\d{2})?)?""")

        /** `2026-10-25`, `2026-10-25T14:32` or `2026-10-25T14:32:07`; null for anything else, or a day that cannot exist. */
        fun parse(text: String): ReceiptDate? {
            val match = ISO.matchEntire(text.trim()) ?: return null
            val (y, mo, d, h, mi) = match.destructured
            val date = ReceiptDate(y.toInt(), mo.toInt(), d.toInt(), h.toIntOrNull(), mi.toIntOrNull())
            val valid = date.month in 1..12 && date.day in 1..31 &&
                (date.hour ?: 0) in 0..23 && (date.minute ?: 0) in 0..59
            return date.takeIf { valid }
        }

        private fun Int.pad() = toString().padStart(2, '0')
    }
}

/**
 * How well a reading adds up. The library measures; Kadent decides what is good enough, for
 * instance whether a receipt may file itself without Review.
 *
 * - [linesMinor] is every line summed, voids included as negatives.
 * - [subtotalGapMinor] is the printed subtotal less [linesMinor]. Zero on most receipts; a store
 *   that counts deposits or fees into its subtotal leaves those here.
 * - [totalGapMinor] is the printed total less lines, tax, fees and deposit. Zero means every cent
 *   on the paper was read; a positive gap is usually a missed line.
 *
 * A gap is null when the receipt printed no subtotal or no total, because then nothing was measured.
 */
data class ReceiptChecks(val linesMinor: Long, val subtotalGapMinor: Long?, val totalGapMinor: Long?) {
    companion object {
        fun of(receipt: ReadReceipt): ReceiptChecks {
            val lines = receipt.lines.sumOf { it.priceMinor }
            val charges = receipt.taxes.sumOf { it.amountMinor } + (receipt.feesMinor ?: 0) + (receipt.depositMinor ?: 0)
            return ReceiptChecks(
                linesMinor = lines,
                subtotalGapMinor = receipt.subtotalMinor?.let { it - lines },
                totalGapMinor = receipt.totalMinor?.let { it - lines - charges },
            )
        }
    }
}

/**
 * Amounts as the model writes them, `"5.49"`, into cents. Text with exactly two decimal places and
 * nothing else, so a misread `6.999` or a `$5.49` is refused rather than guessed at.
 */
object Money {
    /** The pattern the schema gives the model for every amount. */
    const val PATTERN = "^-?[0-9]+\\.[0-9]{2}$"

    private val REGEX = Regex(PATTERN)

    fun toMinor(text: String): Long? {
        if (!REGEX.matches(text)) return null
        val negative = text.startsWith("-")
        val (whole, cents) = text.removePrefix("-").split(".")
        val minor = whole.toLong() * 100 + cents.toLong()
        return if (negative) -minor else minor
    }
}
