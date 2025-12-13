# rename-refactoring-assist

## IntelliJ Plugin for Smart Local Variable Rename Suggestions

`rename-refactoring-assist` enhances IntelliJ’s built-in *Rename Refactoring* with **intelligent, LLM-powered name recommendations**.
It provides high-quality rename suggestions for **local variables** by analyzing the surrounding code context and optionally incorporating project rename history.

![Build](https://github.com/Martini024/rename-refactoring-assist/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

---

## 🌐 High-Level Architecture

The plugin has two core extension points:

### 1. 🔌 `LlmClient` — A Unified API for Any LLM Provider

```kotlin
abstract class LlmClient {
    protected abstract fun createModel(): ChatModel
    open val model: ChatModel by lazy { createModel() }
}
```

### What this enables

* Plug in **OpenAI**, **Ollama**, **local models**, **enterprise models**, or any LangChain4j `ChatModel`.
* Provider-specific config is fully encapsulated.
* The rest of the plugin uses the same unified interface.

### Example: Creating a custom provider

```kotlin
class MyOllamaClient : LlmClient() {
    override fun createModel() =
        OllamaChatModel(baseUrl, modelName)
}
```

Just implement `createModel()` and the system instantly supports the new model.

---

### 2. 🧠 `RenameSuggestionStrategy` — Pluggable Prompt & Logic Engine

```kotlin
abstract class RenameSuggestionStrategy(
    var llm: LlmClient,
    var snippetMode: PsiContextExtractor.CodeSnippetMode
) {
    init { PsiContextExtractor.setSnippetMode(snippetMode) }

    open fun buildSystemMessage(): String { ... }
    open fun buildUserPrompt(context: RenameContext, topK: Int): String { ... }

    open suspend fun suggest(context: RenameContext, topK: Int = 5) =
        invokeLlm(context, topK)

    suspend fun invokeLlm(context: RenameContext, topK: Int = 5): RenameSuggestionsEnvelope { ... }
}
```

### What this enables

* Full control over:
  * system message
  * user prompt
  * rename logic
  * ranking
  * response shaping
* Perfect for experimenting with:
  * dynamic/static context heuristics
  * method-level context
  * project-specific conventions
  * domain-specific naming rules

### Example: Custom Strategy

```kotlin
class DomainAwareStrategy(llm: LlmClient) : RenameSuggestionStrategy(llm) {
    override fun buildSystemMessage() =
        "You are an expert Java naming assistant…"

    override fun buildUserPrompt(context: RenameContext, topK: Int) =
        "Given the following snippet:\n${context.code}\nSuggest better variable names…"
}
```

---

Here is an updated README section that **cleanly incorporates the IntelliJ “Run CLI” configuration option**, integrated smoothly with the existing CLI description.

You can drop this directly into your README.

---

## 🧪 CLI for Offline Experiments

Besides the IntelliJ plugin itself, the project includes a standalone **CLI runner** for batch-testing rename strategies, evaluating model quality, or comparing providers.

You can run it in **two ways**:

---

### ✅ Option 1 — Run from IntelliJ (Recommended)

The project includes a built-in **IntelliJ “Run CLI” configuration**.

1. Open **Run → Edit Configurations…**
2. Select **Run CLI**
3. Adjust command-line arguments in the **Program Arguments** field
4. Run the configuration normally

This is the easiest way to iterate on different models, strategies, or input files without touching your shell.

---

### ✅ Option 2 — Run from the command line

```
./gradlew run --args="--input input.json 
                      [--outputDir out_dir] 
                      [--topK 5] 
                      [--provider OPENAI|OLLAMA] 
                      [--baseUrl URL] 
                      [--model MODEL]
                      [--temperature 0.5] 
                      [--timeout 60] 
                      [--apiKey sk-...] 
                      [--strategies STRAT1,STRAT2,...]"
```

### Notes

* If `--outputDir` is omitted, results are written next to the input file.
* `--strategies` is a comma-separated list (e.g., `METHOD_LEVEL_LLM,HISTORY_ONLY`).
* If omitted, the default strategy is: **`METHOD_LEVEL_LLM`**.
* Provider arguments automatically instantiate the appropriate `LlmClient`.
