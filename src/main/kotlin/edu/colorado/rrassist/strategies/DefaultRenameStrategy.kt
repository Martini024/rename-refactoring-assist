package edu.colorado.rrassist.strategies

import edu.colorado.rrassist.llm.LlmClient

class DefaultRenameStrategy(override var llm: LlmClient) : RenameSuggestionStrategy {
}