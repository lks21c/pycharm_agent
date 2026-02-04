package com.hdsp.pycharm_agent.settings

import com.google.gson.Gson
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import okhttp3.OkHttpClient
import okhttp3.Request
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.util.concurrent.TimeUnit
import javax.swing.*

/**
 * Settings UI for PyCharm Agent plugin
 *
 * Supports two modes:
 * 1. ACP Mode (recommended): External ACP-compatible CLI agent
 * 2. Legacy Mode (deprecated): Python FastAPI backend
 */
class AgentSettingsConfigurable : Configurable {

    private var mainPanel: JPanel? = null

    // Mode selection
    private var acpModeRadio: JRadioButton? = null
    private var legacyModeRadio: JRadioButton? = null
    private var modeCardsPanel: JPanel? = null
    private var modeCardLayout: CardLayout? = null

    // ACP Agent settings
    private var acpAgentPathField: TextFieldWithBrowseButton? = null
    private var acpAgentArgsField: JBTextField? = null
    private var acpAutoStartCheckbox: JCheckBox? = null
    private var acpAutoRestartCheckbox: JCheckBox? = null
    private var acpHealthCheckIntervalField: JBTextField? = null
    private var testAcpAgentButton: JButton? = null

    // OpenRouter settings
    private var openrouterApiKeyField: JBPasswordField? = null
    private var openrouterModelCombo: JComboBox<String>? = null
    private var openrouterBaseUrlField: JBTextField? = null
    private var useOpenRouterDirectCheckbox: JCheckBox? = null
    private var openrouterSystemPromptField: JBTextArea? = null
    private var testOpenrouterButton: JButton? = null

    // Direct Provider Fallback
    private var enableDirectProvidersCheckbox: JCheckBox? = null
    private var geminiApiKeyField: JBPasswordField? = null
    private var geminiModelCombo: JComboBox<String>? = null
    private var openaiApiKeyField: JBPasswordField? = null
    private var openaiModelCombo: JComboBox<String>? = null
    private var directProvidersPanel: JPanel? = null

    // HITL settings
    private var autoApproveReadCheckbox: JCheckBox? = null
    private var autoApproveWriteCheckbox: JCheckBox? = null
    private var autoApproveShellCheckbox: JCheckBox? = null
    private var showDiffPreviewCheckbox: JCheckBox? = null
    private var diffPreviewTimeoutField: JBTextField? = null

    // Advanced settings
    private var defaultModeCombo: JComboBox<String>? = null
    private var workspaceRootField: JBTextField? = null
    private var systemPromptArea: JBTextArea? = null
    private var idleTimeoutField: JBTextField? = null

    // Legacy settings (deprecated)
    private var backendUrlField: JBTextField? = null
    private var testBackendButton: JButton? = null
    private var legacyAutoApproveCheckbox: JCheckBox? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    override fun getDisplayName(): String = "PyCharm Agent"

    override fun createComponent(): JComponent {
        mainPanel = JPanel(BorderLayout(0, JBUI.scale(10)))
        mainPanel!!.border = JBUI.Borders.empty(10)

        // Mode selection panel
        val modeSelectionPanel = createModeSelectionPanel()

        // Cards for ACP and Legacy modes
        modeCardsPanel = JPanel(CardLayout().also { modeCardLayout = it })
        modeCardsPanel!!.add(createAcpModePanel(), "acp")
        modeCardsPanel!!.add(createLegacyModePanel(), "legacy")

        // Scrollable content
        val contentPanel = JPanel(BorderLayout(0, JBUI.scale(10)))
        contentPanel.add(modeSelectionPanel, BorderLayout.NORTH)
        contentPanel.add(modeCardsPanel!!, BorderLayout.CENTER)

        val scrollPane = JBScrollPane(contentPanel)
        scrollPane.border = null

        mainPanel!!.add(scrollPane, BorderLayout.CENTER)

        // Add mode change listeners
        acpModeRadio?.addActionListener {
            modeCardLayout?.show(modeCardsPanel, "acp")
        }
        legacyModeRadio?.addActionListener {
            modeCardLayout?.show(modeCardsPanel, "legacy")
        }

        return mainPanel!!
    }

