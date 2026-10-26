package com.k80.geminiagent

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 全屏点击【总攻击 / 弱点暴击】视觉特效实例 (450ms)
 */
data class P5ClickBurstVfx(
    val id: Long,
    val x: Float,
    val y: Float,
    val label: String,
    val progress: Animatable<Float, *> = Animatable(0f)
)

/**
 * 全屏滑动【武士刀光拖尾】视觉特效实例 (420ms)
 */
data class P5SwipeSlashVfx(
    val id: Long,
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val progress: Animatable<Float, *> = Animatable(0f)
)

/**
 * 在 Service WindowManager 中承载 Jetpack Compose 所需的独立 LifecycleOwner
 */
private class OverlayComposeLifecycleOwner :
    LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    fun onCreate() {
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }

    fun attachToView(view: View) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
    }
}

/**
 * 模块四：P5 怪盗团通信器全局悬浮窗与全屏特效层 (FloatingConsoleOverlay)
 * 使用 TYPE_ACCESSIBILITY_OVERLAY 构建双层免额外权限悬浮架构：
 * - 层级 1：全屏穿透特效层 (绘制 450ms 十字准星暴击 + 四角星芒 + 武士刀光滑动轨迹)
 * - 层级 2：可拖拽贴边的 P5 怪盗五角星徽章 + 展开态锯齿漫画气泡控制台
 */
