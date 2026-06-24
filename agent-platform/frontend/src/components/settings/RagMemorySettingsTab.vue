<template>
  <div class="rag-memory-settings">
    <n-form label-placement="left" label-width="160" class="rag-memory-settings__form">
      <n-form-item label="记忆模式（全局）">
        <n-switch v-model:value="enabled" :loading="saving" @update:value="handleSave" />
        <span class="rag-memory-settings__hint">
          开启后对话/Agent/工作流启用 RAG 证据 + 用户长期记忆（会话/Agent/工作流级可覆盖，默认关）
        </span>
      </n-form-item>
    </n-form>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { NForm, NFormItem, NSwitch, useMessage } from 'naive-ui'
import { systemApi } from '@/api/system'

const message = useMessage()
const saving = ref(false)
const enabled = ref(false)

onMounted(load)

async function load() {
  const res = await systemApi.getRagMemorySettings()
  enabled.value = !!res.data.data.enabled
}

async function handleSave() {
  saving.value = true
  try {
    await systemApi.updateRagMemorySettings({ enabled: enabled.value })
    message.success(enabled.value ? '已开启记忆模式' : '已关闭记忆模式')
  } catch {
    // 失败回滚由请求拦截器提示，恢复本地值
    await load()
  } finally {
    saving.value = false
  }
}
</script>

<style lang="scss" scoped>
.rag-memory-settings__form {
  max-width: 640px;
}

.rag-memory-settings__hint {
  margin-left: 12px;
  color: var(--color-text-secondary);
  font-size: 12px;
}
</style>
