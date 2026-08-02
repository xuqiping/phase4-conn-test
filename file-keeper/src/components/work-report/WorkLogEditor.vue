<template>
  <div class="h-full flex flex-col p-4 bg-white dark:bg-dark-panel">
    <div class="flex items-center justify-between mb-3">
      <h3 class="text-sm font-semibold">{{ t('workReport.workLog') }}</h3>
      <button
        @click="startAdd"
        class="px-3 py-1.5 text-xs rounded-md border border-[var(--border-color)] bg-[var(--bg-primary)] text-[var(--text-primary)] hover:bg-[var(--bg-hover)] transition-colors flex items-center space-x-1"
      >
        <Plus :size="14" />
        <span>{{ t('workReport.addLog') }}</span>
      </button>
    </div>

    <div v-if="store.error" class="mb-3 p-2 rounded-md bg-red-50 dark:bg-red-900/20 text-red-600 dark:text-red-400 text-xs">
      {{ store.error }}
    </div>

    <div class="flex-1 overflow-auto space-y-2">
      <div
        v-for="log in store.todayLogs"
        :key="log.id"
        class="p-3 rounded-lg border border-gray-200 dark:border-dark-border bg-gray-50 dark:bg-dark-bg"
      >
        <div v-if="editingId === log.id" class="space-y-2">
          <textarea
            v-model="editingContent"
            rows="2"
            class="w-full px-2 py-1.5 bg-white dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-sm outline-none focus:border-primary resize-none"
          />
          <input
            v-model="editingTags"
            :placeholder="t('workReport.tagsPlaceholder')"
            class="w-full px-2 py-1.5 bg-white dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-sm outline-none focus:border-primary"
          />
          <div class="flex justify-end space-x-2">
            <button @click="cancelEdit" class="px-3 py-1 text-xs rounded-md border border-[var(--border-color)] bg-[var(--bg-primary)] text-[var(--text-primary)] hover:bg-[var(--bg-hover)]">{{ t('common.cancel') }}</button>
            <button
              @click="saveEdit(log.id!)"
              :disabled="saving"
              class="px-3 py-1 text-xs rounded-md border border-[var(--accent-subtle-border)] bg-[var(--bg-primary)] text-[var(--accent-subtle-text)] hover:bg-[var(--accent-subtle-bg)] disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {{ saving ? t('common.saving') : t('common.save') }}
            </button>
          </div>
        </div>
        <div v-else class="group">
          <div class="flex items-start justify-between">
            <p class="text-sm whitespace-pre-wrap">{{ log.content }}</p>
            <span v-if="log.logDate" class="text-[10px] text-gray-400 dark:text-gray-500 ml-2 shrink-0">{{ log.logDate }}</span>
          </div>
          <div v-if="log.tags" class="mt-2 flex flex-wrap gap-1">
            <span
              v-for="tag in log.tags.split(',')"
              :key="tag"
              class="text-[10px] px-1.5 py-0.5 rounded bg-primary/10 text-primary border border-primary/20"
            >
              {{ tag.trim() }}
            </span>
          </div>
          <div class="mt-2 opacity-0 group-hover:opacity-100 transition-opacity flex justify-end space-x-2">
            <button @click="startEdit(log)" class="p-1 rounded hover:bg-gray-200 dark:hover:bg-dark-hover text-gray-500">
              <Pencil :size="14" />
            </button>
            <button @click="store.removeLog(log.id!)" class="p-1 rounded hover:bg-red-100 dark:hover:bg-red-900/20 text-red-500">
              <Trash2 :size="14" />
            </button>
          </div>
        </div>
      </div>

      <div v-if="isAdding" class="p-3 rounded-lg border border-gray-200 dark:border-dark-border bg-gray-50 dark:bg-dark-bg space-y-2">
        <textarea
          v-model="newContent"
          rows="2"
          :placeholder="t('workReport.logPlaceholder')"
          class="w-full px-2 py-1.5 bg-white dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-sm outline-none focus:border-primary resize-none"
        />
        <input
          v-model="newTags"
          :placeholder="t('workReport.tagsPlaceholder')"
          class="w-full px-2 py-1.5 bg-white dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-sm outline-none focus:border-primary"
        />
        <div class="flex justify-end space-x-2">
          <button @click="isAdding = false" class="px-3 py-1 text-xs rounded-md border border-[var(--border-color)] bg-[var(--bg-primary)] text-[var(--text-primary)] hover:bg-[var(--bg-hover)]">{{ t('common.cancel') }}</button>
          <button
            @click="submitAdd"
            :disabled="saving"
            class="px-3 py-1 text-xs rounded-md border border-[var(--accent-subtle-border)] bg-[var(--bg-primary)] text-[var(--accent-subtle-text)] hover:bg-[var(--accent-subtle-bg)] disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {{ saving ? t('common.saving') : t('common.save') }}
          </button>
        </div>
      </div>

      <div v-if="store.todayLogs.length === 0 && !isAdding" class="text-center text-gray-400 py-8">
        {{ t('workReport.emptyLog') }}
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { Plus, Pencil, Trash2 } from 'lucide-vue-next'
import { useWorkReportStore } from '@/stores/workReportStore'
import { useI18n } from '@/composables/useI18n'
import type { WorkLog } from '@/types/workReport'

const store = useWorkReportStore()
const { t } = useI18n()

const isAdding = ref(false)
const newContent = ref('')
const newTags = ref('')
const editingId = ref<number | null>(null)
const editingContent = ref('')
const editingTags = ref('')
const saving = ref(false)

onMounted(() => {
  if (store.todayLogs.length === 0) {
    store.loadToday()
  }
})

function startAdd() {
  isAdding.value = true
  newContent.value = ''
  newTags.value = ''
}

async function submitAdd() {
  if (!newContent.value.trim() || saving.value) return
  saving.value = true
  try {
    await store.saveLog({ content: newContent.value, tags: newTags.value })
    isAdding.value = false
    newContent.value = ''
    newTags.value = ''
  } finally {
    saving.value = false
  }
}

function startEdit(log: WorkLog) {
  editingId.value = log.id ?? null
  editingContent.value = log.content
  editingTags.value = log.tags || ''
}

function cancelEdit() {
  editingId.value = null
}

async function saveEdit(id: number) {
  if (!editingContent.value.trim() || saving.value) return
  saving.value = true
  try {
    await store.saveLog({ id, content: editingContent.value, tags: editingTags.value })
    editingId.value = null
  } finally {
    saving.value = false
  }
}
</script>
