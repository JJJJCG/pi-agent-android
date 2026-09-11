package com.pi.assistant.data.prefs

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * 语音相关的全部配置。
 *
 * 只支持小米 MiMo 一家 —— 它把语音识别和合成都挂在同一个 `/chat/completions` 上，
 * `base_url` 和 API Key 也是同一份，所以不再分「识别侧 / 朗读侧」两套端点：
 * 一个地址、一个 key 同时管两边。
 *
 * 做成嵌套对象而不是平铺进 [PiSettings]：字段名换了一批，老配置里那些
 * OpenAI / 百炼的模型名不会被带进来。这点很关键 —— 模型名对不上时服务端只会
 * 返回一个含糊的错误，用户很难自己发现。
 */
@Serializable
data class MimoSpeech(
    val baseUrl: String = DEFAULT_BASE_URL,
    /** MiMo 控制台的 API Key，识别和朗读共用。 */
    val token: String = "",

    // ---- 识别
    val asrModel: String = "mimo-v2.5-asr",
    /** auto / zh / en。明确语种能提升识别效果，所以默认给 zh 之外的选项可改。 */
    val asrLanguage: String = "auto",

    // ---- 朗读
    val ttsModel: String = "mimo-v2.5-tts",
    /** 预置音色 ID：mimo_default / 冰糖 / 茉莉 / 苏打 / 白桦 / Mia / Chloe / Milo / Dean */
    val ttsVoice: String = "mimo_default",
    /** 自然语言风格指令，会作为 user 消息发出去（合成文本放 assistant）。 */
    val ttsStylePrompt: String = "",
    val ttsSpeed: Float = 1.0f,
    /** MiMo 非流式用 wav —— 拿到的就是完整容器，不用再解码。 */
    val ttsFormat: String = "wav",
    val autoSpeak: Boolean = false,
) {
    val asrConfigured: Boolean
        get() = baseUrl.isNotBlank() && token.isNotBlank() && asrModel.isNotBlank()

    val ttsConfigured: Boolean
        get() = baseUrl.isNotBlank() && token.isNotBlank() && ttsModel.isNotBlank()

    companion object {
        const val DEFAULT_BASE_URL = "https://api.xiaomimimo.com/v1"
    }
}

/**
 * 全部可持久化配置。
 *
 * 整体序列化成一条 JSON 存进 EncryptedSharedPreferences：
 *   · 加字段不用写迁移代码（缺的字段自动走默认值）
 *   · token 依然躺在 Keystore 加密里
 */
@Serializable
data class PiSettings(
    // ---- pi HTTP bridge
    val baseUrl: String = DEFAULT_BASE_URL,
    val token: String = "",
    val timeoutSec: Int = DEFAULT_TIMEOUT_SEC,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,

    // ---- 语音（识别 + 朗读，都是小米 MiMo 一家）
    val speech: MimoSpeech = MimoSpeech(),

    // ---- VAD 断句
    val vadThreshold: Float = 0.5f,
    val vadMinSilenceMs: Int = 600,
    val maxRecordSeconds: Int = 15,

    // ---- 关键词唤醒
    val wakeEnabled: Boolean = false,
    val wakeKeyword: String = "小派同学",
    val kwsScore: Float = 1.5f,
    val kwsThreshold: Float = 0.25f,
    /** KWS 推理线程数。流式小模型 1 线程基本不掉点，默认 1 省一半 CPU。 */
    val kwsThreads: Int = 1,
    val wakeOnlyCharging: Boolean = false,
    val wakeOnlyWifi: Boolean = false,
    val wakeStartHour: Int = HOUR_ANY,
    val wakeEndHour: Int = HOUR_ANY,

    // ---- 隐私
    /** 最近任务里不显示本应用的卡片。 */
    val hideRecents: Boolean = false,
) {
    /** 地址填了才发得出去请求。 */
    val isConfigured: Boolean get() = baseUrl.isNotBlank()

    /** 唤醒词，逗号分隔，允许配多个。 */
    val wakeKeywords: List<String>
        get() = wakeKeyword.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        const val DEFAULT_BASE_URL = "http://192.168.31.145:9901"
        const val DEFAULT_TIMEOUT_SEC = 120
        const val TIMEOUT_MIN = 1
        const val TIMEOUT_MAX = 3600

        /** 唤醒时段：不限 */
        const val HOUR_ANY = -1
    }
}

@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Keystore 不可用时降级为普通存储，功能不受影响，设置页会提示。 */
    var encryptedBacked: Boolean = true
        private set

    private val prefs: SharedPreferences = createPrefs(context)

    private val _state = MutableStateFlow(read())

    val state: StateFlow<PiSettings> = _state.asStateFlow()

    val current: PiSettings get() = _state.value

    fun save(settings: PiSettings) {
        // 地址规范化统一收口在这里 —— 这样连只改一个开关的 updateThemeMode /
        // updateWakeEnabled 也会顺带把地址修好，不会漏。
        val normalized = settings.copy(
            baseUrl = normalizeBaseUrl(settings.baseUrl),
            speech = settings.speech.copy(
                baseUrl = normalizeSpeechBaseUrl(settings.speech.baseUrl),
            ),
        )
        prefs.edit().putString(KEY, json.encodeToString(PiSettings.serializer(), normalized)).apply()
        _state.value = normalized
    }

    fun updateThemeMode(mode: ThemeMode) {
        save(current.copy(themeMode = mode))
    }

    fun updateWakeEnabled(enabled: Boolean) {
        save(current.copy(wakeEnabled = enabled))
    }

    private fun read(): PiSettings {
        val raw = prefs.getString(KEY, null) ?: return PiSettings()
        return runCatching { json.decodeFromString<PiSettings>(raw) }
            .getOrElse {
                Log.w(TAG, "配置解析失败，回落到默认值", it)
                PiSettings()
            }
    }

    private fun createPrefs(context: Context): SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (t: Throwable) {
        encryptedBacked = false
        Log.w(TAG, "EncryptedSharedPreferences 不可用，降级为普通存储", t)
        context.getSharedPreferences(FILE_NAME_FALLBACK, Context.MODE_PRIVATE)
    }

    companion object {
        private const val TAG = "SettingsStore"
        private const val FILE_NAME = "pi_secure_prefs"
        private const val FILE_NAME_FALLBACK = "pi_plain_prefs"
        private const val KEY = "settings_v2"

        /** 用户常常只填 `192.168.31.145:9901`，这里补全成合法 URL。 */
        fun normalizeBaseUrl(raw: String): String {
            var s = raw.trim()
            if (s.isEmpty()) return ""
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
            return s.trimEnd('/')
        }

        /**
         * MiMo 的 base 按 OpenAI 的约定要带 `/v1`（`https://api.xiaomimimo.com/v1`）。
         * 只写域名就自动补上；已经带路径的原样尊重 —— Token Plan 用户会填
         * 订阅页给的区域地址，那些也自带 `/v1`。
         */
        fun normalizeSpeechBaseUrl(raw: String): String {
            val s = normalizeBaseUrl(raw)
            if (s.isEmpty()) return ""
            val afterScheme = s.substringAfter("://", "")
            val hasPath = afterScheme.contains('/')
            return if (hasPath) s else "$s/v1"
        }
    }
}
