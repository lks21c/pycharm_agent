#!/usr/bin/env python3
"""
PyCharm Agent E2E Prompt Test Runner

Runs prompt-based tests against the PyCharm Agent backend API.
Supports mock mode (cached results) and live mode (real API calls).

Usage:
    # Mock mode (default, uses cached results)
    python tests/e2e/run_prompt_tests.py

    # Live mode (calls real backend)
    python tests/e2e/run_prompt_tests.py --live

    # Specific category
    python tests/e2e/run_prompt_tests.py --category chat

    # Limit number of tests
    python tests/e2e/run_prompt_tests.py --limit 5

    # Force live (ignore cache)
    python tests/e2e/run_prompt_tests.py --force-live
"""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import logging
import os
import re
import sys
import time
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import yaml

# Project paths
PROJECT_ROOT = Path(__file__).parent.parent.parent.parent
TESTS_DIR = Path(__file__).parent
RESULTS_DIR = TESTS_DIR / "results"
CASSETTES_DIR = TESTS_DIR / "cassettes"

# Shared prompts from hdsp_agent (avoid duplication)
HDSP_AGENT_PROMPTS_DIR = Path.home() / "repo" / "hdsp_agent" / "tests" / "e2e" / "prompts"
LOCAL_PROMPTS_DIR = TESTS_DIR / "prompts"  # For agent-specific prompts only

# Reports directory (same as hdsp_agent pattern)
REPORTS_DIR = Path.home() / "repo" / "pycharm_agents" / "reports"

# Logging setup
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(levelname)s - %(message)s",
)
logger = logging.getLogger(__name__)


def compute_prompt_hash(
    prompt_text: str, context: dict | None = None
) -> str:
    """Compute hash for prompt (used as cache key)"""
    content = prompt_text + json.dumps(context or {}, sort_keys=True)
    return hashlib.sha256(content.encode()).hexdigest()[:16]


@dataclass
class Prompt:
    """Test prompt definition"""
    id: str
    text: str
    category: str = "chat"  # chat, agent, code, explain
    expected_patterns: list[str] = field(default_factory=list)
    expected_actions: list[str] = field(default_factory=list)
    tags: list[str] = field(default_factory=list)
    context: dict = field(default_factory=dict)
    file_context: str = ""  # Optional file content for context


@dataclass
class ExecutionStep:
    """Individual execution step (for lineage tracking)"""
    phase: str  # "prepare", "request", "stream", "execute", "complete", "error"
    description: str
    start_time_ms: int
    duration_ms: int
    details: dict = field(default_factory=dict)
    code: str = ""      # Executed code or command
    output: str = ""    # Execution result (stdout, stderr, etc.)


@dataclass
class ExecutionResult:
    """Prompt execution result"""
    success: bool
    duration_ms: int
    error: str | None = None
    output: str = ""
    code_generated: str = ""
    steps: list[ExecutionStep] = field(default_factory=list)
    tokens_input: int = 0
    tokens_output: int = 0


@dataclass
class Metrics:
    """Quality metrics"""
    execution_success: bool = False
    response_quality: float = 0.0
    pattern_match: float = 0.0
    latency_score: float = 0.0
    overall_score: float = 0.0


@dataclass
class Performance:
    """Performance metrics"""
    latency_ms: int = 0
    tokens_input: int = 0
    tokens_output: int = 0


@dataclass
class TestResult:
    """Test result"""
    prompt_id: str
    category: str
    prompt_text: str
    timestamp: str
    execution: ExecutionResult
    metrics: Metrics
    performance: Performance


