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

/** 语音端点的三档预设。各家字段名/模型名差异很大，所以参数必须可配，预设只是快捷填充。 */
@Serializable
enum class SpeechPreset {
    OPENAI,
    SELF_HOSTED,
    CUSTOM;

    val label: String
        get() = when (this) {
            OPENAI -> "OpenAI 官方"
            SELF_HOSTED -> "自建兼容端点"
            CUSTOM -> "自定义"
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

    // ---- 语音端点（OpenAI 兼容的 ASR / TTS）
    val speechPreset: SpeechPreset = SpeechPreset.OPENAI,
    val speechBaseUrl: String = DEFAULT_SPEECH_BASE_URL,
    val speechToken: String = "",
    val asrModel: String = "gpt-4o-transcribe",
    val asrLanguage: String = "zh",
    val asrPrompt: String = "",
    val ttsModel: String = "gpt-4o-mini-tts",
    val ttsVoice: String = "alloy",
    val ttsSpeed: Float = 1.0f,
    val ttsFormat: String = "mp3",
    val autoSpeak: Boolean = false,

    // ---- VAD 断句
    val vadThreshold: Float = 0.5f,
    val vadMinSilenceMs: Int = 600,
    val maxRecordSeconds: Int = 15,

    // ---- 关键词唤醒
    val wakeEnabled: Boolean = false,
    val wakeKeyword: String = "小派同学",
    val kwsScore: Float = 1.5f,
    val kwsThreshold: Float = 0.25f,
    val wakeOnlyCharging: Boolean = false,
    val wakeOnlyWifi: Boolean = false,
    val wakeStartHour: Int = HOUR_ANY,
    val wakeEndHour: Int = HOUR_ANY,
) {
    /** 地址填了才发得出去请求。 */
    val isConfigured: Boolean get() = baseUrl.isNotBlank()

    /** 语音端点是否可用（地址 + 至少一个模型名）。 */
    val speechConfigured: Boolean
        get() = speechBaseUrl.isNotBlank() && (asrModel.isNotBlank() || ttsModel.isNotBlank())

    /** 唤醒词，逗号分隔，允许配多个。 */
    val wakeKeywords: List<String>
        get() = wakeKeyword.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        const val DEFAULT_BASE_URL = "http://192.168.31.145:9901"
        const val DEFAULT_SPEECH_BASE_URL = "https://api.openai.com/v1"
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
        val normalized = settings.copy(baseUrl = normalizeBaseUrl(settings.baseUrl))
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
         * 语音端点按 OpenAI 的约定，「base」是要带 `/v1` 的。
         * 用户如果只写 `https://api.openai.com`，这里自动补 `/v1`；
         * 已经写了路径的（自建端点常有自定义前缀）就原样尊重。
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
