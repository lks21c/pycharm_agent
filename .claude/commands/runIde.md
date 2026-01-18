# PyCharm Plugin Test IDE Control

Control the PyCharm plugin test IDE instance.

## Usage
- `/runIde` or `/runIde start` - Start the test IDE
- `/runIde stop` - Stop the running test IDE
- `/runIde restart` - Restart the test IDE

## Instructions

Based on the argument provided, execute the appropriate action:

### start (default)
1. First check if runIde is already running: `pgrep -f "idea-sandbox"`
2. If running, inform user it's already running
3. If not running, execute in background:
```bash
export JAVA_HOME="/Applications/PyCharm.app/Contents/jbr/Contents/Home"
cd /Users/hydra01/repo/pycharm_agent/plugin && ./gradlew runIde
```

### stop
1. Find and kill the runIde process:
```bash
pkill -f "idea-sandbox" || pkill -f "pycharm-community.*sandbox"
```
2. Confirm the process was stopped

### restart
1. Execute stop action
2. Wait 2 seconds
3. Execute start action

Always run commands in background for start/restart and report the status to user.
