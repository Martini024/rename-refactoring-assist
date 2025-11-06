package edu.colorado.rrassist.llm

import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.openai.OpenAiChatModel
import edu.colorado.rrassist.settings.RRAssistConfig
import java.time.Duration

class OpenAiClient(private val cfg: RRAssistConfig) : LlmClient {
    override val model: ChatModel by lazy {
        val logRequests = System.getenv("LLM_LOG_REQUEST")?.toBoolean() ?: false
        val logResponses = System.getenv("LLM_LOG_RESPONSE")?.toBoolean() ?: false

        val key = (cfg.apiKey).trim()
        require(key.isNotEmpty()) { "OpenAI API key is required for OpenAiClient" }

        OpenAiChatModel.builder()
            .baseUrl(cfg.baseUrl)
            .apiKey(cfg.apiKey)
            .modelName(cfg.model)                     // e.g., "gpt-4o-mini"
            .temperature(cfg.temperature)
            .timeout(Duration.ofSeconds(cfg.timeoutSeconds.toLong()))
            .logRequests(logRequests)
            .logResponses(logResponses)
            .build()
    }
}