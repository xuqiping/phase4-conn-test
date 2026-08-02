# 07 - 技能 Skill

## 功能简介
Agent 下挂多步技能 Skill，由有序 SkillStep 组成，运行时按步执行（LLM 调用/动作）。

## 后端 (backend)
- 控制器（同 AgentController）：
  - `GET /api/agents/{id}/skills` `GET /api/skills/{id}` `POST /api/agents/{id}/skills` `PUT /api/skills/{id}` `DELETE /api/skills/{id}`
- 服务：[SkillService.java](../../backend/src/main/java/com/superprogrammer/agent/service/SkillService.java)
- 实体：`agent/entity/` Skill、SkillStep
- Mapper：`agent/mapper/` SkillMapper、SkillStepMapper
- DTO：SkillVO、SkillDetailVO、SkillCreateRequest、SkillSaveRequest、SkillStepRequest
- 执行器：[SkillExecutor.java](../../backend/src/main/java/com/superprogrammer/engine/executor/SkillExecutor.java)（引擎层调用）

## 前端 (frontend)
- 组件：[SkillList.vue](../../frontend/src/components/SkillList.vue)、[SkillFormModal.vue](../../frontend/src/components/SkillFormModal.vue)、[SkillDetail.vue](../../frontend/src/components/SkillDetail.vue)
- 工作流节点：[SkillNode.vue](../../frontend/src/components/workflow/SkillNode.vue)
- API：[agent.ts](../../frontend/src/api/agent.ts)

## Sidecar
Skill 节点由 `resolve_source` 识别为 `SKILL` 来源：
- [node_runtime.py](../../runtime-sidecar/app/node_runtime.py) — `node.type == "SKILL"` → callback 回后端执行
- 回调链路见 [12-Runtime-Sidecar执行](12-Runtime-Sidecar执行.md)

## 数据表
`skills`、`skill_steps`
