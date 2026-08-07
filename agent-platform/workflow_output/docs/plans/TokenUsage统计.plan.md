---
description: "Token 消耗统计（账单） 的实现计划"
created-date: 2026-07-22
---

# ⚠️ 已废弃（SUPERSEDED）· Token 消耗统计（账单）Plan

> **本计划已被取代**：scope 扩为「预付钱包计费系统」，见 [积分计费系统.plan.md](积分计费系统.plan.md)（2026-08-07）。
> 本文件从未执行（步骤全未勾选），provider 透传/流式 side-channel 等设计已被新 plan 继承复用。保留作历史参考，勿据此开发。

---

# Implementation Plan for Token 消耗统计（账单）

> Phase 2 产出。Phase 3 逐步勾选执行。只含伪代码，不含真代码。
> 来源规格：[../specs/TokenUsage统计.md](../specs/TokenUsage统计.md)。
> 文档规模：≤5000 tokens。

## ⚠️ 对原有功能影响分析（用户要求零回归）

| 点 | 影响原功能? | 规避 |
|---|---|---|
| `findProvider` 改返包装 record | 否 | private 方法，仅 gateway 内 6 处调，contained |
| `LlmProviderInterface` 加方法 | 低 | 新方法用 **default**（embedWithUsage / getId 默认实现），两个 impl 覆写，老调用方零改 |
| provider `embed` 旧签名 | 否 | 保留旧 `embed()->float[]` 委托新方法；13 调用方不动 |
| **chatStream Flux 序列** | **风险点** | usage 采集走 **side-channel（外层 AtomicReference 副作用）**，**绝不改发出的 StreamEvent 流**——否则 13 调用方（对话/RAG SSE）回归 |
| LlmConfig 加 name→id 映射 | 否 | 增量字段，init 时填充，不破坏现有构造 |
| 新表 llm_usage_logs | 否 | 独立 append-only，无业务依赖 |
| 写库异步 | 否 | 独立池 + AbortPolicy，满则丢记 WARN，不抛回调用线程（同 RB-001 韧性） |

**结论**：唯一高风险 = 流式 usage 采集不能扰动 Flux。plan Step C 着重处理 + 测试必验。

## 技术实现坑点预判与规避措施

| 坑 | 规避 | 验证 |
|---|---|---|
| 流式 usage chunk 被 `.filter(非空 content)` 丢弃（OpenAI L95 / Claude L97） | side-channel：chatStream 入口建 `AtomicReference<TokenUsage>`，parseStreamChunk/parseClaudeChunk 解析 usage 时写 ref；`doOnComplete` 时把 ref 丢给采集器。**不改 filter，不改发出事件** | 流式对话后断言账单有行 + SSE 输出与改造前字节一致 |
| usage 在最后一个 chunk（choices 空）才到 | side-channel 在 complete/最后 chunk 都写，取非空值 | mock 末 chunk 带 usage 断言采到 |
| 全局 provider 无 DB id | LlmConfig init 时建 `Map<String,Long> nameToId`（来自 entity.getId） | 账单全局行 provider_id 非 null |
| 用户私有 provider id | findProvider user override 分支已知 `up.getId()` + scope=USER | 账单能区分 GLOBAL/USER |
| embed usage 全丢（OpenAI 丢字段 / Claude 不支持） | provider 加 `embedWithUsage`：OpenAI 解析 `/usage`；Claude 抛则 catch→TokenEstimator 估 input，output=0，status=ESTIMATED | embed 调用后账单有行 |
| 写库高频（一轮对话 5-10 行） | 攒批：`UsageWriter` 内存队列，每 50 行或 200ms flush 一次 batch INSERT | 压测一轮对话 DB 写次数 ≤ batch 阈值 |
| 池满丢任务静默 | AbortPolicy catch + WARN 计数（可观测） | 注入满队列断言不抛回主线程 |
| 聚合查询慢（百万行） | 索引 (user_id,created_at)/(provider_scope,provider_id,created_at)/(model,created_at) | EXPLAIN 走索引 |

## 安全检查清单

- [ ] **鉴权**：admin 端点 `@RequirePermission("usage:view")` 或 admin 角色；用户端点 ownership 硬过滤 `WHERE user_id=current`。
- [ ] **输入校验**：日期范围参数校验（防越界/超大区间拖垮查询）；provider_scope ∈ {GLOBAL,USER}。
- [ ] **数据最小化**：usage 日志**不存 prompt/回答原文**，仅 token 计数 + provider/model 元数据，无 PII 泄漏面。
- [ ] **错误处理**：采集失败固定话术，不透传 e.getMessage()。
- [ ] **依赖安全**：无新依赖。

