# 架构规格 · Voice to Text

> Phase 1 产物：系统架构、数据流、模块划分。
> 本文由原根目录「系统音频转文字-开发计划.md」的系统架构章节迁移而来。
> **last_updated: 2026-08-07**（织入网课录屏总结模块，Step 12 文档同步）

## 一、技术栈

| 层级 | 技术 | 说明 |
|------|------|------|
| 前端 | Vue 3 + Pinia + Vite | 实时展示转写结果 / 网课学习区 |
| 后端 | Rust + Tauri 2 | 桌面框架、系统调用 |
| 音频采集 | cpal + WASAPI | 麦克风 / 系统音频内录 |
| 语音识别 | sherpa-onnx (ZipFormer) | 本地 ONNX 流式识别模型 |
| 屏幕捕获 | windows-capture (WGC) | 网课总结：指定窗口抓帧（仅 Windows） |
| 课件 OCR | oar-ocr 0.9 (PP-OCRv5 / ort) | 网课总结：本地 OCR，模型首用自动下载（ModelScope） |
| 云端总结 | reqwest (blocking, rustls) | 网课总结：OpenAI 兼容 chat/completions |
| 凭据存储 | keyring (windows-native) | API Key 只进 Windows 凭据管理器 |
| 打包 | Tauri CLI | 生成 .exe / .msi 安装包 |

## 二、数据流

实时转写（原功能）：

```
麦克风 / 系统音频 → 音频采集(16kHz PCM) → VAD 过滤 → sherpa-onnx 流式识别 → 前端实时显示
                                            ↓
                                      录音保存(WAV)
```

网课录屏总结（录制三路共用 SessionClock t0 → 同一会话目录；录后四阶段串行）：

```
录制:  窗口抓帧 ─→ 视频硬编切片 video/*.mp4 + SAD 预筛 frames/change_*
       音频采集 ─→ audio.wav          （三路同一时间轴 ms）
       流式识别 ─→ transcript.jsonl
录后:  精细抽帧(frames.json) → 课件OCR(回填ocr_text) → 音字帧对齐(aligned.json)
       → 云端总结(summary草稿,仅文字上行/多模态需确认) → 学习区时间轴 → Markdown导出
```

## 三、模块划分

```
┌─────────────────────────────────────────────────────────┐
│                    UI 层（Vue 3）                          │
│  实时转写: Transcription + Controls                        │
│  网课总结: Recorder → Processing → Study + SummaryPanel    │
│           (+ SummarySettings)   App.vue Tab + feature flag │
├─────────────────────────────────────────────────────────┤
│            Tauri IPC 命令（lib.rs 注册，见 §四）            │
├─────────────────────────────────────────────────────────┤
│  核心层（Rust, src-tauri/src/）                             │
│  转写主链路:  audio/(采集)  stt/(识别)  lib.rs(模型路径)     │
│  网课总结:    session/(会话目录+墙钟)  screen/(捕获/编解码/抽帧)│
│              ocr/(课件OCR)  align/(音字帧对齐)               │
│              summary/(云端总结/渲染导出)  feature_flags.rs   │
└─────────────────────────────────────────────────────────┘
```

## 四、内部接口（Tauri IPC 命令）

实时转写（原功能）：

| 命令 | 作用 |
|---|---|
| `start_recording` / `stop_recording` | 开始/停止采集 + 识别，推送文字事件 |
| `list_audio_devices` / `get_recording_status` | 设备枚举 / 录制状态 |

网课录屏总结（均在 [src-tauri/src/lib.rs](../../../src-tauri/src/lib.rs) 注册）：

| 命令 | 作用 |
|---|---|
| `get_feature_flags` | 运维开关：网课总结入口显隐（Step 12） |
| `create_session` / `list_sessions` / `get_session_status` | 会话目录与状态机（FR-103） |
| `list_windows` / `start_window_capture` / `stop_window_capture` / `get_capture_status` | 窗口枚举与裸捕获（FR-101） |
| `start_capture_session` / `stop_capture_session` | 三路录制集成（屏幕+音频+转写同 t0，FR-101/102/103） |
| `process_frames` / `process_ocr` / `align_session` | 录后处理：精细抽帧 / 课件 OCR / 对齐（FR-104/105/106） |
| `get_summary_config` / `set_summary_config` / `set_summary_api_key` / `has_summary_api_key` / `clear_summary_api_key` / `test_summary_connection` | 总结配置与 Key 管理（FR-109） |
| `summarize` / `regenerate_summary` | 云端总结：全量 / 单段重生成（FR-107） |
| `get_timeline` / `export_markdown` | 学习区时间轴 / Markdown 导出（FR-108） |
| `get_video_slices` / `get_ocr_text` / `update_summary_point` | 学习区：切片清单 / OCR 原文核对 / 要点编辑（FR-108/109） |

