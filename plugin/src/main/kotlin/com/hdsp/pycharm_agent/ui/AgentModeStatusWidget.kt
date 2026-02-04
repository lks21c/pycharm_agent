package com.hdsp.pycharm_agent.ui

import com.hdsp.pycharm_agent.services.AgentClientAdapter
import com.hdsp.pycharm_agent.services.AgentClientFactory
import com.hdsp.pycharm_agent.services.ClientMode
import com.hdsp.pycharm_agent.settings.AgentSettings
import com.hdsp.pycharm_agent.settings.AgentSettingsConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import java.awt.Component
import java.awt.event.MouseEvent
import javax.swing.Icon

/**
 * Status bar widget showing current agent mode and connection status
 */
class AgentModeStatusWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {

    companion object {
        const val ID = "PyCharmAgentMode"
    }

    private var statusBar: StatusBar? = null
    private var currentState = AgentClientAdapter.ConnectionState.DISCONNECTED
    private var currentMode = ClientMode.LEGACY

    init {
        // Listen for state changes
        val factory = AgentClientFactory.getInstance(project)
        val client = factory.getClient()
        currentMode = factory.getCurrentMode()

        client.addStateListener { state ->
            currentState = state
            statusBar?.updateWidget(ID)
        }
    }

    override fun ID(): String = ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        // Note: StatusBar doesn't implement Disposable, so we don't register with it
        // The widget is managed by the StatusBarWidgetFactory lifecycle
    }

    override fun dispose() {
        statusBar = null
    }

    // ═══════════════════════════════════════════════════════════════
    // TextPresentation
    // ═══════════════════════════════════════════════════════════════

    override fun getText(): String {
        val modeText = when (currentMode) {
            ClientMode.ACP -> "ACP"
            ClientMode.OPENROUTER -> "OpenRouter"
            ClientMode.LEGACY -> "Legacy"
        }

        val stateIcon = when (currentState) {
            AgentClientAdapter.ConnectionState.CONNECTED -> "●"
            AgentClientAdapter.ConnectionState.CONNECTING -> "◐"
            AgentClientAdapter.ConnectionState.DISCONNECTED -> "○"
            AgentClientAdapter.ConnectionState.ERROR -> "✕"
        }

        return "$stateIcon $modeText"
    }

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getTooltipText(): String {
        val modeDesc = when (currentMode) {
            ClientMode.ACP -> "ACP Agent (External CLI)"
            ClientMode.OPENROUTER -> "OpenRouter Direct API"
            ClientMode.LEGACY -> "Legacy Python Backend"
        }

        val stateDesc = when (currentState) {
            AgentClientAdapter.ConnectionState.CONNECTED -> "Connected"
            AgentClientAdapter.ConnectionState.CONNECTING -> "Connecting..."
            AgentClientAdapter.ConnectionState.DISCONNECTED -> "Disconnected"
            AgentClientAdapter.ConnectionState.ERROR -> "Connection Error"
        }

        val settings = AgentSettings.getInstance()
        val extraInfo = when (currentMode) {
            ClientMode.ACP -> {
                val agentPath = settings.acpAgentPath.ifBlank { "Not configured" }
                "\nAgent: $agentPath"
            }
            ClientMode.OPENROUTER -> {
                val model = settings.openrouterModel
                "\nModel: $model"
            }
            ClientMode.LEGACY -> {
                val url = settings.backendUrl
                "\nBackend: $url"
            }
        }

        return "PyCharm Agent\nMode: $modeDesc\nStatus: $stateDesc$extraInfo\n\nClick to open settings"
    }

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
        ShowSettingsUtil.getInstance().editConfigurable(project, AgentSettingsConfigurable())
    }

    /**
     * Refresh the widget state
     */
    fun refresh() {
        val factory = AgentClientFactory.getInstance(project)
        currentMode = factory.getCurrentMode()
        currentState = factory.getClient().getConnectionState()
        statusBar?.updateWidget(ID)
    }
}

/**
 * Factory for creating AgentModeStatusWidget
 */
class AgentModeStatusWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = AgentModeStatusWidget.ID

    override fun getDisplayName(): String = "PyCharm Agent Mode"

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget {
        return AgentModeStatusWidget(project)
    }

    override fun disposeWidget(widget: StatusBarWidget) {
        // Widget disposes itself via Disposer
    }

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}
