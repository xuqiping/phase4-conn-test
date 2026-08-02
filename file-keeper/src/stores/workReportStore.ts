import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { useAuthStore } from './authStore'
import { useCommercialAuthStore } from './commercialAuthStore'
import * as api from '@/api/workReport'
import * as inboxApi from '@/api/inbox'
import * as inspirationApi from '@/api/inspiration'
import * as rustApi from '@/api/rustWorkReport'
import type {
  WorkLog,
  FixedWorkItem,
  FuturePlan,
  ReportTemplate,
  ReportConfig,
  WorkReport,
  PageResult,
  RecurrenceType,
  PushCredential,
  PushCredentialForm,
  PushTarget,
  PushTargetForm,
} from '@/types/workReport'
import type { InboxMessage } from '@/types/inbox'
import type { InspirationNote } from '@/types/inspiration'

const COMMERCIAL_SERVER_URL = import.meta.env.VITE_FILE_KEEPER_SERVER_URL || 'http://localhost:8088'

export type MainTab = 'inbox' | 'logs' | 'future' | 'fixed' | 'calendar' | 'inspirations' | 'push-config'

export const useWorkReportStore = defineStore('work-report', () => {
  const logs = ref<WorkLog[]>([])
  const fixedWorkItems = ref<FixedWorkItem[]>([])
  const futurePlans = ref<FuturePlan[]>([])
  const inspirationNotes = ref<InspirationNote[]>([])
  const templates = ref<ReportTemplate[]>([])
  const configs = ref<ReportConfig[]>([])
  const reports = ref<WorkReport[]>([])
  const currentReport = ref<WorkReport | null>(null)
  const currentDate = ref<string>(new Date().toISOString().split('T')[0])
  const startDate = ref<string>(currentDate.value)
  const endDate = ref<string>(currentDate.value)
  const activeMainTab = ref<MainTab>('logs')
  const activeFixedSubTab = ref<RecurrenceType>('DAILY')
  const loading = ref(false)
  const error = ref<string | null>(null)

  const pushCredentials = ref<PushCredential[]>([])
  const pushTargets = ref<PushTarget[]>([])

  const inboxMessages = ref<InboxMessage[]>([])
  const inboxLoading = ref(false)
  const pendingInboxCount = computed(() => inboxMessages.value.filter(m => m.status === 'PENDING').length)

  const todayLogs = computed(() =>
    [...logs.value].sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0)),
  )
  const sortedFixedWorkItems = computed(() =>
    [...fixedWorkItems.value].sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0)),
  )
  const sortedFuturePlans = computed(() =>
    [...futurePlans.value].sort((a, b) => new Date(a.scheduledAt).getTime() - new Date(b.scheduledAt).getTime()),
  )
  const sortedInspirationNotes = computed(() =>
    [...inspirationNotes.value].sort((a, b) => new Date(b.createdAt || 0).getTime() - new Date(a.createdAt || 0).getTime()),
  )

  function getAuthContext() {
    const authStore = useAuthStore()
    const commercialStore = useCommercialAuthStore()
    const token = authStore.accessToken
    const deviceIdentity = commercialStore.deviceIdentity
    if (!token) {
      throw new Error('未登录')
    }
    if (!deviceIdentity) {
      throw new Error('未获取设备身份')
    }
    return { baseUrl: COMMERCIAL_SERVER_URL, token, deviceId: deviceIdentity.deviceId }
  }

  async function loadLogs(dateRangeStart?: string, dateRangeEnd?: string) {
    if (dateRangeStart) startDate.value = dateRangeStart
    if (dateRangeEnd) endDate.value = dateRangeEnd
    const { baseUrl, token, deviceId } = getAuthContext()
    loading.value = true
    error.value = null
    try {
      logs.value = await api.listWorkLogs(baseUrl, token, deviceId, startDate.value, endDate.value)
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
    } finally {
      loading.value = false
    }
  }

  async function loadToday(date?: string) {
    if (date) {
      currentDate.value = date
      startDate.value = date
      endDate.value = date
    }
    const { baseUrl, token, deviceId } = getAuthContext()
    loading.value = true
    error.value = null
    try {
      logs.value = await api.listWorkLogs(baseUrl, token, deviceId, startDate.value, endDate.value)
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
    } finally {
      loading.value = false
    }
  }

  async function saveLog(log: Partial<WorkLog>) {
    const { baseUrl, token, deviceId } = getAuthContext()
    try {
      const payload: Record<string, unknown> = {
        content: log.content,
      }
      if (log.tags !== undefined) payload.tags = log.tags
      if (log.source !== undefined) payload.source = log.source
      if (log.sortOrder !== undefined) payload.sortOrder = log.sortOrder
      if (log.id) {
        await api.updateWorkLog(baseUrl, token, deviceId, log.id, { id: log.id, ...payload })
      } else {
        await api.createWorkLog(baseUrl, token, deviceId, { ...payload, logDate: currentDate.value })
      }
      await loadToday()
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
      throw e
    }
  }

  async function removeLog(id: number) {
    const { baseUrl, token, deviceId } = getAuthContext()
    try {
      await api.deleteWorkLog(baseUrl, token, deviceId, id)
      await loadToday()
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
      throw e
    }
  }

  async function loadFixedWork(type?: RecurrenceType) {
    if (type) activeFixedSubTab.value = type
    const { baseUrl, token, deviceId } = getAuthContext()
    try {
      fixedWorkItems.value = await api.listFixedWork(baseUrl, token, deviceId, activeFixedSubTab.value)
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
      throw e
    }
  }

  async function saveFixedWork(item: Partial<FixedWorkItem>) {
    const { baseUrl, token, deviceId } = getAuthContext()
    try {
      const payload: Record<string, unknown> = {
        content: item.content,
        description: item.description,
        recurrenceType: item.recurrenceType ?? activeFixedSubTab.value,
        reminderTime: item.reminderTime ?? '09:00',
        reminderDays: item.reminderDays,
        timezone: item.timezone ?? 'Asia/Shanghai',
        reminderEnabled: item.reminderEnabled ?? false,
        pushTargetId: item.pushTargetId,
        sortOrder: item.sortOrder,
      }
      if (item.id) {
        await api.updateFixedWork(baseUrl, token, deviceId, item.id, payload)
      } else {
        await api.createFixedWork(baseUrl, token, deviceId, payload)
      }
      await loadFixedWork()
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
      throw e
    }
  }

  async function toggleFixedWork(id: number) {
    const { baseUrl, token, deviceId } = getAuthContext()
    try {
      await api.toggleFixedWorkComplete(baseUrl, token, deviceId, id)
      await loadFixedWork()
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
      throw e
    }
  }

  async function removeFixedWork(id: number) {
    const { baseUrl, token, deviceId } = getAuthContext()
    try {
      await api.deleteFixedWork(baseUrl, token, deviceId, id)
      await loadFixedWork()
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
      throw e
    }
  }

  async function loadFuturePlans() {
    const { baseUrl, token, deviceId } = getAuthContext()
    try {
      futurePlans.value = await api.listFuturePlans(baseUrl, token, deviceId)
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
      throw e
    }
  }

  async function saveFuturePlan(plan: Partial<FuturePlan>) {
    const { baseUrl, token, deviceId } = getAuthContext()
    try {
      const payload: Record<string, unknown> = {
        content: plan.content,
        description: plan.description,
        scheduledAt: plan.scheduledAt,
        timezone: plan.timezone ?? 'Asia/Shanghai',
        reminderEnabled: plan.reminderEnabled ?? false,
        reminderMinutesBefore: plan.reminderMinutesBefore ?? 0,
        pushTargetId: plan.pushTargetId,
        sortOrder: plan.sortOrder,
      }
      if (plan.id) {
        await api.updateFuturePlan(baseUrl, token, deviceId, plan.id, payload)
      } else {
        await api.createFuturePlan(baseUrl, token, deviceId, payload)
      }
      await loadFuturePlans()
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
      throw e
    }
  }

  async function completeFuturePlan(id: number) {
    const { baseUrl, token, deviceId } = getAuthContext()
    try {
      await api.completeFuturePlan(baseUrl, token, deviceId, id)
      await loadFuturePlans()
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
      throw e
    }
  }

  async function cancelFuturePlan(id: number) {
    const { baseUrl, token, deviceId } = getAuthContext()
    try {
      await api.cancelFuturePlan(baseUrl, token, deviceId, id)
      await loadFuturePlans()
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
      throw e
    }
  }

  async function removeFuturePlan(id: number) {
    const { baseUrl, token, deviceId } = getAuthContext()
    try {
      await api.deleteFuturePlan(baseUrl, token, deviceId, id)
      await loadFuturePlans()
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
      throw e
    }
  }

  async function loadTemplates() {
    const { baseUrl, token, deviceId } = getAuthContext()
    templates.value = await api.listReportTemplates(baseUrl, token, deviceId)
  }

  async function loadConfigs() {
    const { baseUrl, token, deviceId } = getAuthContext()
    configs.value = await api.listReportConfigs(baseUrl, token, deviceId)
  }

  async function saveConfig(config: Partial<ReportConfig>) {
    const { baseUrl, token, deviceId } = getAuthContext()
    await api.saveReportConfig(baseUrl, token, deviceId, config)
    await loadConfigs()
  }

  async function deleteConfig(id: number) {
    const { baseUrl, token, deviceId } = getAuthContext()
    await api.deleteReportConfig(baseUrl, token, deviceId, id)
    await loadConfigs()
  }

  async function generateReport(configId: number) {
    const { baseUrl, token, deviceId } = getAuthContext()
    loading.value = true
    try {
      currentReport.value = await api.generateReport(baseUrl, token, deviceId, configId)
      return currentReport.value
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
      throw e
    } finally {
      loading.value = false
    }
  }

  async function loadReports(page = 1, size = 20): Promise<PageResult<WorkReport>> {
    const { baseUrl, token, deviceId } = getAuthContext()
    const res = await api.listReports(baseUrl, token, deviceId, page, size)
    reports.value = res.records
    return res
  }

  async function pushReport(reportId: number) {
    const { baseUrl, token, deviceId } = getAuthContext()
    await api.pushReport(baseUrl, token, deviceId, reportId)
  }

  async function removeReport(id: number) {
    const { baseUrl, token, deviceId } = getAuthContext()
    await api.deleteReport(baseUrl, token, deviceId, id)
    await loadReports()
  }

  async function importGitLogs(repoPath: string, since: string, until?: string) {
    loading.value = true
    error.value = null
    try {
      const logs = await rustApi.fetchGitLogs(repoPath, since, until)
      const { baseUrl, token, deviceId } = getAuthContext()
      for (const log of logs) {
        await api.createWorkLog(baseUrl, token, deviceId, {
          content: `[${log.hash.slice(0, 7)}] ${log.message}`,
          source: 'GIT',
          tags: 'git',
          logDate: currentDate.value,
        })
      }
      await loadToday()
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
      throw e
    } finally {
      loading.value = false
    }
  }

  async function loadPushCredentials() {
    const { baseUrl, token, deviceId } = getAuthContext()
    pushCredentials.value = await api.listPushCredentials(baseUrl, token, deviceId)
  }

  async function savePushCredential(id: number | undefined, credential: PushCredentialForm): Promise<PushCredential> {
    const { baseUrl, token, deviceId } = getAuthContext()
    const saved = id
      ? await api.updatePushCredential(baseUrl, token, deviceId, id, credential)
      : await api.createPushCredential(baseUrl, token, deviceId, credential)
    await loadPushCredentials()
    return saved
  }

  async function deletePushCredential(id: number) {
    const { baseUrl, token, deviceId } = getAuthContext()
    await api.deletePushCredential(baseUrl, token, deviceId, id)
    await loadPushCredentials()
  }

  async function loadPushTargets() {
    const { baseUrl, token, deviceId } = getAuthContext()
    pushTargets.value = await api.listPushTargets(baseUrl, token, deviceId)
  }

  async function savePushTarget(id: number | undefined, target: PushTargetForm): Promise<PushTarget> {
    const { baseUrl, token, deviceId } = getAuthContext()
    const saved = id
      ? await api.updatePushTarget(baseUrl, token, deviceId, id, target)
      : await api.createPushTarget(baseUrl, token, deviceId, target)
    await loadPushTargets()
    return saved
  }

  async function deletePushTarget(id: number) {
    const { baseUrl, token, deviceId } = getAuthContext()
    await api.deletePushTarget(baseUrl, token, deviceId, id)
    await loadPushTargets()
  }

  async function loadInspirations(tags?: string[], startDate?: string, endDate?: string) {
    const { baseUrl, token, deviceId } = getAuthContext()
    loading.value = true
    error.value = null
    try {
      inspirationNotes.value = await inspirationApi.listInspirations(baseUrl, token, deviceId, tags, startDate, endDate)
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
      throw e
    } finally {
      loading.value = false
    }
  }

  async function saveInspiration(note: Partial<InspirationNote>) {
    const { baseUrl, token, deviceId } = getAuthContext()
    try {
      const payload = {
        content: note.content ?? '',
        tags: note.tags,
        source: note.source,
        platformMessageId: note.platformMessageId,
        reportConfigIds: note.reportConfigIds,
      }
      if (note.id) {
        await inspirationApi.updateInspiration(baseUrl, token, deviceId, note.id, payload)
      } else {
        await inspirationApi.createInspiration(baseUrl, token, deviceId, payload)
      }
      await loadInspirations()
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
      throw e
    }
  }

  async function reviewInspiration(id: number) {
    const { baseUrl, token, deviceId } = getAuthContext()
    try {
      await inspirationApi.reviewInspiration(baseUrl, token, deviceId, id)
      await loadInspirations()
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
      throw e
    }
  }

  async function removeInspiration(id: number) {
    const { baseUrl, token, deviceId } = getAuthContext()
    try {
      await inspirationApi.deleteInspiration(baseUrl, token, deviceId, id)
      await loadInspirations()
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
      throw e
    }
  }

  async function loadFixedWorkCalendar(startDate: string, endDate: string): Promise<Record<string, { total: number; completed: number; items: (FixedWorkItem & { completed?: boolean })[] }>> {
    const { baseUrl, token, deviceId } = getAuthContext()
    const states: Record<string, { total: number; completed: number; items: (FixedWorkItem & { completed?: boolean })[] }> = {}
    try {
      // 按天查询固定工作完成状态
      const start = new Date(startDate)
      const end = new Date(endDate)
      for (let d = new Date(start); d <= end; d.setDate(d.getDate() + 1)) {
        const dateStr = d.toISOString().split('T')[0]
        const items = await api.listFixedWork(baseUrl, token, deviceId, 'DAILY', dateStr)
        states[dateStr] = {
          total: items.length,
          completed: items.filter(i => i.completedToday).length,
          items: items.map(i => ({ ...i, completed: i.completedToday })),
        }
      }
      return states
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
      throw e
    }
  }

  async function toggleFixedWorkForDate(id: number, date: string) {
    const { baseUrl, token, deviceId } = getAuthContext()
    try {
      await api.toggleFixedWorkComplete(baseUrl, token, deviceId, id, date)
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
      throw e
    }
  }

  async function loadInbox(limit = 50) {
    const { baseUrl, token, deviceId } = getAuthContext()
    inboxLoading.value = true
    error.value = null
    try {
      inboxMessages.value = await inboxApi.listPendingInbox(baseUrl, token, deviceId, limit)
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
      throw e
    } finally {
      inboxLoading.value = false
    }
  }

  async function confirmInboxMessage(id: number, action: 'CONFIRM' | 'IGNORE', correctedPayload?: Record<string, unknown>) {
    const { baseUrl, token, deviceId } = getAuthContext()
    try {
      await inboxApi.confirmInboxMessage(baseUrl, token, deviceId, id, action, correctedPayload)
      await loadInbox()
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
      throw e
    }
  }

  return {
    logs,
    fixedWorkItems,
    futurePlans,
    inspirationNotes,
    templates,
    configs,
    reports,
    currentReport,
    currentDate,
    startDate,
    endDate,
    activeMainTab,
    activeFixedSubTab,
    loading,
    error,
    pushCredentials,
    pushTargets,
    inboxMessages,
    inboxLoading,
    pendingInboxCount,
    todayLogs,
    sortedFixedWorkItems,
    sortedFuturePlans,
    sortedInspirationNotes,
    loadToday,
    loadLogs,
    saveLog,
    removeLog,
    loadFixedWork,
    saveFixedWork,
    toggleFixedWork,
    removeFixedWork,
    loadFuturePlans,
    saveFuturePlan,
    completeFuturePlan,
    cancelFuturePlan,
    removeFuturePlan,
    loadInspirations,
    saveInspiration,
    reviewInspiration,
    removeInspiration,
    loadFixedWorkCalendar,
    toggleFixedWorkForDate,
    loadTemplates,
    loadConfigs,
    saveConfig,
    deleteConfig,
    generateReport,
    loadReports,
    pushReport,
    removeReport,
    importGitLogs,
    loadPushCredentials,
    savePushCredential,
    deletePushCredential,
    loadPushTargets,
    savePushTarget,
    deletePushTarget,
    loadInbox,
    confirmInboxMessage,
  }
})
