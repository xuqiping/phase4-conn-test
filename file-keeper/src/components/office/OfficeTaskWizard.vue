<template>
  <div class="grid min-h-0 gap-5 xl:grid-cols-[minmax(0,1.35fr)_minmax(300px,0.65fr)]">
    <section class="rounded-3xl border border-gray-200 bg-white p-5 shadow-sm dark:border-dark-border dark:bg-dark-panel">
      <div class="mb-6 flex items-center gap-3" :aria-label="t('office.steps.label')">
        <div v-for="(step, index) in steps" :key="step" class="flex min-w-0 flex-1 items-center gap-2">
          <span class="flex h-7 w-7 shrink-0 items-center justify-center rounded-full text-xs font-bold" :class="index <= activeStep ? 'bg-primary text-white' : 'bg-gray-100 text-gray-400 dark:bg-dark-bg'">{{ index + 1 }}</span>
          <span class="truncate text-xs font-semibold" :class="index <= activeStep ? 'text-gray-800 dark:text-gray-100' : 'text-gray-400'">{{ step }}</span>
          <span v-if="index < steps.length - 1" class="ml-auto h-px flex-1 bg-gray-200 dark:bg-dark-border"></span>
        </div>
      </div>

      <div v-if="!store.currentTask" class="space-y-5">
        <div class="grid gap-4 md:grid-cols-2">
          <label class="space-y-2 text-sm font-medium">
            <span>{{ t('office.form.taskType') }}</span>
            <select v-model="taskType" class="w-full rounded-xl border border-gray-200 bg-gray-50 px-3 py-2.5 outline-none focus:border-primary focus:ring-2 focus:ring-primary/15 dark:border-dark-border dark:bg-dark-bg">
              <option v-for="type in taskTypes" :key="type" :value="type">{{ t(`office.taskType.${type}`) }}</option>
            </select>
          </label>
          <label class="space-y-2 text-sm font-medium">
            <span>{{ t('office.form.outputPolicy') }}</span>
            <select v-model="outputPolicy" class="w-full rounded-xl border border-gray-200 bg-gray-50 px-3 py-2.5 outline-none focus:border-primary focus:ring-2 focus:ring-primary/15 dark:border-dark-border dark:bg-dark-bg">
              <option value="multipleIndependent">{{ t('office.outputPolicy.multipleIndependent') }}</option>
              <option value="singleAtomic">{{ t('office.outputPolicy.singleAtomic') }}</option>
            </select>
          </label>
        </div>

        <div class="rounded-2xl border border-dashed border-gray-300 bg-gray-50/70 p-4 dark:border-dark-border dark:bg-dark-bg/60">
          <div class="flex flex-wrap items-center justify-between gap-3">
            <div><h3 class="text-sm font-semibold">{{ t('office.form.inputs') }}</h3><p class="mt-1 text-xs text-gray-500">{{ t('office.form.inputsHint') }}</p></div>
            <button type="button" class="rounded-lg bg-gray-900 px-3 py-2 text-xs font-semibold text-white hover:bg-black dark:bg-white dark:text-gray-900" @click="chooseInputs">{{ t('office.actions.chooseFiles') }}</button>
          </div>
          <ul v-if="inputPaths.length" class="mt-4 max-h-40 space-y-2 overflow-auto">
            <li v-for="path in inputPaths" :key="path" class="flex items-center gap-2 rounded-lg bg-white px-3 py-2 text-xs dark:bg-dark-panel"><FileSpreadsheet :size="15" class="text-primary" /><span class="truncate" :title="path">{{ fileName(path) }}</span></li>
          </ul>
        </div>

        <div class="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-gray-200 p-4 dark:border-dark-border">
          <div class="min-w-0"><h3 class="text-sm font-semibold">{{ t('office.form.outputDirectory') }}</h3><p class="mt-1 truncate text-xs text-gray-500" :title="outputDirectory">{{ outputDirectory || t('office.form.notSelected') }}</p></div>
          <button type="button" class="rounded-lg border border-gray-200 px-3 py-2 text-xs font-semibold hover:border-primary hover:text-primary dark:border-dark-border" @click="chooseOutput">{{ t('office.actions.chooseDirectory') }}</button>
        </div>

        <button type="button" class="w-full rounded-xl bg-primary px-4 py-3 text-sm font-bold text-white shadow-lg shadow-primary/20 transition hover:-translate-y-0.5 disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:translate-y-0" :disabled="!canPreflight || store.loading" @click="runPreflight">
          {{ store.loading ? t('office.actions.scanning') : t('office.actions.preflight') }}
        </button>
      </div>

      <div v-else class="space-y-5">
        <div class="grid gap-3 sm:grid-cols-3">
          <div class="rounded-2xl bg-gray-50 p-4 dark:bg-dark-bg"><p class="text-xs text-gray-400">{{ t('office.review.engine') }}</p><p class="mt-2 text-sm font-bold">{{ t(`office.engine.${store.currentTask.engine}`) }}</p></div>
          <div class="rounded-2xl bg-gray-50 p-4 dark:bg-dark-bg"><p class="text-xs text-gray-400">{{ t('office.review.files') }}</p><p class="mt-2 text-sm font-bold">{{ store.currentTask.inputs.length }}</p></div>
          <div class="rounded-2xl bg-gray-50 p-4 dark:bg-dark-bg"><p class="text-xs text-gray-400">{{ t('office.review.quota') }}</p><p class="mt-2 text-sm font-bold" :class="store.currentTask.withinFreeQuota ? 'text-emerald-600' : 'text-amber-600'">{{ store.currentTask.withinFreeQuota ? t('office.review.freeQuota') : t('office.review.proRequired') }}</p></div>
        </div>

        <OfficeIssueList :issues="store.currentTask.issues" />

        <div class="rounded-2xl border border-sky-200 bg-sky-50 p-4 text-sm text-sky-800 dark:border-sky-900/60 dark:bg-sky-950/20 dark:text-sky-200">
          <p class="font-semibold">{{ t('office.review.readOnlyTitle') }}</p>
          <p class="mt-1 text-xs leading-5">{{ t('office.review.readOnlyDescription') }}</p>
        </div>

        <div v-if="store.queuedTask" class="rounded-2xl border border-emerald-200 bg-emerald-50 p-5 dark:border-emerald-900/50 dark:bg-emerald-950/20">
          <p class="text-sm font-bold text-emerald-700 dark:text-emerald-300">{{ t(`office.status.${store.queuedTask.status}`) }}</p>
          <p class="mt-2 text-xs leading-5 text-emerald-700/80 dark:text-emerald-300/80">{{ queuedActive ? t('office.review.queuedDescription') : t('office.review.finishedDescription') }}</p>
        </div>

        <div class="flex flex-wrap gap-3">
          <button type="button" class="rounded-xl border border-gray-200 px-4 py-2.5 text-sm font-semibold hover:border-primary hover:text-primary dark:border-dark-border" @click="store.resetDraft">{{ t('office.actions.back') }}</button>
          <button v-if="!store.queuedTask" type="button" class="min-w-40 flex-1 rounded-xl bg-primary px-4 py-2.5 text-sm font-bold text-white disabled:cursor-not-allowed disabled:opacity-40" :disabled="!store.currentTask.canConfirm || store.loading" @click="store.confirmCurrent">{{ t('office.actions.confirmQueue') }}</button>
          <button v-else-if="queuedActive" type="button" class="min-w-40 flex-1 rounded-xl bg-red-600 px-4 py-2.5 text-sm font-bold text-white hover:bg-red-700" @click="store.cancelCurrent">{{ t('office.actions.cancel') }}</button>
          <button v-else type="button" class="min-w-40 flex-1 rounded-xl bg-gray-900 px-4 py-2.5 text-sm font-bold text-white dark:bg-white dark:text-gray-900" @click="store.resetDraft">{{ t('office.actions.newTask') }}</button>
        </div>
      </div>

      <p v-if="store.errorCode" class="mt-4 rounded-xl border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-700 dark:border-red-900/50 dark:bg-red-950/20 dark:text-red-300" role="alert">{{ store.errorCode }}</p>
    </section>

    <aside class="space-y-4">
      <div class="rounded-3xl border border-gray-200 bg-[linear-gradient(145deg,#0f172a,#162f3b)] p-5 text-white shadow-xl dark:border-dark-border">
        <ShieldCheck :size="26" class="text-emerald-300" />
        <h2 class="mt-4 text-lg font-semibold">{{ t('office.safety.title') }}</h2>
        <ul class="mt-4 space-y-3 text-xs leading-5 text-slate-300">
          <li v-for="item in safetyItems" :key="item" class="flex gap-2"><CheckCircle2 :size="15" class="mt-0.5 shrink-0 text-emerald-300" />{{ item }}</li>
        </ul>
      </div>
      <div v-if="store.recoverableTasks.length" class="rounded-3xl border border-amber-200 bg-amber-50 p-5 dark:border-amber-900/50 dark:bg-amber-950/20">
        <h3 class="text-sm font-bold text-amber-800 dark:text-amber-300">{{ t('office.recovery.title') }}</h3>
        <p class="mt-2 text-xs leading-5 text-amber-700 dark:text-amber-400">{{ t('office.recovery.count', { count: store.recoverableTasks.length }) }}</p>
        <ul class="mt-3 space-y-2">
          <li v-for="task in store.recoverableTasks" :key="task.taskId" class="flex items-center justify-between gap-3 rounded-xl bg-white/70 px-3 py-2 dark:bg-dark-panel/70">
            <div class="min-w-0"><p class="truncate text-xs font-semibold">{{ t(`office.taskType.${task.taskType}`) }}</p><p class="mt-0.5 text-[11px] text-amber-700/70 dark:text-amber-400/70">{{ t(`office.status.${task.status}`) }}</p></div>
            <button type="button" class="shrink-0 rounded-lg border border-amber-300 px-2 py-1 text-[11px] font-semibold text-amber-800 hover:bg-amber-100 dark:border-amber-800 dark:text-amber-300" @click="store.cancelTask(task.taskId)">{{ t('office.actions.cancel') }}</button>
          </li>
        </ul>
      </div>
    </aside>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { open } from '@tauri-apps/plugin-dialog'
