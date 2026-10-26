package com.k80.geminiagent

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Persona 5 (P5) 殿堂级视觉美学系统与纯代码几何绘制引擎
 * 零外部素材依赖、严禁使用 RoundedCornerShape，100% 纯代码 Path 与 Canvas 矢量合成。
 */
object P5Colors {
    val PhantomCrimson = Color(0xFFE60012)     // 怪盗红 (主强调色、切块背景)
    val CrimsonDark = Color(0xFF8A000B)        // 暗调深红 (半色调波点与条纹色)
    val ShadowBlack = Color(0xFF0A0A0A)        // 暗影黑 (主背景、倾斜卡片底色)
    val VelvetCharcoal = Color(0xFF161616)     // 丝绒深灰 (次级容器填充)
    val StarkWhite = Color(0xFFFFFFFF)         // 纯净白 (文字、错位双层硬阴影、内衬切角)
    val AllOutYellow = Color(0xFFFFE600)       // 警戒黄 (总攻击高亮、关键选中态)
    val MorganaCyan = Color(0xFF00F0FF)        // 导航青 (战术分析数据流点缀)
    val PassGreen = Color(0xFF00FF66)          // 潜入通道开启印章色
}

private val P5ColorScheme = darkColorScheme(
    primary = P5Colors.PhantomCrimson,
    onPrimary = P5Colors.StarkWhite,
    secondary = P5Colors.AllOutYellow,
    onSecondary = P5Colors.ShadowBlack,
    background = P5Colors.ShadowBlack,
    onBackground = P5Colors.StarkWhite,
    surface = P5Colors.VelvetCharcoal,
    onSurface = P5Colors.StarkWhite,
    error = P5Colors.PhantomCrimson,
    onError = P5Colors.StarkWhite
)

@Composable
fun P5PhantomTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = P5ColorScheme,
        content = content
    )
}

/**
 * 自定义 P5 不规则倾斜切角多边形 (P5SkewedPolygonShape)
 * 彻底取代任何圆角矩形，四角带有不对称锐利斜切与倾斜张力。
 */
class P5SkewedPolygonShape(
    private val topLeftCutRatio: Float = 0.04f,
    private val topRightInsetRatio: Float = 0.03f,
    private val bottomRightCutRatio: Float = 0.05f,
    private val bottomLeftInsetRatio: Float = 0.02f,
    private val topSlantPx: Float = 10f,
    private val bottomSlantPx: Float = 8f
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val path = buildP5PolygonPath(
            width = size.width,
            height = size.height,
            topLeftCutRatio = topLeftCutRatio,
            topRightInsetRatio = topRightInsetRatio,
            bottomRightCutRatio = bottomRightCutRatio,
            bottomLeftInsetRatio = bottomLeftInsetRatio,
            topSlantPx = topSlantPx,
            bottomSlantPx = bottomSlantPx
        )
        return Outline.Generic(path)
    }
}

fun buildP5PolygonPath(
    width: Float,
    height: Float,
    topLeftCutRatio: Float = 0.04f,
    topRightInsetRatio: Float = 0.03f,
    bottomRightCutRatio: Float = 0.05f,
    bottomLeftInsetRatio: Float = 0.02f,
    topSlantPx: Float = 10f,
    bottomSlantPx: Float = 8f
): Path {
    val w = width.coerceAtLeast(10f)
    val h = height.coerceAtLeast(10f)
    val tlCut = w * topLeftCutRatio
    val trInset = w * topRightInsetRatio
    val brCut = w * bottomRightCutRatio
    val blInset = w * bottomLeftInsetRatio

    return Path().apply {
        // 左上角锐利切角起点
        moveTo(tlCut, topSlantPx)
        // 顶边向右上方倾斜
        lineTo(w - trInset, 0f)
        // 右上不对称折角
        lineTo(w, h * 0.16f)
        // 右下锐利切角
        lineTo(w - brCut * 0.45f, h - bottomSlantPx)
        // 底边向左下方微倾
        lineTo(w - brCut, h)
        // 左下折角
        lineTo(blInset, h - bottomSlantPx * 0.6f)
        // 左侧斜切回起点
        lineTo(0f, topSlantPx + h * 0.18f)
        close()
    }
}

