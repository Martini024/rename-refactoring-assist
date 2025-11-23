package edu.colorado.rrassist.strategies

import com.intellij.psi.codeStyle.NameUtil
import edu.colorado.rrassist.llm.LlmClient
import edu.colorado.rrassist.psi.JavaVarTarget
import edu.colorado.rrassist.psi.PsiContextExtractor
import edu.colorado.rrassist.services.RenameSuggestion
import edu.colorado.rrassist.services.RenameSuggestionsEnvelope
import kotlin.math.abs

enum class NamingConvention {
    CONSTANT_CASE, // SCREAMING_SNAKE_CASE: MY_VAR
    SNAKE_CASE,    // snake_case: my_var
    PASCAL_CASE,   // PascalCase (UpperCamelCase): ClassName
    CAMEL_CASE,    // camelCase (LowerCamelCase): methodName
    UNKNOWN;

    companion object {
        fun detect(name: String): NamingConvention {
            if (name.isEmpty()) return UNKNOWN

            // CONSTANT_CASE: ALL_CAPS, underscores allowed, digits allowed
            // Includes single-word uppercase constants (MAX, ID)
            if (name.matches(Regex("^[A-Z][A-Z0-9_]*$"))) {
                // If it’s all caps but contains no underscore, we still treat as CONSTANT_CASE
                return CONSTANT_CASE
            }

            // SNAKE_CASE: lowercase_with_underscores
            if (name.matches(Regex("^[a-z][a-z0-9_]*$")) && name.contains('_')) {
                return SNAKE_CASE
            }

            // PASCAL_CASE: UpperCamelCase (no underscores)
            if (name.matches(Regex("^[A-Z][A-Za-z0-9]*$")) && !name.contains('_')) {
                return PASCAL_CASE
            }

            // CAMEL_CASE: lowerCamelCase (no underscores)
            if (name.matches(Regex("^[a-z][A-Za-z0-9]*$")) && !name.contains('_')) {
                return CAMEL_CASE
            }

            return UNKNOWN
        }

        fun convert(name: String, to: NamingConvention): String {
            if (name.isEmpty()) return name

            // IntelliJ's tokenization (splits and normalizes, removes _)
            val tokens = NameUtil.splitNameIntoWords(name).map { it.lowercase() }

            if (tokens.isEmpty()) return name

            return when (to) {
                CONSTANT_CASE ->
                    tokens.joinToString("_") { it.uppercase() }

                SNAKE_CASE ->
                    tokens.joinToString("_") { it.lowercase() }

                PASCAL_CASE ->
                    tokens.joinToString("") {
                        it.replaceFirstChar { c -> c.titlecase() }
                    }

                CAMEL_CASE ->
                    tokens.first().lowercase() +
                            tokens.drop(1).joinToString("") {
                                it.replaceFirstChar { c -> c.titlecase() }
                            }

                UNKNOWN ->
                    name
            }
        }
    }
}

data class RenameHistory(
    val beforeName: String,
    val afterName: String,
    val beforeConventions: Enum<NamingConvention>,
    val afterConventions: Enum<NamingConvention>,
    val filePath: String,
    val type: String,
    val commitId: String? = null
)


open class HistoryFirstStrategy(override var llm: LlmClient) : RenameSuggestionStrategy {

    companion object {
        protected const val THRESHOLD_HIGH: Double = 0.75
        protected const val THRESHOLD_LOW: Double = 0.4

        @JvmStatic
        protected val renameHistories = mutableListOf<RenameHistory>()

        @JvmStatic
        private val historiesByCommit: MutableMap<String?, MutableList<RenameHistory>> =
            mutableMapOf()

        fun addHistory(entry: RenameHistory) {
            // latest first
            renameHistories.add(0, entry)
        }

        fun buildHistory(targets: List<JavaVarTarget>) {
            targets.forEach { t ->
                // 1. Safe extraction of context
                val ctx: RenameContext? = try {
                    PsiContextExtractor.extractFromPathTarget(t)
                } catch (e: Throwable) {
                    // Log and skip this target
                    println("⚠️ Failed to extract context for ${t.path}: ${e.message}")
                    null
                }

                if (ctx == null) return@forEach

                val before = t.oldName
                val after = t.newName
                if (before.isNullOrBlank() || after.isNullOrBlank() || before == after) {
                    return@forEach
                }

                val history = RenameHistory(
                    beforeName = before,
                    afterName = after,
                    beforeConventions = NamingConvention.detect(before),
                    afterConventions = NamingConvention.detect(after),
                    filePath = ctx.filePath,
                    type = ctx.type,
                    commitId = t.commitId()
                )

                // 6. Add to global history + commit index
                val list = historiesByCommit.getOrPut(history.commitId) { mutableListOf() }
                list.add(0, history)
            }

        }

        fun useCommitHistory(commitId: String?, ctx: RenameContext) {
            if (commitId == null) {
                renameHistories.clear()
                return
            }
            val listForCommit = historiesByCommit[commitId].orEmpty()
            renameHistories.clear()
            renameHistories.addAll(
                listForCommit.filter { hist ->
                    !(hist.filePath == ctx.filePath &&
                            hist.beforeName == ctx.symbolName &&
                            hist.type == ctx.type)
                }
            )
        }
    }

