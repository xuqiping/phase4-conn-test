<template>
  <div class="space-y-2">
    <div class="flex items-center justify-between">
      <span class="text-xs font-medium text-gray-500 dark:text-gray-400">{{ t('workReport.pushTarget') }}</span>
      <button
        type="button"
        @click="showGuide = !showGuide"
        class="text-xs text-blue-600 dark:text-blue-400 hover:underline"
      >
        {{ showGuide ? t('workReport.hideGuide') : t('workReport.viewGuide') }}
      </button>
    </div>

    <PushTargetGuide v-if="showGuide" />

    <div
      v-for="(target, index) in targets"
      :key="index"
      class="p-2 rounded border border-gray-200 dark:border-dark-border bg-white dark:bg-dark-hover space-y-2"
    >
      <div class="grid grid-cols-2 gap-2">
        <select v-model="target.platform" class="px-2 py-1 text-xs rounded border border-gray-200 dark:border-dark-border bg-white dark:bg-dark-hover">
          <option value="FEISHU">{{ t('workReport.platformFeishu') }}</option>
          <option value="DINGTALK">{{ t('workReport.platformDingtalk') }}</option>
        </select>
        <select v-model="target.targetType" class="px-2 py-1 text-xs rounded border border-gray-200 dark:border-dark-border bg-white dark:bg-dark-hover">
          <option value="GROUP">{{ t('workReport.targetGroup') }}</option>
          <option value="USER">{{ t('workReport.targetUser') }}</option>
        </select>
      </div>
      <input
        v-model="target.targetId"
        :placeholder="t('workReport.targetIdPlaceholder')"
        class="w-full px-2 py-1 text-xs rounded border border-gray-200 dark:border-dark-border bg-white dark:bg-dark-hover"
      />
      <textarea
        v-model="target.credential"
        :placeholder="credentialPlaceholder(target)"
        rows="2"
        class="w-full px-2 py-1 text-xs rounded border border-gray-200 dark:border-dark-border bg-white dark:bg-dark-hover resize-none"
      />
      <p
        v-if="target.hasCredential && !target.credential"
        class="text-xs text-gray-400 dark:text-gray-500"
      >
        已保存，凭据不会回显以保障安全
      </p>
      <div class="flex justify-end">
        <button @click="remove(index)" class="px-2 py-1 text-xs rounded-md border border-[var(--danger-subtle-border)] bg-[var(--bg-primary)] text-[var(--danger-subtle-text)] hover:bg-[var(--danger-subtle-bg)]">{{ t('common.delete') }}</button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from '@/composables/useI18n'
import PushTargetGuide from './PushTargetGuide.vue'
import type { ReportPushTarget } from '@/types/workReport'

const { t } = useI18n()

const showGuide = ref(false)

const props = defineProps<{
  modelValue?: ReportPushTarget[]
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: ReportPushTarget[]): void
}>()

const targets = computed(() => props.modelValue || [])

function remove(index: number) {
  const next = [...targets.value]
  next.splice(index, 1)
  emit('update:modelValue', next)
}

function credentialPlaceholder(target: ReportPushTarget): string {
  if (target.hasCredential && !target.credential) {
    return '已保存，凭据不会回显以保障安全'
  }
  return t('workReport.credentialPlaceholder')
}
</script>
