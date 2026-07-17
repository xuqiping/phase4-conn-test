# 19 - 用户级 LLM 配置

## 功能简介
用户自带 LLM Provider（个人 Key）、测试连通性、查询可用模型列表。与平台 Provider 区分，按用户隔离。

## 后端 (backend) — `llm` 包
- 控制器：[UserLlmController.java](../../backend/src/main/java/com/superprogrammer/llm/controller/UserLlmController.java)
  - `GET POST /api/llm/user/providers` `DELETE /{id}` `POST /{id}/test` `GET /models/available`
- 服务：UserLlmProviderService
- 实体：`llm/entity/` UserLlmProviderEntity
- Mapper：UserLlmProviderMapper
- DTO：UserLlmProviderVO、UserLlmProviderRequest

## 前端 (frontend)
- 组件：[settings/UserProviderTab.vue](../../frontend/src/components/settings/UserProviderTab.vue)、[chat/ModelSelector.vue](../../frontend/src/components/chat/ModelSelector.vue)（对话中选模型）
- API：[llm.ts](../../frontend/src/api/llm.ts)
- 入口：[SettingsView.vue](../../frontend/src/views/SettingsView.vue)

## Sidecar
无。

## 数据表
`user_llm_providers`


## 待增删改
- 对用户提出的问题，要判断哪些推理强度高，哪些中、低，推荐适配模型，用哪个模型来完成任务