## 性能考虑与验证计划

- [ ] 采集纯异步，主链路零延迟增加（采集不计入用户响应）。
- [ ] 攒批写降压（50行/200ms flush）。
- [ ] 聚合走索引；百万行后考虑月分区（后续）。
- [ ] **性能验证（Phase4 重点）**：见下「测试着重」。

## 功能联动点清单

> 本功能偏静（写日志/查聚合），联动少。

- [ ] **provider 新增/编辑 ↔ LlmConfig name→id 映射刷新**：现有 provider CRUD 后 LlmConfig 有 reload 机制（initProviders @PostConstruct + 疑似 admin 改后重建）→ name→id 映射随同刷新。边界：新加 provider 立即出账单不缺 id。
- [ ] **流式断连 ↔ usage 丢失**：客户端中途断开 → Flux cancel 不触发 doOnComplete → 该轮 usage 可能丢。边界：可接受（断连本就异常），记 WARN。
- [ ] **用户删 provider ↔ 历史 usage**：不级联删（append-only 保留历史账单），provider_id 指向已删行可接受（账单查历史）。边界：JOIN provider 名时 LEFT JOIN 容 null。

## 运维考量清单

| 项 | 做/不做/后续 | 说明 |
|---|---|---|
| 可观测性 | 做 | 采集日志含 userId/provider/model/tokens；失败 WARN 计数 |
| 配置开关 | 做 | `usage.collect-enabled`（默认 true），出问题关掉即停采集零回归 |
| 可回滚 | 做 | V48 建表附回滚 drop；列无外部依赖 |
| 限流/熔断/降级 | 做 | 池满丢任务记 WARN（同记忆池韧性） |
| 运维入口 | 后续 | 数据量大后加「按月归档/清理」端点（MVP 不做） |
| 告警阈值 | 后续 | 失败率/单用户日耗异常（MVP 不做） |
| 容量/性能预案 | 后续 | 月分区（百万行后） |

## 实现步骤

### Chunk A：数据模型（零依赖）

- [ ] **Step 1：Flyway V48 建表**
  - **目标**：`llm_usage_logs` append-only 表。
  - **动作**：建表（id IDENTITY / created_at / user_id nullable / provider_id / provider_scope GLOBAL|USER / model / tokens_input / tokens_output / cost nullable / status / error_msg）+ 3 索引（见规格 db_schema）。附回滚 drop。
  - **文件**（1）：`backend/src/main/resources/db/migration/V48__llm_usage_logs.sql`
  - **依赖**：无。**migration 编号**：回答记忆 plan 占 V47，本特性 V48；若回答记忆未落则重排，回填本备注。
  - **验证**：迁移成功；插测试行；索引 EXPLAIN。

- [ ] **Step 2：实体 + Mapper**
  - **目标**：ORM 映射 + 攒批写 SQL。
  - **动作**：`LlmUsageLogEntity`（@TableName llm_usage_logs）；`LlmUsageLogMapper`：`batchInsert(List)` 攒批 + 聚合查询（`sumByUser(range)`/`sumByProvider(range)`/`sumByModel(range)`/`dailyTotal(range)` GROUP BY）。
  - **文件**（2）：`backend/.../usage/entity/LlmUsageLogEntity.java`、`backend/.../usage/mapper/LlmUsageLogMapper.java`(+xml)
  - **依赖**：Step 1。
  - **验证**：mvn compile；单测 batchInsert + 聚合。

### Chunk B：provider id/scope 透传

- [ ] **Step 3：LlmConfig name→id 映射 + 接口加 getId**
  - **目标**：全局 provider 能拿到 DB id。
  - **动作**：`LlmProviderInterface` 加 `default Long getId(){return null;}`；`OpenAICompatibleProvider`/`ClaudeProvider` 构造加 `long id` 字段 + getId；`LlmConfig.createProvider` 透传 `entity.getId()`；`LlmConfig` 维护 `Map<String,Long> globalNameToId`（initProviders 填充，reload 同步）。
  - **文件**（4）：`LlmProviderInterface.java`、`OpenAICompatibleProvider.java`、`ClaudeProvider.java`、`LlmConfig.java`
  - **依赖**：无。
  - **安全**：default 方法不破老调用方。
  - **验证**：mvn compile；既有 provider 单测零回归。

