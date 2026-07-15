# File Keeper 服务端阶段 1 设计规格

> 日期：2026-06-06  
> 范围：商业售卖版账号权限与授权系统阶段 1  
> 状态：已由用户确认，待实施计划拆解

## 目标

在 `file-keeper/server/` 新增 Spring Boot 授权服务基础工程，完成可运行、可测试的后端底座。阶段 1 只交付数据库模型、通用基础类、Flyway 迁移和超级管理员初始化能力，为后续注册登录、授权、设备绑定、匿名试用和管理后台提供基础。

## 非目标

阶段 1 不实现以下内容：

- 桌面客户端登录、注册或权益接入。
- Web 管理后台。
- 普通用户注册、验证码、登录、JWT 刷新。
- Redis refresh token 撤销。
- 模块授权 API。
- 匿名权益 API。
- 邮箱或短信供应商接入。

这些内容进入后续阶段，不在本切片中提前实现。

## 技术与测试选择

服务端使用：

- Spring Boot 3.2.5。
- Java 17。
- MyBatis-Plus 3.5.5。
- Flyway。
- PostgreSQL 作为开发/生产数据库。
- H2 作为阶段 1 自动化测试数据库。

测试采用 H2 兼容模式，原因是阶段 1 目标是快速建立数据模型和初始化逻辑，不要求本机 Docker 或 PostgreSQL 服务可用。开发/生产配置仍面向 PostgreSQL。迁移 SQL 需要避免仅 PostgreSQL 支持、H2 无法解析的语法。

## 目录结构

新增目录：

```text
file-keeper/server/
  pom.xml
  src/main/java/com/superprogrammer/FileKeeperServerApplication.java
  src/main/java/com/superprogrammer/common/
  src/main/java/com/superprogrammer/config/
  src/main/java/com/superprogrammer/user/entity/
  src/main/java/com/superprogrammer/device/entity/
  src/main/java/com/superprogrammer/settings/entity/
  src/main/java/com/superprogrammer/audit/entity/
  src/main/java/com/superprogrammer/bootstrap/
  src/main/resources/application.yml
  src/main/resources/application-dev.yml
  src/main/resources/db/migration/V1__create_auth_schema.sql
  src/test/java/com/superprogrammer/db/FlywayMigrationTest.java
  src/test/java/com/superprogrammer/bootstrap/SuperAdminInitializerTest.java
```

## 组件设计

### Maven 工程

`file-keeper/server/pom.xml` 定义独立 Spring Boot 服务端工程，artifact 建议为 `file-keeper-server`。依赖包括：

- `spring-boot-starter-web`
- `spring-boot-starter-validation`
- `spring-boot-starter-security`
- `spring-boot-starter-data-redis`
- `mybatis-plus-spring-boot3-starter`
- `flyway-core`
- `postgresql`
- `lombok`
- `h2` 测试依赖
- `spring-boot-starter-test`
- `spring-security-test`

阶段 1 引入 security 和 redis 依赖是为了保持工程骨架与后续阶段一致，但阶段 1 不实现完整认证链路。

### 应用入口

`FileKeeperServerApplication` 是 Spring Boot 启动类，包名为 `com.superprogrammer`，与项目约定一致。

### 通用基础类

`common` 包提供后续阶段统一复用的基础类型：

- `R<T>`：统一响应结构，包含 `code`、`msg`、`data`。
- `PageResult<T>`：分页响应结构，包含 `records`、`total`、`page`、`size`。
- `ErrorCode`：业务错误码枚举。
- `BusinessException`：携带 `ErrorCode` 的运行时异常。
- `BaseEntity`：所有 MyBatis-Plus 实体继承，包含 `id`、`createdBy`、`createdAt`、`updatedBy`、`updatedAt`、`deleted`。

`BaseEntity` 使用 MyBatis-Plus 注解描述主键、逻辑删除和自动填充字段。阶段 1 不实现当前登录用户审计来源，自动填充中的用户字段可使用默认系统值。

### 配置类

`config` 包包含：

- `MyBatisPlusConfig`：启用分页插件。
- `MetaObjectHandlerConfig`：自动填充创建/更新时间和默认操作人。

### 数据实体

阶段 1 创建 6 个实体，分别映射 6 张核心表：

- `User`：账号、角色、状态、联系方式、设备策略、离线缓存策略。
- `UserModuleEntitlement`：用户模块授权，模块 code 为 `files`、`processes`、`clipboard`。
- `UserDevice`：登录账号绑定设备。
- `AnonymousDeviceTrial`：匿名设备试用和长期免费模块选择。
- `SystemSetting`：系统配置键值。
- `AdminAuditLog`：管理员高风险操作审计日志。

