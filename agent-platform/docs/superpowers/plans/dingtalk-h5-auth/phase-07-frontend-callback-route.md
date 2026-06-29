# Phase 7 — 前端回调页 + Store action + 路由 + 入口

> 总路由：[README.md](README.md) · 上一：[Phase 6](phase-06-frontend-ua-redirect.md) · 下一：[Phase 8](phase-08-docs-and-platform-config.md)

**Goal：** 钉钉授权回调页 `/dingtalk/callback`，store `loginByDingTalk(authCode)` action，登录页钉钉入口按钮。

**Files:**
- Modify: `frontend/src/stores/auth.ts`
- Create: `frontend/src/views/DingTalkCallbackView.vue`
- Modify: `frontend/src/router/index.ts`
- Modify: `frontend/src/views/LoginView.vue`
- Modify（可选）: `frontend/src/api/request.ts`（钉钉容器内 401 重新免登）

**Interfaces:**
- Consumes: `redirectToDingTalkAuth/isDingTalkClient`（Phase 6）、`authApi.dingTalkLogin`（Phase 6）、`STORAGE_KEYS/setStorage`（既有）。
- Produces: 路由 `/dingtalk/callback`（免登录白名单）；`authStore.loginByDingTalk(authCode)`；LoginView 钉钉入口。

---

- [ ] **Step 1: `stores/auth.ts` 加 action**

在 `refreshAccessToken` 方法之后加：

```ts
  /**
   * 钉钉免登：用 authCode 换 token
   */
  async function loginByDingTalk(authCode: string) {
    loading.value = true
    try {
      const res = await authApi.dingTalkLogin(authCode)
      const { accessToken: at, refreshToken: rt, userInfo: info } = res.data.data
      accessToken.value = at
      refreshToken.value = rt
      userInfo.value = info
      setStorage(STORAGE_KEYS.ACCESS_TOKEN, at)
      setStorage(STORAGE_KEYS.REFRESH_TOKEN, rt)
      setStorage(STORAGE_KEYS.USER_INFO, info)
    } finally {
      loading.value = false
    }
  }
```

并在 `return { ... }` 内加 `loginByDingTalk,`（紧跟 `refreshAccessToken,` 后）。

- [ ] **Step 2: 写回调页 `views/DingTalkCallbackView.vue`**

```vue
<template>
  <div class="dt-callback">
    <n-spin size="large" description="钉钉登录中..." />
  </div>
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useMessage } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const message = useMessage()
const authStore = useAuthStore()

onMounted(async () => {
  const authCode = route.query.authCode as string | undefined
  const state = route.query.state as string | undefined
  if (state && state !== 'dt') {
    message.error('state 校验失败')
    router.replace('/login')
    return
  }
  if (!authCode) {
    message.error('未收到钉钉授权码')
    router.replace('/login')
    return
  }
  try {
    await authStore.loginByDingTalk(authCode)
    router.replace('/')
  } catch (e: any) {
    message.error(e?.message || '钉钉登录失败')
    router.replace('/login')
  }
})
</script>

<style scoped lang="scss">
.dt-callback {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100vh;
}
</style>
```

- [ ] **Step 3: 路由注册（白名单免登录）**

`router/index.ts`：
- 在路由表加（与 `/login` 同级）：

```ts
  {
    path: '/dingtalk/callback',
    name: 'DingTalkCallback',
    component: () => import('@/views/DingTalkCallbackView.vue'),
    meta: { requiresAuth: false, title: '钉钉登录' }
  },
```

- 守卫里把 `/dingtalk/callback` 与 `/login` 一起放行（既有 `meta.requiresAuth === false` 判断应已覆盖；若守卫用路径白名单，补一条 `name === 'DingTalkCallback'`）。

- [ ] **Step 4: LoginView 加钉钉入口**

`LoginView.vue` 登录卡片底部加按钮：

```vue
<n-button
  v-if="dtEnabled"
  block
  secondary
  type="primary"
  @click="onDingTalkLogin"
>
  钉钉登录
</n-button>
```

`<script setup>` 内加：

```ts
import { isDingTalkEnabled, redirectToDingTalkAuth } from '@/utils/dingtalk'

const dtEnabled = isDingTalkEnabled()

function onDingTalkLogin() {
  redirectToDingTalkAuth('dt')
}
```

- [ ] **Step 5（可选，UX 改进）：钉钉容器内 401 重新免登**

`api/request.ts` 的 `redirectToLogin()` 改为：钉钉容器内跳钉钉授权，否则跳 `/login`。

```ts
import { isDingTalkClient, isDingTalkEnabled, redirectToDingTalkAuth } from '@/utils/dingtalk'

function redirectToLogin() {
  clearAuthStorage()
  if (isRedirectingToLogin) return
  isRedirectingToLogin = true
  // 钉钉容器内 → 重新免登；否则回账密登录页
  if (isDingTalkClient() && isDingTalkEnabled()) {
    redirectToDingTalkAuth('dt')
    return
  }
  const current = window.location.pathname + window.location.search
  const redirect = current && current !== '/login' ? `?redirect=${encodeURIComponent(current)}` : ''
  window.location.href = `/login${redirect}`
}
```

> 说明：钉钉用户 access token 过期（15 分钟）时，账密登录页对钉钉用户无意义。此步让钉钉容器内自动重走免登。可随 Phase 7 一并提交，或单独提交。

- [ ] **Step 6: 类型检查 + 构建**

Run: `cd frontend && npx vue-tsc --noEmit && npm run build`
Expected: 无错，构建成功。

- [ ] **Step 7: 提交**

```bash
git add frontend/src/stores/auth.ts frontend/src/views/DingTalkCallbackView.vue frontend/src/views/LoginView.vue frontend/src/router/index.ts frontend/src/api/request.ts
git commit -m "feat(frontend): 钉钉回调页+store action+路由+登录入口+401重免登"
```

- [ ] **完成后：** 回 [README](README.md) 勾掉 Phase 7，开 Phase 8。
