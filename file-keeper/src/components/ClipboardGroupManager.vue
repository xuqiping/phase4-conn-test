<template>
  <div class="fixed inset-0 z-[95] flex items-center justify-center bg-black/40 p-4" @click.self="$emit('close')">
    <section class="w-full max-w-lg rounded-xl border border-[var(--border-color)] bg-[var(--bg-primary)] p-4 shadow-2xl" role="dialog" aria-modal="true" :aria-labelledby="titleId">
      <div class="flex items-center justify-between">
        <h2 :id="titleId" class="text-lg font-semibold text-[var(--text-primary)]">{{ t('clipboard.groups.manage') }}</h2>
        <button class="rounded px-2 py-1 text-[var(--text-secondary)] hover:bg-[var(--bg-hover)]" :aria-label="t('clipboard.actions.close')" @click="$emit('close')">×</button>
      </div>

      <form data-test="group-create" class="mt-4 flex gap-2" @submit.prevent="createGroup">
        <input
          ref="createInput"
          v-model="newName"
          data-test="group-name-input"
          maxlength="40"
          class="min-w-0 flex-1 rounded-md border border-[var(--border-color)] bg-[var(--bg-secondary)] px-3 py-2 text-sm outline-none focus:border-primary"
          :placeholder="t('clipboard.groups.namePlaceholder')"
        />
        <button class="rounded-md bg-primary px-3 py-2 text-sm text-white disabled:opacity-50" :disabled="busy || !newName.trim()" type="submit">{{ t('clipboard.groups.create') }}</button>
      </form>

      <p v-if="error" class="mt-3 rounded-md bg-red-50 px-3 py-2 text-sm text-red-700 dark:bg-red-900/20 dark:text-red-300" role="alert">{{ error }}</p>

      <div class="mt-4 max-h-80 space-y-2 overflow-auto">
        <p v-if="groups.length === 0" class="py-6 text-center text-sm text-[var(--text-secondary)]">{{ t('clipboard.groups.empty') }}</p>
        <div v-for="group in groups" :key="group.id" class="rounded-lg border border-[var(--border-color)] p-3">
          <div class="flex items-center gap-2">
            <input
              v-model="drafts[group.id]"
              maxlength="40"
              class="min-w-0 flex-1 rounded-md border border-[var(--border-color)] bg-[var(--bg-secondary)] px-2 py-1.5 text-sm outline-none focus:border-primary"
              :aria-label="t('clipboard.groups.renameLabel', { name: group.name })"
            />
            <button :data-test="`group-rename-${group.id}`" class="rounded-md border border-[var(--border-color)] px-2 py-1.5 text-sm hover:bg-[var(--bg-hover)]" :disabled="busy || !drafts[group.id]?.trim()" @click="$emit('rename', group.id, drafts[group.id])">{{ t('clipboard.groups.rename') }}</button>
            <button :data-test="`group-delete-${group.id}`" class="rounded-md border border-red-200 px-2 py-1.5 text-sm text-red-600 hover:bg-red-50 dark:border-red-900" :disabled="busy" @click="pendingDeleteId = group.id">{{ t('clipboard.groups.delete') }}</button>
          </div>
          <div v-if="pendingDeleteId === group.id" class="mt-2 flex items-center justify-between gap-2 rounded-md bg-amber-50 px-2 py-2 text-xs text-amber-800 dark:bg-amber-900/20 dark:text-amber-200">
            <span>{{ t('clipboard.groups.deleteConfirm') }}</span>
            <div class="flex gap-2">
              <button class="rounded px-2 py-1 hover:bg-black/5" @click="pendingDeleteId = null">{{ t('clipboard.groups.cancel') }}</button>
              <button :data-test="`group-confirm-delete-${group.id}`" class="rounded bg-red-600 px-2 py-1 text-white" @click="confirmDelete(group.id)">{{ t('clipboard.groups.confirmDelete') }}</button>
            </div>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from '../composables/useI18n'
import type { ClipboardGroup } from '../types/clipboard'

const props = withDefaults(defineProps<{
  groups: ClipboardGroup[]
  busy?: boolean
  error?: string | null
}>(), {
  busy: false,
  error: null
})

const emit = defineEmits<{
  close: []
  create: [name: string]
  rename: [id: string, name: string]
  delete: [id: string]
}>()

const { t } = useI18n()
const titleId = 'clipboard-group-manager-title'
const newName = ref('')
const pendingDeleteId = ref<string | null>(null)
const createInput = ref<HTMLInputElement | null>(null)
const drafts = reactive<Record<string, string>>({})

watch(() => props.groups, syncDrafts, { immediate: true, deep: true })
onMounted(() => nextTick(() => createInput.value?.focus()))

function syncDrafts(groups: ClipboardGroup[]) {
  for (const group of groups) drafts[group.id] = group.name
  for (const id of Object.keys(drafts)) {
    if (!groups.some(group => group.id === id)) delete drafts[id]
  }
}

function createGroup() {
  const name = newName.value.trim()
  if (!name) return
  emit('create', name)
  newName.value = ''
}

function confirmDelete(id: string) {
  pendingDeleteId.value = null
  emit('delete', id)
}
</script>