import { CheckCircle2, FileSpreadsheet, ShieldCheck } from 'lucide-vue-next'
import { useI18n } from '../../composables/useI18n'
import { useOfficeTaskStore } from '../../stores/officeTaskStore'
import type { OfficeOutputPolicy, OfficeTaskType } from '../../types/office'
import OfficeIssueList from './OfficeIssueList.vue'

const store = useOfficeTaskStore()
const { t } = useI18n()
const taskType = ref<OfficeTaskType>('excelSplit')
const outputPolicy = ref<OfficeOutputPolicy>('multipleIndependent')
const inputPaths = ref<string[]>([])
const outputDirectory = ref('')
const taskTypes: OfficeTaskType[] = ['excelSplit', 'excelMerge', 'wordBatchReplace', 'powerPointMerge', 'powerPointRelink']
const steps = computed(() => [t('office.steps.select'), t('office.steps.review'), t('office.steps.queue')])
const activeStep = computed(() => store.queuedTask ? 2 : store.currentTask ? 1 : 0)
const queuedActive = computed(() => store.queuedTask?.status === 'queued' || store.queuedTask?.status === 'running')
const canPreflight = computed(() => inputPaths.value.length > 0 && outputDirectory.value.length > 0)
const safetyItems = computed(() => [t('office.safety.readOnly'), t('office.safety.local'), t('office.safety.atomic'), t('office.safety.password')])

onMounted(() => void store.loadRecoverable())

async function chooseInputs() {
  const selected = await open({ multiple: true, directory: false, filters: [{ name: 'Office', extensions: ['xlsx', 'xls', 'xlsm', 'csv', 'docx', 'doc', 'docm', 'pptx', 'ppt', 'pptm'] }] })
  if (Array.isArray(selected)) inputPaths.value = selected
  else if (selected) inputPaths.value = [selected]
}

async function chooseOutput() {
  const selected = await open({ directory: true, multiple: false })
  if (typeof selected === 'string') outputDirectory.value = selected
}

async function runPreflight() {
  await store.preflight({ taskType: taskType.value, outputPolicy: outputPolicy.value, inputPaths: inputPaths.value, outputDirectory: outputDirectory.value })
}

function fileName(path: string) {
  return path.split(/[\\/]/).pop() || path
}
</script>
