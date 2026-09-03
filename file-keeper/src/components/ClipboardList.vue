<template>
  <div class="min-h-0 flex-1 overflow-auto p-3" @click="closeContextMenu()">
    <div class="sticky top-0 z-20 mb-3 flex items-center justify-between rounded-lg border border-[var(--border-color)] bg-[var(--bg-primary)] px-3 py-2 text-sm shadow-sm">
      <span class="text-[var(--text-secondary)]">
        <template v-if="selectedIds.size > 0">{{ t('clipboard.stats.selectedCount', { count: selectedIds.size }) }}</template>
        <template v-else>{{ t('clipboard.stats.totalCount', { count: items.length }) }}</template>
      </span>
      <div class="flex items-center gap-2">
        <button class="inline-flex items-center rounded-md border border-[var(--border-color)] bg-[var(--bg-primary)] px-3 py-2 text-sm text-[var(--text-primary)] shadow-sm transition-colors hover:border-[var(--border-hover)] hover:bg-[var(--bg-hover)] disabled:cursor-not-allowed disabled:opacity-50" :disabled="items.length === 0" @click.stop="$emit('selectAll')">
          {{ t('clipboard.actions.selectAll') }}
        </button>
        <button class="inline-flex items-center rounded-md border border-[var(--border-color)] bg-[var(--bg-primary)] px-3 py-2 text-sm text-[var(--text-primary)] shadow-sm transition-colors hover:border-[var(--border-hover)] hover:bg-[var(--bg-hover)] disabled:cursor-not-allowed disabled:opacity-50" :disabled="items.length === 0" @click.stop="$emit('invertSelection')">
          {{ t('clipboard.actions.invertSelection') }}
        </button>
        <button class="inline-flex items-center rounded-md border border-[var(--border-color)] bg-[var(--bg-primary)] px-3 py-2 text-sm text-[var(--text-primary)] shadow-sm transition-colors hover:border-[var(--border-hover)] hover:bg-[var(--bg-hover)] disabled:cursor-not-allowed disabled:opacity-50" :disabled="selectedIds.size === 0" @click.stop="$emit('copySelected')">
          {{ t('clipboard.actions.batchCopy') }}
        </button>
        <button data-test="batch-pin" class="inline-flex items-center rounded-md border border-[var(--border-color)] bg-[var(--bg-primary)] px-3 py-2 text-sm text-[var(--text-primary)] shadow-sm transition-colors hover:bg-[var(--bg-hover)] disabled:cursor-not-allowed disabled:opacity-50" :disabled="selectedIds.size === 0" @click.stop="$emit('setSelectedPinned', true)">
          {{ t('clipboard.actions.pinSelected') }}
        </button>
        <button data-test="batch-unpin" class="inline-flex items-center rounded-md border border-[var(--border-color)] bg-[var(--bg-primary)] px-3 py-2 text-sm text-[var(--text-primary)] shadow-sm transition-colors hover:border-[var(--border-hover)] hover:bg-[var(--bg-hover)] disabled:cursor-not-allowed disabled:opacity-50" :disabled="selectedIds.size === 0" @click.stop="$emit('setSelectedPinned', false)">
          {{ t('clipboard.actions.unpinSelected') }}
        </button>
        <select
          data-test="batch-move"
          class="rounded-md border border-[var(--border-color)] bg-[var(--bg-primary)] px-2 py-2 text-sm text-[var(--text-primary)] disabled:cursor-not-allowed disabled:opacity-50"
          :disabled="selectedIds.size === 0"
          :aria-label="t('clipboard.actions.moveSelectedToGroup')"
          @change="moveSelected($event)"
        >
          <option value="">{{ t('clipboard.actions.moveSelectedToGroup') }}</option>
          <option value="__ungrouped__">{{ t('clipboard.groups.ungrouped') }}</option>
          <option v-for="group in groups" :key="group.id" :value="group.id">{{ group.name }}</option>
        </select>
        <button class="inline-flex items-center rounded-md border border-[var(--danger-subtle-border)] bg-[var(--bg-primary)] px-3 py-2 text-sm text-[var(--danger-subtle-text)] shadow-sm transition-colors hover:bg-[var(--danger-subtle-bg)] disabled:cursor-not-allowed disabled:opacity-50" :disabled="selectedIds.size === 0" @click.stop="$emit('deleteSelected')">
          {{ t('clipboard.actions.deleteSelected') }}
        </button>
        <button class="inline-flex items-center rounded-md border border-[var(--border-color)] bg-[var(--bg-primary)] px-3 py-2 text-sm text-[var(--text-primary)] shadow-sm transition-colors hover:border-[var(--border-hover)] hover:bg-[var(--bg-hover)] disabled:cursor-not-allowed disabled:opacity-50" :disabled="selectedIds.size === 0" @click.stop="$emit('clearSelection')">
          {{ t('clipboard.actions.clearSelection') }}
        </button>
      </div>
    </div>

    <div v-if="items.length === 0" class="flex h-full items-center justify-center text-sm text-gray-400">
      {{ t('clipboard.emptyState') }}
    </div>
    <div v-else class="space-y-2">
      <ClipboardItemRow
        v-for="item in items"
        :key="item.id"
        :item="item"
        :selected="item.id === selectedItemId"
        :checked="selectedIds.has(item.id)"
        @select="selectItem(item.id)"
        @toggle-selected="selectItem(item.id)"
        @copy="copyItem"
        @contextmenu="openContextMenu($event, item)"
      />
    </div>

    <div
      v-if="contextMenu"
      ref="contextMenuRef"
      role="menu"
      :aria-label="t('clipboard.actions.contextMenu')"
      class="fixed z-[90] min-w-32 rounded-lg border border-gray-200 bg-white py-1 text-sm shadow-lg dark:border-dark-border dark:bg-dark-panel"
      :style="{ left: `${contextMenu.x}px`, top: `${contextMenu.y}px` }"
      @click.stop
    >
      <button role="menuitem" class="block w-full px-3 py-2 text-left text-[var(--text-primary)] hover:bg-[var(--bg-hover)]" @click="copyContextItem">
        {{ contextMenu.kind === 'file' ? t('clipboard.actions.copyFile') : t('clipboard.actions.copy') }}
      </button>
      <button
        v-if="contextMenu.kind === 'url'"
        class="block w-full px-3 py-2 text-left text-[var(--text-primary)] hover:bg-[var(--bg-hover)]"
        @click="openContextItemUrl"
      >
        {{ t('clipboard.actions.openLink') }}
      </button>
      <button
        v-if="contextMenu.kind === 'file' || contextMenu.kind === 'image'"
        class="block w-full px-3 py-2 text-left text-[var(--text-primary)] hover:bg-[var(--bg-hover)]"
        @click="openContextItemFile"
      >
        {{ t('clipboard.actions.openFile') }}
      </button>
      <button
        v-if="contextMenu.kind === 'file' || contextMenu.kind === 'image'"
        class="block w-full px-3 py-2 text-left text-[var(--text-primary)] hover:bg-[var(--bg-hover)]"
        @click="openContextItemFolder"
      >
        {{ t('clipboard.actions.showInFolder') }}
      </button>
      <button
        v-if="contextMenu.kind === 'file'"
        class="block w-full px-3 py-2 text-left text-[var(--text-primary)] hover:bg-[var(--bg-hover)]"
        @click="copyContextItemPath"
      >
        {{ t('clipboard.actions.copyFilePath') }}
      </button>
      <button class="block w-full px-3 py-2 text-left text-[var(--text-primary)] hover:bg-[var(--bg-hover)]" @click="editContextItemNote">
        {{ t('clipboard.actions.editNote') }}
      </button>
      <button data-test="context-pin" class="block w-full px-3 py-2 text-left text-[var(--text-primary)] hover:bg-[var(--bg-hover)]" @click="pinContextItem">
        {{ contextMenu.isPinned ? t('clipboard.actions.unpin') : t('clipboard.actions.pin') }}
      </button>
      <div class="border-y border-[var(--border-color)] py-1" role="group" :aria-label="t('clipboard.actions.moveToGroup')">
        <div class="px-3 py-1 text-xs text-[var(--text-secondary)]">{{ t('clipboard.actions.moveToGroup') }}</div>
        <button data-test="context-move-ungrouped" class="block w-full px-3 py-2 text-left text-[var(--text-primary)] hover:bg-[var(--bg-hover)]" @click="moveContextItem(null)">
          {{ t('clipboard.groups.ungrouped') }}
        </button>
        <button
          v-for="group in groups"
          :key="group.id"
          :data-test="`context-move-${group.id}`"
          class="block w-full px-3 py-2 text-left text-[var(--text-primary)] hover:bg-[var(--bg-hover)]"
          @click="moveContextItem(group.id)"
        >
          {{ group.name }}
        </button>
      </div>
      <button class="block w-full px-3 py-2 text-left text-[var(--danger-subtle-text)] hover:bg-[var(--danger-subtle-bg)]" @click="deleteContextItem">
        {{ t('clipboard.actions.delete') }}
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref } from 'vue'
import { useI18n } from '../composables/useI18n'
import ClipboardItemRow from './ClipboardItemRow.vue'
import type { ClipboardGroup, ClipboardItemSummary } from '../types/clipboard'

