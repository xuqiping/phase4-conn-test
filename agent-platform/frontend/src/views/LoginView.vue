<template>
  <AuthLayout>
    <div class="login-card">
      <!-- 卡片发光边框效果 -->
      <div class="login-card__glow"></div>

      <!-- Logo + 标题 -->
      <div class="login-card__header">
        <div class="login-card__logo">
          <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32" width="40" height="40">
            <defs>
              <linearGradient id="login-logo-g" x1="0%" y1="0%" x2="100%" y2="100%">
                <stop offset="0%" style="stop-color:var(--color-gradient-start)"/>
                <stop offset="100%" style="stop-color:var(--color-gradient-end)"/>
              </linearGradient>
            </defs>
            <rect width="32" height="32" rx="6" fill="url(#login-logo-g)"/>
            <text x="16" y="22" text-anchor="middle" fill="white" font-size="18" font-weight="bold" font-family="sans-serif">A</text>
          </svg>
        </div>
        <h1 class="login-card__title">多Agent智能体平台</h1>
        <p class="login-card__subtitle">登录您的账号以继续</p>
      </div>

      <!-- 登录表单 -->
      <n-form
        ref="loginFormRef"
        :model="loginForm"
        :rules="loginRules"
        @submit.prevent="handleLogin"
      >
        <!-- 用户名 -->
        <n-form-item path="username" label="用户名">
          <n-input
            v-model:value="loginForm.username"
            placeholder="请输入用户名"
            size="large"
            :input-props="{ autocomplete: 'username' }"
          >
            <template #prefix>
              <n-icon :component="PersonOutline" color="var(--color-text-tertiary)" />
            </template>
          </n-input>
        </n-form-item>

        <!-- 密码 -->
        <n-form-item path="password" label="密码">
          <n-input
            v-model:value="loginForm.password"
            type="password"
            show-password-on="click"
            placeholder="请输入密码"
            size="large"
            :input-props="{ autocomplete: 'current-password' }"
          >
            <template #prefix>
              <n-icon :component="LockClosedOutline" color="var(--color-text-tertiary)" />
            </template>
          </n-input>
        </n-form-item>

        <!-- 登录按钮 -->
        <n-button
          type="primary"
          block
          size="large"
          :loading="authStore.loading"
          attr-type="submit"
          class="login-card__submit"
        >
          登 录
        </n-button>
      </n-form>

      <!-- 钉钉免登入口（仅当后端配置 AppKey/RedirectURI 时显示） -->
      <n-button
        v-if="dtEnabled"
        block
        secondary
        type="primary"
        size="large"
        class="login-card__dingtalk"
        @click="onDingTalkLogin"
      >
        钉钉登录
      </n-button>

      <!-- 注册链接 -->
      <div class="login-card__footer">
        <span class="login-card__hint">还没有账号？</span>
        <n-button text type="primary" @click="showRegisterModal = true">
          立即注册
        </n-button>
      </div>

      <!-- 底部主题切换 -->
      <div class="login-card__theme-area">
        <span class="login-card__theme-label">主题：</span>
        <div class="login-card__theme-options">
          <button
            v-for="theme in themeList"
            :key="theme.name"
            class="login-card__theme-btn"
            :class="{ 'login-card__theme-btn--active': themeStore.currentTheme === theme.name }"
            :title="theme.label"
            @click="themeStore.setTheme(theme.name)"
          >
            <span
              class="login-card__theme-swatch"
              :style="{ background: `linear-gradient(135deg, ${theme.colors.primary}, ${theme.colors.gradientEnd})` }"
            ></span>
          </button>
        </div>
      </div>
    </div>

    <!-- 注册弹窗 -->
    <n-modal
      v-model:show="showRegisterModal"
      preset="card"
      title="注册新账号"
      :style="{ maxWidth: '440px', width: '90vw' }"
      :bordered="false"
      :segmented="{ content: true }"
    >
      <n-form
        ref="registerFormRef"
        :model="registerForm"
        :rules="registerRules"
        @submit.prevent="handleRegister"
      >
        <n-form-item path="username" label="用户名">
          <n-input
            v-model:value="registerForm.username"
            placeholder="请输入用户名（3-20个字符）"
            size="large"
          >
            <template #prefix>
              <n-icon :component="PersonOutline" color="var(--color-text-tertiary)" />
            </template>
          </n-input>
        </n-form-item>

        <n-form-item path="email" label="邮箱">
          <n-input
            v-model:value="registerForm.email"
            placeholder="请输入邮箱地址"
            size="large"
          >
            <template #prefix>
              <n-icon :component="MailOutline" color="var(--color-text-tertiary)" />
            </template>
          </n-input>
        </n-form-item>

        <n-form-item path="password" label="密码">
          <n-input
            v-model:value="registerForm.password"
            type="password"
            show-password-on="click"
            placeholder="请输入密码（6-20个字符）"
            size="large"
          >
            <template #prefix>
              <n-icon :component="LockClosedOutline" color="var(--color-text-tertiary)" />
            </template>
          </n-input>
        </n-form-item>

        <n-form-item path="confirmPassword" label="确认密码">
          <n-input
            v-model:value="registerForm.confirmPassword"
            type="password"
            show-password-on="click"
            placeholder="请再次输入密码"
            size="large"
          >
            <template #prefix>
              <n-icon :component="LockClosedOutline" color="var(--color-text-tertiary)" />
            </template>
          </n-input>
        </n-form-item>

        <n-button
          type="primary"
          block
          size="large"
          :loading="authStore.loading"
          attr-type="submit"
        >
          注册
        </n-button>
      </n-form>
    </n-modal>
  </AuthLayout>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import type { FormInst, FormRules } from 'naive-ui'
