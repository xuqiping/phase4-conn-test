<template>
  <main class="relative flex-1 overflow-auto bg-[#f4f6f3] p-6 dark:bg-dark-bg">
    <div class="pointer-events-none absolute inset-x-0 top-0 h-48 bg-[radial-gradient(circle_at_20%_0%,rgba(65,191,132,0.16),transparent_55%)]"></div>
    <div class="relative mx-auto max-w-7xl space-y-5">
      <header class="flex flex-wrap items-end justify-between gap-4">
        <div>
          <p class="text-xs font-bold uppercase tracking-[0.22em] text-primary">{{ t('office.eyebrow') }}</p>
          <h1 class="mt-2 text-2xl font-semibold tracking-tight text-gray-950 dark:text-white">{{ t('office.title') }}</h1>
          <p class="mt-2 max-w-2xl text-sm leading-6 text-gray-500 dark:text-gray-400">{{ t('office.description') }}</p>
        </div>
        <div class="flex rounded-xl border border-gray-200 bg-white p-1 shadow-sm dark:border-dark-border dark:bg-dark-panel" role="tablist">
          <button v-for="item in tabs" :key="item.value" type="button" role="tab" :aria-selected="activeTab === item.value" class="rounded-lg px-4 py-2 text-sm font-semibold transition" :class="activeTab === item.value ? 'bg-gray-900 text-white dark:bg-white dark:text-gray-900' : 'text-gray-500 hover:text-gray-900 dark:text-gray-400 dark:hover:text-white'" @click="activeTab = item.value">{{ item.label }}</button>
        </div>
      </header>
      <OfficeTaskWizard v-if="activeTab === 'new'" />
      <OfficeTaskHistory v-else />
    </div>
  </main>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from '../../composables/useI18n'
import OfficeTaskHistory from './OfficeTaskHistory.vue'
import OfficeTaskWizard from './OfficeTaskWizard.vue'

const { t } = useI18n()
const activeTab = ref<'new' | 'history'>('new')
const tabs = computed(() => [
  { value: 'new' as const, label: t('office.tabs.newTask') },
  { value: 'history' as const, label: t('office.tabs.history') }
])
</script>
