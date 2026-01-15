package com.hdsp.pycharm_agent.services

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.hdsp.pycharm_agent.settings.AgentSettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Client for communicating with HDSP Agent Server
 *
 * Features:
 * - Rate limit handling with automatic API key rotation
 * - SSE streaming for chat and agent endpoints
 * - LangChain Agent with HITL (Human-in-the-Loop) support
 * - Legacy plan-execute pattern support
 */
@Service(Service.Level.PROJECT)
class BackendClient(private val project: Project) {

    private val logger = Logger.getInstance(BackendClient::class.java)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val JSON_MEDIA_TYPE = "application/json".toMediaType()

    private fun getBaseUrl(): String {
        return AgentSettings.getInstance().backendUrl
    }

    // ==========================================================================
    // LLM Config Builder (jupyter_extension pattern)
    // ==========================================================================

    /**
     * Build LLM config for request
     *
     * Financial Security: Only sends SINGLE key per request
     * Key rotation handled by client, not server
     */
    private fun buildLlmConfig(): Map<String, Any?> {
        val settings = AgentSettings.getInstance()
        return mapOf(
            "provider" to settings.provider,
            "gemini" to when (settings.provider) {
                "gemini" -> mapOf(
                    "apiKey" to settings.getCurrentGeminiKey(),
                    "model" to settings.geminiModel
                )
                else -> null
            },
            "openai" to when (settings.provider) {
                "openai" -> mapOf(
                    "apiKey" to settings.openaiApiKey,
                    "model" to settings.openaiModel
                )
                else -> null
            },
            "vllm" to when (settings.provider) {
                "vllm" -> mapOf(
                    "endpoint" to settings.vllmEndpoint,
                    "apiKey" to settings.vllmApiKey,
                    "model" to settings.vllmModel
                )
                else -> null
            },
            "workspaceRoot" to settings.workspaceRoot.ifBlank { project.basePath },
            "systemPrompt" to settings.systemPrompt.takeIf { it.isNotBlank() },
            "autoApprove" to settings.autoApprove
        )
    }

    // ==========================================================================
    // Rate Limit Handling with Key Rotation (jupyter_extension pattern)
    // ==========================================================================

    /**
     * Execute request with automatic API key rotation on 429
     *
     * @param maxRetries Maximum number of retry attempts
     * @param onKeyRotation Callback when key rotates (for UI update)
     * @param block Request execution block
     * @return Result of the block execution
     * @throws AllKeysRateLimitedException if all keys are rate limited
     * @throws IOException on other failures
     */
    private inline fun <T> executeWithKeyRotation(
        maxRetries: Int = 10,
        onKeyRotation: (keyIndex: Int, totalKeys: Int) -> Unit = { _, _ -> },
        block: () -> T
    ): T {
        val settings = AgentSettings.getInstance()
        var lastException: Exception? = null

        repeat(maxRetries) { attempt ->
            try {
                val result = block()
                settings.resetKeyRotation() // Success: reset state
                return result
            } catch (e: RateLimitException) {
                logger.warn("Rate limit hit on attempt $attempt: ${e.message}")

                // Rate limited - rotate to next key
                val validKeyCount = settings.getValidKeyCount()
                if (validKeyCount > 0) {
                    settings.markKeyAsRateLimited(attempt % validKeyCount)
                }
                val nextKey = settings.getNextValidKey()

                if (nextKey == null) {
                    throw AllKeysRateLimitedException(
                        "All API keys are rate limited. Please wait and try again."
                    )
                }

                onKeyRotation(
                    settings.getCurrentKeyIndex(),
                    settings.getValidKeyCount()
                )
                lastException = e
            }
        }

        throw lastException ?: IOException("Max retries exceeded")
    }

