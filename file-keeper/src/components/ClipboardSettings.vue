<template>
  <div class="fixed inset-0 z-[80] flex items-center justify-center bg-black/40 p-4" @click="$emit('close')">
    <div class="w-full max-w-2xl rounded-xl bg-white shadow-2xl dark:bg-dark-panel" @click.stop>
      <div class="border-b border-gray-200 px-5 py-4 dark:border-dark-border">
        <h2 class="text-base font-semibold">{{ t('clipboard.settings') }}</h2>
      </div>

      <div class="max-h-[70vh] space-y-5 overflow-auto p-5">
        <section class="space-y-3">
          <h3 class="text-sm font-semibold">{{ t('clipboard.settingsForm.securityTitle') }}</h3>
          <label class="flex items-center justify-between text-sm">
            <span>{{ t('clipboard.settingsForm.protectSensitiveContent') }}</span>
            <input v-model="local.protectSensitiveContent" type="checkbox" />
          </label>
          <p class="text-xs text-gray-500 dark:text-gray-400">
            {{ t('clipboard.privacy.sensitiveProtection') }}
          </p>
          <label class="flex items-center justify-between text-sm">
            <span>{{ t('clipboard.settingsForm.urlPreview') }}</span>
            <input v-model="local.enableLinkPreview" type="checkbox" />
          </label>
          <p class="text-xs text-gray-500 dark:text-gray-400">
            {{ t('clipboard.privacy.linkPreview') }}
          </p>
        </section>

        <section class="space-y-3">
          <h3 class="text-sm font-semibold">{{ t('clipboard.settingsForm.pasteBehaviorTitle') }}</h3>
          <label class="flex items-center justify-between text-sm">
            <span>{{ t('clipboard.settingsForm.autoPaste') }}</span>
            <input data-test="auto-paste" v-model="local.autoPaste" type="checkbox" />
          </label>
          <p class="text-xs text-gray-500 dark:text-gray-400">
            {{ t('clipboard.privacy.autoPaste') }}
          </p>
          <label class="flex items-center justify-between text-sm">
            <span>{{ t('clipboard.settingsForm.enableOcr') }}</span>
            <input v-model="local.enableOcr" type="checkbox" />
          </label>
        </section>

        <section class="space-y-3">
          <h3 class="text-sm font-semibold">{{ t('clipboard.settingsForm.fileSaveTitle') }}</h3>
          <select data-test="file-save-mode" v-model="local.fileSaveMode" class="w-full rounded border px-3 py-2 dark:bg-dark-hover">
            <option value="backup">{{ t('clipboard.settingsForm.backupMode') }}</option>
            <option value="reference_only">{{ t('clipboard.settingsForm.referenceOnlyMode') }}</option>
          </select>
          <div class="space-y-2 rounded-lg bg-gray-50 p-3 text-sm dark:bg-dark-hover">
            <div class="text-xs font-medium text-gray-500 dark:text-gray-400">{{ t('clipboard.settingsForm.backupLocation') }}</div>
            <div class="break-all text-xs text-gray-600 dark:text-gray-300">
              {{ local.backupDirectory || t('clipboard.settingsForm.defaultBackupDirectory') }}
            </div>
            <div class="flex gap-2">
              <button type="button" class="rounded bg-gray-100 px-3 py-1.5 text-xs hover:bg-gray-200 dark:bg-dark-panel dark:hover:bg-dark-border" @click="chooseBackupDirectory">
                {{ t('clipboard.actions.chooseDirectory') }}
              </button>
              <button type="button" class="rounded bg-gray-100 px-3 py-1.5 text-xs hover:bg-gray-200 dark:bg-dark-panel dark:hover:bg-dark-border" @click="local.backupDirectory = null">
                {{ t('clipboard.actions.useDefaultDirectory') }}
              </button>
            </div>
          </div>
          <p class="text-xs text-gray-500 dark:text-gray-400">
            {{ t('clipboard.settingsForm.fileSaveHint') }}
          </p>
        </section>

        <section class="space-y-3">
          <h3 class="text-sm font-semibold">{{ t('clipboard.settingsForm.storageRulesTitle') }}</h3>
          <label class="block text-sm">
            {{ t('clipboard.settingsForm.totalNonTextLimit') }}
            <input v-model.number="local.totalNonTextLimitMb" type="number" min="128" class="mt-1 w-full rounded border px-3 py-2 dark:bg-dark-hover" />
          </label>
          <label class="block text-sm">
            {{ t('clipboard.settingsForm.itemSizeLimit') }}
            <input v-model.number="local.itemSizeLimitMb" type="number" min="1" class="mt-1 w-full rounded border px-3 py-2 dark:bg-dark-hover" />
          </label>
          <p class="text-xs text-gray-500 dark:text-gray-400">
            {{ t('clipboard.privacy.fileCopy') }}
          </p>
        </section>

        <section class="space-y-3">
          <h3 class="text-sm font-semibold">{{ t('clipboard.settingsForm.extensionRulesTitle') }}</h3>
          <select data-test="extension-mode" v-model="local.fileExtensionMode" class="w-full rounded border px-3 py-2 dark:bg-dark-hover">
            <option value="allow_all">{{ t('clipboard.settingsForm.extensionAllowAll') }}</option>
            <option value="allow_list">{{ t('clipboard.settingsForm.extensionAllowList') }}</option>
            <option value="block_list">{{ t('clipboard.settingsForm.extensionBlockList') }}</option>
          </select>
          <label class="block text-sm">
            {{ t('clipboard.settingsForm.extensionListLabel') }}
            <input data-test="extensions" v-model="extensionsText" placeholder="pdf, docx, png" class="mt-1 w-full rounded border px-3 py-2 dark:bg-dark-hover" />
          </label>
        </section>
      </div>

      <div class="flex justify-end gap-3 border-t border-gray-200 px-5 py-4 dark:border-dark-border">
        <button class="rounded px-4 py-2 text-sm hover:bg-gray-100 dark:hover:bg-dark-hover" @click="$emit('close')">{{ t('common.cancel') }}</button>
        <button data-test="save-settings" class="rounded border border-gray-200 bg-white px-4 py-2 text-sm font-medium text-gray-700 shadow-sm hover:bg-gray-50 dark:border-dark-border dark:bg-dark-panel dark:text-gray-200 dark:hover:bg-dark-hover" @click="save">{{ t('common.save') }}</button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { pickFolder } from '../api/files'
import { useI18n } from '../composables/useI18n'
import type { ClipboardSettings } from '../types/clipboard'

const props = defineProps<{
  settings: ClipboardSettings
}>()

const emit = defineEmits<{
  save: [settings: ClipboardSettings]
  close: []
}>()

const { t } = useI18n()
const local = ref<ClipboardSettings>({ ...props.settings, typeLimitsMb: { ...props.settings.typeLimitsMb } })
const extensionsText = ref(props.settings.fileExtensions.join(', '))

watch(() => props.settings, (settings) => {
  local.value = { ...settings, typeLimitsMb: { ...settings.typeLimitsMb } }
  extensionsText.value = settings.fileExtensions.join(', ')
})

async function chooseBackupDirectory() {
  const selected = await pickFolder(local.value.backupDirectory ?? undefined)
  if (selected) {
    local.value.backupDirectory = selected
  }
}

function save() {
  emit('save', {
    ...local.value,
    fileExtensions: extensionsText.value
      .split(',')
      .map(item => item.trim().replace(/^\./, '').toLowerCase())
      .filter(Boolean)
  })
}
</script>
