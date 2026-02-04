package com.hdsp.pycharm_agent.services

import com.hdsp.pycharm_agent.acp.ACPClientService
import com.hdsp.pycharm_agent.acp.ACPClientState
import com.hdsp.pycharm_agent.acp.ContentBlock
import com.hdsp.pycharm_agent.acp.PermissionRequestParams
import com.hdsp.pycharm_agent.acp.PermissionResponse
import com.hdsp.pycharm_agent.acp.SessionUpdate
import com.hdsp.pycharm_agent.settings.AgentSettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * Agent Client Adapter - Abstracts ACP and Legacy backend communication
 *
 * Provides a unified interface for UI components to interact with either:
 * 1. ACP Mode: External CLI agent via subprocess (ACPClientService)
 * 2. Legacy Mode: Python FastAPI backend via HTTP (BackendClient)
 *
 * UI components should use this adapter instead of directly accessing
 * ACPClientService or BackendClient.
 */
interface AgentClientAdapter {

    /**
     * Connection state
     */
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    /**
     * Get current connection state
     */
    fun getConnectionState(): ConnectionState

    /**
     * Connect to the agent (ACP: start subprocess, Legacy: check health)
     */
    fun connect(): Boolean

    /**
     * Disconnect from the agent
     */
    fun disconnect()

    /**
     * Check if agent is available and ready
     */
    fun isAvailable(): Boolean

    /**
     * Create a new chat/agent session
     * @param mode "chat" or "agent"
     * @return Session ID or null if failed
     */
    fun createSession(mode: String): String?

    /**
     * Send a chat message (simple Q&A mode)
     *
     * @param message User message
     * @param sessionId Session ID (optional, creates new if null)
     * @param onChunk Callback for each streaming text chunk
     * @param onComplete Callback when response is complete
     * @param onError Callback on error
     */
    fun sendChatMessage(
        message: String,
        sessionId: String? = null,
        onChunk: (String) -> Unit,
        onComplete: (ChatResponse) -> Unit,
        onError: (String) -> Unit
    )

    /**
     * Send an agent request (with tool execution)
     *
     * @param request User request
     * @param sessionId Session ID (optional, creates new if null)
     * @param onUpdate Callback for streaming updates (content, tool calls, todos, etc.)
     * @param onComplete Callback when request is complete
     * @param onError Callback on error
     */
    fun sendAgentRequest(
        request: String,
        sessionId: String? = null,
        onUpdate: (AgentUpdate) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    )

    /**
     * Cancel current operation
     */
    fun cancelCurrentOperation(sessionId: String? = null)

    /**
     * Set permission request handler (for HITL)
     */
    fun setPermissionHandler(handler: (PermissionRequest) -> PermissionResult)

    /**
     * Add state change listener
     */
    fun addStateListener(listener: (ConnectionState) -> Unit)

    /**
     * Remove state change listener
     */
    fun removeStateListener(listener: (ConnectionState) -> Unit)

    /**
     * Get current session ID
     */
    fun getCurrentSessionId(): String?

    /**
     * Get agent info summary (for display)
     */
    fun getAgentInfoSummary(): String
}

/**
 * Chat response data
 */
data class ChatResponse(
    val content: String,
    val sessionId: String,
    val model: String? = null
)

/**
 * Agent update types (sealed class for type safety)
 */
sealed class AgentUpdate {
    data class Content(val text: String) : AgentUpdate()
    data class ToolCall(val tool: String, val status: String, val result: String? = null) : AgentUpdate()
    data class Plan(val steps: List<String>, val currentStep: Int?) : AgentUpdate()
    data class Todos(val items: List<TodoItem>) : AgentUpdate()
    data class Status(val message: String) : AgentUpdate()
    object TurnComplete : AgentUpdate()
}

// Note: TodoItem is defined in BackendClient.kt - we reuse it

/**
 * Permission request for HITL
 */
