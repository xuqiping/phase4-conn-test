# Relational Database Workflow

## Purpose

基于关系型数据库知识体系，为用户提供关系型数据库选型（MySQL 8.0/PostgreSQL 17/Oracle/SQL Server/国产库）、架构设计、SQL优化、高可用方案或国产信创替代的技术支持。覆盖InnoDB存储引擎、执行计划分析、MGR高可用、读写分离等核心技术。

## Prerequisites

- 用户已明确关系型数据库场景或问题
- 知识库文件 `03_数据库与数据存储.md` 及子目录文件可访问

## Steps

### Step 1: 识别关系型数据库需求场景

**Goal**: 明确用户的关系型数据库需求类型和业务约束
**Completion criterion**: 已确定场景标签、数据库类型约束、性能目标和合规要求

1. 读取用户消息，提取以下信息：
   - 场景类型：数据库选型 / 架构设计 / SQL优化 / 高可用方案 / 国产替代 / 迁移升级
   - 业务类型：互联网OLTP / 金融交易 / 政企政务 / 微软生态 / SaaS多租户
   - 性能要求：QPS目标、延迟要求（P99）、数据量（行数/TB级）、并发连接数
   - 高可用要求：RTO/RPO目标、是否需要金融级可靠性、是否接受异步复制
   - 合规约束：信创要求（需国产库）、等保等级、数据不出域
   - 现有数据库：MySQL/Oracle/SQL Server/PostgreSQL/其他，是否需要迁移

2. 对照知识库中的五大方向初步判断候选数据库：
   - 互联网OLTP+高并发 → MySQL 8.0（高性能OLTP、广泛生态）
   - 复杂业务+扩展性需求 → PostgreSQL 17（功能完备、插件丰富）
   - 金融电信关键行业+现有Oracle → Oracle（保持地位但面临国产替代）或 OceanBase（分布式能力强）
   - 微软生态+BI场景 → SQL Server 2022（深耕微软生态）
   - 信创政策+政企政务 → 达梦（政务深耕20年）/ 人大金仓 / OceanBase

3. 如有信息缺失，向用户追问不超过2个澄清问题。

**Knowledge Base Reference**:
- [参考: Agents知识库/0_超级编程行业知识库/03_数据库与数据存储.md > 关系型数据库]
- [参考: Agents知识库/0_超级编程行业知识库/03_数据库与数据存储.md > 各L2摘要 > 关系型数据库]

### Step 2: 输出关系型数据库方案

**Goal**: 产出针对性的数据库选型、架构或优化方案
**Completion criterion**: 输出包含推荐数据库、架构设计、关键配置、优化建议

根据Step 1确定的场景，按以下分支处理：

**分支A — 数据库选型**：
1. 输出五大方向对比表（MySQL/PostgreSQL/Oracle/SQL Server/国产库），对比维度：
   - 性能特征（OLTP吞吐量、复杂查询能力、扩展性上限）
   - 功能完备性（JSON支持、全文检索、向量索引、GIS、存储过程）
   - 生态与工具（监控/备份/迁移工具成熟度、云厂商支持度）
   - 运维成本（许可费用、人力技能要求、自动化程度）
   - 信创适配（国产CPU/OS兼容性、等保/国密支持、政策合规）
2. 给出最终推荐并附决策理由，标注关键趋势（MySQL/PG开源主导、AI向量扩展、信创国产替代加速）。

**分支B — 架构设计**：
1. 输出存储引擎选型建议（MySQL InnoDB为核心，Buffer Pool/MVCC/Redo Log机制说明）。
2. 给出高可用架构方案：
   - 单节点 → 主从复制（异步/半同步） → MGR组复制（MySQL）/ Patroni（PG）
   - 读写分离架构（ProxySQL/MaxScale中间件、应用层分离）
   - 跨地域容灾（异地多活/冷备/温备）
3. 输出分库分表策略：ShardingSphere中间件、分片键设计原则、全局ID生成方案。
4. 附NewSQL替代路径评估（TiDB/OceanBase vs 传统分库分表）。

**分支C — SQL优化**：
1. 输出执行计划分析方法：EXPLAIN/DESCRIBE解读、type列优化目标（system→const→eq_ref→ref→range→index→ALL）。
2. 给出索引优化策略：B+Tree索引原理、覆盖索引、联合索引最左前缀、索引下推（ICP）。
3. 输出常见慢查询优化模式：全表扫描→索引优化、文件排序→索引覆盖、临时表→查询重写。
4. 附SQL编写规范：避免SELECT *、IN批量控制、分页优化、JOIN顺序。

**分支D — 国产替代/迁移**：
1. 输出国产库选型对比（OceanBase/达梦/人大金仓），覆盖分布式能力/兼容性/政务适配/生态成熟度。
2. 给出迁移评估方案：兼容性测试（SQL语法/存储过程/函数/触发器）、性能基准测试、数据一致性校验。
3. 输出迁移路径：双写并行 → 灰度切换 → 老库下线，附回滚预案。
4. 附运维体系重建要点：监控告警适配、备份策略调整、DBA培训计划。

将结果保存到 `output/rdbms_architecture.md` 或直接在对话中呈现。

**Knowledge Base Reference**:
- [参考: Agents知识库/0_超级编程行业知识库/03_数据库与数据存储.md > 各L2摘要 > 关系型数据库]
- [参考: Agents知识库/0_超级编程行业知识库/数据库与数据存储/关系型数据库.md > InnoDB存储引擎/高可用架构/国产替代]

### Step 3: 验证与交付

**Goal**: 确保数据库方案准确、可落地、与知识库一致
**Completion criterion**: 已通过 checklist 逐项核对

1. 读取 `checklist/relational_database_workflow_checklist.md`。
2. 逐项核对输出是否覆盖要求的知识点。
3. 确认所有关键论断均能在知识库中找到支撑。
4. 如有遗漏或偏差，补充修正。
5. 向用户交付最终答案。

## Post-Workflow

1. 记录完成状态到 `task/current_task.md`。
2. 如需深入某一具体技术（如"InnoDB Buffer Pool调优"、"OceanBase分布式事务"），在当前 Agent 内继续追问并输出。
