package edu.colorado.rrassist.llm

import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.ollama.OllamaChatModel
import edu.colorado.rrassist.settings.RRAssistConfig
import java.time.Duration

class OllamaClient(private val cfg: RRAssistConfig) : LlmClient() {
    override fun createModel(): ChatModel {
        val logRequests = System.getenv("LLM_LOG_REQUEST")?.toBoolean() ?: false
        val logResponses = System.getenv("LLM_LOG_RESPONSE")?.toBoolean() ?: false

        return OllamaChatModel.builder()
            .baseUrl(cfg.baseUrl.removeSuffix("/v1"))   // e.g. http://localhost:11434
            .modelName(cfg.model)                       // e.g. "deepseek-coder:6.7b"
            .temperature(cfg.temperature)
            .timeout(Duration.ofSeconds(cfg.timeoutSeconds.toLong()))
            .logRequests(logRequests)
            .logResponses(logResponses)
            .build()
    }
}