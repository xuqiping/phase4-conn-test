import type {
  ReportConfig,
  ReportTemplate,
  WorkLog,
  FixedWorkItem,
  FuturePlan,
  WorkReport,
  PageResult,
} from '@/types/workReport'

const BASE_PATH = '/api/client/work-report'

interface ApiResponse<T> {
  code: number
  msg: string
  data: T
}

export class WorkReportApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly code: number,
  ) {
    super(message)
    this.name = 'WorkReportApiError'
    Object.setPrototypeOf(this, WorkReportApiError.prototype)
  }
}

export function isWorkReportApiError(error: unknown): error is WorkReportApiError {
  return error instanceof WorkReportApiError || (
    error instanceof Error &&
    error.name === 'WorkReportApiError' &&
    typeof (error as { status?: unknown }).status === 'number' &&
    typeof (error as { code?: unknown }).code === 'number'
  )
}

function normalizeBaseUrl(baseUrl: string): string {
  return baseUrl.replace(/\/+$/, '')
}

function buildUrl(baseUrl: string, path: string, deviceId: string): string {
  const separator = path.includes('?') ? '&' : '?'
  return `${normalizeBaseUrl(baseUrl)}${BASE_PATH}${path}${separator}deviceId=${encodeURIComponent(deviceId)}`
}

async function readApiResponse<T>(response: Response): Promise<T> {
  const payload = (await response.json()) as ApiResponse<T>
  if (!response.ok || payload.code !== 200) {
    throw new WorkReportApiError(
      payload.msg || `请求失败：${response.status}`,
      response.status,
      payload.code,
    )
  }
  return payload.data
}

async function request<T>(
  baseUrl: string,
  token: string,
  deviceId: string,
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const response = await fetch(buildUrl(baseUrl, path, deviceId), {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
      ...(options.headers || {}),
    },
  })
  return readApiResponse<T>(response)
}

export async function listWorkLogs(
  baseUrl: string,
  token: string,
  deviceId: string,
  startDate: string,
  endDate?: string,
): Promise<WorkLog[]> {
  const effectiveEnd = endDate || startDate
  return request<WorkLog[]>(
    baseUrl,
    token,
    deviceId,
    `/logs?startDate=${startDate}&endDate=${effectiveEnd}`,
  )
}

export async function createWorkLog(
  baseUrl: string,
  token: string,
  deviceId: string,
  log: Partial<WorkLog>,
): Promise<WorkLog> {
  return request<WorkLog>(baseUrl, token, deviceId, '/logs', {
    method: 'POST',
    body: JSON.stringify(log),
  })
}

export async function updateWorkLog(
  baseUrl: string,
  token: string,
  deviceId: string,
  id: number,
  log: Partial<WorkLog>,
): Promise<WorkLog> {
  return request<WorkLog>(baseUrl, token, deviceId, `/logs/${id}`, {
    method: 'PUT',
    body: JSON.stringify(log),
  })
}

export async function deleteWorkLog(
  baseUrl: string,
  token: string,
  deviceId: string,
  id: number,
): Promise<void> {
  return request<void>(baseUrl, token, deviceId, `/logs/${id}`, {
    method: 'DELETE',
  })
}

export async function listFixedWork(
  baseUrl: string,
  token: string,
  deviceId: string,
  type: 'DAILY' | 'WEEKLY' | 'MONTHLY',
): Promise<FixedWorkItem[]> {
  return request<FixedWorkItem[]>(baseUrl, token, deviceId, `/fixed-work?type=${type}`)
}

export async function createFixedWork(
  baseUrl: string,
  token: string,
  deviceId: string,
  item: Partial<FixedWorkItem>,
): Promise<FixedWorkItem> {
  return request<FixedWorkItem>(baseUrl, token, deviceId, '/fixed-work', {
    method: 'POST',
    body: JSON.stringify(item),
  })
}

export async function updateFixedWork(
  baseUrl: string,
  token: string,
  deviceId: string,
  id: number,
  item: Partial<FixedWorkItem>,
): Promise<FixedWorkItem> {
  return request<FixedWorkItem>(baseUrl, token, deviceId, `/fixed-work/${id}`, {
    method: 'PUT',
    body: JSON.stringify(item),
  })
}

