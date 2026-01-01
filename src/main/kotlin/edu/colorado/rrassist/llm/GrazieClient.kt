package edu.colorado.rrassist.llm

import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.openai.OpenAiChatModel
import edu.colorado.rrassist.settings.RRAssistConfig
import edu.colorado.rrassist.utils.JsonMapperProvider
import java.time.Duration

/**
 * Client for the Grazie LLM service, which exposes an OpenAI-compatible API.
 * 
 * Grazie uses a "profile" parameter (e.g., "openai-gpt-4o-mini") instead of "model",
 * but the API format is compatible with OpenAI's chat completions endpoint.
 * 
 * Since Grazie may not support OpenAI's structured outputs (response_format with json_schema),
 * this client injects JSON format instructions directly into the system prompt.
 * 
 * Example endpoint: http://localhost:8000/v1
 * Example profile: openai-gpt-4o-mini
 */
class GrazieClient(private val cfg: RRAssistConfig) : LlmClient() {
    override fun createModel(): ChatModel {
        val logRequests = System.getenv("LLM_LOG_REQUEST")?.toBoolean() ?: false
        val logResponses = System.getenv("LLM_LOG_RESPONSE")?.toBoolean() ?: false

        // Grazie doesn't require an API key for local deployments,
        // but we provide a dummy value if empty since langchain4j requires one
        val key = cfg.apiKey.trim().ifEmpty { "grazie-local" }

        return OpenAiChatModel.builder()
            .baseUrl(cfg.baseUrl.trim())                  // e.g., http://localhost:8000/v1
            .apiKey(key)
            .modelName(cfg.model)                         // Grazie uses profile name, e.g., "openai-gpt-4o-mini"
            .temperature(cfg.temperature)
            .timeout(Duration.ofSeconds(cfg.timeoutSeconds.toLong()))
            .logRequests(logRequests)
            .logResponses(logResponses)
            .build()
    }

    /**
     * Override chat to inject JSON schema instructions into the prompt,
     * since Grazie doesn't support OpenAI's structured outputs feature.
     */
    override fun <T : Any> chat(request: ChatRequest, type: Class<T>): T {
        // Build a modified request without responseFormat, but with JSON instructions in the system message
        val modifiedRequest = buildRequestWithJsonInstructions(request)
        
        val response = model.chat(modifiedRequest)
        recordTokenUsage(response.tokenUsage())
        val output = response.aiMessage().text()
        return JsonMapperProvider.mapper.readValue(output, type)
    }

    private fun buildRequestWithJsonInstructions(request: ChatRequest): ChatRequest {
        val jsonInstructions = """

IMPORTANT: You MUST respond with valid JSON only. No markdown, no explanations, just pure JSON.
Your response must follow this exact schema:
{
  "suggestions": [
    {
      "name": "suggestedName",
      "rationale": "Brief explanation for why this name is appropriate",
      "confidence": 0.85
    }
  ]
}

Rules:
- "name" (required): The suggested variable/identifier name as a string
- "rationale" (required): A brief explanation for the suggestion as a string
- "confidence" (required): A number between 0 and 1 indicating your confidence level
- Return an array of suggestions, ordered by confidence (highest first)
"""

        // Find and modify the system message to include JSON instructions
        val modifiedMessages = request.messages().map { message ->
            if (message is SystemMessage) {
                SystemMessage(message.text() + jsonInstructions)
            } else {
                message
            }
        }

        // Build new request without responseFormat
        return ChatRequest.builder()
            .messages(modifiedMessages)
            .build()
    }
}
