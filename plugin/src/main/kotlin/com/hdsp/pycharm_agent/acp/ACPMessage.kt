package com.hdsp.pycharm_agent.acp

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName

/**
 * ACP (Agent Client Protocol) Message Types
 *
 * Based on JSON-RPC 2.0 specification for communication between
 * PyCharm plugin (client) and ACP-compatible agent (server subprocess).
 *
 * Reference: https://agentclientprotocol.com/protocol/overview
 */

// ═══════════════════════════════════════════════════════════════
// JSON-RPC 2.0 Base Types
// ═══════════════════════════════════════════════════════════════

/**
 * JSON-RPC 2.0 Request
 * Sent from client to server, expects a response
 */
data class ACPRequest(
    val jsonrpc: String = "2.0",
    val id: Any,  // String or Int
    val method: String,
    val params: JsonObject? = null
)

/**
 * JSON-RPC 2.0 Response
 * Sent from server to client in response to a request
 */
data class ACPResponse(
    val jsonrpc: String = "2.0",
    val id: Any?,  // Matches request id, null for parse errors
    val result: JsonElement? = null,
    val error: ACPError? = null
) {
    val isSuccess: Boolean get() = error == null
    val isError: Boolean get() = error != null
}

/**
 * JSON-RPC 2.0 Notification
 * Sent without expecting a response (no id field)
 */
data class ACPNotification(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: JsonObject? = null
)

/**
 * JSON-RPC 2.0 Error Object
 */
data class ACPError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
) {
    companion object {
        // Standard JSON-RPC 2.0 error codes
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603

        // ACP-specific error codes (implementation-defined: -32000 to -32099)
        const val SESSION_NOT_FOUND = -32001
        const val PERMISSION_DENIED = -32002
        const val AGENT_BUSY = -32003
        const val OPERATION_CANCELLED = -32004
    }
}

// ═══════════════════════════════════════════════════════════════
// ACP Protocol Types - Initialize
// ═══════════════════════════════════════════════════════════════

/**
 * Client capabilities sent during initialization
 */
data class ClientCapabilities(
    val filesystem: FilesystemCapabilities? = null,
    val terminal: TerminalCapabilities? = null,
    val permissions: PermissionCapabilities? = null
)

data class FilesystemCapabilities(
    @SerializedName("readTextFile")
    val readTextFile: Boolean = true,
    @SerializedName("writeTextFile")
    val writeTextFile: Boolean = true,
    @SerializedName("listDirectory")
    val listDirectory: Boolean = true
)

data class TerminalCapabilities(
    val create: Boolean = true,
    val output: Boolean = true,
    val kill: Boolean = true
)

data class PermissionCapabilities(
    @SerializedName("requestPermission")
    val requestPermission: Boolean = true
)

/**
 * Client info for identification
 */
data class ClientInfo(
    val name: String = "PyCharm Agent",
    val version: String = "2.0.0"
)

/**
 * Initialize request parameters
 */
data class InitializeParams(
    val protocolVersion: String = "0.3.0",
    val clientCapabilities: ClientCapabilities = ClientCapabilities(
        filesystem = FilesystemCapabilities(),
        terminal = TerminalCapabilities(),
        permissions = PermissionCapabilities()
    ),
    val clientInfo: ClientInfo = ClientInfo()
)

/**
 * Agent capabilities returned during initialization
 */
data class AgentCapabilities(
    val streaming: Boolean = true,
    val tools: List<String> = emptyList(),
    val modes: List<String> = listOf("chat", "agent"),
    val plans: Boolean = true
)

/**
 * Agent info for identification
 */
data class AgentInfo(
    val name: String,
    val version: String
)

/**
 * Initialize response result
 */
data class InitializeResult(
    val protocolVersion: String,
    val agentCapabilities: AgentCapabilities,
    val agentInfo: AgentInfo
)

// ═══════════════════════════════════════════════════════════════
// ACP Protocol Types - Session
// ═══════════════════════════════════════════════════════════════

/**
 * Session creation parameters
 */
data class SessionNewParams(
    val workspaceRoot: String,
    val mode: String = "chat"  // "chat" | "agent"
)

/**
 * Session info returned after creation
 */
data class SessionInfo(
    val sessionId: String,
    val mode: String,
    val capabilities: SessionCapabilities? = null
)

data class SessionCapabilities(
    val streaming: Boolean = true,
    val tools: List<String> = emptyList()
)

/**
 * Prompt request parameters
 */
data class SessionPromptParams(
    val sessionId: String,
    val content: List<ContentBlock>
)

/**
 * Content block in messages
 */
data class ContentBlock(
    val type: String,  // "text" | "image"
    val text: String? = null,
    val data: String? = null,  // Base64 for images
    val mimeType: String? = null
) {
    companion object {
        fun text(text: String) = ContentBlock(type = "text", text = text)

        fun image(base64Data: String, mimeType: String = "image/png") = ContentBlock(
            type = "image",
            data = base64Data,
            mimeType = mimeType
        )
    }
}

/**
 * Cancel request parameters
 */
data class SessionCancelParams(
    val sessionId: String
)

/**
 * Mode change request parameters
 */
data class SessionSetModeParams(
    val sessionId: String,
    val mode: String
)

// ═══════════════════════════════════════════════════════════════
// ACP Protocol Types - Session Updates (Notifications)
// ═══════════════════════════════════════════════════════════════

/**
 * Session update notification parameters
 */
data class SessionUpdateParams(
    val sessionId: String,
    val updates: List<SessionUpdate>
)

/**
 * Sealed class for different types of session updates
 */
sealed class SessionUpdate {
    /**
     * Streaming text chunk from agent
     */
    data class AgentMessageChunk(
        val content: List<ContentBlock>
    ) : SessionUpdate()

