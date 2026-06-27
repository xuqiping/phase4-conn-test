<template>
  <div class="space-y-4">
    <!-- 配置列表 -->
    <div v-if="store.configs.length > 0" class="space-y-2">
      <div
        v-for="config in store.configs"
        :key="config.id"
        class="flex items-center justify-between p-3 rounded-lg border border-gray-200 dark:border-dark-border bg-gray-50 dark:bg-dark-bg"
      >
        <div class="min-w-0 flex-1">
          <div class="flex items-center space-x-2">
            <span class="text-sm font-medium truncate">{{ config.name }}</span>
            <span v-if="config.isDefault" class="px-1.5 py-0.5 text-xs rounded bg-primary/10 text-primary">默认</span>
            <span v-if="!config.enabled" class="px-1.5 py-0.5 text-xs rounded bg-gray-200 dark:bg-gray-700 text-gray-600 dark:text-gray-400">已禁用</span>
          </div>
          <p class="text-xs text-gray-500 truncate mt-0.5">
            {{ providerLabel(config.provider) }} · {{ config.model }}
          </p>
        </div>
        <div class="flex items-center space-x-1 ml-2">
          <button
            v-if="!config.isDefault && config.enabled"
            @click="setDefault(config.id)"
            class="p-1.5 text-gray-500 hover:text-primary hover:bg-primary/10 rounded transition-colors"
            title="设为默认"
          >
            <Star :size="14" />
          </button>
          <button
            @click="edit(config)"
            class="p-1.5 text-gray-500 hover:text-blue-500 hover:bg-blue-500/10 rounded transition-colors"
            title="编辑"
          >
            <Pencil :size="14" />
          </button>
          <button
            @click="remove(config.id)"
            class="p-1.5 text-gray-500 hover:text-red-500 hover:bg-red-500/10 rounded transition-colors"
            title="删除"
          >
            <Trash2 :size="14" />
          </button>
        </div>
      </div>
    </div>

    <div v-else class="text-center py-6 text-gray-400 text-sm">
      暂无 AI 配置，点击下方按钮添加
    </div>

    <!-- 新增/编辑表单 -->
    <div v-if="isEditing" class="p-4 rounded-lg border border-gray-200 dark:border-dark-border bg-gray-50 dark:bg-dark-bg space-y-3">
      <input
        v-model="form.name"
        :placeholder="t('aiConfig.namePlaceholder')"
        class="w-full px-3 py-2 bg-white dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-sm outline-none focus:border-primary"
      />
      <div class="grid grid-cols-2 gap-3">
        <select
          v-model="form.provider"
          @change="onProviderChange"
          class="px-3 py-2 bg-white dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-sm outline-none focus:border-primary"
        >
          <option value="qwen">{{ t('aiConfig.providerQwen') }}</option>
          <option value="doubao">{{ t('aiConfig.providerDoubao') }}</option>
          <option value="claude">{{ t('aiConfig.providerClaude') }}</option>
        </select>
        <input
          v-model="form.model"
          :placeholder="t('aiConfig.modelPlaceholder')"
          class="px-3 py-2 bg-white dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-sm outline-none focus:border-primary"
        />
      </div>
      <input
        v-model="form.apiKey"
        type="password"
        :placeholder="editingId ? t('aiConfig.apiKeyUpdatePlaceholder') : t('aiConfig.apiKeyPlaceholder')"
        class="w-full px-3 py-2 bg-white dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-sm outline-none focus:border-primary"
      />
      <input
        v-model="form.endpoint"
        :placeholder="t('aiConfig.endpointPlaceholder')"
        class="w-full px-3 py-2 bg-white dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-sm outline-none focus:border-primary"
      />
      <div class="grid grid-cols-2 gap-3">
        <div>
          <label class="block text-xs text-gray-500 mb-1">{{ t('aiConfig.maxTokens') }}</label>
          <input
            v-model.number="form.maxTokens"
            type="number"
            min="1"
            class="w-full px-3 py-2 bg-white dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-sm outline-none focus:border-primary"
          />
        </div>
        <div>
          <label class="block text-xs text-gray-500 mb-1">{{ t('aiConfig.timeoutSeconds') }}</label>
          <input
            v-model.number="form.timeoutSeconds"
            type="number"
            min="1"
            class="w-full px-3 py-2 bg-white dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-sm outline-none focus:border-primary"
          />
        </div>
      </div>
      <div class="flex items-center space-x-4">
        <label class="flex items-center space-x-2 text-sm">
          <input v-model="form.isDefault" type="checkbox" />
          <span>{{ t('aiConfig.setAsDefault') }}</span>
        </label>
        <label class="flex items-center space-x-2 text-sm">
          <input v-model="form.enabled" type="checkbox" />
          <span>{{ t('aiConfig.enabled') }}</span>
        </label>
      </div>
      <div v-if="formError" class="text-xs text-red-600 dark:text-red-400 bg-red-50 dark:bg-red-900/20 p-2 rounded">
        {{ formError }}
      </div>
      <div class="flex justify-end space-x-2">
        <button
          @click="cancelEdit"
          class="px-3 py-1.5 text-xs rounded-md border border-gray-200 dark:border-dark-border bg-white dark:bg-dark-hover text-gray-700 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-dark-hover transition-colors"
        >
          {{ t('common.cancel') }}
        </button>
        <button
          @click="save"
          :disabled="saving"
          class="px-3 py-1.5 text-xs rounded-md bg-primary hover:bg-[#369b6e] text-white disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
        >
          {{ saving ? t('common.saving') : t('common.save') }}
        </button>
      </div>
    </div>

    <button
      v-else
      @click="startAdd"
      class="w-full px-3 py-2 text-sm rounded-md border border-dashed border-gray-300 dark:border-dark-border text-gray-600 dark:text-gray-400 hover:border-primary hover:text-primary transition-colors flex items-center justify-center space-x-1"
    >
      <Plus :size="14" />
      <span>{{ t('aiConfig.addConfig') }}</span>
    </button>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { Plus, Pencil, Trash2, Star } from 'lucide-vue-next'
