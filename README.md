# PiAgent · pi 语音助手（Android）

把局域网里的 pi（走 HTTP bridge）接到手机上，并且**能用嘴使唤它**：
喊一声唤醒词 → 说完自动断句 → 识别 → 问 pi → 朗读回答。

三个阶段都在这一份工程里：

| 阶段 | 能力 | 状态 |
|---|---|---|
| **M1** | 文本对话：一问一答、错误状态机、Markdown 渲染、本地历史、调试页 | 完成 |
| **M2** | 语音输入（端侧 VAD 自动断句 + 云端 ASR）与朗读（TTS 流式边收边播）、提示音、音频焦点、蓝牙让位 | 完成 |
| **M3** | 后台常驻唤醒（sherpa-onnx KWS）+ 唤醒后全流程编排 + 生效条件与保活引导 | 完成 |

配套文档：`../三阶段实施细节与可复用代码.md`、`../pi-http-bridge-调用方法.md`、
`../TTS流式化-修改文档.md`、`../pi端配合-需求说明.md`

---

## 1. 关键设计：三层解耦，缺一层也能跑

这是整个工程最重要的一点，决定了它永远不会「因为少个库就崩」：

```
界面层  ──►  VoiceSession（编排）  ──►  端侧：VAD / KWS（sherpa-onnx，可选）
                                   └─►  云端：ASR / TTS（小米 MiMo，可选）
                                   └─►  pi  ：PiRepository（必需）
```

- **没有 `libsherpa-onnx-jni.so`** → 麦克风按钮变灰并说明原因，唤醒开不了；文本对话和朗读照常。
- **没填 MiMo 的 API Key** → 识别和朗读都报「语音未配置」；文本对话照常。
- **没有配 pi 地址** → 提示去设置页；其余功能照常。

所以拿到工程**第一件事就能编译运行**，再按需补资源。

---

## 2. 跑起来

### 2.1 打开工程

本机没有 JDK / Android SDK，所以构建要在装了 Android Studio 的机器上做。

1. Android Studio（Ladybug 或更新）→ Open → 选 `PiAgent/`
2. 首次同步会下 Gradle 8.7 与依赖
3. 仓库自带 Gradle Wrapper（`gradlew` / `gradlew.bat` / `gradle-wrapper.jar` 都在），
   所以命令行直接 `./gradlew assembleDebug` 就能出包，不需要先跑 `gradle wrapper`
4. `local.properties` 由 AS 自动生成（参考 `local.properties.example`）；命令行构建则靠
   `ANDROID_HOME` 环境变量找 SDK

### 2.1.1 用 GitHub Actions 构建

推上去之后 `.github/workflows/android.yml` 会在 **push / PR / 手动触发**时产出一份 debug APK。
不装 Android Studio 也能拿到包：

1. 打开仓库的 **Actions** 页 → 选「Android CI」→ **Run workflow**
2. 等构建跑完（首次约 5–10 分钟，之后有缓存会快很多）
3. 在该次运行页面底部的 **Artifacts** 里下载 `PiAgent-debug`

工作流里有几个刻意的设计：

- 编译前先跑 `python3 tools/lint_imports.py`。这是给「本地没有 Android SDK、
  编不了」这种情况准备的兜底：它专门扫 Compose 扩展函数和图标**用了却没 import**
  （`Modifier.fillMaxWidth()`、`Icons.Filled.Mic` 之类），这类错在 Kotlin 里
  一次性会喷十条 `Unresolved reference`，在 CI 前置掉能省一轮往返。
  脚本是启发式的（词法扫描 + 调用形式匹配），只能减少往返，不能替代编译。
- 会先**校验端侧资源是否齐备**（`silero_vad.onnx` / `libsherpa-onnx-jni.so` / `encoder*.onnx` …）。
  资源已入库，正常情况下直接跳过；万一缺失就自动联网补齐 ——
  避免静默编出一个「能装但没语音能力」的 APK。
- 没产物时 `if-no-files-found: error` 直接失败，不给空 artifact。
- 同分支的旧任务会被自动取消，不浪费额度。

> 想在 CI 里省掉那 60MB 二进制，就把 `app/src/main/jniLibs/**` 和 `app/src/main/assets/**`
> 加进 `.gitignore` 并从历史里移除 —— 工作流里的校验步骤会自动改走联网拉取。
> 取舍是：仓库瘦身，但每次构建多一条网络依赖。

### 2.2 补齐端侧语音资源

