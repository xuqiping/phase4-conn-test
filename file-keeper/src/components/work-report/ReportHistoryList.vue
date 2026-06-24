<template>
  <div class="h-full flex flex-col p-4 bg-white dark:bg-dark-panel">
    <div class="flex items-center justify-between mb-3">
      <h3 class="text-sm font-semibold">{{ t('workReport.history') }}</h3>
      <button @click="refresh" class="p-1.5 rounded hover:bg-gray-100 dark:hover:bg-dark-hover text-gray-500">
        <RefreshCw :size="14" />
      </button>
    </div>

    <div class="flex-1 overflow-auto space-y-2">
      <div
        v-for="report in store.reports"
        :key="report.id"
        @click="store.currentReport = report"
        :class="['p-3 rounded-lg border cursor-pointer transition-colors', store.currentReport?.id === report.id ? 'border-primary bg-primary/5' : 'border-gray-200 dark:border-dark-border bg-gray-50 dark:bg-dark-bg hover:bg-gray-100 dark:hover:bg-dark-hover']"
      >
        <div class="flex items-center justify-between">
          <p class="text-sm font-medium truncate flex-1">{{ report.title }}</p>
          <span :class="['text-[10px] px-1.5 py-0.5 rounded flex-shrink-0 ml-2', statusClass(report.status)]">{{ statusLabel(report.status) }}</span>
        </div>
        <p class="text-xs text-gray-500 mt-1">{{ formatDate(report.generatedAt) }}</p>
        <div class="mt-2 flex justify-end space-x-2">
          <button @click.stop="pushReport(report.id)" class="px-2 py-1 text-xs rounded-md border border-[var(--accent-subtle-border)] bg-[var(--bg-primary)] text-[var(--accent-subtle-text)] hover:bg-[var(--accent-subtle-bg)]">{{ t('workReport.pushReport') }}</button>
          <button @click.stop="store.removeReport(report.id)" class="px-2 py-1 text-xs rounded-md border border-[var(--danger-subtle-border)] bg-[var(--bg-primary)] text-[var(--danger-subtle-text)] hover:bg-[var(--danger-subtle-bg)]">{{ t('common.delete') }}</button>
        </div>
      </div>

      <div v-if="store.reports.length === 0" class="text-center text-gray-400 py-8">
        {{ t('workReport.emptyHistory') }}
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import { RefreshCw } from 'lucide-vue-next'
import { useWorkReportStore } from '@/stores/workReportStore'
import { useI18n } from '@/composables/useI18n'

const store = useWorkReportStore()
const { t } = useI18n()

onMounted(() => {
  store.loadReports()
})

async function refresh() {
  await store.loadReports()
}

function statusClass(status: string) {
  switch (status) {
    case 'PUSHED': return 'bg-green-100 dark:bg-green-900/20 text-green-600'
    case 'FAILED': return 'bg-red-100 dark:bg-red-900/20 text-red-600'
    case 'PUSHING': return 'bg-yellow-100 dark:bg-yellow-900/20 text-yellow-600'
    default: return 'bg-gray-100 dark:bg-gray-800 text-gray-600'
  }
}

function statusLabel(status: string) {
  return t(`workReport.status.${status}`) || status
}

function formatDate(value: string) {
  const date = new Date(value)
  return date.toLocaleString()
}

async function pushReport(reportId: number) {
  try {
    await store.pushReport(reportId)
    await store.loadReports()
  } catch (e) {
    alert(e instanceof Error ? e.message : String(e))
  }
}
</script>
