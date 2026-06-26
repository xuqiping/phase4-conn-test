# 06 - Agent 权限与知识库绑定

## 功能简介
Agent 访问授权（哪些用户/角色可用）、Agent 绑定知识库（KB）以启用 RAG、Agent `rag-enabled` 开关。

## 后端 (backend)
- 控制器（同 AgentController）：
  - `GET /api/agents/{id}/access` — 可见性
  - `GET /api/agents/{id}/permissions` `PUT /{id}/permissions` — 权限
  - `GET /api/agents/{id}/kb-bindings` `PUT /{id}/kb-bindings` — KB 绑定
- 服务：[AgentPermissionService.java](../../backend/src/main/java/com/superprogrammer/agent/service/AgentPermissionService.java)、[AgentKbBindingService.java](../../backend/src/main/java/com/superprogrammer/agent/service/AgentKbBindingService.java)
- 实体：`agent/entity/` AgentPermission、AgentKbBinding
- Mapper：`agent/mapper/` AgentPermissionMapper、AgentKbBindingMapper
- DTO：AgentAccessVO、AgentPermissionVO、AgentPermissionSaveRequest、AgentKbBindingVO

## 前端 (frontend)
- 组件：[AgentPermissionModal.vue](../../frontend/src/components/AgentPermissionModal.vue)
- API：[agent.ts](../../frontend/src/api/agent.ts)
- KB 绑定 UI 见 Agent 详情/表单

## Sidecar
无。

## 数据表
`agent_permissions`、`agent_kb_bindings`
