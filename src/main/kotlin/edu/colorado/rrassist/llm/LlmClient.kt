package edu.colorado.rrassist.llm

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.output.TokenUsage
import edu.colorado.rrassist.utils.JsonMapperProvider

enum class Role { System, User, Assistant }

data class ChatMsg(val role: Role, val content: String)

data class TokenUsageAggregate(
    var promptTokens: Long = 0,
    var completionTokens: Long = 0,
    var totalTokens: Long = 0
)

abstract class LlmClient {
    protected abstract fun createModel(): ChatModel

    open val model: ChatModel by lazy { createModel() }

    val usageAggregate = TokenUsageAggregate()

    private fun recordUsage(usage: TokenUsage?) {
        if (usage == null) return
        val logUsage = System.getenv("LLM_LOG_USAGE")?.toBoolean() ?: false
        if (logUsage) {
            println("=== Token Usage ===")
            println("Prompt tokens:     ${usage.inputTokenCount()}")
            println("Completion tokens: ${usage.outputTokenCount()}")
            println("Total tokens:      ${usage.totalTokenCount()}")
        }
        usageAggregate.promptTokens += usage.inputTokenCount()
        usageAggregate.completionTokens += usage.outputTokenCount()
        usageAggregate.totalTokens += usage.totalTokenCount()
    }

    fun printTotalUsage(prefix: String = "=== Total Token Usage ===") {
        val agg = usageAggregate
        println(prefix)
        println("Prompt tokens:     ${agg.promptTokens}")
        println("Completion tokens: ${agg.completionTokens}")
        println("Total tokens:      ${agg.totalTokens}")
    }

    fun chat(messages: List<ChatMsg>): String {
        val lc4jMessages = messages.map { it.toLc4j() }
        val response = model.chat(lc4jMessages)
        return response.aiMessage().text()
    }

    fun <T : Any> chat(request: ChatRequest, type: Class<T>): T {
        val response = model.chat(request)
        val usage = response.tokenUsage()
        recordUsage(usage)
        val output = response.aiMessage().text()
        return JsonMapperProvider.mapper.readValue(output, type)
    }

    private fun ChatMsg.toLc4j(): ChatMessage = when (role) {
        Role.System -> SystemMessage(content)
        Role.User -> UserMessage(content)
        Role.Assistant -> AiMessage(content)
    }
}
