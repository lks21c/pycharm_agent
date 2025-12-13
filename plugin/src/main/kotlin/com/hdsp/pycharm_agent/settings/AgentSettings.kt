package com.hdsp.pycharm_agent.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Persistent settings for PyCharm Agent plugin
 */
@State(
    name = "com.hdsp.pycharm_agent.settings.AgentSettings",
    storages = [Storage("PycharmAgentSettings.xml")]
)
class AgentSettings : PersistentStateComponent<AgentSettings.State> {

    data class State(
        var backendUrl: String = "http://localhost:8765",
        var provider: String = "gemini",
        var geminiApiKey: String = "",
        var geminiModel: String = "gemini-2.5-flash",
        var openaiApiKey: String = "",
        var openaiModel: String = "gpt-4",
        var vllmEndpoint: String = "http://localhost:8000",
        var vllmModel: String = "",
        var autoAcceptDiff: Boolean = false,
        var showDiffPreview: Boolean = true
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var backendUrl: String
        get() = state.backendUrl
        set(value) { state.backendUrl = value }

    var provider: String
        get() = state.provider
        set(value) { state.provider = value }

    var geminiApiKey: String
        get() = state.geminiApiKey
        set(value) { state.geminiApiKey = value }

    var geminiModel: String
        get() = state.geminiModel
        set(value) { state.geminiModel = value }

    var openaiApiKey: String
        get() = state.openaiApiKey
        set(value) { state.openaiApiKey = value }

    var openaiModel: String
        get() = state.openaiModel
        set(value) { state.openaiModel = value }

    var vllmEndpoint: String
        get() = state.vllmEndpoint
        set(value) { state.vllmEndpoint = value }

    var vllmModel: String
        get() = state.vllmModel
        set(value) { state.vllmModel = value }

    var autoAcceptDiff: Boolean
        get() = state.autoAcceptDiff
        set(value) { state.autoAcceptDiff = value }

    var showDiffPreview: Boolean
        get() = state.showDiffPreview
        set(value) { state.showDiffPreview = value }

    companion object {
        fun getInstance(): AgentSettings {
            return ApplicationManager.getApplication().getService(AgentSettings::class.java)
        }
    }
}
