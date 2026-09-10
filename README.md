# PiAgent · pi 语音助手（Android）

把局域网里的 pi（走 HTTP bridge）接到手机上，并且**能用嘴使唤它**：
喊一声唤醒词 → 说完自动断句 → 识别 → 问 pi → 朗读回答。

三个阶段都在这一份工程里：

| 阶段 | 能力 | 状态 |
|---|---|---|
| **M1** | 文本对话：一问一答、错误状态机、Markdown 渲染、本地历史、调试页 | 完成 |
| **M2** | 语音输入（端侧 VAD 自动断句 + 云端 ASR）与朗读（TTS）、音频焦点、蓝牙让位 | 完成 |
| **M3** | 后台常驻唤醒（sherpa-onnx KWS）+ 唤醒后全流程编排 + 生效条件与保活引导 | 完成 |

配套文档：`../三阶段实施细节与可复用代码.md`、`../pi-http-bridge-调用方法.md`

---

## 1. 关键设计：三层解耦，缺一层也能跑

这是整个工程最重要的一点，决定了它永远不会「因为少个库就崩」：

```
界面层  ──►  VoiceSession（编排）  ──►  端侧：VAD / KWS（sherpa-onnx，可选）
                                   └─►  云端：ASR / TTS（OpenAI 兼容，可选）
                                   └─►  pi  ：PiRepository（必需）
```

- **没有 `libsherpa-onnx-jni.so`** → 麦克风按钮变灰并说明原因，唤醒开不了；文本对话和朗读照常。
- **没有配语音端点** → 朗读/识别报「语音端点未配置」；文本对话照常。
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
> 手上这台机器跑 Android Studio 就能直接编。只有换机器、换 ABI，或想改唤醒词时才需要重跑。
> 脚本对已存在的文件会跳过，不会重复下载。

```bash
# 走本地代理的话（代理也可以直接给 HTTPS_PROXY 环境变量）
python tools/fetch_assets.py --proxy http://127.0.0.1:20172

# 只想要 VAD（最省事、体积最小）
python tools/fetch_assets.py --components vad

# 想要模拟器也能跑：多抽一个 x86_64
python tools/fetch_assets.py --components native --abis arm64-v8a,x86_64

# 换唤醒词
python tools/fetch_assets.py --components kws --keywords 小派同学,你好派

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
>   本工程已内置 `小派同学 / 小派小派 / 你好派`，重跑脚本不会覆盖它。

### 2.3 填配置

pi 那边必须先重启过一次（extension 只在启动时加载）：

```bash
curl http://192.168.31.145:9901/healthz          # 应返回 ok
jq -r .token /root/.pi/agent/http-bridge.json    # 复制这个 token
```

App → 设置：

- **pi 地址 / token / 超时** → 点「探活」确认，再「保存」
- **识别（ASR）** → 选预设（OpenAI 官方 / 自建 / 自定义）再按需改模型名，
  点「试识别」录一句，看能不能正确出字
- **朗读（TTS）** → 默认跟识别共用同一服务商；要换成另一家就把
  「与识别使用同一服务商」关掉，单独填地址和 token，点「试听」听一句真话
- **唤醒** → 先「保存」，再打开开关（首次会要录音权限）

> 识别和朗读是两套独立端点，可以用不同服务商 —— 比如识别走本地的
> faster-whisper、朗读走云端的 TTS。设置里改了地址或 token 会立刻生效，
> 不用重启 App（两边的客户端缓存是分开的）。

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
- [ ] 识别结果进输入框**可编辑**，不是直接发出去
- [ ] 朗读期间不会被自己的声音触发（电话来了自动让位）
- [ ] 蓝牙耳机（SCO）能录能放 —— **这块兼容性最差，务必尽早测**

### M3 后台唤醒

- [ ] 息屏连续 8 小时稳定唤醒，进程不被杀
- [ ] 误唤醒 < 1 次/小时（不达标就调高「触发阈值」）
- [ ] 播放回复时不会触发二次唤醒（自激）
- [ ] 设置里关掉后麦克风立即释放（通知消失）
- [ ] 耗电实测明显低于播放音乐类 App

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
    KwsEngine.kt             关键词唤醒监听循环
    AudioFocusHelper.kt      音频焦点
    TtsPlayer.kt             MediaPlayer 播放（挂起直到播完）
    ToneCue.kt               唤醒提示音
  data/
    pi/                      pi bridge 客户端 + 错误状态机（M1 的灵魂）
    speech/                  OpenAI 兼容 ASR / TTS
    prefs/SettingsStore.kt   全部配置（JSON 一条存进 Keystore 加密）
    local/                   Room 历史
  voice/
    VoiceBus.kt              服务 ↔ 界面的状态总线
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

**6. 全部参数可配。** 兼容端点的模型名和字段名差异极大，预设只是快捷填充，
填完你还能改 —— 硬编码必然返工。

**6.1 ASR 与 TTS 是两套独立端点。** 两边各有自己的地址、token、预设和客户端缓存。
`ttsShareAsr` 默认 `true`，让朗读复用识别的端点（同一家服务商是常态，不必填两遍）；
关掉就能填第二家。预设也只填它管的那一侧，不会把另一边手动配好的覆盖掉。

> 旧版本只有一套共用的 `speechBaseUrl` / `speechToken`，升级后靠 `@SerialName`
> 把它们绑到 ASR 那一侧，已填的地址和 token 不会丢。

**7. 所有 `ResponseBody.string()` 都在 `Dispatchers.IO` 里。** 它做的是网络 I/O，
在主线程调直接 `NetworkOnMainThreadException`。

**8. `retryOnConnectionFailure(false)`。** 重试语义全部交给状态机，
避免 OkHttp 隐式重试把 `timeout` 放大。

---

## 7. 已知限制（没做的部分）

- **端侧 ASR 降级没做**：文档里提到网络挂了降级到 SenseVoice，本工程未实现。
  断网时语音识别直接报错，文本对话不受影响。
- **长文本 TTS 未做流式分段**：目前整段合成，首字延迟偏高。文档列为二期优化。
- **没有打断接口**：pi 只有一问一答，唤醒后中途改口要等它答完。
- **厂商保活只能尽力而为**：代码侧只做到引导跳电池优化白名单，
  小米/华为/OPPO/vivo 的自启动与后台白名单仍需手动设置。
- **没跑过真机**：这份工程是在没有 JDK / Android SDK 的机器上写的，
  代码经过仔细核对但**尚未编译验证**。第一次 Sync 若有报错，把日志贴出来即可。
