# File Structure · 文件与目录结构说明

> Context Engineering 核心产物：让 AI（和新成员）一眼看懂每个目录/文件干什么。
> **last_updated: 2026-08-07**。每次新增/删除目录同步更新本文件。
> **文档规模**：本文件 ≤5000 tokens；目录变复杂时按子系统拆子文件，本文件留总览。

## 目录树

```
voice-to-text/
├── README.md                  # 项目说明 + 文档导航入口
├── package.json               # Node 依赖与脚本（dev/build/tauri:dev/download-models）
├── vite.config.ts             # Vite 配置
├── tsconfig.json / .node.json # TypeScript 配置
├── index.html                 # 前端入口
│
├── src/                       # 【前端代码】Vue 3 + Pinia（Tauri 项目前端根）
│   ├── App.vue / main.ts      # 根组件（双功能 Tab：实时转写 / 网课总结，feature flag 控显隐）/ 入口
│   ├── env.d.ts               # 前端类型声明
│   ├── stores/
│   │   ├── app.ts             # 实时转写状态，与 Rust IPC 通信核心
│   │   └── session.ts         # 网课总结：录制会话状态机 + 录后处理编排 + 学习区状态
│   └── components/
│       ├── Controls.vue       # 录音控制 + 设备选择（实时转写）
│       ├── Transcription.vue  # 转写结果展示（临时/确认态）
│       ├── Recorder.vue       # 网课总结：窗口选择 + 录制控制 + 实时转写
│       ├── Processing.vue     # 网课总结：录后处理四阶段编排（取消/重试/断点续跑）
│       ├── Study.vue          # 网课总结：章节时间轴 + 视频切片点播 + 帧/OCR 核对 + 导出
│       ├── SummaryPanel.vue   # 网课总结：总结草稿（重生成/要点编辑/多模态开关）
│       └── SummarySettings.vue # 网课总结：API 配置（Base URL/模型/Key/连通性自检）
│
├── src-tauri/                 # 【后端 + 桌面端代码】Rust + Tauri 2
│   ├── Cargo.toml             # Rust 依赖
│   ├── tauri.conf.json        # Tauri 应用配置（identifier / bundle / assetProtocol）
│   ├── build.rs
│   ├── capabilities/          # Tauri 2 权限配置
│   ├── icons/                 # 应用图标
│   └── src/
│       ├── main.rs            # 程序入口
│       ├── lib.rs             # Tauri 命令注册 + 模型路径解析 + 三路录制/录后处理/总结命令编排
│       ├── feature_flags.rs   # 运维开关（Step 12）：VTT_COURSE_SUMMARY 环境变量 / feature_flags.json
│       ├── audio/
│       │   ├── mod.rs
│       │   ├── capture.rs     # 统一采集（cpal 麦克风 + WASAPI loopback）
│       │   └── loopback.rs    # Windows 系统音频内录
│       ├── stt/
│       │   ├── mod.rs
│       │   └── recognizer.rs  # sherpa-onnx 流式识别 + 重采样
│       ├── session/mod.rs     # 网课总结：会话目录管理 + 三路共用墙钟 SessionClock（FR-103）
│       ├── screen/            # 网课总结：屏幕捕获（FR-101）
│       │   ├── capture.rs     # WGC 窗口捕获，帧打 session 时间戳
│       │   ├── encode.rs      # 视频硬编切片（mp4 分片 + manifest.jsonl）
│       │   ├── decode.rs      # 录后视频解码（供精细抽帧）
│       │   └── scene_detect.rs # SAD/直方图/pHash 判页抽帧 → frames.json
│       ├── ocr/mod.rs         # 网课总结：课件 OCR（oar-ocr / PP-OCRv5 本地推理，FR-105）
│       ├── align/mod.rs       # 网课总结：音字帧对齐 → aligned.json（FR-106）
│       └── summary/           # 网课总结：云端总结（FR-107/108/109）
│           ├── mod.rs         # 配置/Key(keyring)/草稿读写
│           ├── cloud_api.rs   # OpenAI 兼容 API 客户端
│           ├── prompt.rs      # map/reduce 提示词
│           ├── map_reduce.rs  # 分段总结 + 全量/单段重生成 + 本地兜底
│           └── render.rs      # 时间轴结构 + Markdown 导出
│
├── models/                    # 【运行时】语音模型（.gitignore，不入库）
│
├── sessions/                  # 【运行时】网课总结会话数据（exe 旁，.gitignore）：
│                              #   <id>/video/*.mp4 + manifest.jsonl、audio.wav、transcript.jsonl、
│                              #   frames/ + frames.json、aligned.json、summary*.json、exports/summary.md
│
├── scripts/
│   ├── download-models.js     # 模型自动下载
│   ├── check_all.bat / .sh    # 最小质量门（commit 前必跑）
│   ├── check_docs.py          # 文档规则校验（token 上限/失效链接）
│   └── pre-commit.sample      # 可选 git 钩子骨架
│
├── workflow_output/           # 【所有流程文档根目录】
│   ├── docs/
│   │   ├── 项目分析/项目分析报告.md      # Phase 0：选型/方案对比/风险
│   │   ├── specs/
│   │   │   ├── PRD.md                    # Phase 1：需求 + FR/AC（唯一真相源）
│   │   │   └── architecture.md           # Phase 1：架构/数据流/模块
│   │   ├── plans/
│   │   │   ├── 录音转文字.plan.md            # Phase 2：实现计划/里程碑
│   │   │   ├── 网课录屏总结.plan.md          # Phase 2：网课总结父 plan
│   │   │   └── 网课录屏总结/                  # Phase 2：子 plan（00 横切 ~ 05 集成收尾）
│   │   ├── 测试方案/_模板测试方案.md      # Phase 3：人工测试方案（按需）
│   │   ├── feature-map/_模板.feature-map.md  # Phase 3：功能-代码速查表
│   │   ├── user-ops/录音转文字.用户操作手册.md  # Phase 3：用户操作手册
│   │   ├── run-guide/快速启动速查表.md    # Phase 4：启动速查
│   │   ├── deploy/部署手册.md            # Phase 5：打包分发
│   │   ├── changes/_模板.*.md            # Phase 6：变更记录/影响评估
│   │   ├── adr/ (README.md, _模板.ADR.md)  # 架构决策记录
│   │   └── file_structure.md             # 本文件
│   ├── 项目规范约束/
│   │   ├── AGENTS.md                     # 项目级 AI 指令（每次开工必读）
│   │   └── 通用约束.md
│   └── 开发进度/                          # Phase 3：进度跟踪 + 功能 README
│
├── .claude/                   # Claude Code 配置（hooks：改文档后跑 check_docs）
│   ├── settings.json
│   └── README.md
└── .github/
    ├── prompts/ (1-plan / 2-implement / 3-run / 4-review)  # 可复用 AI 提示
    └── workflows/ci.yml       # 最小 CI（Rust + 前端）
```

## 关于代码目录（重要）

> 工作流模板默认代码放 `PROJECT/backend/frontend/desktop`。**本项目是 Tauri 应用，不采用该拆分**——
> - 前端 = `src/`（Vue 3，Vite 构建入口在根目录）
> - 后端 + 桌面端 = `src-tauri/`（Rust + Tauri，二者合一）
>
> 这是因为 `vite.config.ts`、`tauri.conf.json`（`frontendDist` 指向 `../dist`）、`Cargo.toml`、`package.json` 都依赖此路径结构。搬进 `PROJECT/` 会破坏构建。

## 关键文件清单（AI 必读优先级）

1. [workflow_output/项目规范约束/AGENTS.md](../项目规范约束/AGENTS.md) —— 开工前必读，代码风格/禁忌/check 命令。
2. [workflow_output/docs/specs/PRD.md](specs/PRD.md) —— 做功能前必读。
3. 本文件 —— 找文件时必读。
4. [workflow_output/docs/plans/录音转文字.plan.md](plans/录音转文字.plan.md) + [网课录屏总结.plan.md](plans/网课录屏总结.plan.md) —— 实现时照走。
5. [workflow_output/docs/specs/architecture.md](specs/architecture.md) —— 改代码前看模块/接口。
