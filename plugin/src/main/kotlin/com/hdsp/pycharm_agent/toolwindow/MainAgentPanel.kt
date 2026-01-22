package com.hdsp.pycharm_agent.toolwindow

import com.hdsp.pycharm_agent.services.*
import com.hdsp.pycharm_agent.settings.AgentSettings
import com.hdsp.pycharm_agent.settings.AgentSettingsConfigurable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.data.MutableDataSet
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.io.File
import javax.swing.*
import javax.swing.event.HyperlinkEvent
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.StyleSheet

/**
 * Input mode for the unified panel
 */
enum class InputMode {
    CHAT,       // Simple Q&A conversation
    AGENT_V1,   // Plan-Execute pattern (traditional)
    AGENT_V2    // LangChain streaming (HITL)
}

/**
 * Connection status banner - shows server connection state
 */
class ConnectionStatusBanner : JPanel() {
    enum class Status { CONNECTED, DISCONNECTED, CHECKING }

    private val iconLabel = JLabel()
    private val messageLabel = JLabel()
    private val retryButton = JButton("Retry").apply {
        preferredSize = Dimension(70, 26)
        isFocusPainted = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }
    private var onRetry: (() -> Unit)? = null

    init {
        layout = BorderLayout(8, 0)
        border = JBUI.Borders.empty(10, 12)
        isVisible = false

        iconLabel.font = iconLabel.font.deriveFont(14f)
        messageLabel.font = messageLabel.font.deriveFont(12f)

        add(iconLabel, BorderLayout.WEST)
        add(messageLabel, BorderLayout.CENTER)
        add(retryButton, BorderLayout.EAST)

        retryButton.addActionListener { onRetry?.invoke() }
    }

    fun updateStatus(status: Status, message: String? = null, retry: (() -> Unit)? = null) {
        onRetry = retry
        when (status) {
            Status.CONNECTED -> {
                isVisible = false
            }
            Status.CHECKING -> {
                isVisible = true
                background = JBColor(Color(255, 243, 205), Color(66, 56, 30))
                iconLabel.text = "🔄"
                iconLabel.foreground = JBColor(Color(133, 100, 4), Color(250, 204, 21))
                messageLabel.text = message ?: "Checking server connection..."
                messageLabel.foreground = JBColor(Color(133, 100, 4), Color(250, 204, 21))
                retryButton.isVisible = false
            }
            Status.DISCONNECTED -> {
                isVisible = true
                background = JBColor(Color(254, 226, 226), Color(66, 30, 30))
                iconLabel.text = "❌"
                iconLabel.foreground = JBColor(Color(153, 27, 27), Color(248, 113, 113))
                messageLabel.text = message ?: "Server unavailable"
                messageLabel.foreground = JBColor(Color(153, 27, 27), Color(248, 113, 113))
                retryButton.isVisible = true
            }
        }
        revalidate()
        repaint()
    }
}

/**
 * Main panel for PyCharm Agent tool window
 * GitHub Copilot style: Unified Chat + Agent with mode toggle
 */
class MainAgentPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val cardLayout = CardLayout()
    private val contentPanel = JPanel(cardLayout)
    private val chatPanel = ChatPanel(project)
    private val agentV1Panel by lazy { AgentV1Panel(project) }
    private val agentV2Panel = AgentModePanel(project)

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

        // Content panel with CardLayout (3 modes)
        contentPanel.add(chatPanel, "CHAT")
        contentPanel.add(agentV1Panel, "AGENT_V1")
        contentPanel.add(agentV2Panel, "AGENT_V2")
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
        // Cycle through 3 modes: CHAT -> AGENT_V1 -> AGENT_V2 -> CHAT
        currentMode = when (currentMode) {
            InputMode.CHAT -> InputMode.AGENT_V1
            InputMode.AGENT_V1 -> InputMode.AGENT_V2
            InputMode.AGENT_V2 -> InputMode.CHAT
        }
        updateModeUI()
    }

    private fun updateModeUI() {
        when (currentMode) {
            InputMode.CHAT -> {
                cardLayout.show(contentPanel, "CHAT")
                modeToggleButton.text = "💬 Chat"
                modeToggleButton.toolTipText = "Switch to Agent V1 mode (⇧Tab)"
                modeLabel.text = "General conversation"
                modeToggleButton.background = JBColor(Color(230, 240, 255), Color(50, 60, 80))
            }
            InputMode.AGENT_V1 -> {
                cardLayout.show(contentPanel, "AGENT_V1")
                modeToggleButton.text = "📋 V1"
                modeToggleButton.toolTipText = "Switch to Agent V2 mode (⇧Tab)"
                modeLabel.text = "Plan & execute with preview"
                modeToggleButton.background = JBColor(Color(255, 245, 220), Color(80, 70, 40))
            }
            InputMode.AGENT_V2 -> {
                cardLayout.show(contentPanel, "AGENT_V2")
                modeToggleButton.text = "🤖 V2"
                modeToggleButton.toolTipText = "Switch to Chat mode (⇧Tab)"
                modeLabel.text = "LangChain streaming agent"
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
 * Chat mode panel - prompt/response interface with Markdown rendering
 */
class ChatPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val statusBanner = ConnectionStatusBanner()
    private val messagesPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(10)
        background = JBColor(Color(250, 250, 252), Color(32, 33, 36))
    }
    private val scrollPane = JBScrollPane(messagesPanel).apply {
        border = BorderFactory.createEmptyBorder()
        viewport.background = JBColor(Color(250, 250, 252), Color(32, 33, 36))
    }
    private val inputField = JBTextField()
    private val sendButton = JButton("Send")

    private var lastAgentMessagePanel: MessagePanel? = null
    private var isServerConnected = false

    init {
        // Status banner at top
        add(statusBanner, BorderLayout.NORTH)

        // Messages area with scroll
        scrollPane.preferredSize = Dimension(400, 300)
        scrollPane.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        add(scrollPane, BorderLayout.CENTER)

        // Input panel with Material Design styling
        val inputPanel = JPanel(BorderLayout(8, 0)).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border()),
                JBUI.Borders.empty(10, 12)
            )
            background = JBColor(Color(255, 255, 255), Color(40, 42, 46))

            inputField.apply {
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(JBColor(Color(200, 200, 200), Color(60, 62, 66)), 1),
                    JBUI.Borders.empty(8, 12)
                )
                emptyText.text = "Type your message..."
            }
            add(inputField, BorderLayout.CENTER)

            sendButton.apply {
                preferredSize = Dimension(80, 36)
                background = JBColor(Color(88, 101, 242), Color(88, 101, 242))
                foreground = Color.WHITE
                isFocusPainted = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            }
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

        // Initial health check
        checkServerConnection()
    }

    private fun checkServerConnection() {
        statusBanner.updateStatus(
            ConnectionStatusBanner.Status.CHECKING,
            "Checking server connection...",
            retry = { checkServerConnection() }
        )
        setInputEnabled(false)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = project.getService(BackendClient::class.java)
                val isConnected = client.testConnectionSync()

                SwingUtilities.invokeLater {
                    isServerConnected = isConnected
                    if (isConnected) {
                        statusBanner.updateStatus(ConnectionStatusBanner.Status.CONNECTED)
                        setInputEnabled(true)
                    } else {
                        val settings = AgentSettings.getInstance()
                        statusBanner.updateStatus(
                            ConnectionStatusBanner.Status.DISCONNECTED,
                            "Cannot connect to ${settings.backendUrl}",
                            retry = { checkServerConnection() }
                        )
                    }
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    isServerConnected = false
                    statusBanner.updateStatus(
                        ConnectionStatusBanner.Status.DISCONNECTED,
                        "Connection error: ${e.message}",
                        retry = { checkServerConnection() }
                    )
                }
            }
        }
    }

    private fun setInputEnabled(enabled: Boolean) {
        inputField.isEnabled = enabled
        sendButton.isEnabled = enabled
        inputField.emptyText.text = if (enabled)
            "Type your message..."
        else
            "Waiting for server connection..."
    }

    private fun sendMessage() {
        val message = inputField.text.trim()
        if (message.isEmpty()) return

        // Check server connection before sending
        if (!isServerConnected) {
            checkServerConnection()
            return
        }

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
                    // Show error in message panel with better formatting
                    val errorMessage = when {
                        e.message?.contains("timeout", ignoreCase = true) == true ->
                            "⏱️ **Connection Timeout**\n\nThe server took too long to respond. Please try again."
                        e.message?.contains("refused", ignoreCase = true) == true ||
                        e.message?.contains("connect", ignoreCase = true) == true -> {
                            isServerConnected = false
                            statusBanner.updateStatus(
                                ConnectionStatusBanner.Status.DISCONNECTED,
                                "Server connection lost",
                                retry = { checkServerConnection() }
                            )
                            "❌ **Connection Lost**\n\nThe server is no longer available. Please check if the backend is running."
                        }
                        e.message?.contains("rate", ignoreCase = true) == true ->
                            "⚠️ **Rate Limited**\n\nToo many requests. Please wait a moment and try again."
                        else ->
                            "❌ **Error**\n\n${e.message ?: "Unknown error occurred"}"
                    }
                    lastAgentMessagePanel?.updateContent(errorMessage)
                    sendButton.isEnabled = isServerConnected
                }
            }
        }
    }

    private fun addUserMessage(message: String) {
        val panel = MessagePanel("You", message, isUser = true)
        messagesPanel.add(panel)
        messagesPanel.add(Box.createVerticalStrut(12))
        messagesPanel.revalidate()
        scrollToBottom()
    }

    private fun addAgentMessage(message: String): MessagePanel {
        val panel = MessagePanel("Assistant", message, isUser = false)
        messagesPanel.add(panel)
        messagesPanel.add(Box.createVerticalStrut(12))
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
 * Message panel with Material Design styling and Markdown rendering
 * User messages: right-aligned, indigo background
 * Agent messages: left-aligned, dark gray with border
 */
class MessagePanel(
    private val sender: String,
    initialContent: String,
    private val isUser: Boolean
) : JPanel() {

    companion object {
        // Flexmark parser/renderer (singleton for performance)
        private val options = MutableDataSet()
        private val parser: Parser = Parser.builder(options).build()
        private val renderer: HtmlRenderer = HtmlRenderer.builder(options).build()

        // Colors
        private val USER_BG = Color(88, 101, 242)  // Discord-like indigo
        private val AGENT_BG_LIGHT = Color(245, 245, 245)
        private val AGENT_BG_DARK = Color(43, 45, 49)
        private val AGENT_BORDER_LIGHT = Color(200, 200, 200)
        private val AGENT_BORDER_DARK = Color(63, 65, 71)
        private val CODE_BG = Color(30, 30, 30)
        private val INLINE_CODE_BG = "#383A40"
        private val INLINE_CODE_TEXT = "#7DD3FC"
    }

    private val contentPane: JEditorPane
    private val bubble: RoundedPanel
    private var currentContent: String = ""
    private val codeBlocks = mutableListOf<String>()

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(4, 10)

        // Sender label (small, above bubble)
        val senderLabel = JLabel(sender).apply {
            font = font.deriveFont(Font.BOLD, 11f)
            foreground = JBColor(Color(100, 100, 100), Color(150, 150, 150))
            horizontalAlignment = if (isUser) SwingConstants.LEFT else SwingConstants.RIGHT
        }
        // Wrap senderLabel to expand horizontally but use preferred height
        val senderWrapper = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(senderLabel, BorderLayout.CENTER)
        }
        add(senderWrapper)

        // Message bubble with rounded corners
        bubble = RoundedPanel(12).apply {
            layout = BorderLayout()
            background = if (isUser) {
                JBColor(USER_BG, USER_BG)
            } else {
                JBColor(AGENT_BG_LIGHT, AGENT_BG_DARK)
            }
            if (!isUser) {
                border = BorderFactory.createLineBorder(
                    JBColor(AGENT_BORDER_LIGHT, AGENT_BORDER_DARK), 1
                )
            }
        }

        // Content pane with HTML rendering
        contentPane = JEditorPane().apply {
            contentType = "text/html"
            isEditable = false
            isOpaque = false
            border = JBUI.Borders.empty(10, 14)

            // Setup HTML editor kit with custom styles
            val kit = HTMLEditorKit()
            val styleSheet = StyleSheet()
            setupStyleSheet(styleSheet)
            kit.styleSheet = styleSheet
            editorKit = kit

            // Handle copy link clicks
            addHyperlinkListener { e ->
                if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                    val url = e.description
                    if (url != null && url.startsWith("copy:")) {
                        val index = url.removePrefix("copy:").toIntOrNull()
                        if (index != null && index < codeBlocks.size) {
                            copyToClipboard(codeBlocks[index])
                        }
                    }
                }
            }
        }

        bubble.add(contentPane, BorderLayout.CENTER)

        // Wrap bubble with BorderLayout to expand horizontally but use preferred height
        val bubbleWrapper = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(bubble, BorderLayout.CENTER)
        }
        add(bubbleWrapper)

        updateContent(initialContent)
    }

    private fun setupStyleSheet(styleSheet: StyleSheet) {
        val textColor = if (isUser) "#FFFFFF" else "#E0E0E0"
        val linkColor = if (isUser) "#A5D6FF" else "#7DD3FC"
        val inlineCodeBg = if (isUser) "#4752C4" else INLINE_CODE_BG
        val inlineCodeText = if (isUser) "#E0E0E0" else INLINE_CODE_TEXT

        styleSheet.addRule("""
            body {
                font-family: 'Inter', 'Segoe UI', system-ui, sans-serif;
                font-size: 13px;
                color: $textColor;
                margin: 0;
                padding: 0;
            }
        """.trimIndent())

        styleSheet.addRule("""
            p { margin: 0 0 8px 0; }
        """.trimIndent())

        styleSheet.addRule("""
            code {
                background-color: $inlineCodeBg;
                color: $inlineCodeText;
                padding: 2px 6px;
                font-family: 'JetBrains Mono', 'Fira Code', monospace;
                font-size: 12px;
            }
        """.trimIndent())

        styleSheet.addRule("""
            pre {
                background-color: #1E1E1E;
                padding: 12px;
                margin: 8px 0;
                white-space: pre-wrap;
                word-wrap: break-word;
            }
            pre code {
                background-color: transparent;
                padding: 0;
                color: #D4D4D4;
                white-space: pre-wrap;
                word-wrap: break-word;
            }
        """.trimIndent())

        styleSheet.addRule("""
            a {
                color: $linkColor;
                text-decoration: none;
            }
        """.trimIndent())

        styleSheet.addRule("""
            h1 { font-size: 18px; font-weight: bold; margin: 12px 0 8px 0; color: $textColor; }
            h2 { font-size: 16px; font-weight: bold; margin: 10px 0 6px 0; color: $textColor; }
            h3 { font-size: 14px; font-weight: bold; margin: 8px 0 4px 0; color: $textColor; }
        """.trimIndent())

        styleSheet.addRule("""
            ul, ol { margin: 8px 0; padding-left: 24px; }
            li { margin: 4px 0; }
        """.trimIndent())

        styleSheet.addRule("""
            strong, b { font-weight: bold; }
            em, i { font-style: italic; }
            del, s { text-decoration: line-through; }
        """.trimIndent())

        styleSheet.addRule("""
            blockquote {
                border-left: 3px solid #5865F2;
                margin: 8px 0;
                padding: 4px 12px;
                color: ${if (isUser) "#D0D0D0" else "#A0A0A0"};
            }
        """.trimIndent())

        styleSheet.addRule("""
            hr {
                border: none;
                border-top: 1px solid ${if (isUser) "#7078D4" else "#4A4A4A"};
                margin: 12px 0;
            }
        """.trimIndent())

        // Multi-language syntax highlighting (PyCharm Darcula theme colors)
        styleSheet.addRule(".hl-keyword { color: #CC7832; font-weight: bold; }")   // Orange - keywords
        styleSheet.addRule(".hl-string { color: #6A8759; }")                        // Green - strings
        styleSheet.addRule(".hl-number { color: #6897BB; }")                        // Blue - numbers
        styleSheet.addRule(".hl-comment { color: #808080; font-style: italic; }")   // Gray - comments
        styleSheet.addRule(".hl-function { color: #FFC66D; }")                      // Yellow - functions/builtins
        styleSheet.addRule(".hl-type { color: #A9B7C6; }")                          // Light gray - types/classes
        styleSheet.addRule(".hl-operator { color: #A9B7C6; }")                      // Light gray - operators
        styleSheet.addRule(".hl-property { color: #9876AA; }")                      // Purple - properties
        styleSheet.addRule(".hl-annotation { color: #BBB529; }")                    // Yellow-green - annotations
        styleSheet.addRule(".hl-bracket { color: #A9B7C6; }")                       // Light gray - brackets
        styleSheet.addRule(".hl-punctuation { color: #CC7832; }")                   // Orange - punctuation

        // JSON syntax highlighting (specific colors for JSON)
        styleSheet.addRule(".json-key { color: #9CDCFE; }")
        styleSheet.addRule(".json-string { color: #CE9178; }")
        styleSheet.addRule(".json-number { color: #B5CEA8; }")
        styleSheet.addRule(".json-boolean { color: #569CD6; }")
        styleSheet.addRule(".json-null { color: #569CD6; }")
        styleSheet.addRule(".json-bracket { color: #FFD700; }")
        styleSheet.addRule(".json-colon { color: #D4D4D4; }")
        styleSheet.addRule(".json-comma { color: #D4D4D4; }")
    }

    fun updateContent(content: String) {
        currentContent = content
        val html = renderMarkdown(content)
        contentPane.text = html
        contentPane.caretPosition = 0

        // Adjust bubble size
        revalidate()
        repaint()
    }

    private fun renderMarkdown(markdown: String): String {
        if (markdown.isBlank()) return "<html><body></body></html>"

        // Parse and render markdown to HTML
        val document = parser.parse(markdown)
        var htmlBody = renderer.render(document)

        // Apply multi-language syntax highlighting to all code blocks
        htmlBody = highlightCodeBlocks(htmlBody)

        return "<html><body>$htmlBody</body></html>"
    }

    /**
     * Highlight code blocks in HTML content.
     * Replaces <pre><code> structure with styled <div> to allow HTML rendering inside.
     * Adds copy button via anchor tag with hyperlink listener.
     */
    private fun highlightCodeBlocks(html: String): String {
        var result = html

        // Clear previous code blocks
        codeBlocks.clear()

        // Styles
        val containerStyle = "background-color:#1E1E1E;margin:8px 0"
        val headerStyle = "background-color:#2D2D2D;padding:4px 8px"
        val codeStyle = "padding:12px;font-family:monospace;font-size:12px;white-space:pre-wrap;word-wrap:break-word;color:#D4D4D4"
        val langStyle = "color:#808080;font-size:11px"
        val copyLinkStyle = "color:#858585;font-size:11px;text-decoration:none"

        // Pattern to match code blocks with language tag inside <pre>
        val taggedPreCodePattern = """<pre[^>]*>\s*<code\s+class="language-(\w+)">([\s\S]*?)</code>\s*</pre>""".toRegex()
        result = taggedPreCodePattern.replace(result) { match ->
            val language = match.groupValues[1]
            val codeContent = match.groupValues[2]
            val decodedContent = decodeHtmlEntities(codeContent)
            val index = codeBlocks.size
            codeBlocks.add(decodedContent)
            val highlightedCode = SyntaxHighlighter.highlight(decodedContent, language)
            """<div style="$containerStyle">
                <table width="100%" cellpadding="0" cellspacing="0" style="$headerStyle">
                    <tr>
                        <td align="left"><span style="$langStyle">$language</span></td>
                        <td align="right"><a href="copy:$index" style="$copyLinkStyle">Copy</a></td>
                    </tr>
                </table>
                <div style="$codeStyle">$highlightedCode</div>
            </div>"""
        }

        // Pattern to match code blocks without language tag
        val untaggedPreCodePattern = """<pre[^>]*>\s*<code[^>]*>([\s\S]*?)</code>\s*</pre>""".toRegex()
        result = untaggedPreCodePattern.replace(result) { match ->
            val codeContent = match.groupValues[1]
            val decodedContent = decodeHtmlEntities(codeContent)
            val index = codeBlocks.size
            codeBlocks.add(decodedContent)
            val highlightedCode = SyntaxHighlighter.highlightWithAutoDetect(decodedContent)
            """<div style="$containerStyle">
                <table width="100%" cellpadding="0" cellspacing="0" style="$headerStyle">
                    <tr>
                        <td align="left"><span style="$langStyle">code</span></td>
                        <td align="right"><a href="copy:$index" style="$copyLinkStyle">Copy</a></td>
                    </tr>
                </table>
                <div style="$codeStyle">$highlightedCode</div>
            </div>"""
        }

        return result
    }

    /**
     * Decode common HTML entities back to their original characters for highlighting
     */
    private fun decodeHtmlEntities(text: String): String {
        return text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
    }

    /**
     * Copy text to system clipboard
     */
    private fun copyToClipboard(text: String) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(text), null)
    }
}

