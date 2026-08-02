<template>
  <div class="h-full flex flex-col overflow-auto p-4 bg-white dark:bg-dark-panel">
    <div class="flex items-center justify-between mb-3">
      <h3 class="text-sm font-semibold">{{ title }}</h3>
      <button
        @click="startAdd"
        class="px-3 py-1.5 text-xs rounded-md border border-[var(--border-color)] bg-[var(--bg-primary)] text-[var(--text-primary)] hover:bg-[var(--bg-hover)] transition-colors flex items-center space-x-1"
      >
        <Plus :size="14" />
        <span>{{ t('workReport.addFixedWork') }}</span>
      </button>
    </div>

    <div v-if="store.error" class="mb-3 p-2 rounded-md bg-red-50 dark:bg-red-900/20 text-red-600 dark:text-red-400 text-xs">
      {{ store.error }}
    </div>

    <!-- Add/Edit Form -->
    <div v-if="isEditing" class="mb-3 p-3 rounded-lg border border-gray-200 dark:border-dark-border bg-gray-50 dark:bg-dark-bg space-y-2">
      <input
        v-model="editingItem.content"
        :placeholder="t('workReport.fixedWorkContentPlaceholder')"
        class="w-full px-2 py-1.5 bg-white dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-sm outline-none focus:border-primary"
      />
      <textarea
        v-model="editingItem.description"
        rows="2"
        :placeholder="t('workReport.descriptionPlaceholder')"
        class="w-full px-2 py-1.5 bg-white dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-sm outline-none focus:border-primary resize-none"
      />

      <div class="grid grid-cols-2 gap-2">
        <div class="space-y-1">
          <label class="text-[10px] text-gray-500 dark:text-gray-400">{{ t('workReport.reminderTime') }}</label>
          <input
            v-model="editingItem.reminderTime"
            type="time"
            class="w-full px-2 py-1.5 bg-white dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-sm outline-none focus:border-primary"
          />
        </div>
        <div class="space-y-1">
          <label class="text-[10px] text-gray-500 dark:text-gray-400">{{ t('workReport.recurrenceType') }}</label>
          <select
            v-model="editingItem.recurrenceType"
            class="w-full px-2 py-1.5 bg-white dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-sm outline-none focus:border-primary"
          >
            <option value="DAILY">{{ t('workReport.recurrenceDaily') }}</option>
            <option value="WEEKLY">{{ t('workReport.recurrenceWeekly') }}</option>
            <option value="MONTHLY">{{ t('workReport.recurrenceMonthly') }}</option>
          </select>
        </div>
      </div>

      <!-- Weekly days -->
      <div v-if="editingItem.recurrenceType === 'WEEKLY'" class="space-y-1">
        <label class="text-[10px] text-gray-500 dark:text-gray-400">{{ t('workReport.reminderDays') }}</label>
        <div class="flex flex-wrap gap-1">
          <button
            v-for="day in weekDays"
            :key="day.value"
            @click="toggleDay(day.value)"
            :class="['w-8 h-8 text-xs rounded-md border transition-colors', selectedDays.has(day.value) ? 'bg-primary border-primary text-white' : 'bg-white dark:bg-dark-hover border-gray-200 dark:border-dark-border text-gray-600 dark:text-gray-300 hover:border-primary']"
          >
            {{ day.label }}
          </button>
        </div>
      </div>

      <!-- Monthly days -->
      <div v-if="editingItem.recurrenceType === 'MONTHLY'" class="space-y-1">
        <label class="text-[10px] text-gray-500 dark:text-gray-400">{{ t('workReport.reminderDays') }}</label>
        <div class="flex flex-wrap gap-1">
          <button
            v-for="d in 31"
            :key="d"
            @click="toggleMonthDay(d)"
            :class="['w-7 h-7 text-[10px] rounded-md border transition-colors', selectedDays.has(d) ? 'bg-primary border-primary text-white' : 'bg-white dark:bg-dark-hover border-gray-200 dark:border-dark-border text-gray-600 dark:text-gray-300 hover:border-primary']"
          >
            {{ d }}
          </button>
        </div>
      </div>

      <div class="flex items-center space-x-2">
        <input
          id="reminderEnabled"
          v-model="editingItem.reminderEnabled"
          type="checkbox"
          class="rounded border-gray-300 dark:border-dark-border"
        />
        <label for="reminderEnabled" class="text-sm">{{ t('workReport.reminderEnabled') }}</label>
      </div>

      <div class="space-y-1">
        <label class="text-[10px] text-gray-500 dark:text-gray-400">{{ t('workReport.pushTarget') }}</label>
        <select
          v-model="editingItem.pushTargetId"
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
          :disabled="saving || !editingItem.content?.trim()"
          class="px-3 py-1 text-xs rounded-md border border-[var(--accent-subtle-border)] bg-[var(--bg-primary)] text-[var(--accent-subtle-text)] hover:bg-[var(--accent-subtle-bg)] disabled:opacity-50 disabled:cursor-not-allowed"
        >
          {{ saving ? t('common.saving') : t('common.save') }}
        </button>
      </div>
    </div>

    <!-- List -->
    <div class="flex-1 overflow-auto space-y-2">
      <div
        v-for="item in store.sortedFixedWorkItems"
        :key="item.id"
        class="flex items-start justify-between p-3 rounded-lg border border-gray-200 dark:border-dark-border bg-gray-50 dark:bg-dark-bg group"
      >
        <div class="flex items-start space-x-3 flex-1 min-w-0">
          <button
            @click="store.toggleFixedWork(item.id!)"
            :class="['mt-0.5 w-4 h-4 rounded border flex items-center justify-center transition-colors', item.completedToday ? 'bg-primary border-primary' : 'border-gray-300 dark:border-gray-600 hover:border-primary']"
          >
            <Check v-if="item.completedToday" :size="10" class="text-white" />
          </button>
          <div class="flex-1 min-w-0">
            <p class="text-sm">{{ item.content }}</p>
            <p v-if="item.description" class="text-xs text-gray-500 dark:text-gray-400 mt-1 whitespace-pre-wrap">{{ item.description }}</p>
            <div class="mt-1.5 flex flex-wrap items-center gap-2">
              <span class="text-[10px] px-1.5 py-0.5 rounded bg-gray-100 dark:bg-dark-hover text-gray-600 dark:text-gray-300">
                {{ formatRecurrence(item) }}
              </span>
              <span v-if="item.reminderEnabled" class="text-[10px] px-1.5 py-0.5 rounded bg-blue-100 dark:bg-blue-900/20 text-blue-600 dark:text-blue-400">
                {{ t('workReport.reminderOn') }}
              </span>
              <span v-if="item.pushTargetId" class="text-[10px] px-1.5 py-0.5 rounded bg-green-100 dark:bg-green-900/20 text-green-600 dark:text-green-400">
                {{ pushTargetName(item.pushTargetId) }}
              </span>
            </div>
          </div>
        </div>
        <div class="flex items-center space-x-1 ml-2">
          <button
            @click="startEdit(item)"
            class="p-1 rounded opacity-0 group-hover:opacity-100 hover:bg-blue-100 dark:hover:bg-blue-900/20 text-blue-500 transition-opacity"
          >
            <Pencil :size="14" />
          </button>
          <button
            @click="store.removeFixedWork(item.id!)"
            class="p-1 rounded opacity-0 group-hover:opacity-100 hover:bg-red-100 dark:hover:bg-red-900/20 text-red-500 transition-opacity"
          >
            <Trash2 :size="14" />
          </button>
        </div>
      </div>

      <div v-if="store.sortedFixedWorkItems.length === 0 && !isEditing" class="text-center text-gray-400 py-8">
        {{ t('workReport.emptyFixedWork') }}
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { Plus, Check, Trash2, Pencil } from 'lucide-vue-next'
import { useWorkReportStore } from '@/stores/workReportStore'
import { useI18n } from '@/composables/useI18n'
import type { FixedWorkItem, RecurrenceType } from '@/types/workReport'

