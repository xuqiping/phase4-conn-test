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
`roles`、`permissions`、`role_permissions`
