package com.hdsp.pycharm_agent.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.annotations.XCollection

/**
 * Persistent settings for PyCharm Agent plugin
 */
@State(
    name = "com.hdsp.pycharm_agent.settings.AgentSettings",
    storages = [Storage("PycharmAgentSettings.xml")]
)
class AgentSettings : PersistentStateComponent<AgentSettings.State> {

    data class State(
        var backendUrl: String = "http://localhost:8000",
        var provider: String = "gemini",
        // Gemini settings - multi-key support (up to 10)
        @XCollection(style = XCollection.Style.v2)
        var geminiApiKeys: MutableList<String> = mutableListOf(),
        var geminiModel: String = "gemini-2.5-flash",
        // OpenAI settings
        var openaiApiKey: String = "",
        var openaiModel: String = "gpt-4",
        // vLLM settings
        var vllmEndpoint: String = "http://localhost:8000",
        var vllmModel: String = "meta-llama/Llama-2-7b-chat-hf",
        var vllmApiKey: String = "",
        // Workspace & Context
        var workspaceRoot: String = "",  // Empty means use project root
        // Agent behavior
        var autoApprove: Boolean = false,  // Auto-approve tool execution
        var autoAcceptDiff: Boolean = false,
        var showDiffPreview: Boolean = true,
        var autoExecuteMode: Boolean = false,
        // LangChain System Prompt
        var systemPrompt: String = "",
        // Idle Timeout (minutes, 0=disabled)
        var idleTimeoutMinutes: Int = 60
    )

    private var state = State()

    // API Key Rotation State (runtime only, not persisted)
    @Transient
    private var currentKeyIndex: Int = 0

    @Transient
    private val rateLimitedKeys: MutableSet<Int> = mutableSetOf()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var backendUrl: String
        get() = state.backendUrl
        set(value) { state.backendUrl = value }

    var provider: String
        get() = state.provider
        set(value) { state.provider = value }

    // Gemini multi-key management
    var geminiApiKeys: MutableList<String>
        get() = state.geminiApiKeys
        set(value) { state.geminiApiKeys = value }

    var geminiModel: String
        get() = state.geminiModel
        set(value) { state.geminiModel = value }

    // OpenAI
    var openaiApiKey: String
        get() = state.openaiApiKey
        set(value) { state.openaiApiKey = value }

    var openaiModel: String
        get() = state.openaiModel
        set(value) { state.openaiModel = value }

    // vLLM
    var vllmEndpoint: String
        get() = state.vllmEndpoint
        set(value) { state.vllmEndpoint = value }

    var vllmModel: String
        get() = state.vllmModel
        set(value) { state.vllmModel = value }

    var vllmApiKey: String
        get() = state.vllmApiKey
        set(value) { state.vllmApiKey = value }

    // Behavior
    var autoAcceptDiff: Boolean
        get() = state.autoAcceptDiff
        set(value) { state.autoAcceptDiff = value }

    var showDiffPreview: Boolean
        get() = state.showDiffPreview
        set(value) { state.showDiffPreview = value }

    var autoExecuteMode: Boolean
        get() = state.autoExecuteMode
        set(value) { state.autoExecuteMode = value }

    // Workspace & Context
    var workspaceRoot: String
        get() = state.workspaceRoot
        set(value) { state.workspaceRoot = value }

    // Auto-approve tool execution
    var autoApprove: Boolean
        get() = state.autoApprove
        set(value) { state.autoApprove = value }

    // LangChain System Prompt
    var systemPrompt: String
        get() = state.systemPrompt
        set(value) { state.systemPrompt = value }

    // Idle Timeout
    var idleTimeoutMinutes: Int
        get() = state.idleTimeoutMinutes
        set(value) { state.idleTimeoutMinutes = value }

    // ═══════════════════════════════════════════════════════════════
    // API Key Rotation Methods
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get current active Gemini API key for rotation
     *
     * Rate Limit handling:
     * 1. Call markKeyAsRateLimited(currentIndex) when 429 occurs
     * 2. Call getNextValidKey() to get next available key
     * 3. Returns null when all keys are exhausted
     */
    fun getCurrentGeminiKey(): String? {
        val keys = geminiApiKeys.filter { it.isNotBlank() }
        if (keys.isEmpty()) return null
        if (currentKeyIndex >= keys.size) currentKeyIndex = 0
        return keys.getOrNull(currentKeyIndex)
    }

    /**
     * Mark a key as rate limited by its index
     */
    fun markKeyAsRateLimited(index: Int) {
        rateLimitedKeys.add(index)
    }

    /**
     * Get next non-rate-limited key
     * Returns null if all keys are rate limited
     */
    fun getNextValidKey(): String? {
        val keys = geminiApiKeys.filter { it.isNotBlank() }
        if (keys.isEmpty()) return null

        for (i in keys.indices) {
            val nextIndex = (currentKeyIndex + i + 1) % keys.size
            if (nextIndex !in rateLimitedKeys) {
                currentKeyIndex = nextIndex
                return keys[nextIndex]
            }
        }
        return null // All keys rate limited
    }

    /**
     * Reset rotation state after successful request
     */
    fun resetKeyRotation() {
        currentKeyIndex = 0
        rateLimitedKeys.clear()
    }

    /**
     * Get count of valid (non-blank) API keys
     */
    fun getValidKeyCount(): Int = geminiApiKeys.count { it.isNotBlank() }

    /**
     * Get current key index for UI updates
     */
    fun getCurrentKeyIndex(): Int = currentKeyIndex

    companion object {
        const val MAX_GEMINI_KEYS = 10

        fun getInstance(): AgentSettings {
            return ApplicationManager.getApplication().getService(AgentSettings::class.java)
        }
    }
}
