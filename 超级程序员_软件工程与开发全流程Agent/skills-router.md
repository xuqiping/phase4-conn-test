# skills-router.md — Skill Router

## Top-Level Skill

- **Name**: `super_programmer_software_engineering_agent_skill`
- **Purpose**: 软件工程全生命周期管理任务调度与路由。覆盖需求-设计-编码-测试-部署-运维-安全-知识八大领域，将所有执行委托给8个细粒度衍生技能。
- **Skill File Path**: `all_agents/超级程序员_软件工程与开发全流程Agent/skills-router.md`

## Derivative Skills

| Skill Name | Purpose | Workflow File | Notes |
|------------|---------|---------------|-------|
| `super_programmer_software_engineering_agent_skill___requirement_product` | 需求与产品工程：AI-Native用研、需求结构化拆解、优先级组合排序、变更追踪RTM、PRD视觉化、数据驱动迭代 | workflow/requirement_product_workflow.md | 覆盖需求与产品工程L2领域 |
| `super_programmer_software_engineering_agent_skill___system_design` | 系统设计与技术选型：DDD领域驱动、ADR架构决策、技术选型评估矩阵、数据库建模范式、API First设计、UI/UX设计系统、HEART体验度量 | workflow/system_design_workflow.md | 覆盖系统设计与技术选型L2领域 |
| `super_programmer_software_engineering_agent_skill___coding_version_control` | 编码与版本控制：Trunk-Based主干开发、AI辅助Code Review、Clean as You Code质量门禁、规范即代码+AI Rules、四层技术文档金字塔 | workflow/coding_version_control_workflow.md | 覆盖编码与版本控制L2领域 |
| `super_programmer_software_engineering_agent_skill___testing_quality` | 测试与质量保障：测试策略模型选型、四级测试体系、自动化测试工程化、性能压测全链路、质量度量与DORA指标 | workflow/testing_quality_workflow.md | 覆盖测试与质量保障L2领域 |
| `super_programmer_software_engineering_agent_skill___deployment_release` | 部署与发布管理：渐进式交付、蓝绿/金丝雀/滚动部署策略、Feature Toggle解耦、数据库迁移即代码、SemVer版本管理 | workflow/deployment_release_workflow.md | 覆盖部署与发布管理L2领域 |
| `super_programmer_software_engineering_agent_skill___operations_monitoring` | 运维监控与故障响应：OpenTelemetry可观测性三支柱、SLO驱动告警、START故障排查SOP、FinOps容量规划、混沌工程韧性验证 | workflow/operations_monitoring_workflow.md | 覆盖运维监控与故障响应L2领域 |
| `super_programmer_software_engineering_agent_skill___security_lifecycle` | 安全开发生命周期：OWASP Top 10防御、SAST/DAST/SCA安全扫描、STRIDE威胁建模、漏洞响应SLA、SBOM供应链安全 | workflow/security_lifecycle_workflow.md | 覆盖安全开发生命周期L2领域 |
| `super_programmer_software_engineering_agent_skill___documentation_knowledge` | 技术文档与知识管理：四层文档体系、Docs as Code实践、API文档与开发者门户、SECI知识沉淀、Runbook标准化运维 | workflow/documentation_knowledge_workflow.md | 覆盖技术文档与知识管理L2领域 |

## Knowledge Base Index

- **Module Main File**: `Agents知识库/0_超级编程行业知识库/14_软件工程与开发全流程.md`

## Evolution Rules

1. 当知识库更新时，对应工作流自动读取最新内容（执行时读取，非脚手架时固化）。
2. 新增L2领域时，创建新的衍生技能和工作流，遵循现有命名约定。
3. 不创建重复技能；相同领域的能力更新到现有衍生技能。
