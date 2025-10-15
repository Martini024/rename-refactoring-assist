package edu.colorado.rrassist.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.util.xmlb.XmlSerializerUtil

enum class Provider(val displayName: String) {
    OPENAI("OpenAI"),
    OLLAMA("Ollama");

    override fun toString(): String = displayName
}

data class RRAssistConfig(
    var provider: Provider = Provider.OPENAI,
    var baseUrl: String = "https://api.openai.com/v1",   // Ollama: http://localhost:11434/v1
    var model: String = "gpt-4o-mini",                   // Ollama example: "llama3.1"
    var temperature: Double = 0.2,
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
@Service(Service.Level.APP)
class RRAssistAppSettings : PersistentStateComponent<RRAssistConfig> {
    private var state: RRAssistConfig = RRAssistConfig()

    override fun getState(): RRAssistConfig = state
    override fun loadState(newState: RRAssistConfig) {
        XmlSerializerUtil.copyBean(newState, state)

        val key = newState.apiKey.trim()
        state.apiKey = ""

        if (key.isNotEmpty()) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val stored = Secrets.load(SecretKey.API_KEY).orEmpty()
                if (key != stored) {
                    Secrets.save(SecretKey.API_KEY, key)
                }
            }
        }
    }

    companion object { fun getInstance() = service<RRAssistAppSettings>() }
}