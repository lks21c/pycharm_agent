"""Chat mode data models"""

from __future__ import annotations

from pydantic import BaseModel


class ChatRequest(BaseModel):
    """Request for chat message"""

    message: str
    conversation_id: str | None = None
    context: str | None = None  # Optional file context from editor


class CodeBlock(BaseModel):
    """Extracted code block from response"""

    language: str
    code: str
    file_hint: str | None = None  # Suggested filename


class ChatResponse(BaseModel):
    """Response for chat message"""

    conversation_id: str
    message_id: str
    content: str
    code_blocks: list[CodeBlock] = []
    metadata: dict = {}


class StreamEvent(BaseModel):
    """SSE stream event"""

    type: str  # "content", "code_block", "done", "error"
    chunk: str | None = None
    code_block: CodeBlock | None = None
    metadata: dict | None = None
    done: bool = False
    error: str | None = None
