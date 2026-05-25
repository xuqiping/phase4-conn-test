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
        <div v-if="isColumnVisible('name')" class="header-cell cell-name">Name</div>
        <div v-if="isColumnVisible('category')" class="header-cell cell-category">Category</div>
        <div v-if="isColumnVisible('pid')" class="header-cell cell-pid">PID</div>
        <div v-if="isColumnVisible('memory')" class="header-cell cell-memory">Memory</div>
        <div v-if="isColumnVisible('cpu')" class="header-cell cell-cpu">CPU</div>
        <div v-if="isColumnVisible('windowTitle')" class="header-cell cell-window-title">Window Title</div>
      </div>
      <div class="header-actions">Actions</div>
    </div>

    <!-- List -->
    <div class="process-list">
      <div v-if="processStore.filteredProcesses.length === 0" class="empty-state">
        <p>No processes found</p>
      </div>
      <div v-else>
        <ProcessRow
          v-for="process in processStore.filteredProcesses"
          :key="process.pid"
          :process="process"
          :selected="processStore.selectedIds.has(process.pid)"
          @toggle-select="processStore.toggleSelect(process.pid)"
          @close="handleCloseProcess(process.pid)"
        />
      </div>
    </div>

    <!-- Status Bar -->
    <div class="status-bar">
      <span class="status-item">
        Total: {{ processStore.filteredProcesses.length }}
      </span>
      <span v-if="processStore.selectedCount > 0" class="status-item">
        Selected: {{ processStore.selectedCount }}
      </span>
      <span v-if="processStore.error" class="status-item error">
        {{ processStore.error }}
      </span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, inject } from 'vue'
import { useProcessStore } from '../stores/processStore'
import { useProcessSettingsStore } from '../stores/processSettingsStore'
import ProcessRow from './ProcessRow.vue'
import type { ProcessInfo } from '../types/process'
import type { useToast } from '../composables/useToast'

const processStore = useProcessStore()
const settingsStore = useProcessSettingsStore()
const requestConfirmation = inject<(processes: ProcessInfo[], onConfirm: () => void) => void>('requestConfirmation')
const toast = inject<ReturnType<typeof useToast>>('toast')

const isAllSelected = computed(() => {
  if (processStore.filteredProcesses.length === 0) return false
  return processStore.filteredProcesses.every(p => processStore.selectedIds.has(p.pid))
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

function isColumnVisible(key: string): boolean {
  const column = settingsStore.settings.columns.find(col => col.key === key)
  return column?.visible ?? false
}

async function handleCloseProcess(pid: number) {
  const process = processStore.processes.find(p => p.pid === pid)
  if (!process) return

  if (requestConfirmation) {
    requestConfirmation([process], async () => {
      const success = await processStore.closeProcess(pid)
      if (success) {
        toast?.success(`Process ${process.name} (PID: ${pid}) closed successfully`)
      } else {
        toast?.error(`Failed to close process ${process.name} (PID: ${pid})`)
      }
    })
  } else {
    const success = await processStore.closeProcess(pid)
    if (success) {
      toast?.success(`Process ${process.name} (PID: ${pid}) closed successfully`)
    } else {
      toast?.error(`Failed to close process ${process.name} (PID: ${pid})`)
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
  flex-shrink: 0;
  width: 16px;
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

.header-actions {
  flex-shrink: 0;
  width: 60px;
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
  overflow-y: auto;
  overflow-x: hidden;
  min-height: 0;
}

.empty-state {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: var(--text-secondary);
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
  width: 8px;
}

.process-list::-webkit-scrollbar-track {
  background: var(--bg-primary);
}

.process-list::-webkit-scrollbar-thumb {
  background: var(--border-color);
  border-radius: 4px;
}

.process-list::-webkit-scrollbar-thumb:hover {
  background: var(--border-hover);
}
</style>
