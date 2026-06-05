# AGENTS.md — Task Routing Table

## Agent: 超级程序员_数据库与数据存储Agent

本文件是任务路由的唯一真相来源。当用户向本子Agent提出请求时，读取此表以确定加载哪个细粒度工作流。

## Routing Table

| 任务关键词 / 意图 | 工作流文件 | 描述 |
|------------------|-----------|------|
| MySQL,PostgreSQL,Oracle,SQL Server,国产数据库,关系型数据库,SQL优化,数据库选型,RDBMS,主从复制,读写分离,MGR,高可用,InnoDB,执行计划,索引优化,国产替代,信创,迁移,分库分表 | workflow/relational_database_workflow.md | 关系型数据库选型（MySQL/PG/Oracle/国产库）、架构设计、SQL优化、高可用、国产替代 |
| MongoDB,Elasticsearch,ES,InfluxDB,Neo4j,向量数据库,Milvus,Weaviate,NoSQL,文档数据库,时序数据库,图数据库,向量检索,RAG,Dense,Sparse,混合检索,多跳查询 | workflow/nosql_database_workflow.md | NoSQL数据库选型（MongoDB/ES/InfluxDB/Neo4j/Milvus）、架构设计、向量检索RAG、性能调优 |
| 数据仓库,数仓,湖仓一体,Data Lakehouse,Hive,Spark,Flink,实时数仓,Kimball,OneData,维度建模,BI,数据分析,ETL,数据建模,流批一体,Lambda,Kappa,Iceberg,Delta,Hudi,Paimon,StarRocks | workflow/data_warehouse_lakehouse_workflow.md | 数据仓库/湖仓一体：Kimball建模、离线/实时数仓、湖仓选型（Iceberg/Delta/Hudi）、流批一体 |
| 数据库运维,DBA,性能调优,慢查询,执行计划,索引优化,容灾备份,故障排查,数据库迁移,升级,监控,主从延迟,ShardingSphere,TiDB,OceanBase,恢复演练,3-2-1-1-0 | workflow/database_operations_workflow.md | 数据库运维与调优：索引优化、分库分表、主从复制高可用、容灾备份（3-2-1-1-0原则） |

## Notes

- 本子Agent处理所有与数据库、数据存储、数据仓库相关的请求。
- 关键词覆盖范围要足够广以捕获同义/转述请求。
- 新增能力时在此表添加新行，并创建对应 workflow + checklist 文件对。

## Cross-Module Dependencies

- `relational_database_workflow.md` Step 2 中的向量索引可能引用 [参考: Agents知识库/0_超级编程行业知识库/03_数据库与数据存储.md > NoSQL数据库 > 向量数据库]
- `data_warehouse_lakehouse_workflow.md` Step 2 中的实时数仓可能引用 [参考: Agents知识库/0_超级编程行业知识库/02_网络架构与中间件.md > 消息队列中间件 > Kafka]（CDC数据流）
- `database_operations_workflow.md` Step 2 中的监控告警可能引用 [参考: Agents知识库/0_超级编程行业知识库/02_网络架构与中间件.md > 服务治理与观测 > 监控告警体系]（数据库指标接入Prometheus）