/**
 * 构建 P5 漫画锯齿气泡路径 (Comic Speech Bubble)
 * 用于悬浮窗思考态与摩尔加纳/双叶战术播报框。
 */
fun buildP5ComicSpeechBubblePath(
    width: Float,
    height: Float,
    tailOnLeft: Boolean = true
): Path {
    val w = width.coerceAtLeast(40f)
    val h = height.coerceAtLeast(40f)
    val bodyBottom = h * 0.84f
    return Path().apply {
        moveTo(w * 0.05f, h * 0.08f)
        // 顶部凌厉折线
        lineTo(w * 0.52f, 0f)
        lineTo(w * 0.55f, h * 0.05f)
        lineTo(w * 0.98f, h * 0.02f)
        // 右侧锯齿边
        lineTo(w * 0.94f, bodyBottom * 0.48f)
        lineTo(w, bodyBottom * 0.56f)
        lineTo(w * 0.95f, bodyBottom)
        if (tailOnLeft) {
            lineTo(w * 0.34f, bodyBottom * 0.96f)
            // 漫画闪电状尖尾巴
            lineTo(w * 0.22f, h * 0.93f)
            lineTo(w * 0.28f, h * 0.90f)
            lineTo(w * 0.08f, h)
            lineTo(w * 0.18f, bodyBottom * 0.88f)
            lineTo(w * 0.13f, bodyBottom * 0.90f)
            lineTo(w * 0.03f, bodyBottom * 0.94f)
        } else {
            lineTo(w * 0.82f, bodyBottom * 0.95f)
            lineTo(w * 0.92f, h)
            lineTo(w * 0.72f, bodyBottom * 0.94f)
            lineTo(w * 0.03f, bodyBottom * 0.96f)
        }
        // 左侧折角回闭合
        lineTo(0f, bodyBottom * 0.42f)
        close()
    }
}

/**
 * P5 倾斜剪报风排版修饰符 (-4度 ~ -8度旋转 + X轴斜切变换)
 */
fun Modifier.p5SkewedStyle(
    rotationDegrees: Float = -5f,
    skewXFactor: Float = -0.15f
): Modifier = this.graphicsLayer {
    rotationZ = rotationDegrees
    // 通过 4x4 仿射矩阵实现水平斜切或透视张力
    translationX = skewXFactor * 12f
}

/**
 * P5 绑架信剪报风格标题 (Ransom Note Typography)
 * 首字母与偶数关键字符放大并套用红底白字 / 白底黑字错位剪报框。
 */
@Composable
fun P5RansomNoteTitle(
    text: String,
    modifier: Modifier = Modifier,
    baseFontSize: TextUnit = 22.sp,
    tiltAngle: Float = -5f
) {
    Row(
        modifier = modifier.graphicsLayer {
            rotationZ = tiltAngle
        },
        verticalAlignment = Alignment.CenterVertically
    ) {
        text.forEachIndexed { index, char ->
            if (char == ' ') {
                Spacer(modifier = Modifier.width(6.dp))
            } else {
                val isFirstOrAccent = index == 0 || index % 4 == 0
                val isWhiteBox = index % 3 == 1
                val bgColor = when {
                    isFirstOrAccent -> P5Colors.PhantomCrimson
                    isWhiteBox -> P5Colors.StarkWhite
                    else -> P5Colors.ShadowBlack
                }
                val txtColor = when {
                    isWhiteBox -> P5Colors.ShadowBlack
                    else -> P5Colors.StarkWhite
                }
                val charTilt = if (index % 2 == 0) -4f else 5f
                val fontSize = if (index == 0) (baseFontSize.value + 5f).sp else baseFontSize

                Box(
                    modifier = Modifier
                        .padding(horizontal = 1.dp)
                        .graphicsLayer { rotationZ = charTilt }
                        .drawBehind {
                            val boxPath = buildP5PolygonPath(
                                width = size.width,
                                height = size.height,
                                topLeftCutRatio = 0.08f,
                                bottomRightCutRatio = 0.08f,
                                topSlantPx = 3f,
                                bottomSlantPx = 2f
                            )
                            // 错位硬阴影
                            translate(left = 3f, top = 3f) {
                                drawPath(
                                    path = boxPath,
                                    color = if (bgColor == P5Colors.StarkWhite) P5Colors.PhantomCrimson else P5Colors.StarkWhite
                                )
                            }
                            drawPath(path = boxPath, color = bgColor)
                            drawPath(
                                path = boxPath,
                                color = P5Colors.StarkWhite,
                                style = Stroke(width = 2f)
                            )
                        }
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = char.toString(),
                        color = txtColor,
                        fontSize = fontSize,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.SansSerif
                    )
                }
            }
        }
    }
}

