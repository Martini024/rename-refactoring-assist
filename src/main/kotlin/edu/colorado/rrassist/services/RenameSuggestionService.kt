package edu.colorado.rrassist.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import edu.colorado.rrassist.llm.LlmFactory
import edu.colorado.rrassist.settings.RRAssistAppSettings
import edu.colorado.rrassist.settings.RRAssistConfig
import edu.colorado.rrassist.strategies.DefaultRenameStrategy
import edu.colorado.rrassist.strategies.HistoryFirstStrategy
import edu.colorado.rrassist.strategies.RenameContext
import edu.colorado.rrassist.strategies.RenameSuggestionStrategy
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
    HISTORY_FIRST
}

@Serializable
data class RenameSuggestionsEnvelope(
    val suggestions: List<RenameSuggestion>
)

@Service(Service.Level.APP)
class RenameSuggestionService(
    strategyType: StrategyType = StrategyType.HISTORY_FIRST, cfg: RRAssistConfig? = null
) {
    private var llm = LlmFactory.create(cfg ?: RRAssistAppSettings.getInstance().state)

    private var strategy: RenameSuggestionStrategy = createStrategy(strategyType)

    private fun createStrategy(type: StrategyType): RenameSuggestionStrategy =
        when (type) {
            StrategyType.DEFAULT_LLM -> DefaultRenameStrategy(llm)
            StrategyType.HISTORY_FIRST -> HistoryFirstStrategy(llm)
        }

    fun setStrategy(strategy: StrategyType) {
        this.strategy = createStrategy(strategy)
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
