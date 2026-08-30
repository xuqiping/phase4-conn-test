<template>
  <div class="auth-settings">
    <n-form label-placement="left" label-width="120" class="auth-settings__form">
      <n-form-item label="登录超时">
        <n-input-number
          v-model:value="timeoutMinutes"
          :min="1"
          :max="10080"
          :step="5"
          class="auth-settings__input"
        />
        <span class="auth-settings__unit">分钟</span>
      </n-form-item>
      <n-form-item label="单点登录">
        <n-switch v-model:value="singleSessionEnabled" />
        <span class="auth-settings__unit">同账号仅一处在线，新登录踢旧会话</span>
      </n-form-item>
      <n-button type="primary" :loading="saving" @click="handleSave">保存</n-button>
    </n-form>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { NButton, NForm, NFormItem, NInputNumber, NSwitch, useMessage } from 'naive-ui'
import { systemApi } from '@/api/system'

const message = useMessage()
const saving = ref(false)
const timeoutMinutes = ref(15)
const singleSessionEnabled = ref(true)

onMounted(load)

async function load() {
  const res = await systemApi.getAuthSettings()
  timeoutMinutes.value = Math.round(res.data.data.accessTokenExpirationMs / 60000)
  singleSessionEnabled.value = res.data.data.singleSessionEnabled ?? true
}

async function handleSave() {
  saving.value = true
  try {
    await systemApi.updateAuthSettings({
      accessTokenExpirationMs: timeoutMinutes.value * 60000,
      singleSessionEnabled: singleSessionEnabled.value
    })
    message.success('认证设置已更新（超时重新登录后生效，单点登录立即生效）')
  } finally {
    saving.value = false
  }
}
</script>

<style lang="scss" scoped>
.auth-settings__form {
  max-width: 420px;
}

.auth-settings__input {
  width: 160px;
}

.auth-settings__unit {
  margin-left: 8px;
  color: var(--color-text-secondary);
}
</style>