data class PermissionRequest(
    val type: String,  // "write_file", "edit_file", "execute_command", "read_file"
    val path: String?,
    val command: String?,
    val description: String,
    val content: String? = null,
    val oldString: String? = null,
    val newString: String? = null,
    val diff: DiffInfo? = null
)

/**
 * Diff information for file changes
 */
data class DiffInfo(
    val hunks: List<AdapterDiffHunk>,
    val previewContent: String
)

/**
 * Single diff hunk (named AdapterDiffHunk to avoid conflict with BackendClient.DiffHunk)
 */
data class AdapterDiffHunk(
    val startLine: Int,
    val endLine: Int,
    val oldContent: String,
    val newContent: String,
    val changeType: String  // "add", "delete", "modify"
)

/**
 * Permission result from user
 */
data class PermissionResult(
    val granted: Boolean,
    val modifiedContent: String? = null,  // If user edited the content
    val feedback: String? = null  // If user denied with reason
)

/**
 * ACP-based client adapter implementation
 */
class ACPClientAdapter(private val project: Project) : AgentClientAdapter {

    private val log = Logger.getInstance(ACPClientAdapter::class.java)
    private val acpClient: ACPClientService by lazy {
        ACPClientService.getInstance(project)
    }
    private val stateListeners = mutableListOf<(AgentClientAdapter.ConnectionState) -> Unit>()
    private var permissionHandler: ((PermissionRequest) -> PermissionResult)? = null

    init {
        // Forward ACP state changes
        acpClient.addStateListener { acpState ->
            val adapterState = when (acpState) {
                ACPClientState.DISCONNECTED -> AgentClientAdapter.ConnectionState.DISCONNECTED
                ACPClientState.CONNECTING -> AgentClientAdapter.ConnectionState.CONNECTING
                ACPClientState.CONNECTED -> AgentClientAdapter.ConnectionState.CONNECTED
                ACPClientState.ERROR -> AgentClientAdapter.ConnectionState.ERROR
            }
            notifyStateListeners(adapterState)
        }

        // Set up permission handler bridge
        acpClient.setPermissionRequestHandler { acpParams ->
            val request = PermissionRequest(
                type = acpParams.permission,
                path = acpParams.path,
                command = acpParams.command,
                description = acpParams.description,
                content = acpParams.content,
                diff = acpParams.diff?.let { diff ->
                    DiffInfo(
                        hunks = diff.hunks.map { hunk ->
                            AdapterDiffHunk(
                                startLine = hunk.startLine,
                                endLine = hunk.endLine,
                                oldContent = hunk.oldContent,
                                newContent = hunk.newContent,
                                changeType = hunk.changeType
                            )
                        },
                        previewContent = diff.preview
                    )
                }
            )

            val result = permissionHandler?.invoke(request) ?: PermissionResult(granted = false)

            PermissionResponse(
                granted = result.granted,
                feedback = result.feedback
            )
        }
    }

    override fun getConnectionState(): AgentClientAdapter.ConnectionState {
        return when (acpClient.getState()) {
            ACPClientState.DISCONNECTED -> AgentClientAdapter.ConnectionState.DISCONNECTED
            ACPClientState.CONNECTING -> AgentClientAdapter.ConnectionState.CONNECTING
            ACPClientState.CONNECTED -> AgentClientAdapter.ConnectionState.CONNECTED
            ACPClientState.ERROR -> AgentClientAdapter.ConnectionState.ERROR
        }
    }

    override fun connect(): Boolean {
        return acpClient.start()
    }

    override fun disconnect() {
        acpClient.stop()
    }

    override fun isAvailable(): Boolean {
        return acpClient.isRunning()
    }

    override fun createSession(mode: String): String? {
        val sessionInfo = acpClient.createSession(mode)
        return sessionInfo?.sessionId
    }

