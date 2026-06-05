# skills-router.md — Skill Router

## Top-Level Skill

- **Name**: super_programmer_agent_skill
- **Purpose**: 为超级程序员Agent层级体系进行意图分类和子Agent分发。不执行领域特定工作。
- **Skill File Path**: ll_agents/超级程序员Agent/skills-router.md

## Sub-Agent Registry

| Domain Key | Display Name | Sub-Agent Directory | Sub-Agent Skill Name | Knowledge Base Index | Status |
|------------|-------------|---------------------|----------------------|----------------------|--------|
| programming_language | 编程语言与基础开发 | ll_agents/超级程序员_编程语言与基础开发Agent/ | super_programmer_programming_language_agent_skill | Agents知识库/0_超级编程行业知识库/01_编程语言与核心技能.md | pending |
| backend_architecture | 后端架构与中间件 | ll_agents/超级程序员_后端架构与中间件Agent/ | super_programmer_backend_architecture_agent_skill | Agents知识库/0_超级编程行业知识库/02_网络架构与中间件.md | pending |
| database_storage | 数据库与数据存储 | ll_agents/超级程序员_数据库与数据存储Agent/ | super_programmer_database_storage_agent_skill | Agents知识库/0_超级编程行业知识库/03_数据库与数据存储.md | pending |
| cloud_native | 云计算与云原生 | ll_agents/超级程序员_云计算与云原生Agent/ | super_programmer_cloud_native_agent_skill | Agents知识库/0_超级编程行业知识库/04_前端开发与用户交互.md | pending |
| ai_ml | 人工智能与大模型 | ll_agents/超级程序员_人工智能与大模型Agent/ | super_programmer_ai_ml_agent_skill | Agents知识库/0_超级编程行业知识库/05_人工智能与机器学习.md | pending |
| big_data | 大数据技术生态 | ll_agents/超级程序员_大数据技术生态Agent/ | super_programmer_big_data_agent_skill | Agents知识库/0_超级编程行业知识库/06_大数据处理与BI.md | pending |
| cybersecurity | 网络安全与信息安全 | ll_agents/超级程序员_网络安全与信息安全Agent/ | super_programmer_cybersecurity_agent_skill | Agents知识库/0_超级编程行业知识库/07_网络安全与信息安全.md | pending |
| embedded_iot | 嵌入式与物联网 | ll_agents/超级程序员_嵌入式与物联网Agent/ | super_programmer_embedded_iot_agent_skill | Agents知识库/0_超级编程行业知识库/08_嵌入式与物联网.md | pending |
| testing_qa | 软件测试与质量保障 | ll_agents/超级程序员_软件测试与质量保障Agent/ | super_programmer_testing_qa_agent_skill | Agents知识库/0_超级编程行业知识库/09_测试与质量保障.md | pending |
| blockchain_web3 | 区块链与Web3 | ll_agents/超级程序员_区块链与Web3Agent/ | super_programmer_blockchain_web3_agent_skill | Agents知识库/0_超级编程行业知识库/10_区块链与Web3.md | pending |
| devops_sysadmin | 运维工程与系统架构 | ll_agents/超级程序员_运维工程与系统架构Agent/ | super_programmer_devops_sysadmin_agent_skill | Agents知识库/0_超级编程行业知识库/11_运维与系统管理.md | pending |
| project_team_mgmt | IT项目管理与团队管理 | ll_agents/超级程序员_IT项目管理与团队管理Agent/ | super_programmer_project_team_mgmt_agent_skill | Agents知识库/0_超级编程行业知识库/12_IT项目管理与团队协作.md | pending |
| compliance_standard | IT合规与行业标准 | ll_agents/超级程序员_IT合规与行业标准Agent/ | super_programmer_compliance_standard_agent_skill | Agents知识库/0_超级编程行业知识库/13_IT行业规范与行业标准.md | pending |
| software_engineering | 软件工程与开发全流程 | ll_agents/超级程序员_软件工程与开发全流程Agent/ | super_programmer_software_engineering_agent_skill | Agents知识库/0_超级编程行业知识库/14_软件工程与开发全流程.md | pending |

## Dispatch Mapping (for routing logic)

When a user intent matches a domain, the top-level skill delegates to the corresponding sub-Agent skill:
- super_programmer_agent_skill___<module> → maps to super_programmer_<module>_agent_skill
- Execution: load ll_agents/超级程序员_<DisplayName>Agent/AGENTS.md and invoke its most relevant fine-grained workflow.

## Evolution Rules

1. 新增领域模块时，在Sub-Agent Registry中创建新行。
2. 不要为同一领域创建重复的子Agent。
3. 如果领域扩展（新增能力），更新**现有**子Agent的工作流和检查清单，而非生成第二个子Agent。
4. 子Agent是独立的Agent；它们可以演化自己的衍生技能。
