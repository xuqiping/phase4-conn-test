<template>
  <div class="h-full flex flex-col p-4 bg-white dark:bg-dark-panel">
    <div v-if="report" class="h-full flex flex-col">
      <div class="flex items-center justify-between mb-3">
        <div class="min-w-0">
          <h3 class="text-sm font-semibold truncate">{{ report.title }}</h3>
          <span :class="['text-[10px] px-1.5 py-0.5 rounded mt-1 inline-block', statusClass(report.status)]">{{ statusLabel(report.status) }}</span>
        </div>
        <div class="flex items-center space-x-2 flex-shrink-0">
          <button
            @click="pushReport"
            :disabled="isPushing"
            class="px-3 py-1.5 text-xs rounded-md border border-[var(--accent-subtle-border)] bg-[var(--bg-primary)] text-[var(--accent-subtle-text)] hover:bg-[var(--accent-subtle-bg)] disabled:opacity-50 disabled:cursor-not-allowed flex items-center space-x-1"
          >
            <Send :size="14" />
            <span>{{ isPushing ? t('workReport.pushing') : t('workReport.pushReport') }}</span>
          </button>
          <button
            @click="exportMarkdown"
            class="px-3 py-1.5 text-xs rounded-md border border-[var(--border-color)] bg-[var(--bg-primary)] text-[var(--text-primary)] hover:bg-[var(--bg-hover)] flex items-center space-x-1"
          >
            <Download :size="14" />
            <span>{{ t('workReport.exportMarkdown') }}</span>
          </button>
        </div>
      </div>
      <pre class="flex-1 overflow-auto p-3 rounded-lg bg-gray-50 dark:bg-dark-bg border border-gray-200 dark:border-dark-border text-xs whitespace-pre-wrap">{{ report.content }}</pre>
    </div>
    <div v-else class="h-full flex items-center justify-center text-gray-400">
      {{ t('workReport.emptyReport') }}
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { Send, Download } from 'lucide-vue-next'
import { useWorkReportStore } from '@/stores/workReportStore'
import { useI18n } from '@/composables/useI18n'
import type { WorkReport } from '@/types/workReport'

const props = defineProps<{
  report: WorkReport | null
}>()

const store = useWorkReportStore()
const { t } = useI18n()
const isPushing = ref(false)

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

async function pushReport() {
  if (!props.report) return
  isPushing.value = true
  try {
    await store.pushReport(props.report.id)
    await store.loadReports()
  } catch (e) {
    alert(e instanceof Error ? e.message : String(e))
  } finally {
    isPushing.value = false
  }
}

function exportMarkdown() {
  if (!props.report) return
  const blob = new Blob([`# ${props.report.title}\n\n${props.report.content}`], { type: 'text/markdown' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${props.report.title}.md`
  a.click()
  URL.revokeObjectURL(url)
}
</script>
