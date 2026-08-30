# AGENTS.md · 项目级 AI 指令

> Context Engineering 核心产物。AI agent 每次开工前必读，定义「行为准则」。
> 等价于 CLAUDE.md / GEMINI.md / copilot-instructions.md，用 AGENTS.md 通用命名。
> **last_updated: 2026-08-05**（Phase 4/5 收尾时核对本文件是否与代码现状矛盾）。
> **防膨胀**：聚焦「每次开工都需要的规则」，目标 ≤400 行；细节拆 `通用约束.md`。

## 项目宪法（不可协商 · IMMUTABLE）

**工作流底线（所有项目通用，不删）**：
- **specs before code**：没规格不写码。
- **commit 当存档点**：每 chunk 测试通过即提交。
- **再信任也要 review**：AI 写的代码当初级开发的提交，读、跑、测 + 第二个 AI 审。
- **commit 前必跑 `scripts/check_all`**：不绕过质量门。

**本项目特有红线**：
- **隐私优先**：所有语音数据仅在本地处理，**绝不联网上传**任何音频/识别结果。新增任何网络请求需显式 review。
- **【例外·网课总结功能】**（2026-08-05 用户 review 批准）：「网课录屏智能学习总结」功能的 AI 总结步骤，在「用户授权 + 自带 API Key + 默认仅传文字（多模态精修开关默认关）」前提下，**允许将转写文字与课件 OCR 文本上传至用户配置的云端 LLM**；音视频原文件永不离开本机。其余功能仍遵守「绝不联网上传」。详见 [网课录屏总结.plan.md](../docs/plans/网课录屏总结.plan.md)「隐私红线显式 review」。
- **模型路径不硬编码**：模型路径由 `src-tauri/src/lib.rs` 从可执行文件目录解析，新增代码不要写死绝对路径。
- **不入库的产物**：`models/`（模型）、`src-tauri/target/`（构建）、`node_modules/`、`dist/` 永远不提交，已在 `.gitignore`。
- **分发用 MSI 不用 NSIS**：NSIS 安装包有 DLL 缺失 bug，打包/分发默认 MSI（详见 [部署手册](../docs/deploy/部署手册.md)）。

## 通用规则（CORE RULES）

### 代码风格
- **前端**：Vue 3 `<script setup lang="ts">` + Composition API；状态用 Pinia（`src/stores/`）；样式随现有组件风格。
- **后端**：Rust，模块按 `src-tauri/src/{audio,stt}/` 分；Tauri 命令注册在 `lib.rs`，命令加 `#[tauri::command]`。
- **类型检查**：前端 `vue-tsc --noEmit`（已并入 `npm run build`）；后端 `cargo check`。

### 禁忌清单（anti-patterns · 不要做）
- **不**手改 `src-tauri/target/` 下任何生成物（构建产物）。
- **不**把 `models/`、`*.dll`、`target/` 提交进仓库。
- **不**在前端硬编码模型路径或本地文件路径——音频/文件操作走 Tauri IPC 命令。
- **不**绕过 `check_all` 提交（`--no-verify` 仅紧急人工场景）。
- **不**编造：不引用不存在的 sherpa-onnx API / Tauri 命令（与下方反幻觉条款呼应）。

### 偏好（优先这么做）
- 新增音频/识别能力优先扩展 `audio/`、`stt/` 现有模块，而非新建平级模块。
- 跨平台差异（系统音频仅 Windows）在代码里用 `#[cfg(target_os = "windows")]` 隔离，保留 macOS/Linux 麦克风可用。
- 修 bug 时在注释简述理由。

## 反幻觉条款（硬性）
- 不确定或缺上下文时，**先问，不要编**。
- 不引用不存在的函数 / crate / Tauri API。sherpa-rs 绑定不完善时走 C FFI，不要假设高层 API 存在。
- 修 bug 时说明理由（注释或对话）。

## 工作流约束
- **specs before code**：开工前先读 [PRD](../docs/specs/PRD.md)。
- **plan before implement**：按 [实现计划](../docs/plans/录音转文字.plan.md) 走，逐步骤勾选。
- **commit 当存档点**：每完成一个 chunk（`check_all` 全绿）立即建议提交。
- **commit 前必跑最小质量门（硬性）**：`scripts/check_all.bat`（Windows）或 `scripts/check_all.sh`，全绿才提交；失败日志贴回给 AI 修。
- **追溯编号**：PRD 的 `FR-xxx`/`AC-xxx` 为出处；plan Step 标 FR；commit message 带号（如 `feat: FR-004 录音保存 WAV`）。
- **每一轮对话结束更新开发进度**：`workflow_output/开发进度/<功能>/开发进度n.md`。
- **never commit code you can't explain**：看不懂的代码先加注释或简化。

## 技术栈速查（改代码前对照）
- 前端：Vue 3 + Pinia + Vite；入口 `src/main.ts`；IPC 通信核心 `src/stores/app.ts`。
- 后端：Rust + Tauri 2；命令 `start_recording` / `stop_recording` / `list_audio_devices`（`src-tauri/src/lib.rs`）。
- 识别：sherpa-onnx 在线流式（ZipFormer 双语 small）；识别器 `src-tauri/src/stt/recognizer.rs`，含重采样。
- 音频：cpal（麦克风）+ WASAPI loopback（系统音频，仅 Windows）；采集抽象 `src-tauri/src/audio/capture.rs`。
- 打包：`npm run tauri:build`，优先 MSI。

## 文档写作规范
- **单文件 5000 tokens 上限**：`workflow_output/` 下所有文档不得超过；接近 4000 预警，超限拆子文件 + 总路由索引。
- **功能 README**：功能完成时产 `workflow_output/开发进度/<功能>/README.md`，按受众（A 技术 / B 用户 / C 两者）判定。
- **开发进度**：每轮对话结束记 `开发进度n.md`。
- **术语批注**：specs/plans 专业术语首次行内括注 + 文档底部术语表。

## 模块级约束（按需新增 XX约束.md 并在此索引）
- [通用约束.md](通用约束.md) —— 跨所有模块

## 参考文档
- 项目结构 → [file_structure.md](../docs/file_structure.md)
- 需求规格 → [PRD.md](../docs/specs/PRD.md)
- 架构 → [architecture.md](../docs/specs/architecture.md)
- 启动 → [快速启动速查表](../docs/run-guide/快速启动速查表.md)
- 部署 → [部署手册](../docs/deploy/部署手册.md)
- 用户操作 → [用户操作手册](../docs/user-ops/录音转文字.用户操作手册.md)
