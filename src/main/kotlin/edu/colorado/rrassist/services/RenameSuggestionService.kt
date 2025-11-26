package edu.colorado.rrassist.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import edu.colorado.rrassist.llm.LlmFactory
import edu.colorado.rrassist.settings.RRAssistAppSettings
import edu.colorado.rrassist.settings.RRAssistConfig
import edu.colorado.rrassist.strategies.FileLevelLlmStrategy
import edu.colorado.rrassist.strategies.HistoryFirstFileLlmStrategy
import edu.colorado.rrassist.strategies.MethodLevelLlmStrategy
import edu.colorado.rrassist.strategies.HistoryFirstStrategy
import edu.colorado.rrassist.strategies.HistoryOnlyStrategy
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
    METHOD_LEVEL_LLM,
    HISTORY_FIRST_METHOD_LEVEL,
    HISTORY_ONLY,
    FILE_LEVEL_LLM,
    HISTORY_FIRST_FILE_LLM;

    fun isHistoryBased(): Boolean =
        when (this) {
            HISTORY_FIRST_METHOD_LEVEL,
            HISTORY_ONLY,
            HISTORY_FIRST_FILE_LLM -> true

            else -> false
        }

    fun isFileBased(): Boolean =
        when (this) {
            FILE_LEVEL_LLM,
            HISTORY_FIRST_FILE_LLM -> true

            else -> false
        }
}

@Serializable
data class RenameSuggestionsEnvelope(
    val suggestions: List<RenameSuggestion>
)

@Service(Service.Level.APP)
class RenameSuggestionService(
    strategyType: StrategyType = StrategyType.HISTORY_FIRST_METHOD_LEVEL, cfg: RRAssistConfig? = null
) {
    private var llm = LlmFactory.create(cfg ?: RRAssistAppSettings.getInstance().state)
    val llmClient get() = llm

    private var strategy: RenameSuggestionStrategy = createStrategy(strategyType)

    private fun createStrategy(type: StrategyType): RenameSuggestionStrategy =
        when (type) {
            StrategyType.METHOD_LEVEL_LLM -> MethodLevelLlmStrategy(llm)
            StrategyType.HISTORY_FIRST_METHOD_LEVEL -> HistoryFirstStrategy(llm)
            StrategyType.HISTORY_ONLY -> HistoryOnlyStrategy(llm)
            StrategyType.FILE_LEVEL_LLM -> FileLevelLlmStrategy(llm)
            StrategyType.HISTORY_FIRST_FILE_LLM -> HistoryFirstFileLlmStrategy(llm)
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
