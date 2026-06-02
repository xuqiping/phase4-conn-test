# skills-router.md — Skill Router

## Top-Level Skill

- **Name**: `programmer_testing_qa_agent_skill`
- **Purpose**: Task scheduling and routing for the 超级程序员_软件测试与质量保障Agent. Delegates actual execution to derivative skills.
- **Skill File Path**: `all_agents/超级程序员_软件测试与质量保障Agent/skills-router.md`

## Derivative Skills

| Skill Name | Purpose | Workflow File | Notes |
|------------|---------|---------------|-------|
| `programmer_testing_qa_agent_skill___functional_testing` | 功能测试全链路：测试思维模型、用例设计六法、探索性测试SBTM、缺陷管理CLEAR/六维矩阵/AI Triage、根因分析 | workflow/functional_testing_workflow.md | 面向手工测试方法论、用例设计、缺陷全生命周期管理 |
| `programmer_testing_qa_agent_skill___automated_testing` | 自动化测试三维：接口自动化（OpenAPI/契约测试/CI流水线）、UI自动化（Playwright/Selenium/视觉AI/自愈合）、性能测试（k6/JMeter/全链路压测/容量规划） | workflow/automated_testing_workflow.md | 面向接口/UI/性能三大维度的自动化测试工程化 |
| `programmer_testing_qa_agent_skill___testing_engineering` | 测试工程体系：测试左移/右移4D模型、DevOps四层质量门禁、混沌工程、企业测试团队架构（QA→QE→TaaS）、质量度量 | workflow/testing_engineering_workflow.md | 面向组织级质量保障体系建设和团队转型 |

## Evolution Rules

1. When adding a new capability, check whether an existing derivative skill covers the same domain.
2. If yes, **update** the existing derivative skill; do **not** create a duplicate.
3. If no, create a new derivative skill following the naming convention: `programmer_testing_qa_agent_skill___<capability>`.
4. Update this table immediately after any skill change.
