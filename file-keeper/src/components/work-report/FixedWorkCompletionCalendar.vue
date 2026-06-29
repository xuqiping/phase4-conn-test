<template>
  <div class="h-full flex flex-col overflow-auto p-4 bg-white dark:bg-dark-panel">
    <div class="flex items-center justify-between mb-4">
      <button
        @click="prevMonth"
        class="p-1 rounded hover:bg-gray-100 dark:hover:bg-dark-hover"
      >
        <ChevronLeft :size="18" />
      </button>
      <span class="text-sm font-semibold">{{ currentYearMonth }}</span>
      <button
        @click="nextMonth"
        class="p-1 rounded hover:bg-gray-100 dark:hover:bg-dark-hover"
      >
        <ChevronRight :size="18" />
      </button>
    </div>

    <div class="grid grid-cols-7 gap-1 mb-1">
      <div
        v-for="day in weekDays"
        :key="day"
        class="text-center text-[10px] text-gray-500 dark:text-gray-400 py-1"
      >
        {{ day }}
      </div>
    </div>

    <div v-if="loading" class="flex-1 flex items-center justify-center text-gray-400 text-sm">
      {{ t('common.loading') }}
    </div>

    <div v-else class="grid grid-cols-7 gap-1">
      <button
        v-for="cell in calendarCells"
        :key="cell.date"
        @click="selectDate(cell.date)"
        :class="[
          'aspect-square p-1 rounded-lg border text-xs flex flex-col items-center justify-center transition-colors',
          cell.isCurrentMonth ? 'bg-gray-50 dark:bg-dark-bg' : 'bg-transparent text-gray-400',
          selectedDate === cell.date ? 'border-primary ring-1 ring-primary' : 'border-gray-200 dark:border-dark-border hover:border-primary',
        ]"
      >
        <span>{{ cell.dayOfMonth }}</span>
        <span
          v-if="cell.completionRate !== undefined"
          :class="[
            'mt-0.5 h-1.5 w-1.5 rounded-full',
            cell.completionRate === 1 ? 'bg-green-500' : cell.completionRate > 0 ? 'bg-yellow-500' : 'bg-gray-300 dark:bg-gray-600',
          ]"
        />
      </button>
    </div>

    <!-- Selected day detail -->
    <div v-if="selectedDate" class="mt-4 p-3 rounded-lg border border-gray-200 dark:border-dark-border bg-gray-50 dark:bg-dark-bg">
      <div class="flex items-center justify-between mb-2">
        <span class="text-sm font-medium">{{ selectedDate }}</span>
        <button @click="selectedDate = null" class="text-gray-400 hover:text-gray-600">
          <X :size="14" />
        </button>
      </div>
      <div v-if="selectedItems.length === 0" class="text-xs text-gray-400">
        {{ t('workReport.noFixedWorkForDate') }}
      </div>
      <div v-else class="space-y-2">
        <div
          v-for="item in selectedItems"
          :key="item.id"
          class="flex items-center justify-between text-sm"
        >
          <span :class="['flex-1', item.completed ? 'line-through text-gray-400' : '']">{{ item.content }}</span>
          <button
            @click="toggleItem(item)"
            :class="['w-4 h-4 rounded border flex items-center justify-center transition-colors', item.completed ? 'bg-primary border-primary' : 'border-gray-300 dark:border-gray-600 hover:border-primary']"
          >
            <Check v-if="item.completed" :size="10" class="text-white" />
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { ChevronLeft, ChevronRight, Check, X } from 'lucide-vue-next'
import { useWorkReportStore } from '@/stores/workReportStore'
import { useI18n } from '@/composables/useI18n'
import type { FixedWorkItem } from '@/types/workReport'

const store = useWorkReportStore()
const { t } = useI18n()

const currentDate = ref(new Date())
const selectedDate = ref<string | null>(null)
const dateStates = ref<Record<string, { total: number; completed: number; items: (FixedWorkItem & { completed?: boolean })[] }>>({})
const loading = ref(false)

const weekDays = computed(() => [
  t('workReport.sun'),
  t('workReport.mon'),
  t('workReport.tue'),
  t('workReport.wed'),
  t('workReport.thu'),
  t('workReport.fri'),
  t('workReport.sat'),
])

const currentYearMonth = computed(() => {
  const y = currentDate.value.getFullYear()
  const m = currentDate.value.getMonth() + 1
  return `${y}年${m}月`
})

function formatDate(d: Date): string {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

function getMonthRange(d: Date): { start: string; end: string } {
  const year = d.getFullYear()
  const month = d.getMonth()
  const start = new Date(year, month, 1)
  const end = new Date(year, month + 1, 0)
  return { start: formatDate(start), end: formatDate(end) }
}

const calendarCells = computed(() => {
  const year = currentDate.value.getFullYear()
  const month = currentDate.value.getMonth()
  const firstDay = new Date(year, month, 1)
  const lastDay = new Date(year, month + 1, 0)
  const startOffset = firstDay.getDay()
  const daysInMonth = lastDay.getDate()

  const cells: { date: string; dayOfMonth: number; isCurrentMonth: boolean; completionRate?: number }[] = []

  // previous month padding
  const prevLastDay = new Date(year, month, 0).getDate()
  for (let i = startOffset - 1; i >= 0; i--) {
    const d = new Date(year, month - 1, prevLastDay - i)
    cells.push({
      date: formatDate(d),
      dayOfMonth: d.getDate(),
      isCurrentMonth: false,
      completionRate: dateStates.value[formatDate(d)]?.total > 0
        ? dateStates.value[formatDate(d)].completed / dateStates.value[formatDate(d)].total
        : undefined,
    })
  }

  // current month
  for (let i = 1; i <= daysInMonth; i++) {
    const d = new Date(year, month, i)
    const dateStr = formatDate(d)
    cells.push({
      date: dateStr,
      dayOfMonth: i,
      isCurrentMonth: true,
      completionRate: dateStates.value[dateStr]?.total > 0
        ? dateStates.value[dateStr].completed / dateStates.value[dateStr].total
        : undefined,
    })
  }

  // next month padding to fill 6 rows (42 cells)
  const remaining = 42 - cells.length
  for (let i = 1; i <= remaining; i++) {
    const d = new Date(year, month + 1, i)
    cells.push({
      date: formatDate(d),
      dayOfMonth: d.getDate(),
      isCurrentMonth: false,
      completionRate: dateStates.value[formatDate(d)]?.total > 0
        ? dateStates.value[formatDate(d)].completed / dateStates.value[formatDate(d)].total
        : undefined,
    })
  }

  return cells
})

const selectedItems = computed(() => {
  if (!selectedDate.value) return []
  return dateStates.value[selectedDate.value]?.items ?? []
})

function prevMonth() {
  currentDate.value = new Date(currentDate.value.getFullYear(), currentDate.value.getMonth() - 1, 1)
}

function nextMonth() {
  currentDate.value = new Date(currentDate.value.getFullYear(), currentDate.value.getMonth() + 1, 1)
}

function selectDate(date: string) {
  selectedDate.value = date
}

async function toggleItem(item: FixedWorkItem & { completed?: boolean }) {
  if (!selectedDate.value) return
  await store.toggleFixedWorkForDate(item.id!, selectedDate.value)
  await loadMonthData()
}

async function loadMonthData() {
  loading.value = true
  try {
    const { start, end } = getMonthRange(currentDate.value)
    const states = await store.loadFixedWorkCalendar(start, end)
    dateStates.value = states
  } finally {
    loading.value = false
  }
}

watch(currentDate, loadMonthData, { immediate: true })
</script>
