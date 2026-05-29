<template>
  <div
    class="process-row"
    :class="{ selected }"
    @click="$emit('toggle-select')"
    @contextmenu.prevent="handleContextMenu"
  >
    <div class="row-checkbox">
      <input
        type="checkbox"
      :checked="selected"
        @click.stop="$emit('toggle-select')"
      />
    </div>

    <div class="row-content">
      <div
        v-for="column in visibleColumns"
        :key="column.key"
        :class="['row-cell', getProcessColumnClass(column.key)]"
      >
        <span v-if="column.key === 'name'" class="process-name" :title="process.name">{{ process.name }}</span>

        <span v-else-if="column.key === 'category'" class="category-badge" :class="`category-${process.category.toLowerCase()}`">
          {{ t(`process.category_${process.category}`) }}
        </span>

        <template v-else-if="column.key === 'pid'">
          {{ process.pid }}
        </template>

        <template v-else-if="column.key === 'memory'">
          {{ formatMemory(process.memory_mb) }}
        </template>

        <template v-else-if="column.key === 'cpu'">
          {{ process.cpu_usage.toFixed(1) }}%
        </template>

        <span v-else-if="column.key === 'windowTitle'" :title="process.window_title">{{ truncate(process.window_title, 30) }}</span>
      </div>
    </div>

    <div class="row-actions">
      <button
        class="btn-close"
        :title="t('process.menuCloseProcess')"
        @click.stop="$emit('close')"
      >
        <XCircle :size="16" />
    </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { XCircle } from 'lucide-vue-next'
import { useProcessSettingsStore } from '../stores/processSettingsStore'
import { useI18n } from '../composables/useI18n'
import { getProcessColumnClass, getVisibleProcessColumns } from './processColumns'
import type { ProcessInfo } from '../types/process'

interface Props {
  process: ProcessInfo
  selected: boolean
}

defineProps<Props>()

const emit = defineEmits<{
  'toggle-select': []
  close: []
  'context-menu': [event: MouseEvent]
}>()

const settingsStore = useProcessSettingsStore()
const { t } = useI18n()

const visibleColumns = computed(() => getVisibleProcessColumns(settingsStore.settings.columns))

const memoryFormatter = new Intl.NumberFormat(undefined, {
  minimumFractionDigits: 0,
  maximumFractionDigits: 1
})

function formatMemory(memoryMb: number): string {
  if (memoryMb >= 1024) {
    return `${memoryFormatter.format(memoryMb / 1024)} GB`
  }

  return `${memoryFormatter.format(memoryMb)} MB`
}

function truncate(text: string | undefined, maxLength: number): string {
  if (!text) return '-'
  if (text.length <= maxLength) return text
  return text.substring(0, maxLength - 3) + '...'
}

function handleContextMenu(event: MouseEvent) {
  emit('context-menu', event)
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
  width: 16px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
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
  width: 60px;
  flex-shrink: 0;
  display: flex;
  justify-content: center;
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
