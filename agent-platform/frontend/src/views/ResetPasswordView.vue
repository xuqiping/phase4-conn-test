<template>
  <AuthLayout>
    <div class="reset-pwd-card">
      <div class="reset-pwd-card__glow"></div>

      <div class="reset-pwd-card__header">
        <h1 class="reset-pwd-card__title">重置密码</h1>
        <p class="reset-pwd-card__subtitle">设置您的新密码</p>
      </div>

      <!-- 加载中 -->
      <div v-if="status === 'loading'" class="reset-pwd-card__loading">
        <n-spin size="large" />
      </div>

      <!-- 重置成功 -->
      <div v-else-if="status === 'success'" class="reset-pwd-card__result">
        <div class="reset-pwd-card__icon reset-pwd-card__icon--success">✓</div>
        <p class="reset-pwd-card__msg">密码重置成功，请使用新密码登录</p>
        <n-button type="primary" size="large" block class="reset-pwd-card__btn" @click="goLogin">
          返回登录
        </n-button>
      </div>

      <!-- D5（Q8-2）：无 token 引导页（不再落 SMS 死路表单） -->
      <div v-else-if="status === 'guide'" class="reset-pwd-card__result">
        <div class="reset-pwd-card__icon reset-pwd-card__icon--info">!</div>
        <p v-if="guideKind === 'email'" class="reset-pwd-card__msg">
          请通过重置邮件中的链接进入本页设置新密码。<br />
          还没收到邮件？可在登录页使用「忘记密码」重新发送。
        </p>
        <p v-else class="reset-pwd-card__msg">
          当前未开启邮件找回通道，请联系管理员重置密码。
        </p>
        <n-button
          v-if="guideKind === 'email'"
          type="primary"
          size="large"
          block
          class="reset-pwd-card__btn"
          @click="goLogin"
        >
          去登录页重新发送
        </n-button>
      </div>

      <!-- 重置表单 -->
      <n-form v-else ref="formRef" :model="form" :rules="rules" @submit.prevent="handleReset">
        <!-- 短信渠道：需输入手机号 + 重置码 -->
        <template v-if="channel === 'SMS'">
          <n-form-item path="phone" label="手机号">
            <n-input v-model:value="form.phone" placeholder="请输入手机号" size="large" :input-props="{ maxlength: 11 }">
              <template #prefix>
                <n-icon :component="CallOutline" color="var(--color-text-tertiary)" />
              </template>
            </n-input>
          </n-form-item>
          <n-form-item path="code" label="重置验证码">
            <n-input v-model:value="form.code" placeholder="请输入收到的6位验证码" size="large" :input-props="{ maxlength: 6 }">
              <template #prefix>
                <n-icon :component="ShieldCheckmarkOutline" color="var(--color-text-tertiary)" />
              </template>
            </n-input>
          </n-form-item>
        </template>

        <!-- 邮箱渠道：token 从 URL 取，不显示 -->
        <n-form-item path="newPassword" label="新密码">
          <n-input
            v-model:value="form.newPassword"
            type="password"
            show-password-on="click"
            placeholder="请输入新密码（6-100个字符）"
            size="large"
          >
            <template #prefix>
              <n-icon :component="LockClosedOutline" color="var(--color-text-tertiary)" />
            </template>
          </n-input>
        </n-form-item>

        <n-form-item path="confirmPassword" label="确认新密码">
          <n-input
            v-model:value="form.confirmPassword"
            type="password"
            show-password-on="click"
            placeholder="请再次输入新密码"
            size="large"
          >
            <template #prefix>
              <n-icon :component="LockClosedOutline" color="var(--color-text-tertiary)" />
            </template>
          </n-input>
        </n-form-item>

        <n-button type="primary" size="large" block :loading="submitting" attr-type="submit" class="reset-pwd-card__btn">
          重置密码
        </n-button>
      </n-form>
    </div>
  </AuthLayout>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { FormInst, FormRules } from 'naive-ui'
import { NForm, NFormItem, NInput, NButton, NIcon, NSpin, useMessage } from 'naive-ui'
import { LockClosedOutline, CallOutline, ShieldCheckmarkOutline } from '@vicons/ionicons5'
import { authApi } from '@/api/auth'
import type { AuthChannels } from '@/api/auth'
import AuthLayout from '@/layouts/AuthLayout.vue'

const route = useRoute()
const router = useRouter()
const message = useMessage()

type Status = 'form' | 'loading' | 'success' | 'guide'
const status = ref<Status>('loading')
const submitting = ref(false)
/** D5（Q8-2）：引导页两种形态——email=重发邮件指引；admin=无任何自助通道 */
const guideKind = ref<'email' | 'admin'>('email')

