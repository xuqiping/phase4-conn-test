<template>
  <div class="fixed inset-0 z-[80] flex items-center justify-center bg-black/40 p-4" @click="$emit('close')">
    <div class="w-full max-w-2xl rounded-xl bg-white shadow-2xl dark:bg-dark-panel" @click.stop>
      <div class="border-b border-gray-200 px-5 py-4 dark:border-dark-border">
        <h2 class="text-base font-semibold">剪贴板设置</h2>
      </div>

      <div class="max-h-[70vh] space-y-5 overflow-auto p-5">
        <section class="space-y-3">
          <h3 class="text-sm font-semibold">安全防护</h3>
          <label class="flex items-center justify-between text-sm">
            <span>默认拦截密码、密钥、银行卡等敏感内容</span>
            <input v-model="local.protectSensitiveContent" type="checkbox" />
          </label>
          <p class="text-xs text-gray-500 dark:text-gray-400">
            {{ t('clipboard.privacy.sensitiveProtection') }}
          </p>
          <label class="flex items-center justify-between text-sm">
            <span>URL 联网预览</span>
            <input v-model="local.enableLinkPreview" type="checkbox" />
          </label>
          <p class="text-xs text-gray-500 dark:text-gray-400">
            {{ t('clipboard.privacy.linkPreview') }}
          </p>
        </section>

        <section class="space-y-3">
          <h3 class="text-sm font-semibold">粘贴行为</h3>
          <label class="flex items-center justify-between text-sm">
            <span>自动粘贴到原窗口</span>
            <input data-test="auto-paste" v-model="local.autoPaste" type="checkbox" />
          </label>
          <p class="text-xs text-gray-500 dark:text-gray-400">
            {{ t('clipboard.privacy.autoPaste') }}
          </p>
          <label class="flex items-center justify-between text-sm">
            <span>启用 OCR</span>
            <input v-model="local.enableOcr" type="checkbox" />
          </label>
        </section>

        <section class="space-y-3">
          <h3 class="text-sm font-semibold">空间规则</h3>
          <label class="block text-sm">
            非文本缓存上限（MB）
            <input v-model.number="local.totalNonTextLimitMb" type="number" min="128" class="mt-1 w-full rounded border px-3 py-2 dark:bg-dark-hover" />
          </label>
          <label class="block text-sm">
            单条记录大小上限（MB）
            <input v-model.number="local.itemSizeLimitMb" type="number" min="1" class="mt-1 w-full rounded border px-3 py-2 dark:bg-dark-hover" />
          </label>
          <p class="text-xs text-gray-500 dark:text-gray-400">
            {{ t('clipboard.privacy.fileCopy') }}
          </p>
        </section>

        <section class="space-y-3">
          <h3 class="text-sm font-semibold">后缀规则</h3>
          <select data-test="extension-mode" v-model="local.fileExtensionMode" class="w-full rounded border px-3 py-2 dark:bg-dark-hover">
            <option value="allow_all">不限制，默认都可以保存</option>
            <option value="allow_list">只保存这些后缀</option>
            <option value="block_list">排除这些后缀</option>
          </select>
          <label class="block text-sm">
            后缀列表，用逗号分隔
            <input data-test="extensions" v-model="extensionsText" placeholder="pdf, docx, png" class="mt-1 w-full rounded border px-3 py-2 dark:bg-dark-hover" />
          </label>
        </section>
      </div>

      <div class="flex justify-end gap-3 border-t border-gray-200 px-5 py-4 dark:border-dark-border">
        <button class="rounded px-4 py-2 text-sm hover:bg-gray-100 dark:hover:bg-dark-hover" @click="$emit('close')">取消</button>
        <button data-test="save-settings" class="rounded bg-primary px-4 py-2 text-sm text-white" @click="save">保存</button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
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
