# Phase 6 — 前端 UA 判定 + 授权重定向 + API

> 总路由：[README.md](README.md) · 上一：[Phase 5](phase-05-endpoint-whitelist.md) · 下一：[Phase 7](phase-07-frontend-callback-route.md)

**Goal：** 前端钉钉容器 UA 判定 + 授权页重定向拼装 + `dingTalkLogin(authCode)` API 方法。

**Files:**
- Create: `frontend/src/utils/dingtalk.ts`
- Modify: `frontend/src/api/auth.ts`
- Modify: `frontend/.env`（或 `.env.development`）

**Interfaces:**
- Consumes: 环境变量 `VITE_DINGTALK_APP_KEY` / `VITE_DINGTALK_REDIRECT_URI`。
- Produces: `isDingTalkClient()` / `isDingTalkEnabled()` / `redirectToDingTalkAuth(state)`（util），`authApi.dingTalkLogin(authCode)`（api）。

钉钉 H5 授权页（新版 OAuth，client_id = AppKey）：

```
https://login.dingtalk.com/oauth2/auth?redirect_uri={REDIRECT_URI}&response_type=code&client_id={APP_KEY}&scope=openid&state={STATE}&prompt=consent
```

授权后钉钉带 `?authCode=xxx&state=yyy` 重定向回 `REDIRECT_URI`。

---

- [ ] **Step 1: 前端环境变量**

`frontend/.env`（或 `.env.development`）加：

```
VITE_DINGTALK_APP_KEY=dingXXXXXXXX
VITE_DINGTALK_REDIRECT_URI=https://your-domain/dingtalk/callback
```

- [ ] **Step 2: 写 `frontend/src/utils/dingtalk.ts`**

```ts
// ============================================================
// 钉钉 H5 免登工具：UA 判定 + 授权重定向
// ============================================================

const APP_KEY = import.meta.env.VITE_DINGTALK_APP_KEY as string | undefined
const REDIRECT_URI = import.meta.env.VITE_DINGTALK_REDIRECT_URI as string | undefined

/** 当前是否运行在钉钉客户端 webview 内 */
export function isDingTalkClient(): boolean {
  if (typeof navigator === 'undefined') return false
  return /DingTalk/i.test(navigator.userAgent)
}

/** 钉钉免登是否已配置可用 */
export function isDingTalkEnabled(): boolean {
  return !!APP_KEY && !!REDIRECT_URI
}

/**
 * 跳转钉钉授权页。授权后钉钉带 authCode 回到 REDIRECT_URI。
 * @param state 透传状态，防 CSRF（回调页校验）
 */
export function redirectToDingTalkAuth(state = 'dt'): void {
  if (!isDingTalkEnabled()) {
    throw new Error('钉钉免登未配置 VITE_DINGTALK_APP_KEY / VITE_DINGTALK_REDIRECT_URI')
  }
  const params = new URLSearchParams({
    redirect_uri: REDIRECT_URI!,
    response_type: 'code',
    client_id: APP_KEY!,
    scope: 'openid',
    state,
    prompt: 'consent'
  })
  window.location.href = `https://login.dingtalk.com/oauth2/auth?${params.toString()}`
}
```

- [ ] **Step 3: `api/auth.ts` 加方法**

在 `authApi` 对象内 `logout` 之后、`getMe` 之前加：

```ts
  /**
   * 钉钉免登登录
   * POST /api/auth/login/dingtalk
   */
  dingTalkLogin(authCode: string) {
    return request.post<ApiResponse<LoginResponse>>('/auth/login/dingtalk', { authCode })
  },
```

- [ ] **Step 4: 类型检查**

Run: `cd frontend && npx vue-tsc --noEmit`
Expected: 无报错。

- [ ] **Step 5: 提交**

```bash
git add frontend/src/utils/dingtalk.ts frontend/src/api/auth.ts frontend/.env
git commit -m "feat(frontend): 钉钉 UA 判定+授权重定向 util 与 dingTalkLogin API"
```

- [ ] **完成后：** 回 [README](README.md) 勾掉 Phase 6，开 Phase 7。
