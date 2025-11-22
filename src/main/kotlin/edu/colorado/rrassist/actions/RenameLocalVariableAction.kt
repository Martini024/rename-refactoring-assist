package edu.colorado.rrassist.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilBase
import edu.colorado.rrassist.psi.PsiContextExtractor
import edu.colorado.rrassist.services.RenameSuggestionService
import edu.colorado.rrassist.services.StrategyType
import edu.colorado.rrassist.toolWindow.RenameSuggestionsToolWindowFactory
import edu.colorado.rrassist.utils.Log
import kotlinx.coroutines.*
import java.util.Collections

class RenameLocalVariableAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = isTargetVariableInsideFunction(e)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val renameSuggestionService = RenameSuggestionService.getInstance()
        renameSuggestionService.setStrategy(StrategyType.HISTORY_FIRST)

        val editor: Editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val element: PsiElement = PsiUtilBase.getElementAtCaret(editor) ?: return

        val ctx = PsiContextExtractor.extractRenameContext(element)

        val panel = RenameSuggestionsToolWindowFactory.getPanel(project) ?: return
        panel.setTargetElement(element)
        panel.setSuggestions(Collections.emptyList())
        panel.beginLoading()

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Generating Rename Suggestions",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true

                val suggestions = try {
                    // Run your suspending call in this background task
                    runBlocking { renameSuggestionService.suggest(ctx, topK = 5).suggestions }
                } catch (e: Throwable) {
                    ApplicationManager.getApplication().invokeLater {
                        notify(
                            project,
                            "Rename Suggestions Error",
                            e.message ?: "Unknown error",
                            NotificationType.ERROR
                        )
                    }
                    emptyList()
                }

                // Update UI (EDT)
                ApplicationManager.getApplication().invokeLater {
                    panel.setSuggestions(suggestions)
                }
            }

            override fun onFinished() {
                // Ensure exactly one endLoading(), on the EDT
                ApplicationManager.getApplication().invokeLater { panel.endLoading() }
            }
        })
    }

    private fun notify(
        project: Project,
        title: String,
        content: String,
        type: NotificationType = NotificationType.INFORMATION
    ) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("RRAssist")   // define in plugin.xml or use an existing group
            .createNotification(title, content, type)
            .notify(project)
    }

    private fun isTargetVariableInsideFunction(e: AnActionEvent): Boolean {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return false
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return false
        val offset = editor.caretModel.offset
        val leaf = file.findElementAt(offset) ?: return false

        debugLeaf(leaf)

        return isJavaLocalInMethod(leaf)
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
}
