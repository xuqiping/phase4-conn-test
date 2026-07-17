# 安全策略（Security Strategy）

> 类型：Phase 1 真相源（specs）
> 读者：开发、AI
> 定位：鉴权 / 加密 / 授权 / 设备绑定 / 漏洞管理的「唯一真相源」。架构与代码须与此一致；不一致处以本文件为准并回写。
> 维护规则：安全体系发生根本变化或审查发现根因级缺陷时更新；零散修复走 [../changes/变更记录.md](../changes/变更记录.md)。

---

## 1. 总体目标

File Keeper 是**本地优先（local-first）的桌面应用 + 商业授权**产品。安全边界有三层：

1. **服务端（Spring Boot + PostgreSQL + Redis）**：身份认证、授权签发、设备管理 —— 强可信区。
2. **桌面客户端（Tauri：Rust + Vue）**：跑在用户自有机器上 —— **不可信区**，假定攻击者可读二进制、改本地文件、改系统环境与时间、直接调用 Tauri 命令。
3. **离线态**：无网络时客户端须能自证授权有效性 —— 密码学选型必须假设客户端不可信。

> ⚠️ **铁律**：任何「只在客户端做的权限判定」都不是真正的门禁。真正的门禁必须在**不可信客户端上用密码学自证（非对称签名 / 公钥验签）**，或**在服务端在线复核**。

---

## 2. 身份认证（JWT）

- access token 15 分钟、refresh token 7 天（[application.yml:38-44](../../../server/src/main/resources/application.yml)）。
- JWT secret 走环境变量 `FILE_KEEPER_JWT_SECRET`。
- 客户端 Axios 实例自动注入 Bearer + 401 自动刷新（[api/request.ts](../../../src/api/auth.ts)）。
- 注册需验证码 + 管理员审核（`pending_review` 才获商业授权）。

---

## 3. 授权体系（在线）

- 登录后设备注册 → 查询授权快照（`/api/client/authorization`）→ 快照含 `mode / modules[] / deviceBinding / onlineRequired / offlineUsableUntil / offlineToken`。
- 模块授权用 `moduleCode` 体系（files / processes / clipboard / work-report / ai …），服务端是授权真相源。
- 匿名试用：新设备 7 天全功能 → 过期选 1 个免费模块长期使用，30 天可换一次（服务端限流）。

---

## 4. 离线授权（重点 / 已知根因级缺陷）

### 4.1 现状（截至 2026-07-16）
- 服务端用 `OfflineTokenSigner`（HmacSHA256）签 token；客户端 `auth.rs` 用同一 secret 验签。
- token payload：`userId|deviceId|offlineUsableUntilEpochMilli|allowedModules|hmac`。
- 断网时若离线缓存未过期，客户端凭缓存 token 继续放行已授权模块。

### 4.2 已识别风险（详见 [../review/认证与商业授权-安全审查记录.md](../review/认证与商业授权-安全审查记录.md)）

| ID | 级别 | 缺陷 | 状态 |
|----|------|------|------|
| P0-1 | 根本 | 对称签名密钥必然驻留客户端 → 可伪造任意 token | ✅ 已修（Ed25519 非对称签名，客户端只内嵌公钥） |
| P0-2 | 根本 | JWT secret 默认兜底硬编码 / 服务端默认空串 | ✅ 已修（授权凭据私钥 `FILE_KEEPER_ENTITLEMENT_PRIVATE_KEY` 缺失时服务端拒绝启动） |
| P1-1 | 高 | token 绝对过期时间被解析但未校验 → 可无限续期 | ✅ 已修（Rust 侧强校验 `now > notAfter`，无外部 duration 续期路径） |
| P1-2 | 高 | 特权命令（杀进程/截图/剪贴板）Rust 侧无二次校验 | ✅ 已修（所有特权 Tauri 命令入口调用 `require_module`） |
| P2-1 | 中 | 设备身份全客户端生成 → 可刷试用 / 绕设备上限 | 📋 待排期 |
| P2-2 | 中 | 过期依赖 JS `Date.now()`，时钟可回拨 | 📋 待排期（当前关键过期判定已下沉 Rust `SystemTime`） |

### 4.3 整改方向（本文件承诺的目标态）
1. **离线 token 改非对称签名（Ed25519 / ES256 / RSA-PSS）**：服务端私钥签、客户端内嵌公钥验签。客户端不再持有任何 secret。→ 解决 P0-1 / P0-2。
2. **token 内绝对 `not_after` 必须被校验**，且签名覆盖有效期；客户端不允许把 duration 作为外部参数喂入。→ 解决 P1-1。
3. **所有特权 Tauri 命令在 Rust 侧强制 `is_module_allowed` 拦截**（对齐 work-report 现有范例）。→ 解决 P1-2。
4. 关键过期判定下沉 Rust 单调时钟；高敏操作能用网时强制服务端在线复核。

> 实施步骤见 [../plans/安全加固-离线授权与门禁.plan.md](../plans/安全加固-离线授权与门禁.plan.md)。

### 4.4 能力上限声明（必须如实告知决策者）
即便完成上述整改，**纯软件无法 100% 防止「patch 二进制跳过校验」**（攻击者改 `is_module_allowed` 恒返回 true）。非对称签名只让「伪造 token」不可行，并未消除「改代码跳检查」。故离线授权应定位为**延缓型防护**，真正的强保护依赖**服务端在线复核 + 商业策略（如核心数据在服务端）**。

---

## 5. 设备绑定

- `deviceId`（UUID）+ `fingerprintHash`（环境特征），持久化 `file-keeper-auth.json`。
- 登录后服务端检查设备数 ≤ `deviceLimit`，超限 409；管理员可禁用设备。
- **已知薄弱（P2-1）**：身份全客户端生成可篡改 → 待改服务端签发。

---

## 6. 数据与加密

- 业务数据（收藏、剪贴板历史、截图）默认本地存储，不上云。
- 敏感凭据（如推送 token、第三方密钥）经 `.secrets` + `.gitignore` 管理，不入库。
- 传输：服务端 HTTPS（部署层）；客户端 JWT Bearer。

---

## 7. 漏洞管理流程

1. 发现 → 记入 [../review/](../review/) 审查记录（结论 + 修复清单 + 代码定位）。
2. 评级（P0/P1/P2）→ 影响本文件「已知风险表」与整改承诺。
3. 修复计划落 [../plans/](../plans/)；实施后更新本表状态 + 变更记录。
4. 高危以上：修复前评估是否需临时下线 / 收紧服务端策略兜底。

---

## 8. 变更记录

| 日期 | 变更 | 原因 |
|---|---|---|
| 2026-07-16 | 创建本文件；记录离线授权 P0/P1/P2 风险与整改承诺 | Phase 1 真相源补齐 + 安全审查 [认证与商业授权-安全审查记录.md](../review/认证与商业授权-安全审查记录.md) 发现根因级缺陷 |
| 2026-07-17 | 完成安全加固 Chunk 1~4：离线授权改 Ed25519 非对称签名、Rust 强校验 `notAfter`、特权命令加 `require_module` 门禁、旧 HMAC token 兼容、跨语言一致性测试 | 安全加固计划 [安全加固-离线授权与门禁.plan.md](../plans/安全加固-离线授权与门禁.plan.md) 实施完成 |
