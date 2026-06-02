# Cache Middleware Workflow

## Purpose

基于缓存中间件知识体系，为用户提供缓存选型（Redis/Memcached/Caffeine）、多级缓存架构设计、缓存问题防护（穿透/击穿/雪崩）、数据分片与冷热分离策略等技术方案支持。覆盖Redis 8种数据结构、Cluster分片、持久化、本地缓存W-TinyLFU等核心技术。

## Prerequisites

- 用户已明确缓存场景或问题
- 知识库文件 `02_网络架构与中间件.md` 及子目录文件可访问

## Steps

### Step 1: 识别缓存需求场景

**Goal**: 明确用户的缓存需求类型、数据特征和性能目标
**Completion criterion**: 已确定场景标签、数据类型、访问模式和缓存层级需求

1. 读取用户消息，提取以下信息：
   - 场景类型：缓存选型 / 架构设计 / 问题排查（穿透/击穿/雪崩） / 性能优化 / 冷热分离
   - 数据类型：简单KV / 复杂对象 / 集合/列表 / 分布式锁 / 限流计数 / 向量搜索
   - 访问模式：读多写少 / 读写均衡 / 写多读少 / 热点集中（如秒杀）
   - 延迟要求：纳秒级（本地缓存） / 微秒级（同机房Redis） / 毫秒级（跨可用区）
   - 缓存层级需求：是否需要多级缓存（L0边缘→L1本地→L2分布式→L3数据库）
   - 特殊约束：数据量（是否>100GB需分片）、持久化需求、内存预算

2. 对照知识库中的产品矩阵初步判断候选缓存：
   - 全栈需求（缓存+锁+限流+队列+向量搜索）→ Redis（瑞士军刀）
   - 极致简单纯KV、低延迟 → Memcached（Slab分配、纯内存）
   - Java应用、本地缓存、纳秒级延迟 → Caffeine（W-TinyLFU）
   - 超大规模（>100GB）+ 冷热分离需求 → Redis Cluster + 冷热分层

3. 如有信息缺失，向用户追问不超过2个澄清问题。

**Knowledge Base Reference**:
- [参考: Agents知识库/0_超级编程行业知识库/02_网络架构与中间件.md > 缓存中间件 > 产品矩阵]

### Step 2: 输出缓存方案

**Goal**: 产出针对性的缓存选型、架构或问题防护方案
**Completion criterion**: 输出包含推荐缓存产品、架构拓扑、防护策略、配置参数

根据Step 1确定的场景，按以下分支处理：

**分支A — 缓存选型**：
1. 输出选型对比表（Redis vs Memcached vs Caffeine vs 其他），对比维度：
   - 数据结构丰富度（Redis 8种 vs Memcached纯KV vs Caffeine本地KV）
   - 延迟级别（Caffeine纳秒 / Memcached微秒 / Redis微秒-毫秒）
   - 容量上限（本地缓存受JVM内存限制 / Redis受单机内存或集群规模限制）
   - 高可用（Redis主从+Cluster / Memcached无原生高可用 / Caffeine无）
   - 持久化（Redis RDB+AOF / Memcached无 / Caffeine无）
   - 特殊能力（Redis: 分布式锁/限流/向量搜索；Memcached: 多线程吞吐高；Caffeine: W-TinyLFU淘汰策略）
2. 给出最终推荐并附决策理由。

**分支B — 多级缓存架构设计**：
1. 输出多级缓存层级设计：L0边缘缓存（CDN/边缘节点）→ L1本地缓存（Caffeine/Guava，进程内）→ L2分布式缓存（Redis Cluster/Memcached）→ L3数据库。
2. 给出数据一致性策略：Cache-Aside（推荐）/ Write-Through / Write-Behind，附更新时序图。
3. 输出数据分片策略：一致性哈希（Jump Consistent Hash零内存）vs Redis Cluster 16384 Slot，附分片键设计原则。
4. 给出租户隔离策略（如SaaS场景）：Key前缀命名规范、Namespace隔离。

**分支C — 三大经典问题防护**：
1. **缓存穿透**：输出防护方案——布隆过滤器（预加载热点Key）+ 空值缓存（设置短TTL）+ 参数校验（拦截非法请求）。
2. **缓存击穿**：输出防护方案——互斥锁（SETNX/RedLock）+ 逻辑过期（异步重建）+ 热点预加载（定时任务预热）。
3. **缓存雪崩**：输出防护方案——TTL随机偏移（基础TTL ± 随机值）+ 多级缓存降级（L1本地兜底）+ 熔断降级（直接查DB，DB必须能独立承载全部流量）。
4. 给出降级方案设计原则：缓存必须有降级方案，数据库必须能独立承载全部流量。

**分支D — 冷热分离与性能优化**：
1. 输出冷热分离策略：30天未访问数据迁移至SSD/对象存储，成本降60-70%。
2. 给出Redis性能优化要点：避免大Key（String > 10KB、Hash/Set/ZSet成员数 > 5000）、使用Pipeline批量操作、合理设置内存淘汰策略（allkeys-lru/volatile-lru）。
3. 输出Redis 8.0多线程优化和Valkey开源分叉的选型建议。

将结果保存到 `output/cache_architecture.md` 或直接在对话中呈现。

**Knowledge Base Reference**:
- [参考: Agents知识库/0_超级编程行业知识库/02_网络架构与中间件.md > 缓存中间件 > 架构设计核心]
- [参考: Agents知识库/0_超级编程行业知识库/02_网络架构与中间件.md > 缓存中间件 > 三大经典防护]

### Step 3: 验证与交付

**Goal**: 确保缓存方案在生产环境可行、与知识库一致
**Completion criterion**: 已通过 checklist 逐项核对

1. 读取 `checklist/cache_middleware_workflow_checklist.md`。
2. 逐项核对输出是否覆盖要求的知识点。
3. 确认所有关键论断均能在知识库中找到支撑。
4. 如有遗漏或偏差，补充修正。
5. 向用户交付最终答案。

## Post-Workflow

1. 记录完成状态到 `task/current_task.md`。
2. 如需深入某一具体场景（如"Redis Cluster 16384 Slot分片实战"、"Caffeine W-TinyLFU参数调优"），在当前 Agent 内继续追问并输出。
