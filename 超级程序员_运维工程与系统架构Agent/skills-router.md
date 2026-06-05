# skills-router.md — Skill Router

## Top-Level Skill

- **Name**: `programmer_ops_architecture_agent_skill`
- **Purpose**: Task scheduling and routing for the 超级程序员_运维工程与系统架构Agent. Delegates actual execution to derivative skills.
- **Skill File Path**: `all_agents/超级程序员_运维工程与系统架构Agent/skills-router.md`

## Derivative Skills

| Skill Name | Purpose | Workflow File | Notes |
|------------|---------|---------------|-------|
| `programmer_ops_architecture_agent_skill___linux_operations` | Linux运维全栈：内核调优/io_uring/cgroup v2/eBPF+BBR v3/Shell+Python工程化脚本/性能调优OODA/USE+RED+Golden Signals监控 | workflow/linux_operations_workflow.md | 面向SRE/DevOps/平台工程师的Linux基础设施运维 |
| `programmer_ops_architecture_agent_skill___windows_operations` | Windows系统运维：C盘清理/安全五层防线/启动优化/AD域控+Entra ID+Intune/信创替换/零信任SASE | workflow/windows_operations_workflow.md | 面向企业桌面运维、AD管理员、信创替换项目 |
| `programmer_ops_architecture_agent_skill___datacenter_networking` | 机房与组网架构：SD-WAN/SASE/ZTNA/三层架构Leaf-Spine/M-LAG/Wi-Fi 7/液冷机柜/配电布线/DCIM | workflow/datacenter_networking_workflow.md | 面向网络工程师、数据中心架构师、基础设施团队 |

## Evolution Rules

1. When adding a new capability, check whether an existing derivative skill covers the same domain.
2. If yes, **update** the existing derivative skill; do **not** create a duplicate.
3. If no, create a new derivative skill following the naming convention: `programmer_ops_architecture_agent_skill___<capability>`.
4. Update this table immediately after any skill change.
