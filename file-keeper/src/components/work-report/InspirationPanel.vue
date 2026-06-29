<template>
  <div class="h-full flex flex-col overflow-auto p-4 bg-white dark:bg-dark-panel">
    <div class="flex items-center justify-between mb-3">
      <h3 class="text-sm font-semibold">{{ t('workReport.inspirations') }}</h3>
      <button
        @click="startAdd"
        class="px-3 py-1.5 text-xs rounded-md border border-[var(--border-color)] bg-[var(--bg-primary)] text-[var(--text-primary)] hover:bg-[var(--bg-hover)] transition-colors flex items-center space-x-1"
      >
        <Plus :size="14" />
        <span>{{ t('workReport.addInspiration') }}</span>
      </button>
    </div>

    <div v-if="store.error" class="mb-3 p-2 rounded-md bg-red-50 dark:bg-red-900/20 text-red-600 dark:text-red-400 text-xs">
      {{ store.error }}
    </div>

    <!-- Quick input / edit form -->
    <div v-if="isEditing" class="mb-3 p-3 rounded-lg border border-gray-200 dark:border-dark-border bg-gray-50 dark:bg-dark-bg space-y-2">
      <textarea
        v-model="editingNote.content"
        rows="3"
        :placeholder="t('workReport.inspirationContentPlaceholder')"
        class="w-full px-2 py-1.5 bg-white dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-sm outline-none focus:border-primary resize-none"
      />
      <input
        v-model="tagInput"
        :placeholder="t('workReport.inspirationTagsPlaceholder')"
        class="w-full px-2 py-1.5 bg-white dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-sm outline-none focus:border-primary"
      />
      <div class="flex justify-end space-x-2">
        <button @click="cancelEdit" class="px-3 py-1 text-xs rounded-md border border-[var(--border-color)] bg-[var(--bg-primary)] text-[var(--text-primary)] hover:bg-[var(--bg-hover)]">{{ t('common.cancel') }}</button>
        <button
          @click="submitSave"
          :disabled="saving || !editingNote.content?.trim()"
          class="px-3 py-1 text-xs rounded-md border border-[var(--accent-subtle-border)] bg-[var(--bg-primary)] text-[var(--accent-subtle-text)] hover:bg-[var(--accent-subtle-bg)] disabled:opacity-50 disabled:cursor-not-allowed"
        >
          {{ saving ? t('common.saving') : t('common.save') }}
        </button>
      </div>
    </div>

    <!-- Tag filter -->
    <div v-if="allTags.length > 0" class="mb-3 flex flex-wrap gap-1">
      <button
        v-for="tag in allTags"
        :key="tag"
        @click="toggleTag(tag)"
        :class="['px-2 py-0.5 text-[10px] rounded-full border transition-colors', selectedTags.has(tag) ? 'bg-primary border-primary text-white' : 'bg-white dark:bg-dark-hover border-gray-200 dark:border-dark-border text-gray-600 dark:text-gray-300 hover:border-primary']"
      >
        #{{ tag }}
      </button>
      <button
        v-if="selectedTags.size > 0"
        @click="selectedTags.clear()"
        class="px-2 py-0.5 text-[10px] rounded-full border border-gray-200 dark:border-dark-border text-gray-500 hover:border-primary"
      >
        {{ t('common.clear') }}
      </button>
    </div>

    <!-- List -->
    <div class="flex-1 overflow-auto space-y-2">
      <div
        v-for="note in filteredNotes"
        :key="note.id"
        class="flex items-start justify-between p-3 rounded-lg border border-gray-200 dark:border-dark-border bg-gray-50 dark:bg-dark-bg group"
      >
        <div class="flex-1 min-w-0">
          <p class="text-sm whitespace-pre-wrap">{{ note.content }}</p>
          <div class="mt-1.5 flex flex-wrap items-center gap-2">
            <span
              v-for="tag in note.tags"
              :key="tag"
              class="text-[10px] px-1.5 py-0.5 rounded-full bg-blue-100 dark:bg-blue-900/20 text-blue-600 dark:text-blue-400"
            >
              #{{ tag }}
            </span>
            <span v-if="note.reviewedAt" class="text-[10px] px-1.5 py-0.5 rounded-full bg-green-100 dark:bg-green-900/20 text-green-600 dark:text-green-400">
              {{ t('workReport.reviewed') }}
            </span>
            <span class="text-[10px] text-gray-400">
              {{ formatDate(note.createdAt) }}
            </span>
          </div>
        </div>
        <div class="flex items-center space-x-1 ml-2">
          <button
            v-if="!note.reviewedAt"
            @click="store.reviewInspiration(note.id!)"
            class="p-1 rounded opacity-0 group-hover:opacity-100 hover:bg-green-100 dark:hover:bg-green-900/20 text-green-500 transition-opacity"
            :title="t('workReport.markReviewed')"
          >
            <Check :size="14" />
          </button>
          <button
            @click="startEdit(note)"
            class="p-1 rounded opacity-0 group-hover:opacity-100 hover:bg-blue-100 dark:hover:bg-blue-900/20 text-blue-500 transition-opacity"
          >
            <Pencil :size="14" />
          </button>
          <button
            @click="store.removeInspiration(note.id!)"
            class="p-1 rounded opacity-0 group-hover:opacity-100 hover:bg-red-100 dark:hover:bg-red-900/20 text-red-500 transition-opacity"
          >
            <Trash2 :size="14" />
          </button>
        </div>
      </div>

      <div v-if="filteredNotes.length === 0 && !isEditing" class="text-center text-gray-400 py-8">
        {{ t('workReport.emptyInspirations') }}
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { Plus, Check, Trash2, Pencil } from 'lucide-vue-next'
import { useWorkReportStore } from '@/stores/workReportStore'
import { useI18n } from '@/composables/useI18n'
import type { InspirationNote } from '@/types/inspiration'

