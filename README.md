# PyCharm Agent

AI-powered coding assistant for PyCharm with Chat and Agent modes.

## Features

### Chat Mode
- Simple prompt/response interface
- Streaming responses with real-time display
- Code blocks with syntax highlighting and copy button
- Conversation history within session

### Agent Mode
- Automatic code modifications with plan-and-execute pattern
- GitHub Copilot-style diff preview
- Accept/Reject changes with Tab/Esc shortcuts
- Step-by-step execution with user approval

## Architecture

```
┌──────────────────────────────────┐
│   PyCharm Plugin (Kotlin)        │
│   - Chat Panel (Tool Window)     │
│   - Agent Panel                  │
│   - Diff Highlighter (Editor)    │
└────────────┬─────────────────────┘
             │ HTTP/SSE
┌────────────┴─────────────────────┐
│   FastAPI Backend (Python)       │
│   - Chat API (streaming)         │
│   - Agent API (plan/execute)     │
│   - LLM Service (Gemini/OpenAI)  │
└──────────────────────────────────┘
```

## Prerequisites

- **Python 3.9+** with pip
- **Java 17+** (for plugin development)
  - macOS: `brew install openjdk@17`
  - Ubuntu: `sudo apt install openjdk-17-jdk`
- **PyCharm** (Professional or Community Edition)

## Getting Started

### 1. Start the Backend

```bash
cd backend
pip install fastapi uvicorn aiohttp pydantic sse-starlette eval_type_backport
python -m uvicorn main:app --host 0.0.0.0 --port 8000
```

### 2. Configure API Key

Create `~/.pycharm_agent/config.json`:

```json
{
  "provider": "gemini",
  "gemini": {
    "apiKey": "YOUR_GEMINI_API_KEY",
    "model": "gemini-2.5-flash"
  }
}
```

### 3. Build and Install Plugin

```bash
cd plugin
./gradlew buildPlugin
```

Install the plugin from `plugin/build/distributions/pycharm-agent-*.zip`

Or run in development mode:
```bash
./gradlew runIde
```

## API Endpoints

### Chat Mode
- `POST /api/chat/message` - Single response
- `POST /api/chat/stream` - SSE streaming response

### Agent Mode
- `POST /api/agent/plan` - Generate execution plan
- `POST /api/agent/execute-step` - Execute step and get diff
- `POST /api/agent/refine` - Refine code after error
- `POST /api/agent/replan` - Replan after failure

### Configuration
- `GET /api/config` - Get current config
- `PUT /api/config` - Update config
- `POST /api/config/validate` - Validate API key

## Project Structure

```
pycharm_agent/
├── backend/
│   ├── main.py              # FastAPI entry
│   ├── routers/
│   │   ├── chat.py          # Chat endpoints
│   │   ├── agent.py         # Agent endpoints
│   │   └── config.py        # Config endpoints
│   ├── services/
│   │   ├── llm_service.py   # LLM provider abstraction
│   │   ├── config_manager.py
│   │   └── diff_generator.py
│   └── models/
│       ├── chat.py
│       ├── agent.py
│       └── diff.py
│
├── plugin/
│   ├── build.gradle.kts
│   └── src/main/kotlin/com/hdsp/pycharm_agent/
│       ├── settings/
│       │   ├── AgentSettings.kt
│       │   └── AgentSettingsConfigurable.kt
│       ├── toolwindow/
│       │   ├── AgentToolWindowFactory.kt
│       │   └── MainAgentPanel.kt
│       ├── services/
│       │   ├── BackendClient.kt
│       │   └── DiffApplicationService.kt
│       └── actions/
│           ├── AcceptDiffAction.kt
│           └── RejectDiffAction.kt
│
└── README.md
```

## Supported LLM Providers

- **Gemini** (default) - Google's Gemini 2.5 Flash/Pro
- **OpenAI** - GPT-4, GPT-4-turbo
- **vLLM** - Self-hosted models

## Keyboard Shortcuts

| Shortcut | Action |
|----------|--------|
| Tab | Accept pending diff |
| Esc | Reject pending diff |

## Development

### Backend Development
```bash
cd backend
pip install -e ".[dev]"
pytest
```

### Plugin Development
```bash
cd plugin
./gradlew runIde  # Run with test PyCharm instance
./gradlew test    # Run tests
```

## License

MIT License
