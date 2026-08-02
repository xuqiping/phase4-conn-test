# File Structure · 文件与目录结构说明

> 这是 Context Engineering 的核心产物：让 AI agent（和新加入的人）一眼看懂每个文件/目录干什么。
> 维护：每次新增/删除目录，**同步更新本文件**。它和 AGENTS.md 是 AI 的「入职手册」。
> **文档规模**：本文件不得超过 5000 tokens。目录结构复杂时，按子系统/模块拆分子文件（如 `file_structure.后端.md`），本文件保留总览索引。

## 目录树

<!-- 参照 ../README.md 的完整树，按本项目实际情况填充 -->

```
<项目名>/
├── workflow_output/       # 所有流程产出文档的根目录
│   ├── docs/              # Phase 0~6 文档产物
│   │   ├── 项目分析/       # Phase 0：商业前置深度报告（竞品/受众/商业模式/定价）
│   │   ├── specs/          # Phase 1：规格文档，唯一真相源（PRD 等）；底部带术语表
│   │   ├── plans/          # Phase 2：实现计划，逐步骤带复选框；底部带术语表
│   │   ├── 测试方案/        # Phase 3：需人工测试的功能的人工测试方案（按需，非每功能必产）
│   │   ├── feature-map/    # Phase 3：粗略功能-代码速查表（技术维护用）
│   │   ├── user-ops/       # Phase 3：用户操作手册（傻瓜式操作步骤，B/C 类用户可见功能才产）
│   │   ├── run-guide/      # Phase 4：快速启动速查表（项目怎么跑起来）
│   │   ├── deploy/         # Phase 5：部署手册
│   │   ├── changes/        # Phase 6：变更记录 + 影响评估
│   │   └── file_structure.md  # 本文件
│   ├── 项目规范约束/        # 项目级规范，AI 指令 + 文档写作规范（AGENTS.md + 通用约束 + XX约束）
│   └── 开发进度/            # Phase 3：进度跟踪（总览 + 每功能逐步骤）+ 功能 README
├── PROJECT/               # 实际代码
│   ├── backend/           # TODO: 说明（语言/框架/职责）
│   ├── frontend/          # TODO: 说明
│   └── desktop/           # TODO: 说明（若有桌面客户端）
└── .github/prompts/       # 可复用 AI 提示（1-plan / 2-implement / 3-run / 4-review）
```

## 目录职责说明

| 目录/文件 | 职责 | 谁来读 | 谁来写 |
|---|---|---|---|
| workflow_output/docs/项目分析/ | 商业可行性、竞品、受众、变现 | 决策者、PM | Phase 0 |
| workflow_output/docs/specs/ | 需求规格，唯一真相源；底部术语表 | 全员、AI | Phase 1 |
| workflow_output/docs/plans/ | 实现计划；底部术语表 | 开发、AI | Phase 2 |
| workflow_output/docs/测试方案/ | 需人工测试的功能的人工测试方案（按需） | 开发、测试、AI | Phase 3 |
| workflow_output/docs/feature-map/ | 粗略功能-代码速查表：代码位置 + 调用链 + 技术原理注解 + 建表注解 | 开发、维护、AI | Phase 3 |
| workflow_output/docs/user-ops/ | 用户操作手册：傻瓜式操作步骤（B/C 类用户可见功能才产） | 测试、用户、客服、AI | Phase 3 |
| workflow_output/docs/run-guide/ | 快速启动速查表：项目组成/启动命令/端口 | 开发、运维、新成员 | Phase 4 |
| workflow_output/docs/deploy/ | 部署手册：环境/软件/部署步骤/回滚 | 运维、DevOps | Phase 5 |
| workflow_output/docs/changes/ | 变更记录 + 影响评估 | 开发、维护 | Phase 6 |
| workflow_output/项目规范约束/ | 代码规范、AI 指令、文档写作规范 | 全员、AI（每次必读） | Phase 0 起，Phase 3 持续 |
| workflow_output/开发进度/ | 进度跟踪 + 功能 README（按受众写用户地图/技术说明） | 开发、决策者 | Phase 3 |
| PROJECT/ | 实际代码 | 开发、AI | Phase 3 |

## 关键文件清单（AI 必读优先级）

1. **workflow_output/项目规范约束/AGENTS.md** —— 每次开工前必读，定义代码风格、禁忌、文档写作规范。
2. **workflow_output/docs/specs/PRD.md** —— 做任何功能前必读，明确要建什么。
3. **workflow_output/docs/file_structure.md**（本文件）—— 找文件时必读。
4. 对应的 **workflow_output/docs/plans/<功能>.plan.md** —— 实现时按它走。
5. 对应的 **workflow_output/docs/测试方案/<功能>测试方案.md**（若有）—— 需人工测试的功能，开发与验收时照它走。
6. 各 specs/plans **底部的术语表** + **workflow_output/开发进度/<功能>/README.md** —— 看不懂专业词、想了解功能价值时查。
