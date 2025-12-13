"""Diff-related data models"""

from __future__ import annotations

from pydantic import BaseModel


class DiffHunk(BaseModel):
    """A single change hunk in a diff"""

    start_line: int  # 1-indexed
    end_line: int
    original_content: str
    new_content: str
    change_type: str  # "add", "modify", "delete"


class DiffResult(BaseModel):
    """Complete diff result for a file"""

    file_path: str
    hunks: list[DiffHunk]
    unified_diff: str  # Standard unified diff format
    preview_content: str  # Full file with changes applied
