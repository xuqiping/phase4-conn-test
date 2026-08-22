<template>
  <!-- S5 A6 TOTP：两步登录——第一屏密码，已绑定用户过密码后切到第二屏验证码 -->
  <n-form
    v-if="!mfaToken"
    ref="formRef"
    :model="form"
    :rules="rules"
    @submit.prevent="handleLogin"
  >
    <n-form-item path="username" label="用户名">
      <n-input
        v-model:value="form.username"
        placeholder="请输入用户名"
        size="large"
        :input-props="{ autocomplete: 'username' }"
      >
        <template #prefix>
          <n-icon :component="PersonOutline" color="var(--color-text-tertiary)" />
        </template>
      </n-input>
    </n-form-item>

    <n-form-item path="password" label="密码">
      <n-input
        v-model:value="form.password"
        type="password"
        show-password-on="click"
        placeholder="请输入密码"
        size="large"
        :input-props="{ autocomplete: 'current-password' }"
        @keyup.enter="handleLogin"
      >
        <template #prefix>
          <n-icon :component="LockClosedOutline" color="var(--color-text-tertiary)" />
        </template>
      </n-input>
    </n-form-item>

    <div class="pwd-tab__row">
      <n-button text type="primary" size="small" @click="emit('forgot')">忘记密码？</n-button>
    </div>

    <!-- 12x B2：同账号连续失败 ≥2 次 → 滑块闸（后端 40107 触发展示） -->
    <div v-if="needCaptcha" class="pwd-tab__captcha">
      <SliderCaptcha ref="captchaRef" :width="290" @success="onCaptchaSuccess" @fail="onCaptchaFail" />
      <p class="pwd-tab__captcha-hint">失败次数过多，请完成滑块验证后再登录</p>
    </div>

    <n-button
      type="primary"
      block
      size="large"
      :loading="authStore.loading"
      :disabled="needCaptcha && !captchaToken"
      attr-type="submit"
      class="pwd-tab__submit"
    >
      登 录
    </n-button>
  </n-form>

  <!-- 第二屏：TOTP 验证码 / 一次性恢复码 -->
  <n-form v-else @submit.prevent="handleVerifyMfa">
    <n-alert type="info" :bordered="false" class="pwd-tab__mfa-tip">
      账号已开启两步验证，请输入验证器 App 中的 6 位动态码；手机丢失时可输入一次性恢复码
    </n-alert>

    <n-form-item label="验证码">
      <n-input
        v-model:value="mfaCode"
        placeholder="6 位动态码或恢复码"
        size="large"
        :input-props="{ autocomplete: 'one-time-code' }"
        @keyup.enter="handleVerifyMfa"
      >
        <template #prefix>
          <n-icon :component="ShieldCheckmarkOutline" color="var(--color-text-tertiary)" />
        </template>
      </n-input>
    </n-form-item>

    <n-button
      type="primary"
      block
      size="large"
      :loading="authStore.loading"
      attr-type="submit"
      class="pwd-tab__submit"
    >
      验证并登录
    </n-button>

    <n-button quaternary block size="small" class="pwd-tab__back" @click="backToPassword">
      返回重新输入密码
    </n-button>
  </n-form>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import type { FormInst, FormRules } from 'naive-ui'
import { NForm, NFormItem, NInput, NButton, NIcon, NAlert, useMessage } from 'naive-ui'
import { PersonOutline, LockClosedOutline, ShieldCheckmarkOutline } from '@vicons/ionicons5'
import { useAuthStore } from '@/stores/auth'
import SliderCaptcha from './SliderCaptcha.vue'

const emit = defineEmits<{
  /** 登录成功（父组件跳转） */
  (e: 'success'): void
  /** 点击「忘记密码」（父组件打开找回密码弹窗） */
  (e: 'forgot'): void
}>()

const message = useMessage()
const authStore = useAuthStore()

const formRef = ref<FormInst | null>(null)
const form = reactive({ username: '', password: '' })

// S5 A6：两步登录状态——mfaToken 非空即处于第二屏
const mfaToken = ref('')
const mfaCode = ref('')

// 12x B2：渐进式滑块——后端返 40107 才展示；token 单次有效，每次提交后刷新
const needCaptcha = ref(false)
const captchaToken = ref('')
const captchaRef = ref<InstanceType<typeof SliderCaptcha> | null>(null)

function onCaptchaSuccess(token: string) {
  captchaToken.value = token
}
function onCaptchaFail() {
  captchaToken.value = ''
}
/** 每次登录提交后调用：滑块 token 已被后端消费（单次有效），强制重滑。 */
function consumeCaptcha() {
  if (!needCaptcha.value) return
  captchaToken.value = ''
  captchaRef.value?.fetchCaptcha()
}

const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码至少6个字符', trigger: 'blur' }
  ]
}

async function handleLogin() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  try {
    const result = await authStore.login({
      username: form.username,
      password: form.password,
      captchaVerification: captchaToken.value || undefined
    })
    if (result.mfaRequired && result.mfaToken) {
      // 密码因子已过 → 进第二屏（未落任何登录态）
      mfaToken.value = result.mfaToken
      mfaCode.value = ''
      return
    }
    if (result.mfaBindAdvice) {
      message.warning('平台建议管理员开启两步验证：设置 → 安全设置 → 两步验证')
    }
    message.success('登录成功')
    // 成功即回归无验证态
    needCaptcha.value = false
    captchaToken.value = ''
    emit('success')
  } catch (error: unknown) {
    // 12x B2：40107 = 滑块门槛触发 → 展示滑块
    if ((error as { code?: number }).code === 40107) {
      needCaptcha.value = true
    }
    consumeCaptcha()
    const msg = error instanceof Error ? error.message : '登录失败，请检查用户名和密码'
    message.error(msg)
  }
}

async function handleVerifyMfa() {
  if (!mfaCode.value.trim()) {
    message.warning('请输入验证码')
    return
  }
  try {
    await authStore.verifyMfa(mfaToken.value, mfaCode.value.trim())
    message.success('登录成功')
    mfaToken.value = ''
    emit('success')
  } catch (error: unknown) {
    const msg = error instanceof Error ? error.message : '验证码错误'
    message.error(msg)
  }
}

/** 第二屏返回：mfaToken 作废（后端 5min 一次性），重新走密码步。 */
function backToPassword() {
  mfaToken.value = ''
  mfaCode.value = ''
}

defineExpose({
  /** 填入用户名（注册成功后自动带入登录表单） */
  fillUsername(username: string) {
    form.username = username
  }
})
</script>

<style lang="scss" scoped>
.pwd-tab__row {
  display: flex;
  justify-content: flex-end;
  margin: -8px 0 16px;
}
.pwd-tab__captcha {
  margin-bottom: 12px;
}
.pwd-tab__captcha-hint {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--color-warning, #f0a020);
}
.pwd-tab__submit {
  height: 44px;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-semibold);
  background: linear-gradient(135deg, var(--color-gradient-start), var(--color-gradient-end));
  border: none;
  transition: all var(--duration-fast) var(--ease-in-out);
  &:hover { box-shadow: var(--shadow-primary); transform: translateY(-1px); }
  &:active { transform: translateY(0); }
}
.pwd-tab__mfa-tip {
  margin-bottom: 16px;
}
.pwd-tab__back {
  margin-top: 8px;
}
</style>