- [ ] **Step 4：findProvider 改返包装**
  - **目标**：gateway 出口能拿 provider_id+scope+name。
  - **动作**：新增 record `ResolvedProvider(LlmProviderInterface provider, Long providerId, String scope/*GLOBAL|USER*/, String name)`；`findProvider` 改返它：user override 分支填 `(up.getId(), "USER", name)`；全局分支填 `(globalNameToId.get(name), "GLOBAL", name)`；endpoint 继承全局分支填 GLOBAL+全局 id。
  - **文件**（1）：`backend/.../llm/LlmGateway.java`
  - **依赖**：Step 3。
  - **验证**：gateway 内 6 方法改用 resolved.provider()；compile；既有调用零回归。

### Chunk C：流式 usage 采集（风险点）

- [ ] **Step 5：OpenAI 流式 usage**
  - **目标**：流式末 chunk 带 usage，side-channel 捕获。
  - **动作**：`buildRequestBody`（L142）stream=true 时加 `stream_options:{include_usage:true}`；`parseStreamChunk`（L217）遇 `/usage` 非空（choices 空的末 chunk）解析 prompt/completion_tokens；**side-channel**：`chatStream`（L78）入口建 `AtomicReference<TokenUsage> usageRef`，parseStreamChunk 解析到 usage 写 ref，`flux.doOnComplete(()-> collector.collect(resolved, userId, usageRef.get()))`；**不改 filter(L95)，不改发出的 StreamEvent**。
  - **文件**（1）：`OpenAICompatibleProvider.java`
  - **依赖**：Step 4（resolved）。
  - **验证**：流式对话→账单有行；SSE 输出与改造前字节级一致（回归重点）。

- [ ] **Step 6：Claude 流式 usage**
  - **目标**：message_delta 事件带 usage。
  - **动作**：`parseClaudeChunk`（L213）switch 加 `else if("message_delta".equals(type))` 解析 `/usage/input_tokens`+`/output_tokens`；同 Step5 side-channel（usageRef + doOnComplete）。
  - **文件**（1）：`ClaudeProvider.java`
  - **依赖**：Step 4。
  - **验证**：同 Step5。

### Chunk D：embed usage 采集

- [ ] **Step 7：provider embedWithUsage + 估算兜底**
  - **目标**：embed 也采 token。
  - **动作**：接口加 `default EmbedResult embedWithUsage(text,model){ return new EmbedResult(embed(text,model), null);}`（EmbedResult record=float[] vector + TokenUsage usage）；OpenAICompatibleProvider 覆写：解析 embed 响应 `/usage`（现 L120-134 丢弃，改为读出）；ClaudeProvider 不覆写（抛 UnsupportedOperation 由 gateway catch）。gateway `embed` 内部改调 `embedWithUsage`：usage 非空用真值，null/异常→`TokenEstimator.estimate(text)` 估 input、output=0、status=ESTIMATED；返回值仍 float[]（调用方零改）。
  - **文件**（4）：`LlmProviderInterface.java`、`OpenAICompatibleProvider.java`、`LlmGateway.java`、`backend/.../llm/dto/EmbedResult.java`
  - **依赖**：Step 4。
  - **验证**：embed 调用后账单有行；既有 embed 返回值不变（回归）。

### Chunk E：gateway 采集 + 异步写

- [ ] **Step 8：UsageCollector + UsageWriter + 线程池**
  - **目标**：统一采集、攒批异步入库。
  - **动作**：`usageTaskExecutor` Bean（复制 MemoryTaskExecutorConfig：core2/max4/queue200/prefix=usage-task/AbortPolicy）；`UsageCollector.collect(resolved, userId, usage)` 异步提交：构 Entity（provider_id/scope/model/user_id/tokens/status）→ 投 `UsageWriter`；`UsageWriter` 内存队列 + 定时（200ms）/满 50 flush batchInsert；开关 `usage.collect-enabled=false` 时全短路；池满 catch RejectedExecutionException 记 WARN 不抛。
  - **文件**（4）：`backend/.../usage/config/UsageTaskExecutorConfig.java`、`backend/.../usage/service/UsageCollector.java`、`backend/.../usage/service/UsageWriter.java`、`SystemSettingService`（加 collect-enabled 读，或 @Value）
  - **依赖**：Step 2/4。
  - **验证**：单测 pool-full 不抛；攒批 flush 计数；开关关闭零写入。

