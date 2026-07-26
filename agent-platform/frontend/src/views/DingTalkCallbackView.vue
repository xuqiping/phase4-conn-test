<template>
  <div class="dt-callback">
    <n-spin size="large" description="钉钉登录中..." />
  </div>
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useMessage, NSpin } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const message = useMessage()
const authStore = useAuthStore()

onMounted(async () => {
  const authCode = route.query.authCode as string | undefined
  const state = route.query.state as string | undefined
  console.log('[DingTalk] 回调页挂载, query =', {
    hasAuthCode: !!authCode,
    authCodeLen: authCode?.length,
    state,
    fullQuery: route.query
  })
  if (state && state !== 'dt') {
    console.warn('[DingTalk] state 校验失败:', state)
    message.error('state 校验失败')
    router.replace('/login')
    return
  }
  if (!authCode) {
    console.error('[DingTalk] 未收到 authCode，钉钉可能未授权或 redirect_uri 不在白名单')
    message.error('未收到钉钉授权码')
    router.replace('/login')
    return
  }
  try {
    console.log('[DingTalk] 调用 loginByDingTalk，authCode 长度 =', authCode.length)
    await authStore.loginByDingTalk(authCode)
    console.log('[DingTalk] 登录成功，跳转首页')
    router.replace('/')
  } catch (e: any) {
    console.error('[DingTalk] 登录失败:', e, e?.response?.data)
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
