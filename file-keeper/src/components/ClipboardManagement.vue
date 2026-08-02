<template>
  <section class="flex min-h-0 flex-1 flex-col overflow-hidden bg-gray-50 dark:bg-dark-bg">
    <ClipboardToolbar
      v-model:search-query="clipboardStore.searchQuery"
      v-model:kind="clipboardStore.kindFilter"
      v-model:date-preset="clipboardStore.datePreset"
      v-model:custom-start-date="clipboardStore.customStartDate"
      v-model:custom-end-date="clipboardStore.customEndDate"
      @search="clipboardStore.searchItems"
      @open-settings="showSettings = true"
    />

    <div
      v-if="copyNotice"
      class="pointer-events-none fixed left-1/2 top-20 z-[90] -translate-x-1/2 rounded-lg border px-4 py-2 text-sm shadow-lg"
      :class="copyNotice.type === 'success'
        ? 'border-primary/30 bg-primary/5 text-primary dark:border-primary/30 dark:bg-primary/10 dark:text-green-300'
        : 'border-red-200 bg-red-50 text-red-700 dark:border-red-900/60 dark:bg-red-900/20 dark:text-red-300'
      "
    >
      {{ copyNotice.message }}
    </div>

    <div class="grid min-h-0 flex-1 grid-cols-[180px_minmax(320px,1fr)_360px] overflow-hidden">
      <aside class="space-y-3 border-r border-gray-200 p-3 dark:border-dark-border">
        <h2 class="text-base font-semibold">{{ t('clipboard.title') }}</h2>
        <nav class="space-y-1 text-sm">
          <button v-for="filter in filters" :key="filter.kind" class="block w-full rounded px-2 py-1.5 text-left hover:bg-gray-100 dark:hover:bg-dark-hover" @click="setKind(filter.kind)">
            {{ filter.label }}
          </button>
        </nav>
        <ClipboardStorageUsage :usage="clipboardStore.storageUsage" @clear-cache="clearNonTextCache" />
        <ClipboardSecurityEvents />
        <button class="w-full rounded bg-gray-100 px-3 py-2 text-sm dark:bg-dark-hover" @click="showSettings = true">
          {{ t('clipboard.settings') }}
        </button>
      </aside>

      <ClipboardList
        :items="clipboardStore.items"
        :selected-item-id="clipboardStore.selectedItemId"
        :selected-ids="clipboardStore.selectedIds"
        @select="selectItem"
        @toggle-selected="clipboardStore.toggleSelected"
        @copy="copyContextItems"
        @copy-selected="copySelectedItems"
        @open-url="openItemUrl"
        @open-file="openItemFile"
        @open-folder="openItemFolder"
        @copy-file-path="copyItemFilePath"
        @edit-note="editItemNote"
        @delete="deleteItem"
        @delete-selected="deleteSelectedItems"
        @select-all="clipboardStore.selectAllVisible"
        @invert-selection="clipboardStore.invertVisibleSelection"
        @clear-selection="clipboardStore.clearSelection"
      />

      <ClipboardPreview
        :item="clipboardStore.selectedItem"
        :detail="clipboardStore.selectedDetail"
        :note-focus-key="noteFocusKey"
        :note-editing="noteEditing"
        @copy="copySingleItem"
        @paste="clipboardStore.pasteItem"
        @delete="clipboardStore.deleteItem"
        @save-note="saveItemNote"
      />
    </div>

    <ClipboardSettings
      v-if="showSettings"
      :settings="clipboardStore.settings"
      @close="showSettings = false"
      @save="saveSettings"
    />
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from '../composables/useI18n'
import { openUrl } from '@tauri-apps/plugin-opener'
import { clearClipboardHistory } from '../api/clipboard'
import { openFile, showInFolder } from '../api/files'
import { useClipboardStore } from '../stores/clipboardStore'
import ClipboardList from './ClipboardList.vue'
import ClipboardPreview from './ClipboardPreview.vue'
import ClipboardSecurityEvents from './ClipboardSecurityEvents.vue'
import ClipboardSettings from './ClipboardSettings.vue'
import ClipboardStorageUsage from './ClipboardStorageUsage.vue'
import ClipboardToolbar from './ClipboardToolbar.vue'
import type { ClipboardKind, ClipboardPasteFormat, ClipboardSettings as ClipboardSettingsType } from '../types/clipboard'

