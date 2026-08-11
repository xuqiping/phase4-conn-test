# 设置模块 10x 问题修复 · 功能 README

> 受众判定：**C 两者**——既有用户可感知变化（菜单/设置入口/对话模型），也有技术说明（模块开关机制、守卫纯函数、导出导入）。

## 用户地图

### 谁用
- **admin**：进设置管理全局供应商（新增导出/导入能力），权限与模块显隐的受益方。
- **普通用户**：无感（看不到的模块本就不该见）；对话模型统一走全局，体验更一致。

### 什么场景
1. admin 想备份/迁移全局模型供应商配置 → 用导出/导入一键完成（不用逐条重录）。
2. 项目阶段性不需要 Agent大厅/工作流/执行监控 → 一键隐藏，全员（含 admin）不可见，保留代码随时恢复。
3. 不同角色看到不同菜单 → 无权限模块自动隐藏，避免误点 403。

### 什么效益
- 配置可移植（导出 JSON 跨环境迁移）。
- 菜单更干净（按项目实际启用的模块展示）。
- 权限收敛（设置、敏感管理功能仅 admin）。
- 对话路由统一（消除"有人配过个人 key 仍生效"的认知偏差）。

## 技术说明

### 核心机制
1. **项目级模块开关** `frontend/src/config/modules.ts`
   - `ENABLED_MODULES`：模块布尔表，单点控制显隐。
   - `MODULE_PERMISSION_MAP`：模块→权限码，叠加 RBAC。
   - 消费方：Sidebar（菜单）、accessGuard（路由）、入口组件。
2. **路由守卫纯函数** `frontend/src/router/accessGuard.ts`
   - `resolveRouteAccess` 把访问判定抽成无副作用函数，便于单测。
   - beforeEach 调用它，读 localStorage 构造 UserContext（早于 Pinia）。
3. **供应商导出/导入**
   - 后端：`LlmController` export（明文 key 下载）/import（upsert by name）端点，@RequirePermission + @AuditLog。
   - 前端：ProviderManageTab 按钮，二次确认 + 预览计数。
4. **对话路由统一** `LlmGateway.findProvider`
   - 停用用户级 override，chat 一律走全局 CHAT 注册表。

### 关键设计取舍
- **不删代码**：用户级 LLM（UserProviderTab/UserLlmController/user_llm_providers 表）保留，仅断入口与路由——未来恢复成本低。
- **导出明文 key**：admin 间迁移最省事，靠 @RequirePermission + @AuditLog + 前端确认保安全。
- **零 Flyway 迁移**：纯代码 + 配置，revert 即回滚。

### 测试覆盖
- 前端 30 个新增测试（模块开关/Sidebar/守卫/SettingsView）。
- 后端 15 个新增测试（导出/导入/chat 路由）。
- 全量回归：前端 424 绿。

## 相关文档
- 计划：[`docs/plans/设置模块人工测试问题修复.plan.md`](../../docs/plans/设置模块人工测试问题修复.plan.md)
- 测试方案：[`docs/测试方案/设置模块10x问题修复测试方案.md`](../../docs/测试方案/设置模块10x问题修复测试方案.md)
- Feature Map：[`docs/feature-map/设置模块10x问题修复.feature-map.md`](../../docs/feature-map/设置模块10x问题修复.feature-map.md)
- 用户操作手册：[`docs/user-ops/设置模块10x问题修复用户操作手册.md`](../../docs/user-ops/设置模块10x问题修复用户操作手册.md)
- 速查表：[`项目工程文档/项目功能介绍/速查表/21-系统设置.md`](../../../项目工程文档/项目功能介绍/速查表/21-系统设置.md)
