# 18 - LLM 供应商与网关

## 功能简介
平台级 LLM Provider 配置（OpenAI 兼容 / Claude），Key AES 加密存储，连接测试（chat + embed），网关统一调用层，热重载。

## 后端 (backend) — `llm` 包
- 控制器：[LlmController.java](../../backend/src/main/java/com/superprogrammer/llm/controller/LlmController.java)
  - `GET/POST /api/llm/providers` `PUT/DELETE /{id}` `POST /{id}/test`(chat) `POST /{id}/test-embed` `POST /providers/reload`
- 网关：[LlmGateway.java](../../backend/src/main/java/com/superprogrammer/llm/LlmGateway.java)（按 model 路由 provider：先查用户级 override，回退全局 `llmConfig.getProviders()`，无匹配抛「没有找到支持模型 'X' 的Provider」）
- Provider 实现：`llm/provider/`
  - LlmProviderInterface（接口）
  - OpenAICompatibleProvider、ClaudeProvider
- 服务：LlmProviderService、[AesEncryptService.java](../../backend/src/main/java/com/superprogrammer/llm/service/AesEncryptService.java)（Key 加密）
- 实体：`llm/entity/` LlmProviderEntity
- Mapper：LlmProviderMapper、EmbeddingModelVersionMapper
- 配置：LlmConfig
- DTO：LlmProviderVO、LlmProviderCreateRequest、AvailableModelVO、TestConnectionResult、LlmRequest/Response、LlmMessage、TokenUsage

## 前端 (frontend)
- 组件：[settings/ProviderManageTab.vue](../../frontend/src/components/settings/ProviderManageTab.vue)
- API：[llm.ts](../../frontend/src/api/llm.ts)
- 入口：[SettingsView.vue](../../frontend/src/views/SettingsView.vue)

## Sidecar
无（LLM 调用经后端网关；Sidecar 节点回调后端执行 LLM）。

## ⚠️ 已知缺陷：WebClient 无超时（[RB-001 根因](../项目漏洞核查/运行时bug.md)）
[OpenAICompatibleProvider.java:31](../../backend/src/main/java/com/superprogrammer/llm/provider/OpenAICompatibleProvider.java#L31) 与 [ClaudeProvider.java](../../backend/src/main/java/com/superprogrammer/llm/provider/ClaudeProvider.java) 构造 `WebClient.builder()` **未设 `responseTimeout` / 底层 HttpClient connect timeout**；chat（`.block()`）、embed（`.block()`）、chatStream 均**无超时上限**。

后果：provider 慢 / 连接 stall / 网络抖动时，`.block()` **无限阻塞**，调用线程永久挂死。云部署到 `ark.cn-beijing.volces.com` 一次网络抖动即可拖垮整个 JVM 线程池 → 记忆抽取静默失败（[09](09-个人记忆与冲突解决.md) extractFacts 吞异常）+ 流式线程耗尽 → RB-001 死亡螺旋。

**记忆/judge 模型**：[RagConfig](../../backend/src/main/java/com/superprogrammer/knowledge/service/RagConfig.java) `MEMORY_JUDGE_MODEL=doubao-seed-2.0-code`（extract/judge/route）、`MEMORY_EMBED_MODEL=doubao-embedding-vision`。云端 `llm_providers` 表**必须有支持这两个模型的 chat/embed provider**，否则 `findProvider` 抛异常 → 记忆静默不落库。

**待修（P0）**：WebClient 加 `reactor.netty.http.client.HttpClient.responseTimeout` + connect timeout + `.block(Duration)`；LlmGateway 失败有界重试 + 熔断。详见 [运行时bug.md](../项目漏洞核查/运行时bug.md) RB-001 修复方案。

## 数据表
`llm_providers`、`embedding_model_versions`
