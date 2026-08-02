<template>
  <div class="space-y-2 border border-gray-200 dark:border-dark-border rounded-lg p-3 bg-gray-50 dark:bg-dark-bg">
    <div class="flex items-center justify-between">
      <p class="text-xs font-medium text-gray-500 dark:text-gray-400">{{ t('workReport.pushConfig') }}</p>
      <button
        type="button"
        @click="showGuide = !showGuide"
        class="text-xs text-blue-600 dark:text-blue-400 hover:underline"
      >
        {{ showGuide ? t('workReport.hideGuide') : t('workReport.viewGuide') }}
      </button>
    </div>

    <PushTargetGuide v-if="showGuide" />

    <select
      :value="modelValue.pushPlatform"
      @change="updateField('pushPlatform', ($event.target as HTMLSelectElement).value)"
      class="w-full px-2 py-1.5 bg-white dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-sm outline-none focus:border-primary"
    >
      <option value="">{{ t('workReport.platformNone') }}</option>
      <option value="FEISHU">{{ t('workReport.platformFeishu') }}</option>
      <option value="DINGTALK">{{ t('workReport.platformDingtalk') }}</option>
      <option value="WECHAT_WORK">{{ t('workReport.platformWechatWork') }}</option>
      <option value="SLACK">{{ t('workReport.platformSlack') }}</option>
    </select>
    <input
      :value="modelValue.pushTargetId"
      @input="updateField('pushTargetId', ($event.target as HTMLInputElement).value)"
      type="text"
      :placeholder="t('workReport.pushTargetIdPlaceholder')"
      class="w-full px-2 py-1.5 bg-white dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-sm outline-none focus:border-primary"
    />
    <textarea
      :value="modelValue.pushCredential"
      @input="updateField('pushCredential', ($event.target as HTMLTextAreaElement).value)"
      rows="2"
      :placeholder="t('workReport.pushCredentialPlaceholder')"
      class="w-full px-2 py-1.5 bg-white dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-sm outline-none focus:border-primary resize-none"
    />
    <p v-if="modelValue.hasCredential" class="text-[10px] text-green-600 dark:text-green-400">
      {{ t('workReport.credentialSet') }}
    </p>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from '@/composables/useI18n'
import PushTargetGuide from './PushTargetGuide.vue'

interface PushConfigModel {
  pushPlatform?: string
  pushTargetId?: string
  pushCredential?: string
  hasCredential?: boolean
}

const props = defineProps<{
  modelValue: PushConfigModel
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: PushConfigModel): void
}>()

const { t } = useI18n()

const showGuide = ref(false)

function updateField(key: keyof PushConfigModel, value: string) {
  emit('update:modelValue', { ...props.modelValue, [key]: value })
}
</script>
