<template>
  <div class="process-toolbar">
    <div class="toolbar-left">
      <button
        class="btn btn-primary"
        :disabled="processStore.isRefreshing"
        @click="handleRefresh"
      >
    <RefreshCw :class="{ 'animate-spin': processStore.isRefreshing }" :size="16" />
        Refresh
      </button>

      <button
        class="btn"
        :class="{ 'btn-active': settingsStore.settings.autoRefresh }"
        @click="toggleAutoRefresh"
      >
        <Clock :size="16" />
        Auto
        <span v-if="settingsStore.settings.autoRefresh && countdown > 0" class="countdown">
          ({{ countdown }}s)
      </span>
      </button>

      <div class="divider" />

      <button
        class="btn"
        :disabled="processStore.filteredProcesses.length === 0"
        @click="processStore.selectAll()"
      >
        <CheckSquare :size="16" />
        Select All
      </button>

      <button
        class="btn"
        :disabled="processStore.filteredProcesses.length === 0"
        @click="processStore.invertSelection()"
      >
        <Square :size="16" />
        Invert
      </button>

      <button
        class="btn"
        :disabled="processStore.selectedCount === 0"
        @click="processStore.deselectAll()"
      >
        <X :size="16" />
        Clear
      </button>

      <div class="divider" />

      <button
        class="btn btn-danger"
        :disabled="processStore.selectedCount === 0"
        @click="handleCloseSelected"
      >
        <XCircle :size="16" />
      Close Selected ({{ processStore.selectedCount }})
      </button>
    </div>

    <div class="toolbar-right">
      <button
        class="btn"
        @click="showColumnSettings = true"
        title="Column Settings"
      >
        <Settings :size="16" />
        Columns
      </button>

      <span class="status-text">
        {{ processStore.filteredProcesses.length }} processes
        <span v-if="processStore.lastRefreshTime > 0" class="last-refresh">
        · Last refresh: {{ formatLastRefresh() }}
        </span>
      </span>
    </div>
  </div>

  <ColumnSettings
    v-if="showColumnSettings"
    @close="showColumnSettings = false"
    @save="handleColumnsSaved"
  />
</template>

<script setup lang="ts">
import { ref, watch, onUnmounted, inject } from 'vue'
import { RefreshCw, Clock, CheckSquare, Square, X, XCircle, Settings } from 'lucide-vue-next'
import { useProcessStore } from '../stores/processStore'
import { useProcessSettingsStore } from '../stores/processSettingsStore'
import ColumnSettings from './ColumnSettings.vue'
import type { ProcessInfo } from '../types/process'
import type { useToast } from '../composables/useToast'

const processStore = useProcessStore()
const settingsStore = useProcessSettingsStore()
const requestConfirmation = inject<(processes: ProcessInfo[], onConfirm: () => void) => void>('requestConfirmation')
const toast = inject<ReturnType<typeof useToast>>('toast')

const countdown = ref(0)
const showColumnSettings = ref(false)
let countdownTimer: number | null = null

function handleRefresh() {
  processStore.refresh()
  resetCountdown()
}

function toggleAutoRefresh() {
  const newValue = !settingsStore.settings.autoRefresh
  settingsStore.updateAutoRefresh(newValue)
  if (newValue) {
    resetCountdown()
    startCountdown()
  } else {
    stopCountdown()
  }
}

function handleCloseSelected() {
  const selectedProcesses = processStore.selectedProcesses

  if (requestConfirmation) {
    requestConfirmation(selectedProcesses, async () => {
      const result = await processStore.closeSelected()
      if (result.success > 0) {
        toast?.success(`Successfully closed ${result.success} process(es)`)
      }
      if (result.failed > 0) {
        toast?.error(`Failed to close ${result.failed} process(es)`)
      }
    })
  } else {
    processStore.closeSelected().then(result => {
      if (result.success > 0) {
        toast?.success(`Successfully closed ${result.success} process(es)`)
      }
      if (result.failed > 0) {
        toast?.error(`Failed to close ${result.failed} process(es)`)
   }
    })
  }
}

function handleColumnsSaved() {
  // Columns are automatically saved by the ColumnSettings component
  // This is just for any additional logic if needed
}

function resetCountdown() {
  countdown.value = Math.floor(settingsStore.settings.refreshInterval / 1000)
}

function startCountdown() {
  stopCountdown()

  if (settingsStore.settings.autoRefresh) {
    resetCountdown()
    countdownTimer = window.setInterval(() => {
      countdown.value--
      if (countdown.value <= 0) {
        resetCountdown()
      }
    }, 1000)
  }
}

function stopCountdown() {
  if (countdownTimer !== null) {
    clearInterval(countdownTimer)
    countdownTimer = null
  }
  countdown.value = 0
}

function formatLastRefresh(): string {
  if (processStore.lastRefreshTime === 0) return 'Never'

  const seconds = Math.floor((Date.now() - processStore.lastRefreshTime) / 1000)
  if (seconds < 60) return `${seconds}s ago`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes}m ago`
  const hours = Math.floor(minutes / 60)
  return `${hours}h ago`
}

// Watch for auto-refresh changes
watch(() => settingsStore.settings.autoRefresh, (enabled) => {
  if (enabled) {
    startCountdown()
  } else {
    stopCountdown()
  }
}, { immediate: true })

onUnmounted(() => {
  stopCountdown()
})
</script>

<style scoped>
.process-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0.75rem;
  background: var(--bg-secondary);
  border-radius: 0.5rem;
  gap: 1rem;
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex-wrap: wrap;
}

.toolbar-right {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
.btn {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  padding: 0.5rem 0.75rem;
  border: 1px solid var(--border-color);
  background: var(--bg-primary);
  color: var(--text-primary);
  border-radius: 0.375rem;
  cursor: pointer;
  font-size: 0.875rem;
  transition: all 0.2s;
}

.btn:hover:not(:disabled) {
  background: var(--bg-hover);
  border-color: var(--border-hover);
}

.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn-primary {
  background: var(--primary-color);
  color: white;
  border-color: var(--primary-color);
}

.btn-primary:hover:not(:disabled) {
  background: var(--primary-hover);
  border-color: var(--primary-hover);
}

.btn-danger {
  background: var(--danger-color);
  color: white;
  border-color: var(--danger-color);
}

.btn-danger:hover:not(:disabled) {
  background: var(--danger-hover);
  border-color: var(--danger-hover);
}

.btn-active {
  background: var(--primary-color);
  color: white;
  border-color: var(--primary-color);
}

.divider {
  width: 1px;
  height: 1.5rem;
  background: var(--border-color);
}

.countdown {
  font-size: 0.75rem;
  opacity: 0.8;
}

.status-text {
  font-size: 0.875rem;
  color: var(--text-secondary);
}

.last-refresh {
  opacity: 0.7;
}

.animate-spin {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}
</style>