export async function deleteFixedWork(
  baseUrl: string,
  token: string,
  deviceId: string,
  id: number,
): Promise<void> {
  return request<void>(baseUrl, token, deviceId, `/fixed-work/${id}`, {
    method: 'DELETE',
  })
}

export async function toggleFixedWorkComplete(
  baseUrl: string,
  token: string,
  deviceId: string,
  id: number,
): Promise<FixedWorkItem> {
  return request<FixedWorkItem>(baseUrl, token, deviceId, `/fixed-work/${id}/toggle-complete`, {
    method: 'POST',
  })
}

export async function listFuturePlans(
  baseUrl: string,
  token: string,
  deviceId: string,
): Promise<FuturePlan[]> {
  return request<FuturePlan[]>(baseUrl, token, deviceId, '/future-plans')
}

export async function createFuturePlan(
  baseUrl: string,
  token: string,
  deviceId: string,
  plan: Partial<FuturePlan>,
): Promise<FuturePlan> {
  return request<FuturePlan>(baseUrl, token, deviceId, '/future-plans', {
    method: 'POST',
    body: JSON.stringify(plan),
  })
}

export async function updateFuturePlan(
  baseUrl: string,
  token: string,
  deviceId: string,
  id: number,
  plan: Partial<FuturePlan>,
): Promise<FuturePlan> {
  return request<FuturePlan>(baseUrl, token, deviceId, `/future-plans/${id}`, {
    method: 'PUT',
    body: JSON.stringify(plan),
  })
}

export async function completeFuturePlan(
  baseUrl: string,
  token: string,
  deviceId: string,
  id: number,
): Promise<FuturePlan> {
  return request<FuturePlan>(baseUrl, token, deviceId, `/future-plans/${id}/complete`, {
    method: 'POST',
  })
}

export async function cancelFuturePlan(
  baseUrl: string,
  token: string,
  deviceId: string,
  id: number,
): Promise<FuturePlan> {
  return request<FuturePlan>(baseUrl, token, deviceId, `/future-plans/${id}/cancel`, {
    method: 'POST',
  })
}

export async function deleteFuturePlan(
  baseUrl: string,
  token: string,
  deviceId: string,
  id: number,
): Promise<void> {
  return request<void>(baseUrl, token, deviceId, `/future-plans/${id}`, {
    method: 'DELETE',
  })
}

export async function listReportTemplates(
  baseUrl: string,
  token: string,
  deviceId: string,
): Promise<ReportTemplate[]> {
  return request<ReportTemplate[]>(baseUrl, token, deviceId, '/templates')
}

export async function listReportConfigs(
  baseUrl: string,
  token: string,
  deviceId: string,
): Promise<ReportConfig[]> {
  return request<ReportConfig[]>(baseUrl, token, deviceId, '/configs')
}

export async function saveReportConfig(
  baseUrl: string,
  token: string,
  deviceId: string,
  config: Partial<ReportConfig>,
): Promise<ReportConfig> {
  return request<ReportConfig>(baseUrl, token, deviceId, '/configs', {
    method: 'POST',
    body: JSON.stringify(config),
  })
}

export async function deleteReportConfig(
  baseUrl: string,
  token: string,
  deviceId: string,
  id: number,
): Promise<void> {
  return request<void>(baseUrl, token, deviceId, `/configs/${id}`, {
    method: 'DELETE',
  })
}

export async function generateReport(
  baseUrl: string,
  token: string,
  deviceId: string,
  configId: number,
): Promise<WorkReport> {
  return request<WorkReport>(baseUrl, token, deviceId, '/reports/generate', {
    method: 'POST',
    body: JSON.stringify({ configId }),
  })
}

export async function listReports(
  baseUrl: string,
  token: string,
  deviceId: string,
  page = 1,
  size = 20,
): Promise<PageResult<WorkReport>> {
  return request<PageResult<WorkReport>>(
    baseUrl,
    token,
    deviceId,
    `/reports?page=${page}&size=${size}`,
  )
}

export async function pushReport(
  baseUrl: string,
  token: string,
  deviceId: string,
  reportId: number,
): Promise<void> {
  return request<void>(baseUrl, token, deviceId, `/reports/${reportId}/push`, {
    method: 'POST',
  })
}

export async function deleteReport(
  baseUrl: string,
  token: string,
  deviceId: string,
  id: number,
): Promise<void> {
  return request<void>(baseUrl, token, deviceId, `/reports/${id}`, {
    method: 'DELETE',
  })
}
