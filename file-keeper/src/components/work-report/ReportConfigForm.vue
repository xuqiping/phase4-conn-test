<template>
  <div class="max-w-2xl mx-auto space-y-6">
    <div class="bg-white dark:bg-dark-panel rounded-lg border border-gray-200 dark:border-dark-border p-4">
      <div class="flex items-center justify-between mb-4">
        <h3 class="text-sm font-semibold">{{ t('workReport.reportConfig') }}</h3>
        <button
          @click="startAdd"
          class="px-3 py-1.5 text-xs rounded-md border border-[var(--border-color)] bg-[var(--bg-primary)] text-[var(--text-primary)] hover:bg-[var(--bg-hover)] transition-colors flex items-center space-x-1"
        >
          <Plus :size="14" />
          <span>{{ t('workReport.addConfig') }}</span>
        </button>
      </div>

      <!-- 配置列表 -->
      <div v-for="config in store.configs" :key="config.id" class="mb-4 p-3 rounded-lg border border-gray-200 dark:border-dark-border bg-gray-50 dark:bg-dark-bg">
        <div v-if="editingId === config.id" class="space-y-3">
          <input v-model="editingConfig.name" :placeholder="t('workReport.configName')" class="w-full px-2 py-1.5 bg-white dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-sm" />
          <div class="grid grid-cols-2 gap-3">
            <select v-model="editingConfig.reportType" class="px-2 py-1.5 bg-white dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-sm">
              <option value="DAILY">{{ t('workReport.daily') }}</option>
              <option value="WEEKLY">{{ t('workReport.weekly') }}</option>
            </select>
            <select v-model="editingConfig.templateId" class="px-2 py-1.5 bg-white dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-sm">
              <option v-for="tpl in store.templates" :key="tpl.id" :value="tpl.id">{{ tpl.name }}</option>
            </select>
          </div>
          <select v-model="editingConfig.cronExpression" class="w-full px-2 py-1.5 bg-white dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-sm">
            <option value="0 0 18 * * ?">{{ t('workReport.cronDaily18') }}</option>
            <option value="0 0 9 * * ?">{{ t('workReport.cronDaily9') }}</option>
            <option value="0 0 18 ? * 5">{{ t('workReport.cronWeeklyFri18') }}</option>
            <option value="0 0 9 ? * 1">{{ t('workReport.cronWeeklyMon9') }}</option>
          </select>
          <div class="flex items-center space-x-4">
            <label class="flex items-center space-x-2 text-sm">
              <input v-model="editingConfig.enabled" type="checkbox" />
              <span>{{ t('workReport.enabled') }}</span>
            </label>
            <label class="flex items-center space-x-2 text-sm">
              <input v-model="editingConfig.aiEnabled" type="checkbox" />
              <span>{{ t('workReport.aiEnabled') }}</span>
            </label>
          </div>

          <div class="border-t border-gray-200 dark:border-dark-border pt-3">
            <div class="flex items-center justify-between mb-2">
              <span class="text-sm font-medium">{{ t('workReport.pushTarget') }}</span>
              <button @click="addPushTarget" class="px-2 py-1 text-xs rounded-md border border-[var(--accent-subtle-border)] bg-[var(--bg-primary)] text-[var(--accent-subtle-text)] hover:bg-[var(--accent-subtle-bg)]">{{ t('workReport.addPushTarget') }}</button>
            </div>
            <PushTargetForm v-model="editingConfig.pushTargets" />
          </div>

          <div v-if="editError" class="text-xs text-red-600 dark:text-red-400 bg-red-50 dark:bg-red-900/20 p-2 rounded">
            {{ editError }}
          </div>

          <div class="flex justify-end space-x-2">
            <button @click="cancelEdit" class="px-3 py-1 text-xs rounded-md border border-[var(--border-color)] bg-[var(--bg-primary)] text-[var(--text-primary)] hover:bg-[var(--bg-hover)]">{{ t('common.cancel') }}</button>
            <button
              @click="saveEdit"
              :disabled="saving"
              class="px-3 py-1 text-xs rounded-md border border-[var(--accent-subtle-border)] bg-[var(--bg-primary)] text-[var(--accent-subtle-text)] hover:bg-[var(--accent-subtle-bg)] disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {{ saving ? t('common.saving') : t('common.save') }}
            </button>
          </div>
        </div>
        <div v-else class="flex items-center justify-between">
          <div>
            <p class="text-sm font-medium">{{ config.name }}</p>
            <p class="text-xs text-gray-500">{{ config.reportType === 'DAILY' ? t('workReport.daily') : t('workReport.weekly') }} · {{ config.templateName || t('workReport.unknownTemplate') }} · {{ config.cronExpression }}</p>
            <p class="text-xs text-gray-500">AI: {{ config.aiEnabled ? t('common.on') : t('common.off') }} · {{ config.enabled ? t('workReport.enabled') : t('workReport.disabled') }}</p>
          </div>
          <div class="flex items-center space-x-2">
            <button @click="generate(config.id!)" class="px-3 py-1.5 text-xs rounded-md border border-[var(--accent-subtle-border)] bg-[var(--bg-primary)] text-[var(--accent-subtle-text)] hover:bg-[var(--accent-subtle-bg)]">{{ t('workReport.generateReport') }}</button>
            <button @click="startEdit(config)" class="p-1.5 rounded hover:bg-gray-200 dark:hover:bg-dark-hover text-gray-500">
              <Pencil :size="14" />
            </button>
            <button @click="store.deleteConfig(config.id!)" class="p-1.5 rounded hover:bg-red-100 dark:hover:bg-red-900/20 text-red-500">
              <Trash2 :size="14" />
            </button>
          </div>
        </div>
      </div>

      <!-- 新增配置 -->
      <div v-if="isAdding" class="p-3 rounded-lg border border-gray-200 dark:border-dark-border bg-gray-50 dark:bg-dark-bg space-y-3">
        <input v-model="newConfig.name" :placeholder="t('workReport.configName')" class="w-full px-2 py-1.5 bg-white dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-sm" />
        <div class="grid grid-cols-2 gap-3">
          <select v-model="newConfig.reportType" class="px-2 py-1.5 bg-white dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-sm">
            <option value="DAILY">{{ t('workReport.daily') }}</option>
            <option value="WEEKLY">{{ t('workReport.weekly') }}</option>
          </select>
          <select v-model="newConfig.templateId" class="px-2 py-1.5 bg-white dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-sm">
            <option v-for="tpl in store.templates" :key="tpl.id" :value="tpl.id">{{ tpl.name }}</option>
          </select>
        </div>
        <select v-model="newConfig.cronExpression" class="w-full px-2 py-1.5 bg-white dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-sm">
          <option value="0 0 18 * * ?">{{ t('workReport.cronDaily18') }}</option>
          <option value="0 0 9 * * ?">{{ t('workReport.cronDaily9') }}</option>
          <option value="0 0 18 ? * 5">{{ t('workReport.cronWeeklyFri18') }}</option>
          <option value="0 0 9 ? * 1">{{ t('workReport.cronWeeklyMon9') }}</option>
        </select>
        <div class="flex items-center space-x-4">
          <label class="flex items-center space-x-2 text-sm">
            <input v-model="newConfig.enabled" type="checkbox" />
            <span>{{ t('workReport.enabled') }}</span>
          </label>
          <label class="flex items-center space-x-2 text-sm">
            <input v-model="newConfig.aiEnabled" type="checkbox" />
            <span>{{ t('workReport.aiEnabled') }}</span>
          </label>
        </div>

        <div class="border-t border-gray-200 dark:border-dark-border pt-3">
          <div class="flex items-center justify-between mb-2">
            <span class="text-sm font-medium">{{ t('workReport.pushTarget') }}</span>
            <button @click="addNewPushTarget" class="px-2 py-1 text-xs rounded-md border border-[var(--accent-subtle-border)] bg-[var(--bg-primary)] text-[var(--accent-subtle-text)] hover:bg-[var(--accent-subtle-bg)]">{{ t('workReport.addPushTarget') }}</button>
          </div>
          <PushTargetForm v-model="newConfig.pushTargets" />
        </div>

        <div v-if="addError" class="text-xs text-red-600 dark:text-red-400 bg-red-50 dark:bg-red-900/20 p-2 rounded">
          {{ addError }}
        </div>

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

      <div v-if="store.configs.length === 0 && !isAdding" class="text-center text-gray-400 py-8">
        {{ t('workReport.emptyConfig') }}
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { Plus, Pencil, Trash2 } from 'lucide-vue-next'
import { useWorkReportStore } from '@/stores/workReportStore'
import { useI18n } from '@/composables/useI18n'
import PushTargetForm from './PushTargetForm.vue'
import type { ReportConfig, ReportPushTarget } from '@/types/workReport'

