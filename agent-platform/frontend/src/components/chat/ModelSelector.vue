<template>
  <n-select
    v-model:value="selectedModel"
    :options="options"
    :placeholder="placeholder"
    :clearable="optional"
    size="small"
    class="model-selector"
    @update:value="handleChange"
  />
</template>

<script setup lang="ts">
import { ref, onMounted, computed, watch } from 'vue'
import { NSelect } from 'naive-ui'
import { llmApi } from '@/api/llm'
import type { AvailableModel } from '@/api/llm'

const props = withDefaults(defineProps<{
  modelValue?: string | null
  /**
   * FR-006 可选模式（模型覆盖场景：画布节点 / 资产拆分场）：
   * 不自动选中、可清空回退默认；清空时 emit 空串 ''（调用方映射为「不覆盖」）。
   * 缺省 false 保持 ChatView/DocumentOptionsModal 原有「自动选中」行为。
   */
  optional?: boolean
  /** optional 模式下的占位文案（说明留空时走哪个默认配置）。 */
  emptyLabel?: string
}>(), {
  optional: false,
  emptyLabel: '默认（跟随全局配置）'
})

const emit = defineEmits<{
  'update:modelValue': [model: string]
  change: [model: string]
}>()

const preferredModel = 'doubao-seed-2.0-code'
const selectedModel = ref<string | null>(props.modelValue || (props.optional ? null : preferredModel))
const models = ref<AvailableModel[]>([])

const placeholder = computed(() => (props.optional ? props.emptyLabel : '选择模型'))

watch(
  () => props.modelValue,
  value => {
    // optional 模式允许外部清空（''/null → null，显示默认占位）
    if (value !== selectedModel.value) {
      selectedModel.value = value || null
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
    // optional 模式不自动选中（留空=不覆盖默认模型）；已选值即使不在列表里也保留展示
    if (!props.optional && models.value.length) {
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

function handleChange(value: string | null) {
  // 清空（optional clearable）→ emit 空串，调用方映射为「不覆盖」
  emit('update:modelValue', value ?? '')
  emit('change', value ?? '')
}
</script>

<style lang="scss" scoped>
.model-selector {
  width: 200px;
}

@media (max-width: 768px) {
  .model-selector {
    width: 100%;
  }
}
</style>
