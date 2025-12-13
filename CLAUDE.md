# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

PyCharm Agent is an AI-powered coding assistant plugin for PyCharm with two modes:
- **Chat Mode**: Simple prompt/response with streaming
- **Agent Mode**: Plan-execute pattern with diff preview and user approval

## Architecture

Two-tier system:
1. **Plugin (Kotlin)**: IntelliJ Platform plugin providing UI and editor integration
2. **Backend (Python)**: FastAPI server handling LLM communication and code generation

Communication: HTTP/SSE between plugin and backend on localhost:8000

## Build & Run Commands

### Backend
```bash
cd backend
pip install fastapi uvicorn aiohttp pydantic sse-starlette eval_type_backport
python -m uvicorn main:app --host 0.0.0.0 --port 8000
```

### Plugin
```bash
cd plugin
./gradlew runIde          # Launch test PyCharm instance with plugin
./gradlew buildPlugin     # Build distributable zip
./gradlew test            # Run tests
```

## Key Architectural Patterns

### Plugin-Backend Communication
- `BackendClient.kt` uses OkHttp with SSE for streaming responses
- Chat streaming: `/api/chat/stream` returns SSE events with `type: content|done|error`
- Agent flow: `/api/agent/plan` -> `/api/agent/execute-step` (per step)

### Diff Application Flow
1. Backend generates `DiffResult` with hunks and preview content
2. `DiffApplicationService.kt` stages diff and shows inline highlights
3. User accepts (Tab) or rejects (Esc) via `AcceptDiffAction`/`RejectDiffAction`
4. On accept, full file content is replaced with `previewContent`

### LLM Provider Abstraction
`LLMService` in `backend/services/llm_service.py` supports:
- Gemini (default) - with thinking mode for 2.5 models
- OpenAI - GPT-4 compatible
- vLLM - self-hosted models

Configuration stored in `~/.pycharm_agent/config.json`

## Configuration

API keys and provider selection: `~/.pycharm_agent/config.json`
```json
{
  "provider": "gemini",
  "gemini": { "apiKey": "...", "model": "gemini-2.5-flash" }
}
```

Plugin settings: Settings -> Tools -> PyCharm Agent

## Plugin Structure

- `toolwindow/`: Tool window UI (AgentToolWindowFactory, MainAgentPanel)
- `services/`: BackendClient (HTTP), DiffApplicationService (editor integration)
- `settings/`: AgentSettings (persistence), AgentSettingsConfigurable (UI)
- `actions/`: AcceptDiffAction, RejectDiffAction (Tab/Esc handlers)

## Backend Structure

- `routers/`: FastAPI endpoints (chat, agent, config)
- `services/`: LLMService (provider abstraction), ConfigManager, DiffGenerator
- `models/`: Pydantic models for request/response validation

## Platform Compatibility

- Target: PyCharm 2025.2 (build 252-253.*)
- Kotlin JVM toolchain: 21
- Python: 3.9+
