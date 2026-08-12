<template>
  <AuthLayout>
    <div class="verify-email-card">
      <div class="verify-email-card__glow"></div>

      <!-- 加载中 -->
      <div v-if="status === 'loading'" class="verify-email-card__body">
        <n-spin size="large" />
        <h2 class="verify-email-card__title">正在验证邮箱…</h2>
        <p class="verify-email-card__subtitle">请稍候</p>
      </div>

      <!-- 验证成功 -->
      <div v-else-if="status === 'success'" class="verify-email-card__body">
        <div class="verify-email-card__icon verify-email-card__icon--success">✓</div>
        <h2 class="verify-email-card__title">邮箱验证成功</h2>
        <p class="verify-email-card__subtitle">您的邮箱已验证，现在可以用于找回密码</p>
        <n-button type="primary" size="large" block class="verify-email-card__btn" @click="goLogin">
          返回登录
        </n-button>
      </div>

      <!-- 验证失败 -->
      <div v-else class="verify-email-card__body">
        <div class="verify-email-card__icon verify-email-card__icon--fail">✕</div>
        <h2 class="verify-email-card__title">验证失败</h2>
        <p class="verify-email-card__subtitle">{{ errorMessage }}</p>
        <n-button type="primary" size="large" block class="verify-email-card__btn" @click="goLogin">
          返回登录
        </n-button>
      </div>
    </div>
  </AuthLayout>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NSpin, NButton, useMessage } from 'naive-ui'
import { authApi } from '@/api/auth'
import AuthLayout from '@/layouts/AuthLayout.vue'

const route = useRoute()
const router = useRouter()
const message = useMessage()

type Status = 'loading' | 'success' | 'fail'
const status = ref<Status>('loading')
const errorMessage = ref('链接无效或已过期')

async function verify() {
  const token = (route.query.token as string) || ''
  if (!token) {
    status.value = 'fail'
    errorMessage.value = '缺少验证参数，请通过邮件中的链接打开'
    return
  }
  try {
    await authApi.verifyEmail(token)
    status.value = 'success'
    message.success('邮箱验证成功')
  } catch (e) {
    status.value = 'fail'
    errorMessage.value = (e as Error).message || '链接无效或已过期，请重新发送验证邮件'
  }
}

function goLogin() {
  router.push('/login')
}

onMounted(verify)
</script>

<style lang="scss" scoped>
.verify-email-card {
  position: relative;
  width: 400px;
  max-width: 90vw;
  padding: var(--spacing-10) var(--spacing-8);
  border-radius: var(--radius-lg);
  background: rgba(var(--color-primary-rgb), 0.03);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border: 1px solid var(--color-border);
  text-align: center;
}
.verify-email-card__glow {
  position: absolute;
  inset: -1px;
  border-radius: inherit;
  background: linear-gradient(135deg, rgba(var(--color-primary-rgb), 0.3), transparent 50%, rgba(var(--color-primary-rgb), 0.1));
  z-index: -1;
  filter: blur(1px);
}
.verify-email-card__body {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}
.verify-email-card__icon {
  width: 64px;
  height: 64px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 32px;
  color: #fff;
  margin-bottom: 8px;
  &--success { background: #18a058; }
  &--fail { background: #d03050; }
}
.verify-email-card__title {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  margin: 0;
}
.verify-email-card__subtitle {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  margin: 0 0 16px;
}
.verify-email-card__btn {
  background: linear-gradient(135deg, var(--color-gradient-start), var(--color-gradient-end));
  border: none;
}
</style>
