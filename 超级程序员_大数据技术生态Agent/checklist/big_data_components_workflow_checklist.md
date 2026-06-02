# Big Data Components Workflow Checklist

在完成 `workflow/big_data_components_workflow.md` 的每一步后，使用此检查清单进行交叉验证。每个项目必须回答**是**才算完成。如果有任何项目回答**否**，修复输出并重新验证。

## Step 1: 识别大数据基础组件需求场景

- [ ] 已明确场景类型（存储选型/计算引擎选型/数据同步工具/湖仓表格式选型/集群部署架构/性能优化/替代升级）
- [ ] 已提取数据规模（数据总量TB/PB/EB级、日增量GB/TB级、文件数量、文件大小分布）
- [ ] 已提取计算类型（批处理/流处理/批流一体/交互式分析/ML训练）
- [ ] 已识别现有技术栈（HDFS/YARN/Hive/Spark 3.x/Flink 1.x/DataX/Canal/容器化基础）
- [ ] 已识别部署环境（自建IDC/公有云/混合云/信创环境）
- [ ] 已提取关键痛点（存储瓶颈/计算慢/数据同步延迟/湖仓选型纠结/成本高）
- [ ] 已对照知识库典型技术栈组合完成初步判断（互联网企业→Spark+Flink+Iceberg+K8s/金融→CDP+Paimon/信创→FusionInsight+SeaTunnel）
- [ ] 如有信息缺失，已向用户追问不超过2个澄清问题

## Step 2: 输出大数据基础组件方案

- [ ] 如为存储底座选型，HDFS/Ozone/对象存储对比已输出（NameNode瓶颈/小文件友好/S3兼容/混合方案）
- [ ] 如为存储底座选型，元数据管理已覆盖（Hive Metastore/Iceberg REST Catalog/Glue Data Catalog/自研）
- [ ] 如为存储底座选型，HDFS→Ozone迁移路径已给出（评估→双写→迁移→切换）
- [ ] 如为存储底座选型，信创存储适配已说明（FusionStorage/HBlock/Ceph）
- [ ] 如为计算引擎选型，批处理引擎对比已输出（Spark 4.x AQE RTM RAPIDS/Hive 4.0 LLAP/Trino/StarRocks Doris）
- [ ] 如为计算引擎选型，流处理引擎对比已覆盖（Flink 2.0 ForSt Materialized Table/Spark Streaming/Kafka Streams）
- [ ] 如为计算引擎选型，资源调度选型已说明（YARN/YuniKorn/K8s原生/选型建议）
- [ ] 如为计算引擎选型，部署架构模板已给出（离线分析/实时流处理/交互式分析/ML训练）
- [ ] 如为数据同步工具，SeaTunnel/DataX/Canal/Flink CDC/Debezium对比已输出
- [ ] 如为数据同步工具，同步场景匹配已给出（异构批量→SeaTunnel/MySQL CDC→Flink CDC/已有DataX→渐进迁移）
- [ ] 如为数据同步工具，同步架构设计已覆盖（批量同步/实时CDC/数据湖入湖）
- [ ] 如为数据同步工具，性能优化已说明（并行度/批量写入/反压处理/Exactly-Once）
- [ ] 如为湖仓表格式，Iceberg/Delta/Paimon三足鼎立对比已输出（开放性/生态绑定/流批一体）
- [ ] 如为湖仓表格式，选型决策树已给出（多引擎→Iceberg/Spark+Databricks→Delta/Flink实时→Paimon）
- [ ] 如为湖仓表格式，湖仓架构设计已覆盖（存储层/计算层/入湖策略/治理层）
- [ ] 如为湖仓表格式，性能优化已说明（Compaction策略/索引加速/缓存层）
- [ ] 如为集群部署，部署模式选型已对比（自建IDC/公有云EMR/K8s+Operator/Serverless）
- [ ] 如为集群部署，成本优化策略已覆盖（Spot实例降本60-80%/自动伸缩/存储分层/资源隔离）
- [ ] 如为集群部署，GPU加速方案已说明（Spark RAPIDS CuDF CuML/适用场景/部署要点）
- [ ] 所有核心论断均能在知识库中找到支撑来源

## Step 3: 验证与交付

- [ ] 已读取对应 checklist 并逐项核对
- [ ] 技术选型数据（Spark 4.x/Flink 2.0/SeaTunnel 100+连接器等）准确
- [ ] 已向用户交付最终答案

## Overall

- [ ] 工作流中的所有步骤已按顺序执行，没有跳过
- [ ] 每一步都已与其检查清单部分进行交叉验证
- [ ] 没有在任何检查清单部分通过前提前进入下一步
- [ ] `task/current_task.md` 已更新完成记录
- [ ] 所有 `[参考: ...]` 标注均指向存在的知识库文件
