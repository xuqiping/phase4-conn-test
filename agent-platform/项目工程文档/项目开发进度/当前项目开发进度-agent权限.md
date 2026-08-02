# Agent权限功能开发进度

> 创建时间：2026-06-12  
> 对应计划：`项目工程文档/计划/计划9-Agent权限功能开发计划.md`  
> 当前分支：`codex/plan8-runtime-composition`

---

## 总体状态

| 任务 | 内容 | 状态 | 说明 |
|------|------|------|------|
| Task 1 | AgentPermission 实体、Mapper、DTO、数据库迁移 | ✅ 完成 | 新增 `agent_permissions` 迁移、H2 schema、实体、Mapper、DTO |
| Task 2 | AgentPermissionService 权限判断与授权保存 | ✅ 完成 | 默认权限、隐含使用权限、授权保存与非持有者拒绝已测试 |
| Task 3 | 授权管理接口 | ✅ 完成 | 新增 `/access`、`/permissions` GET/PUT 接口并通过 Controller 测试 |
| Task 4 | Agent 列表、详情、聊天目标、工作流入口过滤与脱敏 | ✅ 完成 | Agent 列表/详情、Chat 目标、Workflow prompt 可见性已接入对象级权限 |
| Task 5 | Agent 复制保存语义 | ✅ 完成 | 新增复制请求 DTO、服务复制逻辑、`POST /api/agents/{id}/copy` 接口 |
| Task 6 | 前端 Agent API 类型与接口 | ✅ 完成 | 新增访问能力、授权列表、授权保存、复制 Agent API 类型与调用 |
| Task 7 | Agent 详情权限分发 UI | ✅ 完成 | 新增 Agent 授权弹窗，持有者/管理员可为用户分配使用、可读提示词、可复制权限 |
| Task 8 | Agent 详情按访问能力控制编辑/复制保存 | ✅ 完成 | AgentFormModal 支持 copy 保存模式，非管理但可复制用户在详情页显示“复制编辑” |
| Task 9 | 工作流与聊天入口接入对象级权限 | ✅ 完成 | 工作流/聊天入口过滤已接入，运行时 Skill/AgentRef 执行补充 canUse 校验 |

---

## 进度日志

### 2026-06-12

- 初始化 Agent 权限功能开发进度文件。
- 完成 Task 1-2：新增 Agent 对象级权限数据结构与 `AgentPermissionService`。
- 验证：`mvn -q "-Dtest=AgentPermissionServiceTest" test` 通过。
- 完成 Task 3：新增 Agent 授权访问能力查询、授权列表查询、授权保存接口。
- 验证：`mvn -q "-Dtest=AgentControllerTest" test` 通过。
- 完成 Task 4：Agent 查询过滤、详情访问校验、Chat 目标使用校验、Workflow Skill prompt 可读/可编辑拆分。
- 验证：`mvn -q "-Dtest=AgentControllerTest,AgentServiceTest,ChatTargetServiceTest,WorkflowServiceTest,AgentPermissionServiceTest" test` 通过。
- 完成 Task 5：实现 Agent 复制保存语义，复制后新 Agent/Skill/SkillStep 的创建者为当前用户，原 Agent 不变。
- 验证：`mvn -q "-Dtest=AgentControllerTest,AgentServiceTest,AgentPermissionServiceTest,ChatTargetServiceTest,WorkflowServiceTest" test` 通过。
- 完成 Task 6：补齐前端 Agent 权限 API 类型与接口。
- 验证：`npm test -- --run src/api/agent.test.ts` 通过。
- 完成 Task 7：新增 `AgentPermissionModal`，Agent 详情页按对象级 `canManage` 显示授权入口。
- 验证：`npm test -- --run src/views/AgentDetailView.test.ts src/components/AgentPermissionModal.test.ts` 通过。
- 完成 Task 8：Agent 编辑弹窗支持复制保存，详情页按 `canCopy && !canManage` 显示复制编辑入口。
- 验证：`npm test -- --run src/views/AgentDetailView.test.ts src/components/AgentFormModal.test.ts` 通过。
- 完成 Task 9：修正前端授权字段与后端 DTO 对齐，运行时节点回调执行 Agent/Skill 时校验对象级 `canUse`。
- 验证：`npm test -- --run src/api/agent.test.ts src/components/AgentPermissionModal.test.ts src/views/AgentDetailView.test.ts src/components/AgentFormModal.test.ts src/components/workflow/PropertyPanel.test.ts` 通过。
- 验证：`mvn -q "-Dtest=AgentControllerTest,AgentServiceTest,AgentPermissionServiceTest,ChatTargetServiceTest,WorkflowServiceTest,RuntimeNodeCallbackServiceTest" test` 通过。
