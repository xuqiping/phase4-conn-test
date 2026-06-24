import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useWorkReportStore } from '../workReportStore'

const mocks = vi.hoisted(() => ({
  listWorkLogs: vi.fn(),
  createWorkLog: vi.fn(),
  updateWorkLog: vi.fn(),
  deleteWorkLog: vi.fn(),
  listFixedWork: vi.fn(),
  createFixedWork: vi.fn(),
  updateFixedWork: vi.fn(),
  toggleFixedWorkComplete: vi.fn(),
  deleteFixedWork: vi.fn(),
  listFuturePlans: vi.fn(),
  createFuturePlan: vi.fn(),
  updateFuturePlan: vi.fn(),
  completeFuturePlan: vi.fn(),
  cancelFuturePlan: vi.fn(),
  deleteFuturePlan: vi.fn(),
  listReportTemplates: vi.fn(),
  listReportConfigs: vi.fn(),
  saveReportConfig: vi.fn(),
  deleteReportConfig: vi.fn(),
  generateReport: vi.fn(),
  listReports: vi.fn(),
  pushReport: vi.fn(),
  deleteReport: vi.fn(),
  fetchGitLogs: vi.fn(),
}))

vi.mock('@/api/workReport', () => ({
  listWorkLogs: mocks.listWorkLogs,
  createWorkLog: mocks.createWorkLog,
  updateWorkLog: mocks.updateWorkLog,
  deleteWorkLog: mocks.deleteWorkLog,
  listFixedWork: mocks.listFixedWork,
  createFixedWork: mocks.createFixedWork,
  updateFixedWork: mocks.updateFixedWork,
  toggleFixedWorkComplete: mocks.toggleFixedWorkComplete,
  deleteFixedWork: mocks.deleteFixedWork,
  listFuturePlans: mocks.listFuturePlans,
  createFuturePlan: mocks.createFuturePlan,
  updateFuturePlan: mocks.updateFuturePlan,
  completeFuturePlan: mocks.completeFuturePlan,
  cancelFuturePlan: mocks.cancelFuturePlan,
  deleteFuturePlan: mocks.deleteFuturePlan,
  listReportTemplates: mocks.listReportTemplates,
  listReportConfigs: mocks.listReportConfigs,
  saveReportConfig: mocks.saveReportConfig,
  deleteReportConfig: mocks.deleteReportConfig,
  generateReport: mocks.generateReport,
  listReports: mocks.listReports,
  pushReport: mocks.pushReport,
  deleteReport: mocks.deleteReport,
}))

vi.mock('@/api/rustWorkReport', () => ({
  fetchGitLogs: mocks.fetchGitLogs,
  showNotification: vi.fn(),
  exportReportMarkdown: vi.fn(),
}))

vi.mock('@tauri-apps/api/core', () => ({
  invoke: vi.fn(),
}))

vi.mock('../authStore', () => ({
  useAuthStore: () => ({
    accessToken: 'access-token',
  }),
}))

vi.mock('../commercialAuthStore', () => ({
  useCommercialAuthStore: () => ({
    deviceIdentity: { deviceId: 'device-1' },
  }),
}))

