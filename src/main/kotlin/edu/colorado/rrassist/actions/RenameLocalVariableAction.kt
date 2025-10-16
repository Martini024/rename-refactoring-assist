package edu.colorado.rrassist.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.*
import com.intellij.psi.util.PsiUtilBase
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.openapi.util.TextRange
import edu.colorado.rrassist.services.RenameContext
import kotlinx.coroutines.*
import edu.colorado.rrassist.services.RenameSuggestionService
import edu.colorado.rrassist.utils.Log

class RenameLocalVariableAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = isTargetVariableInsideFunction(e)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val renameSuggestionService = RenameSuggestionService.getInstance()

        val editor: Editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val element: PsiElement = PsiUtilBase.getElementAtCaret(editor) ?: return

        // Build context (best-effort, keep it lightweight)
        val symbolName = (element as? PsiNamedElement)?.name ?: element.text.take(64)
        val languageId = element.language.id
        val codeSnippet = extractSnippet(editor, element.textRange)
        val related = collectSiblingNames(element)

        val ctx = RenameContext(
            symbolName = symbolName,
            symbolKind = "localVariable",
            language = languageId,
            type = null,                 // fill in later if you wire type inference
            scopeHint = null,                // e.g., nearest function/class — optional
            filePath = element.containingFile?.virtualFile?.path,
            projectStyle = null,             // plug your naming rules here later
            purposeHint = null,
            codeSnippet = codeSnippet,
            relatedNames = related
        )

        // Run suggest() off EDT; then post result back to UI
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val envelope = renameSuggestionService.suggest(ctx, topK = 5)
                val pretty = envelope.suggestions.joinToString(separator = "<br><br>") { s ->
                    buildString {
                        append("• <b>${s.name}</b>")
                        s.confidence?.let { append(" (${ "%.2f".format(it * 100) }%)") }
                        s.rationale?.let { append("<br>&nbsp;&nbsp;&nbsp;&nbsp;— $it") }
                    }
                }.ifBlank { "No suggestions." }

                withContext(Dispatchers.Main) {
                    notify(project,
                        title = "Rename Suggestions for \"$symbolName\"",
                        content = pretty
                    )
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    notify(project, "Rename Suggestions Error", t.message ?: "Unknown error", NotificationType.ERROR)
                }
            }
        }
    }

    private fun extractSnippet(editor: Editor, range: TextRange, contextChars: Int = 160): String {
        val doc = editor.document
        val start = (range.startOffset - contextChars).coerceAtLeast(0)
        val end = (range.endOffset + contextChars).coerceAtMost(doc.textLength)
        return doc.getText(TextRange(start, end))
    }

    private fun collectSiblingNames(element: PsiElement): List<String> {
        val parent = element.parent ?: return emptyList()
        return parent.children
            .asSequence()
            .filterIsInstance<PsiNamedElement>()
            .mapNotNull { it.name }
            .distinct()
            .take(20)
            .toList()
    }

    private fun notify(project: Project, title: String, content: String, type: NotificationType = NotificationType.INFORMATION) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("RRAssist")   // define in plugin.xml or use an existing group
            .createNotification(title, content, type)
            .notify(project)
    }

    private fun isTargetVariableInsideFunction(e: AnActionEvent): Boolean {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return false
        val file   = e.getData(CommonDataKeys.PSI_FILE) ?: return false
        val offset = editor.caretModel.offset
        val leaf   = file.findElementAt(offset) ?: return false

        debugLeaf(leaf)

        return isJavaLocalInMethod(leaf) || isKotlinLocalPropertyInFunction(leaf)
    }

    private fun debugLeaf(leaf: PsiElement) {
        val cls = leaf::class.java.name
        val tokenType = leaf.node?.elementType?.toString() ?: "null"
        val text = leaf.text.replace("\n", "\\n")
        val parents = generateSequence(leaf.parent) { it.parent }
            .take(4)
            .joinToString(" -> ") { it::class.java.simpleName.ifEmpty { it::class.java.name } }

        val msg = """
        [RR-Assist] Leaf:
          class = $cls
          tokenType = $tokenType
          text = '$text'
          parents(top 4) = $parents
    """.trimIndent()

        println(msg)      // shows in Gradle Run console
        Log.info(msg)     // also in idea.log
    }

    /* ---------- Java: ONLY local variables inside a PsiMethod ---------- */
    private fun isJavaLocalInMethod(leaf: PsiElement): Boolean {
        // Find a Java local variable at/above the caret
        val localVar = PsiTreeUtil.getParentOfType(leaf, PsiLocalVariable::class.java)
            ?: return false

        // Ensure it's declared inside a method body (not a field / class initializer / top-level)
        val enclosingMethod = PsiTreeUtil.getParentOfType(localVar, PsiMethod::class.java)
            ?: return false

        // Optional: also ensure it’s inside a code block of that method
        val codeBlock = PsiTreeUtil.getParentOfType(localVar, PsiCodeBlock::class.java)
            ?: return false

        return PsiTreeUtil.isAncestor(enclosingMethod.body, localVar, /*strict*/ true)
                && PsiTreeUtil.isAncestor(codeBlock, localVar, /*strict*/ false)
    }

    /* ---------- Kotlin: ONLY local properties (val/var) inside a KtNamedFunction ---------- */
    private fun isKotlinLocalPropertyInFunction(leaf: PsiElement): Boolean {
        // Find a Kotlin property (val/var) at/above the caret
        val ktProperty = PsiTreeUtil.getParentOfType(leaf, KtProperty::class.java)
            ?: return false

        // Must be inside a named function and within a block body (not class-level or top-level)
        val fn = PsiTreeUtil.getParentOfType(ktProperty, KtNamedFunction::class.java)
            ?: return false
        val inBlock = PsiTreeUtil.getParentOfType(ktProperty, KtBlockExpression::class.java) != null

        return inBlock && PsiTreeUtil.isAncestor(fn, ktProperty, /*strict*/ true)
    }

}
