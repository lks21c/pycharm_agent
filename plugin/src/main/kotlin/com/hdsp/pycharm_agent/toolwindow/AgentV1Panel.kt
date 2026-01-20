package com.hdsp.pycharm_agent.toolwindow

import com.hdsp.pycharm_agent.services.*
import com.hdsp.pycharm_agent.settings.AgentSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.io.File
import javax.swing.*
import javax.swing.border.TitledBorder

/**
 * Agent V1 Panel - Traditional Plan-Execute Pattern
 *
 * Flow:
 * 1. User enters request
 * 2. Generate plan via /agent/plan
 * 3. Display plan steps with progress
 * 4. Execute steps one by one or all at once
 * 5. Handle errors with refine/replan options
 */
class AgentV1Panel(private val project: Project) : JPanel(BorderLayout()) {

    // UI Components
    private val statusBanner = ConnectionStatusBanner()
    private val requestArea = JBTextArea(3, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = "Describe what you want to do... (Ctrl+Enter to generate plan)"
    }
    private val generateButton = JButton("Generate Plan")
    private val clearButton = JButton("Clear")

    // Plan display components
    private val planOverviewPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(5)
    }
    private val goalLabel = JLabel(" ")
    private val progressLabel = JLabel(" ")
    private val stepsListPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }

    // Step detail components
    private val stepDetailPanel = JPanel(BorderLayout()).apply {
        border = BorderFactory.createTitledBorder("Step Detail")
    }
    private val stepToolLabel = JLabel(" ")
    private val codePreviewArea = JBTextArea(8, 40).apply {
        isEditable = false
        font = Font("JetBrains Mono", Font.PLAIN, 12).let { f ->
            if (f.family == "JetBrains Mono") f else Font(Font.MONOSPACED, Font.PLAIN, 12)
        }
        background = JBColor(Color(40, 44, 52), Color(30, 30, 30))
        foreground = JBColor(Color(200, 200, 200), Color(200, 200, 200))
    }

    // Action buttons
    private val executeStepButton = JButton("Execute Step").apply { isEnabled = false }
    private val executeAllButton = JButton("Execute All").apply { isEnabled = false }
    private val skipButton = JButton("Skip").apply { isEnabled = false }

    // Error recovery panel
    private val errorRecoveryPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
        border = BorderFactory.createTitledBorder("Error Recovery")
        isVisible = false
    }
    private val refineButton = JButton("Refine").apply { isEnabled = false }
    private val replanButton = JButton("Replan").apply { isEnabled = false }
    private val errorMessageLabel = JLabel(" ")

    // Status
    private val statusLabel = JLabel(" ").apply {
        font = font.deriveFont(Font.ITALIC, 11f)
        foreground = JBColor.gray
    }

    // State
    private var currentPlan: HdspExecutionPlan? = null
    private var currentStepIndex = 0
    private var isServerConnected = false
    private var isExecuting = false
    private var lastError: ErrorInfo? = null
    private var executionHistory = mutableListOf<Map<String, Any>>()
    private var previousAttempts = 0
    private var previousCodes = mutableListOf<String>()

    init {
        setupUI()
        setupEventHandlers()
        checkServerConnection()
    }

    private fun setupUI() {
        // Top: Status banner + Request area
        val topPanel = JPanel(BorderLayout())
        topPanel.add(statusBanner, BorderLayout.NORTH)

        val requestPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Request")

            add(JBScrollPane(requestArea).apply {
                preferredSize = Dimension(400, 80)
            }, BorderLayout.CENTER)

            val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
                add(generateButton)
                add(clearButton)
            }
            add(buttonPanel, BorderLayout.SOUTH)
        }
        topPanel.add(requestPanel, BorderLayout.CENTER)
        add(topPanel, BorderLayout.NORTH)

        // Center: Plan Overview + Step Detail
        val centerPanel = JPanel(BorderLayout())

        // Plan overview
        val planPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Plan Overview")

            val headerPanel = JPanel(GridLayout(2, 1)).apply {
                add(goalLabel)
                add(progressLabel)
            }
            add(headerPanel, BorderLayout.NORTH)
            add(JBScrollPane(stepsListPanel).apply {
                preferredSize = Dimension(400, 150)
            }, BorderLayout.CENTER)
        }
        centerPanel.add(planPanel, BorderLayout.NORTH)

        // Step detail
        stepDetailPanel.add(stepToolLabel, BorderLayout.NORTH)
        stepDetailPanel.add(JBScrollPane(codePreviewArea).apply {
            preferredSize = Dimension(400, 150)
        }, BorderLayout.CENTER)

        val actionButtonPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(executeStepButton)
            add(executeAllButton)
            add(skipButton)
        }
        stepDetailPanel.add(actionButtonPanel, BorderLayout.SOUTH)
        centerPanel.add(stepDetailPanel, BorderLayout.CENTER)

        add(centerPanel, BorderLayout.CENTER)

        // Bottom: Error recovery + Status
        val bottomPanel = JPanel(BorderLayout())

        errorRecoveryPanel.add(errorMessageLabel)
        errorRecoveryPanel.add(refineButton)
        errorRecoveryPanel.add(replanButton)
        bottomPanel.add(errorRecoveryPanel, BorderLayout.CENTER)

        val statusBar = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(3, 5)
            background = JBColor(Color(245, 245, 245), Color(45, 45, 45))
            add(statusLabel, BorderLayout.WEST)
        }
        bottomPanel.add(statusBar, BorderLayout.SOUTH)

        add(bottomPanel, BorderLayout.SOUTH)
    }

    private fun setupEventHandlers() {
        generateButton.addActionListener { generatePlan() }
        clearButton.addActionListener { clearAll() }

        executeStepButton.addActionListener { executeCurrentStep() }
        executeAllButton.addActionListener { executeAllSteps() }
        skipButton.addActionListener { skipCurrentStep() }

        refineButton.addActionListener { refineCurrentStep() }
        replanButton.addActionListener { replanFromError() }

        requestArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && e.isControlDown) {
                    generatePlan()
                    e.consume()
                }
            }
        })
    }

    private fun checkServerConnection() {
        statusBanner.updateStatus(
            ConnectionStatusBanner.Status.CHECKING,
            "Checking server connection...",
            retry = { checkServerConnection() }
        )
        setInputEnabled(false)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = project.getService(BackendClient::class.java)
                val isConnected = client.testConnectionSync()

                SwingUtilities.invokeLater {
                    isServerConnected = isConnected
                    if (isConnected) {
                        statusBanner.updateStatus(ConnectionStatusBanner.Status.CONNECTED)
                        setInputEnabled(true)
                    } else {
                        val settings = AgentSettings.getInstance()
                        statusBanner.updateStatus(
                            ConnectionStatusBanner.Status.DISCONNECTED,
                            "Cannot connect to ${settings.backendUrl}",
                            retry = { checkServerConnection() }
                        )
                    }
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    isServerConnected = false
                    statusBanner.updateStatus(
                        ConnectionStatusBanner.Status.DISCONNECTED,
                        "Connection error: ${e.message}",
                        retry = { checkServerConnection() }
                    )
                }
            }
        }
    }

    private fun setInputEnabled(enabled: Boolean) {
        requestArea.isEnabled = enabled
        generateButton.isEnabled = enabled && !isExecuting
    }

    private fun generatePlan() {
        val request = requestArea.text.trim()
        if (request.isEmpty()) return

        if (!isServerConnected) {
            checkServerConnection()
            return
        }

        generateButton.isEnabled = false
        statusLabel.text = "Generating plan..."
        clearPlanDisplay()

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = project.getService(BackendClient::class.java)
                val response = client.generatePlanSync(request)

                SwingUtilities.invokeLater {
                    currentPlan = response.plan
                    currentStepIndex = 0
                    executionHistory.clear()
                    previousAttempts = 0
                    previousCodes.clear()
                    lastError = null
                    errorRecoveryPanel.isVisible = false

                    displayPlan(response.plan)
                    statusLabel.text = "Plan generated: ${response.plan.totalSteps} steps"
                    generateButton.isEnabled = true
                    updateActionButtons()
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    statusLabel.text = "Error: ${e.message}"
                    generateButton.isEnabled = true
                }
            }
        }
    }

    private fun displayPlan(plan: HdspExecutionPlan) {
        goalLabel.text = "<html><b>Goal:</b> ${plan.goal}</html>"
        progressLabel.text = "Progress: Step ${currentStepIndex + 1}/${plan.totalSteps}"

        stepsListPanel.removeAll()
        for ((index, step) in plan.steps.withIndex()) {
            val stepPanel = createStepListItem(index, step)
            stepsListPanel.add(stepPanel)
        }
        stepsListPanel.add(Box.createVerticalGlue())
        stepsListPanel.revalidate()
        stepsListPanel.repaint()

        // Display first step detail
        if (plan.steps.isNotEmpty()) {
            displayStepDetail(plan.steps[0])
        }
    }

    private fun createStepListItem(index: Int, step: HdspPlanStep): JPanel {
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(3, 5)
            maximumSize = Dimension(Int.MAX_VALUE, 30)

            val statusIcon = when {
                index < currentStepIndex -> "✅"
                index == currentStepIndex -> if (isExecuting) "🔄" else "➡️"
                else -> "⏳"
            }

            val label = JLabel("[$statusIcon] Step ${step.stepNumber}: ${step.description}").apply {
                foreground = when {
                    index < currentStepIndex -> JBColor(Color(0, 150, 0), Color(100, 200, 100))
                    index == currentStepIndex -> JBColor.blue
                    else -> JBColor.gray
                }
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            }
            add(label, BorderLayout.CENTER)

            // Click to select step
            label.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    if (!isExecuting && index >= currentStepIndex) {
                        currentStepIndex = index
                        refreshPlanDisplay()
                        displayStepDetail(step)
                    }
                }
            })
        }
    }

    private fun displayStepDetail(step: HdspPlanStep) {
        stepToolLabel.text = "<html><b>Step ${step.stepNumber}:</b> ${step.description}</html>"

        val codePreview = StringBuilder()
        for (toolCall in step.toolCalls) {
            codePreview.append("Tool: ${toolCall.tool}\n")
            codePreview.append("Parameters:\n")
            for ((key, value) in toolCall.parameters) {
                val valueStr = when (value) {
                    is String -> if (value.length > 200) value.take(200) + "..." else value
                    else -> value.toString()
                }
                codePreview.append("  $key: $valueStr\n")
            }
            codePreview.append("\n")
        }

        if (step.expectedOutput != null) {
            codePreview.append("Expected Output: ${step.expectedOutput}\n")
        }

        codePreviewArea.text = codePreview.toString()
        codePreviewArea.caretPosition = 0
    }

    private fun updateActionButtons() {
        val plan = currentPlan
        val hasMoreSteps = plan != null && currentStepIndex < plan.totalSteps

        executeStepButton.isEnabled = hasMoreSteps && !isExecuting
        executeAllButton.isEnabled = hasMoreSteps && !isExecuting
        skipButton.isEnabled = hasMoreSteps && !isExecuting
    }

    private fun refreshPlanDisplay() {
        val plan = currentPlan ?: return
        progressLabel.text = "Progress: Step ${currentStepIndex + 1}/${plan.totalSteps}"

        stepsListPanel.removeAll()
        for ((index, step) in plan.steps.withIndex()) {
            val stepPanel = createStepListItem(index, step)
            stepsListPanel.add(stepPanel)
        }
        stepsListPanel.add(Box.createVerticalGlue())
        stepsListPanel.revalidate()
        stepsListPanel.repaint()
    }

    private fun executeCurrentStep() {
        val plan = currentPlan ?: return
        if (currentStepIndex >= plan.totalSteps) return

        val step = plan.steps[currentStepIndex]
        executeStep(step)
    }

    private fun executeStep(step: HdspPlanStep) {
        isExecuting = true
        updateActionButtons()
        statusLabel.text = "Executing step ${step.stepNumber}..."
        errorRecoveryPanel.isVisible = false

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                var success = true
                var errorMessage: String? = null

                for (toolCall in step.toolCalls) {
                    val result = executeToolCall(toolCall)
                    if (!result.first) {
                        success = false
                        errorMessage = result.second
                        break
                    }
                }

                SwingUtilities.invokeLater {
                    if (success) {
                        // Record success in history
                        executionHistory.add(mapOf(
                            "stepIndex" to currentStepIndex,
                            "status" to "completed"
                        ))

                        currentStepIndex++
                        previousAttempts = 0
                        previousCodes.clear()
                        lastError = null

                        val plan = currentPlan
                        if (plan != null && currentStepIndex >= plan.totalSteps) {
                            statusLabel.text = "✅ All steps completed!"
                            executeStepButton.isEnabled = false
                            executeAllButton.isEnabled = false
                            skipButton.isEnabled = false
                        } else {
                            statusLabel.text = "✅ Step completed"
                            if (plan != null && currentStepIndex < plan.totalSteps) {
                                displayStepDetail(plan.steps[currentStepIndex])
                            }
                        }
                        refreshPlanDisplay()
                    } else {
                        // Handle error
                        lastError = ErrorInfo(
                            type = "runtime",
                            message = errorMessage ?: "Unknown error"
                        )
                        previousAttempts++
                        showErrorRecovery(errorMessage ?: "Unknown error")
                        statusLabel.text = "❌ Step failed"
                    }
                    isExecuting = false
                    updateActionButtons()
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    lastError = ErrorInfo(type = "runtime", message = e.message ?: "Unknown error")
                    previousAttempts++
                    showErrorRecovery(e.message ?: "Unknown error")
                    statusLabel.text = "❌ Error: ${e.message}"
                    isExecuting = false
                    updateActionButtons()
                }
            }
        }
    }

    private fun executeToolCall(toolCall: ToolCall): Pair<Boolean, String?> {
        val settings = AgentSettings.getInstance()
        val workspaceRoot = settings.workspaceRoot.ifBlank { project.basePath ?: "." }

        return when (toolCall.tool) {
            "write_file" -> {
                val path = toolCall.parameters["path"] as? String
                    ?: return Pair(false, "Missing 'path' parameter")
                val content = toolCall.parameters["content"] as? String
                    ?: return Pair(false, "Missing 'content' parameter")

                try {
                    val fullPath = File(workspaceRoot, path).absolutePath
                    val file = File(fullPath)
                    file.parentFile?.mkdirs()

                    ApplicationManager.getApplication().invokeAndWait {
                        WriteAction.run<Throwable> {
                            file.writeText(content)
                            LocalFileSystem.getInstance().refreshAndFindFileByPath(fullPath)
                        }
                    }
                    Pair(true, null)
                } catch (e: Exception) {
                    Pair(false, "Write failed: ${e.message}")
                }
            }

            "edit_file" -> {
                val path = toolCall.parameters["path"] as? String
                    ?: return Pair(false, "Missing 'path' parameter")
                val oldString = toolCall.parameters["old_string"] as? String
                    ?: return Pair(false, "Missing 'old_string' parameter")
                val newString = toolCall.parameters["new_string"] as? String
                    ?: return Pair(false, "Missing 'new_string' parameter")
                val replaceAll = toolCall.parameters["replace_all"] as? Boolean ?: false

                try {
                    val fullPath = File(workspaceRoot, path).absolutePath
                    val file = File(fullPath)

                    if (!file.exists()) {
                        return Pair(false, "File not found: $path")
                    }

                    val originalText = file.readText()
                    val newText = if (replaceAll) {
                        originalText.replace(oldString, newString)
                    } else {
                        originalText.replaceFirst(oldString, newString)
                    }

                    if (originalText == newText) {
                        return Pair(false, "old_string not found in file")
                    }

                    ApplicationManager.getApplication().invokeAndWait {
                        WriteAction.run<Throwable> {
                            file.writeText(newText)
                            LocalFileSystem.getInstance().refreshAndFindFileByPath(fullPath)
                        }
                    }
                    Pair(true, null)
                } catch (e: Exception) {
                    Pair(false, "Edit failed: ${e.message}")
                }
            }

            "read_file" -> {
                val path = toolCall.parameters["path"] as? String
                    ?: return Pair(false, "Missing 'path' parameter")

                try {
                    val fullPath = File(workspaceRoot, path).absolutePath
                    val file = File(fullPath)

                    if (!file.exists()) {
                        return Pair(false, "File not found: $path")
                    }

                    // Just verify the file is readable
                    file.readText()
                    Pair(true, null)
                } catch (e: Exception) {
                    Pair(false, "Read failed: ${e.message}")
                }
            }

            "shell", "run_command" -> {
                val command = toolCall.parameters["command"] as? String
                    ?: return Pair(false, "Missing 'command' parameter")

                try {
                    val process = ProcessBuilder("/bin/sh", "-c", command)
                        .directory(File(workspaceRoot))
                        .redirectErrorStream(true)
                        .start()

                    val output = process.inputStream.bufferedReader().readText()
                    val exitCode = process.waitFor()

                    if (exitCode != 0) {
                        return Pair(false, "Command failed (exit $exitCode): $output")
                    }
                    Pair(true, null)
                } catch (e: Exception) {
                    Pair(false, "Shell execution failed: ${e.message}")
                }
            }

            else -> {
                // Unknown tool - assume success for now
                Pair(true, null)
            }
        }
    }

    private fun executeAllSteps() {
        val plan = currentPlan ?: return
        if (currentStepIndex >= plan.totalSteps) return

        isExecuting = true
        updateActionButtons()
        statusLabel.text = "Executing all remaining steps..."

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                while (currentStepIndex < plan.totalSteps) {
                    val step = plan.steps[currentStepIndex]

                    SwingUtilities.invokeLater {
                        statusLabel.text = "Executing step ${step.stepNumber}/${plan.totalSteps}..."
                        refreshPlanDisplay()
                        displayStepDetail(step)
                    }

                    var success = true
                    var errorMessage: String? = null

                    for (toolCall in step.toolCalls) {
                        val result = executeToolCall(toolCall)
                        if (!result.first) {
                            success = false
                            errorMessage = result.second
                            break
                        }
                    }

                    if (!success) {
                        SwingUtilities.invokeLater {
                            lastError = ErrorInfo(type = "runtime", message = errorMessage ?: "Unknown error")
                            previousAttempts++
                            showErrorRecovery(errorMessage ?: "Unknown error")
                            statusLabel.text = "❌ Step ${step.stepNumber} failed"
                            isExecuting = false
                            updateActionButtons()
                        }
                        return@executeOnPooledThread
                    }

                    executionHistory.add(mapOf(
                        "stepIndex" to currentStepIndex,
                        "status" to "completed"
                    ))
                    currentStepIndex++
                }

                SwingUtilities.invokeLater {
                    statusLabel.text = "✅ All steps completed!"
                    refreshPlanDisplay()
                    isExecuting = false
                    updateActionButtons()
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    lastError = ErrorInfo(type = "runtime", message = e.message ?: "Unknown error")
                    showErrorRecovery(e.message ?: "Unknown error")
                    statusLabel.text = "❌ Error: ${e.message}"
                    isExecuting = false
                    updateActionButtons()
                }
            }
        }
    }

    private fun skipCurrentStep() {
        val plan = currentPlan ?: return
        if (currentStepIndex >= plan.totalSteps) return

        executionHistory.add(mapOf(
            "stepIndex" to currentStepIndex,
            "status" to "skipped"
        ))

        currentStepIndex++
        previousAttempts = 0
        previousCodes.clear()
        lastError = null
        errorRecoveryPanel.isVisible = false

        if (currentStepIndex >= plan.totalSteps) {
            statusLabel.text = "All steps processed (some skipped)"
        } else {
            displayStepDetail(plan.steps[currentStepIndex])
            statusLabel.text = "Step skipped"
        }
        refreshPlanDisplay()
        updateActionButtons()
    }

    private fun showErrorRecovery(errorMessage: String) {
        errorRecoveryPanel.isVisible = true
        errorMessageLabel.text = "<html><font color='red'>Error: ${errorMessage.take(100)}</font></html>"
        refineButton.isEnabled = true
        replanButton.isEnabled = true
    }

    private fun refineCurrentStep() {
        val plan = currentPlan ?: return
        val error = lastError ?: return
        if (currentStepIndex >= plan.totalSteps) return

        val step = plan.steps[currentStepIndex]

        refineButton.isEnabled = false
        replanButton.isEnabled = false
        statusLabel.text = "Refining step..."

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = project.getService(BackendClient::class.java)
                val response = client.refineSync(
                    step = step,
                    error = error,
                    attempt = previousAttempts,
                    previousCode = previousCodes.lastOrNull()
                )

                SwingUtilities.invokeLater {
                    // Update the step with refined tool calls
                    val refinedStep = HdspPlanStep(
                        stepNumber = step.stepNumber,
                        description = step.description,
                        toolCalls = response.toolCalls,
                        expectedOutput = step.expectedOutput
                    )

                    // Update plan
                    val updatedSteps = plan.steps.toMutableList()
                    updatedSteps[currentStepIndex] = refinedStep
                    currentPlan = HdspExecutionPlan(
                        goal = plan.goal,
                        totalSteps = plan.totalSteps,
                        steps = updatedSteps
                    )

                    displayStepDetail(refinedStep)
                    statusLabel.text = "Step refined - try executing again"
                    errorRecoveryPanel.isVisible = false
                    updateActionButtons()
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    statusLabel.text = "Refine failed: ${e.message}"
                    refineButton.isEnabled = true
                    replanButton.isEnabled = true
                }
            }
        }
    }

    private fun replanFromError() {
        val plan = currentPlan ?: return
        val error = lastError ?: return

        refineButton.isEnabled = false
        replanButton.isEnabled = false
        statusLabel.text = "Replanning..."

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = project.getService(BackendClient::class.java)
                val response = client.replanSync(
                    originalPlan = plan,
                    currentStepIndex = currentStepIndex,
                    error = error,
                    executionHistory = executionHistory,
                    previousAttempts = previousAttempts,
                    previousCodes = previousCodes
                )

                SwingUtilities.invokeLater {
                    when (response.decision) {
                        "abort" -> {
                            statusLabel.text = "⚠️ Replan decision: Abort"
                            errorRecoveryPanel.isVisible = false
                        }
                        "refine" -> {
                            // Trigger refine instead
                            statusLabel.text = "Replan suggests refining..."
                            refineCurrentStep()
                        }
                        else -> {
                            // For insert_steps, replace_step, replan_remaining
                            // We need to regenerate the plan
                            statusLabel.text = "Replan: ${response.decision} - regenerating plan..."

                            // For now, just regenerate the entire plan
                            errorRecoveryPanel.isVisible = false
                            generatePlan()
                        }
                    }
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    statusLabel.text = "Replan failed: ${e.message}"
                    refineButton.isEnabled = true
                    replanButton.isEnabled = true
                }
            }
        }
    }

    private fun clearAll() {
        requestArea.text = ""
        currentPlan = null
        currentStepIndex = 0
        executionHistory.clear()
        previousAttempts = 0
        previousCodes.clear()
        lastError = null

        clearPlanDisplay()
        statusLabel.text = " "
    }

    private fun clearPlanDisplay() {
        goalLabel.text = " "
        progressLabel.text = " "
        stepsListPanel.removeAll()
        stepsListPanel.revalidate()
        stepsListPanel.repaint()

        stepToolLabel.text = " "
        codePreviewArea.text = ""

        executeStepButton.isEnabled = false
        executeAllButton.isEnabled = false
        skipButton.isEnabled = false

        errorRecoveryPanel.isVisible = false
        refineButton.isEnabled = false
        replanButton.isEnabled = false
    }
}
