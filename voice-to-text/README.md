# Voice to Text · 实时语音转文字

基于 **Tauri 2 + Rust + Vue 3 + sherpa-onnx** 的本地实时语音转文字桌面应用。支持**麦克风输入**与**系统音频内录**（WASAPI Loopback），无需联网即可中英文实时识别——所有语音数据本地处理，隐私零外泄。

> 本项目文档按「编程类可迭代工作流」（Spec → Plan → Implement → Run）组织，全部位于 [workflow_output/](workflow_output/)。下方导航表是入口。

## 技术栈

| 层 | 技术 |
|---|---|
| 前端 | Vue 3 + Pinia + Vite + vue-tsc |
| 桌面端 / 后端 | Rust + Tauri 2 |
| 音频采集 | cpal + WASAPI（麦克风 / 系统音频内录） |
| 语音识别 | sherpa-onnx（ZipFormer 本地流式模型） |
| 打包 | Tauri CLI（.msi / .exe） |

## 文档导航

| 想做什么 | 看哪里 |
|---|---|
| 了解要做什么、需求清单 | [PRD](workflow_output/docs/specs/PRD.md) |
| 系统架构、数据流、模块 | [架构规格](workflow_output/docs/specs/architecture.md) |
| 技术选型为什么这么定 | [项目分析报告](workflow_output/docs/项目分析/项目分析报告.md) |
| 实现计划、里程碑 | [实现计划](workflow_output/docs/plans/录音转文字.plan.md) |
| 项目怎么跑起来 | [快速启动速查表](workflow_output/docs/run-guide/快速启动速查表.md) |
| 打包与分发 | [部署手册](workflow_output/docs/deploy/部署手册.md) |
| 怎么用（傻瓜式操作） | [用户操作手册](workflow_output/docs/user-ops/录音转文字.用户操作手册.md) |
| AI / 开发约定 | [AGENTS.md](workflow_output/项目规范约束/AGENTS.md) |
| 目录结构说明 | [file_structure.md](workflow_output/docs/file_structure.md) |

## 快速开始

```bash
npm install              # 装前端依赖
npm run download-models  # 下语音模型（约 100MB，首次）
npm run tauri:dev        # 启动开发（Vite + Tauri 窗口，热重载）
```

> 完整步骤见 [快速启动速查表](workflow_output/docs/run-guide/快速启动速查表.md)。

## 许可证

MIT。语音模型来源 [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)（Apache 2.0）。
