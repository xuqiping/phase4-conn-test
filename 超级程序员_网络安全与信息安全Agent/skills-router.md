# skills-router.md — Skill Router

## Top-Level Skill
- **Name**: `super_programmer_cybersecurity_agent_skill`
- **Purpose**: 执行网络安全与信息安全领域的具体任务。
- **Skill File Path**: `all_agents/超级程序员_网络安全与信息安全Agent/skills-router.md`

## Derivative Skills
| Skill Name | Purpose | Workflow File | Notes |
|------------|---------|---------------|-------|
| `super_programmer_cybersecurity_agent_skill___network_security` | 网络基础安全执行 | workflow/network_security_workflow.md | 网络基础安全：协议安全、防火墙/WAF、渗透测试、DDoS防护 |
| `super_programmer_cybersecurity_agent_skill___application_security` | 应用安全执行 | workflow/application_security_workflow.md | 应用安全：Web安全、接口鉴权、代码安全审计、移动端APP安全 |
| `super_programmer_cybersecurity_agent_skill___security_compliance` | 企业安全合规执行 | workflow/security_compliance_workflow.md | 企业安全合规：等保2.0、数据安全法、隐私计算、政企安全架构 |
| `super_programmer_cybersecurity_agent_skill___security_operations` | 安全运维与应急响应执行 | workflow/security_operations_workflow.md | 安全运维与应急响应：安全日志分析、入侵检测、应急处置、取证 |

## Knowledge Base Link
- **Base Path**: `Agents知识库/0_超级编程行业知识库`
- **Main Index**: `Agents知识库/0_超级编程行业知识库/00_总索引.md`
- **Module Main File**: `Agents知识库/0_超级编程行业知识库/07_网络安全与信息安全.md`
- **Detail Directory**: `Agents知识库/0_超级编程行业知识库/网络安全与信息安全/`

## Evolution Rules
1. 新增能力时先检查是否已有同名衍生技能；如有则更新，如无则新建。
2. 工作流中禁止嵌入知识库全文，一律使用 `[参考: <path>]` 引用。
3. 若知识库原文更新，子Agent下次执行时自动读取最新内容。