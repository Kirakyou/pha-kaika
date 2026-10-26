package com.k80.geminiagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * 模块三：免 Root 高可靠无障碍动作执行器 (ActionExecutor)
 * 100% 纯粹依靠 Android 官方 AccessibilityService API 执行手势、文本注入、剪贴板粘贴兜底与应用直连启动。
 * 严禁调用任何 su、shell input 或 Shizuku。
 */
class ActionExecutor(
    private val service: AccessibilityService,
    private val soundManager: P5SoundAndHapticManager,
    private val onTriggerClickVfx: (x: Float, y: Float, label: String) -> Unit,
    private val onTriggerSwipeVfx: (startX: Float, startY: Float, endX: Float, endY: Float) -> Unit
) {

    /**
     * 内置常见国内与系统高频应用包名映射表（适配小米 HyperOS 3）
     */
    private val commonAppPackages: Map<String, String> = mapOf(
        "微信" to "com.tencent.mm",
        "wechat" to "com.tencent.mm",
        "qq" to "com.tencent.mobileqq",
        "支付宝" to "com.eg.android.AlipayGphone",
        "淘宝" to "com.taobao.taobao",
        "京东" to "com.jingdong.app.mall",
        "美团" to "com.sankuai.meituan",
        "拼多多" to "com.xunmeng.pinduoduo",
        "哔哩哔哩" to "tv.danmaku.bili",
        "b站" to "tv.danmaku.bili",
        "bilibili" to "tv.danmaku.bili",
        "抖音" to "com.ss.android.ugc.aweme",
        "小红书" to "com.xingin.xhs",
        "高德地图" to "com.autonavi.minimap",
        "高德" to "com.autonavi.minimap",
        "百度地图" to "com.baidu.BaiduMap",
        "网易云音乐" to "com.netease.cloudmusic",
        "qq音乐" to "com.tencent.qqmusic",
        "知乎" to "com.zhihu.android",
        "微博" to "com.sina.weibo",
        "闲鱼" to "com.taobao.idlefish",
        "饿了么" to "me.ele",
        "携程" to "ctrip.android.view",
        "12306" to "com.MobileTicket",
        "设置" to "com.android.settings",
        "系统设置" to "com.android.settings",
        "浏览器" to "com.android.browser",
        "相机" to "com.android.camera",
        "相册" to "com.miui.gallery",
        "计算器" to "com.miui.calculator",
        "时钟" to "com.android.deskclock",
        "应用商店" to "com.xiaomi.market",
        "小米应用商店" to "com.xiaomi.market"
    )

    /**
     * 统一执行 AI 决策动作并返回执行结果描述
     */
    suspend fun executeDecision(
        decision: GeminiAgentDecision,
        snapshot: PerceptionSnapshot,
        brainClient: GeminiBrainClient
    ): String {
        val actionType = decision.action.trim().uppercase()
        return when (actionType) {
            "CLICK" -> {
                val coord = brainClient.resolveTargetCoordinate(decision, snapshot)
                val ok = performClick(coord.realX, coord.realY, "HIT!")
                if (ok) "成功点击 ${coord.sourceDescription}" else "点击手势被系统取消 ${coord.sourceDescription}"
            }

            "LONG_PRESS" -> {
                val coord = brainClient.resolveTargetCoordinate(decision, snapshot)
                val ok = performLongPress(coord.realX, coord.realY)
                if (ok) "成功长按 ${coord.sourceDescription}" else "长按手势失败 ${coord.sourceDescription}"
            }

            "SWIPE" -> {
                val (start, end) = brainClient.resolveSwipeCoordinates(decision, snapshot)
                val ok = performSwipe(start.realX, start.realY, end.realX, end.realY)
                if (ok) {
                    "成功滑动 (${start.realX.roundToInt()},${start.realY.roundToInt()}) -> (${end.realX.roundToInt()},${end.realY.roundToInt()})"
                } else {
                    "滑动手势未完成"
                }
            }

            "INPUT_TEXT" -> {
                val textToInput = decision.inputText.orEmpty()
                val hasCoordinate = decision.targetNodeId != null || !decision.normPoint.isNullOrEmpty()
                val coord = if (hasCoordinate) brainClient.resolveTargetCoordinate(decision, snapshot) else null
                val ok = performInputText(textToInput, coord?.realX, coord?.realY)
                if (ok) "已输入文本: \"$textToInput\"" else "文本输入失败: \"$textToInput\""
            }

            "PRESS_BACK" -> {
                soundManager.playMenuChainSlash()
                val ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                if (ok) "已执行系统返回 (GLOBAL_ACTION_BACK)" else "系统返回执行失败"
            }

            "PRESS_HOME" -> {
                soundManager.playMenuChainSlash()
                val ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                if (ok) "已返回手机桌面 (GLOBAL_ACTION_HOME)" else "返回桌面执行失败"
            }

            "OPEN_APP" -> {
                val targetApp = decision.appName.orEmpty().trim()
                val ok = launchAppByNameOrPackage(targetApp)
                if (ok) "已直连启动目标应用: $targetApp" else "未找到或无法启动应用: $targetApp"
            }

            "WAIT" -> {
                delay(1200L)
                "等待界面加载 1200ms"
            }

            "COMPLETE" -> {
                soundManager.playAllOutComplete()
                "MISSION COMPLETE：怪盗团委托任务圆满完成！"
            }

            "FAIL" -> {
                soundManager.playCancelOrAlert()
                "MISSION FAILED：${decision.thought}"
            }

            else -> {
                delay(800L)
                "未知动作类型 ($actionType)，已执行默认等待"
            }
        }
    }

    /**
     * 1. 免 Root 无障碍单点点击 (60ms) + P5 十字准星/四角星芒暴击特效与合成器重击音
     */
    suspend fun performClick(realX: Float, realY: Float, badgeText: String = "HIT!"): Boolean {
        soundManager.playCriticalHit()
        onTriggerClickVfx(realX, realY, badgeText)
        return dispatchSingleStrokeGesture(
            startX = realX,
            startY = realY,
            endX = realX,
            endY = realY,
            durationMs = 60L
        )
    }

    /**
     * 2. 免 Root 无障碍长按 (850ms) + P5 准星锁定特效
     */
    suspend fun performLongPress(realX: Float, realY: Float): Boolean {
        soundManager.playCriticalHit()
        onTriggerClickVfx(realX, realY, "HOLD!")
        return dispatchSingleStrokeGesture(
            startX = realX,
            startY = realY,
            endX = realX,
            endY = realY,
            durationMs = 850L
        )
    }

    /**
     * 3. 免 Root 无障碍滑动 (400ms) + P5 武士刀红白刀光拖尾特效
     */
    suspend fun performSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 400L
    ): Boolean {
        soundManager.playCriticalHit()
        onTriggerSwipeVfx(startX, startY, endX, endY)
        return dispatchSingleStrokeGesture(
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            durationMs = durationMs
        )
    }

    /**
     * 4. 免 Root 全兼容文本输入 (INPUT_TEXT)：
     * - 第一步：若指定了坐标，先通过 dispatchGesture 执行 CLICK 激活输入框焦点并等待 400ms
     * - 第二步：查找处于 isFocused 或 isEditable 状态的节点，调用 ACTION_SET_TEXT 注入中英文
     * - 第三步：若不支持 ACTION_SET_TEXT，写入系统 ClipboardManager 并调用 ACTION_PASTE 粘贴兜底
     */
    suspend fun performInputText(
        text: String,
        targetX: Float?,
        targetY: Float?
    ): Boolean {
        if (text.isEmpty()) return false

        // 第一步：点击目标输入框坐标获取焦点
        if (targetX != null && targetY != null) {
            performClick(targetX, targetY, "FOCUS!")
            delay(400L)
        }

        // 第二步：定位当前焦点或可编辑节点并尝试 ACTION_SET_TEXT
        val targetNode = findFocusedOrEditableNode()
        if (targetNode != null) {
            val arguments = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
            val setOk = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            if (setOk) {
                return true
            }

            // 第三步：剪贴板粘贴兜底
            val copied = copyToSystemClipboard(text)
            if (copied) {
                targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val pasteOk = targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                if (pasteOk) {
                    return true
                }
            }
        } else {
            // 即使未找到明确节点，也将文本写入剪贴板备查
            copyToSystemClipboard(text)
        }
        return false
    }

    /**
     * 5. 小米 HyperOS 3 应用直连启动 (OPEN_APP)：
     * 优先匹配内置常用包名映射表，其次通过 PackageManager.queryIntentActivities 动态模糊匹配已安装应用名。
     */
    suspend fun launchAppByNameOrPackage(appNameOrPkg: String): Boolean = withContext(Dispatchers.Main) {
        if (appNameOrPkg.isBlank()) return@withContext false
        soundManager.playMenuChainSlash()

        val pm = service.packageManager
        val normalizedKey = appNameOrPkg.trim().lowercase()

        // Special handling for Settings
        if (normalizedKey == "设置" || normalizedKey == "系统设置" || normalizedKey == "com.android.settings") {
            return@withContext try {
                val settingsIntent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                service.startActivity(settingsIntent)
                true
            } catch (_: Exception) {
                false
            }
        }

        // 1. 尝试内置包名映射表或直接作为包名启动
        val mappedPkg = commonAppPackages[normalizedKey] ?: appNameOrPkg.trim()
        try {
            val directIntent = pm.getLaunchIntentForPackage(mappedPkg)
            if (directIntent != null) {
                directIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                service.startActivity(directIntent)
                return@withContext true
            }
        } catch (_: Exception) {
        }

        // 2. 动态遍历已安装桌面入口 Activity 模糊匹配应用名称
        return@withContext try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
            val matchedInfo = resolveInfos.firstOrNull { info ->
                val label = info.loadLabel(pm).toString().trim().lowercase()
                val pkg = info.activityInfo.packageName.lowercase()
                label == normalizedKey || label.contains(normalizedKey) ||
                        normalizedKey.contains(label) || pkg.contains(normalizedKey)
            }

            if (matchedInfo != null) {
                val targetPkg = matchedInfo.activityInfo.packageName
                val launchIntent = pm.getLaunchIntentForPackage(targetPkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    service.startActivity(launchIntent)
                    true
                } else {
                    false
                }
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 使用 suspendCancellableCoroutine 封装 dispatchGesture，确保手势真正执行完毕后再继续下一步
     */
    private suspend fun dispatchSingleStrokeGesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ): Boolean = suspendCancellableCoroutine { cont ->
        try {
            val safeStartX = startX.coerceAtLeast(1f)
            val safeStartY = startY.coerceAtLeast(1f)
            val safeEndX = endX.coerceAtLeast(1f)
            val safeEndY = endY.coerceAtLeast(1f)

            val gesturePath = Path().apply {
                moveTo(safeStartX, safeStartY)
                if (safeStartX != safeEndX || safeStartY != safeEndY) {
                    lineTo(safeEndX, safeEndY)
                }
            }

            val stroke = GestureDescription.StrokeDescription(
                gesturePath,
                0L,
                durationMs.coerceIn(40L, 4000L)
            )
            val gesture = GestureDescription.Builder()
                .addStroke(stroke)
                .build()

            val dispatched = service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null
            )

            if (!dispatched && cont.isActive) {
                cont.resume(false)
            }
        } catch (_: Exception) {
            if (cont.isActive) cont.resume(false)
        }
    }

    private fun findFocusedOrEditableNode(): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null

        // 优先查找当前已经获得输入焦点的节点
        val focusedInput = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedInput != null && (focusedInput.isEditable || focusedInput.className?.contains("EditText") == true)) {
            return focusedInput
        }

        // 递归遍历寻找屏幕上第一个可见的 isEditable 节点
        var firstEditable: AccessibilityNodeInfo? = null
        fun search(node: AccessibilityNodeInfo?) {
            if (node == null || firstEditable != null) return
            if (node.isVisibleToUser && (node.isEditable || node.className?.toString()?.contains("EditText") == true)) {
                if (node.isFocused) {
                    firstEditable = node
                    return
                }
                if (firstEditable == null) {
                    firstEditable = node
                }
            }
            for (i in 0 until node.childCount) {
                search(try { node.getChild(i) } catch (_: Exception) { null })
            }
        }
        search(root)
        return firstEditable ?: focusedInput
    }

    private suspend fun copyToSystemClipboard(text: String): Boolean = withContext(Dispatchers.Main) {
        return@withContext try {
            val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("PhantomAgentInput", text)
            clipboard.setPrimaryClip(clip)
            true
        } catch (_: Exception) {
            false
        }
    }
}