const clipboardStore = useClipboardStore()
const showSettings = ref(false)
const { t } = useI18n()
const copyNotice = ref<{ type: 'success' | 'error'; message: string } | null>(null)
const noteFocusKey = ref(0)
const noteEditing = ref(false)
let copyNoticeTimer: ReturnType<typeof setTimeout> | null = null
let noteEditingTimer: ReturnType<typeof setTimeout> | null = null

const filters = computed<Array<{ kind: ClipboardKind | 'all'; label: string }>>(() => [
  { kind: 'all', label: t('clipboard.kindLabels.all') },
  { kind: 'text', label: t('clipboard.kindLabels.text') },
  { kind: 'html', label: t('clipboard.kindLabels.html') },
  { kind: 'image', label: t('clipboard.kindLabels.image') },
  { kind: 'file', label: t('clipboard.kindLabels.file') },
  { kind: 'url', label: t('clipboard.kindLabels.url') },
  { kind: 'color', label: t('clipboard.kindLabels.color') },
  { kind: 'security_event', label: t('clipboard.kindLabels.security_event') }
])

onMounted(() => {
  window.addEventListener('keydown', handleKeydown)
  void clipboardStore.loadItems()
  void clipboardStore.loadSettings()
  setTimeout(() => {
    void clipboardStore.refreshStorageUsage()
  }, 300)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleKeydown)
  if (copyNoticeTimer) {
    clearTimeout(copyNoticeTimer)
  }
  if (noteEditingTimer) {
    clearTimeout(noteEditingTimer)
  }
})

function setKind(kind: ClipboardKind | 'all') {
  clipboardStore.kindFilter = kind
  clipboardStore.searchItems()
}

function selectItem(id: string) {
  clipboardStore.loadDetail(id)
}

function handleKeydown(event: KeyboardEvent) {
  const target = event.target
  const element = target instanceof HTMLElement ? target : null
  const tagName = element?.tagName.toLowerCase()
  if (tagName === 'input' || tagName === 'textarea' || tagName === 'select' || element?.isContentEditable) {
    return
  }
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'c' && clipboardStore.selectedIds.size > 0) {
    event.preventDefault()
    void copySelectedItems()
  }
}

function showCopyNotice(type: 'success' | 'error', message: string) {
  copyNotice.value = { type, message }
  if (copyNoticeTimer) {
    clearTimeout(copyNoticeTimer)
  }
  copyNoticeTimer = setTimeout(() => {
    copyNotice.value = null
    copyNoticeTimer = null
  }, 2200)
}

function copyErrorMessage(err: unknown) {
  return err instanceof Error ? err.message : String(err)
}

async function copySingleItem(id: string, format: ClipboardPasteFormat = 'original') {
  try {
    await clipboardStore.copyItem(id, format)
    showCopyNotice('success', t('clipboard.notices.copied'))
  } catch (err) {
    showCopyNotice('error', t('clipboard.notices.copyFailed', { error: copyErrorMessage(err) }))
  }
}

async function copySelectedItems() {
  if (clipboardStore.selectedIds.size === 0) return
  try {
    const copiedCount = await clipboardStore.copySelectedItems()
    if (copiedCount) {
      showCopyNotice('success', t('clipboard.notices.copiedCount', { count: copiedCount }))
    }
  } catch (err) {
    showCopyNotice('error', t('clipboard.notices.copyFailed', { error: copyErrorMessage(err) }))
  }
}

async function copyContextItems(id: string) {
  const ids = clipboardStore.selectedIdsForAction(id)
  try {
    if (ids.length > 1) {
      const copiedCount = await clipboardStore.copySelectedItems()
      showCopyNotice('success', t('clipboard.notices.copiedCount', { count: copiedCount || ids.length }))
    } else {
      await clipboardStore.copyItem(ids[0])
      showCopyNotice('success', t('clipboard.notices.copied'))
    }
  } catch (err) {
    showCopyNotice('error', t('clipboard.notices.copyFailed', { error: copyErrorMessage(err) }))
  }
}

