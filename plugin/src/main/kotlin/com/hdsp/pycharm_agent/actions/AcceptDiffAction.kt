package com.hdsp.pycharm_agent.actions

import com.hdsp.pycharm_agent.services.DiffApplicationService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

/**
 * Action to accept the proposed diff change
 */
class AcceptDiffAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        e.getData(CommonDataKeys.EDITOR) ?: return  // Ensure editor context exists
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        val diffService = project.getService(DiffApplicationService::class.java)
        val filePath = virtualFile.path

        if (diffService.hasPendingDiff(filePath)) {
            diffService.acceptDiff(filePath)
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
