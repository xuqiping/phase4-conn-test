# 硬件自动化与智能控制 Workflow Checklist

Use this checklist after completing every step of `workflow/hardware_automation_workflow.md`. Every item must be answered **yes** before the step is considered complete. If any item is **no**, fix the output and re-validate.

## Step 1: 机械臂控制开发与运动规划

- [ ] 机械臂选型矩阵覆盖≥6个维度（负载/工作半径/精度/自由度/速度/安全认证/价格）
- [ ] 选型匹配场景：轻负载精密装配→协作机器人/中负载搬运→工业臂/重负载→重载臂/高精度→力控专用臂
- [ ] ROS2版本和中间件选型明确（Humble/Jazzy + Zenoh/CycloneDDS）
- [ ] URDF建模通过MoveIt Setup Assistant验证，自碰撞矩阵正确
- [ ] 规划算法选择有明确理由（OMPL通用/CHOMP平滑/STOMP噪声/MPC约束严格）
- [ ] 运动学/动力学建模完成，逆运动学求解通过trac_ik验证
- [ ] 力控方案选择合理（阻抗控制精密装配/导纳控制重载），六维力传感器集成通过
- [ ] 视觉伺服完成手眼标定（Eye-in-hand/Eye-to-hand），标定误差<0.5mm
- [ ] 安全合规：ISO 10218 + ISO/TS 15066，碰撞检测触停时间<500ms
- [ ] Sim2Real迁移验证：仿真轨迹→真实硬件执行，一次通过率>95%（若使用具身智能）
- [ ] 所有`[参考: ...]`标注指向的知识库文件真实存在且章节可定位

## Step 2: PLC编程与工业控制系统

- [ ] PLC平台选型匹配控制类型：逻辑控制→紧凑型/运动控制→运动型/过程控制→过程型/安全功能→安全PLC
- [ ] 编程语言选择合理：LD（逻辑控制）/ST（算法主流趋势）/FBD（过程控制）/SFC（批次流程）
- [ ] 程序结构遵循OB→FB→FC→DB层次，第四版OOP特性（如适用）正确使用
- [ ] 通信协议配置匹配生态：PROFINET（西门子）/EtherCAT（倍福/国产首选）/Modbus（跨品牌）/OPC UA（MES对接）
- [ ] 运动控制功能块使用正确（MC_Power/MC_MoveAbsolute/MC_MoveRelative/MC_MoveVelocity）
- [ ] 多轴联动功能实现（电子齿轮/电子凸轮/飞剪追剪），同步精度达标
- [ ] 过程控制PID/MPC参数整定完成，模拟量处理（4-20mA/0-10V）正确
- [ ] 国产PLC替代评估完成：中小型项目替代可行，大型项目风险评估明确
- [ ] MES/SCADA对接通过OPC UA，OEE数据采集完整
- [ ] 安全功能验证通过：急停/安全门/双手操作/速度监控/SIL等级达标
- [ ] 扫描周期稳定在规定范围（通常1-30ms），无超时或抖动异常

## Step 3: 硬件视觉识别与工业质检

- [ ] 视觉任务定义明确（缺陷检测/OCR/尺寸测量/机器人引导/行为分析）
- [ ] YOLO模型选型合理：YOLOv8n/s（速度优先）/YOLOv8m/l/x（精度优先）/YOLO26（2026无NMS）/YOLOE-26（开放词汇）
- [ ] 数据集来自产线真实样本，标注规范统一，数据增强策略合理
- [ ] 训练验证指标达标：mAP@0.5>0.85、Precision>0.90、Recall>0.85
- [ ] 边缘AI芯片选型矩阵覆盖≥4个维度（INT8算力/功耗/SDK成熟度/供货周期）
- [ ] 部署流水线完整：训练→导出ONNX→优化（TensorRT/RKNN/OpenVINO）→INT8量化→部署
- [ ] 产线集成延迟预算≤100ms（触发→采集→ISP→推理→输出→PLC响应）
- [ ] MLOps闭环：NG样本回流→标注→重训练→OTA更新→AB测试
- [ ] 隐私合规：端侧只输出结构化数据，不上传原始图像（安防场景）
- [ ] 嵌入式视觉驱动（V4L2/MIPI CSI/USB）和ISP调优完成

## Step 4: 手机自动化硬件测试系统（如适用）

- [ ] UEE范式定义完整：真机+物理交互+黑盒验证，三层架构（感知/决策/执行）
- [ ] 若跳过此步骤，有明确理由说明
- [ ] 机械臂选型正确（负载3-10kg/精度±0.02mm/协作安全认证）
- [ ] 末端执行器设计匹配测试类型：触控笔/气动夹爪/力控传感器
- [ ] 视觉识别包含OCR（PaddleOCR/Tesseract）+ UI元素检测（OpenCV DNN）+ 屏幕状态判断
- [ ] 触屏交互精度：坐标偏差<0.5mm，时序精度<5ms，多点触控同步<10ms
- [ ] 生物识别测试符合ISO 30107 PAD标准，覆盖指纹/人脸/虹膜
- [ ] 传感器/射频测试环境完整：屏蔽房+综测仪，六面翻转法校准运动传感器
- [ ] 产线架构：≥8工位并行，测试节拍<60秒/台，Burn-in 72小时老化
- [ ] SPC统计过程控制：Cpk>1.33，控制图监控良率趋势

## Overall

- [ ] All steps in the workflow have been executed in order without skipping.
- [ ] Every step has been cross-validated against its checklist section.
- [ ] No step was advanced before its checklist section passed.
- [ ] `task/current_task.md` has been updated with a completion record.
- [ ] 所有`[参考: ...]`标注指向的知识库文件真实存在且章节可定位。