function firstFilePath(files: Awaited<ReturnType<typeof clipboardStore.loadDetail>>['files']) {
  return files?.[0]?.cachedPath || files?.[0]?.originalPath || null
}

async function openItemUrl(id: string) {
  try {
    const detail = await clipboardStore.loadDetail(id)
    const url = detail.url || detail.text || detail.summary || detail.title
    if (!/^https?:\/\//i.test(url)) {
      showCopyNotice('error', t('clipboard.notices.invalidLink'))
      return
    }
    await openUrl(url)
    showCopyNotice('success', t('clipboard.notices.linkOpened'))
  } catch (err) {
    showCopyNotice('error', t('clipboard.notices.openLinkFailed', { error: copyErrorMessage(err) }))
  }
}

async function openItemFile(id: string) {
  try {
    const detail = await clipboardStore.loadDetail(id)
    const path = firstFilePath(detail.files) || detail.imagePath
    if (!path) {
      showCopyNotice('error', t('clipboard.notices.noFilePath'))
      return
    }
    await openFile(path)
    showCopyNotice('success', t('clipboard.notices.fileOpened'))
  } catch (err) {
    showCopyNotice('error', t('clipboard.notices.openFileFailed', { error: copyErrorMessage(err) }))
  }
}

async function openItemFolder(id: string) {
  try {
    const detail = await clipboardStore.loadDetail(id)
    const path = firstFilePath(detail.files) || detail.imagePath
    if (!path) {
      showCopyNotice('error', t('clipboard.notices.noFolderPath'))
      return
    }
    await showInFolder(path)
    showCopyNotice('success', t('clipboard.notices.folderOpened'))
  } catch (err) {
    showCopyNotice('error', t('clipboard.notices.openFolderFailed', { error: copyErrorMessage(err) }))
  }
}

async function copyItemFilePath(id: string) {
  try {
    await clipboardStore.copyItem(id, 'plain_text')
    showCopyNotice('success', t('clipboard.notices.filePathCopied'))
  } catch (err) {
    showCopyNotice('error', t('clipboard.notices.copyFilePathFailed', { error: copyErrorMessage(err) }))
  }
}

function showNoteEditor() {
  noteEditing.value = true
  showCopyNotice('success', t('clipboard.notices.noteEditorOpened'))
  if (noteEditingTimer) {
    clearTimeout(noteEditingTimer)
  }
  noteEditingTimer = setTimeout(() => {
    noteEditing.value = false
    noteEditingTimer = null
  }, 3000)
}

function focusNoteEditor() {
  void nextTick(() => {
    noteFocusKey.value += 1
  })
}

function editItemNote(id: string) {
  const detailPromise = Promise.resolve(clipboardStore.loadDetail(id))
  showNoteEditor()
  focusNoteEditor()
  void detailPromise
    .then(() => focusNoteEditor())
    .catch(err => showCopyNotice('error', t('clipboard.notices.openNoteEditorFailed', { error: copyErrorMessage(err) })))
}

async function saveItemNote(id: string, note: string) {
  try {
    const savedNote = await clipboardStore.updateItemNote(id, note)
    noteEditing.value = false
    showCopyNotice('success', savedNote ? t('clipboard.notices.noteSaved') : t('clipboard.notices.noteCleared'))
  } catch (err) {
    showCopyNotice('error', t('clipboard.notices.noteSaveFailed', { error: copyErrorMessage(err) }))
  }
}

async function deleteItem(id: string) {
  const ids = clipboardStore.selectedIdsForAction(id)
  if (ids.length > 1) {
    await clipboardStore.deleteSelectedItems()
  } else {
    await clipboardStore.deleteItem(ids[0])
  }
}

async function deleteSelectedItems() {
  await clipboardStore.deleteSelectedItems()
}

async function saveSettings(settings: ClipboardSettingsType) {
  await clipboardStore.updateSettings(settings)
  showSettings.value = false
}

async function clearNonTextCache() {
  await clearClipboardHistory('non_text_cache')
  await Promise.all([
    clipboardStore.refreshStorageUsage(),
    clipboardStore.loadItems()
  ])
}
</script>
