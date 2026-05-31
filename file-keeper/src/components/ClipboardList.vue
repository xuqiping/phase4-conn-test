<template>
  <div class="min-h-0 flex-1 overflow-auto p-3" @click="closeContextMenu">
    <div class="sticky top-0 z-20 mb-3 flex items-center justify-between rounded-lg border border-[var(--border-color)] bg-[var(--bg-primary)] px-3 py-2 text-sm shadow-sm">
      <span class="text-[var(--text-secondary)]">
        <template v-if="selectedIds.size > 0">已选择 {{ selectedIds.size }} 条记录</template>
        <template v-else>共 {{ items.length }} 条记录</template>
      </span>
      <div class="flex items-center gap-2">
        <button class="inline-flex items-center rounded-md border border-[var(--border-color)] bg-[var(--bg-primary)] px-3 py-2 text-sm text-[var(--text-primary)] shadow-sm transition-colors hover:border-[var(--border-hover)] hover:bg-[var(--bg-hover)] disabled:cursor-not-allowed disabled:opacity-50" :disabled="items.length === 0" @click.stop="$emit('selectAll')">
          全选
        </button>
        <button class="inline-flex items-center rounded-md border border-[var(--border-color)] bg-[var(--bg-primary)] px-3 py-2 text-sm text-[var(--text-primary)] shadow-sm transition-colors hover:border-[var(--border-hover)] hover:bg-[var(--bg-hover)] disabled:cursor-not-allowed disabled:opacity-50" :disabled="items.length === 0" @click.stop="$emit('invertSelection')">
          反选
        </button>
        <button class="inline-flex items-center rounded-md border border-[var(--border-color)] bg-[var(--bg-primary)] px-3 py-2 text-sm text-[var(--text-primary)] shadow-sm transition-colors hover:border-[var(--border-hover)] hover:bg-[var(--bg-hover)] disabled:cursor-not-allowed disabled:opacity-50" :disabled="selectedIds.size === 0" @click.stop="$emit('copySelected')">
          批量复制
        </button>
        <button class="inline-flex items-center rounded-md border border-[var(--danger-subtle-border)] bg-[var(--bg-primary)] px-3 py-2 text-sm text-[var(--danger-subtle-text)] shadow-sm transition-colors hover:bg-[var(--danger-subtle-bg)] disabled:cursor-not-allowed disabled:opacity-50" :disabled="selectedIds.size === 0" @click.stop="$emit('deleteSelected')">
          删除选中
        </button>
        <button class="inline-flex items-center rounded-md border border-[var(--border-color)] bg-[var(--bg-primary)] px-3 py-2 text-sm text-[var(--text-primary)] shadow-sm transition-colors hover:border-[var(--border-hover)] hover:bg-[var(--bg-hover)] disabled:cursor-not-allowed disabled:opacity-50" :disabled="selectedIds.size === 0" @click.stop="$emit('clearSelection')">
          取消选择
        </button>
      </div>
    </div>

    <div v-if="items.length === 0" class="flex h-full items-center justify-center text-sm text-gray-400">
      暂无剪贴板历史
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
      class="fixed z-[90] min-w-32 rounded-lg border border-gray-200 bg-white py-1 text-sm shadow-lg dark:border-dark-border dark:bg-dark-panel"
      :style="{ left: `${contextMenu.x}px`, top: `${contextMenu.y}px` }"
      @click.stop
    >
      <button class="block w-full px-3 py-2 text-left text-[var(--text-primary)] hover:bg-[var(--bg-hover)]" @click="copyContextItem">
        {{ contextMenu.kind === 'file' ? '复制文件' : '复制' }}
      </button>
      <button
        v-if="contextMenu.kind === 'url'"
        class="block w-full px-3 py-2 text-left text-[var(--text-primary)] hover:bg-[var(--bg-hover)]"
        @click="openContextItemUrl"
      >
        打开链接
      </button>
      <button
        v-if="contextMenu.kind === 'file' || contextMenu.kind === 'image'"
        class="block w-full px-3 py-2 text-left text-[var(--text-primary)] hover:bg-[var(--bg-hover)]"
        @click="openContextItemFile"
      >
        打开文件
      </button>
      <button
        v-if="contextMenu.kind === 'file' || contextMenu.kind === 'image'"
        class="block w-full px-3 py-2 text-left text-[var(--text-primary)] hover:bg-[var(--bg-hover)]"
        @click="openContextItemFolder"
      >
        打开文件所在目录
      </button>
      <button
        v-if="contextMenu.kind === 'file'"
        class="block w-full px-3 py-2 text-left text-[var(--text-primary)] hover:bg-[var(--bg-hover)]"
        @click="copyContextItemPath"
      >
        复制文件路径
      </button>
      <button class="block w-full px-3 py-2 text-left text-[var(--text-primary)] hover:bg-[var(--bg-hover)]" @click="editContextItemNote">
        编辑备注
      </button>
      <button class="block w-full px-3 py-2 text-left text-[var(--danger-subtle-text)] hover:bg-[var(--danger-subtle-bg)]" @click="deleteContextItem">
        删除
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import ClipboardItemRow from './ClipboardItemRow.vue'
import type { ClipboardItemSummary } from '../types/clipboard'

defineProps<{
  items: ClipboardItemSummary[]
  selectedItemId: string | null
  selectedIds: Set<string>
}>()

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
}>()

const contextMenu = ref<{ id: string; kind: ClipboardItemSummary['kind']; x: number; y: number } | null>(null)

function selectItem(id: string) {
  emit('select', id)
  emit('toggleSelected', id)
}

function copyItem(id: string) {
  emit('copy', id)
}

function openContextMenu(event: MouseEvent, item: ClipboardItemSummary) {
  event.preventDefault()
  emit('select', item.id)
  contextMenu.value = { id: item.id, kind: item.kind, x: event.clientX, y: event.clientY }
}

function closeContextMenu() {
  contextMenu.value = null
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

function deleteContextItem() {
  if (!contextMenu.value) return
  emit('delete', contextMenu.value.id)
  contextMenu.value = null
}
</script>
