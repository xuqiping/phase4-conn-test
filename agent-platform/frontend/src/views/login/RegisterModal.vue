<template>
  <n-modal
    :show="show"
    preset="card"
    title="注册新账号"
    :style="{ maxWidth: '440px', width: '90vw' }"
    :bordered="false"
    :segmented="{ content: true }"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <n-form
      ref="formRef"
      :model="form"
      :rules="rules"
      @submit.prevent="handleRegister"
    >
      <n-form-item path="username" label="用户名">
        <n-input v-model:value="form.username" placeholder="请输入用户名（3-20个字符）" size="large">
          <template #prefix>
            <n-icon :component="PersonOutline" color="var(--color-text-tertiary)" />
          </template>
        </n-input>
      </n-form-item>

      <n-form-item path="email" label="邮箱">
        <n-input v-model:value="form.email" placeholder="请输入邮箱地址（注册即验证，用于找回密码）" size="large">
          <template #prefix>
            <n-icon :component="MailOutline" color="var(--color-text-tertiary)" />
          </template>
        </n-input>
      </n-form-item>

      <!-- 12x B1：注册前置邮箱验证码（证明邮箱归属才建号） -->
      <n-form-item path="emailCode" label="邮箱验证码">
        <div class="register-modal__code-row">
          <n-input
            v-model:value="form.emailCode"
            placeholder="6 位数字验证码"
            size="large"
            maxlength="6"
            :input-props="{ inputmode: 'numeric', autocomplete: 'one-time-code' }"
          />
          <n-button
            size="large"
            :disabled="codeCountdown > 0 || codeSending"
            :loading="codeSending"
            class="register-modal__code-btn"
            @click="handleSendCode"
          >
            {{ codeCountdown > 0 ? `${codeCountdown}s 后重发` : '获取验证码' }}
          </n-button>
        </div>
      </n-form-item>

      <n-form-item path="password" label="密码">
        <n-input
          v-model:value="form.password"
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
          v-model:value="form.confirmPassword"
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

      <!-- 协议勾选（合规：必须同意条款才能注册） -->
      <n-form-item path="agreeTerms" :show-label="false">
        <n-checkbox v-model:checked="form.agreeTerms">
          我已阅读并同意
          <n-button text type="primary" size="tiny" @click="showTerms = true">《用户协议与隐私条款》</n-button>
        </n-checkbox>
      </n-form-item>

      <n-button
        type="primary"
        block
        size="large"
        :loading="authStore.loading"
        :disabled="!form.agreeTerms"
        attr-type="submit"
      >
        注册
      </n-button>
    </n-form>

    <n-alert type="info" :bordered="false" class="register-modal__notice">
      注册前需先验证邮箱：填邮箱 → 点「获取验证码」→ 查收邮件填 6 位数字码（10 分钟内有效）。
      验证通过的邮箱可直接用于找回密码。
    </n-alert>
  </n-modal>

  <!-- 协议条款弹窗 -->
  <n-modal
    v-model:show="showTerms"
    preset="card"
    title="用户协议与隐私条款"
    :style="{ maxWidth: '560px', width: '90vw' }"
  >
    <div class="register-modal__terms">
      <p>本平台尊重并保护用户的个人隐私。注册即表示您同意我们按照本协议收集、使用、存储您的相关信息。</p>
      <p>1. 您的账号密码、邮箱、手机号等仅用于身份认证与安全通知。</p>
      <p>2. 我们不会在未经您许可的情况下向第三方共享您的个人信息。</p>
      <p>3. 您应妥善保管账号凭证，因密码泄露导致的损失由您自行承担。</p>
      <p>4. 平台有权对违规账号进行限制或封禁处理。</p>
      <p>5. 完整的收集范围、用途、留存期限（180 天）与注销账号操作路径，见
        <router-link to="/privacy" target="_blank">《隐私政策》</router-link>。</p>
    </div>
  </n-modal>
</template>

