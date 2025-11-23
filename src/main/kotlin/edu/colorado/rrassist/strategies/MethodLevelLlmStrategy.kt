package edu.colorado.rrassist.strategies

import edu.colorado.rrassist.llm.LlmClient

class MethodLevelLlmStrategy(override var llm: LlmClient) : RenameSuggestionStrategy {
}