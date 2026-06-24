<template>
  <form data-test="register-submit" class="space-y-4" @submit.prevent="handleSubmit">
    <div>
      <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
        注册方式
      </label>
      <select
        data-test="register-contact-type"
        v-model="contactType"
        class="w-full px-3 py-2 bg-gray-100 dark:bg-dark-hover border border-gray-300 dark:border-dark-border rounded-md outline-none focus:border-primary text-sm transition-colors"
      >
        <option value="email">邮箱</option>
        <option value="phone">手机号</option>
      </select>
    </div>

    <div>
      <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
        {{ contactType === 'email' ? '邮箱' : '手机号' }}
      </label>
      <div class="flex items-center space-x-2">
        <input
          data-test="register-contact"
          v-model.trim="contact"
          type="text"
          :placeholder="contactType === 'email' ? '请输入邮箱' : '请输入手机号'"
          class="flex-1 px-3 py-2 bg-gray-100 dark:bg-dark-hover border border-gray-300 dark:border-dark-border rounded-md outline-none focus:border-primary text-sm transition-colors"
        />
        <button
          data-test="register-send-code"
          type="button"
          :disabled="authStore.loading"
          class="px-3 py-2 text-sm text-gray-600 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-dark-hover border border-gray-300 dark:border-dark-border rounded-md transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
          @click="handleSendCode"
        >
          发送验证码
        </button>
      </div>
    </div>

    <div>
      <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
        验证码
      </label>
      <div class="flex items-center space-x-2">
        <input
          data-test="register-code"
          v-model.trim="code"
          type="text"
          placeholder="请输入验证码"
          class="flex-1 px-3 py-2 bg-gray-100 dark:bg-dark-hover border border-gray-300 dark:border-dark-border rounded-md outline-none focus:border-primary text-sm transition-colors"
        />
        <button
          data-test="register-check-code"
          type="button"
          :disabled="authStore.loading"
          class="px-3 py-2 text-sm text-gray-600 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-dark-hover border border-gray-300 dark:border-dark-border rounded-md transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
          @click="handleCheckCode"
        >
          校验验证码
        </button>
      </div>
    </div>

    <div>
      <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
        密码
      </label>
      <input
        data-test="register-password"
        v-model="password"
        type="password"
        autocomplete="new-password"
        placeholder="请输入密码"
        class="w-full px-3 py-2 bg-gray-100 dark:bg-dark-hover border border-gray-300 dark:border-dark-border rounded-md outline-none focus:border-primary text-sm transition-colors"
      />
    </div>

    <p v-if="message" :class="['text-xs', success ? 'text-primary' : 'text-red-500']">
      {{ message }}
    </p>
    <p v-if="success" data-test="register-success-message" class="text-xs text-primary">
      注册成功，等待管理员审核
    </p>

    <button
      type="submit"
      :disabled="authStore.loading"
      class="w-full px-4 py-2 text-sm bg-primary hover:bg-[#369b6e] text-white rounded-md transition-colors font-medium shadow-sm shadow-primary/20 disabled:opacity-60 disabled:cursor-not-allowed"
    >
      {{ authStore.loading ? '提交中...' : '提交注册' }}
    </button>
  </form>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useAuthStore } from '../stores/authStore'
import type { ContactType } from '../api/auth'

const props = defineProps<{
  baseUrl: string
}>()

const authStore = useAuthStore()
const contactType = ref<ContactType>('email')
const contact = ref('')
const password = ref('')
const code = ref('')
const message = ref('')
const success = ref(false)
const codeVerified = ref(false)

watch([contactType, contact, code], () => {
  codeVerified.value = false
})

function requireContact() {
  if (!contact.value) {
    message.value = '请填写邮箱或手机号'
    success.value = false
    return false
  }
  return true
}

async function handleSendCode() {
  message.value = ''
  if (!requireContact()) return

  try {
    await authStore.sendVerificationCode(props.baseUrl, {
      contactType: contactType.value,
      contact: contact.value
    })
    message.value = '验证码已发送'
    success.value = true
  } catch (error) {
    message.value = error instanceof Error ? error.message : '发送验证码失败'
    success.value = false
  }
}

async function handleCheckCode() {
  message.value = ''
  if (!requireContact()) return
  if (!code.value) {
    message.value = '请填写验证码'
    success.value = false
    return
  }

  try {
    const verified = await authStore.checkVerificationCode(props.baseUrl, {
      contactType: contactType.value,
      contact: contact.value,
      code: code.value
    })
    codeVerified.value = verified
    message.value = verified ? '验证码校验通过' : '验证码不正确'
    success.value = verified
  } catch (error) {
    message.value = error instanceof Error ? error.message : '校验验证码失败'
    success.value = false
  }
}

async function handleSubmit() {
  message.value = ''
  success.value = false
  if (!requireContact()) return
  if (!password.value) {
    message.value = '请填写密码'
    return
  }
  if (!code.value) {
    message.value = '请填写验证码'
    return
  }
  if (!codeVerified.value) {
    message.value = '请先校验验证码'
    return
  }

  try {
    await authStore.register(props.baseUrl, {
      email: contactType.value === 'email' ? contact.value : null,
      phone: contactType.value === 'phone' ? contact.value : null,
      password: password.value
    })
    success.value = true
    message.value = ''
  } catch (error) {
    message.value = error instanceof Error ? error.message : '注册失败'
  }
}
</script>
