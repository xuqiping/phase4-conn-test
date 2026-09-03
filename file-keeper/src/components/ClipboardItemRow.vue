<template>
  <button
    type="button"
    :class="[
      'w-full text-left rounded-lg border px-3 py-2 transition-colors',
      isHighlighted
        ? 'border-primary/30 bg-primary/5 shadow-sm ring-1 ring-primary/15 dark:border-primary/30 dark:bg-primary/10 dark:ring-primary/20'
        : 'border-gray-200 bg-white hover:border-gray-300 hover:bg-gray-50 dark:border-dark-border dark:bg-dark-panel dark:hover:bg-dark-hover'
    ]"
    @click="$emit('select', item.id)"
    @dblclick.stop="$emit('copy', item.id)"
    @contextmenu="$emit('contextmenu', $event)"
    @keydown="handleKeydown"
  >
    <div class="flex items-start gap-3">
      <input
        type="checkbox"
        class="mt-1"
        :checked="checked"
        @click.stop="$emit('toggleSelected', item.id)"
      />
      <div class="min-w-0 flex-1">
        <div class="flex items-center gap-2">
          <span class="text-xs font-medium text-primary">{{ kindLabel }}</span>
          <span v-if="item.isPinned" class="text-[11px] text-amber-600 dark:text-amber-300" :aria-label="t('clipboard.actions.pinned')">{{ t('clipboard.actions.pinned') }}</span>
          <span v-if="item.cacheState === 'cached'" class="text-[11px] text-green-600">{{ t('clipboard.cacheState.cached') }}</span>
          <span v-if="item.cacheState === 'reference_only'" class="text-[11px] text-amber-600">{{ t('clipboard.cacheState.referenceOnly') }}</span>
        </div>
        <div class="mt-1 truncate text-sm font-medium">{{ item.title }}</div>
        <div class="mt-1 line-clamp-2 text-xs text-gray-500 dark:text-gray-400">{{ item.summary }}</div>
        <div v-if="item.note" class="mt-1 line-clamp-1 text-xs text-amber-600 dark:text-amber-300">{{ t('clipboard.notePrefix') }}{{ item.note }}</div>
        <div class="mt-2 flex items-center justify-between text-[11px] text-gray-400">
          <span>{{ item.sourceApp?.processName || t('clipboard.unknownSource') }}</span>
          <span>{{ timeLabel }}</span>
        </div>
      </div>
      <div v-if="thumbnailSrc" class="flex h-12 w-12 flex-shrink-0 items-center justify-center rounded bg-gray-100 p-1 dark:bg-dark-hover">
        <img :src="thumbnailSrc" class="max-h-full max-w-full object-contain" alt="" loading="lazy" />
      </div>
    </div>
  </button>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from '../composables/useI18n'
import { convertFileSrc } from '@tauri-apps/api/core'
import type { ClipboardItemSummary } from '../types/clipboard'

const { t } = useI18n()
const props = defineProps<{
  item: ClipboardItemSummary
  selected: boolean
  checked: boolean
}>()

const emit = defineEmits<{
  select: [id: string]
  toggleSelected: [id: string]
  copy: [id: string]
  contextmenu: [event: MouseEvent | KeyboardEvent]
}>()

const isHighlighted = computed(() => props.checked)
const kindLabel = computed(() => t(`clipboard.kindLabels.${props.item.kind}`) || t('clipboard.kindLabels.unknown'))
const timeLabel = computed(() => new Date(props.item.createdAt).toLocaleString())
const thumbnailSrc = computed(() => props.item.thumbnailPath ? convertLocalPath(props.item.thumbnailPath) : '')

function convertLocalPath(path: string) {
  if (/^(asset|https?|data|blob):/i.test(path)) return path
  return convertFileSrc(path)
}

function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'ContextMenu' || (event.shiftKey && event.key === 'F10')) {
    event.preventDefault()
    emit('contextmenu', event)
  }
}
</script>
