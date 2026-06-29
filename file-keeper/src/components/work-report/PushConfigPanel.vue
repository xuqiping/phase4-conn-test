<template>
  <div class="max-w-3xl mx-auto space-y-4">
    <div class="bg-white dark:bg-dark-panel rounded-lg border border-gray-200 dark:border-dark-border p-4">
      <div class="flex items-center justify-between mb-4">
        <h3 class="text-sm font-semibold">{{ t('workReport.pushConfig') }}</h3>
        <div class="flex space-x-2">
          <button
            v-for="tab in subTabs"
            :key="tab.key"
            @click="activeSubTab = tab.key"
            :class="['px-3 py-1 text-xs rounded-md border transition-colors', activeSubTab === tab.key ? 'bg-[var(--accent-subtle-bg)] text-[var(--accent-subtle-text)] border-[var(--accent-subtle-border)]' : 'bg-[var(--bg-primary)] text-[var(--text-primary)] border-[var(--border-color)] hover:bg-[var(--bg-hover)]']"
          >
            {{ tab.label }}
          </button>
        </div>
      </div>

      <div v-if="activeSubTab === 'credentials'">
        <div class="space-y-2 mb-4">
          <div
            v-for="credential in store.pushCredentials"
            :key="credential.id"
            class="flex items-center justify-between p-3 rounded-lg border border-gray-200 dark:border-dark-border bg-gray-50 dark:bg-dark-bg"
          >
            <div class="flex items-center space-x-3">
              <span class="text-sm font-medium">{{ credential.name }}</span>
              <span class="text-xs px-2 py-0.5 rounded bg-gray-200 dark:bg-dark-hover">{{ platformLabel(credential.platform) }}</span>
              <span v-if="credential.hasCredential" class="text-xs text-green-600 dark:text-green-400">{{ t('workReport.credentialSet') }}</span>
            </div>
            <div class="flex items-center space-x-2">
              <button @click="editCredential(credential)" class="p-1.5 rounded hover:bg-gray-200 dark:hover:bg-dark-hover text-gray-500">
                <Pencil :size="14" />
              </button>
              <button @click="store.deletePushCredential(credential.id)" class="p-1.5 rounded hover:bg-red-100 dark:hover:bg-red-900/20 text-red-500">
                <Trash2 :size="14" />
              </button>
            </div>
          </div>
          <div v-if="store.pushCredentials.length === 0" class="text-center text-gray-400 py-6 text-sm">
            {{ t('workReport.emptyPushCredential') }}
          </div>
        </div>

        <div class="border-t border-gray-200 dark:border-dark-border pt-4">
          <div class="flex items-center justify-between mb-3">
            <span class="text-sm font-medium">{{ credentialEditingId ? t('workReport.editCredential') : t('workReport.addCredential') }}</span>
            <button v-if="credentialEditingId" @click="resetCredentialForm" class="text-xs text-gray-500 hover:text-gray-700">{{ t('common.cancel') }}</button>
          </div>
          <div class="space-y-3">
            <input v-model="credentialForm.name" :placeholder="t('workReport.credentialName')" class="w-full px-2 py-1.5 bg-white dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-sm" />
            <select v-model="credentialForm.platform" class="w-full px-2 py-1.5 bg-white dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-sm">
              <option value="FEISHU">{{ t('workReport.platformFeishu') }}</option>
              <option value="DINGTALK">{{ t('workReport.platformDingtalk') }}</option>
              <option value="WECHAT_WORK">{{ t('workReport.platformWecom') }}</option>
              <option value="SLACK">{{ t('workReport.platformSlack') }}</option>
            </select>
            <textarea
              v-model="credentialForm.credential"
              :placeholder="credentialPlaceholder"
              rows="3"
              class="w-full px-2 py-1.5 bg-white dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-sm resize-none"
            />
            <div v-if="credentialError" class="text-xs text-red-600 dark:text-red-400 bg-red-50 dark:bg-red-900/20 p-2 rounded">
              {{ credentialError }}
            </div>
            <button
              @click="saveCredential"
              :disabled="savingCredential"
              class="px-3 py-1.5 text-xs rounded-md border border-[var(--accent-subtle-border)] bg-[var(--bg-primary)] text-[var(--accent-subtle-text)] hover:bg-[var(--accent-subtle-bg)] disabled:opacity-50"
            >
              {{ savingCredential ? t('common.saving') : t('common.save') }}
            </button>
          </div>
        </div>
      </div>

      <div v-else-if="activeSubTab === 'targets'">
        <div class="space-y-2 mb-4">
          <div
            v-for="target in store.pushTargets"
            :key="target.id"
            class="flex items-center justify-between p-3 rounded-lg border border-gray-200 dark:border-dark-border bg-gray-50 dark:bg-dark-bg"
          >
            <div class="flex items-center space-x-3">
              <span class="text-sm font-medium">{{ target.name }}</span>
              <span class="text-xs px-2 py-0.5 rounded bg-gray-200 dark:bg-dark-hover">{{ platformLabel(target.platform) }}</span>
              <span class="text-xs text-gray-500">{{ target.targetType }} · {{ target.targetId }}</span>
              <span v-if="target.credentialName" class="text-xs text-gray-400">({{ target.credentialName }})</span>
            </div>
            <div class="flex items-center space-x-2">
              <button @click="editTarget(target)" class="p-1.5 rounded hover:bg-gray-200 dark:hover:bg-dark-hover text-gray-500">
                <Pencil :size="14" />
              </button>
              <button @click="store.deletePushTarget(target.id)" class="p-1.5 rounded hover:bg-red-100 dark:hover:bg-red-900/20 text-red-500">
                <Trash2 :size="14" />
              </button>
            </div>
          </div>
          <div v-if="store.pushTargets.length === 0" class="text-center text-gray-400 py-6 text-sm">
            {{ t('workReport.emptyPushTarget') }}
          </div>
        </div>

        <div class="border-t border-gray-200 dark:border-dark-border pt-4">
          <div class="flex items-center justify-between mb-3">
            <span class="text-sm font-medium">{{ targetEditingId ? t('workReport.editTarget') : t('workReport.addTarget') }}</span>
            <button v-if="targetEditingId" @click="resetTargetForm" class="text-xs text-gray-500 hover:text-gray-700">{{ t('common.cancel') }}</button>
          </div>
          <div class="space-y-3">
            <input v-model="targetForm.name" :placeholder="t('workReport.targetName')" class="w-full px-2 py-1.5 bg-white dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-sm" />
            <div class="grid grid-cols-2 gap-3">
              <select v-model="targetForm.platform" class="px-2 py-1.5 bg-white dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-sm">
                <option value="FEISHU">{{ t('workReport.platformFeishu') }}</option>
                <option value="DINGTALK">{{ t('workReport.platformDingtalk') }}</option>
                <option value="WECHAT_WORK">{{ t('workReport.platformWecom') }}</option>
                <option value="SLACK">{{ t('workReport.platformSlack') }}</option>
              </select>
              <select v-model="targetForm.targetType" class="px-2 py-1.5 bg-white dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-sm">
                <option value="GROUP">{{ t('workReport.targetGroup') }}</option>
                <option value="USER">{{ t('workReport.targetUser') }}</option>
              </select>
            </div>
            <input v-model="targetForm.targetId" :placeholder="t('workReport.targetIdPlaceholder')" class="w-full px-2 py-1.5 bg-white dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-sm" />
            <select v-model="targetForm.credentialId" class="w-full px-2 py-1.5 bg-white dark:bg-dark-hover border border-gray-200 dark:border-dark-border rounded-md text-sm">
              <option :value="undefined">{{ t('workReport.selectCredential') }}</option>
              <option
                v-for="credential in filteredCredentials"
                :key="credential.id"
                :value="credential.id"
              >
                {{ credential.name }} ({{ platformLabel(credential.platform) }})
              </option>
            </select>
            <div v-if="targetError" class="text-xs text-red-600 dark:text-red-400 bg-red-50 dark:bg-red-900/20 p-2 rounded">
              {{ targetError }}
            </div>
            <button
              @click="saveTarget"
              :disabled="savingTarget"
              class="px-3 py-1.5 text-xs rounded-md border border-[var(--accent-subtle-border)] bg-[var(--bg-primary)] text-[var(--accent-subtle-text)] hover:bg-[var(--accent-subtle-bg)] disabled:opacity-50"
            >
              {{ savingTarget ? t('common.saving') : t('common.save') }}
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { Pencil, Trash2 } from 'lucide-vue-next'
import { useWorkReportStore } from '@/stores/workReportStore'
import { useI18n } from '@/composables/useI18n'
import type { PushCredential, PushTarget, PushCredentialForm, PushTargetForm } from '@/types/workReport'

