package com.hdsp.pycharm_agent.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.annotations.XCollection

/**
 * Persistent settings for PyCharm Agent plugin
 *
 * Supports two modes:
 * 1. ACP Mode (new): Uses external ACP-compatible CLI agent via subprocess
 * 2. Legacy Mode: Uses Python FastAPI backend (deprecated, for backward compatibility)
 */
@State(
    name = "com.hdsp.pycharm_agent.settings.AgentSettings",
    storages = [Storage("PycharmAgentSettings.xml")]
)
class AgentSettings : PersistentStateComponent<AgentSettings.State> {

    data class State(
        // ═══════════════════════════════════════════════════════════════
        // Mode Selection
        // ═══════════════════════════════════════════════════════════════
        var useAcpMode: Boolean = true,  // true = ACP mode, false = legacy backend mode

        // ═══════════════════════════════════════════════════════════════
        // ACP Agent Configuration (NEW)
        // ═══════════════════════════════════════════════════════════════
        var acpAgentPath: String = "",           // Path to ACP agent executable (e.g., /usr/local/bin/claude-code)
        @XCollection(style = XCollection.Style.v2)
        var acpAgentArgs: MutableList<String> = mutableListOf(),  // Additional CLI arguments
        var acpAutoStart: Boolean = true,        // Auto-start agent on project open
        var acpAutoRestart: Boolean = true,      // Auto-restart on crash
        var acpHealthCheckInterval: Int = 30,    // Seconds between health checks (0=disabled)

        // ═══════════════════════════════════════════════════════════════
        // OpenRouter Configuration (NEW - Primary LLM Provider)
        // ═══════════════════════════════════════════════════════════════
        var openrouterApiKey: String = "",
        var openrouterModel: String = "anthropic/claude-sonnet-4",
        var openrouterBaseUrl: String = "https://openrouter.ai/api/v1",
        var useOpenRouterDirect: Boolean = false,   // Use OpenRouter directly without ACP agent
        var openrouterSystemPrompt: String = "",    // Custom system prompt for OpenRouter

        // ═══════════════════════════════════════════════════════════════
        // Direct Provider Fallback (Optional - when OpenRouter unavailable)
        // ═══════════════════════════════════════════════════════════════
        var enableDirectProviders: Boolean = false,
        var geminiApiKey: String = "",           // Single key (simplified from multi-key)
        var geminiModel: String = "gemini-2.5-flash",
        var openaiApiKey: String = "",
        var openaiModel: String = "gpt-4",

        // ═══════════════════════════════════════════════════════════════
        // HITL (Human-in-the-Loop) Configuration
        // ═══════════════════════════════════════════════════════════════
        var autoApproveRead: Boolean = true,     // Auto-approve read operations
        var autoApproveWrite: Boolean = false,   // Require approval for file writes
        var autoApproveShell: Boolean = false,   // Require approval for shell commands
        var showDiffPreview: Boolean = true,     // Show inline diff preview before write
        var diffPreviewTimeout: Int = 0,         // Auto-accept timeout in seconds (0=disabled)

        // ═══════════════════════════════════════════════════════════════
        // Agent Behavior
        // ═══════════════════════════════════════════════════════════════
        var defaultMode: String = "chat",        // Default input mode: "chat" | "agent"
        var systemPrompt: String = "",           // Custom system prompt
        var workspaceRoot: String = "",          // Override project root (empty = use project root)

        // ═══════════════════════════════════════════════════════════════
        // Session Management
        // ═══════════════════════════════════════════════════════════════
        var persistSessions: Boolean = true,     // Save session history
        var maxSessionHistory: Int = 50,         // Max sessions to retain
        var idleTimeoutMinutes: Int = 60,        // Kill agent after idle period (0=disabled)

        // ═══════════════════════════════════════════════════════════════
        // Legacy Settings (for backward compatibility - deprecated)
        // ═══════════════════════════════════════════════════════════════
        @Deprecated("Use useAcpMode instead")
        var backendUrl: String = "http://localhost:8000",
        @Deprecated("Use openrouterModel or direct provider settings")
        var provider: String = "gemini",
        @Deprecated("Use geminiApiKey instead")
        @XCollection(style = XCollection.Style.v2)
        var geminiApiKeys: MutableList<String> = mutableListOf(),
        @Deprecated("Use vllmEndpoint in direct providers")
        var vllmEndpoint: String = "http://localhost:8000",
        @Deprecated("Use vllmModel in direct providers")
        var vllmModel: String = "meta-llama/Llama-2-7b-chat-hf",
        @Deprecated("Use vllmApiKey in direct providers")
        var vllmApiKey: String = "",
        @Deprecated("Use autoApproveWrite and autoApproveShell instead")
        var autoApprove: Boolean = false,
        @Deprecated("Use diffPreviewTimeout instead")
        var autoAcceptDiff: Boolean = false,
        @Deprecated("Removed - always use agent mode when needed")
        var autoExecuteMode: Boolean = false
    )