class FloatingConsoleOverlay(
    private val context: Context,
    private val soundManager: P5SoundAndHapticManager,
    private val onStartMission: (goalText: String) -> Unit,
    private val onAbortMission: (reason: String) -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val overlayScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var vfxLayerView: ComposeView? = null
    private var consoleLayerView: ComposeView? = null
    private var vfxLifecycleOwner: OverlayComposeLifecycleOwner? = null
    private var consoleLifecycleOwner: OverlayComposeLifecycleOwner? = null
    private var consoleLayoutParams: WindowManager.LayoutParams? = null

    private val _isExpandedFlow = MutableStateFlow(false)
    val isExpandedFlow: StateFlow<Boolean> = _isExpandedFlow.asStateFlow()

    private val _isAgentRunningFlow = MutableStateFlow(false)
    val isAgentRunningFlow: StateFlow<Boolean> = _isAgentRunningFlow.asStateFlow()

    private val _statusHeadlineFlow = MutableStateFlow("TAKE YOUR TIME // 待命潜入中")
    val statusHeadlineFlow: StateFlow<String> = _statusHeadlineFlow.asStateFlow()

    private val _thoughtLogsFlow = MutableStateFlow<List<String>>(
        listOf("【Mona】怪盗团异世界导航系统已就绪！点击上方预设芯片或输入潜入指令即可出击！")
    )
    val thoughtLogsFlow: StateFlow<List<String>> = _thoughtLogsFlow.asStateFlow()

    private val activeClickBursts = mutableStateListOf<P5ClickBurstVfx>()
    private val activeSwipeSlashes = mutableStateListOf<P5SwipeSlashVfx>()

    /**
     * 挂载两个 TYPE_ACCESSIBILITY_OVERLAY 悬浮层
     */
    fun attachOverlays() {
        if (vfxLayerView != null || consoleLayerView != null) return

        // 1. 创建层级 1：全屏穿透特效层 (FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE)
        val vfxParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        val vfxOwner = OverlayComposeLifecycleOwner().also { it.onCreate() }
        vfxLifecycleOwner = vfxOwner
        val vfxCompose = ComposeView(context).apply {
            vfxOwner.attachToView(this)
            setContent {
                P5PhantomTheme {
                    FullScreenVfxCanvasLayer(
                        clickBursts = activeClickBursts,
                        swipeSlashes = activeSwipeSlashes
                    )
                }
            }
        }
        vfxLayerView = vfxCompose

        // 2. 创建层级 2：P5 怪盗团悬浮通信器控制台
        val consoleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 320
        }
        consoleLayoutParams = consoleParams

        val consoleOwner = OverlayComposeLifecycleOwner().also { it.onCreate() }
        consoleLifecycleOwner = consoleOwner
        val consoleCompose = ComposeView(context).apply {
            consoleOwner.attachToView(this)
            setContent {
                P5PhantomTheme {
                    FloatingConsoleRootContent()
                }
            }
        }
        consoleLayerView = consoleCompose

        try {
            windowManager.addView(vfxCompose, vfxParams)
            windowManager.addView(consoleCompose, consoleParams)
        } catch (_: Exception) {
        }
    }

    /**
     * 销毁并移除所有悬浮层
     */
    fun detachOverlays() {
        try {
            vfxLayerView?.let { windowManager.removeViewImmediate(it) }
        } catch (_: Exception) {
        }
        try {
            consoleLayerView?.let { windowManager.removeViewImmediate(it) }
        } catch (_: Exception) {
        }
        vfxLifecycleOwner?.onDestroy()
        consoleLifecycleOwner?.onDestroy()
        vfxLayerView = null
        consoleLayerView = null
        vfxLifecycleOwner = null
        consoleLifecycleOwner = null
        overlayScope.cancel()
    }

    /**
     * 截屏防遮挡专用：在截屏前将两个图层均设为 View.GONE，截屏完成后恢复 View.VISIBLE
     */
    suspend fun setOverlaysVisibleForScreenshot(visible: Boolean) = withContext(Dispatchers.Main.immediate) {
        val targetVis = if (visible) View.VISIBLE else View.GONE
        vfxLayerView?.visibility = targetVis
        consoleLayerView?.visibility = targetVis
    }

    /**
     * 动态管理悬浮窗输入焦点：
     * 点击输入框时移除 FLAG_NOT_FOCUSABLE 唤起软键盘；发送或折叠时立即恢复 FLAG_NOT_FOCUSABLE 绝不阻塞底层触控。
     */
    private fun setWindowFocusable(focusable: Boolean) {
        val view = consoleLayerView ?: return
        val params = consoleLayoutParams ?: return
        val currentlyNotFocusable =
            (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0

        if (focusable && currentlyNotFocusable) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            try {
                windowManager.updateViewLayout(view, params)
            } catch (_: Exception) {
            }
        } else if (!focusable && !currentlyNotFocusable) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            try {
                windowManager.updateViewLayout(view, params)
            } catch (_: Exception) {
            }
        }
    }

    fun updateRunningState(running: Boolean, headline: String) {
        _isAgentRunningFlow.value = running
        _statusHeadlineFlow.value = headline
        if (running) {
            // 开始执行时自动收起大面板为紧凑思考气泡或徽章，避免挡住过多视野
            setWindowFocusable(false)
        }
    }

    fun appendTacticalLog(logLine: String) {
        val current = _thoughtLogsFlow.value.toMutableList()
        current.add(logLine)
        if (current.size > 40) {
            current.removeAt(0)
        }
        _thoughtLogsFlow.value = current
    }

    /**
     * 在目标真实像素坐标 (realX, realY) 触发 450ms P5【总攻击 / 弱点暴击】全屏特效
     */
    fun triggerClickBurstVfx(realX: Float, realY: Float, label: String = "HIT!") {
        overlayScope.launch {
            val vfx = P5ClickBurstVfx(
                id = System.nanoTime(),
                x = realX,
                y = realY,
                label = label
            )
            activeClickBursts.add(vfx)
            vfx.progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing)
            )
            activeClickBursts.remove(vfx)
        }
    }

    /**
     * 在起点到终点之间触发 420ms P5【武士刀光拖尾】全屏特效
     */
    fun triggerSwipeSlashVfx(startX: Float, startY: Float, endX: Float, endY: Float) {
        overlayScope.launch {
            val slash = P5SwipeSlashVfx(
                id = System.nanoTime(),
                startX = startX,
                startY = startY,
                endX = endX,
                endY = endY
            )
            activeSwipeSlashes.add(slash)
            slash.progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing)
            )
            activeSwipeSlashes.remove(slash)
        }
    }

    @Composable
    private fun FloatingConsoleRootContent() {
        val isExpanded by isExpandedFlow.collectAsState()
        val isRunning by isAgentRunningFlow.collectAsState()
        val headline by statusHeadlineFlow.collectAsState()
        val logs by thoughtLogsFlow.collectAsState()

        var inputTaskText by remember { mutableStateOf("") }
        val listState = rememberLazyListState()

        LaunchedEffect(logs.size) {
            if (logs.isNotEmpty()) {
                listState.animateScrollToItem(logs.lastIndex)
            }
        }

        val presetTasks = remember {
            listOf(
                "微信早安" to "打开微信，进入文件传输助手发送一句：早安 Joker！今日怪盗团行动开始！",
                "打开B站搜P5R" to "打开哔哩哔哩，点击搜索框输入 女神异闻录5皇家版 并点击搜索",
                "检查WLAN设置" to "打开系统设置，进入WLAN或网络设置页面查看当前连接状态",
                "清理返回桌面" to "连续返回并回到手机主屏幕桌面"
            )
        }

        Box(
            modifier = Modifier
                .pointerInput(isExpanded) {
                    if (!isExpanded) {
                        detectDragGestures(
                            onDragEnd = {
                                // 松手后自动贴靠最近左右屏幕边缘
                                snapBadgeToNearestEdge()
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                moveOverlayByDelta(dragAmount.x.roundToInt(), dragAmount.y.roundToInt())
                            }
                        )
                    }
                }
        ) {
            // 折叠态：贴边的黑红白斜切怪盗五角星徽章 + 思考态迷你漫画气泡
            AnimatedVisibility(
                visible = !isExpanded,
                enter = scaleIn(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ) + fadeIn(),
                exit = scaleOut() + fadeOut()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(4.dp)
                ) {
                    P5FloatingStarBadge(
                        isRunning = isRunning,
                        onClick = {
                            soundManager.playMenuChainSlash()
                            _isExpandedFlow.value = true
                        }
                    )

                    // 当正在执行任务且处于折叠态时，旁边展开一条紧凑锯齿漫画气泡实时播报战况 + 一键熔断按钮
                    if (isRunning) {
                        Spacer(modifier = Modifier.width(6.dp))
                        ThinkingComicBubbleMini(
                            headline = headline,
                            latestThought = logs.lastOrNull().orEmpty(),
                            onExpandClick = {
                                soundManager.playMenuChainSlash()
                                _isExpandedFlow.value = true
                            },
                            onAbortClick = {
                                onAbortMission("用户点击悬浮窗 ABORT MISSION 按钮紧急熔断")
                            }
                        )
                    }
                }
            }

            // 展开态：144Hz 弹簧刀光切入的 P5 怪盗团 IM 通信器锯齿漫画面板
            AnimatedVisibility(
                visible = isExpanded,
                enter = scaleIn(
                    initialScale = 0.65f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ) + fadeIn(),
                exit = scaleOut(targetScale = 0.75f) + fadeOut()
            ) {
                ExpandedPhantomCommunicatorPanel(
                    isRunning = isRunning,
                    headline = headline,
                    logs = logs,
                    listState = listState,
                    inputTaskText = inputTaskText,
                    onInputTaskChange = { inputTaskText = it },
                    presetTasks = presetTasks,
                    onSelectPreset = { goal ->
                        soundManager.playMenuChainSlash()
                        inputTaskText = goal
                    },
                    onFocusInput = { focused ->
                        setWindowFocusable(focused)
                    },
                    onSendExecute = {
                        val finalGoal = inputTaskText.trim()
                        if (finalGoal.isNotEmpty()) {
                            soundManager.playMenuChainSlash()
                            setWindowFocusable(false)
                            _isExpandedFlow.value = false
                            onStartMission(finalGoal)
                        } else {
                            soundManager.playCancelOrAlert()
                            appendTacticalLog("【Mona】请先输入委托任务指令或点击上方快捷预设芯片！")
                        }
                    },
                    onAbortClick = {
                        setWindowFocusable(false)
                        onAbortMission("用户点击控制台 ABORT MISSION 立即终止任务")
                    },
                    onCollapseClick = {
                        soundManager.playMenuChainSlash()
                        setWindowFocusable(false)
                        _isExpandedFlow.value = false
                    },
                    onHeaderDrag = { dx, dy ->
                        moveOverlayByDelta(dx, dy)
                    }
                )
            }
        }
    }

    private fun moveOverlayByDelta(dx: Int, dy: Int) {
        val view = consoleLayerView ?: return
        val params = consoleLayoutParams ?: return
        params.x = (params.x + dx).coerceAtLeast(0)
        params.y = (params.y + dy).coerceAtLeast(40)
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
        }
    }

    private fun snapBadgeToNearestEdge() {
        val view = consoleLayerView ?: return
        val params = consoleLayoutParams ?: return
        val screenWidth = try {
            windowManager.maximumWindowMetrics.bounds.width()
        } catch (_: Exception) {
            1280
        }
        val targetX = if (params.x + view.width / 2 < screenWidth / 2) {
            16
        } else {
            (screenWidth - view.width - 16).coerceAtLeast(16)
        }
        overlayScope.launch {
            val anim = Animatable(params.x.toFloat())
            anim.animateTo(
                targetValue = targetX.toFloat(),
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            ) {
                params.x = value.roundToInt()
                try {
                    windowManager.updateViewLayout(view, params)
                } catch (_: Exception) {
                }
            }
        }
    }
}

