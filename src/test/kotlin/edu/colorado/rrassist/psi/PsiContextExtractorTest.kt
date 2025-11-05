package edu.colorado.rrassist.psi

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import edu.colorado.rrassist.services.RenameContext

class PsiContextExtractorTest : BasePlatformTestCase() {

    // ------------- Test 1: fixture → PsiElement → RenameContext -------------
    fun testExtractFromPsiElement() {
        val code = """
            package sample;

            public class Example {
                private int counter = 0;

                public void increment() {
                    int te<caret>mp = counter + 1;
                    counter = temp;
                }
            }
        """.trimIndent()

        // Configure an in-memory file with a caret marker; fixture gives us the PSI
        val file = myFixture.configureByText("Example.java", code)
        val offset = myFixture.caretOffset
        val leaf = file.findElementAt(offset)!!
        val ctx: RenameContext = PsiContextExtractor.extractRenameContext(leaf)

        assertEquals("temp", ctx.symbolName)
        assertEquals("JAVA", ctx.language)
        assertTrue(ctx.scopeHint?.contains("increment") ?: true)
        assertTrue(ctx.codeSnippet?.contains("temp") ?: true)
    }

    // ------------- Test 2: disk file + (line,col) → extractFromFileAndLocator -------------
    fun testExtractFromFileAndLocator() {
        // Write a temp Java file to disk to simulate CLI input
        val javaText = """
            package sample;

            public class Example2 {
                public void run() {
                    int value = 42;
                    int tem${'$'}{CARET}p = value + 1; // aim caret around here
                    value = temp;
                }
            }
        """.trimIndent().replace("\${CARET}", "") // we won’t use caret for the disk file

        val tempDir = Files.createTempDirectory("rrassist-psi-test")
        val javaPath: Path = tempDir.resolve("Example2.java")
        Files.writeString(javaPath, javaText)

        // Compute a 1-based line/column to land on "temp" (line numbers count from 1)
        val lines = javaText.lines()
        val targetLineIndex = lines.indexOfFirst { it.contains("int temp") }
        assertTrue("Could not find target line with 'int temp'", targetLineIndex >= 0)

        val line1Based = targetLineIndex + 1
        val col1Based = lines[targetLineIndex].indexOf("temp") + 1 // 1-based column

        val target = JavaVarTarget(
            path = javaPath.toString(),
            locator = SourceLocator(line = line1Based, column = col1Based)
        )

        val ctx: RenameContext = PsiContextExtractor.extractFromPathTarget(target, myFixture.project)

        assertEquals("temp", ctx.symbolName)
        assertEquals("JAVA", ctx.language)
        assertTrue(ctx.scopeHint?.contains("run") ?: true)
        assertTrue(ctx.codeSnippet?.contains("temp") ?: true)
    }

    fun testExtractFromGithubKotlinFile() {
        val url =
            "https://raw.githubusercontent.com/Martini024/rename-refactoring-assist/9e2635f3dd72e8511eb5145d3497d104662a6e48/plugin/src/main/kotlin/edu/colorado/rrassist/services/RenameSuggestionService.kt"

        val target = JavaVarTarget(
            path = url,
            locator = SourceLocator(line = 91, column = 13)
        )

        // Project-aware call so we reuse the test fixture's Project (no Core env inside tests)
        val ctx = PsiContextExtractor.extractFromPathTarget(
            target = target,
            project = myFixture.project
        )

        // Basic sanity checks — it's a Kotlin file
        assertTrue(ctx.language.equals("Java", ignoreCase = true))
        assertNotNull("symbolName should be resolved near 91:13", ctx.symbolName)
        assertEquals("request", ctx.symbolName)
        assertTrue(ctx.codeSnippet?.isNotBlank() == true)
    }
}
