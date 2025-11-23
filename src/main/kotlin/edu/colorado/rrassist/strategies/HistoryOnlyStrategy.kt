package edu.colorado.rrassist.strategies

import edu.colorado.rrassist.llm.LlmClient
import edu.colorado.rrassist.services.RenameSuggestionsEnvelope

class HistoryOnlyStrategy(llm: LlmClient) : HistoryFirstStrategy(llm) {
    override suspend fun suggest(context: RenameContext, topK: Int): RenameSuggestionsEnvelope {
        if (renameHistories.isNotEmpty()) {
            rankHistory(context)
            suggestFromHistory(context)?.let { suggestion ->
                suggestion.confidence?.let {
                    if (it >= THRESHOLD_HIGH) {
                        return RenameSuggestionsEnvelope(listOf(suggestion))
                    }
                }
            }
        }
        return RenameSuggestionsEnvelope(emptyList());
    }
}