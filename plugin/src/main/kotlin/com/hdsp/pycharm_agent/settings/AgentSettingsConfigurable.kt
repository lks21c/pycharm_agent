package com.hdsp.pycharm_agent.settings

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.util.concurrent.TimeUnit
import javax.swing.*
import javax.swing.table.DefaultTableModel

/**
 * Settings UI for PyCharm Agent plugin
 * Dynamic provider-specific settings with multi-key Gemini support
 */
class AgentSettingsConfigurable : Configurable {

    private var mainPanel: JPanel? = null

    // Backend settings
    private var backendUrlField: JBTextField? = null
    private var testBackendButton: JButton? = null

    // Provider selection
    private var providerCombo: JComboBox<String>? = null
    private var providerCardsPanel: JPanel? = null
    private var providerCardLayout: CardLayout? = null

    // Gemini settings
    private var geminiModelCombo: JComboBox<String>? = null
    private var geminiKeysTableModel: DefaultTableModel? = null
    private var geminiKeysTable: JBTable? = null
    private var newGeminiKeyField: JBPasswordField? = null
    private var addGeminiKeyButton: JButton? = null
    private var removeGeminiKeyButton: JButton? = null
    private var testGeminiKeysButton: JButton? = null

    // OpenAI settings
    private var openaiApiKeyField: JBPasswordField? = null
    private var openaiModelCombo: JComboBox<String>? = null
    private var testOpenaiButton: JButton? = null

    // vLLM settings
    private var vllmEndpointField: JBTextField? = null
    private var vllmModelField: JBTextField? = null
    private var vllmApiKeyField: JBPasswordField? = null
    private var testVllmButton: JButton? = null

    // Agent behavior
    private var autoExecuteCheckbox: JCheckBox? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    override fun getDisplayName(): String = "PyCharm Agent"

    override fun createComponent(): JComponent {
        mainPanel = JPanel(BorderLayout(0, JBUI.scale(10)))
        mainPanel!!.border = JBUI.Borders.empty(10)

        // Backend URL section
        val backendPanel = createBackendPanel()

        // Provider selection section
        val providerPanel = createProviderSelectionPanel()

        // Provider-specific settings (CardLayout)
        providerCardsPanel = JPanel(CardLayout().also { providerCardLayout = it })
        providerCardsPanel!!.add(createGeminiPanel(), "gemini")
        providerCardsPanel!!.add(createOpenAIPanel(), "openai")
        providerCardsPanel!!.add(createVLLMPanel(), "vllm")

        // Main layout
        val topPanel = JPanel()
        topPanel.layout = BoxLayout(topPanel, BoxLayout.Y_AXIS)
        topPanel.add(backendPanel)
        topPanel.add(Box.createVerticalStrut(JBUI.scale(10)))
        topPanel.add(providerPanel)

        mainPanel!!.add(topPanel, BorderLayout.NORTH)
        mainPanel!!.add(providerCardsPanel!!, BorderLayout.CENTER)

        // Set initial provider card
        providerCombo?.addActionListener {
            val selected = providerCombo?.selectedItem as? String ?: "gemini"
            providerCardLayout?.show(providerCardsPanel, selected)
        }

        return mainPanel!!
    }

    private fun createBackendPanel(): JPanel {
        val panel = JPanel(BorderLayout(JBUI.scale(10), 0))
        panel.border = BorderFactory.createTitledBorder("Backend Server (HDSP Agent)")

        backendUrlField = JBTextField()
        backendUrlField!!.preferredSize = Dimension(300, backendUrlField!!.preferredSize.height)

        testBackendButton = JButton("Test Connection").apply {
            addActionListener { testBackendConnection() }
        }

        val inputPanel = JPanel(BorderLayout(JBUI.scale(5), 0))
        inputPanel.add(JBLabel("Backend URL:"), BorderLayout.WEST)
        inputPanel.add(backendUrlField!!, BorderLayout.CENTER)
        inputPanel.add(testBackendButton!!, BorderLayout.EAST)

        // Agent behavior options
        autoExecuteCheckbox = JCheckBox("Auto-execute mode (execute all steps automatically)")

        val optionsPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(autoExecuteCheckbox)
        }

