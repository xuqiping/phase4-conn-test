# skills-router.md — Skill Router

## Top-Level Skill

- **Name**: `programmer_blockchain_web3_agent_skill`
- **Purpose**: Task scheduling and routing for the 超级程序员_区块链与Web3Agent. Delegates actual execution to derivative skills.
- **Skill File Path**: `all_agents/超级程序员_区块链与Web3Agent/skills-router.md`

## Derivative Skills

| Skill Name | Purpose | Workflow File | Notes |
|------------|---------|---------------|-------|
| `programmer_blockchain_web3_agent_skill___blockchain_fundamentals` | 区块链基础与智能合约：共识选型/链平台选型/Solidity+Move开发/DeFi+RWA合约/安全审计与形式化验证 | workflow/blockchain_fundamentals_workflow.md | 面向公链开发、DeFi协议、智能合约安全 |
| `programmer_blockchain_web3_agent_skill___consortium_blockchain` | 联盟链与国产链：Fabric 3.x架构/国产平台五维选型/国密合规/政务落地/K8s运维 | workflow/consortium_blockchain_workflow.md | 面向企业级联盟链、政务区块链、国密信创 |
| `programmer_blockchain_web3_agent_skill___web3_digital_assets` | 元宇宙与数字资产：BIGANT六维架构/NFT协议选型/数字资产发行与合规/Web3创业八大赛道 | workflow/web3_digital_assets_workflow.md | 面向元宇宙建设、NFT项目、Web3创业 |

## Evolution Rules

1. When adding a new capability, check whether an existing derivative skill covers the same domain.
2. If yes, **update** the existing derivative skill; do **not** create a duplicate.
3. If no, create a new derivative skill following the naming convention: `programmer_blockchain_web3_agent_skill___<capability>`.
4. Update this table immediately after any skill change.
