package com.hdsp.pycharm_agent.diagnostics

import com.hdsp.pycharm_agent.acp.ACPClientService
import com.hdsp.pycharm_agent.acp.ACPClientState
import com.hdsp.pycharm_agent.acp.ACPSessionManager
import com.hdsp.pycharm_agent.openrouter.OpenRouterService
import com.hdsp.pycharm_agent.services.AgentClientAdapter
import com.hdsp.pycharm_agent.services.AgentClientFactory
import com.hdsp.pycharm_agent.services.BackendClient
import com.hdsp.pycharm_agent.services.ClientMode
import com.hdsp.pycharm_agent.settings.AgentSettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Agent Diagnostics
 *
 * Provides diagnostic information and health checks for the PyCharm Agent plugin.
 * Useful for troubleshooting and verifying configuration.
 */
class AgentDiagnostics(private val project: Project) {

    private val log = Logger.getInstance(AgentDiagnostics::class.java)

    /**
     * Run full diagnostic check and return report
     */
    fun runDiagnostics(): DiagnosticReport {
        val checks = mutableListOf<DiagnosticCheck>()
        val timestamp = Instant.now()

        // Settings check
        checks.add(checkSettings())

        // ACP check
        checks.add(checkACP())

        // OpenRouter check
        checks.add(checkOpenRouter())

        // Legacy backend check
        checks.add(checkLegacyBackend())

        // Session manager check
        checks.add(checkSessionManager())

        // Client factory check
        checks.add(checkClientFactory())

        return DiagnosticReport(
            timestamp = timestamp,
            projectName = project.name,
            projectPath = project.basePath ?: "unknown",
            checks = checks,
            summary = generateSummary(checks)
        )
    }

    private fun checkSettings(): DiagnosticCheck {
        val settings = AgentSettings.getInstance()
        val issues = mutableListOf<String>()
        val info = mutableMapOf<String, String>()

        info["useAcpMode"] = settings.useAcpMode.toString()
        info["useOpenRouterDirect"] = settings.useOpenRouterDirect.toString()
        info["acpAgentPath"] = settings.acpAgentPath.ifBlank { "(not set)" }
        info["openrouterModel"] = settings.openrouterModel
        info["openrouterApiKey"] = if (settings.openrouterApiKey.isNotBlank()) "****" else "(not set)"

        // Check for configuration issues
        if (settings.useAcpMode && settings.acpAgentPath.isBlank()) {
            issues.add("ACP mode enabled but agent path not configured")
        }

        if (settings.useAcpMode && settings.acpAgentPath.isNotBlank()) {
            val agentFile = File(settings.acpAgentPath)
            if (!agentFile.exists()) {
                issues.add("ACP agent path does not exist: ${settings.acpAgentPath}")
            } else if (!agentFile.canExecute()) {
                issues.add("ACP agent is not executable: ${settings.acpAgentPath}")
            }
        }

        if (settings.useOpenRouterDirect && settings.openrouterApiKey.isBlank()) {
            issues.add("OpenRouter direct mode enabled but API key not configured")
        }

        return DiagnosticCheck(
            name = "Settings",
            status = if (issues.isEmpty()) CheckStatus.OK else CheckStatus.WARNING,
            info = info,
            issues = issues
        )
    }

    private fun checkACP(): DiagnosticCheck {
        val issues = mutableListOf<String>()
        val info = mutableMapOf<String, String>()

        try {
            val acpClient = ACPClientService.getInstance(project)
            val state = acpClient.getState()

            info["state"] = state.name
            info["isRunning"] = acpClient.isRunning().toString()

            val agentInfo = acpClient.getAgentInfo()
            if (agentInfo != null) {
                info["agentName"] = agentInfo.name
                info["agentVersion"] = agentInfo.version
            }

            val sessionId = acpClient.getCurrentSessionId()
            info["currentSession"] = sessionId ?: "(none)"

            if (state == ACPClientState.ERROR) {
                issues.add("ACP client is in error state")
            }

            val status = when (state) {
                ACPClientState.CONNECTED -> CheckStatus.OK
                ACPClientState.CONNECTING -> CheckStatus.WARNING
                ACPClientState.DISCONNECTED -> CheckStatus.INFO
                ACPClientState.ERROR -> CheckStatus.ERROR
            }

            return DiagnosticCheck(
                name = "ACP Client",
                status = status,
                info = info,
                issues = issues
            )
        } catch (e: Exception) {
            return DiagnosticCheck(
                name = "ACP Client",
                status = CheckStatus.ERROR,
                info = info,
                issues = listOf("Error checking ACP: ${e.message}")
            )
        }
    }

