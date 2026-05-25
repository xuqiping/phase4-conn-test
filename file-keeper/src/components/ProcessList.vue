<template>
  <div class="process-list-container">
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
import { inject } from 'vue'
import { useProcessStore } from '../stores/processStore'
import ProcessRow from './ProcessRow.vue'
import type { ProcessInfo } from '../types/process'
import type { useToast } from '../composables/useToast'

const processStore = useProcessStore()
const requestConfirmation = inject<(processes: ProcessInfo[], onConfirm: () => void) => void>('requestConfirmation')
const toast = inject<ReturnType<typeof useToast>>('toast')

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

.process-list {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
}

.empty-state {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: var(--text-secondary);
}

.status-bar {
  display: flex;
  align-items: center;
  gap: 1rem;
  padding: 0.5rem 0.75rem;
  background: var(--bg-primary);
  border-top: 1px solid var(--border-color);
  font-size: 0.8125rem;
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