const store = useWorkReportStore()
const { t } = useI18n()

const emit = defineEmits<{
  (e: 'generate', configId: number): void
}>()

const isAdding = ref(false)
const editingId = ref<number | null>(null)
const editingConfig = ref<Partial<ReportConfig>>(defaultConfig())
const newConfig = ref<Partial<ReportConfig>>(defaultConfig())
const saving = ref(false)
const addError = ref<string | null>(null)
const editError = ref<string | null>(null)

function defaultConfig(): Partial<ReportConfig> {
  return {
    name: '',
    reportType: 'DAILY',
    templateId: undefined,
    cronExpression: '0 0 18 * * ?',
    timezone: 'Asia/Shanghai',
    enabled: true,
    aiEnabled: true,
    pushTargets: [],
  }
}

onMounted(async () => {
  await store.loadTemplates()
  await store.loadConfigs()
  if (store.templates.length > 0 && !newConfig.value.templateId) {
    newConfig.value.templateId = store.templates[0].id
  }
})

function startAdd() {
  isAdding.value = true
  addError.value = null
  newConfig.value = defaultConfig()
  if (store.templates.length > 0) {
    newConfig.value.templateId = store.templates[0].id
  }
}

function validateConfig(config: Partial<ReportConfig>): string | null {
  if (!config.name?.trim()) {
    return t('workReport.configNameRequired')
  }
  if (!config.templateId) {
    return t('workReport.configTemplateRequired')
  }
  const targets = config.pushTargets || []
  for (const target of targets) {
    if (!target.platform || !target.targetType || !target.targetId?.trim()) {
      return t('workReport.configTargetRequired')
    }
  }
  return null
}