    private fun checkOpenRouter(): DiagnosticCheck {
        val issues = mutableListOf<String>()
        val info = mutableMapOf<String, String>()

        try {
            val settings = AgentSettings.getInstance()
            val service = OpenRouterService.getInstance(project)

            info["configured"] = service.isConfigured().toString()
            info["connectionState"] = service.getConnectionState().name
            info["model"] = settings.openrouterModel

            if (!service.isConfigured()) {
                info["status"] = "Not configured"
                return DiagnosticCheck(
                    name = "OpenRouter",
                    status = CheckStatus.INFO,
                    info = info,
                    issues = issues
                )
            }

            // Don't actually test connection to avoid API usage
            val status = when (service.getConnectionState()) {
                OpenRouterService.ConnectionState.CONNECTED -> CheckStatus.OK
                OpenRouterService.ConnectionState.CONNECTING -> CheckStatus.WARNING
                OpenRouterService.ConnectionState.DISCONNECTED -> CheckStatus.INFO
                OpenRouterService.ConnectionState.ERROR -> CheckStatus.ERROR
            }

            return DiagnosticCheck(
                name = "OpenRouter",
                status = status,
                info = info,
                issues = issues
            )
        } catch (e: Exception) {
            return DiagnosticCheck(
                name = "OpenRouter",
                status = CheckStatus.ERROR,
                info = info,
                issues = listOf("Error checking OpenRouter: ${e.message}")
            )
        }
    }

    private fun checkLegacyBackend(): DiagnosticCheck {
        val issues = mutableListOf<String>()
        val info = mutableMapOf<String, String>()

        try {
            val settings = AgentSettings.getInstance()
            val client = project.getService(BackendClient::class.java)

            info["backendUrl"] = settings.backendUrl
            info["provider"] = settings.provider

            // Check health without blocking
            val isHealthy = try {
                client.checkHealth()
            } catch (e: Exception) {
                false
            }

            info["healthy"] = isHealthy.toString()

            if (!isHealthy) {
                issues.add("Legacy backend is not responding at ${settings.backendUrl}")
            }

            return DiagnosticCheck(
                name = "Legacy Backend",
                status = if (isHealthy) CheckStatus.OK else CheckStatus.WARNING,
                info = info,
                issues = issues
            )
        } catch (e: Exception) {
            return DiagnosticCheck(
                name = "Legacy Backend",
                status = CheckStatus.WARNING,
                info = info,
                issues = listOf("Error checking legacy backend: ${e.message}")
            )
        }
    }

    private fun checkSessionManager(): DiagnosticCheck {
        val info = mutableMapOf<String, String>()

        try {
            val sessionManager = ACPSessionManager.getInstance(project)
            val activeSessions = sessionManager.getActiveSessions()
            val history = sessionManager.getSessionHistory()

            info["activeSessions"] = activeSessions.size.toString()
            info["historyEntries"] = history.size.toString()

            val activeSession = sessionManager.getActiveSession()
            if (activeSession != null) {
                info["currentSessionId"] = activeSession.id
                info["currentSessionMode"] = activeSession.mode
                info["currentSessionAge"] = "${activeSession.getAge() / 1000}s"
            }

            return DiagnosticCheck(
                name = "Session Manager",
                status = CheckStatus.OK,
                info = info,
                issues = emptyList()
            )
        } catch (e: Exception) {
            return DiagnosticCheck(
                name = "Session Manager",
                status = CheckStatus.ERROR,
                info = info,
                issues = listOf("Error checking session manager: ${e.message}")
            )
        }
    }