/**
 * Panel with rounded corners
 */
class RoundedPanel(private val cornerRadius: Int) : JPanel() {
    init {
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = background
        g2.fillRoundRect(0, 0, width, height, cornerRadius, cornerRadius)
        g2.dispose()
        super.paintComponent(g)
    }
}

/**
 * Agent mode panel - LangChain agent with HITL support (HDSP Agent Server integration)
 */
class AgentModePanel(private val project: Project) : JPanel(BorderLayout()) {

    private val statusBanner = ConnectionStatusBanner()
    private val requestArea = JBTextArea(3, 40).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val sendButton = JButton("Send")
    private val stopButton = JButton("Stop").apply { isEnabled = false }

    // Response area (streaming) with Markdown rendering
    private val responseArea = JEditorPane().apply {
        contentType = "text/html"
        isEditable = false

        // Setup HTML editor kit with custom styles
        val kit = HTMLEditorKit()
        val styleSheet = StyleSheet()
        setupAgentStyleSheet(styleSheet)
        kit.styleSheet = styleSheet
        editorKit = kit
    }

    // Raw markdown content for streaming (converted to HTML on display)
    private var rawResponseContent = StringBuilder()

    // Flexmark parser/renderer for markdown
    private val options = MutableDataSet()
    private val parser: Parser = Parser.builder(options).build()
    private val renderer: HtmlRenderer = HtmlRenderer.builder(options).build()

