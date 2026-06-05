# skills-router.md — Skill Router

## Top-Level Skill

- **Name**: `programmer_pm_team_agent_skill`
- **Purpose**: Task scheduling and routing for the 超级程序员_IT项目管理与团队管理Agent. Delegates actual execution to derivative skills.
- **Skill File Path**: `all_agents/超级程序员_IT项目管理与团队管理Agent/skills-router.md`

## Derivative Skills

| Skill Name | Purpose | Workflow File | Notes |
|------------|---------|---------------|-------|
| `programmer_pm_team_agent_skill___product_manager` | 产品经理体系：需求设计(JTBD/KANO/RICE/Validation Pyramid)→原型设计&8段式PRD(Double Diamond/Atomic Design)→商业化规划(BMC/Lean Canvas/PLG/SLG/SaaS指标) | workflow/product_manager_workflow.md | 面向产品经理、技术负责人、创业者 |
| `programmer_pm_team_agent_skill___team_management` | 技术团队管理：团队架构(Conway/Team Topologies/Squad+Tribe/超级个体+AI)→人才梯队(双通道M/P/九宫格/Bar Raiser)→研发流程(Platform Engineering/Trunk-Based Dev/ADR/C4/DDD/SRE/DORA+SPACE) | workflow/team_management_workflow.md | 面向技术团队Leader、CTO、HRBP |
| `programmer_pm_team_agent_skill___project_management` | 项目管理方法论：PMP(PMBOK 8th/WBS/EVM/CPM/蒙特卡洛)→敏捷Scrum(3-5-3/WSJF/DoD/SAFe/LeSS/Nexus)→瀑布模型(7阶段/V模型/基线控制/外瀑内敏) | workflow/project_management_workflow.md | 面向项目经理、Scrum Master、PMO |

## Evolution Rules

1. When adding a new capability, check whether an existing derivative skill covers the same domain.
2. If yes, **update** the existing derivative skill; do **not** create a duplicate.
3. If no, create a new derivative skill following the naming convention: `programmer_pm_team_agent_skill___<capability>`.
4. Update this table immediately after any skill change.