class PromptLoader:
    """Load prompts from YAML files (shared from hdsp_agent + local agent prompts)"""

    def __init__(self, prompts_dir: Path | None = None):
        # Use hdsp_agent prompts by default for shared prompts
        self.hdsp_prompts_dir = HDSP_AGENT_PROMPTS_DIR
        self.local_prompts_dir = LOCAL_PROMPTS_DIR
        self._prompts: dict[str, list[Prompt]] = {}

    def load_all(self) -> dict[str, list[Prompt]]:
        """Load all prompts from YAML files"""
        # Load shared prompts from hdsp_agent
        hdsp_categories = ["python", "athena", "spark", "multi_agent"]
        for category in hdsp_categories:
            file_path = self.hdsp_prompts_dir / f"{category}_prompts.yaml"
            if file_path.exists():
                self._prompts[category] = self._load_category(file_path)
                logger.info(
                    f"Loaded {len(self._prompts[category])} prompts from hdsp_agent/{category}"
                )

        # Load local agent-specific prompts
        local_files = ["agent_prompts.yaml"]
        for filename in local_files:
            file_path = self.local_prompts_dir / filename
            if file_path.exists():
                category = filename.replace("_prompts.yaml", "")
                self._prompts[category] = self._load_category(file_path)
                logger.info(
                    f"Loaded {len(self._prompts[category])} prompts from local/{category}"
                )

        return self._prompts

    def _load_category(self, file_path: Path) -> list[Prompt]:
        """Load prompts from a category file"""
        with open(file_path) as f:
            data = yaml.safe_load(f)

        prompts = []
        for p in data.get("prompts", []):
            context = p.get("context", {})
            if isinstance(context, str):
                context = {"description": context}

            prompts.append(
                Prompt(
                    id=p["id"],
                    text=p["text"],
                    category=p.get("category", "chat"),
                    expected_patterns=p.get("expected_patterns", []),
                    expected_actions=p.get("expected_actions", []),
                    tags=p.get("tags", []),
                    context=context,
                    file_context=p.get("file_context", ""),
                )
            )
        return prompts

    def get_by_category(self, category: str) -> list[Prompt]:
        """Get prompts by category"""
        if not self._prompts:
            self.load_all()
        return self._prompts.get(category, [])

    def get_by_id(self, prompt_id: str) -> Prompt | None:
        """Get prompt by ID"""
        if not self._prompts:
            self.load_all()

        for prompts in self._prompts.values():
            for p in prompts:
                if p.id == prompt_id:
                    return p
        return None

    def get_all(self) -> list[Prompt]:
        """Get all prompts"""
        if not self._prompts:
            self.load_all()

        all_prompts = []
        for prompts in self._prompts.values():
            all_prompts.extend(prompts)
        return all_prompts


class MockExecutor:
    """Mock mode executor (uses cached results)"""

    def __init__(self, cassettes_dir: Path = CASSETTES_DIR):
        self.cassettes_dir = cassettes_dir
        self.cassettes_dir.mkdir(parents=True, exist_ok=True)

    async def execute(self, prompt: Prompt) -> ExecutionResult:
        """Execute in mock mode - return cached result"""
        cassette_file = self.cassettes_dir / f"{prompt.id}.yaml"

        if cassette_file.exists():
            with open(cassette_file) as f:
                cached = yaml.safe_load(f)

            steps = []
            for step_data in cached.get("steps", []):
                steps.append(
                    ExecutionStep(
                        phase=step_data.get("phase", "unknown"),
                        description=step_data.get("description", ""),
                        start_time_ms=step_data.get("start_time_ms", 0),
                        duration_ms=step_data.get("duration_ms", 0),
                        details=step_data.get("details", {}),
                    )
                )

            return ExecutionResult(
                success=cached.get("success", True),
                duration_ms=cached.get("duration_ms", 100),
                error=cached.get("error"),
                output=cached.get("output", "Mock output"),
                code_generated=cached.get("code_generated", ""),
                steps=steps,
                tokens_input=cached.get("tokens_input", 0),
                tokens_output=cached.get("tokens_output", 0),
            )

        # No cache found - return default mock result
        mock_steps = [
            ExecutionStep(
                phase="mock",
                description="No cassette found - run with --live to record",
                start_time_ms=0,
                duration_ms=50,
            ),
        ]
        return ExecutionResult(
            success=True,
            duration_ms=50,
            output="Mock execution - no cassette found. Run with --live to record.",
            code_generated="",
            steps=mock_steps,
        )


