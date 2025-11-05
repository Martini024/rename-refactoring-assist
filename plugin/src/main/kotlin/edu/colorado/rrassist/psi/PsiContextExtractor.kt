package edu.colorado.rrassist.psi

import com.intellij.core.CoreProjectEnvironment
import com.intellij.core.JavaCoreApplicationEnvironment
import com.intellij.lang.java.JavaLanguage
import com.intellij.mock.MockProject
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import edu.colorado.rrassist.services.RenameContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.max
import kotlin.math.min

data class SourceLocator(
    val line: Int? = null,
    val column: Int? = null,
    val offset: Int? = null
)

data class JavaVarTarget(
    val path: String,
    val locator: SourceLocator
)

object PsiContextExtractor {

    // --------------------------
    // Public API
    // --------------------------

    /**
     * Resolve the nearest declaration (local variable / parameter / field) at the given location.
     * Throws an IllegalStateException with details if not found.
     */
    fun extractPsiElement(target: JavaVarTarget): PsiElement {
        val (_, psiFile, disposer) = createPsiForFile(target.path)
        try {
            val offset = target.locator.offset ?: lineColToOffset(
                text = psiFile.text,
                line = requireNotNull(target.locator.line) { "line or offset required" },
                col = requireNotNull(target.locator.column) { "column or offset required" }
            )

            val leaf = psiFile.findElementAt(offset)
                ?: error("No PSI leaf at offset=$offset (path=${target.path})")

            // Climb to a declaration
            val decl = ascendToDeclaration(leaf)
                ?: error(
                    "No variable/parameter/field declaration at or near offset=$offset (path=${target.path}). " +
                            "Leaf=${leaf::class.simpleName}"
                )

            return decl
        } finally {
            Disposer.dispose(disposer)
        }
    }

    /**
     * Convert a resolved declaration element into a RenameContext.
     * Accepts PsiLocalVariable, PsiParameter, or PsiField.
     */
    fun extractRenameContext(element: PsiElement): RenameContext {
        val file = element.containingFile
            ?: error("Element has no containing file: ${element::class.simpleName}")

        return when (element) {
            is PsiLocalVariable -> makeContextFromLocal(file, element)
            is PsiParameter     -> makeContextFromParameter(file, element)
            is PsiField         -> makeContextFromField(file, element)
            else -> {
                // If caller passed a non-declaration leaf, try to ascend just in case.
                val decl = ascendToDeclaration(element)
                    ?: error("Unsupported element: ${element::class.simpleName}. Provide a variable/parameter/field declaration.")
                return extractRenameContext(decl)
            }
        }
    }

    /**
     * Convenience: resolve declaration at (file, locator) and directly return RenameContext.
     */
    fun extractFromFileAndLocator(target: JavaVarTarget, project: Project? = null): RenameContext {
        val (_, psiFile, disposer) = createPsiForFile(target.path, project)
        try {
            val text = psiFile.text
            val offset = target.locator.offset ?: lineColToOffset(
                text = text,
                line = requireNotNull(target.locator.line) { "line or offset required" },
                col = requireNotNull(target.locator.column) { "column or offset required" }
            )

            val leaf = psiFile.findElementAt(offset)
                ?: error("No PSI leaf at offset=$offset (path=${target.path})")

            val decl = ascendToDeclaration(leaf)
                ?: error("No declaration at or near offset=$offset (path=${target.path})")

            return extractRenameContext(decl)
        } finally {
            Disposer.dispose(disposer)
        }
    }

    // --------------------------
    // Internals
    // --------------------------

    private data class PsiTuple(
        val project: Project,
        val psiFile: PsiFile,
        val disposer: Disposable
    )

    /**
     * Build a minimal Core PSI environment and create a PsiFile from the given path.
     * Headless and no indexing.
     */
    private fun createPsiForFile(pathStr: String, project: Project? = null): PsiTuple {
        val path = Path.of(pathStr)
        val text = Files.readString(path)

        if (project != null) {
            val psiFile = PsiFileFactory.getInstance(project).createFileFromText(
                path.fileName.toString(),
                JavaLanguage.INSTANCE,
                text,
                false,
                false
            ) ?: error("PsiFileFactory returned null (is com.intellij.java loaded for tests?)")
            return PsiTuple(project, psiFile, Disposable { })
        }

        System.setProperty("java.awt.headless", "true")

        val disposable: Disposable = Disposer.newDisposable("rrassist-psi")
        val appEnv = JavaCoreApplicationEnvironment(disposable)
        val projEnv = CoreProjectEnvironment(disposable, appEnv)
        val project: MockProject = projEnv.project

        val psiFile = PsiFileFactory.getInstance(project)
            .createFileFromText(
                /* name    = */ path.fileName.toString(),
                /* lang    = */ JavaLanguage.INSTANCE,
                /* text    = */ text,
                /* timestamp */ false,
                /* eventSystemEnabled */ false
            )

        return PsiTuple(project, psiFile, disposable)
    }

