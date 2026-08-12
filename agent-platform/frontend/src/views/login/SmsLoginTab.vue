<template>
  <n-form ref="formRef" :model="form" :rules="rules" @submit.prevent="handleLogin">
    <n-form-item path="phone" label="手机号">
      <n-input
        v-model:value="form.phone"
        placeholder="请输入手机号"
        size="large"
        :input-props="{ autocomplete: 'tel', maxlength: 11 }"
      >
        <template #prefix>
          <n-icon :component="CallOutline" color="var(--color-text-tertiary)" />
        </template>
      </n-input>
    </n-form-item>

    <!-- 滑块验证码（发码前置闸门，防 SMS Pumping） -->
    <div v-if="!captchaPassed" class="sms-tab__captcha">
      <SliderCaptcha :width="captchaWidth" @success="onCaptchaSuccess" @fail="onCaptchaFail" />
      <p class="sms-tab__captcha-hint">完成滑块验证后可获取验证码</p>
    </div>

    <n-form-item path="code" label="验证码">
      <div class="sms-tab__code-row">
        <n-input
          v-model:value="form.code"
          placeholder="请输入6位验证码"
          size="large"
          :input-props="{ autocomplete: 'one-time-code', maxlength: 6 }"
          @keyup.enter="handleLogin"
        >
          <template #prefix>
            <n-icon :component="ShieldCheckmarkOutline" color="var(--color-text-tertiary)" />
          </template>
        </n-input>
        <n-button
          :disabled="!canSendCode"
          :loading="sendingCode"
          size="large"
          class="sms-tab__send-btn"
          @click="handleSendCode"
        >
          {{ sendBtnText }}
        </n-button>
      </div>
    </n-form-item>

    <n-button
      type="primary"
      block
      size="large"
      :loading="authStore.loading"
      attr-type="submit"
      class="sms-tab__submit"
    >
      验证码登录
    </n-button>

    <p class="sms-tab__notice">
      新手机号首次验证码登录将自动注册账号
    </p>
  </n-form>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onUnmounted } from 'vue'
import type { FormInst, FormRules } from 'naive-ui'
import { NForm, NFormItem, NInput, NButton, NIcon, useMessage } from 'naive-ui'
import { CallOutline, ShieldCheckmarkOutline } from '@vicons/ionicons5'
import { useAuthStore } from '@/stores/auth'
import { authApi } from '@/api/auth'
import SliderCaptcha from './SliderCaptcha.vue'

const emit = defineEmits<{
  /** 登录成功（父组件跳转） */
  (e: 'success'): void
}>()

const message = useMessage()
const authStore = useAuthStore()

const captchaWidth = 290 // 适配登录卡片内宽

const formRef = ref<FormInst | null>(null)
const form = reactive({ phone: '', code: '' })

const rules: FormRules = {
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    {
      validator: (_r, v: string) => /^1[3-9]\d{9}$/.test(v),
      message: '请输入正确的手机号',
      trigger: 'blur'
    }
  ],
  code: [
    { required: true, message: '请输入验证码', trigger: 'blur' },
    { pattern: /^\d{6}$/, message: '验证码为6位数字', trigger: 'blur' }
  ]
}

// 滑块状态
const captchaPassed = ref(false)
const captchaToken = ref('') // 滑块产出的加密轨迹串（发码的 captchaToken）
const captchaRef = ref<InstanceType<typeof SliderCaptcha> | null>(null)

function onCaptchaSuccess(token: string) {
  captchaPassed.value = true
  captchaToken.value = token
}
function onCaptchaFail(_reason: string) {
  captchaPassed.value = false
  captchaToken.value = ''
}

// 发码按钮倒计时（60s）
const sendingCode = ref(false)
const countdown = ref(0)
let timer: ReturnType<typeof setInterval> | null = null

const sendBtnText = computed(() => {
  if (sendingCode.value) return '发送中…'
  if (countdown.value > 0) return `${countdown.value}s 后重发`
  if (!captchaPassed.value) return '先过滑块验证'
  return '获取验证码'
})

const canSendCode = computed(
  () => captchaPassed.value && countdown.value === 0 && !sendingCode.value
)

function startCountdown() {
  countdown.value = 60
  timer = setInterval(() => {
    countdown.value--
    if (countdown.value <= 0 && timer) {
      clearInterval(timer)
      timer = null
    }
  }, 1000)
}

onUnmounted(() => {
  if (timer) clearInterval(timer)
})

async function handleSendCode() {
  // 手动校验手机号（FormInst 无 validateField，validate() 会连带校验空 code 字段）
  if (!/^1[3-9]\d{9}$/.test(form.phone)) {
    message.warning('请输入正确的手机号')
    return
  }
  if (!captchaToken.value) {
    message.warning('请先完成滑块验证')
    return
  }
  sendingCode.value = true
  try {
    const res = await authApi.sendSmsCode(form.phone, captchaToken.value)
    message.success(res.data.data || '验证码已发送')
    startCountdown()
    // 滑块 token 单次有效，发码后重置滑块状态（下次需重新滑）
    captchaPassed.value = false
    captchaToken.value = ''
    captchaRef.value?.fetchCaptcha()
  } catch (error: unknown) {
    const msg = error instanceof Error ? error.message : '发送失败，请稍后重试'
    message.error(msg)
    // 滑块校验失败或限流 → 重置滑块让用户重来
    captchaPassed.value = false
    captchaToken.value = ''
    captchaRef.value?.fetchCaptcha()
  } finally {
    sendingCode.value = false
  }
}

async function handleLogin() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  try {
    await authStore.loginBySms(form.phone, form.code)
    message.success('登录成功')
    emit('success')
  } catch (error: unknown) {
    const msg = error instanceof Error ? error.message : '验证码错误或已过期'
    message.error(msg)
  }
}
</script>

<style lang="scss" scoped>
.sms-tab__code-row {
  display: flex;
  gap: 8px;
  width: 100%;
}
.sms-tab__send-btn {
  flex-shrink: 0;
  width: 120px;
}
.sms-tab__captcha {
  margin-bottom: 16px;
}
.sms-tab__captcha-hint {
  font-size: 12px;
  color: var(--color-text-tertiary);
  margin: 6px 0 0;
}
.sms-tab__submit {
  height: 44px;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-semibold);
  background: linear-gradient(135deg, var(--color-gradient-start), var(--color-gradient-end));
  border: none;
  &:hover { box-shadow: var(--shadow-primary); transform: translateY(-1px); }
  &:active { transform: translateY(0); }
}
.sms-tab__notice {
  text-align: center;
  font-size: 12px;
  color: var(--color-text-tertiary);
  margin-top: 12px;
}
</style>
