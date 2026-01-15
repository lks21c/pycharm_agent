package com.hdsp.pycharm_agent.toolwindow

import com.hdsp.pycharm_agent.services.*
import com.hdsp.pycharm_agent.settings.AgentSettings
import com.hdsp.pycharm_agent.settings.AgentSettingsConfigurable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*

/**
 * Input mode for the unified panel
 */
enum class InputMode {
    CHAT, AGENT
}

/**
 * Main panel for PyCharm Agent tool window
 * GitHub Copilot style: Unified Chat + Agent with mode toggle
 */
class MainAgentPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val cardLayout = CardLayout()
    private val contentPanel = JPanel(cardLayout)
    private val chatPanel = ChatPanel(project)
    private val agentPanel = AgentModePanel(project)

    private var currentMode = InputMode.CHAT
    private val modeToggleButton = JButton()
    private val modeLabel = JLabel()

    init {
        // Header with Settings button
        val headerPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2, 5)
            val settingsButton = JButton("⚙").apply {
                toolTipText = "Open PyCharm Agent Settings"
                preferredSize = Dimension(28, 28)
                addActionListener {
                    ShowSettingsUtil.getInstance().editConfigurable(project, AgentSettingsConfigurable())
                }
            }
            add(settingsButton, BorderLayout.EAST)
        }
        add(headerPanel, BorderLayout.NORTH)

        // Content panel with CardLayout
        contentPanel.add(chatPanel, "CHAT")
        contentPanel.add(agentPanel, "AGENT")
        add(contentPanel, BorderLayout.CENTER)

        // Bottom mode toggle bar (GitHub Copilot style)
        val modeBar = createModeBar()
        add(modeBar, BorderLayout.SOUTH)

        // Set initial mode
        updateModeUI()
    }

    private fun createModeBar(): JPanel {
        return JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border()),
                JBUI.Borders.empty(6, 10)
            )
            background = JBColor(Color(245, 245, 245), Color(45, 45, 45))

            // Left side: Mode toggle button
            val togglePanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)).apply {
                isOpaque = false

                modeToggleButton.apply {
                    preferredSize = Dimension(90, 26)
                    isFocusPainted = false
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    addActionListener { toggleMode() }
                }
                add(modeToggleButton)

                modeLabel.apply {
                    font = font.deriveFont(11f)
                    foreground = JBColor.gray
                }
                add(modeLabel)
            }
            add(togglePanel, BorderLayout.WEST)

            // Right side: Keyboard shortcut hint
            val hintLabel = JLabel("⇧Tab to switch").apply {
                font = font.deriveFont(10f)
                foreground = JBColor(Color(150, 150, 150), Color(100, 100, 100))
            }
            add(hintLabel, BorderLayout.EAST)
        }
    }

    private fun toggleMode() {
        currentMode = if (currentMode == InputMode.CHAT) InputMode.AGENT else InputMode.CHAT
        updateModeUI()
    }

    private fun updateModeUI() {
        when (currentMode) {
            InputMode.CHAT -> {
                cardLayout.show(contentPanel, "CHAT")
                modeToggleButton.text = "💬 Chat"
                modeToggleButton.toolTipText = "Switch to Agent mode (⇧Tab)"
                modeLabel.text = "General conversation"
                modeToggleButton.background = JBColor(Color(230, 240, 255), Color(50, 60, 80))
            }
            InputMode.AGENT -> {
                cardLayout.show(contentPanel, "AGENT")
                modeToggleButton.text = "🤖 Agent"
                modeToggleButton.toolTipText = "Switch to Chat mode (⇧Tab)"
                modeLabel.text = "Plan & execute code changes"
                modeToggleButton.background = JBColor(Color(255, 240, 230), Color(80, 60, 50))
            }
        }
    }

    /**
     * Handle keyboard shortcut for mode switching
     */
    fun handleKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.VK_TAB && e.isShiftDown) {
            toggleMode()
            return true
        }
        return false
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
 * Agent mode panel - LangChain agent with HITL support (HDSP Agent Server integration)
 */