const props = defineProps<{
  type: RecurrenceType
}>()

const store = useWorkReportStore()
const { t } = useI18n()

const isEditing = ref(false)
const saving = ref(false)
const editingItem = ref<Partial<FixedWorkItem>>({
  recurrenceType: props.type,
  reminderTime: '09:00',
  reminderDays: '',
  reminderEnabled: false,
})
const selectedDays = ref<Set<number>>(new Set())

const title = computed(() => {
  switch (props.type) {
    case 'DAILY': return t('workReport.fixedWorkDay')
    case 'WEEKLY': return t('workReport.fixedWorkWeek')
    case 'MONTHLY': return t('workReport.fixedWorkMonth')
    default: return t('workReport.fixedWork')
  }
})

const weekDays = [
  { value: 1, label: t('workReport.mon') },
  { value: 2, label: t('workReport.tue') },
  { value: 3, label: t('workReport.wed') },
  { value: 4, label: t('workReport.thu') },
  { value: 5, label: t('workReport.fri') },
  { value: 6, label: t('workReport.sat') },
  { value: 7, label: t('workReport.sun') },
]

onMounted(() => {
  store.loadFixedWork(props.type)
  store.loadPushTargets()
})

watch(() => props.type, (newType) => {
  if (!isEditing.value) {
    editingItem.value.recurrenceType = newType
  }
  store.loadFixedWork(newType)
})

