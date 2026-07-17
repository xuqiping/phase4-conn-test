# Phase 4 运行验证记录 · 认证与 token 刷新修复

> 验证对象：> 1. `work-report` 模块未登录时周期性报「未登录」bug 修复> 2. 后端 `UserAuthService` 硬编码 `expiresInSeconds` 导致前端每 15 分钟强制刷新 token 的修复> 3. 超管 access token 1 分钟过期 + refresh 机制验证

## 验证环境

| 项目 | 版本/说明 |
|---|---|
| OS | Windows 11 Pro |
| Java | OpenJDK 17.0.19 |
| Node.js | 18+（已安装） |
| Maven | 已安装 |
| PostgreSQL | **当前环境未安装/未启动** |
| Redis | **当前环境未安装/未启动** |

> 说明：本次验证**未能在当前环境启动真实 PostgreSQL + Redis + 桌面客户端**，因此采用「后端集成测试 + 前端单元测试」组合验证核心逻辑；真实端到端验证需在本地补齐依赖后按「快速启动速查表」执行。

## 一、Run（运行与自检）

### 1.1 后端集成测试

#### 1.1.1 超管 1 分钟 token 过期 + refresh 验证

测试类：`server/src/test/java/com/superprogrammer/admin/AdminTokenRefreshVerificationTest.java`

**验证场景 A：登录后立即 refresh**
- 登录超管（`adm@example.com` / `adm123`），断言 `expiresInSeconds = 60`
- 解析 access token JWT，断言实际过期时间为 60 秒
- 用 refresh token 调用 `/api/admin/auth/refresh`，断言返回新 access token 且 `expiresInSeconds = 60`
- 用新 access token 访问 `/api/admin/users`，断言 200

**验证场景 B：等待 61 秒后 refresh**
- 登录超管，获取 refresh token
- `Thread.sleep(61_000)` 让 access token 真正过期
- 用 refresh token 刷新，断言成功并返回新 token
- 用新 token 访问 admin 接口，断言 200

**测试结果**：
```
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

#### 1.1.2 普通用户与超管 `expiresInSeconds` 正确性验证

- `ClientAuthorizationTest#clientLoginReturns24HourAccessTokenExpiration`：普通用户登录返回 86400 秒（24 小时）
- `AdminAuthControllerTest#superAdminCanLoginAndRefresh`：超管登录返回 900 秒（15 分钟）

**测试结果**：
```
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### 1.2 前端单元测试

验证 `work-report` 未登录时不触发认证 API 的修复：

```bash
npm run test -- src/stores/__tests__/workReportStore.test.ts src/api/__tests__/workReport.test.ts src/components/__tests__/authDialog.test.ts src/stores/__tests__/authStore.test.ts
```

**测试结果**：
```
Test Files  4 passed (4)
     Tests  50 passed (50)
```

### 1.3 真实服务启动尝试

- 检查 PostgreSQL 端口 5432：**未运行**
- 检查 Redis 端口 6379：**未运行**
- 检查 Docker：**未安装**
- 检查 WSL：**未启用**
- 结论：**当前环境无法启动完整后端 + 桌面客户端做端到端验证**。

## 二、Review（交叉审查）

### 2.1 对照 Feature Map

- 认证与商业授权 Feature Map（`06-认证与商业授权.feature-map.md`）中描述的登录、refresh、设备绑定、授权快照链路与实际代码一致。
- 新增修复点：
  - `UserAuthService.createAuthResponse` 按角色返回 `expiresInSeconds`
  - `InboxPanel.vue` / `WorkReportManagement.vue` 增加 `authStore.isAuthenticated` guard

### 2.2 代码审查结论

- `UserAuthService` 已注入 `AuthProperties`，按角色返回正确过期时间，逻辑正确。
- 前端 guard 直接读取 Pinia store 的 `isAuthenticated`（已被解包为 boolean），使用正确。
- 潜在风险：H2 内存数据库测试中 `FuturePlanRepository.findPendingReminders` 使用了 PostgreSQL 特有函数 `make_interval`，导致后台定时任务报错。该问题**仅影响 H2 测试环境**，真实 PostgreSQL 环境无此问题。

## 三、性能评测

未执行。原因：当前环境无法启动真实服务，无运行中的实例可测量接口响应时间和并发表现。建议在真实环境中补齐：
- 登录/refresh 接口 p50/p95/p99
- 授权快照查询接口响应时间
- 多并发登录场景

## 四、遗留问题与建议

| 问题 | 影响 | 建议 |
|---|---|---|
| H2 不兼容 `make_interval` | 仅影响单元测试后台定时任务日志报错 | 生产使用 PostgreSQL，无需处理；如需在 H2 跑完整测试，需改写 SQL 或加 H2 兼容函数 |
| 无法启动真实服务 | 缺少端到端截图/录屏证据 | 在本地补齐 PostgreSQL + Redis 后按「快速启动速查表」执行 |

## 五、出口判定

- [x] 后端集成测试通过，核心逻辑已验证
- [x] 前端单元测试通过
- [ ] 真实应用跑通（因环境缺失，未完成）
- [x] 快速启动速查表已产出
- [ ] 性能评测（因环境缺失，未完成）
- [x] 修复已 commit

**结论**：代码层面修复验证通过。由于当前环境缺少 PostgreSQL 和 Redis，未能完成 Phase 4 要求的真实端到端运行验证和性能评测，建议在具备完整依赖的环境补做。

## 相关 Commit

- `dc61625` `fix: 客户端 access token 过期时间按角色返回正确值，避免前端每15分钟强制刷新`
- `083b8dd` `docs: 记录客户端 access token 过期时间修复`
- `74b241b` `fix: 未登录时工作助手模块避免周期性调用认证接口报错`
- （本次新增测试与验证文档待提交）
