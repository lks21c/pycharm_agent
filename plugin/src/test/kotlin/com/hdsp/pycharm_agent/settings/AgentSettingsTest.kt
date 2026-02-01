package com.hdsp.pycharm_agent.settings

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested

/**
 * Unit tests for AgentSettings
 *
 * Tests the settings persistence, API key rotation, and provider configuration.
 * These tests don't require IntelliJ platform services.
 */
class AgentSettingsTest {

    private lateinit var settings: AgentSettings

    @BeforeEach
    fun setUp() {
        settings = AgentSettings()
    }

    @Nested
    @DisplayName("State Initialization")
    inner class StateInitialization {

        @Test
        fun `default state has correct initial values`() {
            val state = settings.state
            assertEquals("http://localhost:8000", state.backendUrl)
            assertEquals("gemini", state.provider)
            assertEquals("gemini-2.5-flash", state.geminiModel)
            assertEquals("gpt-4", state.openaiModel)
            assertFalse(state.autoApprove)
            assertTrue(state.geminiApiKeys.isEmpty())
        }

        @Test
        fun `loadState correctly applies state`() {
            val newState = AgentSettings.State(
                backendUrl = "http://custom:9000",
                provider = "openai",
                geminiApiKeys = mutableListOf("key1", "key2"),
                autoApprove = true
            )

            settings.loadState(newState)

            assertEquals("http://custom:9000", settings.backendUrl)
            assertEquals("openai", settings.provider)
            assertEquals(2, settings.geminiApiKeys.size)
            assertTrue(settings.autoApprove)
        }
    }

    @Nested
    @DisplayName("Property Accessors")
    inner class PropertyAccessors {

        @Test
        fun `backendUrl getter and setter work correctly`() {
            settings.backendUrl = "http://test:8080"
            assertEquals("http://test:8080", settings.backendUrl)
        }

        @Test
        fun `provider getter and setter work correctly`() {
            settings.provider = "vllm"
            assertEquals("vllm", settings.provider)
        }

        @Test
        fun `geminiModel getter and setter work correctly`() {
            settings.geminiModel = "gemini-pro"
            assertEquals("gemini-pro", settings.geminiModel)
        }

        @Test
        fun `openaiApiKey getter and setter work correctly`() {
            settings.openaiApiKey = "sk-test-key"
            assertEquals("sk-test-key", settings.openaiApiKey)
        }

        @Test
        fun `vllm settings getter and setter work correctly`() {
            settings.vllmEndpoint = "http://vllm:8000"
            settings.vllmModel = "llama-3"
            settings.vllmApiKey = "vllm-key"

            assertEquals("http://vllm:8000", settings.vllmEndpoint)
            assertEquals("llama-3", settings.vllmModel)
            assertEquals("vllm-key", settings.vllmApiKey)
        }

        @Test
        fun `boolean settings work correctly`() {
            settings.autoApprove = true
            settings.autoAcceptDiff = true
            settings.showDiffPreview = false
            settings.autoExecuteMode = true

            assertTrue(settings.autoApprove)
            assertTrue(settings.autoAcceptDiff)
            assertFalse(settings.showDiffPreview)
            assertTrue(settings.autoExecuteMode)
        }

        @Test
        fun `systemPrompt getter and setter work correctly`() {
            settings.systemPrompt = "You are a helpful assistant"
            assertEquals("You are a helpful assistant", settings.systemPrompt)
        }

        @Test
        fun `idleTimeoutMinutes getter and setter work correctly`() {
            settings.idleTimeoutMinutes = 30
            assertEquals(30, settings.idleTimeoutMinutes)
        }
    }

    @Nested
    @DisplayName("API Key Rotation")
    inner class ApiKeyRotation {

        @BeforeEach
        fun setUpKeys() {
            settings.geminiApiKeys = mutableListOf("key1", "key2", "key3")
        }

        @Test
        fun `getCurrentGeminiKey returns first key initially`() {
            val key = settings.getCurrentGeminiKey()
            assertEquals("key1", key)
        }

        @Test
        fun `getCurrentGeminiKey returns null when no keys configured`() {
            settings.geminiApiKeys = mutableListOf()
            assertNull(settings.getCurrentGeminiKey())
        }

        @Test
        fun `getCurrentGeminiKey skips blank keys`() {
            settings.geminiApiKeys = mutableListOf("", "key2", "  ")
            assertEquals("key2", settings.getCurrentGeminiKey())
        }

        @Test
        fun `markKeyAsRateLimited marks key correctly`() {
            settings.markKeyAsRateLimited(0)
            val nextKey = settings.getNextValidKey()
            assertEquals("key2", nextKey)
        }

        @Test
        fun `getNextValidKey returns next non-rate-limited key`() {
            settings.markKeyAsRateLimited(0)
            settings.markKeyAsRateLimited(1)

            val nextKey = settings.getNextValidKey()
            assertEquals("key3", nextKey)
        }

        @Test
        fun `getNextValidKey returns null when all keys rate limited`() {
            settings.markKeyAsRateLimited(0)
            settings.markKeyAsRateLimited(1)
            settings.markKeyAsRateLimited(2)

            assertNull(settings.getNextValidKey())
        }

        @Test
        fun `resetKeyRotation clears rate limits`() {
            settings.markKeyAsRateLimited(0)
            settings.markKeyAsRateLimited(1)
            settings.resetKeyRotation()

            assertEquals("key1", settings.getCurrentGeminiKey())
        }

        @Test
        fun `getValidKeyCount returns correct count`() {
            assertEquals(3, settings.getValidKeyCount())

            settings.geminiApiKeys = mutableListOf("key1", "", "key3", "  ")
            assertEquals(2, settings.getValidKeyCount())
        }

        @Test
        fun `getCurrentKeyIndex returns current index`() {
            assertEquals(0, settings.getCurrentKeyIndex())

            settings.markKeyAsRateLimited(0)
            settings.getNextValidKey()

            assertEquals(1, settings.getCurrentKeyIndex())
        }

        @Test
        fun `key rotation wraps around correctly`() {
            // Move to key2 (index 1)
            settings.markKeyAsRateLimited(0)
            settings.getNextValidKey()

            // Move to key3 (index 2)
            settings.markKeyAsRateLimited(1)
            settings.getNextValidKey()

            // Now with 0, 1 rate limited, getting next should fail if 2 is also limited
            settings.markKeyAsRateLimited(2)
            assertNull(settings.getNextValidKey())
        }
    }

    @Nested
    @DisplayName("State Serialization")
    inner class StateSerialization {

        @Test
        fun `getState returns current state`() {
            settings.backendUrl = "http://test:9000"
            settings.provider = "openai"

            val state = settings.state
            assertEquals("http://test:9000", state.backendUrl)
            assertEquals("openai", state.provider)
        }

        @Test
        fun `state changes are reflected in getState`() {
            settings.geminiApiKeys.add("new-key")
            settings.autoApprove = true

            val state = settings.state
            assertTrue(state.geminiApiKeys.contains("new-key"))
            assertTrue(state.autoApprove)
        }
    }

    @Nested
    @DisplayName("Constants")
    inner class Constants {

        @Test
        fun `MAX_GEMINI_KEYS is 10`() {
            assertEquals(10, AgentSettings.MAX_GEMINI_KEYS)
        }
    }
}