class LiveExecutor:
    """Live mode executor (calls real backend API)"""

    def __init__(
        self, backend_url: str = "http://localhost:8000", force_live: bool = False
    ):
        self.backend_url = backend_url.rstrip("/")
        self.cassettes_dir = CASSETTES_DIR
        self.cassettes_dir.mkdir(parents=True, exist_ok=True)
        self.force_live = force_live
        self.llm_config = self._load_llm_config()

    def _load_llm_config(self) -> dict:
        """Load LLM configuration from ~/.pycharm_agent/config.json"""
        config_path = Path.home() / ".pycharm_agent" / "config.json"
        if not config_path.exists():
            logger.warning(f"Config not found: {config_path}")
            return {"provider": "gemini"}

        try:
            with open(config_path) as f:
                config = json.load(f)

            provider = config.get("provider", "gemini")
            llm_config: dict[str, Any] = {"provider": provider}

            if provider == "gemini":
                gemini_config = config.get("gemini", {})
                api_key = gemini_config.get("apiKey", "")
                if not api_key:
                    # Try keys array
                    keys = gemini_config.get("keys", [])
                    if keys:
                        api_key = keys[0].get("key", "")
                llm_config["gemini"] = {
                    "apiKey": api_key,
                    "model": gemini_config.get("model", "gemini-2.5-flash"),
                }
            elif provider == "openai":
                openai_config = config.get("openai", {})
                llm_config["openai"] = {
                    "apiKey": openai_config.get("apiKey", ""),
                    "model": openai_config.get("model", "gpt-4"),
                }

            return llm_config
        except Exception as e:
            logger.warning(f"Failed to load config: {e}")
            return {"provider": "gemini"}

    def _load_cached_result(self, prompt: Prompt) -> ExecutionResult | None:
        """Load cached result if prompt hash matches"""
        cassette_file = self.cassettes_dir / f"{prompt.id}.yaml"
        if not cassette_file.exists():
            return None

        with open(cassette_file) as f:
            cached = yaml.safe_load(f)

        current_hash = compute_prompt_hash(prompt.text, prompt.context)
        cached_hash = cached.get("prompt_hash", "")

        if cached_hash != current_hash:
            logger.info(f"  Cache miss for {prompt.id}: prompt changed")
            return None

        logger.info(f"  Cache hit for {prompt.id}: using recorded result")

        steps = []
        for step_data in cached.get("steps", []):
            steps.append(
                ExecutionStep(
                    phase=step_data.get("phase", "unknown"),
                    description=step_data.get("description", ""),
                    start_time_ms=step_data.get("start_time_ms", 0),
                    duration_ms=step_data.get("duration_ms", 0),
                    details=step_data.get("details", {}),
                    code=step_data.get("code", ""),
                    output=step_data.get("output", ""),
                )
            )

        steps.insert(
            0,
            ExecutionStep(
                phase="cache",
                description="Loaded from cached cassette",
                start_time_ms=0,
                duration_ms=0,
                details={"cached_hash": cached_hash},
            ),
        )

        return ExecutionResult(
            success=cached.get("success", True),
            duration_ms=cached.get("duration_ms", 0),
            error=cached.get("error"),
            output=cached.get("output", ""),
            code_generated=cached.get("code_generated", ""),
            steps=steps,
            tokens_input=cached.get("tokens_input", 0),
            tokens_output=cached.get("tokens_output", 0),
        )

    async def execute(self, prompt: Prompt) -> ExecutionResult:
        """Execute prompt against backend API"""
        # Check cache first (unless force_live)
        if not self.force_live:
            cached_result = self._load_cached_result(prompt)
            if cached_result:
                return cached_result

        return await self._execute_live(prompt)

    async def _execute_live(self, prompt: Prompt) -> ExecutionResult:
        """Execute live API call with full agent loop (tool execution + resume)"""
        import aiohttp
        import subprocess
        import tempfile

        start_time = time.time()
        steps: list[ExecutionStep] = []
        full_response = ""
        all_code_generated: list[str] = []

        def add_step(
            phase: str,
            description: str,
            step_start: float,
            code: str = "",
            output: str = "",
            **details: Any,
        ):
            duration = int((time.time() - step_start) * 1000)
            elapsed = int((step_start - start_time) * 1000)
            steps.append(
                ExecutionStep(
                    phase=phase,
                    description=description,
                    start_time_ms=elapsed,
                    duration_ms=duration,
                    details=details,
                    code=code,
                    output=output,
                )
            )

        def execute_tool(tool_name: str, tool_args: dict) -> dict:
            """Execute a tool locally and return the result"""
            exec_start = time.time()

            if tool_name in ("execute_command_tool", "shell"):
                command = tool_args.get("command", "")
                timeout_ms = tool_args.get("timeout", 60000)
                timeout_sec = timeout_ms / 1000 if timeout_ms else 60

                try:
                    result = subprocess.run(
                        command,
                        shell=True,
                        capture_output=True,
                        text=True,
                        timeout=timeout_sec,
                        cwd=prompt.context.get("workspaceRoot", "."),
                    )
                    output = result.stdout + result.stderr
                    success = result.returncode == 0
                    add_step(
                        "execute",
                        f"Shell: {command[:50]}{'...' if len(command) > 50 else ''}",
                        exec_start,
                        code=command,
                        output=output[:2000],
                        tool=tool_name,
                        returncode=result.returncode,
                    )
                    return {
                        "success": success,
                        "stdout": result.stdout,
                        "stderr": result.stderr,
                        "returncode": result.returncode,
                    }
                except subprocess.TimeoutExpired:
                    add_step(
                        "execute",
                        f"Shell timeout: {command[:50]}",
                        exec_start,
                        code=command,
                        output="Timeout",
                        tool=tool_name,
                    )
                    return {"success": False, "error": "Command timed out"}
                except Exception as e:
                    add_step(
                        "execute",
                        f"Shell error: {command[:50]}",
                        exec_start,
                        code=command,
                        output=str(e),
                        tool=tool_name,
                    )
                    return {"success": False, "error": str(e)}

            elif tool_name in ("jupyter_cell", "jupyter_cell_tool"):
                code = tool_args.get("code", "")
                all_code_generated.append(code)

                # Execute Python code in a subprocess
                try:
                    with tempfile.NamedTemporaryFile(
                        mode="w", suffix=".py", delete=False
                    ) as f:
                        f.write(code)
                        temp_file = f.name

                    result = subprocess.run(
                        ["python3", temp_file],
                        capture_output=True,
                        text=True,
                        timeout=60,
                        cwd=prompt.context.get("workspaceRoot", "."),
                    )
                    output = result.stdout + result.stderr
                    os.unlink(temp_file)

                    add_step(
                        "execute",
                        f"Python: {code[:50]}{'...' if len(code) > 50 else ''}",
                        exec_start,
                        code=code,
                        output=output[:2000],
                        tool=tool_name,
                    )
                    return {
                        "success": result.returncode == 0,
                        "stdout": result.stdout,
                        "stderr": result.stderr,
                        "output": output,
                    }
                except Exception as e:
                    add_step(
                        "execute",
                        f"Python error",
                        exec_start,
                        code=code,
                        output=str(e),
                        tool=tool_name,
                    )
                    return {"success": False, "error": str(e)}

            elif tool_name in ("write_file", "write_file_tool"):
                file_path = tool_args.get("path", "")
                content = tool_args.get("content", "")
                try:
                    workspace = prompt.context.get("workspaceRoot", ".")
                    full_path = os.path.join(workspace, file_path)
                    os.makedirs(os.path.dirname(full_path), exist_ok=True)
                    with open(full_path, "w") as f:
                        f.write(content)
                    add_step(
                        "execute",
                        f"Write: {file_path}",
                        exec_start,
                        code=f"# Write to {file_path}\n{content[:200]}{'...' if len(content) > 200 else ''}",
                        output=f"Written {len(content)} bytes to {file_path}",
                        tool=tool_name,
                    )
                    return {"success": True, "message": f"File written: {file_path}"}
                except Exception as e:
                    add_step(
                        "execute",
                        f"Write error: {file_path}",
                        exec_start,
                        output=str(e),
                        tool=tool_name,
                    )
                    return {"success": False, "error": str(e)}

            elif tool_name in ("read_file", "read_file_tool"):
                file_path = tool_args.get("path", "")
                try:
                    workspace = prompt.context.get("workspaceRoot", ".")
                    full_path = os.path.join(workspace, file_path)
                    with open(full_path) as f:
                        content = f.read()
                    add_step(
                        "execute",
                        f"Read: {file_path}",
                        exec_start,
                        output=f"Read {len(content)} bytes from {file_path}",
                        tool=tool_name,
                    )
                    return {"success": True, "content": content}
                except Exception as e:
                    add_step(
                        "execute",
                        f"Read error: {file_path}",
                        exec_start,
                        output=str(e),
                        tool=tool_name,
                    )
                    return {"success": False, "error": str(e)}

            elif tool_name in ("markdown", "markdown_tool"):
                content = tool_args.get("content", "")
                add_step(
                    "execute",
                    "Markdown output",
                    exec_start,
                    code=content[:200],
                    tool=tool_name,
                )
                return {"success": True, "content": content}

            elif tool_name in ("final_answer", "final_answer_tool"):
                answer = tool_args.get("answer", "")
                add_step(
                    "execute",
                    "Final answer",
                    exec_start,
                    output=answer[:500],
                    tool=tool_name,
                )
                return {"success": True, "answer": answer}

            else:
                # Unknown tool - just log it
                add_step(
                    "execute",
                    f"Unknown tool: {tool_name}",
                    exec_start,
                    details=tool_args,
                    tool=tool_name,
                )
                return {"success": True, "message": f"Tool {tool_name} acknowledged"}

        async def process_stream(
            session: aiohttp.ClientSession,
            endpoint: str,
            payload: dict,
        ) -> tuple[str | None, str | None, dict | None]:
            """
            Process SSE stream from agent endpoint.
            Returns: (thread_id, error, interrupt_info)
            """
            nonlocal full_response

            step_start = time.time()
            async with session.post(
                endpoint,
                json=payload,
                timeout=aiohttp.ClientTimeout(total=300),
            ) as response:
                add_step("request", "Sending request", step_start, status=response.status)

                if response.status != 200:
                    error_text = await response.text()
                    return None, f"HTTP {response.status}: {error_text[:200]}", None

                current_event_type = ""
                thread_id = None

                async for line in response.content:
                    line_text = line.decode("utf-8").strip()

                    if line_text.startswith("event:"):
                        current_event_type = line_text[6:].strip()
                        continue

                    if not line_text.startswith("data:"):
                        continue

                    data = line_text[5:].strip()
                    if not data or data == "[DONE]":
                        continue

                    try:
                        event = json.loads(data)
                    except json.JSONDecodeError:
                        continue

                    # Handle different event types
                    if current_event_type == "tool_call":
                        tool_name = event.get("tool", "")
                        tool_args = {
                            k: v
                            for k, v in event.items()
                            if k not in ("tool", "event")
                        }

                        # Execute tool locally
                        exec_result = execute_tool(tool_name, tool_args)

                        # Return interrupt info for resume
                        return thread_id, None, {
                            "tool": tool_name,
                            "args": tool_args,
                            "result": exec_result,
                        }

                    elif current_event_type == "complete":
                        thread_id = event.get("thread_id")
                        add_step("complete", "Agent completed", time.time())
                        return thread_id, None, None

                    elif current_event_type == "error":
                        error_msg = event.get("error", "Unknown error")
                        return None, error_msg, None

                    else:
                        # Default event handling
                        if event.get("error"):
                            return None, event.get("error"), None

                        if event.get("content"):
                            full_response += event.get("content", "")

                        if event.get("status"):
                            logger.debug(f"Status: {event.get('status')}")

                        # HITL Interrupt
                        if event.get("thread_id") and event.get("action"):
                            thread_id = event.get("thread_id")
                            action = event.get("action")
                            args = event.get("args", {})

                            # Auto-approve for E2E testing
                            return thread_id, None, {
                                "interrupt": True,
                                "action": action,
                                "args": args,
                            }

                return thread_id, None, None

        try:
            # Use LangChain agent endpoint for full E2E
            endpoint = f"{self.backend_url}/agent/langchain/stream"
            llm_config = {**self.llm_config, "autoApprove": True}
            payload = {
                "request": prompt.text,
                "threadId": None,
                "llmConfig": llm_config,
                "workspaceRoot": prompt.context.get("workspaceRoot", "."),
            }

            step_start = time.time()
            add_step("prepare", "Preparing agent request", step_start, endpoint=endpoint)

            async with aiohttp.ClientSession() as session:
                thread_id = None
                max_iterations = 20  # Prevent infinite loops

                for iteration in range(max_iterations):
                    if iteration > 0:
                        # Resume endpoint for subsequent iterations
                        endpoint = f"{self.backend_url}/agent/langchain/resume"
                        payload = {
                            "threadId": thread_id,
                            "decisions": [{"type": "approve"}],
                            "llmConfig": self.llm_config,
                            "workspaceRoot": prompt.context.get("workspaceRoot", "."),
                        }

                    result_thread_id, error, interrupt_info = await process_stream(
                        session, endpoint, payload
                    )

                    if result_thread_id:
                        thread_id = result_thread_id

                    if error:
                        return ExecutionResult(
                            success=False,
                            duration_ms=int((time.time() - start_time) * 1000),
                            error=error,
                            output=full_response,
                            steps=steps,
                        )

                    if interrupt_info is None:
                        # Completed successfully
                        break

                    if interrupt_info.get("interrupt"):
                        # HITL interrupt - auto-approve and continue
                        logger.info(f"Auto-approving HITL: {interrupt_info.get('action')}")
                        continue

                    if interrupt_info.get("tool"):
                        # Tool was executed, resume with result
                        tool_result = interrupt_info.get("result", {})
                        payload = {
                            "threadId": thread_id,
                            "decisions": [{
                                "type": "approve",
                                "args": {"execution_result": tool_result},
                            }],
                            "llmConfig": self.llm_config,
                            "workspaceRoot": prompt.context.get("workspaceRoot", "."),
                        }
                        endpoint = f"{self.backend_url}/agent/langchain/resume"

                duration_ms = int((time.time() - start_time) * 1000)
                execution_result = ExecutionResult(
                    success=True,
                    duration_ms=duration_ms,
                    output=full_response,
                    code_generated="\n\n".join(all_code_generated),
                    steps=steps,
                )
                self._save_cassette(prompt, execution_result)
                return execution_result

        except asyncio.TimeoutError:
            return ExecutionResult(
                success=False,
                duration_ms=int((time.time() - start_time) * 1000),
                error="Timeout after 300s",
                output=full_response,
                steps=steps,
            )
        except aiohttp.ClientConnectorError as e:
            return ExecutionResult(
                success=False,
                duration_ms=int((time.time() - start_time) * 1000),
                error=f"Connection error: {e}. Is backend running on {self.backend_url}?",
                steps=steps,
            )
        except Exception as e:
            logger.exception("Unexpected error in _execute_live")
            return ExecutionResult(
                success=False,
                duration_ms=int((time.time() - start_time) * 1000),
                error=str(e),
                output=full_response,
                steps=steps,
            )

    def _extract_code(self, response: str) -> str:
        """Extract code blocks from markdown response"""
        code_blocks = re.findall(r"```(?:\w+)?\n(.*?)```", response, re.DOTALL)
        return "\n\n".join(code_blocks)

    def _save_cassette(self, prompt: Prompt, result: ExecutionResult) -> None:
        """Save result to cassette cache"""
        cassette_file = self.cassettes_dir / f"{prompt.id}.yaml"

        prompt_hash = compute_prompt_hash(prompt.text, prompt.context)

        result_dict = {
            "prompt_hash": prompt_hash,
            "prompt_text": prompt.text,
            "success": result.success,
            "duration_ms": result.duration_ms,
            "error": result.error,
            "output": result.output,
            "code_generated": result.code_generated,
            "steps": [asdict(s) for s in result.steps],
            "tokens_input": result.tokens_input,
            "tokens_output": result.tokens_output,
            "recorded_at": datetime.now(timezone.utc).isoformat(),
        }

        with open(cassette_file, "w") as f:
            yaml.dump(result_dict, f, default_flow_style=False, allow_unicode=True)


