package edu.colorado.rrassist.stratigies

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
    val symbolName: String,
    val symbolKind: String,        // e.g., "localVariable", "parameter", "function", "class"
    val language: String,          // e.g., "Kotlin"
    val type: String? = null,  // e.g., "Int", "MutableList<String>"
    val scopeHint: String? = null, // e.g., "inside for-loop of fetchUsers()"
    val filePath: String? = null,
    val projectStyle: String? = null, // e.g., "lowerCamelCase for locals; UpperCamelCase for classes"
    val purposeHint: String? = null,  // short natural-language purpose, if known
    val codeSnippet: String? = null,  // small excerpt around the symbol
    val relatedNames: List<String> = emptyList() // names of nearby vars/functions to keep consistent
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

    fun buildUserPrompt(context: RenameContext, topK: Int = 5): String

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