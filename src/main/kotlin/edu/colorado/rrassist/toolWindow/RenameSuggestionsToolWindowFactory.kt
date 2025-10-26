package edu.colorado.rrassist.toolWindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import edu.colorado.rrassist.services.RenameSuggestion
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*
import javax.swing.border.EmptyBorder


class RenameSuggestionsToolWindowFactory: ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = RenameSuggestionsPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    companion object {
        fun getPanel(project: Project): RenameSuggestionsPanel? {
            val tw = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                .getToolWindow("Rename Suggestions") ?: return null
            val cm = tw.contentManager
            val content = cm.contents.firstOrNull() ?: return null
            return content.component as? RenameSuggestionsPanel
        }
    }

    class RenameSuggestionsPanel(private val project: Project) : JPanel(BorderLayout()) {

        private var targetPointer: SmartPsiElementPointer<PsiNamedElement>? = null

        // Center: scrollable list of suggestion "cards"
        private val cardsContainer = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = EmptyBorder(8, 8, 8, 8)
            background = UIManager.getColor("Editor.background")
        }
        private val scrollPane = JBScrollPane(cardsContainer).apply {
            border = null
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }

        // Bottom: fixed action row
        private val applyButton = JButton("Apply Rename")

        // Selection handling across cards
        private val selectionGroup = ButtonGroup()
        private val cardViews = mutableListOf<SuggestionCard>()

        init {
            add(scrollPane, BorderLayout.CENTER)

            val bottom = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(8, 8)
                add(applyButton, BorderLayout.EAST)
            }
            add(bottom, BorderLayout.SOUTH)

            applyButton.addActionListener {
                val selected = cardViews.firstOrNull { it.isSelected() } ?: return@addActionListener
                runRename(preview = false, newName = selected.suggestion.name)
            }
        }

        fun setTargetElement(element: PsiElement) {
            val namedElement = PsiTreeUtil.getParentOfType<PsiNamedElement?>(element, PsiNamedElement::class.java)
            if (namedElement != null) {
                targetPointer = SmartPointerManager.createPointer(namedElement)
            }
        }

        fun setSuggestions(suggestions: List<RenameSuggestion>) {
            // Clear old
            cardsContainer.removeAll()
            cardViews.clear()
            selectionGroup.clearSelection()

            // Build new cards
            suggestions.forEachIndexed { idx, s ->
                val card = SuggestionCard(s)
                cardViews += card
                selectionGroup.add(card.selector)
                cardsContainer.add(card)
                cardsContainer.add(Box.createVerticalStrut(8))
                if (idx == 0) card.selector.isSelected = true
            }

            // Keep layout responsive
            cardsContainer.add(Box.createVerticalGlue())
            cardsContainer.revalidate()
            cardsContainer.repaint()
        }

        private fun runRename(preview: Boolean, newName: String) {
            val target = targetPointer?.element ?: return
            RenameProcessor(
                project,
                target,
                newName,
                /*searchInComments*/ true,
                /*searchTextOccurrences*/ true
            ).apply {
                if (preview) setPreviewUsages(true)
            }.run()
        }

        // --- UI: one card --------------------------------------------------------

        class SuggestionCard(val suggestion: RenameSuggestion) : JPanel() {

            val selector = JRadioButton()

            init {
                layout = BorderLayout()
                isOpaque = true
                background = JBColor.PanelBackground
                border = JBUI.Borders.compound(
                    JBUI.Borders.customLine(JBColor.border(), 1),
                    JBUI.Borders.empty(8)
                )
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)

                // --- Header row: selector (left) + name + confidence (right)
                val header = JPanel(BorderLayout()).apply {
                    isOpaque = false

                    val left = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                        isOpaque = false
                        add(selector)
                        add(Box.createHorizontalStrut(4))
                        val nameLabel = JBLabel("<html><b>${escapeHtml(suggestion.name)}</b></html>")
                        add(nameLabel)
                    }

                    val right = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                        isOpaque = false
                        val conf = suggestion.confidence?.let { "${"%.0f".format(it * 100)}%" } ?: ""
                        val confLabel = JBLabel(conf)
                        add(confLabel)
                    }

                    add(left, BorderLayout.WEST)
                    add(right, BorderLayout.EAST)
                }

                // --- Second row: Preview button + rationale text
                // --- Body: rationale (top-aligned, wraps to parent width)
                val body = JPanel(GridBagLayout()).apply {
                    isOpaque = false

                    val gbc = GridBagConstraints().apply {
                        gridx = 0
                        gridy = 0
                        weightx = 1.0
                        fill = GridBagConstraints.HORIZONTAL
                        anchor = GridBagConstraints.NORTHWEST
                        insets = JBUI.insets(6,4)
                    }

                    val rationaleText = suggestion.rationale?.takeIf { it.isNotBlank() } ?: "(no rationale)"
                    val rationaleLabel = JBLabel("<html>${escapeHtml(rationaleText)}</html>").apply {
                        // allow HTML wrapping; width comes from GridBag fill + weightx
                        putClientProperty("html.disable", false)
                        alignmentX = LEFT_ALIGNMENT
                        alignmentY = TOP_ALIGNMENT
                    }

                    add(rationaleLabel, gbc)

                    // Optional spacer to keep NORTH anchoring when parent grows
                    val spacerGbc = GridBagConstraints().apply {
                        gridx = 0
                        gridy = 1
                        weightx = 1.0
                        weighty = 0.0
                        fill = GridBagConstraints.HORIZONTAL
                    }
                    add(Box.createGlue(), spacerGbc)
                }

                add(header, BorderLayout.NORTH)
                add(body, BorderLayout.CENTER)
            }

            private fun escapeHtml(s: String): String =
                s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

            fun isSelected(): Boolean = selector.isSelected
        }
    }
}