    override fun sendChatMessage(
        message: String,
        sessionId: String?,
        onChunk: (String) -> Unit,
        onComplete: (ChatResponse) -> Unit,
        onError: (String) -> Unit
    ) {
        // Ensure session exists
        val sid = sessionId ?: acpClient.getCurrentSessionId() ?: run {
            val newSession = acpClient.createSession("chat")
            if (newSession == null) {
                onError("Failed to create session")
                return
            }
            newSession.sessionId
        }

        val contentBuilder = StringBuilder()

        acpClient.sendPrompt(
            message = message,
            sessionId = sid,
            onUpdate = { update ->
                when (update) {
                    is SessionUpdate.AgentMessageChunk -> {
                        update.content.forEach { block ->
                            if (block.type == "text" && block.text != null) {
                                contentBuilder.append(block.text)
                                onChunk(block.text)
                            }
                        }
                    }
                    is SessionUpdate.TurnComplete -> {
                        // Handled in onComplete callback
                    }
                    is SessionUpdate.ErrorUpdate -> {
                        onError(update.error)
                    }
                    else -> {
                        // Ignore other update types in chat mode
                    }
                }
            },
            onComplete = {
                val agentInfo = acpClient.getAgentInfo()
                onComplete(ChatResponse(
                    content = contentBuilder.toString(),
                    sessionId = sid,
                    model = agentInfo?.name
                ))
            },
            onError = onError
        )
    }

    override fun sendAgentRequest(
        request: String,
        sessionId: String?,
        onUpdate: (AgentUpdate) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        // Ensure session exists
        val sid = sessionId ?: acpClient.getCurrentSessionId() ?: run {
            val newSession = acpClient.createSession("agent")
            if (newSession == null) {
                onError("Failed to create session")
                return
            }
            newSession.sessionId
        }

        acpClient.sendPrompt(
            message = request,
            sessionId = sid,
            onUpdate = { update ->
                val agentUpdate = when (update) {
                    is SessionUpdate.AgentMessageChunk -> {
                        val text = update.content
                            .filter { it.type == "text" && it.text != null }
                            .joinToString("") { it.text!! }
                        if (text.isNotEmpty()) AgentUpdate.Content(text) else null
                    }
                    is SessionUpdate.ToolCallUpdate -> {
                        AgentUpdate.ToolCall(
                            tool = update.tool,
                            status = update.status,
                            result = update.result
                        )
                    }
                    is SessionUpdate.PlanUpdate -> {
                        AgentUpdate.Plan(
                            steps = update.plan.steps,
                            currentStep = update.plan.currentStep
                        )
                    }
                    is SessionUpdate.TodoUpdate -> {
                        AgentUpdate.Todos(
                            items = update.todos.map { todo ->
                                TodoItem(content = todo.content, status = todo.status)
                            }
                        )
                    }
                    is SessionUpdate.TurnComplete -> {
                        AgentUpdate.TurnComplete
                    }
                    is SessionUpdate.ErrorUpdate -> {
                        onError(update.error)
                        null
                    }
                    else -> null
                }

                agentUpdate?.let { onUpdate(it) }
            },
            onComplete = onComplete,
            onError = onError
        )
    }

    override fun cancelCurrentOperation(sessionId: String?) {
        acpClient.cancelSession(sessionId)
    }

    override fun setPermissionHandler(handler: (PermissionRequest) -> PermissionResult) {
        permissionHandler = handler
    }

    override fun addStateListener(listener: (AgentClientAdapter.ConnectionState) -> Unit) {
        stateListeners.add(listener)
    }

    override fun removeStateListener(listener: (AgentClientAdapter.ConnectionState) -> Unit) {
        stateListeners.remove(listener)
    }

    override fun getCurrentSessionId(): String? {
        return acpClient.getCurrentSessionId()
    }

    override fun getAgentInfoSummary(): String {
        val info = acpClient.getAgentInfo()
        return if (info != null) {
            "${info.name} v${info.version}"
        } else {
            "ACP Agent (not connected)"
        }
    }

    private fun notifyStateListeners(state: AgentClientAdapter.ConnectionState) {
        stateListeners.forEach { listener ->
            try {
                listener(state)
            } catch (e: Exception) {
                log.error("Error in state listener", e)
            }
        }
    }
}

