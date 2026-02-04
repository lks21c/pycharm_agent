package com.hdsp.pycharm_agent.settings

import com.hdsp.pycharm_agent.services.AgentClientFactory
import com.hdsp.pycharm_agent.ui.AgentModeStatusWidget
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.WindowManager

/**
 * Settings Change Notifier
 *
 * Notifies components when plugin settings change.
 * This allows components to refresh their state without restart.
 */
@Service(Service.Level.APP)
class SettingsChangeNotifier {

    private val log = Logger.getInstance(SettingsChangeNotifier::class.java)
    private val listeners = mutableListOf<SettingsChangeListener>()

    companion object {
        fun getInstance(): SettingsChangeNotifier {
            return ApplicationManager.getApplication().getService(SettingsChangeNotifier::class.java)
        }
    }

    /**
     * Notify all components that settings have changed
     */
    fun notifySettingsChanged() {
        log.info("Settings changed, notifying components")

        // Notify all registered listeners
        listeners.forEach { listener ->
            try {
                listener.onSettingsChanged()
            } catch (e: Exception) {
                log.error("Error notifying settings listener", e)
            }
        }

        // Refresh all project-level components
        ProjectManager.getInstance().openProjects.forEach { project ->
            refreshProjectComponents(project)
        }
    }

    /**
     * Refresh components for a specific project
     */
    fun refreshProjectComponents(project: Project) {
        try {
            // Refresh client factory
            AgentClientFactory.getInstance(project).refresh()
            log.info("Refreshed AgentClientFactory for project: ${project.name}")

            // Refresh status bar widget
            val statusBar = WindowManager.getInstance().getStatusBar(project)
            val widget = statusBar?.getWidget(AgentModeStatusWidget.ID) as? AgentModeStatusWidget
            widget?.refresh()
        } catch (e: Exception) {
            log.warn("Failed to refresh project components", e)
        }
    }

    /**
     * Add a settings change listener
     */
    fun addListener(listener: SettingsChangeListener) {
        listeners.add(listener)
    }

    /**
     * Remove a settings change listener
     */
    fun removeListener(listener: SettingsChangeListener) {
        listeners.remove(listener)
    }
}

/**
 * Listener interface for settings changes
 */
interface SettingsChangeListener {
    fun onSettingsChanged()
}

/**
 * Extension function to easily trigger settings change notification
 */
fun AgentSettings.notifyChanged() {
    SettingsChangeNotifier.getInstance().notifySettingsChanged()
}
