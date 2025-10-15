package edu.colorado.rrassist.llm

import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.ollama.OllamaChatModel
import edu.colorado.rrassist.settings.RRAssistConfig
import java.time.Duration

class OllamaClient(private val cfg: RRAssistConfig): LlmClient {
    override val model: ChatModel by lazy {
        OllamaChatModel.builder()
            .baseUrl(cfg.baseUrl.removeSuffix("/v1"))      // e.g. http://localhost:11434
            .modelName(cfg.model)                        // e.g. "deepseek-coder:6.7b"
            .temperature(cfg.temperature)
            .timeout(Duration.ofSeconds(cfg.timeoutSeconds.toLong()))
            .build()
    }
}