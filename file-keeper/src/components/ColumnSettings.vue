<template>
  <div class="column-settings-overlay" @click.self="$emit('close')">
    <div class="column-settings">
      <div class="settings-header">
        <h3 class="settings-title">
          <Settings :size="20" />
          Column Settings
        </h3>
        <button class="btn-close-dialog" @click="$emit('close')">
          <X :size="20" />
        </button>
      </div>

      <div class="settings-body">
        <p class="settings-description">
          Customize which columns to display and their order. Drag to reorder.
        </p>

        <div ref="columnListRef" class="column-list">
          <div
            v-for="column in localColumns"
            :key="column.key"
         class="column-item"
            :data-key="column.key"
          >
            <div class="drag-handle">
              <GripVertical :size="16" />
            </div>
            <input
              type="checkbox"
              :id="`col-${column.key}`"
              v-model="column.visible"
              class="column-checkbox"
            />
            <label :for="`col-${column.key}`" class="column-label">
              {{ column.label }}
            </label>
          </div>
        </div>
      </div>

      <div class="settings-footer">
        <button class="btn btn-secondary" @click="handleReset">
          Reset to Default
        </button>
        <div class="footer-actions">
          <button class="btn btn-secondary" @click="$emit('close')">
            Cancel
          </button>
        <button class="btn btn-primary" @click="handleSave">
            Save Changes
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { Settings, X, GripVertical } from 'lucide-vue-next'
import Sortable from 'sortablejs'
import { useProcessSettingsStore } from '../stores/processSettingsStore'
import type { ColumnConfig } from '../types/process'

const emit = defineEmits<{
  close: []
  save: []
}>()

const settingsStore = useProcessSettingsStore()
const columnListRef = ref<HTMLElement | null>(null)
const localColumns = ref<ColumnConfig[]>(
  JSON.parse(JSON.stringify(settingsStore.settings.columns))
)

let sortableInstance: Sortable | null = null

onMounted(() => {
  if (columnListRef.value) {
    sortableInstance = Sortable.create(columnListRef.value, {
      animation: 150,
      handle: '.drag-handle',
      ghostClass: 'sortable-ghost',
      dragClass: 'sortable-drag',
      onEnd: (evt) => {
        if (evt.oldIndex !== undefined && evt.newIndex !== undefined) {
          const movedItem = localColumns.value.splice(evt.oldIndex, 1)[0]
          localColumns.value.splice(evt.newIndex, 0, movedItem)
        }
      }
    })
  }
})

onUnmounted(() => {
  if (sortableInstance) {
    sortableInstance.destroy()
  }
})

function handleSave() {
  settingsStore.updateColumns(localColumns.value)
  emit('save')
  emit('close')
}

function handleReset() {
  // Reset to default columns
  const defaultColumns: ColumnConfig[] = [
    { key: 'name', label: 'Name', width: '200px', visible: true, sortable: true },
    { key: 'category', label: 'Category', width: '120px', visible: true, sortable: true },
    { key: 'pid', label: 'PID', width: '80px', visible: true, sortable: true },
    { key: 'memory', label: 'Memory', width: '100px', visible: true, sortable: true },
    { key: 'cpu', label: 'CPU', width: '80px', visible: true, sortable: true },
    { key: 'runtime', label: 'Runtime', width: '100px', visible: true, sortable: true },
    { key: 'path', label: 'Path', width: '300px', visible: false, sortable: false },
    { key: 'windowTitle', label: 'Window Title', width: '200px', visible: false, sortable: false }
  ]
  localColumns.value = JSON.parse(JSON.stringify(defaultColumns))
}
</script>

<style scoped>
.column-settings-overlay {
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

.column-settings {
  background: var(--bg-primary);
  border-radius: 0.75rem;
  box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.1), 0 10px 10px -5px rgba(0, 0, 0, 0.04);
  max-width: 500px;
  width: 90%;
  max-height: 80vh;
  display: flex;
  flex-direction: column;
}

.settings-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 1.25rem;
  border-bottom: 1px solid var(--border-color);
}

.settings-title {
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

.settings-body {
  flex: 1;
  padding: 1.25rem;
  overflow-y: auto;
}

.settings-description {
  margin: 0 0 1rem 0;
  color: var(--text-secondary);
  font-size: 0.875rem;
  line-height: 1.5;
}

.column-list {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.column-item {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.75rem;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 0.375rem;
  cursor: move;
  transition: all 0.15s;
}

.column-item:hover {
  background: var(--bg-hover);
  border-color: var(--border-hover);
}

.drag-handle {
  display: flex;
  align-items: center;
  color: var(--text-secondary);
  cursor: grab;
}

.drag-handle:active {
  cursor: grabbing;
}

.column-checkbox {
  width: 18px;
  height: 18px;
  cursor: pointer;
}

.column-label {
  flex: 1;
  font-size: 0.875rem;
  font-weight: 500;
  color: var(--text-primary);
  cursor: pointer;
  user-select: none;
}

.sortable-ghost {
  opacity: 0.4;
  background: var(--primary-bg);
  border-color: var(--primary-color);
}

.sortable-drag {
  opacity: 1;
  box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1), 0 2px 4px -1px rgba(0, 0, 0, 0.06);
}

.settings-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 1.25rem;
  border-top: 1px solid var(--border-color);
}

.footer-actions {
  display: flex;
  gap: 0.75rem;
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

.btn-primary {
  background: var(--primary-color);
  color: white;
  border-color: var(--primary-color);
}

.btn-primary:hover {
  background: var(--primary-hover);
  border-color: var(--primary-hover);
}

/* Scrollbar styling */
.settings-body::-webkit-scrollbar {
  width: 6px;
}

.settings-body::-webkit-scrollbar-track {
  background: var(--bg-primary);
}

.settings-body::-webkit-scrollbar-thumb {
  background: var(--border-color);
  border-radius: 3px;
}

.settings-body::-webkit-scrollbar-thumb:hover {
  background: var(--border-hover);
}
</style>
