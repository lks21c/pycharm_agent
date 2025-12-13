"""
Diff Generator Service - Generate unified diffs for code modifications
"""

from __future__ import annotations

from difflib import SequenceMatcher, unified_diff

from models.diff import DiffHunk, DiffResult


class DiffGenerator:
    """Generate unified diffs for code modifications"""

    def generate_diff(
        self,
        original_content: str,
        new_content: str,
        file_path: str,
    ) -> DiffResult:
        """Generate structured diff from original and new content"""
        original_lines = original_content.splitlines(keepends=True)
        new_lines = new_content.splitlines(keepends=True)

        # Ensure last lines have newlines for proper diff
        if original_lines and not original_lines[-1].endswith("\n"):
            original_lines[-1] += "\n"
        if new_lines and not new_lines[-1].endswith("\n"):
            new_lines[-1] += "\n"

        # Generate unified diff
        unified = list(
            unified_diff(
                original_lines,
                new_lines,
                fromfile=f"a/{file_path}",
                tofile=f"b/{file_path}",
            )
        )

        # Extract hunks for granular application
        hunks = self._extract_hunks(original_lines, new_lines)

        return DiffResult(
            file_path=file_path,
            hunks=hunks,
            unified_diff="".join(unified),
            preview_content="".join(new_lines),
        )

    def _extract_hunks(
        self,
        original: list[str],
        modified: list[str],
    ) -> list[DiffHunk]:
        """Extract individual change hunks from diff"""
        matcher = SequenceMatcher(None, original, modified)
        hunks = []

        for tag, i1, i2, j1, j2 in matcher.get_opcodes():
            if tag == "equal":
                continue

            change_type = "add" if tag == "insert" else "delete" if tag == "delete" else "modify"

            hunks.append(
                DiffHunk(
                    start_line=i1 + 1,  # 1-indexed for IDE
                    end_line=i2,
                    original_content="".join(original[i1:i2]),
                    new_content="".join(modified[j1:j2]),
                    change_type=change_type,
                )
            )

        return hunks

    def generate_inline_preview(
        self,
        original_content: str,
        new_content: str,
        context_lines: int = 3,
    ) -> str:
        """Generate inline preview with context lines around changes"""
        original_lines = original_content.splitlines()
        new_lines = new_content.splitlines()

        matcher = SequenceMatcher(None, original_lines, new_lines)
        result_lines = []

        for tag, i1, i2, j1, j2 in matcher.get_opcodes():
            if tag == "equal":
                # Show context lines only
                start = max(0, i1)
                end = min(len(original_lines), i2)
                for i in range(start, end):
                    if i < i1 + context_lines or i >= i2 - context_lines:
                        result_lines.append(f"  {original_lines[i]}")
                    elif len(result_lines) > 0 and not result_lines[-1].startswith("..."):
                        result_lines.append("...")
            elif tag == "replace":
                for line in original_lines[i1:i2]:
                    result_lines.append(f"- {line}")
                for line in new_lines[j1:j2]:
                    result_lines.append(f"+ {line}")
            elif tag == "delete":
                for line in original_lines[i1:i2]:
                    result_lines.append(f"- {line}")
            elif tag == "insert":
                for line in new_lines[j1:j2]:
                    result_lines.append(f"+ {line}")

        return "\n".join(result_lines)
