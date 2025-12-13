# PyCharm Agent

PyCharm용 AI 기반 코딩 어시스턴트 - Chat 모드와 Agent 모드 지원

## 기능

### Chat 모드
- 간단한 프롬프트/응답 인터페이스
- 실시간 스트리밍 응답 표시
- 코드 블록 구문 강조 및 복사 버튼
- 세션 내 대화 기록 유지

### Agent 모드
- 계획-실행 패턴 기반 자동 코드 수정
- GitHub Copilot 스타일 diff 미리보기
- Tab/Esc 단축키로 변경사항 승인/거부
- 사용자 승인 기반 단계별 실행

## 아키텍처

```
┌──────────────────────────────────┐
│   PyCharm 플러그인 (Kotlin)       │
│   - Chat 패널 (Tool Window)      │
│   - Agent 패널                   │
│   - Diff 하이라이터 (에디터)      │
└────────────┬─────────────────────┘
             │ HTTP/SSE
┌────────────┴─────────────────────┐
│   FastAPI 백엔드 (Python)         │
│   - Chat API (스트리밍)          │
│   - Agent API (계획/실행)        │
│   - LLM 서비스 (Gemini/OpenAI)   │
└──────────────────────────────────┘
```

## 필수 요구사항

- **Python 3.9+** (pip 포함)
- **Java 17+** (플러그인 개발용)
  - macOS: `brew install openjdk@17`
  - Ubuntu: `sudo apt install openjdk-17-jdk`
- **PyCharm** (Professional 또는 Community Edition)

## 시작하기

### 1. 백엔드 실행

```bash
cd backend
pip install fastapi uvicorn aiohttp pydantic sse-starlette eval_type_backport
python -m uvicorn main:app --host 0.0.0.0 --port 8000
```

### 2. API 키 설정

`~/.pycharm_agent/config.json` 파일 생성:

```json
{
  "provider": "gemini",
  "gemini": {
    "apiKey": "YOUR_GEMINI_API_KEY",
    "model": "gemini-2.5-flash"
  }
}
```

### 3. 플러그인 빌드 및 설치

```bash
cd plugin
./gradlew buildPlugin
```

`plugin/build/distributions/pycharm-agent-*.zip`에서 플러그인 설치

개발 모드로 실행:
```bash
./gradlew runIde
```

## API 엔드포인트

### Chat 모드
- `POST /api/chat/message` - 단일 응답
- `POST /api/chat/stream` - SSE 스트리밍 응답

### Agent 모드
- `POST /api/agent/plan` - 실행 계획 생성
- `POST /api/agent/execute-step` - 단계 실행 및 diff 반환
- `POST /api/agent/refine` - 에러 후 코드 수정
- `POST /api/agent/replan` - 실패 후 재계획

### 설정
- `GET /api/config` - 현재 설정 조회
- `PUT /api/config` - 설정 업데이트
- `POST /api/config/validate` - API 키 검증

## 프로젝트 구조

```
pycharm_agent/
├── backend/
│   ├── main.py              # FastAPI 진입점
│   ├── routers/
│   │   ├── chat.py          # Chat 엔드포인트
│   │   ├── agent.py         # Agent 엔드포인트
│   │   └── config.py        # Config 엔드포인트
│   ├── services/
│   │   ├── llm_service.py   # LLM 제공자 추상화
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

## 지원 LLM 제공자

- **Gemini** (기본) - Google Gemini 2.5 Flash/Pro
- **OpenAI** - GPT-4, GPT-4-turbo
- **vLLM** - 자체 호스팅 모델

## 키보드 단축키

| 단축키 | 동작 |
|--------|------|
| Tab | 대기 중인 diff 승인 |
| Esc | 대기 중인 diff 거부 |

## 개발

### 백엔드 개발
```bash
cd backend
pip install -e ".[dev]"
pytest
```

### 플러그인 개발
```bash
cd plugin
./gradlew runIde  # 테스트 PyCharm 인스턴스로 실행
./gradlew test    # 테스트 실행
```

## 라이선스

MIT License
