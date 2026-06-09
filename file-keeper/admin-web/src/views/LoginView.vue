<template>
  <div style="height: 100vh; display: flex; align-items: center; justify-content: center">
    <n-card title="管理后台登录" style="width: 360px">
      <n-form ref="formRef" :model="form" :rules="rules" @submit.prevent="handleLogin">
        <n-form-item label="账号" path="identifier">
          <n-input v-model:value="form.identifier" placeholder="邮箱或手机号" />
        </n-form-item>
        <n-form-item label="密码" path="password">
          <n-input v-model:value="form.password" type="password" placeholder="密码" />
        </n-form-item>
        <n-button type="primary" block :loading="loading" attr-type="submit">
          登录
        </n-button>
      </n-form>
    </n-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { NCard, NForm, NFormItem, NInput, NButton, useMessage } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const message = useMessage()
const authStore = useAuthStore()

const loading = ref(false)
const form = reactive({ identifier: '', password: '' })
const rules = {
  identifier: { required: true, message: '请输入账号', trigger: 'blur' },
  password: { required: true, message: '请输入密码', trigger: 'blur' }
}

async function handleLogin() {
  loading.value = true
  try {
    await authStore.login(form.identifier, form.password)
    const redirect = (route.query.redirect as string) || '/'
    router.push(redirect)
  } catch (err) {
    message.error(err instanceof Error ? err.message : '登录失败')
  } finally {
    loading.value = false
  }
}
</script>
