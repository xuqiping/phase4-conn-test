<template>
  <div class="process-management">
    <ProcessToolbar />
    <ProcessFilter />
    <ProcessList />
    <ConfirmDialog
      v-if="showConfirmDialog"
      :processes="processesToClose"
      @confirm="handleConfirmClose"
    @cancel="handleCancelClose"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, onActivated, onDeactivated, onMounted, onUnmounted } from 'vue'
import { useProcessStore } from '../stores/processStore'
import { useProcessSettingsStore } from '../stores/processSettingsStore'
import ProcessToolbar from './ProcessToolbar.vue'
import ProcessFilter from './ProcessFilter.vue'
import ProcessList from './ProcessList.vue'
import ConfirmDialog from './ConfirmDialog.vue'
import type { ProcessInfo } from '../types/process'

const processStore = useProcessStore()
const settingsStore = useProcessSettingsStore()

const showConfirmDialog = ref(false)
const processesToClose = ref<ProcessInfo[]>([])
let autoRefreshTimer: number | null = null
let visibilityChangeHandler: (() => void) | null = null

// Start monitoring when tab becomes active
onActivated(() => {
  console.log('ProcessManagement activated')
  processStore.refresh()
  startAutoRefresh()
  setupVisibilityListener()
})

// Stop monitoring when tab becomes inactive
onDeactivated(() => {
  console.log('ProcessManagement deactivated')
  stopAutoRefresh()
  removeVisibilityListener()
  processStore.clearProcesses()
})

onMounted(() => {
  // Initial load if component is mounted directly (not via keep-alive)
  processStore.refresh()
  startAutoRefresh()
  setupVisibilityListener()
})

onUnmounted(() => {
  stopAutoRefresh()
  removeVisibilityListener()
})

function startAutoRefresh() {
  stopAutoRefresh() // Clear any existing timer

  if (settingsStore.settings.autoRefresh) {
    autoRefreshTimer = window.setInterval(() => {
      // Only refresh if document is visible
      if (document.visibilityState === 'visible') {
        processStore.refresh()
      }
    }, settingsStore.settings.refreshInterval)
  }

function stopAutoRefresh() {
  if (autoRefreshTimer !== null) {
    clearInterval(autoRefreshTimer)
    autoRefreshTimer = null
  }
}

function setupVisibilityListener() {
  visibilityChangeHandler = () => {
    if (document.visibilityState === 'visible') {
      // Refresh when browser tab becomes visible
      processStore.refresh()
      startAutoRefresh()
    } else {
      // Stop auto-refresh when browser tab is hidden
      stopAutoRefresh()
    }
  }
  document.addEventListener('visibilitychange', visibilityChangeHandler)
}

function removeVisibilityListener() {
  if (visibilityChangeHandler) {
    document.removeEventListener('visibilitychange', visibilityChangeHandler)
    visibilityChangeHandler = null
  }
}

function handleConfirmClose() {
  showConfirmDialog.value = false
  // Close logic will be handled by the component that triggered the dialog
  processesToClose.value = []
}

function handleCancelClose() {
  showConfirmDialog.value = false
  processesToClose.value = []
}

// Expose methods for child components to trigger confirmation
defineExpose({
  showConfirmDialog: (processes: ProcessInfo[]) => {
    processesToClose.value = processes
    showConfirmDialog.value = true
  },
  startAutoRefresh,
  stopAutoRefresh
})
</script>

<style scoped>
.process-management {
  display: flex;
  flex-direction: column;
  height: 100%;
  gap: 1rem;
  padding: 1rem;
}
</style>
