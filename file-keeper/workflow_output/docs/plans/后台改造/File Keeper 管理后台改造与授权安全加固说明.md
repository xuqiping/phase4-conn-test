# File Keeper 管理后台改造与授权安全加固说明

> 本文档汇总 File Keeper 商业授权系统管理后台改造、匿名设备防滥用加固、登录用户离线缓存安全加固的全部内容。
>
> 版本：2026-06-19

---

## 一、项目背景

阶段 4 商业授权功能完成后，进入阶段 5E 真实环境端到端联调。发现管理后台仅有“用户管理”一个页面，登录后显得空旷，且存在以下安全隐患：

1. 匿名设备可通过不断刷新 `deviceId` 无限获得 7 天全功能试用
2. 登录用户离线缓存完全依赖客户端 `Date.now()`，易被系统时间回拨绕过
3. 客户端授权状态保存在 JS 内存，易被 DevTools 篡改
4. 后台缺乏识别高频重置、同一 IP 多设备等异常行为的手段

因此决定补齐管理后台缺失页面，并对匿名设备、离线缓存两条链路进行安全加固。

---

## 二、改造范围

| 模块 | 内容 |
|---|---|
| 管理后台页面 | Dashboard 统计页、系统设置页、匿名设备运营页 |
| 匿名设备链路 | 注册频率限制、设备指纹增强、试用重置次数限制、运营异常识别 |
| 登录授权链路 | 离线授权 Token 签名、Rust 层签名校验、防时间回拨、联网时间同步异常检测 |
| 基础设施 | Flyway 数据库迁移、CORS 配置、请求日志开关 |

---

## 三、管理后台页面

### 3.1 Dashboard 统计页

- 路径：`admin-web/src/views/DashboardView.vue`
- 接口：`GET /api/admin/stats/dashboard`
- 展示内容：总用户数、待审核用户、活跃/禁用用户、在线设备、即将过期/已过期权益等

### 3.2 系统设置页

- 路径：`admin-web/src/views/SettingsView.vue`
- 接口：`GET /api/admin/settings`、`PUT /api/admin/settings`
- 可配置全局默认值：
  - `defaultDeviceLimit` — 默认设备上限
  - `defaultOfflineCacheMinutes` — 默认离线缓存分钟数
  - `anonymousTrialDays` — 匿名试用天数
  - `freeModuleChangeDays` — 免费模块更换间隔天数

### 3.3 匿名设备运营页

- 路径：`admin-web/src/views/AnonymousDevicesView.vue`
- 接口：`GET /api/admin/anonymous-devices`、`POST .../reset-trial`、`POST .../disable`、`POST .../enable`、`GET .../ip-abuse`
- 功能：
  - 匿名设备列表、分页、状态筛选
  - 按 IP 筛选
  - 高频重置筛选（≥2 / ≥3）
  - 重置试用、禁用、启用操作
  - IP 滥用统计弹窗

---

## 四、匿名设备安全加固

### 4.1 服务端注册频率限制

- 同一 **IP** 每天最多注册 **5** 个匿名设备
- 同一 **fingerprintHash** 每天最多注册 **3** 次
- 使用 Redis 计数，超限返回 `429 Too Many Requests`

相关文件：
- `server/.../device/service/AnonymousTrialRateLimiter.java`
- `server/.../device/service/AnonymousTrialService.java`

### 4.2 设备指纹增强

- 服务端记录首次注册来源 IP 和 User-Agent 哈希
- 客户端 `fingerprintHash` 基于 UA / platform / hardwareConcurrency / deviceMemory / 屏幕特征计算，不再随机生成
- 客户端双存储：Tauri Store（主）+ localStorage（备份）

相关文件：
- `server/.../device/repository/AnonymousTrialRepository.java`
- `file-keeper/src/api/commercialAuth.ts`

### 4.3 试用重置次数限制

- 单个匿名设备最多被管理员重置 **3** 次
- 超过后后台按钮禁用，接口返回 `422`
- 重置次数写入 `anonymous_device_trials.trial_reset_count`

相关文件：
- `server/.../admin/service/AdminAnonymousDeviceService.java`
- `admin-web/src/views/AnonymousDevicesView.vue`

### 4.4 运营异常识别

- 后台列表展示来源 IP、重置次数
- 支持按 IP 筛选
- 支持“高频重置”快速筛选
- IP 滥用统计展示同一 IP 下设备数

---

## 五、登录用户离线缓存安全加固

### 5.1 服务端签发离线授权 Token

