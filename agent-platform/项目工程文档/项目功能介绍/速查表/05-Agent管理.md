# 05 - Agent 管理

## 功能简介
Agent 大厅列表、详情、增删改查、启停、复制、Markdown 同步、Agent 分组。

## 后端 (backend)
- 控制器：[AgentController.java](../../backend/src/main/java/com/superprogrammer/agent/controller/AgentController.java)
  - `GET /api/agents` `GET /{id}` `POST` `PUT /{id}` `DELETE /{id}` `PUT /{id}/status` `POST /{id}/copy` `POST /agents/sync` `PUT /{id}/rag-enabled`
- 分组：[AgentGroupController.java](../../backend/src/main/java/com/superprogrammer/agent/controller/AgentGroupController.java) — `GET /api/agent-groups`
- 服务：`agent/service/` AgentService、MarkdownSyncService、AgentKbBindingService、AgentPermissionService
- 实体：`agent/entity/` Agent、AgentGroup
- Mapper：`agent/mapper/` AgentMapper、AgentGroupMapper

## 前端 (frontend)
- 视图：[AgentHallView.vue](../../frontend/src/views/AgentHallView.vue)（大厅）、[AgentDetailView.vue](../../frontend/src/views/AgentDetailView.vue)（详情）
- 组件：[AgentCard.vue](../../frontend/src/components/AgentCard.vue)、[AgentFormModal.vue](../../frontend/src/components/AgentFormModal.vue)、[SkillList.vue](../../frontend/src/components/SkillList.vue)
- API：[agent.ts](../../frontend/src/api/agent.ts)
- 路由：`/agents`、`/agents/:id`

## Sidecar
无（Agent 作为节点配置由工作流执行，见 [12-Runtime-Sidecar执行](12-Runtime-Sidecar执行.md)）。

## 数据表
`agents`（`group_id` 外键直连 `agent_groups`，无成员中间表）、`agent_groups`
