# P03 本地执行与沙箱 · 交付 README

## 功能范围

覆盖 FR-001（本地隔离执行）、FR-003（全流程闭环）、FR-005（自动环境配置）、FR-009（两档审批模式）、FR-012（Secrets 管理）。

## 交付物清单

- **后端代码**
  - `core-sandbox`：路径归一化、沙箱策略、危险命令审批门。
  - `core-exec`：异步命令执行器、环境探测与缓存、一键安装、任务 runner、Secrets 注入/脱敏。
  - `core-state`：`pending_approvals`、`env_profiles`、`secrets` 三张表及 CRUD。
  - `src-tauri/src/commands.rs`：全部 Tauri IPC 命令（含 `execute_build` 状态机触发）。
- **前端代码**
  - `InstallWizard.tsx`：环境一键安装向导。
  - `TaskRunnerPanel.tsx`：任务执行结果展示。
  - `SecretsPanel.tsx`：Secrets 增删改查。
  - `BuildButton.tsx`：建造阶段一键执行构建。
- **文档**
  - `workflow_output/docs/feature-map/P03_本地执行与沙箱.feature-map.md`
  - `workflow_output/docs/user-ops/P03_本地执行与沙箱用户操作手册.md`
  - `workflow_output/docs/specs/db_schema.md`（已更新 L4~L6）
  - `workflow_output/docs/plans/00_MVP实现计划总索引.md`（P03 已交付）

## 测试成绩

- Rust workspace：66 单测全绿。
- 桌面前端：29 单测全绿。
- 云端：e2e 40/40 全绿。
- `bash scripts/check_all.sh`：EXIT=0（本地 cargo/npm audit 因网络/镜像降级为 WARN，CI 兜底）。

## 已知限制与后续依赖

- **修复策略**：runner 的 `FixStrategy` MVP 为 `NoOpFixStrategy`，真正 LLM 自动修复依赖 P04/P05 的云端 chat/estimate 接入。
- **审批自动挂起**：当前 runner 未在命令执行前调用 `ApprovalGate` 自动挂起；审批通过独立 Tauri 命令管理，自动挂起留给 P06 安全卡点。
- **日志透明层**：runner/install 已预留 `on_event` 回调，但前端未接入实时流（FR-038）。
- **真实冒烟**：需人工用真实项目走「创建 → 需求 → 计划 → 建造 → 执行 → 验收」完整链路；本 README 基于自动化测试。

## 运维要点

- 本地库路径：`~/.devpilot/devpilot.db`（WAL 模式）。
- Secrets 加密密钥派生自设备指纹；可通过环境变量 `DEVPILOT_MASTER_KEY` 覆盖（用于企业部署统一密钥）。
- 所有 `git commit` 存档点带 `--no-verify`，避免用户本地 hook 干扰。

## 下一张 plan

P04_工作流主链：想法需求计划（FR-030/031/032/044/008）。