    /**
     * 1-based (line, column) → absolute offset in [text].
     * Lines separated by '\n'. Column counts code points; here we treat as chars.
     */
    private fun lineColToOffset(text: String, line: Int, col: Int): Int {
        require(line >= 1) { "line must be >= 1" }
        require(col >= 1) { "column must be >= 1" }

        var curLine = 1
        var i = 0
        while (i < text.length && curLine < line) {
            if (text[i] == '\n') curLine++
            i++
        }
        return (i + (col - 1)).coerceIn(0, text.length)
    }

    /**
     * Climb ancestors to find a variable/parameter/field declaration.
     */
    private fun ascendToDeclaration(e: PsiElement): PsiElement? {
        // Fast checks
        when (e) {
            is PsiLocalVariable -> return e
            is PsiParameter     -> return e
            is PsiField         -> return e
        }
        // Climb
        return PsiTreeUtil.getParentOfType(
            e,
            PsiLocalVariable::class.java,
            PsiParameter::class.java,
            PsiField::class.java
        )
    }

    // -------- Context builders --------

    private fun makeContextFromLocal(file: PsiFile, v: PsiLocalVariable): RenameContext {
        val method = PsiTreeUtil.getParentOfType(v, PsiMethod::class.java)
        val scopeHint = method?.name?.let { "in $it(...)" } ?: "local"
        val related = collectRelatedNames(v)

        return RenameContext(
            symbolName   = v.name,
            symbolKind   = "localVariable",
            language     = v.language.id,
            type         = v.type.presentableText,
            scopeHint    = scopeHint,
            filePath     = file.virtualFile?.path ?: file.name,
            projectStyle = "lowerCamelCase",
            purposeHint  = null,
            codeSnippet  = snippetAround(file, v, contextLines = 6),
            relatedNames = related
        )
    }

    private fun makeContextFromParameter(file: PsiFile, p: PsiParameter): RenameContext {
        val method = PsiTreeUtil.getParentOfType(p, PsiMethod::class.java)
        val methodName = method?.name ?: "method"
        val related = collectRelatedNames(p)

        return RenameContext(
            symbolName   = p.name,
            symbolKind   = "parameter",
            language     = "Java",
            type         = p.type.presentableText,
            scopeHint    = "in $methodName(...)",
            filePath     = file.virtualFile?.path ?: file.name,
            projectStyle = "lowerCamelCase",
            purposeHint  = null,
            codeSnippet  = snippetAround(file, p, contextLines = 6),
            relatedNames = related
        )
    }

    private fun makeContextFromField(file: PsiFile, f: PsiField): RenameContext {
        val clsName = f.containingClass?.name ?: "class"
        val related = collectRelatedNames(f)

        return RenameContext(
            symbolName   = f.name,
            symbolKind   = "field",
            language     = "Java",
            type         = f.type.presentableText,
            scopeHint    = "in $clsName",
            filePath     = file.virtualFile?.path ?: file.name,
            projectStyle = "lowerCamelCase",
            purposeHint  = null,
            codeSnippet  = snippetAround(file, f, contextLines = 6),
            relatedNames = related
        )
    }

    /**
     * Grab a few nearby lines of text around [element]. Tries Document first; if unavailable, falls back to raw text slicing.
     */
    private fun snippetAround(file: PsiFile, element: PsiElement, contextLines: Int): String {
        val project = file.project
        val doc = PsiDocumentManager.getInstance(project).getDocument(file)
        if (doc != null) {
            val startLine = max(0, doc.getLineNumber(element.textRange.startOffset) - contextLines)
            val endLine = min(doc.lineCount - 1, doc.getLineNumber(element.textRange.endOffset) + contextLines)
            val startOff = doc.getLineStartOffset(startLine)
            val endOff = doc.getLineEndOffset(endLine)
            return file.text.substring(startOff, endOff)
        }

        // Fallback: approximate by character range expansion
        val t = file.text
        val r = element.textRange
        val pad = 240 // ~ a few lines
        val s = max(0, r.startOffset - pad)
        val e = min(t.length, r.endOffset + pad)
        return t.substring(s, e)
    }

    /**
     * Very lightweight "related names": sibling variables + method/class names nearby.
     * Expand later if you want control/data-flow signals.
     */
    private fun collectRelatedNames(owner: PsiVariable): List<String> {
        val names = LinkedHashSet<String>()

        owner.nameIdentifier?.text?.let { if (it.isNotBlank()) names += it }

        // Sibling variables in same declaration/context
        owner.parent?.children?.forEach {
            if (it is PsiVariable && it != owner) {
                it.name?.let { n -> if (n.isNotBlank()) names += n }
            }
        }

        // Nearby method and class
        PsiTreeUtil.getParentOfType(owner, PsiMethod::class.java)?.name?.let { names += it }
        PsiTreeUtil.getParentOfType(owner, PsiClass::class.java)?.name?.let { names += it }

        return names.toList().take(12)
    }
}
