# 区块链基础与智能合约 Workflow

## Purpose

为区块链项目提供从共识机制选型、链平台选型、智能合约开发到安全审计与部署的完整执行路径。覆盖比特币/以太坊底层原理、多共识算法对比、Solidity+Move双语言开发、DeFi/RWA合约架构及形式化验证。

## Prerequisites

- 用户已明确项目类型（DeFi/RWA/NFT/供应链/游戏/社交/基础设施）
- 用户已明确目标用户群体（C端消费者/B端企业/开发者/机构）
- 用户已明确安全预算（审计费用占总开发预算比例）

## Steps

### Step 1: 共识机制与链平台选型

**Goal**: 根据项目需求确定最优共识机制和底层链平台，输出技术选型报告。
**Completion criterion**: 共识选型报告产出，包含安全性/性能/去中心化/生态成熟度评估，经用户确认。

**A. 共识机制选型矩阵**
- 评估维度：安全性模型、最终性类型、典型TPS、去中心化程度、能耗/可持续性、治理灵活性
- 选型决策：
  - 价值存储/抗审查 → **PoW**（比特币SHA-256，算力~600 EH/s，BitVM突破图灵完备）
  - 通用智能合约平台 → **PoS**（以太坊Casper FFG + LMD GHOST，质押ETH>3000万枚）
  - 联盟协作/已知身份 → **BFT/PBFT**（HotStuff/CometBFT，确定性最终性，3,000-10,000 TPS）
  - 高频DeFi/游戏/社交 → **DPoS/DAG**（Sui Narwhal-Bullshark，理论峰值297,000 TPS）
  - 物联网/微支付 → **DAG**（IOTA Tangle，零手续费，轻量级节点）
  - 混合需求 → **模块化共识**（Celestia DA层 + Rollup执行层解耦）

**B. 链平台选型矩阵**
- 若项目为企业内部数据共享、无代币 → **PoA（Geth Clique）** 或自建联盟链
- 若项目为DeFi/金融应用、需继承以太坊安全性 → **以太坊L2（Arbitrum/Optimism/Base）**
  - Dencun后L2交易成本降至$0.001级，EIP-4844 blob空间专为Rollup设计
- 若项目为高性能游戏/社交、需并行执行 → **Sui（DAG+BFT+Move）** 或 **Aptos**
- 若项目为物联网微支付、零手续费 → **IOTA Tangle**
- 若项目为比特币生态应用 → **B² Network/Merlin Chain（比特币L2）**

**C. 安全性评估**
- PoW 51%攻击风险评估：算力分布、矿池集中度、抗ASIC算法
- PoS经济安全评估：质押总量、罚没机制、长程攻击防御（弱主观性检查点）
- BFT容错评估：3f+1节点数、f值确定、节点准入机制
- MEV防御：Flashbots Protect、批量拍卖、私有内存池
- 跨链桥安全：累计被盗>28亿美元，多重审计+形式化验证+保险+大额延迟提现

**D. 生态与工具成熟度**
- EVM生态（Solidity/Foundry/Hardhat）：开发者基数最大、工具最成熟、审计公司最多
- Move生态（Sui/Aptos）：编译期资源安全、并行执行、蓝海机会
- 比特币生态（BitVM/闪电网络）：价值存储+新兴可编程性

**输出物**: `共识与链选型报告_项目名称.md`（含选型矩阵、安全性评估、生态成熟度评分、推荐方案及备选）。

**[参考: Agents知识库/0_超级编程行业知识库/区块链与Web3/区块链基础.md > 二、共识机制]`
**[参考: Agents知识库/0_超级编程行业知识库/区块链与Web3.md > 关键数据速查]`

---

### Step 2: 智能合约架构设计

**Goal**: 完成合约架构设计，确定代币标准、升级策略、治理机制和数据存储方案。
**Completion criterion**: 合约架构文档产出，包含模块划分、接口定义、升级策略、安全机制，经架构评审通过。

**A. 语言与框架选型**
- EVM方向（默认选择）：
  - 框架：Foundry（Rust编写，测试速度快，首选）/ Hardhat（JavaScript生态成熟）
  - 语言：Solidity（最成熟）/ Vyper（形式化验证友好）
- Move方向（安全优先）：
  - 平台：Sui（Object-Centric并行执行）/ Aptos（并行BFT）
  - 工具：Move Prover（编译期形式化验证）

**B. 合约架构模式**
- 可升级合约：
  - UUPS（Universal Upgradeable Proxy Standard）：成为主流，Gas优化，单一代理合约
  - Diamond Pattern（EIP-2535）：20+模块超大型系统，每个Facet独立升级
  - 升级治理：Timelock（时间锁延迟）+ 多签（Gnosis Safe）+ DAO投票（Snapshot/Tally）三层架构
