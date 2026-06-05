# Data Warehouse & Lakehouse Workflow

## Purpose

基于数据仓库与湖仓一体知识体系，为用户提供数仓建模（Kimball维度建模/阿里OneData）、离线/实时数仓架构设计、湖仓一体（Iceberg/Delta/Hudi）选型、流批一体（Flink+Paimon+StarRocks）等技术方案支持。覆盖Hive 4.0/Spark 4.0离线底座、Flink实时引擎、对象存储ACID事务等核心技术。

## Prerequisites

- 用户已明确数据仓库/湖仓场景或问题
- 知识库文件 `03_数据库与数据存储.md` 及子目录文件可访问

## Steps

### Step 1: 识别数据仓库/湖仓需求场景

**Goal**: 明确用户的数仓/湖仓需求类型、数据规模和实时性要求
**Completion criterion**: 已确定场景标签、数据规模、实时性要求和现有技术栈

1. 读取用户消息，提取以下信息：
   - 场景类型：数仓建模设计 / 离线数仓架构 / 实时数仓架构 / 湖仓一体选型 / 流批一体改造 / BI报表优化
   - 数据规模：数据量（TB/PB级）、日增量（GB/TB）、数据源数量（个位数/数十个/上百个）
   - 实时性要求：离线（T+1）/ 准实时（分钟级）/ 实时（秒级/毫秒级）
   - 现有技术栈：已有Hive/Spark/Flink/其他、云厂商（阿里云/腾讯云/华为云/其他）、是否有对象存储（S3/OSS/COS）
   - 关键痛点：查询慢 / 数据不一致 / Schema变更困难 / 实时化需求 / 成本过高
   - 业务场景：BI报表 / 实时大屏 / AI特征工程 / 数据治理 / 多租户数据服务

2. 对照知识库中的架构演进路径初步判断：
   - 离线为主+成本敏感 → Hive 4.0+Spark 4.0（ORC/Parquet+Tez/LLAP）
   - 需要Schema演进+Time Travel → Iceberg/Delta Lake/Hudi（湖仓一体）
   - 实时化需求强 → Flink为核心+Paimon+StarRocks（流批一体）
   - 已有Lambda架构需简化 → Kappa或流批一体替代

3. 如有信息缺失，向用户追问不超过2个澄清问题。

**Knowledge Base Reference**:
- [参考: Agents知识库/0_超级编程行业知识库/03_数据库与数据存储.md > 数据仓库与湖仓一体]
- [参考: Agents知识库/0_超级编程行业知识库/03_数据库与数据存储.md > 各L2摘要 > 数据仓库与湖仓一体]

### Step 2: 输出数据仓库/湖仓方案

**Goal**: 产出针对性的数仓/湖仓选型、建模或架构方案
**Completion criterion**: 输出包含推荐架构、建模方法论、技术栈、实施路径

根据Step 1确定的场景，按以下分支处理：

**分支A — 数仓建模设计**：
1. 输出建模方法论对比：Kimball维度建模（星型模型，适合BI报表、快速迭代）vs Inmon企业级建模（3NF，适合大型企业、数据治理严格）vs 阿里OneData体系（中国互联网企业主流、业务过程+原子指标+派生指标）。
2. 给出维度建模实施步骤：业务过程梳理 → 粒度确定 → 维度设计（维度表/退化维度/缓慢变化维）→ 事实表设计（事务事实/周期快照/累积快照）。
3. 输出指标体系建设：原子指标（业务过程+度量+修饰词）→ 派生指标（原子指标+时间周期+业务范围）→ 复合指标（派生指标运算）。
4. 附命名规范：表命名（层级_主题_实体_周期）、字段命名（词根法）、指标命名（业务过程_度量_修饰词_时间周期）。

