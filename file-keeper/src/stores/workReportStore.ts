import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { useAuthStore } from './authStore'
import { useCommercialAuthStore } from './commercialAuthStore'
import * as api from '@/api/workReport'
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
} from '@/types/workReport'

const COMMERCIAL_SERVER_URL = import.meta.env.VITE_FILE_KEEPER_SERVER_URL || 'http://localhost:8088'

export type MainTab = 'logs' | 'future' | 'fixed'

export const useWorkReportStore = defineStore('work-report', () => {
  const logs = ref<WorkLog[]>([])
  const fixedWorkItems = ref<FixedWorkItem[]>([])
  const futurePlans = ref<FuturePlan[]>([])
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

  const todayLogs = computed(() =>
    [...logs.value].sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0)),
  )
  const sortedFixedWorkItems = computed(() =>
    [...fixedWorkItems.value].sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0)),
  )
  const sortedFuturePlans = computed(() =>
    [...futurePlans.value].sort((a, b) => new Date(a.scheduledAt).getTime() - new Date(b.scheduledAt).getTime()),
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
        pushPlatform: item.pushPlatform,
        pushTargetId: item.pushTargetId,
        pushCredential: item.pushCredential,
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
        pushPlatform: plan.pushPlatform,
        pushTargetId: plan.pushTargetId,
        pushCredential: plan.pushCredential,
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

  return {
    logs,
    fixedWorkItems,
    futurePlans,
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
    todayLogs,
    sortedFixedWorkItems,
    sortedFuturePlans,
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
    loadTemplates,
    loadConfigs,
    saveConfig,
    deleteConfig,
    generateReport,
    loadReports,
    pushReport,
    removeReport,
    importGitLogs,
  }
})
