package com.hdsp.pycharm_agent.toolwindow

import com.hdsp.pycharm_agent.services.BackendClient
import com.hdsp.pycharm_agent.services.DiffResult
import com.hdsp.pycharm_agent.services.PlanResponse
import com.hdsp.pycharm_agent.services.PlanStep
import com.hdsp.pycharm_agent.settings.AgentSettingsConfigurable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*

/**
 * Main panel for PyCharm Agent tool window
 */
class MainAgentPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val tabbedPane = JBTabbedPane()
    private val chatPanel = ChatPanel(project)
    private val agentPanel = AgentModePanel(project)

    init {
        // Header with Settings button
        val headerPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2, 5)
            val settingsButton = JButton("⚙ Settings").apply {
                toolTipText = "Open PyCharm Agent Settings"
                addActionListener {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, AgentSettingsConfigurable::class.java)
                }
            }
            add(settingsButton, BorderLayout.EAST)
        }
        add(headerPanel, BorderLayout.NORTH)

        tabbedPane.addTab("Chat", chatPanel)
        tabbedPane.addTab("Agent", agentPanel)
        add(tabbedPane, BorderLayout.CENTER)
    }
}

/**
 * Chat mode panel - prompt/response interface with code block support
 */
class ChatPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val messagesPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(10)
    }
    private val scrollPane = JBScrollPane(messagesPanel)
    private val inputField = JBTextField()
    private val sendButton = JButton("Send")

    private var lastAgentMessagePanel: MessagePanel? = null

    init {
        // Messages area with scroll
        scrollPane.preferredSize = Dimension(400, 300)
        scrollPane.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        add(scrollPane, BorderLayout.CENTER)

        // Input panel
        val inputPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(5)
            add(inputField, BorderLayout.CENTER)
            add(sendButton, BorderLayout.EAST)
        }
        add(inputPanel, BorderLayout.SOUTH)

        // Event handlers
        sendButton.addActionListener { sendMessage() }
        inputField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) {
                    sendMessage()
                    e.consume()
                }
            }
        })
    }

    private fun sendMessage() {
        val message = inputField.text.trim()
        if (message.isEmpty()) return

        inputField.text = ""
        sendButton.isEnabled = false
        addUserMessage(message)

        // Add placeholder for streaming response
        val agentPanel = addAgentMessage("...")
        lastAgentMessagePanel = agentPanel

        // Run in background thread
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = project.getService(BackendClient::class.java)
                val response = StringBuilder()

                client.streamChatSync(message) { chunk ->
                    response.append(chunk)
                    SwingUtilities.invokeLater {
                        lastAgentMessagePanel?.updateContent(response.toString())
                        scrollToBottom()
                    }
                }

                SwingUtilities.invokeLater {
                    if (response.isEmpty()) {
                        lastAgentMessagePanel?.updateContent("(No response)")
                    }
                    sendButton.isEnabled = true
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    lastAgentMessagePanel?.updateContent("Error: ${e.message ?: "Unknown error"}")
                    sendButton.isEnabled = true
                }
            }
        }
    }

    private fun addUserMessage(message: String) {
        val panel = MessagePanel("You", message, isUser = true)
        messagesPanel.add(panel)
        messagesPanel.add(Box.createVerticalStrut(8))
        messagesPanel.revalidate()
        scrollToBottom()
    }

    private fun addAgentMessage(message: String): MessagePanel {
        val panel = MessagePanel("Agent", message, isUser = false)
        messagesPanel.add(panel)
        messagesPanel.add(Box.createVerticalStrut(8))
        messagesPanel.revalidate()
        scrollToBottom()
        return panel
    }

    private fun scrollToBottom() {
        SwingUtilities.invokeLater {
            val vsb = scrollPane.verticalScrollBar
            vsb.value = vsb.maximum
        }
    }
}

/**
 * Message panel with code block detection and copy buttons
 */
