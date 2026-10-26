package com.k80.geminiagent

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Gemini 返回的严格战术 JSON 决策对象
 */
data class GeminiAgentDecision(
    @SerializedName("thought")
    val thought: String = "正在扫描异世界认知节点...",
    @SerializedName("action")
    val action: String = "WAIT",
    @SerializedName("target_node_id")
    val targetNodeId: Int? = null,
    @SerializedName("norm_point")
    val normPoint: List<Int>? = null,
    @SerializedName("norm_end_point")
    val normEndPoint: List<Int>? = null,
    @SerializedName("input_text")
    val inputText: String? = null,
    @SerializedName("app_name")
    val appName: String? = null
)

/**
 * 经过双重坐标回退对齐后的真实物理坐标点
 */
data class ResolvedCoordinate(
    val realX: Float,
    val realY: Float,
    val sourceDescription: String
)

/**
 * 模块二：Gemini 多模态大脑与双重坐标对齐系统
 * 支持滑动窗口记忆 (最近 10 步)、怪盗团导航员 (Oracle / Mona) 战术人设、以及 #ID 节点 + 归一化视觉双重坐标解析。
 */
class GeminiBrainClient {

    private val gson = Gson()
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // 滑动窗口动作历史记忆（保留最近 10 步）
    private val actionHistory = ArrayDeque<String>(MAX_HISTORY_STEPS)

    fun clearHistory() {
        synchronized(actionHistory) {
            actionHistory.clear()
        }
    }

    fun recordStepHistory(stepNumber: Int, decision: GeminiAgentDecision, execSummary: String) {
        synchronized(actionHistory) {
            if (actionHistory.size >= MAX_HISTORY_STEPS) {
                actionHistory.removeFirst()
            }
            val nodeStr = decision.targetNodeId?.let { "#$it" } ?: "norm:${decision.normPoint}"
            val entry = "Step $stepNumber | Action=${decision.action} ($nodeStr) | Thought=${decision.thought} | Result=$execSummary"
            actionHistory.addLast(entry)
        }
    }

    fun getHistorySnapshot(): List<String> {
        synchronized(actionHistory) {
            return actionHistory.toList()
        }
    }

