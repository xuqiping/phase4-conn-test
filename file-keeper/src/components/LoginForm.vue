<template>
  <form data-test="login-submit" class="space-y-4" @submit.prevent="handleSubmit">
    <div>
      <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
        邮箱或手机号
      </label>
      <input
        data-test="login-identifier"
        v-model.trim="identifier"
        type="text"
        autocomplete="username"
        placeholder="请输入邮箱或手机号"
        class="w-full px-3 py-2 bg-gray-100 dark:bg-dark-hover border border-gray-300 dark:border-dark-border rounded-md outline-none focus:border-primary text-sm transition-colors"
      />
    </div>

    <div>
      <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
        密码
      </label>
      <input
        data-test="login-password"
        v-model="password"
        type="password"
        autocomplete="current-password"
        placeholder="请输入密码"
        class="w-full px-3 py-2 bg-gray-100 dark:bg-dark-hover border border-gray-300 dark:border-dark-border rounded-md outline-none focus:border-primary text-sm transition-colors"
      />
    </div>

    <p v-if="message" class="text-xs text-red-500">{{ message }}</p>

    <button
      type="submit"
      :disabled="authStore.loading"
      class="w-full px-4 py-2 text-sm bg-primary hover:bg-[#369b6e] text-white rounded-md transition-colors font-medium shadow-sm shadow-primary/20 disabled:opacity-60 disabled:cursor-not-allowed"
    >
      {{ authStore.loading ? '登录中...' : '登录' }}
    </button>
  </form>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useAuthStore } from '../stores/authStore'

const props = defineProps<{
  baseUrl: string
}>()

const emit = defineEmits<{
  success: []
}>()

const authStore = useAuthStore()
const identifier = ref('')
const password = ref('')
const message = ref('')

async function handleSubmit() {
  message.value = ''
  if (!identifier.value || !password.value) {
    message.value = '请填写账号和密码'
    return
  }

  try {
    await authStore.login(props.baseUrl, identifier.value, password.value)
    emit('success')
  } catch (error) {
    message.value = error instanceof Error ? error.message : '登录失败'
  }
}
</script>