    /**
     * Check if response indicates rate limit error
     */
    private fun isRateLimitError(response: Response): Boolean {
        if (response.code == 429) return true

        // Check response body for rate limit indicators
        // Note: This consumes the body, so only call when needed
        val bodyString = try {
            response.peekBody(1024).string()
        } catch (e: Exception) {
            ""
        }

        return bodyString.contains("429") ||
               bodyString.contains("rate limit", ignoreCase = true) ||
               bodyString.contains("RESOURCE_EXHAUSTED", ignoreCase = true)
    }

    /**
     * Check error message for rate limit indicators
     */
    private fun isRateLimitMessage(message: String): Boolean {
        return message.contains("429") ||
               message.contains("rate limit", ignoreCase = true) ||
               message.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
               message.contains("quota", ignoreCase = true)
    }

    // ==========================================================================
    // Chat API (Simple Q&A) - /chat/stream
    // ==========================================================================

    /**
     * Stream chat response via SSE (backward-compatible single-callback version)
     *
     * Simple Q&A without agent tools
     *
     * @param message User message
     * @param onChunk Callback for each content chunk
     */
    fun streamChatSync(
        message: String,
        onChunk: (String) -> Unit
    ) {
        streamChatSync(
            message = message,
            conversationId = null,
            onMetadata = {},
            onKeyRotation = { _, _ -> },
            onChunk = onChunk
        )
    }

    /**
     * Stream chat response via SSE (full version)
     *
     * Simple Q&A without agent tools
     *
     * @param message User message
     * @param conversationId Optional conversation ID for context
     * @param onMetadata Callback for chat metadata (conversation ID, etc.)
     * @param onKeyRotation Callback when API key rotates
     * @param onChunk Callback for each content chunk (last for trailing lambda support)
     */
    fun streamChatSync(
        message: String,
        conversationId: String?,
        onMetadata: (ChatMetadata) -> Unit,
        onKeyRotation: (keyIndex: Int, totalKeys: Int) -> Unit,
        onChunk: (String) -> Unit
    ) {
        executeWithKeyRotation(onKeyRotation = onKeyRotation) {
            streamChatInternal(message, conversationId, onChunk, onMetadata)
        }
    }

    private fun streamChatInternal(
        message: String,
        conversationId: String?,
        onChunk: (String) -> Unit,
        onMetadata: (ChatMetadata) -> Unit
    ) {
        val requestBody = gson.toJson(mapOf(
            "message" to message,
            "conversationId" to conversationId,
            "llmConfig" to buildLlmConfig()
        )).toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url("${getBaseUrl()}/chat/stream")
            .post(requestBody)
            .build()

        val latch = CountDownLatch(1)
        val errorRef = AtomicReference<Throwable?>(null)

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                try {
                    val event = gson.fromJson(data, JsonObject::class.java)

                    // Error handling
                    event.get("error")?.asString?.let { error ->
                        if (isRateLimitMessage(error)) {
                            errorRef.set(RateLimitException(error))
                        } else {
                            errorRef.set(IOException(error))
                        }
                        eventSource.cancel()
                        latch.countDown()
                        return
                    }

                    // Content streaming
                    event.get("content")?.asString?.let { content ->
                        if (content.isNotEmpty()) onChunk(content)
                    }

                    // Completion
                    if (event.get("done")?.asBoolean == true) {
                        val metadata = ChatMetadata(
                            conversationId = event.get("conversationId")?.asString,
                            messageId = event.get("messageId")?.asString,
                            provider = event.get("provider")?.asString,
                            model = event.get("model")?.asString
                        )
                        onMetadata(metadata)
                        eventSource.cancel()
                        latch.countDown()
                    }
                } catch (e: Exception) {
                    logger.debug("Parse error in chat stream: ${e.message}")
                    // Continue on parse errors
                }
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?
            ) {
                val error = if (response != null && isRateLimitError(response)) {
                    RateLimitException("Rate limit exceeded")
                } else {
                    t ?: IOException("SSE connection failed: ${response?.code}")
                }
                errorRef.set(error)
                latch.countDown()
            }

