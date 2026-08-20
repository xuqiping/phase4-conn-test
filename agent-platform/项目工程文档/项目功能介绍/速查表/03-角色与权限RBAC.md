# 03 - 角色与权限 RBAC

## 功能简介
角色管理、权限点分配、方法级权限注解 `@RequirePermission("resource:action")`、PermissionEvaluator 运行时鉴权。

## 后端 (backend)
- 控制器：[RoleController.java](../../backend/src/main/java/com/superprogrammer/auth/controller/RoleController.java)
  - `GET /api/roles` `GET /all` `GET /{id}` `GET/PUT /{id}/permissions` `GET /permissions/all`
- 注解与切面：
  - [RequirePermission.java](../../backend/src/main/java/com/superprogrammer/auth/security/RequirePermission.java)
  - [RequirePermissionAspect.java](../../backend/src/main/java/com/superprogrammer/auth/security/RequirePermissionAspect.java)
  - [PermissionEvaluator.java](../../backend/src/main/java/com/superprogrammer/auth/security/PermissionEvaluator.java)
- 实体：`auth/entity/` Role、Permission、RolePermission
- Mapper：`auth/mapper/` RoleMapper、PermissionMapper、RolePermissionMapper

## 前端 (frontend)
- 视图：[RoleManageView.vue](../../frontend/src/views/admin/RoleManageView.vue)
- API：[admin.ts](../../frontend/src/api/admin.ts)
- 路由：`/admin/roles`

## 数据表
`roles`、`permissions`、`role_permissions`、`user_roles`（复合主键 `(user_id, role_id)`——一人可多角色、多人均可为管理员）

## 多系统管理员（16x-1，2026-08-20 核验支持）
- 一个用户可同时持有多个角色；多个用户可同时是系统管理员——`user_roles` 复合主键天然支持。
- **操作**：系统管理 → 用户管理 → 目标用户行「分配角色」→ 勾选「系统管理员」（可与其他角色并存）→ 保存。
- 后端：`PUT /api/users/{id}/roles`（[UserController.java:130](../../backend/src/main/java/com/superprogrammer/auth/controller/UserController.java)）全量替换该用户角色集；`user:manage` 权限 + `@AuditLog(assign_roles)` 审计留痕。
- 生效时机：**用户需重新登录**——权限烘焙在 JWT，旧 token 仍是旧权限集。

## user 角色权限范围（16x-2，V136 变更）
- 2026-08-20 前存量库：`media:gen`/`canvas:write`/`asset:write`/`media:edit` 四权限码建表时只授了 admin → 普通用户进图片/视频/画布 403。
- [V136__user_role_media_canvas_grant.sql](../../backend/src/main/resources/db/migration/V136__user_role_media_canvas_grant.sql)：一次性补授 `user` 角色（`ON CONFLICT DO NOTHING` 幂等）；新库自动生效。
- 数据归属不受影响：画布/资产仍只能访问自己创建的（服务层 loadOwned 咽喉控制），授予的只是「能用自己的」。
- [V137__user_role_project_group_grant.sql](../../backend/src/main/resources/db/migration/V137__user_role_project_group_grant.sql)：补授 `project-group:manage`（V134 只授 admin → 普通用户 `/api/project-groups/mine` 403，页顶「参与项目」选择器恒空，2026-08-20 冒烟实测发现）。越权防线不动：组长级资金/成员操作另有 service 层 `requireOwner` 二层校验（只能操作自己是组长的组）。

