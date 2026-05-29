<template>
  <div class="flex items-center gap-3 border-b border-gray-200 bg-white p-3 dark:border-dark-border dark:bg-dark-bg">
    <input
      :value="searchQuery"
      class="w-80 rounded-md border border-gray-200 bg-gray-50 px-3 py-2 text-sm outline-none focus:border-primary dark:border-dark-border dark:bg-dark-hover"
      placeholder="搜索内容、来源应用、OCR 文本..."
      @input="$emit('update:searchQuery', ($event.target as HTMLInputElement).value)"
      @keydown.enter="$emit('search')"
    />
    <select
      :value="kind"
      class="rounded-md border border-gray-200 bg-gray-50 px-3 py-2 text-sm dark:border-dark-border dark:bg-dark-hover"
      @change="$emit('update:kind', ($event.target as HTMLSelectElement).value)"
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
    <button class="rounded bg-primary px-3 py-2 text-sm text-white" @click="$emit('search')">搜索</button>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  searchQuery: string
  kind: string
}>()

defineEmits<{
  'update:searchQuery': [value: string]
  'update:kind': [value: string]
  search: []
}>()
</script>
