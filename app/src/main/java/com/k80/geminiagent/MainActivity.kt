package com.k80.geminiagent

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * 模块五：Persona 5 怪盗团基地风格主界面与小米 HyperOS 3 权限中心 (MainActivity)
 * 专为 Redmi K80 至尊版 (1280 x 2772, Android 16 / HyperOS 3) 打造。
 */
class MainActivity : ComponentActivity() {

    private lateinit var soundManager: P5SoundAndHapticManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        soundManager = P5SoundAndHapticManager(applicationContext)

        setContent {
            P5PhantomTheme {
                PhantomHideoutMainScreen(soundManager = soundManager)
            }
        }
    }
}

@Composable
private fun PhantomHideoutMainScreen(
    soundManager: P5SoundAndHapticManager
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember {
        context.getSharedPreferences(P5SoundAndHapticManager.PREFS_NAME, Context.MODE_PRIVATE)
    }

    // 战术装备持久化配置状态
    var apiKey by remember {
        mutableStateOf(prefs.getString(AgentAccessibilityService.KEY_API_KEY, "").orEmpty())
    }
    var showApiKeyPlain by remember { mutableStateOf(false) }
    var modelName by remember {
        mutableStateOf(
            prefs.getString(
                AgentAccessibilityService.KEY_MODEL_NAME,
                GeminiBrainClient.DEFAULT_MODEL
            ).orEmpty().ifEmpty { GeminiBrainClient.DEFAULT_MODEL }
        )
    }
    var maxStepsText by remember {
        mutableStateOf(prefs.getInt(AgentAccessibilityService.KEY_MAX_STEPS, 25).toString())
    }
    var stepDelayText by remember {
        mutableStateOf(prefs.getLong(AgentAccessibilityService.KEY_STEP_DELAY_MS, 1500L).toString())
    }
    var soundEnabled by remember {
        mutableStateOf(soundManager.isSoundAndHapticEnabled)
    }
    var quickMissionText by remember {
        mutableStateOf("打开系统设置，进入WLAN页面查看当前Wi-Fi连接状态")
    }

    // 潜入通道权限实时自检状态
    val serviceConnectedByInstance by AgentAccessibilityService.isServiceConnectedFlow.collectAsState()
    var isAccessibilityEnabled by remember { mutableStateOf(checkAccessibilityEnabled(context)) }
    var isBatteryOptimizationIgnored by remember {
        mutableStateOf(checkBatteryOptimizationIgnored(context))
    }

    // 每次从系统设置返回 Activity 时自动刷新权限印章状态
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isAccessibilityEnabled = checkAccessibilityEnabled(context)
                isBatteryOptimizationIgnored = checkBatteryOptimizationIgnored(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val finalAccessibilityReady = isAccessibilityEnabled || serviceConnectedByInstance

    Box(modifier = Modifier.fillMaxSize()) {
        // 底层：动态旋转红黑半色调波点 + 怪盗团星芒放射背景
        P5HalftoneStarburstBackground()

        // 主体滚动容器
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 1. 顶部动态 Banner：PHANTOM AGENT // VER 1.6
            PhantomTopHeroBanner()

            // 2. 潜入权限状态自检面板（带 PASS / LOCKED 倾斜印章视觉效果）
            InfiltrationPermissionsCard(
                isAccessibilityPassed = finalAccessibilityReady,
                isBatteryPassed = isBatteryOptimizationIgnored,
                onOpenAccessibilitySettings = {
                    soundManager.playMenuChainSlash()
                    openAccessibilitySettings(context)
                },
                onRequestBatteryWhitelist = {
                    soundManager.playMenuChainSlash()
                    requestIgnoreBatteryOptimizations(context)
                }
            )

            // 3. 小米 HyperOS 3 (Android 16) 专属潜入破解指南卡片 + 一键跳转应用详情页
            HyperOS3TacticalBriefingCard(
                onOpenAppDetails = {
                    soundManager.playMenuChainSlash()
                    openApplicationDetailsSettings(context)
                }
            )

            // 4. 战术装备配置区（P5 倾斜多边形卡片）
            TacticalGearConfigCard(
                apiKey = apiKey,
                onApiKeyChange = { apiKey = it },
                showApiKeyPlain = showApiKeyPlain,
                onToggleShowApiKey = {
                    soundManager.playMenuChainSlash()
                    showApiKeyPlain = !showApiKeyPlain
                },
                modelName = modelName,
                onModelNameChange = { modelName = it },
                maxStepsText = maxStepsText,
                onMaxStepsChange = { maxStepsText = it },
                stepDelayText = stepDelayText,
                onStepDelayChange = { stepDelayText = it },
                soundEnabled = soundEnabled,
                onSoundEnabledChange = { enabled ->
                    soundEnabled = enabled
                    soundManager.isSoundAndHapticEnabled = enabled
                    if (enabled) {
                        soundManager.playTargetLockOn()
                    }
                },
                onTestSoundEffects = {
                    soundManager.isSoundAndHapticEnabled = true
                    soundEnabled = true
                    soundManager.playCriticalHit()
                    Toast.makeText(
                        context,
                        "P5 合成器音效已触发！(暴击和弦 + 清脆震动)",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onSaveConfiguration = {
                    val parsedSteps = maxStepsText.toIntOrNull()?.coerceIn(1, 100) ?: 25
                    val parsedDelay = stepDelayText.toLongOrNull()?.coerceIn(400L, 10000L) ?: 1500L
                    maxStepsText = parsedSteps.toString()
                    stepDelayText = parsedDelay.toString()

                    prefs.edit()
                        .putString(AgentAccessibilityService.KEY_API_KEY, apiKey.trim())
                        .putString(
                            AgentAccessibilityService.KEY_MODEL_NAME,
                            modelName.trim().ifEmpty { GeminiBrainClient.DEFAULT_MODEL }
                        )
                        .putInt(AgentAccessibilityService.KEY_MAX_STEPS, parsedSteps)
                        .putLong(AgentAccessibilityService.KEY_STEP_DELAY_MS, parsedDelay)
                        .putBoolean(P5SoundAndHapticManager.KEY_SOUND_ENABLED, soundEnabled)
                        .apply()

                    soundManager.playAllOutComplete()
                    Toast.makeText(
                        context,
                        "SHOWTIME！怪盗战术装备参数已持久化保存！",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )

            // 5. 怪盗基地直接发起突袭任务面板
            DirectMissionDispatchCard(
                missionText = quickMissionText,
                onMissionTextChange = { quickMissionText = it },
                onLaunchNow = {
                    // 先自动保存当前填写的 API Key 配置
                    prefs.edit()
                        .putString(AgentAccessibilityService.KEY_API_KEY, apiKey.trim())
                        .putString(
                            AgentAccessibilityService.KEY_MODEL_NAME,
                            modelName.trim().ifEmpty { GeminiBrainClient.DEFAULT_MODEL }
                        )
                        .apply()

                    val service = AgentAccessibilityService.instance
                    if (service == null) {
                        soundManager.playCancelOrAlert()
                        Toast.makeText(
                            context,
                            "请先开启【潜入通道 1：无障碍服务】后再发动总攻击！",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        soundManager.playMenuChainSlash()
                        service.startAutonomousMission(quickMissionText)
                    }
                }
            )

            Spacer(modifier = Modifier.height(36.dp))
        }
    }
}

/**
 * 1. 顶部动态 Banner：黑红斜切大标题 "PHANTOM AGENT // VER 1.6"
 */
@Composable
private fun PhantomTopHeroBanner() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        P5RansomNoteTitle(
            text = "PHANTOM AGENT",
            baseFontSize = 23.sp,
            tiltAngle = -4.5f
        )
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .p5SkewedStyle(rotationDegrees = -3.5f, skewXFactor = -0.15f)
                .drawBehind {
                    val p = buildP5PolygonPath(
                        width = size.width,
                        height = size.height,
                        topSlantPx = 5f,
                        bottomSlantPx = 4f
                    )
                    drawPath(p, P5Colors.ShadowBlack)
                    drawPath(p, P5Colors.AllOutYellow, style = Stroke(width = 3.5f))
                }
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = "VER 1.6 // REDMI K80 ULTRA • ANDROID 16 NON-ROOT NAVIGATOR",
                color = P5Colors.AllOutYellow,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                fontStyle = FontStyle.Italic
            )
        }
    }
}

/**
 * 2. 潜入权限状态自检面板（带 PASS / LOCKED 倾斜印章视觉效果）
 */
@Composable
private fun InfiltrationPermissionsCard(
    isAccessibilityPassed: Boolean,
    isBatteryPassed: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestBatteryWhitelist: () -> Unit
) {
    P5SkewedCard(
        modifier = Modifier.fillMaxWidth(),
        fillColor = P5Colors.ShadowBlack,
        shadowColor = P5Colors.PhantomCrimson,
        borderColor = P5Colors.StarkWhite
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "INFILTRATION ROUTE // 潜入通道状态自检",
                color = P5Colors.PhantomCrimson,
                fontSize = 16.sp,
                fontWeight = FontWeight.Black,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.p5SkewedStyle(rotationDegrees = -3f)
            )

            // 潜入通道 1：无障碍服务
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(P5Colors.VelvetCharcoal)
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "【潜入通道 1】无障碍视觉与手势服务",
                            color = P5Colors.StarkWhite,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = "提供免 Root 静默截屏 (SoM)、UI 节点读取、手势点击与音量减键紧急熔断。",
                            color = Color.LightGray,
                            fontSize = 11.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    P5StatusStamp(isPassed = isAccessibilityPassed)
                }
                Spacer(modifier = Modifier.height(10.dp))
                P5SkewedButton(
                    text = if (isAccessibilityPassed) "已开启 // 重新检查无障碍设置" else "立即开启无障碍服务 ->",
                    onClick = onOpenAccessibilitySettings,
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = if (isAccessibilityPassed) P5Colors.VelvetCharcoal else P5Colors.PhantomCrimson
                )
            }

            // 潜入通道 2：电池优化白名单
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(P5Colors.VelvetCharcoal)
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "【潜入通道 2】忽略电池优化白名单",
                            color = P5Colors.StarkWhite,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = "防止天玑 9400+ 与 HyperOS 3 在跨应用跳转时冻结 AI 协程网络请求。",
                            color = Color.LightGray,
                            fontSize = 11.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    P5StatusStamp(isPassed = isBatteryPassed)
                }
                Spacer(modifier = Modifier.height(10.dp))
                P5SkewedButton(
                    text = if (isBatteryPassed) "已加入电池白名单 (PASS)" else "一键申请加入电池白名单 ->",
                    onClick = onRequestBatteryWhitelist,
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = if (isBatteryPassed) P5Colors.VelvetCharcoal else P5Colors.PhantomCrimson
                )
            }
        }
    }
}

