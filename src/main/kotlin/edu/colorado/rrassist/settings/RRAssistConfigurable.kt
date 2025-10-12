package edu.colorado.rrassist.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.*
import javax.swing.JComponent

class RRAssistConfigurable : Configurable {
    private val settings = RRAssistAppSettings.getInstance()
    private var local = settings.state.copy()
    private lateinit var panel: DialogPanel

    override fun getDisplayName() = "RRAssist / LLM"

    override fun createComponent(): JComponent {
        panel = panel {
            group("Provider") {
                row("Provider:") {
                    comboBox(Provider.entries)
                        .bindItem(local::provider.toNullableProperty())
                }
                row("Base URL:") {
                    textField()
                        .bindText(local::baseUrl)
                        .comment("OpenAI: https://api.openai.com/v1 · Ollama: http://localhost:11434/v1")
                }
                row("Model:") {
                    textField()
                        .bindText(local::model)
                        .comment("Example: gpt-4o-mini (OpenAI) · llama3.1 (Ollama)")
                }
                row("API Key:") {
                    passwordField()
                        .columns(30)
                        .bindText(local::apiKey)
                }
            }
        }
        return panel
    }

    override fun isModified(): Boolean = panel.isModified()
    override fun apply() {
        panel.apply()
        settings.loadState(local)
    }
    override fun reset() {
        local.updateFrom(settings.state)
        panel.reset()
    }
}