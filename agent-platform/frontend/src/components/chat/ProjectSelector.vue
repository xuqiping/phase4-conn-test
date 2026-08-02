<template>
  <n-select
    v-model:value="selected"
    :options="options"
    :loading="loading"
    :disabled="disabled"
    placeholder="总记忆"
    size="small"
    style="width: 160px"
    :consistent-menu-width="false"
    @update:value="handleChange"
    @update:show="onToggleShow"
  />
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { NSelect } from 'naive-ui'
import { projectApi } from '@/api/project'
import type { Project } from '@/api/project'

const props = defineProps<{
  modelValue?: number | null
  disabled?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [projectId: number | null]
  change: [projectId: number | null]
}>()

const selected = ref<number | null>(props.modelValue ?? null)
const projects = ref<Project[]>([])
const loading = ref(false)

watch(
  () => props.modelValue,
  v => {
    const next = v ?? null
    if (next !== selected.value) selected.value = next
  }
)

const options = computed(() => {
  const list: Array<Record<string, unknown>> = [
    { label: '总记忆（不归属项目）', value: null }
  ]
  if (projects.value.length) {
    list.push({
      type: 'group',
      label: '项目记忆',
      key: 'projects',
      children: projects.value.map(p => ({ label: p.name, value: p.id }))
    })
  }
  return list
})

/** 拉项目列表。onMounted 调一次；下拉每次打开也调——修「项目管理新建后下拉列表不刷新」
 *  （ProjectSelector 内部 list 与 ChatView 的 projectOptions 各自独立，项目管理 modal 的 @changed
 *  只刷新 ChatView 的 list，这里靠开下拉 reload 自洽）。 */
async function loadProjects() {
  loading.value = true
  try {
    const res = await projectApi.list()
    projects.value = res.data.data || []
  } catch {
    projects.value = []
  } finally {
    loading.value = false
  }
}

function onToggleShow(show: boolean) {
  if (show) void loadProjects()
}

onMounted(loadProjects)

function handleChange(v: number | null) {
  emit('update:modelValue', v)
  emit('change', v)
}
</script>