withDefaults(defineProps<{
  items: ClipboardItemSummary[]
  selectedItemId: string | null
  selectedIds: Set<string>
  groups?: ClipboardGroup[]
}>(), {
  groups: () => []
})

const { t } = useI18n()

const emit = defineEmits<{
  select: [id: string]
  toggleSelected: [id: string]
  copy: [id: string]
  copySelected: []
  openUrl: [id: string]
  openFile: [id: string]
  openFolder: [id: string]
  copyFilePath: [id: string]
  editNote: [id: string]
  delete: [id: string]
  deleteSelected: []
  selectAll: []
  invertSelection: []
  clearSelection: []
  setPinned: [id: string, pinned: boolean]
  moveToGroup: [id: string, groupId: string | null]
  setSelectedPinned: [pinned: boolean]
  moveSelectedToGroup: [groupId: string | null]
}>()

const contextMenu = ref<{ id: string; kind: ClipboardItemSummary['kind']; isPinned: boolean; x: number; y: number } | null>(null)
const contextMenuRef = ref<HTMLElement | null>(null)
let contextTrigger: HTMLElement | null = null

window.addEventListener('keydown', handleMenuKeydown)
onBeforeUnmount(() => window.removeEventListener('keydown', handleMenuKeydown))

function selectItem(id: string) {
  emit('select', id)
  emit('toggleSelected', id)
}