> **当前工程里已经拉好了**（`jniLibs/arm64-v8a` 4 个 so + `silero_vad.onnx` + `assets/kws/` 完整模型），
> 手上这台机器跑 Android Studio 就能直接编。只有换机器、换 ABI 时才需要重跑；
> 换唤醒词不归脚本管 —— 唤醒词已固定为打包词表里的「水蓝蓝」，要换就改
> `assets/kws/keywords.txt` 后重新打包（App 内不支持自定义）。
> 脚本对已存在的文件会跳过，不会重复下载。

```bash
# 走本地代理的话（代理也可以直接给 HTTPS_PROXY 环境变量）
python tools/fetch_assets.py --proxy http://127.0.0.1:20172

# 只想要 VAD（最省事、体积最小）
python tools/fetch_assets.py --components vad

# 想要模拟器也能跑：多抽一个 x86_64
python tools/fetch_assets.py --components native --abis arm64-v8a,x86_64

# 换打包内置的唤醒词（需重新打包安装；App 内不支持自定义）
python tools/fetch_assets.py --components kws --keywords 水蓝蓝

# 只看现状
python tools/fetch_assets.py --check
```

脚本只做三件事（全程标准库，不需要 pip）：

| 产物 | 去向 | 说明 |
|---|---|---|
| `libsherpa-onnx-jni.so` 等 4 个 so | `app/src/main/jniLibs/<abi>/` | 从上游 release 的 `-android.tar.bz2` 里抽 |
| `silero_vad.onnx`（629KB） | `app/src/main/assets/` | 端侧断句 |
| 关键词模型（3.3M zipformer，中文） | `app/src/main/assets/kws/` | 唤醒 |

用 Python 而不是 shell，是因为 `.tar.bz2` 在 Windows 上经常没现成解压工具；
`tarfile` 能在解压时直接过滤成员、剥掉顶层目录，**全程不需要任何 copy / mv**。
下载带了重试与断点续传 —— GitHub release 会 302 到 objects.githubusercontent.com，
大文件过代理时偶发 SSL 被掐断，续着下比从头再来靠谱。

> 两个容易忽略的点：
> - `assets/kws/` 里的文件名是按 **前缀发现** 的（`encoder*` / `decoder*` / `joiner*` / `tokens*` / `keywords*`），
>   所以上游换模型版本、文件名带上 `-epoch-12-avg-2-chunk-16-left-64` 这种后缀也不会失效。
> - `keywords.txt` 和模型自带的 `keywords_model.txt` 要分清：前者是真正生效的唤醒词，
>   格式是 ppinyin（一个汉字 = 声母 + 韵母，韵母带声调），可用音素都在 `tokens.txt` 里。
>   本工程已内置 `水蓝蓝`，重跑脚本不会覆盖它。

### 2.3 填配置

pi 那边必须先重启过一次（extension 只在启动时加载）：

```bash
curl http://192.168.31.145:9901/healthz          # 应返回 ok
jq -r .token /root/.pi/agent/http-bridge.json    # 复制这个 token
```

App → 设置：

- **pi 地址 / token / 超时** → 点「探活」确认，再「保存」
- **语音（小米 MiMo）** → 填 MiMo 控制台的 API Key（识别和朗读共用这一个）。
  点「试识别」录一句看能不能出字，再点「试听」听一句真话
- **唤醒** → 先「保存」，再打开开关（首次会要录音权限）

### 2.3.1 语音用的是小米 MiMo

识别和朗读都走 MiMo 一家，一个 API Key 同时管两边 —— 因为 MiMo 把两个能力都挂在
**同一个 `chat/completions` 接口**上。这点和 OpenAI 很不一样，值得先说清楚：

| | OpenAI | MiMo |
|---|---|---|
| 识别端点 | `POST /audio/transcriptions` | `POST /chat/completions` |
| 朗读端点 | `POST /audio/speech` | `POST /chat/completions` |
| 识别怎么传音频 | multipart 表单 | `messages[].input_audio.data`，base64 data URI |
| 识别结果在哪 | `text` | `choices[0].message.content` |
| 朗读的文本放哪 | 请求体 `input` | **`assistant` 消息的 content** |
| 朗读音频在哪 | 响应体字节流 | 非流式 `message.audio.data`；流式逐帧 `delta.audio.data` |
| 流式朗读 | 请求体字节流 | `"stream": true` + `audio.format` 必须 `pcm16`（SSE，24kHz 单声道） |
| 语速 | `speed` 数值 | 只能写进自然语言指令 |

**调 `/audio/transcriptions` 或 `/audio/speech` 会直接 404。** MiMo 官方文档里
「OpenAI API 兼容」指的是复用了 OpenAI 的**聊天补全**形态（能用 openai SDK 指过去），
不是它的语音端点。