    /**
     * 调用 Gemini REST API 进行多模态视觉推理
     * Endpoint: https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={apiKey}
     */
    suspend fun analyzeScreenAndDecide(
        apiKey: String,
        modelName: String,
        userGoal: String,
        currentStep: Int,
        maxSteps: Int,
        snapshot: PerceptionSnapshot
    ): Result<GeminiAgentDecision> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("未配置 Gemini API Key！请先在怪盗基地主页输入密钥。"))
        }

        val cleanModel = modelName.trim().ifEmpty { DEFAULT_MODEL }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$cleanModel:generateContent?key=${apiKey.trim()}"

        val historyText = synchronized(actionHistory) {
            if (actionHistory.isEmpty()) "暂无历史步骤（当前为第 1 回合潜入行动）"
            else actionHistory.joinToString("\n")
        }

        val systemInstructionText = """
            你是《女神异闻录5》(Persona 5) 怪盗团的殿堂级异世界导航员 Oracle (佐仓双叶) 与 Mona (摩尔加纳) 联合战术 AI 大脑！
            你正在操控一台未 Root 的 Redmi K80 至尊版 (Android 16 / HyperOS 3，物理分辨率 ${snapshot.screenWidth}x${snapshot.screenHeight})。
            
            【核心任务准则】
            1. 仔细观察上传的手机屏幕截图（图中已用红框与黄边黑底数字标签 #1, #2, #3... 标注了可交互 UI 节点），并结合下方的 UI 节点文本清单做决策。
            2. 你的 "thought" 字段必须准确分析当前界面状态与下一步动作意图，并且带有酷炫热血的 Persona 5 怪盗团导航员战术播报风格（例如：“发现阴影防线薄弱点！正在锁定搜索栏 (#5) 突入目標区域...” 或 “Nice Joker！目标页面已展开，准备发动总攻击点击确认按钮 (#12)！”）。
            3. 优先选择带有明确 #ID 的节点填入 "target_node_id"，同时始终计算并填入 "norm_point": [x, y]（范围 0~1000 的归一化坐标，左上角为 [0,0]，右下角为 [1000,1000]）作为视觉兜底坐标！
            4. 如果要直接打开某个常用 App（如微信、支付宝、淘宝、美团、B站、抖音、小红书、QQ、设置等），请优先使用 "OPEN_APP" 动作并在 "app_name" 填入应用名称，无需在桌面上盲目翻找图标。
            5. 如果需要在输入框打字，请使用 "INPUT_TEXT"，同时提供输入框的 "target_node_id" 或 "norm_point"，并在 "input_text" 填入要输入的文本。
            6. 当用户的最终目标已经达成时，立即返回 "COMPLETE"；当遇到无法逾越的错误或需要用户手动输入密码/验证码时，返回 "FAIL" 并在 thought 中说明原因。
            
            【严格 JSON 输出格式要求】
            必须且只能输出一个合法的 JSON 对象，严禁包含 Markdown 代码块符号，结构如下：
            {
              "thought": "战术播报风格的当前界面分析与下一步推理",
              "action": "CLICK | LONG_PRESS | SWIPE | INPUT_TEXT | PRESS_BACK | PRESS_HOME | OPEN_APP | WAIT | COMPLETE | FAIL",
              "target_node_id": 整数或null,
              "norm_point": [整数x(0-1000), 整数y(0-1000)],
              "norm_end_point": [整数x2(0-1000), 整数y2(0-1000)],
              "input_text": "仅 INPUT_TEXT 时填写的字符串，其余为 null",
              "app_name": "仅 OPEN_APP 时填写的应用名称或包名，其余为 null"
            }
        """.trimIndent()

        val userPromptText = """
            【怪盗团委托任务目标 (Mission Objective)】：$userGoal
            【当前潜入回合】：Step $currentStep / $maxSteps
            
            【最近动作历史记忆 (Sliding Window History)】：
            $historyText
            
            【当前屏幕 Set-of-Mark (SoM) 节点列表】：
            ${snapshot.uiNodeSummaryText}
            
            请结合上方带 #ID 标签的屏幕截图与节点清单，立即给出下一步 JSON 战术指令！
        """.trimIndent()

        val payloadJson = JsonObject().apply {
            add("systemInstruction", JsonObject().apply {
                add("parts", JsonArray().apply {
                    add(JsonObject().apply { addProperty("text", systemInstructionText) })
                })
            })
            add("contents", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    add("parts", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("text", userPromptText)
                        })
                        add(JsonObject().apply {
                            add("inlineData", JsonObject().apply {
                                addProperty("mimeType", "image/jpeg")
                                addProperty("data", snapshot.annotatedJpegBase64)
                            })
                        })
                    })
                })
            })
            add("generationConfig", JsonObject().apply {
                addProperty("responseMimeType", "application/json")
                addProperty("temperature", 0.1)
            })
        }

        val requestBody = gson.toJson(payloadJson)
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        return@withContext try {
            okHttpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("Gemini API 响应错误 (HTTP ${response.code}): ${bodyStr.take(260)}")
                    )
                }

                val rootObj = JsonParser.parseString(bodyStr).asJsonObject
                val candidates = rootObj.getAsJsonArray("candidates")
                if (candidates == null || candidates.isEmpty) {
                    return@withContext Result.failure(
                        IllegalStateException("Gemini 未返回有效候选结果: ${bodyStr.take(200)}")
                    )
                }

                val rawContentText = candidates[0].asJsonObject
                    .getAsJsonObject("content")
                    .getAsJsonArray("parts")[0].asJsonObject
                    .get("text").asString

                val cleanedJson = sanitizeJsonText(rawContentText)
                val decision = gson.fromJson(cleanedJson, GeminiAgentDecision::class.java)
                Result.success(decision)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 双重坐标回退解析逻辑：
     * 1. 优先模式：若 target_node_id 有效且存在于本地缓存节点树中，直接取 Rect.centerX() 和 Rect.centerY()（100% 像素级精准）
     * 2. 兜底视觉模式：当 target_node_id 为 null 或不存在时，使用 norm_point (0~1000) 乘以当前屏幕真实物理宽高
     */
    fun resolveTargetCoordinate(
        decision: GeminiAgentDecision,
        snapshot: PerceptionSnapshot
    ): ResolvedCoordinate {
        val nodeId = decision.targetNodeId
        if (nodeId != null && nodeId > 0) {
            val matchedNode = snapshot.nodeMap[nodeId]
            if (matchedNode != null) {
                return ResolvedCoordinate(
                    realX = matchedNode.centerX.toFloat(),
                    realY = matchedNode.centerY.toFloat(),
                    sourceDescription = "SoM 节点 #$nodeId 精确中心 (${matchedNode.centerX}, ${matchedNode.centerY})"
                )
            }
        }

        val normPt = decision.normPoint
        if (normPt != null && normPt.size >= 2) {
            val normX = normPt[0].coerceIn(0, 1000)
            val normY = normPt[1].coerceIn(0, 1000)
            val realX = (normX / 1000f) * snapshot.screenWidth.toFloat()
            val realY = (normY / 1000f) * snapshot.screenHeight.toFloat()
            return ResolvedCoordinate(
                realX = realX.coerceIn(1f, snapshot.screenWidth - 1f),
                realY = realY.coerceIn(1f, snapshot.screenHeight - 1f),
                sourceDescription = "视觉归一化坐标 [$normX, $normY] -> (${realX.roundToInt()}, ${realY.roundToInt()})"
            )
        }

        // 默认屏幕中心安全兜底
        val cx = snapshot.screenWidth / 2f
        val cy = snapshot.screenHeight / 2f
        return ResolvedCoordinate(
            realX = cx,
            realY = cy,
            sourceDescription = "默认屏幕中心 (${cx.roundToInt()}, ${cy.roundToInt()})"
        )
    }

    /**
     * 解析滑动手势的起止物理坐标
     */
    fun resolveSwipeCoordinates(
        decision: GeminiAgentDecision,
        snapshot: PerceptionSnapshot
    ): Pair<ResolvedCoordinate, ResolvedCoordinate> {
        val startCoord = if (decision.normPoint != null && decision.normPoint.size >= 2) {
            val nx = decision.normPoint[0].coerceIn(0, 1000)
            val ny = decision.normPoint[1].coerceIn(0, 1000)
            ResolvedCoordinate(
                realX = (nx / 1000f) * snapshot.screenWidth,
                realY = (ny / 1000f) * snapshot.screenHeight,
                sourceDescription = "起点[$nx,$ny]"
            )
        } else {
            ResolvedCoordinate(
                realX = snapshot.screenWidth * 0.5f,
                realY = snapshot.screenHeight * 0.72f,
                sourceDescription = "默认上滑起点"
            )
        }

        val endCoord = if (decision.normEndPoint != null && decision.normEndPoint.size >= 2) {
            val ex = decision.normEndPoint[0].coerceIn(0, 1000)
            val ey = decision.normEndPoint[1].coerceIn(0, 1000)
            ResolvedCoordinate(
                realX = (ex / 1000f) * snapshot.screenWidth,
                realY = (ey / 1000f) * snapshot.screenHeight,
                sourceDescription = "终点[$ex,$ey]"
            )
        } else {
            ResolvedCoordinate(
                realX = snapshot.screenWidth * 0.5f,
                realY = snapshot.screenHeight * 0.26f,
                sourceDescription = "默认上滑终点"
            )
        }
        return Pair(startCoord, endCoord)
    }

    private fun sanitizeJsonText(raw: String): String {
        var trimmed = raw.trim()
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.removePrefix("```json").removePrefix("```").trim()
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.removeSuffix("```").trim()
            }
        }
        val firstBrace = trimmed.indexOf('{')
        val lastBrace = trimmed.lastIndexOf('}')
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            trimmed = trimmed.substring(firstBrace, lastBrace + 1)
        }
        return trimmed
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-2.5-flash"
        private const val MAX_HISTORY_STEPS = 10
    }
}