## 五、核心模块代码位置

| 模块 | 文件 | 职责 |
|---|---|---|
| 前端状态（转写） | [src/stores/app.ts](../../../src/stores/app.ts) | Pinia 状态，与 Rust 后端 IPC 通信 |
| 前端状态（网课） | [src/stores/session.ts](../../../src/stores/session.ts) | 录制会话状态机 + 录后编排 + 学习区 |
| 控制栏 | [src/components/Controls.vue](../../../src/components/Controls.vue) | 录音控制、设备选择 |
| 转写展示 | [src/components/Transcription.vue](../../../src/components/Transcription.vue) | 实时识别结果（临时/确认态） |
| 命令注册 | [src-tauri/src/lib.rs](../../../src-tauri/src/lib.rs) | Tauri 命令注册、模型路径解析、运维埋点 |
| 音频采集抽象 | [src-tauri/src/audio/capture.rs](../../../src-tauri/src/audio/capture.rs) | 统一采集（cpal 麦克风 + WASAPI loopback） |
| 系统音频内录 | [src-tauri/src/audio/loopback.rs](../../../src-tauri/src/audio/loopback.rs) | Windows 专属 WASAPI loopback |
| 流式识别器 | [src-tauri/src/stt/recognizer.rs](../../../src-tauri/src/stt/recognizer.rs) | sherpa-onnx 在线流式识别，含重采样 |
| 会话管理 | [src-tauri/src/session/mod.rs](../../../src-tauri/src/session/mod.rs) | 会话目录树 + SessionClock 墙钟（traceId=session_id） |
| 屏幕捕获 | [src-tauri/src/screen/](../../../src-tauri/src/screen/) | WGC 抓帧 / mp4 硬编切片 / 解码 / 判页抽帧 |
| 课件 OCR | [src-tauri/src/ocr/mod.rs](../../../src-tauri/src/ocr/mod.rs) | oar-ocr 本地推理，回填 frames.json |
| 音字帧对齐 | [src-tauri/src/align/mod.rs](../../../src-tauri/src/align/mod.rs) | 中点规则归并 → aligned.json |
| 云端总结 | [src-tauri/src/summary/](../../../src-tauri/src/summary/) | map-reduce 总结 / Key 管理 / 时间轴渲染导出 |
| 运维开关 | [src-tauri/src/feature_flags.rs](../../../src-tauri/src/feature_flags.rs) | feature flag（环境变量 > 配置文件 > 默认开） |

## 六、关键设计决策

- **模型路径解析**：由 `lib.rs` 从可执行文件所在目录自动查找 `models/`，支持开发态与打包态（详见 [部署手册](../deploy/部署手册.md)）。
- **容错**：模型加载失败时回退 mock 模式（显示 `[mock] 你好`），不崩溃。
- **平台差异**：系统音频内录与窗口捕获仅 Windows（WASAPI loopback / WGC）；macOS/Linux 仅麦克风。
- **统一时间轴**：网课总结三路（屏幕/音频/转写）共用 `SessionClock` t0，落盘全部用 session 相对毫秒，录后对齐不依赖文件元数据。
- **会话隔离**：每节课一个 `sessions/<id>/` 目录，id 由后端时间戳生成（不接受前端输入，防路径穿越）。
- **隐私红线**：仅文字上行云端（多模态精修需前端二次确认才附课件帧图）；API Key 只存 Windows 凭据管理器；音视频原文件不出本机。
- **帧图/视频加载**：走 `asset://` 协议（setup 动态放行 sessions 根目录），不走 base64 IPC 撑爆 webview。
- **feature flag 运维开关**：`VTT_COURSE_SUMMARY` 环境变量或 `<app_config_dir>/feature_flags.json` 可关停网课总结入口（前端隐藏 Tab + 后端拒绝 `start_capture_session`），出问题不必回滚发版。

---

## 术语表

| 术语 | 大白话 | 简单案例 |
|---|---|---|
| Tauri IPC | 前端 JS 调用 Rust 函数的桥 | 前端点「开始」→ 调 Rust 的 start_recording |
| PCM | 未压缩的原始音频数据格式 | 16kHz 单声道 PCM |
| 重采样 | 把音频采样率转换成模型需要的值 | 48kHz → 16kHz |
| WGC | Windows 官方窗口抓屏接口 | 只录用户选中的播放器窗口 |
| SessionClock | 三路录制共用的「开录秒表」 | 视频帧、音频句都记「开录后第 N 毫秒」 |
| map-reduce | 先分段总结再汇总成大纲的套路 | 2h 课切 8 段分别总结，再合并 |
| feature flag | 功能开关，改配置就能上下线功能 | 网课总结出问题，置 off 隐藏入口 |
