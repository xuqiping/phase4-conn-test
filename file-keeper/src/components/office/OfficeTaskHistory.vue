<template>
  <section class="space-y-3">
    <div class="flex items-center justify-between">
      <div>
        <h2 class="text-base font-semibold text-gray-900 dark:text-gray-100">{{ t('office.history.title') }}</h2>
        <p class="mt-1 text-xs text-gray-500 dark:text-gray-400">{{ t('office.history.count', { count: store.historyTotal }) }}</p>
      </div>
      <button type="button" class="rounded-lg border border-gray-200 px-3 py-2 text-xs font-medium hover:border-primary hover:text-primary dark:border-dark-border" @click="refresh">
        {{ t('office.actions.refresh') }}
      </button>
    </div>

    <div v-if="store.history.length" class="grid gap-3 lg:grid-cols-2">
      <article v-for="task in store.history" :key="task.taskId" class="rounded-2xl border border-gray-200 bg-white p-4 shadow-sm dark:border-dark-border dark:bg-dark-panel">
        <div class="flex items-start justify-between gap-4">
          <div>
            <p class="text-sm font-semibold text-gray-900 dark:text-gray-100">{{ t(`office.taskType.${task.taskType}`) }}</p>
            <p class="mt-1 font-mono text-[11px] text-gray-400">{{ task.taskId }}</p>
          </div>
          <span class="rounded-full px-2.5 py-1 text-[11px] font-bold" :class="statusClass(task.status)">{{ t(`office.status.${task.status}`) }}</span>
        </div>
        <dl class="mt-4 grid grid-cols-3 gap-3 text-xs">
          <div><dt class="text-gray-400">{{ t('office.history.files') }}</dt><dd class="mt-1 font-semibold">{{ task.inputCount }}</dd></div>
          <div><dt class="text-gray-400">{{ t('office.history.size') }}</dt><dd class="mt-1 font-semibold">{{ formatBytes(task.totalBytes) }}</dd></div>
          <div><dt class="text-gray-400">{{ t('office.history.created') }}</dt><dd class="mt-1 font-semibold">{{ formatDate(task.createdAt) }}</dd></div>
        </dl>
      </article>
    </div>
    <div v-else class="rounded-2xl border border-dashed border-gray-300 px-6 py-16 text-center text-sm text-gray-400 dark:border-dark-border">
      {{ t('office.history.empty') }}
    </div>
  </section>
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import { useI18n } from '../../composables/useI18n'
import { useOfficeTaskStore } from '../../stores/officeTaskStore'
import type { OfficeTaskStatus } from '../../types/office'

const store = useOfficeTaskStore()
const { t } = useI18n()
const refresh = () => void store.loadHistory()
onMounted(refresh)

function formatBytes(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 ** 2) return `${(bytes / 1024).toFixed(1)} KB`
  if (bytes < 1024 ** 3) return `${(bytes / 1024 ** 2).toFixed(1)} MB`
  return `${(bytes / 1024 ** 3).toFixed(1)} GB`
}

function formatDate(timestamp: number) {
  return new Date(timestamp).toLocaleString()
}

function statusClass(status: OfficeTaskStatus) {
  if (status === 'succeeded') return 'bg-emerald-100 text-emerald-700 dark:bg-emerald-950/40 dark:text-emerald-300'
  if (status === 'failed' || status === 'cancelled') return 'bg-red-100 text-red-700 dark:bg-red-950/40 dark:text-red-300'
  return 'bg-sky-100 text-sky-700 dark:bg-sky-950/40 dark:text-sky-300'
}
</script>