/**
 * 3. 小米 HyperOS 3 (Android 16) 专属潜入破解指南卡片 + 一键跳转应用详情页
 */
@Composable
private fun HyperOS3TacticalBriefingCard(
    onOpenAppDetails: () -> Unit
) {
    P5SkewedCard(
        modifier = Modifier.fillMaxWidth(),
        fillColor = P5Colors.ShadowBlack,
        shadowColor = P5Colors.AllOutYellow,
        borderColor = P5Colors.PhantomCrimson
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "HYPEROS 3 BRIEFING // 小米 Android 16 潜入破解指南",
                color = P5Colors.AllOutYellow,
                fontSize = 15.sp,
                fontWeight = FontWeight.Black,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.p5SkewedStyle(rotationDegrees = -3.5f)
            )

            Text(
                text = "在未 Root 的红米 K80 至尊版 (HyperOS 3 / Android 16) 上，请点击下方按钮进入【应用详情页】完成以下三项怪盗破壁授权：",
                color = P5Colors.StarkWhite,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )

            BriefingStepItem(
                stepBadge = "STEP 01",
                title = "解除 Android 16 侧载“允许受限设置”",
                desc = "进入【应用详情页】-> 滑到最底部或点击右上角菜单 (⋮) -> 点击【允许受限设置 (Allow restricted settings)】并验证锁屏密码，否则无障碍开关会置灰无法打开！"
            )

            BriefingStepItem(
                stepBadge = "STEP 02",
                title = "开启小米“后台弹出界面”与“显示悬浮窗”",
                desc = "在【应用详情 -> 权限管理 -> 其他权限】中，将【后台弹出界面】和【显示悬浮窗】均设为允许，确保 AI 能在后台秒开微信/淘宝/B站等目标应用。"
            )

            BriefingStepItem(
                stepBadge = "STEP 03",
                title = "开启“自启动”与省电策略“无限制”",
                desc = "在【应用详情】中打开【自启动】，并将【省电策略】设为【无限制】，彻底防止 HyperOS 3 杀后台导致无障碍悬浮窗消失。"
            )

            Spacer(modifier = Modifier.height(4.dp))

            P5SkewedButton(
                text = "★ 一键潜入【本应用详情页】完成三项授权 ★",
                onClick = onOpenAppDetails,
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = P5Colors.AllOutYellow,
                textColor = P5Colors.ShadowBlack,
                shadowColor = P5Colors.PhantomCrimson
            )
        }
    }
}

