package com.k80.geminiagent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 核心无障碍服务 (AgentAccessibilityService)
 * 串联模块一 (ScreenPerceptionEngine)、模块二 (GeminiBrainClient)、模块三 (ActionExecutor) 与模块四 (FloatingConsoleOverlay)，
 * 并提供硬件【音量减键 (KEYCODE_VOLUME_DOWN)】紧急熔断拦截 (Kill Switch)。
 */
class AgentAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var soundManager: P5SoundAndHapticManager
    private lateinit var perceptionEngine: ScreenPerceptionEngine
    private lateinit var brainClient: GeminiBrainClient
    private lateinit var actionExecutor: ActionExecutor
    private var floatingOverlay: FloatingConsoleOverlay? = null

    private var activeMissionJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceConnectedFlow.value = true

        soundManager = P5SoundAndHapticManager(applicationContext)
        perceptionEngine = ScreenPerceptionEngine(this)
        brainClient = GeminiBrainClient()

        val overlay = FloatingConsoleOverlay(
            context = this,
            soundManager = soundManager,
            onStartMission = { goal -> startAutonomousMission(goal) },
            onAbortMission = { reason -> abortCurrentMission(reason) }
        )
        floatingOverlay = overlay
        overlay.attachOverlays()

        actionExecutor = ActionExecutor(
            service = this,
            soundManager = soundManager,
            onTriggerClickVfx = { x, y, label ->
                floatingOverlay?.triggerClickBurstVfx(x, y, label)
            },
            onTriggerSwipeVfx = { sx, sy, ex, ey ->
                floatingOverlay?.triggerSwipeSlashVfx(sx, sy, ex, ey)
            }
        )

        soundManager.playAllOutComplete()
        floatingOverlay?.appendTacticalLog("【Oracle】Redmi K80 Ultra 无障碍神经链路已连接！随时准备潜入认知空间！")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 感知与节点提取在每回合决策前按需实时主动触发，避免高频事件消耗 CPU
    }

    override fun onInterrupt() {
        abortCurrentMission("系统无障碍服务被中断 (onInterrupt)")
    }

    /**
     * 硬件级熔断机制 (Kill Switch)：
     * 当自动化任务正在运行且检测到按下【音量减键 (KEYCODE_VOLUME_DOWN)】时，
     * 立即播放 P5 警报音效并取消当前协程任务 (job.cancel())，弹出提示“MISSION ABORTED：已通过音量键紧急终止”。
     */
    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event != null &&
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN &&
            event.action == KeyEvent.ACTION_DOWN
        ) {
            val runningJob = activeMissionJob
            if (runningJob != null && runningJob.isActive) {
                abortCurrentMission("MISSION ABORTED：已通过音量键紧急终止")
                Toast.makeText(
                    applicationContext,
                    "MISSION ABORTED：已通过音量键紧急终止",
                    Toast.LENGTH_SHORT
                ).show()
                return true // 消费此次音量减键事件，防止误调音量
            }
        }
        return super.onKeyEvent(event)
    }

    /**
     * 启动全自动多模态视觉推理与无障碍控制循环
     */
    fun startAutonomousMission(userGoal: String) {
        if (userGoal.isBlank()) return

        // 若已有任务在运行，先安全终止旧任务
        activeMissionJob?.cancel()

        val prefs = applicationContext.getSharedPreferences(
            P5SoundAndHapticManager.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        val apiKey = prefs.getString(KEY_API_KEY, "").orEmpty().trim()
        val modelName = prefs.getString(KEY_MODEL_NAME, GeminiBrainClient.DEFAULT_MODEL)
            .orEmpty().trim().ifEmpty { GeminiBrainClient.DEFAULT_MODEL }
        val maxSteps = prefs.getInt(KEY_MAX_STEPS, 25).coerceIn(1, 100)
        val stepCooldownMs = prefs.getLong(KEY_STEP_DELAY_MS, 1500L).coerceIn(400L, 10000L)

        if (apiKey.isEmpty()) {
            soundManager.playCancelOrAlert()
            floatingOverlay?.appendTacticalLog("【Mona 警告】未检测到 Gemini API Key！请先在怪盗基地主页填写并保存 API Key。")
            Toast.makeText(applicationContext, "请先在主页配置 Gemini API Key", Toast.LENGTH_LONG).show()
            return
        }

        brainClient.clearHistory()
        floatingOverlay?.updateRunningState(true, "ANALYZING SHADOW... (0/$maxSteps)")
        floatingOverlay?.appendTacticalLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        floatingOverlay?.appendTacticalLog("【收到预告信委托】$userGoal")

        activeMissionJob = serviceScope.launch {
            try {
                for (step in 1..maxSteps) {
                    if (!isActive) break

                    floatingOverlay?.updateRunningState(
                        true,
                        "STEP $step/$maxSteps // CAPTURING SCREEN..."
                    )

                    // 1. 截屏并提取 Set-of-Mark (SoM) 标注位图 (自动隐藏悬浮窗 120ms 防遮挡 + Android 16 冷却重试)
                    val snapshotResult = perceptionEngine.captureAndAnnotateScreen(
                        onToggleOverlayVisibility = { visible ->
                            floatingOverlay?.setOverlaysVisibleForScreenshot(visible)
                        }
                    )

                    if (snapshotResult.isFailure) {
                        val errMsg = snapshotResult.exceptionOrNull()?.message ?: "未知截屏错误"
                        soundManager.playCancelOrAlert()
                        floatingOverlay?.appendTacticalLog("【Step $step 视觉感知异常】$errMsg")
                        delay(1000L)
                        continue
                    }

                    val snapshot = snapshotResult.getOrThrow()
                    soundManager.playTargetLockOn()

                    floatingOverlay?.updateRunningState(
                        true,
                        "STEP $step/$maxSteps // ORACLE THINKING..."
                    )

                    // 2. 将 720x1559 Base64 标注图像与 UI 节点清单发送给 Gemini 多模态大脑
                    val decisionResult = brainClient.analyzeScreenAndDecide(
                        apiKey = apiKey,
                        modelName = modelName,
                        userGoal = userGoal,
                        currentStep = step,
                        maxSteps = maxSteps,
                        snapshot = snapshot
                    )

                    if (decisionResult.isFailure) {
                        val apiErr = decisionResult.exceptionOrNull()?.message ?: "API 通信异常"
                        soundManager.playCancelOrAlert()
                        floatingOverlay?.appendTacticalLog("【Step $step 大脑推理报错】$apiErr")
                        floatingOverlay?.updateRunningState(false, "ERROR // 通信中断")
                        return@launch
                    }

                    val decision = decisionResult.getOrThrow()
                    floatingOverlay?.appendTacticalLog("【Step $step Oracle】${decision.thought}")

                    // 3. 执行无障碍动作并记录滑动窗口历史
                    floatingOverlay?.updateRunningState(
                        true,
                        "STEP $step/$maxSteps // ACTION: ${decision.action}"
                    )

                    val actionSummary = actionExecutor.executeDecision(
                        decision = decision,
                        snapshot = snapshot,
                        brainClient = brainClient
                    )
                    brainClient.recordStepHistory(step, decision, actionSummary)
                    floatingOverlay?.appendTacticalLog(" ↳ [执行反馈] $actionSummary")

                    val upperAction = decision.action.trim().uppercase()
                    if (upperAction == "COMPLETE") {
                        floatingOverlay?.updateRunningState(false, "SHOWTIME // 任务圆满完成！")
                        Toast.makeText(
                            applicationContext,
                            "SHOWTIME! 怪盗团任务圆满完成！",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@launch
                    }

                    if (upperAction == "FAIL") {
                        floatingOverlay?.updateRunningState(false, "MISSION FAILED // 潜入受阻")
                        return@launch
                    }

                    // 4. 每回合冷却延迟，等待目标 App 页面动画稳定
                    delay(stepCooldownMs)
                }

                // 达到最大步数限制
                soundManager.playCancelOrAlert()
                floatingOverlay?.appendTacticalLog("【Mona】已达到最大潜入回合数 ($maxSteps 步)，行动自动停止。")
                floatingOverlay?.updateRunningState(false, "MAX STEPS // 回合耗尽")
            } catch (_: CancellationException) {
                // 任务被用户通过按钮或音量减键主动熔断
            } catch (t: Throwable) {
                soundManager.playCancelOrAlert()
                floatingOverlay?.appendTacticalLog("【系统异常终止】${t.message}")
                floatingOverlay?.updateRunningState(false, "ABORTED // 异常中断")
            }
        }
    }

    /**
     * 立即中断当前自动化任务并播放 P5 警报音效
     */
    fun abortCurrentMission(reason: String) {
        val hadActiveJob = activeMissionJob?.isActive == true
        activeMissionJob?.cancel()
        activeMissionJob = null
        if (this::soundManager.isInitialized) {
            soundManager.playCancelOrAlert()
        }
        floatingOverlay?.updateRunningState(false, "MISSION ABORTED // 已紧急停止")
        if (hadActiveJob || reason.isNotBlank()) {
            floatingOverlay?.appendTacticalLog("【紧急熔断】$reason")
        }
    }

    override fun onDestroy() {
        instance = null
        _isServiceConnectedFlow.value = false
        activeMissionJob?.cancel()
        floatingOverlay?.detachOverlays()
        floatingOverlay = null
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val KEY_API_KEY = "gemini_api_key"
        const val KEY_MODEL_NAME = "gemini_model_name"
        const val KEY_MAX_STEPS = "agent_max_steps"
        const val KEY_STEP_DELAY_MS = "agent_step_delay_ms"

        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

        private val _isServiceConnectedFlow = MutableStateFlow(false)
        val isServiceConnectedFlow: StateFlow<Boolean> = _isServiceConnectedFlow.asStateFlow()
    }
}
