package edu.colorado.rrassist.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.JBColor
import com.intellij.ui.dsl.builder.*
import edu.colorado.rrassist.llm.LlmHealthCheck
import edu.colorado.rrassist.services.RenameSuggestionService
import javax.swing.JComponent

class RRAssistConfigurable : Configurable {
    private val settings = RRAssistAppSettings.getInstance()
    private val service = RenameSuggestionService.getInstance()
    private var local = settings.state.copy()
    private lateinit var panel: DialogPanel
    private var isModified = false

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
                row {
                    val testBtn = button("Test Connection") {}.component  // JButton
                    val statusLabel = label("").resizableColumn().component  // expands for long messages

                    testBtn.addActionListener {
                        isModified = panel.isModified()
                        panel.apply()

                        testBtn.isEnabled = false
                        statusLabel.text = "Testing…"
                        statusLabel.foreground = JBColor.GRAY

                        ApplicationManager.getApplication().executeOnPooledThread {
                            val success = LlmHealthCheck.test(local)

                            if (success) {
                                statusLabel.text = "Connection Succeeded"
                                statusLabel.foreground = JBColor(0x2E7D32, 0xA5D6A7) // green (light/dark)
                            } else {
                                statusLabel.text = "Connection Failed"
                                statusLabel.foreground = JBColor(0xC62828, 0xEF9A9A) // red (light/dark)
                            }
                            testBtn.isEnabled = true
                        }
                    }
                }
            }
            group("Model Settings") {
                row("Temperature:") {
                    spinner(0.0..2.0, 0.1)
                        .bindValue(local::temperature)
                        .comment("Controls randomness: 0 = deterministic, 1 = balanced, 2 = very creative.")
                }
                row("Timeout (seconds):") {
                    intTextField(range = 1..300)
                        .bindIntText(local::timeoutSeconds)
                        .comment("Maximum time to wait for model response. Default: 60s")
                }
            }
        }
        return panel
    }

    override fun isModified(): Boolean = panel.isModified() || isModified
    override fun apply() {
        panel.apply()
        settings.loadState(local)
        service.updateLlmClient()
        isModified = false
    }

    override fun reset() {
        local.updateFrom(settings.state)
        panel.reset()
        isModified = false
    }
}
