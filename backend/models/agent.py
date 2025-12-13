"""Agent mode data models"""

from __future__ import annotations

from enum import Enum

from pydantic import BaseModel

from .diff import DiffResult


class ToolName(str, Enum):
    """Available tools for agent execution"""

    EDIT_FILE = "edit_file"
    CREATE_FILE = "create_file"
    FINAL_ANSWER = "final_answer"


class FileContext(BaseModel):
    """Context for a single file"""

    path: str
    content: str
    language: str = "python"
    cursor_position: int | None = None
    selection: tuple[int, int] | None = None


class ProjectContext(BaseModel):
    """Context for the entire project"""

    project_root: str
    open_files: list[FileContext] = []
    active_file: FileContext | None = None


class PlanStep(BaseModel):
    """A single step in the execution plan"""

    step_number: int
    description: str
    tool: ToolName
    target_file: str | None = None
    dependencies: list[int] = []
    estimated_diff_type: str | None = None  # add, modify, delete


class ExecutionPlan(BaseModel):
    """Complete execution plan"""

    steps: list[PlanStep]
    total_steps: int
    reasoning: str | None = None


class PlanRequest(BaseModel):
    """Request for plan generation"""

    request: str
    project_context: ProjectContext


class PlanResponse(BaseModel):
    """Response with generated plan"""

    plan: ExecutionPlan
    reasoning: str


class ExecuteStepRequest(BaseModel):
    """Request to execute a single step"""

    step: PlanStep
    project_context: ProjectContext
    previous_results: list[dict] = []


class ExecuteStepResponse(BaseModel):
    """Response from step execution"""

    success: bool
    step_number: int
    diff: DiffResult | None = None
    explanation: str
    requires_user_approval: bool = True


class RefineRequest(BaseModel):
    """Request to refine code after error"""

    step: PlanStep
    error_message: str
    project_context: ProjectContext
    previous_code: str


class ReplanRequest(BaseModel):
    """Request to replan after failure"""

    original_request: str
    failed_step: PlanStep
    error_message: str
    project_context: ProjectContext
    completed_steps: list[int] = []