class MetricsEvaluator:
    """Evaluate test metrics"""

    def evaluate(self, prompt: Prompt, result: ExecutionResult) -> Metrics:
        """Evaluate execution result"""
        metrics = Metrics()

        metrics.execution_success = result.success

        if result.success:
            metrics.response_quality = self._evaluate_response_quality(result.output)
            metrics.pattern_match = self._evaluate_pattern_match(
                result.output + result.code_generated, prompt.expected_patterns
            )
            metrics.latency_score = self._evaluate_latency(result.duration_ms)

        metrics.overall_score = self._calculate_overall(metrics)
        return metrics

    def _evaluate_response_quality(self, output: str) -> float:
        """Evaluate response quality (0-100)"""
        if not output.strip():
            return 0.0

        score = 50.0

        # Length bonus (reasonable response length)
        length = len(output)
        if length > 100:
            score += 10
        if length > 500:
            score += 10
        if length > 1000:
            score += 10

        # Structure bonus (markdown formatting)
        if "```" in output:
            score += 10
        if "\n-" in output or "\n*" in output:
            score += 5
        if "##" in output or "**" in output:
            score += 5

        return min(score, 100.0)

    def _evaluate_pattern_match(self, text: str, patterns: list[str]) -> float:
        """Evaluate pattern matching (0-100)"""
        if not patterns:
            return 100.0

        matched = sum(
            1 for p in patterns if re.search(p, text, re.IGNORECASE)
        )
        return (matched / len(patterns)) * 100

    def _evaluate_latency(self, duration_ms: int) -> float:
        """Evaluate latency score (0-100)"""
        if duration_ms < 2000:
            return 100.0
        elif duration_ms < 5000:
            return 80.0
        elif duration_ms < 10000:
            return 60.0
        elif duration_ms < 30000:
            return 40.0
        else:
            return 30.0

    def _calculate_overall(self, metrics: Metrics) -> float:
        """Calculate overall score"""
        if not metrics.execution_success:
            return 0.0

        weights = {
            "response_quality": 0.30,
            "pattern_match": 0.50,
            "latency_score": 0.20,
        }

        score = sum(
            getattr(metrics, key, 0) * weight for key, weight in weights.items()
        )

        return round(score, 2)


