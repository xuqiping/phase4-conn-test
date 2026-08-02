<template>
  <div class="rounded-lg border border-gray-200 bg-white p-3 text-xs dark:border-dark-border dark:bg-dark-panel">
    <div class="font-medium">{{ t('clipboard.security.title') }}</div>
    <p class="mt-1 text-gray-500">{{ t('clipboard.security.description') }}</p>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from '../composables/useI18n'

const { t } = useI18n()
</script>
