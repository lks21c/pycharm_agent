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
 * Client for communicating with PyCharm Agent backend
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

    /**
     * Stream chat response via SSE (synchronous version for use with executeOnPooledThread)
     */
    fun streamChatSync(message: String, onChunk: (String) -> Unit) {
        val requestBody = gson.toJson(mapOf(
            "message" to message
        )).toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url("${getBaseUrl()}/api/chat/stream")
            .post(requestBody)
            .build()

        val latch = CountDownLatch(1)
        val errorRef = AtomicReference<Throwable?>(null)

        val eventSourceListener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                try {
                    val event = gson.fromJson(data, JsonObject::class.java)
                    when (event.get("type")?.asString) {
                        "content" -> {
                            val chunk = event.get("chunk")?.asString ?: ""
                            onChunk(chunk)
                        }
                        "done" -> {
                            eventSource.cancel()
                            latch.countDown()
                        }
                        "error" -> {
                            val error = event.get("error")?.asString ?: "Unknown error"
                            errorRef.set(IOException(error))
                            eventSource.cancel()
                            latch.countDown()
                        }
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
    fun generatePlanSync(request: String): PlanResponse {
        val requestBody = gson.toJson(mapOf(
            "request" to request,
            "project_context" to mapOf(
                "project_root" to project.basePath,
                "open_files" to emptyList<Any>(),
                "active_file" to null
            )
        )).toRequestBody(JSON_MEDIA_TYPE)

        val httpRequest = Request.Builder()
            .url("${getBaseUrl()}/api/agent/plan")
            .post(requestBody)
            .build()

        val response = client.newCall(httpRequest).execute()
        if (!response.isSuccessful) {
            throw IOException("Plan generation failed: ${response.code}")
        }
        val body = response.body?.string() ?: throw IOException("Empty response")
        return gson.fromJson(body, PlanResponse::class.java)
    }

    /**
     * Execute a single step and get diff (synchronous version)
     */
    fun executeStepSync(stepNumber: Int, plan: PlanResponse): DiffResult {
        val step = plan.plan.steps.find { it.stepNumber == stepNumber }
            ?: throw IOException("Step $stepNumber not found in plan")

        // Get file content if target file exists
        val filePath = step.targetFile
        val fileContent = if (filePath != null) {
            readFileContent(filePath)
        } else {
            ""
        }

        val requestBody = gson.toJson(mapOf(
            "step" to step,
            "project_context" to mapOf(
                "project_root" to project.basePath,
                "open_files" to emptyList<Any>(),
                "active_file" to if (filePath != null) mapOf(
                    "path" to filePath,
                    "content" to fileContent,
                    "language" to detectLanguage(filePath)
                ) else null
            ),
            "previous_results" to emptyList<Any>()
        )).toRequestBody(JSON_MEDIA_TYPE)

        val httpRequest = Request.Builder()
            .url("${getBaseUrl()}/api/agent/execute-step")
            .post(requestBody)
            .build()

        val response = client.newCall(httpRequest).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            throw IOException("Step execution failed: ${response.code} - $errorBody")
        }
        val body = response.body?.string() ?: throw IOException("Empty response")
        val stepResponse = gson.fromJson(body, ExecuteStepResponse::class.java)

        return stepResponse.diff ?: DiffResult(
            filePath = filePath ?: "",
            hunks = emptyList(),
            unifiedDiff = "No changes generated",
            previewContent = fileContent
        )
    }

    private fun readFileContent(filePath: String): String {
        val projectPath = project.basePath ?: return ""
        val fullPath = if (filePath.startsWith("/")) filePath else "$projectPath/$filePath"
        return try {
            java.io.File(fullPath).readText()
        } catch (e: Exception) {
            ""
        }
    }

    private fun detectLanguage(filePath: String): String {
        return when {
            filePath.endsWith(".py") -> "python"
            filePath.endsWith(".kt") -> "kotlin"
            filePath.endsWith(".java") -> "java"
            filePath.endsWith(".js") -> "javascript"
            filePath.endsWith(".ts") -> "typescript"
            filePath.endsWith(".tsx") -> "typescript"
            filePath.endsWith(".jsx") -> "javascript"
            else -> "text"
        }
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
}

// Data classes for API responses
data class PlanResponse(
    val plan: ExecutionPlan,
    val reasoning: String
)

data class ExecutionPlan(
    val steps: List<PlanStep>,
    val totalSteps: Int,
    val reasoning: String?
)

data class PlanStep(
    val stepNumber: Int,
    val description: String,
    val tool: String,
    val targetFile: String?,
    val dependencies: List<Int>,
    val estimatedDiffType: String?
)

data class ExecuteStepResponse(
    val success: Boolean,
    val stepNumber: Int,
    val diff: DiffResult?,
    val explanation: String,
    val requiresUserApproval: Boolean
)

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
