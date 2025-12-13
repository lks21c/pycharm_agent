package com.hdsp.pycharm_agent.settings

import com.google.gson.Gson
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.swing.*

/**
 * Settings UI for PyCharm Agent plugin
 */
class AgentSettingsConfigurable : Configurable {

    private var mainPanel: JPanel? = null
    private var backendUrlField: JBTextField? = null
    private var providerCombo: JComboBox<String>? = null
    private var geminiApiKeyField: JBPasswordField? = null
    private var geminiModelField: JBTextField? = null
    private var openaiApiKeyField: JBPasswordField? = null
    private var openaiModelField: JBTextField? = null
    private var vllmEndpointField: JBTextField? = null
    private var vllmModelField: JBTextField? = null
    private var testConnectionButton: JButton? = null
    private var syncToBackendButton: JButton? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    override fun getDisplayName(): String = "PyCharm Agent"

    override fun createComponent(): JComponent {
        backendUrlField = JBTextField()
        providerCombo = JComboBox(arrayOf("gemini", "openai", "vllm"))
        geminiApiKeyField = JBPasswordField()
        geminiModelField = JBTextField()
        openaiApiKeyField = JBPasswordField()
        openaiModelField = JBTextField()
        vllmEndpointField = JBTextField()
        vllmModelField = JBTextField()

        testConnectionButton = JButton("Test Connection").apply {
            addActionListener { testConnection() }
        }

        syncToBackendButton = JButton("Sync to Backend").apply {
            addActionListener { syncToBackend() }
        }

        val buttonPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(testConnectionButton)
            add(Box.createHorizontalStrut(10))
            add(syncToBackendButton)
        }

        mainPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Backend URL:"), backendUrlField!!, 1, false)
            .addComponent(buttonPanel)
            .addSeparator()
            .addLabeledComponent(JBLabel("LLM Provider:"), providerCombo!!, 1, false)
            .addSeparator()
            .addLabeledComponent(JBLabel("Gemini API Key:"), geminiApiKeyField!!, 1, false)
            .addLabeledComponent(JBLabel("Gemini Model:"), geminiModelField!!, 1, false)
            .addSeparator()
            .addLabeledComponent(JBLabel("OpenAI API Key:"), openaiApiKeyField!!, 1, false)
            .addLabeledComponent(JBLabel("OpenAI Model:"), openaiModelField!!, 1, false)
            .addSeparator()
            .addLabeledComponent(JBLabel("vLLM Endpoint:"), vllmEndpointField!!, 1, false)
            .addLabeledComponent(JBLabel("vLLM Model:"), vllmModelField!!, 1, false)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        return mainPanel!!
    }

    private fun testConnection() {
        val url = backendUrlField?.text ?: return
        try {
            val request = Request.Builder()
                .url("$url/health")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                Messages.showInfoMessage("Connection successful!", "PyCharm Agent")
            } else {
                Messages.showErrorDialog("Connection failed: ${response.code}", "PyCharm Agent")
            }
        } catch (e: Exception) {
            Messages.showErrorDialog("Connection failed: ${e.message}", "PyCharm Agent")
        }
    }

    private fun syncToBackend() {
        val url = backendUrlField?.text ?: return

        try {
            val config = mapOf(
                "provider" to (providerCombo?.selectedItem as? String ?: "gemini"),
                "gemini" to mapOf(
                    "apiKey" to String(geminiApiKeyField?.password ?: charArrayOf()),
                    "model" to (geminiModelField?.text ?: "gemini-2.5-flash")
                ),
                "openai" to mapOf(
                    "apiKey" to String(openaiApiKeyField?.password ?: charArrayOf()),
                    "model" to (openaiModelField?.text ?: "gpt-4")
                ),
                "vllm" to mapOf(
                    "endpoint" to (vllmEndpointField?.text ?: "http://localhost:8000"),
                    "model" to (vllmModelField?.text ?: ""),
                    "apiKey" to ""
                )
            )

            val requestBody = gson.toJson(config)
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$url/api/config")
                .put(requestBody)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                Messages.showInfoMessage("Settings synced to backend!", "PyCharm Agent")
            } else {
                Messages.showErrorDialog("Sync failed: ${response.code} - ${response.body?.string()}", "PyCharm Agent")
            }
        } catch (e: Exception) {
            Messages.showErrorDialog("Sync failed: ${e.message}", "PyCharm Agent")
        }
    }

    override fun isModified(): Boolean {
        val settings = AgentSettings.getInstance()
        return backendUrlField?.text != settings.backendUrl ||
                providerCombo?.selectedItem != settings.provider ||
                String(geminiApiKeyField?.password ?: charArrayOf()) != settings.geminiApiKey ||
                geminiModelField?.text != settings.geminiModel ||
                String(openaiApiKeyField?.password ?: charArrayOf()) != settings.openaiApiKey ||
                openaiModelField?.text != settings.openaiModel ||
                vllmEndpointField?.text != settings.vllmEndpoint ||
                vllmModelField?.text != settings.vllmModel
    }

    override fun apply() {
        val settings = AgentSettings.getInstance()
        settings.backendUrl = backendUrlField?.text ?: "http://localhost:8765"
        settings.provider = providerCombo?.selectedItem as? String ?: "gemini"
        settings.geminiApiKey = String(geminiApiKeyField?.password ?: charArrayOf())
        settings.geminiModel = geminiModelField?.text ?: "gemini-2.5-flash"
        settings.openaiApiKey = String(openaiApiKeyField?.password ?: charArrayOf())
        settings.openaiModel = openaiModelField?.text ?: "gpt-4"
        settings.vllmEndpoint = vllmEndpointField?.text ?: "http://localhost:8000"
        settings.vllmModel = vllmModelField?.text ?: ""

        // Auto-sync to backend on apply
        syncToBackend()
    }

    override fun reset() {
        val settings = AgentSettings.getInstance()
        backendUrlField?.text = settings.backendUrl
        providerCombo?.selectedItem = settings.provider
        geminiApiKeyField?.text = settings.geminiApiKey
        geminiModelField?.text = settings.geminiModel
        openaiApiKeyField?.text = settings.openaiApiKey
        openaiModelField?.text = settings.openaiModel
        vllmEndpointField?.text = settings.vllmEndpoint
        vllmModelField?.text = settings.vllmModel
    }

    override fun disposeUIResources() {
        mainPanel = null
        backendUrlField = null
        providerCombo = null
        geminiApiKeyField = null
        geminiModelField = null
        openaiApiKeyField = null
        openaiModelField = null
        vllmEndpointField = null
        vllmModelField = null
        testConnectionButton = null
        syncToBackendButton = null
    }
}
