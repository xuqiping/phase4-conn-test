# 多Agent智能体平台 - 项目约定

## 项目结构
- `agent-platform/backend/` — Spring Boot 3.2.5 后端（Java 17）
- `agent-platform/frontend/` — Vue 3 + TypeScript + Vite 前端

## 后端约定
- 包名：`com.superprogrammer`
- ORM：MyBatis-Plus 3.5.5，所有实体继承 `BaseEntity`
- 响应：统一使用 `R<T>` 封装，分页用 `PageResult<T>`
- 异常：业务异常抛 `BusinessException(ErrorCode)`
- 权限：方法级用 `@RequirePermission("resource:action")`
- 逻辑删除：`deleted` 字段，`@TableLogic`
- 自动填充：`created_by/created_at/updated_by/updated_at` 通过 `MetaObjectHandler`
- 数据库：PostgreSQL，主键用 `GENERATED ALWAYS AS IDENTITY`
- 迁移：Flyway，文件放 `src/main/resources/db/migration/`
- 安全：JWT access 15分钟 + refresh 7天，Redis黑名单

## 前端约定
- 框架：Vue 3 + TypeScript + Vite 5
- UI库：Naive UI（暗色主题）
- 状态：Pinia stores（`stores/auth.ts`、`stores/theme.ts`）
- API：`src/api/request.ts` Axios实例（自动JWT注入+401刷新）
- 主题：高山流水双主题 夜墨ye-mo(默认)/宣纸xuan-zhi，CSS变量驱动；旧3套暗色 hidden 隐藏未删（theme.ts）
- 样式：Sass，BEM命名，CSS变量引用主题色
- 路由：`src/router/index.ts`，守卫检查认证

## Git约定
- 分支：main（稳定）、develop（开发）
- Commit格式：`feat:/fix:/docs:/refactor:/chore: 中文描述`
- 不提交：node_modules/、target/、.env、IDE配置

## API规范
- 前缀：`/api/`
- 认证：Header `Authorization: Bearer {token}`
- 响应格式：`{ "code": 200, "msg": "success", "data": {...} }`
