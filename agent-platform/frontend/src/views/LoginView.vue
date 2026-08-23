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
                <stop offset="0%" style="stop-color:var(--color-gradient-start)" />
                <stop offset="100%" style="stop-color:var(--color-gradient-end)" />
              </linearGradient>
            </defs>
            <rect width="32" height="32" rx="6" fill="url(#login-logo-g)" />
            <text x="16" y="22" text-anchor="middle" fill="white" font-size="18" font-weight="bold" font-family="sans-serif">A</text>
          </svg>
        </div>
        <h1 class="login-card__title">多Agent智能体平台</h1>
        <p class="login-card__subtitle">登录您的账号以继续</p>
      </div>

      <!-- 微信回调处理中提示 -->
      <div v-if="wechatProcessing" class="login-card__wechat-processing">
        <n-spin size="small" /> 正在完成微信登录…
      </div>

      <!-- 微信回调失败提示 -->
      <n-alert v-if="wechatError" type="error" :bordered="false" class="login-card__wechat-error">
        微信登录失败，请重试或使用其他方式登录
      </n-alert>

      <!-- Tab 切换多通道登录 -->
      <n-tabs v-model:value="activeTab" type="line" animated size="large" class="login-card__tabs">
        <!-- 账号密码 -->
        <n-tab-pane v-if="channels.passwordEnabled" name="password" tab="账号密码">
          <PasswordLoginTab
            ref="passwordTabRef"
            @success="onLoginSuccess"
            @forgot="showForgotModal = true"
          />
        </n-tab-pane>

        <!-- 手机验证码 -->
        <n-tab-pane v-if="channels.smsEnabled" name="sms" tab="手机验证码">
          <SmsLoginTab @success="onLoginSuccess" />
        </n-tab-pane>

        <!-- 扫码登录（微信 + 钉钉） -->
        <n-tab-pane
          v-if="channels.wechatEnabled || dingtalkEnabled"
          name="qrcode"
          tab="扫码登录"
        >
          <QrcodeLoginTab :wechat-enabled="channels.wechatEnabled" @success="onLoginSuccess" />
        </n-tab-pane>
      </n-tabs>

      <!-- 注册链接 -->
      <div class="login-card__footer">
        <span class="login-card__hint">还没有账号？</span>
        <n-button text type="primary" @click="showRegisterModal = true">立即注册</n-button>
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
    <RegisterModal
      v-model:show="showRegisterModal"
      :email-code-required="channels.registerEmailCodeRequired"
      @registered="onRegistered"
    />

    <!-- 找回密码弹窗 -->
    <ForgotPasswordModal
      v-model:show="showForgotModal"
      :email-enabled="channels.emailEnabled"
      :sms-enabled="channels.smsEnabled"
    />
  </AuthLayout>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { NTabs, NTabPane, NButton, NSpin, NAlert, useMessage } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore, THEME_LIST } from '@/stores/theme'
import { authApi, type AuthChannels } from '@/api/auth'
import { isDingTalkEnabled } from '@/utils/dingtalk'
import { parseWechatCallback, clearWechatCallbackParams } from '@/utils/wechat'
import AuthLayout from '@/layouts/AuthLayout.vue'
import PasswordLoginTab from './login/PasswordLoginTab.vue'
import SmsLoginTab from './login/SmsLoginTab.vue'
import QrcodeLoginTab from './login/QrcodeLoginTab.vue'
import RegisterModal from './login/RegisterModal.vue'
import ForgotPasswordModal from './login/ForgotPasswordModal.vue'

const router = useRouter()
const route = useRoute()
const message = useMessage()
const authStore = useAuthStore()
const themeStore = useThemeStore()

// 初始化主题
themeStore.initTheme()
const themeList = THEME_LIST

// 钉钉免登入口（仅当配置了 AppKey/RedirectURI 时显示扫码 Tab）
const dingtalkEnabled = isDingTalkEnabled()

// 通道开关（onMounted 从后端拉取）
const channels = reactive<AuthChannels>({
  passwordEnabled: true,
  emailEnabled: false,
  smsEnabled: false,
  wechatEnabled: false,
  registerEmailCodeRequired: false
})

// Tab 激活态（默认账密）
const activeTab = ref('password')

// 弹窗
const showRegisterModal = ref(false)
const showForgotModal = ref(false)

// 子组件引用
const passwordTabRef = ref<InstanceType<typeof PasswordLoginTab> | null>(null)

// 微信回调处理状态
const wechatProcessing = ref(false)
const wechatError = ref(false)

/** 登录成功统一跳转。 */
function onLoginSuccess() {
  const redirect = (route.query.redirect as string) || '/chat'
  router.push(redirect)
}

/** 注册成功 → 自动填入登录表单 + 切到账密 Tab。 */
function onRegistered(username: string) {
  activeTab.value = 'password'
  passwordTabRef.value?.fillUsername(username)
}

/** 拉取通道开关。 */
async function loadChannels() {
  try {
    const res = await authApi.getChannels()
    Object.assign(channels, res.data.data)
    // 如果账密通道关（理论上不会），切到第一个可用 tab
    if (!channels.passwordEnabled && channels.smsEnabled) activeTab.value = 'sms'
    else if (!channels.passwordEnabled && (channels.wechatEnabled || dingtalkEnabled)) {
      activeTab.value = 'qrcode'
    }
  } catch {
    // 拉取失败保持默认（仅账密可见）
  }
}

/** 处理微信回调落地（URL fragment 带 token）。 */
async function handleWechatCallback() {
  const result = parseWechatCallback()
  if (!result) return

  if (result.ok && result.accessToken && result.refreshToken) {
    wechatProcessing.value = true
    try {
      await authStore.loginByWechatToken(result.accessToken, result.refreshToken)
      message.success('微信登录成功')
      clearWechatCallbackParams()
      onLoginSuccess()
    } catch (e) {
      wechatError.value = true
      message.error('微信登录失败：' + ((e as Error).message || '请重试'))
    } finally {
      wechatProcessing.value = false
    }
  } else {
    wechatError.value = true
    clearWechatCallbackParams()
  }
}

onMounted(() => {
  loadChannels()
  handleWechatCallback()
})
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
  background: linear-gradient(135deg, rgba(var(--color-primary-rgb), 0.3), transparent 50%, rgba(var(--color-primary-rgb), 0.1));
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

// Tab 区
.login-card__tabs {
  margin-top: var(--spacing-2);
}

// 微信回调处理提示
.login-card__wechat-processing {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 12px;
  margin-bottom: 16px;
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
}
.login-card__wechat-error {
  margin-bottom: 16px;
}

// 底部
.login-card__footer {
  text-align: center;
  margin-top: var(--spacing-5);
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
