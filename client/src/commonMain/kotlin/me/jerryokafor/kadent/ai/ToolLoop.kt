package me.jerryokafor.kadent.ai

/** What a tool call answered: text for the model's next turn, or the end of the loop. */
sealed interface ToolAnswer {
    /** Sent back as the call's `tool_result`. [isError] tells the model the call itself was wrong. */
    data class Result(val content: String, val isError: Boolean = false) : ToolAnswer

    /** This call is the answer the loop was run for; its input is the result. Nothing is sent back. */
    data object Finish : ToolAnswer
}

sealed interface LoopOutcome {
    /**
     * A tool answered [ToolAnswer.Finish] for [call]. [messages] is the whole conversation, ending with
     * the model's turn that made the call, so a caller can continue it: answer [call] with a
     * `tool_result` and add what the person said next.
     */
    data class Finished(val call: ContentBlock.ToolUse, val messages: List<Message>, val usage: Usage) : LoopOutcome

    /** No answer from the client: the client's own reasons, unchanged. */
    data class Failed(val failure: AiResult.Failed) : LoopOutcome

    /**
     * The model stopped without finishing: [reason] is its stop reason (`end_turn` with no tool call,
     * `max_tokens`, `refusal`), or [ToolLoop.TOO_MANY_TURNS].
     */
    data class Stopped(val reason: String) : LoopOutcome
}

/**
 * The agent loop over the Messages API: send, run each tool the model calls, send the answers back,
 * until one call finishes it (#273).
 *
 * **The finishing tool is how an agent answers.** Its `input_schema` is the shape of the answer, so the
 * model fills in a schema as it would for `output_config`, but in the same turn it can still call
 * another tool first. Meta's `tool_choice` cannot force a named tool, so the caller says which call
 * finishes by answering [ToolAnswer.Finish].
 *
 * **Each of the model's turns is sent back whole**, its `redacted_thinking` blocks included (kept as
 * [ContentBlock.Unknown]), because the API checks that a tool result follows the turn that asked for it
 * as that turn was written.
 *
 * Calls in one turn are answered in order, all in one user message. Every request keeps the tools, the
 * system prompt and the settings of [request]; only the messages grow.
 */
class ToolLoop(private val ai: AiClient, private val maxTurns: Int = 8) {

    suspend fun run(request: MessagesRequest, answer: suspend (ContentBlock.ToolUse) -> ToolAnswer): LoopOutcome {
        var messages = request.messages
        var usage = Usage(0, 0)
        repeat(maxTurns) {
            val response = when (val result = ai.send(request.copy(messages = messages))) {
                is AiResult.Failed -> return LoopOutcome.Failed(result)
                is AiResult.Answered -> result.response
            }
            usage = Usage(usage.inputTokens + response.usage.inputTokens, usage.outputTokens + response.usage.outputTokens)
            val calls = response.toolCalls
            // Cut off mid-call, the call's input may be incomplete JSON the API has already patched up.
            if (response.stopReason == StopReason.MAX_TOKENS || calls.isEmpty()) {
                return LoopOutcome.Stopped(response.stopReason ?: StopReason.END_TURN)
            }
            messages = messages + Message.assistant(response.content)
            val results = mutableListOf<ContentBlock>()
            for (call in calls) {
                when (val answered = answer(call)) {
                    ToolAnswer.Finish -> return LoopOutcome.Finished(call, messages, usage)
                    is ToolAnswer.Result -> results += ContentBlock.ToolResult(call.id, answered.content, answered.isError.takeIf { it })
                }
            }
            messages = messages + Message(Role.USER, results)
        }
        return LoopOutcome.Stopped(TOO_MANY_TURNS)
    }

    companion object {
        /** [LoopOutcome.Stopped]'s reason when the model used every turn and never finished. */
        const val TOO_MANY_TURNS = "too_many_turns"
    }
}
