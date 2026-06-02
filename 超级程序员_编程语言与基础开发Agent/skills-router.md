# skills-router.md — Skill Router

## Top-Level Skill

- **Name**: `super_programmer_programming_language_agent_skill`
- **Purpose**: 执行编程语言与基础开发领域的具体任务，覆盖后端语言、前端框架、编译原理与底层基础、低代码/无代码四大子域。
- **Skill File Path**: `all_agents/超级程序员_编程语言与基础开发Agent/skills-router.md`

## Derivative Skills

| Skill Name | Purpose | Workflow File | Notes |
|------------|---------|---------------|-------|
| `super_programmer_programming_language_agent_skill___backend_language` | 后端编程语言执行 | workflow/backend_language_workflow.md | Java/Python/Go/C++/Rust/PHP/Node.js |
| `super_programmer_programming_language_agent_skill___frontend_framework` | 前端框架执行 | workflow/frontend_framework_workflow.md | Vue/React/Angular/JS/TS/uni-app/小程序 |
| `super_programmer_programming_language_agent_skill___compiler_fundamentals` | 编译原理与底层基础执行 | workflow/compiler_fundamentals_workflow.md | 数据结构/算法/OS/编译原理/设计模式 |
| `super_programmer_programming_language_agent_skill___lowcode_nocode` | 低代码与无代码执行 | workflow/lowcode_nocode_workflow.md | 平台选型/企业落地/创业应用 |

## Knowledge Base Link

- **Base Path**: `Agents知识库/0_超级编程行业知识库`
- **Main Index**: `Agents知识库/0_超级编程行业知识库/00_总索引.md`
- **Module Main File**: `Agents知识库/0_超级编程行业知识库/01_编程语言与核心技能.md`
- **Detail Directory**: `Agents知识库/0_超级编程行业知识库/编程语言与核心技能/`

## Evolution Rules

1. 新增能力时先检查是否已有同名衍生技能；如有则更新，如无则新建。
2. 工作流中禁止嵌入知识库全文，一律使用 `[参考: <path>]` 引用。
3. 若知识库原文更新，子Agent下次执行时自动读取最新内容。