            override fun onClosed(eventSource: EventSource) {
                latch.countDown()
            }
        }

        EventSources.createFactory(client)
            .newEventSource(request, listener)

        latch.await(120, TimeUnit.SECONDS)
        errorRef.get()?.let { throw it }
    }

    // ==========================================================================
    // LangChain Agent API (HITL, Tools) - /agent/langchain/stream
    // ==========================================================================

    /**
     * Stream LangChain agent response via SSE
     *
     * Full agent with:
     * - Tool execution (jupyter_cell, read_file, write_file, shell, etc.)
     * - Human-in-the-Loop (HITL) interrupts
     * - Todo tracking
     * - Debug status updates
     *
     * @param request User request
     * @param threadId Optional thread ID for resuming conversations
     * @param notebookContext Optional notebook context for Jupyter integration
     * @param onChunk Callback for content chunks
     * @param onDebug Callback for debug status updates
     * @param onInterrupt Callback for HITL interrupts (requires user decision)
     * @param onTodos Callback for todo list updates
     * @param onToolCall Callback for tool call events
     * @param onComplete Callback when agent completes (provides thread ID)
     * @param onKeyRotation Callback when API key rotates
     */
    fun streamAgentSync(
        request: String,
        threadId: String? = null,
        notebookContext: NotebookContext? = null,
        onChunk: (String) -> Unit,
        onDebug: (String) -> Unit = {},
        onInterrupt: (AgentInterrupt) -> Unit = {},
        onTodos: (List<TodoItem>) -> Unit = {},
        onToolCall: (ToolCallEvent) -> Unit = {},
        onComplete: (String) -> Unit = {},
        onKeyRotation: (keyIndex: Int, totalKeys: Int) -> Unit = { _, _ -> }
    ) {
        executeWithKeyRotation(onKeyRotation = onKeyRotation) {
            streamAgentInternal(
                request, threadId, notebookContext,
                onChunk, onDebug, onInterrupt, onTodos, onToolCall, onComplete
            )
        }
    }

    private fun streamAgentInternal(
        request: String,
        threadId: String?,
        notebookContext: NotebookContext?,
        onChunk: (String) -> Unit,
        onDebug: (String) -> Unit,
        onInterrupt: (AgentInterrupt) -> Unit,
        onTodos: (List<TodoItem>) -> Unit,
        onToolCall: (ToolCallEvent) -> Unit,
        onComplete: (String) -> Unit
    ) {
        val settings = AgentSettings.getInstance()

        val requestBody = gson.toJson(mapOf(
            "request" to request,
            "threadId" to threadId,
            "notebookContext" to notebookContext?.let {
                mapOf(
                    "notebook_path" to it.notebookPath,
                    "cell_count" to it.cellCount,
                    "imported_libraries" to it.importedLibraries,
                    "defined_variables" to it.definedVariables,
                    "recent_cells" to it.recentCells
                )
            },
            "llmConfig" to buildLlmConfig(),
            "workspaceRoot" to settings.workspaceRoot.ifBlank { project.basePath }
        )).toRequestBody(JSON_MEDIA_TYPE)

        val httpRequest = Request.Builder()
            .url("${getBaseUrl()}/agent/langchain/stream")
            .post(requestBody)
            .build()

        val latch = CountDownLatch(1)
        val errorRef = AtomicReference<Throwable?>(null)

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                val eventType = type ?: ""

                try {
                    val event = gson.fromJson(data, JsonObject::class.java)

                    when (eventType) {
                        "todos" -> {
                            // Todo list update
                            event.getAsJsonArray("todos")?.let { todosArray ->
                                val todos = todosArray.map { todoElement ->
                                    val todoObj = todoElement.asJsonObject
                                    TodoItem(
                                        content = todoObj.get("content")?.asString ?: "",
                                        status = todoObj.get("status")?.asString ?: "pending"
                                    )
                                }
                                onTodos(todos)
                            }
                        }

                        "debug_clear" -> {
                            // Signal to clear debug panel
                            onDebug("")
                        }

                        "tool_call" -> {
                            // Tool execution event
                            onToolCall(ToolCallEvent(
                                tool = event.get("tool")?.asString ?: "",
                                code = event.get("code")?.asString,
                                content = event.get("content")?.asString,
                                command = event.get("command")?.asString,
                                path = event.get("path")?.asString,
                                timeout = event.get("timeout")?.asInt
                            ))
                        }

                        "complete" -> {
                            // Agent completed
                            event.get("thread_id")?.asString?.let { completedThreadId ->
                                onComplete(completedThreadId)
                            }
                            eventSource.cancel()
                            latch.countDown()
                            return
                        }

                        else -> {
                            // Handle non-typed events

                            // Error check
                            event.get("error")?.asString?.let { error ->
                                onDebug("Error: $error")
                                if (isRateLimitMessage(error)) {
                                    errorRef.set(RateLimitException(error))
                                } else {
                                    errorRef.set(IOException(error))
                                }
                                eventSource.cancel()
                                latch.countDown()
                                return
                            }

                            // Debug status update
                            event.get("status")?.asString?.let { status ->
                                onDebug(status)
                            }

                            // HITL Interrupt - requires user decision
                            if (event.has("thread_id") && event.has("action")) {
                                @Suppress("UNCHECKED_CAST")
                                val args = event.get("args")?.let {
                                    try {
                                        gson.fromJson<Map<String, Any>>(
                                            it,
                                            object : TypeToken<Map<String, Any>>() {}.type
                                        )
                                    } catch (e: Exception) {
                                        emptyMap()
                                    }
                                } ?: emptyMap()

                                onInterrupt(AgentInterrupt(
                                    threadId = event.get("thread_id").asString,
                                    action = event.get("action").asString,
                                    args = args,
                                    description = event.get("description")?.asString ?: ""
                                ))
                                // Note: We don't cancel here - wait for resume
                                eventSource.cancel()
                                latch.countDown()
                                return
                            }

                            // Content chunk
                            event.get("content")?.asString?.let { content ->
                                if (content.isNotEmpty()) onChunk(content)
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.debug("Parse error in agent stream: ${e.message}")
                    // Continue on parse errors
                }
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?
            ) {
                val error = if (response != null && isRateLimitError(response)) {
                    RateLimitException("Rate limit exceeded")
                } else {
                    t ?: IOException("SSE connection failed: ${response?.code}")
                }
                errorRef.set(error)
                latch.countDown()
            }

            override fun onClosed(eventSource: EventSource) {
                latch.countDown()
            }
        }

        EventSources.createFactory(client)
            .newEventSource(httpRequest, listener)

        // Agent operations may take longer
        latch.await(300, TimeUnit.SECONDS)
        errorRef.get()?.let { throw it }
    }

    // ==========================================================================
    // Resume Agent - /agent/langchain/resume
    // ==========================================================================

    /**
     * Resume interrupted agent after HITL decision
     *
     * Continue execution after user approval, edit, or rejection
     *
     * @param threadId Thread ID from the interrupt
     * @param decision User decision: approve | edit | reject
     * @param args Modified arguments (for edit decision)
     * @param feedback User feedback (for reject decision)
     * @param onChunk Callback for content chunks
     * @param onDebug Callback for debug status
     * @param onInterrupt Callback for subsequent interrupts
     * @param onTodos Callback for todo updates
     * @param onToolCall Callback for tool calls
     * @param onComplete Callback when complete
     * @param onKeyRotation Callback when API key rotates
     */
    fun resumeAgentSync(
        threadId: String,
        decision: String,
        args: Map<String, Any>? = null,
        feedback: String? = null,
        onChunk: (String) -> Unit,
        onDebug: (String) -> Unit = {},
        onInterrupt: (AgentInterrupt) -> Unit = {},
        onTodos: (List<TodoItem>) -> Unit = {},
        onToolCall: (ToolCallEvent) -> Unit = {},
        onComplete: (String) -> Unit = {},
        onKeyRotation: (keyIndex: Int, totalKeys: Int) -> Unit = { _, _ -> }
    ) {
        executeWithKeyRotation(onKeyRotation = onKeyRotation) {
            resumeAgentInternal(
                threadId, decision, args, feedback,
                onChunk, onDebug, onInterrupt, onTodos, onToolCall, onComplete
            )
        }
    }

    private fun resumeAgentInternal(
        threadId: String,
        decision: String,
        args: Map<String, Any>?,
        feedback: String?,
        onChunk: (String) -> Unit,
        onDebug: (String) -> Unit,
        onInterrupt: (AgentInterrupt) -> Unit,
        onTodos: (List<TodoItem>) -> Unit,
        onToolCall: (ToolCallEvent) -> Unit,
        onComplete: (String) -> Unit
    ) {
        val settings = AgentSettings.getInstance()

        val requestBody = gson.toJson(mapOf(
            "threadId" to threadId,
            "decisions" to listOf(mapOf(
                "type" to decision,
                "args" to args,
                "feedback" to feedback
            )),
            "llmConfig" to buildLlmConfig(),
            "workspaceRoot" to settings.workspaceRoot.ifBlank { project.basePath }
        )).toRequestBody(JSON_MEDIA_TYPE)

        val httpRequest = Request.Builder()
            .url("${getBaseUrl()}/agent/langchain/resume")
            .post(requestBody)
            .build()

        val latch = CountDownLatch(1)
        val errorRef = AtomicReference<Throwable?>(null)

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                val eventType = type ?: ""

                try {
                    val event = gson.fromJson(data, JsonObject::class.java)

                    when (eventType) {
                        "todos" -> {
                            event.getAsJsonArray("todos")?.let { todosArray ->
                                val todos = todosArray.map { todoElement ->
                                    val todoObj = todoElement.asJsonObject
                                    TodoItem(
                                        content = todoObj.get("content")?.asString ?: "",
                                        status = todoObj.get("status")?.asString ?: "pending"
                                    )
                                }
                                onTodos(todos)
                            }
                        }

                        "debug_clear" -> {
                            onDebug("")
                        }

                        "tool_call" -> {
                            onToolCall(ToolCallEvent(
                                tool = event.get("tool")?.asString ?: "",
                                code = event.get("code")?.asString,
                                content = event.get("content")?.asString,
                                command = event.get("command")?.asString,
                                path = event.get("path")?.asString,
                                timeout = event.get("timeout")?.asInt
                            ))
                        }

                        "complete" -> {
                            event.get("thread_id")?.asString?.let { completedThreadId ->
                                onComplete(completedThreadId)
                            }
                            eventSource.cancel()
                            latch.countDown()
                            return
                        }

                        else -> {
                            event.get("error")?.asString?.let { error ->
                                onDebug("Error: $error")
                                if (isRateLimitMessage(error)) {
                                    errorRef.set(RateLimitException(error))
                                } else {
                                    errorRef.set(IOException(error))
                                }
                                eventSource.cancel()
                                latch.countDown()
                                return
                            }

                            event.get("status")?.asString?.let { status ->
                                onDebug(status)
                            }

                            // Another HITL Interrupt
                            if (event.has("thread_id") && event.has("action")) {
                                @Suppress("UNCHECKED_CAST")
                                val interruptArgs = event.get("args")?.let {
                                    try {
                                        gson.fromJson<Map<String, Any>>(
                                            it,
                                            object : TypeToken<Map<String, Any>>() {}.type
                                        )
                                    } catch (e: Exception) {
                                        emptyMap()
                                    }
                                } ?: emptyMap()

                                onInterrupt(AgentInterrupt(
                                    threadId = event.get("thread_id").asString,
                                    action = event.get("action").asString,
                                    args = interruptArgs,
                                    description = event.get("description")?.asString ?: ""
                                ))
                                eventSource.cancel()
                                latch.countDown()
                                return
                            }

                            event.get("content")?.asString?.let { content ->
                                if (content.isNotEmpty()) onChunk(content)
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.debug("Parse error in resume stream: ${e.message}")
                }
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?
            ) {
                val error = if (response != null && isRateLimitError(response)) {
                    RateLimitException("Rate limit exceeded")
                } else {
                    t ?: IOException("SSE connection failed: ${response?.code}")
                }
                errorRef.set(error)
                latch.countDown()
            }

            override fun onClosed(eventSource: EventSource) {
                latch.countDown()
            }
        }

        EventSources.createFactory(client)
            .newEventSource(httpRequest, listener)

        latch.await(300, TimeUnit.SECONDS)
        errorRef.get()?.let { throw it }
    }

    // ==========================================================================
    // Legacy Agent API (Plan-Execute Pattern)
    // ==========================================================================

    /**
     * Generate execution plan - /agent/plan
     */
    fun generatePlanSync(
        request: String,
        onKeyRotation: (keyIndex: Int, totalKeys: Int) -> Unit = { _, _ -> }
    ): HdspPlanResponse {
        return executeWithKeyRotation(onKeyRotation = onKeyRotation) {
            generatePlanInternal(request)
        }
    }

    private fun generatePlanInternal(request: String): HdspPlanResponse {
        val requestBody = gson.toJson(mapOf(
            "request" to request,
            "projectContext" to mapOf(
                "projectRoot" to project.basePath,
                "openFiles" to emptyList<Any>(),
                "activeFile" to null
            ),
            "llmConfig" to buildLlmConfig()
        )).toRequestBody(JSON_MEDIA_TYPE)

        val httpRequest = Request.Builder()
            .url("${getBaseUrl()}/agent/plan")
            .post(requestBody)
            .build()

        val response = client.newCall(httpRequest).execute()

        if (isRateLimitError(response)) {
            throw RateLimitException("Rate limit exceeded during plan generation")
        }

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            throw IOException("Plan generation failed: ${response.code} - $errorBody")
        }

        val body = response.body?.string() ?: throw IOException("Empty response")
        return gson.fromJson(body, HdspPlanResponse::class.java)
    }

    /**
     * Refine code after execution error - /agent/refine
     */
    fun refineSync(
        step: HdspPlanStep,
        error: ErrorInfo,
        attempt: Int,
        previousCode: String?,
        onKeyRotation: (keyIndex: Int, totalKeys: Int) -> Unit = { _, _ -> }
    ): RefineResponse {
        return executeWithKeyRotation(onKeyRotation = onKeyRotation) {
            refineInternal(step, error, attempt, previousCode)
        }
    }

    private fun refineInternal(
        step: HdspPlanStep,
        error: ErrorInfo,
        attempt: Int,
        previousCode: String?
    ): RefineResponse {
        val requestBody = gson.toJson(mapOf(
            "step" to step,
            "error" to error,
            "attempt" to attempt,
            "previousCode" to previousCode,
            "llmConfig" to buildLlmConfig()
        )).toRequestBody(JSON_MEDIA_TYPE)

        val httpRequest = Request.Builder()
            .url("${getBaseUrl()}/agent/refine")
            .post(requestBody)
            .build()

        val response = client.newCall(httpRequest).execute()

        if (isRateLimitError(response)) {
            throw RateLimitException("Rate limit exceeded during refine")
        }

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            throw IOException("Refine failed: ${response.code} - $errorBody")
        }

        val body = response.body?.string() ?: throw IOException("Empty response")
        return gson.fromJson(body, RefineResponse::class.java)
    }

    /**
     * Determine replan strategy after error - /agent/replan
     */
    fun replanSync(
        originalPlan: HdspExecutionPlan,
        currentStepIndex: Int,
        error: ErrorInfo,
        executionHistory: List<Map<String, Any>>,
        previousAttempts: Int,
        previousCodes: List<String>,
        onKeyRotation: (keyIndex: Int, totalKeys: Int) -> Unit = { _, _ -> }
    ): ReplanResponse {
        return executeWithKeyRotation(onKeyRotation = onKeyRotation) {
            replanInternal(
                originalPlan, currentStepIndex, error,
                executionHistory, previousAttempts, previousCodes
            )
        }
    }

    private fun replanInternal(
        originalPlan: HdspExecutionPlan,
        currentStepIndex: Int,
        error: ErrorInfo,
        executionHistory: List<Map<String, Any>>,
        previousAttempts: Int,
        previousCodes: List<String>
    ): ReplanResponse {
        val requestBody = gson.toJson(mapOf(
            "originalPlan" to originalPlan,
            "currentStepIndex" to currentStepIndex,
            "error" to error,
            "executionHistory" to executionHistory,
            "previousAttempts" to previousAttempts,
            "previousCodes" to previousCodes,
            "useLlmFallback" to true
        )).toRequestBody(JSON_MEDIA_TYPE)

        val httpRequest = Request.Builder()
            .url("${getBaseUrl()}/agent/replan")
            .post(requestBody)
            .build()

        val response = client.newCall(httpRequest).execute()

        if (isRateLimitError(response)) {
            throw RateLimitException("Rate limit exceeded during replan")
        }

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            throw IOException("Replan failed: ${response.code} - $errorBody")
        }

        val body = response.body?.string() ?: throw IOException("Empty response")
        return gson.fromJson(body, ReplanResponse::class.java)
    }

    /**
     * Verify execution state after step completion - /agent/verify-state
     */
    fun verifyStateSync(
        stepIndex: Int,
        expectedChanges: Map<String, Any>,
        actualOutput: String,
        onKeyRotation: (keyIndex: Int, totalKeys: Int) -> Unit = { _, _ -> }
    ): VerifyStateResponse {
        return executeWithKeyRotation(onKeyRotation = onKeyRotation) {
            verifyStateInternal(stepIndex, expectedChanges, actualOutput)
        }
    }

    private fun verifyStateInternal(
        stepIndex: Int,
        expectedChanges: Map<String, Any>,
        actualOutput: String
    ): VerifyStateResponse {
        val requestBody = gson.toJson(mapOf(
            "stepIndex" to stepIndex,
            "expectedChanges" to expectedChanges,
            "actualOutput" to actualOutput,
            "executionResult" to mapOf<String, Any>()
        )).toRequestBody(JSON_MEDIA_TYPE)

        val httpRequest = Request.Builder()
            .url("${getBaseUrl()}/agent/verify-state")
            .post(requestBody)
            .build()

        val response = client.newCall(httpRequest).execute()

        if (isRateLimitError(response)) {
            throw RateLimitException("Rate limit exceeded during verify state")
        }

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            throw IOException("Verify state failed: ${response.code} - $errorBody")
        }

        val body = response.body?.string() ?: throw IOException("Empty response")
        return gson.fromJson(body, VerifyStateResponse::class.java)
    }

    // ==========================================================================
    // Utility Methods
    // ==========================================================================

    /**
     * Test backend connection
     */
    fun testConnectionSync(): Boolean {
        val request = Request.Builder()
            .url("${getBaseUrl()}/health")
            .get()
            .build()

        return try {
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            logger.warn("Connection test failed: ${e.message}")
            false
        }
    }

    /**
     * Get configuration from server
     */
    fun getConfigSync(): JsonObject? {
        val request = Request.Builder()
            .url("${getBaseUrl()}/config")
            .get()
            .build()

        return try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return null
                gson.fromJson(body, JsonObject::class.java)
            } else {
                null
            }
        } catch (e: Exception) {
            logger.warn("Get config failed: ${e.message}")
            null
        }
    }

    /**
     * Update configuration on server
     */
    fun updateConfigSync(config: Map<String, Any>): Boolean {
        val requestBody = gson.toJson(config).toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url("${getBaseUrl()}/config")
            .post(requestBody)
            .build()

        return try {
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            logger.warn("Update config failed: ${e.message}")
            false
        }
    }
}

