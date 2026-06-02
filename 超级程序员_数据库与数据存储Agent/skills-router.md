# skills-router.md — Skill Router

## Top-Level Skill

- **Name**: `super_programmer_database_storage_agent_skill`
- **Purpose**: 执行数据库与数据存储领域的具体任务，覆盖关系型数据库、NoSQL数据库、数据仓库与湖仓一体、数据库运维与调优四大子域。
- **Skill File Path**: `all_agents/超级程序员_数据库与数据存储Agent/skills-router.md`

## Derivative Skills

| Skill Name | Purpose | Workflow File | Notes |
|------------|---------|---------------|-------|
| `super_programmer_database_storage_agent_skill___relational_database` | 关系型数据库执行 | workflow/relational_database_workflow.md | MySQL/PostgreSQL/Oracle/SQL Server/国产数据库 |
| `super_programmer_database_storage_agent_skill___nosql_database` | NoSQL数据库执行 | workflow/nosql_database_workflow.md | MongoDB/ES/InfluxDB/Neo4j/向量数据库 |
| `super_programmer_database_storage_agent_skill___data_warehouse_lakehouse` | 数据仓库与湖仓一体执行 | workflow/data_warehouse_lakehouse_workflow.md | Kimball/Inmon/OneData/Iceberg/Delta Lake/Hudi/Flink |
| `super_programmer_database_storage_agent_skill___database_operations` | 数据库运维与调优执行 | workflow/database_operations_workflow.md | 性能调优/容灾备份/故障排查/迁移升级 |

## Knowledge Base Link

- **Base Path**: `Agents知识库/0_超级编程行业知识库`
- **Main Index**: `Agents知识库/0_超级编程行业知识库/00_总索引.md`
- **Module Main File**: `Agents知识库/0_超级编程行业知识库/03_数据库与数据存储.md`
- **Detail Directory**: `Agents知识库/0_超级编程行业知识库/数据库与数据存储/`

## Evolution Rules

1. 新增能力时先检查是否已有同名衍生技能；如有则更新，如无则新建。
2. 工作流中禁止嵌入知识库全文，一律使用 `[参考: <path>]` 引用。
3. 若知识库原文更新，子Agent下次执行时自动读取最新内容。
