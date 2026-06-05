# skills-router.md — Skill Router

## Top-Level Skill
- **Name**: `super_programmer_big_data_agent_skill`
- **Purpose**: 执行大数据技术生态领域的具体任务。
- **Skill File Path**: `all_agents/超级程序员_大数据技术生态Agent/skills-router.md`

## Derivative Skills
| Skill Name | Purpose | Workflow File | Notes |
|------------|---------|---------------|-------|
| `super_programmer_big_data_agent_skill___big_data_components` | 大数据基础组件执行 | workflow/big_data_components_workflow.md | 大数据基础组件：Hadoop/Spark/Flink生态、数据同步工具 |
| `super_programmer_big_data_agent_skill___data_governance` | 数据治理执行 | workflow/data_governance_workflow.md | 数据治理：数据标准、数据质量、数据血缘、元数据管理 |
| `super_programmer_big_data_agent_skill___bi_analytics` | BI可视化与数据分析执行 | workflow/bi_analytics_workflow.md | BI可视化与数据分析：BI平台、数据分析方法论、自助式报表 |

## Knowledge Base Link
- **Base Path**: `Agents知识库/0_超级编程行业知识库`
- **Main Index**: `Agents知识库/0_超级编程行业知识库/00_总索引.md`
- **Module Main File**: `Agents知识库/0_超级编程行业知识库/06_大数据处理与BI.md`
- **Detail Directory**: `Agents知识库/0_超级编程行业知识库/大数据处理与BI/`

## Evolution Rules
1. 新增能力时先检查是否已有同名衍生技能；如有则更新，如无则新建。
2. 工作流中禁止嵌入知识库全文，一律使用 `[参考: <path>]` 引用。
3. 若知识库原文更新，子Agent下次执行时自动读取最新内容。