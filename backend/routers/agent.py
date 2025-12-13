"""Agent mode API endpoints"""

from __future__ import annotations

import json
import re

from fastapi import APIRouter, HTTPException

from models.agent import (
    ExecuteStepRequest,
    ExecuteStepResponse,
    ExecutionPlan,
    PlanRequest,
    PlanResponse,
    PlanStep,
    RefineRequest,
    ReplanRequest,
    ToolName,
)
from models.diff import DiffResult
from services.config_manager import ConfigManager
from services.diff_generator import DiffGenerator
from services.llm_service import LLMService

router = APIRouter()
diff_generator = DiffGenerator()


def parse_json_from_response(response: str) -> dict:
    """Parse JSON from LLM response, handling code blocks"""
    # Try to extract JSON from code block
    json_match = re.search(r"```(?:json)?\s*([\s\S]*?)```", response)
    if json_match:
        json_str = json_match.group(1).strip()
    else:
        # Try to find raw JSON
        json_str = response.strip()

    try:
        return json.loads(json_str)
    except json.JSONDecodeError as e:
        # Try to find JSON object in response
        brace_start = json_str.find("{")
        brace_end = json_str.rfind("}") + 1
        if brace_start >= 0 and brace_end > brace_start:
            json_str = json_str[brace_start:brace_end]
            return json.loads(json_str)
        raise HTTPException(status_code=500, detail=f"Failed to parse JSON: {e}")


def build_plan_prompt(request: str, project_context: dict) -> str:
    """Build prompt for plan generation"""
    active_file = project_context.get("active_file", {})
    file_content = active_file.get("content", "") if active_file else ""
    file_path = active_file.get("path", "unknown") if active_file else "unknown"

    return f"""You are an AI coding assistant. Generate an execution plan for the following request.

USER REQUEST:
{request}

CURRENT FILE ({file_path}):
```
{file_content[:2000]}
```

Generate a JSON execution plan with the following structure:
{{
    "reasoning": "Brief explanation of your approach",
    "steps": [
        {{
            "step_number": 1,
            "description": "What this step does",
            "tool": "edit_file",
            "target_file": "path/to/file.py",
            "estimated_diff_type": "modify"
        }}
    ]
}}

Rules:
- Use "edit_file" for modifying existing files
- Use "create_file" for creating new files
- Use "final_answer" for the last step to summarize what was done
- Keep steps atomic and focused
- estimated_diff_type can be: "add", "modify", "delete"

Return ONLY the JSON, no additional text."""


def build_code_generation_prompt(step: PlanStep, project_context: dict, previous_results: list) -> str:
    """Build prompt for code generation"""
    active_file = project_context.get("active_file", {})
    current_content = active_file.get("content", "") if active_file else ""
    file_path = step.target_file or (active_file.get("path", "") if active_file else "")

    return f"""You are an AI coding assistant. Generate the code changes for this step.

STEP {step.step_number}: {step.description}

CURRENT FILE ({file_path}):
```python
{current_content}
```

Generate the COMPLETE modified file content. Do not use placeholders or comments like "# rest of file".
Include ALL the original code with your modifications.

Return ONLY the code, no explanations. Wrap in ```python ... ```"""


@router.post("/plan", response_model=PlanResponse)
async def generate_plan(request: PlanRequest) -> PlanResponse:
    """Generate an execution plan for the user request"""
    config = ConfigManager.get_instance().get_config()
    llm_service = LLMService(config)

    prompt = build_plan_prompt(
        request.request,
        request.project_context.model_dump(),
    )

    response = await llm_service.generate_response(prompt)
    plan_data = parse_json_from_response(response)

    steps = []
    for step_data in plan_data.get("steps", []):
        steps.append(
            PlanStep(
                step_number=step_data.get("step_number", len(steps) + 1),
                description=step_data.get("description", ""),
                tool=ToolName(step_data.get("tool", "edit_file")),
                target_file=step_data.get("target_file"),
                dependencies=step_data.get("dependencies", []),
                estimated_diff_type=step_data.get("estimated_diff_type"),
            )
        )

    plan = ExecutionPlan(
        steps=steps,
        total_steps=len(steps),
        reasoning=plan_data.get("reasoning"),
    )

    return PlanResponse(
        plan=plan,
        reasoning=plan_data.get("reasoning", ""),
    )