class MessagePanel(
    private val sender: String,
    initialContent: String,
    private val isUser: Boolean
) : JPanel() {

    private val contentPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }

    init {
        layout = BorderLayout()
        border = JBUI.Borders.empty(5, 10)
        background = if (isUser) {
            JBColor(Color(240, 240, 255), Color(50, 50, 70))
        } else {
            JBColor(Color(245, 245, 245), Color(45, 45, 45))
        }

        // Sender label
        val senderLabel = JLabel(sender).apply {
            font = font.deriveFont(Font.BOLD)
            border = JBUI.Borders.emptyBottom(5)
        }
        add(senderLabel, BorderLayout.NORTH)
        add(contentPanel, BorderLayout.CENTER)

        updateContent(initialContent)
    }

    fun updateContent(content: String) {
        contentPanel.removeAll()
        parseAndRenderContent(content)
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun parseAndRenderContent(content: String) {
        val codeBlockPattern = Regex("```(\\w*)\\n([\\s\\S]*?)```")
        var lastIndex = 0

        codeBlockPattern.findAll(content).forEach { match ->
            // Add text before code block
            if (match.range.first > lastIndex) {
                val textBefore = content.substring(lastIndex, match.range.first).trim()
                if (textBefore.isNotEmpty()) {
                    addTextBlock(textBefore)
                }
            }

            // Add code block with copy button
            val language = match.groupValues[1]
            val code = match.groupValues[2].trim()
            addCodeBlock(code, language)

            lastIndex = match.range.last + 1
        }

        // Add remaining text after last code block
        if (lastIndex < content.length) {
            val remaining = content.substring(lastIndex).trim()
            if (remaining.isNotEmpty()) {
                addTextBlock(remaining)
            }
        }

        // If no content was added, show the raw content
        if (contentPanel.componentCount == 0) {
            addTextBlock(content)
        }
    }

    private fun addTextBlock(text: String) {
        val textArea = JBTextArea(text).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
            border = JBUI.Borders.empty(2)
        }
        textArea.alignmentX = Component.LEFT_ALIGNMENT
        contentPanel.add(textArea)
        contentPanel.add(Box.createVerticalStrut(5))
    }

    private fun addCodeBlock(code: String, language: String) {
        val codePanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border()),
                JBUI.Borders.empty(8)
            )
            background = JBColor(Color(40, 44, 52), Color(30, 30, 30))
            alignmentX = Component.LEFT_ALIGNMENT
        }

        // Header with language and copy button
        val headerPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(5)
        }

        if (language.isNotEmpty()) {
            val langLabel = JLabel(language).apply {
                foreground = JBColor(Color(150, 150, 150), Color(150, 150, 150))
                font = font.deriveFont(Font.ITALIC, 11f)
            }
            headerPanel.add(langLabel, BorderLayout.WEST)
        }

        val copyButton = JButton("Copy").apply {
            font = font.deriveFont(10f)
            preferredSize = Dimension(60, 22)
            addActionListener {
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                clipboard.setContents(StringSelection(code), null)
                text = "Copied!"
                Timer(1500) { text = "Copy" }.apply {
                    isRepeats = false
                    start()
                }
            }
        }
        headerPanel.add(copyButton, BorderLayout.EAST)
        codePanel.add(headerPanel, BorderLayout.NORTH)

        // Code area
        val codeArea = JBTextArea(code).apply {
            isEditable = false
            font = Font("JetBrains Mono", Font.PLAIN, 12).let { f ->
                if (f.family == "JetBrains Mono") f else Font(Font.MONOSPACED, Font.PLAIN, 12)
            }
            foreground = JBColor(Color(200, 200, 200), Color(200, 200, 200))
            background = JBColor(Color(40, 44, 52), Color(30, 30, 30))
            border = JBUI.Borders.empty()
        }
        codePanel.add(codeArea, BorderLayout.CENTER)

        contentPanel.add(codePanel)
        contentPanel.add(Box.createVerticalStrut(8))
    }
}

/**
 * Agent mode panel - plan execution with diff preview (GitHub Copilot style)
 */
class AgentModePanel(private val project: Project) : JPanel(BorderLayout()) {

