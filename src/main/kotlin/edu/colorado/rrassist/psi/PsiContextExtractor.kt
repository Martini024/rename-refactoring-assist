package edu.colorado.rrassist.psi

import com.intellij.core.JavaCoreApplicationEnvironment
import com.intellij.core.JavaCoreProjectEnvironment
import com.intellij.lang.java.JavaLanguage
import com.intellij.mock.MockProject
import com.intellij.openapi.Disposable
import com.intellij.openapi.extensions.Extensions
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.*
import com.intellij.psi.augment.PsiAugmentProvider
import com.intellij.psi.util.PsiTreeUtil
import edu.colorado.rrassist.strategies.RenameContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.max
import kotlin.math.min

@Serializable
data class SourceLocator(
    val line: Int? = null,
    val column: Int? = null,
    val offset: Int? = null
)

@Serializable
data class JavaVarTarget(
    val path: String,
    val locators: List<SourceLocator>,
    @SerialName("old_name")
    val oldName: String? = null,
    @SerialName("new_name")
    val newName: String? = null,
)

object PsiContextExtractor {

    // --------------------------
    // Public API
    // --------------------------

    /**
     * Convert a resolved declaration element into a RenameContext.
     * Accepts PsiLocalVariable, PsiParameter, or PsiField.
     */
    fun extractRenameContext(element: PsiElement): RenameContext {
        val file = element.containingFile
            ?: error("Element has no containing file: ${element::class.simpleName}")

        return when (element) {
            is PsiLocalVariable -> makeContextFromLocal(file, element)
            is PsiParameter -> makeContextFromParameter(file, element)
            is PsiField -> makeContextFromField(file, element)
            else -> {
                // If caller passed a non-declaration leaf, try to ascend just in case.
                val decl = ascendToDeclaration(element)
                    ?: error("Unsupported element: ${element::class.simpleName}. Provide a variable/parameter/field declaration.")
                return extractRenameContext(decl)
            }
        }
    }

    private fun isUrl(s: String): Boolean = s.startsWith("http://") || s.startsWith("https://")

    /**
     * Unified entry: if target.path is a URL (e.g. GitHub blob/raw URL), fetch the text and build a PSI file in-memory;
     * otherwise open from local disk using your existing createPsiForFile.
     */
    fun extractFromPathTarget(target: JavaVarTarget, project: Project? = null): RenameContext {
        return if (isUrl(target.path)) {
            extractFromRemotePath(target, project)
        } else {
            extractFromLocalPath(target, project)
        }
    }

    private fun extractFromRemotePath(target: JavaVarTarget, project: Project? = null): RenameContext {
        val text = readGithubFile(target.path)
        val fileName = target.path.substringAfterLast('/')

        val tempDir = Files.createTempDirectory("rrassist-psi-test")
        val javaPath = tempDir.resolve(fileName)
        Files.writeString(javaPath, text)

        return extractFromLocalPath(target.copy(path = javaPath.toString()))
    }

    private fun extractFromLocalPath(target: JavaVarTarget, project: Project? = null): RenameContext {
        val (_, psiFile, disposer) = createPsiForFile(target.path, project)
        try {
            val text = psiFile.text

            // locatorList is now a list of SourceLocator
            for (loc in target.locators) {
                val offset = loc.offset
                    ?: lineColToOffset(
                        text = text,
                        line = requireNotNull(loc.line) { "line or offset required" },
                        col = requireNotNull(loc.column) { "column or offset required" }
                    )

                val leaf = psiFile.findElementAt(offset) ?: continue
                val decl = ascendToDeclaration(leaf) ?: continue

                val name = getPsiElementName(decl)
                if (name != target.oldName) {
                    println("skip: decl=${decl.javaClass.simpleName}, name=$name, expected=${target.oldName}")
                    continue
                }
                // If we successfully found a valid declaration, return immediately
                return extractRenameContext(decl)
            }

            error("No valid declaration found in any locator (path=${target.path})")
        } finally {
            Disposer.dispose(disposer)
        }
    }

