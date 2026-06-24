<template>
  <div class="flex h-full bg-gray-50 dark:bg-dark-bg text-gray-800 dark:text-gray-200 overflow-hidden">
    <!-- 左侧：日期导航 + 快捷入口 -->
    <div class="w-64 border-r border-gray-200 dark:border-dark-border flex flex-col bg-white dark:bg-dark-panel shrink-0">
      <div class="p-4 border-b border-gray-200 dark:border-dark-border">
        <label class="block text-xs font-medium text-gray-500 dark:text-gray-400 mb-2">{{ t('workReport.dateRange') }}</label>
        <div class="space-y-2">
          <input
            v-model="store.startDate"
            type="date"
            class="w-full min-w-0 px-2 py-1.5 bg-gray-100 dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-xs outline-none focus:border-primary"
            @change="store.loadLogs()"
          />
          <input
            v-model="store.endDate"
            type="date"
            class="w-full min-w-0 px-2 py-1.5 bg-gray-100 dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-xs outline-none focus:border-primary"
            @change="store.loadLogs()"
          />
        </div>
      </div>

      <div class="p-4 space-y-2 flex-1 overflow-auto">
        <button
          v-for="tab in mainTabs"
          :key="tab.key"
          @click="store.activeMainTab = tab.key"
          :class="['w-full text-left px-3 py-2 rounded-md text-sm transition-colors border', store.activeMainTab === tab.key ? 'bg-[var(--accent-subtle-bg)] text-[var(--accent-subtle-text)] border-[var(--accent-subtle-border)]' : 'bg-[var(--bg-primary)] text-[var(--text-primary)] border-[var(--border-color)] hover:bg-[var(--bg-hover)]']"
        >
          {{ tab.label }}
        </button>

        <div class="pt-4 mt-4 border-t border-gray-200 dark:border-dark-border space-y-2">
          <button
            @click="activePanel = 'config'"
            :class="['w-full text-left px-3 py-2 rounded-md text-sm transition-colors border', activePanel === 'config' ? 'bg-[var(--accent-subtle-bg)] text-[var(--accent-subtle-text)] border-[var(--accent-subtle-border)]' : 'bg-[var(--bg-primary)] text-[var(--text-primary)] border-[var(--border-color)] hover:bg-[var(--bg-hover)]']"
          >
            {{ t('workReport.reportConfig') }}
          </button>
          <button
            @click="activePanel = 'history'"
            :class="['w-full text-left px-3 py-2 rounded-md text-sm transition-colors border', activePanel === 'history' ? 'bg-[var(--accent-subtle-bg)] text-[var(--accent-subtle-text)] border-[var(--accent-subtle-border)]' : 'bg-[var(--bg-primary)] text-[var(--text-primary)] border-[var(--border-color)] hover:bg-[var(--bg-hover)]']"
          >
            {{ t('workReport.history') }}
          </button>
        </div>
      </div>
    </div>

    <!-- 右侧内容区 -->
    <div class="flex-1 flex flex-col min-w-0 overflow-hidden">
      <!-- 主 Tab 切换条 -->
      <div v-if="activePanel === 'main'" class="px-4 pt-4 pb-2">
        <div class="flex space-x-1 p-1 bg-gray-100 dark:bg-dark-hover rounded-lg">
          <button
            v-for="tab in mainTabs"
            :key="tab.key"
            @click="store.activeMainTab = tab.key"
            :class="['px-4 py-1.5 text-sm rounded-md transition-colors', store.activeMainTab === tab.key ? 'bg-white dark:bg-dark-panel text-primary shadow-sm' : 'text-gray-600 dark:text-gray-300 hover:text-gray-900 dark:hover:text-gray-100']"
          >
            {{ tab.label }}
          </button>
        </div>
      </div>

      <!-- 固定工作子 Tab -->
      <div v-if="activePanel === 'main' && store.activeMainTab === 'fixed'" class="px-4 pb-2">
        <div class="flex space-x-1">
          <button
            v-for="sub in fixedSubTabs"
            :key="sub.key"
            @click="store.activeFixedSubTab = sub.key"
            :class="['px-3 py-1 text-xs rounded-md border transition-colors', store.activeFixedSubTab === sub.key ? 'bg-[var(--accent-subtle-bg)] text-[var(--accent-subtle-text)] border-[var(--accent-subtle-border)]' : 'bg-[var(--bg-primary)] text-[var(--text-primary)] border-[var(--border-color)] hover:bg-[var(--bg-hover)]']"
          >
            {{ sub.label }}
          </button>
        </div>
      </div>

      <!-- 内容面板 -->
      <div v-if="activePanel === 'main'" class="flex-1 overflow-hidden">
        <WorkLogEditor v-if="store.activeMainTab === 'logs'" />
        <FuturePlanPanel v-else-if="store.activeMainTab === 'future'" />
        <FixedWorkPanel v-else-if="store.activeMainTab === 'fixed'" :key="store.activeFixedSubTab" :type="store.activeFixedSubTab" />
      </div>

      <div v-else-if="activePanel === 'config'" class="flex-1 overflow-auto p-4">
        <ReportConfigForm @generate="handleGenerate" />
      </div>

      <div v-else-if="activePanel === 'history'" class="flex-1 overflow-hidden flex flex-col">
        <ReportViewer v-if="store.currentReport" :report="store.currentReport" class="flex-1 min-h-0 border-b border-gray-200 dark:border-dark-border" />
        <ReportHistoryList class="flex-1 min-h-0" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, computed, watch } from 'vue'
import { useWorkReportStore } from '@/stores/workReportStore'
import { useI18n } from '@/composables/useI18n'
import WorkLogEditor from './WorkLogEditor.vue'
import FixedWorkPanel from './FixedWorkPanel.vue'
import FuturePlanPanel from './FuturePlanPanel.vue'
import ReportConfigForm from './ReportConfigForm.vue'
import ReportViewer from './ReportViewer.vue'
import ReportHistoryList from './ReportHistoryList.vue'

import type { MainTab } from '@/stores/workReportStore'
import type { RecurrenceType } from '@/types/workReport'

const store = useWorkReportStore()
const { t } = useI18n()
const activePanel = ref<'main' | 'config' | 'history'>('main')

const mainTabs = computed(() => [
  { key: 'logs' as MainTab, label: t('workReport.workLogs') },
  { key: 'future' as MainTab, label: t('workReport.futurePlans') },
  { key: 'fixed' as MainTab, label: t('workReport.fixedWork') },
])

const fixedSubTabs = computed(() => [
  { key: 'DAILY' as RecurrenceType, label: t('workReport.fixedWorkDay') },
  { key: 'WEEKLY' as RecurrenceType, label: t('workReport.fixedWorkWeek') },
  { key: 'MONTHLY' as RecurrenceType, label: t('workReport.fixedWorkMonth') },
])

watch(() => store.activeMainTab, () => {
  activePanel.value = 'main'
})

onMounted(() => {
  store.loadToday()
})

async function handleGenerate(configId: number) {
  activePanel.value = 'history'
  await store.generateReport(configId)
  await store.loadReports()
}
</script>