    private var state = State()

    // Migration flag (runtime only)
    @Transient
    private var migrationPerformed = false

    // API Key Rotation State (runtime only, not persisted) - Legacy
    @Transient
    private var currentKeyIndex: Int = 0

    @Transient
    private val rateLimitedKeys: MutableSet<Int> = mutableSetOf()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
        performMigrationIfNeeded()
    }

    // ═══════════════════════════════════════════════════════════════
    // Mode Selection
    // ═══════════════════════════════════════════════════════════════

    var useAcpMode: Boolean
        get() = state.useAcpMode
        set(value) { state.useAcpMode = value }

    // ═══════════════════════════════════════════════════════════════
    // ACP Agent Configuration (NEW)
    // ═══════════════════════════════════════════════════════════════

    var acpAgentPath: String
        get() = state.acpAgentPath
        set(value) { state.acpAgentPath = value }

    var acpAgentArgs: MutableList<String>
        get() = state.acpAgentArgs
        set(value) { state.acpAgentArgs = value }

    var acpAutoStart: Boolean
        get() = state.acpAutoStart
        set(value) { state.acpAutoStart = value }

    var acpAutoRestart: Boolean
        get() = state.acpAutoRestart
        set(value) { state.acpAutoRestart = value }

    var acpHealthCheckInterval: Int
        get() = state.acpHealthCheckInterval
        set(value) { state.acpHealthCheckInterval = value }

    // ═══════════════════════════════════════════════════════════════
    // OpenRouter Configuration (NEW)
    // ═══════════════════════════════════════════════════════════════

    var openrouterApiKey: String
        get() = state.openrouterApiKey
        set(value) { state.openrouterApiKey = value }

    var openrouterModel: String
        get() = state.openrouterModel
        set(value) { state.openrouterModel = value }

    var openrouterBaseUrl: String
        get() = state.openrouterBaseUrl
        set(value) { state.openrouterBaseUrl = value }

    var useOpenRouterDirect: Boolean
        get() = state.useOpenRouterDirect
        set(value) { state.useOpenRouterDirect = value }

    var openrouterSystemPrompt: String
        get() = state.openrouterSystemPrompt
        set(value) { state.openrouterSystemPrompt = value }

    // ═══════════════════════════════════════════════════════════════
    // Direct Provider Fallback (Optional)
    // ═══════════════════════════════════════════════════════════════

    var enableDirectProviders: Boolean
        get() = state.enableDirectProviders
        set(value) { state.enableDirectProviders = value }

    var geminiApiKey: String
        get() = state.geminiApiKey
        set(value) { state.geminiApiKey = value }

    var geminiModel: String
        get() = state.geminiModel
        set(value) { state.geminiModel = value }

    var openaiApiKey: String
        get() = state.openaiApiKey
        set(value) { state.openaiApiKey = value }

    var openaiModel: String
        get() = state.openaiModel
        set(value) { state.openaiModel = value }

    // ═══════════════════════════════════════════════════════════════
    // HITL Configuration (NEW)
    // ═══════════════════════════════════════════════════════════════

    var autoApproveRead: Boolean
        get() = state.autoApproveRead
        set(value) { state.autoApproveRead = value }

    var autoApproveWrite: Boolean
        get() = state.autoApproveWrite
        set(value) { state.autoApproveWrite = value }

    var autoApproveShell: Boolean
        get() = state.autoApproveShell
        set(value) { state.autoApproveShell = value }

    var showDiffPreview: Boolean
        get() = state.showDiffPreview
        set(value) { state.showDiffPreview = value }

    var diffPreviewTimeout: Int
        get() = state.diffPreviewTimeout
        set(value) { state.diffPreviewTimeout = value }

    // ═══════════════════════════════════════════════════════════════
    // Agent Behavior
    // ═══════════════════════════════════════════════════════════════

    var defaultMode: String
        get() = state.defaultMode
        set(value) { state.defaultMode = value }

    var systemPrompt: String
        get() = state.systemPrompt
        set(value) { state.systemPrompt = value }

    var workspaceRoot: String
        get() = state.workspaceRoot
        set(value) { state.workspaceRoot = value }

    // ═══════════════════════════════════════════════════════════════
    // Session Management
    // ═══════════════════════════════════════════════════════════════

    var persistSessions: Boolean
        get() = state.persistSessions
        set(value) { state.persistSessions = value }

    var maxSessionHistory: Int
        get() = state.maxSessionHistory
        set(value) { state.maxSessionHistory = value }

    var idleTimeoutMinutes: Int
        get() = state.idleTimeoutMinutes
        set(value) { state.idleTimeoutMinutes = value }

    // ═══════════════════════════════════════════════════════════════
    // Legacy Properties (Deprecated - for backward compatibility)
    // ═══════════════════════════════════════════════════════════════

    @Deprecated("Use useAcpMode instead")
    var backendUrl: String
        get() = state.backendUrl
        set(value) { state.backendUrl = value }

    @Deprecated("Use openrouterModel or direct provider settings")
    var provider: String
        get() = state.provider
        set(value) { state.provider = value }

    @Deprecated("Use geminiApiKey instead")
    var geminiApiKeys: MutableList<String>
        get() = state.geminiApiKeys
        set(value) { state.geminiApiKeys = value }

    @Deprecated("Use vllmEndpoint in direct providers")
    var vllmEndpoint: String
        get() = state.vllmEndpoint
        set(value) { state.vllmEndpoint = value }

    @Deprecated("Use vllmModel in direct providers")
    var vllmModel: String
        get() = state.vllmModel
        set(value) { state.vllmModel = value }

    @Deprecated("Use vllmApiKey in direct providers")
    var vllmApiKey: String
        get() = state.vllmApiKey
        set(value) { state.vllmApiKey = value }

    @Deprecated("Use autoApproveWrite and autoApproveShell instead")
    var autoApprove: Boolean
        get() = state.autoApprove
        set(value) { state.autoApprove = value }

    @Deprecated("Use diffPreviewTimeout instead")
    var autoAcceptDiff: Boolean
        get() = state.autoAcceptDiff
        set(value) { state.autoAcceptDiff = value }

    @Deprecated("Removed")
    var autoExecuteMode: Boolean
        get() = state.autoExecuteMode
        set(value) { state.autoExecuteMode = value }

    // ═══════════════════════════════════════════════════════════════
    // Migration
    // ═══════════════════════════════════════════════════════════════

    /**
     * Migrate legacy settings to new format (one-time)
     */
    private fun performMigrationIfNeeded() {
        if (migrationPerformed) return
        migrationPerformed = true

        // Migrate geminiApiKeys to single geminiApiKey
        if (state.geminiApiKey.isBlank() && state.geminiApiKeys.isNotEmpty()) {
            state.geminiApiKey = state.geminiApiKeys.firstOrNull { it.isNotBlank() } ?: ""
        }

        // Migrate autoApprove to new HITL settings
        if (state.autoApprove) {
            state.autoApproveWrite = true
            state.autoApproveShell = true
        }

        // Migrate autoAcceptDiff to diffPreviewTimeout
        if (state.autoAcceptDiff && state.diffPreviewTimeout == 0) {
            state.diffPreviewTimeout = 5 // 5 second auto-accept
        }

        // If legacy settings exist but ACP not configured, start in legacy mode
        if (state.acpAgentPath.isBlank() && state.backendUrl.isNotBlank()) {
            state.useAcpMode = false
        }
    }

    /**
     * Check if ACP mode is properly configured
     */
    fun isAcpConfigured(): Boolean {
        return state.acpAgentPath.isNotBlank()
    }

    /**
     * Check if OpenRouter is configured
     */
    fun isOpenRouterConfigured(): Boolean {
        return state.openrouterApiKey.isNotBlank()
    }

    /**
     * Get effective LLM configuration summary for display
     */
    fun getLlmConfigSummary(): String {
        return when {
            state.openrouterApiKey.isNotBlank() -> "OpenRouter: ${state.openrouterModel}"
            state.enableDirectProviders && state.geminiApiKey.isNotBlank() -> "Gemini: ${state.geminiModel}"
            state.enableDirectProviders && state.openaiApiKey.isNotBlank() -> "OpenAI: ${state.openaiModel}"
            else -> "Not configured"
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Legacy API Key Rotation Methods (for backward compatibility)
    // ═══════════════════════════════════════════════════════════════

    @Deprecated("Use single geminiApiKey instead")
    fun getCurrentGeminiKey(): String? {
        val keys = geminiApiKeys.filter { it.isNotBlank() }
        if (keys.isEmpty()) return null
        if (currentKeyIndex >= keys.size) currentKeyIndex = 0
        return keys.getOrNull(currentKeyIndex)
    }

    @Deprecated("Use single geminiApiKey instead")
    fun markKeyAsRateLimited(index: Int) {
        rateLimitedKeys.add(index)
    }

    @Deprecated("Use single geminiApiKey instead")
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
        return null
    }

    @Deprecated("Use single geminiApiKey instead")
    fun resetKeyRotation() {
        currentKeyIndex = 0
        rateLimitedKeys.clear()
    }

    @Deprecated("Use single geminiApiKey instead")
    fun getValidKeyCount(): Int = geminiApiKeys.count { it.isNotBlank() }

    @Deprecated("Use single geminiApiKey instead")
    fun getCurrentKeyIndex(): Int = currentKeyIndex

    companion object {
        const val MAX_GEMINI_KEYS = 10

        // Popular OpenRouter models for dropdown
        val OPENROUTER_MODELS = listOf(
            "anthropic/claude-sonnet-4" to "Claude Sonnet 4 (Anthropic)",
            "anthropic/claude-opus-4" to "Claude Opus 4 (Anthropic)",
            "openai/gpt-4o" to "GPT-4o (OpenAI)",
            "openai/gpt-4-turbo" to "GPT-4 Turbo (OpenAI)",
            "google/gemini-2.0-flash" to "Gemini 2.0 Flash (Google)",
            "google/gemini-2.0-pro" to "Gemini 2.0 Pro (Google)",
            "meta-llama/llama-3.1-70b-instruct" to "Llama 3.1 70B (Meta)",
            "deepseek/deepseek-chat" to "DeepSeek Chat",
            "mistralai/mistral-large" to "Mistral Large"
        )

        fun getInstance(): AgentSettings {
            return ApplicationManager.getApplication().getService(AgentSettings::class.java)
        }
    }
}
