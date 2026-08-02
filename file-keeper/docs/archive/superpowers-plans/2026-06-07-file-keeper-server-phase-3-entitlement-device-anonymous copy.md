# File Keeper Server Phase 3: 模块授权、设备绑定、匿名试用、授权查询

## Context

阶段 1 完成了服务端基础工程，阶段 2 完成了认证闭环（注册、登录、审核、禁用启用）。阶段 3 实现商业授权的核心业务逻辑：管理员授予模块权益、客户端设备绑定、匿名设备 7 天试用及免费模块、统一的授权查询 API（桌面客户端调用入口）。

数据库表和实体已在阶段 1 创建，无需新 Flyway 迁移。本阶段只构建 Service/Repository/Controller 层。

---

## Task 1: 模块授权管理（Admin CRUD + Client 查询）

### 新建文件
- `server/src/main/java/com/superprogrammer/user/repository/EntitlementRepository.java`
- `server/src/main/java/com/superprogrammer/user/service/EntitlementService.java`
- `server/src/main/java/com/superprogrammer/user/controller/ClientEntitlementController.java`
- `server/src/main/java/com/superprogrammer/admin/controller/AdminEntitlementController.java`
- `server/src/main/java/com/superprogrammer/user/dto/ModuleEntitlementDto.java` — `record(Long id, Long userId, String moduleCode, Boolean enabled, OffsetDateTime expiresAt)`
- `server/src/main/java/com/superprogrammer/user/dto/GrantEntitlementRequest.java` — `record(@NotBlank String moduleCode, OffsetDateTime expiresAt)`
- `server/src/main/java/com/superprogrammer/user/dto/UpdateEntitlementRequest.java` — `record(Boolean enabled, OffsetDateTime expiresAt)`
- `server/src/test/java/com/superprogrammer/admin/AdminEntitlementTest.java`
- `server/src/test/java/com/superprogrammer/user/ClientEntitlementTest.java`

### 修改文件
- `AuthConstants.java` — 新增 `MODULE_FILES = "files"`, `MODULE_PROCESSES = "processes"`, `MODULE_CLIPBOARD = "clipboard"`

### API 路径
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/admin/users/{id}/entitlements` | 管理员查看用户权益 |
| POST | `/api/admin/users/{id}/entitlements` | 授予模块权益（审计: `entitlement.grant`） |
| PUT | `/api/admin/users/{id}/entitlements/{eid}` | 更新权益（审计: `entitlement.update`） |
| DELETE | `/api/admin/users/{id}/entitlements/{eid}` | 撤销权益（审计: `entitlement.revoke`） |
| GET | `/api/client/entitlements` | 客户端查看自己的权益（只返回 enabled=true 且未过期） |

### 业务规则
- moduleCode 必须是 files/processes/clipboard 之一
- 同一用户同一模块不能重复授予（409）
- 撤销使用软删除（deleted=1）
- 客户端查询过滤：enabled=true 且（expiresAt IS NULL OR expiresAt > now）

### 关键测试用例
1. 管理员授予 files 模块，验证 DB 记录和审计日志
2. 重复授予同一模块返回 409
3. 授予无效 moduleCode 返回 400
4. 更新 enabled 和 expiresAt
5. 软删除权益
6. 客户端只看到 enabled=true 且未过期的权益
7. 过期权益不出现在客户端查询中

---

## Task 2: 设备绑定

### 新建文件
- `server/src/main/java/com/superprogrammer/device/repository/DeviceRepository.java`
- `server/src/main/java/com/superprogrammer/device/service/DeviceBindingService.java`
- `server/src/main/java/com/superprogrammer/device/controller/ClientDeviceController.java`
- `server/src/main/java/com/superprogrammer/admin/controller/AdminDeviceController.java`
- `server/src/main/java/com/superprogrammer/device/dto/DeviceDto.java` — `record(Long id, Long userId, String deviceId, String fingerprintHash, String deviceName, String status, OffsetDateTime lastSeenAt)`
- `server/src/main/java/com/superprogrammer/device/dto/RegisterDeviceRequest.java` — `record(@NotBlank String deviceId, @NotBlank String fingerprintHash, String deviceName)`
- `server/src/test/java/com/superprogrammer/device/DeviceBindingTest.java`
- `server/src/test/java/com/superprogrammer/admin/AdminDeviceTest.java`

### API 路径
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/client/devices/register` | 注册/绑定设备；已存在则更新 lastSeenAt |
| GET | `/api/client/devices` | 列出自己的设备 |
| GET | `/api/admin/users/{id}/devices` | 管理员查看用户设备 |
| POST | `/api/admin/users/{id}/devices/{deviceId}/disable` | 禁用设备（审计: `device.disable`） |

