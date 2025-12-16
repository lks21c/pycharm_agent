package com.hdsp.pycharm_agent.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.annotations.XCollection

/**
 * Persistent settings for PyCharm Agent plugin
 */
@State(
    name = "com.hdsp.pycharm_agent.settings.AgentSettings",
    storages = [Storage("PycharmAgentSettings.xml")]
)
class AgentSettings : PersistentStateComponent<AgentSettings.State> {

    data class State(
        var backendUrl: String = "http://localhost:8000",
        var provider: String = "gemini",
        // Gemini settings - multi-key support (up to 10)
        @XCollection(style = XCollection.Style.v2)
        var geminiApiKeys: MutableList<String> = mutableListOf(),
        var geminiModel: String = "gemini-2.5-flash",
        // OpenAI settings
        var openaiApiKey: String = "",
        var openaiModel: String = "gpt-4",
        // vLLM settings
        var vllmEndpoint: String = "http://localhost:8000",
        var vllmModel: String = "meta-llama/Llama-2-7b-chat-hf",
        var vllmApiKey: String = "",
        // Agent behavior
        var autoAcceptDiff: Boolean = false,
        var showDiffPreview: Boolean = true,
        var autoExecuteMode: Boolean = false
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

    // Gemini multi-key management
    var geminiApiKeys: MutableList<String>
        get() = state.geminiApiKeys
        set(value) { state.geminiApiKeys = value }

    var geminiModel: String
        get() = state.geminiModel
        set(value) { state.geminiModel = value }

    // OpenAI
    var openaiApiKey: String
        get() = state.openaiApiKey
        set(value) { state.openaiApiKey = value }

    var openaiModel: String
        get() = state.openaiModel
        set(value) { state.openaiModel = value }

    // vLLM
    var vllmEndpoint: String
        get() = state.vllmEndpoint
        set(value) { state.vllmEndpoint = value }

    var vllmModel: String
        get() = state.vllmModel
        set(value) { state.vllmModel = value }

    var vllmApiKey: String
        get() = state.vllmApiKey
        set(value) { state.vllmApiKey = value }

    // Behavior
    var autoAcceptDiff: Boolean
        get() = state.autoAcceptDiff
        set(value) { state.autoAcceptDiff = value }

    var showDiffPreview: Boolean
        get() = state.showDiffPreview
        set(value) { state.showDiffPreview = value }

    var autoExecuteMode: Boolean
        get() = state.autoExecuteMode
        set(value) { state.autoExecuteMode = value }

    companion object {
        const val MAX_GEMINI_KEYS = 10

        fun getInstance(): AgentSettings {
            return ApplicationManager.getApplication().getService(AgentSettings::class.java)
        }
    }
}