/**
 * P5 倾斜多边形双层错位硬阴影卡片 (Offset Drop Shadow Skewed Card)
 */
@Composable
fun P5SkewedCard(
    modifier: Modifier = Modifier,
    fillColor: Color = P5Colors.ShadowBlack,
    shadowColor: Color = P5Colors.PhantomCrimson,
    borderColor: Color = P5Colors.StarkWhite,
    shadowOffsetX: Dp = 7.dp,
    shadowOffsetY: Dp = 7.dp,
    contentPadding: Dp = 14.dp,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .drawBehind {
                val ox = shadowOffsetX.toPx()
                val oy = shadowOffsetY.toPx()
                val mainPath = buildP5PolygonPath(
                    width = size.width,
                    height = size.height,
                    topLeftCutRatio = 0.03f,
                    topRightInsetRatio = 0.02f,
                    bottomRightCutRatio = 0.04f,
                    topSlantPx = 8f,
                    bottomSlantPx = 6f
                )
                // 第一层：纯白外扩错位硬阴影
                val outerShadowMatrix = Matrix().apply { translate(ox + 4f, oy + 4f) }
                val outerPath = Path().apply {
                    addPath(mainPath)
                    transform(outerShadowMatrix)
                }
                drawPath(path = outerPath, color = P5Colors.StarkWhite)

                // 第二层：怪盗红/指定色错位硬阴影
                val innerShadowMatrix = Matrix().apply { translate(ox, oy) }
                val innerPath = Path().apply {
                    addPath(mainPath)
                    transform(innerShadowMatrix)
                }
                drawPath(path = innerPath, color = shadowColor)

                // 第三层：暗影黑主填充体
                drawPath(path = mainPath, color = fillColor)

                // 第四层：锐利双层描边
                drawPath(
                    path = mainPath,
                    color = borderColor,
                    style = Stroke(width = 4.5f, join = StrokeJoin.Miter)
                )
            }
            .padding(contentPadding),
        content = content
    )
}

/**
 * P5 凌厉斜切战术按钮 (带 144Hz 弹簧缩放与刀光高光反馈)
 */
@Composable
fun P5SkewedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backgroundColor: Color = P5Colors.PhantomCrimson,
    textColor: Color = P5Colors.StarkWhite,
    shadowColor: Color = P5Colors.StarkWhite,
    accentSeed: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.93f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "p5BtnScale"
    )
    val tilt by animateFloatAsState(
        targetValue = if (isPressed) -6.5f else -3.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy),
        label = "p5BtnTilt"
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                rotationZ = tilt
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .drawBehind {
                val path = buildP5PolygonPath(
                    width = size.width,
                    height = size.height,
                    topLeftCutRatio = 0.06f,
                    bottomRightCutRatio = 0.07f,
                    topSlantPx = 7f,
                    bottomSlantPx = 5f
                )
                val shadowShift = if (isPressed) 3f else 8f
                val shadowPath = Path().apply {
                    addPath(path)
                    transform(Matrix().apply { translate(shadowShift, shadowShift) })
                }
                drawPath(path = shadowPath, color = shadowColor)
                drawPath(
                    path = path,
                    color = if (isPressed) P5Colors.AllOutYellow else backgroundColor
                )
                drawPath(
                    path = path,
                    color = P5Colors.ShadowBlack,
                    style = Stroke(width = 4f)
                )
                // 左上角装饰性四角星
                if (accentSeed) {
                    drawP5FourPointStar(
                        center = Offset(24f, size.height * 0.35f),
                        outerRadius = 11f,
                        innerRadius = 3.5f,
                        color = if (isPressed) P5Colors.ShadowBlack else P5Colors.AllOutYellow,
                        rotationDeg = 18f
                    )
                }
            }
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (isPressed) P5Colors.ShadowBlack else textColor,
            fontWeight = FontWeight.Black,
            fontStyle = FontStyle.Italic,
            fontSize = 15.sp,
            letterSpacing = 0.8.sp
        )
    }
}

