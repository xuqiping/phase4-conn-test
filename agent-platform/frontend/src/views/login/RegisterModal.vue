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
        <n-input v-model:value="form.email" placeholder="请输入邮箱地址（用于验证和找回密码）" size="large">
          <template #prefix>
            <n-icon :component="MailOutline" color="var(--color-text-tertiary)" />
          </template>
        </n-input>
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
      注册成功后即可登录。系统将向您的邮箱发送验证邮件，<b>未验证邮箱不可用于找回密码</b>，请及时验证。
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
import { ref, reactive, watch } from 'vue'
import type { FormInst, FormRules } from 'naive-ui'
import { NModal, NForm, NFormItem, NInput, NButton, NIcon, NCheckbox, NAlert, useMessage } from 'naive-ui'
import { PersonOutline, MailOutline, LockClosedOutline } from '@vicons/ionicons5'
import { useAuthStore } from '@/stores/auth'

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
  password: '',
  confirmPassword: '',
  agreeTerms: false
})

const rules: FormRules = {
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
      form.password = ''
      form.confirmPassword = ''
      form.agreeTerms = false
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
      email: form.email,
      password: form.password
    })
    message.success('注册成功，请登录并完成邮箱验证')
    emit('registered', form.username)
    emit('update:show', false)
  } catch (error: unknown) {
    const msg = error instanceof Error ? error.message : '注册失败'
    message.error(msg)
  }
}
</script>

<style lang="scss" scoped>
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
