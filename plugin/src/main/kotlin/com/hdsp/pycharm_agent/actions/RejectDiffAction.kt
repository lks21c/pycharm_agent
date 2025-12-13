package com.hdsp.pycharm_agent.actions

import com.hdsp.pycharm_agent.services.DiffApplicationService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

/**
 * Action to reject the proposed diff change
 */
class RejectDiffAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        val diffService = project.getService(DiffApplicationService::class.java)
        val filePath = virtualFile.path

        if (diffService.hasPendingDiff(filePath)) {
            diffService.rejectDiff(filePath, editor)
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)

        e.presentation.isEnabled = if (project != null && virtualFile != null) {
            val diffService = project.getService(DiffApplicationService::class.java)
            diffService.hasPendingDiff(virtualFile.path)
        } else {
            false
        }
    }
}
