package edu.colorado.rrassist.cli

import edu.colorado.rrassist.psi.JavaVarTarget
import edu.colorado.rrassist.psi.PsiContextExtractor
import edu.colorado.rrassist.psi.SourceLocator
import edu.colorado.rrassist.services.RenameContext
import edu.colorado.rrassist.services.RenameSuggestion
import edu.colorado.rrassist.settings.RRAssistConfig
import edu.colorado.rrassist.services.RenameSuggestionService
import edu.colorado.rrassist.settings.Provider
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.BufferedWriter
import kotlinx.datetime.Clock
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.time.DurationUnit
import kotlin.time.toDuration

// ----- Output models -----
@Serializable
data class PerTargetOutput(
    val path: String,
    val locators: List<SourceLocator>,
    @SerialName("old_name")
    val oldName: String? = null,
    @SerialName("new_name")
    val newName: String? = null,
    val ctx: RenameContext? = null,
    val suggestions: List<RenameSuggestion>? = null,
    val error: String? = null
)

@Serializable
data class CliOutput(
    val results: List<PerTargetOutput>
)

suspend fun main(args: Array<String>) {
    val opts = parseArgs(args)

    if (!opts.containsKey("input")) {
        System.err.println(
            """
            Usage:
              ./gradlew :cli:run --args="--input input.json [--outputDir out_dir] [--topK 5] 
                                     [--provider OPENAI|OLLAMA] [--baseUrl URL] [--model MODEL]
                                     [--temperature 0.5] [--timeout 60] [--apiKey sk-...]"
            
            Notes:
              - If --outputDir is not provided, output will be written to the same directory as the input file.
            """.trimIndent()
        )
        return
    }

    val inputPath = opts["input"]!!
    val baseOutputDir = opts["outputDir"] ?: File(inputPath).parent ?: "."

    // Timestamped run folder under outputDir
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    val timestamp = "%04d%02d%02d_%02d%02d%02d".format(
        now.year, now.monthNumber, now.dayOfMonth, now.hour, now.minute, now.second
    )
    val runDir: Path = Paths.get(baseOutputDir, "rrassist_$timestamp")
    Files.createDirectories(runDir)

    val resultsPath = runDir.resolve("results.json")
    val errorsPath = runDir.resolve("errors.json")

    val topK = opts["topK"]?.toIntOrNull() ?: 5

    val cfg = RRAssistConfig(
        provider = opts["provider"]?.let { Provider.valueOf(it) } ?: Provider.OLLAMA,
        baseUrl = opts["baseUrl"] ?: "http://localhost:11434",
        model = opts["model"] ?: "deepseek-coder:6.7b",
        temperature = opts["temperature"]?.toDoubleOrNull() ?: 0.5,
        timeoutSeconds = opts["timeout"]?.toIntOrNull() ?: 60,
        apiKey = opts["apiKey"] ?: "OPENAI_API_KEY"
    )

    val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    val targets: List<JavaVarTarget> = json.decodeFromString(File(inputPath).readText())
    val total = targets.size
    println("🔍 Loaded $total targets from $inputPath")

    val rename = RenameSuggestionService(cfg)

    val startTime = Clock.System.now()
    var successCount = 0
    var errorCount = 0

    resultsPath.toFile().bufferedWriter().use { okOut ->
        errorsPath.toFile().bufferedWriter().use { errOut ->
            registerShutdownHooks(okOut, errOut)

            startJsonArray(okOut)
            startJsonArray(errOut)

            var firstOk = true
            var firstErr = true

            for ((idx, t) in targets.withIndex()) {
                val itemStart = Clock.System.now()
                var ctx: RenameContext? = null
                try {
                    ctx = PsiContextExtractor.extractFromPathTarget(t)
                    val envelope = rename.suggest(ctx, topK)

                    val perTargetOk = PerTargetOutput(
                        path = t.path,
                        locators = t.locators,
                        oldName = t.oldName,
                        newName = t.newName,
                        ctx = ctx,
                        suggestions = envelope.suggestions.map {
                            RenameSuggestion(name = it.name, rationale = it.rationale, confidence = it.confidence)
                        },
                        error = null
                    )

                    writeArrayItem(okOut, json.encodeToString(perTargetOk), firstOk)
                    if (firstOk) firstOk = false
                    successCount++
                } catch (e: Exception) {
                    val perTargetErr = PerTargetOutput(
                        path = t.path,
                        locators = t.locators,
                        oldName = t.oldName,
                        newName = t.newName,
                        ctx = ctx,
                        suggestions = null,
                        error = e.message ?: e::class.java.name
                    )
                    writeArrayItem(errOut, json.encodeToString(perTargetErr), firstErr)
                    if (firstErr) firstErr = false
                    errorCount++
                }

                val itemTime = (Clock.System.now() - itemStart).inWholeSeconds
                if ((idx + 1) % 10 == 0 || idx == total - 1) {
                    val elapsed = (Clock.System.now() - startTime).inWholeSeconds
                    println(
                        "📦 Processed ${idx + 1}/$total | ✅ $successCount | ❌ $errorCount | " +
                                "⏱ ${elapsed}s elapsed | Last item ${itemTime}s"
                    )
                }

                if ((idx + 1) % 10 == 0) {
                    okOut.flush()
                    errOut.flush()
                }
            }

            safeEndArrays(okOut, errOut)
        }
    }

    val totalElapsed = (Clock.System.now() - startTime)
    val elapsedText = "%d min %.1f s".format(
        totalElapsed.inWholeMinutes,
        (totalElapsed - totalElapsed.inWholeMinutes.toDuration(DurationUnit.MINUTES)).inWholeSeconds.toDouble()
    )

    println("✅ Completed $total targets in $elapsedText")
    println("   - Successes: $successCount → ${resultsPath.toAbsolutePath()}")
    println("   - Errors:    $errorCount → ${errorsPath.toAbsolutePath()}")
}

/* ---------- Small helpers to stream a JSON array inside an object ---------- */

private fun startJsonArray(out: BufferedWriter) {
    out.append("{\n  \"results\": [\n")
}

private fun writeArrayItem(out: BufferedWriter, itemJson: String, first: Boolean) {
    if (!first) out.append(",\n")
    // indent item by two spaces so it looks nice inside "results"
    val indented = itemJson.prependIndent("  ")
    out.append(indented)
}

private fun endJsonArray(out: BufferedWriter) {
    out.append("\n  ]\n}\n")
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

// -----------------------------
// Safe shutdown handling
// -----------------------------
@Volatile private var arraysClosed = false

private fun safeEndArrays(okOut: BufferedWriter, errOut: BufferedWriter) {
    if (!arraysClosed) {
        synchronized(::arraysClosed) {
            if (!arraysClosed) {
                try {
                    endJsonArray(okOut)
                } catch (_: Exception) {}
                try {
                    endJsonArray(errOut)
                } catch (_: Exception) {}
                arraysClosed = true
            }
        }
    }
}

private fun registerShutdownHooks(okOut: BufferedWriter, errOut: BufferedWriter) {
    Runtime.getRuntime().addShutdownHook(Thread {
        try {
            println("\n⚠️  Shutdown detected. Closing JSON arrays...")
            okOut.flush()
            errOut.flush()
            safeEndArrays(okOut, errOut)
            try { okOut.close() } catch (_: Exception) {}
            try { errOut.close() } catch (_: Exception) {}
            println("✅ JSON arrays closed.")
        } catch (ex: Exception) {
            println("⚠️  Failed to close JSON properly: ${ex.message}")
        }
    })
}