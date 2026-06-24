import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  createFixedWork,
  createFuturePlan,
  createWorkLog,
  deleteWorkLog,
  generateReport,
  isWorkReportApiError,
  listFixedWork,
  listFuturePlans,
  listReportConfigs,
  listReportTemplates,
  listReports,
  listWorkLogs,
  pushReport,
  saveReportConfig,
  toggleFixedWorkComplete,
  updateWorkLog,
  WorkReportApiError,
} from '../workReport'

const mocks = vi.hoisted(() => ({
  fetch: vi.fn(),
}))

describe('work report API', () => {
  beforeEach(() => {
    mocks.fetch.mockReset()
    vi.stubGlobal('fetch', mocks.fetch)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('lists work logs with token and device id', async () => {
    mocks.fetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ code: 200, msg: 'success', data: [{ id: 1, content: 'log' }] }),
    })

    const result = await listWorkLogs('http://localhost:8088', 'token', 'device-1', '2026-06-22')

    expect(mocks.fetch).toHaveBeenCalledWith(
      'http://localhost:8088/api/client/work-report/logs?startDate=2026-06-22&endDate=2026-06-22&deviceId=device-1',
      {
        headers: {
          'Content-Type': 'application/json',
          Authorization: 'Bearer token',
        },
      },
    )
    expect(result).toEqual([{ id: 1, content: 'log' }])
  })

  it('creates work log', async () => {
    mocks.fetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ code: 200, msg: 'success', data: { id: 1, content: 'created' } }),
    })

    await createWorkLog('http://localhost:8088', 'token', 'device-1', { content: 'created' })

    expect(mocks.fetch).toHaveBeenCalledWith(
      'http://localhost:8088/api/client/work-report/logs?deviceId=device-1',
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: 'Bearer token',
        },
        body: JSON.stringify({ content: 'created' }),
      },
    )
  })

  it('updates work log', async () => {
    mocks.fetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ code: 200, msg: 'success', data: { id: 1, content: 'updated' } }),
    })

    await updateWorkLog('http://localhost:8088', 'token', 'device-1', 1, { content: 'updated' })

    expect(mocks.fetch).toHaveBeenCalledWith(
      'http://localhost:8088/api/client/work-report/logs/1?deviceId=device-1',
      {
        method: 'PUT',
        headers: {
          'Content-Type': 'application/json',
          Authorization: 'Bearer token',
        },
        body: JSON.stringify({ content: 'updated' }),
      },
    )
  })

  it('deletes work log', async () => {
    mocks.fetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ code: 200, msg: 'success', data: null }),
    })

    await deleteWorkLog('http://localhost:8088', 'token', 'device-1', 1)

    expect(mocks.fetch).toHaveBeenCalledWith(
      'http://localhost:8088/api/client/work-report/logs/1?deviceId=device-1',
      {
        method: 'DELETE',
        headers: {
          'Content-Type': 'application/json',
          Authorization: 'Bearer token',
        },
      },
    )
  })

  it('lists fixed work by type', async () => {
    mocks.fetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ code: 200, msg: 'success', data: [{ id: 1, content: 'fixed' }] }),
    })

    await listFixedWork('http://localhost:8088', 'token', 'device-1', 'DAILY')

    expect(mocks.fetch).toHaveBeenCalledWith(
      'http://localhost:8088/api/client/work-report/fixed-work?type=DAILY&deviceId=device-1',
      expect.any(Object),
    )
  })

  it('creates fixed work', async () => {
    mocks.fetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ code: 200, msg: 'success', data: { id: 1 } }),
    })

    await createFixedWork('http://localhost:8088', 'token', 'device-1', { content: 'fixed' })

    expect(mocks.fetch).toHaveBeenCalledWith(
      'http://localhost:8088/api/client/work-report/fixed-work?deviceId=device-1',
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: 'Bearer token',
        },
        body: JSON.stringify({ content: 'fixed' }),
      },
    )
  })

  it('toggles fixed work complete', async () => {
    mocks.fetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ code: 200, msg: 'success', data: { id: 1, completedToday: true } }),
    })

    await toggleFixedWorkComplete('http://localhost:8088', 'token', 'device-1', 1)

    expect(mocks.fetch).toHaveBeenCalledWith(
      'http://localhost:8088/api/client/work-report/fixed-work/1/toggle-complete?deviceId=device-1',
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: 'Bearer token',
        },
      },
    )
  })

  it('lists future plans', async () => {
    mocks.fetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ code: 200, msg: 'success', data: [{ id: 1, content: 'future' }] }),
    })

    await listFuturePlans('http://localhost:8088', 'token', 'device-1')

    expect(mocks.fetch).toHaveBeenCalledWith(
      'http://localhost:8088/api/client/work-report/future-plans?deviceId=device-1',
      expect.any(Object),
    )
  })

  it('creates future plan', async () => {
    mocks.fetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ code: 200, msg: 'success', data: { id: 1 } }),
    })

    await createFuturePlan('http://localhost:8088', 'token', 'device-1', { content: 'future' })

    expect(mocks.fetch).toHaveBeenCalledWith(
      'http://localhost:8088/api/client/work-report/future-plans?deviceId=device-1',
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: 'Bearer token',
        },
        body: JSON.stringify({ content: 'future' }),
      },
    )
  })

  it('lists report templates', async () => {
    mocks.fetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ code: 200, msg: 'success', data: [{ id: 1, name: '日报' }] }),
    })

    await listReportTemplates('http://localhost:8088', 'token', 'device-1')

    expect(mocks.fetch).toHaveBeenCalledWith(
      'http://localhost:8088/api/client/work-report/templates?deviceId=device-1',
      expect.any(Object),
    )
  })

  it('lists report configs', async () => {
    mocks.fetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ code: 200, msg: 'success', data: [{ id: 1, name: '配置' }] }),
    })

    await listReportConfigs('http://localhost:8088', 'token', 'device-1')

    expect(mocks.fetch).toHaveBeenCalledWith(
      'http://localhost:8088/api/client/work-report/configs?deviceId=device-1',
      expect.any(Object),
    )
  })

  it('saves report config', async () => {
    mocks.fetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ code: 200, msg: 'success', data: { id: 1, name: '配置' } }),
    })

    await saveReportConfig('http://localhost:8088', 'token', 'device-1', { name: '配置' })

    expect(mocks.fetch).toHaveBeenCalledWith(
      'http://localhost:8088/api/client/work-report/configs?deviceId=device-1',
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: 'Bearer token',
        },
        body: JSON.stringify({ name: '配置' }),
      },
    )
  })

  it('generates report', async () => {
    mocks.fetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ code: 200, msg: 'success', data: { id: 1, title: '日报' } }),
    })

    await generateReport('http://localhost:8088', 'token', 'device-1', 1)

    expect(mocks.fetch).toHaveBeenCalledWith(
      'http://localhost:8088/api/client/work-report/reports/generate?deviceId=device-1',
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: 'Bearer token',
        },
        body: JSON.stringify({ configId: 1 }),
      },
    )
  })

  it('lists reports with pagination', async () => {
    mocks.fetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: () => Promise.resolve({
        code: 200,
        msg: 'success',
        data: { records: [{ id: 1 }], total: 1, page: 1, size: 20 },
      }),
    })

    const result = await listReports('http://localhost:8088', 'token', 'device-1', 1, 20)

    expect(result.records).toHaveLength(1)
    expect(result.total).toBe(1)
  })

  it('pushes report', async () => {
    mocks.fetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ code: 200, msg: 'success', data: null }),
    })

    await pushReport('http://localhost:8088', 'token', 'device-1', 1)

    expect(mocks.fetch).toHaveBeenCalledWith(
      'http://localhost:8088/api/client/work-report/reports/1/push?deviceId=device-1',
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: 'Bearer token',
        },
      },
    )
  })

  it('throws WorkReportApiError on failure', async () => {
    mocks.fetch.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ code: 403, msg: 'forbidden', data: null }),
    })

    await expect(listWorkLogs('http://localhost:8088', 'token', 'device-1', '2026-06-22'))
      .rejects.toBeInstanceOf(WorkReportApiError)
  })

  it('isWorkReportApiError identifies error', () => {
    expect(isWorkReportApiError(new WorkReportApiError('msg', 200, 403))).toBe(true)
    expect(isWorkReportApiError(new Error('other'))).toBe(false)
  })
})
