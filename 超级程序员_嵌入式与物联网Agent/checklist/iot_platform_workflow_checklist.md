# 物联网平台与行业落地 Workflow Checklist

Use this checklist after completing every step of `workflow/iot_platform_workflow.md`. Every item must be answered **yes** before the step is considered complete. If any item is **no**, fix the output and re-validate.

## Step 1: 通信协议选型与Broker部署

- [ ] 协议选型矩阵覆盖≥6个维度（带宽效率/功耗/实时性/可靠性/安全性/穿透性/生态成熟度）
- [ ] 场景匹配正确：传感器遥测→MQTT/资源受限→CoAP/工业SCADA→Sparkplug B/车联网→MQTT over QUIC/智能家居→Matter
- [ ] Topic分层命名规范已定义，禁止通配符滥用，共享订阅使用`$share/group/topic`
- [ ] QoS策略合理：遥测QoS 0/控制指令QoS 1/OTA QoS 2
- [ ] 安全栈完整：TLS 1.3 + mTLS + X.509 + JITR + 证书自动轮换策略
- [ ] Broker选型匹配规模：Mosquitto/NanoMQ（<1万）/HiveMQ（1-10万）/EMQX（10万+）
- [ ] 边缘网关职责明确：协议转换/本地缓存/数据预处理/本地决策
- [ ] 压力测试通过：连接数达标、吞吐达标（单节点10万消息/秒）、P99延迟<500ms、7×24小时稳定
- [ ] 所有`[参考: ...]`标注指向的知识库文件真实存在且章节可定位

## Step 2: 物联网平台架构设计

- [ ] 分层架构完整：感知层→网络层→平台层（接入/管理/规则/时序/孪生/OTA）→应用层→安全层
- [ ] 端-边-云协同策略明确：端侧（采集+端侧AI）→边缘（实时推理+聚合）→云端（训练+分析+优化）
- [ ] 时序数据库选型矩阵覆盖≥6个维度（写入吞吐/查询延迟/压缩率/SQL支持/集群扩展/生态集成）
- [ ] 数字孪生选型匹配需求（Azure/51World/中移物联），同步延迟目标定义
- [ ] 云原生部署方案完整：K8s编排/Serverless计费/弹性伸缩/HPA/多租户隔离
- [ ] 架构文档通过评审，核心组件部署脚本/配置完成
- [ ] 时序数据库写入性能实测达标

## Step 3: 设备接入与消息上报实现

- [ ] 设备身份认证方式梯度选择合理（密码→Token→X.509/mTLS→PSK），生产环境强制mTLS
- [ ] JITR流程完整：出厂预置证书→首次连接自动注册→配置下发→正式接入
- [ ] 零信任认证：每次连接独立认证+行为基线建模+异常二次认证
- [ ] 消息格式规范：必填字段（device_id/timestamp/msg_type）+ 遥测/事件/状态分类定义
- [ ] Payload格式选择合理（JSON调试/Protobuf生产/CBOR受限设备），Protobuf压缩率提升80%+
- [ ] 设备影子实现完整：Desired/Reported双状态，断网异步控制支持
- [ ] OTA方案完整：差分升级（减少70-90%传输）+ A/B分区原子切换 + 健康检查回滚 + 灰度发布
- [ ] 离线续传机制：设备端SQLite缓存+老化策略，网关侧消息暂存
- [ ] 监控运维指标定义完整：在线率>99.5%/延迟/丢包率/OTA成功率，Prometheus+Grafana配置完成
- [ ] 批量设备上线测试通过（10万设备1小时内完成）

## Step 4: 垂直行业方案落地

- [ ] 行业场景识别正确，进入对应分支（A智慧城市/B IIoT/C车联网/D智能家居/E通用）
- [ ] 技术架构匹配行业特性（RedCap/OPC UA/5G-V2X/Matter 2.0/LoRa等）
- [ ] 标杆案例引用≥2个，数据真实可溯源
- [ ] 安全合规要求明确（等保2.0/ISO 27001/HIPAA/GB/Z 177-2026等）
- [ ] ROI测算完整：投入成本（硬件+软件+运营）+ 收益（降本/增效/增收）+ 回本周期
- [ ] 规模化路径清晰：POC（10-100台）→ 小批量（1000台）→ 规模化（10万+台）
- [ ] 行业解决方案文档包含技术架构/部署计划/ROI测算/标杆案例/风险评估

## Overall

- [ ] All steps in the workflow have been executed in order without skipping.
- [ ] Every step has been cross-validated against its checklist section.
- [ ] No step was advanced before its checklist section passed.
- [ ] `task/current_task.md` has been updated with a completion record.
- [ ] 所有`[参考: ...]`标注指向的知识库文件真实存在且章节可定位。