import { useAiConfigStore } from '@/stores/aiConfigStore'
import { useI18n } from '@/composables/useI18n'
import type { AiConfig, AiConfigForm } from '@/types/aiConfig'

const store = useAiConfigStore()
const { t } = useI18n()

const isEditing = ref(false)
const editingId = ref<number | undefined>(undefined)
const saving = ref(false)
const formError = ref<string | null>(null)

const form = ref<AiConfigForm>(defaultForm())

const providerDefaults: Record<string, { model: string; endpoint: string }> = {
  qwen: { model: 'qwen-turbo', endpoint: 'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions' },
  doubao: { model: 'doubao-lite-4k', endpoint: 'https://ark.cn-beijing.volces.com/api/v3/chat/completions' },
  claude: { model: 'claude-3-haiku-20240307', endpoint: 'https://api.anthropic.com/v1/messages' },
}

function defaultForm(): AiConfigForm {
  return {
    name: '',
    provider: 'qwen',
    model: providerDefaults.qwen.model,
    apiKey: '',
    endpoint: '',
    maxTokens: 2048,
    timeoutSeconds: 30,
    isDefault: false,
    enabled: true,
  }
}

onMounted(() => {
  store.loadConfigs().catch(() => {
    // 错误已在 store 中记录
  })
})

function onProviderChange() {
  const defaults = providerDefaults[form.value.provider]
  if (defaults) {
    form.value.model = defaults.model
  }
}

function providerLabel(provider: string): string {
  return t(`aiConfig.provider${provider.charAt(0).toUpperCase() + provider.slice(1)}`)
}

function startAdd() {
  editingId.value = undefined
  form.value = defaultForm()
  formError.value = null
  isEditing.value = true
}

function edit(config: AiConfig) {
  editingId.value = config.id
  form.value = {
    name: config.name,
    provider: config.provider,
    model: config.model,
    apiKey: '',
    endpoint: config.endpoint || '',
    maxTokens: config.maxTokens,
    timeoutSeconds: config.timeoutSeconds,
    isDefault: config.isDefault,
    enabled: config.enabled,
  }
  formError.value = null
  isEditing.value = true
}

function cancelEdit() {
  isEditing.value = false
  editingId.value = undefined
  formError.value = null
}

function validate(): string | null {
  if (!form.value.name.trim()) {
    return t('aiConfig.nameRequired')
  }
  if (!form.value.model.trim()) {
    return t('aiConfig.modelRequired')
  }
  if (form.value.maxTokens <= 0) {
    return t('aiConfig.maxTokensInvalid')
  }
  if (form.value.timeoutSeconds <= 0) {
    return t('aiConfig.timeoutSecondsInvalid')
  }
  return null
}

async function save() {
  formError.value = validate()
  if (formError.value) {
    return
  }
  saving.value = true
  try {
    await store.saveConfig(editingId.value, { ...form.value, name: form.value.name.trim() })
    isEditing.value = false
    editingId.value = undefined
    form.value = defaultForm()
  } catch (e) {
    formError.value = e instanceof Error ? e.message : String(e)
  } finally {
    saving.value = false
  }
}

async function remove(id: number) {
  if (!confirm(t('aiConfig.confirmDelete'))) {
    return
  }
  try {
    await store.deleteConfig(id)
  } catch (e) {
    alert(e instanceof Error ? e.message : String(e))
  }
}

async function setDefault(id: number) {
  try {
    await store.setDefault(id)
  } catch (e) {
    alert(e instanceof Error ? e.message : String(e))
  }
}
</script>
