package edu.colorado.rrassist.strategies

import edu.colorado.rrassist.llm.LlmClient
import edu.colorado.rrassist.psi.PsiContextExtractor

class HistoryFirstFileLlmStrategy(llm: LlmClient) :
    HistoryFirstStrategy(llm, PsiContextExtractor.CodeSnippetMode.WHOLE_FILE) {
}