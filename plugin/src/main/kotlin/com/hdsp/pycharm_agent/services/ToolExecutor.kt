package com.hdsp.pycharm_agent.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

/**
 * Executes HDSP tool calls locally in PyCharm
 */
@Service(Service.Level.PROJECT)
class ToolExecutor(private val project: Project) {

    /**
     * Execute a tool call and return the result
     *
     * Supports both legacy tools and LangChain agent tools:
     * - jupyter_cell, read_file, write_file, final_answer (legacy)
     * - shell, edit_file, create_file, delete_file (LangChain)
     */
    fun execute(toolCall: ToolCall): ToolExecutionResult {
        return try {
            when (toolCall.tool) {
                // Legacy tools
                "jupyter_cell" -> executeJupyterCell(toolCall.parameters)
                "read_file" -> executeReadFile(toolCall.parameters)
                "write_file" -> executeWriteFile(toolCall.parameters)
                "final_answer" -> executeFinalAnswer(toolCall.parameters)
                "list_files" -> executeListFiles(toolCall.parameters)
                "execute_command" -> executeCommand(toolCall.parameters)
                "search_files" -> executeSearchFiles(toolCall.parameters)

                // LangChain agent tools
                "shell" -> executeShell(toolCall.parameters)
                "edit_file" -> executeEditFile(toolCall.parameters)
                "create_file" -> executeCreateFile(toolCall.parameters)
                "delete_file" -> executeDeleteFile(toolCall.parameters)
                "read" -> executeReadFile(toolCall.parameters)  // Alias for read_file
                "write" -> executeWriteFile(toolCall.parameters)  // Alias for write_file
                "glob" -> executeGlob(toolCall.parameters)
                "grep" -> executeGrep(toolCall.parameters)

                else -> ToolExecutionResult(
                    success = false,
                    error = ErrorInfo(message = "Unknown tool: ${toolCall.tool}")
                )
            }
        } catch (e: Exception) {
            ToolExecutionResult(
                success = false,
                error = ErrorInfo(
                    type = e.javaClass.simpleName,
                    message = e.message ?: "Unknown error",
                    traceback = e.stackTrace.take(5).map { it.toString() }
                )
            )
        }
    }

    /**
     * Convert jupyter_cell to file edit
     * In PyCharm context, we interpret this as inserting/modifying code in a file
     */
    private fun executeJupyterCell(params: Map<String, Any>): ToolExecutionResult {
        val action = params["action"]?.toString() ?: "CREATE"
        val code = params["code"]?.toString() ?: params["content"]?.toString() ?: ""
        val targetFile = params["target_file"]?.toString() ?: params["file"]?.toString()

        if (targetFile == null) {
            // If no target file specified, return the code as output
            return ToolExecutionResult(
                success = true,
                output = code,
                codeGenerated = code
            )
        }

        val fullPath = resolveFilePath(targetFile)
        val file = File(fullPath)
        val existingContent = if (file.exists()) file.readText() else ""

        val newContent = when (action.uppercase()) {
            "CREATE" -> code
            "MODIFY", "INSERT_AFTER" -> "$existingContent\n\n$code"
            "INSERT_BEFORE" -> "$code\n\n$existingContent"
            else -> code
        }

        val diff = generateDiff(fullPath, existingContent, newContent)
        return ToolExecutionResult(
            success = true,
            diff = diff,
            output = "Generated code for $targetFile"
        )
    }

    /**
     * Read file contents
     */
    private fun executeReadFile(params: Map<String, Any>): ToolExecutionResult {
        val filePath = params["path"]?.toString() ?: params["file"]?.toString()
            ?: return ToolExecutionResult(
                success = false,
                error = ErrorInfo(message = "Missing 'path' parameter")
            )

        val fullPath = resolveFilePath(filePath)
        val file = File(fullPath)

        if (!file.exists()) {
            return ToolExecutionResult(
                success = false,
                error = ErrorInfo(message = "File not found: $filePath")
            )
        }

        val content = file.readText()
        return ToolExecutionResult(
            success = true,
            output = content
        )
    }