/**
 * Legacy backend client adapter implementation
 * Wraps existing BackendClient for backward compatibility
 */
class LegacyClientAdapter(private val project: Project) : AgentClientAdapter {

    private val log = Logger.getInstance(LegacyClientAdapter::class.java)
    private val backendClient: BackendClient by lazy {
        project.getService(BackendClient::class.java)
    }
    private val stateListeners = mutableListOf<(AgentClientAdapter.ConnectionState) -> Unit>()
    private var currentState = AgentClientAdapter.ConnectionState.DISCONNECTED
    private var currentSessionId: String? = null
    private var permissionHandler: ((PermissionRequest) -> PermissionResult)? = null

    override fun getConnectionState(): AgentClientAdapter.ConnectionState = currentState

    override fun connect(): Boolean {
        updateState(AgentClientAdapter.ConnectionState.CONNECTING)

        return try {
            // Check backend health
            val isHealthy = backendClient.checkHealth()
            if (isHealthy) {
                updateState(AgentClientAdapter.ConnectionState.CONNECTED)
                true
            } else {
                updateState(AgentClientAdapter.ConnectionState.ERROR)
                false
            }
        } catch (e: Exception) {
            log.warn("Backend health check failed", e)
            updateState(AgentClientAdapter.ConnectionState.ERROR)
            false
        }
    }

    override fun disconnect() {
        currentSessionId = null
        updateState(AgentClientAdapter.ConnectionState.DISCONNECTED)
    }

    override fun isAvailable(): Boolean {
        return currentState == AgentClientAdapter.ConnectionState.CONNECTED
    }

    override fun createSession(mode: String): String? {
        // Legacy backend doesn't have explicit sessions, generate pseudo-session
        currentSessionId = "legacy-${System.currentTimeMillis()}"
        return currentSessionId
    }

    override fun sendChatMessage(
        message: String,
        sessionId: String?,
        onChunk: (String) -> Unit,
        onComplete: (ChatResponse) -> Unit,
        onError: (String) -> Unit
    ) {
        val contentBuilder = StringBuilder()
        var responseSessionId = sessionId ?: currentSessionId ?: "legacy-chat"
        var model: String? = null

        try {
            backendClient.streamChatSync(
                message = message,
                conversationId = responseSessionId,
                onMetadata = { metadata ->
                    responseSessionId = metadata.conversationId ?: responseSessionId
                    model = metadata.model
                    currentSessionId = responseSessionId
                },
                onKeyRotation = { keyIndex, totalKeys ->
                    // Legacy rate limiting notification
                    log.info("Key rotation: $keyIndex / $totalKeys")
                },
                onChunk = { chunk ->
                    contentBuilder.append(chunk)
                    onChunk(chunk)
                }
            )

            onComplete(ChatResponse(
                content = contentBuilder.toString(),
                sessionId = responseSessionId,
                model = model
            ))
        } catch (e: Exception) {
            onError(e.message ?: "Unknown error")
        }
    }

