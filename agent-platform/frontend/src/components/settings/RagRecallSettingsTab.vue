<template>
  <div class="rag-recall-settings">
    <n-form label-placement="left" label-width="160" class="rag-recall-settings__form">
      <n-form-item label="Query 扩展（全局）">
        <n-switch v-model:value="enabled" :loading="saving" @update:value="handleSave" />
        <span class="rag-recall-settings__hint">
          开启后检索前先改写/扩展 query 提升召回。4 条检索路径（检索调试 / RAG 问答 / 智能对话 / Agent·工作流）同读此开关，调试与真实一致
        </span>
      </n-form-item>
      <n-form-item label="切块触发阈值">
        <n-input-number
          v-model:value="threshold"
          :min="1"
          :max="5000"
          :loading="saving"
          :disabled="!enabled"
          style="max-width: 220px"
          @update:value="handleSave"
        />
        <span class="rag-recall-settings__hint">
          字数。输入 &gt; 此值 → 切块多路召回（多主题各有命中，不丢内容、不调改写 LLM）；≤ 此值 → 改写+HyDE。默认 200
        </span>
      </n-form-item>
    </n-form>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { NForm, NFormItem, NInputNumber, NSwitch, useMessage } from 'naive-ui'
import { systemApi } from '@/api/system'

const message = useMessage()
const saving = ref(false)
const enabled = ref(true)
const threshold = ref<number>(200)

onMounted(load)

async function load() {
  const res = await systemApi.getRagRecallSettings()
  enabled.value = !!res.data.data.enabled
  threshold.value = typeof res.data.data.threshold === 'number' ? res.data.data.threshold : 200
}

async function handleSave() {
  // 不 guard enabled：关开关也要入库（否则刷新回弹）
  saving.value = true
  try {
    await systemApi.updateRagRecallSettings({
      enabled: enabled.value,
      threshold: threshold.value
    })
    message.success(`Query 扩展：${enabled.value ? '开（阈值 ' + threshold.value + ' 字）' : '关（单 query 直接检索）'}`)
  } catch {
    await load()
  } finally {
    saving.value = false
  }
}
</script>

<style lang="scss" scoped>
.rag-recall-settings__form {
  max-width: 640px;
}

.rag-recall-settings__hint {
  margin-left: 12px;
  color: var(--color-text-secondary);
  font-size: 12px;
}

@media (max-width: 768px) {
  .rag-recall-settings__form {
    max-width: 100%;
  }
  .rag-recall-settings__hint {
    display: block;
    margin-left: 0;
    margin-top: 4px;
  }
}
</style>
