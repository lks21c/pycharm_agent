package com.hdsp.pycharm_agent.acp

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.hdsp.pycharm_agent.settings.AgentSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * ACP Client Service
 *
 * Manages ACP agent subprocess lifecycle and provides high-level API
 * for communication with ACP-compatible coding agents.
 *
 * Key responsibilities:
 * - Subprocess lifecycle management (start, stop, health check, crash recovery)
 * - ACP protocol implementation (initialize, session management)
 * - Message routing and handler registration
 * - OpenRouter credential injection
 */
@Service(Service.Level.PROJECT)
class ACPClientService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(ACPClientService::class.java)
    private val gson: Gson = GsonBuilder().create()
    private val settings = AgentSettings.getInstance()

    // Process management
    private val processRef = AtomicReference<Process?>(null)
    private val transportRef = AtomicReference<StdioTransport?>(null)
    private val isInitialized = AtomicBoolean(false)

    // State
    private val state = AtomicReference(ACPClientState.DISCONNECTED)
    private val agentInfo = AtomicReference<AgentInfo?>(null)
    private val agentCapabilities = AtomicReference<AgentCapabilities?>(null)

    // Session management
    private val sessions = ConcurrentHashMap<String, ACPSession>()
    private var currentSessionId: String? = null

    // Health monitoring
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ACP-HealthMonitor").apply { isDaemon = true }
    }
    private var healthCheckTask: ScheduledFuture<*>? = null
    private var restartAttempts = 0
    private val maxRestartAttempts = 3

    // Event listeners
    private val stateListeners = mutableListOf<(ACPClientState) -> Unit>()
    private val sessionUpdateListeners = mutableListOf<(String, SessionUpdate) -> Unit>()
    private val permissionRequestHandler = AtomicReference<((PermissionRequestParams) -> PermissionResponse)?>(null)

    // Filesystem and terminal handlers
    private val fileSystemHandler = AtomicReference<ACPFileSystemHandler?>(null)
    private val terminalHandler = AtomicReference<ACPTerminalHandler?>(null)

    init {
        Disposer.register(project, this)
    }

    // ═══════════════════════════════════════════════════════════════
    // Public API - Lifecycle
    // ═══════════════════════════════════════════════════════════════

    /**
     * Start the ACP agent subprocess
     *
     * @return true if started successfully
     */
    fun start(): Boolean {
        if (state.get() == ACPClientState.CONNECTED) {
            log.info("ACP agent already running")
            return true
        }

        val agentPath = settings.acpAgentPath
        if (agentPath.isBlank()) {
            log.warn("ACP agent path not configured")
            updateState(ACPClientState.ERROR)
            return false
        }

        val agentFile = File(agentPath)
        if (!agentFile.exists() || !agentFile.canExecute()) {
            log.warn("ACP agent not found or not executable: $agentPath")
            updateState(ACPClientState.ERROR)
            return false
        }

        updateState(ACPClientState.CONNECTING)

        try {
            val process = startProcess(agentPath)
            processRef.set(process)

            val transport = StdioTransport.fromProcess(process)
            transportRef.set(transport)

            // Register handlers before starting receive loop
            registerClientMethods(transport)

            // Start receiving messages
            transport.startReceiving()

            // Perform ACP initialization handshake
            val initResult = initialize(transport)
            if (initResult == null) {
                stop()
                return false
            }

            agentInfo.set(initResult.agentInfo)
            agentCapabilities.set(initResult.agentCapabilities)
            isInitialized.set(true)

            updateState(ACPClientState.CONNECTED)
            restartAttempts = 0

            // Start health monitoring
            startHealthMonitor()

            log.info("ACP agent started: ${initResult.agentInfo.name} v${initResult.agentInfo.version}")
            return true

        } catch (e: Exception) {
            log.error("Failed to start ACP agent", e)
            stop()
            updateState(ACPClientState.ERROR)
            return false
        }
    }

    /**
     * Stop the ACP agent subprocess
     */
    fun stop() {
        log.info("Stopping ACP agent")

        stopHealthMonitor()
        isInitialized.set(false)

        // Send shutdown if possible
        try {
            transportRef.get()?.let { transport ->
                if (transport.isConnected()) {
                    val request = ACPRequest(
                        id = (transport as? StdioTransport)?.nextRequestId() ?: 0,
                        method = ACPMethods.SHUTDOWN
                    )
                    try {
                        transport.sendRequest(request, timeoutMs = 5000)
                    } catch (e: Exception) {
                        log.debug("Shutdown request failed (may be expected)", e)
                    }
                }
            }
        } catch (e: Exception) {
            log.debug("Error during shutdown", e)
        }

        // Close transport
        try {
            transportRef.getAndSet(null)?.close()
        } catch (e: Exception) {
            log.debug("Error closing transport", e)
        }

        // Destroy process
        try {
            processRef.getAndSet(null)?.let { process ->
                if (process.isAlive) {
                    process.destroy()
                    if (!process.waitFor(5, TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                    }
                }
            }
        } catch (e: Exception) {
            log.debug("Error destroying process", e)
        }

        // Clear sessions
        sessions.clear()
        currentSessionId = null

        updateState(ACPClientState.DISCONNECTED)
        log.info("ACP agent stopped")
    }

    /**
     * Restart the ACP agent
     */
    fun restart(): Boolean {
        stop()
        Thread.sleep(500) // Brief pause before restart
        return start()
    }

    /**
     * Check if agent is running and connected
     */
    fun isRunning(): Boolean {
        val process = processRef.get() ?: return false
        val transport = transportRef.get() ?: return false
        return process.isAlive && transport.isConnected() && isInitialized.get()
    }

    /**
     * Get current state
     */
    fun getState(): ACPClientState = state.get()

    /**
     * Get agent info (after initialization)
     */
    fun getAgentInfo(): AgentInfo? = agentInfo.get()

    /**
     * Get agent capabilities (after initialization)
     */
    fun getAgentCapabilities(): AgentCapabilities? = agentCapabilities.get()

    // ═══════════════════════════════════════════════════════════════
    // Public API - Session Management
    // ═══════════════════════════════════════════════════════════════

    /**
     * Create a new session
     *
     * @param mode "chat" or "agent"
     * @param workspaceRoot Project root path
     * @return Session info or null if failed
     */
    fun createSession(mode: String = "chat", workspaceRoot: String? = null): SessionInfo? {
        val transport = transportRef.get()
        if (transport == null || !isRunning()) {
            log.warn("Cannot create session: agent not running")
            return null
        }

        val root = workspaceRoot ?: settings.workspaceRoot.takeIf { it.isNotBlank() } ?: project.basePath ?: ""

        val params = JsonObject().apply {
            addProperty("workspaceRoot", root)
            addProperty("mode", mode)
        }

        val request = ACPRequest(
            id = (transport as StdioTransport).nextRequestId(),
            method = ACPMethods.SESSION_NEW,
            params = params
        )

        return try {
            val response = transport.sendRequest(request, timeoutMs = 30000)
            if (response.isError) {
                log.error("Failed to create session: ${response.error?.message}")
                return null
            }

            val result = gson.fromJson(response.result, SessionInfo::class.java)
            sessions[result.sessionId] = ACPSession(result.sessionId, mode, root)
            currentSessionId = result.sessionId

            log.info("Created session: ${result.sessionId} (mode: $mode)")
            result

        } catch (e: Exception) {
            log.error("Error creating session", e)
            null
        }
    }

    /**
     * Send a prompt to the current session
     *
     * @param message User message text
     * @param sessionId Session ID (uses current if not specified)
     * @param onUpdate Callback for streaming updates
     * @param onComplete Callback when turn completes
     * @param onError Callback on error
     */
    fun sendPrompt(
        message: String,
        sessionId: String? = null,
        onUpdate: (SessionUpdate) -> Unit = {},
        onComplete: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val transport = transportRef.get()
        if (transport == null || !isRunning()) {
            onError("Agent not running")
            return
        }

        val sid = sessionId ?: currentSessionId
        if (sid == null) {
            onError("No active session")
            return
        }

        val session = sessions[sid]
        if (session == null) {
            onError("Session not found: $sid")
            return
        }

        // Register temporary listener for this prompt
        session.updateListener = { update ->
            onUpdate(update)
            if (update is SessionUpdate.TurnComplete) {
                onComplete()
            } else if (update is SessionUpdate.ErrorUpdate) {
                onError(update.error)
            }
        }

        val content = listOf(
            JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", message)
            }
        )

        val params = JsonObject().apply {
            addProperty("sessionId", sid)
            add("content", gson.toJsonTree(content))
        }

        val request = ACPRequest(
            id = (transport as StdioTransport).nextRequestId(),
            method = ACPMethods.SESSION_PROMPT,
            params = params
        )

        try {
            // Send request (response comes via session/update notifications)
            val response = transport.sendRequest(request, timeoutMs = 300000) // 5 min timeout for agent work

            if (response.isError) {
                onError(response.error?.message ?: "Unknown error")
            }
        } catch (e: TimeoutException) {
            onError("Request timed out")
        } catch (e: Exception) {
            log.error("Error sending prompt", e)
            onError(e.message ?: "Unknown error")
        }
    }

    /**
     * Cancel the current operation in a session
     */
    fun cancelSession(sessionId: String? = null) {
        val transport = transportRef.get() ?: return
        val sid = sessionId ?: currentSessionId ?: return

        val params = JsonObject().apply {
            addProperty("sessionId", sid)
        }

        val request = ACPRequest(
            id = (transport as StdioTransport).nextRequestId(),
            method = ACPMethods.SESSION_CANCEL,
            params = params
        )

        try {
            transport.sendRequest(request, timeoutMs = 5000)
            log.info("Cancelled session: $sid")
        } catch (e: Exception) {
            log.warn("Error cancelling session", e)
        }
    }

    /**
     * Change session mode
     */
    fun setSessionMode(mode: String, sessionId: String? = null): Boolean {
        val transport = transportRef.get() ?: return false
        val sid = sessionId ?: currentSessionId ?: return false

        val params = JsonObject().apply {
            addProperty("sessionId", sid)
            addProperty("mode", mode)
        }

        val request = ACPRequest(
            id = (transport as StdioTransport).nextRequestId(),
            method = ACPMethods.SESSION_SET_MODE,
            params = params
        )

        return try {
            val response = transport.sendRequest(request, timeoutMs = 10000)
            if (response.isSuccess) {
                sessions[sid]?.mode = mode
                true
            } else {
                log.warn("Failed to set mode: ${response.error?.message}")
                false
            }
        } catch (e: Exception) {
            log.error("Error setting session mode", e)
            false
        }
    }

    /**
     * Get current session ID
     */
    fun getCurrentSessionId(): String? = currentSessionId

    // ═══════════════════════════════════════════════════════════════
    // Public API - Handler Registration
    // ═══════════════════════════════════════════════════════════════

    /**
     * Register a state change listener
     */
    fun addStateListener(listener: (ACPClientState) -> Unit) {
        stateListeners.add(listener)
    }

    /**
     * Remove a state change listener
     */
    fun removeStateListener(listener: (ACPClientState) -> Unit) {
        stateListeners.remove(listener)
    }

    /**
     * Register a session update listener
     */
    fun addSessionUpdateListener(listener: (String, SessionUpdate) -> Unit) {
        sessionUpdateListeners.add(listener)
    }

    /**
     * Register permission request handler (for HITL)
     */
    fun setPermissionRequestHandler(handler: (PermissionRequestParams) -> PermissionResponse) {
        permissionRequestHandler.set(handler)
    }

    /**
     * Register filesystem handler
     */
    fun setFileSystemHandler(handler: ACPFileSystemHandler) {
        fileSystemHandler.set(handler)
    }

    /**
     * Register terminal handler
     */
    fun setTerminalHandler(handler: ACPTerminalHandler) {
        terminalHandler.set(handler)
    }

    // ═══════════════════════════════════════════════════════════════
    // Private - Process Management
    // ═══════════════════════════════════════════════════════════════

    private fun startProcess(agentPath: String): Process {
        val command = mutableListOf(agentPath)

        // Add configured arguments
        val args = settings.acpAgentArgs
        if (args.isNotEmpty()) {
            command.addAll(args)
        }

        val processBuilder = ProcessBuilder(command)

        // Set working directory to project root
        val workDir = project.basePath?.let { File(it) }
        if (workDir?.exists() == true) {
            processBuilder.directory(workDir)
        }

        // Set environment variables
        val env = processBuilder.environment()

        // OpenRouter credentials
        if (settings.openrouterApiKey.isNotBlank()) {
            env["OPENROUTER_API_KEY"] = settings.openrouterApiKey
            env["OPENROUTER_MODEL"] = settings.openrouterModel
            env["OPENROUTER_BASE_URL"] = settings.openrouterBaseUrl

            // Also set OpenAI-compatible env vars for agents using OpenAI SDK
            env["OPENAI_API_KEY"] = settings.openrouterApiKey
            env["OPENAI_API_BASE"] = settings.openrouterBaseUrl
        }

        // Fallback to direct providers if configured
        if (settings.enableDirectProviders) {
            if (settings.geminiApiKey.isNotBlank()) {
                env["GEMINI_API_KEY"] = settings.geminiApiKey
            }
            if (settings.openaiApiKey.isNotBlank()) {
                env["OPENAI_API_KEY"] = settings.openaiApiKey
            }
        }

        // Workspace root
        env["ACP_WORKSPACE_ROOT"] = settings.workspaceRoot.takeIf { it.isNotBlank() } ?: project.basePath ?: ""

        // Redirect stderr to stdout for unified logging
        processBuilder.redirectErrorStream(true)

        log.info("Starting ACP agent: ${command.joinToString(" ")}")
        return processBuilder.start()
    }

    private fun initialize(transport: StdioTransport): InitializeResult? {
        val params = InitializeParams()
        val paramsJson = gson.toJsonTree(params).asJsonObject

        val request = ACPRequest(
            id = transport.nextRequestId(),
            method = ACPMethods.INITIALIZE,
            params = paramsJson
        )

        return try {
            val response = transport.sendRequest(request, timeoutMs = 30000)
            if (response.isError) {
                log.error("Initialize failed: ${response.error?.message}")
                return null
            }

            gson.fromJson(response.result, InitializeResult::class.java)
        } catch (e: TimeoutException) {
            log.error("Initialize timeout")
            null
        } catch (e: Exception) {
            log.error("Initialize error", e)
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Private - Client Method Handlers
    // ═══════════════════════════════════════════════════════════════

    private fun registerClientMethods(transport: StdioTransport) {
        // Session update notifications
        transport.onNotification(ACPMethods.SESSION_UPDATE) { notification ->
            handleSessionUpdate(notification)
        }

        // Permission requests
        transport.onRequest(ACPMethods.SESSION_REQUEST_PERMISSION) { request ->
            handlePermissionRequest(request)
        }

        // Filesystem operations
        transport.onRequest(ACPMethods.FS_READ_TEXT_FILE) { request ->
            handleFsReadTextFile(request)
        }

        transport.onRequest(ACPMethods.FS_WRITE_TEXT_FILE) { request ->
            handleFsWriteTextFile(request)
        }

        transport.onRequest(ACPMethods.FS_LIST_DIRECTORY) { request ->
            handleFsListDirectory(request)
        }

        // Terminal operations
        transport.onRequest(ACPMethods.TERMINAL_CREATE) { request ->
            handleTerminalCreate(request)
        }

        transport.onRequest(ACPMethods.TERMINAL_KILL) { request ->
            handleTerminalKill(request)
        }

        // Terminal output (notification from us to agent)
        // This is handled in the opposite direction via sendTerminalOutput()
    }

    private fun handleSessionUpdate(notification: ACPNotification) {
        val params = notification.params ?: return
        val sessionId = params.get("sessionId")?.asString ?: return
        val updates = params.getAsJsonArray("updates") ?: return

        val session = sessions[sessionId]

        for (updateElement in updates) {
            val updateObj = updateElement.asJsonObject
            val update = parseSessionUpdate(updateObj)

            if (update != null) {
                // Notify session-specific listener
                session?.updateListener?.invoke(update)

                // Notify global listeners
                sessionUpdateListeners.forEach { listener ->
                    try {
                        listener(sessionId, update)
                    } catch (e: Exception) {
                        log.error("Error in session update listener", e)
                    }
                }
            }
        }
    }

    private fun parseSessionUpdate(obj: JsonObject): SessionUpdate? {
        val type = obj.get("type")?.asString ?: return null

        return when (type) {
            "AgentMessageChunk" -> {
                val content = obj.getAsJsonArray("content")?.map { element ->
                    gson.fromJson(element, ContentBlock::class.java)
                } ?: emptyList()
                SessionUpdate.AgentMessageChunk(content)
            }
            "ToolCallUpdate" -> {
                SessionUpdate.ToolCallUpdate(
                    tool = obj.get("tool")?.asString ?: "",
                    status = obj.get("status")?.asString ?: "",
                    result = obj.get("result")?.asString,
                    error = obj.get("error")?.asString
                )
            }
            "PlanUpdate" -> {
                val plan = gson.fromJson(obj.get("plan"), AgentPlan::class.java)
                SessionUpdate.PlanUpdate(plan)
            }
            "TodoUpdate" -> {
                val todos = obj.getAsJsonArray("todos")?.map { element ->
                    gson.fromJson(element, TodoItem::class.java)
                } ?: emptyList()
                SessionUpdate.TodoUpdate(todos)
            }
            "ModeChange" -> {
                SessionUpdate.ModeChange(obj.get("mode")?.asString ?: "")
            }
            "TurnComplete" -> {
                SessionUpdate.TurnComplete
            }
            "ErrorUpdate" -> {
                SessionUpdate.ErrorUpdate(
                    error = obj.get("error")?.asString ?: "Unknown error",
                    recoverable = obj.get("recoverable")?.asBoolean ?: false
                )
            }
            else -> {
                log.debug("Unknown update type: $type")
                null
            }
        }
    }

    private fun handlePermissionRequest(request: ACPRequest): ACPResponse {
        val params = request.params?.let {
            gson.fromJson(it, PermissionRequestParams::class.java)
        } ?: return ACPResponse(
            id = request.id,
            error = ACPError(ACPError.INVALID_PARAMS, "Missing params")
        )

        val handler = permissionRequestHandler.get()
        if (handler == null) {
            // No handler - auto-deny
            return ACPResponse(
                id = request.id,
                result = gson.toJsonTree(PermissionResponse(granted = false, feedback = "No permission handler"))
            )
        }

        // Check auto-approve settings
        val autoApprove = when (params.permission) {
            "read_file" -> settings.autoApproveRead
            "write_file", "edit_file" -> settings.autoApproveWrite
            "execute_command" -> settings.autoApproveShell
            else -> false
        }

        val response = if (autoApprove) {
            PermissionResponse(granted = true)
        } else {
            handler(params)
        }

        return ACPResponse(
            id = request.id,
            result = gson.toJsonTree(response)
        )
    }

    private fun handleFsReadTextFile(request: ACPRequest): ACPResponse {
        val params = request.params?.let {
            gson.fromJson(it, FsReadTextFileParams::class.java)
        } ?: return ACPResponse(
            id = request.id,
            error = ACPError(ACPError.INVALID_PARAMS, "Missing params")
        )

        val handler = fileSystemHandler.get()
        return if (handler != null) {
            try {
                val content = handler.readTextFile(params.path)
                ACPResponse(
                    id = request.id,
                    result = gson.toJsonTree(FsReadTextFileResult(content))
                )
            } catch (e: Exception) {
                ACPResponse(
                    id = request.id,
                    error = ACPError(ACPError.INTERNAL_ERROR, e.message ?: "Read failed")
                )
            }
        } else {
            // Default implementation using File API
            try {
                val file = File(params.path)
                val content = file.readText()
                ACPResponse(
                    id = request.id,
                    result = gson.toJsonTree(FsReadTextFileResult(content))
                )
            } catch (e: Exception) {
                ACPResponse(
                    id = request.id,
                    error = ACPError(ACPError.INTERNAL_ERROR, e.message ?: "Read failed")
                )
            }
        }
    }

    private fun handleFsWriteTextFile(request: ACPRequest): ACPResponse {
        val params = request.params?.let {
            gson.fromJson(it, FsWriteTextFileParams::class.java)
        } ?: return ACPResponse(
            id = request.id,
            error = ACPError(ACPError.INVALID_PARAMS, "Missing params")
        )

        val handler = fileSystemHandler.get()
        return if (handler != null) {
            try {
                val success = handler.writeTextFile(params.path, params.content)
                ACPResponse(
                    id = request.id,
                    result = gson.toJsonTree(FsWriteTextFileResult(success))
                )
            } catch (e: Exception) {
                ACPResponse(
                    id = request.id,
                    result = gson.toJsonTree(FsWriteTextFileResult(false, e.message))
                )
            }
        } else {
            // Default implementation
            try {
                val file = File(params.path)
                file.parentFile?.mkdirs()
                file.writeText(params.content)
                ACPResponse(
                    id = request.id,
                    result = gson.toJsonTree(FsWriteTextFileResult(true))
                )
            } catch (e: Exception) {
                ACPResponse(
                    id = request.id,
                    result = gson.toJsonTree(FsWriteTextFileResult(false, e.message))
                )
            }
        }
    }

    private fun handleFsListDirectory(request: ACPRequest): ACPResponse {
        val params = request.params?.let {
            gson.fromJson(it, FsListDirectoryParams::class.java)
        } ?: return ACPResponse(
            id = request.id,
            error = ACPError(ACPError.INVALID_PARAMS, "Missing params")
        )

        val handler = fileSystemHandler.get()
        return if (handler != null) {
            try {
                val entries = handler.listDirectory(params.path)
                ACPResponse(
                    id = request.id,
                    result = gson.toJsonTree(FsListDirectoryResult(entries))
                )
            } catch (e: Exception) {
                ACPResponse(
                    id = request.id,
                    error = ACPError(ACPError.INTERNAL_ERROR, e.message ?: "List failed")
                )
            }
        } else {
            // Default implementation
            try {
                val dir = File(params.path)
                val entries = dir.listFiles()?.map { file ->
                    DirectoryEntry(
                        name = file.name,
                        type = if (file.isDirectory) "directory" else "file",
                        size = if (file.isFile) file.length() else null
                    )
                } ?: emptyList()
                ACPResponse(
                    id = request.id,
                    result = gson.toJsonTree(FsListDirectoryResult(entries))
                )
            } catch (e: Exception) {
                ACPResponse(
                    id = request.id,
                    error = ACPError(ACPError.INTERNAL_ERROR, e.message ?: "List failed")
                )
            }
        }
    }

    private fun handleTerminalCreate(request: ACPRequest): ACPResponse {
        val params = request.params?.let {
            gson.fromJson(it, TerminalCreateParams::class.java)
        } ?: return ACPResponse(
            id = request.id,
            error = ACPError(ACPError.INVALID_PARAMS, "Missing params")
        )

        val handler = terminalHandler.get()
        return if (handler != null) {
            try {
                val terminalId = handler.createTerminal(params.command, params.cwd, params.env)
                ACPResponse(
                    id = request.id,
                    result = gson.toJsonTree(TerminalCreateResult(terminalId))
                )
            } catch (e: Exception) {
                ACPResponse(
                    id = request.id,
                    error = ACPError(ACPError.INTERNAL_ERROR, e.message ?: "Terminal create failed")
                )
            }
        } else {
            ACPResponse(
                id = request.id,
                error = ACPError(ACPError.METHOD_NOT_FOUND, "Terminal handler not registered")
            )
        }
    }

    private fun handleTerminalKill(request: ACPRequest): ACPResponse {
        val params = request.params?.let {
            gson.fromJson(it, TerminalKillParams::class.java)
        } ?: return ACPResponse(
            id = request.id,
            error = ACPError(ACPError.INVALID_PARAMS, "Missing params")
        )

        val handler = terminalHandler.get()
        return if (handler != null) {
            handler.killTerminal(params.terminalId)
            ACPResponse(
                id = request.id,
                result = JsonObject()
            )
        } else {
            ACPResponse(
                id = request.id,
                error = ACPError(ACPError.METHOD_NOT_FOUND, "Terminal handler not registered")
            )
        }
    }

    /**
     * Send terminal output to the agent
     */
    fun sendTerminalOutput(terminalId: String, output: String, isStderr: Boolean = false) {
        val transport = transportRef.get() ?: return

        val params = JsonObject().apply {
            addProperty("terminalId", terminalId)
            addProperty("output", output)
            addProperty("isStderr", isStderr)
        }

        transport.sendNotification(ACPNotification(
            method = ACPMethods.TERMINAL_OUTPUT,
            params = params
        ))
    }

    /**
     * Send terminal exit notification to the agent
     */
    fun sendTerminalExit(terminalId: String, exitCode: Int) {
        val transport = transportRef.get() ?: return

        val params = JsonObject().apply {
            addProperty("terminalId", terminalId)
            addProperty("exitCode", exitCode)
        }

        transport.sendNotification(ACPNotification(
            method = ACPMethods.TERMINAL_EXIT,
            params = params
        ))
    }

    // ═══════════════════════════════════════════════════════════════
    // Private - Health Monitoring
    // ═══════════════════════════════════════════════════════════════

    private fun startHealthMonitor() {
        val intervalSeconds = settings.acpHealthCheckInterval.toLong()
        if (intervalSeconds <= 0) {
            return
        }

        healthCheckTask = scheduler.scheduleAtFixedRate({
            checkHealth()
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS)
    }

    private fun stopHealthMonitor() {
        healthCheckTask?.cancel(false)
        healthCheckTask = null
    }

    private fun checkHealth() {
        val process = processRef.get()
        val transport = transportRef.get()

        if (process == null || !process.isAlive || transport == null || !transport.isConnected()) {
            log.warn("ACP agent health check failed - process not running")
            handleCrash()
        }
    }

    private fun handleCrash() {
        updateState(ACPClientState.ERROR)

        if (restartAttempts < maxRestartAttempts && settings.acpAutoRestart) {
            restartAttempts++
            log.info("Attempting to restart ACP agent (attempt $restartAttempts/$maxRestartAttempts)")

            scheduler.schedule({
                if (start()) {
                    log.info("ACP agent restarted successfully")
                } else {
                    log.error("Failed to restart ACP agent")
                }
            }, 2, TimeUnit.SECONDS)
        } else {
            log.error("ACP agent crashed and max restart attempts reached")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Private - State Management
    // ═══════════════════════════════════════════════════════════════

    private fun updateState(newState: ACPClientState) {
        val oldState = state.getAndSet(newState)
        if (oldState != newState) {
            log.info("ACP client state: $oldState -> $newState")
            stateListeners.forEach { listener ->
                try {
                    listener(newState)
                } catch (e: Exception) {
                    log.error("Error in state listener", e)
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Disposable
    // ═══════════════════════════════════════════════════════════════

    override fun dispose() {
        stop()
        scheduler.shutdown()
        stateListeners.clear()
        sessionUpdateListeners.clear()
    }

    companion object {
        fun getInstance(project: Project): ACPClientService {
            return project.getService(ACPClientService::class.java)
        }
    }
}

/**
 * ACP Client State
 */
enum class ACPClientState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

/**
 * Internal session state
 */
class ACPSession(
    val sessionId: String,
    var mode: String,
    val workspaceRoot: String
) {
    var updateListener: ((SessionUpdate) -> Unit)? = null
}

/**
 * Interface for filesystem operations handler
 */
interface ACPFileSystemHandler {
    fun readTextFile(path: String): String
    fun writeTextFile(path: String, content: String): Boolean
    fun listDirectory(path: String): List<DirectoryEntry>
}

/**
 * Interface for terminal operations handler
 */
interface ACPTerminalHandler {
    fun createTerminal(command: String, cwd: String?, env: Map<String, String>?): String
    fun killTerminal(terminalId: String)
}
