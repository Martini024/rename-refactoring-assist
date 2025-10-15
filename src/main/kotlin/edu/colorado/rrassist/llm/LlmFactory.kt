package edu.colorado.rrassist.llm

import edu.colorado.rrassist.settings.Provider
import edu.colorado.rrassist.settings.RRAssistAppSettings
import edu.colorado.rrassist.settings.RRAssistConfig

object LlmFactory {
    fun create(cfg: RRAssistConfig? = null): LlmClient {
        val effective = cfg ?: RRAssistAppSettings.getInstance().state
        effective.provider.let { p ->
            return when (p) {
                Provider.OPENAI  -> OpenAiClient(effective)
                Provider.OLLAMA -> OllamaClient(effective)
            }
        }
    }
}