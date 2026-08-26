<template>
  <section class="rounded-2xl border border-gray-200 bg-white/80 p-4 shadow-sm dark:border-dark-border dark:bg-dark-panel/80">
    <div class="mb-3 flex flex-wrap items-center justify-between gap-3">
      <div>
        <h3 class="text-sm font-semibold text-gray-900 dark:text-gray-100">{{ t('office.issues.title') }}</h3>
        <p class="mt-1 text-xs text-gray-500 dark:text-gray-400">{{ t('office.issues.blockingAlwaysVisible') }}</p>
      </div>
      <div class="flex rounded-lg bg-gray-100 p-1 dark:bg-dark-bg" role="group" :aria-label="t('office.issues.filterLabel')">
        <button
          v-for="option in filters"
          :key="option.value"
          type="button"
          class="rounded-md px-3 py-1.5 text-xs font-medium transition-colors"
          :class="filter === option.value ? 'bg-white text-gray-900 shadow-sm dark:bg-dark-hover dark:text-white' : 'text-gray-500 hover:text-gray-800 dark:text-gray-400 dark:hover:text-gray-200'"
          @click="filter = option.value"
        >
          {{ option.label }}
        </button>
      </div>
    </div>

    <div v-if="visibleIssues.length" class="space-y-2" aria-live="polite">
      <article
        v-for="issue in visibleIssues"
        :key="issue.issueId"
        class="flex items-start gap-3 rounded-xl border px-3 py-3"
        :class="issue.severity === 'blocking' ? 'border-red-200 bg-red-50/80 dark:border-red-900/60 dark:bg-red-950/20' : 'border-amber-200 bg-amber-50/70 dark:border-amber-900/50 dark:bg-amber-950/20'"
      >
        <ShieldAlert :size="17" class="mt-0.5 shrink-0" :class="issue.severity === 'blocking' ? 'text-red-600' : 'text-amber-600'" />
        <div class="min-w-0">
          <div class="flex flex-wrap items-center gap-2">
            <span class="text-xs font-bold uppercase tracking-wide" :class="issue.severity === 'blocking' ? 'text-red-700 dark:text-red-300' : 'text-amber-700 dark:text-amber-300'">
              {{ t(`office.severity.${issue.severity}`) }}
            </span>
            <code class="text-[11px] text-gray-400">{{ issue.code }}</code>
          </div>
          <p class="mt-1 break-words text-sm text-gray-700 dark:text-gray-200">{{ t(issue.messageKey) }}</p>
        </div>
      </article>
    </div>
    <p v-else class="rounded-xl border border-emerald-200 bg-emerald-50 px-3 py-4 text-sm text-emerald-700 dark:border-emerald-900/50 dark:bg-emerald-950/20 dark:text-emerald-300">
      {{ t('office.issues.none') }}
    </p>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ShieldAlert } from 'lucide-vue-next'
import { useI18n } from '../../composables/useI18n'
import type { OfficePreflightIssue } from '../../types/office'

const props = defineProps<{ issues: OfficePreflightIssue[] }>()
const { t } = useI18n()
const filter = ref<'all' | 'warning' | 'blocking'>('all')
const filters = computed(() => [
  { value: 'all' as const, label: t('office.issues.all') },
  { value: 'warning' as const, label: t('office.severity.warning') },
  { value: 'blocking' as const, label: t('office.severity.blocking') }
])
const visibleIssues = computed(() => props.issues.filter(issue => {
  if (issue.severity === 'blocking') return true
  return filter.value === 'all' || issue.severity === filter.value
}))
</script>
