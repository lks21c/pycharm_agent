"""
PyCharm Agent Backend - FastAPI Application Entry Point
"""

from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from routers import chat, agent, config
from services.config_manager import ConfigManager
from services.api_key_manager import get_key_manager
from services import llm_service


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Application lifespan manager - startup and shutdown logic"""
    # Startup: Initialize singleton services
    print("[Backend] Starting PyCharm Agent Backend...")
    config_manager = ConfigManager.get_instance()
    print("[Backend] ConfigManager initialized")

    # Initialize key manager and connect to LLM service
    key_manager = get_key_manager(config_manager)
    llm_service.set_key_manager(key_manager)
    print(f"[Backend] GeminiKeyManager initialized with {key_manager.get_key_count()} keys")

    yield
    # Shutdown: Cleanup
    print("[Backend] Shutting down PyCharm Agent Backend...")


app = FastAPI(
    title="PyCharm Agent Backend",
    description="AI-powered code assistance backend for PyCharm plugin",
    version="1.0.0",
    lifespan=lifespan,
)

# CORS middleware for PyCharm plugin communication
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # PyCharm plugin runs locally
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Include routers
app.include_router(chat.router, prefix="/api/chat", tags=["chat"])
app.include_router(agent.router, prefix="/api/agent", tags=["agent"])
app.include_router(config.router, prefix="/api/config", tags=["config"])


@app.get("/health")
async def health_check():
    """Health check endpoint"""
    return {"status": "healthy", "service": "pycharm-agent-backend"}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=8000)
