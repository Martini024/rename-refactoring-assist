package edu.colorado.rrassist.strategies

import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.request.ResponseFormat
import dev.langchain4j.model.chat.request.ResponseFormatType
import dev.langchain4j.model.chat.request.json.JsonArraySchema
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import dev.langchain4j.model.chat.request.json.JsonSchema
import edu.colorado.rrassist.llm.LlmClient
import edu.colorado.rrassist.services.RenameSuggestion
import edu.colorado.rrassist.services.RenameSuggestionsEnvelope
import kotlinx.serialization.Serializable
import kotlin.isNaN

/**
 * Minimal context we’ll feed to the LLM. Add/remove fields as you like.
 */
@Serializable
data class RenameContext(
    var symbolName: String,
    var symbolKind: String,        // e.g., "localVariable", "parameter", "function", "class"
    var language: String,          // e.g., "Kotlin"
    var type: String,  // e.g., "Int", "MutableList<String>"
    var scopeHint: String? = null, // e.g., "inside for-loop of fetchUsers()"
    var filePath: String,
    var offset: Int = 0,
    var projectStyle: String? = null, // e.g., "lowerCamelCase for locals; UpperCamelCase for classes"
    var purposeHint: String? = null,  // short natural-language purpose, if known
    var codeSnippet: String? = null,  // small excerpt around the symbol
    var relatedNames: List<String> = emptyList(), // names of nearby vars/functions to keep consistent
    var conflictNames: List<String> = emptyList()
)

interface RenameSuggestionStrategy {
    var llm: LlmClient

    fun buildSystemMessage(): String {
        return """
            You are a code rename assistant.
            Produce STRICT JSON conforming to the provided JSON Schema.
            No prose, no markdown, no extra fields.
            """.trimIndent()
    }

    fun buildUserPrompt(context: RenameContext, topK: Int): String {
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

        Context:eac
        - related names: $related
        - snippet: ${context.codeSnippet ?: "(omitted)"}
        """.trimIndent()
    }

    suspend fun suggest(context: RenameContext, topK: Int = 5): RenameSuggestionsEnvelope {
        return invokeLlm(context, topK)
    }

    private fun normalize(s: RenameSuggestion) = s.copy(
        confidence = when (val c = s.confidence) {
            null -> 0.0
            else -> when {
                c.isNaN() -> 0.0
                c > 1.0 && c <= 100.0 -> c / 100.0
                else -> c.coerceIn(0.0, 1.0)
            }
        }
    )

    suspend fun invokeLlm(context: RenameContext, topK: Int = 5): RenameSuggestionsEnvelope {
        val sys = SystemMessage(buildSystemMessage())
        val user = UserMessage(buildUserPrompt(context, topK))

        val jsonSchema = JsonSchema.builder()
            .name("RenameSuggestionsEnvelope")
            .rootElement(
                JsonObjectSchema.builder()
                    .addProperty(
                        "suggestions", JsonArraySchema.builder()
                            .items(
                                JsonObjectSchema.builder()
                                    .addStringProperty("name")
                                    .addStringProperty("rationale")
                                    .addNumberProperty("confidence", "In range of 0 to 1")
                                    .required("name", "rationale", "confidence")
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()

        val responseFormat = ResponseFormat.builder()
            .type(ResponseFormatType.JSON)
            .jsonSchema(jsonSchema)
            .build()

        val request = ChatRequest.builder()
            .messages(listOf(sys, user))
            .responseFormat(responseFormat)
            .build()
        // Let the model reply with JSON text (per System instructions).
        // We return it raw so the caller gets a JSON string immediately.
        val response = llm.chat(request, RenameSuggestionsEnvelope::class.java)
        val sorted = response.suggestions
            .map(::normalize)
            .sortedByDescending { it.confidence }
        return RenameSuggestionsEnvelope(sorted)
    }
}