class ResultCollector:
    """Collect and save test results"""

    def __init__(self, results_dir: Path = RESULTS_DIR):
        self.results_dir = results_dir
        self.results_dir.mkdir(parents=True, exist_ok=True)
        self.results: list[TestResult] = []

    def add(self, result: TestResult) -> None:
        """Add a test result"""
        self.results.append(result)

    def save_jsonl(self) -> Path:
        """Save results in JSONL format"""
        timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        output_file = self.results_dir / f"results-{timestamp}.jsonl"

        with open(output_file, "w") as f:
            for result in self.results:
                f.write(json.dumps(self._to_dict(result), ensure_ascii=False) + "\n")

        # Update latest symlink
        latest_file = self.results_dir / "latest.jsonl"
        latest_file.write_text(output_file.read_text())

        return output_file

    def save_summary(self) -> Path:
        """Save summary JSON"""
        summary = self._generate_summary()
        output_file = self.results_dir / "latest.json"

        with open(output_file, "w") as f:
            json.dump(summary, f, indent=2, ensure_ascii=False)

        return output_file

    def _generate_summary(self) -> dict[str, Any]:
        """Generate summary statistics"""
        total = len(self.results)
        passed = sum(1 for r in self.results if r.execution.success)
        failed = total - passed

        avg_score = (
            sum(r.metrics.overall_score for r in self.results) / total
            if total > 0
            else 0
        )
        avg_latency = (
            sum(r.execution.duration_ms for r in self.results) / total
            if total > 0
            else 0
        )

        return {
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "total_prompts": total,
            "passed": passed,
            "failed": failed,
            "pass_rate": round(passed / total * 100, 2) if total > 0 else 0,
            "average_score": round(avg_score, 2),
            "average_latency_ms": round(avg_latency, 2),
            "by_category": self._summarize_by_category(),
            "failures": [
                {"prompt_id": r.prompt_id, "error": r.execution.error}
                for r in self.results
                if not r.execution.success
            ],
        }

    def _summarize_by_category(self) -> dict[str, dict]:
        """Summarize by category"""
        categories: dict[str, list[TestResult]] = {}
        for r in self.results:
            if r.category not in categories:
                categories[r.category] = []
            categories[r.category].append(r)

        summary = {}
        for cat, results in categories.items():
            total = len(results)
            passed = sum(1 for r in results if r.execution.success)
            avg_score = (
                sum(r.metrics.overall_score for r in results) / total
                if total > 0
                else 0
            )
            summary[cat] = {
                "total": total,
                "passed": passed,
                "failed": total - passed,
                "average_score": round(avg_score, 2),
            }
        return summary

    def _to_dict(self, result: TestResult) -> dict:
        """Convert TestResult to dict"""
        execution_dict = {
            "success": result.execution.success,
            "duration_ms": result.execution.duration_ms,
            "error": result.execution.error,
            "output": result.execution.output,
            "code_generated": result.execution.code_generated,
            "steps": [asdict(s) for s in result.execution.steps],
            "tokens_input": result.execution.tokens_input,
            "tokens_output": result.execution.tokens_output,
        }
        return {
            "prompt_id": result.prompt_id,
            "category": result.category,
            "prompt_text": result.prompt_text,
            "timestamp": result.timestamp,
            "execution": execution_dict,
            "metrics": asdict(result.metrics),
            "performance": asdict(result.performance),
        }

    def generate_html_report(self) -> Path:
        """Generate HTML report with embedded data"""
        summary = self._generate_summary()
        results_data = [self._to_dict(r) for r in self.results]

        template_file = self.results_dir / "report_template.html"
        output_file = self.results_dir / "report.html"

        if not template_file.exists():
            logger.warning("No report template found, skipping HTML report")
            return output_file

        template = template_file.read_text()

        # Escape JSON for safe embedding
        def safe_json(data):
            json_str = json.dumps(data, ensure_ascii=False)
            return json_str.replace("</script>", '</scr"+"ipt>')

        summary_json = safe_json(summary)
        results_json = safe_json(results_data)

        # Embed JSON data in script tags
        data_script = f'''<script type="application/json" id="summary-data">{summary_json}</script>
    <script type="application/json" id="results-data">{results_json}</script>
    '''
        template = template.replace("</head>", data_script + "</head>")

        # Update JavaScript to load embedded data
        template = re.sub(
            r"let summaryData = null;",
            'let summaryData = JSON.parse(document.getElementById("summary-data")?.textContent || "null");',
            template,
        )
        template = re.sub(
            r"let resultsData = \[\];",
            'let resultsData = JSON.parse(document.getElementById("results-data")?.textContent || "[]");',
            template,
        )
        template = re.sub(
            r"loadAndRender\(\);",
            "if (summaryData && resultsData.length) { renderReport(); } else { loadAndRender(); }",
            template,
        )

        output_file.write_text(template)

        # Also copy to reports directory
        REPORTS_DIR.mkdir(parents=True, exist_ok=True)
        reports_output = REPORTS_DIR / "test" / "report.html"
        reports_output.parent.mkdir(parents=True, exist_ok=True)
        reports_output.write_text(template)

        return output_file


