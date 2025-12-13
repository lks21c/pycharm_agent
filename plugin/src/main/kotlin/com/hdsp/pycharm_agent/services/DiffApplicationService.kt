package com.hdsp.pycharm_agent.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.ui.JBColor
import java.awt.Color
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Service for applying diffs to files with preview support
 */
@Service(Service.Level.PROJECT)
class DiffApplicationService(private val project: Project) {

    private val pendingDiffs = mutableMapOf<String, DiffResult>()
    private val highlighters = mutableMapOf<String, MutableList<RangeHighlighter>>()

    /**
     * Apply a diff directly (returns true if successful)
     */
    fun applyDiff(diff: DiffResult): Boolean {
        if (diff.filePath.isEmpty() || diff.previewContent.isEmpty()) {
            return false
        }

        val virtualFile = findFile(diff.filePath)
        if (virtualFile == null) {
            // Try to create the file if it doesn't exist
            return createFileWithContent(diff.filePath, diff.previewContent)
        }

        val result = AtomicBoolean(false)

        ApplicationManager.getApplication().invokeAndWait {
            val document = FileDocumentManager.getInstance().getDocument(virtualFile)
            if (document != null) {
                CommandProcessor.getInstance().executeCommand(project, {
                    WriteAction.run<Throwable> {
                        document.setText(diff.previewContent)
                        PsiDocumentManager.getInstance(project).commitDocument(document)
                        result.set(true)
                    }
                }, "PyCharm Agent: Apply Changes", "PyCharmAgent")
            }
        }

        return result.get()
    }

    /**
     * Stage a diff for preview (doesn't apply yet)
     */
    fun stageDiff(diff: DiffResult) {
        pendingDiffs[diff.filePath] = diff
    }

    /**
     * Show diff preview in editor with inline highlighting
     */
    fun showPreview(editor: Editor, diff: DiffResult) {
        clearHighlights(diff.filePath, editor)

        val document = editor.document
        val markupModel = editor.markupModel
        val newHighlighters = mutableListOf<RangeHighlighter>()

        for (hunk in diff.hunks) {
            val startLine = (hunk.startLine - 1).coerceAtLeast(0)
            val endLine = (hunk.endLine - 1).coerceAtLeast(startLine)

            if (startLine >= document.lineCount) continue

            val startOffset = document.getLineStartOffset(startLine.coerceAtMost(document.lineCount - 1))
            val endOffset = if (endLine < document.lineCount) {
                document.getLineEndOffset(endLine)
            } else {
                document.textLength
            }

            val attributes = when (hunk.changeType) {
                "add" -> TextAttributes().apply {
                    backgroundColor = JBColor(Color(144, 238, 144, 80), Color(0, 100, 0, 80))
                }
                "delete" -> TextAttributes().apply {
                    backgroundColor = JBColor(Color(255, 182, 193, 80), Color(139, 0, 0, 80))
                }
                "modify" -> TextAttributes().apply {
                    backgroundColor = JBColor(Color(255, 255, 150, 80), Color(139, 139, 0, 80))
                }
                else -> TextAttributes()
            }

            val highlighter = markupModel.addRangeHighlighter(
                startOffset.coerceAtMost(document.textLength),
                endOffset.coerceAtMost(document.textLength),
                HighlighterLayer.SELECTION - 1,
                attributes,
                HighlighterTargetArea.LINES_IN_RANGE
            )

            newHighlighters.add(highlighter)
        }

        highlighters[diff.filePath] = newHighlighters
    }

    /**
     * Show diff preview by opening file in editor
     */
    fun showDiffPreviewInEditor(diff: DiffResult) {
        val virtualFile = findFile(diff.filePath) ?: return

        ApplicationManager.getApplication().invokeLater {
            val editor = FileEditorManager.getInstance(project).openTextEditor(
                com.intellij.openapi.fileEditor.OpenFileDescriptor(project, virtualFile),
                true
            )
            if (editor != null) {
                stageDiff(diff)
                showPreview(editor, diff)
            }
        }
    }

    /**
     * Accept and apply the staged diff
     */
    fun acceptDiff(filePath: String): Boolean {
        val diff = pendingDiffs[filePath] ?: return false
        val result = applyDiff(diff)
        if (result) {
            pendingDiffs.remove(filePath)
        }
        return result
    }

    /**
     * Reject staged diff
     */
    fun rejectDiff(filePath: String, editor: Editor? = null) {
        pendingDiffs.remove(filePath)
        if (editor != null) {
            clearHighlights(filePath, editor)
        }
    }

    /**
     * Check if there's a pending diff for a file
     */
    fun hasPendingDiff(filePath: String): Boolean {
        return pendingDiffs.containsKey(filePath)
    }

    /**
     * Get pending diff for a file
     */
    fun getPendingDiff(filePath: String): DiffResult? {
        return pendingDiffs[filePath]
    }

    /**
     * Clear highlights for a file
     */
    private fun clearHighlights(filePath: String, editor: Editor) {
        highlighters[filePath]?.forEach { highlighter ->
            editor.markupModel.removeHighlighter(highlighter)
        }
        highlighters.remove(filePath)
    }

    /**
     * Find virtual file by path
     */
    private fun findFile(filePath: String): VirtualFile? {
        val projectPath = project.basePath ?: return null
        val fullPath = if (filePath.startsWith("/")) {
            filePath
        } else {
            "$projectPath/$filePath"
        }
        return LocalFileSystem.getInstance().findFileByPath(fullPath)
    }

    /**
     * Create a new file with content
     */
    private fun createFileWithContent(filePath: String, content: String): Boolean {
        val projectPath = project.basePath ?: return false
        val fullPath = if (filePath.startsWith("/")) filePath else "$projectPath/$filePath"

        return try {
            val file = java.io.File(fullPath)
            file.parentFile?.mkdirs()
            file.writeText(content)
            LocalFileSystem.getInstance().refreshAndFindFileByPath(fullPath)
            true
        } catch (e: Exception) {
            false
        }
    }
}