describe('workReportStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    Object.values(mocks).forEach((mock) => mock.mockReset())
  })

  it('loads today logs', async () => {
    mocks.listWorkLogs.mockResolvedValueOnce([{ id: 1, content: 'log' }])
    const store = useWorkReportStore()

    await store.loadToday('2026-06-22')

    expect(store.currentDate).toBe('2026-06-22')
    expect(store.logs).toEqual([{ id: 1, content: 'log' }])
    expect(mocks.listWorkLogs).toHaveBeenCalledWith(
      'http://localhost:8088',
      'access-token',
      'device-1',
      '2026-06-22',
      '2026-06-22',
    )
  })

  it('creates a new work log and reloads today', async () => {
    mocks.createWorkLog.mockResolvedValueOnce({ id: 1 })
    mocks.listWorkLogs.mockResolvedValueOnce([])
    const store = useWorkReportStore()
    store.currentDate = '2026-06-22'

    await store.saveLog({ content: 'new log' })

    expect(mocks.createWorkLog).toHaveBeenCalledWith(
      'http://localhost:8088',
      'access-token',
      'device-1',
      expect.objectContaining({ content: 'new log', logDate: '2026-06-22' }),
    )
    expect(mocks.listWorkLogs).toHaveBeenCalled()
  })

  it('updates an existing work log', async () => {
    mocks.updateWorkLog.mockResolvedValueOnce({ id: 1 })
    mocks.listWorkLogs.mockResolvedValueOnce([])
    const store = useWorkReportStore()

    await store.saveLog({ id: 1, content: 'updated' })

    expect(mocks.updateWorkLog).toHaveBeenCalledWith(
      'http://localhost:8088',
      'access-token',
      'device-1',
      1,
      { id: 1, content: 'updated' },
    )
  })

  it('removes a work log', async () => {
    mocks.deleteWorkLog.mockResolvedValueOnce(undefined)
    mocks.listWorkLogs.mockResolvedValueOnce([])
    const store = useWorkReportStore()

    await store.removeLog(1)

    expect(mocks.deleteWorkLog).toHaveBeenCalledWith(
      'http://localhost:8088',
      'access-token',
      'device-1',
      1,
    )
  })

  it('loads fixed work', async () => {
    mocks.listFixedWork.mockResolvedValueOnce([{ id: 1, content: 'fixed' }])
    const store = useWorkReportStore()

    await store.loadFixedWork('DAILY')

    expect(store.activeFixedSubTab).toBe('DAILY')
    expect(store.fixedWorkItems).toEqual([{ id: 1, content: 'fixed' }])
    expect(mocks.listFixedWork).toHaveBeenCalledWith(
      'http://localhost:8088',
      'access-token',
      'device-1',
      'DAILY',
    )
  })

  it('toggles fixed work completion', async () => {
    mocks.toggleFixedWorkComplete.mockResolvedValueOnce({ id: 1, completedToday: true })
    mocks.listFixedWork.mockResolvedValueOnce([])
    const store = useWorkReportStore()
    store.activeFixedSubTab = 'DAILY'

    await store.toggleFixedWork(1)

    expect(mocks.toggleFixedWorkComplete).toHaveBeenCalledWith(
      'http://localhost:8088',
      'access-token',
      'device-1',
      1,
    )
  })

  it('loads future plans', async () => {
    mocks.listFuturePlans.mockResolvedValueOnce([{ id: 1, content: 'future' }])
    const store = useWorkReportStore()

    await store.loadFuturePlans()

    expect(store.futurePlans).toEqual([{ id: 1, content: 'future' }])
    expect(mocks.listFuturePlans).toHaveBeenCalledWith(
      'http://localhost:8088',
      'access-token',
      'device-1',
    )
  })

  it('completes a future plan', async () => {
    mocks.completeFuturePlan.mockResolvedValueOnce({ id: 1, status: 'COMPLETED' })
    mocks.listFuturePlans.mockResolvedValueOnce([])
    const store = useWorkReportStore()

    await store.completeFuturePlan(1)

    expect(mocks.completeFuturePlan).toHaveBeenCalledWith(
      'http://localhost:8088',
      'access-token',
      'device-1',
      1,
    )
  })

  it('generates a report', async () => {
    const report = { id: 1, title: '日报' }
    mocks.generateReport.mockResolvedValueOnce(report)
    const store = useWorkReportStore()

    const result = await store.generateReport(1)

    expect(result).toEqual(report)
    expect(store.currentReport).toEqual(report)
    expect(mocks.generateReport).toHaveBeenCalledWith(
      'http://localhost:8088',
      'access-token',
      'device-1',
      1,
    )
  })

  it('pushes a report', async () => {
    mocks.pushReport.mockResolvedValueOnce(undefined)
    const store = useWorkReportStore()

    await store.pushReport(1)

    expect(mocks.pushReport).toHaveBeenCalledWith(
      'http://localhost:8088',
      'access-token',
      'device-1',
      1,
    )
  })

  it('imports git logs as work logs', async () => {
    mocks.fetchGitLogs.mockResolvedValueOnce([
      { hash: 'abc1234567890', date: '2024-01-01', message: 'Fix bug', author: 'Alice' },
    ])
    mocks.createWorkLog.mockResolvedValueOnce({ id: 1 })
    mocks.listWorkLogs.mockResolvedValueOnce([])
    const store = useWorkReportStore()
    store.currentDate = '2026-06-22'

    await store.importGitLogs('/path/to/repo', '2024-01-01', '2024-01-02')

    expect(mocks.fetchGitLogs).toHaveBeenCalledWith('/path/to/repo', '2024-01-01', '2024-01-02')
    expect(mocks.createWorkLog).toHaveBeenCalledWith(
      'http://localhost:8088',
      'access-token',
      'device-1',
      expect.objectContaining({
        content: '[abc1234] Fix bug',
        source: 'GIT',
        tags: 'git',
        logDate: '2026-06-22',
      }),
    )
  })

  it('sets error when importGitLogs fails', async () => {
    mocks.fetchGitLogs.mockRejectedValueOnce(new Error('git failed'))
    const store = useWorkReportStore()

    await expect(store.importGitLogs('/path/to/repo', '2024-01-01')).rejects.toThrow('git failed')
    expect(store.error).toBe('git failed')
    expect(store.loading).toBe(false)
  })
})
