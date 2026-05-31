<template>
  <div class="rounded-lg border border-gray-200 bg-white p-3 text-xs dark:border-dark-border dark:bg-dark-panel">
    <div class="flex items-center justify-between">
      <span class="font-medium">缓存空间</span>
      <span>{{ usedLabel }} / {{ limitLabel }}</span>
    </div>
    <div class="mt-2 h-2 rounded bg-gray-100 dark:bg-dark-hover">
      <div class="h-2 rounded bg-slate-400 dark:bg-slate-500" :style="{ width: `${percent}%` }"></div>
    </div>
    <button class="mt-3 w-full rounded bg-gray-100 px-3 py-1.5 text-xs dark:bg-dark-hover" @click="$emit('clearCache')">
      清理非文本缓存
    </button>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { ClipboardStorageUsage } from '../types/clipboard'

const props = defineProps<{
  usage: ClipboardStorageUsage | null
}>()

defineEmits<{
  clearCache: []
}>()

const usedLabel = computed(() => formatBytes(props.usage?.totalBytes ?? 0))
const limitLabel = computed(() => formatBytes(props.usage?.limitBytes ?? 0))
const percent = computed(() => {
  if (!props.usage || props.usage.limitBytes <= 0) return 0
  return Math.min(100, Math.round((props.usage.totalBytes / props.usage.limitBytes) * 100))
})

function formatBytes(bytes: number): string {
  if (bytes <= 0) return '0 MB'
  return `${Math.round(bytes / 1024 / 1024)} MB`
}
</script>