    private val requestArea = JBTextArea(3, 40).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val generatePlanButton = JButton("Generate Plan")
    private val stepsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(5)
    }
    private val statusLabel = JLabel(" ")
    private val executeNextButton = JButton("Execute Next Step")
    private val acceptButton = JButton("✓ Accept")
    private val rejectButton = JButton("✗ Reject")

    private var currentPlan: PlanResponse? = null
    private var currentStepIndex = 0
    private var pendingDiff: DiffResult? = null
    private val stepPanels = mutableListOf<StepPanel>()

    init {
        // Request input panel
        val requestPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(5)
            add(JLabel("Request:"), BorderLayout.NORTH)
            add(JBScrollPane(requestArea).apply {
                preferredSize = Dimension(400, 80)
            }, BorderLayout.CENTER)
            add(generatePlanButton, BorderLayout.SOUTH)
        }
        add(requestPanel, BorderLayout.NORTH)

        // Steps display panel
        val stepsScrollPane = JBScrollPane(stepsPanel).apply {
            border = BorderFactory.createTitledBorder("Execution Plan")
        }
        add(stepsScrollPane, BorderLayout.CENTER)

        // Control panel at bottom
        val controlPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(5)

            add(statusLabel, BorderLayout.NORTH)

            val buttonsPanel = JPanel(FlowLayout(FlowLayout.CENTER, 10, 5)).apply {
                add(executeNextButton)
                add(acceptButton)
                add(rejectButton)
            }
            add(buttonsPanel, BorderLayout.CENTER)
        }
        add(controlPanel, BorderLayout.SOUTH)

        // Initial button states
        executeNextButton.isEnabled = false
        acceptButton.isEnabled = false
        rejectButton.isEnabled = false

        // Event handlers
        generatePlanButton.addActionListener { generatePlan() }
        executeNextButton.addActionListener { executeNextStep() }
        acceptButton.addActionListener { acceptDiff() }
        rejectButton.addActionListener { rejectDiff() }
    }

    private fun generatePlan() {
        val request = requestArea.text.trim()
        if (request.isEmpty()) return

        generatePlanButton.isEnabled = false
        statusLabel.text = "Generating plan..."
        stepsPanel.removeAll()
        stepPanels.clear()
        currentStepIndex = 0
        pendingDiff = null

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = project.getService(BackendClient::class.java)
                val plan = client.generatePlanSync(request)
                currentPlan = plan

                SwingUtilities.invokeLater {
                    displayPlan(plan)
                    statusLabel.text = "Plan generated. Click 'Execute Next Step' to begin."
                    executeNextButton.isEnabled = plan.plan.steps.isNotEmpty()
                    generatePlanButton.isEnabled = true
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    statusLabel.text = "Error: ${e.message}"
                    generatePlanButton.isEnabled = true
                }
            }
        }
    }

    private fun displayPlan(plan: PlanResponse) {
        stepsPanel.removeAll()
        stepPanels.clear()

        // Reasoning section
        val reasoningPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(5)
            val reasoningArea = JBTextArea(plan.reasoning).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                background = JBColor(Color(255, 255, 230), Color(60, 60, 40))
                border = JBUI.Borders.empty(5)
            }
            add(JLabel("Reasoning:").apply { font = font.deriveFont(Font.BOLD) }, BorderLayout.NORTH)
            add(reasoningArea, BorderLayout.CENTER)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 100)
        }
        stepsPanel.add(reasoningPanel)
        stepsPanel.add(Box.createVerticalStrut(10))

        // Steps
        plan.plan.steps.forEachIndexed { index, step ->
            val stepPanel = StepPanel(index, step)
            stepPanels.add(stepPanel)
            stepsPanel.add(stepPanel)
            stepsPanel.add(Box.createVerticalStrut(5))
        }

        stepsPanel.add(Box.createVerticalGlue())
        stepsPanel.revalidate()
        stepsPanel.repaint()
    }

    private fun executeNextStep() {
        val plan = currentPlan ?: return
        if (currentStepIndex >= plan.plan.steps.size) {
            statusLabel.text = "All steps completed!"
            executeNextButton.isEnabled = false
            return
        }

        val step = plan.plan.steps[currentStepIndex]
        stepPanels.getOrNull(currentStepIndex)?.setStatus(StepStatus.IN_PROGRESS)
        executeNextButton.isEnabled = false
        statusLabel.text = "Executing step ${currentStepIndex + 1}..."

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = project.getService(BackendClient::class.java)
                val diffResult = client.executeStepSync(step.stepNumber, plan)

                SwingUtilities.invokeLater {
                    pendingDiff = diffResult
                    stepPanels.getOrNull(currentStepIndex)?.showDiff(diffResult)

                    // Show diff preview in editor with highlighting
                    if (diffResult.filePath.isNotEmpty()) {
                        val diffService = project.getService(com.hdsp.pycharm_agent.services.DiffApplicationService::class.java)
                        diffService.showDiffPreviewInEditor(diffResult)
                    }

                    statusLabel.text = "Review the diff. Accept (Tab) or Reject (Esc)"
                    acceptButton.isEnabled = true
                    rejectButton.isEnabled = true
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    statusLabel.text = "Error: ${e.message}"
                    stepPanels.getOrNull(currentStepIndex)?.setStatus(StepStatus.ERROR)
                    executeNextButton.isEnabled = true
                }
            }
        }
    }

    private fun acceptDiff() {
        val diff = pendingDiff ?: return
        acceptButton.isEnabled = false
        rejectButton.isEnabled = false

        // Apply the diff using DiffApplicationService
        val diffService = project.getService(com.hdsp.pycharm_agent.services.DiffApplicationService::class.java)
        val success = diffService.applyDiff(diff)

        if (success) {
            stepPanels.getOrNull(currentStepIndex)?.setStatus(StepStatus.COMPLETED)
            statusLabel.text = "Step ${currentStepIndex + 1} applied successfully."
        } else {
            stepPanels.getOrNull(currentStepIndex)?.setStatus(StepStatus.ERROR)
            statusLabel.text = "Failed to apply diff."
        }

        pendingDiff = null
        currentStepIndex++
        executeNextButton.isEnabled = currentStepIndex < (currentPlan?.plan?.steps?.size ?: 0)

        if (currentStepIndex >= (currentPlan?.plan?.steps?.size ?: 0)) {
            statusLabel.text = "All steps completed!"
        }
    }

    private fun rejectDiff() {
        pendingDiff = null
        acceptButton.isEnabled = false
        rejectButton.isEnabled = false

        stepPanels.getOrNull(currentStepIndex)?.setStatus(StepStatus.REJECTED)
        statusLabel.text = "Step ${currentStepIndex + 1} rejected. Click 'Execute Next Step' to retry or skip."
        executeNextButton.isEnabled = true
    }
}