几个由此而来的取舍：

- **识别不能带提示词**。MiMo 明确要求 `user` 的 `content` 里只能有 `input_audio`，
  混进 `text` 会报错（和一般多模态模型相反）。所以设置里没有「识别提示词」这一项，
  提升专有名词识别率只能靠把「语种」定死。
- **朗读的语速是折算的**。MiMo 没有数字语速参数，`ttsSpeed` 会被折成一句
  「语速稍快」这类自然语言，拼进风格指令。要精确控制就自己在「风格指令」里写细一点。
- **鉴权头带了两个**。MiMo 的两份官方示例不一致：curl 写 `api-key`，Python(OpenAI SDK)
  走 `Authorization`。代码里两个都发（值相同不会冲突），省得在真机上试错。
- **朗读默认走流式**。`stream: true` 边合成边播，第一块音频到就出声，不用等整段合完；
  代价是播放期间没有 wav 文件可落盘（裸 PCM 本来也存不成通用音频格式）。
  端点不认 `stream` 参数时，设置里关掉「流式播放」即可退回整段合成——见 §7 的兜底逻辑。

> 地址默认 `https://api.xiaomimimo.com/v1`，只写域名会自动补 `/v1`。
> Token Plan 用户填订阅页给的区域地址。改地址或 Key 会立刻生效，不用重启 App。

---

## 3. 验收清单

### M1 文本对话

- [ ] 连发 5 条消息不串台、顺序正确
- [ ] timeout 设成 `1` 制造 504 → 显示「pi 还在跑」，**且不给重发按钮**
- [ ] pi 界面里执行 `/new` 时发消息 → 503 自动等 3s 重试一次
- [ ] 断网 / pi 未启动 → 中文提示，不是 `UnknownHostException` 堆栈
- [ ] 回复含 Markdown 代码块 → 正常渲染
- [ ] 长按气泡复制；失败气泡出现「未送达 · 重发」

### M2 语音输入与朗读

- [ ] 安静 / 嘈杂 / 3 米远场三种场景识别可用
- [ ] 说完自动停（VAD），不用手动点
- [ ] 开头一声「叮」、说完两声「叮·叮」，两声之间能听出间隔（不是一声拖长音）
- [ ] 提示音**没被录进自己的录音**：回放识别结果，开头不该多出杂音
- [ ] 识别结果进输入框**可编辑**，不是直接发出去
- [ ] 朗读期间不会被自己的声音触发（电话来了自动让位）
- [ ] 蓝牙耳机（SCO）能录能放 —— **这块兼容性最差，务必尽早测**

### M2 · 流式朗读（见《TTS流式化-修改文档.md》）

- [ ] 问一句会答一段的话：从「识别出文字」到**听到第一个字**，明显快于整段合成（体感是「立刻」）
- [ ] 几百字的长回复，朗读全程无卡顿、无吞字、结尾不截断
- [ ] 朗读中途点「停止」立刻静音；再点一次朗读能从头正常开始（不会带着上次的残音）
- [ ] 设置里关掉「流式播放」→ 朗读仍正常出声（走整段合成 + 落盘 + MediaPlayer）
- [ ] 用一个不认 `stream` 参数的端点（或故意填错格式）→ 报错前能自动退回整段合成，而不是哑掉
- [ ] 流式朗读期间锁屏/切后台，回来不崩、不重复播
- [ ] `adb shell ls /data/data/com.pi.assistant/cache` 里没有流式朗读留下的文件（流式本就不落盘）

### M3 后台唤醒

- [ ] 息屏连续 8 小时稳定唤醒，进程不被杀
- [ ] 误唤醒 < 1 次/小时（不达标就调高「触发阈值」）
- [ ] 播放回复时不会触发二次唤醒（自激）
- [ ] 设置里关掉后麦克风立即释放（通知消失）
- [ ] 耗电实测明显低于播放音乐类 App

### M3 · 后台占用优化（见《后台占用优化-修改文档.md》）