        val mainPanel = JPanel()
        mainPanel.layout = BoxLayout(mainPanel, BoxLayout.Y_AXIS)
        mainPanel.add(inputPanel)
        mainPanel.add(Box.createVerticalStrut(JBUI.scale(5)))
        mainPanel.add(optionsPanel)

        panel.add(mainPanel, BorderLayout.CENTER)

        return panel
    }

    private fun createProviderSelectionPanel(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT))

        panel.add(JBLabel("LLM Provider:"))
        providerCombo = JComboBox(arrayOf("gemini", "openai", "vllm"))
        panel.add(providerCombo!!)

        return panel
    }

    private fun createGeminiPanel(): JPanel {
        val panel = JPanel(BorderLayout(0, JBUI.scale(10)))
        panel.border = BorderFactory.createTitledBorder("Gemini Settings")

        // Model selection
        val modelPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        modelPanel.add(JBLabel("Model:"))
        geminiModelCombo = JComboBox(arrayOf("gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.0-flash"))
        modelPanel.add(geminiModelCombo!!)

        // API Keys table
        geminiKeysTableModel = object : DefaultTableModel(arrayOf("No.", "API Key (masked)", "Status"), 0) {
            override fun isCellEditable(row: Int, column: Int) = false
        }
        geminiKeysTable = JBTable(geminiKeysTableModel!!)
        geminiKeysTable!!.columnModel.getColumn(0).preferredWidth = 40
        geminiKeysTable!!.columnModel.getColumn(1).preferredWidth = 200
        geminiKeysTable!!.columnModel.getColumn(2).preferredWidth = 100

        val tableScrollPane = JBScrollPane(geminiKeysTable)
        tableScrollPane.preferredSize = Dimension(400, 150)

        // Add key controls
        val addKeyPanel = JPanel(BorderLayout(JBUI.scale(5), 0))
        newGeminiKeyField = JBPasswordField()
        newGeminiKeyField!!.emptyText.text = "Enter new API key (AIza...)"

        addGeminiKeyButton = JButton("Add").apply {
            addActionListener { addGeminiKey() }
        }
        removeGeminiKeyButton = JButton("Remove").apply {
            addActionListener { removeGeminiKey() }
        }
        testGeminiKeysButton = JButton("Test All Keys").apply {
            addActionListener { testGeminiKeys() }
        }

        addKeyPanel.add(newGeminiKeyField!!, BorderLayout.CENTER)

        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        buttonPanel.add(addGeminiKeyButton!!)
        buttonPanel.add(removeGeminiKeyButton!!)
        buttonPanel.add(testGeminiKeysButton!!)

        val keysPanel = JPanel(BorderLayout(0, JBUI.scale(5)))
        keysPanel.add(JBLabel("API Keys (max ${AgentSettings.MAX_GEMINI_KEYS}):"), BorderLayout.NORTH)
        keysPanel.add(tableScrollPane, BorderLayout.CENTER)
        keysPanel.add(addKeyPanel, BorderLayout.SOUTH)

        // Info label
        val infoLabel = JBLabel("<html><small>Rate limit hit 시 자동으로 다음 키로 전환됩니다.</small></html>")

        panel.add(modelPanel, BorderLayout.NORTH)
        panel.add(keysPanel, BorderLayout.CENTER)

        val bottomPanel = JPanel(BorderLayout())
        bottomPanel.add(buttonPanel, BorderLayout.NORTH)
        bottomPanel.add(infoLabel, BorderLayout.SOUTH)
        panel.add(bottomPanel, BorderLayout.SOUTH)

        return panel
    }

    private fun createOpenAIPanel(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = BorderFactory.createTitledBorder("OpenAI Settings")

        // API Key
        val keyPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        keyPanel.add(JBLabel("API Key:"))
        openaiApiKeyField = JBPasswordField()
        openaiApiKeyField!!.preferredSize = Dimension(300, openaiApiKeyField!!.preferredSize.height)
        openaiApiKeyField!!.emptyText.text = "sk-..."
        keyPanel.add(openaiApiKeyField!!)

        // Model selection
        val modelPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        modelPanel.add(JBLabel("Model:"))
        openaiModelCombo = JComboBox(arrayOf("gpt-4", "gpt-4-turbo", "gpt-4o", "gpt-3.5-turbo"))
        modelPanel.add(openaiModelCombo!!)

        // Test button
        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        testOpenaiButton = JButton("Test API Key").apply {
            addActionListener { testOpenAIKey() }
        }
        buttonPanel.add(testOpenaiButton!!)

        panel.add(keyPanel)
        panel.add(modelPanel)
        panel.add(buttonPanel)
        panel.add(Box.createVerticalGlue())

        return panel
    }

    private fun createVLLMPanel(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = BorderFactory.createTitledBorder("vLLM Settings")

        // Endpoint
        val endpointPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        endpointPanel.add(JBLabel("Server Endpoint:"))
        vllmEndpointField = JBTextField()
        vllmEndpointField!!.preferredSize = Dimension(300, vllmEndpointField!!.preferredSize.height)
        vllmEndpointField!!.emptyText.text = "http://localhost:8000"
        endpointPanel.add(vllmEndpointField!!)

        // Model
        val modelPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        modelPanel.add(JBLabel("Model Name:"))
        vllmModelField = JBTextField()
        vllmModelField!!.preferredSize = Dimension(300, vllmModelField!!.preferredSize.height)
        vllmModelField!!.emptyText.text = "meta-llama/Llama-2-7b-chat-hf"
        modelPanel.add(vllmModelField!!)

        // API Key (optional)
        val keyPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        keyPanel.add(JBLabel("API Key (optional):"))
        vllmApiKeyField = JBPasswordField()
        vllmApiKeyField!!.preferredSize = Dimension(300, vllmApiKeyField!!.preferredSize.height)
        keyPanel.add(vllmApiKeyField!!)

        // Test button
        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        testVllmButton = JButton("Test Connection").apply {
            addActionListener { testVLLMConnection() }
        }
        buttonPanel.add(testVllmButton!!)

        panel.add(endpointPanel)
        panel.add(modelPanel)
        panel.add(keyPanel)
        panel.add(buttonPanel)
        panel.add(Box.createVerticalGlue())

        return panel
    }

    // ========== Button Actions ==========

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

    private fun addGeminiKey() {
        val newKey = String(newGeminiKeyField?.password ?: charArrayOf()).trim()
        if (newKey.isEmpty()) {
            Messages.showWarningDialog("Please enter an API key", "PyCharm Agent")
            return
        }

        if (!newKey.startsWith("AIza")) {
            Messages.showWarningDialog("Invalid Gemini API key format (should start with 'AIza')", "PyCharm Agent")
            return
        }

        val rowCount = geminiKeysTableModel?.rowCount ?: 0
        if (rowCount >= AgentSettings.MAX_GEMINI_KEYS) {
            Messages.showWarningDialog("Maximum ${AgentSettings.MAX_GEMINI_KEYS} keys allowed", "PyCharm Agent")
            return
        }

        // Check for duplicate
        for (i in 0 until rowCount) {
            val existingMasked = geminiKeysTableModel?.getValueAt(i, 1) as? String ?: ""
            if (existingMasked.endsWith(newKey.takeLast(4))) {
                Messages.showWarningDialog("This key appears to already exist", "PyCharm Agent")
                return
            }
        }

        // Add to table
        val maskedKey = "****${newKey.takeLast(4)}"
        geminiKeysTableModel?.addRow(arrayOf(rowCount + 1, maskedKey, "Pending"))
        newGeminiKeyField?.text = ""

        // Store actual key in settings temporarily
        val settings = AgentSettings.getInstance()
        settings.geminiApiKeys.add(newKey)
    }

    private fun removeGeminiKey() {
        val selectedRow = geminiKeysTable?.selectedRow ?: -1
        if (selectedRow < 0) {
            Messages.showWarningDialog("Please select a key to remove", "PyCharm Agent")
            return
        }

        geminiKeysTableModel?.removeRow(selectedRow)

        // Update settings
        val settings = AgentSettings.getInstance()
        if (selectedRow < settings.geminiApiKeys.size) {
            settings.geminiApiKeys.removeAt(selectedRow)
        }

        // Update row numbers
        for (i in 0 until (geminiKeysTableModel?.rowCount ?: 0)) {
            geminiKeysTableModel?.setValueAt(i + 1, i, 0)
        }
    }

    private fun testGeminiKeys() {
        val settings = AgentSettings.getInstance()
        if (settings.geminiApiKeys.isEmpty()) {
            Messages.showWarningDialog("No API keys to test", "PyCharm Agent")
            return
        }

        val results = StringBuilder()
        var successCount = 0

        for ((index, key) in settings.geminiApiKeys.withIndex()) {
            try {
                val testUrl = "https://generativelanguage.googleapis.com/v1beta/models?key=$key"
                val request = Request.Builder().url(testUrl).get().build()
                val response = client.newCall(request).execute()

                val maskedKey = "****${key.takeLast(4)}"
                if (response.isSuccessful) {
                    results.append("$maskedKey: OK\n")
                    geminiKeysTableModel?.setValueAt("Active", index, 2)
                    successCount++
                } else {
                    results.append("$maskedKey: Failed (${response.code})\n")
                    geminiKeysTableModel?.setValueAt("Failed", index, 2)
                }
            } catch (e: Exception) {
                val maskedKey = "****${key.takeLast(4)}"
                results.append("$maskedKey: Error - ${e.message}\n")
                geminiKeysTableModel?.setValueAt("Error", index, 2)
            }
        }

        Messages.showInfoMessage(
            "Test Results: $successCount/${settings.geminiApiKeys.size} keys valid\n\n$results",
            "PyCharm Agent"
        )
    }

    private fun testOpenAIKey() {
        val apiKey = String(openaiApiKeyField?.password ?: charArrayOf())
        if (apiKey.isEmpty()) {
            Messages.showWarningDialog("Please enter an API key", "PyCharm Agent")
            return
        }

        try {
            val request = Request.Builder()
                .url("https://api.openai.com/v1/models")
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                Messages.showInfoMessage("OpenAI API key is valid!", "PyCharm Agent")
            } else {
                Messages.showErrorDialog("OpenAI API key validation failed: ${response.code}", "PyCharm Agent")
            }
        } catch (e: Exception) {
            Messages.showErrorDialog("OpenAI API test failed: ${e.message}", "PyCharm Agent")
        }
    }

    private fun testVLLMConnection() {
        val endpoint = vllmEndpointField?.text ?: return
        try {
            val request = Request.Builder()
                .url("$endpoint/v1/models")
                .apply {
                    val apiKey = String(vllmApiKeyField?.password ?: charArrayOf())
                    if (apiKey.isNotEmpty()) {
                        addHeader("Authorization", "Bearer $apiKey")
                    }
                }
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                Messages.showInfoMessage("vLLM connection successful!", "PyCharm Agent")
            } else {
                Messages.showErrorDialog("vLLM connection failed: ${response.code}", "PyCharm Agent")
            }
        } catch (e: Exception) {
            Messages.showErrorDialog("vLLM connection failed: ${e.message}", "PyCharm Agent")
        }
    }

    private fun syncToBackend() {
        val url = backendUrlField?.text ?: return
        val settings = AgentSettings.getInstance()

        try {
            val config = mapOf(
                "provider" to (providerCombo?.selectedItem as? String ?: "gemini"),
                "gemini" to mapOf(
                    "keys" to settings.geminiApiKeys.map { key ->
                        mapOf(
                            "key" to key,
                            "id" to "key_${key.takeLast(8)}",
                            "enabled" to true
                        )
                    },
                    "model" to (geminiModelCombo?.selectedItem as? String ?: "gemini-2.5-flash")
                ),
                "openai" to mapOf(
                    "apiKey" to String(openaiApiKeyField?.password ?: charArrayOf()),
                    "model" to (openaiModelCombo?.selectedItem as? String ?: "gpt-4")
                ),
                "vllm" to mapOf(
                    "endpoint" to (vllmEndpointField?.text ?: "http://localhost:8000"),
                    "model" to (vllmModelField?.text ?: ""),
                    "apiKey" to String(vllmApiKeyField?.password ?: charArrayOf())
                )
            )

            val requestBody = gson.toJson(config)
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$url/config")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                println("Sync to backend failed: ${response.code}")
            }
        } catch (e: Exception) {
            println("Sync to backend failed: ${e.message}")
        }
    }

    // ========== Configurable Implementation ==========

    override fun isModified(): Boolean {
        val settings = AgentSettings.getInstance()
        return backendUrlField?.text != settings.backendUrl ||
                providerCombo?.selectedItem != settings.provider ||
                geminiModelCombo?.selectedItem != settings.geminiModel ||
                String(openaiApiKeyField?.password ?: charArrayOf()) != settings.openaiApiKey ||
                openaiModelCombo?.selectedItem != settings.openaiModel ||
                vllmEndpointField?.text != settings.vllmEndpoint ||
                vllmModelField?.text != settings.vllmModel ||
                String(vllmApiKeyField?.password ?: charArrayOf()) != settings.vllmApiKey ||
                autoExecuteCheckbox?.isSelected != settings.autoExecuteMode
    }

    override fun apply() {
        val settings = AgentSettings.getInstance()
        settings.backendUrl = backendUrlField?.text ?: "http://localhost:8888"
        settings.provider = providerCombo?.selectedItem as? String ?: "gemini"
        settings.geminiModel = geminiModelCombo?.selectedItem as? String ?: "gemini-2.5-flash"
        settings.openaiApiKey = String(openaiApiKeyField?.password ?: charArrayOf())
        settings.openaiModel = openaiModelCombo?.selectedItem as? String ?: "gpt-4"
        settings.vllmEndpoint = vllmEndpointField?.text ?: "http://localhost:8000"
        settings.vllmModel = vllmModelField?.text ?: ""
        settings.vllmApiKey = String(vllmApiKeyField?.password ?: charArrayOf())
        settings.autoExecuteMode = autoExecuteCheckbox?.isSelected ?: false

        // Sync to backend
        syncToBackend()
    }

    override fun reset() {
        val settings = AgentSettings.getInstance()

        backendUrlField?.text = settings.backendUrl
        providerCombo?.selectedItem = settings.provider
        providerCardLayout?.show(providerCardsPanel, settings.provider)
        autoExecuteCheckbox?.isSelected = settings.autoExecuteMode

        // Gemini
        geminiModelCombo?.selectedItem = settings.geminiModel
        geminiKeysTableModel?.rowCount = 0
        for ((index, key) in settings.geminiApiKeys.withIndex()) {
            val maskedKey = "****${key.takeLast(4)}"
            geminiKeysTableModel?.addRow(arrayOf(index + 1, maskedKey, "Active"))
        }

        // OpenAI
        openaiApiKeyField?.text = settings.openaiApiKey
        openaiModelCombo?.selectedItem = settings.openaiModel

        // vLLM
        vllmEndpointField?.text = settings.vllmEndpoint
        vllmModelField?.text = settings.vllmModel
        vllmApiKeyField?.text = settings.vllmApiKey
    }

    override fun disposeUIResources() {
        mainPanel = null
        backendUrlField = null
        testBackendButton = null
        providerCombo = null
        providerCardsPanel = null
        providerCardLayout = null
        geminiModelCombo = null
        geminiKeysTableModel = null
        geminiKeysTable = null
        newGeminiKeyField = null
        addGeminiKeyButton = null
        removeGeminiKeyButton = null
        testGeminiKeysButton = null
        openaiApiKeyField = null
        openaiModelCombo = null
        testOpenaiButton = null
        vllmEndpointField = null
        vllmModelField = null
        vllmApiKeyField = null
        testVllmButton = null
        autoExecuteCheckbox = null
    }
}
