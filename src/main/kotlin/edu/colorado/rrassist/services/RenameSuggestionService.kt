package edu.colorado.rrassist.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import edu.colorado.rrassist.llm.LlmFactory
import edu.colorado.rrassist.settings.RRAssistAppSettings
import edu.colorado.rrassist.settings.RRAssistConfig
import edu.colorado.rrassist.stratigies.DefaultRenameStrategy
import edu.colorado.rrassist.stratigies.RenameContext
import edu.colorado.rrassist.stratigies.RenameSuggestionStrategy
import kotlinx.serialization.Serializable

/**
 * JSON output schema from the LLM.
 * The call returns a String containing exactly this JSON envelope.
 */
@Serializable
data class RenameSuggestion(
    val name: String,
    val rationale: String? = null,
    val confidence: Double? = null
)

enum class StrategyType {
    DEFAULT_LLM,
}

@Serializable
data class RenameSuggestionsEnvelope(
    val suggestions: List<RenameSuggestion>
)

@Service(Service.Level.APP)
class RenameSuggestionService(
    strategyType: StrategyType = StrategyType.DEFAULT_LLM, cfg: RRAssistConfig? = null
) {
    private var llm = LlmFactory.create(cfg ?: RRAssistAppSettings.getInstance().state)

    private val strategy: RenameSuggestionStrategy = when (strategyType) {
        StrategyType.DEFAULT_LLM ->
            DefaultRenameStrategy(llm)  // your LLM strategy
    }

    fun updateLlmClient() {
        llm = LlmFactory.create(RRAssistAppSettings.getInstance().state)
        strategy.llm = llm
        print("updateLlmClient: ${RRAssistAppSettings.getInstance().state}")
    }

    suspend fun suggest(ctx: RenameContext, topK: Int = 5): RenameSuggestionsEnvelope {
        return strategy.suggest(ctx, topK)
    }

    companion object {
        fun getInstance() = service<RenameSuggestionService>()
    }
}