// ==========================================================================
// Data Classes - Chat Metadata
// ==========================================================================

/**
 * Metadata returned after chat completion
 */
data class ChatMetadata(
    val conversationId: String? = null,
    val messageId: String? = null,
    val provider: String? = null,
    val model: String? = null
)

// ==========================================================================
// Data Classes - Notebook Context (for Jupyter integration)
// ==========================================================================

/**
 * Notebook context for Jupyter integration
 */
data class NotebookContext(
    val notebookPath: String?,
    val cellCount: Int = 0,
    val importedLibraries: List<String> = emptyList(),
    val definedVariables: List<String> = emptyList(),
    val recentCells: List<Map<String, String>> = emptyList()
)

// ==========================================================================
// Data Classes - Agent Interrupt (HITL)
// ==========================================================================

/**
 * Agent interrupt requiring user decision
 */
data class AgentInterrupt(
    val threadId: String,
    val action: String,
    val args: Map<String, Any>,
    val description: String
)

// ==========================================================================
// Data Classes - Todo Item
// ==========================================================================

/**
 * Todo item from agent execution
 */
data class TodoItem(
    val content: String,
    val status: String  // pending | in_progress | completed
)

// ==========================================================================
// Data Classes - Tool Call Event
// ==========================================================================

/**
 * Tool call event from agent execution
 */
