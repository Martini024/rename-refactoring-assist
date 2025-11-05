package edu.colorado.rrassist.cli

import edu.colorado.rrassist.psi.JavaVarTarget
import edu.colorado.rrassist.psi.PsiContextExtractor
import edu.colorado.rrassist.psi.SourceLocator
import edu.colorado.rrassist.services.RenameSuggestion
import edu.colorado.rrassist.settings.RRAssistConfig
import edu.colorado.rrassist.services.RenameSuggestionService
import edu.colorado.rrassist.services.RenameSuggestionsEnvelope
import edu.colorado.rrassist.settings.Provider
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

// ----- Output models -----
@Serializable
data class PerTargetOutput(
    val path: String,
    val locator: SourceLocator,
    val suggestions: List<RenameSuggestion>? = null,
    val error: String? = null
)

@Serializable
data class CliOutput(
    val results: List<PerTargetOutput>
)

suspend fun main(args: Array<String>) {
    val opts = parseArgs(args)

    if (!opts.containsKey("input") || !opts.containsKey("output")) {
        System.err.println(
            """
            Usage:
              ./gradlew :cli:run --args="--input input.json --output output.json [--topK 5] [--provider OPENAI|OLLAMA]
                                     [--baseUrl URL] [--model MODEL] [--temperature 0.5] [--timeout 60] [--apiKey sk-...]"
            """.trimIndent()
        )
        return
    }

    val inputPath = opts["input"]!!
    val outputPath = opts["output"]!!
    val topK = opts["topK"]?.toIntOrNull() ?: 5

    val cfg = RRAssistConfig(
        provider = opts["provider"]?.let { Provider.valueOf(it) } ?: Provider.OLLAMA,
        baseUrl = opts["baseUrl"] ?: "http://localhost:11434",
        model = opts["model"] ?: "deepseek-coder:6.7b",
        temperature = opts["temperature"]?.toDoubleOrNull() ?: 0.5,
        timeoutSeconds = opts["timeout"]?.toIntOrNull() ?: 60,
        apiKey = opts["apiKey"] ?: "OPENAI_API_KEY"
    )

    // Read targets
    val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    val targets: List<JavaVarTarget> = json.decodeFromString(File(inputPath).readText())

    // Prepare rename service (simple constructor is fine for CLI use)
    val rename = RenameSuggestionService(cfg)

    val results = mutableListOf<PerTargetOutput>()

    for (t in targets) {
        try {
            // Build RenameContext from the Java var declaration (local/param/field)
            val ctx = PsiContextExtractor.extractFromPathTarget(t)

            // Ask the model for suggestions
            val envelope: RenameSuggestionsEnvelope = rename.suggest(ctx, topK)

            results += PerTargetOutput(
                path = t.path,
                locator = t.locator,
                suggestions = envelope.suggestions.map {
                    RenameSuggestion(name = it.name, rationale = it.rationale, confidence = it.confidence)
                },
                error = null
            )
        } catch (e: Exception) {
            results += PerTargetOutput(
                path = t.path,
                locator = t.locator,
                suggestions = null,
                error = e.message ?: e::class.java.name
            )
            println(e)
        }
    }

    // Write output
    val out = CliOutput(results)
    File(outputPath).writeText(json.encodeToString(out))
}

private fun parseArgs(args: Array<String>): Map<String, String> {
    val map = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val arg = args[i]
        if (arg.startsWith("--")) {
            val key = arg.removePrefix("--")
            val value = args.getOrNull(i + 1)
            if (value != null && !value.startsWith("--")) {
                map[key] = value
                i += 2
            } else {
                // flag-like argument (boolean)
                map[key] = "true"
                i += 1
            }
        } else {
            i += 1
        }
    }
    return map
}