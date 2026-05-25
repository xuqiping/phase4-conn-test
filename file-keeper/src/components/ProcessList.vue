<template>
  <div class="process-list-container">
    <div
      ref="containerRef"
      class="process-list"
      @scroll="handleScroll"
    >
      <div class="virtual-scroll-spacer" :style="{ height: `${totalHeight}px` }">
      <div
          v-for="{ item, offsetTop } in visibleItems"
          :key="item.pid"
          class="process-row-wrapper"
          :style="{ transform: `translateY(${offsetTop}px)` }"
        >
          <ProcessRow
            :process="item"
            :selected="processStore.selectedIds.has(item.pid)"
            @toggle-select="processStore.toggleSelect(item.pid)"
            @close="handleCloseProcess(item.pid)"
          />
        </div>
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
import { ref, computed, inject } from 'vue'
import { useProcessStore } from '../stores/processStore'
import { useVirtualScroll } from '../composables/useVirtualScroll'
import ProcessRow from './ProcessRow.vue'
import type { ProcessInfo } from '../types/process'
import type { useToast } from '../composables/useToast'

const processStore = useProcessStore()
const requestConfirmation = inject<(processes: ProcessInfo[], onConfirm: () => void) => void>('requestConfirmation')
const toast = inject<ReturnType<typeof useToast>>('toast')

const containerRef = ref<HTMLElement | null>(null)

// Virtual scrolling setup
const ITEM_HEIGHT = 48 // Height of each process row in pixels

const { visibleItems, totalHeight, handleScroll } = useVirtualScroll(
  containerRef,
  computed(() => processStore.filteredProcesses),
  {
    itemHeight: ITEM_HEIGHT,
    itemsPerRow: 1,
    overscan: 10
  }
)

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
  position: relative;
}

.virtual-scroll-spacer {
  position: relative;
  width: 100%;
}

.process-row-wrapper {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  will-change: transform;
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
