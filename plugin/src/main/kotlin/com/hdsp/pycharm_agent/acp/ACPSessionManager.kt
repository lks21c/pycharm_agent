package com.hdsp.pycharm_agent.acp

import com.hdsp.pycharm_agent.settings.AgentSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * ACP Session Manager
 *
 * Manages ACP session lifecycle including:
 * - Session creation, tracking, and cleanup
 * - Session persistence for recovery
 * - Session history management
 * - Idle session timeout
 * - Session context (files, workspace)
 */
@Service(Service.Level.PROJECT)
class ACPSessionManager(private val project: Project) : Disposable {

    private val log = Logger.getInstance(ACPSessionManager::class.java)
    private val settings = AgentSettings.getInstance()

    // Active sessions
    private val sessions = ConcurrentHashMap<String, ManagedSession>()

    // Current active session
    @Volatile
    private var activeSessionId: String? = null

    // Session cleanup scheduler
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ACP-SessionManager").apply { isDaemon = true }
    }
    private var cleanupTask: ScheduledFuture<*>? = null

    // Session listeners
    private val sessionListeners = mutableListOf<SessionListener>()

    // Session history (persisted)
    private val sessionHistory = mutableListOf<SessionHistoryEntry>()
    private val maxHistorySize = 50

    init {
        Disposer.register(project, this)
        loadSessionHistory()
        startCleanupScheduler()
    }

    companion object {
        fun getInstance(project: Project): ACPSessionManager {
            return project.getService(ACPSessionManager::class.java)
        }

        // Session timeout (30 minutes of inactivity)
        internal const val SESSION_TIMEOUT_MS = 30 * 60 * 1000L

        // Cleanup interval (5 minutes)
        private const val CLEANUP_INTERVAL_MS = 5 * 60 * 1000L
    }

    // ═══════════════════════════════════════════════════════════════
    // Session Lifecycle
    // ═══════════════════════════════════════════════════════════════

    /**
     * Create a new session
     *
     * @param mode Session mode ("chat" or "agent")
     * @param title Optional session title
     * @return Created session or null on failure
     */
    fun createSession(mode: String, title: String? = null): ManagedSession? {
        val acpClient = ACPClientService.getInstance(project)
        if (!acpClient.isRunning()) {
            log.warn("Cannot create session: ACP client not running")
            return null
        }

        val sessionInfo = acpClient.createSession(mode, project.basePath)
        if (sessionInfo == null) {
            log.error("Failed to create ACP session")
            return null
        }

        val session = ManagedSession(
            id = sessionInfo.sessionId,
            mode = mode,
            title = title ?: generateSessionTitle(mode),
            createdAt = Instant.now(),
            lastActivityAt = Instant.now(),
            workspaceRoot = project.basePath,
            context = SessionContext()
        )

        sessions[session.id] = session
        activeSessionId = session.id

        notifySessionCreated(session)
        log.info("Created session: ${session.id} (mode=$mode)")

        return session
    }

    /**
     * Get or create active session
     */
    fun getOrCreateActiveSession(mode: String): ManagedSession? {
        // Return active session if valid
        activeSessionId?.let { id ->
            sessions[id]?.let { session ->
                if (session.mode == mode && !session.isExpired()) {
                    session.lastActivityAt = Instant.now()
                    return session
                }
            }
        }

        // Create new session
        return createSession(mode)
    }

    /**
     * Get session by ID
     */
    fun getSession(sessionId: String): ManagedSession? {
        return sessions[sessionId]?.also {
            it.lastActivityAt = Instant.now()
        }
    }

    /**
     * Get active session
     */
    fun getActiveSession(): ManagedSession? {
        return activeSessionId?.let { sessions[it] }
    }

    /**
     * Set active session
     */
    fun setActiveSession(sessionId: String): Boolean {
        val session = sessions[sessionId] ?: return false
        activeSessionId = sessionId
        session.lastActivityAt = Instant.now()
        notifySessionActivated(session)
        return true
    }

    /**
     * End a session
     */
    fun endSession(sessionId: String) {
        val session = sessions.remove(sessionId) ?: return

        // Add to history
        addToHistory(session)

        // Clear active if this was active
        if (activeSessionId == sessionId) {
            activeSessionId = null
        }

        notifySessionEnded(session)
        log.info("Ended session: $sessionId")
    }

    /**
     * End all sessions
     */
    fun endAllSessions() {
        val sessionIds = sessions.keys.toList()
        sessionIds.forEach { endSession(it) }
    }

    /**
     * Get all active sessions
     */
    fun getActiveSessions(): List<ManagedSession> {
        return sessions.values.toList()
    }

    // ═══════════════════════════════════════════════════════════════
    // Session Context
    // ═══════════════════════════════════════════════════════════════

    /**
     * Add file to session context
     */
    fun addFileToContext(sessionId: String, filePath: String) {
        sessions[sessionId]?.let { session ->
            session.context.files.add(filePath)
            session.lastActivityAt = Instant.now()
        }
    }

    /**
     * Remove file from session context
     */
    fun removeFileFromContext(sessionId: String, filePath: String) {
        sessions[sessionId]?.context?.files?.remove(filePath)
    }

    /**
     * Get session context files
     */
    fun getContextFiles(sessionId: String): Set<String> {
        return sessions[sessionId]?.context?.files?.toSet() ?: emptySet()
    }

    /**
     * Add message to session history
     */
    fun addMessage(sessionId: String, role: String, content: String) {
        sessions[sessionId]?.let { session ->
            session.messages.add(SessionMessage(role, content, Instant.now()))
            session.lastActivityAt = Instant.now()
            session.messageCount++
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Session History
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get session history
     */
    fun getSessionHistory(): List<SessionHistoryEntry> {
        return sessionHistory.toList()
    }

    /**
     * Clear session history
     */
    fun clearSessionHistory() {
        sessionHistory.clear()
        saveSessionHistory()
    }

    private fun addToHistory(session: ManagedSession) {
        val entry = SessionHistoryEntry(
            id = session.id,
            mode = session.mode,
            title = session.title,
            createdAt = session.createdAt,
            endedAt = Instant.now(),
            messageCount = session.messageCount
        )

        sessionHistory.add(0, entry) // Add to front

        // Trim history
        while (sessionHistory.size > maxHistorySize) {
            sessionHistory.removeAt(sessionHistory.size - 1)
        }

        saveSessionHistory()
    }

    private fun loadSessionHistory() {
        if (!settings.persistSessions) return

        try {
            val historyFile = getHistoryFile()
            if (historyFile.exists()) {
                // Simple line-based format: id|mode|title|createdAt|endedAt|messageCount
                historyFile.readLines().forEach { line ->
                    val parts = line.split("|")
                    if (parts.size >= 6) {
                        sessionHistory.add(SessionHistoryEntry(
                            id = parts[0],
                            mode = parts[1],
                            title = parts[2],
                            createdAt = Instant.parse(parts[3]),
                            endedAt = Instant.parse(parts[4]),
                            messageCount = parts[5].toIntOrNull() ?: 0
                        ))
                    }
                }
                log.info("Loaded ${sessionHistory.size} session history entries")
            }
        } catch (e: Exception) {
            log.warn("Failed to load session history", e)
        }
    }

    private fun saveSessionHistory() {
        if (!settings.persistSessions) return

        try {
            val historyFile = getHistoryFile()
            historyFile.parentFile?.mkdirs()
            historyFile.writeText(sessionHistory.joinToString("\n") { entry ->
                "${entry.id}|${entry.mode}|${entry.title}|${entry.createdAt}|${entry.endedAt}|${entry.messageCount}"
            })
        } catch (e: Exception) {
            log.warn("Failed to save session history", e)
        }
    }

    private fun getHistoryFile(): File {
        val configDir = File(System.getProperty("user.home"), ".pycharm_agent")
        return File(configDir, "session_history.txt")
    }

    // ═══════════════════════════════════════════════════════════════
    // Session Cleanup
    // ═══════════════════════════════════════════════════════════════

    private fun startCleanupScheduler() {
        cleanupTask = scheduler.scheduleAtFixedRate(
            { cleanupExpiredSessions() },
            CLEANUP_INTERVAL_MS,
            CLEANUP_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
    }

    private fun cleanupExpiredSessions() {
        val now = Instant.now()
        val expiredIds = sessions.entries
            .filter { it.value.isExpired(now) }
            .map { it.key }

        expiredIds.forEach { id ->
            log.info("Cleaning up expired session: $id")
            endSession(id)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Listeners
    // ═══════════════════════════════════════════════════════════════

    fun addSessionListener(listener: SessionListener) {
        sessionListeners.add(listener)
    }

    fun removeSessionListener(listener: SessionListener) {
        sessionListeners.remove(listener)
    }

    private fun notifySessionCreated(session: ManagedSession) {
        sessionListeners.forEach { it.onSessionCreated(session) }
    }

    private fun notifySessionActivated(session: ManagedSession) {
        sessionListeners.forEach { it.onSessionActivated(session) }
    }

    private fun notifySessionEnded(session: ManagedSession) {
        sessionListeners.forEach { it.onSessionEnded(session) }
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════

    private fun generateSessionTitle(mode: String): String {
        val timestamp = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("MMM d, HH:mm"))
        return when (mode) {
            "chat" -> "Chat - $timestamp"
            "agent" -> "Agent - $timestamp"
            else -> "$mode - $timestamp"
        }
    }

    override fun dispose() {
        cleanupTask?.cancel(false)
        scheduler.shutdown()
        endAllSessions()
    }
}

// ═══════════════════════════════════════════════════════════════
// Data Classes
// ═══════════════════════════════════════════════════════════════

/**
 * Managed session with full lifecycle tracking
 */
data class ManagedSession(
    val id: String,
    val mode: String,
    val title: String,
    val createdAt: Instant,
    var lastActivityAt: Instant,
    val workspaceRoot: String?,
    val context: SessionContext,
    val messages: MutableList<SessionMessage> = mutableListOf(),
    var messageCount: Int = 0
) {
    fun isExpired(now: Instant = Instant.now()): Boolean {
        val elapsed = now.toEpochMilli() - lastActivityAt.toEpochMilli()
        return elapsed > ACPSessionManager.SESSION_TIMEOUT_MS
    }

    fun getAge(): Long {
        return Instant.now().toEpochMilli() - createdAt.toEpochMilli()
    }

    fun getIdleTime(): Long {
        return Instant.now().toEpochMilli() - lastActivityAt.toEpochMilli()
    }
}

/**
 * Session context (files, selections, etc.)
 */
data class SessionContext(
    val files: MutableSet<String> = mutableSetOf(),
    var selectedText: String? = null,
    var cursorPosition: CursorPosition? = null,
    val metadata: MutableMap<String, Any> = mutableMapOf()
)

/**
 * Cursor position in editor
 */
data class CursorPosition(
    val file: String,
    val line: Int,
    val column: Int
)

/**
 * Session message
 */
data class SessionMessage(
    val role: String,
    val content: String,
    val timestamp: Instant
)

/**
 * Session history entry (for persistence)
 */
data class SessionHistoryEntry(
    val id: String,
    val mode: String,
    val title: String,
    val createdAt: Instant,
    val endedAt: Instant,
    val messageCount: Int
)

/**
 * Session lifecycle listener
 */
interface SessionListener {
    fun onSessionCreated(session: ManagedSession) {}
    fun onSessionActivated(session: ManagedSession) {}
    fun onSessionEnded(session: ManagedSession) {}
}
