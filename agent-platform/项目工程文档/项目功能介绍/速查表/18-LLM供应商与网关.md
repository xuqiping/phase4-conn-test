# 18 - LLM 供应商与网关

## 功能简介
平台级模型 Provider 配置（**category 四分**：CHAT 对话 / EMBEDDING 向量 / VIDEO 视频 / IMAGE 生图预留），**endpoint 存完整请求 URL、运行时零拼接直发**（FR-001，V60 起），Key AES 加密存储，按类型分流的连接测试，网关按类型路由，热重载。

> 改造依据：[模型供应商全URL与类型化改造.plan.md](../../../workflow_output/docs/plans/模型供应商全URL与类型化改造.plan.md)（FR-001~006，B1-B4 全落 2026-08-06）。

## category 四分与消费方路由（FR-002/003）
| category | 消费方 | 测试分流（FR-004） |
|---|---|---|
| CHAT | chat 模型列表（`/llm/user/models/available` 只放 CHAT）、Agent/工作流/画布文本+脚本节点、记忆 judge | `POST /{id}/test` chat 短对话 |
| EMBEDDING | 知识库 RAG 索引/检索、记忆 embed（`LlmGateway.embed` 只找 EMBEDDING 行，注册进独立 embed 注册表） | `POST /{id}/test-embed` 取维度 |
| VIDEO | 视频生成目录（`GET /api/media/models`，MediaModelService 只认 VIDEO） | `POST /api/media/providers/{id}/test` 零成本探测 |
| IMAGE | 预留（画布 R-3 生图），不进任何目录 | 前端直接提示「生图 provider 尚未接入」不发请求 |

- 废弃 `CHAT_EMBEDDING`/`MEDIA`（「V60」迁移：CHAT_EMBEDDING→CHAT、MEDIA→VIDEO，endpoint best-effort 补全，备份表 `llm_providers_bak_v60`）。**注意**：Flyway 文件名实为 `V63__provider_full_url_category.sql`（脚本内注释自称 V60，备份表名亦沿用 v60 口径）。
- **CHAT_EMBEDDING 迁移坑**：原双用行变 CHAT 后 embed 落空 → 若承担 embed 需人工补建一条 EMBEDDING 行（迁移 WARN 逐条列 name）。
- **ANTHROPIC + EMBEDDING 不支持**（Claude 无 embed 接口）：前端禁选，后端测试返明确话术。

## 完整 URL 直发（FR-001）
- `api_endpoint` = 完整请求 URL（如 `https://api.openai.com/v1/chat/completions`、`.../v1/embeddings`、`.../v1/messages`、`.../v1/contents/generations/tasks`），运行时**原样作为请求地址**，代码不补任何路径。
- **唯一例外**：Ark 视频查任务/探测的 `/{taskId}` 是协议级资源路径（一次性任务定位符），保留拼接。
- 前端 placeholder 按 category 给完整 URL 示例；CHAT/EMBEDDING 保存后若以 `/v1`、`/api/v3` 等 base 形态结尾 → 软警告不拦截。
- **迁移后人工动作**：逐条 provider 点「测试」验证 V60 补全猜对。

## 后端 (backend) — `llm` 包
- 控制器：[LlmController.java](../../backend/src/main/java/com/superprogrammer/llm/controller/LlmController.java)
  - `GET/POST /api/llm/providers` `PUT/DELETE /{id}` `POST /{id}/test`(chat) `POST /{id}/test-embed` `POST /providers/reload`
  - VIDEO 类 provider 的「测试」不走 chat：[MediaGenController.java](../../backend/src/main/java/com/superprogrammer/media/controller/MediaGenController.java) `POST /api/media/providers/{id}/test` → ArkSeedanceProvider.testConnection 零成本探测（GET 任务端点/不存在id，401/403=Key 无效，2xx/400/404=鉴权通过即成功，不建任务不计费）
