package com.hdsp.pycharm_agent.openrouter

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import com.hdsp.pycharm_agent.settings.AgentSettings
import com.intellij.openapi.diagnostic.Logger
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * OpenRouter API Client
 *
 * Provides direct access to OpenRouter's unified LLM API, supporting:
 * - 300+ AI models through a single interface
 * - Streaming responses (SSE)
 * - OpenAI SDK compatible format
 *
 * @see https://openrouter.ai/docs
 */
class OpenRouterClient(
    private val apiKey: String,
    private val baseUrl: String = "https://openrouter.ai/api/v1",
    private val defaultModel: String = "anthropic/claude-sonnet-4"
) {

    private val log = Logger.getInstance(OpenRouterClient::class.java)
    private val gson: Gson = GsonBuilder().create()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        /**
         * Create client from current settings
         */
        fun fromSettings(): OpenRouterClient? {
            val settings = AgentSettings.getInstance()
            if (settings.openrouterApiKey.isBlank()) {
                return null
            }
            return OpenRouterClient(
                apiKey = settings.openrouterApiKey,
                baseUrl = settings.openrouterBaseUrl,
                defaultModel = settings.openrouterModel
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Chat Completions API
    // ═══════════════════════════════════════════════════════════════

    /**
     * Send a chat completion request (non-streaming)
     *
     * @param messages List of chat messages
     * @param model Model ID (defaults to configured model)
     * @param temperature Sampling temperature (0-2)
     * @param maxTokens Maximum tokens to generate
     * @return ChatCompletionResponse or null on error
     */
    fun chatCompletion(
        messages: List<ChatMessage>,
        model: String = defaultModel,
        temperature: Double = 0.7,
        maxTokens: Int? = null
    ): ChatCompletionResponse? {
        val request = ChatCompletionRequest(
            model = model,
            messages = messages,
            temperature = temperature,
            maxTokens = maxTokens,
            stream = false
        )

        val requestBody = gson.toJson(request).toRequestBody(jsonMediaType)

        val httpRequest = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("HTTP-Referer", "https://github.com/hdsp/pycharm-agent")
            .addHeader("X-Title", "PyCharm Agent")
            .post(requestBody)
            .build()

        return try {
            httpClient.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    log.error("OpenRouter API error: ${response.code} - $errorBody")
                    null
                } else {
                    val body = response.body?.string() ?: return null
                    gson.fromJson(body, ChatCompletionResponse::class.java)
                }
            }
        } catch (e: Exception) {
            log.error("OpenRouter request failed", e)
            null
        }
    }

    /**
     * Send a streaming chat completion request
     *
     * @param messages List of chat messages
     * @param model Model ID (defaults to configured model)
     * @param temperature Sampling temperature (0-2)
     * @param maxTokens Maximum tokens to generate
     * @param onChunk Callback for each content chunk
     * @param onComplete Callback when streaming is complete
     * @param onError Callback on error
     */
    fun chatCompletionStream(
        messages: List<ChatMessage>,
        model: String = defaultModel,
        temperature: Double = 0.7,
        maxTokens: Int? = null,
        onChunk: (String) -> Unit,
        onComplete: (ChatCompletionResponse?) -> Unit,
        onError: (String) -> Unit
    ) {
        val request = ChatCompletionRequest(
            model = model,
            messages = messages,
            temperature = temperature,
            maxTokens = maxTokens,
            stream = true
        )

        val requestBody = gson.toJson(request).toRequestBody(jsonMediaType)

        val httpRequest = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("HTTP-Referer", "https://github.com/hdsp/pycharm-agent")
            .addHeader("X-Title", "PyCharm Agent")
            .addHeader("Accept", "text/event-stream")
            .post(requestBody)
            .build()

        try {
            httpClient.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    log.error("OpenRouter streaming error: ${response.code} - $errorBody")
                    onError("API error: ${response.code} - ${parseErrorMessage(errorBody)}")
                    return
                }

                val reader = BufferedReader(InputStreamReader(response.body?.byteStream() ?: return))
                val contentBuilder = StringBuilder()
                var lastResponse: ChatCompletionResponse? = null

                reader.forEachLine { line ->
                    if (line.startsWith("data: ")) {
                        val data = line.substring(6).trim()
                        if (data == "[DONE]") {
                            // Stream complete
                            return@forEachLine
                        }

                        try {
                            val chunk = gson.fromJson(data, ChatCompletionChunk::class.java)
                            chunk.choices.firstOrNull()?.delta?.content?.let { content ->
                                contentBuilder.append(content)
                                onChunk(content)
                            }

                            // Build final response from last chunk
                            if (chunk.choices.firstOrNull()?.finishReason != null) {
                                lastResponse = ChatCompletionResponse(
                                    id = chunk.id,
                                    model = chunk.model,
                                    created = chunk.created,
                                    choices = listOf(
                                        ChatChoice(
                                            index = 0,
                                            message = ChatMessage(
                                                role = "assistant",
                                                content = contentBuilder.toString()
                                            ),
                                            finishReason = chunk.choices.first().finishReason
                                        )
                                    ),
                                    usage = chunk.usage
                                )
                            }
                        } catch (e: Exception) {
                            log.debug("Failed to parse SSE chunk: $data", e)
                        }
                    }
                }

                onComplete(lastResponse)
            }
        } catch (e: IOException) {
            log.error("OpenRouter streaming request failed", e)
            onError("Network error: ${e.message}")
        } catch (e: Exception) {
            log.error("OpenRouter streaming error", e)
            onError("Error: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Models API
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get list of available models
     *
     * @return List of model information or empty list on error
     */
    fun listModels(): List<ModelInfo> {
        val httpRequest = Request.Builder()
            .url("$baseUrl/models")
            .addHeader("Authorization", "Bearer $apiKey")
            .get()
            .build()

        return try {
            httpClient.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    log.error("Failed to list models: ${response.code}")
                    emptyList()
                } else {
                    val body = response.body?.string() ?: return emptyList()
                    val jsonObject = JsonParser.parseString(body).asJsonObject
                    val dataArray = jsonObject.getAsJsonArray("data") ?: return emptyList()
                    dataArray.map { gson.fromJson(it, ModelInfo::class.java) }
                }
            }
        } catch (e: Exception) {
            log.error("Failed to list models", e)
            emptyList()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Helper Methods
    // ═══════════════════════════════════════════════════════════════

    private fun parseErrorMessage(errorBody: String?): String {
        if (errorBody.isNullOrBlank()) return "Unknown error"
        return try {
            val json = JsonParser.parseString(errorBody).asJsonObject
            json.getAsJsonObject("error")?.get("message")?.asString ?: errorBody
        } catch (e: Exception) {
            errorBody
        }
    }

    /**
     * Test API connectivity
     *
     * @return true if API is accessible and key is valid
     */
    fun testConnection(): Boolean {
        return try {
            val models = listModels()
            models.isNotEmpty()
        } catch (e: Exception) {
            log.warn("Connection test failed", e)
            false
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// Data Classes - Request/Response Models
// ═══════════════════════════════════════════════════════════════

/**
 * Chat message (OpenAI format)
 */
data class ChatMessage(
    val role: String,  // "system", "user", "assistant"
    val content: String,
    val name: String? = null
)

/**
 * Chat completion request
 */
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.7,
    @SerializedName("max_tokens")
    val maxTokens: Int? = null,
    val stream: Boolean = false,
    @SerializedName("top_p")
    val topP: Double? = null,
    @SerializedName("frequency_penalty")
    val frequencyPenalty: Double? = null,
    @SerializedName("presence_penalty")
    val presencePenalty: Double? = null,
    val stop: List<String>? = null
)

/**
 * Chat completion response
 */
data class ChatCompletionResponse(
    val id: String,
    val model: String,
    val created: Long,
    val choices: List<ChatChoice>,
    val usage: Usage? = null
)

/**
 * Chat choice in response
 */
data class ChatChoice(
    val index: Int,
    val message: ChatMessage,
    @SerializedName("finish_reason")
    val finishReason: String?
)

/**
 * Streaming chunk response
 */
data class ChatCompletionChunk(
    val id: String,
    val model: String,
    val created: Long,
    val choices: List<ChunkChoice>,
    val usage: Usage? = null
)

/**
 * Chunk choice (streaming)
 */
data class ChunkChoice(
    val index: Int,
    val delta: DeltaContent,
    @SerializedName("finish_reason")
    val finishReason: String?
)

/**
 * Delta content in streaming
 */
data class DeltaContent(
    val role: String? = null,
    val content: String? = null
)

/**
 * Token usage statistics
 */
data class Usage(
    @SerializedName("prompt_tokens")
    val promptTokens: Int,
    @SerializedName("completion_tokens")
    val completionTokens: Int,
    @SerializedName("total_tokens")
    val totalTokens: Int
)

/**
 * Model information from /models endpoint
 */
data class ModelInfo(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    val context_length: Int? = null,
    val pricing: ModelPricing? = null,
    val top_provider: TopProvider? = null
)

/**
 * Model pricing information
 */
data class ModelPricing(
    val prompt: String? = null,
    val completion: String? = null,
    val request: String? = null,
    val image: String? = null
)

/**
 * Top provider information
 */
data class TopProvider(
    val context_length: Int? = null,
    val max_completion_tokens: Int? = null,
    val is_moderated: Boolean? = null
)
