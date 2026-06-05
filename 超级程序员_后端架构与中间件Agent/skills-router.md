# skills-router.md — Skill Router

## Top-Level Skill

- **Name**: `super_programmer_backend_architecture_agent_skill`
- **Purpose**: 执行后端架构与中间件领域的具体任务，覆盖微服务/分布式、消息队列、缓存、服务治理与观测四大子域。
- **Skill File Path**: `all_agents/超级程序员_后端架构与中间件Agent/skills-router.md`

## Derivative Skills

| Skill Name | Purpose | Workflow File | Notes |
|------------|---------|---------------|-------|
| `super_programmer_backend_architecture_agent_skill___microservice_architecture` | 微服务与分布式架构执行 | workflow/microservice_architecture_workflow.md | DDD/康威定律/网关/分布式事务/限流熔断/多租户 |
| `super_programmer_backend_architecture_agent_skill___message_queue` | 消息队列执行 | workflow/message_queue_workflow.md | RabbitMQ/RocketMQ/Kafka/Pulsar |
| `super_programmer_backend_architecture_agent_skill___cache_middleware` | 缓存中间件执行 | workflow/cache_middleware_workflow.md | Redis/Memcached/本地缓存/分布式缓存 |
| `super_programmer_backend_architecture_agent_skill___service_governance` | 服务治理与观测执行 | workflow/service_governance_workflow.md | OpenTelemetry/Prometheus/ELK/APM/eBPF |

## Knowledge Base Link

- **Base Path**: `Agents知识库/0_超级编程行业知识库`
- **Main Index**: `Agents知识库/0_超级编程行业知识库/00_总索引.md`
- **Module Main File**: `Agents知识库/0_超级编程行业知识库/02_网络架构与中间件.md`
- **Detail Directory**: `Agents知识库/0_超级编程行业知识库/网络架构与中间件/`

## Evolution Rules

1. 新增能力时先检查是否已有同名衍生技能；如有则更新，如无则新建。
2. 工作流中禁止嵌入知识库全文，一律使用 `[参考: <path>]` 引用。
3. 若知识库原文更新，子Agent下次执行时自动读取最新内容。
