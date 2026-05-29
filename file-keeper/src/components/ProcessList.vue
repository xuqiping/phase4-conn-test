<template>
  <div class="process-list-container">
    <!-- Header -->
    <div class="list-header">
      <div class="header-checkbox">
        <input
          type="checkbox"
          :checked="isAllSelected"
          :indeterminate="isIndeterminate"
          @change="toggleSelectAll"
        />
      </div>
      <div class="header-content">
        <button
          v-for="column in visibleColumns"
          :key="column.key"
          :class="['header-cell', 'header-sortable', getProcessColumnClass(column.key)]"
          @click="handleSort(column.key)"
        >
          <span>{{ getColumnLabel(column.key) }}</span>
          <component :is="getSortIcon(column.key)" :size="14" class="sort-icon" />
        </button>
      </div>
      <div class="header-actions">{{ t('process.columns_actions') }}</div>
    </div>

    <!-- List -->
    <div class="process-list">
      <div v-if="processStore.filteredProcesses.length === 0" class="empty-state">
        <p>{{ t('process.noProcesses') }}</p>
      </div>
      <div v-else class="process-list-content">
        <ProcessRow
          v-for="process in processStore.filteredProcesses"
          :key="process.window_handle"
          :process="process"
          :selected="processStore.selectedIds.has(process.window_handle)"
          @toggle-select="processStore.toggleSelect(process.window_handle)"
          @close="handleCloseProcess(process.window_handle)"
          @context-menu="handleProcessContextMenu($event, process)"
        />
      </div>
    </div>

    <transition name="fade">
      <div
        v-if="contextMenu.show"
        class="process-context-menu"
        :style="{ top: contextMenu.y + 'px', left: contextMenu.x + 'px' }"
        @click.stop
      >
        <div class="process-context-menu__title">
          {{ contextMenu.process?.name }}
        </div>

        <button class="process-context-menu__item" @click="handleContextMenuAction('activate')">
          {{ t('process.menuShowToFront') }}
        </button>
        <button class="process-context-menu__item" @click="handleContextMenuAction('copy')">
          {{ t('process.menuCopyInfo') }}
        </button>

        <div class="process-context-menu__divider"></div>

        <button class="process-context-menu__item process-context-menu__item--danger" @click="handleContextMenuAction('close')">
          {{ t('process.menuCloseProcess') }}
        </button>
      </div>
    </transition>

    <div v-if="contextMenu.show" class="process-context-menu__overlay" @click="closeContextMenu"></div>

    <!-- Status Bar -->
    <div class="status-bar">
      <span class="status-item">
        {{ t('process.total', { count: processStore.filteredProcesses.length }) }}
      </span>
      <span v-if="processStore.selectedCount > 0" class="status-item">
        {{ t('process.selected', { count: processStore.selectedCount }) }}
      </span>
      <span v-if="processStore.error" class="status-item error">
        {{ processStore.error }}
      </span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, inject, ref } from 'vue'
import { ArrowDown, ArrowUp, ArrowUpDown } from 'lucide-vue-next'
import { activateWindow } from '../api/process'
import { useProcessStore } from '../stores/processStore'
import { useProcessSettingsStore } from '../stores/processSettingsStore'
import { useI18n } from '../composables/useI18n'
import { getProcessColumnClass, getVisibleProcessColumns, type SupportedProcessColumnKey } from './processColumns'
import ProcessRow from './ProcessRow.vue'
import type { ProcessInfo } from '../types/process'
import type { useToast } from '../composables/useToast'

const processStore = useProcessStore()
const settingsStore = useProcessSettingsStore()
const { t } = useI18n()
const requestConfirmation = inject<(processes: ProcessInfo[], onConfirm: () => void) => void>('requestConfirmation')
const toast = inject<ReturnType<typeof useToast>>('toast')

const contextMenu = ref({
  show: false,
  x: 0,
  y: 0,
  process: null as ProcessInfo | null
})

const visibleColumns = computed(() => getVisibleProcessColumns(settingsStore.settings.columns))