function copyItem(id: string) {
  emit('copy', id)
}

function openContextMenu(event: MouseEvent | KeyboardEvent, item: ClipboardItemSummary) {
  event.preventDefault()
  emit('select', item.id)
  contextTrigger = event.currentTarget instanceof HTMLElement ? event.currentTarget : null
  const x = event instanceof MouseEvent ? event.clientX : contextTrigger?.getBoundingClientRect().left ?? 0
  const y = event instanceof MouseEvent ? event.clientY : contextTrigger?.getBoundingClientRect().bottom ?? 0
  contextMenu.value = { id: item.id, kind: item.kind, isPinned: item.isPinned, x, y }
  void nextTick(() => contextMenuRef.value?.querySelector<HTMLButtonElement>('button')?.focus())
}

function closeContextMenu(restoreFocus = false) {
  contextMenu.value = null
  if (restoreFocus) contextTrigger?.focus()
}

function handleMenuKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && contextMenu.value) {
    event.preventDefault()
    closeContextMenu(true)
  }
}

function moveSelected(event: Event) {
  const select = event.target as HTMLSelectElement
  if (!select.value) return
  emit('moveSelectedToGroup', select.value === '__ungrouped__' ? null : select.value)
  select.value = ''
}

function copyContextItem() {
  if (!contextMenu.value) return
  emit('copy', contextMenu.value.id)
  contextMenu.value = null
}

function openContextItemUrl() {
  if (!contextMenu.value) return
  emit('openUrl', contextMenu.value.id)
  contextMenu.value = null
}

function openContextItemFile() {
  if (!contextMenu.value) return
  emit('openFile', contextMenu.value.id)
  contextMenu.value = null
}

function openContextItemFolder() {
  if (!contextMenu.value) return
  emit('openFolder', contextMenu.value.id)
  contextMenu.value = null
}

function copyContextItemPath() {
  if (!contextMenu.value) return
  emit('copyFilePath', contextMenu.value.id)
  contextMenu.value = null
}

function editContextItemNote() {
  if (!contextMenu.value) return
  emit('editNote', contextMenu.value.id)
  contextMenu.value = null
}

function pinContextItem() {
  if (!contextMenu.value) return
  emit('setPinned', contextMenu.value.id, !contextMenu.value.isPinned)
  closeContextMenu(true)
}

function moveContextItem(groupId: string | null) {
  if (!contextMenu.value) return
  emit('moveToGroup', contextMenu.value.id, groupId)
  closeContextMenu(true)
}

function deleteContextItem() {
  if (!contextMenu.value) return
  emit('delete', contextMenu.value.id)
  contextMenu.value = null
}
</script>
