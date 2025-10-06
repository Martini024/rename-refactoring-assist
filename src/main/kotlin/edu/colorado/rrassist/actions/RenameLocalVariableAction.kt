package edu.colorado.rrassist.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.*
import edu.colorado.rrassist.utils.Log.LOG
import com.intellij.psi.util.PsiUtilBase
import com.intellij.openapi.editor.Editor
import com.intellij.refactoring.RefactoringActionHandlerFactory

class RenameLocalVariableAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = isTargetVariableInsideFunction(e)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor: Editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val element: PsiElement = PsiUtilBase.getElementAtCaret(editor) ?: return

        val handler = RefactoringActionHandlerFactory.getInstance().createRenameHandler()
        // This triggers in-place rename when possible, or falls back to dialog.
        handler.invoke(project, arrayOf(element), e.dataContext)
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
        LOG.info(msg)     // also in idea.log
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
