package edu.colorado.rrassist.strategies

import edu.colorado.rrassist.llm.LlmClient
import edu.colorado.rrassist.psi.PsiContextExtractor
import edu.colorado.rrassist.services.RenameSuggestionsEnvelope

class HistoryAugmentedLlmStrategy(llm: LlmClient) :
    HistoryFirstStrategy(llm, PsiContextExtractor.CodeSnippetMode.METHOD_WITH_COMMENTS) {
    override suspend fun suggest(context: RenameContext, topK: Int): RenameSuggestionsEnvelope {
        if (renameHistories.isNotEmpty()) {
            rankHistory(context)
            suggestFromHistory(context)?.let { suggestion ->
                suggestion.confidence?.let {
                    if (it < THRESHOLD_HIGH) {
                        context.relatedNames += suggestion.rationale ?: suggestion.name
                    }
                }
            }
        }
        return invokeLlm(context, topK)
    }
}