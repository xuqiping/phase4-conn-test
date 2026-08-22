# File Structure · DevPilot 文件与目录结构说明

> Context Engineering 核心产物：让 AI（和新人）一眼看懂每个目录干什么。新增/删除目录时同步更新本文件。
> **last_updated: 2026-08-13**

## 目录树

```
DevPilot/
├── workflow_output/            # 流程产出文档根目录
│   ├── docs/
│   │   ├── 项目分析/            # Phase 0：设计总路由 + 商业分析 + 功能规格 + UI + 引擎 + 架构路线 + prototypes/
│   │   ├── specs/              # Phase 1：PRD（+功能需求/验收标准拆分）+ architecture + db_schema + testing/security/performance
│   │   ├── adr/                # 架构决策记录（ADR-001~003 + README 索引）
│   │   ├── plans/              # Phase 2：实现计划（待产）
│   │   ├── 测试方案/            # Phase 3：需人工测试功能的方案（待产）
│   │   ├── feature-map/        # Phase 3：功能-代码速查表（待产）
│   │   ├── user-ops/           # Phase 3：用户操作手册（待产）
│   │   ├── run-guide/          # Phase 4：快速启动 + 性能评测报告（待产）
│   │   ├── deploy/             # Phase 5：部署手册（待产）
│   │   ├── changes/            # Phase 6：变更记录（待产）
│   │   └── file_structure.md   # 本文件
│   ├── 项目规范约束/            # AGENTS.md（项目宪法）+ 通用约束
│   └── 开发进度/                # 进度总览 + 每功能进度 + 功能 README（Phase 3 起）
├── PROJECT/                    # 实际代码（Phase 3 起建）
│   ├── desktop/                # Tauri 2 客户端（Rust workspace + React UI）
│   │   ├── src-tauri/          # Rust 内核：crates（core-state/orchestrator/sandbox/exec/meter/skills/mcp/cli）
│   │   │   └── crates/         # P07 新增：core-skills（skill_file/registry/generator）、core-mcp（manager/rpc/market + assets/market_catalog.json + tests/ 真进程集成测试）
│   │   └── src-ui/             # React 前端：views / components / stores / lib
│   │       └── components/     # P07 新增：input/（SkillAutocomplete/TaskInputBox/AttachmentChips/VoiceDictation）、settings/（McpPanel/McpMarket）、views/Mcp.tsx
│   ├── cloud/                  # NestJS 云端：auth / billing / gateway / skills-market
│   └── cli/                    # devpilot CLI 入口（薄壳，转发 core-cli）
├── scripts/                    # check_all 最小质量门 + check_docs
├── .github/
│   ├── prompts/                # 1-plan / 2-implement / 3-run / 4-review
│   └── workflows/ci.yml        # 最小 CI（双端 matrix）
└── .claude/                    # skills / agents / hooks（对齐 Claude 生态）
```

## 目录职责说明

| 目录/文件 | 职责 | 谁来写 |
|---|---|---|
| workflow_output/docs/项目分析/ | Phase 0 设计与商业分析（含 prototypes/ 高保真原型） | Phase 0 ✅ |
| workflow_output/docs/specs/ | 规格，唯一真相源 | Phase 1 ✅ |
| workflow_output/docs/adr/ | 关键架构决策（推翻常规的决定必写） | Phase 1/3/6 按需 |
| workflow_output/项目规范约束/AGENTS.md | 项目宪法：代码风格/禁忌/文档规范 | Phase 1 初版 ✅，Phase 3 持续织入 |
| workflow_output/开发进度/ | 每轮对话结束必更新 | Phase 3 |
| PROJECT/desktop/ | Tauri 客户端全部代码 | Phase 3 |
| PROJECT/cloud/ | 云端薄层（账号/计费/网关） | Phase 3 |
| PROJECT/cli/ | CLI 薄壳 | Phase 3（二期功能） |
| scripts/check_all | commit 前必跑最小质量门 | Phase 2 配置 |

## 关键文件清单（AI 必读优先级）

1. **workflow_output/项目规范约束/AGENTS.md** —— 每次开工前必读
2. **workflow_output/docs/specs/PRD.md**（+功能需求/验收标准）—— 做任何功能前必读
3. **本文件** —— 找文件时必读
4. 对应 **plans/<功能>.plan.md** —— 实现时按它走
5. 各 specs/plans **底部术语表**