enum class StepStatus { PENDING, IN_PROGRESS, COMPLETED, REJECTED, ERROR }

/**
 * Individual step panel with status indicator
 */
class StepPanel(
    private val index: Int,
    private val step: PlanStep
) : JPanel(BorderLayout()) {

    private val statusIcon = JLabel("○")
    private val diffArea = JBTextArea().apply {
        isEditable = false
        font = Font("JetBrains Mono", Font.PLAIN, 11).let { f ->
            if (f.family == "JetBrains Mono") f else Font(Font.MONOSPACED, Font.PLAIN, 11)
        }
        isVisible = false
        background = JBColor(Color(40, 44, 52), Color(30, 30, 30))
        foreground = JBColor(Color(200, 200, 200), Color(200, 200, 200))
    }

    init {
        border = JBUI.Borders.empty(3, 5)
        alignmentX = Component.LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)

        // Header with status and description
        val headerPanel = JPanel(BorderLayout()).apply {
            isOpaque = false

            statusIcon.font = Font(Font.MONOSPACED, Font.PLAIN, 14)
            statusIcon.border = JBUI.Borders.emptyRight(8)
            add(statusIcon, BorderLayout.WEST)

            val descLabel = JLabel("<html><b>Step ${index + 1}:</b> ${step.description}</html>")
            add(descLabel, BorderLayout.CENTER)
        }
        add(headerPanel, BorderLayout.NORTH)

        // Details
        val detailsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.emptyLeft(25)

            val toolLabel = JLabel("Tool: ${step.tool}").apply {
                font = font.deriveFont(Font.ITALIC, 11f)
                foreground = JBColor.gray
            }
            add(toolLabel)

            step.targetFile?.let { file ->
                val fileLabel = JLabel("File: $file").apply {
                    font = font.deriveFont(Font.ITALIC, 11f)
                    foreground = JBColor.gray
                }
                add(fileLabel)
            }
        }
        add(detailsPanel, BorderLayout.CENTER)

        // Diff area (initially hidden)
        val diffScrollPane = JBScrollPane(diffArea).apply {
            preferredSize = Dimension(400, 150)
            border = JBUI.Borders.empty(5, 25, 0, 0)
            isVisible = false
        }
        add(diffScrollPane, BorderLayout.SOUTH)
    }

    fun setStatus(status: StepStatus) {
        when (status) {
            StepStatus.PENDING -> {
                statusIcon.text = "○"
                statusIcon.foreground = JBColor.gray
            }
            StepStatus.IN_PROGRESS -> {
                statusIcon.text = "→"
                statusIcon.foreground = JBColor.blue
            }
            StepStatus.COMPLETED -> {
                statusIcon.text = "✓"
                statusIcon.foreground = JBColor(Color(0, 150, 0), Color(100, 200, 100))
            }
            StepStatus.REJECTED -> {
                statusIcon.text = "✗"
                statusIcon.foreground = JBColor.orange
            }
            StepStatus.ERROR -> {
                statusIcon.text = "!"
                statusIcon.foreground = JBColor.red
            }
        }
    }

    fun showDiff(diff: DiffResult) {
        diffArea.text = diff.unifiedDiff
        diffArea.isVisible = true
        (diffArea.parent as? JComponent)?.isVisible = true
        revalidate()
        repaint()
    }
}
