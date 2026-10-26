package com.k80.geminiagent

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Persona 5 纯代码实时合成器音效与清脆震动引擎 (Procedural Synth Audio & Haptics)
 * 零外部 .mp3 / .wav 依赖，100% 通过 AudioTrack (44100Hz, PCM_16BIT, MODE_STATIC) 算法实时合成波形。
 */
class P5SoundAndHapticManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val synthScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    var isSoundAndHapticEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SOUND_ENABLED, value).apply()
        }

    /**
     * 1. 打开面板 / 点击按钮音效 (playMenuChainSlash)
     * 高频金属白噪声包络 + 快速下潜的方波扫频 (约 90ms)，模拟 P5 翻动菜单时的锁链与刀锋切音。
     */
    fun playMenuChainSlash() {
        if (!isSoundAndHapticEnabled) return
        triggerPredefinedHaptic(VibrationEffect.EFFECT_CLICK)
        synthScope.launch {
            val durationMs = 90
            val numSamples = (SAMPLE_RATE * durationMs) / 1000
            val pcm = ShortArray(numSamples)
            var phase = 0.0
            val rand = Random(42)

            for (i in 0 until numSamples) {
                val progress = i.toDouble() / numSamples
                // 指数衰减包络
                val envelope = exp(-progress * 5.2)
                // 快速下潜方波扫频 (2600Hz -> 380Hz)
                val freq = 2600.0 - (2220.0 * progress)
                phase += (2.0 * PI * freq) / SAMPLE_RATE
                val squareWave = if (sin(phase) >= 0.0) 0.55 else -0.55
                // 高频金属感白噪声调制
                val metallicNoise = (rand.nextDouble() * 2.0 - 1.0) * 0.45 * exp(-progress * 3.5)
                val mixed = (squareWave + metallicNoise) * envelope
                pcm[i] = (mixed.coerceIn(-1.0, 1.0) * Short.MAX_VALUE * 0.75).toInt().toShort()
            }
            playStaticPcmBuffer(pcm)
        }
    }

    /**
     * 2. AI 识别屏幕完成 / 锁定坐标音效 (playTargetLockOn)
     * 清脆的双音高八度电子合成音 (B5 987.77Hz -> E6 1318.51Hz 快速跳音，约 120ms)。
     */
    fun playTargetLockOn() {
        if (!isSoundAndHapticEnabled) return
        triggerPredefinedHaptic(VibrationEffect.EFFECT_TICK)
        synthScope.launch {
            val durationMs = 120
            val numSamples = (SAMPLE_RATE * durationMs) / 1000
            val pcm = ShortArray(numSamples)
            var phase1 = 0.0
            var phase2 = 0.0

            for (i in 0 until numSamples) {
                val progress = i.toDouble() / numSamples
                // 前半段 B5 (987.77Hz)，后半段跃升至 E6 (1318.51Hz)
                val baseFreq = if (progress < 0.45) 987.77 else 1318.51
                val localProgress = if (progress < 0.45) progress / 0.45 else (progress - 0.45) / 0.55
                val env = (1.0 - localProgress * 0.65)

                phase1 += (2.0 * PI * baseFreq) / SAMPLE_RATE
                phase2 += (2.0 * PI * (baseFreq * 2.0)) / SAMPLE_RATE

                // 叠加基频正弦波 + 高八度泛音 + 轻度方波谐波
                val tone = sin(phase1) * 0.65 + sin(phase2) * 0.25 + (if (sin(phase1) > 0) 0.1 else -0.1)
                val sample = tone * env
                pcm[i] = (sample.coerceIn(-1.0, 1.0) * Short.MAX_VALUE * 0.72).toInt().toShort()
            }
            playStaticPcmBuffer(pcm)
        }
    }

    /**
     * 3. AI 执行点击 / 滑动瞬间暴击音效 (playCriticalHit)
     * 极具打击感的低频冲击波 (190Hz -> 52Hz) 叠加高频清脆和弦音与瞬态爆破 (约 150ms)。
     */
    fun playCriticalHit() {
        if (!isSoundAndHapticEnabled) return
        triggerPredefinedHaptic(VibrationEffect.EFFECT_HEAVY_CLICK)
        synthScope.launch {
            val durationMs = 150
            val numSamples = (SAMPLE_RATE * durationMs) / 1000
            val pcm = ShortArray(numSamples)
            var subPhase = 0.0
            var chordPhaseA = 0.0
            var chordPhaseB = 0.0
            var chordPhaseC = 0.0
            val rand = Random(105)

            for (i in 0 until numSamples) {
                val progress = i.toDouble() / numSamples
                // 低频重击下潜
                val subFreq = 210.0 * exp(-progress * 4.5) + 45.0
                subPhase += (2.0 * PI * subFreq) / SAMPLE_RATE
                val subWave = sin(subPhase) * exp(-progress * 3.2)

                // 高频怪盗风格锐利三和弦 (E6 + G#6 + B6)
                chordPhaseA += (2.0 * PI * 1318.5) / SAMPLE_RATE
                chordPhaseB += (2.0 * PI * 1661.2) / SAMPLE_RATE
                chordPhaseC += (2.0 * PI * 1975.5) / SAMPLE_RATE
                val chordEnv = exp(-progress * 6.5)
                val chordWave = ((sin(chordPhaseA) + sin(chordPhaseB) + sin(chordPhaseC)) / 3.0) * chordEnv

                // 开头 25ms 刀光瞬态冲击噪声
                val transientNoise = if (progress < 0.18) {
                    (rand.nextDouble() * 2.0 - 1.0) * (1.0 - progress / 0.18) * 0.5
                } else 0.0

                val mixed = (subWave * 0.65 + chordWave * 0.45 + transientNoise).coerceIn(-1.0, 1.0)
                pcm[i] = (mixed * Short.MAX_VALUE * 0.85).toInt().toShort()
            }
            playStaticPcmBuffer(pcm)
        }
    }

    /**
     * 4. 任务圆满完成音效 (playAllOutComplete)
     * 激昂欢快的 P5 风格大七和弦琶音上行 (Arpeggio Up: E5 -> G#5 -> B5 -> D#6 -> E6，约 450ms)。
     */
    fun playAllOutComplete() {
        if (!isSoundAndHapticEnabled) return
        triggerPredefinedHaptic(VibrationEffect.EFFECT_HEAVY_CLICK)
        synthScope.launch {
            val durationMs = 450
            val numSamples = (SAMPLE_RATE * durationMs) / 1000
            val pcm = ShortArray(numSamples)
            val notes = doubleArrayOf(659.25, 830.61, 987.77, 1244.51, 1318.51)
            var phase = 0.0
            var octavePhase = 0.0

            for (i in 0 until numSamples) {
                val progress = i.toDouble() / numSamples
                val noteIndex = (progress * notes.size).toInt().coerceIn(0, notes.lastIndex)
                val freq = notes[noteIndex]

                val segmentLen = 1.0 / notes.size
                val localPos = (progress - noteIndex * segmentLen) / segmentLen
                val env = if (noteIndex == notes.lastIndex) {
                    exp(-localPos * 2.2)
                } else {
                    exp(-localPos * 3.0)
                }

                phase += (2.0 * PI * freq) / SAMPLE_RATE
                octavePhase += (2.0 * PI * freq * 2.0) / SAMPLE_RATE

                // 温暖合成器锯齿/正弦混合音色
                val synth = sin(phase) * 0.65 + sin(octavePhase) * 0.25 + (if (sin(phase) >= 0) 0.15 else -0.15)
                val out = (synth * env).coerceIn(-1.0, 1.0)
                pcm[i] = (out * Short.MAX_VALUE * 0.78).toInt().toShort()
            }
            playStaticPcmBuffer(pcm)
        }
    }

    /**
     * 5. 按下音量减键紧急中断 / 报错音效 (playCancelOrAlert)
     * 低沉失真的下行警告双音 (F4 349.23Hz -> B3 246.94Hz 三全音减五度警戒音，约 200ms)。
     */
    fun playCancelOrAlert() {
        if (!isSoundAndHapticEnabled) return
        triggerPredefinedHaptic(VibrationEffect.EFFECT_HEAVY_CLICK)
        synthScope.launch {
            val durationMs = 200
            val numSamples = (SAMPLE_RATE * durationMs) / 1000
            val pcm = ShortArray(numSamples)
            var phase = 0.0

            for (i in 0 until numSamples) {
                val progress = i.toDouble() / numSamples
                val freq = if (progress < 0.48) 349.23 else 246.94
                val localProgress = if (progress < 0.48) progress / 0.48 else (progress - 0.48) / 0.52
                val env = (1.0 - localProgress * 0.55)

                phase += (2.0 * PI * freq) / SAMPLE_RATE
                // 过载方波失真音色
                val raw = sin(phase) * 2.4
                val distorted = raw.coerceIn(-0.85, 0.85)
                val sample = distorted * env
                pcm[i] = (sample.coerceIn(-1.0, 1.0) * Short.MAX_VALUE * 0.80).toInt().toShort()
            }
            playStaticPcmBuffer(pcm)
        }
    }

    private fun triggerPredefinedHaptic(effectId: Int) {
        try {
            val vib = vibrator ?: return
            if (!vib.hasVibrator()) return
            vib.vibrate(VibrationEffect.createPredefined(effectId))
        } catch (_: Exception) {
            // 忽略部分定制系统震动权限异常
        }
    }

    private fun playStaticPcmBuffer(pcmData: ShortArray) {
        var audioTrack: AudioTrack? = null
        try {
            val byteBufferSize = pcmData.size * 2
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(byteBufferSize)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            audioTrack.write(pcmData, 0, pcmData.size)
            audioTrack.play()

            val sleepMs = ((pcmData.size * 1000L) / SAMPLE_RATE) + 45L
            Thread.sleep(sleepMs)
        } catch (_: Exception) {
            // 防止音频焦点争抢崩溃
        } finally {
            try {
                audioTrack?.stop()
            } catch (_: Exception) {
            }
            try {
                audioTrack?.release()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val SAMPLE_RATE = 44100
        const val PREFS_NAME = "p5_phantom_agent_prefs"
        const val KEY_SOUND_ENABLED = "p5_sound_haptic_enabled"
    }
}
