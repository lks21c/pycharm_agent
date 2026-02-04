package com.hdsp.pycharm_agent.acp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ACP Filesystem Handler Implementation
 *
 * Uses IntelliJ's VFS (Virtual File System) for proper file operations
 * with correct threading and file synchronization.
 */
class ACPFileSystemHandlerImpl(private val project: Project) : ACPFileSystemHandler {

    private val log = Logger.getInstance(ACPFileSystemHandlerImpl::class.java)

    override fun readTextFile(path: String): String {
        log.debug("Reading file: $path")

        val file = File(path)
        if (!file.exists()) {
            throw IllegalArgumentException("File not found: $path")
        }
        if (!file.isFile) {
            throw IllegalArgumentException("Not a file: $path")
        }

        // Try to get from VFS for fresh content
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(path)
        if (virtualFile != null) {
            return ApplicationManager.getApplication().runReadAction<String> {
                val document = FileDocumentManager.getInstance().getDocument(virtualFile)
                document?.text ?: virtualFile.contentsToByteArray().toString(Charsets.UTF_8)
            }
        }

        // Fallback to direct file read
        return file.readText()
    }

    override fun writeTextFile(path: String, content: String): Boolean {
        log.debug("Writing file: $path (${content.length} chars)")

        return try {
            val result = AtomicBoolean(false)

            ApplicationManager.getApplication().invokeAndWait {
                WriteAction.run<Exception> {
                    CommandProcessor.getInstance().executeCommand(project, {
                        try {
                            val file = File(path)
                            val parentDir = file.parentFile

                            // Create parent directories if needed
                            if (parentDir != null && !parentDir.exists()) {
                                parentDir.mkdirs()
                                LocalFileSystem.getInstance().refreshAndFindFileByPath(parentDir.absolutePath)
                            }

                            // Write file
                            file.writeText(content)

                            // Refresh VFS
                            val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
                            if (virtualFile != null) {
                                virtualFile.refresh(false, false)
                                // Also refresh document if open in editor
                                FileDocumentManager.getInstance().reloadFiles(virtualFile)
                            }

                            result.set(true)
                            log.info("File written successfully: $path")
                        } catch (e: Exception) {
                            log.error("Failed to write file: $path", e)
                        }
                    }, "ACP Write File", null)
                }
            }

            result.get()
        } catch (e: Exception) {
            log.error("Error writing file: $path", e)
            false
        }
    }

    override fun listDirectory(path: String): List<DirectoryEntry> {
        log.debug("Listing directory: $path")

        val dir = File(path)
        if (!dir.exists()) {
            throw IllegalArgumentException("Directory not found: $path")
        }
        if (!dir.isDirectory) {
            throw IllegalArgumentException("Not a directory: $path")
        }

        return dir.listFiles()?.map { file ->
            DirectoryEntry(
                name = file.name,
                type = if (file.isDirectory) "directory" else "file",
                size = if (file.isFile) file.length() else null
            )
        }?.sortedWith(compareBy({ it.type != "directory" }, { it.name }))
            ?: emptyList()
    }

    /**
     * Create a file with content (convenience method)
     */
    fun createFile(path: String, content: String): Boolean {
        return writeTextFile(path, content)
    }

    /**
     * Delete a file
     */
    fun deleteFile(path: String): Boolean {
        log.debug("Deleting file: $path")

        return try {
            val result = AtomicBoolean(false)

            ApplicationManager.getApplication().invokeAndWait {
                WriteAction.run<Exception> {
                    val virtualFile = LocalFileSystem.getInstance().findFileByPath(path)
                    if (virtualFile != null) {
                        virtualFile.delete(this)
                        result.set(true)
                    } else {
                        val file = File(path)
                        result.set(file.delete())
                    }
                }
            }

            result.get()
        } catch (e: Exception) {
            log.error("Error deleting file: $path", e)
            false
        }
    }

    /**
     * Check if path exists
     */
    fun exists(path: String): Boolean {
        return File(path).exists()
    }

    /**
     * Check if path is a file
     */
    fun isFile(path: String): Boolean {
        return File(path).isFile
    }

    /**
     * Check if path is a directory
     */
    fun isDirectory(path: String): Boolean {
        return File(path).isDirectory
    }
}

/**
 * ACP Terminal Handler Implementation
 *
 * Manages terminal/shell command execution for ACP agents.
 * Creates process-based terminals with output streaming.
 */
class ACPTerminalHandlerImpl(private val project: Project) : ACPTerminalHandler {

    private val log = Logger.getInstance(ACPTerminalHandlerImpl::class.java)
    private val terminals = ConcurrentHashMap<String, TerminalProcess>()

    // Callback for sending terminal output to ACP
    var onOutput: ((terminalId: String, output: String, isStderr: Boolean) -> Unit)? = null
    var onExit: ((terminalId: String, exitCode: Int) -> Unit)? = null

