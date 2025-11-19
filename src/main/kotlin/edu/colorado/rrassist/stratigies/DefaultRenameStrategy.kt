package edu.colorado.rrassist.stratigies

import edu.colorado.rrassist.llm.LlmClient

class DefaultRenameStrategy(override var llm: LlmClient) : RenameSuggestionStrategy {
    override fun buildUserPrompt(context: RenameContext, topK: Int): String {
        val related = if (context.relatedNames.isNotEmpty())
            context.relatedNames.joinToString()
        else "(none)"

        return """
        TASK: Propose up to $topK concise, idiomatic ${context.language} names for the following symbol.
        The goal is to make each name clear, consistent, and type-appropriate.
        DO NOT repeat the original name.
        Each suggestion must follow ${context.projectStyle ?: "idiomatic ${context.language}"} conventions.
        Provide only names that match the symbol's kind and role.

        Target Symbol:
        - name: ${context.symbolName}
        - kind: ${context.symbolKind}
        - type: ${context.type ?: "(unknown)"}
        - scope: ${context.scopeHint ?: "(none)"}
        - file: ${context.filePath ?: "(unknown)"}
        - purpose: ${context.purposeHint ?: "(none provided)"}

        Context:
        - related names: $related
        - snippet: ${context.codeSnippet ?: "(omitted)"}
        """.trimIndent()
    }
}