function startAdd() {
  isEditing.value = true
  editingItem.value = {
    recurrenceType: props.type,
    reminderTime: '09:00',
    reminderDays: '',
    reminderEnabled: false,
  }
  selectedDays.value = new Set()
}

function startEdit(item: FixedWorkItem) {
  isEditing.value = true
  editingItem.value = { ...item }
  selectedDays.value = parseDays(item.reminderDays)
}

function cancelEdit() {
  isEditing.value = false
  editingItem.value = {
    recurrenceType: props.type,
    reminderTime: '09:00',
    reminderDays: '',
    reminderEnabled: false,
  }
  selectedDays.value = new Set()
}

function toggleDay(day: number) {
  const next = new Set(selectedDays.value)
  if (next.has(day)) {
    next.delete(day)
  } else {
    next.add(day)
  }
  selectedDays.value = next
}

function toggleMonthDay(day: number) {
  toggleDay(day)
}

function parseDays(daysStr?: string): Set<number> {
  if (!daysStr) return new Set()
  return new Set(daysStr.split(',').map(s => parseInt(s.trim(), 10)).filter(n => !isNaN(n)))
}

function formatDays(days?: string): string {
  if (!days) return ''
  const nums = days.split(',').map(s => parseInt(s.trim(), 10)).filter(n => !isNaN(n)).sort((a, b) => a - b)
  return nums.join(', ')
}

function formatRecurrence(item: FixedWorkItem): string {
  const time = item.reminderTime ?? ''
  if (item.recurrenceType === 'DAILY') {
    return `${t('workReport.recurrenceDaily')} ${time}`
  }
  if (item.recurrenceType === 'WEEKLY') {
    const labels = weekDays
      .filter(d => parseDays(item.reminderDays).has(d.value))
      .map(d => d.label)
      .join(', ')
    return `${t('workReport.recurrenceWeekly')} ${labels} ${time}`
  }
  if (item.recurrenceType === 'MONTHLY') {
    return `${t('workReport.recurrenceMonthly')} ${formatDays(item.reminderDays)} ${time}`
  }
  return time
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

async function submitSave() {
  if (!editingItem.value.content?.trim() || saving.value) return
  saving.value = true
  try {
    const days = Array.from(selectedDays.value).sort((a, b) => a - b).join(',')
    await store.saveFixedWork({
      ...editingItem.value,
      reminderDays: days,
    })
    cancelEdit()
  } finally {
    saving.value = false
  }
}
</script>
