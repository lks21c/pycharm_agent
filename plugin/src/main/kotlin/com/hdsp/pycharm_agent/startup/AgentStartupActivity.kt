package com.hdsp.pycharm_agent.startup

import com.hdsp.pycharm_agent.services.AgentClientFactory
import com.hdsp.pycharm_agent.services.ClientMode
import com.hdsp.pycharm_agent.settings.AgentSettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.io.File

/**
 * Agent Startup Activity
 *
 * Runs when a project is opened to initialize the agent plugin
 * and log any configuration issues.
 */
class AgentStartupActivity : ProjectActivity {

    private val log = Logger.getInstance(AgentStartupActivity::class.java)

    override suspend fun execute(project: Project) {
        log.info("═══════════════════════════════════════════════════════════════")
        log.info("PyCharm Agent Plugin Initializing")
        log.info("Project: ${project.name}")
        log.info("═══════════════════════════════════════════════════════════════")

        // Log settings status
        logSettingsStatus()

        // Initialize and log client factory status
        initializeClientFactory(project)

        // Log any configuration warnings
        logConfigurationWarnings()

        log.info("PyCharm Agent Plugin initialization complete")
        log.info("═══════════════════════════════════════════════════════════════")
    }

    private fun logSettingsStatus() {
        val settings = AgentSettings.getInstance()

        log.info("Settings Status:")
        log.info("  - ACP Mode Enabled: ${settings.useAcpMode}")
        log.info("  - OpenRouter Direct Mode: ${settings.useOpenRouterDirect}")
        log.info("  - ACP Agent Path: ${settings.acpAgentPath.ifBlank { "(not configured)" }}")
        log.info("  - OpenRouter API Key: ${if (settings.openrouterApiKey.isNotBlank()) "configured" else "not configured"}")
        log.info("  - OpenRouter Model: ${settings.openrouterModel}")
        log.info("  - Legacy Backend URL: ${settings.backendUrl}")
        log.info("  - Legacy Provider: ${settings.provider}")
    }

    private fun initializeClientFactory(project: Project) {
        try {
            val factory = AgentClientFactory.getInstance(project)
            val mode = factory.getCurrentMode()

            log.info("Client Factory Status:")
            log.info("  - Active Mode: ${mode.name}")
            log.info("  - Description: ${getModeDescription(mode)}")

            // Initialize client to ensure everything is ready
            val client = factory.getClient()
            log.info("  - Client Adapter: ${client.javaClass.simpleName}")
        } catch (e: Exception) {
            log.error("Failed to initialize client factory", e)
        }
    }

    private fun getModeDescription(mode: ClientMode): String {
        return when (mode) {
            ClientMode.ACP -> "Using ACP protocol with external CLI agent"
            ClientMode.OPENROUTER -> "Using OpenRouter API for direct LLM access"
            ClientMode.LEGACY -> "Using legacy Python backend"
        }
    }

    private fun logConfigurationWarnings() {
        val settings = AgentSettings.getInstance()
        val warnings = mutableListOf<String>()

        // Check ACP configuration
        if (settings.useAcpMode) {
            if (settings.acpAgentPath.isBlank()) {
                warnings.add("ACP mode enabled but agent path not configured")
            } else {
                val agentFile = File(settings.acpAgentPath)
                if (!agentFile.exists()) {
                    warnings.add("ACP agent path does not exist: ${settings.acpAgentPath}")
                } else if (!agentFile.canExecute()) {
                    warnings.add("ACP agent is not executable: ${settings.acpAgentPath}")
                }
            }
        }

        // Check OpenRouter configuration
        if (settings.useOpenRouterDirect && settings.openrouterApiKey.isBlank()) {
            warnings.add("OpenRouter direct mode enabled but API key not configured")
        }

        // Log warnings
        if (warnings.isNotEmpty()) {
            log.warn("Configuration Warnings:")
            warnings.forEach { warning ->
                log.warn("  ⚠️ $warning")
            }
        } else {
            log.info("No configuration warnings detected")
        }
    }
}