    /**
     * Write file contents with diff preview
     */
    private fun executeWriteFile(params: Map<String, Any>): ToolExecutionResult {
        val filePath = params["path"]?.toString() ?: params["file"]?.toString()
            ?: return ToolExecutionResult(
                success = false,
                error = ErrorInfo(message = "Missing 'path' parameter")
            )

        val content = params["content"]?.toString()
            ?: return ToolExecutionResult(
                success = false,
                error = ErrorInfo(message = "Missing 'content' parameter")
            )

        val fullPath = resolveFilePath(filePath)
        val file = File(fullPath)
        val existingContent = if (file.exists()) file.readText() else ""

        val diff = generateDiff(fullPath, existingContent, content)
        return ToolExecutionResult(
            success = true,
            diff = diff,
            output = "Will write to $filePath"
        )
    }

    /**
     * Handle final_answer tool - display result to user
     */
    private fun executeFinalAnswer(params: Map<String, Any>): ToolExecutionResult {
        val answer = params["answer"]?.toString() ?: params["content"]?.toString() ?: ""
        return ToolExecutionResult(
            success = true,
            output = answer,
            isFinalAnswer = true
        )
    }

    /**
     * List files in directory
     */
    private fun executeListFiles(params: Map<String, Any>): ToolExecutionResult {
        val dirPath = params["path"]?.toString() ?: params["directory"]?.toString() ?: "."
        val pattern = params["pattern"]?.toString() ?: "*"

        val fullPath = resolveFilePath(dirPath)
        val dir = File(fullPath)

        if (!dir.exists() || !dir.isDirectory) {
            return ToolExecutionResult(
                success = false,
                error = ErrorInfo(message = "Directory not found: $dirPath")
            )
        }

        val files = if (pattern == "*") {
            dir.listFiles()?.map { it.name } ?: emptyList()
        } else {
            dir.listFiles()?.filter { it.name.matches(Regex(pattern.replace("*", ".*"))) }?.map { it.name }
                ?: emptyList()
        }

        return ToolExecutionResult(
            success = true,
            output = files.joinToString("\n")
        )
    }

