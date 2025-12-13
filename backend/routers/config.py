"""Configuration API endpoints"""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from services.config_manager import ConfigManager
from services.llm_service import LLMService

router = APIRouter()


class ConfigUpdateRequest(BaseModel):
    """Request to update configuration"""

    provider: str | None = None
    gemini: dict | None = None
    openai: dict | None = None
    vllm: dict | None = None


class ConfigResponse(BaseModel):
    """Configuration response"""

    provider: str
    gemini: dict
    openai: dict
    vllm: dict


class ValidateResponse(BaseModel):
    """Validation response"""

    valid: bool
    message: str
    provider: str


@router.get("", response_model=ConfigResponse)
async def get_config() -> ConfigResponse:
    """Get current configuration"""
    config = ConfigManager.get_instance().get_config()

    # Mask API keys for security
    def mask_key(key: str) -> str:
        if not key:
            return ""
        if len(key) <= 8:
            return "*" * len(key)
        return key[:4] + "*" * (len(key) - 8) + key[-4:]

    gemini = config.get("gemini", {}).copy()
    openai = config.get("openai", {}).copy()
    vllm = config.get("vllm", {}).copy()

    gemini["apiKey"] = mask_key(gemini.get("apiKey", ""))
    openai["apiKey"] = mask_key(openai.get("apiKey", ""))
    vllm["apiKey"] = mask_key(vllm.get("apiKey", ""))

    return ConfigResponse(
        provider=config.get("provider", "gemini"),
        gemini=gemini,
        openai=openai,
        vllm=vllm,
    )


@router.put("")
async def update_config(request: ConfigUpdateRequest) -> dict[str, Any]:
    """Update configuration"""
    config_manager = ConfigManager.get_instance()
    current_config = config_manager.get_config()

    # Update only provided fields
    if request.provider:
        current_config["provider"] = request.provider
    if request.gemini:
        current_config["gemini"] = {**current_config.get("gemini", {}), **request.gemini}
    if request.openai:
        current_config["openai"] = {**current_config.get("openai", {}), **request.openai}
    if request.vllm:
        current_config["vllm"] = {**current_config.get("vllm", {}), **request.vllm}

    config_manager.save_config(current_config)

    return {"status": "success", "message": "Configuration updated"}


@router.post("/validate", response_model=ValidateResponse)
async def validate_config() -> ValidateResponse:
    """Validate current configuration by testing LLM connection"""
    config = ConfigManager.get_instance().get_config()
    provider = config.get("provider", "gemini")

    try:
        llm_service = LLMService(config)
        # Simple test prompt
        response = await llm_service.generate_response("Say 'OK' if you can hear me.")

        if response and len(response) > 0:
            return ValidateResponse(
                valid=True,
                message=f"Successfully connected to {provider}",
                provider=provider,
            )
        else:
            return ValidateResponse(
                valid=False,
                message="Received empty response from LLM",
                provider=provider,
            )

    except Exception as e:
        return ValidateResponse(
            valid=False,
            message=f"Connection failed: {str(e)}",
            provider=provider,
        )
