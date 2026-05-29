<template>
  <button
    type="button"
    :class="[
      'w-full text-left rounded-lg border px-3 py-2 transition-colors',
      selected
        ? 'border-primary bg-primary/10 text-gray-900 dark:text-gray-100'
        : 'border-gray-200 dark:border-dark-border bg-white dark:bg-dark-panel hover:bg-gray-50 dark:hover:bg-dark-hover'
    ]"
    @click="$emit('select', item.id)"
  >
    <div class="flex items-start justify-between gap-3">
      <div class="min-w-0 flex-1">
        <div class="flex items-center gap-2">
          <span class="text-xs font-medium text-primary">{{ kindLabel }}</span>
          <span v-if="item.cacheState === 'cached'" class="text-[11px] text-green-600">已缓存</span>
          <span v-if="item.cacheState === 'reference_only'" class="text-[11px] text-amber-600">仅引用</span>
        </div>
        <div class="mt-1 truncate text-sm font-medium">{{ item.title }}</div>
        <div class="mt-1 line-clamp-2 text-xs text-gray-500 dark:text-gray-400">{{ item.summary }}</div>
      </div>
      <img v-if="item.thumbnailPath" :src="item.thumbnailPath" class="h-12 w-12 rounded object-cover" alt="" />
    </div>
    <div class="mt-2 flex items-center justify-between text-[11px] text-gray-400">
      <span>{{ item.sourceApp?.processName || '未知来源' }}</span>
      <span>{{ timeLabel }}</span>
    </div>
  </button>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { ClipboardItemSummary } from '../types/clipboard'

const props = defineProps<{
  item: ClipboardItemSummary
  selected: boolean
}>()

defineEmits<{
  select: [id: string]
}>()

const labels: Record<string, string> = {
  text: '文本',
  html: '富文本',
  image: '图片',
  file: '文件',
  url: '链接',
  color: '颜色',
  mixed: '混合',
  security_event: '安全'
}

const kindLabel = computed(() => labels[props.item.kind] ?? '未知')
const timeLabel = computed(() => new Date(props.item.createdAt).toLocaleString())
</script>