- 不可升级合约：适用于简单ERC-20/721，部署即固化，信任度最高
- 合约初始化安全：原子化初始化、Initializable修饰符、部署后立即验证初始化状态

**C. 代币与资产标准选型**
- 同质化代币：ERC-20（基础）/ ERC-4626（收益金库）
- 非同质化代币：
  - ERC-721：单资产标准（艺术/收藏品/地产）
  - ERC-1155：多代币标准（游戏道具/批量发行，批量转账Gas优化）
  - ERC-3525：半同质化SFT（RWA资产分割/债券，按比例分割所有权）
  - ERC-3643：合规证券代币（机构RWA，内置KYC/AML/转让限制）
  - ERC-6551：NFT绑定账户（可组合资产/游戏角色，NFT拥有独立合约账户）
  - ERC-721A：Gas优化铸造（大规模PFP发行，批量铸造Gas降低70%）
  - Solana cNFT：压缩NFT（会员体系百万级，Merkle Tree压缩，成本$0.0001/枚）
- 账户抽象：ERC-4337（社交恢复/批量交易/Gas代付）

**D. DeFi合约架构**
- AMM（自动做市商）：Uniswap V4 Singleton + Hooks（动态手续费/TWAMM/限价单/MEV-aware路由）
- 借贷协议：Aave V3 E-Mode（同类资产高杠杆隔离）+ 闪电贷（无抵押借贷，同一交易内归还）
- 收益聚合：Yearn/Sommelier（策略自动化切换）
- 意图为中心（Intent-Centric）：用户表达意图 + 求解器优化执行（CoW Swap/1inch Fusion）
- 再质押（Restaking）：EigenLayer允许质押ETH为其他协议提供共享安全，催生LRT新资产类别

**E. RWA（真实世界资产）合约**
- 资产代币化：ERC-1400安全代币标准（分区+文档管理+强制转移）
- 链上法律包装：SPV（特殊目的载体）持有底层资产，代币持有法律权益
- 资产验证：Chainlink Proof of Reserve（储备金证明，链下资产链上验证）
- 合规嵌入：KYC/AML检查点嵌入合约（ERC-3643内置转账限制）
- 预言机：Chainlink多节点+TWAP+价格偏差熔断（防止价格操纵）

**输出物**: `合约架构设计文档_项目名称.md`（含模块图、接口定义、代币标准选型、升级策略、安全机制）。

**[参考: Agents知识库/0_超级编程行业知识库/区块链与Web3/区块链基础.md > 三、智能合约开发]`
**[参考: Agents知识库/0_超级编程行业知识库/区块链与Web3/元宇宙与数字身份.md > 二、NFT与数字资产]`

---

### Step 3: 智能合约编码实现

**Goal**: 完成合约代码编写、单元测试、集成测试，代码通过静态分析和团队Code Review。
**Completion criterion**: 合约代码编译通过，测试覆盖率≥95%，静态扫描无Critical漏洞，Code Review通过。

**A. 安全编码规范（Solidity）**
- CEI模式（Checks-Effects-Interactions）：先检查条件→再修改状态→最后外部调用，防止重入攻击
- ReentrancyGuard：对不可信外部调用使用OpenZeppelin非重入锁
- Transient Storage（EIP-1153）：跨调用临时存储，无需写入永久状态，降低重入风险
- 访问控制：Ownable（单管理员）/ AccessControl（RBAC多角色）/ TimelockController（时间锁延迟操作）
- 整数安全：SafeMath（Solidity 0.8前）/ 内置溢出检查（0.8+），注意unchecked块
- 预言机安全：多数据源聚合+TWAP+偏差熔断（价格变化>阈值暂停交易）
- 闪电贷防御：抗闪电贷经济模型、Commit-Reveal机制、多区块状态依赖

**B. Move语言安全特性**
- 资源模型：资产作为资源（不可复制/不可丢弃/不可伪造），编译期保障
- 能力系统（Ability）：Copy/Drop/Store/Key四种能力，精确控制资源行为
- 并行执行：Object-Centric模型，无状态冲突的交易并行执行
- 形式化验证：Move Prover验证函数前后条件、循环不变量、资源守恒

**C. 测试策略**
- 单元测试：Foundry/Hardhat测试框架，覆盖所有函数路径、边界条件、异常输入
- 模糊测试（Fuzzing）：Echidna/Foundry fuzz，自动生成随机输入探测边界漏洞
- 不变量测试：定义合约必须始终保持的属性（如"总供应量=各地址余额之和"），每次交易后验证
- 集成测试：多合约交互场景、跨合约重入检测、预言机故障模拟
- 分叉测试：Foundry fork模式在真实链状态上测试（主网/L2 fork）

