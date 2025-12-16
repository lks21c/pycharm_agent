package com.hdsp.pycharm_agent.services

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.hdsp.pycharm_agent.settings.AgentSettings
import com.intellij.openapi.components.Service
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
 */
@Service(Service.Level.PROJECT)
class BackendClient(private val project: Project) {

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

    private fun buildLlmConfig(): Map<String, Any?> {
        val settings = AgentSettings.getInstance()
        return mapOf(
            "provider" to settings.provider,
            "gemini" to mapOf(
                "apiKeys" to settings.geminiApiKeys,
                "model" to settings.geminiModel
            ),
            "openai" to mapOf(
                "apiKey" to settings.openaiApiKey,
                "model" to settings.openaiModel
            ),
            "vllm" to mapOf(
                "endpoint" to settings.vllmEndpoint,
                "apiKey" to settings.vllmApiKey,
                "model" to settings.vllmModel
            )
        )
    }

    /**
     * Stream chat response via SSE (synchronous version for use with executeOnPooledThread)
     */
    fun streamChatSync(message: String, onChunk: (String) -> Unit) {
        val requestBody = gson.toJson(mapOf(
            "message" to message,
            "llmConfig" to buildLlmConfig()
        )).toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url("${getBaseUrl()}/chat/stream")
            .post(requestBody)
            .build()

        val latch = CountDownLatch(1)
        val errorRef = AtomicReference<Throwable?>(null)

        val eventSourceListener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                try {
                    val event = gson.fromJson(data, JsonObject::class.java)
                    val content = event.get("content")?.asString
                    val done = event.get("done")?.asBoolean ?: false

                    if (content != null && content.isNotEmpty()) {
                        onChunk(content)
                    }

                    if (done) {
                        eventSource.cancel()
                        latch.countDown()
                    }
                } catch (e: Exception) {
                    // Ignore parse errors, continue streaming
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                errorRef.set(t ?: IOException("SSE connection failed"))
                latch.countDown()
            }

            override fun onClosed(eventSource: EventSource) {
                latch.countDown()
            }
        }

        EventSources.createFactory(client)
            .newEventSource(request, eventSourceListener)

        latch.await(120, TimeUnit.SECONDS)

        errorRef.get()?.let { throw it }
    }

    /**
     * Generate execution plan (synchronous version)
     */
    fun generatePlanSync(request: String): HdspPlanResponse {
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
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            throw IOException("Plan generation failed: ${response.code} - $errorBody")
        }
        val body = response.body?.string() ?: throw IOException("Empty response")
        return gson.fromJson(body, HdspPlanResponse::class.java)
    }

    /**
     * Refine code after execution error
     */
    fun refineSync(step: HdspPlanStep, error: ErrorInfo, attempt: Int, previousCode: String?): RefineResponse {
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
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            throw IOException("Refine failed: ${response.code} - $errorBody")
        }
        val body = response.body?.string() ?: throw IOException("Empty response")
        return gson.fromJson(body, RefineResponse::class.java)
    }

    /**
     * Determine replan strategy after error
     */
    fun replanSync(
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
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            throw IOException("Replan failed: ${response.code} - $errorBody")
        }
        val body = response.body?.string() ?: throw IOException("Empty response")
        return gson.fromJson(body, ReplanResponse::class.java)
    }

    /**
     * Verify execution state after step completion
     */
    fun verifyStateSync(stepIndex: Int, expectedChanges: Map<String, Any>, actualOutput: String): VerifyStateResponse {
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
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            throw IOException("Verify state failed: ${response.code} - $errorBody")
        }
        val body = response.body?.string() ?: throw IOException("Empty response")
        return gson.fromJson(body, VerifyStateResponse::class.java)
    }

    /**
     * Test backend connection (synchronous version)
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
            false
        }
    }
}

// HDSP API Response Models
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
