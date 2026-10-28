package me.jerryokafor.kadent.ai

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The agent loop: tools run until one ends it, every answer goes back, and every way it can stop is said. */
class ToolLoopTest {
    private val request = MessagesRequest(
        model = Models.SPARK,
        maxTokens = 4096,
        messages = listOf(Message.user(ContentBlock.Text("Plan the trip."))),
        tools = listOf(Tool("list_items", "The list.", JsonObject(emptyMap()))),
    )

    private fun call(id: String, name: String, input: Map<String, String> = emptyMap()) =
        ContentBlock.ToolUse(id, name, JsonObject(input.mapValues { JsonPrimitive(it.value) }))

    private fun answer(vararg content: ContentBlock, stop: String = StopReason.TOOL_USE, usage: Usage = Usage(100, 10)) =
        AiResult.Answered(MessagesResponse("msg", Models.SPARK, content.toList(), stop, usage))

    /** Answers in order, and keeps every request it was sent. */
    private class Scripted(vararg answers: AiResult) : AiClient {
        private val left = answers.toMutableList()
        val sent = mutableListOf<MessagesRequest>()
        override suspend fun send(request: MessagesRequest): AiResult {
            sent += request
            return left.removeAt(0)
        }
    }

    private val hidden = ContentBlock.Unknown("redacted_thinking", JsonObject(mapOf("type" to JsonPrimitive("redacted_thinking"), "data" to JsonPrimitive("x"))))

    @Test
    fun toolsRunUntilTheFinishingOneAndEachAnswerGoesBack() = runTest {
        val ai = Scripted(
            answer(hidden, call("t1", "list_items")),
            answer(call("t2", "propose_trip", mapOf("note" to "ok"))),
        )
        val ran = mutableListOf<String>()

        val outcome = ToolLoop(ai).run(request) { use ->
            ran += use.name
            if (use.name == "propose_trip") ToolAnswer.Finish else ToolAnswer.Result("[\"milk\"]")
        }

        val finished = assertIs<LoopOutcome.Finished>(outcome)
        assertEquals("propose_trip", finished.call.name)
        assertEquals(listOf("list_items", "propose_trip"), ran)
        assertEquals(Usage(200, 20), finished.usage, "summed over both turns")
        val second = ai.sent[1].messages
        assertEquals(3, second.size, "the question, the model's turn, the tool's answer")
        assertEquals(listOf(hidden, call("t1", "list_items")), second[1].content, "the model's turn sent back whole, its reasoning included")
        assertEquals(ContentBlock.ToolResult("t1", "[\"milk\"]"), second[2].content.single())
        assertEquals(second + Message.assistant(listOf(call("t2", "propose_trip", mapOf("note" to "ok")))), finished.messages)
    }

    @Test
    fun severalCallsInOneTurnAreAllAnsweredInOneMessage() = runTest {
        val ai = Scripted(
            answer(call("a", "list_items"), call("b", "budget_left")),
            answer(call("c", "propose_trip")),
        )

        ToolLoop(ai).run(request) { use -> if (use.name == "propose_trip") ToolAnswer.Finish else ToolAnswer.Result(use.name) }

        val results = ai.sent[1].messages.last().content
        assertEquals(listOf(ContentBlock.ToolResult("a", "list_items"), ContentBlock.ToolResult("b", "budget_left")), results)
    }

    @Test
    fun anErrorFromAToolIsSentAsOneAndTheLoopGoesOn() = runTest {
        val ai = Scripted(answer(call("a", "price_history")), answer(call("b", "propose_trip")))

        val outcome = ToolLoop(ai).run(request) { use ->
            if (use.name == "propose_trip") ToolAnswer.Finish else ToolAnswer.Result("No item has that id.", isError = true)
        }

        assertIs<LoopOutcome.Finished>(outcome)
        assertEquals(ContentBlock.ToolResult("a", "No item has that id.", isError = true), ai.sent[1].messages.last().content.single())
    }

    @Test
    fun aClientFailureEndsTheLoopWithIt() = runTest {
        val failure = AiResult.Failed(AiResult.Reason.RATE_LIMITED, "slow down")
        assertEquals(LoopOutcome.Failed(failure), ToolLoop(Scripted(failure)).run(request) { ToolAnswer.Finish })
    }

    @Test
    fun anAnswerWithNoToolCallStops() = runTest {
        val outcome = ToolLoop(Scripted(answer(ContentBlock.Text("Here is your plan."), stop = StopReason.END_TURN))).run(request) { ToolAnswer.Finish }
        assertEquals(LoopOutcome.Stopped(StopReason.END_TURN), outcome)
    }

    @Test
    fun aCutOffAnswerStopsEvenWithACallInIt() = runTest {
        val outcome = ToolLoop(Scripted(answer(call("a", "list_items"), stop = StopReason.MAX_TOKENS))).run(request) { ToolAnswer.Result("x") }
        assertEquals(LoopOutcome.Stopped(StopReason.MAX_TOKENS), outcome)
    }

    @Test
    fun aModelThatNeverFinishesIsStoppedAfterItsTurns() = runTest {
        val ai = Scripted(*Array(3) { answer(call("t$it", "list_items")) })

        val outcome = ToolLoop(ai, maxTurns = 3).run(request) { ToolAnswer.Result("again") }

        assertEquals(LoopOutcome.Stopped(ToolLoop.TOO_MANY_TURNS), outcome)
        assertEquals(3, ai.sent.size)
        assertTrue(ai.sent.all { it.tools == request.tools })
    }
}