@router.post("/execute-step", response_model=ExecuteStepResponse)
async def execute_step(request: ExecuteStepRequest) -> ExecuteStepResponse:
    """Execute a single step and generate diff"""
    config = ConfigManager.get_instance().get_config()
    llm_service = LLMService(config)

    step = request.step

    # Handle final_answer tool
    if step.tool == ToolName.FINAL_ANSWER:
        return ExecuteStepResponse(
            success=True,
            step_number=step.step_number,
            diff=None,
            explanation=step.description,
            requires_user_approval=False,
        )

    # Get current file content
    active_file = request.project_context.active_file
    if not active_file:
        raise HTTPException(status_code=400, detail="No active file in context")

    current_content = active_file.content

    # Generate new code
    prompt = build_code_generation_prompt(
        step,
        request.project_context.model_dump(),
        request.previous_results,
    )

    response = await llm_service.generate_response(prompt)

    # Extract code from response
    code_match = re.search(r"```(?:python)?\s*([\s\S]*?)```", response)
    if code_match:
        new_content = code_match.group(1).strip()
    else:
        new_content = response.strip()

    # Generate diff
    target_file = step.target_file or active_file.path
    diff_result = diff_generator.generate_diff(current_content, new_content, target_file)

    return ExecuteStepResponse(
        success=True,
        step_number=step.step_number,
        diff=diff_result,
        explanation=f"Generated changes for: {step.description}",
        requires_user_approval=True,
    )


@router.post("/refine")
async def refine_code(request: RefineRequest) -> ExecuteStepResponse:
    """Refine code after an error"""
    config = ConfigManager.get_instance().get_config()
    llm_service = LLMService(config)

    prompt = f"""The following code produced an error. Fix it.

ORIGINAL CODE:
```python
{request.previous_code}
```

ERROR:
{request.error_message}

Generate the corrected COMPLETE file. Return ONLY the code wrapped in ```python ... ```"""

    response = await llm_service.generate_response(prompt)

    # Extract code
    code_match = re.search(r"```(?:python)?\s*([\s\S]*?)```", response)
    if code_match:
        new_content = code_match.group(1).strip()
    else:
        new_content = response.strip()

    # Get original content
    active_file = request.project_context.active_file
    original_content = active_file.content if active_file else request.previous_code

    # Generate diff
    target_file = request.step.target_file or (active_file.path if active_file else "file.py")
    diff_result = diff_generator.generate_diff(original_content, new_content, target_file)

    return ExecuteStepResponse(
        success=True,
        step_number=request.step.step_number,
        diff=diff_result,
        explanation=f"Fixed error: {request.error_message[:100]}",
        requires_user_approval=True,
    )


@router.post("/replan")
async def replan(request: ReplanRequest) -> PlanResponse:
    """Generate a new plan after failure"""
    config = ConfigManager.get_instance().get_config()
    llm_service = LLMService(config)

    prompt = f"""The original plan failed. Generate a new plan.

ORIGINAL REQUEST:
{request.original_request}

FAILED STEP:
Step {request.failed_step.step_number}: {request.failed_step.description}

ERROR:
{request.error_message}

COMPLETED STEPS: {request.completed_steps}

Generate a revised JSON execution plan that avoids the previous error.
Return ONLY the JSON."""

    response = await llm_service.generate_response(prompt)
    plan_data = parse_json_from_response(response)

    steps = []
    for step_data in plan_data.get("steps", []):
        steps.append(
            PlanStep(
                step_number=step_data.get("step_number", len(steps) + 1),
                description=step_data.get("description", ""),
                tool=ToolName(step_data.get("tool", "edit_file")),
                target_file=step_data.get("target_file"),
                dependencies=step_data.get("dependencies", []),
                estimated_diff_type=step_data.get("estimated_diff_type"),
            )
        )

    plan = ExecutionPlan(
        steps=steps,
        total_steps=len(steps),
        reasoning=plan_data.get("reasoning"),
    )

    return PlanResponse(
        plan=plan,
        reasoning=plan_data.get("reasoning", "Revised plan after error"),
    )
