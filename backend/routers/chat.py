"""Chat mode API endpoints"""

from __future__ import annotations

import re
import uuid

from fastapi import APIRouter
from sse_starlette.sse import EventSourceResponse

from models.chat import ChatRequest, ChatResponse, CodeBlock, StreamEvent
from services.config_manager import ConfigManager
from services.llm_service import LLMService

router = APIRouter()

# In-memory conversation storage (for session persistence)
conversations: dict[str, list[dict]] = {}


def extract_code_blocks(content: str) -> list[CodeBlock]:
    """Extract code blocks from markdown response"""
    pattern = r"```(\w+)?\n(.*?)```"
    matches = re.findall(pattern, content, re.DOTALL)

    blocks = []
    for lang, code in matches:
        blocks.append(
            CodeBlock(
                language=lang or "text",
                code=code.strip(),
                file_hint=None,
            )
        )
    return blocks


@router.post("/message", response_model=ChatResponse)
async def chat_message(request: ChatRequest) -> ChatResponse:
    """Send a chat message and get a response (non-streaming)"""
    config = ConfigManager.get_instance().get_config()
    llm_service = LLMService(config)

    # Generate or use existing conversation ID
    conversation_id = request.conversation_id or str(uuid.uuid4())
    message_id = str(uuid.uuid4())

    # Build prompt with context
    prompt = request.message
    context = request.context

    # Get response from LLM
    response_content = await llm_service.generate_response(prompt, context)

    # Extract code blocks
    code_blocks = extract_code_blocks(response_content)

    # Store in conversation history
    if conversation_id not in conversations:
        conversations[conversation_id] = []
    conversations[conversation_id].append({"role": "user", "content": request.message})
    conversations[conversation_id].append({"role": "assistant", "content": response_content})

    return ChatResponse(
        conversation_id=conversation_id,
        message_id=message_id,
        content=response_content,
        code_blocks=code_blocks,
        metadata={"provider": config.get("provider", "gemini")},
    )


@router.post("/stream")
async def chat_stream(request: ChatRequest):
    """Send a chat message and get a streaming response (SSE)"""
    config = ConfigManager.get_instance().get_config()
    llm_service = LLMService(config)

    conversation_id = request.conversation_id or str(uuid.uuid4())

    async def event_generator():
        full_content = ""

        try:
            async for chunk in llm_service.generate_response_stream(
                request.message, request.context
            ):
                full_content += chunk
                event = StreamEvent(type="content", chunk=chunk)
                yield {"event": "message", "data": event.model_dump_json()}

            # Extract code blocks from complete response
            code_blocks = extract_code_blocks(full_content)

            # Send code blocks
            for block in code_blocks:
                event = StreamEvent(type="code_block", code_block=block)
                yield {"event": "message", "data": event.model_dump_json()}

            # Send done event
            event = StreamEvent(
                type="done",
                done=True,
                metadata={
                    "conversation_id": conversation_id,
                    "provider": config.get("provider", "gemini"),
                },
            )
            yield {"event": "message", "data": event.model_dump_json()}

            # Store in conversation history
            if conversation_id not in conversations:
                conversations[conversation_id] = []
            conversations[conversation_id].append({"role": "user", "content": request.message})
            conversations[conversation_id].append({"role": "assistant", "content": full_content})

        except Exception as e:
            event = StreamEvent(type="error", error=str(e))
            yield {"event": "message", "data": event.model_dump_json()}

    return EventSourceResponse(event_generator())
