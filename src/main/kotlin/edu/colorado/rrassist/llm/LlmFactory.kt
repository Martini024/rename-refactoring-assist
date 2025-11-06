package edu.colorado.rrassist.llm

import edu.colorado.rrassist.settings.Provider
import edu.colorado.rrassist.settings.RRAssistConfig

object LlmFactory {
    fun create(cfg: RRAssistConfig): LlmClient {
        cfg.provider.let { p ->
            return when (p) {
                Provider.OPENAI  -> OpenAiClient(cfg)
                Provider.OLLAMA -> OllamaClient(cfg)
            }
        }
    }
}