- 网关：[LlmGateway.java](../../backend/src/main/java/com/superprogrammer/llm/LlmGateway.java)（按 model 路由 provider：chat 只在 CHAT 行找（用户级 override 优先）；embed 只在 EMBEDDING 行找；报错话术区分「对话/向量 Provider」）
- 注册表：[LlmConfig.java](../../backend/src/main/java/com/superprogrammer/llm/config/LlmConfig.java) chat/embed 双注册表；VIDEO/IMAGE 不注册（「仅 embed」/跳过日志按 category 话术）
- Provider 实现：`llm/provider/` OpenAICompatibleProvider、ClaudeProvider（均全 URL 直发）；视频走 media 包 ArkSeedanceProvider
- 服务：LlmProviderService（CATEGORIES=CHAT/VIDEO/IMAGE/EMBEDDING，非法回退 CHAT；IMAGE 测试短路话术）、[AesEncryptService.java](../../backend/src/main/java/com/superprogrammer/llm/service/AesEncryptService.java)（Key 加密）
- 实体：`llm/entity/` LlmProviderEntity；Mapper：LlmProviderMapper、EmbeddingModelVersionMapper
- DTO：LlmProviderVO、LlmProviderCreateRequest、AvailableModelVO、TestConnectionResult、LlmRequest/Response、LlmMessage、TokenUsage

## 前端 (frontend)
- 组件：[settings/ProviderManageTab.vue](../../frontend/src/components/settings/ProviderManageTab.vue)（类型四分选项/徽标、协议仅 CHAT/EMBEDDING 显示、placeholder 按类型、测试四分）
- API：[llm.ts](../../frontend/src/api/llm.ts)（ProviderCategory='CHAT'|'EMBEDDING'|'VIDEO'|'IMAGE'）
- 入口：[SettingsView.vue](../../frontend/src/views/SettingsView.vue)

## Sidecar
无（LLM 调用经后端网关；Sidecar 节点回调后端执行 LLM）。

## ⚠️ 已知缺陷：WebClient 无超时 —— ✅ 已修复（[RB-001 根因](../项目漏洞核查/运行时bug.md)）
[OpenAICompatibleProvider.java:29-47](../../backend/src/main/java/com/superprogrammer/llm/provider/OpenAICompatibleProvider.java#L29) 与 [ClaudeProvider.java:28-47](../../backend/src/main/java/com/superprogrammer/llm/provider/ClaudeProvider.java#L28) 均已配齐：底层 HttpClient `CONNECT_TIMEOUT_MILLIS=10s` + `responseTimeout=30s`，chat/embed 用 `.block(RESPONSE_TIMEOUT)` 限时；chatStream 走 `Flux` 无 `.block()`。（ArkSeedanceProvider 本就有超时。）

> 修复前后果（历史）：provider 慢 / 连接 stall / 网络抖动时 `.block()` 无限阻塞，线程永久挂死 → RB-001 死亡螺旋。P1（request.ts 连续网络错误计数跳出）亦已落地；剩余待办为上云验证 + LlmGateway 有界重试/熔断。

**记忆/judge 模型**：[RagConfig](../../backend/src/main/java/com/superprogrammer/knowledge/service/RagConfig.java) `MEMORY_JUDGE_MODEL=doubao-seed-2.0-code`（extract/judge/route）、`MEMORY_EMBED_MODEL=doubao-embedding-vision`。云端 `llm_providers` 表**必须有支持这两个模型的 CHAT/EMBEDDING provider**，否则 `findProvider` 抛异常 → 记忆静默不落库。

**待修（P1 残留）**：LlmGateway 失败有界重试 + 熔断（`LlmGateway.java` 目前无 retry/CircuitBreaker）。P0 超时修复已落地，详见 [运行时bug.md](../项目漏洞核查/运行时bug.md) RB-001。

## 数据表
`llm_providers`（V60 起 category 四分 + endpoint 完整 URL；备份表 `llm_providers_bak_v60`）、`embedding_model_versions`
