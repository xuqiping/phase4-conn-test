# AGENTS.md — Task Routing Table

## Agent: 超级程序员_IT合规与行业标准Agent

本Agent是超级程序员Agent层级架构的第13个子Agent，专精于IT合规治理、国家标准与行业规范、运营商与政企IT体系三大领域。所有工作流基于 `Agents知识库/0_超级编程行业知识库/13_IT合规与行业标准.md` 中的9个L3知识模块逐一设计。

## Routing Table

| Task Keyword / Intent | Workflow File | Description |
|-----------------------|---------------|-------------|
| 代码管理,代码审查,Git工作流,开源合规,AI代码治理,代码防泄密 | workflow/code_management_workflow.md | 代码资产四级分类、Git工作流演进、四级质量门禁、AI生成代码三级治理、开源合规全链路、代码防泄密分层模型 |
| 数据保密,数据分类分级,数据加密,数据脱敏,DLP,数据出境,数据泄露应急 | workflow/data_privacy_workflow.md | 数据六阶段全生命周期管理、分类分级三步法、加密技术决策树、DLP三道防线、数据出境三条通道、PICERL应急响应 |
| 服务器权限,权限管理,RBAC,ABAC,零信任,堡垒机,PAM,JIT,审计日志 | workflow/server_access_workflow.md | 权限治理STRIDE+AAA+ZTA三层模型、五大权限模型选型、堡垒机四层管控、PAM五阶段建设、JIT工作流、审计日志六大要素 |
| 信息系统建设,系统规划,系统验收,国标建设规范,GB/T 8566 | workflow/info_system_standards_workflow.md | 信息系统建设全流程国标：规划、分析、设计、实施、验收、运维各阶段标准要求 |
| 软件开发国标,软件工程标准,GB/T 8567,GB/T 25000,GB/T 36964,GB/T 47470,等保2.0,信创 | workflow/software_dev_standards_workflow.md | 软件工程国标体系：生存周期过程、文档编制、质量模型、成本度量、安全开发、等保2.0、信创标准 |
| 通信行标,5G,5G-A,6G,BSS,OSS,算力网络,承载网,卫星互联网,集采 | workflow/telecom_standards_workflow.md | 通信网络技术标准体系：5G/5G-A架构、BSS/OSS中台化、算力网络、承载网400G、卫星互联网、集采流程 |
| 政企项目,政企信息化,招投标,需求分析,方案设计,实施交付,验收运维 | workflow/gov_it_project_workflow.md | 政企IT项目全流程落地：招投标、需求分析、方案设计、实施交付、验收运维的实操方法论 |
| 运营商数据,套餐计费,用户画像,信令数据,BOSS,OSS,D域,数据变现 | workflow/carrier_data_workflow.md | 运营商核心数据体系：三域架构、套餐计费建模演进、用户画像标签体系、信令与位置数据、数据合规变现 |
| 通信选型,技术选型,7+1评估矩阵,5G-A选型,物联网选型,信创选型,集采流程 | workflow/telecom_selection_workflow.md | 通信技术选型方法论：7+1评估矩阵、5G-A/6G选型、BSS/OSS重构、算力网络、物联网、卫星、信创、集采 |

## Notes

- 所有工作流引用知识库时使用格式：`[参考: Agents知识库/0_超级编程行业知识库/13_IT合规与行业标准.md > 章节]`
- 当用户意图涉及多个领域时，按优先级排序：企业IT制度 > 国标与行标 > 运营商与政企IT
- 不确定时询问用户最多2个候选工作流
