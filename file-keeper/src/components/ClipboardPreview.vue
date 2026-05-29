<template>
  <aside class="flex h-full min-h-0 flex-col border-l border-gray-200 bg-white p-4 dark:border-dark-border dark:bg-dark-panel">
    <div v-if="!item" class="flex flex-1 items-center justify-center text-sm text-gray-400">
      选择一条历史记录查看预览
    </div>

    <template v-else>
      <div class="mb-4">
        <div class="text-xs text-gray-500">{{ kindLabel }}</div>
        <h3 class="mt-1 text-base font-semibold text-gray-900 dark:text-gray-100">{{ item.title }}</h3>
        <p class="mt-1 text-xs text-gray-500">{{ item.sourceApp?.processName || '未知来源' }}</p>
        <p v-if="detail?.imageWidth && detail?.imageHeight" class="mt-1 text-xs text-gray-500">
          {{ detail.imageWidth }}×{{ detail.imageHeight }} · {{ detail.imageFormat }}
        </p>
      </div>

      <div class="min-h-0 flex-1 overflow-auto rounded-lg bg-gray-50 p-3 text-sm dark:bg-dark-hover">
        <img v-if="detail?.imagePath" :src="detail.imagePath" class="max-w-full rounded" alt="剪贴板图片" />
        <div v-else-if="detail?.files" class="space-y-2">
          <div v-for="file in detail.files" :key="file.originalPath" class="rounded border border-gray-200 bg-white p-2 text-xs dark:border-dark-border dark:bg-dark-panel">
            <div class="font-medium">{{ file.name }}</div>
            <div class="text-gray-500">{{ file.originalPath }}</div>
            <div class="mt-1 text-gray-400">{{ file.copyState === 'cached' ? '已保存副本' : '仅保存引用' }}</div>
          </div>
        </div>
        <div v-else-if="detail?.url" class="space-y-2">
          <div class="text-sm font-medium">{{ detail.urlTitle || detail.url }}</div>
          <a class="break-all text-primary" :href="detail.url">{{ detail.url }}</a>
          <p class="text-xs text-gray-500">{{ detail.urlDescription || '链接预览未联网抓取' }}</p>
        </div>
        <div v-else-if="detail?.colorHex" class="space-y-3">
          <div class="h-24 rounded" :style="{ backgroundColor: detail.colorHex }"></div>
          <div class="font-mono text-sm">{{ detail.colorHex }}</div>
          <div class="font-mono text-sm">{{ detail.colorRgb }}</div>
        </div>
        <pre v-else class="whitespace-pre-wrap break-words font-sans">{{ previewText }}</pre>
      </div>

      <div class="mt-4 flex flex-wrap gap-2">
        <button class="rounded bg-primary px-3 py-1.5 text-sm text-white" @click="$emit('copy', item.id, 'original')">复制</button>
        <button class="rounded bg-gray-100 px-3 py-1.5 text-sm dark:bg-dark-hover" @click="$emit('paste', item.id, 'original')">粘贴</button>
        <button class="rounded bg-gray-100 px-3 py-1.5 text-sm dark:bg-dark-hover" @click="$emit('copy', item.id, 'plain_text')">纯文本</button>
        <button class="rounded bg-red-50 px-3 py-1.5 text-sm text-red-600 dark:bg-red-900/20" @click="$emit('delete', item.id)">删除</button>
      </div>
    </template>
  </aside>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { ClipboardItemDetail, ClipboardItemSummary, ClipboardPasteFormat } from '../types/clipboard'

const props = defineProps<{
  item: ClipboardItemSummary | null
  detail: ClipboardItemDetail | null
}>()

defineEmits<{
  copy: [id: string, format: ClipboardPasteFormat]
  paste: [id: string, format: ClipboardPasteFormat]
  delete: [id: string]
}>()

const labels: Record<string, string> = {
  text: '文本',
  html: '富文本',
  image: '图片',
  file: '文件',
  url: '链接',
  color: '颜色',
  mixed: '混合',
  security_event: '安全事件'
}

const kindLabel = computed(() => props.item ? labels[props.item.kind] : '')
const previewText = computed(() => props.detail?.text || props.detail?.markdown || props.item?.summary || '')
</script>
