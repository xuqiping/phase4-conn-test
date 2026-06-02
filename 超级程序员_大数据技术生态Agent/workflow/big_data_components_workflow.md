# Big Data Components Workflow

## Purpose

基于大数据基础组件知识体系，为用户提供大数据存储计算基础设施选型（HDFS/Ozone/对象存储）、计算引擎（Spark 4.x/Flink 2.0）、数据同步工具（SeaTunnel替代DataX/Canal）、湖仓表格式（Iceberg/Delta/Paimon）及部署架构（K8s+Operator/Spot降本）的技术方案支持。覆盖Ozone替代HDFS、Spark RTM实时模式、Flink 2.0存算分离、Spark RAPIDS GPU加速等2026核心演进趋势。

## Prerequisites

- 用户已明确大数据基础组件场景或问题
- 知识库文件 `06_大数据处理与BI.md` 及子目录文件可访问

## Steps

### Step 1: 识别大数据基础组件需求场景

**Goal**: 明确用户的大数据基础设施需求类型、数据规模和实时性要求
**Completion criterion**: 已确定场景标签、数据规模、计算类型和部署环境

1. 读取用户消息，提取以下信息：
   - 场景类型：存储选型 / 计算引擎选型 / 数据同步工具 / 湖仓表格式选型 / 集群部署架构 / 性能优化 / 替代升级
   - 数据规模：数据总量（TB/PB/EB级）、日增量（GB/TB级）、数据文件数量（万/亿级）、文件大小分布（小文件/大文件混合）
   - 计算类型：批处理（T+1离线分析） / 流处理（实时秒级/毫秒级） / 批流一体（统一引擎） / 交互式分析（Ad-hoc查询） / ML训练（Spark ML/Flink ML）
   - 现有技术栈：HDFS/YARN/Hive/Spark 3.x/Flink 1.x/DataX/Canal/其他，是否有容器化（K8s）基础
   - 部署环境：自建IDC / 公有云（阿里云/腾讯云/华为云） / 混合云 / 信创环境（鲲鹏/麒麟）
   - 关键痛点：存储瓶颈（NameNode内存/小文件） / 计算慢（Spark Shuffle/资源争抢） / 数据同步延迟 / 湖仓选型纠结 / 成本高（常驻集群利用率低）

2. 对照知识库中的典型技术栈组合初步判断：
   - 互联网企业+海量小文件+批流一体 → Spark+Flink+Iceberg+K8s
   - 金融企业+强一致+实时特征 → CDP/ FusionInsight+Spark+Flink+Paimon
   - 传统企业上云+成本敏感 → Spark on K8s+Ozone+Iceberg+Spot实例
   - 创业公司+轻量 → PostgreSQL+dbt+Metabase（非大数据但适用）
   - 信创合规 → FusionInsight+SeaTunnel+Paimon+国产BI

3. 如有信息缺失，向用户追问不超过2个澄清问题。

**Knowledge Base Reference**:
- [参考: Agents知识库/0_超级编程行业知识库/06_大数据处理与BI.md > 大数据基础组件]
- [参考: Agents知识库/0_超级编程行业知识库/06_大数据处理与BI.md > 各L2摘要 > 大数据基础组件]
- [参考: Agents知识库/0_超级编程行业知识库/06_大数据处理与BI.md > 典型技术栈组合]

### Step 2: 输出大数据基础组件方案

**Goal**: 产出针对性的存储计算基础设施选型、部署架构或优化方案
**Completion criterion**: 输出包含推荐技术栈、架构设计、部署方案、性能优化建议

根据Step 1确定的场景，按以下分支处理：

**分支A — 存储底座选型**：
1. 输出存储方案对比：
   - HDFS：成熟稳定、NameNode内存瓶颈（~4亿文件上限）、小文件问题（不适合<128MB文件）、强一致、社区维护
   - Ozone：对象存储接口（S3兼容）、NameNode无瓶颈（基于RocksDB的元数据管理）、小文件友好（自动合并）、2026新建平台首选
   - 对象存储（S3/OSS/COS）：无限扩展、低成本归档、适合冷数据、与计算引擎集成（S3A/S3Select）
   - 混合方案：热数据Ozone/HDFS+温冷数据对象存储+归档磁带/冰川
2. 给出元数据管理：
   - Hive Metastore：传统方案、功能有限、性能瓶颈
   - Iceberg REST Catalog：湖仓元数据新标准、无状态服务、多引擎共享、高可用部署
   - Glue Data Catalog：云托管、自动爬虫、与AWS生态深度集成
   - 自研元数据服务：基于MySQL/PostgreSQL+缓存、适合大规模自定义需求
3. 输出HDFS→Ozone迁移路径：
   - 评估阶段：文件数量/大小分布/访问模式分析、Ozone集群容量规划
   - 双写阶段：新数据写入Ozone、旧数据保留HDFS、应用层路由
   - 迁移阶段：DistCp批量迁移（大文件优先）、小文件合并后迁移、校验和对比
   - 切换阶段：HDFS进入只读、Ozone成为主存储、HDFS退役
