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
    <ToastContainer />
  </div>
</template>

<script setup lang="ts">
import { ref, provide, onActivated, onDeactivated, onMounted, onUnmounted } from 'vue'
import { useProcessStore } from '../stores/processStore'
import { useProcessSettingsStore } from '../stores/processSettingsStore'
import { useToast } from '../composables/useToast'
import ProcessToolbar from './ProcessToolbar.vue'
import ProcessFilter from './ProcessFilter.vue'
import ProcessList from './ProcessList.vue'
import ConfirmDialog from './ConfirmDialog.vue'
import ToastContainer from './ToastContainer.vue'
import type { ProcessInfo } from '../types/process'
const processStore = useProcessStore()
const settingsStore = useProcessSettingsStore()
const toast = useToast()

const showConfirmDialog = ref(false)
const processesToClose = ref<ProcessInfo[]>([])
let pendingCloseAction: (() => void) | null = null
let autoRefreshTimer: number | null = null
let visibilityChangeHandler: (() => void) | null = null

// Provide confirmation function to child components
provide('requestConfirmation', async (processes: ProcessInfo[], onConfirm: () => void) => {
  const mode = settingsStore.settings.confirmMode

  // Check if confirmation is needed
  if (mode === 'never') {
    onConfirm()
    return
  }

  if (mode === 'whitelist') {
    // Only confirm if any process is in whitelist
    const hasWhitelisted = processes.some(p =>
      settingsStore.settings.whitelist.some(name =>
        p.name.toLowerCase().includes(name.toLowerCase())
      )
    )
    if (!hasWhitelisted) {
      onConfirm()
    return
    }
  }

  // Show confirmation dialog
  processesToClose.value = processes
  pendingCloseAction = onConfirm
  showConfirmDialog.value = true
})

// Provide toast function to child components
provide('toast', toast)

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
  if (pendingCloseAction) {
    pendingCloseAction()
    pendingCloseAction = null
  }
  processesToClose.value = []
}

function handleCancelClose() {
  showConfirmDialog.value = false
  pendingCloseAction = null
  processesToClose.value = []
}

// Expose methods for child components to trigger confirmation
defineExpose({
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
