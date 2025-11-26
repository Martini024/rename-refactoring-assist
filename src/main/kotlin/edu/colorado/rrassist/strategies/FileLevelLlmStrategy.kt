package edu.colorado.rrassist.strategies

import edu.colorado.rrassist.llm.LlmClient
import edu.colorado.rrassist.psi.PsiContextExtractor

class FileLevelLlmStrategy(llm: LlmClient) :
    RenameSuggestionStrategy(llm, PsiContextExtractor.CodeSnippetMode.WHOLE_FILE) {
}