/**
 * 思考态紧凑漫画锯齿气泡 (Comic Speech Bubble Mini)
 * 伴随 "TAKE YOUR TIME..." / "ANALYZING SHADOW..." 倾斜跳动文字动画与黄黑警戒熔断按钮。
 */
@Composable
private fun ThinkingComicBubbleMini(
    headline: String,
    latestThought: String,
    onExpandClick: () -> Unit,
    onAbortClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "thinkingBounce")
    val textJumpY by infiniteTransition.animateFloat(
        initialValue = -2.5f,
        targetValue = 2.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 360, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "textJumpY"
    )

    Column(
        modifier = Modifier
            .width(275.dp)
            .drawBehind {
                val bubblePath = buildP5ComicSpeechBubblePath(
                    width = size.width,
                    height = size.height,
                    tailOnLeft = true
                )
                val shadowPath = Path().apply {
                    addPath(bubblePath)
                    transform(Matrix().apply { translate(6f, 6f) })
                }
                drawPath(path = shadowPath, color = P5Colors.PhantomCrimson)
                drawPath(path = bubblePath, color = P5Colors.ShadowBlack)
                drawPath(
                    path = bubblePath,
                    color = P5Colors.StarkWhite,
                    style = Stroke(width = 4f, join = StrokeJoin.Miter)
                )
            }
            .clickable(onClick = onExpandClick)
            .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 18.dp)
    ) {
        Text(
            text = headline,
            color = P5Colors.AllOutYellow,
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            fontStyle = FontStyle.Italic,
            modifier = Modifier.graphicsLayer {
                rotationZ = -3f
                translationY = textJumpY
            }
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = latestThought.take(68),
            color = P5Colors.StarkWhite,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2
        )
        Spacer(modifier = Modifier.height(6.dp))
        // 常驻黄黑警戒【ABORT MISSION】立即停止按钮
        AbortMissionHazardBar(onAbortClick = onAbortClick)
    }
}