    private fun checkClientFactory(): DiagnosticCheck {
        val info = mutableMapOf<String, String>()

        try {
            val factory = AgentClientFactory.getInstance(project)
            val mode = factory.getCurrentMode()
            val client = factory.getClient()
            val state = client.getConnectionState()

            info["mode"] = mode.name
            info["adapterClass"] = client.javaClass.simpleName
            info["connectionState"] = state.name
            info["agentInfo"] = client.getAgentInfoSummary()

            val status = when (state) {
                AgentClientAdapter.ConnectionState.CONNECTED -> CheckStatus.OK
                AgentClientAdapter.ConnectionState.CONNECTING -> CheckStatus.WARNING
                AgentClientAdapter.ConnectionState.DISCONNECTED -> CheckStatus.INFO
                AgentClientAdapter.ConnectionState.ERROR -> CheckStatus.ERROR
            }

            return DiagnosticCheck(
                name = "Client Factory",
                status = status,
                info = info,
                issues = emptyList()
            )
        } catch (e: Exception) {
            return DiagnosticCheck(
                name = "Client Factory",
                status = CheckStatus.ERROR,
                info = info,
                issues = listOf("Error checking client factory: ${e.message}")
            )
        }
    }

    private fun generateSummary(checks: List<DiagnosticCheck>): String {
        val errorCount = checks.count { it.status == CheckStatus.ERROR }
        val warningCount = checks.count { it.status == CheckStatus.WARNING }
        val okCount = checks.count { it.status == CheckStatus.OK }

        return when {
            errorCount > 0 -> "⚠️ $errorCount error(s), $warningCount warning(s)"
            warningCount > 0 -> "⚡ $warningCount warning(s), $okCount OK"
            else -> "✅ All $okCount checks passed"
        }
    }

    /**
     * Format report as text
     */
    fun formatReport(report: DiagnosticReport): String {
        val sb = StringBuilder()
        val formatter = DateTimeFormatter.ISO_INSTANT

        sb.appendLine("═══════════════════════════════════════════════════════════════")
        sb.appendLine("PyCharm Agent Diagnostics Report")
        sb.appendLine("═══════════════════════════════════════════════════════════════")
        sb.appendLine("Timestamp: ${formatter.format(report.timestamp)}")
        sb.appendLine("Project: ${report.projectName}")
        sb.appendLine("Path: ${report.projectPath}")
        sb.appendLine("Summary: ${report.summary}")
        sb.appendLine()

        report.checks.forEach { check ->
            val statusIcon = when (check.status) {
                CheckStatus.OK -> "✅"
                CheckStatus.WARNING -> "⚠️"
                CheckStatus.ERROR -> "❌"
                CheckStatus.INFO -> "ℹ️"
            }

            sb.appendLine("───────────────────────────────────────────────────────────────")
            sb.appendLine("$statusIcon ${check.name}")
            sb.appendLine("───────────────────────────────────────────────────────────────")

            check.info.forEach { (key, value) ->
                sb.appendLine("  $key: $value")
            }

            if (check.issues.isNotEmpty()) {
                sb.appendLine("  Issues:")
                check.issues.forEach { issue ->
                    sb.appendLine("    - $issue")
                }
            }
            sb.appendLine()
        }

        return sb.toString()
    }

    companion object {
        fun getInstance(project: Project): AgentDiagnostics {
            return AgentDiagnostics(project)
        }
    }
}

/**
 * Diagnostic report
 */
data class DiagnosticReport(
    val timestamp: Instant,
    val projectName: String,
    val projectPath: String,
    val checks: List<DiagnosticCheck>,
    val summary: String
)

/**
 * Single diagnostic check result
 */
data class DiagnosticCheck(
    val name: String,
    val status: CheckStatus,
    val info: Map<String, String>,
    val issues: List<String>
)

/**
 * Check status
 */
enum class CheckStatus {
    OK,
    WARNING,
    ERROR,
    INFO
}
