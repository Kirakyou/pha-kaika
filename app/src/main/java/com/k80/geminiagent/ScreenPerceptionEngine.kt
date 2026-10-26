package com.k80.geminiagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.Base64
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * UI 节点标注实体 (Set-of-Mark Node Element)
 */
data class SoMNodeElement(
    val id: Int,
    val className: String,
    val text: String,
    val contentDescription: String,
    val viewIdResourceName: String,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val boundsInScreen: Rect,
    val centerX: Int,
    val centerY: Int,
    val normX: Int,
    val normY: Int
)

/**
 * 屏幕感知完整快照结果
 */
data class PerceptionSnapshot(
    val annotatedJpegBase64: String,
    val uiNodeSummaryText: String,
    val nodeMap: Map<Int, SoMNodeElement>,
    val screenWidth: Int,
    val screenHeight: Int
)

/**
 * 模块一：Android 16 免 Root 混合视觉感知与 SoM (Set-of-Mark) 视觉增强引擎
 * 专为红米 K80 至尊版 (1280 x 2772, 144Hz, HyperOS 3 / Android 16) 深度调优。
 */
class ScreenPerceptionEngine(
    private val service: AccessibilityService
) {
    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    /**
     * 动态读取当前真实屏幕物理尺寸 (竖屏默认 1280x2772，横屏自动切换 2772x1280)
     */
    fun getRealScreenBounds(): Pair<Int, Int> {
        return try {
            val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val bounds = wm.maximumWindowMetrics.bounds
            val w = if (bounds.width() > 0) bounds.width() else 1280
            val h = if (bounds.height() > 0) bounds.height() else 2772
            Pair(w, h)
        } catch (_: Exception) {
            Pair(1280, 2772)
        }
    }

    /**
     * 执行完整免 Root 视觉感知流程：
     * 1. 隐藏 P5 悬浮窗与全屏特效层 -> 延迟 120ms 等待 144Hz 刷新率完成重绘
     * 2. 调用 takeScreenshot 并自动处理 Android 16 截屏冷却锁 (ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT)
     * 3. 立即恢复 P5 悬浮窗显示
     * 4. 将 HARDWARE 位图拷贝为 ARGB_8888 软位图并立即 close() 释放 HardwareBuffer
     * 5. 提取可交互 AccessibilityNodeInfo 节点树，在位图上绘制 P5 风格红框与 #ID 标签，压缩为 720x1559 Base64
     */
    suspend fun captureAndAnnotateScreen(
        onToggleOverlayVisibility: suspend (visible: Boolean) -> Unit
    ): Result<PerceptionSnapshot> = withContext(Dispatchers.Default) {
        val (screenWidth, screenHeight) = getRealScreenBounds()

        // 1. 截屏前先提取 UI 节点树，并隐藏本应用悬浮窗防遮挡
        val extractedNodes = extractInteractiveNodes(screenWidth, screenHeight)

        var softwareBitmap: Bitmap? = null
        try {
            onToggleOverlayVisibility(false)
            // 等待 120ms，确保 144Hz 屏幕彻底完成约 17 帧合成重绘，彻底消除残影
            delay(120L)

            val captureResult = takeScreenshotWithCooldownRetry(maxRetries = 3)
            if (captureResult.isFailure) {
                return@withContext Result.failure(
                    captureResult.exceptionOrNull() ?: IllegalStateException("免 Root 截屏失败")
                )
            }
            softwareBitmap = captureResult.getOrNull()
        } finally {
            // 无论成功或失败，立即恢复悬浮窗与特效层可见性
            onToggleOverlayVisibility(true)
        }

        val mutableBitmap = softwareBitmap
            ?: return@withContext Result.failure(IllegalStateException("未能获取有效 ARGB_8888 位图"))

        return@withContext try {
            // 2. 在可变软位图上绘制 Set-of-Mark (#1, #2, #3...) 半透明红框与标签
            drawSoMAnnotationsOnBitmap(mutableBitmap, extractedNodes)

            // 3. 按比例缩放至宽度 720px (在 1280x2772 下高度自动缩放到 1559px)
            val targetWidth = 720
            val scaleRatio = targetWidth.toFloat() / mutableBitmap.width.toFloat()
            val targetHeight = (mutableBitmap.height * scaleRatio).roundToInt().coerceAtLeast(1280)

            val scaledBitmap = Bitmap.createScaledBitmap(
                mutableBitmap,
                targetWidth,
                targetHeight,
                true
            )

            val base64Jpeg = ByteArrayOutputStream().use { bos ->
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 75, bos)
                Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
            }

            if (scaledBitmap !== mutableBitmap && !scaledBitmap.isRecycled) {
                scaledBitmap.recycle()
            }
            if (!mutableBitmap.isRecycled) {
                mutableBitmap.recycle()
            }

            // 4. 构建 UI 节点清单文本与 ID 索引表
            val nodeMap = LinkedHashMap<Int, SoMNodeElement>()
            val summaryBuilder = StringBuilder()
            summaryBuilder.append("当前屏幕分辨率: ${screenWidth}x${screenHeight} (Redmi K80 Ultra)\n")
            summaryBuilder.append("可交互 UI 节点清单 (共 ${extractedNodes.size} 个):\n")

            for (node in extractedNodes) {
                nodeMap[node.id] = node
                val typeTag = buildString {
                    append(node.className.substringAfterLast('.'))
                    if (node.isClickable) append("/可点")
                    if (node.isEditable) append("/可输入")
                    if (node.isScrollable) append("/可滑")
                }
                val labelDesc = buildString {
                    if (node.text.isNotBlank()) append("文本:\"${node.text}\" ")
                    if (node.contentDescription.isNotBlank()) append("描述:\"${node.contentDescription}\" ")
                    if (node.viewIdResourceName.isNotBlank()) {
                        append("ID:${node.viewIdResourceName.substringAfterLast('/')}")
                    }
                }.trim().ifEmpty { "无文本图标或容器" }

                summaryBuilder.append(
                    "[#${node.id}] $typeTag | $labelDesc | 像素中心:(${node.centerX},${node.centerY}) | 归一化:(${node.normX},${node.normY})\n"
                )
            }

            Result.success(
                PerceptionSnapshot(
                    annotatedJpegBase64 = base64Jpeg,
                    uiNodeSummaryText = summaryBuilder.toString(),
                    nodeMap = nodeMap,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight
                )
            )
        } catch (e: Exception) {
            if (!mutableBitmap.isRecycled) {
                mutableBitmap.recycle()
            }
            Result.failure(e)
        }
    }

    /**
     * Android 16 截屏冷却锁 (ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) 自动重试封装
     * 同时严密处理 HardwareBuffer -> ARGB_8888 转换与内存释放。
     */
    private suspend fun takeScreenshotWithCooldownRetry(maxRetries: Int = 3): Result<Bitmap> {
        var attempt = 0
        var lastErrorCode = -1

        while (attempt <= maxRetries) {
            val singleAttempt = takeSingleScreenshotOnce()
            if (singleAttempt.isSuccess) {
                return singleAttempt
            }

            val err = singleAttempt.exceptionOrNull()
            val errorCode = (err as? ScreenshotException)?.errorCode ?: -1
            lastErrorCode = errorCode

            // ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT 常量值为 3
            if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && attempt < maxRetries) {
                attempt++
                delay(850L)
                continue
            } else if (attempt < maxRetries) {
                attempt++
                delay(500L)
                continue
            } else {
                break
            }
        }
        return Result.failure(
            IllegalStateException("无障碍截屏失败 (尝试 ${attempt + 1} 次, 错误码=$lastErrorCode)")
        )
    }

    private suspend fun takeSingleScreenshotOnce(): Result<Bitmap> =
        suspendCancellableCoroutine { cont ->
            try {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    screenshotExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            val hwBuffer = screenshot.hardwareBuffer
                            val colorSpace = screenshot.colorSpace
                            var hardwareBitmap: Bitmap? = null
                            try {
                                hardwareBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                                if (hardwareBitmap == null) {
                                    if (cont.isActive) {
                                        cont.resume(
                                            Result.failure(IllegalStateException("wrapHardwareBuffer 返回 null"))
                                        )
                                    }
                                    return
                                }
                                // Android 16 硬件加速避坑：将 HARDWARE 位图拷贝为可变 ARGB_8888 软位图
                                val argbBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, true)
                                if (argbBitmap == null) {
                                    if (cont.isActive) {
                                        cont.resume(
                                            Result.failure(IllegalStateException("拷贝 ARGB_8888 位图失败"))
                                        )
                                    }
                                    return
                                }
                                if (cont.isActive) {
                                    cont.resume(Result.success(argbBitmap))
                                } else {
                                    argbBitmap.recycle()
                                }
                            } catch (t: Throwable) {
                                if (cont.isActive) {
                                    cont.resume(Result.failure(t))
                                }
                            } finally {
                                // 立即回收 hardwareBitmap 并关闭 HardwareBuffer 防止底层 GraphicBuffer 泄漏
                                try {
                                    hardwareBitmap?.recycle()
                                } catch (_: Exception) {
                                }
                                try {
                                    hwBuffer.close()
                                } catch (_: Exception) {
                                }
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            if (cont.isActive) {
                                cont.resume(Result.failure(ScreenshotException(errorCode)))
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                if (cont.isActive) {
                    cont.resume(Result.failure(e))
                }
            }
        }

    /**
     * 遍历当前活动窗口与交互窗口，提取所有有交互价值且可见的 UI 节点
     */
    private fun extractInteractiveNodes(screenWidth: Int, screenHeight: Int): List<SoMNodeElement> {
        val rawNodes = mutableListOf<SoMNodeElement>()
        val seenRectSignatures = HashSet<String>()
        var nextId = 1

        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            service.rootInActiveWindow?.let { roots.add(it) }
            val interactiveWindows = service.windows
            if (!interactiveWindows.isNullOrEmpty()) {
                for (win in interactiveWindows) {
                    if (win.type == AccessibilityWindowInfo.TYPE_APPLICATION ||
                        win.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                        win.type == AccessibilityWindowInfo.TYPE_SYSTEM
                    ) {
                        val root = win.root
                        if (root != null && root.packageName?.toString() != service.packageName) {
                            roots.add(root)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        fun traverse(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 42 || nextId > 95) return
            if (node.packageName?.toString() == service.packageName) return

            if (node.isVisibleToUser) {
                val rect = Rect()
                node.getBoundsInScreen(rect)

                // 过滤掉无效微缩节点或全屏背景大容器
                val width = rect.width()
                val height = rect.height()
                val isReasonableSize = width >= 18 && height >= 18 &&
                        !(width > screenWidth * 0.96 && height > screenHeight * 0.92 && !node.isScrollable)

                val nodeText = node.text?.toString()?.trim()?.replace("\n", " ") ?: ""
                val nodeDesc = node.contentDescription?.toString()?.trim()?.replace("\n", " ") ?: ""
                val viewId = node.viewIdResourceName ?: ""
                val hasMeaningfulContent = nodeText.isNotEmpty() || nodeDesc.isNotEmpty()
                val isInteractive = node.isClickable || node.isEditable || node.isLongClickable ||
                        node.isCheckable || node.isScrollable

                if (isReasonableSize && (isInteractive || hasMeaningfulContent)) {
                    val centerX = rect.centerX().coerceIn(0, screenWidth)
                    val centerY = rect.centerY().coerceIn(0, screenHeight)
                    val signature = "${centerX / 16}_${centerY / 16}_${width / 24}_${height / 24}"

                    if (seenRectSignatures.add(signature)) {
                        val normX = ((centerX.toFloat() / screenWidth.toFloat()) * 1000f)
                            .roundToInt().coerceIn(0, 1000)
                        val normY = ((centerY.toFloat() / screenHeight.toFloat()) * 1000f)
                            .roundToInt().coerceIn(0, 1000)

                        rawNodes.add(
                            SoMNodeElement(
                                id = nextId++,
                                className = node.className?.toString() ?: "View",
                                text = nodeText.take(40),
                                contentDescription = nodeDesc.take(40),
                                viewIdResourceName = viewId,
                                isClickable = node.isClickable,
                                isEditable = node.isEditable,
                                isScrollable = node.isScrollable,
                                boundsInScreen = Rect(rect),
                                centerX = centerX,
                                centerY = centerY,
                                normX = normX,
                                normY = normY
                            )
                        )
                    }
                }
            }

            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = try {
                    node.getChild(i)
                } catch (_: Exception) {
                    null
                }
                if (child != null) {
                    traverse(child, depth + 1)
                }
            }
        }

        for (root in roots) {
            traverse(root, 0)
        }
        return rawNodes
    }

    /**
     * 在 ARGB_8888 位图副本上使用原生 Canvas 绘制 Set-of-Mark 半透明红框与 #ID 数字标签
     */
    private fun drawSoMAnnotationsOnBitmap(bitmap: Bitmap, nodes: List<SoMNodeElement>) {
        val canvas = Canvas(bitmap)

        val boxFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(28, 230, 0, 18) // 半透明怪盗红填充
        }
        val boxStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3.5f
            color = Color.argb(225, 230, 0, 18) // 鲜亮怪盗红描边
        }
        val tagBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(240, 10, 10, 10) // 暗影黑底色
        }
        val tagBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            color = Color.rgb(255, 230, 0) // 警戒黄边框
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 26f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC)
        }

        for (node in nodes) {
            val r = node.boundsInScreen
            canvas.drawRect(r, boxFillPaint)
            canvas.drawRect(r, boxStrokePaint)

            val label = "#${node.id}"
            val textWidth = textPaint.measureText(label)
            val tagHeight = 34f
            val tagWidth = textWidth + 16f

            val tagLeft = r.left.toFloat().coerceIn(4f, (bitmap.width - tagWidth - 4f).coerceAtLeast(4f))
            val tagTop = (r.top.toFloat() - tagHeight * 0.65f)
                .coerceIn(4f, (bitmap.height - tagHeight - 4f).coerceAtLeast(4f))
            val tagRight = tagLeft + tagWidth
            val tagBottom = tagTop + tagHeight

            canvas.drawRect(tagLeft, tagTop, tagRight, tagBottom, tagBgPaint)
            canvas.drawRect(tagLeft, tagTop, tagRight, tagBottom, tagBorderPaint)
            canvas.drawText(label, tagLeft + 8f, tagBottom - 8f, textPaint)
        }
    }

    private class ScreenshotException(val errorCode: Int) :
        Exception("Accessibility takeScreenshot failed with code: $errorCode")
}