**分支B — 离线数仓架构**：
1. 输出分层架构设计：ODS（原始数据层）→ DWD（明细数据层，清洗+脱敏）→ DWS（汇总数据层，轻度/中度/高度聚合）→ ADS（应用数据层，面向具体应用）。
2. 给出技术栈组合：
   - 存储：Hive 4.0（ORC/Parquet列式存储）+ HDFS/S3对象存储
   - 计算：Spark 4.0（批处理+SQL引擎）/ Tez / LLAP（低延迟分析）
   - 调度：Airflow / DolphinScheduler / Azkaban
   - 元数据：Hive Metastore / Atlas / DataHub
3. 输出ETL设计规范：抽取策略（全量/增量/CDC）、转换规则（清洗/关联/聚合）、加载策略（覆盖/追加/合并）。
4. 附数据质量校验：完整性/准确性/一致性/及时性/有效性校验规则。

**分支C — 实时数仓架构**：
1. 输出架构演进路径：Lambda（批处理+流处理双路径，维护成本高）→ Kappa（纯流处理，对消息队列依赖强）→ 流批一体（统一引擎+Flink+Paimon+StarRocks，趋势方向）。
2. 给出流批一体技术栈：
   - 采集：Flink CDC / Debezium / Canal（MySQL Binlog实时捕获）
   - 计算：Flink（流处理+批处理统一SQL）
   - 存储：Paimon（流批一体存储，LSM-Tree+对象存储）/ StarRocks（实时OLAP，向量化执行）
   -  serving：StarRocks / Doris / ClickHouse（高并发低延迟查询）
3. 输出实时化实施路径：试点场景选择（实时大屏/实时监控）→ 数据源CDC接入 → Flink实时ETL → 实时OLAP查询 → 全场景推广。
4. 附实时与离线一致性保障：Lambda架构的数据对账、Kappa架构的消息回放验证。

**分支D — 湖仓一体选型与实施**：
1. 输出湖仓一体三大引擎对比：
   - Apache Iceberg：开放标准、多引擎支持（Spark/Flink/Trino）、Hive兼容性好、社区活跃
   - Delta Lake：Databricks主导、ACID事务成熟、Time Travel完善、与Spark深度集成
   - Apache Hudi：Uber开源、增量处理友好、索引机制丰富、适合CDC场景
2. 给出选型决策树：
   - 多引擎共存（Spark+Flink+Trino）→ Iceberg
   - Spark生态深度用户+Databricks平台 → Delta Lake
   - CDC增量处理为主+索引加速 → Hudi
3. 输出湖仓一体架构设计：对象存储（S3/OSS）→ 湖仓格式（Iceberg/Delta/Hudi）→ 计算引擎（Spark/Flink/Trino）→ 元数据管理（Hive Metastore+Glue/自研）。
4. 附关键特性应用：Schema演进（添加/删除/修改列）、Time Travel（历史版本查询）、分区演化（动态分区策略调整）、 hidden partitioning（隐藏分区简化查询）。

将结果保存到 `output/data_warehouse_architecture.md` 或直接在对话中呈现。

**Knowledge Base Reference**:
- [参考: Agents知识库/0_超级编程行业知识库/03_数据库与数据存储.md > 各L2摘要 > 数据仓库与湖仓一体]
- [参考: Agents知识库/0_超级编程行业知识库/数据库与数据存储/数据仓库与湖仓一体.md > Kimball维度建模/OneData/Hive/Spark/Iceberg/Delta/Hudi/Flink]

### Step 3: 验证与交付

**Goal**: 确保数仓/湖仓方案准确、可落地、与知识库一致
**Completion criterion**: 已通过 checklist 逐项核对

1. 读取 `checklist/data_warehouse_lakehouse_workflow_checklist.md`。
2. 逐项核对输出是否覆盖要求的知识点。
3. 确认所有关键论断均能在知识库中找到支撑。
4. 如有遗漏或偏差，补充修正。
5. 向用户交付最终答案。

## Post-Workflow

1. 记录完成状态到 `task/current_task.md`。
2. 如需深入某一具体技术（如"Flink CDC MySQL Binlog接入实战"、"Iceberg Schema Evolution配置"），在当前 Agent 内继续追问并输出。