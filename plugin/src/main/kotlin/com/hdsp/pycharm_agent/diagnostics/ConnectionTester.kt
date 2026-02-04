package com.hdsp.pycharm_agent.diagnostics

import com.hdsp.pycharm_agent.acp.ACPClientService
import com.hdsp.pycharm_agent.acp.ACPClientState
import com.hdsp.pycharm_agent.openrouter.OpenRouterService
import com.hdsp.pycharm_agent.services.BackendClient
import com.hdsp.pycharm_agent.services.AgentClientFactory
import com.hdsp.pycharm_agent.services.ClientMode
import com.hdsp.pycharm_agent.settings.AgentSettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*
import java.time.Duration
import java.time.Instant

/**
 * Connection Tester
 *
 * Provides quick connectivity tests for all backend services.
 * Useful for troubleshooting and integration testing.
 */
class ConnectionTester(private val project: Project) {

    private val log = Logger.getInstance(ConnectionTester::class.java)

    /**
     * Test result for a single connection test
     */
    data class TestResult(
        val service: String,
        val success: Boolean,
        val latencyMs: Long,
        val message: String,
        val details: Map<String, String> = emptyMap()
    )

    /**
     * Full test suite result
     */
    data class TestSuiteResult(
        val timestamp: Instant,
        val activeMode: ClientMode,
        val results: List<TestResult>,
        val overallSuccess: Boolean
    )

    /**
     * Run all connection tests
     */
    fun runAllTests(): TestSuiteResult {
        val results = mutableListOf<TestResult>()
        val factory = AgentClientFactory.getInstance(project)
        val activeMode = factory.getCurrentMode()

        // Test based on active mode
        when (activeMode) {
            ClientMode.ACP -> {
                results.add(testACPConnection())
            }
            ClientMode.OPENROUTER -> {
                results.add(testOpenRouterConnection())
            }
            ClientMode.LEGACY -> {
                results.add(testLegacyBackendConnection())
            }
        }

        // Also test other configured services
        val settings = AgentSettings.getInstance()

        if (activeMode != ClientMode.ACP && settings.useAcpMode && settings.acpAgentPath.isNotBlank()) {
            results.add(testACPConnection())
        }

        if (activeMode != ClientMode.OPENROUTER && settings.openrouterApiKey.isNotBlank()) {
            results.add(testOpenRouterConnection())
        }

        if (activeMode != ClientMode.LEGACY) {
            results.add(testLegacyBackendConnection())
        }

        val overallSuccess = results.any { it.success }

        return TestSuiteResult(
            timestamp = Instant.now(),
            activeMode = activeMode,
            results = results,
            overallSuccess = overallSuccess
        )
    }

    /**
     * Test ACP agent connection
     */
    fun testACPConnection(): TestResult {
        val start = Instant.now()
        return try {
            val acpClient = ACPClientService.getInstance(project)
            val state = acpClient.getState()

            val latencyMs = Duration.between(start, Instant.now()).toMillis()

            when (state) {
                ACPClientState.CONNECTED -> {
                    val agentInfo = acpClient.getAgentInfo()
                    TestResult(
                        service = "ACP Client",
                        success = true,
                        latencyMs = latencyMs,
                        message = "Connected to ${agentInfo?.name ?: "agent"}",
                        details = mapOf(
                            "state" to state.name,
                            "agentName" to (agentInfo?.name ?: "unknown"),
                            "agentVersion" to (agentInfo?.version ?: "unknown"),
                            "sessionId" to (acpClient.getCurrentSessionId() ?: "none")
                        )
                    )
                }
                ACPClientState.CONNECTING -> {
                    TestResult(
                        service = "ACP Client",
                        success = false,
                        latencyMs = latencyMs,
                        message = "Still connecting to agent",
                        details = mapOf("state" to state.name)
                    )
                }
                ACPClientState.DISCONNECTED -> {
                    TestResult(
                        service = "ACP Client",
                        success = false,
                        latencyMs = latencyMs,
                        message = "Agent not connected",
                        details = mapOf("state" to state.name)
                    )
                }
                ACPClientState.ERROR -> {
                    TestResult(
                        service = "ACP Client",
                        success = false,
                        latencyMs = latencyMs,
                        message = "Agent in error state",
                        details = mapOf("state" to state.name)
                    )
                }
            }
        } catch (e: Exception) {
            val latencyMs = Duration.between(start, Instant.now()).toMillis()
            log.warn("ACP connection test failed", e)
            TestResult(
                service = "ACP Client",
                success = false,
                latencyMs = latencyMs,
                message = "Error: ${e.message}",
                details = mapOf("exception" to e.javaClass.simpleName)
            )
        }
    }

