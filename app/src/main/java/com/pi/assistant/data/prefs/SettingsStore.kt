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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * 「协议」= 请求长什么样，跟服务商是两回事。
 *
 * 之所以要单独拎出来：各家的 HTTP 契约差得远，不是换个域名就能通 ——
 *   · OpenAI 风格把音频当 multipart 上传、直接回音频字节
 *   · 百炼 Fun-ASR 要把音频 base64 塞进 JSON 的 messages 里
 *   · MiMo 的 TTS 走的是 chat/completions，音频 base64 藏在返回的 JSON 里
 * 所以仓库层必须按协议分发，光靠一个 baseUrl 区分不出来。
 */
@Serializable
enum class AsrProtocol {
    /** OpenAI 风格：multipart POST {base}/audio/transcriptions */
    OPENAI,

    /** 阿里云百炼 Fun-ASR-Flash：DashScope 的 multimodal-generation 端点。 */
    BAILIAN_FUN_ASR,
}

@Serializable
enum class TtsProtocol {
    /** OpenAI 风格：POST {base}/audio/speech，响应体就是音频字节。 */
    OPENAI,

    /** 小米 MiMo：POST {base}/chat/completions，音频是 base64 放在 JSON 里。 */
    MIMO_CHAT,
}

/**
 * 识别侧的预设。预设只负责把「这一侧」的协议、地址、模型填好，
 * 填完照样能改 —— 兼容端点的差异太大，硬编码必然返工。
 */
@Serializable
enum class AsrPreset {
    BAILIAN_FUN_ASR,
    OPENAI,
    SELF_HOSTED,
    CUSTOM;

    val label: String
        get() = when (this) {
            BAILIAN_FUN_ASR -> "百炼 Fun-ASR"
            OPENAI -> "OpenAI 官方"
            SELF_HOSTED -> "自建兼容端点"
            CUSTOM -> "自定义"
        }

    /** null 只出现在 CUSTOM：协议交给用户显式选。 */
    val protocol: AsrProtocol?
        get() = when (this) {
            BAILIAN_FUN_ASR -> AsrProtocol.BAILIAN_FUN_ASR
            OPENAI, SELF_HOSTED -> AsrProtocol.OPENAI
            CUSTOM -> null
        }

    val baseUrl: String?
        get() = when (this) {
            BAILIAN_FUN_ASR -> DEFAULT_BAILIAN_ASR_URL
            OPENAI -> "https://api.openai.com/v1"
            SELF_HOSTED -> DEFAULT_SELF_HOSTED_SPEECH_URL
            CUSTOM -> null
        }

    val model: String?
        get() = when (this) {
            BAILIAN_FUN_ASR -> "fun-asr-flash-2026-06-15"
            OPENAI -> "gpt-4o-transcribe"
            SELF_HOSTED -> "Systran/faster-whisper-large-v3"
            CUSTOM -> null
        }

    companion object {
        /**
         * 百炼的域名里要带自己的 Workspace ID，所以这里只能给个模板 ——
         * 用户得把 `{WorkspaceId}` 换成控制台上的真实值，否则请求打不通。
         */
        const val DEFAULT_BAILIAN_ASR_URL =
            "https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com"

        /** 自建端点默认指向 pi 机器上常见的本地部署地址，用户按需改。 */
        const val DEFAULT_SELF_HOSTED_SPEECH_URL = "http://192.168.31.145:9000/v1"
    }
}

/** 朗读侧的预设。理由同 [AsrPreset]。 */
@Serializable
enum class TtsPreset {
    MIMO_V25,
    OPENAI,
    SELF_HOSTED,
    CUSTOM;

    val label: String
        get() = when (this) {
            MIMO_V25 -> "小米 MiMo"
            OPENAI -> "OpenAI 官方"
            SELF_HOSTED -> "自建兼容端点"
            CUSTOM -> "自定义"
        }

    val protocol: TtsProtocol?
        get() = when (this) {
            MIMO_V25 -> TtsProtocol.MIMO_CHAT
            OPENAI, SELF_HOSTED -> TtsProtocol.OPENAI
            CUSTOM -> null
        }

    val baseUrl: String?
        get() = when (this) {
            MIMO_V25 -> DEFAULT_MIMO_BASE_URL
            OPENAI -> "https://api.openai.com/v1"
            SELF_HOSTED -> AsrPreset.DEFAULT_SELF_HOSTED_SPEECH_URL
            CUSTOM -> null
        }

