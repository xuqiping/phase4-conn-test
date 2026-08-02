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

## 待增修改
1. ~~现在我本人创建的技能和Agent，在工作流中拖拽出来，选中后都无法修改提示词，传参了，并且我在Agent的能力中设置了参数，在工作流里也看不到了~~ **已修复(2026-06-30)**：根因是工作流落点/加载节点从不回填能力元数据。修复：[PropertyPanel.vue](../../frontend/src/components/workflow/PropertyPanel.vue) 选中 skill 节点时调 `getSkillDetail`+`getAgentAccess`，注入 `inputParams`/`systemPrompt`/`promptTemplate`/`model`/`temperature`/`outputKey` 及权限标志 `promptConfigVisible`(=canReadPrompt)/`promptConfigEditable`(=canManage)/`descriptionEditable`，并写回 node.data 随保存持久化。拖拽与加载已存工作流两条路径均验证通过。注：`agent_ref` 节点按设计不暴露单条提示词（Agent 路由到技能），其提示词编辑归属对应 skill 节点。
2. 工作流自编排能力
3. 结合我现在的知识库，如何导入，以及后续如何自成长