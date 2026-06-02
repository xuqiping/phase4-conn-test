# AGENTS.md — Task Routing Table

## Agent: 超级程序员_后端架构与中间件Agent

本文件是任务路由的唯一真相来源。当用户向本子Agent提出请求时，读取此表以确定加载哪个细粒度工作流。

## Routing Table

| 任务关键词 / 意图 | 工作流文件 | 描述 |
|------------------|-----------|------|
| 微服务,分布式,服务拆分,DDD,Bounded Context,康威定律,Event Storming,注册中心,配置中心,网关,Gateway,BFF,分布式事务,SAGA,TCC,2PC,Seata,PACELC,限流,熔断,降级,令牌桶,Sentinel,多租户,SaaS,渐进式拆分 | workflow/microservice_architecture_workflow.md | 微服务与分布式架构：服务拆分策略、基础设施四件套选型、稳定性铁三角、多租户架构 |
| 消息队列,MQ,RabbitMQ,RocketMQ,Kafka,Pulsar,AMQP,事务消息,KRaft,CommitLog,Quorum Queue,消息选型,消息架构,消息迁移,幂等,可靠投递,Consumer Lag | workflow/message_queue_workflow.md | 消息队列中间件：选型（9维评估）、架构设计、工程实践（幂等/可靠投递/大消息）、迁移方案 |
| 缓存,Redis,Memcached,Caffeine,缓存选型,缓存架构,多级缓存,本地缓存,分布式缓存,缓存穿透,缓存击穿,缓存雪崩,布隆过滤器,互斥锁,冷热分离,一致性哈希,Redis Cluster,数据分片 | workflow/cache_middleware_workflow.md | 缓存中间件：选型、多级缓存架构、三大经典问题防护、冷热分离与性能优化 |
| 观测,监控,告警,链路追踪,日志,APM,OpenTelemetry,Prometheus,Grafana,RED,USE,SLO,Error Budget,eBPF,Continuous Profiling,火焰图,TraceID,结构化日志,可观测性 | workflow/service_governance_workflow.md | 服务治理与观测：可观测性三支柱体系、监控告警优化、链路追踪、APM性能观测 |

## Notes

- 本子Agent处理所有与后端架构、中间件、服务治理相关的请求。
- 关键词覆盖范围要足够广以捕获同义/转述请求。
- 新增能力时在此表添加新行，并创建对应 workflow + checklist 文件对。

## Cross-Module Dependencies

- `microservice_architecture_workflow.md` Step 2 中的网关限流可能引用 [参考: Agents知识库/0_超级编程行业知识库/02_网络架构与中间件.md > 消息队列中间件 > 选型决策核心]（流量削峰场景）
- `cache_middleware_workflow.md` Step 2 中的分布式锁可能引用 [参考: Agents知识库/0_超级编程行业知识库/02_网络架构与中间件.md > 微服务与分布式架构 > 稳定性铁三角]（限流计数场景）
- `service_governance_workflow.md` Step 2 中的日志存储可能引用 [参考: Agents知识库/0_超级编程行业知识库/03_数据库与数据存储.md > 时序数据库]（Metrics/Logs存储选型）