async def run_tests(
    prompts: list[Prompt],
    executor: MockExecutor | LiveExecutor,
    evaluator: MetricsEvaluator,
    collector: ResultCollector,
    parallel: int = 1,
    rate_limit_delay: float = 0.0,
) -> None:
    """Run tests"""
    semaphore = asyncio.Semaphore(parallel)

    async def run_single(prompt: Prompt, index: int) -> None:
        async with semaphore:
            if rate_limit_delay > 0 and index > 0:
                delay = rate_limit_delay * (index % parallel)
                if delay > 0:
                    await asyncio.sleep(delay)

            logger.info(f"Running: {prompt.id}")
            execution = await executor.execute(prompt)
            metrics = evaluator.evaluate(prompt, execution)

            result = TestResult(
                prompt_id=prompt.id,
                category=prompt.category,
                prompt_text=prompt.text,
                timestamp=datetime.now(timezone.utc).isoformat(),
                execution=execution,
                metrics=metrics,
                performance=Performance(
                    latency_ms=execution.duration_ms,
                    tokens_input=execution.tokens_input,
                    tokens_output=execution.tokens_output,
                ),
            )

            collector.add(result)

            status = "PASS" if execution.success else "FAIL"
            logger.info(f"  {prompt.id}: {status} (score: {metrics.overall_score})")

            if rate_limit_delay > 0:
                await asyncio.sleep(rate_limit_delay)

    tasks = [run_single(p, i) for i, p in enumerate(prompts)]
    await asyncio.gather(*tasks)