import {
  NForm, NFormItem, NInput, NButton, NIcon, NModal, useMessage
} from 'naive-ui'
import {
  PersonOutline, LockClosedOutline, MailOutline
} from '@vicons/ionicons5'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore, THEME_LIST } from '@/stores/theme'
import AuthLayout from '@/layouts/AuthLayout.vue'
import { isDingTalkEnabled, redirectToDingTalkAuth } from '@/utils/dingtalk'

const router = useRouter()
const route = useRoute()
const message = useMessage()
const authStore = useAuthStore()
const themeStore = useThemeStore()

// 钉钉免登入口（仅当配置了 AppKey/RedirectURI 时显示）
const dtEnabled = isDingTalkEnabled()

function onDingTalkLogin() {
  redirectToDingTalkAuth('dt')
}

// 初始化主题
themeStore.initTheme()

const themeList = THEME_LIST

// === 登录表单 ===
const loginFormRef = ref<FormInst | null>(null)
const loginForm = reactive({
  username: '',
  password: ''
})

const loginRules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码至少6个字符', trigger: 'blur' }
  ]
}

// === 注册表单 ===
const showRegisterModal = ref(false)
const registerFormRef = ref<FormInst | null>(null)
const registerForm = reactive({
  username: '',
  email: '',
  password: '',
  confirmPassword: ''
})

const registerRules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 20, message: '用户名长度3-20个字符', trigger: 'blur' }
  ],
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '请输入有效的邮箱地址', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 20, message: '密码长度6-20个字符', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请确认密码', trigger: 'blur' },
    {
      validator: (_rule, value) => {
        if (value !== registerForm.password) {
          return new Error('两次输入的密码不一致')
        }
        return true
      },
      trigger: 'blur'
    }
  ]
}

// === 处理登录 ===
async function handleLogin() {
  try {
    await loginFormRef.value?.validate()
  } catch {
    return
  }

  try {
    await authStore.login({
      username: loginForm.username,
      password: loginForm.password
    })

    message.success('登录成功')

    // 跳转到之前的页面或首页
    const redirect = (route.query.redirect as string) || '/agents'
    router.push(redirect)
  } catch (error: unknown) {
    const msg = error instanceof Error ? error.message : '登录失败，请检查用户名和密码'
    message.error(msg)
  }
}

// === 处理注册 ===
async function handleRegister() {
  try {
    await registerFormRef.value?.validate()
  } catch {
    return
  }

  try {
    await authStore.register({
      username: registerForm.username,
      email: registerForm.email,
      password: registerForm.password
    })

    message.success('注册成功，请登录')
    showRegisterModal.value = false

    // 将注册的用户名自动填入登录表单
    loginForm.username = registerForm.username

    // 清空注册表单
    registerForm.username = ''
    registerForm.email = ''
    registerForm.password = ''
    registerForm.confirmPassword = ''
  } catch (error: unknown) {
    const msg = error instanceof Error ? error.message : '注册失败'
    message.error(msg)
  }
}
</script>

<style lang="scss" scoped>
// 登录卡片
.login-card {
  position: relative;
  width: 400px;
  max-width: 90vw;
  padding: var(--spacing-8) var(--spacing-8) var(--spacing-6);
  border-radius: var(--radius-lg);
  background: rgba(var(--color-primary-rgb), 0.03);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border: 1px solid var(--color-border);
  animation: fade-in 0.6s var(--ease-out);
}

// 发光边框效果
.login-card__glow {
  position: absolute;
  inset: -1px;
  border-radius: inherit;
  background: linear-gradient(
    135deg,
    rgba(var(--color-primary-rgb), 0.3),
    transparent 50%,
    rgba(var(--color-primary-rgb), 0.1)
  );
  z-index: -1;
  filter: blur(1px);
}

// 头部
.login-card__header {
  text-align: center;
  margin-bottom: var(--spacing-6);
}

.login-card__logo {
  display: flex;
  justify-content: center;
  margin-bottom: var(--spacing-3);
}

.login-card__title {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  margin-bottom: var(--spacing-1);
}

.login-card__subtitle {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

// 登录按钮
.login-card__submit {
  margin-top: var(--spacing-4);
  height: 44px;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-semibold);
  border-radius: var(--radius-base);
  background: linear-gradient(135deg, var(--color-gradient-start), var(--color-gradient-end));
  border: none;
  transition: all var(--duration-fast) var(--ease-in-out);

  &:hover {
    box-shadow: var(--shadow-primary);
    transform: translateY(-1px);
  }

  &:active {
    transform: translateY(0);
  }
}

// 底部
.login-card__footer {
  text-align: center;
  margin-top: var(--spacing-4);
}

.login-card__hint {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

// 主题选择区域
.login-card__theme-area {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--spacing-2);
  margin-top: var(--spacing-5);
  padding-top: var(--spacing-4);
  border-top: 1px solid var(--color-border-light);
}

.login-card__theme-label {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}

.login-card__theme-options {
  display: flex;
  gap: var(--spacing-2);
}

.login-card__theme-btn {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  border: 2px solid transparent;
  padding: 0;
  cursor: pointer;
  overflow: hidden;
  transition: all var(--duration-instant) var(--ease-in-out);

  &--active {
    border-color: var(--color-primary);
    box-shadow: 0 0 8px rgba(var(--color-primary-rgb), 0.4);
  }

  &:hover {
    transform: scale(1.15);
  }
}

.login-card__theme-swatch {
  display: block;
  width: 100%;
  height: 100%;
  border-radius: 50%;
}
</style>
