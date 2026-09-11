# PiAgent · Pi 语音助手（Android）

把局域网里的 pi（HTTP bridge）接到手机上，用嘴使唤它：唤醒 → 识别 → 问 pi → 朗读回答。

## 功能

- **M1 文本对话**：一问一答、错误状态机、Markdown 渲染、本地历史、调试页
- **M2 语音**：端侧 VAD 自动断句 + 云端 ASR；TTS 流式边收边播、提示音、音频焦点、蓝牙让位
- **M3 后台唤醒**：sherpa-onnx 关键词唤醒（唤醒词「喵喵」）+ 全流程编排 + 保活引导
- **M3+ pi 流式回复**：`/v1/chat/stream` 边收边念
- **快捷开关**：长按桌面图标（开 / 关分开两条）/ 下拉快捷设置磁贴，一键开关后台监听，不用进 App

> 详细设计与实现见项目根目录配套文档：`三阶段实施细节与可复用代码.md`、`pi-http-bridge-调用方法.md`、`TTS流式化-修改文档.md`、`pi端配合-需求说明.md`、`pi端配合-实现回复-2026-09-11.md`。

## 快速开始

**1. 构建**（需 JDK / Android SDK，装了 Android Studio 的机器上做）
- Android Studio 打开 `PiAgent/`，或命令行 `./gradlew assembleDebug`
- 无本地 SDK 也可在 GitHub Actions 的 **Actions** 页「Run workflow」拿 debug APK

**2. 补齐端侧语音资源**（工程已自带，仅换机器 / ABI / 唤醒词时才需重跑）
```bash
python tools/fetch_assets.py --check      # 看现状
python tools/fetch_assets.py              # 按需补齐 so / VAD / KWS 模型
```

**3. 配置**（pi 需先重启一次以加载 extension）
```bash
curl http://<pi-ip>:9901/healthz                    # 应返回 ok
jq -r .token /root/.pi/agent/http-bridge.json         # 复制 token
```
App → 设置：
- **pi 地址 / token / 超时** → 点「探活」确认再保存
- **语音（小米 MiMo）** → 填 MiMo API Key（识别+朗读共用），「试识别」「试听」验证
- **唤醒** → 先保存，再开开关（首次要录音权限）

**4. 后台监听的三个入口**（同一件事，逻辑都走 `WakeControl`）
- 设置页 → 「后台唤醒」开关（最完整，能改阈值和生效条件）
- 长按桌面「Pi 助手」图标 → 「打开监听」/「关闭监听」两条分开的快捷方式（缺录音权限会直接弹授权）
- 下拉通知栏 → 编辑快捷设置 → 把「后台监听」磁贴拖进去（可看状态，点一下切换）

## 架构：三层解耦，缺一层也能跑

```
界面层 → VoiceSession（编排）
              ├─ 端侧：VAD / KWS（sherpa-onnx，可选）
              ├─ 云端：ASR / TTS（小米 MiMo，可选）
              └─ pi ：PiRepository（必需）

后台监听的三个入口（设置页开关 / 桌面快捷方式 / 快捷设置磁贴）
              └─ 统一收口到 WakeControl → WakeWordService
```

- 无 `libsherpa-onnx-jni.so` → 麦克风按钮禁用，文本 / 朗读照常
- 没填 MiMo Key → 识别 / 朗读报「语音未配置」，文本对话照常
- 没配 pi 地址 → 提示去设置，其余照常

拿到工程第一件事就能编译运行，再按需补资源。

## 关键约定

- **语音只接小米 MiMo**：识别与朗读共用同一个 `chat/completions` 接口（非 OpenAI 语音端点）；调 `/audio/transcriptions`、`/audio/speech` 会 404。
- **504 ≠ 失败**：pi 超时只取消等待、任务继续跑；收到 504 先查 `/v1/status.busy`，忙则不重发。
- **客户端超时必须 > 服务端 timeout**（+30s），否则先被自己掐断。
- **`com.k2fsa.sherpa.onnx` 包勿改**：JNI 按包名 / 类名反查，动它必 `UnsatisfiedLinkError`。
- **后台监听的开关只有 `WakeControl` 一个出口**：设置页、快捷方式、磁贴三处共用；新增入口请接它，别各写一套校验。
- **磁贴开监听在 Android 14 上不能直接起前台服务**（系统限制，15 才修）：缺权限时统一拉 `WakeToggleActivity` 走授权。
- **代码未经真机验证**：本地无 Android SDK，仅 CI（release + R8 + import 体检）验证可编译。

## 已知限制

- 断网时语音识别直接报错（端侧 ASR 降级未做）；文本对话不受影响。
- 手打消息走整段 `/v1/chat`，聊天页不逐字渲染。
- 点停朗读只静音、丢弃后续句，pi 那轮照常跑完（回复仍落库）。
- 识别不能带提示词；朗读语速是折算的自然语言指令（MiMo 无数字语速参数）。
- 仅支持 MiMo，换服务商需改 `SpeechRepository`。
- 磁贴的开关状态来自配置（`wakeEnabled`）；服务被厂商省电策略杀掉后，配置仍是「开」，磁贴不会知道。要确认服务真在跑，看设置页的状态提示。
- 厂商保活仅引导跳白名单，小米 / 华为 / OPPO / vivo 仍需手动设置。