**D. 静态分析**
- Slither（Trail of Bits）：漏洞检测、代码优化建议、ERC标准合规检查
- Mythril（ConsenSys）：符号执行，探测深度路径漏洞
- 预提交扫描：Git hooks自动运行Slither，阻断含Critical漏洞的提交
- OWASP 2026 Top 10：业务逻辑漏洞升至第2位，代理可升级性漏洞进入第10位

**输出物**: `智能合约源码_项目名称/`（含合约代码、测试文件、部署脚本、ABI/元数据）。

**[参考: Agents知识库/0_超级编程行业知识库/区块链与Web3/区块链基础.md > 四、共识与合约的安全防御]`
**[参考: Agents知识库/0_超级编程行业知识库/区块链与Web3.md > 跨领域关联]`

---

### Step 4: 安全审计与部署上线

**Goal**: 通过专业安全审计、形式化验证和渐进式部署策略，确保合约上线后资金安全。
**Completion criterion**: 至少2家独立审计公司出具审计报告，Critical/Medium漏洞全部修复，部署后监控体系运行。

**A. 安全开发生命周期（SSDLC）**
- 设计阶段：威胁建模（STRIDE），识别欺骗/篡改/否认/信息泄露/拒绝服务/权限提升风险
- 编码阶段：安全规范（CEI模式）+ 预提交Slither扫描
- 测试阶段：单元测试→模糊测试→不变量测试→分叉测试
- 审计阶段：至少2家独立审计公司交叉审计
- 部署阶段：渐进式上线（测试网→主网小额→主网逐步扩大）
- 运营阶段：持续监控（Tenderly实时警报+Forta异常检测+应急响应）

**B. 审计准备**
- 审计文档包：
  - 技术白皮书/设计文档
  - 完整源码+编译配置
  - 测试用例和覆盖率报告
  - 已知风险和缓解措施清单
  - 部署和升级流程文档
- 审计公司选择：
  - 顶级：Trail of Bits / OpenZeppelin / ConsenSys Diligence / CertiK
  - 专项：形式化验证（Certora）、零知识证明（Least Authority）
  - 社区：Code4rena（竞争审计）、Immunefi（Bug Bounty平台）
- 审计预算：占开发总预算20-30%，资金>5000万美元的协议强制形式化验证

**C. 形式化验证（高风险项目必选）**
- Solidity：Certora（规则验证）、KEVM（K框架验证EVM语义）
- Move：Move Prover（编译期验证资源守恒）
- 验证目标：
  - 函数前后条件（Pre/Post Condition）
  - 循环不变量（Loop Invariant）
  - 资金守恒（Total Supply = Sum of Balances）
  - 权限控制（只有Owner能执行admin函数）

**D. Bug Bounty与保险**
- Bug Bounty平台：Immunefi（DeFi协议首选）、HackerOne、Code4rena
- 悬赏分级：Critical（资金风险，赏金$50K-$1M+）/ High（功能破坏）/ Medium（局部影响）/ Low（信息泄露）
- 智能合约保险：Nexus Mutual（去中心化保险）、InsurAce（多链覆盖）

**E. 部署策略**
- 测试网验证：Goerli（已弃用）→ Sepolia（当前推荐）/ Holesky（质押测试）+ L2测试网
- 主网渐进式：
  - 阶段1：部署合约，存入最小资金（<$1K），监控72小时
  - 阶段2：存入中等资金（$10K-$100K），监控1周
  - 阶段3：存入目标资金量，启用Timelock+多签管理
  - 紧急暂停：集成OpenZeppelin Defender/自定义Pause机制
- 部署验证：Etherscan/BscScan源代码验证，Sourcify去中心化验证

**F. 运营监控**
- Tenderly：实时交易模拟、Gas优化、告警规则（大额转账/异常调用/权限变更）
- Forta：去中心化安全监控网络，社区Bot检测异常模式
- 应急响应：多签紧急暂停→漏洞修复→审计→重新上线

**输出物**: `安全审计报告_项目名称.md`（含审计公司报告、漏洞修复记录、形式化验证结果、部署计划、监控配置）。

**[参考: Agents知识库/0_超级编程行业知识库/区块链与Web3/区块链基础.md > 四、共识与合约的安全防御]`
**[参考: Agents知识库/0_超级编程行业知识库/区块链与Web3.md > 领域定位]`

---

## Post-Workflow

1. Read `checklist/blockchain_fundamentals_workflow_checklist.md`.
2. Cross-validate every output against every checklist item.
3. Only proceed to the next workflow or notify the user of completion after all checklist items pass.
