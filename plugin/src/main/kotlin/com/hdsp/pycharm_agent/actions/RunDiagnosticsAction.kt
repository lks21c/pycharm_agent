package com.hdsp.pycharm_agent.actions

import com.hdsp.pycharm_agent.diagnostics.AgentDiagnostics
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Action to run PyCharm Agent diagnostics
 *
 * Available from Tools menu or via keyboard shortcut.
 * Runs comprehensive diagnostics and displays results in a dialog.
 */
class RunDiagnosticsAction : AnAction() {

    private val log = Logger.getInstance(RunDiagnosticsAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Running PyCharm Agent Diagnostics",
            false
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Checking plugin configuration..."
                indicator.fraction = 0.1

                val diagnostics = AgentDiagnostics.getInstance(project)

                indicator.text = "Running diagnostic checks..."
                indicator.fraction = 0.3

                val report = diagnostics.runDiagnostics()

                indicator.text = "Generating report..."
                indicator.fraction = 0.8

                val formattedReport = diagnostics.formatReport(report)

                indicator.fraction = 1.0

                // Show dialog on EDT
                ApplicationManager.getApplication().invokeLater {
                    DiagnosticsResultDialog(project, formattedReport).show()
                }
            }
        })
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
}

/**
 * Dialog to display diagnostic results
 */
private class DiagnosticsResultDialog(
    project: com.intellij.openapi.project.Project,
    private val reportText: String
) : DialogWrapper(project, false) {

    init {
        title = "PyCharm Agent Diagnostics"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())

        val textArea = JBTextArea(reportText).apply {
            isEditable = false
            font = java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12)
            caretPosition = 0
        }

        val scrollPane = JBScrollPane(textArea).apply {
            preferredSize = Dimension(700, 500)
        }

        panel.add(scrollPane, BorderLayout.CENTER)
        return panel
    }

    override fun createActions(): Array<Action> {
        return arrayOf(okAction)
    }
}
