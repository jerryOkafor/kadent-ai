package me.jerryokafor.kadent.ai.lists

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.jerryokafor.kadent.ai.AiClient
import me.jerryokafor.kadent.ai.AiJson
import me.jerryokafor.kadent.ai.AiResult
import me.jerryokafor.kadent.ai.ContentBlock
import me.jerryokafor.kadent.ai.Effort
import me.jerryokafor.kadent.ai.Message
import me.jerryokafor.kadent.ai.MessagesRequest
import me.jerryokafor.kadent.ai.Models
import me.jerryokafor.kadent.ai.OutputConfig
import me.jerryokafor.kadent.ai.StopReason

/**
 * Reads a sentence about the household's lists into a [ListPlan] (#275): "we're out of milk and eggs,
 * and add bananas to the Costco list". Typed, or spoken through the phone keyboard's own dictation.
 *
 * **One request, not a tool loop.** Trip planning (#273) asks for prices and budgets as the model needs
 * them; here everything the model needs, the lists and their rows, is small and known before it starts,
 * so it goes in the request and the answer is JSON in the schema's shape, which the person is waiting on.
 *
 * **The answer is checked, not trusted** ([checked]):
 * - a list the household does not have is dropped, and the schema allows only its lists' ids;
 * - an item id that is not a row of that list is read as a new item by its name;
 * - a new item whose name is a row the list already has, in any case, is that row: never a second copy;
 * - a row already on the trip is reported, not put on it again;
 * - an aisle outside the taxonomy is none, and a count under one is one;
 * - two entries for one list are one change, and a list with nothing to do is left out.
 *
 * Effort is low, as the receipt reader's is: the reasoning counts in `max_tokens` and the person is
 * waiting on the sheet.
 */
class ListSentenceReader(private val ai: AiClient, private val model: String = Models.SPARK) {

    suspend fun read(sentence: String, context: SentenceContext): SentenceOutcome {
        val request = MessagesRequest(
            model = model,
            maxTokens = MAX_TOKENS,
            system = SYSTEM,
            messages = listOf(Message.user(ContentBlock.Text(prompt(sentence, context)))),
            temperature = 0.0,
            outputConfig = OutputConfig.jsonSchema(schema(context), Effort.LOW),
        )
        val response = when (val result = ai.send(request)) {
            is AiResult.Failed -> return SentenceOutcome.Failed(result)
            is AiResult.Answered -> result.response
        }
        when (response.stopReason) {
            StopReason.MAX_TOKENS -> return SentenceOutcome.Unplanned("cut_off")
            StopReason.REFUSAL -> return SentenceOutcome.Unplanned("refused")
        }
        val wire = try {
            AiJson.decodeFromString(PlanWire.serializer(), response.text)
        } catch (unreadable: SerializationException) {
            return SentenceOutcome.Unplanned("unreadable")
        } catch (unreadable: IllegalArgumentException) {
            return SentenceOutcome.Unplanned("unreadable")
        }
        return SentenceOutcome.Planned(checked(wire, context), response.usage)
    }

    private fun checked(wire: PlanWire, context: SentenceContext): ListPlan {
        val aisles = context.aisles.toSet()
        val changes = wire.changes
            .groupBy { it.listId }
            .mapNotNull { (listId, entries) ->
                val list = context.lists.firstOrNull { it.id == listId } ?: return@mapNotNull null
                val byId = list.items.associateBy { it.id }
                val byName = list.items.associateBy { key(it.name) }
                val onTrip = mutableListOf<String>()
                val already = mutableListOf<String>()
                val added = mutableListOf<NewItem>()
                for (item in entries.flatMap { it.items }) {
                    val row = item.itemId?.let(byId::get) ?: byName[key(item.name)]
                    when {
                        row == null -> {
                            val name = item.name.trim()
                            if (name.isNotEmpty() && added.none { key(it.name) == key(name) }) {
                                added += NewItem(name, item.aisle?.takeIf { it in aisles }, item.quantity.coerceAtLeast(1))
                            }
                        }
                        row.onTrip -> if (row.id !in already) already += row.id
                        row.id !in onTrip -> onTrip += row.id
                    }
                }
                ListChange(list.id, onTrip, added, already).takeIf { it.onTrip.isNotEmpty() || it.added.isNotEmpty() || it.alreadyOnTrip.isNotEmpty() }
            }
        return ListPlan(changes, wire.note?.trim()?.ifEmpty { null })
    }

