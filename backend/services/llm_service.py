"""
LLM Service - Handles interactions with different LLM providers
Ported from hdsp_agent for PyCharm Agent Backend
"""

from __future__ import annotations

import asyncio
import json
from contextlib import asynccontextmanager
from typing import Any, Optional

import aiohttp

# Optional key manager import - will be used if available
_key_manager = None


def set_key_manager(manager):
    """Set the key manager instance for Gemini key rotation"""
    global _key_manager
    _key_manager = manager


class LLMService:
    """Service for interacting with various LLM providers"""

    def __init__(self, config: dict[str, Any]):
        self.config = config
        self.provider = config.get("provider", "gemini")

    # ========== Config Helpers ==========

    async def _get_gemini_config_async(self) -> tuple[str, str, str, Optional[str]]:
        """Get Gemini config with key rotation: (api_key, model, base_url, key_id). Raises if no api_key available."""
        global _key_manager
        cfg = self.config.get("gemini", {})
        model = cfg.get("model", "gemini-2.5-flash")
        base_url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}"

        # Try to get key from key manager (for multi-key rotation)
        if _key_manager:
            api_key, key_id = await _key_manager.get_available_key()
            if api_key:
                return api_key, model, base_url, key_id

        # Fallback to single key from config
        api_key = cfg.get("apiKey")
        if not api_key:
            raise ValueError("Gemini API key not configured")

        return api_key, model, base_url, None

    def _get_gemini_config(self) -> tuple[str, str, str]:
        """Get Gemini config (sync version): (api_key, model, base_url). Raises if api_key missing."""
        cfg = self.config.get("gemini", {})
        api_key = cfg.get("apiKey")
        if not api_key:
            raise ValueError("Gemini API key not configured")
        model = cfg.get("model", "gemini-2.5-flash")
        base_url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}"
        return api_key, model, base_url

    def _get_openai_config(self) -> tuple[str, str, dict[str, str]]:
        """Get OpenAI config: (model, url, headers). Raises if api_key missing."""
        cfg = self.config.get("openai", {})
        api_key = cfg.get("apiKey")
        if not api_key:
            raise ValueError("OpenAI API key not configured")
        model = cfg.get("model", "gpt-4")
        url = "https://api.openai.com/v1/chat/completions"
        headers = {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}
        return model, url, headers

    def _get_vllm_config(self) -> tuple[str, str, dict[str, str]]:
        """Get vLLM config: (model, url, headers)."""
        cfg = self.config.get("vllm", {})
        endpoint = cfg.get("endpoint", "http://localhost:8000")
        model = cfg.get("model", "default")
        url = f"{endpoint}/v1/chat/completions"
        headers = {"Content-Type": "application/json"}
        if cfg.get("apiKey"):
            headers["Authorization"] = f"Bearer {cfg['apiKey']}"
        return model, url, headers

    # ========== Message/Payload Builders ==========

    def _build_prompt(self, prompt: str, context: str | None = None) -> str:
        """Build full prompt with optional context"""
        if context:
            return f"Context:\n{context}\n\nUser Request:\n{prompt}"
        return prompt

    def _build_openai_messages(self, prompt: str, context: str | None = None) -> list:
        """Build OpenAI-style messages array"""
        messages = []
        if context:
            messages.append({"role": "system", "content": f"Context:\n{context}"})
        messages.append({"role": "user", "content": prompt})
        return messages

    async def _retry_with_backoff(
        self,
        operation,
        max_retries: int = 3,
        provider: str = "API",
        retryable_statuses: tuple = (503, 429),
    ):
        """Execute operation with exponential backoff retry logic"""
        for attempt in range(max_retries):
            try:
                return await operation()
            except asyncio.TimeoutError:
                if attempt < max_retries - 1:
                    wait_time = (2**attempt) * 3
                    print(
                        f"[LLMService] Request timeout. Retrying in {wait_time}s... "
                        f"(attempt {attempt + 1}/{max_retries})"
                    )
                    await asyncio.sleep(wait_time)
                    continue
                raise Exception(f"Request timeout after {max_retries} retries")
            except Exception as e:
                error_msg = str(e)
                # Rate limit (429)
                if "rate limit" in error_msg.lower() or "(429)" in error_msg:
                    if attempt < max_retries - 1:
                        wait_time = 40 + (attempt * 20)
                        print(
                            f"[LLMService] Rate limit hit. Waiting {wait_time}s before retry... "
                            f"(attempt {attempt + 1}/{max_retries})"
                        )
                        await asyncio.sleep(wait_time)
                        continue
                    raise Exception(
                        f"Rate limit exceeded after {max_retries} retries. "
                        "Please wait a minute and try again."
                    )
                # Server overloaded (503)
                if "overloaded" in error_msg.lower() or "(503)" in error_msg:
                    if attempt < max_retries - 1:
                        wait_time = (2**attempt) * 5
                        print(
                            f"[LLMService] Server overloaded. Retrying in {wait_time}s... "
                            f"(attempt {attempt + 1}/{max_retries})"
                        )
                        await asyncio.sleep(wait_time)
                        continue
                    raise
                # Other API errors - fail immediately
                if "API error" in error_msg and "rate limit" not in error_msg.lower():
                    raise
                if "timeout" in error_msg.lower():
                    raise
                # Network errors - retry
                if attempt < max_retries - 1:
                    wait_time = (2**attempt) * 2
                    print(
                        f"[LLMService] Network error: {e}. Retrying in {wait_time}s... "
                        f"(attempt {attempt + 1}/{max_retries})"
                    )
                    await asyncio.sleep(wait_time)
                    continue
                raise

    @asynccontextmanager
    async def _request(
        self,
        url: str,
        payload: dict[str, Any],
        headers: dict[str, str] | None = None,
        timeout_seconds: int = 60,
        provider: str = "API",
    ):
        """Context manager for HTTP POST requests with automatic session cleanup"""
        timeout = aiohttp.ClientTimeout(total=timeout_seconds)
        async with aiohttp.ClientSession(timeout=timeout) as session:
            async with session.post(url, json=payload, headers=headers) as response:
                if response.status != 200:
                    error_text = await response.text()
                    print(f"[LLMService] {provider} API Error: {error_text}")
                    raise Exception(f"{provider} API error: {error_text}")
                yield response

    async def _request_json(
        self,
        url: str,
        payload: dict[str, Any],
        headers: dict[str, str] | None = None,
        timeout_seconds: int = 60,
        provider: str = "API",
    ) -> dict[str, Any]:
        """Make request and return JSON response"""
        async with self._request(url, payload, headers, timeout_seconds, provider) as response:
            return await response.json()

    async def _stream_response(
        self, url: str, payload: dict[str, Any], headers: dict[str, str] | None, provider: str, line_parser
    ):
        """Stream response and yield parsed content"""
        async with self._request(url, payload, headers, timeout_seconds=120, provider=provider) as response:
            async for line in response.content:
                line_text = line.decode("utf-8").strip()
                content = line_parser(line_text)
                if content:
                    yield content

    # ========== Response Parsers ==========

    def _parse_openai_response(self, data: dict[str, Any]) -> str:
        """Parse OpenAI-compatible response format"""
        if "choices" in data and len(data["choices"]) > 0:
            choice = data["choices"][0]
            if "message" in choice and "content" in choice["message"]:
                return choice["message"]["content"]
            elif "text" in choice:
                return choice["text"]
        raise Exception("No valid response from API")

    def _extract_gemini_text(self, data: dict[str, Any]) -> str | None:
        """Extract text from Gemini response data"""
        if "candidates" in data and len(data["candidates"]) > 0:
            candidate = data["candidates"][0]
            if "content" in candidate and "parts" in candidate["content"]:
                parts = candidate["content"]["parts"]
                if len(parts) > 0 and "text" in parts[0]:
                    return parts[0]["text"]
        return None

    def _parse_gemini_response(self, data: dict[str, Any]) -> str:
        """Parse Gemini API response format"""
        text = self._extract_gemini_text(data)
        if text is not None:
            return text
        raise Exception("No valid response from Gemini API")

    def _parse_sse_line(self, line_text: str, extractor) -> str | None:
        """Parse SSE line with given extractor function"""
        if not line_text.startswith("data: "):
            return None
        data_str = line_text[6:]
        if data_str == "[DONE]":
            return None
        try:
            data = json.loads(data_str)
            return extractor(data)
        except json.JSONDecodeError:
            return None

    def _extract_openai_delta(self, data: dict[str, Any]) -> str | None:
        """Extract content delta from OpenAI stream data"""
        if "choices" in data and len(data["choices"]) > 0:
            delta = data["choices"][0].get("delta", {})
            return delta.get("content", "") or None
        return None

    def _parse_openai_stream_line(self, line_text: str) -> str | None:
        """Parse a single SSE line from OpenAI-compatible stream"""
        return self._parse_sse_line(line_text, self._extract_openai_delta)

    def _parse_gemini_stream_line(self, line_text: str) -> str | None:
        """Parse a single SSE line from Gemini stream"""
        return self._parse_sse_line(line_text, self._extract_gemini_text)

    def _build_openai_payload(
        self,
        model: str,
        messages: list,
        max_tokens: int = 4096,
        temperature: float = 0.0,
        stream: bool = False,
    ) -> dict[str, Any]:
        """Build OpenAI-compatible request payload"""
        return {
            "model": model,
            "messages": messages,
            "max_tokens": max_tokens,
            "temperature": temperature,
            "stream": stream,
        }

    def _build_gemini_payload(
        self,
        prompt: str,
        max_output_tokens: int = 32768,
        temperature: float | None = None,
    ) -> dict[str, Any]:
        """Build Gemini API request payload"""
        cfg = self.config.get("gemini", {})
        temp = temperature if temperature is not None else cfg.get("temperature", 0.0)
        model = cfg.get("model", "gemini-2.5-flash")

        payload = {
            "contents": [{"parts": [{"text": prompt}]}],
            "generationConfig": {
                "temperature": temp,
                "topK": 1,
                "topP": 0.95,
                "maxOutputTokens": max_output_tokens,
            },
            "safetySettings": [
                {"category": "HARM_CATEGORY_HARASSMENT", "threshold": "BLOCK_MEDIUM_AND_ABOVE"},
                {"category": "HARM_CATEGORY_HATE_SPEECH", "threshold": "BLOCK_MEDIUM_AND_ABOVE"},
                {"category": "HARM_CATEGORY_SEXUALLY_EXPLICIT", "threshold": "BLOCK_MEDIUM_AND_ABOVE"},
                {"category": "HARM_CATEGORY_DANGEROUS_CONTENT", "threshold": "BLOCK_MEDIUM_AND_ABOVE"},
            ],
        }

        # Gemini 2.5 models have built-in "thinking"
        if "2.5" in model or "2-5" in model:
            payload["generationConfig"]["thinkingConfig"] = {"thinkingBudget": 8192}

        return payload

    async def generate_response_stream(self, prompt: str, context: str | None = None):
        """Generate a streaming response from the configured LLM provider"""
        if self.provider == "gemini":
            async for chunk in self._call_gemini_stream(prompt, context):
                yield chunk
        elif self.provider == "vllm":
            async for chunk in self._call_vllm_stream(prompt, context):
                yield chunk
        elif self.provider == "openai":
            async for chunk in self._call_openai_stream(prompt, context):
                yield chunk
        else:
            raise ValueError(f"Unsupported provider: {self.provider}")

    async def generate_response(self, prompt: str, context: str | None = None) -> str:
        """Generate a response from the configured LLM provider"""
        if self.provider == "gemini":
            return await self._call_gemini(prompt, context)
        elif self.provider == "vllm":
            return await self._call_vllm(prompt, context)
        elif self.provider == "openai":
            return await self._call_openai(prompt, context)
        else:
            raise ValueError(f"Unsupported provider: {self.provider}")

    async def _call_gemini(self, prompt: str, context: str | None = None, max_retries: int = 3) -> str:
        """Call Google Gemini API with retry logic and key rotation"""
        global _key_manager

        api_key, model, base_url, key_id = await self._get_gemini_config_async()
        print(f"[LLMService] Calling Gemini API with model: {model}" + (f" (key: {key_id})" if key_id else ""))

        url = f"{base_url}:generateContent?key={api_key}"
        full_prompt = self._build_prompt(prompt, context)
        payload = self._build_gemini_payload(full_prompt)

        async def _execute_request():
            nonlocal api_key, url, key_id
            timeout = aiohttp.ClientTimeout(total=60)
            async with aiohttp.ClientSession(timeout=timeout) as session:
                async with session.post(url, json=payload) as response:
                    if response.status == 429:
                        error_text = await response.text()
                        # Handle rate limit with key rotation
                        if _key_manager and key_id:
                            await _key_manager.mark_key_rate_limited(
                                key_id,
                                error_message=error_text
                            )
                            # Try to get a new key
                            api_key, key_id = await _key_manager.get_available_key()
                            if api_key:
                                url = f"{base_url}:generateContent?key={api_key}"
                                print(f"[LLMService] Rate limited, switching to key: {key_id}")
                        raise Exception(f"Gemini API rate limit (429): {error_text}")

                    if response.status == 503:
                        error_text = await response.text()
                        raise Exception(f"Gemini API overloaded (503): {error_text}")

                    if response.status != 200:
                        error_text = await response.text()
                        print(f"[LLMService] Gemini API Error: {error_text}")
                        raise Exception(f"Gemini API error: {error_text}")

                    data = await response.json()
                    response_text = self._parse_gemini_response(data)

                    # Mark key as successful
                    if _key_manager and key_id:
                        await _key_manager.mark_key_success(key_id)

                    print(f"[LLMService] Received response from {model} (length: {len(response_text)} chars)")
                    return response_text

        return await self._retry_with_backoff(_execute_request, max_retries, "Gemini")

    async def _call_vllm(self, prompt: str, context: str | None = None) -> str:
        """Call vLLM endpoint with OpenAI Compatible API"""
        model, url, headers = self._get_vllm_config()
        full_prompt = self._build_prompt(prompt, context)
        messages = [{"role": "user", "content": full_prompt}]
        payload = self._build_openai_payload(model, messages, stream=False)

        data = await self._request_json(url, payload, headers, provider="vLLM")
        return self._parse_openai_response(data)

    async def _call_openai(self, prompt: str, context: str | None = None) -> str:
        """Call OpenAI API"""
        model, url, headers = self._get_openai_config()
        messages = self._build_openai_messages(prompt, context)
        payload = self._build_openai_payload(model, messages, max_tokens=2000, stream=False)

        data = await self._request_json(url, payload, headers, provider="OpenAI")
        return self._parse_openai_response(data)

    async def _call_gemini_stream(self, prompt: str, context: str | None = None):
        """Call Google Gemini API with streaming and key rotation"""
        global _key_manager

        api_key, model, base_url, key_id = await self._get_gemini_config_async()
        url = f"{base_url}:streamGenerateContent?key={api_key}&alt=sse"
        full_prompt = self._build_prompt(prompt, context)
        payload = self._build_gemini_payload(full_prompt)

        try:
            async for content in self._stream_response(url, payload, None, "Gemini", self._parse_gemini_stream_line):
                yield content
            # Mark key as successful after streaming completes
            if _key_manager and key_id:
                await _key_manager.mark_key_success(key_id)
        except Exception as e:
            # Handle rate limit in streaming
            if "429" in str(e) and _key_manager and key_id:
                await _key_manager.mark_key_rate_limited(key_id, error_message=str(e))
            raise

    async def _call_vllm_stream(self, prompt: str, context: str | None = None):
        """Call vLLM endpoint with streaming"""
        model, url, headers = self._get_vllm_config()
        full_prompt = self._build_prompt(prompt, context)
        messages = [{"role": "user", "content": full_prompt}]
        payload = self._build_openai_payload(model, messages, stream=True)

        async for content in self._stream_response(url, payload, headers, "vLLM", self._parse_openai_stream_line):
            yield content

    async def _call_openai_stream(self, prompt: str, context: str | None = None):
        """Call OpenAI API with streaming"""
        model, url, headers = self._get_openai_config()
        messages = self._build_openai_messages(prompt, context)
        payload = self._build_openai_payload(model, messages, max_tokens=2000, stream=True)

        async for content in self._stream_response(url, payload, headers, "OpenAI", self._parse_openai_stream_line):
            yield content


# ═══════════════════════════════════════════════════════════════════════════
# Module-level helper functions
# ═══════════════════════════════════════════════════════════════════════════


async def call_llm(prompt: str, config: dict[str, Any], context: str | None = None) -> str:
    """Convenience function to call LLM with the given config."""
    service = LLMService(config)
    return await service.generate_response(prompt, context)


async def call_llm_stream(prompt: str, config: dict[str, Any], context: str | None = None):
    """Convenience function to stream LLM response with the given config."""
    service = LLMService(config)
    async for chunk in service.generate_response_stream(prompt, context):
        yield chunk
