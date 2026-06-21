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
import { ref, onMounted, computed, watch } from 'vue'
import { NSelect } from 'naive-ui'
import { llmApi } from '@/api/llm'
import type { AvailableModel } from '@/api/llm'

const props = defineProps<{
  modelValue?: string | null
}>()

const emit = defineEmits<{
  'update:modelValue': [model: string]
  change: [model: string]
}>()

const preferredModel = 'doubao-seed-2.0-code'
const selectedModel = ref<string | null>(props.modelValue || preferredModel)
const models = ref<AvailableModel[]>([])

watch(
  () => props.modelValue,
  value => {
    if (value && value !== selectedModel.value) {
      selectedModel.value = value
    }
  }
)

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
      const availableModelIds = new Set(models.value.map(m => m.modelId))
      if (selectedModel.value && availableModelIds.has(selectedModel.value)) {
        return
      }
      const nextModel = availableModelIds.has(preferredModel)
        ? preferredModel
        : models.value[0].modelId
      selectedModel.value = nextModel
      emit('update:modelValue', nextModel)
      emit('change', nextModel)
    }
  } catch {
    // Silent fail — model selector is optional
  }
})

function handleChange(value: string) {
  emit('update:modelValue', value)
  emit('change', value)
}
</script>