class AgentModePanel(private val project: Project) : JPanel(BorderLayout()) {

    private val requestArea = JBTextArea(3, 40).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val sendButton = JButton("Send")
    private val stopButton = JButton("Stop").apply { isEnabled = false }

    // Response area (streaming)
    private val responseArea = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }

    // Todo list panel
    private val todosPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(5)
    }

    // Debug/Status panel
    private val debugLabel = JLabel(" ").apply {
        font = font.deriveFont(Font.ITALIC, 11f)
        foreground = JBColor.gray
    }

    // Key rotation indicator
    private val keyStatusLabel = JLabel(" ").apply {
        font = font.deriveFont(10f)
        foreground = JBColor(Color(100, 150, 100), Color(150, 200, 150))
    }

    // State
    private var currentThreadId: String? = null
    private var pendingInterrupt: AgentInterrupt? = null
    private var isRunning = false

    init {
        // Request input panel
        val requestPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(5)
            add(JLabel("Request:"), BorderLayout.NORTH)
            add(JBScrollPane(requestArea).apply {
                preferredSize = Dimension(400, 80)
            }, BorderLayout.CENTER)

            val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
                add(sendButton)
                add(stopButton)
            }
            add(buttonPanel, BorderLayout.SOUTH)
        }
        add(requestPanel, BorderLayout.NORTH)

        // Main content split - Response on top, Todos at bottom
        val contentPanel = JPanel(BorderLayout())

        // Response area with scroll
        val responseScrollPane = JBScrollPane(responseArea).apply {
            border = BorderFactory.createTitledBorder("Response")
            preferredSize = Dimension(400, 200)
        }
        contentPanel.add(responseScrollPane, BorderLayout.CENTER)

        // Todos panel with scroll
        val todosScrollPane = JBScrollPane(todosPanel).apply {
            border = BorderFactory.createTitledBorder("Todos")
            preferredSize = Dimension(400, 150)
        }
        contentPanel.add(todosScrollPane, BorderLayout.SOUTH)

        add(contentPanel, BorderLayout.CENTER)

        // Status bar at bottom
        val statusBar = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(3, 5)
            background = JBColor(Color(245, 245, 245), Color(45, 45, 45))

            add(debugLabel, BorderLayout.WEST)
            add(keyStatusLabel, BorderLayout.EAST)
        }
        add(statusBar, BorderLayout.SOUTH)

        // Event handlers
        sendButton.addActionListener { sendRequest() }
        stopButton.addActionListener { stopAgent() }
        requestArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && e.isControlDown) {
                    sendRequest()
                    e.consume()
                }
            }
        })
    }

    private fun sendRequest() {
        val request = requestArea.text.trim()
        if (request.isEmpty()) return

        sendButton.isEnabled = false
        stopButton.isEnabled = true
        isRunning = true
        responseArea.text = ""
        todosPanel.removeAll()
        todosPanel.revalidate()
        debugLabel.text = "Starting agent..."

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = project.getService(BackendClient::class.java)
                val responseBuilder = StringBuilder()

                client.streamAgentSync(
                    request = request,
                    threadId = currentThreadId,
                    notebookContext = null,
                    onChunk = { chunk ->
                        responseBuilder.append(chunk)
                        SwingUtilities.invokeLater {
                            responseArea.text = responseBuilder.toString()
                            responseArea.caretPosition = responseArea.document.length
                        }
                    },
                    onDebug = { status ->
                        SwingUtilities.invokeLater {
                            debugLabel.text = if (status.isEmpty()) " " else status
                        }
                    },
                    onInterrupt = { interrupt ->
                        SwingUtilities.invokeLater {
                            handleInterrupt(interrupt)
                        }
                    },
                    onTodos = { todos ->
                        SwingUtilities.invokeLater {
                            updateTodos(todos)
                        }
                    },
                    onToolCall = { toolCall ->
                        SwingUtilities.invokeLater {
                            debugLabel.text = "🔧 ${toolCall.tool}"
                        }
                    },
                    onComplete = { threadId ->
                        currentThreadId = threadId
                        SwingUtilities.invokeLater {
                            debugLabel.text = "Complete"
                            finishAgent()
                        }
                    },
                    onKeyRotation = { keyIndex, totalKeys ->
                        SwingUtilities.invokeLater {
                            keyStatusLabel.text = "Key ${keyIndex + 1}/$totalKeys"
                        }
                    }
                )
            } catch (e: AllKeysRateLimitedException) {
                SwingUtilities.invokeLater {
                    debugLabel.text = "⚠️ All API keys rate limited"
                    responseArea.append("\n\n[Error: All API keys are rate limited. Please wait and try again.]")
                    finishAgent()
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    debugLabel.text = "Error: ${e.message}"
                    responseArea.append("\n\n[Error: ${e.message}]")
                    finishAgent()
                }
            }
        }
    }

    private fun handleInterrupt(interrupt: AgentInterrupt) {
        pendingInterrupt = interrupt
        currentThreadId = interrupt.threadId

        debugLabel.text = "⏸️ Waiting for approval: ${interrupt.action}"

        // Show HITL dialog
        val dialog = HITLDialog(
            project = project,
            interrupt = interrupt,
            onApprove = { resumeAgent("approve", null, null) },
            onEdit = { modifiedArgs -> resumeAgent("edit", modifiedArgs, null) },
            onReject = { feedback -> resumeAgent("reject", null, feedback) }
        )
        dialog.show()
    }

    private fun resumeAgent(decision: String, args: Map<String, Any>?, feedback: String?) {
        val threadId = currentThreadId ?: return
        pendingInterrupt = null

        debugLabel.text = "Resuming with decision: $decision"

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = project.getService(BackendClient::class.java)
                val responseBuilder = StringBuilder(responseArea.text)

                client.resumeAgentSync(
                    threadId = threadId,
                    decision = decision,
                    args = args,
                    feedback = feedback,
                    onChunk = { chunk ->
                        responseBuilder.append(chunk)
                        SwingUtilities.invokeLater {
                            responseArea.text = responseBuilder.toString()
                            responseArea.caretPosition = responseArea.document.length
                        }
                    },
                    onDebug = { status ->
                        SwingUtilities.invokeLater {
                            debugLabel.text = if (status.isEmpty()) " " else status
                        }
                    },
                    onInterrupt = { interrupt ->
                        SwingUtilities.invokeLater {
                            handleInterrupt(interrupt)
                        }
                    },
                    onTodos = { todos ->
                        SwingUtilities.invokeLater {
                            updateTodos(todos)
                        }
                    },
                    onToolCall = { toolCall ->
                        SwingUtilities.invokeLater {
                            debugLabel.text = "🔧 ${toolCall.tool}"
                        }
                    },
                    onComplete = { newThreadId ->
                        currentThreadId = newThreadId
                        SwingUtilities.invokeLater {
                            debugLabel.text = "Complete"
                            finishAgent()
                        }
                    },
                    onKeyRotation = { keyIndex, totalKeys ->
                        SwingUtilities.invokeLater {
                            keyStatusLabel.text = "Key ${keyIndex + 1}/$totalKeys"
                        }
                    }
                )
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    debugLabel.text = "Resume error: ${e.message}"
                    finishAgent()
                }
            }
        }
    }

    private fun updateTodos(todos: List<TodoItem>) {
        todosPanel.removeAll()

        for (todo in todos) {
            val statusIcon = when (todo.status) {
                "completed" -> "✅"
                "in_progress" -> "🔄"
                else -> "⏳"
            }
            val todoLabel = JLabel("$statusIcon ${todo.content}").apply {
                border = JBUI.Borders.empty(2, 5)
                foreground = when (todo.status) {
                    "completed" -> JBColor(Color(0, 150, 0), Color(100, 200, 100))
                    "in_progress" -> JBColor.blue
                    else -> JBColor.gray
                }
            }
            todosPanel.add(todoLabel)
        }

        todosPanel.add(Box.createVerticalGlue())
        todosPanel.revalidate()
        todosPanel.repaint()
    }

    private fun stopAgent() {
        isRunning = false
        finishAgent()
    }

    private fun finishAgent() {
        isRunning = false
        sendButton.isEnabled = true
        stopButton.isEnabled = false
    }
}