`AuthorizationService.authenticatedSnapshot()` 为登录用户生成 HMAC-SHA256 签名 token：

```
base64url(userId|deviceId|offlineUsableUntilEpochMilli|allowedModules|signature)
```

- 服务端用 `file-keeper.auth.jwt.secret` 签名
- 客户端无法伪造或篡改允许模块与过期时间

相关文件：
- `server/.../authorization/service/OfflineTokenSigner.java`
- `server/.../authorization/service/AuthorizationService.java`

### 5.2 Rust 层签名校验 + 防时间回拨

新增 `src-tauri/src/commands/auth.rs`：

- `set_offline_token(token, offline_seconds)` — 保存 token 并记录 `Instant::now()`（单调时钟）
- `check_offline_access(module_code)` — 验证签名 + 用单调时钟检查是否过期 + 校验模块
- `clear_offline_token()` — 清除缓存

由于使用 `Instant::now()` 计算经过时间，**回拨系统时间无法延长离线缓存**。

### 5.3 前端集成

- 获取/刷新授权后调用 `set_offline_token` 交给 Rust
- 离线模式下 `isModuleAllowed()` 优先使用 Rust 校验结果
- 授权失败或刷新时调用 `clear_offline_token`

相关文件：
- `file-keeper/src/stores/commercialAuthStore.ts`

### 5.4 联网时间同步异常检测

- 桌面端每次请求 `/api/client/authorization` 附带 `clientTimestamp`
- 服务端比较客户端时间与服务器时间，偏差超过 **5 分钟** 时 `user_devices.time_sync_anomaly_count` +1
- 管理后台“用户详情 → 设备列表”显示时间异常次数

相关文件：
- `server/.../authorization/controller/ClientAuthorizationController.java`
- `server/.../device/repository/DeviceRepository.java`
- `admin-web/src/views/UserDetailView.vue`

---

## 六、数据库迁移

| 迁移文件 | 内容 |
|---|---|
| `V2__widen_device_name.sql` | `user_devices` 和 `anonymous_device_trials` 的 `device_name` 放宽到 `VARCHAR(255)` |
| `V3__harden_anonymous_trial.sql` | 匿名设备表新增 `first_seen_ip`、`user_agent_hash`、`trial_reset_count` |
| `V4__device_time_sync_anomaly.sql` | 用户设备表新增 `time_sync_anomaly_count` |

---

## 七、测试验证

| 测试项 | 结果 |
|---|---|
| 后端 `mvn test` | 51 tests passed ✅ |
| 桌面端 `npm test` | 212 tests passed ✅ |
| 桌面端 `npm run build` | ✅ |
| 管理后台 `npm run build` | ✅ |
| 后端匿名频率限制 | 同 IP 5 次、同指纹 3 次后返回 429 ✅ |
| 后端匿名重置限制 | 第 4 次返回 422 ✅ |
| 后端离线 Token 生成 | API 返回带签名 token ✅ |
| 后端时间同步异常检测 | 偏差 5 分钟以上计数递增 ✅ |
| 管理后台异常标记 | 设备列表正确显示时间异常次数 ✅ |

---

## 八、配置说明

### 8.1 后端环境变量

```bash
FILE_KEEPER_DB_URL=jdbc:postgresql://localhost:5432/file_keeper
FILE_KEEPER_DB_USERNAME=postgres
FILE_KEEPER_DB_PASSWORD=postgres
FILE_KEEPER_REDIS_HOST=localhost
FILE_KEEPER_REDIS_PORT=6379
FILE_KEEPER_JWT_SECRET=your-secure-jwt-secret-at-least-32-bytes
FILE_KEEPER_SUPER_ADMIN_EMAIL=admin@filekeeper.local
FILE_KEEPER_SUPER_ADMIN_PASSWORD=YourSecurePassword
FILE_KEEPER_VERIFICATION_DEV_FIXED_CODE=123456
```

### 8.2 请求日志开关

```yaml
file-keeper:
  request-logging:
    enabled: true   # 生产环境建议 false
```

---

## 九、已知限制与后续建议

### 9.1 已知限制

1. **离线缓存的固有矛盾**：允许断网使用 = 用户/设备被禁用后，已离线客户端在缓存期内仍可继续使用
2. **Rust secret 可提取**：桌面应用代码在用户机器上，技术用户可能从二进制中提取 JWT secret
3. **VM / 代理刷量**：虚拟机 + 代理 IP 仍可绕过部分匿名设备限制

