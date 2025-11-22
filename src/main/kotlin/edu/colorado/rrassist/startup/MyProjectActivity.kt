package edu.colorado.rrassist.startup

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.refactoring.listeners.RefactoringEventListener
import edu.colorado.rrassist.strategies.RefactoringEventTracker

class MyProjectActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        thisLogger().warn("Don't forget to remove all non-needed sample code files with their corresponding registration entries in `plugin.xml`.")
        val connection = project.messageBus.connect()
        connection.subscribe(RefactoringEventListener.REFACTORING_EVENT_TOPIC, RefactoringEventTracker())
    }
}