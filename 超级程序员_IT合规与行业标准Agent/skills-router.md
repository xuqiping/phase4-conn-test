# skills-router.md — Skill Router

## Top-Level Skill

- **Name**: `super_programmer_it_compliance_agent_skill`
- **Purpose**: IT合规治理、国家标准与行业规范、运营商与政企IT体系的任务调度与路由。将所有执行委托给9个细粒度衍生技能。
- **Skill File Path**: `all_agents/超级程序员_IT合规与行业标准Agent/skills-router.md`

## Derivative Skills

| Skill Name | Purpose | Workflow File | Notes |
|------------|---------|---------------|-------|
| `super_programmer_it_compliance_agent_skill___code_management` | 代码资产全生命周期管理：分级分类、Git工作流、代码审查、AI代码治理、开源合规、防泄密 | workflow/code_management_workflow.md | 覆盖代码管理制度L3模块 |
| `super_programmer_it_compliance_agent_skill___data_privacy` | 数据六阶段全生命周期管理：分类分级、加密密钥、脱敏去标识化、DLP防泄漏、出境合规、应急响应 | workflow/data_privacy_workflow.md | 覆盖数据保密管理制度L3模块 |
| `super_programmer_it_compliance_agent_skill___server_access` | 基础设施权限管控：最小权限原则、RBAC/ABAC、堡垒机、PAM特权管理、零信任JIT、审计日志 | workflow/server_access_workflow.md | 覆盖服务器权限管理制度L3模块 |
| `super_programmer_it_compliance_agent_skill___info_system_standards` | 信息系统建设全流程国标：系统规划、分析、设计、实施、验收、运维各阶段标准要求 | workflow/info_system_standards_workflow.md | 覆盖信息系统建设规范L3模块 |
| `super_programmer_it_compliance_agent_skill___software_dev_standards` | 软件工程国标体系：生存周期过程、文档编制、质量模型、成本度量、安全开发、等保2.0、信创 | workflow/software_dev_standards_workflow.md | 覆盖软件开发国家标准L3模块 |
| `super_programmer_it_compliance_agent_skill___telecom_standards` | 通信网络技术标准体系：5G/5G-A架构、BSS/OSS、算力网络、承载网、卫星互联网、集采认证 | workflow/telecom_standards_workflow.md | 覆盖通信行业技术规范L3模块 |
| `super_programmer_it_compliance_agent_skill___gov_it_project` | 政企IT项目全流程落地：招投标、需求分析、方案设计、实施交付、验收运维实操方法论 | workflow/gov_it_project_workflow.md | 覆盖政企信息化项目落地L3模块 |
| `super_programmer_it_compliance_agent_skill___carrier_data` | 运营商核心数据体系：BOSS/OSS/D域架构、套餐计费建模、用户画像标签、信令位置数据、数据合规变现 | workflow/carrier_data_workflow.md | 覆盖运营商套餐&用户数据建模L3模块 |
| `super_programmer_it_compliance_agent_skill___telecom_selection` | 通信技术选型方法论：7+1评估矩阵、5G-A/6G选型、BSS/OSS重构、算力网络、物联网、卫星、信创、集采 | workflow/telecom_selection_workflow.md | 覆盖通信行业技术选型规范L3模块 |

## Knowledge Base Index

- **Module Main File**: `Agents知识库/0_超级编程行业知识库/13_IT合规与行业标准.md`

## Evolution Rules

1. 当知识库更新时，对应工作流自动读取最新内容（执行时读取，非脚手架时固化）。
2. 新增L3模块时，创建新的衍生技能和工作流，遵循现有命名约定。
3. 不创建重复技能；相同领域的能力更新到现有衍生技能。