- [ ] **连续唤醒 10 轮，日志里 `KeywordSpotter` 只初始化 1 次**（A1。改之前是每轮一次）
- [ ] 拔掉麦克风权限 / 被别的 App 占用时，日志退避依次是 3s → 6s → 12s → … → 60s，不再固定 3s（A2）
- [ ] 关掉唤醒开关后：`dumpsys audio` 里不再有我们的 record client，常驻通知消失（回归这条，别改坏）
- [ ] 条件设成「仅充电」+ 拔掉充电器 → 通知可划掉 / 消失，进程优先级降下去（`dumpsys activity processes` 里 oom_adj 变化）（A4）
- [ ] 插上充电器 → 30s 内自动重新进前台、通知回来、能正常唤醒（A4）
- [ ] `dumpsys meminfo com.pi.assistant`：唤醒关闭 + 进后台 30s 后，PSS 低于改动前（A6/A7）
- [ ] 进后台 8s 再回前台，聊天列表和发消息都正常（A6 的 Room close）
- [ ] `adb shell ls /data/data/com.pi.assistant/cache` 里没有 `utt_*` / `tts_*` 残留（A8）
- [ ] 开启唤醒后喊「水蓝蓝」能唤醒；设置页「后台唤醒」里显示固定提示「唤醒词：水蓝蓝」，无可编辑输入框
- [ ] 「隐私 → 隐藏最近任务卡片」开启后最近任务看不到本应用，关闭后恢复
- [ ] 息屏 8 小时稳定唤醒、误唤醒 < 1 次/小时（回归）

> 耗电用 `adb shell dumpsys batterystats` 跑一晚，配 Battery Historian 看 KWS 期间的持续 CPU%，
> 把「耗电实测明显低于播放音乐类 App」落成具体数字。

---

## 4. 代码地图

```
com.k2fsa.sherpa.onnx/
  SherpaOnnxApi.kt        ★ JNI 契约层，见 §5，不要动

com.pi.assistant/
  audio/
    SherpaNative.kt          native/资源可用性探测 + assets 前缀发现
    AudioRecorder.kt         AudioRecord 最薄封装（VOICE_RECOGNITION / 16k / mono）
    WavWriter.kt             边录边写 WAV，close 时回填长度字段
    VadRecorder.kt           VAD 断句录音（说完自动停）
    KwsEngine.kt             关键词唤醒监听循环（唤醒词固定「水蓝蓝」，见 assets/kws/keywords.txt）
    AudioFocusHelper.kt      音频焦点
    TtsPlayer.kt             整段播放：MediaPlayer 吃落盘的 wav/mp3（挂起直到播完）
    PcmStreamPlayer.kt       流式播放：AudioTrack 边收边灌裸 PCM16（24k/单声道）
    ToneCue.kt               提示音：开口「叮」+ 说完「叮·叮」
  data/
    pi/                      pi bridge 客户端 + 错误状态机（M1 的灵魂）
    net/HttpClients.kt       唯一根 OkHttpClient，pi/语音都从它派生（共享连接池）
    speech/                  小米 MiMo 语音：识别 + 合成（同一个 chat/completions）
    prefs/SettingsStore.kt   全部配置（JSON 一条存进 Keystore 加密）
    local/                   Room 历史
  system/IdleReaper.kt       进后台且空闲时释放 OkHttp/Room/缓存文件
  voice/
    VoiceBus.kt              服务 ↔ 界面的状态总线
    TtsSpeaker.kt            朗读的唯一入口：流式/整段选路 + 兜底
    VoiceSession.kt          语音链路编排（M2/M3 的枢纽）
  service/
    WakeWordService.kt       前台服务：监听 → 唤醒 → 一轮对话 → 回到监听
  ui/
    chat/ settings/ debug/  三个页面
  util/MarkdownStripper.kt   朗读前剥掉 Markdown 标记
tools/fetch_assets.py        拉 native 库与模型
```

---

## 5. 为什么有一个 `com.k2fsa.sherpa.onnx` 包

那个文件是**唯一「照抄」的部分**，原因不是偷懒：

JNI 是按「包名 + 类名 + 方法名」和「data class 的字段名」反查的。
把 `Vad` 挪到别的包，`Java_com_k2fsa_sherpa_onnx_Vad_newFromAsset` 这个符号就对不上，
运行时报 `UnsatisfiedLinkError`；少一个字段（比如 `OnlineModelConfig.parafformer`）
则会在 native 侧读字段时崩。

也就是说这层是 **ABI，不是业务代码**，没有「自己写一遍」的空间。
除此之外的每一行都在 `com.pi.assistant` 下自己实现。

---

## 6. 几个关键的实现决定

**1. 504 不等于失败。** pi 的语义是「timeout 到点只取消等待，任务继续跑」。
所以收到 504 先查 `/v1/status.busy`：忙就只提示「还在跑」，**不给重发按钮**，越点越堆。

**2. 客户端超时必须 > 服务端 `timeout`。** 固定 `+30s`，否则先被自己掐断而 pi 还在跑。

**3. 唤醒的监听循环和一轮对话是串行的。** 命中唤醒词后先让 `listen()` 返回
（`AudioRecord` 随之释放），再去录音。否则两个 `AudioRecord` 会互相抢设备。

