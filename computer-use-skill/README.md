# 项目模板 · 标准脚手架

> 用法：新项目 Phase 0 结束后，**拷贝整个 `项目模板/` 目录**为新项目根，改名为项目名，然后从 Phase 1 开始填。
> 对应工作流：[../0_工作流总览.md](../0_工作流总览.md)

## 完整目录树（拷贝后长这样）

```
<项目名>/
├── README.md                         ← 项目说明（本文件改写而来）
├── workflow_output/                  ← 所有流程产出文档的根目录
│   ├── docs/                         ← Phase 0~6 文档产物
│   │   ├── 项目分析/                 ← Phase 0 产物
│   │   │   └── 项目分析报告.md
│   │   ├── specs/                    ← Phase 1 产物（specs before code，底部含术语表）
│   │   │   ├── PRD.md                ← 需求文档（含验收标准 FR/AC）
│   │   │   ├── architecture.md       ← 架构规格
│   │   │   ├── db_schema.md          ← 全局数据库设计文档（ER图+数据字典+Flyway清单）
│   │   │   ├── testing_strategy.md   ← 测试策略
│   │   │   ├── security_strategy.md  ← 安全策略
│   │   │   └── performance_goals.md  ← 性能目标
│   │   ├── plans/                    ← Phase 2 产物（实现计划，底部含术语表）
│   │   │   └── _模板.plan.md
│   │   ├── 测试方案/                 ← Phase 3 产物（仅需人工测试的功能才产）
│   │   │   └── _模板测试方案.md
│   │   ├── feature-map/              ← Phase 3 产物：粗略功能-代码速查表
│   │   │   └── _模板.feature-map.md
│   │   ├── user-ops/                 ← Phase 3 产物：傻瓜式用户操作手册（B/C 类功能才产）
│   │   │   └── _模板.用户操作手册.md
│   │   ├── run-guide/                ← Phase 4 产物：快速启动速查表
│   │   │   └── _模板.快速启动速查表.md
│   │   ├── deploy/                   ← Phase 5 产物：部署手册
│   │   │   └── _模板.部署手册.md
│   │   ├── changes/                  ← Phase 6 产物：变更记录 + 影响评估
│   │   │   ├── _模板.变更记录.md
│   │   │   └── _模板.影响评估.md
│   │   ├── adr/                      ← 架构决策记录（为什么选 A 不选 B，推翻常规做法必写）
│   │   │   ├── README.md             ← ADR 索引总表
│   │   │   └── _模板.ADR.md
│   │   └── file_structure.md         ← Context Engineering 核心：告诉 AI 每个目录干嘛
│   ├── 项目规范约束/                  ← Context Engineering 核心（持续织入）
│   │   ├── AGENTS.md                 ← 项目级 AI 指令 + 文档写作规范
│   │   ├── 通用约束.md
│   │   └── XX约束.md                 ← 按模块/主题拆
│   └── 开发进度/                      ← Phase 3 产物
│       ├── 开发进度总览.md
│       ├── _模板功能README.md        ← 功能 README 骨架（受众判定 A/B/C）
│       └── <功能名>/                 ← 每个功能一个目录
│           ├── <功能名>开发进度总览.md
│           ├── <功能名>开发进度1.md
│           └── README.md             ← 功能 README（用户地图 / 技术说明）
├── PROJECT/                          ← 实际代码
│   ├── backend/
│   ├── frontend/
│   └── desktop/                      ← 桌面客户端（若有）
├── scripts/                          ← 最小质量门与自动化脚本
│   ├── check_all.bat                 ← Windows：commit 前必跑（lint+类型检查+测试一条命令）
│   ├── check_all.sh                  ← Linux/Mac：同上
│   ├── check_docs.py                 ← 文档规则校验（tokens 上限/失效链接/孤立文档，已并入 check_all）
│   └── pre-commit.sample             ← 可选：git pre-commit 钩子骨架（自动跑 check_all）
├── .claude/                          ← Claude Code 配置骨架（hooks：改文档后自动跑 check_docs）
│   ├── settings.json
│   └── README.md
└── .github/
    └── prompts/                      ← 可复用 AI 提示（Plan-Implement-Run）
        ├── 1-plan.prompt.md
        ├── 2-implement.prompt.md
        ├── 3-run.prompt.md
        └── 4-review.prompt.md
```

## 各目录与 Phase 对应

| 目录 | 哪个 Phase 产出 | 作用 |
|---|---|---|
| workflow_output/docs/项目分析/ | Phase 0 | 商业前置深度报告 |
| workflow_output/docs/specs/ | Phase 1 | 规格文档（PRD 等），唯一真相源；底部带术语表 |
| workflow_output/docs/plans/ | Phase 2 | 实现计划，逐步骤带复选框；底部带术语表 |
| workflow_output/docs/测试方案/ | Phase 3 | 需人工交互测试的功能的人工测试方案（**不需要人工测试的功能不产**） |
| workflow_output/docs/feature-map/ | Phase 3 | 粗略功能-代码速查表：代码位置 + 调用链 + 技术原理注解 + 建表注解 |
| workflow_output/docs/user-ops/ | Phase 3 | 细化用户操作手册：傻瓜式操作步骤（**B/C 类用户可见功能才产**，A 类技术功能不产） |
| workflow_output/docs/run-guide/ | Phase 4 | 快速启动速查表：项目组成 / 启动命令 / 端口 |
| workflow_output/docs/deploy/ | Phase 5 | 部署手册：环境 / 软件 / 部署步骤 / 回滚 |
| workflow_output/docs/changes/ | Phase 6 | 变更记录 + 影响评估 |
| workflow_output/docs/adr/ | Phase 1/3/6（按需） | 架构决策记录：关键决策的背景/备选/决定/代价，防「AI 新会话好心改回去」 |
| workflow_output/docs/file_structure.md | Phase 1 | 目录结构说明，AI 导航用 |
| workflow_output/项目规范约束/AGENTS.md | Phase 0 起，Phase 3 持续更新 | 项目级 AI 指令 + 文档写作规范 |
| workflow_output/开发进度/ | Phase 3 | 进度跟踪（总览 + 逐步骤）+ **功能 README**（按受众写用户地图/技术说明） |
| PROJECT/ | Phase 3 | 实际代码 |
| scripts/ | Phase 0 自带 | 最小质量门 check_all（commit 前必跑）+ 可选 pre-commit 钩子 |
| .github/prompts/ | Phase 2/3/4 调用 | plan/implement/run/review 提示词 |

## 快速开始

1. 拷贝本目录 → 改名为项目名。
2. 改写本 README 为项目说明。
3. 走 **Phase 0**：填 `workflow_output/docs/项目分析/项目分析报告.md`，建 `workflow_output/项目规范约束/AGENTS.md` 初版；按技术栈改好 `scripts/check_all` 里的检查命令。
4. 走 **Phase 1**：填 `workflow_output/docs/specs/PRD.md`（含底部术语表），写 `workflow_output/docs/file_structure.md`。
5. 之后按 Phase 2→5 推进。