/**
 * 展开态：P5 怪盗团 IM 通信器锯齿漫画对话框控制台
 */
@Composable
private fun ExpandedPhantomCommunicatorPanel(
    isRunning: Boolean,
    headline: String,
    logs: List<String>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    inputTaskText: String,
    onInputTaskChange: (String) -> Unit,
    presetTasks: List<Pair<String, String>>,
    onSelectPreset: (String) -> Unit,
    onFocusInput: (Boolean) -> Unit,
    onSendExecute: () -> Unit,
    onAbortClick: () -> Unit,
    onCollapseClick: () -> Unit,
    onHeaderDrag: (Int, Int) -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "panelThinking")
    val bannerSkewWave by infiniteTransition.animateFloat(
        initialValue = -3.5f,
        targetValue = -1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 520, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bannerSkewWave"
    )

    P5SkewedCard(
        modifier = Modifier
            .width(345.dp)
            .padding(6.dp),
        fillColor = P5Colors.ShadowBlack.copy(alpha = 0.96f),
        shadowColor = P5Colors.PhantomCrimson,
        borderColor = P5Colors.StarkWhite,
        contentPadding = 12.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 1. 顶部 IM 通信头像框与倾斜状态栏 (支持按住拖拽窗口)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            onHeaderDrag(dragAmount.x.roundToInt(), dragAmount.y.roundToInt())
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 怪盗导航员头像徽章框
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .drawBehind {
                                val avatarBox = buildP5PolygonPath(
                                    width = size.width,
                                    height = size.height,
                                    topLeftCutRatio = 0.1f,
                                    bottomRightCutRatio = 0.1f,
                                    topSlantPx = 4f,
                                    bottomSlantPx = 3f
                                )
                                drawPath(path = avatarBox, color = P5Colors.PhantomCrimson)
                                drawPath(
                                    path = avatarBox,
                                    color = P5Colors.StarkWhite,
                                    style = Stroke(width = 3f)
                                )
                                drawP5FourPointStar(
                                    center = Offset(size.width / 2f, size.height / 2f),
                                    outerRadius = size.minDimension * 0.36f,
                                    innerRadius = size.minDimension * 0.12f,
                                    color = P5Colors.AllOutYellow,
                                    rotationDeg = -12f
                                )
                            }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "ORACLE // NAVI COMM",
                            color = P5Colors.PhantomCrimson,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            fontStyle = FontStyle.Italic
                        )
                        Text(
                            text = headline,
                            color = if (isRunning) P5Colors.AllOutYellow else P5Colors.StarkWhite,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.graphicsLayer {
                                rotationZ = if (isRunning) bannerSkewWave else -2f
                            }
                        )
                    }
                }

                // 收起悬浮窗按钮
                Box(
                    modifier = Modifier
                        .clickable(onClick = onCollapseClick)
                        .drawBehind {
                            val p = buildP5PolygonPath(size.width, size.height, topSlantPx = 3f, bottomSlantPx = 2f)
                            drawPath(p, P5Colors.StarkWhite)
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "HIDE ✕",
                        color = P5Colors.ShadowBlack,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 2. 快捷预设任务芯片行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                presetTasks.forEach { (chipLabel, fullGoal) ->
                    Box(
                        modifier = Modifier
                            .clickable { onSelectPreset(fullGoal) }
                            .drawBehind {
                                val p = buildP5PolygonPath(
                                    width = size.width,
                                    height = size.height,
                                    topSlantPx = 3f,
                                    bottomSlantPx = 2f
                                )
                                drawPath(p, P5Colors.VelvetCharcoal)
                                drawPath(p, P5Colors.PhantomCrimson, style = Stroke(width = 2.5f))
                            }
                            .padding(horizontal = 9.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = "★ $chipLabel",
                            color = P5Colors.StarkWhite,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 3. 漫画气泡样式的实时思考日志滚动区
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 165.dp)
                    .drawBehind {
                        val logPath = buildP5ComicSpeechBubblePath(
                            width = size.width,
                            height = size.height,
                            tailOnLeft = false
                        )
                        drawPath(logPath, Color(0xFF121212))
                        drawPath(
                            logPath,
                            P5Colors.StarkWhite.copy(alpha = 0.85f),
                            style = Stroke(width = 2.5f)
                        )
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(logs) { line ->
                        Text(
                            text = line,
                            color = if (line.contains("COMPLETE") || line.contains("目标")) {
                                P5Colors.AllOutYellow
                            } else if (line.contains("ABORT") || line.contains("FAIL") || line.contains("错误")) {
                                P5Colors.PhantomCrimson
                            } else {
                                P5Colors.StarkWhite
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 15.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 4. 自然语言任务输入框 (动态切换焦点以弹出输入法)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        val inputPath = buildP5PolygonPath(
                            width = size.width,
                            height = size.height,
                            topSlantPx = 4f,
                            bottomSlantPx = 3f
                        )
                        drawPath(inputPath, P5Colors.VelvetCharcoal)
                        drawPath(inputPath, P5Colors.StarkWhite, style = Stroke(width = 3f))
                    }
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                BasicTextField(
                    value = inputTaskText,
                    onValueChange = onInputTaskChange,
                    textStyle = TextStyle(
                        color = P5Colors.StarkWhite,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    cursorBrush = SolidColor(P5Colors.PhantomCrimson),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focusState ->
                            onFocusInput(focusState.isFocused)
                        },
                    decorationBox = { innerTextField ->
                        if (inputTaskText.isEmpty()) {
                            Text(
                                text = "输入怪盗潜入指令 (例: 打开微信给置顶发早安)...",
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                        }
                        innerTextField()
                    }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 5. 【TAKE YOUR HEART (发送执行)】斜切按钮 & 常驻黄黑警戒【ABORT MISSION】按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                P5SkewedButton(
                    text = if (isRunning) "ANALYZING..." else "TAKE YOUR HEART (出击)",
                    onClick = onSendExecute,
                    modifier = Modifier.weight(1f),
                    backgroundColor = P5Colors.PhantomCrimson,
                    textColor = P5Colors.StarkWhite,
                    accentSeed = true
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            AbortMissionHazardBar(onAbortClick = onAbortClick)
        }
    }
}

/**
 * 常驻醒目的黄黑警戒条纹【ABORT MISSION (立即停止)】按钮
 */
@Composable
private fun AbortMissionHazardBar(
    onAbortClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onAbortClick)
            .drawBehind {
                val barPath = buildP5PolygonPath(
                    width = size.width,
                    height = size.height,
                    topSlantPx = 4f,
                    bottomSlantPx = 3f
                )
                drawPath(barPath, P5Colors.AllOutYellow)
                // 绘制斜向警戒黑条纹
                val stripeStep = 28f
                var xPos = -size.height
                while (xPos < size.width + size.height) {
                    val stripe = Path().apply {
                        moveTo(xPos, size.height)
                        lineTo(xPos + 12f, size.height)
                        lineTo(xPos + 26f, 0f)
                        lineTo(xPos + 14f, 0f)
                        close()
                    }
                    drawPath(stripe, P5Colors.ShadowBlack.copy(alpha = 0.22f))
                    xPos += stripeStep
                }
                drawPath(
                    barPath,
                    P5Colors.ShadowBlack,
                    style = Stroke(width = 3.5f)
                )
            }
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "⚠ ABORT MISSION // 立即紧急熔断 (或按音量-) ⚠",
            color = P5Colors.ShadowBlack,
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            fontStyle = FontStyle.Italic
        )
    }
}

/**
 * 全屏透明穿透特效层 Canvas：
 * 1. CLICK 450ms 特效：黑红双色十字准星极速锁定收缩 -> 锐利四角星芒 (4-Point Starburst) + 不规则锯齿扩散环 -> 飘出倾斜 "HIT!" / "ACTION!" 漫画字样
 * 2. SWIPE 420ms 特效：起点到终点绘制红白相间的凌厉武士刀光拖尾轨迹 (Slash Trail)
 */
@Composable
private fun FullScreenVfxCanvasLayer(
    clickBursts: List<P5ClickBurstVfx>,
    swipeSlashes: List<P5SwipeSlashVfx>
) {
    val labelPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 48f
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.SANS_SERIF,
                android.graphics.Typeface.BOLD_ITALIC
            )
            color = android.graphics.Color.WHITE
        }
    }
    val labelStrokePaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 48f
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.SANS_SERIF,
                android.graphics.Typeface.BOLD_ITALIC
            )
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 10f
            color = android.graphics.Color.BLACK
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        // 1. 绘制所有活动中的 CLICK 准星锁定与四角星芒爆裂
        for (burst in clickBursts) {
            val p = burst.progress.value
            val center = Offset(burst.x, burst.y)
            val alpha = (1f - p * 0.85f).coerceIn(0f, 1f)

            // 阶段 A (0.0 ~ 0.45)：黑红双色十字准星极速锁定收缩
            val crosshairRadius = (150f * (1f - (p / 0.45f).coerceIn(0f, 1f))) + 26f
            rotate(degrees = -22f + p * 75f, pivot = center) {
                drawCircle(
                    color = P5Colors.ShadowBlack.copy(alpha = alpha),
                    radius = crosshairRadius,
                    center = center,
                    style = Stroke(width = 10f)
                )
                drawCircle(
                    color = P5Colors.PhantomCrimson.copy(alpha = alpha),
                    radius = crosshairRadius,
                    center = center,
                    style = Stroke(width = 5f)
                )
                // 十字准星刻度线
                val lineHalf = crosshairRadius + 24f
                drawLine(
                    color = P5Colors.StarkWhite.copy(alpha = alpha),
                    start = Offset(center.x - lineHalf, center.y),
                    end = Offset(center.x + lineHalf, center.y),
                    strokeWidth = 5f
                )
                drawLine(
                    color = P5Colors.PhantomCrimson.copy(alpha = alpha),
                    start = Offset(center.x, center.y - lineHalf),
                    end = Offset(center.x, center.y + lineHalf),
                    strokeWidth = 5f
                )
            }

            // 阶段 B (0.2 ~ 1.0)：炸开锐利四角星芒 (4-Point Starburst) + 不规则锯齿扩散环
            if (p >= 0.15f) {
                val burstProgress = ((p - 0.15f) / 0.85f).coerceIn(0f, 1f)
                val starOuter = 30f + 105f * sin(burstProgress * PI.toFloat())
                val starInner = starOuter * 0.24f

                // 不规则锯齿扩散环
                val jagRadius = 35f + 135f * burstProgress
                val jagPath = Path().apply {
                    val points = 16
                    for (i in 0 until points) {
                        val r = if (i % 2 == 0) jagRadius else jagRadius * 0.76f
                        val ang = (i * (360.0 / points)) * PI / 180.0
                        val px = center.x + (r * cos(ang)).toFloat()
                        val py = center.y + (r * sin(ang)).toFloat()
                        if (i == 0) moveTo(px, py) else lineTo(px, py)
                    }
                    close()
                }
                drawPath(
                    path = jagPath,
                    color = P5Colors.PhantomCrimson.copy(alpha = (1f - burstProgress)),
                    style = Stroke(width = 6f, join = StrokeJoin.Miter)
                )

                // 外层黑红四角星芒与内层警戒黄高光星
                drawP5FourPointStar(
                    center = center,
                    outerRadius = starOuter * 1.15f,
                    innerRadius = starInner * 1.15f,
                    color = P5Colors.ShadowBlack.copy(alpha = alpha),
                    rotationDeg = -18f + burstProgress * 35f
                )
                drawP5FourPointStar(
                    center = center,
                    outerRadius = starOuter,
                    innerRadius = starInner,
                    color = P5Colors.PhantomCrimson.copy(alpha = alpha),
                    rotationDeg = -18f + burstProgress * 35f
                )
                drawP5FourPointStar(
                    center = center,
                    outerRadius = starOuter * 0.58f,
                    innerRadius = starInner * 0.58f,
                    color = P5Colors.AllOutYellow.copy(alpha = alpha),
                    rotationDeg = 27f + burstProgress * 35f
                )

                // 飘出倾斜的 "HIT!" / "ACTION!" 漫画字样
                val textOffsetX = center.x + 36f
                val textOffsetY = center.y - 42f - (64f * burstProgress)
                drawContext.canvas.nativeCanvas.apply {
                    save()
                    rotate(-12f, textOffsetX, textOffsetY)
                    labelStrokePaint.alpha = (alpha * 255).toInt()
                    labelPaint.alpha = (alpha * 255).toInt()
                    drawText(burst.label, textOffsetX, textOffsetY, labelStrokePaint)
                    drawText(burst.label, textOffsetX, textOffsetY, labelPaint)
                    restore()
                }
            }
        }

        // 2. 绘制所有活动中的 SWIPE 武士刀光拖尾轨迹 (Slash Trail)
        for (slash in swipeSlashes) {
            val p = slash.progress.value
            val headProgress = (p * 1.35f).coerceIn(0f, 1f)
            val tailProgress = ((p - 0.25f) * 1.35f).coerceIn(0f, 1f)

            val curStartX = slash.startX + (slash.endX - slash.startX) * tailProgress
            val curStartY = slash.startY + (slash.endY - slash.startY) * tailProgress
            val curEndX = slash.startX + (slash.endX - slash.startX) * headProgress
            val curEndY = slash.startY + (slash.endY - slash.startY) * headProgress

            val dx = curEndX - curStartX
            val dy = curEndY - curStartY
            val len = hypot(dx, dy)
            if (len > 4f) {
                val angle = atan2(dy, dx)
                val perpX = -sin(angle) * 18f * (1f - p * 0.6f)
                val perpY = cos(angle) * 18f * (1f - p * 0.6f)
                val midX = (curStartX + curEndX) * 0.5f
                val midY = (curStartY + curEndY) * 0.5f

                val bladePath = Path().apply {
                    moveTo(curStartX, curStartY)
                    lineTo(midX + perpX, midY + perpY)
                    lineTo(curEndX, curEndY)
                    lineTo(midX - perpX, midY - perpY)
                    close()
                }

                val alpha = (1f - p * 0.75f).coerceIn(0f, 1f)
                // 外层怪盗红刀气
                drawPath(
                    path = bladePath,
                    color = P5Colors.PhantomCrimson.copy(alpha = alpha)
                )
                // 内芯纯白凌厉刀锋
                drawLine(
                    color = P5Colors.StarkWhite.copy(alpha = alpha),
                    start = Offset(curStartX, curStartY),
                    end = Offset(curEndX, curEndY),
                    strokeWidth = 7f,
                    cap = StrokeCap.Round
                )
                // 刀尖星芒闪光
                drawP5FourPointStar(
                    center = Offset(curEndX, curEndY),
                    outerRadius = 38f * alpha,
                    innerRadius = 10f * alpha,
                    color = P5Colors.AllOutYellow.copy(alpha = alpha),
                    rotationDeg = 20f
                )
            }
        }
    }
}
