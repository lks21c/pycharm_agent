package com.hdsp.pycharm_agent.openrouter

import com.hdsp.pycharm_agent.services.*
import com.hdsp.pycharm_agent.settings.AgentSettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.util.UUID

/**
 * OpenRouter Client Adapter
 *
 * Implements AgentClientAdapter using OpenRouter API directly.
 * This provides a lightweight alternative to the full ACP agent
 * for simple chat interactions.
 *
 * Note: This adapter only supports chat mode, not full agent mode
 * with tool execution. For agent mode, use ACPClientAdapter.
 */
class OpenRouterClientAdapter(private val project: Project) : AgentClientAdapter {

    private val log = Logger.getInstance(OpenRouterClientAdapter::class.java)
    private val openRouterService: OpenRouterService by lazy {
        OpenRouterService.getInstance(project)
    }
    private val stateListeners = mutableListOf<(AgentClientAdapter.ConnectionState) -> Unit>()
    private var currentState = AgentClientAdapter.ConnectionState.DISCONNECTED
    private var currentSessionId: String? = null
    private var permissionHandler: ((PermissionRequest) -> PermissionResult)? = null

    override fun getConnectionState(): AgentClientAdapter.ConnectionState = currentState

    override fun connect(): Boolean {
        updateState(AgentClientAdapter.ConnectionState.CONNECTING)

        if (!openRouterService.isConfigured()) {
            log.warn("OpenRouter not configured")
            updateState(AgentClientAdapter.ConnectionState.ERROR)
            return false
        }

        return try {
            val result = openRouterService.testConnection()
            if (result) {
                updateState(AgentClientAdapter.ConnectionState.CONNECTED)
                true
            } else {
                updateState(AgentClientAdapter.ConnectionState.ERROR)
                false
            }
        } catch (e: Exception) {
            log.warn("OpenRouter connection failed", e)
            updateState(AgentClientAdapter.ConnectionState.ERROR)
            false
        }
    }

    override fun disconnect() {
        currentSessionId = null
        openRouterService.clearAllConversations()
        updateState(AgentClientAdapter.ConnectionState.DISCONNECTED)
    }

    override fun isAvailable(): Boolean {
        return currentState == AgentClientAdapter.ConnectionState.CONNECTED
    }

    override fun createSession(mode: String): String? {
        currentSessionId = "openrouter-${UUID.randomUUID().toString().take(8)}"
        return currentSessionId
    }

    override fun sendChatMessage(
        message: String,
        sessionId: String?,
        onChunk: (String) -> Unit,
        onComplete: (ChatResponse) -> Unit,
        onError: (String) -> Unit
    ) {
        val sid = sessionId ?: currentSessionId ?: createSession("chat") ?: run {
            onError("Failed to create session")
            return
        }

        val settings = AgentSettings.getInstance()
        // Use custom system prompt if set, otherwise use default
        val customPrompt = settings.openrouterSystemPrompt.ifBlank { settings.systemPrompt }
        val systemPrompt = if (customPrompt.isNotBlank()) {
            customPrompt
        } else {
            "You are a helpful AI assistant integrated into PyCharm IDE. " +
            "You help developers with coding questions, debugging, and software development tasks. " +
            "Be concise and provide practical, actionable advice."
        }

        openRouterService.chatStream(
            message = message,
            conversationId = sid,
            systemPrompt = systemPrompt,
            onChunk = onChunk,
            onComplete = { content ->
                onComplete(ChatResponse(
                    content = content,
                    sessionId = sid,
                    model = settings.openrouterModel
                ))
            },
            onError = onError
        )
    }

    /**
     * Agent mode is not fully supported by OpenRouter adapter.
     * This provides a basic implementation that treats agent requests as chat.
     * For full agent capabilities with tool execution, use ACPClientAdapter.
     */
    override fun sendAgentRequest(
        request: String,
        sessionId: String?,
        onUpdate: (AgentUpdate) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        // Warn that full agent mode is not supported
        log.info("OpenRouter adapter: Agent request handled as chat (no tool execution)")

        val sid = sessionId ?: currentSessionId ?: createSession("agent") ?: run {
            onError("Failed to create session")
            return
        }

        // Enhanced system prompt for agent-like behavior
        val systemPrompt = """You are an AI coding assistant integrated into PyCharm IDE.
            |You help developers with:
            |- Writing and improving code
            |- Debugging and fixing issues
            |- Explaining code and concepts
            |- Suggesting best practices
            |
            |When asked to make changes:
            |1. Explain what changes are needed
            |2. Provide the complete code with changes
            |3. Explain any important considerations
            |
            |Be specific and provide actionable guidance.
            |Use markdown code blocks with language specification.""".trimMargin()

        openRouterService.chatStream(
            message = request,
            conversationId = sid,
            systemPrompt = systemPrompt,
            onChunk = { chunk ->
                onUpdate(AgentUpdate.Content(chunk))
            },
            onComplete = { _ ->
                onUpdate(AgentUpdate.TurnComplete)
                onComplete()
            },
            onError = onError
        )
    }

    override fun cancelCurrentOperation(sessionId: String?) {
        // OpenRouter doesn't support cancellation of in-flight requests
        // Just clear the session for next request
        log.info("Cancel requested (OpenRouter doesn't support mid-request cancellation)")
    }

    override fun setPermissionHandler(handler: (PermissionRequest) -> PermissionResult) {
        permissionHandler = handler
        // Note: OpenRouter adapter doesn't use permissions since it doesn't execute tools
    }

    override fun addStateListener(listener: (AgentClientAdapter.ConnectionState) -> Unit) {
        stateListeners.add(listener)
    }

    override fun removeStateListener(listener: (AgentClientAdapter.ConnectionState) -> Unit) {
        stateListeners.remove(listener)
    }

    override fun getCurrentSessionId(): String? = currentSessionId

    override fun getAgentInfoSummary(): String {
        val settings = AgentSettings.getInstance()
        val modelName = settings.openrouterModel.split("/").lastOrNull() ?: settings.openrouterModel
        return "OpenRouter ($modelName)"
    }

    private fun updateState(newState: AgentClientAdapter.ConnectionState) {
        if (currentState != newState) {
            currentState = newState
            stateListeners.forEach { listener ->
                try {
                    listener(newState)
                } catch (e: Exception) {
                    log.error("Error in state listener", e)
                }
            }
        }
    }
}