@Composable
private fun BriefingStepItem(
    stepBadge: String,
    title: String,
    desc: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(P5Colors.VelvetCharcoal)
            .padding(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer { rotationZ = -6f }
                .background(P5Colors.PhantomCrimson)
                .padding(horizontal = 6.dp, vertical = 3.dp)
        ) {
            Text(
                text = stepBadge,
                color = P5Colors.StarkWhite,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = P5Colors.AllOutYellow,
                fontSize = 13.sp,
                fontWeight = FontWeight.Black
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = desc,
                color = P5Colors.StarkWhite,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }
    }
}

/**
 * 4. 战术装备配置区（P5 倾斜多边形卡片）
 */
@Composable
private fun TacticalGearConfigCard(
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    showApiKeyPlain: Boolean,
    onToggleShowApiKey: () -> Unit,
    modelName: String,
    onModelNameChange: (String) -> Unit,
    maxStepsText: String,
    onMaxStepsChange: (String) -> Unit,
    stepDelayText: String,
    onStepDelayChange: (String) -> Unit,
    soundEnabled: Boolean,
    onSoundEnabledChange: (Boolean) -> Unit,
    onTestSoundEffects: () -> Unit,
    onSaveConfiguration: () -> Unit
) {
    P5SkewedCard(
        modifier = Modifier.fillMaxWidth(),
        fillColor = P5Colors.ShadowBlack,
        shadowColor = P5Colors.PhantomCrimson,
        borderColor = P5Colors.StarkWhite
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "TACTICAL GEAR // 怪盗团战术装备配置",
                color = P5Colors.PhantomCrimson,
                fontSize = 16.sp,
                fontWeight = FontWeight.Black,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.p5SkewedStyle(rotationDegrees = -3f)
            )

            // Gemini API Key 输入框
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "GEMINI API KEY (Google AI Studio 密钥)",
                        color = P5Colors.AllOutYellow,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = if (showApiKeyPlain) "[隐藏密钥]" else "[显示明文]",
                        color = P5Colors.PhantomCrimson,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.clickable(onClick = onToggleShowApiKey)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                P5SkewedTextField(
                    value = apiKey,
                    onValueChange = onApiKeyChange,
                    placeholder = "粘贴 AIzaSy... 开头的 Gemini API Key",
                    isPassword = !showApiKeyPlain
                )
            }

            // 模型名称输入
            Column {
                Text(
                    text = "GEMINI MULTIMODAL MODEL (默认 gemini-2.5-flash)",
                    color = P5Colors.AllOutYellow,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black
                )
                Spacer(modifier = Modifier.height(4.dp))
                P5SkewedTextField(
                    value = modelName,
                    onValueChange = onModelNameChange,
                    placeholder = "gemini-2.5-flash"
                )
            }

            // 最大行动回合数 & 每回合冷却延迟
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "最大回合数 (默认 25)",
                        color = P5Colors.StarkWhite,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    P5SkewedTextField(
                        value = maxStepsText,
                        onValueChange = onMaxStepsChange,
                        placeholder = "25",
                        keyboardType = KeyboardType.Number
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "回合冷却 ms (默认 1500)",
                        color = P5Colors.StarkWhite,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    P5SkewedTextField(
                        value = stepDelayText,
                        onValueChange = onStepDelayChange,
                        placeholder = "1500",
                        keyboardType = KeyboardType.Number
                    )
                }
            }

            // P5 音效与震动反馈总开关 + 试听音效按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(P5Colors.VelvetCharcoal)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "P5 纯算法合成音效 & 清脆震动",
                        color = P5Colors.StarkWhite,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = "刀锋菜单音 / 准星锁定 / 暴击重音 / 胜利琶音",
                        color = Color.LightGray,
                        fontSize = 10.sp
                    )
                }
                Switch(
                    checked = soundEnabled,
                    onCheckedChange = onSoundEnabledChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = P5Colors.AllOutYellow,
                        checkedTrackColor = P5Colors.PhantomCrimson
                    )
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                P5SkewedButton(
                    text = "♪ 试听 P5 音效",
                    onClick = onTestSoundEffects,
                    modifier = Modifier.weight(1f),
                    backgroundColor = P5Colors.VelvetCharcoal,
                    textColor = P5Colors.AllOutYellow,
                    shadowColor = P5Colors.PhantomCrimson
                )
                P5SkewedButton(
                    text = "SAVE GEAR (保存装备)",
                    onClick = onSaveConfiguration,
                    modifier = Modifier.weight(1.2f),
                    backgroundColor = P5Colors.PhantomCrimson,
                    textColor = P5Colors.StarkWhite,
                    accentSeed = true
                )
            }
        }
    }
}

