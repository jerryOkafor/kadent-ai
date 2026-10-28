package me.jerryokafor.kadent.ai

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64

// The request and answer of Anthropic's Messages API, as Meta's Model API accepts them at
// /v1/messages. Only the parts Kadent uses are modelled; the function passes the request on as it is
// and drops nothing it names here. Meta's own list of fields is at
// https://dev.meta.ai/docs/api-reference/messages/schemas.

/**
 * The JSON every request and answer goes through. Absent fields are omitted rather than written as
 * null, and fields this library does not model are ignored, so a field Meta adds to an answer
 * never breaks an older phone.
 */
val AiJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/** The model ids the function accepts. It refuses every other id, the `-contributor` ones above all. */
object Models {
    /** Multimodal, tools and JSON output: what reads a receipt and runs an agent. */
    const val SPARK = "muse-spark-1.3"

    /** Meta's agentic model, for long tool-calling runs. A preview. */
    const val WONDER = "muse-wonder-1.0"
}

@Serializable
data class MessagesRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val messages: List<Message>,
    val system: String? = null,
    val tools: List<Tool>? = null,
    @SerialName("tool_choice") val toolChoice: ToolChoice? = null,
    val temperature: Double? = null,
    @SerialName("output_config") val outputConfig: OutputConfig? = null,
)

@Serializable
data class Message(val role: Role, val content: List<ContentBlock>) {
    companion object {
        fun user(vararg content: ContentBlock) = Message(Role.USER, content.toList())
        fun assistant(content: List<ContentBlock>) = Message(Role.ASSISTANT, content)
    }
}

@Serializable
enum class Role {
    @SerialName("user") USER,
    @SerialName("assistant") ASSISTANT,
}

/**
 * One block of a message. Written and read with its `type` (`text`, `image`, ...). A type this
 * library does not know, such as a server tool's call, is kept whole as [Unknown] and written
 * back unchanged, so a conversation that passes through a phone loses nothing.
 */
@Serializable(with = ContentBlockSerializer::class)
sealed interface ContentBlock {
    @Serializable
    data class Text(val text: String) : ContentBlock

    @Serializable
    data class Image(val source: MediaSource) : ContentBlock

    /** A PDF, read page by page by the model. A shared PDF receipt goes in as one. */
    @Serializable
    data class Document(val source: MediaSource) : ContentBlock

    /** The model asking for a tool to be run. [input] matches the tool's `input_schema`. */
    @Serializable
    data class ToolUse(val id: String, val name: String, val input: JsonObject) : ContentBlock

    /** A tool's answer, sent back in the next user message. */
    @Serializable
    data class ToolResult(
        @SerialName("tool_use_id") val toolUseId: String,
        val content: String,
        @SerialName("is_error") val isError: Boolean? = null,
    ) : ContentBlock

    @Serializable
    data class Thinking(val thinking: String) : ContentBlock

    data class Unknown(val type: String, val json: JsonObject) : ContentBlock
}

@Serializable
data class MediaSource(
    @SerialName("media_type") val mediaType: String,
    val data: String,
    val type: String = "base64",
) {
    companion object {
        fun base64(mediaType: String, bytes: ByteArray) = MediaSource(mediaType, Base64.encode(bytes))
    }
}

@Serializable
data class Tool(
    val name: String,
    val description: String,
    @SerialName("input_schema") val inputSchema: JsonObject,
)

/** `auto`, `any` or `none`. Meta ignores a named tool here, so a specific tool cannot be forced. */
@Serializable
data class ToolChoice(val type: String) {
    companion object {
        val AUTO = ToolChoice("auto")
        val ANY = ToolChoice("any")
        val NONE = ToolChoice("none")
    }
}

/** Where a JSON schema for the answer goes. Meta has no top-level `output_format`. */
@Serializable
data class OutputConfig(val format: OutputFormat) {
    companion object {
        fun jsonSchema(schema: JsonObject) = OutputConfig(OutputFormat(schema = schema))
    }
}

