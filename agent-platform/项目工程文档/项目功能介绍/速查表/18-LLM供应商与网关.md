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

- 废弃 `CHAT_EMBEDDING`/`MEDIA`（V60 迁移：CHAT_EMBEDDING→CHAT、MEDIA→VIDEO，endpoint best-effort 补全，备份表 `llm_providers_bak_v60`）。
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
- 注册表：[LlmConfig.java](../../backend/src/main/java/com/superprogrammer/llm/LlmConfig.java) chat/embed 双注册表；VIDEO/IMAGE 不注册（「仅 embed」/跳过日志按 category 话术）
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

## ⚠️ 已知缺陷：WebClient 无超时（[RB-001 根因](../项目漏洞核查/运行时bug.md)）
[OpenAICompatibleProvider.java:31](../../backend/src/main/java/com/superprogrammer/llm/provider/OpenAICompatibleProvider.java#L31) 与 [ClaudeProvider.java](../../backend/src/main/java/com/superprogrammer/llm/provider/ClaudeProvider.java) 构造 `WebClient.builder()` **未设 `responseTimeout` / 底层 HttpClient connect timeout**；chat（`.block()`）、embed（`.block()`）、chatStream 均**无超时上限**。（ArkSeedanceProvider 已有 10s connect + response timeout，不在此列。）

后果：provider 慢 / 连接 stall / 网络抖动时，`.block()` **无限阻塞**，调用线程永久挂死。云部署到 `ark.cn-beijing.volces.com` 一次网络抖动即可拖垮整个 JVM 线程池 → 记忆抽取静默失败（[09](09-个人记忆与冲突解决.md) extractFacts 吞异常）+ 流式线程耗尽 → RB-001 死亡螺旋。

**记忆/judge 模型**：[RagConfig](../../backend/src/main/java/com/superprogrammer/knowledge/service/RagConfig.java) `MEMORY_JUDGE_MODEL=doubao-seed-2.0-code`（extract/judge/route）、`MEMORY_EMBED_MODEL=doubao-embedding-vision`。云端 `llm_providers` 表**必须有支持这两个模型的 CHAT/EMBEDDING provider**，否则 `findProvider` 抛异常 → 记忆静默不落库。

**待修（P0）**：WebClient 加 `reactor.netty.http.client.HttpClient.responseTimeout` + connect timeout + `.block(Duration)`；LlmGateway 失败有界重试 + 熔断。详见 [运行时bug.md](../项目漏洞核查/运行时bug.md) RB-001 修复方案。

## 数据表
`llm_providers`（V60 起 category 四分 + endpoint 完整 URL；备份表 `llm_providers_bak_v60`）、`embedding_model_versions`