    /**
     * Execute shell command (limited support)
     */
    private fun executeCommand(params: Map<String, Any>): ToolExecutionResult {
        val command = params["command"]?.toString()
            ?: return ToolExecutionResult(
                success = false,
                error = ErrorInfo(message = "Missing 'command' parameter")
            )

        // For security, only allow certain safe commands
        val safeCommands = listOf("ls", "pwd", "cat", "head", "tail", "wc", "grep", "find", "echo")
        val firstWord = command.split(" ").firstOrNull()?.trim()

        if (firstWord !in safeCommands) {
            return ToolExecutionResult(
                success = false,
                error = ErrorInfo(message = "Command not allowed: $firstWord. Only safe commands are permitted.")
            )
        }

        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .directory(File(project.basePath ?: "."))
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                ToolExecutionResult(success = true, output = output)
            } else {
                ToolExecutionResult(
                    success = false,
                    error = ErrorInfo(message = "Command failed with exit code $exitCode: $output")
                )
            }
        } catch (e: Exception) {
            ToolExecutionResult(
                success = false,
                error = ErrorInfo(message = "Failed to execute command: ${e.message}")
            )
        }
    }

    /**
     * Search files for content
     */
    private fun executeSearchFiles(params: Map<String, Any>): ToolExecutionResult {
        val pattern = params["pattern"]?.toString() ?: params["query"]?.toString()
            ?: return ToolExecutionResult(
                success = false,
                error = ErrorInfo(message = "Missing 'pattern' parameter")
            )

        val dirPath = params["path"]?.toString() ?: params["directory"]?.toString() ?: "."
        val fullPath = resolveFilePath(dirPath)

        val results = mutableListOf<String>()
        val regex = Regex(pattern, RegexOption.IGNORE_CASE)

        File(fullPath).walkTopDown()
            .filter { it.isFile && !it.path.contains(".git") }
            .take(100) // Limit files to search
            .forEach { file ->
                try {
                    file.readLines().forEachIndexed { lineNum, line ->
                        if (regex.containsMatchIn(line)) {
                            results.add("${file.relativeTo(File(fullPath))}:${lineNum + 1}: $line")
                        }
                    }
                } catch (e: Exception) {
                    // Skip files that can't be read
                }
            }

        return ToolExecutionResult(
            success = true,
            output = if (results.isEmpty()) "No matches found" else results.take(50).joinToString("\n")
        )
    }

    // =========================================================================
    // LangChain Agent Tools
    // =========================================================================

    /**
     * Execute shell command (LangChain tool)
     * Similar to execute_command but with broader support
     */
    private fun executeShell(params: Map<String, Any>): ToolExecutionResult {
        val command = params["command"]?.toString()
            ?: return ToolExecutionResult(
                success = false,
                error = ErrorInfo(message = "Missing 'command' parameter")
            )

        val timeout = (params["timeout"] as? Number)?.toLong() ?: 30L

        // Security check - block dangerous commands
        val dangerousPatterns = listOf("rm -rf /", "rm -rf ~", ":(){ :|:& };:", "> /dev/sda")
        if (dangerousPatterns.any { command.contains(it) }) {
            return ToolExecutionResult(
                success = false,
                error = ErrorInfo(message = "Dangerous command blocked for security")
            )
        }

        return try {
            val processBuilder = ProcessBuilder("sh", "-c", command)
                .directory(File(project.basePath ?: "."))
                .redirectErrorStream(true)

            val process = processBuilder.start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                ToolExecutionResult(success = true, output = output)
            } else {
                ToolExecutionResult(
                    success = false,
                    output = output,
                    error = ErrorInfo(message = "Command failed with exit code $exitCode")
                )
            }
        } catch (e: Exception) {
            ToolExecutionResult(
                success = false,
                error = ErrorInfo(message = "Shell execution failed: ${e.message}")
            )
        }
    }

    /**
     * Edit file with specific changes (LangChain tool)
     */
    private fun executeEditFile(params: Map<String, Any>): ToolExecutionResult {
        val filePath = params["path"]?.toString() ?: params["file"]?.toString()
            ?: return ToolExecutionResult(
                success = false,
                error = ErrorInfo(message = "Missing 'path' parameter")
            )

        val oldStr = params["old_str"]?.toString() ?: params["search"]?.toString()
        val newStr = params["new_str"]?.toString() ?: params["replace"]?.toString() ?: ""

        val fullPath = resolveFilePath(filePath)
        val file = File(fullPath)

        if (!file.exists()) {
            return ToolExecutionResult(
                success = false,
                error = ErrorInfo(message = "File not found: $filePath")
            )
        }

        val existingContent = file.readText()

        val newContent = if (oldStr != null) {
            if (!existingContent.contains(oldStr)) {
                return ToolExecutionResult(
                    success = false,
                    error = ErrorInfo(message = "Search string not found in file")
                )
            }
            existingContent.replace(oldStr, newStr)
        } else {
            // If no old_str, treat new_str as full replacement
            newStr
        }

        val diff = generateDiff(fullPath, existingContent, newContent)
        return ToolExecutionResult(
            success = true,
            diff = diff,
            output = "Edit prepared for $filePath"
        )
    }

    /**
     * Create new file (LangChain tool)
     */
    private fun executeCreateFile(params: Map<String, Any>): ToolExecutionResult {
        val filePath = params["path"]?.toString() ?: params["file"]?.toString()
            ?: return ToolExecutionResult(
                success = false,
                error = ErrorInfo(message = "Missing 'path' parameter")
            )

        val content = params["content"]?.toString() ?: ""

        val fullPath = resolveFilePath(filePath)
        val file = File(fullPath)

        if (file.exists()) {
            return ToolExecutionResult(
                success = false,
                error = ErrorInfo(message = "File already exists: $filePath")
            )
        }

        val diff = generateDiff(fullPath, "", content)
        return ToolExecutionResult(
            success = true,
            diff = diff,
            output = "Create file: $filePath"
        )
    }

    /**
     * Delete file (LangChain tool) - returns diff for preview
     */
    private fun executeDeleteFile(params: Map<String, Any>): ToolExecutionResult {
        val filePath = params["path"]?.toString() ?: params["file"]?.toString()
            ?: return ToolExecutionResult(
                success = false,
                error = ErrorInfo(message = "Missing 'path' parameter")
            )

        val fullPath = resolveFilePath(filePath)
        val file = File(fullPath)

        if (!file.exists()) {
            return ToolExecutionResult(
                success = false,
                error = ErrorInfo(message = "File not found: $filePath")
            )
        }

        val existingContent = file.readText()
        val diff = DiffResult(
            filePath = fullPath,
            hunks = listOf(DiffHunk(
                startLine = 1,
                endLine = existingContent.lines().size,
                originalContent = existingContent,
                newContent = "",
                changeType = "delete"
            )),
            unifiedDiff = "--- a/$filePath\n+++ /dev/null\n@@ -1,${existingContent.lines().size} +0,0 @@\n${existingContent.lines().joinToString("\n") { "-$it" }}",
            previewContent = ""  // Empty content = delete
        )

        return ToolExecutionResult(
            success = true,
            diff = diff,
            output = "Delete file: $filePath"
        )
    }

    /**
     * Glob pattern file search (LangChain tool)
     */
    private fun executeGlob(params: Map<String, Any>): ToolExecutionResult {
        val pattern = params["pattern"]?.toString()
            ?: return ToolExecutionResult(
                success = false,
                error = ErrorInfo(message = "Missing 'pattern' parameter")
            )

        val basePath = params["path"]?.toString() ?: "."
        val fullPath = resolveFilePath(basePath)

        val results = mutableListOf<String>()
        val globRegex = pattern
            .replace(".", "\\.")
            .replace("**", "{{DOUBLE_STAR}}")
            .replace("*", "[^/]*")
            .replace("{{DOUBLE_STAR}}", ".*")
            .let { Regex(it) }

        File(fullPath).walkTopDown()
            .filter { it.isFile && !it.path.contains(".git") }
            .take(500)
            .forEach { file ->
                val relativePath = file.relativeTo(File(fullPath)).path
                if (globRegex.matches(relativePath)) {
                    results.add(relativePath)
                }
            }

        return ToolExecutionResult(
            success = true,
            output = if (results.isEmpty()) "No files matched pattern" else results.joinToString("\n")
        )
    }

    /**
     * Grep search (LangChain tool)
     */
    private fun executeGrep(params: Map<String, Any>): ToolExecutionResult {
        val pattern = params["pattern"]?.toString() ?: params["regex"]?.toString()
            ?: return ToolExecutionResult(
                success = false,
                error = ErrorInfo(message = "Missing 'pattern' parameter")
            )

        val path = params["path"]?.toString() ?: "."
        val glob = params["glob"]?.toString()
        val contextLines = (params["context"] as? Number)?.toInt() ?: 0

        val fullPath = resolveFilePath(path)
        val results = mutableListOf<String>()

        try {
            val regex = Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
            val globPattern = glob?.let {
                it.replace(".", "\\.")
                    .replace("**", "{{DOUBLE_STAR}}")
                    .replace("*", "[^/]*")
                    .replace("{{DOUBLE_STAR}}", ".*")
                    .let { p -> Regex(p) }
            }

            File(fullPath).walkTopDown()
                .filter { file ->
                    file.isFile &&
                    !file.path.contains(".git") &&
                    (globPattern == null || globPattern.matches(file.relativeTo(File(fullPath)).path))
                }
                .take(100)
                .forEach { file ->
                    try {
                        val lines = file.readLines()
                        lines.forEachIndexed { lineNum, line ->
                            if (regex.containsMatchIn(line)) {
                                val relativePath = file.relativeTo(File(fullPath)).path
                                if (contextLines > 0) {
                                    val startLine = maxOf(0, lineNum - contextLines)
                                    val endLine = minOf(lines.size - 1, lineNum + contextLines)
                                    for (i in startLine..endLine) {
                                        val prefix = if (i == lineNum) ">" else " "
                                        results.add("$relativePath:${i + 1}:$prefix ${lines[i]}")
                                    }
                                    results.add("---")
                                } else {
                                    results.add("$relativePath:${lineNum + 1}: $line")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Skip files that can't be read
                    }
                }
        } catch (e: Exception) {
            return ToolExecutionResult(
                success = false,
                error = ErrorInfo(message = "Invalid regex pattern: ${e.message}")
            )
        }

        return ToolExecutionResult(
            success = true,
            output = if (results.isEmpty()) "No matches found" else results.take(200).joinToString("\n")
        )
    }

    /**
     * Resolve file path relative to project root
     */
    private fun resolveFilePath(path: String): String {
        return if (path.startsWith("/")) {
            path
        } else {
            "${project.basePath}/$path"
        }
    }

    /**
     * Generate a diff between old and new content
     */
    private fun generateDiff(filePath: String, oldContent: String, newContent: String): DiffResult {
        val hunks = mutableListOf<DiffHunk>()

        if (oldContent.isEmpty()) {
            // New file
            hunks.add(
                DiffHunk(
                    startLine = 1,
                    endLine = newContent.lines().size,
                    originalContent = "",
                    newContent = newContent,
                    changeType = "add"
                )
            )
        } else if (oldContent != newContent) {
            // Modified file - simple diff
            hunks.add(
                DiffHunk(
                    startLine = 1,
                    endLine = maxOf(oldContent.lines().size, newContent.lines().size),
                    originalContent = oldContent,
                    newContent = newContent,
                    changeType = "modify"
                )
            )
        }

        val unifiedDiff = buildUnifiedDiff(filePath, oldContent, newContent)

        return DiffResult(
            filePath = filePath,
            hunks = hunks,
            unifiedDiff = unifiedDiff,
            previewContent = newContent
        )
    }

    /**
     * Build a simple unified diff string
     */
    private fun buildUnifiedDiff(filePath: String, oldContent: String, newContent: String): String {
        val sb = StringBuilder()
        sb.appendLine("--- a/$filePath")
        sb.appendLine("+++ b/$filePath")

        val oldLines = oldContent.lines()
        val newLines = newContent.lines()

        if (oldContent.isEmpty()) {
            sb.appendLine("@@ -0,0 +1,${newLines.size} @@")
            newLines.forEach { sb.appendLine("+$it") }
        } else {
            sb.appendLine("@@ -1,${oldLines.size} +1,${newLines.size} @@")
            oldLines.forEach { sb.appendLine("-$it") }
            newLines.forEach { sb.appendLine("+$it") }
        }

        return sb.toString()
    }

    /**
     * Apply a diff result to the actual file
     * Handles create, modify, and delete operations
     */
    fun applyDiff(diff: DiffResult): Boolean {
        return try {
            val file = File(diff.filePath)

            if (diff.previewContent.isEmpty() && file.exists()) {
                // Delete operation
                val deleted = file.delete()
                if (deleted) {
                    // Refresh VFS
                    ApplicationManager.getApplication().invokeLater {
                        LocalFileSystem.getInstance().refreshAndFindFileByPath(diff.filePath)?.let { vf ->
                            vf.refresh(false, false)
                        }
                    }
                }
                deleted
            } else {
                // Create or modify operation
                // Ensure parent directories exist
                file.parentFile?.mkdirs()

                // Write the new content
                file.writeText(diff.previewContent)

                // Refresh VFS
                ApplicationManager.getApplication().invokeLater {
                    LocalFileSystem.getInstance().refreshAndFindFileByPath(diff.filePath)?.let { vf ->
                        vf.refresh(false, false)
                    }
                }

                true
            }
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Result of a tool execution
 */
data class ToolExecutionResult(
    val success: Boolean,
    val output: String? = null,
    val error: ErrorInfo? = null,
    val diff: DiffResult? = null,
    val codeGenerated: String? = null,
    val isFinalAnswer: Boolean = false
)