    override fun createTerminal(command: String, cwd: String?, env: Map<String, String>?): String {
        val terminalId = "term-${UUID.randomUUID().toString().take(8)}"
        log.info("Creating terminal $terminalId: $command")

        try {
            val workingDir = cwd?.let { File(it) } ?: project.basePath?.let { File(it) }

            // Build process
            val processBuilder = ProcessBuilder()

            // Parse command (simple split for now, could use shell parsing)
            if (System.getProperty("os.name").lowercase().contains("windows")) {
                processBuilder.command("cmd", "/c", command)
            } else {
                processBuilder.command("sh", "-c", command)
            }

            // Set working directory
            if (workingDir?.exists() == true) {
                processBuilder.directory(workingDir)
            }

            // Set environment
            env?.forEach { (key, value) ->
                processBuilder.environment()[key] = value
            }

            // Redirect stderr to stdout for unified output
            processBuilder.redirectErrorStream(false)

            val process = processBuilder.start()
            val terminalProcess = TerminalProcess(terminalId, process)
            terminals[terminalId] = terminalProcess

            // Start output reading threads
            startOutputReader(terminalId, process)

            return terminalId
        } catch (e: Exception) {
            log.error("Failed to create terminal: $command", e)
            throw e
        }
    }

    override fun killTerminal(terminalId: String) {
        log.info("Killing terminal: $terminalId")

        terminals.remove(terminalId)?.let { terminal ->
            try {
                terminal.process.destroy()
                if (terminal.process.isAlive) {
                    terminal.process.destroyForcibly()
                }
            } catch (e: Exception) {
                log.warn("Error killing terminal $terminalId", e)
            }
        }
    }

    private fun startOutputReader(terminalId: String, process: Process) {
        // Read stdout
        Thread({
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    onOutput?.invoke(terminalId, line + "\n", false)
                }
            } catch (e: Exception) {
                if (terminals.containsKey(terminalId)) {
                    log.debug("Stdout reader ended for $terminalId", e)
                }
            }
        }, "ACP-Terminal-$terminalId-stdout").apply {
            isDaemon = true
            start()
        }

        // Read stderr
        Thread({
            try {
                process.errorStream.bufferedReader().forEachLine { line ->
                    onOutput?.invoke(terminalId, line + "\n", true)
                }
            } catch (e: Exception) {
                if (terminals.containsKey(terminalId)) {
                    log.debug("Stderr reader ended for $terminalId", e)
                }
            }
        }, "ACP-Terminal-$terminalId-stderr").apply {
            isDaemon = true
            start()
        }

        // Wait for process exit
        Thread({
            try {
                val exitCode = process.waitFor()
                log.info("Terminal $terminalId exited with code $exitCode")
                onExit?.invoke(terminalId, exitCode)
                terminals.remove(terminalId)
            } catch (e: Exception) {
                log.debug("Exit waiter ended for $terminalId", e)
            }
        }, "ACP-Terminal-$terminalId-exit").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Check if terminal is running
     */
    fun isRunning(terminalId: String): Boolean {
        return terminals[terminalId]?.process?.isAlive == true
    }

    /**
     * Get all active terminal IDs
     */
    fun getActiveTerminals(): Set<String> {
        return terminals.keys.toSet()
    }

    /**
     * Kill all terminals
     */
    fun killAll() {
        terminals.keys.toList().forEach { killTerminal(it) }
    }

    private data class TerminalProcess(
        val id: String,
        val process: Process
    )
}

/**
 * ACP Permission Handler Implementation
 *
 * Handles HITL (Human-in-the-Loop) permission requests from ACP agents.
 * Shows dialogs for user approval of file operations and commands.
 */
class ACPPermissionHandlerImpl(private val project: Project) {

    private val log = Logger.getInstance(ACPPermissionHandlerImpl::class.java)

    // Permission callbacks
    var onShowWriteDialog: ((PermissionRequestParams) -> PermissionResponse)? = null
    var onShowEditDialog: ((PermissionRequestParams) -> PermissionResponse)? = null
    var onShowShellDialog: ((PermissionRequestParams) -> PermissionResponse)? = null

    /**
     * Handle permission request
     * Called by ACPClientService when agent requests permission
     */
    fun handlePermissionRequest(params: PermissionRequestParams): PermissionResponse {
        val settings = com.hdsp.pycharm_agent.settings.AgentSettings.getInstance()

        // Check auto-approve settings
        val autoApprove = when (params.permission) {
            "read_file" -> settings.autoApproveRead
            "write_file" -> settings.autoApproveWrite
            "edit_file" -> settings.autoApproveWrite
            "execute_command" -> settings.autoApproveShell
            else -> false
        }

        if (autoApprove) {
            log.info("Auto-approving ${params.permission} for ${params.path ?: params.command}")
            return PermissionResponse(granted = true)
        }

        // Show appropriate dialog based on permission type
        return when (params.permission) {
            "write_file" -> {
                onShowWriteDialog?.invoke(params) ?: PermissionResponse(granted = false)
            }
            "edit_file" -> {
                onShowEditDialog?.invoke(params) ?: PermissionResponse(granted = false)
            }
            "execute_command" -> {
                onShowShellDialog?.invoke(params) ?: PermissionResponse(granted = false)
            }
            else -> {
                log.warn("Unknown permission type: ${params.permission}")
                PermissionResponse(granted = false, feedback = "Unknown permission type")
            }
        }
    }
}