    private fun createModeSelectionPanel(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT))
        panel.border = BorderFactory.createTitledBorder("Agent Mode")

        acpModeRadio = JRadioButton("ACP Mode (Recommended)").apply {
            toolTipText = "Use external ACP-compatible CLI agent (Claude Code, Codex CLI, etc.)"
        }
        legacyModeRadio = JRadioButton("Legacy Mode (Deprecated)").apply {
            toolTipText = "Use Python FastAPI backend (requires separate server)"
        }

        val modeGroup = ButtonGroup()
        modeGroup.add(acpModeRadio)
        modeGroup.add(legacyModeRadio)

        panel.add(acpModeRadio)
        panel.add(Box.createHorizontalStrut(JBUI.scale(20)))
        panel.add(legacyModeRadio)

        return panel
    }

    private fun createAcpModePanel(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)

        // Essential Settings (simplified)
        panel.add(createEssentialSettingsPanel())
        panel.add(Box.createVerticalStrut(JBUI.scale(10)))

        // Advanced Settings (collapsible)
        panel.add(createAdvancedSettingsCollapsible())

        return panel
    }

    private fun createEssentialSettingsPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = BorderFactory.createTitledBorder("Essential Settings")
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(5)
        }

        // ACP Agent Path
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
        panel.add(JBLabel("Agent Path:"), gbc)

        acpAgentPathField = TextFieldWithBrowseButton().apply {
            addBrowseFolderListener(
                "Select ACP Agent",
                "Select the ACP-compatible agent executable (e.g., claude, codex)",
                null,
                FileChooserDescriptor(true, false, false, false, false, false)
            )
            textField.toolTipText = "Path to ACP agent: claude, codex, or custom agent"
        }
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(acpAgentPathField!!, gbc)

        // Test Agent Button
        gbc.gridx = 2; gbc.weightx = 0.0
        testAcpAgentButton = JButton("Test").apply {
            addActionListener { testAcpAgent() }
        }
        panel.add(testAcpAgentButton!!, gbc)

        // OpenRouter API Key
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0
        panel.add(JBLabel("OpenRouter API Key:"), gbc)

        openrouterApiKeyField = JBPasswordField().apply {
            emptyText.text = "sk-or-v1-..."
        }
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(openrouterApiKeyField!!, gbc)

        // Test API Button
        gbc.gridx = 2; gbc.weightx = 0.0
        testOpenrouterButton = JButton("Test").apply {
            addActionListener { testOpenRouterKey() }
        }
        panel.add(testOpenrouterButton!!, gbc)

        // Model Selection
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0
        panel.add(JBLabel("Model:"), gbc)

        openrouterModelCombo = JComboBox(AgentSettings.OPENROUTER_MODELS.map { it.first }.toTypedArray()).apply {
            isEditable = true
            toolTipText = "Select or enter OpenRouter model ID"
        }
        gbc.gridx = 1; gbc.gridwidth = 2; gbc.weightx = 1.0
        panel.add(openrouterModelCombo!!, gbc)

        // Use OpenRouter Direct checkbox
        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 3
        useOpenRouterDirectCheckbox = JCheckBox("Use OpenRouter directly (without ACP agent for chat)").apply {
            toolTipText = "Chat mode uses OpenRouter API directly; Agent mode still requires ACP agent"
        }
        panel.add(useOpenRouterDirectCheckbox!!, gbc)

        // Help link
        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 3
        val helpPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        helpPanel.add(JBLabel("<html><small><a href='https://openrouter.ai/keys'>Get OpenRouter API Key</a></small></html>"))
        panel.add(helpPanel, gbc)

        return panel
    }

    private var advancedPanel: JPanel? = null
    private var advancedToggleButton: JButton? = null

    private fun createAdvancedSettingsCollapsible(): JPanel {
        val outerPanel = JPanel(BorderLayout())

        // Toggle button
        advancedToggleButton = JButton("▶ Advanced Settings").apply {
            horizontalAlignment = SwingConstants.LEFT
            isBorderPainted = false
            isContentAreaFilled = false
            addActionListener {
                val isVisible = advancedPanel?.isVisible ?: false
                advancedPanel?.isVisible = !isVisible
                text = if (!isVisible) "▼ Advanced Settings" else "▶ Advanced Settings"
            }
        }
        outerPanel.add(advancedToggleButton!!, BorderLayout.NORTH)

        // Advanced settings panel (initially hidden)
        advancedPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isVisible = false
            border = JBUI.Borders.emptyLeft(20)
        }

        // ACP Agent Advanced
        advancedPanel!!.add(createAcpAgentAdvancedPanel())
        advancedPanel!!.add(Box.createVerticalStrut(JBUI.scale(10)))

        // OpenRouter Advanced
        advancedPanel!!.add(createOpenRouterAdvancedPanel())
        advancedPanel!!.add(Box.createVerticalStrut(JBUI.scale(10)))

        // Direct Provider Fallback
        advancedPanel!!.add(createDirectProvidersPanel())
        advancedPanel!!.add(Box.createVerticalStrut(JBUI.scale(10)))

        // HITL Configuration
        advancedPanel!!.add(createHitlPanel())
        advancedPanel!!.add(Box.createVerticalStrut(JBUI.scale(10)))

        // Other Advanced
        advancedPanel!!.add(createAdvancedPanel())

        outerPanel.add(advancedPanel!!, BorderLayout.CENTER)

        return outerPanel
    }

    private fun createAcpAgentAdvancedPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = BorderFactory.createTitledBorder("ACP Agent (Advanced)")
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(3)
        }

        // Agent Arguments
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
        panel.add(JBLabel("Arguments:"), gbc)

        acpAgentArgsField = JBTextField().apply {
            emptyText.text = "--acp (space-separated)"
        }
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(acpAgentArgsField!!, gbc)

        // Health Check Interval
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0
        panel.add(JBLabel("Health Check (sec):"), gbc)

        acpHealthCheckIntervalField = JBTextField(5).apply {
            text = "30"
            toolTipText = "0 = disabled"
        }
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(acpHealthCheckIntervalField!!, gbc)

        // Checkboxes
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2
        val checkboxPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        acpAutoStartCheckbox = JCheckBox("Auto-start").apply { isSelected = true }
        acpAutoRestartCheckbox = JCheckBox("Auto-restart on crash").apply { isSelected = true }
        checkboxPanel.add(acpAutoStartCheckbox)
        checkboxPanel.add(Box.createHorizontalStrut(JBUI.scale(15)))
        checkboxPanel.add(acpAutoRestartCheckbox)
        panel.add(checkboxPanel, gbc)

        return panel
    }

    private fun createOpenRouterAdvancedPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = BorderFactory.createTitledBorder("OpenRouter (Advanced)")
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(3)
        }

        // Base URL
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
        panel.add(JBLabel("Base URL:"), gbc)

        openrouterBaseUrlField = JBTextField().apply {
            text = "https://openrouter.ai/api/v1"
        }
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(openrouterBaseUrlField!!, gbc)

        // System Prompt
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0; gbc.anchor = GridBagConstraints.NORTHWEST
        panel.add(JBLabel("System Prompt:"), gbc)

        openrouterSystemPromptField = JBTextArea(2, 40).apply {
            lineWrap = true
            wrapStyleWord = true
            emptyText.text = "Optional custom instructions"
        }
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.BOTH
        panel.add(JBScrollPane(openrouterSystemPromptField), gbc)

        return panel
    }

    private fun createDirectProvidersPanel(): JPanel {
        val outerPanel = JPanel(BorderLayout())
        outerPanel.border = BorderFactory.createTitledBorder("Direct Provider Fallback (Optional)")

        // Enable checkbox
        enableDirectProvidersCheckbox = JCheckBox("Enable direct provider fallback when OpenRouter unavailable").apply {
            addActionListener {
                directProvidersPanel?.isVisible = isSelected
            }
        }
        outerPanel.add(enableDirectProvidersCheckbox!!, BorderLayout.NORTH)

        // Provider settings (initially hidden)
        directProvidersPanel = JPanel(GridBagLayout()).apply {
            isVisible = false
        }
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(3)
        }

        // Gemini
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
        directProvidersPanel!!.add(JBLabel("Gemini API Key:"), gbc)

        geminiApiKeyField = JBPasswordField().apply {
            emptyText.text = "AIza..."
        }
        gbc.gridx = 1; gbc.weightx = 1.0
        directProvidersPanel!!.add(geminiApiKeyField!!, gbc)

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0
        directProvidersPanel!!.add(JBLabel("Gemini Model:"), gbc)

        geminiModelCombo = JComboBox(arrayOf("gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.0-flash"))
        gbc.gridx = 1; gbc.weightx = 1.0
        directProvidersPanel!!.add(geminiModelCombo!!, gbc)

        // OpenAI
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0
        directProvidersPanel!!.add(JBLabel("OpenAI API Key:"), gbc)

        openaiApiKeyField = JBPasswordField().apply {
            emptyText.text = "sk-..."
        }
        gbc.gridx = 1; gbc.weightx = 1.0
        directProvidersPanel!!.add(openaiApiKeyField!!, gbc)

        gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0.0
        directProvidersPanel!!.add(JBLabel("OpenAI Model:"), gbc)

        openaiModelCombo = JComboBox(arrayOf("gpt-4", "gpt-4-turbo", "gpt-4o", "gpt-3.5-turbo"))
        gbc.gridx = 1; gbc.weightx = 1.0
        directProvidersPanel!!.add(openaiModelCombo!!, gbc)

        outerPanel.add(directProvidersPanel!!, BorderLayout.CENTER)

        return outerPanel
    }

    private fun createHitlPanel(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = BorderFactory.createTitledBorder("Human-in-the-Loop (HITL) Configuration")

        // Auto-approve checkboxes
        autoApproveReadCheckbox = JCheckBox("Auto-approve file read operations").apply {
            toolTipText = "Skip confirmation for reading files"
        }
        autoApproveWriteCheckbox = JCheckBox("Auto-approve file write operations").apply {
            toolTipText = "Skip confirmation for writing/editing files (CAUTION)"
        }
        autoApproveShellCheckbox = JCheckBox("Auto-approve shell commands").apply {
            toolTipText = "Skip confirmation for executing shell commands (CAUTION)"
        }
        showDiffPreviewCheckbox = JCheckBox("Show diff preview before file writes").apply {
            toolTipText = "Display inline diff highlighting before applying changes"
        }

        // Timeout
        val timeoutPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        timeoutPanel.add(JBLabel("Auto-accept timeout (seconds, 0=disabled): "))
        diffPreviewTimeoutField = JBTextField(5).apply {
            text = "0"
            toolTipText = "Automatically accept changes after N seconds"
        }
        timeoutPanel.add(diffPreviewTimeoutField)
        timeoutPanel.alignmentX = Component.LEFT_ALIGNMENT

        panel.add(autoApproveReadCheckbox)
        panel.add(autoApproveWriteCheckbox)
        panel.add(autoApproveShellCheckbox)
        panel.add(showDiffPreviewCheckbox)
        panel.add(Box.createVerticalStrut(JBUI.scale(5)))
        panel.add(timeoutPanel)

        return panel
    }

    private fun createAdvancedPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = BorderFactory.createTitledBorder("Advanced Settings")
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(3)
        }

        // Default Mode
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
        panel.add(JBLabel("Default Mode:"), gbc)

        defaultModeCombo = JComboBox(arrayOf("chat", "agent"))
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(defaultModeCombo!!, gbc)

        // Workspace Root
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0
        panel.add(JBLabel("Workspace Root:"), gbc)

        workspaceRootField = JBTextField().apply {
            emptyText.text = "(empty = use project root)"
        }
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(workspaceRootField!!, gbc)

        // Idle Timeout
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0
        panel.add(JBLabel("Idle Timeout (min):"), gbc)

        idleTimeoutField = JBTextField(5).apply {
            text = "60"
            toolTipText = "Stop agent after N minutes of inactivity (0=disabled)"
        }
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(idleTimeoutField!!, gbc)

        // System Prompt
        gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0.0; gbc.anchor = GridBagConstraints.NORTHWEST
        panel.add(JBLabel("System Prompt:"), gbc)

        systemPromptArea = JBTextArea(4, 40).apply {
            lineWrap = true
            wrapStyleWord = true
        }
        val scrollPane = JBScrollPane(systemPromptArea).apply {
            preferredSize = Dimension(400, 80)
        }
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH
        panel.add(scrollPane, gbc)

        return panel
    }

    private fun createLegacyModePanel(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)

        // Warning message
        val warningPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        warningPanel.add(JBLabel("<html><b style='color:orange'>⚠ Legacy mode is deprecated.</b> " +
                "Consider migrating to ACP mode for better performance and features.</html>"))
        panel.add(warningPanel)
        panel.add(Box.createVerticalStrut(JBUI.scale(10)))

        // Backend URL
        val backendPanel = JPanel(GridBagLayout())
        backendPanel.border = BorderFactory.createTitledBorder("Backend Server")
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(3)
        }

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
        backendPanel.add(JBLabel("Backend URL:"), gbc)

        backendUrlField = JBTextField().apply {
            emptyText.text = "http://localhost:8000"
        }
        gbc.gridx = 1; gbc.weightx = 1.0
        backendPanel.add(backendUrlField!!, gbc)

        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 2
        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        testBackendButton = JButton("Test Connection").apply {
            addActionListener { testBackendConnection() }
        }
        buttonPanel.add(testBackendButton)
        backendPanel.add(buttonPanel, gbc)

        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2
        legacyAutoApproveCheckbox = JCheckBox("Auto-approve tool execution (HITL bypass)")
        backendPanel.add(legacyAutoApproveCheckbox!!, gbc)

        panel.add(backendPanel)
        panel.add(Box.createVerticalStrut(JBUI.scale(10)))

        // Link to migration guide
        val migrationPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        migrationPanel.add(JBLabel("<html><a href='#'>View migration guide</a></html>"))
        panel.add(migrationPanel)

        return panel
    }

    // ========== Test Actions ==========

    private fun testAcpAgent() {
        val agentPath = acpAgentPathField?.text ?: ""
        if (agentPath.isBlank()) {
            Messages.showWarningDialog("Please enter an agent path", "PyCharm Agent")
            return
        }

        try {
            val file = java.io.File(agentPath)
            if (!file.exists()) {
                Messages.showErrorDialog("Agent not found: $agentPath", "PyCharm Agent")
                return
            }
            if (!file.canExecute()) {
                Messages.showErrorDialog("Agent is not executable: $agentPath", "PyCharm Agent")
                return
            }

            // Try to get version
            val process = ProcessBuilder(agentPath, "--version")
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                Messages.showInfoMessage("Agent found!\n\n$output", "PyCharm Agent")
            } else {
                Messages.showWarningDialog("Agent exists but --version failed.\nThis may still work if it's an ACP agent.", "PyCharm Agent")
            }
        } catch (e: Exception) {
            Messages.showErrorDialog("Error testing agent: ${e.message}", "PyCharm Agent")
        }
    }

    private fun testOpenRouterKey() {
        val apiKey = String(openrouterApiKeyField?.password ?: charArrayOf())
        if (apiKey.isBlank()) {
            Messages.showWarningDialog("Please enter an API key", "PyCharm Agent")
            return
        }

        try {
            val request = Request.Builder()
                .url("https://openrouter.ai/api/v1/models")
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                Messages.showInfoMessage("OpenRouter API key is valid!", "PyCharm Agent")
            } else {
                val body = response.body?.string() ?: ""
                Messages.showErrorDialog("API key validation failed: ${response.code}\n$body", "PyCharm Agent")
            }
        } catch (e: Exception) {
            Messages.showErrorDialog("Test failed: ${e.message}", "PyCharm Agent")
        }
    }

    private fun testBackendConnection() {
        val url = backendUrlField?.text ?: return
        try {
            val request = Request.Builder()
                .url("$url/health")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                Messages.showInfoMessage("Backend connection successful!", "PyCharm Agent")
            } else {
                Messages.showErrorDialog("Backend connection failed: ${response.code}", "PyCharm Agent")
            }
        } catch (e: Exception) {
            Messages.showErrorDialog("Backend connection failed: ${e.message}", "PyCharm Agent")
        }
    }

    // ========== Configurable Implementation ==========

    override fun isModified(): Boolean {
        val settings = AgentSettings.getInstance()
        return acpModeRadio?.isSelected != settings.useAcpMode ||
                acpAgentPathField?.text != settings.acpAgentPath ||
                acpAgentArgsField?.text != settings.acpAgentArgs.joinToString(" ") ||
                acpAutoStartCheckbox?.isSelected != settings.acpAutoStart ||
                acpAutoRestartCheckbox?.isSelected != settings.acpAutoRestart ||
                (acpHealthCheckIntervalField?.text?.toIntOrNull() ?: 30) != settings.acpHealthCheckInterval ||
                String(openrouterApiKeyField?.password ?: charArrayOf()) != settings.openrouterApiKey ||
                openrouterModelCombo?.selectedItem != settings.openrouterModel ||
                openrouterBaseUrlField?.text != settings.openrouterBaseUrl ||
                useOpenRouterDirectCheckbox?.isSelected != settings.useOpenRouterDirect ||
                openrouterSystemPromptField?.text != settings.openrouterSystemPrompt ||
                enableDirectProvidersCheckbox?.isSelected != settings.enableDirectProviders ||
                String(geminiApiKeyField?.password ?: charArrayOf()) != settings.geminiApiKey ||
                geminiModelCombo?.selectedItem != settings.geminiModel ||
                String(openaiApiKeyField?.password ?: charArrayOf()) != settings.openaiApiKey ||
                openaiModelCombo?.selectedItem != settings.openaiModel ||
                autoApproveReadCheckbox?.isSelected != settings.autoApproveRead ||
                autoApproveWriteCheckbox?.isSelected != settings.autoApproveWrite ||
                autoApproveShellCheckbox?.isSelected != settings.autoApproveShell ||
                showDiffPreviewCheckbox?.isSelected != settings.showDiffPreview ||
                (diffPreviewTimeoutField?.text?.toIntOrNull() ?: 0) != settings.diffPreviewTimeout ||
                defaultModeCombo?.selectedItem != settings.defaultMode ||
                workspaceRootField?.text != settings.workspaceRoot ||
                systemPromptArea?.text != settings.systemPrompt ||
                (idleTimeoutField?.text?.toIntOrNull() ?: 60) != settings.idleTimeoutMinutes ||
                backendUrlField?.text != settings.backendUrl ||
                legacyAutoApproveCheckbox?.isSelected != settings.autoApprove
    }

    override fun apply() {
        val settings = AgentSettings.getInstance()

        // Mode selection
        settings.useAcpMode = acpModeRadio?.isSelected ?: true

        // ACP settings
        settings.acpAgentPath = acpAgentPathField?.text ?: ""
        settings.acpAgentArgs = (acpAgentArgsField?.text ?: "")
            .split(" ")
            .filter { it.isNotBlank() }
            .toMutableList()
        settings.acpAutoStart = acpAutoStartCheckbox?.isSelected ?: true
        settings.acpAutoRestart = acpAutoRestartCheckbox?.isSelected ?: true
        settings.acpHealthCheckInterval = acpHealthCheckIntervalField?.text?.toIntOrNull() ?: 30

        // OpenRouter settings
        settings.openrouterApiKey = String(openrouterApiKeyField?.password ?: charArrayOf())
        settings.openrouterModel = openrouterModelCombo?.selectedItem as? String ?: "anthropic/claude-sonnet-4"
        settings.openrouterBaseUrl = openrouterBaseUrlField?.text ?: "https://openrouter.ai/api/v1"
        settings.useOpenRouterDirect = useOpenRouterDirectCheckbox?.isSelected ?: false
        settings.openrouterSystemPrompt = openrouterSystemPromptField?.text ?: ""

        // Direct provider settings
        settings.enableDirectProviders = enableDirectProvidersCheckbox?.isSelected ?: false
        settings.geminiApiKey = String(geminiApiKeyField?.password ?: charArrayOf())
        settings.geminiModel = geminiModelCombo?.selectedItem as? String ?: "gemini-2.5-flash"
        settings.openaiApiKey = String(openaiApiKeyField?.password ?: charArrayOf())
        settings.openaiModel = openaiModelCombo?.selectedItem as? String ?: "gpt-4"

        // HITL settings
        settings.autoApproveRead = autoApproveReadCheckbox?.isSelected ?: true
        settings.autoApproveWrite = autoApproveWriteCheckbox?.isSelected ?: false
        settings.autoApproveShell = autoApproveShellCheckbox?.isSelected ?: false
        settings.showDiffPreview = showDiffPreviewCheckbox?.isSelected ?: true
        settings.diffPreviewTimeout = diffPreviewTimeoutField?.text?.toIntOrNull() ?: 0

        // Advanced settings
        settings.defaultMode = defaultModeCombo?.selectedItem as? String ?: "chat"
        settings.workspaceRoot = workspaceRootField?.text ?: ""
        settings.systemPrompt = systemPromptArea?.text ?: ""
        settings.idleTimeoutMinutes = idleTimeoutField?.text?.toIntOrNull() ?: 60

        // Legacy settings
        settings.backendUrl = backendUrlField?.text ?: "http://localhost:8000"
        settings.autoApprove = legacyAutoApproveCheckbox?.isSelected ?: false

        // Notify components that settings have changed
        SettingsChangeNotifier.getInstance().notifySettingsChanged()
    }

    override fun reset() {
        val settings = AgentSettings.getInstance()

        // Mode selection
        if (settings.useAcpMode) {
            acpModeRadio?.isSelected = true
            modeCardLayout?.show(modeCardsPanel, "acp")
        } else {
            legacyModeRadio?.isSelected = true
            modeCardLayout?.show(modeCardsPanel, "legacy")
        }

        // ACP settings
        acpAgentPathField?.text = settings.acpAgentPath
        acpAgentArgsField?.text = settings.acpAgentArgs.joinToString(" ")
        acpAutoStartCheckbox?.isSelected = settings.acpAutoStart
        acpAutoRestartCheckbox?.isSelected = settings.acpAutoRestart
        acpHealthCheckIntervalField?.text = settings.acpHealthCheckInterval.toString()

        // OpenRouter settings
        openrouterApiKeyField?.text = settings.openrouterApiKey
        openrouterModelCombo?.selectedItem = settings.openrouterModel
        openrouterBaseUrlField?.text = settings.openrouterBaseUrl
        useOpenRouterDirectCheckbox?.isSelected = settings.useOpenRouterDirect
        openrouterSystemPromptField?.text = settings.openrouterSystemPrompt

        // Direct provider settings
        enableDirectProvidersCheckbox?.isSelected = settings.enableDirectProviders
        directProvidersPanel?.isVisible = settings.enableDirectProviders
        geminiApiKeyField?.text = settings.geminiApiKey
        geminiModelCombo?.selectedItem = settings.geminiModel
        openaiApiKeyField?.text = settings.openaiApiKey
        openaiModelCombo?.selectedItem = settings.openaiModel

        // HITL settings
        autoApproveReadCheckbox?.isSelected = settings.autoApproveRead
        autoApproveWriteCheckbox?.isSelected = settings.autoApproveWrite
        autoApproveShellCheckbox?.isSelected = settings.autoApproveShell
        showDiffPreviewCheckbox?.isSelected = settings.showDiffPreview
        diffPreviewTimeoutField?.text = settings.diffPreviewTimeout.toString()

        // Advanced settings
        defaultModeCombo?.selectedItem = settings.defaultMode
        workspaceRootField?.text = settings.workspaceRoot
        systemPromptArea?.text = settings.systemPrompt
        idleTimeoutField?.text = settings.idleTimeoutMinutes.toString()

        // Legacy settings
        backendUrlField?.text = settings.backendUrl
        legacyAutoApproveCheckbox?.isSelected = settings.autoApprove
    }

    override fun disposeUIResources() {
        mainPanel = null
        acpModeRadio = null
        legacyModeRadio = null
        modeCardsPanel = null
        modeCardLayout = null
        acpAgentPathField = null
        acpAgentArgsField = null
        acpAutoStartCheckbox = null
        acpAutoRestartCheckbox = null
        acpHealthCheckIntervalField = null
        testAcpAgentButton = null
        openrouterApiKeyField = null
        openrouterModelCombo = null
        openrouterBaseUrlField = null
        useOpenRouterDirectCheckbox = null
        openrouterSystemPromptField = null
        testOpenrouterButton = null
        enableDirectProvidersCheckbox = null
        geminiApiKeyField = null
        geminiModelCombo = null
        openaiApiKeyField = null
        openaiModelCombo = null
        directProvidersPanel = null
        autoApproveReadCheckbox = null
        autoApproveWriteCheckbox = null
        autoApproveShellCheckbox = null
        showDiffPreviewCheckbox = null
        diffPreviewTimeoutField = null
        defaultModeCombo = null
        workspaceRootField = null
        systemPromptArea = null
        idleTimeoutField = null
        backendUrlField = null
        testBackendButton = null
        legacyAutoApproveCheckbox = null
        advancedPanel = null
        advancedToggleButton = null
    }
}
