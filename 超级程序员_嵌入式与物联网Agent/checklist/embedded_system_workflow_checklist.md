# 嵌入式系统开发 Workflow Checklist

Use this checklist after completing every step of `workflow/embedded_system_workflow.md`. Every item must be answered **yes** before the step is considered complete. If any item is **no**, fix the output and re-validate.

## Step 1: 需求分析与架构选型

- [ ] 已提取完整的需求约束（性能、功耗、成本、安全合规、实时性）
- [ ] 已输出架构选型决策（MCU/ARM/Linux/树莓派），且理由充分
- [ ] 芯片选型矩阵覆盖≥5个维度（性能/功耗/成本/生态/供应链/安全），每项有1-5分评分
- [ ] 已确定主芯片型号及至少1个备选方案
- [ ] 工具链清单包含IDE/调试器/仿真环境/版本控制
- [ ] 选型报告中标注了参考来源：`[参考: Agents知识库/0_超级编程行业知识库/嵌入式开发与IoT/嵌入式系统.md > ...]`
- [ ] 用户已确认选型报告

## Step 2: 硬件设计与最小系统搭建

- [ ] 最小系统包含电源/晶振/复位/调试接口四大基础模块
- [ ] 电源设计包含LDO/DCDC选型、去耦电容策略、功耗预算计算
- [ ] 晶振设计包含负载电容计算、PCB Layout禁忌（下方禁止走线）
- [ ] 外设接口规划包含有线（UART/SPI/I2C/CAN）和无线（BLE/Wi-Fi/LoRa/Matter）
- [ ] EMC设计从PCB阶段开始规划，参考CISPR 32 / IEC 61000-4-x
- [ ] 硬件验证包含上电测试、晶振起振测试、调试接口测试、电流测量
- [ ] 所有测试项通过，数据记录完整

## Step 3: 固件开发与RTOS配置

- [ ] 编程语言选择明确（C遵循MISRA C:2012 / Rust试点评估），规范文件已输出
- [ ] 启动代码顺序正确：Reset Handler→时钟初始化→外设初始化→跳转main()
- [ ] RTOS选型有明确理由（FreeRTOS/Zephyr/RT-Thread/PREEMPT_RT）
- [ ] 任务划分按功能/优先级/周期拆分，任务栈静态分配
- [ ] 同步机制选型合理（队列优先/互斥量/Mutex启用优先级继承）
- [ ] 中断设计遵循"短ISR+延后处理"原则，中断优先级分组合理
- [ ] 若电池供电项目，低功耗设计包含Tickless/外设门控/各模式电流实测
- [ ] 固件编译通过，无HardFault/Watchdog复位，关键外设驱动验证通过

## Step 4: 通信协议集成与联调

- [ ] 有线协议（UART/SPI/I2C/CAN）参数配置与从设备匹配
- [ ] 无线协议（BLE/Wi-Fi/LoRa/Matter）包含功耗/穿透性/认证评估
- [ ] MQTT/CoAP Topic设计遵循分层命名规范，QoS策略合理
- [ ] 协议联调包含逻辑分析仪/示波器物理层验证和抓包分析
- [ ] 压力测试：连续1000包，丢包率<0.1%，延迟<100ms
- [ ] 异常测试：断线重连和断网续传验证通过

## Step 5: 固件安全与OTA升级

- [ ] 安全启动信任链完整（ROM→二级Bootloader→应用固件），每级有签名验证
- [ ] 签名算法选择合理（ECDSA P-256或RSA-2048），密钥管理策略明确
- [ ] 防降级机制（Anti-rollback Counter）和调试端口锁定策略已定义
- [ ] OTA分区设计为A/B双分区（或三分区含Recovery），支持原子切换
- [ ] 差分升级（Delta OTA）可减少70-90%传输量，升级流程图完整
- [ ] 安全测试通过：篡改拒绝启动/回滚拒绝/OTA断电10次回滚/密钥提取防护
- [ ] 通过PSA Certified Level 1或等效安全评估（若适用）

## Step 6: 边缘AI/TinyML部署（如适用）

- [ ] 适用性评估明确（若跳过此步骤，需说明理由）
- [ ] 模型选型与训练数据来自项目实际场景，目标精度>90%
- [ ] 量化策略选择合理（PTQ/QAT），精度损失<1%
- [ ] 模型转换工具链选择正确（STM32Cube.AI/ESP-DL/TFLite Micro/CMSIS-NN）
- [ ] 推理流水线完整：采集→预处理→推理→输出→触发动作
- [ ] 验证通过：精度偏差<2%、推理延迟<50ms、功耗在预算内

## Step 7: 系统集成与量产准备

- [ ] 功能测试覆盖率100%（逐条验证需求规格书）
- [ ] 压力测试：连续72小时无故障，监控HardFault/Watchdog/内存泄漏
- [ ] 环境测试通过：高低温/湿度/振动/ESD（参考CISPR 32/IEC 61000-4-x）
- [ ] 量产测试工装（ATE）可自动烧录+自检+打印序列号
- [ ] 序列号唯一且绑定云端设备注册表
- [ ] 文档归档完整：硬件/软件/认证/维护四大类文档齐全
- [ ] 交付包包含所有必要文件，用户签字确认

## Overall

- [ ] All steps in the workflow have been executed in order without skipping.
- [ ] Every step has been cross-validated against its checklist section.
- [ ] No step was advanced before its checklist section passed.
- [ ] `task/current_task.md` has been updated with a completion record.
- [ ] 所有`[参考: ...]`标注指向的知识库文件真实存在且章节可定位。
