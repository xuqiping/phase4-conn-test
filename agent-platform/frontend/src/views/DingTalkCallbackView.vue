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
