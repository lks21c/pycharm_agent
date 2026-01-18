# Agent Server Control

Control the Agent Server (FastAPI backend on port 8000).

**Usage**: `/as [start|stop|status|restart|logs]`

**Argument**: $ARGUMENTS

## Instructions

Based on the argument provided, perform the appropriate action:

### If argument is "start" or empty:
1. First check if agent server is already running:
   ```bash
   ps aux | grep "uvicorn.*agent_server" | grep -v grep
   ```
2. If already running, report "Agent Server is already running on port 8000"
3. If not running, start it in background:
   ```bash
   cd /Users/hydra01/repo/hdsp_agent/agent-server && poetry run uvicorn agent_server.main:app --host 0.0.0.0 --port 8000
   ```
   (Run this command in background using `run_in_background: true`)
4. Wait 3 seconds and verify it started:
   ```bash
   curl -s http://localhost:8000/health || echo "Starting..."
   ```

### If argument is "stop":
1. Find and kill the agent server process:
   ```bash
   pkill -f "uvicorn.*agent_server" && echo "Agent Server stopped" || echo "Agent Server was not running"
   ```

### If argument is "status":
1. Check if running:
   ```bash
   ps aux | grep "uvicorn.*agent_server" | grep -v grep
   ```
2. If running, also check health endpoint:
   ```bash
   curl -s http://localhost:8000/health
   ```
3. Report status clearly (Running/Stopped, PID, port)

### If argument is "restart":
1. Stop the server first (using stop logic above)
2. Wait 2 seconds
3. Start the server (using start logic above)

### If argument is "logs":
1. Show recent logs from the background task if available
2. Use `tail` on the output file or check task status

## Output Format

Always report clearly:
- **Status**: Running / Stopped
- **Port**: 8000
- **PID**: (if running)
- **Health**: (if applicable)
