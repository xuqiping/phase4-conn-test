<template>
  <div class="min-h-0 flex-1 overflow-auto p-3">
    <div v-if="items.length === 0" class="flex h-full items-center justify-center text-sm text-gray-400">
      暂无剪贴板历史
    </div>
    <div v-else class="space-y-2">
      <ClipboardItemRow
        v-for="item in items"
        :key="item.id"
        :item="item"
        :selected="item.id === selectedItemId"
        @select="$emit('select', $event)"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import ClipboardItemRow from './ClipboardItemRow.vue'
import type { ClipboardItemSummary } from '../types/clipboard'

defineProps<{
  items: ClipboardItemSummary[]
  selectedItemId: string | null
}>()

defineEmits<{
  select: [id: string]
}>()
</script>
