# PyCharm Agent Build

Build the PyCharm Agent plugin with OpenJDK 21.

## Instructions

Execute the following command to build the plugin:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && cd /Users/hydra01/repo/pycharm_agent/plugin && ./gradlew buildPlugin
```

After build completes, report:
1. Build status (success/failure)
2. Plugin file location: `plugin/build/distributions/pycharm-agent-*.zip`
3. File size

If build fails, analyze the error and suggest fixes.
