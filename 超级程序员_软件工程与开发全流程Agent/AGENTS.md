# AGENTS.md — Task Routing Table

## Agent: 超级程序员_软件工程与开发全流程Agent

本Agent是超级程序员Agent层级架构的第14个子Agent，专精于软件工程全生命周期管理，覆盖从需求分析到运维监控的八大核心领域。所有工作流基于 `Agents知识库/0_超级编程行业知识库/14_软件工程与开发全流程.md` 中的8个L2领域逐一设计。

## Routing Table

| Task Keyword / Intent | Workflow File | Description |
|-----------------------|---------------|-------------|
| 需求分析,产品工程,用户研究,需求收集,PRD,故事地图,Kano,优先级排序,需求变更 | workflow/requirement_product_workflow.md | AI-Native用研、需求结构化拆解、优先级组合排序、变更追踪RTM、PRD视觉化、数据驱动迭代 |
| 系统设计,技术选型,架构设计,DDD,ADR,数据库设计,API设计,UI/UX,HEART | workflow/system_design_workflow.md | DDD领域驱动、ADR架构决策、技术选型评估矩阵、数据库建模范式、API First设计、UI/UX设计系统、HEART体验度量 |
| 编码规范,版本控制,Git,Code Review,代码质量,Trunk-Based,Feature Toggle,Clean Code | workflow/coding_version_control_workflow.md | Trunk-Based主干开发、AI辅助Code Review、Clean as You Code质量门禁、规范即代码+AI Rules、四层技术文档金字塔 |
| 测试策略,质量保障,自动化测试,单元测试,集成测试,E2E,性能测试,DORA,质量度量 | workflow/testing_quality_workflow.md | 测试策略模型选型、四级测试体系、自动化测试工程化、性能压测全链路、质量度量与DORA指标 |
| 部署发布,CI/CD,蓝绿部署,金丝雀,滚动发布,Feature Toggle,数据库迁移,版本管理 | workflow/deployment_release_workflow.md | 渐进式交付、蓝绿/金丝雀/滚动部署策略、Feature Toggle解耦、数据库迁移即代码、SemVer版本管理 |
| 运维监控,故障响应,可观测性,SRE,SLO,混沌工程,容量规划,FinOps,OpenTelemetry | workflow/operations_monitoring_workflow.md | OpenTelemetry可观测性三支柱、SLO驱动告警、START故障排查SOP、FinOps容量规划、混沌工程韧性验证 |
| 安全开发,SDL,OWASP,SAST,DAST,威胁建模,STRIDE,漏洞响应,SBOM,供应链安全 | workflow/security_lifecycle_workflow.md | OWASP Top 10防御、SAST/DAST/SCA安全扫描、STRIDE威胁建模、漏洞响应SLA、SBOM供应链安全 |
| 技术文档,知识管理,Docs as Code,API文档,开发者门户,Runbook,SECI,知识沉淀 | workflow/documentation_knowledge_workflow.md | 四层文档体系、Docs as Code实践、API文档与开发者门户、SECI知识沉淀、Runbook标准化运维 |

## Notes

- 所有工作流引用知识库时使用格式：`[参考: Agents知识库/0_超级编程行业知识库/14_软件工程与开发全流程.md > 章节]`
- 软件工程八大领域天然闭环：需求→设计→编码→测试→部署→运维→安全→知识，跨领域关联见知识库"跨领域关联"章节
- 不确定时询问用户最多2个候选工作流
