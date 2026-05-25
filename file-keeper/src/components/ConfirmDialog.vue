<template>
  <div class="confirm-dialog-overlay" @click.self="$emit('cancel')">
    <div class="confirm-dialog">
      <div class="dialog-header">
        <h3 class="dialog-title">
          <AlertTriangle :size="20" />
          Confirm Close Processes
        </h3>
        <button class="btn-close-dialog" @click="$emit('cancel')">
          <X :size="20" />
        </button>
      </div>

      <div class="dialog-body">
        <p class="warning-text">
        You are about to close {{ processes.length }} process(es).
       This action cannot be undone.
        </p>

        <div v-if="whitelistedProcesses.length > 0" class="whitelist-warning">
       <AlertTriangle :size="16" />
          <span>
         <strong>Warning:</strong> The following important processes are in your selection:
          </span>
        </div>

        <div class="process-list">
          <div
            v-for="process in processes"
            :key="process.pid"
         class="process-item"
         :class="{ whitelisted: isWhitelisted(process.name) }"
          >
      <span class="process-name">{{ process.name }}</span>
         <span class="process-pid">PID: {{ process.pid }}</span>
            <span v-if="isWhitelisted(process.name)" class="whitelist-badge">
              Important
            </span>
        </div>
        </div>
      </div>

      <div class="dialog-footer">
        <button class="btn btn-secondary" @click="$emit('cancel')">
          Cancel
        </button>
        <button class="btn btn-danger" @click="$emit('confirm')">
          Close {{ processes.length }} Process(es)
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { AlertTriangle, X } from 'lucide-vue-next'
import { useProcessSettingsStore } from '../stores/processSettingsStore'
import type { ProcessInfo } from '../types/process'

interface Props {
  processes: ProcessInfo[]
}

const props = defineProps<Props>()

defineEmits<{
  confirm: []
  cancel: []
}>()

const settingsStore = useProcessSettingsStore()

const whitelistedProcesses = computed(() => {
  return props.processes.filter(p => isWhitelisted(p.name))
})

function isWhitelisted(processName: string): boolean {
  return settingsStore.settings.whitelist.some(name =>
    processName.toLowerCase().includes(name.toLowerCase())
  )
}
</script>

<style scoped>
.confirm-dialog-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  backdrop-filter: blur(2px);
}

.confirm-dialog {
  background: var(--bg-primary);
  border-radius: 0.75rem;
  box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.1), 0 10px 10px -5px rgba(0, 0, 0, 0.04);
  max-width: 500px;
  width: 90%;
  max-height: 80vh;
  display: flex;
  flex-direction: column;
}

.dialog-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 1.25rem;
  border-bottom: 1px solid var(--border-color);
}

.dialog-title {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin: 0;
  font-size: 1.125rem;
  font-weight: 600;
  color: var(--text-primary);
}

.btn-close-dialog {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0.375rem;
  border: none;
  background: transparent;
  color: var(--text-secondary);
  border-radius: 0.25rem;
  cursor: pointer;
  transition: all 0.15s;
}

.btn-close-dialog:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.dialog-body {
  flex: 1;
  padding: 1.25rem;
  overflow-y: auto;
}

.warning-text {
  margin: 0 0 1rem 0;
  color: var(--text-secondary);
  font-size: 0.9375rem;
  line-height: 1.5;
}

.whitelist-warning {
  display: flex;
  align-items: flex-start;
  gap: 0.5rem;
  padding: 0.75rem;
  background: #fef3c7;
  color: #92400e;
  border-radius: 0.375rem;
  margin-bottom: 1rem;
  font-size: 0.875rem;
}

.whitelist-warning strong {
  font-weight: 600;
}

.process-list {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  max-height: 300px;
  overflow-y: auto;
}

.process-item {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.75rem;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 0.375rem;
  font-size: 0.875rem;
}

.process-item.whitelisted {
  background: #fef3c7;
  border-color: #fbbf24;
}

.process-name {
  flex: 1;
  font-weight: 500;
  color: var(--text-primary);
}

.process-pid {
  color: var(--text-secondary);
  font-family: monospace;
  font-size: 0.8125rem;
}

.whitelist-badge {
  padding: 0.25rem 0.5rem;
  background: #f59e0b;
  color: white;
  border-radius: 0.25rem;
  font-size: 0.75rem;
  font-weight: 600;
  text-transform: uppercase;
}

.dialog-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 0.75rem;
  padding: 1.25rem;
  border-top: 1px solid var(--border-color);
}

.btn {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  padding: 0.625rem 1rem;
  border: 1px solid var(--border-color);
  background: var(--bg-primary);
  color: var(--text-primary);
  border-radius: 0.375rem;
  cursor: pointer;
  font-size: 0.875rem;
  font-weight: 500;
  transition: all 0.2s;
}

.btn:hover {
  background: var(--bg-hover);
  border-color: var(--border-hover);
}

.btn-secondary {
  background: var(--bg-secondary);
}

.btn-danger {
  background: var(--danger-color);
  color: white;
  border-color: var(--danger-color);
}

.btn-danger:hover {
  background: var(--danger-hover);
  border-color: var(--danger-hover);
}

/* Scrollbar styling */
.process-list::-webkit-scrollbar {
  width: 6px;
}

.process-list::-webkit-scrollbar-track {
  background: var(--bg-primary);
}

.process-list::-webkit-scrollbar-thumb {
  background: var(--border-color);
  border-radius: 3px;
}

.process-list::-webkit-scrollbar-thumb:hover {
  background: var(--border-hover);
}
</style>
