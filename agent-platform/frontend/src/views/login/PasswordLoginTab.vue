<template>
  <n-form
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

    <n-button
      type="primary"
      block
      size="large"
      :loading="authStore.loading"
      attr-type="submit"
      class="pwd-tab__submit"
    >
      登 录
    </n-button>
  </n-form>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import type { FormInst, FormRules } from 'naive-ui'
import { NForm, NFormItem, NInput, NButton, NIcon, useMessage } from 'naive-ui'
import { PersonOutline, LockClosedOutline } from '@vicons/ionicons5'
import { useAuthStore } from '@/stores/auth'

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
    await authStore.login({ username: form.username, password: form.password })
    message.success('登录成功')
    emit('success')
  } catch (error: unknown) {
    const msg = error instanceof Error ? error.message : '登录失败，请检查用户名和密码'
    message.error(msg)
  }
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
</style>
