<template>
  <div class="process-toolbar">
    <div class="toolbar-left">
      <button
        class="btn btn-primary"
        :disabled="processStore.isRefreshing"
        @click="handleRefresh"
      >
    <RefreshCw :class="{ 'animate-spin': processStore.isRefreshing }" :size="16" />
        {{ t('process.refresh') }}
      </button>

      <button
        class="btn"
        :class="{ 'btn-active': settingsStore.settings.autoRefresh }"
        @click="toggleAutoRefresh"
      >
        <Clock :size="16" />
        {{ t('process.autoRefresh') }}
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
        {{ t('process.selectAll') }}
      </button>

      <button
        class="btn"
        :disabled="processStore.filteredProcesses.length === 0"
        @click="processStore.invertSelection()"
      >
        <Square :size="16" />
        {{ t('process.invert') }}
      </button>

      <button
        class="btn"
        :disabled="processStore.selectedCount === 0"
        @click="processStore.deselectAll()"
      >
        <X :size="16" />
        {{ t('process.deselect') }}
      </button>

      <div class="divider" />

      <button
        class="btn btn-danger"
        :disabled="processStore.selectedCount === 0"
        @click="handleCloseSelected"
      >
        <XCircle :size="16" />
      {{ t('process.closeSelected') }} ({{ processStore.selectedCount }})
      </button>

      <button
        class="btn btn-danger btn-danger-strong"
        :disabled="processStore.selectedCount === 0"
        @click="handleKillSelected"
      >
        <XCircle :size="16" />
      {{ t('process.killSelected') }} ({{ processStore.selectedCount }})
      </button>
    </div>

    <div class="toolbar-right">
      <button
        class="btn"
        @click="showColumnSettings = true"
        :title="t('process.columns')"
      >
        <Settings :size="16" />
        {{ t('process.columns') }}
      </button>

      <span class="status-text">
        {{ t('process.total', { count: processStore.filteredProcesses.length }) }}
        <span v-if="processStore.lastRefreshTime > 0" class="last-refresh">
        · {{ t('process.lastRefresh', { time: formatLastRefresh() }) }}
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
import { useI18n } from '../composables/useI18n'
import ColumnSettings from './ColumnSettings.vue'
import type { ProcessInfo } from '../types/process'
import type { useToast } from '../composables/useToast'

const processStore = useProcessStore()
const settingsStore = useProcessSettingsStore()
const { t } = useI18n()
const requestConfirmation = inject<((processes: ProcessInfo[], onConfirm: () => void) => void) | undefined>('requestConfirmation', undefined)
const requestKillConfirmation = inject<((processes: ProcessInfo[], onConfirm: () => void) => void) | undefined>('requestKillConfirmation', undefined)
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
        toast?.success(t('process.batchCloseSuccess', { count: result.success }))
      }
      if (result.failed > 0) {
        toast?.error(t('process.batchCloseFailed', { count: result.failed }))
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

function handleKillSelected() {
  const selectedProcesses = processStore.selectedProcesses

  const onConfirm = async () => {
    const result = await processStore.killSelected()
    if (result.success > 0) {
      toast?.success(t('process.batchKillSuccess', { count: result.success }))
    }
    if (result.failed > 0) {
      toast?.error(t('process.batchKillFailed', { count: result.failed }))
    }
  }

  if (requestKillConfirmation) {
    requestKillConfirmation(selectedProcesses, onConfirm)
    return
  }

  if (window.confirm(t('process.confirmKillMultiple', { count: selectedProcesses.length }))) {
    onConfirm()
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
  if (processStore.lastRefreshTime === 0) return t('process.never')

  const seconds = Math.floor((Date.now() - processStore.lastRefreshTime) / 1000)
  if (seconds < 60) return t('process.secondsAgo', { count: seconds })
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return t('process.minutesAgo', { count: minutes })
  const hours = Math.floor(minutes / 60)
  return t('process.hoursAgo', { count: hours })
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
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
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
  background: var(--bg-primary);
  color: var(--accent-subtle-text);
  border-color: var(--accent-subtle-border);
}

.btn-primary:hover:not(:disabled) {
  background: var(--accent-subtle-bg);
  border-color: var(--accent-subtle-border);
}

.btn-danger {
  background: var(--bg-primary);
  color: var(--danger-subtle-text);
  border-color: var(--danger-subtle-border);
}

.btn-danger:hover:not(:disabled) {
  background: var(--danger-subtle-bg);
  border-color: var(--danger-subtle-border);
}

.btn-danger-strong {
  color: var(--danger-color);
  border-color: var(--danger-color);
}

.btn-danger-strong:hover:not(:disabled) {
  background: var(--danger-bg);
  border-color: var(--danger-color);
}

.btn-active {
  background: var(--accent-subtle-bg);
  color: var(--accent-subtle-text);
  border-color: var(--accent-subtle-border);
  box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--accent-subtle-border) 65%, transparent);
}

.btn-active:hover:not(:disabled) {
  background: var(--accent-subtle-hover);
  border-color: var(--accent-subtle-border);
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