const store = useWorkReportStore()
const { t } = useI18n()

const activeSubTab = ref<'credentials' | 'targets'>('credentials')

const subTabs = computed(() => [
  { key: 'credentials' as const, label: t('workReport.pushCredentials') },
  { key: 'targets' as const, label: t('workReport.pushTargets') },
])

const credentialEditingId = ref<number | undefined>(undefined)
const credentialForm = ref<PushCredentialForm>(defaultCredentialForm())
const credentialError = ref<string | null>(null)
const savingCredential = ref(false)

const targetEditingId = ref<number | undefined>(undefined)
const targetForm = ref<PushTargetForm>(defaultTargetForm())
const targetError = ref<string | null>(null)
const savingTarget = ref(false)

const credentialPlaceholder = computed(() => {
  if (credentialEditingId.value && !credentialForm.value.credential) {
    return t('workReport.credentialUpdatePlaceholder')
  }
  return t('workReport.credentialPlaceholder')
})

const filteredCredentials = computed(() => {
  return store.pushCredentials.filter(c => c.platform === targetForm.value.platform)
})

function defaultCredentialForm(): PushCredentialForm {
  return {
    name: '',
    platform: 'FEISHU',
    credential: '',
  }
}

function defaultTargetForm(): PushTargetForm {
  return {
    name: '',
    platform: 'FEISHU',
    targetType: 'GROUP',
    targetId: '',
    credentialId: undefined,
  }
}