4. 附信创存储适配：FusionStorage（华为）、HBlock（浪潮）、基于Ceph的对象存储。

**分支B — 计算引擎选型与架构**：
1. 输出批处理引擎对比：
   - Spark 4.x：统一计算引擎（批+流+SQL+ML）、DAG内存计算、AQE自适应查询执行（自动分区合并/倾斜处理/Join策略切换）、RTM实时模式（低延迟批处理）、RAPIDS GPU加速（CuDF/CuML，SQL 5-10倍加速）、Connect模式（1.5MB客户端调用分布式计算）
   - Hive 4.0：SQL批处理、LLAP低延迟分析、ORC/Parquet列式存储、适合传统ETL和报表
   - Trino/Presto：交互式分析（亚秒级）、MPP架构、联邦查询（跨Hive/Iceberg/PostgreSQL/MySQL）、适合Ad-hoc BI查询
   - StarRocks/Doris：实时OLAP（向量化执行、短查询优化）、适合高并发BI查询、与湖仓集成（Iceberg外表）
2. 输出流处理引擎对比：
   - Flink 2.0：存算分离（ForSt状态后端、RocksDB外部化）、Materialized Table（物化表，流批统一语义）、CDC YAML Pipeline（声明式数据同步）、与Paimon深度集成（Streaming Lakehouse）
   - Spark Streaming：微批模式（秒级延迟）、与Spark SQL/ML统一、适合批流一体场景
   - Kafka Streams：轻量级、内嵌在应用中、毫秒级延迟、适合简单流处理（聚合/窗口/Join）
3. 给出计算资源调度：
   - YARN：传统方案、成熟但资源隔离弱、适合已有Hadoop生态
   - YuniKorn：YARN+K8s统一调度、队列管理、适合混合负载
   - K8s原生：Spark on K8s（Spark Operator）、Flink on K8s（Flink Kubernetes Operator）、资源隔离强、弹性伸缩
   - 选型建议：新建平台直接K8s+Operator，已有YARN集群渐进迁移
4. 输出部署架构模板：
   - 离线分析集群：Spark 4.x on K8s + Ozone/Iceberg + Hive Metastore/REST Catalog + Airflow调度
   - 实时流处理集群：Flink 2.0 on K8s + Kafka/Pulsar + Paimon/Iceberg + Prometheus/Grafana监控
   - 交互式分析集群：Trino/StarRocks + Iceberg外表 + 查询缓存 + 查询路由层
   - ML训练集群：Spark ML on Connect + RAPIDS GPU节点 + MLflow模型管理

**分支C — 数据同步工具选型**：
1. 输出同步工具对比：
   - SeaTunnel（推荐）：批流一体Zeta引擎（统一API批/流/CDC）、100+连接器（数据库/消息队列/文件/SaaS）、信创适配（达梦/人大金仓/神通）、活跃社区、2026替代DataX/Canal首选
   - DataX：阿里开源、批处理为主、30+连接器、进入维护期（停止新功能开发）、已有系统可继续用但不建议新项目
   - Canal：阿里开源、MySQL Binlog实时同步、单数据源、功能有限、进入维护期
   - Flink CDC：Flink原生、CDC数据源（MySQL/PostgreSQL/MongoDB/Oracle）、与Flink计算深度集成、适合Flink生态用户
   - Debezium：开源CDC、Kafka Connect集成、多数据源、社区活跃、适合Kafka生态用户
2. 给出同步场景匹配：
   - 异构数据源批量同步（MySQL→Hive/ClickHouse/StarRocks）→ SeaTunnel
   - MySQL实时CDC→Kafka→实时分析 → Flink CDC或Debezium
   - 已有DataX大量Job需要维护 → 渐进迁移到SeaTunnel（SeaTunnel提供DataX兼容模式）
   - 云厂商DTS（阿里云/腾讯云数据传输服务）→ 适合云环境、免运维但成本高、功能受限
3. 输出同步架构设计：
   - 批量同步：调度（Airflow/DolphinScheduler）→ SeaTunnel Job → 目标存储 → 校验（行数/MD5/抽样对比）→ 告警
   - 实时CDC：数据源CDC捕获 → 消息队列（Kafka/Pulsar）→ 消费者（Flink/Spark Streaming/SeaTunnel）→ 目标存储 → 延迟监控
   - 数据湖入湖：CDC → Kafka → Flink → Iceberg/Paimon（Merge-on-Read/Copy-on-Write策略选择）→ 元数据注册
4. 附性能优化：并行度调优（source/sink并行度匹配）、批量写入（sink.batch.size）、反压处理（backpressure监控）、Exactly-Once语义（两阶段提交）。