### 业务规则
- 注册时查 user.device_limit，active 设备数达到上限则返回 409
- 同一用户同一 deviceId 已存在：更新 lastSeenAt，不新建
- 禁用设备后该设备无法通过授权查询

### 关键测试用例
1. 注册第一台设备成功
2. 同一设备重复注册更新 lastSeenAt
3. device_limit=1 时第二台设备被拒绝（409）
4. 禁用用户无法注册设备
5. 管理员禁用设备 + 审计日志

---

## Task 3: 管理员用户设置

### 新建文件
- `server/src/main/java/com/superprogrammer/user/dto/UserSettingsUpdateRequest.java` — `record(@NotNull Integer deviceLimit, @NotNull Integer offlineCacheMinutes)`
- `server/src/test/java/com/superprogrammer/admin/AdminUserSettingsTest.java`

### 修改文件
- `UserRepository.java` — 新增 `updateSettings(id, deviceLimit, offlineCacheMinutes, operatorId)` 方法
- `AdminUserService.java` — 新增 `updateSettings()` 方法
- `AdminUserController.java` — 新增 `PUT /{id}/settings` 端点（审计: `user.update_settings`）

### 业务规则
- deviceLimit >= 1，offlineCacheMinutes >= 0
- 目标用户不存在返回 404

### 关键测试用例
1. 修改 device_limit 为 3 + 审计日志
2. 修改 offline_cache_minutes 为 60
3. deviceLimit < 1 返回 400
4. offlineCacheMinutes < 0 返回 400

---

## Task 4: 匿名设备试用

### 新建文件
- `server/src/main/java/com/superprogrammer/device/repository/AnonymousTrialRepository.java`
- `server/src/main/java/com/superprogrammer/device/service/AnonymousTrialService.java`
- `server/src/main/java/com/superprogrammer/device/controller/AnonymousTrialController.java`
- `server/src/main/java/com/superprogrammer/device/dto/AnonymousTrialStatusDto.java`
- `server/src/main/java/com/superprogrammer/device/dto/StartTrialRequest.java` — `record(@NotBlank String deviceId, @NotBlank String fingerprintHash, String deviceName)`
- `server/src/main/java/com/superprogrammer/device/dto/SelectFreeModuleRequest.java` — `record(@NotBlank String deviceId, @NotBlank String fingerprintHash, @NotBlank String freeModuleCode)`
- `server/src/test/java/com/superprogrammer/device/AnonymousTrialTest.java`

### 修改文件
- `SecurityConfig.java` — 新增 `.requestMatchers("/api/anonymous/**").permitAll()` 在 admin 规则之前
- `AuthConstants.java` — 新增试用天数/更换间隔配置常量

### API 路径
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/anonymous/trial/start` | 开始试用（首次创建 7 天全功能记录，已有则返回状态） |
| GET | `/api/anonymous/trial/status?deviceId=xxx&fingerprintHash=xxx` | 查询试用状态 |
| POST | `/api/anonymous/trial/select-free-module` | 试用结束后选择免费模块 |
| POST | `/api/anonymous/trial/change-free-module` | 更换免费模块（每月一次） |

### 业务规则
- 首次使用创建记录：trialStartedAt=now, trialExpiresAt=now+7天
- 全功能试用期内：三个模块全部可用
- 试用过期后：必须选择一个免费模块才能使用
- 免费模块每月可更换一次（lastFreeModuleChangedAt + 30天）
- 所有匿名端点校验 fingerprintHash 一致
- 被禁用设备无法使用试用

### 关键测试用例
1. 首次设备创建 7 天试用，inFullTrial=true
2. 已有设备返回当前状态不重复创建
3. 插入已过期记录，trialExpired=true
4. 试用期过后选择 files 免费模块
5. 试用期内选择免费模块返回错误
6. 30 天后更换免费模块成功
7. 30 天内更换返回错误
8. fingerprintHash 不匹配被拒绝

---

## Task 5: 授权查询 API

### 新建文件
- `server/src/main/java/com/superprogrammer/authorization/service/AuthorizationService.java`
- `server/src/main/java/com/superprogrammer/authorization/controller/ClientAuthorizationController.java`
- `server/src/main/java/com/superprogrammer/authorization/controller/AnonymousAuthorizationController.java`
- `server/src/main/java/com/superprogrammer/authorization/dto/AuthorizationSnapshot.java`
- `server/src/main/java/com/superprogrammer/authorization/dto/AnonymousAuthorizationSnapshot.java`
- `server/src/main/java/com/superprogrammer/authorization/dto/ModuleAccess.java` — `record(String moduleCode, boolean allowed, String reason, OffsetDateTime expiresAt)`
- `server/src/test/java/com/superprogrammer/authorization/ClientAuthorizationTest.java`
- `server/src/test/java/com/superprogrammer/authorization/AnonymousAuthorizationTest.java`

### API 路径
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/client/authorization?deviceId=xxx` | 登录用户授权快照 |
| GET | `/api/anonymous/authorization?deviceId=xxx&fingerprintHash=xxx` | 匿名设备授权快照 |

