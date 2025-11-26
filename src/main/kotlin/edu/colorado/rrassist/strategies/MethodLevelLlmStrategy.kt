package edu.colorado.rrassist.strategies

import edu.colorado.rrassist.llm.LlmClient

class MethodLevelLlmStrategy(llm: LlmClient) : RenameSuggestionStrategy(llm) {
}