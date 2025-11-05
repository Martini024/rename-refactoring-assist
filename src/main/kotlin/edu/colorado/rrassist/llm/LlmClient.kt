package edu.colorado.rrassist.llm

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import edu.colorado.rrassist.utils.JsonMapperProvider

enum class Role { System, User, Assistant }

data class ChatMsg(val role: Role, val content: String)

interface LlmClient {
    val model: ChatModel

    suspend fun chat(messages: List<ChatMsg>): String {
        val lc4jMessages = messages.map { it.toLc4j() }
        val response = model.chat(lc4jMessages)
        return response.aiMessage().text()
    }

    suspend fun <T : Any> chat(request: ChatRequest, type: Class<T>): T {
        val response = model.chat(request)
        val output = response.aiMessage().text()
        return JsonMapperProvider.mapper.readValue(output, type)
    }

    private fun ChatMsg.toLc4j(): ChatMessage = when (role) {
        Role.System    -> SystemMessage(content)
        Role.User      -> UserMessage(content)
        Role.Assistant -> AiMessage(content)
    }
}