function platformLabel(platform: string): string {
  switch (platform) {
    case 'FEISHU': return t('workReport.platformFeishu')
    case 'DINGTALK': return t('workReport.platformDingtalk')
    case 'WECHAT_WORK': return t('workReport.platformWechatWork')
    case 'SLACK': return t('workReport.platformSlack')
    default: return platform
  }
}

function editCredential(credential: PushCredential) {
  credentialEditingId.value = credential.id
  credentialForm.value = {
    name: credential.name,
    platform: credential.platform,
    credential: '',
  }
}

function resetCredentialForm() {
  credentialEditingId.value = undefined
  credentialForm.value = defaultCredentialForm()
  credentialError.value = null
}

async function saveCredential() {
  credentialError.value = validateCredential(credentialForm.value)
  if (credentialError.value) return

  savingCredential.value = true
  try {
    await store.savePushCredential(credentialEditingId.value, credentialForm.value)
    resetCredentialForm()
  } catch (e) {
    credentialError.value = e instanceof Error ? e.message : String(e)
  } finally {
    savingCredential.value = false
  }
}

function validateCredential(form: PushCredentialForm): string | null {
  if (!form.name.trim()) return t('workReport.credentialNameRequired')
  if (!form.platform) return t('workReport.credentialPlatformRequired')
  if (!credentialEditingId.value && !form.credential.trim()) return t('workReport.credentialValueRequired')
  return null
}

function editTarget(target: PushTarget) {
  targetEditingId.value = target.id
  targetForm.value = {
    name: target.name,
    platform: target.platform,
    targetType: target.targetType,
    targetId: target.targetId,
    credentialId: target.credentialId,
  }
}

function resetTargetForm() {
  targetEditingId.value = undefined
  targetForm.value = defaultTargetForm()
  targetError.value = null
}

async function saveTarget() {
  targetError.value = validateTarget(targetForm.value)
  if (targetError.value) return

  savingTarget.value = true
  try {
    await store.savePushTarget(targetEditingId.value, targetForm.value)
    resetTargetForm()
  } catch (e) {
    targetError.value = e instanceof Error ? e.message : String(e)
  } finally {
    savingTarget.value = false
  }
}

function validateTarget(form: PushTargetForm): string | null {
  if (!form.name.trim()) return t('workReport.targetNameRequired')
  if (!form.platform) return t('workReport.targetPlatformRequired')
  if (!form.targetType) return t('workReport.targetTypeRequired')
  if (!form.targetId.trim()) return t('workReport.targetIdRequired')
  if (!form.credentialId) return t('workReport.targetCredentialRequired')
  return null
}

onMounted(() => {
  store.loadPushCredentials()
  store.loadPushTargets()
})
</script>