    override fun sendAgentRequest(
        request: String,
        sessionId: String?,
        onUpdate: (AgentUpdate) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            backendClient.streamAgentSync(
                request = request,
                threadId = sessionId,
                onChunk = { chunk ->
                    onUpdate(AgentUpdate.Content(chunk))
                },
                onDebug = { status ->
                    onUpdate(AgentUpdate.Status(status))
                },
                onInterrupt = { interrupt ->
                    // Handle HITL interrupt
                    handleInterrupt(interrupt, onError)
                },
                onTodos = { todos ->
                    onUpdate(AgentUpdate.Todos(
                        items = todos.map { TodoItem(it.content, it.status) }
                    ))
                },
                onToolCall = { toolEvent ->
                    onUpdate(AgentUpdate.ToolCall(toolEvent.tool, "started"))
                },
                onComplete = { threadId ->
                    currentSessionId = threadId
                    onUpdate(AgentUpdate.TurnComplete)
                    onComplete()
                },
                onKeyRotation = { keyIndex, totalKeys ->
                    log.info("Key rotation: $keyIndex / $totalKeys")
                }
            )
        } catch (e: Exception) {
            onError(e.message ?: "Unknown error")
        }
    }

    private fun handleInterrupt(interrupt: AgentInterrupt, onError: (String) -> Unit) {
        val request = PermissionRequest(
            type = interrupt.action,
            path = interrupt.args?.get("path") as? String,
            command = interrupt.args?.get("command") as? String,
            description = interrupt.description ?: "Tool execution request",
            content = interrupt.args?.get("content") as? String,
            oldString = interrupt.args?.get("old_string") as? String,
            newString = interrupt.args?.get("new_string") as? String
        )

        val result = permissionHandler?.invoke(request) ?: PermissionResult(granted = false)

        // Resume with decision
        try {
            backendClient.resumeAgentSync(
                threadId = interrupt.threadId,
                decision = if (result.granted) "approve" else "reject",
                args = null,
                feedback = result.feedback,
                onChunk = { },
                onDebug = { },
                onInterrupt = { },
                onTodos = { },
                onToolCall = { },
                onComplete = { },
                onKeyRotation = { _, _ -> }
            )
        } catch (e: Exception) {
            onError("Failed to resume: ${e.message}")
        }
    }

    override fun cancelCurrentOperation(sessionId: String?) {
        // Legacy backend doesn't support cancellation
        log.warn("Cancel not supported in legacy mode")
    }

    override fun setPermissionHandler(handler: (PermissionRequest) -> PermissionResult) {
        permissionHandler = handler
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
        return "Legacy Backend (${settings.provider})"
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

/**
 * Client mode for adapter selection
 */
enum class ClientMode {
    ACP,            // External CLI agent via subprocess
    OPENROUTER,     // Direct OpenRouter API calls
    LEGACY          // Legacy Python backend
}

/**
 * Factory for creating appropriate client adapter based on settings
 */
@Service(Service.Level.PROJECT)
class AgentClientFactory(private val project: Project) {

    private val log = Logger.getInstance(AgentClientFactory::class.java)
    private var cachedAdapter: AgentClientAdapter? = null
    private var cachedMode: ClientMode? = null

    /**
     * Determine which client mode to use based on settings
     */
    fun determineMode(): ClientMode {
        val settings = AgentSettings.getInstance()

        // Priority 1: ACP mode if enabled and configured
        if (settings.useAcpMode && settings.isAcpConfigured()) {
            return ClientMode.ACP
        }

        // Priority 2: OpenRouter direct mode if enabled and configured
        if (settings.useOpenRouterDirect && settings.isOpenRouterConfigured()) {
            return ClientMode.OPENROUTER
        }

        // Priority 3: Legacy backend
        return ClientMode.LEGACY
    }

    /**
     * Get or create client adapter based on current settings
     */
    fun getClient(): AgentClientAdapter {
        val mode = determineMode()

        // Return cached adapter if mode hasn't changed
        if (cachedAdapter != null && cachedMode == mode) {
            return cachedAdapter!!
        }

        log.info("Creating client adapter for mode: $mode")

        // Create new adapter based on mode
        cachedAdapter = when (mode) {
            ClientMode.ACP -> ACPClientAdapter(project)
            ClientMode.OPENROUTER -> {
                // Import dynamically to avoid circular dependency
                com.hdsp.pycharm_agent.openrouter.OpenRouterClientAdapter(project)
            }
            ClientMode.LEGACY -> LegacyClientAdapter(project)
        }
        cachedMode = mode

        return cachedAdapter!!
    }

    /**
     * Get current client mode
     */
    fun getCurrentMode(): ClientMode {
        return cachedMode ?: determineMode()
    }

    /**
     * Force refresh adapter (e.g., after settings change)
     */
    fun refresh() {
        cachedAdapter?.disconnect()
        cachedAdapter = null
        cachedMode = null
    }

    companion object {
        fun getInstance(project: Project): AgentClientFactory {
            return project.getService(AgentClientFactory::class.java)
        }
    }
}