<script setup lang="ts">
import { ref, reactive, watch, onBeforeUnmount } from 'vue'
import type { FormInst, FormRules } from 'naive-ui'
import { NModal, NForm, NFormItem, NInput, NButton, NIcon, NCheckbox, NAlert, useMessage } from 'naive-ui'
import { PersonOutline, MailOutline, LockClosedOutline } from '@vicons/ionicons5'
import { useAuthStore } from '@/stores/auth'
import { authApi } from '@/api/auth'

const props = defineProps<{
  show: boolean
}>()
const emit = defineEmits<{
  (e: 'update:show', v: boolean): void
  /** 注册成功，回传用户名（父组件自动填入登录表单） */
  (e: 'registered', username: string): void
}>()

const message = useMessage()
const authStore = useAuthStore()

const formRef = ref<FormInst | null>(null)
const showTerms = ref(false)
const form = reactive({
  username: '',
  email: '',
  emailCode: '',
  password: '',
  confirmPassword: '',
  agreeTerms: false
})

// 12x B1：发码按钮 60s 倒计时（与后端 regcode:resend 60s 窗口一致）
const codeSending = ref(false)
const codeCountdown = ref(0)
let codeTimer: ReturnType<typeof setInterval> | null = null

function startCodeCountdown() {
  codeCountdown.value = 60
  codeTimer = setInterval(() => {
    codeCountdown.value -= 1
    if (codeCountdown.value <= 0 && codeTimer) {
      clearInterval(codeTimer)
      codeTimer = null
    }
  }, 1000)
}

onBeforeUnmount(() => {
  if (codeTimer) clearInterval(codeTimer)
})

async function handleSendCode() {
  const email = form.email.trim()
  if (!/^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/.test(email)) {
    message.error('请先填写正确的邮箱地址')
    return
  }
  codeSending.value = true
  try {
    await authApi.sendRegisterEmailCode(email)
    message.success('验证码已发送，10 分钟内有效')
    startCodeCountdown()
  } catch (error: unknown) {
    const msg = error instanceof Error ? error.message : '发送失败'
    message.error(msg)
  } finally {
    codeSending.value = false
  }
}

const rules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 20, message: '用户名长度3-20个字符', trigger: 'blur' }
  ],
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '请输入有效的邮箱地址', trigger: 'blur' }
  ],
  emailCode: [
    { required: true, message: '请输入邮箱验证码', trigger: 'blur' },
    {
      validator: (_rule, value) => {
        if (value && !/^\d{6}$/.test(value)) return new Error('验证码为 6 位数字')
        return true
      },
      trigger: 'blur'
    }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 20, message: '密码长度6-20个字符', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请确认密码', trigger: 'blur' },
    {
      validator: (_rule, value) => {
        if (value !== form.password) return new Error('两次输入的密码不一致')
        return true
      },
      trigger: 'blur'
    }
  ],
  agreeTerms: [
    {
      validator: (_rule, value) => {
        if (!value) return new Error('请阅读并同意协议')
        return true
      },
      trigger: 'change'
    }
  ]
}

// 弹窗关闭时重置表单
watch(
  () => props.show,
  (v) => {
    if (!v) {
      form.username = ''
      form.email = ''
      form.emailCode = ''
      form.password = ''
      form.confirmPassword = ''
      form.agreeTerms = false
      if (codeTimer) {
        clearInterval(codeTimer)
        codeTimer = null
      }
      codeCountdown.value = 0
    }
  }
)

async function handleRegister() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  try {
    await authStore.register({
      username: form.username,
      email: form.email.trim(),
      password: form.password,
      emailCode: form.emailCode.trim()
    })
    message.success('注册成功，请登录')
    emit('registered', form.username)
    emit('update:show', false)
  } catch (error: unknown) {
    const msg = error instanceof Error ? error.message : '注册失败'
    message.error(msg)
  }
}
</script>

<style lang="scss" scoped>
.register-modal__code-row {
  display: flex;
  gap: 8px;
  width: 100%;
  .n-input { flex: 1; }
}
.register-modal__code-btn {
  flex-shrink: 0;
  min-width: 112px;
}
.register-modal__notice {
  margin-top: 12px;
  font-size: 13px;
}
.register-modal__terms {
  font-size: 13px;
  line-height: 1.8;
  color: var(--color-text-secondary);
  p { margin: 0 0 8px; }
}
</style>