### 9.2 后续建议

1. 敏感操作强制联网校验（文件删除、批量导出、修改设置）
2. 设备上线心跳：恢复网络后 5 秒内刷新授权
3. 接入行为风控与设备信誉分
4. 对高价值客户考虑硬件绑定或云端实时授权

---

## 十、相关文件清单

### 后端
- `server/.../authorization/controller/ClientAuthorizationController.java`
- `server/.../authorization/service/AuthorizationService.java`
- `server/.../authorization/service/OfflineTokenSigner.java`
- `server/.../device/service/AnonymousTrialRateLimiter.java`
- `server/.../device/service/AnonymousTrialService.java`
- `server/.../device/repository/AnonymousTrialRepository.java`
- `server/.../device/repository/AnonymousDeviceAdminRepository.java`
- `server/.../device/repository/DeviceRepository.java`
- `server/.../device/dto/AnonymousDeviceDto.java`
- `server/.../device/dto/DeviceDto.java`
- `server/.../admin/service/AdminAnonymousDeviceService.java`
- `server/.../admin/controller/AdminAnonymousDeviceController.java`
- `server/.../admin/controller/AdminDeviceController.java`
- `server/.../admin/controller/AdminStatsController.java`
- `server/.../admin/controller/AdminSettingsController.java`
- `server/.../security/RequestLoggingFilter.java`
- `server/.../security/SecurityConfig.java`
- `server/.../common/GlobalExceptionHandler.java`
- `server/.../common/ErrorCode.java`
- `server/.../db/migration/V2__widen_device_name.sql`
- `server/.../db/migration/V3__harden_anonymous_trial.sql`
- `server/.../db/migration/V4__device_time_sync_anomaly.sql`

### 桌面端
- `file-keeper/src/api/commercialAuth.ts`
- `file-keeper/src/api/auth.ts`
- `file-keeper/src/stores/commercialAuthStore.ts`
- `file-keeper/src/stores/authStore.ts`
- `file-keeper/src-tauri/src/commands/auth.rs`
- `file-keeper/src-tauri/src/commands/mod.rs`
- `file-keeper/src-tauri/src/main.rs`
- `file-keeper/src-tauri/Cargo.toml`
- `file-keeper/src/components/AuthDialog.vue`
- `file-keeper/src/components/EntitlementStatus.vue`
- `file-keeper/src/components/FreeModuleSelector.vue`
- `file-keeper/src/components/LoginForm.vue`
- `file-keeper/src/components/RegisterForm.vue`

### 管理后台
- `admin-web/src/views/AnonymousDevicesView.vue`
- `admin-web/src/views/DashboardView.vue`
- `admin-web/src/views/SettingsView.vue`
- `admin-web/src/views/UserDetailView.vue`
- `admin-web/src/views/UserListView.vue`
- `admin-web/src/views/Layout.vue`
- `admin-web/src/api/anonymousDevices.ts`
- `admin-web/src/api/settings.ts`
- `admin-web/src/api/stats.ts`
- `admin-web/src/api/devices.ts`
- `admin-web/src/types/index.ts`
- `admin-web/src/router/index.ts`

### 测试
- `server/src/test/java/com/superprogrammer/admin/AdminSettingsTest.java`
- `server/src/test/java/com/superprogrammer/device/AnonymousTrialTest.java`
- `server/src/test/java/com/superprogrammer/user/UserRegistrationTest.java`
- `file-keeper/src/api/__tests__/commercialAuth.test.ts`
- `file-keeper/src/api/__tests__/auth.test.ts`
- `file-keeper/src/stores/__tests__/commercialAuthStore.test.ts`
- `file-keeper/src/stores/__tests__/authStore.test.ts`
- `file-keeper/src/components/__tests__/authDialog.test.ts`
- `file-keeper/src/components/__tests__/entitlementStatus.test.ts`
- `file-keeper/src/components/__tests__/freeModuleSelector.test.ts`

### 文档
- `项目相关文档/后台改造计划/00-总览与进度.md`
- `项目相关文档/后台改造计划/01-Dashboard统计页.md`
- `项目相关文档/后台改造计划/02-系统设置页.md`
- `项目相关文档/后台改造计划/03-匿名设备运营页.md`
- `项目相关文档/后台改造计划/04-联调阶段已修复的Bug.md`
- `项目相关文档/后台改造计划/匿名、离线缓存风险与应对措施.md`
- `项目相关文档/后台改造计划/File Keeper 管理后台改造与授权安全加固说明.md`
