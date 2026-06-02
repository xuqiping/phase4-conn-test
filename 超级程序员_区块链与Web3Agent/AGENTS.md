# AGENTS.md — Task Routing Table

## Agent: 超级程序员_区块链与Web3Agent

本文件是任务路由的唯一真相来源。当用户向本子Agent提出请求时，读取此表以确定加载哪个细粒度工作流。

## Routing Table

| 任务关键词 / 意图 | 工作流文件 | 描述 |
|------------------|-----------|------|
| 区块链基础,比特币,以太坊,PoW,PoS,BFT,DPoS,DAG,共识机制,智能合约,Solidity,Move,EVM,Rollup,L2,Layer2,跨链,DeFi,RWA,预言机,Chainlink,ERC-20,ERC-721,ERC-1155,安全审计,重入攻击,MEV,闪电贷,形式化验证,Gas优化,Foundry,Hardhat,Uniswap,Aave,再质押,EigenLayer,BitVM,比特币ETF | workflow/blockchain_fundamentals_workflow.md | 区块链基础与智能合约：共识机制选型（PoW/PoS/BFT/DPoS/DAG）/链平台选型（比特币/以太坊L2/Sui/IOTA）/智能合约开发（Solidity+Move/Foundry+Hardhat）/DeFi+RWA合约/安全审计与形式化验证 |
| 联盟链,国产链,Hyperledger Fabric,Fabric,长安链,蚂蚁链,FISCO BCOS,趣链,百度超级链,国密,SM2,SM3,SM4,等保,密评,信创,政务区块链,BaaS,Channel,Chaincode,Peer,Orderer,MSP,私有数据,PDC,跨链互操作,WECROSS,Poly Enterprise,政务存证,司法存证,数据共享,目录区块链 | workflow/consortium_blockchain_workflow.md | 联盟链与国产链：Fabric 3.x架构（Peer/Orderer/Channel/Chaincode）/国产平台五维选型（长安链/蚂蚁链/FISCO BCOS/趣链/百度超级链）/国密合规（SM2/SM3/SM4+密评+等保）/政务落地（五横三纵架构/目录区块链/资金监管）/K8s运维 |
| 元宇宙,NFT,数字资产,Web3,DePIN,AI Agent,稳定币,RWA,GameFi,SocialFi,DAO,Tokenomics,ERC-3525,ERC-3643,ERC-6551,SBT,BIGANT,数字孪生,UE5,Unity,AIGC,VR,AR,MR,数字人,数字藏品,香港VASP,LEAP,Ensemble沙盒,Web3创业,合规路径,虚拟资产 | workflow/web3_digital_assets_workflow.md | 元宇宙与数字资产：BIGANT六维架构（区块链/交互/引擎/AI/网络/孪生）/NFT协议选型（ERC-721/1155/3525/3643/6551）/数字资产发行与合规（KYC/AML/香港LEAP）/Web3创业八大赛道（AI Agent/RWA/DePIN/稳定币/工具SaaS） |

## Notes

- 本子Agent处理所有与区块链底层技术、智能合约开发、联盟链部署、国产链选型、国密合规、元宇宙架构、NFT数字资产、Web3创业相关的请求。
- 关键词覆盖范围要足够广以捕获同义/转述请求。
- 新增能力时在此表添加新行，并创建对应 workflow + checklist 文件对。

## Cross-Module Dependencies

- `blockchain_fundamentals_workflow.md` Step 1 中的共识安全模型可能引用 [参考: Agents知识库/0_超级编程行业知识库/07_网络安全与信息安全.md > 应用安全]（密码学安全/密钥管理/安全编码）
- `blockchain_fundamentals_workflow.md` Step 3 中的智能合约安全可能引用 [参考: Agents知识库/0_超级编程行业知识库/07_网络安全与信息安全.md > 应用安全]（SAST/代码审计/漏洞扫描）
- `consortium_blockchain_workflow.md` Step 2 中的国产链选型可能引用 [参考: Agents知识库/0_超级编程行业知识库/13_IT合规与行业标准.md]（国密合规/等保/密评）
- `consortium_blockchain_workflow.md` Step 4 中的K8s运维可能引用 [参考: Agents知识库/0_超级编程行业知识库/04_云计算与云原生.md > 容器技术]（K8s/Operator/GitOps/监控告警）
- `web3_digital_assets_workflow.md` Step 2 中的AIGC内容生成可能引用 [参考: Agents知识库/0_超级编程行业知识库/05_人工智能与大模型.md > AI应用开发]（AIGC/文生图/3D生成）
- `web3_digital_assets_workflow.md` Step 4 中的创业合规可能引用 [参考: Agents知识库/0_超级编程行业知识库/13_IT合规与行业标准.md]（数据安全法/个人信息保护法/跨境合规）
