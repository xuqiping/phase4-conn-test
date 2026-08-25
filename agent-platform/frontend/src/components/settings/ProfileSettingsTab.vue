<template>
  <!-- 17x：个人信息（昵称/姓名 users.name）——项目组/账单/充值下拉等展示用；空=回落账号名 -->
  <div class="profile-tab">
    <n-form label-placement="left" :label-width="100" style="max-width: 480px">
      <n-form-item label="账号">
        <n-input :value="authStore.userInfo?.username ?? ''" disabled />
      </n-form-item>
      <n-form-item label="昵称/姓名">
        <n-input
          v-model:value="name"
          maxlength="32"
          show-count
          clearable
          placeholder="项目组/账单等处展示用；留空则显示账号名"
        />
      </n-form-item>
      <n-form-item :label="' '">
        <n-button type="primary" :loading="saving" :disabled="!dirty" @click="save">保存</n-button>
      </n-form-item>
    </n-form>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NButton, NForm, NFormItem, NInput, useMessage } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
const message = useMessage()

const name = ref(authStore.userInfo?.name ?? '')
const saving = ref(false)
/** 与已存值比对（null 与 '' 视为同值——空即清除） */
const dirty = computed(() => (name.value.trim() || '') !== (authStore.userInfo?.name ?? ''))

// 外部刷新（fetchUserInfo/重新登录）→ 回填，但编辑中不覆盖
watch(() => authStore.userInfo?.name, v => {
  if (!dirty.value) name.value = v ?? ''
})

async function save() {
  saving.value = true
  try {
    const trimmed = name.value.trim()
    await authStore.updateProfileName(trimmed === '' ? null : trimmed)
    name.value = trimmed
    message.success('已保存')
  } finally {
    saving.value = false
  }
}
</script>

<style lang="scss" scoped>
.profile-tab {
  padding: var(--spacing-4) 0;
}
</style>