    /**
     * Given a GitHub file URL (raw or "blob" form), fetches the file content as plain text.
     *
     * Examples:
     *   - https://github.com/user/repo/blob/main/src/Foo.java
     *   - https://raw.githubusercontent.com/user/repo/main/src/Foo.java
     */
    fun readGithubFile(url: String): String {
        val rawUrl = when {
            // already a raw URL
            url.contains("raw.githubusercontent.com") -> url
            // convert "blob" form to "raw" form
            url.contains("github.com") && url.contains("/blob/") -> {
                url.replace("github.com", "raw.githubusercontent.com")
                    .replace("/blob/", "/")
            }

            else -> error("Unsupported GitHub URL format: $url")
        }
        val encoded = rawUrl.replace("^", "%5E")

        val conn = URI(encoded).toURL().openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Accept", "text/plain")

        return conn.inputStream.bufferedReader().use { it.readText() }
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
        val rootArea = Extensions.getRootArea()
        JavaCoreApplicationEnvironment.registerExtensionPoint(
            rootArea, PsiAugmentProvider.EP_NAME, PsiAugmentProvider::class.java
        )
        val projEnv = JavaCoreProjectEnvironment(disposable, appEnv)
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
            is PsiParameter -> return e
            is PsiField -> return e
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
    private fun collectConflictsForLocal(v: PsiLocalVariable): List<String> {
        val method = PsiTreeUtil.getParentOfType(v, PsiMethod::class.java) ?: return emptyList()
        val body = method.body ?: return emptyList()

        val names = mutableSetOf<String>()

        // parameters are also names you can't reuse
        method.parameterList.parameters.forEach { p ->
            p.name?.let(names::add)
        }

        // locals in the same body (excluding the variable itself)
        body.accept(object : JavaRecursiveElementVisitor() {
            override fun visitLocalVariable(var2: PsiLocalVariable) {
                if (var2 !== v) {
                    var2.name?.let(names::add)
                }
                super.visitLocalVariable(var2)
            }
        })

        return names.toList()
    }


    private fun makeContextFromLocal(file: PsiFile, v: PsiLocalVariable): RenameContext {
        val method = PsiTreeUtil.getParentOfType(v, PsiMethod::class.java)
        val scopeHint = method?.name?.let { "in $it(...)" } ?: "local"
        val related = collectRelatedNames(v)

        return RenameContext(
            symbolName = v.name,
            symbolKind = "localVariable",
            language = v.language.id,
            type = v.type.presentableText,
            scopeHint = scopeHint,
            filePath = file.virtualFile?.path ?: file.name,
            offset = v.textOffset,
            projectStyle = "lowerCamelCase",
            purposeHint = null,
            codeSnippet = snippetAround(file, v, contextLines = 6),
            relatedNames = related,
            conflictNames = collectConflictsForLocal(v)
        )
    }

    private fun collectConflictsForParameter(param: PsiParameter): List<String> {
        val method = PsiTreeUtil.getParentOfType(param, PsiMethod::class.java) ?: return emptyList()
        val names = mutableSetOf<String>()

        // other parameters
        method.parameterList.parameters
            .filter { it !== param }
            .forEach { it.name?.let(names::add) }

        // locals in the body
        method.body?.accept(object : JavaRecursiveElementVisitor() {
            override fun visitLocalVariable(variable: PsiLocalVariable) {
                variable.name?.let(names::add)
                super.visitLocalVariable(variable)
            }
        })

        return names.toList()
    }

    private fun makeContextFromParameter(file: PsiFile, p: PsiParameter): RenameContext {
        val method = PsiTreeUtil.getParentOfType(p, PsiMethod::class.java)
        val methodName = method?.name ?: "method"
        val related = collectRelatedNames(p)

        return RenameContext(
            symbolName = p.name,
            symbolKind = "parameter",
            language = p.language.id,
            type = p.type.presentableText,
            scopeHint = "in $methodName(...)",
            filePath = file.virtualFile?.path ?: file.name,
            offset = p.textOffset,
            projectStyle = "lowerCamelCase",
            purposeHint = null,
            codeSnippet = snippetAround(file, p, contextLines = 6),
            relatedNames = related,
            conflictNames = collectConflictsForParameter(p)
        )
    }

    private fun collectConflictsForField(field: PsiField): List<String> {
        val cls = PsiTreeUtil.getParentOfType(field, PsiClass::class.java) ?: return emptyList()
        val names = mutableSetOf<String>()

        cls.fields
            .filter { it !== field }
            .forEach { it.name?.let(names::add) }

        return names.toList()
    }

    private fun makeContextFromField(file: PsiFile, f: PsiField): RenameContext {
        val clsName = f.containingClass?.name ?: "class"
        val related = collectRelatedNames(f)

        return RenameContext(
            symbolName = f.name,
            symbolKind = "field",
            language = f.language.id,
            type = f.type.presentableText,
            scopeHint = "in $clsName",
            filePath = file.virtualFile?.path ?: file.name,
            projectStyle = "lowerCamelCase",
            purposeHint = null,
            codeSnippet = snippetAround(file, f, contextLines = 6),
            relatedNames = related,
            conflictNames = collectConflictsForField(f)
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
