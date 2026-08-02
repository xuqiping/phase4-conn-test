<template>
  <div class="h-full flex flex-col overflow-hidden p-4 bg-gray-50 dark:bg-dark-bg">
    <div class="flex items-center justify-between mb-3">
      <h3 class="text-sm font-semibold">{{ t('workReport.inbox') }}</h3>
      <button
        @click="store.loadInbox()"
        :disabled="store.inboxLoading"
        class="px-3 py-1.5 text-xs rounded-md border border-[var(--border-color)] bg-[var(--bg-primary)] text-[var(--text-primary)] hover:bg-[var(--bg-hover)] transition-colors disabled:opacity-50"
      >
        {{ store.inboxLoading ? '加载中...' : t('common.refresh') }}
      </button>
    </div>

    <div v-if="store.error" class="mb-3 p-2 rounded-md bg-red-50 dark:bg-red-900/20 text-red-600 dark:text-red-400 text-xs">
      {{ store.error }}
    </div>

    <div class="flex-1 overflow-auto space-y-2 -mx-4 px-4">
      <div
        v-for="message in store.inboxMessages"
        :key="message.id"
        class="p-3 rounded-lg border border-gray-200 dark:border-dark-border bg-white dark:bg-dark-panel"
      >
        <div class="flex items-start justify-between gap-2">
          <div class="flex-1 min-w-0">
            <p class="text-sm font-medium">{{ message.rawText }}</p>
            <div class="mt-1.5 flex flex-wrap items-center gap-2 text-[10px] text-gray-500 dark:text-gray-400">
              <span class="px-1.5 py-0.5 rounded bg-gray-100 dark:bg-dark-hover">{{ platformLabel(message.platform) }}</span>
              <span>{{ message.senderName || message.senderId || t('workReport.unknownSender') }}</span>
              <span>{{ formatIntent(message.intent) }}</span>
              <span v-if="message.confidence >= 0.85" class="text-green-600 dark:text-green-400">{{ t('workReport.highConfidence') }}</span>
              <span v-else class="text-yellow-600 dark:text-yellow-400">{{ t('workReport.lowConfidence') }}</span>
            </div>
          </div>
          <div class="flex items-center space-x-1 shrink-0">
            <button
              @click="confirm(message.id, 'CONFIRM')"
              class="px-2 py-1 text-xs rounded-md bg-primary text-white hover:bg-primary/90 transition-colors"
            >
              {{ t('common.confirm') }}
            </button>
            <button
              @click="confirm(message.id, 'IGNORE')"
              class="px-2 py-1 text-xs rounded-md border border-[var(--border-color)] bg-[var(--bg-primary)] text-[var(--text-primary)] hover:bg-[var(--bg-hover)] transition-colors"
            >
              {{ t('common.ignore') }}
            </button>
          </div>
        </div>
      </div>

      <div v-if="store.inboxMessages.length === 0" class="text-center text-gray-400 py-8 text-sm">
        {{ t('workReport.emptyInbox') }}
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted } from 'vue'
import { useWorkReportStore } from '@/stores/workReportStore'
import { useAuthStore } from '@/stores/authStore'
import { useI18n } from '@/composables/useI18n'
import type { InboxIntent } from '@/types/inbox'

const store = useWorkReportStore()
const authStore = useAuthStore()
const { t } = useI18n()

let pollTimer: ReturnType<typeof setInterval> | null = null

onMounted(() => {
  if (!authStore.isAuthenticated) {
    return
  }
  store.loadInbox()
  // MVP 使用轮询；后端 SSE 已就绪，后续可替换为 EventSource
  pollTimer = setInterval(() => store.loadInbox(), 30000)
})

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
})

function platformLabel(platform: string): string {
  switch (platform) {
    case 'FEISHU': return t('workReport.platformFeishu')
    case 'DINGTALK': return t('workReport.platformDingtalk')
    case 'WECHAT_WORK': return t('workReport.platformWechatWork')
    case 'SLACK': return t('workReport.platformSlack')
    default: return platform
  }
}

function formatIntent(intent: InboxIntent): string {
  switch (intent) {
    case 'complete_fixed_work': return t('workReport.intentCompleteFixedWork')
    case 'add_work_log': return t('workReport.intentAddWorkLog')
    case 'add_inspiration': return t('workReport.intentAddInspiration')
    case 'help': return t('workReport.intentHelp')
    default: return t('workReport.intentUnknown')
  }
}

async function confirm(id: number, action: 'CONFIRM' | 'IGNORE') {
  await store.confirmInboxMessage(id, action)
}
</script>