/**
 * P5 潜入权限状态倾斜印章 (PASS / LOCKED Stamp)
 */
@Composable
fun P5StatusStamp(
    isPassed: Boolean,
    modifier: Modifier = Modifier
) {
    val stampColor = if (isPassed) P5Colors.AllOutYellow else P5Colors.PhantomCrimson
    val stampText = if (isPassed) "PASS // 潜入就绪" else "LOCKED // 未授权"

    Box(
        modifier = modifier
            .graphicsLayer { rotationZ = -8f }
            .drawBehind {
                val path = buildP5PolygonPath(
                    width = size.width,
                    height = size.height,
                    topLeftCutRatio = 0.08f,
                    bottomRightCutRatio = 0.08f,
                    topSlantPx = 4f,
                    bottomSlantPx = 4f
                )
                drawPath(path = path, color = P5Colors.ShadowBlack)
                drawPath(path = path, color = stampColor, style = Stroke(width = 4f))
            }
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = stampText,
            color = stampColor,
            fontWeight = FontWeight.Black,
            fontSize = 12.sp
        )
    }
}

/**
 * P5 动态旋转红黑半色调波点 + 怪盗团放射状星芒条纹背景
 */
@Composable
fun P5HalftoneStarburstBackground(
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "p5BgAnim")
    val burstRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 28000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "burstRotation"
    )
    val halftoneShift by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 48f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "halftoneShift"
    )
    val starPulse by infiniteTransition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "starPulse"
    )

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(P5Colors.ShadowBlack)
    ) {
        val w = size.width
        val h = size.height
        val center = Offset(w * 0.85f, h * 0.14f)
        val maxRadius = hypot(w, h) * 1.2f

        // 1. 放射状怪盗红暗影条纹
        val rayCount = 16
        rotate(degrees = burstRotation, pivot = center) {
            for (i in 0 until rayCount) {
                val startAngle = (i * (360f / rayCount)) * (PI.toFloat() / 180f)
                val sweepAngle = (11f) * (PI.toFloat() / 180f)
                val rayPath = Path().apply {
                    moveTo(center.x, center.y)
                    lineTo(
                        center.x + cos(startAngle) * maxRadius,
                        center.y + sin(startAngle) * maxRadius
                    )
                    lineTo(
                        center.x + cos(startAngle + sweepAngle) * maxRadius,
                        center.y + sin(startAngle + sweepAngle) * maxRadius
                    )
                    close()
                }
                drawPath(
                    path = rayPath,
                    color = P5Colors.CrimsonDark.copy(alpha = 0.34f)
                )
            }
        }

        // 2. 动态斜向流动的半色调波点阵列 (Halftone Dots)
        val spacing = 48f
        val cols = (w / spacing).toInt() + 3
        val rows = (h / spacing).toInt() + 3
        for (r in -1..rows) {
            for (c in -1..cols) {
                val dx = c * spacing + (if (r % 2 == 0) spacing * 0.5f else 0f) - halftoneShift
                val dy = r * spacing + halftoneShift * 0.5f
                val distNorm = (hypot(dx - center.x, dy - center.y) / maxRadius).coerceIn(0f, 1f)
                val dotRadius = (1f - distNorm * 0.65f) * 9.5f
                if (dotRadius > 1.5f) {
                    drawCircle(
                        color = P5Colors.PhantomCrimson.copy(alpha = 0.22f),
                        radius = dotRadius,
                        center = Offset(dx, dy)
                    )
                }
            }
        }

        // 3. 顶部与底部凌厉斜切装饰色块
        val topSlash = Path().apply {
            moveTo(0f, 0f)
            lineTo(w, 0f)
            lineTo(w, h * 0.08f)
            lineTo(0f, h * 0.14f)
            close()
        }
        drawPath(path = topSlash, color = P5Colors.PhantomCrimson.copy(alpha = 0.85f))

        // 4. 装饰性闪烁四角星与五角星
        drawP5FourPointStar(
            center = Offset(w * 0.88f, h * 0.09f),
            outerRadius = 42f * starPulse,
            innerRadius = 12f * starPulse,
            color = P5Colors.StarkWhite,
            rotationDeg = -15f
        )
        drawP5FourPointStar(
            center = Offset(w * 0.88f, h * 0.09f),
            outerRadius = 26f * starPulse,
            innerRadius = 7f * starPulse,
            color = P5Colors.ShadowBlack,
            rotationDeg = -15f
        )
    }
}

