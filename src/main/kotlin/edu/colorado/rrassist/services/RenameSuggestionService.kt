package edu.colorado.rrassist.services

import com.intellij.openapi.components.*
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.request.ResponseFormat
import dev.langchain4j.model.chat.request.ResponseFormatType
import dev.langchain4j.model.chat.request.json.*
import edu.colorado.rrassist.llm.LlmFactory
import kotlinx.serialization.Serializable


/**
 * Minimal context we’ll feed to the LLM. Add/remove fields as you like.
 */
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

/**
 * JSON output schema from the LLM.
 * The call returns a String containing exactly this JSON envelope.
 */
@Serializable
data class RenameSuggestion(
    val name: String,
    val rationale: String? = null,
    val confidence: Double? = null
)

@Serializable
data class RenameSuggestionsEnvelope(
    val suggestions: List<RenameSuggestion>
)

@Service(Service.Level.APP)
class RenameSuggestionService() {
    private val llm = LlmFactory.create()

    suspend fun suggest(context: RenameContext, topK: Int = 5): RenameSuggestionsEnvelope {
        val sys = SystemMessage(
            """
            You are a code rename assistant.
            Produce STRICT JSON conforming to the provided JSON Schema.
            No prose, no markdown, no extra fields.
            """.trimIndent()
        )
        val user = UserMessage(buildUserPrompt(context, topK))

        val jsonSchema = JsonSchema.builder()
            .name("RenameSuggestionsEnvelope")
            .rootElement(
                JsonObjectSchema.builder()
                    .addProperty("suggestions", JsonArraySchema.builder()
                        .items( JsonObjectSchema.builder()
                            .addStringProperty("name")
                            .addStringProperty("rationale")
                            .addNumberProperty("confidence", "In range of 0 to 1")
                            .required("name", "rationale", "confidence")
                            .build())
                        .build())
                    .build())
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
        return llm.chat(request, RenameSuggestionsEnvelope::class.java)
    }

    private fun buildUserPrompt(ctx: RenameContext, topK: Int): String {
        val related = if (ctx.relatedNames.isNotEmpty())
            ctx.relatedNames.joinToString()
        else "(none)"

        return """
        TASK: Propose up to $topK concise, idiomatic ${ctx.language} names for the following symbol.
        The goal is to make each name clear, consistent, and type-appropriate.
        DO NOT repeat the original name.
        Each suggestion must follow ${ctx.projectStyle ?: "idiomatic ${ctx.language}"} conventions.
        Provide only names that match the symbol's kind and role.

        Target Symbol:
        - name: ${ctx.symbolName}
        - kind: ${ctx.symbolKind}
        - type: ${ctx.type ?: "(unknown)"}
        - scope: ${ctx.scopeHint ?: "(none)"}
        - file: ${ctx.filePath ?: "(unknown)"}
        - purpose: ${ctx.purposeHint ?: "(none provided)"}

        Context:
        - related names: $related
        - snippet: ${ctx.codeSnippet ?: "(omitted)"}
        """.trimIndent()
    }

    companion object { fun getInstance() = service<RenameSuggestionService>() }
}
