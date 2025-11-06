package edu.colorado.rrassist.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.util.xmlb.XmlSerializerUtil
import edu.colorado.rrassist.services.RenameSuggestionService

enum class Provider(val displayName: String) {
    OPENAI("OpenAI"),
    OLLAMA("Ollama");

    override fun toString(): String = displayName
}

data class RRAssistConfig(
    var provider: Provider = Provider.OPENAI,
    var baseUrl: String = "https://api.openai.com/v1",   // Ollama: http://localhost:11434/v1
    var model: String = "gpt-4o-mini",                   // Ollama example: "llama3.1"
    var temperature: Double = 0.5,
    var timeoutSeconds: Int = 60,
    @Transient var apiKey: String = "OPENAI_API_KEY",            // logical name for PasswordSafe
) {
    fun updateFrom(other: RRAssistConfig) = apply {
        provider = other.provider
        baseUrl = other.baseUrl
        model = other.model
        temperature = other.temperature
        timeoutSeconds = other.timeoutSeconds
        apiKey = ""
    }
}

@State(name = "RRAssistAppSettings", storages = [Storage("rrassist_app_settings.xml")])
class RRAssistAppSettings : PersistentStateComponent<RRAssistConfig> {
    private var state: RRAssistConfig = RRAssistConfig()

    init {
        state.apiKey = Secrets.load(SecretKey.API_KEY).orEmpty()
    }

    override fun getState(): RRAssistConfig = state
    override fun loadState(newState: RRAssistConfig) {
        XmlSerializerUtil.copyBean(newState, state)

        val key = newState.apiKey.trim()
        if (key.isNotEmpty()) {
            state.apiKey = key
            ApplicationManager.getApplication().executeOnPooledThread {
                val stored = Secrets.load(SecretKey.API_KEY).orEmpty()
                if (stored != key) Secrets.save(SecretKey.API_KEY, key)
            }
        } else {
            state.apiKey = Secrets.load(SecretKey.API_KEY).orEmpty()
        }
        RenameSuggestionService.getInstance().updateLlmClient()
    }

    companion object { fun getInstance() = RRAssistAppSettings() }
}