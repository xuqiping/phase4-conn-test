# 认证系统增强 · 功能 README

> Phase 3 收尾产物。功能全景 + 用户地图 + 技术说明。
> 详细代码速查：[`docs/feature-map/认证系统增强.feature-map.md`](../../docs/feature-map/认证系统增强.feature-map.md)
> 用户操作手册：[`docs/user-ops/认证系统增强用户操作手册.md`](../../docs/user-ops/认证系统增强用户操作手册.md)
> 测试方案：[`docs/测试方案/认证系统增强测试方案.md`](../../docs/测试方案/认证系统增强测试方案.md)

## 功能一句话

把单卡片账密登录改成「账密 / 手机验证码 / 微信扫码」三 Tab 多通道，新增邮箱验证注册、找回密码（邮件+短信）、异地登录提醒、安全设置页（绑/解绑凭证 + 改密），底层 user_credential 多凭证账号模型支撑。

## 用户地图（谁用 / 什么场景 / 什么效益）

| 用户 | 场景 | 效益 |
|---|---|---|
| 普通用户 | 注册 | 填邮箱即可注册并立即登录；邮箱验证后可找回密码（防垃圾注册） |
| 普通用户 | 手机登录 | 输手机号收码即登录，新号自动注册（免记密码） |
| 普通用户 | 微信登录 | 扫码即登录（免输密码，管理员开启后） |
| 普通用户 | 忘记密码 | 邮箱链接 / 手机短信两种找回，重置后旧设备全踢下线 |
| 普通用户 | 安全设置 | 自助绑/解绑邮箱、改密、查看登录方式；改密后强制重登 |
| 普通用户 | 异地登录 | 账号在异省登录时收提醒邮件（防盗号） |
| 管理员 | 通道开关 | 在系统设置页动态开/关邮件/短信通道，配 AK/SK，无需重启 |

## 实现的 7 个 Chunk

| Chunk | 内容 | commit |
|---|---|---|
| A | 凭证表 user_credential + DB 迁移 V102 + CredentialService 基础 | bac665ca |
| B | 通道A 邮箱验证注册（EmailService + 注册改造 + 凭证集成） | 9e27e327 |
| C | 通道B 手机验证码登录 + 滑块验证码 | 57bbaee9 |
| D | 通道C 微信扫码登录（WxJava 4.8.0） | 07ee4d89 |
| E | 找回密码（邮件+短信）+ 异地登录提醒 | 5f971880 |
| F | 前端 LoginView 重构（Tab 多通道 + 滑块 + 落地页） | 27d75f07 |
| G | 安全设置页（绑/解绑邮箱 + 改密）+ 修重置密码踢会话 bug | f52ea1ca |

## 技术说明（简要）

- **多凭证账号模型**：user_credential 表一人 N 条凭证（密码/邮箱/手机/微信），任一 verified=TRUE 可登录。解绑至少留一种防失联。
- **AJ-Captcha 滑块**：BLOCKPuzzle + redis 缓存（单次有效）；前端 crypto-js AES-ECB 加密轨迹。
- **单点登录 + 踢会话**：SessionService 管 `session:user:{userId}`；clearSession(sid 比对) vs kickAllSessions(无条件删) 两套语义。
- **防枚举**：找回密码/重发邮件统一话术；user==null 跑 dummy bcrypt 抹时序侧信道。
- **token fragment 传递**：微信回调 JWT 放 URL # 后（不进 log/referer）。
- **通道动态配置**：AuthChannelSettingService DB 覆盖→环境变量兜底；密钥 AES 加密不入库明文。

## 安全检查清单（逐项已落地）

- [x] 密钥零入库（AppSecret/AK/SK 全环境变量）
- [x] 审计 detail 只带 reason 码，严禁密码/token 原文
- [x] 重置/激活 token SecureRandom 32 字节 + Base64URL + 用完即删
- [x] 统一话术防账号枚举（login/forgot/sms）
- [x] user==null 跑 dummy bcrypt 抹时序侧信道
- [x] XFF 默认不可信（复用 currentClientIp）
- [x] 解绑至少留一种 + 改密验旧密码 + 踢会话
- [x] 滑块前端结果不可信（后端发码时复验 captchaToken）
- [x] 公开端点两处同步登记（SecurityConfig + SecurityEndpointRegistry）
- [x] /api/me/** userId 取自 SecurityContext（无入参旁路）

## 部署前置（管理员）

| 项 | 环境变量 | 说明 |
|---|---|---|
| 邮件通道 | `APP_AUTH_EMAIL_ENABLED` / `ALIYUN_DM_AK`/`SK` | 阿里云 DirectMail；配后注册发激活邮件、找回发重置邮件 |
| 短信通道 | `APP_AUTH_SMS_ENABLED` / `ALIYUN_SMS_AK`/`SK` | 阿里云 SMS；配后手机验证码登录/找回可用 |
| 微信通道 | `APP_AUTH_WECHAT_ENABLED` / `APP_WECHAT_APP_SECRET` | 微信开放平台网站应用；审核 1-7 天，不阻塞其他通道 |
| ip2region | 放置 `backend/src/main/resources/ip2region.xdb`（~11MB，不入仓库） | 异地登录提醒地域识别依赖；未放则该功能降级 |

## Phase 2 TODO（已记录，不阻塞上线）

- 绑手机（需独立 `sms:bind:code:` 验证码回路）
- 绑微信（需 state 绑定已登录 userId 的 OAuth 回路）
- PasswordResetService 短信重置码完整校验
- 账密登录失败≥3 次条件滑块（后端 captchaRequired 返回值）
