<template>
  <div class="p-3 rounded-lg border border-gray-200 dark:border-dark-border bg-gray-50 dark:bg-dark-bg">
    <textarea
      v-model="content"
      rows="2"
      :placeholder="props.placeholder || t('workReport.inspirationQuickInputPlaceholder')"
      class="w-full px-2 py-1.5 bg-white dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-sm outline-none focus:border-primary resize-none"
      @keydown.enter.ctrl.prevent="submit"
    />
    <div class="mt-2 flex items-center justify-between">
      <input
        v-model="tagInput"
        :placeholder="t('workReport.inspirationTagsPlaceholder')"
        class="flex-1 min-w-0 mr-2 px-2 py-1.5 bg-white dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-xs outline-none focus:border-primary"
      />
      <button
        @click="submit"
        :disabled="!content.trim() || submitting"
        class="px-3 py-1.5 text-xs rounded-md border border-[var(--accent-subtle-border)] bg-[var(--bg-primary)] text-[var(--accent-subtle-text)] hover:bg-[var(--accent-subtle-bg)] disabled:opacity-50 disabled:cursor-not-allowed flex items-center space-x-1"
      >
        <Sparkles :size="12" />
        <span>{{ submitting ? t('common.saving') : t('common.save') }}</span>
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { Sparkles } from 'lucide-vue-next'
import { useWorkReportStore } from '@/stores/workReportStore'
import { useI18n } from '@/composables/useI18n'

const props = defineProps<{
  placeholder?: string
}>()

const emit = defineEmits<{
  (e: 'saved'): void
}>()

const store = useWorkReportStore()
const { t } = useI18n()

const content = ref('')
const tagInput = ref('')
const submitting = ref(false)

function parseTags(input: string): string[] {
  return input
    .split(/[,，\s]+/)
    .map(s => s.trim().replace(/^#/, ''))
    .filter(s => s.length > 0)
}

async function submit() {
  if (!content.value.trim() || submitting.value) return
  submitting.value = true
  try {
    await store.saveInspiration({
      content: content.value,
      tags: parseTags(tagInput.value),
    })
    content.value = ''
    tagInput.value = ''
    emit('saved')
  } finally {
    submitting.value = false
  }
}
</script>
