# Phase 8 — 文档 + 钉钉平台配置清单

> 总路由：[README.md](README.md) · 上一：[Phase 7](phase-07-frontend-callback-route.md) · 下一：—（收尾）

**Goal：** 速查表 01 增钉钉章节；钉钉开放平台配置清单。本 phase 不写代码。

**Files:**
- Modify: `项目工程文档/项目功能介绍/速查表/01-认证与登录.md`

---

- [ ] **Step 1: 速查表 01 末尾追加钉钉章节**

在「## 数据表」之前插入：

```markdown
## 钉钉 H5 微应用免登（可选）
- 流程：钉钉容器内打开 H5 → 重定向钉钉授权页拿 `authCode` → 回调页 `/dingtalk/callback` → POST `/api/auth/login/dingtalk` → 后端用 `authCode` 换用户 `unionId` → 按 `unionId` 查/建本地用户 → 签发标准 JWT（与账密登录同）。
- 后端：
  - 控制器：`auth/dingtalk/controller/DingTalkAuthController.java` — `POST /api/auth/login/dingtalk`
  - 服务：`auth/dingtalk/service/DingTalkService.java` — 换 userAccessToken + 拉用户信息
  - 配置：`auth/dingtalk/config/DingTalkProperties.java`（`dingtalk.enabled/app-key/app-secret/agent-id`）
  - 业务：`AuthService.loginByDingTalk()` — unionId 查找/自动建号、签 JWT
  - 白名单：`SecurityConfig` 放行 `/api/auth/login/dingtalk`
- 前端：
  - 工具：`utils/dingtalk.ts` — `isDingTalkClient()` / `redirectToDingTalkAuth()`
  - 回调页：`views/DingTalkCallbackView.vue`（路由 `/dingtalk/callback`，免登录）
  - 登录页：`LoginView.vue` 钉钉登录入口
  - API：`auth.ts` `dingTalkLogin(authCode)` / store `loginByDingTalk(authCode)`
- 数据表：`users` 加 `bind_type / dingtalk_union_id（唯一部分索引）/ dingtalk_open_id`（V41）。
- 钉钉平台配置清单（管理员）：
  1. 钉钉开放平台创建「H5 微应用」（企业内部应用）。
  2. 「开发配置 > 安全设置」：把 `VITE_DINGTALK_REDIRECT_URI`（如 `https://your-domain/dingtalk/callback`）加入**重定向 URL** 白名单。
  3. 拿 `AppKey/AppSecret/AgentId`，填后端 `dingtalk.app-key/app-secret/agent-id`（生产走环境变量 `DINGTALK_APP_KEY` 等），`dingtalk.enabled=true`。
  4. 前端 `.env` 填 `VITE_DINGTALK_APP_KEY`、`VITE_DINGTALK_REDIRECT_URI`。
  5. 微应用「应用首页地址」指向前端首页（或 `/login`）。
  6. 在钉钉手机端/PC 端打开微应用，验证免登进入。
```

- [ ] **Step 2: 提交**

```bash
git add "项目工程文档/项目功能介绍/速查表/01-认证与登录.md"
git commit -m "docs(auth): 速查表 01 增钉钉 H5 微应用免登章节"
```

- [ ] **完成后：** 回 [README](README.md) 勾掉 Phase 8。全 8 phase 完成。

## 收尾验证（联调，需钉钉企业账号）

- [ ] 后端 `dingtalk.enabled=true` + 真实 AppKey/AppSecret。
- [ ] 钉钉开放平台「安全设置」已加重定向 URL。
- [ ] 钉钉手机端打开微应用 → 免登进首页 → 聊天/知识库正常 → WebSocket 流式回复正常。
- [ ] 钉钉 PC 端同测一遍。
- [ ] access token 过期（15 分钟）后，钉钉容器内自动重免登（Phase 7 Step 5 生效）。