data class ToolCallEvent(
    val tool: String,
    val code: String? = null,
    val content: String? = null,
    val command: String? = null,
    val path: String? = null,
    val timeout: Int? = null
)

// ==========================================================================
// Exceptions
// ==========================================================================

/**
 * Exception thrown when rate limit is hit
 */
class RateLimitException(message: String) : IOException(message)

/**
 * Exception thrown when all API keys are rate limited
 */
class AllKeysRateLimitedException(message: String) : IOException(message)

// ==========================================================================
// HDSP API Response Models
// ==========================================================================

data class HdspPlanResponse(
    val plan: HdspExecutionPlan,
    val reasoning: String
)

data class HdspExecutionPlan(
    val goal: String,
    val totalSteps: Int,
    val steps: List<HdspPlanStep>
)

data class HdspPlanStep(
    val stepNumber: Int,
    val description: String,
    val toolCalls: List<ToolCall>,
    val expectedOutput: String?
)

data class ToolCall(
    val tool: String,
    val parameters: Map<String, Any>
)

data class ErrorInfo(
    val type: String = "runtime",
    val message: String,
    val traceback: List<String> = emptyList()
)

data class RefineResponse(
    val toolCalls: List<ToolCall>,
    val reasoning: String
)

data class ReplanResponse(
    val decision: String,  // refine, insert_steps, replace_step, replan_remaining, abort
    val analysis: Map<String, Any>?,
    val reasoning: String,
    val changes: Map<String, Any>?,
    val usedLlm: Boolean,
    val confidence: Double
)

data class VerifyStateResponse(
    val verified: Boolean,
    val discrepancies: List<String>,
    val confidence: Double
)

// For backward compatibility - will be removed
data class DiffResult(
    val filePath: String,
    val hunks: List<DiffHunk>,
    val unifiedDiff: String,
    val previewContent: String
)

data class DiffHunk(
    val startLine: Int,
    val endLine: Int,
    val originalContent: String,
    val newContent: String,
    val changeType: String
)
