# 钉钉 H5 微应用免登接入 — 总路由

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement, phase-by-phase. 每 phase 文件用 `- [ ]` 复选框跟踪。

**Goal：** 用户在钉钉手机端 / PC 端打开 H5 微应用免登进入本平台，钉钉授权码换本平台 JWT，复用现有认证体系与全部业务功能。

**Architecture：** 钉钉新版 OAuth 免登。前端检测钉钉容器 → 重定向钉钉授权页拿 `authCode` → 回前端回调页 → POST `authCode` 到后端 → `DingTalkService` 换用户 `accessToken` 再拉 `unionId/nick/avatar` → 按 `unionId` 查/建本地用户（无密码，`bind_type=dingtalk`）→ `AuthService` 签标准 JWT → 前端存 token 进 `stores/auth.ts` → 之后所有请求走现有 Axios 拦截器（HTTP + WebSocket 全复用，零业务改动）。

**Tech Stack：** Spring Boot 3.2.5 / Java 17 / WebFlux `WebClient`（pom 已有）/ MyBatis-Plus / Flyway / JJWT / OkHttp `MockWebServer`（test，pom 已有）/ Vue 3 + TS + Pinia。

---

## 进度总览

| # | Phase | 状态 | 关键产出 |
|---|-------|------|---------|
| 1 | [用户表加钉钉绑定字段](phase-01-user-table-fields.md) | `- [x]` | V41 迁移 + User 字段 |
| 2 | [钉钉配置属性](phase-02-dingtalk-properties.md) | `- [x]` | `DingTalkProperties` + yml |
| 3 | [DingTalkService 换码拉用户](phase-03-dingtalk-service.md) | `- [x]` | `exchangeUser(authCode)` + MockWebServer 测 |
| 4 | [AuthService.loginByDingTalk](phase-04-auth-service-dingtalk.md) | `- [x]` | unionId 查/建号 + 签 JWT |
| 5 | [免登端点 + 白名单](phase-05-endpoint-whitelist.md) | `- [x]` | `POST /api/auth/login/dingtalk` |
| 6 | [前端 UA 判定 + 授权重定向](phase-06-frontend-ua-redirect.md) | `- [x]` | `utils/dingtalk.ts` + `dingTalkLogin` API |
| 7 | [前端回调页 + 路由 + 入口](phase-07-frontend-callback-route.md) | `- [x]` | `/dingtalk/callback` + store action |
| 8 | [文档 + 钉钉平台配置清单](phase-08-docs-and-platform-config.md) | `- [x]` | 速查表 01 钉钉章 |

**当前进度：8 / 8。**

---

## 执行顺序（严格串行）

Phase 依赖链：1 → 2 → 3 → 4 → 5（后端链路）→ 6 → 7（前端链路）→ 8（收尾）。

- Phase 3 依赖 2（`DingTalkProperties`）；Phase 4 依赖 1（User 字段）+ 3（`DingTalkUserInfo`）；Phase 5 依赖 3+4。
- Phase 6/7 依赖 5（端点路径）。
- 每 phase 末尾 `git commit`，失败不进下一 phase。

---

## Global Constraints（每 phase 隐含遵守）

- 包名 `com.superprogrammer.auth.dingtalk.*`（新增子包）。
- 实体继承 `BaseEntity`；响应统一 `R<T>`；业务异常抛 `BusinessException(ErrorCode)`。
- PostgreSQL + Flyway，下一迁移号 **V41**（当前最新 V40）。
- 逻辑删除 `deleted`+`@TableLogic`；自动填充 `created_by/at/updated_by/at`。
- 前端 Naive UI 暗色主题，API 走 `src/api/request.ts`，token 持久化 `src/utils/storage.ts`。
- 钉钉密钥入 `application.yml`（生产走环境变量），不入库。
- 用钉钉**新版 API**（`api.dingtalk.com`，2021+），`unionId` 做绑定主键，`openId` 仅记录。

---

## 风险与边界

- **企业账号依赖：** 联调需钉钉企业管理员 + 已审批 H5 微应用。开发期 Phase 3 的 MockWebServer 测试不依赖真实账号。
- **JWT 与现有体系零冲突：** 钉钉用户落 `users` 表后等价账密用户，`@RequirePermission`、角色、所有业务 API、WebSocket 全复用。后端 HTTP 鉴权只认 `Authorization: Bearer` 头，WS 读 `?token=` 或该头 —— 钉钉 webview 无影响。
- **unionId 唯一性：** 同一钉钉开发者账号下跨应用 unionId 一致；将来接多钉钉租户需加 `tenant_id` 维度（本计划单租户）。
- **旧账密登录不受影响：** Phase 4 仅重构 `login` 末尾为 `issueTokens`，行为等价，跑既有 Auth 测试防回归。

---

## 执行方式

二选一：
1. **Subagent 驱动（推荐）** — 每 phase 派新 subagent，phase 间 review。
2. **会话内执行** — 按 superpowers:executing-plans 批量跑，带检查点。