    val model: String?
        get() = when (this) {
            MIMO_V25 -> "mimo-v2.5-tts"
            OPENAI -> "gpt-4o-mini-tts"
            SELF_HOSTED -> "kokoro"
            CUSTOM -> null
        }

    val voice: String?
        get() = when (this) {
            MIMO_V25 -> "mimo_default"
            OPENAI -> "alloy"
            SELF_HOSTED -> "af_heart"
            CUSTOM -> null
        }

    val format: String?
        get() = when (this) {
            // MiMo 非流式的官方示例就是 wav；它默认回 MP3，但 wav 拿到的
            // 是完整容器，直接落盘即可，省掉一次解码。
            MIMO_V25 -> "wav"
            OPENAI -> "mp3"
            SELF_HOSTED -> "mp3"
            CUSTOM -> null
        }

    companion object {
        const val DEFAULT_MIMO_BASE_URL = "https://api.xiaomimimo.com/v1"
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

    // ---- 语音端点（ASR / TTS 两侧各自独立，协议 + 服务商都可以不同）
    //
    // 老配置只有一套共用的 speechPreset / speechBaseUrl / speechToken，这里用
    // @SerialName 把旧 key 绑到 ASR 这一侧，已经填好的地址和 token 不会丢。
    @SerialName("speechPreset")
    val asrPreset: AsrPreset = AsrPreset.BAILIAN_FUN_ASR,

    /**
     * 用户显式选的协议；null 表示「跟随 [asrPreset]」。
     *
     * 之所以弄成可空：老配置里根本没有这个字段，如果给它一个非空默认值，
     * 那些用 OpenAI 的老配置会解析成默认协议，请求直接发错。null 兜底到
     * 预设自带的协议，正好还原它们原本的行为。
     */
    val asrProtocolOverride: AsrProtocol? = null,
    @SerialName("speechBaseUrl")
    val asrBaseUrl: String = AsrPreset.DEFAULT_BAILIAN_ASR_URL,
    @SerialName("speechToken")
    val asrToken: String = "",
    val asrModel: String = "fun-asr-flash-2026-06-15",
    val asrLanguage: String = "zh",
    val asrPrompt: String = "",

    /** 朗读侧默认独立配置 —— 默认服务商（MiMo）和识别侧不是同一家。 */
    val ttsShareAsr: Boolean = false,
    val ttsPreset: TtsPreset = TtsPreset.MIMO_V25,
    val ttsProtocolOverride: TtsProtocol? = null,
    val ttsBaseUrl: String = TtsPreset.DEFAULT_MIMO_BASE_URL,
    val ttsToken: String = "",
    val ttsModel: String = "mimo-v2.5-tts",
    val ttsVoice: String = "mimo_default",

    /**
     * 朗读的风格指令。MiMo 把它作为 user 消息发出去（合成文本放 assistant），
     * 用来控制语气/情绪/方言；OpenAI 协议下没有对应字段，不发。
     */
    val ttsStylePrompt: String = "",
    val ttsSpeed: Float = 1.0f,
    val ttsFormat: String = "wav",
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

    // ---- 协议解析

    val asrProtocol: AsrProtocol get() = asrProtocolOverride ?: asrPreset.protocol ?: AsrProtocol.OPENAI

    /** 识别那家能不能顺带做朗读。「共用」开关是否有意义，全看它。 */
    val ttsShareAvailable: Boolean
        get() = when (asrProtocol) {
            AsrProtocol.OPENAI -> true
            // 百炼这套端点只做识别，没法拿来朗读
            AsrProtocol.BAILIAN_FUN_ASR -> false
        }

    /**
     * 朗读侧实际用的协议。
     *
     * 开了共用就跟着识别走 —— 但只在识别那家确实有朗读能力时才生效，
     * 否则（比如识别选了百炼）共用是被忽略的，避免拼出一个不存在的请求。
     */
    val ttsProtocol: TtsProtocol
        get() = if (ttsShareAsr && ttsShareAvailable) {
            TtsProtocol.OPENAI
        } else {
            ttsProtocolOverride ?: ttsPreset.protocol ?: TtsProtocol.OPENAI
        }

    // ---- 语音端点实际生效值：TTS 开了共用就跟着 ASR 走

    val ttsEffectiveBaseUrl: String
        get() = if (ttsShareAsr && ttsShareAvailable) asrBaseUrl else ttsBaseUrl

    val ttsEffectiveToken: String
        get() = if (ttsShareAsr && ttsShareAvailable) asrToken else ttsToken

    /** 识别侧可用：地址和模型名都齐了。 */
    val asrConfigured: Boolean get() = asrBaseUrl.isNotBlank() && asrModel.isNotBlank()

    /** 朗读侧可用：看的是生效后的端点（共用时就是 ASR 那套）。 */
    val ttsConfigured: Boolean
        get() = ttsEffectiveBaseUrl.isNotBlank() && ttsModel.isNotBlank()

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
        // 枚举字段遇到不认识的值时退回默认值，而不是抛异常。
        // 关键作用：预设枚举改过名字，如果老配置里的旧值解析不了，
        // 整个 decodeFromString 会失败 → 落到 PiSettings() → 用户所有配置被清空。
        coerceInputValues = true
    }