    // Todo list panel
    private val todosPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(5)
    }

    // Next items panel (styled suggestions from agent)
    private val nextItemsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(5)
        isVisible = false
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
    private var isServerConnected = false

    // Execution tracking - track which operations have actually been executed
    private val executedOperations = mutableSetOf<String>()
    private var lastTodos: List<TodoItem> = emptyList()

    // Code blocks for copy functionality
    private val agentCodeBlocks = mutableListOf<String>()

    init {
        // Handle copy link clicks in response area
        responseArea.addHyperlinkListener { e ->
            if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                val url = e.description
                if (url != null && url.startsWith("copy:")) {
                    val index = url.removePrefix("copy:").toIntOrNull()
                    if (index != null && index < agentCodeBlocks.size) {
                        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                        clipboard.setContents(StringSelection(agentCodeBlocks[index]), null)
                    }
                }
            }
        }

        // Top panel with status banner and request area
        val topPanel = JPanel(BorderLayout())
        topPanel.add(statusBanner, BorderLayout.NORTH)

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
        topPanel.add(requestPanel, BorderLayout.CENTER)
        add(topPanel, BorderLayout.NORTH)

        // Main content split - Response on top, Todos at bottom
        val contentPanel = JPanel(BorderLayout())

        // Response area with scroll
        val responseScrollPane = JBScrollPane(responseArea).apply {
            border = BorderFactory.createTitledBorder("Response")
            preferredSize = Dimension(400, 200)
        }
        contentPanel.add(responseScrollPane, BorderLayout.CENTER)

        // Bottom panel containing Todos and Next Items
        val bottomPanel = JPanel(BorderLayout())

        // Todos panel with scroll
        val todosScrollPane = JBScrollPane(todosPanel).apply {
            border = BorderFactory.createTitledBorder("Todos")
            preferredSize = Dimension(400, 120)
        }
        bottomPanel.add(todosScrollPane, BorderLayout.CENTER)

        // Next items panel with scroll
        val nextItemsScrollPane = JBScrollPane(nextItemsPanel).apply {
            border = BorderFactory.createTitledBorder("💡 Next Steps")
            preferredSize = Dimension(400, 100)
        }
        bottomPanel.add(nextItemsScrollPane, BorderLayout.SOUTH)

        contentPanel.add(bottomPanel, BorderLayout.SOUTH)

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

        // Initial health check
        checkServerConnection()
    }

    private fun checkServerConnection() {
        statusBanner.updateStatus(
            ConnectionStatusBanner.Status.CHECKING,
            "Checking server connection...",
            retry = { checkServerConnection() }
        )
        setInputEnabled(false)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = project.getService(BackendClient::class.java)
                val isConnected = client.testConnectionSync()

                SwingUtilities.invokeLater {
                    isServerConnected = isConnected
                    if (isConnected) {
                        statusBanner.updateStatus(ConnectionStatusBanner.Status.CONNECTED)
                        setInputEnabled(true)
                    } else {
                        val settings = AgentSettings.getInstance()
                        statusBanner.updateStatus(
                            ConnectionStatusBanner.Status.DISCONNECTED,
                            "Cannot connect to ${settings.backendUrl}",
                            retry = { checkServerConnection() }
                        )
                    }
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    isServerConnected = false
                    statusBanner.updateStatus(
                        ConnectionStatusBanner.Status.DISCONNECTED,
                        "Connection error: ${e.message}",
                        retry = { checkServerConnection() }
                    )
                }
            }
        }
    }

    private fun setInputEnabled(enabled: Boolean) {
        requestArea.isEnabled = enabled
        sendButton.isEnabled = enabled && !isRunning
        requestArea.emptyText.text = if (enabled)
            "Describe what you want the agent to do... (Ctrl+Enter to send)"
        else
            "Waiting for server connection..."
    }

    private fun sendRequest() {
        val request = requestArea.text.trim()
        if (request.isEmpty()) return

        // Check server connection before sending
        if (!isServerConnected) {
            checkServerConnection()
            return
        }

        sendButton.isEnabled = false
        stopButton.isEnabled = true
        isRunning = true
        rawResponseContent = StringBuilder()
        updateResponseArea("")
        todosPanel.removeAll()
        todosPanel.revalidate()
        nextItemsPanel.removeAll()
        nextItemsPanel.isVisible = false
        nextItemsPanel.revalidate()
        debugLabel.text = "Starting agent..."

        // Reset execution tracking for new request
        executedOperations.clear()
        lastTodos = emptyList()

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
                            updateResponseArea(responseBuilder.toString())
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
                            // Process final response to extract and display next_items
                            val finalContent = responseBuilder.toString()
                            val cleanedContent = processResponseContent(finalContent)
                            updateResponseArea(cleanedContent)
                            finishAgent()
                        }
                    },
                    onKeyRotation = { keyIndex, totalKeys ->
                        SwingUtilities.invokeLater {
                            keyStatusLabel.text = "Key ${keyIndex + 1}/$totalKeys"
                        }
                    }
                )

                // Safety net: If stream returned without error but agent still running,
                // ensure we finish properly (handles edge cases like connection drops)
                SwingUtilities.invokeLater {
                    if (isRunning) {
                        debugLabel.text = "Stream ended"
                        finishAgent()
                    }
                }
            } catch (e: AllKeysRateLimitedException) {
                SwingUtilities.invokeLater {
                    debugLabel.text = "⚠️ All API keys rate limited"
                    appendToResponseArea("\n\n⚠️ Rate Limited\n\nAll API keys are rate limited. Please wait and try again.")
                    markTodosAsInterrupted("Rate limited - file operations may not have completed")
                    finishAgent()
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    // Check if it's a connection error
                    val isConnectionError = e.message?.contains("refused", ignoreCase = true) == true ||
                            e.message?.contains("connect", ignoreCase = true) == true ||
                            e.message?.contains("timeout", ignoreCase = true) == true ||
                            e.message?.contains("closed", ignoreCase = true) == true

                    if (isConnectionError) {
                        isServerConnected = false
                        statusBanner.updateStatus(
                            ConnectionStatusBanner.Status.DISCONNECTED,
                            "Server connection lost",
                            retry = { checkServerConnection() }
                        )
                        debugLabel.text = "❌ Connection lost"
                        appendToResponseArea("\n\n❌ Connection Lost\n\nThe server is no longer available. Please check if the backend is running.")
                        markTodosAsInterrupted("Connection lost - file operations may not have completed")
                    } else {
                        debugLabel.text = "Error: ${e.message}"
                        appendToResponseArea("\n\n❌ Error\n\n${e.message ?: "Unknown error occurred"}")
                        markTodosAsInterrupted("Error occurred - check operations were completed")
                    }
                    finishAgent()
                }
            }
        }
    }

    private fun handleInterrupt(interrupt: AgentInterrupt) {
        pendingInterrupt = interrupt
        currentThreadId = interrupt.threadId

        val settings = AgentSettings.getInstance()

        // Check if auto-approve is enabled - skip dialogs and execute directly
        if (settings.autoApprove) {
            debugLabel.text = "🤖 Auto-approving: ${interrupt.action}"
            when (interrupt.action) {
                "write_file_tool" -> {
                    val path = interrupt.args["path"] as? String ?: run {
                        resumeAgentWithError("Missing 'path' argument")
                        return
                    }
                    val content = interrupt.args["content"] as? String ?: run {
                        resumeAgentWithError("Missing 'content' argument")
                        return
                    }
                    executeFileWrite(path, content)
                }
                "edit_file_tool" -> {
                    val path = interrupt.args["path"] as? String ?: run {
                        resumeAgentWithError("Missing 'path' argument")
                        return
                    }
                    val oldString = interrupt.args["old_string"] as? String ?: run {
                        resumeAgentWithError("Missing 'old_string' argument")
                        return
                    }
                    val newString = interrupt.args["new_string"] as? String ?: run {
                        resumeAgentWithError("Missing 'new_string' argument")
                        return
                    }
                    val replaceAll = interrupt.args["replace_all"] as? Boolean ?: false
                    executeFileEdit(path, oldString, newString, replaceAll)
                }
                "multiedit_file_tool" -> {
                    val path = interrupt.args["path"] as? String ?: run {
                        resumeAgentWithError("Missing 'path' argument")
                        return
                    }
                    @Suppress("UNCHECKED_CAST")
                    val edits = interrupt.args["edits"] as? List<Map<String, Any>> ?: run {
                        resumeAgentWithError("Missing 'edits' argument")
                        return
                    }
                    executeMultiEdit(path, edits)
                }
                "jupyter_cell_tool" -> {
                    // Convert Jupyter cell to PyCharm-compatible Python file execution
                    val code = interrupt.args["code"] as? String ?: run {
                        resumeAgentWithError("Missing 'code' argument")
                        return
                    }
                    val cellType = interrupt.args["cell_type"] as? String ?: "code"
                    executeJupyterCell(code, cellType)
                }
                else -> {
                    // Auto-approve generic tool calls
                    resumeAgent("approve", null, null)
                }
            }
            return
        }

        // Manual approval mode - show dialogs
        debugLabel.text = "⏸️ Waiting for approval: ${interrupt.action}"

        // Route file operations to specialized handlers
        when (interrupt.action) {
            "write_file_tool" -> showFileWriteDialog(interrupt)
            "edit_file_tool" -> showFileEditDialog(interrupt)
            "multiedit_file_tool" -> showMultiEditDialog(interrupt)
            "jupyter_cell_tool" -> showJupyterCellDialog(interrupt)
            else -> {
                // Show generic HITL dialog for other actions
                val dialog = HITLDialog(
                    project = project,
                    interrupt = interrupt,
                    onApprove = { resumeAgent("approve", null, null) },
                    onEdit = { modifiedArgs -> resumeAgent("edit", modifiedArgs, null) },
                    onReject = { feedback -> resumeAgent("reject", null, feedback) }
                )
                dialog.show()
            }
        }
    }

    private fun showJupyterCellDialog(interrupt: AgentInterrupt) {
        val code = interrupt.args["code"] as? String ?: run {
            resumeAgentWithError("Missing 'code' argument")
            return
        }
        val cellType = interrupt.args["cell_type"] as? String ?: "code"

        val dialog = JupyterCellDialog(
            project = project,
            code = code,
            cellType = cellType,
            onApprove = { executeJupyterCell(code, cellType) },
            onReject = { feedback -> resumeAgent("reject", null, feedback) }
        )
        dialog.show()
    }

    private fun showFileWriteDialog(interrupt: AgentInterrupt) {
        val path = interrupt.args["path"] as? String ?: run {
            resumeAgentWithError("Missing 'path' argument")
            return
        }
        val content = interrupt.args["content"] as? String ?: run {
            resumeAgentWithError("Missing 'content' argument")
            return
        }

        val dialog = FileOperationDialog(
            project = project,
            operationType = "write",
            filePath = path,
            newContent = content,
            onApprove = { executeFileWrite(path, content) },
            onReject = { feedback -> resumeAgent("reject", null, feedback) }
        )
        dialog.show()
    }

    private fun showFileEditDialog(interrupt: AgentInterrupt) {
        val path = interrupt.args["path"] as? String ?: run {
            resumeAgentWithError("Missing 'path' argument")
            return
        }
        val oldString = interrupt.args["old_string"] as? String ?: run {
            resumeAgentWithError("Missing 'old_string' argument")
            return
        }
        val newString = interrupt.args["new_string"] as? String ?: run {
            resumeAgentWithError("Missing 'new_string' argument")
            return
        }
        val replaceAll = interrupt.args["replace_all"] as? Boolean ?: false

        val dialog = FileOperationDialog(
            project = project,
            operationType = "edit",
            filePath = path,
            oldString = oldString,
            newString = newString,
            replaceAll = replaceAll,
            onApprove = { executeFileEdit(path, oldString, newString, replaceAll) },
            onReject = { feedback -> resumeAgent("reject", null, feedback) }
        )
        dialog.show()
    }

    @Suppress("UNCHECKED_CAST")
    private fun showMultiEditDialog(interrupt: AgentInterrupt) {
        val path = interrupt.args["path"] as? String ?: run {
            resumeAgentWithError("Missing 'path' argument")
            return
        }
        val edits = interrupt.args["edits"] as? List<Map<String, Any>> ?: run {
            resumeAgentWithError("Missing 'edits' argument")
            return
        }

        val dialog = FileOperationDialog(
            project = project,
            operationType = "multiedit",
            filePath = path,
            edits = edits,
            onApprove = { executeMultiEdit(path, edits) },
            onReject = { feedback -> resumeAgent("reject", null, feedback) }
        )
        dialog.show()
    }

    private fun resolveWorkspacePath(relativePath: String): String {
        val settings = AgentSettings.getInstance()
        val workspaceRoot = settings.workspaceRoot.ifBlank { project.basePath ?: "." }
        return File(workspaceRoot, relativePath).absolutePath
    }

    private fun resumeAgentWithError(errorMessage: String) {
        debugLabel.text = "❌ Error: $errorMessage"
        resumeAgent("approve", mapOf(
            "execution_result" to mapOf(
                "success" to false,
                "error" to errorMessage
            )
        ), null)
    }

    private fun resumeAgentWithResult(path: String, success: Boolean, error: String?) {
        val result = mutableMapOf<String, Any>(
            "success" to success,
            "path" to path
        )
        if (error != null) result["error"] = error

        resumeAgent("approve", mapOf("execution_result" to result), null)
    }

    private fun executeFileWrite(path: String, content: String) {
        debugLabel.text = "📝 Writing file: $path"

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val fullPath = resolveWorkspacePath(path)
                val file = File(fullPath)

                // Create parent directories if needed
                file.parentFile?.mkdirs()

                // Write file
                ApplicationManager.getApplication().invokeAndWait {
                    WriteAction.run<Throwable> {
                        file.writeText(content)
                        // Refresh VFS to show the file in IDE
                        LocalFileSystem.getInstance().refreshAndFindFileByPath(fullPath)
                    }
                }

                // Track successful execution
                executedOperations.add("write:$path")

                SwingUtilities.invokeLater {
                    debugLabel.text = "✅ File written: $path"
                    resumeAgentWithResult(path, true, null)
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    debugLabel.text = "❌ Write failed: ${e.message}"
                    resumeAgentWithResult(path, false, e.message)
                }
            }
        }
    }

    private fun executeFileEdit(path: String, oldString: String, newString: String, replaceAll: Boolean) {
        debugLabel.text = "✏️ Editing file: $path"

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val fullPath = resolveWorkspacePath(path)
                val file = File(fullPath)

                if (!file.exists()) {
                    throw java.io.FileNotFoundException("File not found: $path")
                }

                val originalText = file.readText()
                val newText = if (replaceAll) {
                    originalText.replace(oldString, newString)
                } else {
                    originalText.replaceFirst(oldString, newString)
                }

                if (originalText == newText) {
                    throw IllegalStateException("old_string not found in file")
                }

                ApplicationManager.getApplication().invokeAndWait {
                    WriteAction.run<Throwable> {
                        file.writeText(newText)
                        LocalFileSystem.getInstance().refreshAndFindFileByPath(fullPath)
                    }
                }

                // Track successful execution
                executedOperations.add("edit:$path")

                SwingUtilities.invokeLater {
                    debugLabel.text = "✅ File edited: $path"
                    resumeAgentWithResult(path, true, null)
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    debugLabel.text = "❌ Edit failed: ${e.message}"
                    resumeAgentWithResult(path, false, e.message)
                }
            }
        }
    }

    /**
     * Execute Jupyter cell code in PyCharm context.
     * Converts Jupyter cell to Python file append operation.
     */
    private fun executeJupyterCell(code: String, cellType: String) {
        debugLabel.text = "📝 Executing cell: ${cellType}"

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val settings = AgentSettings.getInstance()
                val workspaceRoot = settings.workspaceRoot.ifBlank { project.basePath ?: "." }

                // Determine target file - use a.py or main.py as default
                val targetPath = File(workspaceRoot, "a.py")

                // Prepare code content
                val contentToWrite = if (cellType == "markdown") {
                    // Convert markdown to Python comments
                    code.lines().joinToString("\n") { "# $it" } + "\n"
                } else {
                    // Code cell - add newlines for separation
                    code + "\n\n"
                }

                // Append to file or create if doesn't exist
                ApplicationManager.getApplication().invokeAndWait {
                    WriteAction.run<Throwable> {
                        val existingContent = if (targetPath.exists()) {
                            targetPath.readText()
                        } else {
                            // Create file with header
                            "# Generated by PyCharm Agent\n\n"
                        }

                        val newContent = existingContent + contentToWrite
                        targetPath.writeText(newContent)

                        // Refresh VFS to show the file in IDE
                        LocalFileSystem.getInstance().refreshAndFindFileByPath(targetPath.absolutePath)
                    }
                }

                // Track execution
                executedOperations.add("jupyter_cell:${code.hashCode()}")

                SwingUtilities.invokeLater {
                    debugLabel.text = "✅ Cell executed: ${targetPath.name}"
                    resumeAgent("approve", mapOf(
                        "execution_result" to mapOf(
                            "success" to true,
                            "output" to "Code written to ${targetPath.name}",
                            "cell_type" to cellType
                        )
                    ), null)
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    debugLabel.text = "❌ Cell execution failed: ${e.message}"
                    resumeAgent("approve", mapOf(
                        "execution_result" to mapOf(
                            "success" to false,
                            "error" to (e.message ?: "Unknown error")
                        )
                    ), null)
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun executeMultiEdit(path: String, edits: List<Map<String, Any>>) {
        debugLabel.text = "✏️ Multi-editing file: $path (${edits.size} edits)"

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val fullPath = resolveWorkspacePath(path)
                val file = File(fullPath)

                if (!file.exists()) {
                    throw java.io.FileNotFoundException("File not found: $path")
                }

                var text = file.readText()
                var appliedCount = 0

                for (edit in edits) {
                    val oldString = edit["old_string"] as? String ?: continue
                    val newString = edit["new_string"] as? String ?: continue
                    val replaceAll = edit["replace_all"] as? Boolean ?: false

                    val newText = if (replaceAll) {
                        text.replace(oldString, newString)
                    } else {
                        text.replaceFirst(oldString, newString)
                    }

                    if (newText != text) {
                        text = newText
                        appliedCount++
                    }
                }

                ApplicationManager.getApplication().invokeAndWait {
                    WriteAction.run<Throwable> {
                        file.writeText(text)
                        LocalFileSystem.getInstance().refreshAndFindFileByPath(fullPath)
                    }
                }

                SwingUtilities.invokeLater {
                    debugLabel.text = "✅ Multi-edit complete: $appliedCount/${edits.size} edits applied"
                    resumeAgent("approve", mapOf(
                        "execution_result" to mapOf(
                            "success" to true,
                            "path" to path,
                            "edits_applied" to appliedCount,
                            "edits_total" to edits.size
                        )
                    ), null)
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    debugLabel.text = "❌ Multi-edit failed: ${e.message}"
                    resumeAgentWithResult(path, false, e.message)
                }
            }
        }
    }

    private fun resumeAgent(decision: String, args: Map<String, Any>?, feedback: String?) {
        val threadId = currentThreadId ?: return
        pendingInterrupt = null

        debugLabel.text = "Resuming with decision: $decision"

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = project.getService(BackendClient::class.java)
                val responseBuilder = StringBuilder(rawResponseContent.toString())

                client.resumeAgentSync(
                    threadId = threadId,
                    decision = decision,
                    args = args,
                    feedback = feedback,
                    onChunk = { chunk ->
                        responseBuilder.append(chunk)
                        SwingUtilities.invokeLater {
                            updateResponseArea(responseBuilder.toString())
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
                            // Process final response to extract and display next_items
                            val finalContent = responseBuilder.toString()
                            val cleanedContent = processResponseContent(finalContent)
                            updateResponseArea(cleanedContent)
                            finishAgent()
                        }
                    },
                    onKeyRotation = { keyIndex, totalKeys ->
                        SwingUtilities.invokeLater {
                            keyStatusLabel.text = "Key ${keyIndex + 1}/$totalKeys"
                        }
                    }
                )

                // Safety net: If stream returned without error but agent still running,
                // ensure we finish properly (handles edge cases like connection drops)
                SwingUtilities.invokeLater {
                    if (isRunning) {
                        debugLabel.text = "Stream ended"
                        finishAgent()
                    }
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    // Check if it's a connection error
                    val isConnectionError = e.message?.contains("refused", ignoreCase = true) == true ||
                            e.message?.contains("connect", ignoreCase = true) == true ||
                            e.message?.contains("timeout", ignoreCase = true) == true ||
                            e.message?.contains("closed", ignoreCase = true) == true

                    if (isConnectionError) {
                        isServerConnected = false
                        statusBanner.updateStatus(
                            ConnectionStatusBanner.Status.DISCONNECTED,
                            "Server connection lost",
                            retry = { checkServerConnection() }
                        )
                        debugLabel.text = "❌ Connection lost"
                        appendToResponseArea("\n\n❌ Connection Lost\n\nThe server is no longer available. Please check if the backend is running.")
                        markTodosAsInterrupted("Connection lost during resume - file operations may not have completed")
                    } else {
                        debugLabel.text = "Resume error: ${e.message}"
                        appendToResponseArea("\n\n❌ Resume Error\n\n${e.message ?: "Unknown error occurred"}")
                        markTodosAsInterrupted("Resume error - check operations were completed")
                    }
                    finishAgent()
                }
            }
        }
    }

    private fun updateTodos(todos: List<TodoItem>) {
        lastTodos = todos
        renderTodos(todos)
    }

    private fun renderTodos(todos: List<TodoItem>, errorMessage: String? = null) {
        todosPanel.removeAll()

        for (todo in todos) {
            val statusIcon = when (todo.status) {
                "completed" -> "✅"
                "in_progress" -> "🔄"
                "error", "interrupted" -> "❌"
                else -> "⏳"
            }
            val todoLabel = JLabel("$statusIcon ${todo.content}").apply {
                border = JBUI.Borders.empty(2, 5)
                foreground = when (todo.status) {
                    "completed" -> JBColor(Color(0, 150, 0), Color(100, 200, 100))
                    "in_progress" -> JBColor.blue
                    "error", "interrupted" -> JBColor(Color(200, 50, 50), Color(255, 100, 100))
                    else -> JBColor.gray
                }
            }
            todosPanel.add(todoLabel)
        }

        // Show error message if present
        if (errorMessage != null) {
            val errorLabel = JLabel("⚠️ $errorMessage").apply {
                border = JBUI.Borders.empty(5)
                foreground = JBColor(Color(200, 100, 0), Color(255, 150, 50))
                font = font.deriveFont(Font.ITALIC)
            }
            todosPanel.add(errorLabel)
        }

        todosPanel.add(Box.createVerticalGlue())
        todosPanel.revalidate()
        todosPanel.repaint()
    }

    /**
     * Mark in_progress todos as interrupted when an error occurs.
     * This helps distinguish between "marked complete by agent" vs "actually executed".
     */
    private fun markTodosAsInterrupted(errorMessage: String) {
        val interruptedTodos = lastTodos.map { todo ->
            if (todo.status == "in_progress") {
                TodoItem(todo.content, "interrupted")
            } else {
                todo
            }
        }
        renderTodos(interruptedTodos, errorMessage)
    }

    /**
     * Data class for next item suggestions
     */
    data class NextItem(val subject: String, val description: String)

    /**
     * Parse and extract next_items from response content.
     * Returns the cleaned content (without the JSON block) and extracted items.
     */
    private fun parseNextItems(content: String): Pair<String, List<NextItem>> {
        // Pattern to match next_items JSON block
        val jsonPattern = """\{[\s\S]*?"next_items"\s*:\s*\[([\s\S]*?)\][\s\S]*?\}""".toRegex()
        val match = jsonPattern.find(content)

        if (match == null) {
            return Pair(content, emptyList())
        }

        val items = mutableListOf<NextItem>()

        // Extract individual items from the array
        val itemPattern = """\{\s*"subject"\s*:\s*"([^"]+)"\s*,\s*"description"\s*:\s*"([^"]+)"\s*\}""".toRegex()
        val arrayContent = match.groupValues[1]

        itemPattern.findAll(arrayContent).forEach { itemMatch ->
            val subject = itemMatch.groupValues[1]
            val description = itemMatch.groupValues[2]
            items.add(NextItem(subject, description))
        }

        // Remove the JSON block from content
        val cleanedContent = content.replace(match.value, "").trim()

        return Pair(cleanedContent, items)
    }

    /**
     * Update next items panel with styled items.
     */
    private fun updateNextItems(items: List<NextItem>) {
        nextItemsPanel.removeAll()

        if (items.isEmpty()) {
            nextItemsPanel.isVisible = false
            nextItemsPanel.revalidate()
            nextItemsPanel.repaint()
            return
        }

        nextItemsPanel.isVisible = true

        for ((index, item) in items.withIndex()) {
            val itemPanel = JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(3, 5)
            }

            // Subject label with icon
            val subjectLabel = JLabel("→ ${item.subject}").apply {
                font = font.deriveFont(Font.BOLD, 12f)
                foreground = JBColor(Color(70, 130, 180), Color(100, 180, 255))
            }
            itemPanel.add(subjectLabel, BorderLayout.NORTH)

            // Description label
            val descLabel = JLabel("<html><body style='width: 350px'>${item.description}</body></html>").apply {
                font = font.deriveFont(11f)
                foreground = JBColor(Color(100, 100, 100), Color(180, 180, 180))
                border = JBUI.Borders.empty(2, 15, 0, 0)
            }
            itemPanel.add(descLabel, BorderLayout.CENTER)

            nextItemsPanel.add(itemPanel)

            // Add separator except for last item
            if (index < items.size - 1) {
                nextItemsPanel.add(Box.createVerticalStrut(5))
            }
        }

        nextItemsPanel.add(Box.createVerticalGlue())
        nextItemsPanel.revalidate()
        nextItemsPanel.repaint()
    }

    /**
     * Process response content: parse next_items and update UI accordingly.
     * Returns cleaned content for display in response area.
     */
    private fun processResponseContent(content: String): String {
        val (cleanedContent, nextItems) = parseNextItems(content)
        if (nextItems.isNotEmpty()) {
            updateNextItems(nextItems)
        }
        return cleanedContent
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

    /**
     * Setup stylesheet for agent response area (similar to MessagePanel)
     */
    private fun setupAgentStyleSheet(styleSheet: StyleSheet) {
        val textColor = "#E0E0E0"
        val linkColor = "#7DD3FC"

        styleSheet.addRule("""
            body {
                font-family: 'Inter', 'Segoe UI', system-ui, sans-serif;
                font-size: 13px;
                color: $textColor;
                margin: 8px;
                padding: 0;
                background-color: #2B2D31;
            }
        """.trimIndent())

        styleSheet.addRule("p { margin: 0 0 8px 0; }")
        styleSheet.addRule("""
            code {
                background-color: #383A40;
                color: #7DD3FC;
                padding: 2px 6px;
                font-family: 'JetBrains Mono', 'Fira Code', monospace;
                font-size: 12px;
            }
        """.trimIndent())

        styleSheet.addRule("""
            pre {
                background-color: #1E1E1E;
                padding: 12px;
                margin: 8px 0;
            }
            pre code {
                background-color: transparent;
                padding: 0;
                color: #D4D4D4;
            }
        """.trimIndent())

        styleSheet.addRule("a { color: $linkColor; text-decoration: none; }")
        styleSheet.addRule("h1 { font-size: 18px; font-weight: bold; margin: 12px 0 8px 0; color: $textColor; }")
        styleSheet.addRule("h2 { font-size: 16px; font-weight: bold; margin: 10px 0 6px 0; color: $textColor; }")
        styleSheet.addRule("h3 { font-size: 14px; font-weight: bold; margin: 8px 0 4px 0; color: $textColor; }")
        styleSheet.addRule("ul, ol { margin: 8px 0; padding-left: 24px; }")
        styleSheet.addRule("li { margin: 4px 0; }")
        styleSheet.addRule("strong, b { font-weight: bold; }")
        styleSheet.addRule("em, i { font-style: italic; }")
        styleSheet.addRule("blockquote { border-left: 3px solid #5865F2; margin: 8px 0; padding: 4px 12px; color: #A0A0A0; }")
        styleSheet.addRule("hr { border: none; border-top: 1px solid #4A4A4A; margin: 12px 0; }")

        // Multi-language syntax highlighting (PyCharm Darcula theme colors)
        styleSheet.addRule(".hl-keyword { color: #CC7832; font-weight: bold; }")
        styleSheet.addRule(".hl-string { color: #6A8759; }")
        styleSheet.addRule(".hl-number { color: #6897BB; }")
        styleSheet.addRule(".hl-comment { color: #808080; font-style: italic; }")
        styleSheet.addRule(".hl-function { color: #FFC66D; }")
        styleSheet.addRule(".hl-type { color: #A9B7C6; }")
        styleSheet.addRule(".hl-operator { color: #A9B7C6; }")
        styleSheet.addRule(".hl-property { color: #9876AA; }")
        styleSheet.addRule(".hl-annotation { color: #BBB529; }")
        styleSheet.addRule(".hl-bracket { color: #A9B7C6; }")
        styleSheet.addRule(".hl-punctuation { color: #CC7832; }")

        // JSON syntax highlighting
        styleSheet.addRule(".json-key { color: #9CDCFE; }")
        styleSheet.addRule(".json-string { color: #CE9178; }")
        styleSheet.addRule(".json-number { color: #B5CEA8; }")
        styleSheet.addRule(".json-boolean { color: #569CD6; }")
        styleSheet.addRule(".json-null { color: #569CD6; }")
        styleSheet.addRule(".json-bracket { color: #FFD700; }")
        styleSheet.addRule(".json-colon { color: #D4D4D4; }")
        styleSheet.addRule(".json-comma { color: #D4D4D4; }")
    }

    /**
     * Render markdown content to HTML with syntax highlighting
     */
    private fun renderAgentMarkdown(markdown: String): String {
        if (markdown.isBlank()) return "<html><body></body></html>"

        val document = parser.parse(markdown)
        var htmlBody = renderer.render(document)
        htmlBody = highlightAgentCodeBlocks(htmlBody)
        return "<html><body>$htmlBody</body></html>"
    }

    /**
     * Highlight code blocks in agent response HTML
     * Replaces <pre><code> structure with styled <div> to allow HTML rendering inside.
     * Adds copy button via anchor tag with hyperlink listener.
     */
    private fun highlightAgentCodeBlocks(html: String): String {
        var result = html

        // Clear previous code blocks
        agentCodeBlocks.clear()

        // Helper function to decode HTML entities
        fun decode(text: String): String = text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")

        // Styles
        val containerStyle = "background-color:#1E1E1E;margin:8px 0"
        val headerStyle = "background-color:#2D2D2D;padding:4px 8px"
        val codeStyle = "padding:12px;font-family:monospace;font-size:12px;white-space:pre-wrap;word-wrap:break-word;color:#D4D4D4"
        val langStyle = "color:#808080;font-size:11px"
        val copyLinkStyle = "color:#858585;font-size:11px;text-decoration:none"

        // Pattern to match code blocks with language tag inside <pre>
        val taggedPreCodePattern = """<pre[^>]*>\s*<code\s+class="language-(\w+)">([\s\S]*?)</code>\s*</pre>""".toRegex()
        result = taggedPreCodePattern.replace(result) { match ->
            val language = match.groupValues[1]
            val codeContent = match.groupValues[2]
            val decodedContent = decode(codeContent)
            val index = agentCodeBlocks.size
            agentCodeBlocks.add(decodedContent)
            val highlightedCode = SyntaxHighlighter.highlight(decodedContent, language)
            """<div style="$containerStyle">
                <table width="100%" cellpadding="0" cellspacing="0" style="$headerStyle">
                    <tr>
                        <td align="left"><span style="$langStyle">$language</span></td>
                        <td align="right"><a href="copy:$index" style="$copyLinkStyle">Copy</a></td>
                    </tr>
                </table>
                <div style="$codeStyle">$highlightedCode</div>
            </div>"""
        }

        // Pattern to match code blocks without language tag
        val untaggedPreCodePattern = """<pre[^>]*>\s*<code[^>]*>([\s\S]*?)</code>\s*</pre>""".toRegex()
        result = untaggedPreCodePattern.replace(result) { match ->
            val codeContent = match.groupValues[1]
            val decodedContent = decode(codeContent)
            val index = agentCodeBlocks.size
            agentCodeBlocks.add(decodedContent)
            val highlightedCode = SyntaxHighlighter.highlightWithAutoDetect(decodedContent)
            """<div style="$containerStyle">
                <table width="100%" cellpadding="0" cellspacing="0" style="$headerStyle">
                    <tr>
                        <td align="left"><span style="$langStyle">code</span></td>
                        <td align="right"><a href="copy:$index" style="$copyLinkStyle">Copy</a></td>
                    </tr>
                </table>
                <div style="$codeStyle">$highlightedCode</div>
            </div>"""
        }

        return result
    }

    /**
     * Update response area with rendered markdown
     */
    private fun updateResponseArea(content: String) {
        rawResponseContent = StringBuilder(content)
        responseArea.text = renderAgentMarkdown(content)
    }

    /**
     * Append content to response area with rendered markdown
     */
    private fun appendToResponseArea(chunk: String) {
        rawResponseContent.append(chunk)
        responseArea.text = renderAgentMarkdown(rawResponseContent.toString())
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

/**
 * File Operation Dialog for agent file modifications
 * Shows preview of file changes before applying
 */
class FileOperationDialog(
    private val project: Project,
    private val operationType: String,  // "write", "edit", "multiedit"
    private val filePath: String,
    private val newContent: String? = null,
    private val oldString: String? = null,
    private val newString: String? = null,
    private val replaceAll: Boolean = false,
    private val edits: List<Map<String, Any>>? = null,
    private val onApprove: () -> Unit,
    private val onReject: (String) -> Unit
) : DialogWrapper(project, true) {

    companion object {
        private val CODE_BG = JBColor(Color(40, 44, 52), Color(30, 30, 30))
        private val CODE_FG = JBColor(Color(200, 200, 200), Color(200, 200, 200))
        private val ADD_COLOR = JBColor(Color(0, 100, 0), Color(50, 150, 50))
        private val REMOVE_COLOR = JBColor(Color(150, 0, 0), Color(200, 80, 80))
    }

    private val feedbackArea = JBTextArea(2, 40).apply {
        lineWrap = true
        wrapStyleWord = true
    }

    init {
        title = when (operationType) {
            "write" -> "Write File: $filePath"
            "edit" -> "Edit File: $filePath"
            "multiedit" -> "Multi-Edit File: $filePath"
            else -> "File Operation: $filePath"
        }
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(10)))
        panel.preferredSize = Dimension(600, 450)

        // File path header
        val headerPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(5)
            val pathLabel = JLabel("<html><b>📄 $filePath</b></html>")
            add(pathLabel, BorderLayout.WEST)
        }
        panel.add(headerPanel, BorderLayout.NORTH)

        // Content preview based on operation type
        val contentPanel = when (operationType) {
            "write" -> createWritePreview()
            "edit" -> createEditPreview()
            "multiedit" -> createMultiEditPreview()
            else -> JPanel()
        }
        panel.add(contentPanel, BorderLayout.CENTER)

        // Feedback area (for rejection)
        val feedbackPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Feedback (optional)")
            add(JBScrollPane(feedbackArea).apply {
                preferredSize = Dimension(550, 60)
            }, BorderLayout.CENTER)
        }
        panel.add(feedbackPanel, BorderLayout.SOUTH)

        return panel
    }

    private fun createWritePreview(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createTitledBorder("📝 New File Content")

        val contentArea = JBTextArea(newContent ?: "").apply {
            isEditable = false
            font = Font("JetBrains Mono", Font.PLAIN, 12).let { f ->
                if (f.family == "JetBrains Mono") f else Font(Font.MONOSPACED, Font.PLAIN, 12)
            }
            background = CODE_BG
            foreground = CODE_FG
            border = JBUI.Borders.empty(10)
        }

        panel.add(JBScrollPane(contentArea).apply {
            preferredSize = Dimension(550, 280)
        }, BorderLayout.CENTER)

        // File info
        val infoLabel = JLabel("<html><i>Lines: ${newContent?.lines()?.size ?: 0}, Characters: ${newContent?.length ?: 0}</i></html>").apply {
            foreground = JBColor.gray
            border = JBUI.Borders.empty(5)
        }
        panel.add(infoLabel, BorderLayout.SOUTH)

        return panel
    }

    private fun createEditPreview(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createTitledBorder("✏️ Edit Preview")

        val diffPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = CODE_BG
            border = JBUI.Borders.empty(10)
        }

        // Old string (to be removed)
        val oldLabel = JLabel("- Remove:").apply {
            foreground = REMOVE_COLOR
            font = font.deriveFont(Font.BOLD)
            border = JBUI.Borders.empty(0, 0, 5, 0)
        }
        diffPanel.add(oldLabel)

        val oldArea = JBTextArea(oldString ?: "").apply {
            isEditable = false
            font = Font("JetBrains Mono", Font.PLAIN, 12).let { f ->
                if (f.family == "JetBrains Mono") f else Font(Font.MONOSPACED, Font.PLAIN, 12)
            }
            background = JBColor(Color(60, 30, 30), Color(60, 30, 30))
            foreground = REMOVE_COLOR
            border = JBUI.Borders.empty(5)
        }
        diffPanel.add(JBScrollPane(oldArea).apply {
            preferredSize = Dimension(530, 100)
            maximumSize = Dimension(Int.MAX_VALUE, 100)
        })

        diffPanel.add(Box.createVerticalStrut(15))

        // New string (to be added)
        val newLabel = JLabel("+ Add:").apply {
            foreground = ADD_COLOR
            font = font.deriveFont(Font.BOLD)
            border = JBUI.Borders.empty(0, 0, 5, 0)
        }
        diffPanel.add(newLabel)

        val newArea = JBTextArea(newString ?: "").apply {
            isEditable = false
            font = Font("JetBrains Mono", Font.PLAIN, 12).let { f ->
                if (f.family == "JetBrains Mono") f else Font(Font.MONOSPACED, Font.PLAIN, 12)
            }
            background = JBColor(Color(30, 60, 30), Color(30, 60, 30))
            foreground = ADD_COLOR
            border = JBUI.Borders.empty(5)
        }
        diffPanel.add(JBScrollPane(newArea).apply {
            preferredSize = Dimension(530, 100)
            maximumSize = Dimension(Int.MAX_VALUE, 100)
        })

        panel.add(JBScrollPane(diffPanel).apply {
            preferredSize = Dimension(550, 280)
        }, BorderLayout.CENTER)

        // Replace all indicator
        if (replaceAll) {
            val infoLabel = JLabel("<html><i>⚠️ Replace ALL occurrences</i></html>").apply {
                foreground = JBColor(Color(200, 150, 0), Color(255, 200, 50))
                border = JBUI.Borders.empty(5)
            }
            panel.add(infoLabel, BorderLayout.SOUTH)
        }

        return panel
    }

    @Suppress("UNCHECKED_CAST")
    private fun createMultiEditPreview(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createTitledBorder("✏️ Multiple Edits (${edits?.size ?: 0})")

        val editsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = CODE_BG
            border = JBUI.Borders.empty(10)
        }

        edits?.forEachIndexed { index, edit ->
            val oldStr = edit["old_string"] as? String ?: ""
            val newStr = edit["new_string"] as? String ?: ""
            val all = edit["replace_all"] as? Boolean ?: false

            // Edit header
            val headerLabel = JLabel("Edit ${index + 1}${if (all) " (all occurrences)" else ""}").apply {
                foreground = JBColor(Color(150, 150, 200), Color(150, 150, 200))
                font = font.deriveFont(Font.BOLD, 11f)
                border = JBUI.Borders.empty(5, 0)
            }
            editsPanel.add(headerLabel)

            // Compact diff view
            val diffText = buildString {
                append("- ${oldStr.take(100)}${if (oldStr.length > 100) "..." else ""}\n")
                append("+ ${newStr.take(100)}${if (newStr.length > 100) "..." else ""}")
            }
            val diffArea = JBTextArea(diffText).apply {
                isEditable = false
                font = Font("JetBrains Mono", Font.PLAIN, 11).let { f ->
                    if (f.family == "JetBrains Mono") f else Font(Font.MONOSPACED, Font.PLAIN, 11)
                }
                background = JBColor(Color(50, 50, 55), Color(35, 35, 40))
                foreground = CODE_FG
                border = JBUI.Borders.empty(5)
                rows = 2
            }
            editsPanel.add(diffArea)

            if (index < (edits.size - 1)) {
                editsPanel.add(Box.createVerticalStrut(10))
            }
        }

        panel.add(JBScrollPane(editsPanel).apply {
            preferredSize = Dimension(550, 280)
        }, BorderLayout.CENTER)

        return panel
    }

    override fun createActions(): Array<Action> {
        return arrayOf(
            object : DialogWrapperAction("✅ Apply") {
                override fun doAction(e: java.awt.event.ActionEvent?) {
                    onApprove()
                    close(OK_EXIT_CODE)
                }
            },
            object : DialogWrapperAction("❌ Reject") {
                override fun doAction(e: java.awt.event.ActionEvent?) {
                    onReject(feedbackArea.text)
                    close(CANCEL_EXIT_CODE)
                }
            },
            cancelAction
        )
    }
}

