import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import {
  cancelOfficeTask,
  confirmOfficeTask,
  createOfficePreflight,
  listOfficeTasks,
  recoverOfficeTasks
} from '../api/office'
import type {
  OfficeCreateTaskRequest,
  OfficePreflightResponse,
  OfficeTaskSummary
} from '../types/office'

export const useOfficeTaskStore = defineStore('office-tasks', () => {
  const currentTask = ref<OfficePreflightResponse | null>(null)
  const queuedTask = ref<OfficeTaskSummary | null>(null)
  const history = ref<OfficeTaskSummary[]>([])
  const recoverableTasks = ref<OfficeTaskSummary[]>([])
  const historyTotal = ref(0)
  const loading = ref(false)
  const errorCode = ref<string | null>(null)

  const blockingIssueCount = computed(
    () => currentTask.value?.issues.filter(issue => issue.severity === 'blocking').length ?? 0
  )

  async function run<T>(action: () => Promise<T>): Promise<T> {
    loading.value = true
    errorCode.value = null
    try {
      return await action()
    } catch (error) {
      errorCode.value = typeof error === 'string' ? error : error instanceof Error ? error.message : String(error)
      throw error
    } finally {
      loading.value = false
    }
  }

  async function preflight(request: OfficeCreateTaskRequest) {
    currentTask.value = await run(() => createOfficePreflight(request))
    queuedTask.value = null
  }

  async function confirmCurrent() {
    if (!currentTask.value) return
    queuedTask.value = await run(() => confirmOfficeTask(currentTask.value!.taskId))
    currentTask.value.status = queuedTask.value.status
    await loadHistory()
  }

  async function cancelCurrent() {
    const taskId = queuedTask.value?.taskId ?? currentTask.value?.taskId
    if (!taskId) return
    const cancelled = await run(() => cancelOfficeTask(taskId))
    queuedTask.value = cancelled
    if (currentTask.value?.taskId === taskId) currentTask.value.status = cancelled.status
    await loadHistory()
  }

  async function cancelTask(taskId: string) {
    await run(() => cancelOfficeTask(taskId))
    await Promise.all([loadHistory(), loadRecoverable()])
  }

  async function loadHistory() {
    const page = await run(() => listOfficeTasks())
    history.value = page.items
    historyTotal.value = page.total
  }

  async function loadRecoverable() {
    recoverableTasks.value = await run(recoverOfficeTasks)
  }

  function resetDraft() {
    currentTask.value = null
    queuedTask.value = null
    errorCode.value = null
  }

  return {
    currentTask,
    queuedTask,
    history,
    recoverableTasks,
    historyTotal,
    loading,
    errorCode,
    blockingIssueCount,
    preflight,
    confirmCurrent,
    cancelCurrent,
    cancelTask,
    loadHistory,
    loadRecoverable,
    resetDraft
  }
})