    /** Keystore 不可用时降级为普通存储，功能不受影响，设置页会提示。 */
    var encryptedBacked: Boolean = true
        private set

    private val prefs: SharedPreferences = createPrefs(context)

    private val _state = MutableStateFlow(read())

    val state: StateFlow<PiSettings> = _state.asStateFlow()

    val current: PiSettings get() = _state.value

    fun save(settings: PiSettings) {
        // 规范化统一收口在这里 —— 这样连只改一个开关的 updateThemeMode /
        // updateWakeEnabled 也会顺带把地址修好，不会漏。
        val normalized = settings.copy(
            baseUrl = normalizeBaseUrl(settings.baseUrl),
            asrBaseUrl = normalizeBaseFor(settings.asrProtocol, settings.asrBaseUrl),
            ttsBaseUrl = normalizeBaseFor(settings.ttsProtocol, settings.ttsBaseUrl),
            // 识别那家不做朗读时，「共用」是个无效状态，直接落成 false，
            // 免得配置里留着一个永远不生效的 true 让人困惑。
            ttsShareAsr = settings.ttsShareAsr && settings.ttsShareAvailable,
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
         * OpenAI 风格的语音端点，「base」按约定要带 `/v1`。
         * 只写域名就自动补上；已经带路径的（自建端点常有自定义前缀）原样尊重。
         * MiMo 也是这一套（`https://api.xiaomimimo.com/v1`）。
         */
        fun normalizeOpenAiStyleBase(raw: String): String {
            val s = normalizeBaseUrl(raw)
            if (s.isEmpty()) return ""
            val afterScheme = s.substringAfter("://", "")
            val hasPath = afterScheme.contains('/')
            return if (hasPath) s else "$s/v1"
        }

        /** 阿里云 DashScope 的入口路径。 */
        private const val DASHSCOPE_GENERATION_PATH =
            "/api/v1/services/aigc/multimodal-generation/generation"

        /**
         * 百炼的地址**不能**补 `/v1` —— 它的路径是
         * `/api/v1/services/aigc/multimodal-generation/generation`，由接口声明去拼，
         * 用户只要填到域名（含自己的 Workspace ID）就行。
         *
         * 顺手把误粘进来的完整路径剥掉：从控制台复制的时候很容易连端点一起复制，
         * 那种情况下再拼一次路径就变成 404 了。
         */
        fun normalizeDashScopeBase(raw: String): String {
            val s = normalizeBaseUrl(raw)
            if (s.isEmpty()) return ""
            return s.removeSuffix(DASHSCOPE_GENERATION_PATH).trimEnd('/')
        }

        /** 按协议选对应的规范化方式 —— 两者对路径的要求不一样。 */
        fun normalizeBaseFor(protocol: AsrProtocol, raw: String): String = when (protocol) {
            AsrProtocol.OPENAI -> normalizeOpenAiStyleBase(raw)
            AsrProtocol.BAILIAN_FUN_ASR -> normalizeDashScopeBase(raw)
        }

        fun normalizeBaseFor(protocol: TtsProtocol, raw: String): String = when (protocol) {
            TtsProtocol.OPENAI -> normalizeOpenAiStyleBase(raw)
            TtsProtocol.MIMO_CHAT -> normalizeOpenAiStyleBase(raw)
        }
    }
}