**分支D — 湖仓表格式选型**：
1. 输出三足鼎立对比：
   - Apache Iceberg：开放性最强（Spark/Flink/Trino/StarRocks/Doris全部支持）、REST Catalog新标准、Schema Evolution灵活、Partition Evolution动态分区、Hidden Partitioning、Time Travel、社区最活跃、2026新数仓首选
   - Delta Lake：Databricks主导、与Spark深度集成、ACID成熟、Liquid Clustering自动优化、Predictive I/O智能读取、但生态相对封闭（其他引擎支持较弱）
   - Apache Paimon：流批一体首选、Flink生态原生支持、LSM-Tree存储结构、支持实时更新（Merge-on-Read/Full Compaction）、与Flink Materialized Table深度集成、适合实时数仓场景
2. 给出选型决策树：
   - 多引擎共存（Spark+Flink+Trino+StarRocks都要查）→ Iceberg（开放性最佳）
   - Spark生态深度用户+Databricks平台 → Delta Lake（集成最深）
   - Flink实时流处理为主+实时更新需求 → Paimon（Flink原生集成）
   - 新建平台无历史包袱 → Iceberg（REST Catalog+多引擎+Schema Evolution全套能力）
3. 输出湖仓架构设计：
   - 存储层：对象存储（S3/OSS/COS/Ozone）→ 表格式（Iceberg/Delta/Paimon）→ 元数据（REST Catalog/Hive Metastore）
   - 计算层：批处理（Spark）+ 流处理（Flink）+ 交互分析（Trino/StarRocks）+ 机器学习（Spark ML）
   - 入湖策略：CDC实时入湖（Flink CDC→Kafka→Flink→Iceberg/Paimon）+ 批量入湖（SeaTunnel→对象存储→Iceberg注册）
   - 治理层：数据质量校验（Great Expectations/Deequ）+ 数据血缘（OpenLineage）+ 元数据管理（DataHub）
4. 附性能优化：Compaction策略（小文件合并、Z-Order排序、Hilbert曲线空间填充）、索引加速（Bloom Filter/Partition Statistics）、缓存层（Alluxio/本地SSD缓存热数据）。

**分支E — 集群部署与成本优化**：
1. 输出部署模式选型：
   - 自建IDC：硬件采购+运维团队+机房托管、长期成本低但CAPEX高、适合数据量大且稳定的企业
   - 公有云EMR/CDP：按需创建/销毁集群、云厂商托管Hadoop生态、适合弹性需求/快速启动
   - K8s+Operator：云原生部署、资源隔离强、弹性伸缩、适合已有K8s基础设施
   - Serverless（云厂商无服务器大数据）：AWS Athena/GCP BigQuery/阿里云MaxCompute、零运维、按查询付费、适合中小规模/探索性分析
2. 给出成本优化策略：
   - Spot实例/抢占式实例：降本60%-80%、适合容错性高的批处理Job（Spark/Flink Checkpoint恢复）、需要设计重试机制
   - 自动伸缩：K8s HPA（基于队列深度/CPU/内存）、集群自动启停（非工作时间自动缩到零）、Airflow任务触发集群创建
   - 存储分层：热数据SSD/温数据HDD/冷数据对象存储归档、生命周期自动迁移策略
   - 计算资源隔离：队列管理（YuniKorn/K8s Queue）、资源配额（Namespace级别）、优先级调度（高优先级Job抢占低优先级）
3. 输出GPU加速方案：
   - Spark RAPIDS：CuDF加速DataFrame操作、CuML加速ML算法、CuGraph加速图计算、SQL查询5-10倍加速
   - 适用场景：大规模ETL（数据清洗/转换）、ML特征工程（向量化/归一化/编码）、图分析（PageRank/社区发现）
   - 部署要点：GPU节点池（NVIDIA T4/V100/A100）、RAPIDS插件配置、CUDA版本匹配、内存+显存协同管理
4. 附监控运维：Prometheus+Grafana（集群资源/Job性能）、日志聚合（ELK/Loki）、告警规则（Job失败/资源不足/延迟超标）。

将结果保存到 `output/big_data_components.md` 或直接在对话中呈现。

**Knowledge Base Reference**:
- [参考: Agents知识库/0_超级编程行业知识库/06_大数据处理与BI.md > 各L2摘要 > 大数据基础组件]
- [参考: Agents知识库/0_超级编程行业知识库/06_大数据处理与BI.md > 典型技术栈组合]
- [参考: Agents知识库/0_超级编程行业知识库/06_大数据处理与BI.md > 跨领域关联 > 存储计算 → 数据治理/BI分析]

### Step 3: 验证与交付

**Goal**: 确保大数据基础组件方案技术可行、与知识库一致
**Completion criterion**: 已通过 checklist 逐项核对

1. 读取 `checklist/big_data_components_workflow_checklist.md`。
2. 逐项核对输出是否覆盖要求的知识点。
3. 确认技术选型数据（Spark 4.x/Flink 2.0/SeaTunnel 100+连接器等）准确。
4. 如有遗漏或偏差，补充修正。
5. 向用户交付最终答案。

## Post-Workflow

1. 记录完成状态到 `task/current_task.md`。
2. 如需深入某一具体技术（如"Flink 2.0 ForSt存算分离配置"、"Iceberg REST Catalog高可用部署"），在当前 Agent 内继续追问并输出。
