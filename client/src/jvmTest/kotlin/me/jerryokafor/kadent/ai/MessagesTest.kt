package me.jerryokafor.kadent.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MessagesTest {
    @Test
    fun aRequestIsWrittenInTheMessagesFormat() {
        val schema = buildJsonObject { put("type", "object") }
        val request = MessagesRequest(
            model = Models.SPARK,
            maxTokens = 512,
            system = "Read the receipt.",
            messages = listOf(
                Message.user(
                    ContentBlock.Image(MediaSource.base64("image/jpeg", byteArrayOf(1, 2, 3))),
                    ContentBlock.Text("What does this receipt say?"),
                ),
            ),
            outputConfig = OutputConfig.jsonSchema(schema),
        )

        val written = AiJson.encodeToJsonElement(MessagesRequest.serializer(), request).jsonObject

        val expected = AiJson.parseToJsonElement(
            """
            {
              "model": "muse-spark-1.3",
              "max_tokens": 512,
              "messages": [{
                "role": "user",
                "content": [
                  {"type": "image", "source": {"type": "base64", "media_type": "image/jpeg", "data": "AQID"}},
                  {"type": "text", "text": "What does this receipt say?"}
                ]
              }],
              "system": "Read the receipt.",
              "output_config": {"format": {"type": "json_schema", "schema": {"type": "object"}}}
            }
            """,
        )
        assertEquals(expected, written)
    }

    @Test
    fun aToolResultIsWrittenWithItsCallsId() {
        val block: ContentBlock = ContentBlock.ToolResult(toolUseId = "call_1", content = "3 left")
        val written = AiJson.encodeToJsonElement(ContentBlock.serializer(), block)
        assertEquals(
            AiJson.parseToJsonElement("""{"type": "tool_result", "tool_use_id": "call_1", "content": "3 left"}"""),
            written,
        )
    }

    @Test
    fun anAnswerIsReadWithItsTextAndToolCalls() {
        val response = AiJson.decodeFromString(
            MessagesResponse.serializer(),
            """
            {
              "id": "msg_1", "type": "message", "role": "assistant", "model": "muse-spark-1.3",
              "stop_reason": "tool_use", "stop_sequence": null,
              "content": [
                {"type": "text", "text": "Checking."},
                {"type": "tool_use", "id": "call_1", "name": "rhythm", "input": {"item": "milk"}}
              ],
              "usage": {"input_tokens": 12, "output_tokens": 7}
            }
            """,
        )

        assertEquals("Checking.", response.text)
        assertEquals(StopReason.TOOL_USE, response.stopReason)
        val call = assertIs<ContentBlock.ToolUse>(response.content[1])
        assertEquals("rhythm", call.name)
        assertEquals(JsonPrimitive("milk"), call.input["item"])
        assertEquals(Usage(inputTokens = 12, outputTokens = 7), response.usage)
    }

    @Test
    fun aBlockTypeThisLibraryDoesNotKnowIsKeptAndWrittenBackUnchanged() {
        val raw = """{"type": "server_tool_use", "id": "s1", "name": "web_search", "input": {"query": "eggs"}}"""
        val block = AiJson.decodeFromString(ContentBlock.serializer(), raw)

        val unknown = assertIs<ContentBlock.Unknown>(block)
        assertEquals("server_tool_use", unknown.type)
        assertEquals(AiJson.parseToJsonElement(raw), AiJson.encodeToJsonElement(ContentBlock.serializer(), block))
    }

    @Test
    fun anAnswerWithJsonOutputIsReadAsAnObject() {
        val response = MessagesResponse(
            id = "msg_1",
            model = Models.SPARK,
            content = listOf(ContentBlock.Text("""{"store": "No Frills"}""")),
            stopReason = StopReason.END_TURN,
            usage = Usage(1, 1),
        )
        assertEquals(JsonObject(mapOf("store" to JsonPrimitive("No Frills"))), response.json())
    }
}