**4. 播放期间绝对不采集，播完静默 500ms 再恢复。** 否则 App 会把自己播的声音
当唤醒词，无限自激。`wakeLockoutMs = 3000` 再挡一层重复触发。

**5. 唤醒只由用户手动开。** Android 12+ 本来也不允许后台自启；
常驻通知里必须能一键停；设置页明确写了「麦克风将常驻采集」。

**6. 语音只支持 MiMo 一家。** 识别和朗读共用一份地址与 Key，配置收进一个嵌套的
`MimoSpeech` 对象（见 §2.3.1）。做成嵌套而不是平铺进 `PiSettings` 是有意的：
字段名换过一批，老配置里那些 OpenAI / 百炼的模型名不会被带进来 —— 模型名对不上时
服务端只回一个含糊的错误，用户很难自己发现。pi 的地址和 token 不受影响。

**7. 所有 `ResponseBody.string()` 都在 `Dispatchers.IO` 里。** 它做的是网络 I/O，
在主线程调直接 `NetworkOnMainThreadException`。

**8. `retryOnConnectionFailure(false)`。** 重试语义全部交给状态机，
避免 OkHttp 隐式重试把 `timeout` 放大。

**9. 流式播放不设 `callTimeout`，用 `readTimeout` 兜底。** 流式是「边生成边收」，
四千字读下来可能好几分钟——任何固定的总超时都会在用户正听着的时候把连接掐掉。
正常的流式每几百毫秒就有一块音频，所以「30 秒没动静」是明确异常，报错比干等强。
（非流式那条路保留 180s 总超时，它本来就是一次性的。）

**10. AudioTrack 用非阻塞写，不用阻塞写。** 阻塞写要么占住一个线程，要么在被 `stop()`
打断时得靠 pause/flush 把一个卡在内核里的 `write()` 撬出来 —— 两个线程抢同一个
AudioTrack，时序很难论证，还有「刚 flush 完又写进一块、然后永远卡住」的窗口。
非阻塞写让循环只剩「查标志 → 写 → 让出 10ms」一个节奏，`stop()` 就只是置个标志；
缓冲区满时返回 0 也正好就是我们要的背压。

**11. 两个播放器不是冗余，是两条路。** `MediaPlayer` 必须先有完整容器才肯起播，
天然要等整段合成完；`AudioTrack` 吃裸 PCM，发一块就能响。前者是兼容性兜底
（端点不认 `stream` 时唯一能用的路），后者是日常。选路和「流式一个音都没出来就退回
整段」的兜底只在 `TtsSpeaker` 里维护一份，免得两个调用点行为悄悄分叉。

---

## 7. 已知限制（没做的部分）

- **端侧 ASR 降级没做**：文档里提到网络挂了降级到 SenseVoice，本工程未实现。
  断网时语音识别直接报错，文本对话不受影响。
- **流式朗读只在「同一条回复内」省时间**：pi 的 bridge 是一问一答、整段返回
  （见《pi-http-bridge-调用方法.md》§3），所以第一块音频最早也只能在 pi 答完之后
  才到。想做到「pi 一边写、这边一边念」，需要 pi 那边开一个 SSE 流式端点 ——
  要改什么、契约怎么定，都写在《pi端配合-需求说明.md》里。
- **流式播放期间没有音频文件**：裸 PCM16 存不成通用音频格式，所以这段音频用完即弃，
  不能像整段模式那样落盘成 wav 供导出。
- **流式被打断就丢弃**：中途点停 = 直接掐掉连接，不会把已播的部分留档。
- **识别不能带提示词**：MiMo 要求 `user` 的 content 里只能有 `input_audio`，
  混进 `text` 会报错，所以热词只能靠把语种定死（详见 §2.3.1）。
- **朗读语速是折算的**：MiMo 没有数字语速参数，`ttsSpeed` 会折成自然语言指令，
  不是精确倍率。
- **只支持 MiMo**：不再支持 OpenAI 或其它兼容端点，要换服务商得改
  `SpeechRepository` 里的请求构造。
- **没有打断接口**：pi 只有一问一答，唤醒后中途改口要等它答完。
- **厂商保活只能尽力而为**：代码侧只做到引导跳电池优化白名单，
  小米/华为/OPPO/vivo 的自启动与后台白名单仍需手动设置。
- **没跑过真机**：这份工程是在没有 JDK / Android SDK 的机器上写的，
  代码经过仔细核对但**尚未编译验证**。第一次 Sync 若有报错，把日志贴出来即可。