/**
 * HITL (Human-in-the-Loop) Dialog for agent interrupts
 */
class HITLDialog(
    private val project: Project,
    private val interrupt: AgentInterrupt,
    private val onApprove: () -> Unit,
    private val onEdit: (Map<String, Any>) -> Unit,
    private val onReject: (String) -> Unit
) : DialogWrapper(project, true) {

    private val feedbackArea = JBTextArea(3, 40).apply {
        lineWrap = true
        wrapStyleWord = true
    }

    init {
        title = "Agent Requires Approval"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(10)))
        panel.preferredSize = Dimension(500, 350)

        // Action description
        val actionPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Requested Action")

            val actionLabel = JLabel("<html><b>${interrupt.action}</b></html>")
            add(actionLabel, BorderLayout.NORTH)

            if (interrupt.description.isNotEmpty()) {
                val descArea = JBTextArea(interrupt.description).apply {
                    isEditable = false
                    lineWrap = true
                    wrapStyleWord = true
                    background = JBColor(Color(250, 250, 250), Color(50, 50, 50))
                    border = JBUI.Borders.empty(5)
                }
                add(JBScrollPane(descArea), BorderLayout.CENTER)
            }
        }
        panel.add(actionPanel, BorderLayout.NORTH)

        // Arguments preview
        if (interrupt.args.isNotEmpty()) {
            val argsPanel = JPanel(BorderLayout()).apply {
                border = BorderFactory.createTitledBorder("Arguments")

                val argsText = interrupt.args.entries.joinToString("\n") { (k, v) ->
                    "$k: $v"
                }
                val argsArea = JBTextArea(argsText).apply {
                    isEditable = false
                    font = Font("JetBrains Mono", Font.PLAIN, 11).let { f ->
                        if (f.family == "JetBrains Mono") f else Font(Font.MONOSPACED, Font.PLAIN, 11)
                    }
                    background = JBColor(Color(40, 44, 52), Color(30, 30, 30))
                    foreground = JBColor(Color(200, 200, 200), Color(200, 200, 200))
                    border = JBUI.Borders.empty(5)
                }
                add(JBScrollPane(argsArea).apply {
                    preferredSize = Dimension(450, 120)
                }, BorderLayout.CENTER)
            }
            panel.add(argsPanel, BorderLayout.CENTER)
        }

        // Feedback area (for rejection)
        val feedbackPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Feedback (optional, for rejection)")
            add(JBScrollPane(feedbackArea).apply {
                preferredSize = Dimension(450, 80)
            }, BorderLayout.CENTER)
        }
        panel.add(feedbackPanel, BorderLayout.SOUTH)

        return panel
    }

    override fun createActions(): Array<Action> {
        return arrayOf(
            object : DialogWrapperAction("Approve") {
                override fun doAction(e: java.awt.event.ActionEvent?) {
                    onApprove()
                    close(OK_EXIT_CODE)
                }
            },
            object : DialogWrapperAction("Reject") {
                override fun doAction(e: java.awt.event.ActionEvent?) {
                    onReject(feedbackArea.text)
                    close(CANCEL_EXIT_CODE)
                }
            },
            cancelAction
        )
    }
}

