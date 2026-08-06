# 规格规格 · Token 消耗统计（账单）

> SDD 特性级规格（Phase 1 产出）。实现须与本文件对齐；冲突时改实现或改本文档（注明原因）。
> 来源：本会话 Phase0 分析 + 用户决策（2026-07-22）。主 PRD 见 [PRD.md](PRD.md)（F14 审计日志与统计 的延伸）。
> ≤5000 tokens。

## 1. 项目概述
- **定位**：统计全平台所有用户调用大模型消耗的 token 数，按 user / provider / model 维度出账单视图。
- **背景与动机**：现状 LLM token 只在 2 处 `log.info` 打印（[DefaultChatStrategy:39](../../../backend/src/main/java/com/superprogrammer/engine/strategy/DefaultChatStrategy.java#L39)、[LlmCallHandler:63](../../../backend/src/main/java/com/superprogrammer/engine/executor/LlmCallHandler.java#L63)），零持久化，无法回答「谁用了多少 / 成本花在哪 / 是否暴增」。需一份可查询账单。
- **成功指标**：所有经 `LlmGateway` 的 LLM 调用（chat / chatStream / embed）token 数 100% 落库；admin 可看全局聚合，普通用户可看自己；零主链路延迟回归（异步采集不阻塞对话）。

## 2. 用户故事
- 作为**管理员**，我看全局 token 账单总览（总量/趋势/按用户/按模型排行），掌握平台 LLM 成本。
- 作为**管理员**，我按日期范围筛选，定位消耗异常暴增的用户或模型。
- 作为**普通用户**，我看自己用了多少 token（明细），心里有数。
- 作为**管理员**，我后续可编辑「模型→价格」映射，把 token 数折成钱（MVP 不做，留接口）。

## 3. 功能需求
| 编号 | 功能 | 描述 | 优先级 | MVP |
|---|---|---|---|---|
| T1 | usage 落库表 | `llm_usage_logs` append-only 表 + Flyway migration | P0 | 是 |
| T2 | Gateway 采集埋点 | `LlmGateway` 6 方法出口统一异步采 token，userId 经现有 `chat(req, userId)` 重载获取（无 userId 重载记 null） | P0 | 是 |
| T3 | 流式 usage 解析 | OpenAI 兼容加 `stream_options.include_usage`；Claude 解析 `message_delta.usage` | P0 | 是 |
| T4 | embed usage | provider 回吐用真值，不回吐用 `TokenEstimator` 估 input（output 记 0） | P0 | 是 |
| T5 | 异步写库 | fire-and-forget 到独立线程池 + 攒批写，不阻塞主链路 | P0 | 是 |
| T6 | admin 账单页 | 总览（总量/趋势）+ 按用户/模型排行 + 日期筛选 | P0 | 是 |
| T7 | 用户自查看页 | 普通用户看自己的 token 明细（ownership 过滤） | P0 | 是 |
| T8 | 价表配置 | `llm_pricing` 表 + admin 页可编辑模型单价（input/output per 1M tokens）；cost 列 MVP 留 nullable，后做回填 | P1 | 否 |
| T9 | 成本回算 | T8 落地后，按价表把存量 token 数折 cost（异步批跑） | P1 | 否 |

> scene 决策：**砍**。不记 CHAT/RAG/MEMORY 等场景维度（用户明确不要）。仅记 user/provider/model/tokens/ts。

## 4. 非功能需求
- **性能**（详见 performance_goals 要点）：
  - 采集**纯异步**，主链路（对话/RAG/记忆）延迟零增加（采集耗时不计入用户响应）。
  - 线程池满（AbortPolicy）→ 丢任务记 WARN，绝不抛回调用线程（同 `memoryTaskExecutor` 韧性模式）。
  - 单轮对话 5-10 条 LLM 调用 → 每条 1 行 append，攒批（每批 ≤50 / 200ms flush）降压 DB 写。
  - 聚合查询走索引（user_id+created_at、provider_id+created_at）；百万级行后考虑按月分区。
- **安全**：
  - 普通用户只能查自己的 usage（`WHERE user_id = currentUser`，ownership 硬过滤）。
  - admin 全局查询走 `@RequirePermission("usage:view")` 或复用 admin 角色。
  - usage 日志不含敏感原文（不存 prompt/content，仅 token 计数 + provider/model 元数据），无 PEL/PII 泄漏面。
  - 落库失败不泄露内部细节（固定话术 incident，同现有记忆 incident 模式）。
- **可观测性**：采集埋点日志含 userId/providerId/model/tokens；失败率指标（WARN 计数）。
- **可回滚**：migration 加表，回滚 = drop 表（append-only，无业务依赖）。

## 5. 架构
采集链路：
```
调用方(DefaultChatStrategy/AgentRouter/LlmCallHandler/RAG/Memory/...)
        │ LlmRequest(带 userId)
        ▼
   LlmGateway  ◄── 单一总闸口（findProvider 解析 provider+model）
        │
   ├─ 同步 chat / embed → LlmResponse.usage（已有，provider 解析）
   ├─ 流式 chatStream → 新增：末 chunk 收 usage
   │
   ▼ (出口拦截，异步)
   UsageCollector ──fire-and-forget──► UsageWriter(独立池, 攒批)
                                          │ batch INSERT
                                          ▼
                                   llm_usage_logs
                                          │
                          admin/user 聚合查询（GROUP BY）
```
**关键技术决策**：
1. **单一埋点在 Gateway 出口**，13 调用方零改动（未来新调用方自动覆盖）。
2. **流式 usage**：OpenAI `stream_options.include_usage=true`（末 chunk 带 usage）；Claude 解析 `message_delta` event 的 usage。协议级覆盖——用户新增任何 OpenAI 兼容 provider 自动生效，无需单配。
3. **embed usage**：input 用 provider 回吐或本地 `TokenEstimator` 估算（input=嵌入文本，可估）；output=0（向量不算文本 token）。
4. **异步独立池**：不复用 `memoryTaskExecutor`（避免记忆任务和 usage 写互饿），新建 `usageTaskExecutor`，同 AbortPolicy 韧性。
5. **cost 后做**：MVP 只存 token 数，`cost` 列 nullable；T8 价表落地后异步回算。

## 6. 数据模型
```sql
-- llm_usage_logs：append-only token 消耗日志
CREATE TABLE llm_usage_logs (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  user_id       BIGINT,                       -- nullable：系统索引任务无 user
  provider_id   BIGINT NOT NULL,              -- provider 表 id（全局或私有，靠 provider_scope 区分）
  provider_scope VARCHAR(8) NOT NULL DEFAULT 'GLOBAL',  -- GLOBAL=llm_providers / USER=user_llm_providers
  model         VARCHAR(128) NOT NULL,        -- 冗余模型名，账单切分快
  tokens_input  INT  NOT NULL DEFAULT 0,
  tokens_output INT  NOT NULL DEFAULT 0,
  cost          DECIMAL(12,6),                -- nullable：MVP 不算，T8 后回填
  status        VARCHAR(16) NOT NULL DEFAULT 'SUCCESS',  -- SUCCESS/FAILED/ESTIMATED
  error_msg     VARCHAR(256)                  -- 失败原因（截断，不存原文）
);
CREATE INDEX idx_usage_user_time   ON llm_usage_logs(user_id, created_at);
CREATE INDEX idx_usage_provider_tm ON llm_usage_logs(provider_scope, provider_id, created_at);  -- 全局/私有分开算
CREATE INDEX idx_usage_model_time  ON llm_usage_logs(model, created_at);

-- T8(P1)：模型价格表
CREATE TABLE llm_pricing (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  provider_id  BIGINT,
  model        VARCHAR(128) NOT NULL,
  price_input  DECIMAL(12,6) NOT NULL,   -- per 1M tokens
  price_output DECIMAL(12,6) NOT NULL,
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(provider_id, model)
);
```
**字段说明**：
- `user_id` nullable：系统级索引任务（DocumentParserService/IndexJobWorker）无归属用户，记 null。
- `provider_id` + `provider_scope`：全局 provider（`llm_providers`）记 `scope=GLOBAL`；用户私有 provider（`user_llm_providers`）记 `scope=USER`。两张表 id 空间不重叠也不互斥，靠 scope 判别，账单可分开算「平台 key 消耗」vs「各用户自带 key 消耗」。
- `status`：SUCCESS=provider 回吐真值；ESTIMATED=本地估算（embed 兜底）；FAILED=调用失败。
- 软删/审计四字段**不加**：append-only 日志表，不删不改，靠 created_at + 分区归档。

## 7. 测试策略
**单元测试**：
- `LlmGateway` 出口拦截：mock provider 返不同 usage（真值/空/异常）→ 断言异步入库正确。
- 流式 usage 解析：mock OpenAI 末 chunk 带 usage、Claude message_delta 带 usage → 断言采到。
- embed usage：provider 回吐 vs 不回吐 → 真值 vs 估算分支。
- 线程池满：注入 AbortPolicy → 断言不抛回调用线程、记 WARN。

**集成测试**：
- 真 PG：一次对话（含 chat + 记忆 embed + judge）→ `llm_usage_logs` 落对应行数 + token 数合理。
- ownership 过滤：普通用户查自己 OK，查他人返空。

**⚠️ 测试阶段着重（用户要求重点验）**：
1. **写库性能/异步韧性**（难点4）：高并发对话下 usage 写不阻塞主链路；池满不抛；攒批写延迟可控；丢任务可观测（WARN 计数）。
2. **流式 usage 准确性**（难点1）：末 chunk 丢包/早断场景 usage 是否丢；不同 provider 真实流式是否真带 usage。
3. **聚合正确性**：各调用方 token 不漏采（13 调用方逐一冒烟：对话/Agent/工作流 LLM_CALL/RAG问答/RAG索引/记忆embed×N/judge/查询扩展/文档解析）。

**手动/Playwright 冒烟**：
- admin 账单页：总览数字 + 按用户/模型排行 + 日期筛选生效。
- 普通用户：只看自己。
- 发一轮对话 → 账单数字增长。

## 8. 边界与不做
- **不做配额/超限拦截**（纯看账单，非真计费）。后续要再开。
- **不做 agent/workflow/project/session 维度切分**（用户明确不要）。
- **不做场景维度 scene**（用户明确砍）。
- **MVP 不折成本**（cost 留 nullable，T8 价表后做）。
- **不存 prompt/回答原文**（仅 token 计数 + 元数据）。
- **不做实时流式账单**（查询走库聚合，非推送）。

## 9. 变更记录
| 日期 | 变更 | 原因 |
|---|---|---|
| 2026-07-22 | 特性规格建立（Phase0 分析 + 决策审定） | 新增 token 账单功能 |

## 10. 术语表
| 术语 | 大白话 | 案例 |
|---|---|---|
| token | 大模型计费的最小单位（约 ¾ 个英文单词、½ 个汉字） | 「你好」约 1-2 token |
| usage（用量） | 模型返回的本次调用 input/output token 数 | OpenAI 响应里 `usage.total_tokens` |
| append-only | 只追加不改不删的表 | 日志表，查询用、不更新 |
| fire-and-forget | 丢出去就不管，不等结果 | 采集完 token 异步丢给写库线程，不等写完 |
| 流式 usage | 流式响应最后一块才带的 token 统计 | SSE 最后一个 chunk 里有 usage |
| include_usage | OpenAI 流式开关注，开了末 chunk 才回 token 数 | `stream_options:{include_usage:true}` |
| 攒批写 | 攒够 N 条或等 T 毫秒再一次写库 | 50 条或 200ms flush，省 DB 往返 |
| ownership 过滤 | 查询强制带「只能查自己」条件 | 用户查账单自动 `WHERE user_id=自己` |
| 成本换算 | 按「模型→单价」把 token 数折成钱 | glm-4 输入 0.5 元/百万 token |
