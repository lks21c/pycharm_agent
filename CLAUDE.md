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

---

## 이슈에 관여하면 담당자를 채운다

**스킬이 이슈·PR 을 실제로 건드리는 순간, 담당자가 비어 있으면 실행자를 담당자로 지정한다.**

```bash
gh issue view <번호> --json assignees --jq '.assignees | length'   # 0 이면
gh issue edit <번호> --add-assignee @me
```

- 대상: 이슈나 PR 을 **쓰기 조작**하는 스킬 — `/issue` `/ic` `/listup` `/cm` `/gcp`,
  그리고 개인 스킬(`/f` `/o` `/issuef` `/issueo` `/planf` `/plano` `/enhancef` `/cmf`).
  PR 이면 `gh pr edit <번호> --add-assignee @me`.
- **이미 담당자가 있으면 건드리지 않는다.** 남의 이슈를 가로채지 않는다.
- **읽기 전용 스킬은 하지 않는다** — 현황을 보여줄 뿐이라 담당자를 바꾸는 것 자체가 부작용이다.
- 실패해도 본작업을 막지 않는다. 권한이 없거나 조직 정책으로 거부되면 한 줄 남기고 계속한다.

**왜**: 담당자 없는 이슈는 큐에서 주인이 안 보이고, 여러 세션이 같은 이슈를 동시에 잡아도
아무도 모른다. 관여한 사람이 곧 담당자라는 게 가장 싼 규칙이다.

---

## 스킬은 남의 워크트리·공유 저장소를 건드리지 않는다

커스텀 스킬은 **여러 세션이 동시에 도는 것을 전제**로 쓴다. 워크트리와 메인 저장소가
공유 자원이다.

- **자기가 만든 워크트리에서만 쓴다.** 다른 스킬이 만든 워크트리를 빌려 쓰지 않는다.
  그 안에서 다른 세션이 편집·커밋 중일 수 있고, `git status --short` 한 번은 그 순간의
  스냅샷일 뿐이다.
- **고정 경로를 `--force` 로 지우지 않는다.** `git worktree remove --force <고정경로>` 는
  같은 대상에 스킬이 둘 돌면 앞 세션의 실행 중 워크트리를 통째로 날린다. 일회용
  워크트리는 경로에 `$$`(PID)를 붙여 세션마다 유일하게 만들고, 자기가 만든 것만 지운다.
- **메인 저장소에서 `git add -A` 하지 않는다.** 모든 세션이 공유하므로 남이 편집 중인
  파일과 무관한 로컬 수정이 통째로 커밋돼 push 된다.
- 🔴 **`main` 브랜치에는 사용자가 그 요청에서 직접 승인했을 때만 커밋·푸시한다.**
  앞선 다른 작업의 지시나 "개인 저장소니까" 같은 판단은 승인이 아니다.