@Serializable
data class OutputFormat(val schema: JsonObject, val type: String = "json_schema")

@Serializable
data class MessagesResponse(
    val id: String,
    val model: String,
    val content: List<ContentBlock>,
    @SerialName("stop_reason") val stopReason: String? = null,
    val usage: Usage,
) {
    /** Every text block, joined. With a JSON schema in the request, this is the JSON. */
    val text: String get() = content.filterIsInstance<ContentBlock.Text>().joinToString("") { it.text }

    /** The tools the model asked to be run, in order. */
    val toolCalls: List<ContentBlock.ToolUse> get() = content.filterIsInstance<ContentBlock.ToolUse>()

    /** The answer as a JSON object, for a request that gave a JSON schema. Throws if it is not one. */
    fun json(): JsonObject = AiJson.parseToJsonElement(text).jsonObject
}

/** Why the model stopped. A string rather than an enum, so a reason Meta adds is not an error. */
object StopReason {
    const val END_TURN = "end_turn"
    const val MAX_TOKENS = "max_tokens"
    const val TOOL_USE = "tool_use"
    const val REFUSAL = "refusal"
}

@Serializable
data class Usage(
    @SerialName("input_tokens") val inputTokens: Int,
    @SerialName("output_tokens") val outputTokens: Int,
)

/**
 * Reads a block's `type` first and decodes the rest with that type's own serializer; writes the
 * block and puts its `type` back. Kotlin's built-in polymorphism would do the same for the known
 * types, but it fails on an unknown one rather than keeping it.
 */
internal object ContentBlockSerializer : KSerializer<ContentBlock> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ContentBlock")

    override fun deserialize(decoder: Decoder): ContentBlock {
        val input = decoder as? JsonDecoder ?: throw SerializationException("ContentBlock is JSON only")
        val json = input.decodeJsonElement().jsonObject
        val type = json["type"]?.jsonPrimitive?.content ?: throw SerializationException("A content block has no type")
        val decoded: ContentBlock? = when (type) {
            "text" -> input.json.decodeFromJsonElement(ContentBlock.Text.serializer(), json)
            "image" -> input.json.decodeFromJsonElement(ContentBlock.Image.serializer(), json)
            "document" -> input.json.decodeFromJsonElement(ContentBlock.Document.serializer(), json)
            "tool_use" -> input.json.decodeFromJsonElement(ContentBlock.ToolUse.serializer(), json)
            "tool_result" -> input.json.decodeFromJsonElement(ContentBlock.ToolResult.serializer(), json)
            "thinking" -> input.json.decodeFromJsonElement(ContentBlock.Thinking.serializer(), json)
            else -> null
        }
        return decoded ?: ContentBlock.Unknown(type, json)
    }

    override fun serialize(encoder: Encoder, value: ContentBlock) {
        val output = encoder as? JsonEncoder ?: throw SerializationException("ContentBlock is JSON only")
        val (type, body) = when (value) {
            is ContentBlock.Text -> "text" to output.json.encodeToJsonElement(ContentBlock.Text.serializer(), value)
            is ContentBlock.Image -> "image" to output.json.encodeToJsonElement(ContentBlock.Image.serializer(), value)
            is ContentBlock.Document -> "document" to output.json.encodeToJsonElement(ContentBlock.Document.serializer(), value)
            is ContentBlock.ToolUse -> "tool_use" to output.json.encodeToJsonElement(ContentBlock.ToolUse.serializer(), value)
            is ContentBlock.ToolResult -> "tool_result" to output.json.encodeToJsonElement(ContentBlock.ToolResult.serializer(), value)
            is ContentBlock.Thinking -> "thinking" to output.json.encodeToJsonElement(ContentBlock.Thinking.serializer(), value)
            is ContentBlock.Unknown -> return output.encodeJsonElement(value.json)
        }
        // `type` first, as the API's own examples write it.
        output.encodeJsonElement(JsonObject(mapOf("type" to JsonPrimitive(type)) + body.jsonObject))
    }
}