/**
 * 5. 怪盗基地直接发起突袭任务面板
 */
@Composable
private fun DirectMissionDispatchCard(
    missionText: String,
    onMissionTextChange: (String) -> Unit,
    onLaunchNow: () -> Unit
) {
    P5SkewedCard(
        modifier = Modifier.fillMaxWidth(),
        fillColor = P5Colors.ShadowBlack,
        shadowColor = P5Colors.StarkWhite,
        borderColor = P5Colors.PhantomCrimson
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "SEND CALLING CARD // 立即发送预告信执行任务",
                color = P5Colors.AllOutYellow,
                fontSize = 15.sp,
                fontWeight = FontWeight.Black,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.p5SkewedStyle(rotationDegrees = -3f)
            )

            Text(
                text = "开启无障碍服务后，屏幕边缘会出现黑红五角星悬浮徽章。你也可以直接在下方输入指令立即出击（运行中按【音量减键】可随时熔断终止）：",
                color = P5Colors.StarkWhite,
                fontSize = 11.sp
            )

            P5SkewedTextField(
                value = missionText,
                onValueChange = onMissionTextChange,
                placeholder = "输入自动化任务目标..."
            )

            P5SkewedButton(
                text = "TAKE YOUR HEART // 立即启动全自动潜入",
                onClick = onLaunchNow,
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = P5Colors.PhantomCrimson,
                textColor = P5Colors.StarkWhite,
                accentSeed = true
            )
        }
    }
}