### 登录用户 AuthorizationSnapshot
- `mode = "authenticated"`, userId, accountStatus, deviceLimit
- `onlineRequired` = offlineCacheMinutes == 0
- `offlineUsableUntil` = now + offlineCacheMinutes（如果 > 0）
- `modules`: 遍历三个模块，检查 entitlement enabled && 未过期
- `deviceBinding`: 查 deviceId 是否已绑定且 active

### 匿名设备 AnonymousAuthorizationSnapshot
- `mode = "anonymous"`, `onlineRequired = true`
- 全功能试用期内：三个模块全部 allowed=true
- 过期 + 已选免费模块：只有 freeModuleCode allowed=true
- 过期 + 未选：全部 allowed=false，reason="试用期已结束，请选择免费模块"
- 无试用记录：全部 allowed=false，reason="未开始试用"
- 被禁用：全部 allowed=false

### 关键测试用例
**ClientAuthorizationTest:**
1. active 用户 + 有权益 + 设备已绑定 → 正确的授权模块
2. 无权益用户 → 所有模块 allowed=false
3. 过期权益 → allowed=false
4. 设备被禁用 → 所有模块 allowed=false
5. offlineCacheMinutes > 0 → 返回 offlineUsableUntil

**AnonymousAuthorizationTest:**
1. 无试用记录 → 全部不允许
2. 全功能试用期内 → 三个模块全部 allowed
3. 过期 + 已选免费模块 → 只允许选择的
4. 过期 + 未选 → 全部不允许

---

## Task 6: SecurityConfig 收尾 + 全量验证

### 修改文件
- `SecurityConfig.java` — 确认 `/api/anonymous/**` permitAll
- `SecurityConfigTest.java` — 新增匿名端点安全测试

### SecurityConfig 最终新增规则
```java
.requestMatchers("/api/anonymous/**").permitAll()  // 在 admin 规则之前
```

### 验证
```bash
# 环境变量
JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot"
MAVEN_HOME="/c/Users/19536/.local/tools/apache-maven-3.9.11"

# Task 验证（每个 Task 完成后）
mvn -f "file-keeper/server/pom.xml" test -Dtest=<TestClassName>
mvn -f "file-keeper/server/pom.xml" test  # 全量

# 最终验证
mvn -f "file-keeper/server/pom.xml" test  # 全部测试通过
```

---

## 任务依赖与执行顺序

```
Task 1 (模块授权) ──────────┐
Task 2 (设备绑定) ──────────┼── Task 5 (授权查询) ── Task 6 (收尾)
Task 3 (管理员设置) ─────────┤
Task 4 (匿名试用) ──────────┘
```

建议顺序：**1 → 2 → 3 → 4 → 5 → 6**（Task 1-4 可并行，Task 5 依赖 1/2/4）

## 代码风格参考

沿用阶段 2 模式：
- Repository: JdbcTemplate + 手写 SQL，参考 `UserRepository.java`
- Service: `@RequiredArgsConstructor`，参考 `AdminUserService.java`
- Controller: `R<T>` 包装，`AuthPrincipal` 提取认证主体
- 审计: `AdminAuditLogService.record(adminUserId, action, targetType, targetId, detail)`
- 测试: 独立 H2 数据库名 + `@Import(TestStoreConfig.class)` + `@DirtiesContext`
- 软删除: SQL 条件加 `and deleted = 0`

## 新建文件统计

| 类别 | 数量 |
|------|------|
| Repository | 3 |
| Service | 4 |
| Controller | 6 |
| DTO | 11 |
| 测试 | 7 |
| 修改已有文件 | 6 |
