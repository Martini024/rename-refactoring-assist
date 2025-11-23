package edu.colorado.rrassist.strategies

import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.refactoring.listeners.RefactoringEventData
import com.intellij.refactoring.listeners.RefactoringEventListener

class RefactoringEventTracker : RefactoringEventListener {
    private data class BeforeRenameInfo(
        val pointer: SmartPsiElementPointer<PsiLocalVariable>,
        val beforeName: String
    )

    private val beforeInfo = ThreadLocal<BeforeRenameInfo?>()

    // --- A. Capture the 'BEFORE' State in refactoringStarted ---
    override fun refactoringStarted(refactoringId: String, beforeData: RefactoringEventData?) {
        if (refactoringId == "refactoring.rename") {
            // Get the element *before* the rename starts
            val element = beforeData?.getUserData(RefactoringEventData.PSI_ELEMENT_KEY)

            if (element is PsiLocalVariable) {
                val project = element.project
                val pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(element)
                // Store the original name before the refactoring framework changes it
                beforeInfo.set(
                    BeforeRenameInfo(
                        pointer = pointer,
                        beforeName = element.name
                    )
                )
            }
        }
    }

    // --- B. Execute Logic with 'AFTER' State in refactoringDone ---
    override fun refactoringDone(refactoringId: String, afterData: RefactoringEventData?) {
        // 1) Only care about rename events
        if (refactoringId != "refactoring.rename")
            return

        // 2) If we never saw a matching refactoringStarted, bail out
        val info = beforeInfo.get() ?: return
        beforeInfo.remove()   // clear immediately to avoid leaks / reuse

        // 3) Use the smart pointer as the source of truth AFTER rename
        val element = info.pointer.element as? PsiLocalVariable ?: return

        val beforeName = info.beforeName
        val newName = element.name
        val beforeConvention = NamingConvention.detect(beforeName)
        val afterConvention = NamingConvention.detect(newName)

        val file = element.containingFile
        val filePath = file.virtualFile?.path ?: file.name
        val dataType = element.type.presentableText
        val offset = element.textOffset

        val historyEntry = RenameHistory(
            beforeName = beforeName,
            afterName = newName,
            beforeConventions = beforeConvention,
            afterConventions = afterConvention,
            filePath = filePath,
            type = dataType,
        )

        HistoryFirstStrategy.addHistory(historyEntry)
    }
}