async function submitAdd() {
  addError.value = null
  const validationError = validateConfig(newConfig.value)
  if (validationError) {
    addError.value = validationError
    return
  }
  saving.value = true
  try {
    await store.saveConfig({ ...newConfig.value })
    isAdding.value = false
    newConfig.value = defaultConfig()
  } catch (e) {
    addError.value = e instanceof Error ? e.message : String(e)
  } finally {
    saving.value = false
  }
}

function startEdit(config: ReportConfig) {
  editingId.value = config.id ?? null
  editError.value = null
  editingConfig.value = {
    ...config,
    pushTargets: config.pushTargets ? [...config.pushTargets] : [],
  }
}

function cancelEdit() {
  editingId.value = null
  editError.value = null
}

async function saveEdit() {
  editError.value = null
  const validationError = validateConfig(editingConfig.value)
  if (validationError) {
    editError.value = validationError
    return
  }
  saving.value = true
  try {
    await store.saveConfig({ ...editingConfig.value })
    editingId.value = null
  } catch (e) {
    editError.value = e instanceof Error ? e.message : String(e)
  } finally {
    saving.value = false
  }
}

function addPushTarget() {
  editingConfig.value.pushTargets = [...(editingConfig.value.pushTargets || []), defaultPushTarget()]
}

function addNewPushTarget() {
  newConfig.value.pushTargets = [...(newConfig.value.pushTargets || []), defaultPushTarget()]
}

function defaultPushTarget(): ReportPushTarget {
  return {
    platform: 'FEISHU',
    targetType: 'GROUP',
    targetId: '',
    credential: '',
  }
}

async function generate(configId: number) {
  emit('generate', configId)
}
</script>
