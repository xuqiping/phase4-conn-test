# 联盟链与国产链 Workflow Checklist

Use this checklist after completing every step of `workflow/consortium_blockchain_workflow.md`. Every item must be answered **yes** before the step is considered complete. If any item is **no**, fix the output and re-validate.

## Step 1: 场景分析与联盟设计

- [ ] 场景识别明确：存证溯源/可信共享/资金监管/供应链金融/跨境贸易之一
- [ ] 参与方角色定义清晰：发起方/共识节点/记账节点/应用接入/监管节点
- [ ] 准入流程完整：申请→身份验证→联盟投票→节点部署→证书签发
- [ ] 退出流程完整：提前通知→数据迁移/销毁→证书吊销→节点下线
- [ ] 数据共享规则三级划分：公开数据/授权数据/隐私数据
- [ ] 联盟治理方案经所有参与方确认

## Step 2: 平台选型与架构设计

- [ ] 平台选型矩阵覆盖五维：安全性/性能/合规/生态/成本
- [ ] 选型匹配场景：国际→Fabric/国密政务→长安链/商业BaaS→蚂蚁链/开源金融→FISCO BCOS
- [ ] Fabric 3.x架构设计包含Peer/Orderer/CA/Channel/Chaincode五大组件规格
- [ ] 隐私三层架构已设计：Channel/PDC/SideDB，按数据敏感度分级
- [ ] 国产链包含国密适配和信创方案（麒麟OS+鲲鹏芯片+SM2/SM3/SM4）
- [ ] 网络拓扑包含高可用和容灾设计（同城双活+异地灾备）

## Step 3: 链码开发与国密合规

- [ ] 链码开发完成，语言选择有理由（Go/Java/Node.js/Solidity）
- [ ] 国密改造三项完成：SM2签名/SM3哈希/SM4加密，HSM存储根密钥
- [ ] 通过密评认证（GM/T 0054）
- [ ] 通过等保测评（GB/T 22239，第二级或第三级）
- [ ] 测试验证通过：功能测试/性能测试/国密验证/渗透测试

## Step 4: 政务落地与生产运维

- [ ] 政务场景设计匹配发展阶段：1.0存证→2.0共享→3.0无感服务
- [ ] K8s生产部署完成：Operator模式/CCaaS/高可用/备份恢复/GitOps
- [ ] 监控告警体系运行：Prometheus+Grafana+Alertmanager，规则覆盖区块高度/Leader切换/证书过期/节点离线
- [ ] 安全运维：密钥管理（HSM离线根CA）/网络隔离（国密TLS）/数据隐私（PDC+SideDB+ZKP）
- [ ] 运维手册交付：日常巡检/变更管理/应急响应/容量规划
- [ ] 混沌工程验证：Chaos Mesh注入至少1类故障并通过韧性验证

## Overall

- [ ] All steps in the workflow have been executed in order without skipping.
- [ ] Every step has been cross-validated against its checklist section.
- [ ] No step was advanced before its checklist section passed.
- [ ] `task/current_task.md` has been updated with a completion record.
- [ ] 所有`[参考: ...]`标注指向的知识库文件真实存在且章节可定位。
