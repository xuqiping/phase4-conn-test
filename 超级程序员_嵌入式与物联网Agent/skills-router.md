# skills-router.md — Skill Router

## Top-Level Skill

- **Name**: `programmer_embedded_iot_agent_skill`
- **Purpose**: Task scheduling and routing for the 超级程序员_嵌入式与物联网Agent. Delegates actual execution to derivative skills.
- **Skill File Path**: `all_agents/超级程序员_嵌入式与物联网Agent/skills-router.md`

## Derivative Skills

| Skill Name | Purpose | Workflow File | Notes |
|------------|---------|---------------|-------|
| `programmer_embedded_iot_agent_skill___embedded_system` | 嵌入式系统全栈开发：MCU/ARM/Linux驱动/树莓派，覆盖选型、硬件设计、固件编程、RTOS、通信协议、安全启动、OTA、边缘AI/TinyML | workflow/embedded_system_workflow.md | 面向芯片到操作系统的底层软硬件协同开发 |
| `programmer_embedded_iot_agent_skill___iot_platform` | 物联网平台与行业方案：通信协议（MQTT/CoAP）、平台架构（端-边-云协同/数字孪生/时序数据库）、设备接入（认证/OTA/离线续传）、垂直行业落地 | workflow/iot_platform_workflow.md | 面向设备互联、数据传输、平台管理与行业应用 |
| `programmer_embedded_iot_agent_skill___hardware_automation` | 硬件自动化与智能控制：机械臂（ROS2+MoveIt2/力控/具身AI）、PLC编程（IEC 61131-3/EtherCAT）、硬件视觉（YOLO+边缘芯片/工业质检）、手机自动化测试（UEE/产线） | workflow/hardware_automation_workflow.md | 面向工业自动化、机器人控制、视觉质检、硬件测试 |

## Evolution Rules

1. When adding a new capability, check whether an existing derivative skill covers the same domain.
2. If yes, **update** the existing derivative skill; do **not** create a duplicate.
3. If no, create a new derivative skill following the naming convention: `programmer_embedded_iot_agent_skill___<capability>`.
4. Update this table immediately after any skill change.