阶段 1 只定义实体字段和表结构，不实现业务服务。

## 数据库设计

`V1__create_auth_schema.sql` 创建以下表：

### users

关键字段：

- `id`
- `email`
- `phone`
- `password_hash`
- `role`
- `status`
- `email_verified`
- `phone_verified`
- `device_limit`
- `offline_cache_minutes`
- `created_by`
- `created_at`
- `updated_by`
- `updated_at`
- `deleted`

约束：

- `email` 唯一。
- `phone` 唯一。
- `email` 和 `phone` 至少一个非空。
- `role` 限制为 `super_admin` 或 `user`。
- `status` 限制为 `pending_verification`、`pending_review`、`active`、`disabled`。

### user_module_entitlements

关键字段：

- `user_id`
- `module_code`
- `enabled`
- `expires_at`

约束：

- `user_id + module_code` 唯一。
- `module_code` 限制为 `files`、`processes`、`clipboard`。

### user_devices

关键字段：

- `user_id`
- `device_id`
- `fingerprint_hash`
- `device_name`
- `status`
- `last_seen_at`

约束：

- `user_id + device_id` 唯一。
- `status` 限制为 `active` 或 `disabled`。

### anonymous_device_trials

关键字段：

- `device_id`
- `fingerprint_hash`
- `device_name`
- `trial_started_at`
- `trial_expires_at`
- `free_module_code`
- `free_module_selected_at`
- `last_free_module_changed_at`
- `status`

约束：

- `device_id` 唯一。
- `free_module_code` 为空或为 `files`、`processes`、`clipboard`。
- `status` 限制为 `active` 或 `disabled`。

### system_settings

关键字段：

- `setting_key`
- `setting_value`
- `description`

约束：

- `setting_key` 唯一。

### admin_audit_logs

关键字段：

- `admin_user_id`
- `action`
- `target_type`
- `target_id`
- `detail`
- `ip_address`

用于后续管理员审核、授权、设备管理、匿名设备重置等高风险操作记录。

## 超级管理员初始化

`SuperAdminInitializer` 在应用启动后读取配置：

- `file-keeper.bootstrap.super-admin.email`
- `file-keeper.bootstrap.super-admin.phone`
- `file-keeper.bootstrap.super-admin.password`

初始化规则：

1. 如果密码为空，不创建超级管理员。
2. 如果邮箱和手机号都为空，不创建超级管理员。
3. 如果已存在 `role = super_admin` 且同邮箱或同手机号的用户，不重复创建。
4. 如果不存在，创建 `role = super_admin`、`status = active`、联系方式已验证的用户。
5. 阶段 1 可以使用 Spring Security `PasswordEncoder` 生成 `password_hash`，不实现登录流程。

## 测试设计

### FlywayMigrationTest

目标：证明阶段 1 迁移可以在测试环境完整执行，并创建 6 张核心表。

验证点：

- Spring Boot 测试上下文能启动。
- Flyway 自动执行 `V1__create_auth_schema.sql`。
- H2 `INFORMATION_SCHEMA.TABLES` 中存在：
  - `USERS`
  - `USER_MODULE_ENTITLEMENTS`
  - `USER_DEVICES`
  - `ANONYMOUS_DEVICE_TRIALS`
  - `SYSTEM_SETTINGS`
  - `ADMIN_AUDIT_LOGS`

### SuperAdminInitializerTest

目标：证明配置存在时会初始化超级管理员。

验证点：

- 测试配置提供超级管理员邮箱和密码。
- 启动后 `users` 表存在一条 `role = super_admin` 的用户。
- 该用户 `status = active`。
- 该用户 `email_verified = true`。
- `password_hash` 不为空，且不等于明文密码。

## 验证命令

阶段 1 完成后运行：

```bash
mvn -f "file-keeper/server/pom.xml" test
```

预期：服务端测试全部通过。

可选启动验证：

```bash
mvn -f "file-keeper/server/pom.xml" spring-boot:run
```

预期：服务端启动成功；如果本机没有 PostgreSQL，开发配置需要先提供可用数据库连接或使用测试 profile 运行测试。

## 交付边界

阶段 1 完成定义：

- `file-keeper/server/` 工程存在。
- Maven 测试命令可执行。
- Flyway 能创建 6 张核心表。
- 基础通用类和实体已创建。
- 超级管理员初始化逻辑有测试覆盖。
- 不包含阶段 2 及以后的 API 行为。