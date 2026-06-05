# NoSQL Database Workflow

## Purpose

基于NoSQL数据库知识体系，为用户提供NoSQL选型（MongoDB/ES/InfluxDB/Neo4j/Milvus）、架构设计、数据模型优化或AI场景向量检索的技术支持。覆盖文档/检索/时序/图/向量五种NoSQL类型，以及Dense+Sparse混合检索等RAG标配技术。

## Prerequisites

- 用户已明确NoSQL场景或问题
- 知识库文件 `03_数据库与数据存储.md` 及子目录文件可访问

## Steps

### Step 1: 识别NoSQL需求场景

**Goal**: 明确用户的NoSQL需求类型、数据模型和AI关联度
**Completion criterion**: 已确定场景标签、数据模型类型、规模预期和一致性要求

1. 读取用户消息，提取以下信息：
   - 场景类型：NoSQL选型 / 架构设计 / 数据模型优化 / 向量检索RAG / 性能调优
   - 数据模型：文档型（JSON/嵌套对象） / 检索型（全文搜索/日志分析） / 时序型（IoT/监控指标） / 图型（关系网络/知识图谱） / 向量型（Embedding/语义检索）
   - 规模预期：数据量（GB/TB/PB）、写入QPS、查询QPS、时间跨度（时序数据）
   - 一致性要求：强一致 / 最终一致 / 可调一致性级别
   - AI关联度：是否用于RAG（检索增强生成）、是否需要Dense+Sparse混合检索、是否需要多跳关联查询
   - 现有技术栈：已有Elasticsearch/MongoDB/其他，是否需要迁移或升级

2. 对照知识库中的选型关键初步判断候选NoSQL：
   - 文档型（内容平台/游戏后端） → MongoDB（灵活Schema+水平分片）
   - 检索型（搜索/日志/RAG） → Elasticsearch（RRF+向量检索、事实标准）
   - 时序型（IoT/监控/金融tick） → InfluxDB 3.x（Apache Arrow重写、Cardinality无限制）
   - 图型（风控/知识图谱/社交网络） → Neo4j（原生图存储、多跳关联查询）
   - 向量型（AI Embedding/RAG） → Milvus/Weaviate（Dense+Sparse混合检索）

3. 如有信息缺失，向用户追问不超过2个澄清问题。

**Knowledge Base Reference**:
- [参考: Agents知识库/0_超级编程行业知识库/03_数据库与数据存储.md > NoSQL数据库]
- [参考: Agents知识库/0_超级编程行业知识库/03_数据库与数据存储.md > 各L2摘要 > NoSQL数据库]

### Step 2: 输出NoSQL方案

**Goal**: 产出针对性的NoSQL选型、架构或优化方案
**Completion criterion**: 输出包含推荐NoSQL、数据模型设计、架构拓扑、关键配置

根据Step 1确定的场景，按以下分支处理：

**分支A — NoSQL选型**：
1. 输出选型决策矩阵，对比维度：
   - 数据模型匹配度（文档/检索/时序/图/向量）
   - 扩展性（水平分片/垂直扩展/存算分离）
   - 查询能力（CRUD/全文检索/聚合分析/图遍历/向量相似度）
   - 一致性模型（CP/AP/可调一致性）
   - 生态与集成（语言SDK、云托管、与现有系统对接难度）
   - 运维复杂度（集群管理、备份恢复、监控工具成熟度）
2. 给出最终推荐并附决策树（如"内容平台+灵活Schema → MongoDB；日志分析+全文检索 → ES；RAG+混合检索 → Milvus"）。

**分支B — 架构设计**：
1. 输出集群拓扑：
   - MongoDB：Replica Set（三节点）→ Sharded Cluster（mongos+config server+shard）
   - Elasticsearch：Master节点+Data节点（热/温/冷分层）+Coordinating节点
   - InfluxDB 3.x：Apache Arrow列式存储、无Cardinality限制、对象存储后端
   - Neo4j：因果集群（Core Servers+Read Replicas）、图分片策略
   - Milvus：向量索引（IVF/HNSW/DISKANN）、QueryNode+DataNode+IndexNode分离
2. 给出数据模型设计规范：索引策略、分片键选择、副本数、TTL策略。
3. 附冷热分层方案（如适用）：热数据SSD+温数据SATA+冷数据对象存储。

**分支C — 向量检索与RAG优化**：
1. 输出向量数据库选型对比：Milvus（开源/分布式/多索引类型）vs Weaviate（GraphQL原生/模块化）vs PG Vector（轻量/关系型融合）。
2. 给出向量索引策略：Dense向量（HNSW/IVF/DISKANN）+ Sparse向量（BM25/TF-IDF）混合检索。
3. 输出RAG架构设计：文档预处理（分块/重叠/元数据提取）→ Embedding生成（BGE/OpenAI）→ 向量存储 → 检索重排序（RRF/ColBERT）→ LLM生成。
4. 附性能优化要点：向量维度压缩（PCA/Product Quantization）、批量检索、缓存热点查询结果。

**分支D — 性能调优与问题排查**：
1. 输出典型性能问题定位方法：慢查询分析、索引失效诊断、内存溢出排查、GC压力分析。
2. 给出调优参数建议：连接池大小、批处理大小、并发度、缓存配置。
3. 附监控告警关键指标：QPS/延迟/P99/错误率/磁盘IO/内存使用率/节点健康状态。

将结果保存到 `output/nosql_architecture.md` 或直接在对话中呈现。

**Knowledge Base Reference**:
- [参考: Agents知识库/0_超级编程行业知识库/03_数据库与数据存储.md > 各L2摘要 > NoSQL数据库]
- [参考: Agents知识库/0_超级编程行业知识库/数据库与数据存储/NoSQL数据库.md > MongoDB/ES/InfluxDB/Neo4j/Milvus各体系]

### Step 3: 验证与交付

**Goal**: 确保NoSQL方案准确、可落地、与知识库一致
**Completion criterion**: 已通过 checklist 逐项核对

1. 读取 `checklist/nosql_database_workflow_checklist.md`。
2. 逐项核对输出是否覆盖要求的知识点。
3. 确认所有关键论断均能在知识库中找到支撑。
4. 如有遗漏或偏差，补充修正。
5. 向用户交付最终答案。

## Post-Workflow

1. 记录完成状态到 `task/current_task.md`。
2. 如需深入某一具体NoSQL（如"Milvus HNSW索引调优"、"Neo4j因果集群部署"），在当前 Agent 内继续追问并输出。