/**
 * Jupyter Cell Dialog for viewing and approving Jupyter cell execution
 * In PyCharm context, this converts Jupyter cells to Python file operations
 */
class JupyterCellDialog(
    private val project: Project,
    private val code: String,
    private val cellType: String,
    private val onApprove: () -> Unit,
    private val onReject: (String) -> Unit
) : DialogWrapper(project, true) {

    companion object {
        private val CODE_BG = JBColor(Color(40, 44, 52), Color(30, 30, 30))
        private val CODE_FG = JBColor(Color(200, 200, 200), Color(200, 200, 200))
    }

    private val feedbackArea = JBTextArea(2, 40).apply {
        lineWrap = true
        wrapStyleWord = true
    }

    init {
        title = "Execute Jupyter Cell"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(10)))
        panel.preferredSize = Dimension(550, 400)

        // Header with cell type info
        val headerPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(5)
            val typeIcon = if (cellType == "markdown") "📝" else "💻"
            val typeLabel = JLabel("<html><b>$typeIcon Cell Type: ${cellType.replaceFirstChar { it.uppercase() }}</b></html>")
            add(typeLabel, BorderLayout.WEST)

            val infoLabel = JLabel("<html><i>Code will be appended to a.py</i></html>").apply {
                foreground = JBColor.gray
            }
            add(infoLabel, BorderLayout.EAST)
        }
        panel.add(headerPanel, BorderLayout.NORTH)

        // Code preview
        val codePanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Code Preview")

            val displayCode = if (cellType == "markdown") {
                // Show how markdown will be converted
                "# Markdown content will be converted to Python comments:\n" +
                code.lines().joinToString("\n") { "# $it" }
            } else {
                code
            }

            val codeArea = JBTextArea(displayCode).apply {
                isEditable = false
                font = Font("JetBrains Mono", Font.PLAIN, 12).let { f ->
                    if (f.family == "JetBrains Mono") f else Font(Font.MONOSPACED, Font.PLAIN, 12)
                }
                background = CODE_BG
                foreground = CODE_FG
                border = JBUI.Borders.empty(10)
            }

            add(JBScrollPane(codeArea).apply {
                preferredSize = Dimension(500, 250)
            }, BorderLayout.CENTER)
        }
        panel.add(codePanel, BorderLayout.CENTER)

        // Feedback area
        val feedbackPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Feedback (optional)")
            add(JBScrollPane(feedbackArea).apply {
                preferredSize = Dimension(500, 60)
            }, BorderLayout.CENTER)
        }
        panel.add(feedbackPanel, BorderLayout.SOUTH)

        return panel
    }

    override fun createActions(): Array<Action> {
        return arrayOf(
            object : DialogWrapperAction("▶️ Execute") {
                override fun doAction(e: java.awt.event.ActionEvent?) {
                    onApprove()
                    close(OK_EXIT_CODE)
                }
            },
            object : DialogWrapperAction("❌ Reject") {
                override fun doAction(e: java.awt.event.ActionEvent?) {
                    onReject(feedbackArea.text)
                    close(CANCEL_EXIT_CODE)
                }
            },
            cancelAction
        )
    }
}