const store = useWorkReportStore()
const { t } = useI18n()

const isEditing = ref(false)
const saving = ref(false)
const editingNote = ref<Partial<InspirationNote>>({})
const tagInput = ref('')
const selectedTags = ref<Set<string>>(new Set())

onMounted(() => {
  store.loadInspirations()
})

const allTags = computed(() => {
  const tags = new Set<string>()
  store.inspirationNotes.forEach(note => {
    note.tags?.forEach(tag => tags.add(tag))
  })
  return Array.from(tags).sort()
})

const filteredNotes = computed(() => {
  let notes = store.inspirationNotes
  if (selectedTags.value.size > 0) {
    notes = notes.filter(note => note.tags?.some(tag => selectedTags.value.has(tag)))
  }
  return notes
})

function startAdd() {
  isEditing.value = true
  editingNote.value = {}
  tagInput.value = ''
}

function startEdit(note: InspirationNote) {
  isEditing.value = true
  editingNote.value = { ...note }
  tagInput.value = note.tags?.join(', ') ?? ''
}

function cancelEdit() {
  isEditing.value = false
  editingNote.value = {}
  tagInput.value = ''
}

function toggleTag(tag: string) {
  const next = new Set(selectedTags.value)
  if (next.has(tag)) {
    next.delete(tag)
  } else {
    next.add(tag)
  }
  selectedTags.value = next
}

function parseTags(input: string): string[] {
  return input
    .split(/[,，\s]+/)
    .map(s => s.trim().replace(/^#/, ''))
    .filter(s => s.length > 0)
}

function formatDate(dateStr?: string): string {
  if (!dateStr) return ''
  const d = new Date(dateStr)
  return d.toLocaleString()
}

async function submitSave() {
  if (!editingNote.value.content?.trim() || saving.value) return
  saving.value = true
  try {
    await store.saveInspiration({
      ...editingNote.value,
      tags: parseTags(tagInput.value),
    })
    cancelEdit()
  } finally {
    saving.value = false
  }
}
</script>
