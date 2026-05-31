<template>
  <div class="flex items-center gap-3 border-b border-gray-200 bg-white p-3 dark:border-dark-border dark:bg-dark-bg">
    <input
      :value="searchQuery"
      class="w-80 rounded-md border border-gray-200 bg-gray-50 px-3 py-2 text-sm outline-none focus:border-primary dark:border-dark-border dark:bg-dark-hover"
      placeholder="搜索内容、来源应用、OCR 文本..."
      @input="handleSearchInput"
      @keydown.enter="searchImmediately"
    />
    <select
      :value="kind"
      class="rounded-md border border-gray-200 bg-gray-50 px-3 py-2 text-sm dark:border-dark-border dark:bg-dark-hover"
      @change="handleKindChange"
    >
      <option value="all">全部</option>
      <option value="text">文本</option>
      <option value="html">富文本</option>
      <option value="image">图片</option>
      <option value="file">文件</option>
      <option value="url">链接</option>
      <option value="color">颜色</option>
      <option value="security_event">安全事件</option>
    </select>
    <select
      :value="datePreset"
      class="rounded-md border border-gray-200 bg-gray-50 px-3 py-2 text-sm dark:border-dark-border dark:bg-dark-hover"
      @change="handleDatePresetChange"
    >
      <option value="all">全部时间</option>
      <option value="today">今天</option>
      <option value="yesterday">昨天</option>
      <option value="last7Days">近 7 天</option>
      <option value="last30Days">近 30 天</option>
      <option value="custom">自定义</option>
    </select>
    <template v-if="datePreset === 'custom'">
      <input
        :value="customStartDate"
        type="date"
        class="rounded-md border border-gray-200 bg-gray-50 px-3 py-2 text-sm dark:border-dark-border dark:bg-dark-hover"
        @change="handleCustomStartChange"
      />
      <input
        :value="customEndDate"
        type="date"
        class="rounded-md border border-gray-200 bg-gray-50 px-3 py-2 text-sm dark:border-dark-border dark:bg-dark-hover"
        @change="handleCustomEndChange"
      />
    </template>
    <button class="rounded-md border border-gray-200 bg-white px-3 py-2 text-sm text-gray-700 shadow-sm hover:border-gray-300 hover:bg-gray-50 dark:border-dark-border dark:bg-dark-panel dark:text-gray-200 dark:hover:bg-dark-hover" @click="searchImmediately">搜索</button>
    <button data-test="clipboard-settings-button" class="ml-auto rounded-md border border-gray-200 bg-white px-3 py-2 text-sm font-medium text-gray-700 shadow-sm hover:border-gray-300 hover:bg-gray-50 dark:border-dark-border dark:bg-dark-panel dark:text-gray-200 dark:hover:bg-dark-hover" @click="$emit('openSettings')">
      剪贴板设置
    </button>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount } from 'vue'
import type { ClipboardDateRangePreset } from '../types/clipboard'

defineProps<{
  searchQuery: string
  kind: string
  datePreset: ClipboardDateRangePreset
  customStartDate: string
  customEndDate: string
}>()

const emit = defineEmits<{
  'update:searchQuery': [value: string]
  'update:kind': [value: string]
  'update:datePreset': [value: ClipboardDateRangePreset]
  'update:customStartDate': [value: string]
  'update:customEndDate': [value: string]
  search: []
  openSettings: []
}>()

let searchTimer: ReturnType<typeof setTimeout> | null = null

function scheduleSearch() {
  if (searchTimer) {
    clearTimeout(searchTimer)
  }
  searchTimer = setTimeout(() => {
    searchTimer = null
    emit('search')
  }, 250)
}

function searchImmediately() {
  if (searchTimer) {
    clearTimeout(searchTimer)
    searchTimer = null
  }
  emit('search')
}

function handleSearchInput(event: Event) {
  emit('update:searchQuery', (event.target as HTMLInputElement).value)
  scheduleSearch()
}

function handleKindChange(event: Event) {
  emit('update:kind', (event.target as HTMLSelectElement).value)
  searchImmediately()
}

function handleDatePresetChange(event: Event) {
  emit('update:datePreset', (event.target as HTMLSelectElement).value as ClipboardDateRangePreset)
  searchImmediately()
}

function handleCustomStartChange(event: Event) {
  emit('update:customStartDate', (event.target as HTMLInputElement).value)
  searchImmediately()
}

function handleCustomEndChange(event: Event) {
  emit('update:customEndDate', (event.target as HTMLInputElement).value)
  searchImmediately()
}

onBeforeUnmount(() => {
  if (searchTimer) {
    clearTimeout(searchTimer)
  }
})
</script>
