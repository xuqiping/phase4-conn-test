# 密码学与 token 存储审计（安全体系 S5 · SEC-FR-074/007）

> 审计日：2026-08-15。范围：后端密码学用法（AES/HMAC/哈希/随机数）+ 前端 token 存储 + sourcemap。
> 结论先行：**大面健康**，1 处代码修复（AES 默认密钥 fail-fast，已落地），其余为「记录在案 + 后续可选」。

## 1. 逐项核查

| # | 项 | 现状 | 结论 |
|---|---|---|---|
| 1 | AES/GCM IV | `AesEncryptService`：每次加密 `SecureRandom` 生成 12B 随机 IV，前缀拼接存储，TAG 128bit | ✅ 符合 NIST SP 800-38D（随机 IV + 不重用） |
| 2 | SecureRandom | `AesEncryptService`/`TotpService` 均为服务单例字段持有，非每次 new | ✅ 单例无种子重置风险 |
| 3 | HS256 密钥类型绑定 | `JwtUtil.getSigningKey` 从 base64 解码构造 SecretKeySpec；只签 HS256，验证侧同算法——无 alg 混淆面（换算法需改代码非 token 声明） | ✅ |
| 4 | JWT 弱默认密钥 | 安全审计 #2 已 fail-fast（KNOWN_WEAK 命中拒启动） | ✅ 已闭环（注意：泄露密钥轮换=生产窗口，见运维手册密钥轮换 runbook） |
| 5 | **AES 默认密钥** | 原内置默认值 `default-secret-key-change-in-production!!` 随 git 公开，无强制更换 | ❌→✅ **本步修复**：生产态（`app.cors.allowed-origins` 非空）默认值/长度<16 → 拒启动；dev 放行 WARN |
| 6 | BCrypt cost | `PasswordEncoder` cost=10（约 60-100ms/次） | ⚠️ 记录：OWASP 下限（10-12 可接受区间）。**不改**——升 cost=12 慢 4 倍，登录高峰期体验受损；现有登录限流+封禁已压爆破面。后续算力升级再调 |
| 7 | MD5 使用面 | 仅 SQL 变更探测（schema diff 指纹）等非安全场景 | ✅ 无安全场景误用 |
| 8 | HMAC 用法 | S5 F2 回调签名（SHA256+恒定时间比对）、TOTP（RFC6238 HmacSHA1 官方要求）、审计链 HMAC | ✅ |
| 9 | 恒定时间比较 | JWT token 黑名单等值比较仍用 equals——但比较对象是 Redis 返回值与 UUID（jti），非密钥材料，无时序探密价值 | ✅ 可接受 |
| 10 | TOTP secret 存储 | AES 加密入 system_settings（`security.totp.secret.u.{uid}`，不在 EDITABLE_KEYS=管理端不可读他人 secret） | ✅ |
| 11 | 恢复码存储 | 8 组 SHA-256 只存哈希，明文仅绑定确认时一次回显 | ✅ |

## 2. A7 token 存储评估（localStorage vs HttpOnly cookie）

**结论：维持 localStorage 现状，不改 HttpOnly。** 依据：

1. **威胁建模**：token 被偷的前提是 XSS 已成立。改 HttpOnly 只是把「XSS 能读 token」降级为「XSS 能带凭证发请求」——攻击者照样能以受害者身份调 API，只是少了离线转卖能力。
2. **XSS 面已压**：C2 门禁（DOMPurify 全量消毒不可信 HTML）+ 联网搜索/KB 正文注入前 `SanitizeUtil` 清洗 + 输出打码开关 + CSP 响应头。残留面主要是依赖供应链（I5 门禁本步同系列补齐）。
3. **改 HttpOnly 的代价**：引 CSRF 面（cookie 自动携带→须补 CSRF token 全链改造）；跨子域部署/钉钉 H5 嵌入场景 cookie 域配置复杂化；前端登出/刷新逻辑重写。重构面 >> 收益。
4. **已对冲**：access 15min 短命 + refresh 旋转（S5 Step1，用后即废）+ logout/旋转双拉黑——token 被偷的可用窗口已被压到分钟级。

**后续可选**（不排期）：若未来出现实际 XSS 事件复盘，再评估 HttpOnly+BFF 架构。

## 3. F1 sourcemap 核查

`frontend/vite.config.ts` 无 sourcemap 配置 → Vite 生产构建**默认不产出** sourcemap（仅开发模式内联）。grep 全前端配置无 `build.sourcemap` 显式开启。✅ 无源码泄露面。运维红线：若未来开 sourcemap 排障，产物只进内网，不入公开 CDN。

## 4. 残留风险清单（接受/后续）

| 风险 | 决策 |
|---|---|
| BCrypt cost=10 | 接受（下限内），记录待算力升级 |
| localStorage 存 token | 接受（依据见 §2），XSS 面由 C2/CSP/旋转对冲 |
| AES 密钥轮换需重录密文 | 运维事项：`LLM_ENCRYPTION_SECRET` 换值后存量 api_key_enc 需重录（换密钥流程入密钥轮换 runbook 执行窗口） |
| 密钥泄露事件历史（commit bbccdcd8） | 轮换=生产窗口，用户已定；本审计不触发动作 |

## 5. 引用位置

- 代码修复：`backend/.../llm/service/AesEncryptService.java`（@PostConstruct validateSecret）
- 测试：`AesEncryptServiceTest` +4（生产默认拒/短密钥拒/强密钥过/dev 放行）
- 关联 plan：[安全体系_S5检测响应与收尾.plan.md](../plans/安全体系_S5检测响应与收尾.plan.md) Step5
