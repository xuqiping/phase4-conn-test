<template>
  <aside class="flex h-full min-h-0 flex-col border-l border-gray-200 bg-white p-4 dark:border-dark-border dark:bg-dark-panel">
    <div v-if="!item" class="flex flex-1 items-center justify-center text-sm text-gray-400">
      {{ t('clipboard.preview.empty') }}
    </div>

    <template v-else>
      <div class="mb-4">
        <div class="text-xs text-gray-500">{{ kindLabel }}</div>
        <h3 class="mt-1 text-base font-semibold text-gray-900 dark:text-gray-100">{{ item.title }}</h3>
        <p class="mt-1 text-xs text-gray-500">{{ item.sourceApp?.processName || t('clipboard.unknownSource') }}</p>
        <p v-if="detail?.imageWidth && detail?.imageHeight" class="mt-1 text-xs text-gray-500">
          {{ detail.imageWidth }}×{{ detail.imageHeight }} · {{ detail.imageFormat }}
        </p>
      </div>

      <div
        :class="[
          'mb-4 rounded-lg border p-3 transition-colors',
          noteEditing
            ? 'border-primary/40 bg-white shadow-sm ring-1 ring-primary/20 dark:border-primary/30 dark:bg-dark-panel dark:ring-primary/20'
            : 'border-gray-200 bg-gray-50 dark:border-dark-border dark:bg-dark-hover'
        ]"
      >
        <div class="flex items-center justify-between gap-2">
          <label class="text-xs font-medium text-gray-500">{{ t('clipboard.preview.note') }}</label>
          <span v-if="noteEditing" class="rounded-full border border-primary/30 bg-white px-2 py-0.5 text-xs text-primary dark:bg-dark-panel">{{ t('clipboard.preview.noteEditing') }}</span>
        </div>
        <textarea
          ref="noteInput"
          v-model="noteDraft"
          class="mt-2 min-h-20 w-full rounded-md border border-gray-200 bg-white px-3 py-2 text-sm outline-none focus:border-primary dark:border-dark-border dark:bg-dark-panel"
          :placeholder="t('clipboard.preview.notePlaceholder')"
        ></textarea>
        <div class="mt-2 flex justify-end gap-2">
          <button class="rounded bg-gray-100 px-3 py-1.5 text-sm dark:bg-dark-panel" @click="clearNote">{{ t('clipboard.actions.clear') }}</button>
          <button class="rounded border border-primary/30 bg-white px-3 py-1.5 text-sm font-medium text-primary hover:bg-gray-50 dark:bg-dark-panel dark:hover:bg-dark-hover" @click="saveNote">{{ t('clipboard.actions.saveNote') }}</button>
        </div>
      </div>

      <div class="min-h-0 flex-1 overflow-auto rounded-lg bg-gray-50 p-3 text-sm dark:bg-dark-hover">
        <div v-if="previewImageSrc" class="flex min-h-64 items-center justify-center rounded bg-white p-3 dark:bg-dark-panel">
          <img :src="previewImageSrc" class="max-h-[60vh] max-w-full object-contain" :alt="t('clipboard.preview.imageAlt')" />
        </div>
        <div v-else-if="detail?.files" class="space-y-2">
          <div v-for="file in detail.files" :key="file.originalPath" class="rounded border border-gray-200 bg-white p-2 text-xs dark:border-dark-border dark:bg-dark-panel">
            <div class="font-medium">{{ file.name }}</div>
            <div class="text-gray-500">{{ file.originalPath }}</div>
            <div class="mt-1 text-gray-400">{{ file.copyState === 'cached' ? t('clipboard.preview.cachedFile') : t('clipboard.preview.referenceOnlyFile') }}</div>
          </div>
        </div>
        <div v-else-if="detail?.url" class="space-y-2">
          <div class="text-sm font-medium">{{ detail.urlTitle || detail.url }}</div>
          <a class="break-all text-primary" :href="detail.url">{{ detail.url }}</a>
          <p class="text-xs text-gray-500">{{ detail.urlDescription || t('clipboard.preview.linkPreviewMissing') }}</p>
        </div>
        <div v-else-if="detail?.colorHex" class="space-y-3">
          <div class="h-24 rounded" :style="{ backgroundColor: detail.colorHex }"></div>
          <div class="font-mono text-sm">{{ detail.colorHex }}</div>
          <div class="font-mono text-sm">{{ detail.colorRgb }}</div>
        </div>
        <pre v-else class="whitespace-pre-wrap break-words font-sans">{{ previewText }}</pre>
      </div>

      <div class="mt-4 flex flex-wrap gap-2">
        <button class="rounded border border-primary/30 bg-white px-3 py-1.5 text-sm font-medium text-primary hover:bg-gray-50 dark:bg-dark-panel dark:hover:bg-dark-hover" @click="$emit('copy', item.id, 'original')">{{ t('clipboard.actions.copy') }}</button>
        <button class="rounded bg-gray-100 px-3 py-1.5 text-sm dark:bg-dark-hover" @click="$emit('paste', item.id, 'original')">{{ t('clipboard.actions.paste') }}</button>
        <button class="rounded bg-gray-100 px-3 py-1.5 text-sm dark:bg-dark-hover" @click="$emit('copy', item.id, 'plain_text')">{{ t('clipboard.actions.plainText') }}</button>
        <button class="rounded bg-red-50 px-3 py-1.5 text-sm text-red-600 dark:bg-red-900/20" @click="$emit('delete', item.id)">{{ t('clipboard.actions.delete') }}</button>
      </div>
    </template>
  </aside>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { convertFileSrc } from '@tauri-apps/api/core'
import { useI18n } from '../composables/useI18n'
import type { ClipboardItemDetail, ClipboardItemSummary, ClipboardPasteFormat } from '../types/clipboard'

const props = defineProps<{
  item: ClipboardItemSummary | null
  detail: ClipboardItemDetail | null
  noteFocusKey?: number
  noteEditing?: boolean
}>()

const emit = defineEmits<{
  copy: [id: string, format: ClipboardPasteFormat]
  paste: [id: string, format: ClipboardPasteFormat]
  delete: [id: string]
  saveNote: [id: string, note: string]
}>()

const { t } = useI18n()
const noteDraft = ref('')
const noteInput = ref<HTMLTextAreaElement | null>(null)

const kindLabel = computed(() => props.item ? t(`clipboard.kindLabels.${props.item.kind}`) : '')
const previewText = computed(() => props.detail?.text || props.detail?.markdown || props.item?.summary || '')
const previewImageSrc = computed(() => props.detail?.imagePath ? convertLocalPath(props.detail.imagePath) : '')

function selectedNote() {
  if (!props.item) return ''
  if (props.detail?.id === props.item.id) {
    return props.detail.note || ''
  }
  return props.item.note || ''
}

function convertLocalPath(path: string) {
  if (/^(asset|https?|data|blob):/i.test(path)) return path
  return convertFileSrc(path)
}

watch([
  () => props.item?.id,
  () => props.item?.note,
  () => props.detail?.id,
  () => props.detail?.note
], () => {
  noteDraft.value = selectedNote()
}, { immediate: true })

watch(() => props.noteFocusKey, async () => {
  if (!props.noteFocusKey) return
  await nextTick()
  noteInput.value?.focus()
  noteInput.value?.select()
}, { flush: 'post' })

function saveNote() {
  if (!props.item) return
  emit('saveNote', props.item.id, noteDraft.value)
}

function clearNote() {
  noteDraft.value = ''
  saveNote()
}
</script>
