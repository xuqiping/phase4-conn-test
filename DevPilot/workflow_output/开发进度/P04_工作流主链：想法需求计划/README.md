# P04 工作流主链：想法需求计划 · 交付 README

## 功能范围

覆盖 FR-008（AGENTS.md 自动维护）、FR-030（想法打磨向导）、FR-031（需求确认卡片）、FR-032（施工计划审批）、FR-044（主动反问澄清）。

## 交付物清单

- **后端代码**
  - `core-state/src/agent_config.rs`：项目约定字段 JSON 存取与 `AGENTS.md` 渲染。
  - `core-state/src/spec_card.rs`：需求卡 CRUD、`all_resolved` 判断。
  - `core-state/src/plan_chunk.rs`：施工计划 chunk CRUD、`approve_all` / `revoke_approval`。
  - `src-tauri/src/commands.rs`：Tauri IPC 命令 + S6 状态机联动（`auto_pass_gate` / `auto_unpass_gate` / `emit_current_state`）。
- **前端代码**
  - `src-ui/lib/generator.ts`：结构化生成管线，统一处理 JSON 提取与追问分支。
  - `src-ui/lib/cloudApi.ts`：`/gateway/complete` 非流调用封装。
  - `src-ui/components/agent/AgentConfigForm.tsx` / `AgentConfigModal.tsx`：项目约定表单。
  - `src-ui/views/Idea.tsx`：想法访谈向导与报告生成。
  - `src-ui/views/Spec.tsx`：需求确认卡片列表与确认流。
  - `src-ui/views/Plan.tsx`：chunk 看板、审批/撤销审批、开工。
  - `src-ui/components/clarify/ClarifyDialog.tsx`：追问弹窗。
  - `src-ui/hooks/useClarifyRound.ts`：追问轮次上限控制。
- **数据迁移**
  - `core-state/migrations/L7__workflow_artifacts.sql`：新增 `agent_configs` / `spec_cards` / `plan_chunks`。
- **文档**
  - `workflow_output/docs/feature-map/P04_工作流主链：想法需求计划.feature-map.md`
  - `workflow_output/docs/user-ops/P04_工作流主链：想法需求计划用户操作手册.md`
  - `workflow_output/docs/测试方案/P04_工作流主链：想法需求计划测试方案.md`
  - `workflow_output/docs/specs/db_schema.md`（已更新 L7）
  - `workflow_output/docs/plans/00_MVP实现计划总索引.md`（P04 已交付）

## 测试成绩

- Rust workspace：80+ 单测全绿（含新增 `commands::tests` 状态机联动用例）。
- 桌面前端：35 单测全绿。
- 云端：e2e 40/40 全绿。
- `bash scripts/check_all.sh`：EXIT=0（本地 cargo/npm audit 因网络/镜像降级为 WARN，CI 兜底）。

## 已知限制与后续依赖

- **真实 LLM 冒烟**：自动化测试使用 mock 后端；真实模型调用需人工走「创建项目 → 想法问答 → 生成报告 → 确认需求卡 → 审批计划 → 开工进入 build」完整链路。
- **产物版本**：`spec_cards` / `plan_chunks` 重新生成前直接清空旧记录，未做历史备份；后续可按运维清单扩展 `_bak` 表。
- **余额前置拦截**：生成管线已返回 `cost_cents`，但 UI 尚未在点击生成前调用 `/gateway/estimate` 强拦截余额不足场景（P02 能力已就绪，可后续接入）。
- **报告产物索引**：`artifacts` 表尚未写入报告/卡片/plan 版本记录，留给 P05/P06 统一产物管理。

## 运维要点

- 本地库路径：`~/.devpilot/devpilot.db`（WAL 模式）。
- 状态机门禁：
  - 全部需求卡 confirmed/skipped 后自动解锁 `requirement_confirm`。
  - 审批计划后自动解锁 `kickoff`；撤销审批后回退 `kickoff`。
- 追问上限：单阶段最多 3 轮，由 `useClarifyRound` 统一控制。
- 所有文件写入限制在项目目录 `workflow_output/` 内，受 P03 沙箱约束。

## 下一张 plan

P05_建造期：任务编排与大白话层（FR-013、015、036、037、038）。
