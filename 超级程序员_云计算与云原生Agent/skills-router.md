# skills-router.md — Skill Router

## Top-Level Skill
- **Name**: super_programmer_cloud_native_agent_skill
- **Purpose**: 执行云计算与云原生领域的具体任务，覆盖公有云、容器技术、云原生生态、虚拟化与私有云四大子域。
- **Skill File Path**: ll_agents/超级程序员_云计算与云原生Agent/skills-router.md

## Derivative Skills
| Skill Name | Purpose | Workflow File | Notes |
|------------|---------|---------------|-------|
| super_programmer_cloud_native_agent_skill___public_cloud | 公有云厂商生态执行 | workflow/public_cloud_workflow.md | 阿里云/腾讯云/华为云/百度云/天翼云 |
| super_programmer_cloud_native_agent_skill___container_technology | 容器技术执行 | workflow/container_technology_workflow.md | Docker/K8s/Harbor/Helm/Knative |
| super_programmer_cloud_native_agent_skill___cloud_native_ecosystem | 云原生生态执行 | workflow/cloud_native_ecosystem_workflow.md | CI/CD/GitOps/Istio/安全/FinOps |
| super_programmer_cloud_native_agent_skill___virtualization_private_cloud | 虚拟化与私有云执行 | workflow/virtualization_private_cloud_workflow.md | VMware/KVM/OpenStack/HCI/混合云 |

## Knowledge Base Link
- **Base Path**: Agents知识库/0_超级编程行业知识库
- **Main Index**: Agents知识库/0_超级编程行业知识库/00_总索引.md
- **Module Main File**: Agents知识库/0_超级编程行业知识库/04_前端开发与用户交互.md
- **Detail Directory**: Agents知识库/0_超级编程行业知识库/前端开发与用户交互/

## Evolution Rules
1. 新增能力时先检查是否已有同名衍生技能；如有则更新，如无则新建。
2. 工作流中禁止嵌入知识库全文，一律使用 [参考: <path>] 引用。
3. 若知识库原文更新，子Agent下次执行时自动读取最新内容。
