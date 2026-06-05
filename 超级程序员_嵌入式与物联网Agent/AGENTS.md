# AGENTS.md — Task Routing Table

## Agent: 超级程序员_嵌入式与物联网Agent

本文件是任务路由的唯一真相来源。当用户向本子Agent提出请求时，读取此表以确定加载哪个细粒度工作流。

## Routing Table

| 任务关键词 / 意图 | 工作流文件 | 描述 |
|------------------|-----------|------|
| 单片机,MCU,STM32,ESP32,GD32,裸机,RTOS,FreeRTOS,Zephyr,RT-Thread,嵌入式C,ARM Cortex-M,RISC-V,低功耗,能量采集,边缘AI,TinyML,固件安全,安全启动,OTA,TrustZone-M,MISRA C,寄存器,中断,DMA,外设驱动,BLE,LoRa,Matter,Thread,Wi-Fi,传感器,ADC,DAC,PWM,GPIO,时钟,晶振,EMC,PCB Layout,看门狗,Tickless,Helium | workflow/embedded_system_workflow.md | 嵌入式系统开发：MCU选型与架构（ARM/RISC-V）、硬件设计（最小系统/EMC/低功耗）、嵌入式C/Rust编程、RTOS多任务设计、通信协议（有线+无线）、固件安全（Secure Boot/OTA/TrustZone）、边缘AI/TinyML部署 |
| 物联网,IoT,MQTT,CoAP,Broker,EMQX,HiveMQ,物联网平台,数字孪生,时序数据库,TDengine,InfluxDB,设备接入,消息上报,OTA升级,设备影子,X.509,mTLS,JITR,零信任,边缘计算,云-边-端协同,Serverless IoT,KubeEdge,k3s,智慧城市,工业物联网,IIoT,车联网,V2X,智能家居,Matter,智慧农业,智慧医疗,智慧能源,智慧物流,RedCap,5G IoT,Sparkplug B,OPC UA | workflow/iot_platform_workflow.md | 物联网平台与行业落地：通信协议选型（MQTT 5.0/CoAP/QUIC）、平台架构（端-边-云协同/数字孪生/时序数据库）、设备接入（身份认证/OTA/离线续传）、垂直行业方案（智慧城市/IIoT/车联网/智能家居） |
| 机械臂,机器人,ROS2,MoveIt2,运动规划,力控,视觉伺服,具身智能,VLA,PLC,工控,工业自动化,CODESYS,TIA Portal,汇川,信捷,EtherCAT,PROFINET,Modbus,OPC UA,IEC 61131-3,PID,MPC,YOLO,边缘AI,工业质检,视觉识别,OpenCV,Jetson,RK3588,手机自动化测试,UEE,协作机器人,数字孪生,Sim2Real,Isaac Sim,LeRobot,传感器测试,射频测试,产线测试 | workflow/hardware_automation_workflow.md | 硬件自动化与智能控制：机械臂控制（ROS2+MoveIt2/力控/视觉伺服/具身AI）、PLC编程（IEC 61131-3/EtherCAT/运动控制）、硬件视觉（YOLO26+边缘芯片/工业质检/MLOps）、手机自动化硬件测试（UEE/机械臂+视觉/产线测试） |

## Notes

- 本子Agent处理所有与嵌入式开发、物联网IoT、硬件自动化、工业控制、机械臂、PLC编程、边缘AI视觉、手机自动化测试相关的请求。
- 关键词覆盖范围要足够广以捕获同义/转述请求。
- 新增能力时在此表添加新行，并创建对应 workflow + checklist 文件对。

## Cross-Module Dependencies

- `embedded_system_workflow.md` Step 2 中的Linux嵌入式驱动可能引用 [参考: Agents知识库/0_超级编程行业知识库/02_后端架构与中间件.md > 服务治理]（实时性要求与限流降级策略的对比）
- `embedded_system_workflow.md` Step 2 中的树莓派边缘AI可能引用 [参考: Agents知识库/0_超级编程行业知识库/05_人工智能与大模型.md > AI应用开发]（模型量化/边缘部署/推理优化）
- `iot_platform_workflow.md` Step 2 中的时序数据库可能引用 [参考: Agents知识库/0_超级编程行业知识库/03_数据库与数据存储.md > 时序数据库]（TDengine/InfluxDB/IoTDB选型与性能优化）
- `iot_platform_workflow.md` Step 2 中的云原生IoT平台可能引用 [参考: Agents知识库/0_超级编程行业知识库/04_云计算与云原生.md > 容器技术]（K8s/KubeEdge/k3s边缘编排）
- `hardware_automation_workflow.md` Step 2 中的YOLO边缘部署可能引用 [参考: Agents知识库/0_超级编程行业知识库/05_人工智能与大模型.md > 计算机视觉]（目标检测/模型量化/边缘推理）
- `hardware_automation_workflow.md` Step 2 中的PLC与IoT集成可能引用 [参考: Agents知识库/0_超级编程行业知识库/08_嵌入式开发与IoT.md > 物联网IoT]（OPC UA/MQTT打通IT/OT）