    /**
     * Tool call status update
     */
    data class ToolCallUpdate(
        val tool: String,
        val status: String,  // "started" | "completed" | "failed"
        val result: String? = null,
        val error: String? = null
    ) : SessionUpdate()

    /**
     * Plan update (for agent mode)
     */
    data class PlanUpdate(
        val plan: AgentPlan
    ) : SessionUpdate()

    /**
     * Todo list update
     */
    data class TodoUpdate(
        val todos: List<TodoItem>
    ) : SessionUpdate()

    /**
     * Mode change notification
     */
    data class ModeChange(
        val mode: String
    ) : SessionUpdate()

    /**
     * Turn complete marker
     */
    object TurnComplete : SessionUpdate()

    /**
     * Error during processing
     */
    data class ErrorUpdate(
        val error: String,
        val recoverable: Boolean = false
    ) : SessionUpdate()
}

/**
 * Agent execution plan
 */
data class AgentPlan(
    val steps: List<String>,
    val currentStep: Int? = null,
    val totalSteps: Int? = null
)

/**
 * Todo item for progress tracking
 */
data class TodoItem(
    val content: String,
    val status: String  // "pending" | "in_progress" | "completed" | "failed"
)

// ═══════════════════════════════════════════════════════════════
// ACP Protocol Types - Permissions
// ═══════════════════════════════════════════════════════════════

/**
 * Permission request from agent to client
 */
data class PermissionRequestParams(
    val permission: String,  // "write_file" | "execute_command" | "read_file" | "edit_file"
    val path: String? = null,
    val command: String? = null,
    val description: String,
    val diff: DiffPreview? = null,
    val content: String? = null,  // For write_file, the content to write
    val edits: List<FileEdit>? = null  // For multi-edit operations
)

/**
 * Diff preview for file modifications
 */
data class DiffPreview(
    val hunks: List<DiffHunk>,
    val preview: String  // Full file content after changes
)

/**
 * Single diff hunk
 */
data class DiffHunk(
    val startLine: Int,
    val endLine: Int,
    val oldContent: String,
    val newContent: String,
    val changeType: String  // "add" | "delete" | "modify"
)

/**
 * File edit operation
 */
data class FileEdit(
    val oldString: String,
    val newString: String,
    val replaceAll: Boolean = false
)

/**
 * Permission response from client to agent
 */
data class PermissionResponse(
    val granted: Boolean,
    val modifiedArgs: JsonObject? = null,  // If user modified the operation
    val feedback: String? = null  // User feedback if denied
)

// ═══════════════════════════════════════════════════════════════
// ACP Protocol Types - Filesystem Operations (Client Methods)
// ═══════════════════════════════════════════════════════════════

/**
 * Read file request parameters (agent -> client)
 */
data class FsReadTextFileParams(
    val path: String
)

/**
 * Read file result (client -> agent)
 */
data class FsReadTextFileResult(
    val content: String
)

/**
 * Write file request parameters (agent -> client)
 */
data class FsWriteTextFileParams(
    val path: String,
    val content: String
)

/**
 * Write file result (client -> agent)
 */
data class FsWriteTextFileResult(
    val success: Boolean,
    val error: String? = null
)

/**
 * List directory request parameters (agent -> client)
 */
data class FsListDirectoryParams(
    val path: String
)

/**
 * Directory entry
 */
data class DirectoryEntry(
    val name: String,
    val type: String,  // "file" | "directory"
    val size: Long? = null
)

/**
 * List directory result (client -> agent)
 */
data class FsListDirectoryResult(
    val entries: List<DirectoryEntry>
)

// ═══════════════════════════════════════════════════════════════
// ACP Protocol Types - Terminal Operations (Client Methods)
// ═══════════════════════════════════════════════════════════════

/**
 * Create terminal request parameters (agent -> client)
 */
data class TerminalCreateParams(
    val command: String,
    val cwd: String? = null,
    val env: Map<String, String>? = null
)

/**
 * Create terminal result (client -> agent)
 */
data class TerminalCreateResult(
    val terminalId: String
)

/**
 * Terminal output notification (client -> agent)
 */
data class TerminalOutputParams(
    val terminalId: String,
    val output: String,
    val isStderr: Boolean = false
)

/**
 * Terminal exit notification (client -> agent)
 */
data class TerminalExitParams(
    val terminalId: String,
    val exitCode: Int
)

/**
 * Kill terminal request parameters (agent -> client)
 */
data class TerminalKillParams(
    val terminalId: String
)

// ═══════════════════════════════════════════════════════════════
// ACP Method Names
// ═══════════════════════════════════════════════════════════════

object ACPMethods {
    // Lifecycle
    const val INITIALIZE = "initialize"
    const val SHUTDOWN = "shutdown"

    // Session management (client -> agent)
    const val SESSION_NEW = "session/new"
    const val SESSION_PROMPT = "session/prompt"
    const val SESSION_CANCEL = "session/cancel"
    const val SESSION_SET_MODE = "session/setMode"
    const val SESSION_LOAD = "session/load"

    // Session notifications (agent -> client)
    const val SESSION_UPDATE = "session/update"

    // Permission (agent -> client)
    const val SESSION_REQUEST_PERMISSION = "session/requestPermission"

    // Filesystem (agent -> client)
    const val FS_READ_TEXT_FILE = "fs/readTextFile"
    const val FS_WRITE_TEXT_FILE = "fs/writeTextFile"
    const val FS_LIST_DIRECTORY = "fs/listDirectory"

    // Terminal (agent -> client)
    const val TERMINAL_CREATE = "terminal/create"
    const val TERMINAL_OUTPUT = "terminal/output"
    const val TERMINAL_EXIT = "terminal/exit"
    const val TERMINAL_KILL = "terminal/kill"
}
