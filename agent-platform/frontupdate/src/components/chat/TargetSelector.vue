<template>
  <n-select
    v-model:value="selectedTarget"
    :options="options"
    :loading="loading"
    :disabled="disabled"
    placeholder="选择目标"
    size="small"
    class="target-selector"
    @update:value="handleChange"
  />
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { NSelect } from 'naive-ui'
import { chatTargetApi } from '@/api/chatTarget'
import type { ChatTarget } from '@/api/chatTarget'

const props = defineProps<{
  modelValue?: string | null
  disabled?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [target: string]
  change: [target: string]
}>()

const fallbackTarget = 'none'
const selectedTarget = ref(props.modelValue || fallbackTarget)
const targets = ref<ChatTarget[]>([])
const loading = ref(false)

watch(
  () => props.modelValue,
  value => {
    const nextValue = value || fallbackTarget
    if (nextValue !== selectedTarget.value) {
      selectedTarget.value = nextValue
    }
  }
)

const options = computed(() => {
  const none = targets.value.find(target => target.type === 'NONE')
  const agents = targets.value.filter(target => target.type === 'AGENT')
  const workflows = targets.value.filter(target => target.type === 'WORKFLOW')
  const result: Array<Record<string, unknown>> = [
    toOption(none || {
      type: 'NONE',
      targetKey: fallbackTarget,
      id: null,
      name: '无',
      description: null,
      available: true
    })
  ]

  if (agents.length) {
    result.push({
      type: 'group',
      label: '智能体',
      key: 'agents',
      children: agents.map(toOption)
    })
  }
  if (workflows.length) {
    result.push({
      type: 'group',
      label: '工作流',
      key: 'workflows',
      children: workflows.map(toOption)
    })
  }

  return result
})

onMounted(async () => {
  loading.value = true
  try {
    const res = await chatTargetApi.listTargets()
    targets.value = res.data.data
    const availableKeys = new Set(targets.value.filter(target => target.available).map(target => target.targetKey))
    if (availableKeys.has(selectedTarget.value)) {
      return
    }
    selectedTarget.value = fallbackTarget
    emit('update:modelValue', fallbackTarget)
    emit('change', fallbackTarget)
  } finally {
    loading.value = false
  }
})

function toOption(target: ChatTarget) {
  return {
    label: target.name,
    value: target.targetKey,
    disabled: !target.available
  }
}

function handleChange(value: string) {
  emit('update:modelValue', value)
  emit('change', value)
}
</script>

<style lang="scss" scoped>
.target-selector {
  width: 220px;
}

@media (max-width: 768px) {
  .target-selector {
    width: 100%;
  }
}
</style>