/**
 * 绘制 P5 标志性锐利四角星芒 (4-Point Starburst)
 */
fun DrawScope.drawP5FourPointStar(
    center: Offset,
    outerRadius: Float,
    innerRadius: Float,
    color: Color,
    rotationDeg: Float = 0f
) {
    rotate(degrees = rotationDeg, pivot = center) {
        val path = Path().apply {
            for (i in 0 until 8) {
                val r = if (i % 2 == 0) outerRadius else innerRadius
                val angle = (i * 45.0) * PI / 180.0
                val x = center.x + (r * cos(angle)).toFloat()
                val y = center.y + (r * sin(angle)).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        drawPath(path = path, color = color, style = Fill)
    }
}

/**
 * 绘制 P5 怪盗团不对称五角星徽章 (Phantom Thief Asymmetric 5-Point Star)
 */
fun DrawScope.drawP5PhantomFivePointStar(
    center: Offset,
    outerRadius: Float,
    innerRadius: Float,
    rotationDeg: Float = -12f
) {
    rotate(degrees = rotationDeg, pivot = center) {
        val starPath = Path().apply {
            for (i in 0 until 10) {
                // 让顶角与左下角略微拉长，形成怪盗团标志性的不规则张力五角星
                val stretch = when (i) {
                    0 -> 1.22f
                    4, 6 -> 1.12f
                    else -> 1.0f
                }
                val r = (if (i % 2 == 0) outerRadius else innerRadius) * stretch
                val angle = ((i * 36.0) - 90.0) * PI / 180.0
                val x = center.x + (r * cos(angle)).toFloat()
                val y = center.y + (r * sin(angle)).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }

        // 外扩纯白硬阴影
        val whiteShadow = Path().apply {
            addPath(starPath)
            transform(Matrix().apply { translate(5f, 5f) })
        }
        drawPath(path = whiteShadow, color = P5Colors.StarkWhite)

        // 怪盗红主体
        drawPath(path = starPath, color = P5Colors.PhantomCrimson)

        // 暗影黑内嵌星与纯白凌厉描边
        drawPath(
            path = starPath,
            color = P5Colors.ShadowBlack,
            style = Stroke(width = 6f, join = StrokeJoin.Miter)
        )
        drawPath(
            path = starPath,
            color = P5Colors.StarkWhite,
            style = Stroke(width = 2.5f, join = StrokeJoin.Miter)
        )

        // 中心异世界导航之眼瞳孔
        drawCircle(
            color = P5Colors.ShadowBlack,
            radius = innerRadius * 0.65f,
            center = center
        )
        drawCircle(
            color = P5Colors.AllOutYellow,
            radius = innerRadius * 0.28f,
            center = center
        )
    }
}

/**
 * 悬浮徽章专用 Composable：呼吸旋转的怪盗五角星 / 异世界导航之眼
 */
@Composable
fun P5FloatingStarBadge(
    isRunning: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "badgeBreath")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = if (isRunning) 1.12f else 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isRunning) 420 else 1100,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val slowTilt by infiniteTransition.animateFloat(
        initialValue = -16f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "slowTilt"
    )

    Canvas(
        modifier = modifier
            .size(68.dp)
            .graphicsLayer {
                scaleX = pulseScale
                scaleY = pulseScale
            }
            .clickable(onClick = onClick)
    ) {
        val c = Offset(size.width / 2f, size.height / 2f)
        // 底层警戒冲击环
        if (isRunning) {
            drawCircle(
                color = P5Colors.AllOutYellow.copy(alpha = 0.35f),
                radius = size.minDimension * 0.48f,
                center = c,
                style = Stroke(width = 5f, cap = StrokeCap.Square)
            )
        }
        drawP5PhantomFivePointStar(
            center = c,
            outerRadius = size.minDimension * 0.42f,
            innerRadius = size.minDimension * 0.18f,
            rotationDeg = slowTilt
        )
    }
}