const columnLabelKeyByColumn = {
  name: 'process.columns_name',
  category: 'process.columns_category',
  pid: 'process.columns_pid',
  memory: 'process.columns_memory',
  cpu: 'process.columns_cpu',
  windowTitle: 'process.columns_windowTitle'
} satisfies Record<SupportedProcessColumnKey, string>

const isAllSelected = computed(() => {
  if (processStore.filteredProcesses.length === 0) return false
  return processStore.filteredProcesses.every(p => processStore.selectedIds.has(p.window_handle))
})

const isIndeterminate = computed(() => {
  if (processStore.selectedCount === 0) return false
  if (isAllSelected.value) return false
  return true
})

function toggleSelectAll() {
  if (isAllSelected.value) {
    processStore.deselectAll()
  } else {
    processStore.selectAll()
  }
}

function getColumnLabel(key: SupportedProcessColumnKey): string {
  return t(columnLabelKeyByColumn[key])
}

function handleSort(key: SupportedProcessColumnKey) {
  processStore.setSort(key)
}

function getSortIcon(key: SupportedProcessColumnKey) {
  if (processStore.sortKey !== key) return ArrowUpDown
  return processStore.sortDirection === 'asc' ? ArrowUp : ArrowDown
}

function isAccessDeniedCloseError(message: string | null): boolean {
  if (!message) return false

  const normalized = message.toLowerCase()
  return normalized.includes('0x80070005') || normalized.includes('access is denied') || message.includes('拒绝访问')
}

function getCloseProcessErrorMessage(process: ProcessInfo): string {
  if (isAccessDeniedCloseError(processStore.error)) {
    return t('process.closeSingleFailedAccessDenied', { name: process.name, pid: process.pid })
  }

  return t('process.closeSingleFailed', { name: process.name, pid: process.pid })
}

function handleProcessContextMenu(event: MouseEvent, process: ProcessInfo) {
  const x = Math.max(8, Math.min(event.clientX, window.innerWidth - 224))
  const y = Math.max(8, Math.min(event.clientY, window.innerHeight - 164))

  contextMenu.value = {
    show: true,
    x,
    y,
    process
  }
}

function closeContextMenu() {
  contextMenu.value = {
    show: false,
    x: 0,
    y: 0,
    process: null
  }
}

async function handleContextMenuAction(action: 'activate' | 'copy' | 'close') {
  const process = contextMenu.value.process
  if (!process) return

  if (action === 'activate') {
    try {
      await activateWindow(process.window_handle)
      toast?.success(t('process.activateSuccess', { name: process.name }))
    } catch (error) {
      toast?.error(t('process.activateFailed', { name: process.name }))
      console.error('Failed to activate process window:', error)
    }
    closeContextMenu()
    return
  }

  if (action === 'copy') {
    try {
      await navigator.clipboard.writeText([
        `${t('process.columns_name')}: ${process.name}`,
        `${t('process.columns_pid')}: ${process.pid}`,
        `${t('process.columns_windowTitle')}: ${process.window_title || '-'}`
      ].join('\n'))
      toast?.success(t('process.copyInfoSuccess'))
    } catch (error) {
      toast?.error(t('process.copyInfoFailed'))
      console.error('Failed to copy process info:', error)
    }
    closeContextMenu()
    return
  }

  closeContextMenu()
  await handleCloseProcess(process.window_handle)
}

async function handleCloseProcess(windowHandle: number) {
  const process = processStore.processes.find(p => p.window_handle === windowHandle)
  if (!process) return

  if (requestConfirmation) {
    requestConfirmation([process], async () => {
      const success = await processStore.closeProcess(windowHandle)
      if (success) {
        toast?.success(t('process.closeSingleSuccess', { name: process.name, pid: process.pid }))
      } else {
        toast?.error(getCloseProcessErrorMessage(process))
      }
    })
  } else {
    const success = await processStore.closeProcess(windowHandle)
    if (success) {
      toast?.success(t('process.closeSingleSuccess', { name: process.name, pid: process.pid }))
    } else {
      toast?.error(getCloseProcessErrorMessage(process))
    }
  }
}
</script>

<style scoped>
.process-list-container {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  background: var(--bg-secondary);
  border-radius: 0.5rem;
  overflow: hidden;
}

