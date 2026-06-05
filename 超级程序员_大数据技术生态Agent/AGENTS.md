# AGENTS.md — Task Routing Table

## Agent: 超级程序员_大数据技术生态Agent

本文件是任务路由的唯一真相来源。当用户向本子Agent提出请求时，读取此表以确定加载哪个细粒度工作流。

## Routing Table

| 任务关键词 / 意图 | 工作流文件 | 描述 |
|------------------|-----------|------|
| 大数据,HDFS,Ozone,Spark,Flink,Hive,DataX,Canal,SeaTunnel,数据同步,计算引擎,存储选型,湖仓,湖仓一体,Iceberg,Delta,Paimon,集群部署,K8s,大数据组件,批处理,流处理,批流一体,GPU加速,RAPIDS,Spot实例,成本优化,对象存储 | workflow/big_data_components_workflow.md | 大数据基础组件：存储底座选型（HDFS/Ozone/对象存储）、计算引擎（Spark 4.x/Flink 2.0）、数据同步（SeaTunnel替代DataX/Canal）、湖仓表格式（Iceberg/Delta/Paimon）、集群部署与成本优化 |
| 数据治理,DAMA,DCMM,数据标准,Data Contract,Standard as Code,数据血缘,OpenLineage,数据质量,六维模型,Data Observability,元数据,DataHub,Apache Atlas,Collibra,数据资产目录,数据目录,主动元数据,AI训练数据血缘,治理组织,CDO,Data Owner | workflow/data_governance_workflow.md | 数据治理：治理体系搭建（DAMA-DMBOK+DCMM）、数据标准代码化（Data Contract）、数据血缘（OpenLineage/AI训练数据血缘21.9亿美元市场）、数据质量（六维模型+AI驱动）、元数据管理（DataHub/主动元数据） |
| BI,可视化,数据分析,报表,Dashboard,Tableau,PowerBI,FineBI,Quick BI,Smartbi,Metabase,Superset,国产BI,信创BI,A/B测试,数据可视化,自助式报表,ChatBI,NLQ,Agentic BI,Headless BI,语义层,dbt Semantic Layer,Cube.dev,AARRR,漏斗,RFM,四层次分析,Gartner | workflow/bi_analytics_workflow.md | BI可视化与数据分析：BI平台选型（湖仓一体+AI原生+Headless BI三引擎）、数据分析方法论（Gartner四层次/AARRR/漏斗/RFM/A/B测试）、自助式报表四代演进（AI对话→Agentic BI）、国产BI（帆软/Quick BI/Smartbi） |

## Notes

- 本子Agent处理所有与大数据基础组件、数据治理、BI可视化与数据分析相关的请求。
- 关键词覆盖范围要足够广以捕获同义/转述请求。
- 新增能力时在此表添加新行，并创建对应 workflow + checklist 文件对。

## Cross-Module Dependencies

- `big_data_components_workflow.md` Step 2 中的计算引擎可能引用 [参考: Agents知识库/0_超级编程行业知识库/04_前端开发与用户交互.md > 容器技术 > Kubernetes]（Spark on K8s/Flink on K8s部署）
- `big_data_components_workflow.md` Step 2 中的对象存储可能引用 [参考: Agents知识库/0_超级编程行业知识库/04_前端开发与用户交互.md > 公有云厂商生态]（阿里云OSS/腾讯云COS/华为云OBS选型）
- `data_governance_workflow.md` Step 2 中的数据血缘可视化可能引用 [参考: Agents知识库/0_超级编程行业知识库/03_数据库与数据存储.md > NoSQL数据库 > 图数据库]（Neo4j/JanusGraph/ArangoDB图数据库选型）
- `bi_analytics_workflow.md` Step 2 中的BI查询引擎可能引用 [参考: Agents知识库/0_超级编程行业知识库/06_大数据处理与BI.md > 大数据基础组件 > 计算引擎]（Trino/StarRocks/Doris与Iceberg集成）
- `bi_analytics_workflow.md` Step 2 中的ChatBI安全可能引用 [参考: Agents知识库/0_超级编程行业知识库/05_人工智能与机器学习.md > 大模型技术体系 > RAG]（RAG检索增强SQL生成）