    private var rankedHistories: List<RenameHistory> = emptyList()
    private val historyEngine = HistoryPatternEngine()

    protected fun rankHistory(context: RenameContext) {
        val currentFile = context.filePath
        val currentType = context.type
        val currentOffset = context.offset

        // 1) Split into histories from the same file vs other files
        val (inFileRS, outsideRS) = renameHistories.partition { it.filePath == currentFile }

        // Helper: split a list into same-type and other-type relative to the current element
        fun List<RenameHistory>.splitByType(): Pair<List<RenameHistory>, List<RenameHistory>> =
            this.partition { it.type == currentType }

        // 2) For each file group, split by data type
        val (inFileSameType, inFileOtherType) = inFileRS.splitByType()
        val (outsideSameType, outsideOtherType) = outsideRS.splitByType()

        // 3) Sort each bucket by distance and concatenate in CARER order
        val ranked = buildList {
            addAll(inFileSameType)
            addAll(inFileOtherType)
            addAll(outsideSameType)
            addAll(outsideOtherType)
        }

        // 4) Store the ranked list back into the context
        rankedHistories = ranked
    }

    private fun applyHistoryPattern(renameHistory: RenameHistory, context: RenameContext): String? {
        val pattern = historyEngine.inferTokenEditPattern(renameHistory.beforeName, renameHistory.afterName)
        return pattern?.apply(context.symbolName)
    }

    private fun scoreSuggestion(renameHistory: RenameHistory, context: RenameContext, suggestedName: String): Double {
        fun tokenSimilarity(a: String, b: String): Double {
            val ta = NameUtil.nameToWords(a).map { it.lowercase() }.toSet()
            val tb = NameUtil.nameToWords(b).map { it.lowercase() }.toSet()

            if (ta.isEmpty() || tb.isEmpty()) return 0.0

            val intersection = ta.intersect(tb).size
            val union = ta.union(tb).size
            return intersection.toDouble() / union.toDouble()
        }

        fun typeRelationshipScore(currentType: String, historyType: String): Double {
            // Exact same type: strongest signal
            if (currentType == historyType) {
                return 0.25
            }

            // Fuzzy similarity based on type name tokens
            val similarity = tokenSimilarity(currentType, historyType)

            // If they share most of their tokens (e.g., TestingJobGraphStore vs JobGraphStore),
            // give partial credit.
            return if (similarity >= 0.6) 0.15 else 0.0
        }

        val sameFile = (renameHistory.filePath == context.filePath)
        val exactNameMatch = (context.symbolName == renameHistory.beforeName)

        // 1) Base context score: file + type
        var baseScore = 0.0

        // Same-file is a strong contextual signal
        if (sameFile) {
            baseScore += 0.40
        }

        // Same-type or inheritance relationship
        baseScore += typeRelationshipScore(context.type, renameHistory.type)

        // 2) Pattern applicability via name similarity (beforeName vs originalName)
        val similarity = tokenSimilarity(context.symbolName, renameHistory.beforeName)

        // 3) Combine base + pattern part
        val score = if (exactNameMatch) {
            // Strong pattern signal: we saw exactly the same old name before.
            // Treat this as an additive pattern confidence term.
            baseScore + 0.65
        } else {
            // For near-matches, add a softer similarity-based boost.
            baseScore + (0.15 * similarity)
        }

        return score.coerceIn(0.0, 1.0)
    }

    protected fun suggestFromHistory(context: RenameContext): RenameSuggestion? {
        var bestSuggestionConf = 0.0
        var bestSuggestionName: String? = null
        for (renameHistory in renameHistories) {
            val suggestionName = applyHistoryPattern(renameHistory, context)
            if (renameHistory.afterName == context.symbolName ||
                suggestionName == null ||
                context.conflictNames.contains(suggestionName) ||
                suggestionName == context.symbolName
            ) continue
            val score = scoreSuggestion(renameHistory, context, suggestionName)
            if (score >= THRESHOLD_HIGH) {
                println(
                    "HIGH MATCH for symbol='${context.symbolName}': " +
                            "suggestion='$suggestionName', history=${renameHistory.beforeName} ${renameHistory.afterName}, score=$score,"
                )
                return RenameSuggestion(name = suggestionName, confidence = score)
            }
            if (score > bestSuggestionConf && score >= THRESHOLD_LOW) {
                bestSuggestionConf = score
                bestSuggestionName = suggestionName
            }
        }
        if (bestSuggestionName != null) {
            return RenameSuggestion(name = bestSuggestionName, confidence = bestSuggestionConf)
        }
        return null
    }

    override suspend fun suggest(context: RenameContext, topK: Int): RenameSuggestionsEnvelope {
        if (renameHistories.isNotEmpty()) {
            rankHistory(context)
            suggestFromHistory(context)?.let { suggestion ->
                suggestion.confidence?.let {
                    if (it >= THRESHOLD_HIGH) {
                        return RenameSuggestionsEnvelope(listOf(suggestion))
                    }
                    context.relatedNames += suggestion.name
                }
            }
        }
        return invokeLlm(context, topK)
    }
}