/* Header */
.list-header {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.75rem;
  background: var(--bg-primary);
  border-bottom: 2px solid var(--border-color);
  font-size: 0.8125rem;
  font-weight: 600;
  color: var(--text-secondary);
  flex-shrink: 0;
}

.header-checkbox {
  width: 16px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}

.header-checkbox input[type="checkbox"] {
  width: 16px;
  height: 16px;
  cursor: pointer;
}

.header-content {
  display: flex;
  align-items: center;
  gap: 1rem;
  flex: 1;
  min-width: 0;
}

.header-cell {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.header-sortable {
  display: flex;
  align-items: center;
  gap: 0.25rem;
  padding: 0;
  border: none;
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
  min-width: 0;
  appearance: none;
  -webkit-appearance: none;
}

.header-sortable span {
  overflow: hidden;
  text-overflow: ellipsis;
}

.header-sortable.cell-memory span,
.header-sortable.cell-cpu span {
  flex: 1;
  text-align: right;
}

.header-sortable:hover {
  color: var(--text-primary);
}

.header-sortable.cell-memory,
.header-sortable.cell-cpu {
  justify-content: flex-end;
}

.sort-icon {
  flex-shrink: 0;
  opacity: 0.7;
}

.header-actions {
  width: 60px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
}

/* Same cell styles as ProcessRow for alignment */
.cell-name {
  flex: 1;
  min-width: 150px;
}

.cell-category {
  width: 100px;
  flex-shrink: 0;
}

.cell-pid {
  width: 70px;
  flex-shrink: 0;
}

.cell-memory {
  width: 90px;
  flex-shrink: 0;
  text-align: right;
}

.cell-cpu {
  width: 60px;
  flex-shrink: 0;
  text-align: right;
}

.cell-window-title {
  flex: 1;
  min-width: 150px;
}

/* List */
.process-list {
  flex: 1;
  overflow-y: scroll;
  overflow-x: hidden;
  min-height: 0;
  scrollbar-gutter: stable;
  scrollbar-width: thin;
  scrollbar-color: var(--border-color) var(--bg-primary);
}

.empty-state {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: var(--text-secondary);
}

.process-list-content {
  min-height: 0;
}

/* Status Bar */
.status-bar {
  display: flex;
  align-items: center;
  gap: 1rem;
  padding: 0.5rem 0.75rem;
  background: var(--bg-primary);
  border-top: 1px solid var(--border-color);
  font-size: 0.8125rem;
  flex-shrink: 0;
}

.status-item {
  color: var(--text-secondary);
}

.status-item.error {
  color: var(--danger-color);
}

/* Scrollbar styling */
.process-list::-webkit-scrollbar {
  width: 10px;
}

.process-list::-webkit-scrollbar-track {
  background: var(--bg-primary);
}

.process-list::-webkit-scrollbar-thumb {
  background: var(--border-color);
  border-radius: 999px;
  border: 2px solid var(--bg-primary);
}

.process-list::-webkit-scrollbar-thumb:hover {
  background: var(--border-hover);
}

.process-context-menu {
  position: fixed;
  z-index: 50;
  width: 14rem;
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: 0.75rem;
  box-shadow: 0 20px 25px -5px rgb(0 0 0 / 0.1), 0 8px 10px -6px rgb(0 0 0 / 0.1);
  padding: 0.25rem 0;
}

.process-context-menu__title {
  padding: 0.5rem 0.75rem;
  font-size: 0.75rem;
  font-weight: 600;
  color: var(--text-secondary);
  border-bottom: 1px solid var(--border-color);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.process-context-menu__item {
  width: 100%;
  border: none;
  background: transparent;
  color: var(--text-primary);
  text-align: left;
  padding: 0.625rem 0.75rem;
  font-size: 0.875rem;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}

.process-context-menu__item:hover {
  background: var(--bg-hover);
}

.process-context-menu__item--danger {
  color: var(--danger-color);
}

.process-context-menu__item--danger:hover {
  background: var(--danger-bg);
}

.process-context-menu__divider {
  height: 1px;
  background: var(--border-color);
  margin: 0.25rem 0;
}

.process-context-menu__overlay {
  position: fixed;
  inset: 0;
  z-index: 40;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.15s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
