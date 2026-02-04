package com.hdsp.pycharm_agent.openrouter

import com.hdsp.pycharm_agent.settings.AgentSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.util.concurrent.atomic.AtomicReference

/**
 * OpenRouter Service
 *
 * Project-level service for managing OpenRouter API access.
 * Provides caching, connection state management, and convenience methods
 * for LLM interactions.
 */
@Service(Service.Level.PROJECT)
class OpenRouterService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(OpenRouterService::class.java)
    private val clientRef = AtomicReference<OpenRouterClient?>(null)
    private val connectionState = AtomicReference(ConnectionState.DISCONNECTED)

    // Conversation history for multi-turn chat
    private val conversationHistory = mutableMapOf<String, MutableList<ChatMessage>>()

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    companion object {
        fun getInstance(project: Project): OpenRouterService {
            return project.getService(OpenRouterService::class.java)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Client Management
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get or create OpenRouter client
     * Returns null if API key is not configured
     */
    fun getClient(): OpenRouterClient? {
        val settings = AgentSettings.getInstance()
        if (settings.openrouterApiKey.isBlank()) {
            return null
        }

        // Return cached client if settings haven't changed
        val existing = clientRef.get()
        if (existing != null) {
            return existing
        }

        // Create new client
        val client = OpenRouterClient(
            apiKey = settings.openrouterApiKey,
            baseUrl = settings.openrouterBaseUrl,
            defaultModel = settings.openrouterModel
        )
        clientRef.set(client)
        return client
    }

    /**
     * Refresh client (e.g., after settings change)
     */
    fun refreshClient() {
        clientRef.set(null)
        connectionState.set(ConnectionState.DISCONNECTED)
    }

    /**
     * Check if OpenRouter is configured and ready
     */
    fun isConfigured(): Boolean {
        val settings = AgentSettings.getInstance()
        return settings.openrouterApiKey.isNotBlank()
    }

    /**
     * Test connection to OpenRouter API
     */
    fun testConnection(): Boolean {
        connectionState.set(ConnectionState.CONNECTING)
        val client = getClient()
        if (client == null) {
            connectionState.set(ConnectionState.ERROR)
            return false
        }

        return try {
            val result = client.testConnection()
            connectionState.set(if (result) ConnectionState.CONNECTED else ConnectionState.ERROR)
            result
        } catch (e: Exception) {
            log.warn("OpenRouter connection test failed", e)
            connectionState.set(ConnectionState.ERROR)
            false
        }
    }

    fun getConnectionState(): ConnectionState = connectionState.get()

    // ═══════════════════════════════════════════════════════════════
    // Chat API
    // ═══════════════════════════════════════════════════════════════

    /**
     * Send a chat message and get response (non-streaming)
     *
     * @param message User message
     * @param conversationId Conversation ID for multi-turn chat
     * @param systemPrompt Optional system prompt
     * @return Assistant response or null on error
     */
    fun chat(
        message: String,
        conversationId: String? = null,
        systemPrompt: String? = null
    ): String? {
        val client = getClient() ?: return null

        val messages = buildMessageList(conversationId, message, systemPrompt)
        val response = client.chatCompletion(messages)

        if (response != null) {
            val assistantMessage = response.choices.firstOrNull()?.message?.content
            if (assistantMessage != null && conversationId != null) {
                addToHistory(conversationId, ChatMessage("user", message))
                addToHistory(conversationId, ChatMessage("assistant", assistantMessage))
            }
            return assistantMessage
        }
        return null
    }

    /**
     * Send a chat message with streaming response
     *
     * @param message User message
     * @param conversationId Conversation ID for multi-turn chat
     * @param systemPrompt Optional system prompt
     * @param onChunk Callback for each content chunk
     * @param onComplete Callback when streaming is complete
     * @param onError Callback on error
     */
    fun chatStream(
        message: String,
        conversationId: String? = null,
        systemPrompt: String? = null,
        onChunk: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val client = getClient()
        if (client == null) {
            onError("OpenRouter not configured. Please set API key in settings.")
            return
        }

        val messages = buildMessageList(conversationId, message, systemPrompt)
        val fullResponse = StringBuilder()

        client.chatCompletionStream(
            messages = messages,
            onChunk = { chunk ->
                fullResponse.append(chunk)
                onChunk(chunk)
            },
            onComplete = { response ->
                val content = fullResponse.toString()
                if (conversationId != null) {
                    addToHistory(conversationId, ChatMessage("user", message))
                    addToHistory(conversationId, ChatMessage("assistant", content))
                }
                onComplete(content)
            },
            onError = onError
        )
    }

    /**
     * Clear conversation history
     */
    fun clearConversation(conversationId: String) {
        conversationHistory.remove(conversationId)
    }

    /**
     * Clear all conversation history
     */
    fun clearAllConversations() {
        conversationHistory.clear()
    }

    // ═══════════════════════════════════════════════════════════════
    // Code Assistance API
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get code completion/suggestion
     */
    fun getCodeSuggestion(
        code: String,
        language: String,
        instruction: String
    ): String? {
        val systemPrompt = """You are an expert $language programmer.
            |Provide concise, production-ready code suggestions.
            |Only output the code, no explanations unless asked.""".trimMargin()

        val userMessage = """$instruction

```$language
$code
```"""

        return chat(
            message = userMessage,
            systemPrompt = systemPrompt
        )
    }

    /**
     * Explain code
     */
    fun explainCode(
        code: String,
        language: String
    ): String? {
        val systemPrompt = """You are an expert programmer and technical writer.
            |Explain code clearly and concisely.""".trimMargin()

        val userMessage = """Explain this $language code:

```$language
$code
```"""

        return chat(
            message = userMessage,
            systemPrompt = systemPrompt
        )
    }

    /**
     * Review code and suggest improvements
     */
    fun reviewCode(
        code: String,
        language: String,
        onChunk: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val systemPrompt = """You are a senior code reviewer.
            |Analyze code for bugs, performance issues, and best practices.
            |Be specific and actionable in your feedback.""".trimMargin()

        val userMessage = """Review this $language code:

```$language
$code
```

Provide:
1. Potential bugs or issues
2. Performance improvements
3. Code style and best practices
4. Security concerns if any"""

        chatStream(
            message = userMessage,
            systemPrompt = systemPrompt,
            onChunk = onChunk,
            onComplete = onComplete,
            onError = onError
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // Model Management
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get list of available models
     */
    fun getAvailableModels(): List<ModelInfo> {
        return getClient()?.listModels() ?: emptyList()
    }

    /**
     * Get current model ID
     */
    fun getCurrentModel(): String {
        return AgentSettings.getInstance().openrouterModel
    }

    // ═══════════════════════════════════════════════════════════════
    // Private Helpers
    // ═══════════════════════════════════════════════════════════════

    private fun buildMessageList(
        conversationId: String?,
        message: String,
        systemPrompt: String?
    ): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()

        // Add system prompt if provided
        if (!systemPrompt.isNullOrBlank()) {
            messages.add(ChatMessage("system", systemPrompt))
        }

        // Add conversation history if exists
        if (conversationId != null) {
            conversationHistory[conversationId]?.let { history ->
                messages.addAll(history)
            }
        }

        // Add current message
        messages.add(ChatMessage("user", message))

        return messages
    }

    private fun addToHistory(conversationId: String, message: ChatMessage) {
        val history = conversationHistory.getOrPut(conversationId) { mutableListOf() }
        history.add(message)

        // Limit history size to prevent context overflow
        if (history.size > 50) {
            // Keep system message if present, remove oldest messages
            val systemMessages = history.filter { it.role == "system" }
            val otherMessages = history.filter { it.role != "system" }
            history.clear()
            history.addAll(systemMessages)
            history.addAll(otherMessages.takeLast(48))
        }
    }

    override fun dispose() {
        conversationHistory.clear()
        clientRef.set(null)
    }
}
