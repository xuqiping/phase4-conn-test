<template>
  <div class="h-full flex flex-col overflow-auto p-4 bg-white dark:bg-dark-panel">
    <div class="flex items-center justify-between mb-3">
      <h3 class="text-sm font-semibold">{{ t('workReport.futurePlans') }}</h3>
      <button
        @click="startAdd"
        class="px-3 py-1.5 text-xs rounded-md border border-[var(--border-color)] bg-[var(--bg-primary)] text-[var(--text-primary)] hover:bg-[var(--bg-hover)] transition-colors flex items-center space-x-1"
      >
        <Plus :size="14" />
        <span>{{ t('workReport.addFuturePlan') }}</span>
      </button>
    </div>

    <div v-if="store.error" class="mb-3 p-2 rounded-md bg-red-50 dark:bg-red-900/20 text-red-600 dark:text-red-400 text-xs">
      {{ store.error }}
    </div>

    <!-- Add/Edit Form -->
    <div v-if="isEditing" class="mb-3 p-3 rounded-lg border border-gray-200 dark:border-dark-border bg-gray-50 dark:bg-dark-bg space-y-2">
      <input
        v-model="editingPlan.content"
        :placeholder="t('workReport.futurePlanContentPlaceholder')"
        class="w-full px-2 py-1.5 bg-white dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-sm outline-none focus:border-primary"
      />
      <textarea
        v-model="editingPlan.description"
        rows="2"
        :placeholder="t('workReport.descriptionPlaceholder')"
        class="w-full px-2 py-1.5 bg-white dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-sm outline-none focus:border-primary resize-none"
      />

      <div class="grid grid-cols-2 gap-2">
        <div class="space-y-1">
          <label class="text-[10px] text-gray-500 dark:text-gray-400">{{ t('workReport.scheduledAt') }}</label>
          <input
            v-model="scheduledAtLocal"
            type="datetime-local"
            class="w-full px-2 py-1.5 bg-white dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-sm outline-none focus:border-primary"
          />
        </div>
        <div class="space-y-1">
          <label class="text-[10px] text-gray-500 dark:text-gray-400">{{ t('workReport.reminderMinutesBefore') }}</label>
          <input
            v-model.number="editingPlan.reminderMinutesBefore"
            type="number"
            min="0"
            :placeholder="t('workReport.minutesPlaceholder')"
            class="w-full px-2 py-1.5 bg-white dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-sm outline-none focus:border-primary"
          />
        </div>
      </div>

      <div class="flex items-center space-x-2">
        <input
          id="futureReminderEnabled"
          v-model="editingPlan.reminderEnabled"
          type="checkbox"
          class="rounded border-gray-300 dark:border-dark-border"
        />
        <label for="futureReminderEnabled" class="text-sm">{{ t('workReport.reminderEnabled') }}</label>
      </div>

      <div class="space-y-1">
        <label class="text-[10px] text-gray-500 dark:text-gray-400">{{ t('workReport.pushTarget') }}</label>
        <select
          v-model="editingPlan.pushTargetId"
          class="w-full px-2 py-1.5 bg-white dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-sm outline-none focus:border-primary"
        >
          <option :value="undefined">{{ t('workReport.noPushTarget') }}</option>
          <option
            v-for="target in store.pushTargets"
            :key="target.id"
            :value="target.id"
          >
            {{ target.name }} · {{ platformLabel(target.platform) }}
          </option>
        </select>
      </div>

      <div class="flex justify-end space-x-2">
        <button @click="cancelEdit" class="px-3 py-1 text-xs rounded-md border border-[var(--border-color)] bg-[var(--bg-primary)] text-[var(--text-primary)] hover:bg-[var(--bg-hover)]">{{ t('common.cancel') }}</button>
        <button
          @click="submitSave"
          :disabled="saving || !editingPlan.content?.trim() || !scheduledAtLocal"
          class="px-3 py-1 text-xs rounded-md border border-[var(--accent-subtle-border)] bg-[var(--bg-primary)] text-[var(--accent-subtle-text)] hover:bg-[var(--accent-subtle-bg)] disabled:opacity-50 disabled:cursor-not-allowed"
        >
          {{ saving ? t('common.saving') : t('common.save') }}
        </button>
      </div>
    </div>

    <!-- List -->
    <div class="flex-1 overflow-auto space-y-2">
      <div
        v-for="plan in store.sortedFuturePlans"
        :key="plan.id"
        class="flex items-start justify-between p-3 rounded-lg border border-gray-200 dark:border-dark-border bg-gray-50 dark:bg-dark-bg group"
      >
        <div class="flex-1 min-w-0">
          <div class="flex items-center space-x-2">
            <p class="text-sm">{{ plan.content }}</p>
            <span :class="['text-[10px] px-1.5 py-0.5 rounded', statusClass(plan.status)]">
              {{ statusLabel(plan.status) }}
            </span>
          </div>
          <p v-if="plan.description" class="text-xs text-gray-500 dark:text-gray-400 mt-1 whitespace-pre-wrap">{{ plan.description }}</p>
          <div class="mt-1.5 flex flex-wrap items-center gap-2 text-[10px] text-gray-500 dark:text-gray-400">
            <span class="bg-gray-100 dark:bg-dark-hover px-1.5 py-0.5 rounded">
              {{ formatDateTime(plan.scheduledAt) }}
            </span>
            <span v-if="plan.reminderEnabled" class="bg-blue-100 dark:bg-blue-900/20 text-blue-600 dark:text-blue-400 px-1.5 py-0.5 rounded">
              {{ t('workReport.reminderOn') }} {{ plan.reminderMinutesBefore }}{{ t('workReport.minutesBefore') }}
            </span>
            <span v-if="plan.pushTargetId" class="bg-green-100 dark:bg-green-900/20 text-green-600 dark:text-green-400 px-1.5 py-0.5 rounded">
              {{ pushTargetName(plan.pushTargetId) }}
            </span>
          </div>
        </div>
        <div class="flex items-center space-x-1 ml-2">
          <button
            v-if="plan.status === 'PENDING'"
            @click="store.completeFuturePlan(plan.id!)"
            class="p-1 rounded opacity-0 group-hover:opacity-100 hover:bg-green-100 dark:hover:bg-green-900/20 text-green-500 transition-opacity"
            :title="t('workReport.complete')"
          >
            <Check :size="14" />
          </button>
          <button
            v-if="plan.status === 'PENDING'"
            @click="store.cancelFuturePlan(plan.id!)"
            class="p-1 rounded opacity-0 group-hover:opacity-100 hover:bg-yellow-100 dark:hover:bg-yellow-900/20 text-yellow-500 transition-opacity"
            :title="t('workReport.cancel')"
          >
            <X :size="14" />
          </button>
          <button
            @click="startEdit(plan)"
            class="p-1 rounded opacity-0 group-hover:opacity-100 hover:bg-blue-100 dark:hover:bg-blue-900/20 text-blue-500 transition-opacity"
          >
            <Pencil :size="14" />
          </button>
          <button
            @click="store.removeFuturePlan(plan.id!)"
            class="p-1 rounded opacity-0 group-hover:opacity-100 hover:bg-red-100 dark:hover:bg-red-900/20 text-red-500 transition-opacity"
          >
            <Trash2 :size="14" />
          </button>
        </div>
      </div>

      <div v-if="store.sortedFuturePlans.length === 0 && !isEditing" class="text-center text-gray-400 py-8">
        {{ t('workReport.emptyFuturePlan') }}
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { Plus, Check, X, Trash2, Pencil } from 'lucide-vue-next'
import { useWorkReportStore } from '@/stores/workReportStore'
import { useI18n } from '@/composables/useI18n'
import type { FuturePlan, FuturePlanStatus } from '@/types/workReport'