    /**
     * Test OpenRouter API connection
     */
    fun testOpenRouterConnection(): TestResult {
        val start = Instant.now()
        return try {
            val service = OpenRouterService.getInstance(project)

            if (!service.isConfigured()) {
                return TestResult(
                    service = "OpenRouter",
                    success = false,
                    latencyMs = 0,
                    message = "API key not configured",
                    details = emptyMap()
                )
            }

            // Test connection
            val connected = service.testConnection()
            val latencyMs = Duration.between(start, Instant.now()).toMillis()

            if (connected) {
                val settings = AgentSettings.getInstance()
                TestResult(
                    service = "OpenRouter",
                    success = true,
                    latencyMs = latencyMs,
                    message = "Connected to OpenRouter API",
                    details = mapOf(
                        "model" to settings.openrouterModel,
                        "connectionState" to service.getConnectionState().name
                    )
                )
            } else {
                TestResult(
                    service = "OpenRouter",
                    success = false,
                    latencyMs = latencyMs,
                    message = "Failed to connect to OpenRouter API",
                    details = mapOf("connectionState" to service.getConnectionState().name)
                )
            }
        } catch (e: Exception) {
            val latencyMs = Duration.between(start, Instant.now()).toMillis()
            log.warn("OpenRouter connection test failed", e)
            TestResult(
                service = "OpenRouter",
                success = false,
                latencyMs = latencyMs,
                message = "Error: ${e.message}",
                details = mapOf("exception" to e.javaClass.simpleName)
            )
        }
    }

    /**
     * Test legacy backend connection
     */
    fun testLegacyBackendConnection(): TestResult {
        val start = Instant.now()
        return try {
            val client = project.getService(BackendClient::class.java)
            val settings = AgentSettings.getInstance()

            val isHealthy = client.checkHealth()
            val latencyMs = Duration.between(start, Instant.now()).toMillis()

            if (isHealthy) {
                TestResult(
                    service = "Legacy Backend",
                    success = true,
                    latencyMs = latencyMs,
                    message = "Backend responding at ${settings.backendUrl}",
                    details = mapOf(
                        "url" to settings.backendUrl,
                        "provider" to settings.provider
                    )
                )
            } else {
                TestResult(
                    service = "Legacy Backend",
                    success = false,
                    latencyMs = latencyMs,
                    message = "Backend not responding at ${settings.backendUrl}",
                    details = mapOf("url" to settings.backendUrl)
                )
            }
        } catch (e: Exception) {
            val latencyMs = Duration.between(start, Instant.now()).toMillis()
            log.warn("Legacy backend connection test failed", e)
            TestResult(
                service = "Legacy Backend",
                success = false,
                latencyMs = latencyMs,
                message = "Error: ${e.message}",
                details = mapOf("exception" to e.javaClass.simpleName)
            )
        }
    }

    /**
     * Quick health check - returns true if active mode is working
     */
    fun quickHealthCheck(): Boolean {
        val factory = AgentClientFactory.getInstance(project)
        return try {
            when (factory.getCurrentMode()) {
                ClientMode.ACP -> {
                    val state = ACPClientService.getInstance(project).getState()
                    state == ACPClientState.CONNECTED
                }
                ClientMode.OPENROUTER -> {
                    val service = OpenRouterService.getInstance(project)
                    service.isConfigured() && service.getConnectionState() == OpenRouterService.ConnectionState.CONNECTED
                }
                ClientMode.LEGACY -> {
                    project.getService(BackendClient::class.java).checkHealth()
                }
            }
        } catch (e: Exception) {
            log.warn("Quick health check failed", e)
            false
        }
    }

    /**
     * Format test results as text
     */
    fun formatResults(result: TestSuiteResult): String {
        val sb = StringBuilder()

        sb.appendLine("═══════════════════════════════════════════════════════════════")
        sb.appendLine("Connection Test Results")
        sb.appendLine("═══════════════════════════════════════════════════════════════")
        sb.appendLine("Active Mode: ${result.activeMode}")
        sb.appendLine("Overall Status: ${if (result.overallSuccess) "✅ PASS" else "❌ FAIL"}")
        sb.appendLine()

        result.results.forEach { test ->
            val statusIcon = if (test.success) "✅" else "❌"
            sb.appendLine("───────────────────────────────────────────────────────────────")
            sb.appendLine("$statusIcon ${test.service} (${test.latencyMs}ms)")
            sb.appendLine("───────────────────────────────────────────────────────────────")
            sb.appendLine("  Message: ${test.message}")
            if (test.details.isNotEmpty()) {
                sb.appendLine("  Details:")
                test.details.forEach { (key, value) ->
                    sb.appendLine("    $key: $value")
                }
            }
            sb.appendLine()
        }

        return sb.toString()
    }

    companion object {
        fun getInstance(project: Project): ConnectionTester {
            return ConnectionTester(project)
        }
    }
}
