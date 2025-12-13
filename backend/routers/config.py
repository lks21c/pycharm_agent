"""Configuration API endpoints"""

from __future__ import annotations

from typing import Any

import aiohttp
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from services.config_manager import ConfigManager
from services.llm_service import LLMService
from services.api_key_manager import get_key_manager

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


# ========== Gemini Key Management Endpoints ==========


class AddKeyRequest(BaseModel):
    """Request to add a new Gemini API key"""
    apiKey: str


class RemoveKeyRequest(BaseModel):
    """Request to remove a Gemini API key"""
    keyId: str


class ToggleKeyRequest(BaseModel):
    """Request to toggle a Gemini API key"""
    keyId: str
    enabled: bool


class KeysResponse(BaseModel):
    """Response with list of keys"""
    keys: list[dict[str, Any]]
    maxKeys: int
    currentCount: int


async def test_gemini_api_key(api_key: str) -> tuple[bool, str]:
    """
    Test if a Gemini API key is valid by making a simple API call.
    Returns (success: bool, message: str)
    """
    url = f"https://generativelanguage.googleapis.com/v1beta/models?key={api_key}"

    try:
        async with aiohttp.ClientSession() as session:
            async with session.get(url, timeout=aiohttp.ClientTimeout(total=10)) as response:
                if response.status == 200:
                    return True, "API key is valid"
                elif response.status == 400:
                    error_data = await response.json()
                    error_msg = error_data.get('error', {}).get('message', 'Invalid API key')
                    return False, f"Invalid API key: {error_msg}"
                elif response.status == 403:
                    return False, "API key is forbidden or disabled"
                else:
                    return False, f"API validation failed (HTTP {response.status})"
    except aiohttp.ClientError as e:
        return False, f"Network error: {str(e)}"
    except Exception as e:
        return False, f"Validation error: {str(e)}"


@router.get("/gemini-keys", response_model=KeysResponse)
async def get_gemini_keys() -> KeysResponse:
    """Get all Gemini API keys status"""
    config_manager = ConfigManager.get_instance()
    key_manager = get_key_manager(config_manager)

    return KeysResponse(
        keys=key_manager.get_all_keys_status(),
        maxKeys=key_manager.MAX_KEYS,
        currentCount=key_manager.get_key_count()
    )


@router.post("/gemini-keys")
async def add_gemini_key(request: AddKeyRequest) -> dict[str, Any]:
    """Add a new Gemini API key (with validation)"""
    api_key = request.apiKey.strip()

    if not api_key:
        raise HTTPException(status_code=400, detail="API key is required")

    # Basic validation for Gemini API key format
    if not api_key.startswith('AIza'):
        raise HTTPException(status_code=400, detail="Invalid Gemini API key format (should start with 'AIza')")

    # Test the API key before adding
    is_valid, test_message = await test_gemini_api_key(api_key)

    if not is_valid:
        raise HTTPException(status_code=400, detail=f"API key validation failed: {test_message}")

    config_manager = ConfigManager.get_instance()
    key_manager = get_key_manager(config_manager)
    success, message = key_manager.add_key(api_key)

    if success:
        return {
            "success": True,
            "message": "API key validated and added successfully",
            "keys": key_manager.get_all_keys_status()
        }
    else:
        raise HTTPException(status_code=400, detail=message)


@router.delete("/gemini-keys")
async def remove_gemini_key(request: RemoveKeyRequest) -> dict[str, Any]:
    """Remove a Gemini API key"""
    key_id = request.keyId.strip()

    if not key_id:
        raise HTTPException(status_code=400, detail="Key ID is required")

    config_manager = ConfigManager.get_instance()
    key_manager = get_key_manager(config_manager)
    success, message = key_manager.remove_key(key_id)

    if success:
        return {
            "success": True,
            "message": message,
            "keys": key_manager.get_all_keys_status()
        }
    else:
        raise HTTPException(status_code=404, detail=message)


@router.post("/gemini-keys/toggle")
async def toggle_gemini_key(request: ToggleKeyRequest) -> dict[str, Any]:
    """Toggle a Gemini API key enabled state"""
    key_id = request.keyId.strip()

    if not key_id:
        raise HTTPException(status_code=400, detail="Key ID is required")

    config_manager = ConfigManager.get_instance()
    key_manager = get_key_manager(config_manager)
    success, message = key_manager.toggle_key(key_id, request.enabled)

    if success:
        return {
            "success": True,
            "message": message,
            "keys": key_manager.get_all_keys_status()
        }
    else:
        raise HTTPException(status_code=404, detail=message)


@router.post("/gemini-keys/test")
async def test_gemini_keys() -> dict[str, Any]:
    """Test all Gemini API keys"""
    config_manager = ConfigManager.get_instance()
    key_manager = get_key_manager(config_manager)
    keys_status = key_manager.get_all_keys_status()

    results = []
    for key_info in keys_status:
        key_id = key_info['id']
        # Get actual key from manager
        actual_key = key_manager._get_key_by_id(key_id)

        if actual_key and key_info['enabled']:
            is_valid, message = await test_gemini_api_key(actual_key)
            results.append({
                'id': key_id,
                'maskedKey': key_info['maskedKey'],
                'success': is_valid,
                'message': message if not is_valid else 'OK'
            })
        else:
            results.append({
                'id': key_id,
                'maskedKey': key_info['maskedKey'],
                'success': False,
                'message': 'Disabled' if not key_info['enabled'] else 'Key not found'
            })

    return {
        'results': results,
        'totalKeys': len(results),
        'successCount': sum(1 for r in results if r['success'])
    }