const store = useWorkReportStore()
const { t } = useI18n()

const isEditing = ref(false)
const saving = ref(false)
const editingPlan = ref<Partial<FuturePlan>>({
  reminderEnabled: false,
  reminderMinutesBefore: 0,
})
const scheduledAtLocal = ref('')

onMounted(() => {
  store.loadFuturePlans()
  store.loadPushTargets()
})

function startAdd() {
  isEditing.value = true
  editingPlan.value = {
    reminderEnabled: false,
    reminderMinutesBefore: 0,
  }
  scheduledAtLocal.value = ''
}

function startEdit(plan: FuturePlan) {
  isEditing.value = true
  editingPlan.value = { ...plan }
  scheduledAtLocal.value = toLocalDatetime(plan.scheduledAt)
}

function cancelEdit() {
  isEditing.value = false
  editingPlan.value = {
    reminderEnabled: false,
    reminderMinutesBefore: 0,
  }
  scheduledAtLocal.value = ''
}

function toLocalDatetime(isoString?: string): string {
  if (!isoString) return ''
  const date = new Date(isoString)
  if (Number.isNaN(date.getTime())) return ''
  const pad = (n: number) => n.toString().padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function toIsoString(local: string): string {
  if (!local) return ''
  return new Date(local).toISOString()
}

function formatDateTime(isoString?: string): string {
  if (!isoString) return ''
  const date = new Date(isoString)
  if (Number.isNaN(date.getTime())) return isoString
  return date.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function platformLabel(platform: string): string {
  switch (platform) {
    case 'FEISHU': return t('workReport.platformFeishu')
    case 'DINGTALK': return t('workReport.platformDingtalk')
    case 'WECHAT_WORK': return t('workReport.platformWechatWork')
    case 'SLACK': return t('workReport.platformSlack')
    default: return platform
  }
}

function pushTargetName(targetId?: number): string {
  if (!targetId) return ''
  return store.pushTargets.find(t => t.id === targetId)?.name || String(targetId)
}

function statusClass(status: FuturePlanStatus): string {
  switch (status) {
    case 'COMPLETED': return 'bg-green-100 dark:bg-green-900/20 text-green-600 dark:text-green-400'
    case 'CANCELLED': return 'bg-gray-100 dark:bg-gray-800 text-gray-500 dark:text-gray-400'
    case 'REMINDED': return 'bg-blue-100 dark:bg-blue-900/20 text-blue-600 dark:text-blue-400'
    default: return 'bg-yellow-100 dark:bg-yellow-900/20 text-yellow-600 dark:text-yellow-400'
  }
}

function statusLabel(status: FuturePlanStatus): string {
  switch (status) {
    case 'COMPLETED': return t('workReport.futurePlanStatusCompleted')
    case 'CANCELLED': return t('workReport.futurePlanStatusCancelled')
    case 'REMINDED': return t('workReport.futurePlanStatusReminded')
    default: return t('workReport.futurePlanStatusPending')
  }
}

async function submitSave() {
  if (!editingPlan.value.content?.trim() || saving.value || !scheduledAtLocal.value) return
  saving.value = true
  try {
    await store.saveFuturePlan({
      ...editingPlan.value,
      scheduledAt: toIsoString(scheduledAtLocal.value),
    })
    cancelEdit()
  } finally {
    saving.value = false
  }
}
</script>