    /** A name as it is compared: `Milk 2%` and `milk 2%` are one row. */
    private fun key(name: String): String = name.trim().lowercase().replace(Regex("""\s+"""), " ")

    private fun prompt(sentence: String, context: SentenceContext): String {
        val lists = buildJsonArray {
            context.lists.forEach { list ->
                addJsonObject {
                    put("list_id", list.id)
                    put("name", list.name)
                    putJsonArray("items") {
                        list.items.forEach { item ->
                            addJsonObject {
                                put("item_id", item.id)
                                put("name", item.name)
                                put("on_trip", item.onTrip)
                            }
                        }
                    }
                }
            }
        }
        val typedOn = context.currentListId?.let { id -> context.lists.firstOrNull { it.id == id } }
        return buildString {
            appendLine("The household's lists:")
            appendLine(AiJson.encodeToString(JsonArray.serializer(), lists))
            appendLine("Aisles a new item may take: ${context.aisles.joinToString(", ")}")
            if (typedOn != null) appendLine("The sentence was typed on the list \"${typedOn.name}\".")
            appendLine()
            appendLine("The sentence:")
            append(sentence.trim())
        }
    }

    private fun schema(context: SentenceContext) = obj(
        "changes" to buildJsonObject {
            put("type", "array")
            put(
                "items",
                obj(
                    "list_id" to buildJsonObject {
                        put("type", "string")
                        put("enum", JsonArray(context.lists.map { JsonPrimitive(it.id) }))
                    },
                    "items" to buildJsonObject {
                        put("type", "array")
                        put(
                            "items",
                            obj(
                                "item_id" to nullable("string"),
                                "name" to buildJsonObject { put("type", "string") },
                                "aisle" to buildJsonObject {
                                    putJsonArray("type") { add("string"); add("null") }
                                    put("enum", JsonArray(context.aisles.map { JsonPrimitive(it) } + JsonNull))
                                },
                                "quantity" to buildJsonObject { put("type", "integer") },
                            ),
                        )
                    },
                ),
            )
        },
        "note" to nullable("string"),
    )

    /** An object whose every property is required and nothing else allowed: the strict form. */
    private fun obj(vararg properties: Pair<String, JsonElement>) = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { properties.forEach { (name, schema) -> put(name, schema) } }
        putJsonArray("required") { properties.forEach { add(it.first) } }
        put("additionalProperties", false)
    }

    private fun nullable(name: String) = buildJsonObject {
        putJsonArray("type") { add(name); add("null") }
    }

    companion object {
        /** A sentence names a handful of items; this leaves the reasoning room. */
        private const val MAX_TOKENS = 4_096

        val SYSTEM = """
            You turn one sentence from a Canadian household into changes to its shopping lists. Every item
            the sentence asks for goes on a list's next trip.

            Rules
            - Use only the lists given, by list_id. A list named in the sentence takes the items said
              with it. An item said with no list goes on the list the sentence was typed on; if none,
              on the list that already has it; if none, on the first list.
            - When the list already has the item, give its item_id and its name as the list writes it,
              even when the sentence words it differently ("milk" for "Milk 2%"). Otherwise item_id is
              null and name is the item as a shopper would write it on a list, capitalised: "Eggs".
            - aisle is one of the given aisles for a new item, or null when none fits.
            - quantity is the number the sentence says, otherwise 1.
            - Put nothing on a list that the sentence did not ask for.
            - note: one short sentence when something could not be placed, such as a list the household
              does not have, otherwise null.
        """.trimIndent()
    }
}
