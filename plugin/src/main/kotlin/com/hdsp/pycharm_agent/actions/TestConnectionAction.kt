package com.hdsp.pycharm_agent.actions

import com.hdsp.pycharm_agent.diagnostics.ConnectionTester
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
 * Action to test PyCharm Agent connections
 *
 * Runs quick connection tests for all configured services
 * and displays results in a dialog.
 */
class TestConnectionAction : AnAction() {

    private val log = Logger.getInstance(TestConnectionAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Testing PyCharm Agent Connections",
            false
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Testing connections..."
                indicator.fraction = 0.2

                val tester = ConnectionTester.getInstance(project)

                indicator.text = "Running connection tests..."
                indicator.fraction = 0.5

                val result = tester.runAllTests()

                indicator.text = "Generating report..."
                indicator.fraction = 0.9

                val formattedReport = tester.formatResults(result)

                indicator.fraction = 1.0

                // Show dialog on EDT
                ApplicationManager.getApplication().invokeLater {
                    ConnectionTestResultDialog(project, formattedReport, result.overallSuccess).show()
                }
            }
        })
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
}

/**
 * Dialog to display connection test results
 */
private class ConnectionTestResultDialog(
    project: com.intellij.openapi.project.Project,
    private val reportText: String,
    private val success: Boolean
) : DialogWrapper(project, false) {

    init {
        title = if (success) "Connection Test - Passed" else "Connection Test - Failed"
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
            preferredSize = Dimension(600, 400)
        }

        panel.add(scrollPane, BorderLayout.CENTER)
        return panel
    }

    override fun createActions(): Array<Action> {
        return arrayOf(okAction)
    }
}