const formRef = ref<FormInst | null>(null)
const form = reactive({
  phone: '',
  code: '',
  newPassword: '',
  confirmPassword: ''
})

// 渠道：URL 有 token → EMAIL；URL 有 channel=sms 或无 token → SMS 表单
const channel = ref<'EMAIL' | 'SMS'>('EMAIL')
let emailToken = ''

const rules: FormRules = {
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '请输入正确的手机号', trigger: 'blur' }
  ],
  code: [
    { required: true, message: '请输入验证码', trigger: 'blur' },
    { pattern: /^\d{6}$/, message: '验证码为6位数字', trigger: 'blur' }
  ],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 100, message: '密码长度6-100个字符', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请确认新密码', trigger: 'blur' },
    {
      validator: (_rule, value) => {
        if (value !== form.newPassword) return new Error('两次输入的密码不一致')
        return true
      },
      trigger: 'blur'
    }
  ]
}

async function handleReset() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  submitting.value = true
  try {
    if (channel.value === 'EMAIL') {
      await authApi.resetPassword(emailToken, form.newPassword, 'EMAIL')
    } else {
      await authApi.resetPassword(form.code, form.newPassword, 'SMS', form.phone)
    }
    status.value = 'success'
    message.success('密码重置成功')
  } catch (e) {
    const msg = (e as Error).message || '重置失败，请检查验证码或链接是否有效'
    message.error(msg)
  } finally {
    submitting.value = false
  }
}

function goLogin() {
  router.push('/login')
}

/**
 * D5（Q8-2）分支（读认证通道开关，不再无脑落 SMS 表单）：
 * ① channel=SMS 且短信开 → SMS 表单；
 * ② 有 token → EMAIL 表单（token 本身即一次性凭证，即便邮件开关已关表单仍可用——
 *    持有效链接者被引导页挡死是反直觉死路，提交侧后端照常校验 token）；
 * ③ 无 token（或 SMS 通道已关）：邮件开 → 引导页「从邮件链接进入/去登录页重发」；
 *    邮件关 → 「请联系管理员重置」。
 * channels 接口失败按全开兜底（不砖页面，提交侧后端仍强校验）。
 */
onMounted(async () => {
  const token = (route.query.token as string) || ''
  const ch = (route.query.channel as string) || ''
  let channels: AuthChannels
  try {
    channels = (await authApi.getChannels()).data.data
  } catch {
    channels = {
      passwordEnabled: true, emailEnabled: true, smsEnabled: true,
      wechatEnabled: false, registerEmailCodeRequired: false
    }
  }
  if (ch.toUpperCase() === 'SMS' && channels.smsEnabled) {
    channel.value = 'SMS'
    status.value = 'form'
    return
  }
  if (token) {
    channel.value = 'EMAIL'
    emailToken = token
    status.value = 'form'
    return
  }
  guideKind.value = channels.emailEnabled ? 'email' : 'admin'
  status.value = 'guide'
})
</script>

<style lang="scss" scoped>
.reset-pwd-card {
  position: relative;
  width: 400px;
  max-width: 90vw;
  padding: var(--spacing-8);
  border-radius: var(--radius-lg);
  background: rgba(var(--color-primary-rgb), 0.03);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border: 1px solid var(--color-border);
}
.reset-pwd-card__glow {
  position: absolute;
  inset: -1px;
  border-radius: inherit;
  background: linear-gradient(135deg, rgba(var(--color-primary-rgb), 0.3), transparent 50%, rgba(var(--color-primary-rgb), 0.1));
  z-index: -1;
  filter: blur(1px);
}
.reset-pwd-card__header {
  text-align: center;
  margin-bottom: var(--spacing-6);
}
.reset-pwd-card__title {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  margin: 0 0 4px;
}
.reset-pwd-card__subtitle {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}
.reset-pwd-card__loading {
  display: flex;
  justify-content: center;
  padding: 40px 0;
}
.reset-pwd-card__result {
  text-align: center;
}
.reset-pwd-card__icon {
  width: 64px;
  height: 64px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 32px;
  color: #fff;
  margin: 0 auto 16px;
  &--success { background: #18a058; }
  &--info { background: var(--color-primary, #4a90d9); }
}
.reset-pwd-card__msg {
  font-size: var(--font-size-md);
  color: var(--color-text-primary);
  margin-bottom: 20px;
}
.reset-pwd-card__btn {
  margin-top: 8px;
  background: linear-gradient(135deg, var(--color-gradient-start), var(--color-gradient-end));
  border: none;
}
</style>
