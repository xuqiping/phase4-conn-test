# 区块链基础与智能合约 Workflow Checklist

Use this checklist after completing every step of `workflow/blockchain_fundamentals_workflow.md`. Every item must be answered **yes** before the step is considered complete. If any item is **no**, fix the output and re-validate.

## Step 1: 共识机制与链平台选型

- [ ] 共识机制选型矩阵覆盖≥6个维度（安全性/性能/去中心化/生态/能耗/治理灵活性）
- [ ] 已根据场景匹配正确共识类型：价值存储→PoW/智能合约→PoS/联盟协作→BFT/高频游戏→DPoS/物联网→DAG
- [ ] 链平台选型有明确理由：DeFi→以太坊L2/高性能游戏→Sui/物联网→IOTA/比特币生态→B² Network
- [ ] 安全性评估包含51%攻击/质押经济/MEV防御/跨链桥安全
- [ ] 生态成熟度评估包含开发者基数/工具链/审计公司/文档完善度
- [ ] 选型报告包含推荐方案+备选方案+风险提示，用户已确认
- [ ] 所有`[参考: ...]`标注指向的知识库文件真实存在且章节可定位

## Step 2: 智能合约架构设计

- [ ] 语言框架选型明确：EVM方向（Foundry/Hardhat+Solidity）或Move方向（Sui/Aptos+Move Prover）
- [ ] 可升级策略明确：UUPS/Diamond/不可升级，升级治理（Timelock+多签+DAO）
- [ ] 代币标准选型正确：ERC-20/721/1155/3525/3643/6551/721A，理由充分
- [ ] DeFi合约架构覆盖AMM/借贷/收益聚合/意图为中心至少1项
- [ ] RWA合约包含ERC-1400/SPV法律包装/Chainlink Proof of Reserve
- [ ] 合约架构文档通过评审，模块图和接口定义完整

## Step 3: 智能合约编码实现

- [ ] 安全编码规范遵循CEI模式+ReentrancyGuard+预言机偏差熔断
- [ ] Move语言项目利用资源模型和Move Prover编译期验证
- [ ] 测试覆盖率≥95%：单元测试+模糊测试+不变量测试+集成测试
- [ ] 静态分析无Critical漏洞：Slither/Mythril扫描通过
- [ ] Code Review通过，至少1名独立Reviewer

## Step 4: 安全审计与部署上线

- [ ] SSDLC全生命周期执行：设计威胁建模→编码安全规范→测试多层验证→审计独立审查→部署渐进上线→运营持续监控
- [ ] 至少2家独立审计公司交叉审计，审计预算占开发总预算20-30%
- [ ] 资金>5000万美元项目已完成形式化验证（Certora/KEVM/Move Prover）
- [ ] Bug Bounty已发布（Immunefi/HackerOne），赏金分级合理
- [ ] 部署策略为渐进式：测试网→主网小额→主网逐步扩大，含紧急暂停机制
- [ ] 运营监控已配置：Tenderly实时警报+Forta异常检测+多签紧急暂停

## Overall

- [ ] All steps in the workflow have been executed in order without skipping.
- [ ] Every step has been cross-validated against its checklist section.
- [ ] No step was advanced before its checklist section passed.
- [ ] `task/current_task.md` has been updated with a completion record.
- [ ] 所有`[参考: ...]`标注指向的知识库文件真实存在且章节可定位。