/**
 * 自定义 P5 斜切多边形输入框 (严禁使用普通 RoundedCornerShape)
 */
@Composable
private fun P5SkewedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val path = buildP5PolygonPath(
                    width = size.width,
                    height = size.height,
                    topLeftCutRatio = 0.03f,
                    bottomRightCutRatio = 0.04f,
                    topSlantPx = 5f,
                    bottomSlantPx = 4f
                )
                drawPath(path, P5Colors.VelvetCharcoal)
                drawPath(path, P5Colors.StarkWhite, style = Stroke(width = 3f))
            }
            .padding(horizontal = 14.dp, vertical = 11.dp)
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(
                color = P5Colors.StarkWhite,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            ),
            cursorBrush = SolidColor(P5Colors.PhantomCrimson),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { innerTextField ->
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }
                innerTextField()
            }
        )
    }
}

private fun checkAccessibilityEnabled(context: Context): Boolean {
    if (AgentAccessibilityService.instance != null) return true
    return try {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        enabledServices.any { info ->
            info.resolveInfo?.serviceInfo?.packageName == context.packageName &&
                    info.resolveInfo?.serviceInfo?.name?.contains("AgentAccessibilityService") == true
        }
    } catch (_: Exception) {
        false
    }
}

private fun checkBatteryOptimizationIgnored(context: Context): Boolean {
    return try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } catch (_: Exception) {
        false
    }
}

private fun openAccessibilitySettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(context, "无法打开无障碍设置页", Toast.LENGTH_SHORT).show()
    }
}

private fun requestIgnoreBatteryOptimizations(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        openApplicationDetailsSettings(context)
    }
}

private fun openApplicationDetailsSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(context, "无法打开应用详情页", Toast.LENGTH_SHORT).show()
    }
}
