package edu.colorado.rrassist.strategies

import com.intellij.psi.codeStyle.NameUtil

/**
 * Represents a single regex-based token edit pattern inferred from a past rename.
 *
 * Example:
 *   regex       = "^(.*)Vec$"
 *   replacement = "$1List"
 *
 *   apply("fieldVec")  -> "fieldList"
 *   apply("methodVec") -> "methodList"
 */
data class TokenEditPattern(
    val regex: Regex,
    val replacement: String
) {
    fun apply(name: String): String = name.replace(regex, replacement)
}

/**
 * Engine that infers token-level edit patterns from a single rename:
 *
 *   oldName -> newName
 *
 * It detects:
 *   - prefix add/remove/replace
 *   - suffix add/remove/replace
 *   - generic “middle substring” replace (less common)
 *
 * Naming-convention conversion (snake/camel/etc.) is done elsewhere.
 */
class HistoryPatternEngine {

    /**
     * Main entry point: infer a TokenEditPattern from one old→new example.
     *
     * Returns null if no meaningful token-level edit can be inferred.
     */
    fun inferTokenEditPattern(oldName: String, newName: String): TokenEditPattern? {
        val oldTokens = NameUtil.splitNameIntoWords(oldName).toList()
        val newTokens = NameUtil.splitNameIntoWords(newName).toList()

        if (oldTokens.isEmpty() || newTokens.isEmpty()) return null

        val m = oldTokens.size
        val n = newTokens.size

        // 1) Longest common token prefix
        var p = 0
        while (p < m && p < n && oldTokens[p] == newTokens[p]) {
            p++
        }

        // 2) Longest common token suffix
        var s = 0
        while (s < (m - p) && s < (n - p) &&
            oldTokens[m - 1 - s] == newTokens[n - 1 - s]
        ) {
            s++
        }

        val oldMid = oldTokens.subList(p, m - s)  // tokens removed/changed from old
        val newMid = newTokens.subList(p, n - s)  // tokens added/changed in new

        if (oldMid.isEmpty() && newMid.isEmpty()) {
            // token sequences identical → nothing to infer
            return null
        }

        return when {
            // Only new tokens added
            oldMid.isEmpty() && newMid.isNotEmpty() -> {
                when {
                    p == 0 -> buildPrefixAddPattern(newMid)
                    s == 0 -> buildSuffixAddPattern(newMid)
                    else -> buildMiddleAddPattern(oldTokens, newTokens, p, s, newMid)
                }
            }

            // Only old tokens removed
            newMid.isEmpty() && oldMid.isNotEmpty() -> {
                when {
                    p == 0 -> buildPrefixRemovePattern(oldMid)
                    s == 0 -> buildSuffixRemovePattern(oldMid)
                    else -> buildMiddleRemovePattern(oldTokens, p, s, oldMid)
                }
            }

            // Replace oldMid with newMid
            else -> {
                if (p == 0 && s > 0 && oldMid.size == 1 && newMid.size == 1) {
                    // typical suffix replace: *Vec -> *List
                    buildSuffixReplacePattern(oldMid.first(), newMid.first())
                } else if (s == 0 && p > 0 && oldMid.size == 1 && newMid.size == 1) {
                    // typical prefix replace: isValid -> hasValid
                    buildPrefixReplacePattern(oldMid.first(), newMid.first())
                } else {
                    // generic middle replace
                    buildMiddleReplacePattern(oldTokens, newTokens, p, s, oldMid, newMid)
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Tokenization helpers
    // -------------------------------------------------------------------------

    /**
     * Simple join: just concatenates tokens without separators.
     * Naming convention conversion is done elsewhere (APPLY_HISTORY_PATTERN).
     */
    private fun joinTokens(tokens: List<String>): String {
        return tokens.joinToString(separator = "")
    }

    private fun escapeRegexLiteral(text: String): String {
        return Regex.escape(text)
    }

    // -------------------------------------------------------------------------
    // Pattern builders – prefix/suffix/middle add/remove/replace
    // -------------------------------------------------------------------------

    // Prefix add: "" -> prefix
    private fun buildPrefixAddPattern(newMid: List<String>): TokenEditPattern {
        val prefix = joinTokens(newMid)
        val regex = Regex("^(.*)$")
        val replacement = prefix + "$1"
        return TokenEditPattern(regex, replacement)
    }

    // Suffix add: "" -> suffix
    private fun buildSuffixAddPattern(newMid: List<String>): TokenEditPattern {
        val suffix = joinTokens(newMid)
        val regex = Regex("^(.*)$")
        val replacement = "$1$suffix".replace("suffix", suffix)
        return TokenEditPattern(regex, replacement)
    }

    // Prefix remove: prefix -> ""
    private fun buildPrefixRemovePattern(oldMid: List<String>): TokenEditPattern {
        val prefix = escapeRegexLiteral(joinTokens(oldMid))
        val regex = Regex("^$prefix(.*)$")
        val replacement = "$1"
        return TokenEditPattern(regex, replacement)
    }

    // Suffix remove: suffix -> ""
    private fun buildSuffixRemovePattern(oldMid: List<String>): TokenEditPattern {
        val suffix = escapeRegexLiteral(joinTokens(oldMid))
        val regex = Regex("^(.*)$suffix$")
        val replacement = "$1"
        return TokenEditPattern(regex, replacement)
    }

    // Prefix replace: oldPrefix -> newPrefix
    private fun buildPrefixReplacePattern(oldPrefixToken: String, newPrefixToken: String): TokenEditPattern {
        val oldPrefix = escapeRegexLiteral(joinTokens(listOf(oldPrefixToken)))
        val newPrefix = joinTokens(listOf(newPrefixToken))
        val regex = Regex("^$oldPrefix(.*)$")
        val replacement = newPrefix + "$1"
        return TokenEditPattern(regex, replacement)
    }

    // Suffix replace: oldSuffix -> newSuffix
    private fun buildSuffixReplacePattern(oldSuffixToken: String, newSuffixToken: String): TokenEditPattern {
        val oldSuffix = escapeRegexLiteral(joinTokens(listOf(oldSuffixToken)))
        val newSuffix = joinTokens(listOf(newSuffixToken))
        val regex = Regex("^(.*)$oldSuffix$")
        val replacement = "$1$newSuffix"
        return TokenEditPattern(regex, replacement)
    }

    // Middle add: insert newMid at some position; generic safe pattern
    private fun buildMiddleAddPattern(
        oldTokens: List<String>,
        newTokens: List<String>,
        p: Int,
        s: Int,
        newMid: List<String>
    ): TokenEditPattern {
        val insert = joinTokens(newMid)
        // generic “insert between any prefix and suffix”
        val regex = Regex("^(.*)(.*)$") // keep generic; refined patterns can be added later
        val replacement = "$1$insert$2"
        return TokenEditPattern(regex, replacement)
    }

    // Middle remove: remove oldMid wherever it appears
    private fun buildMiddleRemovePattern(
        oldTokens: List<String>,
        p: Int,
        s: Int,
        oldMid: List<String>
    ): TokenEditPattern {
        val target = escapeRegexLiteral(joinTokens(oldMid))
        val regex = Regex("^(.*)$target(.*)$")
        val replacement = "$1$2"
        return TokenEditPattern(regex, replacement)
    }

    // Generic middle replace: oldMid -> newMid
    private fun buildMiddleReplacePattern(
        oldTokens: List<String>,
        newTokens: List<String>,
        p: Int,
        s: Int,
        oldMid: List<String>,
        newMid: List<String>
    ): TokenEditPattern {
        val oldStr = escapeRegexLiteral(joinTokens(oldMid))
        val newStr = joinTokens(newMid)
        val regex = Regex("^(.*)$oldStr(.*)$")
        val replacement = "$1$newStr$2"
        return TokenEditPattern(regex, replacement)
    }
}
