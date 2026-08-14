# AGENTS.md · DevPilot 项目宪法

> 每次开工前必读。本文件是项目级 AI 指令与人类约定的地基，Phase 3 持续织入新规范。
> last_updated: 2026-08-14 ｜ 基线工作流：`2_编程类可迭代workflow` v1.20

## 1. 项目定位（一句话）

DevPilot = Win/Mac 桌面端 AI 编程智能体：Codex 级能力 + 内置引导式工作流，服务零代码用户；商业模式 = Token 充值转售。

## 2. 铁律（不可违反）

1. **specs before code**：任何功能先对 PRD（FR/AC），无规格不写码。
2. **未经许可不写码**：实现必须先有计划（plan.md）并获用户批准。
3. **commit 当存档点**：小步提交，message 带 FR 号（`feat: FR-031 需求卡确认锁`）。
4. **每一轮对话结束更新开发进度**。
5. **钱和安全不留情面**：凡涉及计费/扣费/沙箱/密钥的改动，必须双人审视（用户 + 第二模型 review），测试覆盖率红线见 testing_strategy §6。
6. **工具服务于流程**：任何 SDD 工具/脚本的引入服从工作流，不反向绑架。

## 3. 技术约定

- **桌面端**：Tauri 2 + Rust（workspace 多 crate，见 architecture §3）+ React 19 + TypeScript + Vite + Tailwind/Radix。
- **云端**：NestJS + PostgreSQL（Flyway 迁移）+ Redis；统一 `R<T>` 响应封装 `{code, msg, data}`；金额一律 `*_cents` 整数；写接口必幂等。
- **本地存储**：SQLite（sqlx 迁移，L 版本号）。
- **客户端不持有任何上游模型 Key**——全部走自家网关（ADR-003），违者视为严重事故。
- **命名**：代码标识符英文；UI 文案中文；文档中文为主、技术术语保留英文并括注大白话。
- **提交规范**：`feat:/fix:/docs:/refactor:/chore: 中文描述（FR-xxx）`。
- **git 入库注意**：父仓库根 .gitignore 有 `docs/` 全局忽略——本项目文档靠 `!DevPilot/workflow_output/docs/` 放开，新增 docs 外目录时自查 `git status` 是否漏文件。
- **本地依赖审计降级**：cargo/npm audit 本地网络受限时仅 WARN，硬拦截在 CI（GitHub Actions）。

## 4. 文档规范

- `workflow_output/` 下文档单文件 ≤5000 tokens，超限拆分 + 总路由索引。
- 专业术语首次出现行内括注大白话 + 文底术语表。
- 关键文档头部维护 `last_updated`。
- 文档规则机器校验：`scripts/check_docs.py`（.claude hooks 触发）。

## 5. 目录与产物

- 目录结构与职责见 [../docs/file_structure.md](../docs/file_structure.md)，新增目录必须同步更新它。
- 功能完成产：功能 README + Feature Map +（B/C 类）User-Ops，DoD 清单见 `workflow_output/开发进度/功能完成DoD清单.md`（Phase 3 建立）。

## 6. 当前阶段（每次交接必更新——新会话的接力棒）

**Phase 3 进行中（2026-08-14）**：P01 客户端骨架与状态机引擎 · Step 1/9 已完成（commit 27da741）。进度台账见 [../开发进度/开发进度总览.md](../开发进度/开发进度总览.md)。

- 已完成：Phase 0 设计 → Phase 1 规格 → P01 测试方案 + Step 1 脚手架（check_all 全绿）
- 当前：P01 Step 2（Rust workspace 8 crates 骨架）/ Step 3（前端设计令牌与三栏骨架），同批可并行
- P01 之后下一张 plan：P02 云端骨架：账号计费与模型网关（`/phase2 P02_云端骨架：账号计费与模型网关`）
- 新会话开工顺序：读本文件 → 读 plans/00 索引 + 开发进度总览确认当前位置 → 按接力指引行动
