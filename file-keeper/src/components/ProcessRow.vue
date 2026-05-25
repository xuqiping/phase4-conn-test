<template>
  <div
    class="process-row"
    :class="{ selected }"
    @click="$emit('toggle-select')"
  >
    <div class="row-checkbox">
      <input
        type="checkbox"
      :checked="selected"
        @click.stop="$emit('toggle-select')"
      />
    </div>

    <div class="row-content">
      <div v-if="isColumnVisible('name')" class="row-cell cell-name">
        <span class="process-name" :title="process.name">{{ process.name }}</span>
      </div>

      <div v-if="isColumnVisible('category')" class="row-cell cell-category">
        <span class="category-badge" :class="`category-${process.category.toLowerCase()}`">
          {{ process.category }}
        </span>
      </div>

      <div v-if="isColumnVisible('pid')" class="row-cell cell-pid">
        {{ process.pid }}
      </div>

      <div v-if="isColumnVisible('memory')" class="row-cell cell-memory">
      {{ formatMemory(process.memory) }}
      </div>

    <div v-if="isColumnVisible('cpu')" class="row-cell cell-cpu">
        {{ process.cpu.toFixed(1) }}%
      </div>

      <div v-if="isColumnVisible('runtime')" class="row-cell cell-runtime">
        {{ formatRuntime(process.runtime) }}
      </div>

      <div v-if="isColumnVisible('path')" class="row-cell cell-path">
        <span :title="process.path">{{ truncate(process.path, 40) }}</span>
      </div>

      <div v-if="isColumnVisible('windowTitle')" class="row-cell cell-window-title">
        <span :title="process.windowTitle">{{ truncate(process.windowTitle, 30) }}</span>
      </div>
    </div>

    <div class="row-actions">
      <button
        class="btn-close"
        title="Close process"
        @click.stop="$emit('close')"
      >
        <XCircle :size="16" />
    </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { XCircle } from 'lucide-vue-next'
import { useProcessSettingsStore } from '../stores/processSettingsStore'
import type { ProcessInfo } from '../types/process'

interface Props {
  process: ProcessInfo
  selected: boolean
}

defineProps<Props>()

defineEmits<{
  'toggle-select': []
  close: []
}>()

const settingsStore = useProcessSettingsStore()

function isColumnVisible(key: string): boolean {
  const column = settingsStore.settings.columns.find(col => col.key === key)
  return column?.visible ?? false
}

function formatMemory(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`
}

function formatRuntime(seconds: number): string {
  if (seconds < 60) return `${seconds}s`
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m`
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h`
  return `${Math.floor(seconds / 86400)}d`
}

function truncate(text: string | undefined, maxLength: number): string {
  if (!text) return '-'
  if (text.length <= maxLength) return text
  return text.substring(0, maxLength - 3) + '...'
}
</script>

<style scoped>
.process-row {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.75rem;
  background: var(--bg-primary);
  border-bottom: 1px solid var(--border-color);
  cursor: pointer;
  transition: background 0.15s;
  height: 48px;
  box-sizing: border-box;
}

.process-row:hover {
  background: var(--bg-hover);
}

.process-row.selected {
  background: var(--primary-bg);
  border-color: var(--primary-color);
}

.row-checkbox {
  flex-shrink: 0;
}

.row-checkbox input[type="checkbox"] {
  width: 16px;
  height: 16px;
  cursor: pointer;
}

.row-content {
  display: flex;
  align-items: center;
  gap: 1rem;
  flex: 1;
  min-width: 0;
}

.row-cell {
  font-size: 0.875rem;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cell-name {
  flex: 1;
  min-width: 150px;
  font-weight: 500;
}

.process-name {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
}

.cell-category {
  width: 100px;
  flex-shrink: 0;
}

.category-badge {
  display: inline-block;
  padding: 0.25rem 0.5rem;
  border-radius: 0.25rem;
  font-size: 0.75rem;
  font-weight: 500;
  text-transform: uppercase;
}

.category-browser { background: #dbeafe; color: #1e40af; }
.category-office { background: #dcfce7; color: #166534; }
.category-explorer { background: #fef3c7; color: #92400e; }
.category-terminal { background: #e0e7ff; color: #3730a3; }
.category-archive { background: #fce7f3; color: #831843; }
.category-document { background: #f3e8ff; color: #6b21a8; }
.category-media { background: #ffedd5; color: #9a3412; }
.category-image { background: #ccfbf1; color: #115e59; }
.category-communication { background: #ddd6fe; color: #5b21b6; }
.category-download { background: #fecaca; color: #991b1b; }
.category-game { background: #fed7aa; color: #9a3412; }
.category-system { background: #e5e7eb; color: #374151; }
.category-other { background: #f3f4f6; color: #6b7280; }

.cell-pid {
  width: 70px;
  flex-shrink: 0;
  font-family: monospace;
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

.cell-runtime {
  width: 80px;
  flex-shrink: 0;
}

.cell-path {
  flex: 1;
  min-width: 200px;
  font-size: 0.8125rem;
  color: var(--text-secondary);
}

.cell-window-title {
  flex: 1;
  min-width: 150px;
  font-size: 0.8125rem;
  color: var(--text-secondary);
}

.row-actions {
  flex-shrink: 0;
}

.btn-close {
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

.btn-close:hover {
  background: var(--danger-bg);
  color: var(--danger-color);
}
</style>
