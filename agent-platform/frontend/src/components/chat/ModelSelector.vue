<template>
  <n-select
    v-model:value="selectedModel"
    :options="options"
    placeholder="选择模型"
    size="small"
    style="width: 200px"
    @update:value="handleChange"
  />
</template>

<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { NSelect } from 'naive-ui'
import { llmApi } from '@/api/llm'
import type { AvailableModel } from '@/api/llm'

const emit = defineEmits<{
  change: [model: string]
}>()

const preferredModel = 'doubao-seed-2.0-code'
const selectedModel = ref<string | null>(preferredModel)
const models = ref<AvailableModel[]>([])

const options = computed(() => {
  const grouped = new Map<string, { label: string; key: string; type: string; children: { label: string; value: string }[] }>()
  for (const m of models.value) {
    if (!grouped.has(m.providerName)) {
      grouped.set(m.providerName, {
        type: 'group',
        label: m.providerName,
        key: m.providerName,
        children: []
      })
    }
    grouped.get(m.providerName)!.children.push({ label: m.displayName, value: m.modelId })
  }
  return Array.from(grouped.values())
})

onMounted(async () => {
  try {
    const res = await llmApi.listAvailableModels()
    models.value = res.data.data
    if (models.value.length) {
      selectedModel.value = models.value.some(m => m.modelId === preferredModel)
        ? preferredModel
        : models.value[0].modelId
      emit('change', selectedModel.value)
    }
  } catch {
    // Silent fail — model selector is optional
  }
})

function handleChange(value: string) {
  emit('change', value)
}
</script>
