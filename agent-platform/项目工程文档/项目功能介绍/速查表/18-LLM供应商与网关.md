# 18 - LLM 供应商与网关

## 功能简介
平台级 LLM Provider 配置（OpenAI 兼容 / Claude），Key AES 加密存储，连接测试（chat + embed），网关统一调用层，热重载。

## 后端 (backend) — `llm` 包
- 控制器：[LlmController.java](../../backend/src/main/java/com/superprogrammer/llm/controller/LlmController.java)
  - `GET/POST /api/llm/providers` `PUT/DELETE /{id}` `POST /{id}/test`(chat) `POST /{id}/test-embed` `POST /providers/reload`
- 网关：[LlmGateway.java](../../backend/src/main/java/com/superprogrammer/llm/service/LlmGateway.java)
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

## 数据表
`llm_providers`、`embedding_model_versions`