- [ ] **Step 9：gateway 6 出口接 collector**
  - **目标**：所有 LLM 调用采 token。
  - **动作**：`chat(req,userId)` 同步：resolved=findProvider → resp=provider.chat → `collector.collect(resolved, userId, resp.usage())`（usage 空则 status=FAILED 或跳过）；`chatStream`：见 Step5/6 side-channel doOnComplete 采；`embed`：见 Step7。chat 无 userId 重载→userId=null。
  - **文件**（1）：`LlmGateway.java`
  - **依赖**：Step 5/6/7/8。
  - **验证**：一轮对话（chat+记忆embed×N+judge）→ 账单对应行数齐全（13 调用方逐一冒烟）。

### Chunk F：查询 API

- [ ] **Step 10：admin + user 查询端点**
  - **目标**：账单查询。
  - **动作**：`UsageController`：admin `GET /api/usage/admin/overview?from&to`（总量+趋势+按用户/模型排行）、`GET /api/usage/admin/by-user`、`/by-model`、`/by-provider-scope`；user `GET /api/usage/me?from&to`（ownership 强制 current userId）。VO：OverviewVO(totalIn/totalOut/topUsers/topModels/daily[])。admin 权限校验。
  - **文件**（3）：`backend/.../usage/controller/UsageController.java`、`backend/.../usage/service/UsageQueryService.java`、`backend/.../usage/dto/*VO.java`
  - **依赖**：Step 2。
  - **验证**：单测 admin 全量 + user ownership 过滤（查他人返空）。

### Chunk G：前端

- [ ] **Step 11：admin 账单页 + 用户用量页**
  - **目标**：可视化。
  - **动作**：`TokenUsageView.vue`（admin，路由+菜单）：总量卡 + 趋势折线（daily）+ 用户/模型排行表 + 日期范围 picker + 全局/私有 scope 切换；用户中心加「我的用量」卡片或独立页（普通用户，调 `/usage/me`）。
  - **文件**（3）：`frontend/src/views/admin/TokenUsageView.vue`、`frontend/src/api/usage.ts`、`frontend/src/router/index.ts`（路由+权限守卫）
  - **依赖**：Step 10。
  - **验证**：vue-tsc；playwright 冒烟（发对话→账单数字涨；admin 看全部；普通用户只看自己）。

## ⚠️ 测试阶段着重（用户要求重点验）

1. **写库性能/异步韧性（难点4）**：高并发下采集不阻塞主链路（对话延迟对比改造前后）；池满不抛；攒批 flush 延迟可控；丢任务可观测（WARN）。
2. **流式 usage 准确性（难点1）**：OpenAI/Claude 真实流式末 chunk 是否真带 usage；断连丢包场景；**SSE 输出字节级回归**（不能因采集改 Flux）。
3. **13 调用方不漏采（难点5）**：逐一冒烟——对话 chat/Agent 路由/工作流 LLM_CALL/RAG问答/RAG索引 embed/记忆 embed×N/MemoryJudge/查询扩展/文档解析——每条都应在账单出现。

## 整体验证

- [ ] mvn compile + 既有全部单测零回归（含 provider/gateway/memory/rag）
- [ ] 新增单测：采集（同步/流式/embed 三路）、池满韧性、攒批、ownership 过滤、聚合查询
- [ ] vue-tsc + 既有前端测试绿
- [ ] playwright：发对话→账单涨；admin 全量；用户只看自己；日期筛选；scope 切换
- [ ] SSE 输出字节级回归对比（流式改造重点）
- [ ] 与规格 T1-T7 对齐复核

## 术语表

| 术语 | 大白话 | 案例 |
|---|---|---|
| side-channel | 不走主返回值，用外层变量副作用传值 | usage 写 AtomicReference，不改 Flux 发的事件 |
| 攒批 | 攒够 N 条再一次写 | 50 行或 200ms flush |
| provider_scope | 这条用量来自全局还是用户私有 key | GLOBAL=平台 key / USER=自带 key |
| ownership 过滤 | 查询强制只查自己 | `/usage/me` 自动 WHERE user_id=自己 |
| AbortPolicy | 线程池满了直接拒任务 | 丢采集记 WARN，不阻塞对话 |

## 备注

- migration V48 与「回答记忆 origin」V47 顺序：谁先落谁占 V47，后落重排。
- 流式 usage side-channel 是全 plan 最高风险点，Step 5/6 必配 SSE 字节回归测试。
- T8（价表）/T9（成本回算）P1，本 plan 不含，价表落地后单开 plan。