def main() -> int:
    """Main function"""
    parser = argparse.ArgumentParser(description="PyCharm Agent E2E Prompt Tests")
    parser.add_argument(
        "--mock", action="store_true", default=True, help="Mock mode (default)"
    )
    parser.add_argument(
        "--live", action="store_true", help="Live mode (call real backend)"
    )
    parser.add_argument(
        "--force-live",
        action="store_true",
        help="Force live mode (ignore cache)",
    )
    parser.add_argument("--category", type=str, help="Test specific category")
    parser.add_argument("--prompt-id", type=str, help="Test specific prompt ID")
    parser.add_argument("--limit", type=int, help="Limit number of prompts")
    parser.add_argument("--parallel", type=int, default=1, help="Parallel execution")
    parser.add_argument(
        "--backend-url",
        type=str,
        default="http://localhost:8000",
        help="Backend URL",
    )
    parser.add_argument(
        "--rate-limit-delay",
        type=float,
        default=0.0,
        help="Delay between requests (seconds)",
    )
    parser.add_argument("-v", "--verbose", action="store_true", help="Verbose logging")

    args = parser.parse_args()

    if args.verbose:
        logging.getLogger().setLevel(logging.DEBUG)

    # Load prompts
    loader = PromptLoader()
    prompts: list[Prompt] = []

    if args.prompt_id:
        prompt = loader.get_by_id(args.prompt_id)
        if prompt:
            prompts = [prompt]
        else:
            logger.error(f"Prompt not found: {args.prompt_id}")
            return 1
    elif args.category:
        # Category aliases (same as hdsp_agent)
        category_map = {
            "python": "python",
            "py": "python",
            "athena": "athena",
            "ath": "athena",
            "spark": "spark",
            "spk": "spark",
            "multi_agent": "multi_agent",
            "ma": "multi_agent",
            "agent": "agent",
        }
        cat = category_map.get(args.category.lower(), args.category.lower())
        prompts = loader.get_by_category(cat)
    else:
        prompts = loader.get_all()

    if args.limit:
        prompts = prompts[: args.limit]

    if not prompts:
        logger.warning("No prompts to run")
        return 0

    mode = "mock"
    if args.force_live:
        mode = "force-live"
    elif args.live:
        mode = "live"

    logger.info(f"Running {len(prompts)} prompts (mode: {mode})")

    # Select executor
    if args.live or args.force_live:
        executor: MockExecutor | LiveExecutor = LiveExecutor(
            args.backend_url, force_live=args.force_live
        )
    else:
        executor = MockExecutor()

    evaluator = MetricsEvaluator()
    collector = ResultCollector()

    # Run tests
    asyncio.run(
        run_tests(
            prompts, executor, evaluator, collector, args.parallel, args.rate_limit_delay
        )
    )

    # Save results
    jsonl_file = collector.save_jsonl()
    summary_file = collector.save_summary()
    html_file = collector.generate_html_report()

    logger.info(f"Results saved to: {jsonl_file}")
    logger.info(f"Summary saved to: {summary_file}")
    logger.info(f"HTML report: {html_file}")

    # Print summary
    with open(summary_file) as f:
        summary = json.load(f)

    print("\n" + "=" * 50)
    print("TEST SUMMARY")
    print("=" * 50)
    print(f"Total: {summary['total_prompts']}")
    print(f"Passed: {summary['passed']}")
    print(f"Failed: {summary['failed']}")
    print(f"Pass Rate: {summary['pass_rate']}%")
    print(f"Average Score: {summary['average_score']}")
    print(f"Average Latency: {summary['average_latency_ms']}ms")

    if summary["failures"]:
        print("\nFailures:")
        for f_info in summary["failures"]:
            print(f"  - {f_info['prompt_id']}: {f_info['error']}")

    return 0 if summary["failed"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
